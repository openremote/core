/*
 * Copyright 2018, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.openremote.manager.rules;

import static org.openremote.manager.rules.AssetQueryPredicate.groupIsEmpty;
import static org.openremote.model.query.filter.LocationAttributePredicate.getLocationPredicates;
import static org.openremote.model.util.ValueUtil.distinctByKey;

import jakarta.ws.rs.core.MediaType;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.alarm.Alarm;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeInfo;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.MetaMap;
import org.openremote.model.geo.GeoJSONPoint;
import org.openremote.model.notification.*;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.LogicGroup;
import org.openremote.model.query.UserQuery;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.rules.*;
import org.openremote.model.rules.json.*;
import org.openremote.model.util.*;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.NameValueHolder;
import org.openremote.model.webhook.Webhook;
import org.quartz.CronExpression;
import org.shredzone.commons.suncalc.SunTimes;

public class JsonRulesBuilder extends RulesBuilder {

  static class RuleActionExecution {
    Runnable runnable;
    long delay;

    public RuleActionExecution(Runnable runnable, long delay) {
      this.runnable = runnable;
      this.delay = delay;
    }
  }

  /**
   * Stores all state for a given {@link RuleCondition} and calculates which {@link AttributeInfo}s
   * match and don't match the condition.
   */
  class RuleConditionState {

    RuleCondition ruleCondition;
    final TimerService timerService;
    boolean trackUnmatched;
    AssetQuery.OrderBy orderBy;
    int limit;
    LogicGroup<AttributePredicate> attributePredicates = null;
    Function<Collection<AttributeInfo>, Set<AttributeInfo>> assetPredicate = null;
    Map<Pair<AttributeInfo, Integer>, Long> durationMatchTimes = new HashMap<>();
    Set<AttributeInfo> unfilteredAssetStates = new HashSet<>();
    Set<AttributeInfo> previouslyMatchedAssetStates = new HashSet<>();
    Set<AttributeInfo> previouslyUnmatchedAssetStates;
    Predicate<Long> timePredicate;
    RuleConditionEvaluationResult lastEvaluationResult;

    @SuppressWarnings("ConstantConditions")
    public RuleConditionState(
        RuleCondition ruleCondition, boolean trackUnmatched, TimerService timerService)
        throws Exception {
      this.timerService = timerService;
      this.ruleCondition = ruleCondition;
      this.trackUnmatched = trackUnmatched;

      if (trackUnmatched) {
        previouslyUnmatchedAssetStates = new HashSet<>();
      }

      if (!TextUtil.isNullOrEmpty(ruleCondition.cron)) {
        try {
          CronExpression timerExpression = new CronExpression(ruleCondition.cron);
          timerExpression.setTimeZone(TimeZone.getTimeZone("UTC"));
          AtomicLong nextExecuteMillis =
              new AtomicLong(
                  timerExpression
                      .getNextValidTimeAfter(new Date(timerService.getCurrentTimeMillis()))
                      .getTime());

          timePredicate =
              (time) -> {
                long nextExecute = nextExecuteMillis.get();
                if (time >= nextExecute) {
                  nextExecuteMillis.set(
                      timerExpression
                          .getNextValidTimeAfter(
                              timerExpression.getNextInvalidTimeAfter(new Date(nextExecute)))
                          .getTime());
                  return true;
                }
                return false;
              };
        } catch (Exception e) {
          log(
              Level.SEVERE,
              "Failed to parse rule condition cron expression: " + ruleCondition.cron,
              e);
          throw e;
        }
      } else if (ruleCondition.sun != null) {
        SunTimes.Parameters sunCalculator =
            getSunCalculator(jsonRuleset, ruleCondition.sun, timerService);
        final long offsetMillis =
            ruleCondition.sun.getOffsetMins() != null
                ? ruleCondition.sun.getOffsetMins() * 60000
                : 0;
        final boolean useRiseTime =
            ruleCondition.sun.getPosition() == SunPositionTrigger.Position.SUNRISE
                || ruleCondition
                    .sun
                    .getPosition()
                    .toString()
                    .startsWith(SunPositionTrigger.MORNING_TWILIGHT_PREFIX);

        // Calculate the next occurrence
        AtomicReference<SunTimes> sunTimes = new AtomicReference<>(sunCalculator.execute());

        Function<Long, Long> nextExecuteMillisCalculator =
            (time) -> {
              ZonedDateTime occurrence =
                  useRiseTime ? sunTimes.get().getRise() : sunTimes.get().getSet();

              if (occurrence == null) {
                log(
                    Level.WARNING,
                    "Rule condition requested sun position never occurs at the specified location: "
                        + ruleCondition.sun);
                return Long.MAX_VALUE;
              }

              long nextMillis = occurrence.toInstant().toEpochMilli() + offsetMillis;

              // If occurrence is before requested time then advance the sun calculator to either
              // reset occurrence or 5 mins before requested time (whichever is later)
              if (nextMillis < time) {
                // Move to the next day
                ZonedDateTime resetOccurrence =
                    sunTimes.get().getSet().isBefore(sunTimes.get().getRise())
                        ? sunTimes.get().getSet()
                        : sunTimes.get().getRise();
                resetOccurrence = resetOccurrence.truncatedTo(ChronoUnit.DAYS).plusDays(1);
                sunTimes.set(
                    sunCalculator
                        .on(
                            new Date(
                                Math.max(
                                    resetOccurrence.toInstant().toEpochMilli(), time - 300000)))
                        .execute());
              }

              return nextMillis;
            };

        timePredicate =
            (time) -> {
              long nextExecute = nextExecuteMillisCalculator.apply(time);

              // Next execute must be within a minute of requested time
              if (time >= nextExecute && time - nextExecute < 60000) {
                log(
                    Level.INFO,
                    "Rule condition sun position has triggered at: "
                        + timerService.getCurrentTimeMillis());
                return true;
              }
              return false;
            };
      } else if (ruleCondition.assets != null) {
        // Pull out order, limit and attribute predicates so they can be applied at required times
        orderBy = ruleCondition.assets.orderBy;
        limit = ruleCondition.assets.limit;
        attributePredicates = ruleCondition.assets.attributes;
        boolean attributePredicateHasDurationCondition =
            ruleCondition.duration != null && !ruleCondition.duration.isEmpty();

        if (attributePredicates != null && attributePredicates.items != null) {
          // Only supports a single level or logic group for attributes (i.e. cannot nest groups in
          // the UI so
          // don't support it here either)
          attributePredicates.groups = null;
          if (attributePredicateHasDurationCondition) {
            assetPredicate =
                asAttributeMatcher(
                    timerService::getCurrentTimeMillis,
                    attributePredicates.getItems(),
                    ruleCondition.duration,
                    durationMatchTimes);
          } else {
            assetPredicate =
                AssetQueryPredicate.asAttributeMatcher(
                    timerService::getCurrentTimeMillis, attributePredicates);
          }
        }
        ruleCondition.assets.orderBy = null;
        ruleCondition.assets.limit = 0;
        ruleCondition.assets.attributes = null;
      } else {
        throw new IllegalStateException(
            "Invalid rule condition either timer or asset query must be set");
      }
    }

    void updateUnfilteredAssetStates(RulesFacts facts, RulesEngine.AssetStateChangeEvent event) {

      // Only interested in this when condition is of type asset query
      if (ruleCondition.assets != null) {
        // Clear last trigger to ensure update runs again
        lastEvaluationResult = null;

        if (event == null || event.cause == PersistenceEvent.Cause.CREATE) {
          // Do a complete refresh of unfiltered asset states based on the asset query (without
          // attribute predicates)
          unfilteredAssetStates =
              facts.matchAssetState(ruleCondition.assets).collect(Collectors.toSet());
        } else {
          // Replace or remove asset state as required
          switch (event.cause) {
            case UPDATE -> {
              // Only insert if fact was already in there (i.e. it matches the asset type
              // constraints)
              if (unfilteredAssetStates.remove(event.assetState)) {
                unfilteredAssetStates.add(event.assetState);
              }
            }
            case DELETE -> {
              unfilteredAssetStates.remove(event.assetState);
              if (durationMatchTimes != null) {
                durationMatchTimes
                    .keySet()
                    .removeIf(
                        attributeRefPredicateIndex ->
                            attributeRefPredicateIndex
                                .getKey()
                                .getRef()
                                .equals(event.assetState.getRef()));
              }
            }
          }
        }

        // Replace any potentially stale asset states (values may have changed)
        // only need up to date values in the previously matched asset states previously unmatched
        // asset states is only
        // used to compare asset ID and attribute name.
        if (event != null) {
          boolean resetImmediately =
              event.assetState.getMeta().getValue(MetaItemType.RULE_RESET_IMMEDIATE).orElse(false);
          if (!resetImmediately && previouslyMatchedAssetStates.remove(event.assetState)) {
            previouslyMatchedAssetStates.add(event.assetState);
          }
        }

        // During startup notify RulesFacts about any location predicates
        if (facts.trackLocationRules) {
          facts.storeLocationPredicates(getLocationPredicates(attributePredicates));
        }
      }
    }

    void update(Map<String, Long> nextRecurAssetIdMap) {

      // Last trigger is cleared by rule RHS execution if a match is already found then skip the
      // update
      if (lastEvaluationResult != null && lastEvaluationResult.matches) {
        return;
      }

      // Apply time condition if it exists
      if (timePredicate != null) {
        lastEvaluationResult = null;

        if (timePredicate.test(timerService.getCurrentTimeMillis())) {
          lastEvaluationResult =
              new RuleConditionEvaluationResult(
                  true,
                  Collections.emptyList(),
                  Collections.emptyList(),
                  Collections.emptyList(),
                  Collections.emptyList());
        }

        return;
      }

      if (unfilteredAssetStates.isEmpty()) {
        // Maybe assets have been deleted so remove any previous match data
        previouslyMatchedAssetStates.clear();
        if (trackUnmatched) {
          previouslyUnmatchedAssetStates.clear();
        }
        log(Level.FINEST, "Rule trigger has no unfiltered asset states so no match");
        lastEvaluationResult =
            new RuleConditionEvaluationResult(
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        return;
      }

      List<AttributeInfo> matchedAssetStates;
      List<AttributeInfo> unmatchedAssetStates = Collections.emptyList();
      Collection<String> unmatchedAssetIds = Collections.emptyList();

      if (attributePredicates == null) {
        matchedAssetStates = new ArrayList<>(unfilteredAssetStates);
      } else {

        Map<Boolean, List<AttributeInfo>> results = new HashMap<>();
        List<AttributeInfo> matched = new ArrayList<>();
        List<AttributeInfo> unmatched = new ArrayList<>();
        results.put(true, matched);
        results.put(false, unmatched);

        unfilteredAssetStates.stream()
            .collect(Collectors.groupingBy(AttributeInfo::getId))
            .forEach(
                (id, states) -> {
                  Set<AttributeInfo> matches = assetPredicate.apply(states);
                  if (matches != null) {
                    matched.addAll(matches);
                    unmatched.addAll(
                        states.stream()
                            .filter(state -> !matches.contains(state))
                            .collect(Collectors.toSet()));
                  } else {
                    unmatched.addAll(states);
                  }
                });

        matchedAssetStates = results.getOrDefault(true, Collections.emptyList());
        unmatchedAssetStates = results.getOrDefault(false, Collections.emptyList());

        if (trackUnmatched) {

          // Clear out previous unmatched that now match
          previouslyUnmatchedAssetStates.removeIf(matchedAssetStates::contains);

          // Filter out previous un-matches to avoid re-triggering
          unmatchedAssetStates.removeIf(previouslyUnmatchedAssetStates::contains);
        }
      }

      // Remove previous matches where the asset state no longer matches
      previouslyMatchedAssetStates.removeIf(
          previousAssetState -> {
            Optional<AttributeInfo> matched =
                matchedAssetStates.stream()
                    .filter(
                        matchedAssetState -> Objects.equals(previousAssetState, matchedAssetState))
                    .findFirst();

            boolean noLongerMatches = matched.isEmpty();

            if (!noLongerMatches) {
              noLongerMatches =
                  matched
                      .map(
                          matchedAssetState -> {
                            // If reset immediate meta item is set then remove previous state if
                            // timestamp is greater
                            boolean resetImmediately =
                                matchedAssetState
                                    .getMeta()
                                    .getValue(MetaItemType.RULE_RESET_IMMEDIATE)
                                    .orElse(false);
                            return resetImmediately
                                && matchedAssetState.getTimestamp()
                                    > previousAssetState.getTimestamp();
                          })
                      .orElse(false);
            }

            if (noLongerMatches) {
              log(
                  Level.FINEST,
                  "Rule trigger previously matched asset state no longer matches so resetting: "
                      + previousAssetState);
            }

            return noLongerMatches;
          });

      // Remove matches that have an active recurrence timer
      matchedAssetStates.removeIf(
          matchedAssetState ->
              nextRecurAssetIdMap.containsKey(matchedAssetState.getId())
                  && nextRecurAssetIdMap.get(matchedAssetState.getId())
                      > timerService.getCurrentTimeMillis());

      // Filter out previous matches to avoid re-triggering
      matchedAssetStates.removeIf(previouslyMatchedAssetStates::contains);

      // Select unique asset states based on asset id
      Stream<AttributeInfo> matchedAssetStateStream =
          matchedAssetStates.stream().filter(distinctByKey(AttributeInfo::getId));

      // Order asset states before applying limit
      if (orderBy != null) {
        matchedAssetStateStream = matchedAssetStateStream.sorted(RulesFacts.asComparator(orderBy));
      }
      if (limit > 0) {
        matchedAssetStateStream = matchedAssetStateStream.limit(limit);
      }

      Collection<String> matchedAssetIds =
          matchedAssetStateStream.map(AttributeInfo::getId).collect(Collectors.toList());

      if (trackUnmatched) {
        // Select unique asset states based on asset id
        Stream<AttributeInfo> unmatchedAssetStateStream =
            unmatchedAssetStates.stream().filter(distinctByKey(AttributeInfo::getId));

        // Filter out unmatched asset ids that are in the matched list
        unmatchedAssetIds =
            unmatchedAssetStateStream
                .map(AttributeInfo::getId)
                .filter(id -> !matchedAssetIds.contains(id))
                .collect(Collectors.toList());
      }

      lastEvaluationResult =
          new RuleConditionEvaluationResult(
              (!matchedAssetIds.isEmpty() || (trackUnmatched && !unmatchedAssetIds.isEmpty())),
              matchedAssetStates,
              matchedAssetIds,
              unmatchedAssetStates,
              unmatchedAssetIds);
      log(Level.FINEST, "Rule evaluation result: " + lastEvaluationResult);
    }

    Collection<String> getMatchedAssetIds() {

      if (lastEvaluationResult == null) {
        return Collections.emptyList();
      }
      return lastEvaluationResult.matchedAssetIds;
    }

    Collection<String> getUnmatchedAssetIds() {
      if (lastEvaluationResult == null) {
        return Collections.emptyList();
      }

      return lastEvaluationResult.unmatchedAssetIds;
    }
  }

  /** This contains the results of a rule condition trigger evaluation. */
  static class RuleConditionEvaluationResult {
    boolean matches;
    Collection<AttributeInfo> matchedAssetStates;
    Collection<AttributeInfo> unmatchedAssetStates;
    Collection<String> matchedAssetIds;
    Collection<String> unmatchedAssetIds;

    public RuleConditionEvaluationResult(
        boolean matches,
        Collection<AttributeInfo> matchedAssetStates,
        Collection<String> matchedAssetIds,
        Collection<AttributeInfo> unmatchedAssetStates,
        Collection<String> unmatchedAssetIds) {
      this.matches = matches;
      this.matchedAssetStates = matchedAssetStates;
      this.matchedAssetIds = matchedAssetIds;
      this.unmatchedAssetStates = unmatchedAssetStates;
      this.unmatchedAssetIds = unmatchedAssetIds;
    }

    @Override
    public String toString() {
      return RuleConditionEvaluationResult.class.getSimpleName()
          + "{"
          + "matches="
          + matches
          + ", matchedAssetStates="
          + matchedAssetStates.size()
          + ", unmatchedAssetStates="
          + unmatchedAssetStates.size()
          + ", matchedAssetIds="
          + matchedAssetIds.size()
          + ", unmatchedAssetIds="
          + unmatchedAssetIds.size()
          + '}';
    }
  }

  /** Stores the state of the overall rule and each {@link RuleCondition}. */
  class RuleState {

    protected JsonRule rule;
    protected Map<String, RuleConditionState> conditionStateMap = new HashMap<>();
    protected Set<String> thenMatchedAssetIds;
    protected Set<String> otherwiseMatchedAssetIds;
    protected long nextRecur;
    protected boolean matched;
    protected Map<String, Long> nextRecurAssetIdMap = new HashMap<>();

    public RuleState(JsonRule rule) {
      this.rule = rule;
    }

    public void update(Supplier<Long> currentMillisSupplier) {

      matched = false;

      // Check if next recurrence in the future
      if (nextRecur > currentMillisSupplier.get()) {
        return;
      }

      // Clear out expired recurrence timers
      nextRecurAssetIdMap
          .entrySet()
          .removeIf(entry -> entry.getValue() <= currentMillisSupplier.get());

      // Update each condition state
      log(Level.FINEST, "Updating rule condition states for rule: " + rule.name);
      conditionStateMap
          .values()
          .forEach(ruleConditionState -> ruleConditionState.update(nextRecurAssetIdMap));

      thenMatchedAssetIds = new HashSet<>();
      otherwiseMatchedAssetIds = rule.otherwise != null ? new HashSet<>() : null;

      matched = updateMatches(rule.when, thenMatchedAssetIds, otherwiseMatchedAssetIds);

      if (!matched) {
        thenMatchedAssetIds.clear();
        if (otherwiseMatchedAssetIds != null) {
          otherwiseMatchedAssetIds.clear();
        }
      }
    }

    public boolean thenMatched() {
      return (thenMatchedAssetIds != null && !thenMatchedAssetIds.isEmpty()) || !otherwiseMatched();
    }

    public boolean otherwiseMatched() {
      return otherwiseMatchedAssetIds != null && !otherwiseMatchedAssetIds.isEmpty();
    }

    protected boolean updateMatches(
        LogicGroup<RuleCondition> ruleConditionGroup,
        Set<String> thenMatchedAssetIds,
        Set<String> otherwiseMatchedAssetIds) {

      if (groupIsEmpty(ruleConditionGroup)) {
        return false;
      }

      LogicGroup.Operator operator =
          ruleConditionGroup.operator == null
              ? LogicGroup.Operator.AND
              : ruleConditionGroup.operator;
      boolean groupMatches = false;

      if (!ruleConditionGroup.getItems().isEmpty()) {

        if (operator == LogicGroup.Operator.AND) {
          groupMatches =
              ruleConditionGroup.getItems().stream()
                  .map(ruleCondition -> conditionStateMap.get(ruleCondition.tag))
                  .allMatch(
                      ruleConditionState ->
                          ruleConditionState.lastEvaluationResult != null
                              && ruleConditionState.lastEvaluationResult.matches);

          // Prevent stale matches from blocking rule re-triggering for multi LHS condition groups
          if (!groupMatches && ruleConditionGroup.getItems().size() > 1) {

            boolean anyConditionHasNoPreviousMatches =
                ruleConditionGroup.getItems().stream()
                    .map(ruleCondition -> conditionStateMap.get(ruleCondition.tag))
                    .anyMatch(
                        ruleConditionState ->
                            ruleConditionState.previouslyMatchedAssetStates.isEmpty());

            // Clear previously matched asset states, so they can be re-matched and trigger the rule
            // when the group matches again
            if (anyConditionHasNoPreviousMatches) {
              ruleConditionGroup
                  .getItems()
                  .forEach(
                      ruleCondition -> {
                        RuleConditionState conditionState =
                            conditionStateMap.get(ruleCondition.tag);
                        if (conditionState != null
                            && !conditionState.previouslyMatchedAssetStates.isEmpty()) {
                          conditionState.previouslyMatchedAssetStates.clear();
                        }
                      });
            }
          }
        } else {
          groupMatches =
              ruleConditionGroup.getItems().stream()
                  .map(ruleCondition -> conditionStateMap.get(ruleCondition.tag))
                  .anyMatch(
                      ruleConditionState ->
                          ruleConditionState.lastEvaluationResult != null
                              && ruleConditionState.lastEvaluationResult.matches);
        }

        thenMatchedAssetIds.addAll(
            ruleConditionGroup.getItems().stream()
                .map(ruleCondition -> conditionStateMap.get(ruleCondition.tag))
                .filter(
                    ruleConditionState ->
                        ruleConditionState.lastEvaluationResult != null
                            && ruleConditionState.lastEvaluationResult.matches)
                .map(RuleConditionState::getMatchedAssetIds) // Get all matched assetIds
                .flatMap(Collection::stream)
                .collect(Collectors.toSet()));

        if (otherwiseMatchedAssetIds != null) {
          otherwiseMatchedAssetIds.addAll(
              ruleConditionGroup.getItems().stream()
                  .map(ruleCondition -> conditionStateMap.get(ruleCondition.tag))
                  .filter(
                      ruleConditionState ->
                          ruleConditionState.trackUnmatched
                              && ruleConditionState.lastEvaluationResult != null
                              && ruleConditionState.lastEvaluationResult.matches)
                  .map(RuleConditionState::getUnmatchedAssetIds) // Get all unmatched assetIds
                  .flatMap(Collection::stream)
                  .collect(Collectors.toSet()));
        }
      }

      if (ruleConditionGroup.groups != null) {

        if (operator == LogicGroup.Operator.AND) {
          if (!ruleConditionGroup.items.isEmpty() && !groupMatches) {
            return false;
          }

          groupMatches =
              ruleConditionGroup.groups.stream()
                  .allMatch(
                      group -> updateMatches(group, thenMatchedAssetIds, otherwiseMatchedAssetIds));

        } else {

          // updateMatches has side effects which we need (inserts into then and otherwise)
          //noinspection ReplaceInefficientStreamCount
          groupMatches =
              ruleConditionGroup.groups.stream()
                      .filter(
                          group ->
                              updateMatches(group, thenMatchedAssetIds, otherwiseMatchedAssetIds))
                      .count()
                  > 0;
        }
      }

      return groupMatches;
    }
  }

  public static final String PLACEHOLDER_RULESET_ID = "%RULESET_ID%";
  public static final String PLACEHOLDER_RULESET_NAME = "%RULESET_NAME%";
  public static final String PLACEHOLDER_TRIGGER_ASSETS = "%TRIGGER_ASSETS%";
  public static final String PLACEHOLDER_ASSET_ID = "%ASSET_ID%";
  static final String TIMER_TEMPORAL_FACT_NAME_PREFIX = "TimerTemporalFact-";
  static final String LOG_PREFIX = "JSON Rule '";
  protected final AssetStorageService assetStorageService;
  protected final RulesEngine<?> rulesEngine;
  protected final TimerService timerService;
  protected final Assets assetsFacade;
  protected final Users usersFacade;
  protected final Notifications notificationsFacade;
  protected final Webhooks webhooksFacade;
  protected final Alarms alarmsFacade;
  protected final HistoricDatapoints historicDatapointsFacade;
  protected final PredictedDatapoints predictedDatapointsFacade;
  protected final BiConsumer<Runnable, Long> scheduledActionConsumer;
  protected final Map<String, RuleState> ruleStateMap = new ConcurrentHashMap<>();
  protected final JsonRule[] jsonRules;
  protected final Ruleset jsonRuleset;
  protected static Logger LOG;

  public JsonRulesBuilder(
      Logger logger,
      Ruleset ruleset,
      RulesEngine<?> rulesEngine,
      TimerService timerService,
      AssetStorageService assetStorageService,
      Assets assetsFacade,
      Users usersFacade,
      Notifications notificationsFacade,
      Webhooks webhooksFacade,
      Alarms alarmsFacade,
      HistoricDatapoints historicDatapoints,
      PredictedDatapoints predictedDatapoints,
      BiConsumer<Runnable, Long> scheduledActionConsumer)
      throws Exception {
    this.rulesEngine = rulesEngine;
    this.timerService = timerService;
    this.assetStorageService = assetStorageService;
    this.assetsFacade = assetsFacade;
    this.usersFacade = usersFacade;
    this.notificationsFacade = notificationsFacade;
    this.webhooksFacade = webhooksFacade;
    this.alarmsFacade = alarmsFacade;
    this.historicDatapointsFacade = historicDatapoints;
    this.predictedDatapointsFacade = predictedDatapoints;
    this.scheduledActionConsumer = scheduledActionConsumer;
    LOG = logger;

    jsonRuleset = ruleset;
    String rulesStr = ruleset.getRules();
    rulesStr = rulesStr.replace(PLACEHOLDER_RULESET_ID, Long.toString(ruleset.getId()));
    rulesStr = rulesStr.replace(PLACEHOLDER_RULESET_NAME, ruleset.getName());

    JsonRulesetDefinition jsonRulesetDefinition =
        ValueUtil.parse(rulesStr, JsonRulesetDefinition.class).orElse(null);

    if (jsonRulesetDefinition == null
        || jsonRulesetDefinition.rules == null
        || jsonRulesetDefinition.rules.length == 0) {
      throw new IllegalArgumentException("No rules within ruleset so nothing to start: " + ruleset);
    }

    jsonRules = jsonRulesetDefinition.rules;

    for (JsonRule jsonRule : jsonRules) {
      add(jsonRule);
    }
  }

  public void stop(RulesFacts facts) {
    Arrays.stream(jsonRules)
        .forEach(
            jsonRule ->
                executeRuleActions(
                    jsonRule,
                    jsonRule.onStop,
                    "onStop",
                    false,
                    facts,
                    null,
                    assetsFacade,
                    usersFacade,
                    notificationsFacade,
                    webhooksFacade,
                    alarmsFacade,
                    predictedDatapointsFacade,
                    this.scheduledActionConsumer));

    // Remove temporal fact for timer rule evaluation
    String tempFactName = TIMER_TEMPORAL_FACT_NAME_PREFIX + jsonRuleset.getId();
    facts.remove(tempFactName);
  }

  public void start(RulesFacts facts) {

    Arrays.stream(jsonRules)
        .forEach(
            jsonRule -> {
              executeRuleActions(
                  jsonRule,
                  jsonRule.onStart,
                  "onStart",
                  false,
                  facts,
                  null,
                  assetsFacade,
                  usersFacade,
                  notificationsFacade,
                  webhooksFacade,
                  alarmsFacade,
                  predictedDatapointsFacade,
                  this.scheduledActionConsumer);
            });

    // Initialize asset states
    onAssetStatesChanged(facts, null);
  }

  public void onAssetStatesChanged(RulesFacts facts, RulesEngine.AssetStateChangeEvent event) {
    ruleStateMap
        .values()
        .forEach(
            triggerStateMap ->
                triggerStateMap
                    .conditionStateMap
                    .values()
                    .forEach(
                        ruleConditionState ->
                            ruleConditionState.updateUnfilteredAssetStates(facts, event)));
  }

  protected JsonRulesBuilder add(JsonRule rule) throws Exception {

    if (ruleStateMap.containsKey(rule.name)) {
      throw new IllegalArgumentException(
          "Rules must have a unique name within a ruleset, rule name '"
              + rule.name
              + "' already used");
    }

    RuleState ruleState = new RuleState(rule);
    ruleStateMap.put(rule.name, ruleState);
    addRuleConditionStates(
        rule.when, rule.otherwise != null, new AtomicInteger(0), ruleState.conditionStateMap);

    Condition condition = buildLhsCondition(rule, ruleState);
    Action action = buildRhsAction(rule, ruleState);

    if (condition == null || action == null) {
      throw new IllegalArgumentException(
          "Error building JSON rule when or then is not defined: " + rule.name);
    }

    add()
        .name(rule.name)
        .description(rule.description)
        .priority(rule.priority)
        .when(condition)
        .then(action);

    return this;
  }

  protected void addRuleConditionStates(
      LogicGroup<RuleCondition> ruleConditionGroup,
      boolean trackUnmatched,
      AtomicInteger index,
      Map<String, RuleConditionState> triggerStateMap)
      throws Exception {
    if (ruleConditionGroup != null) {
      if (!ruleConditionGroup.getItems().isEmpty()) {
        for (RuleCondition ruleCondition : ruleConditionGroup.getItems()) {
          if (TextUtil.isNullOrEmpty(ruleCondition.tag)) {
            ruleCondition.tag = index.toString();
          }

          triggerStateMap.put(
              ruleCondition.tag,
              new RuleConditionState(ruleCondition, trackUnmatched, timerService));
          index.incrementAndGet();
        }
      }
      if (ruleConditionGroup.groups != null && !ruleConditionGroup.groups.isEmpty()) {
        for (LogicGroup<RuleCondition> childRuleTriggerCondition : ruleConditionGroup.groups) {
          addRuleConditionStates(childRuleTriggerCondition, trackUnmatched, index, triggerStateMap);
        }
      }
    }
  }

  protected Condition buildLhsCondition(JsonRule rule, RuleState ruleState) {
    if (rule.when == null) {
      return null;
    }

    return facts -> {
      ruleState.update(timerService::getCurrentTimeMillis);
      return ruleState.matched;
    };
  }

  protected Action buildRhsAction(JsonRule rule, RuleState ruleState) {

    if (rule.then == null) {
      return null;
    }

    return facts -> {
      try {
        // Execute actions only when the rules engine has already fired to prevent execution during
        // startup
        if (rulesEngine.hasPreviouslyFired()) {
          if (ruleState.thenMatched()) {
            log(Level.FINE, "Triggered rule so executing 'then' actions for rule: " + rule.name);
            executeRuleActions(
                rule,
                rule.then,
                "then",
                false,
                facts,
                ruleState,
                assetsFacade,
                usersFacade,
                notificationsFacade,
                webhooksFacade,
                alarmsFacade,
                predictedDatapointsFacade,
                scheduledActionConsumer);
          }

          if (rule.otherwise != null && ruleState.otherwiseMatched()) {
            log(
                Level.FINE,
                "Triggered rule so executing 'otherwise' actions for rule: " + rule.name);
            executeRuleActions(
                rule,
                rule.otherwise,
                "otherwise",
                true,
                facts,
                ruleState,
                assetsFacade,
                usersFacade,
                notificationsFacade,
                webhooksFacade,
                alarmsFacade,
                predictedDatapointsFacade,
                scheduledActionConsumer);
          }
        }
      } catch (Exception e) {
        log(Level.SEVERE, "Exception thrown during rule RHS execution", e);
        throw e;
      } finally {
        // Store recurrence times as required
        boolean recurPerAsset =
            rule.recurrence == null || rule.recurrence.scope != RuleRecurrence.Scope.GLOBAL;
        long currentTime = timerService.getCurrentTimeMillis();
        long nextRecur =
            rule.recurrence == null || rule.recurrence.mins == null
                ? Long.MAX_VALUE
                : currentTime + (rule.recurrence.mins * 60000);

        if (nextRecur > currentTime) {
          if (recurPerAsset) {
            ruleState.thenMatchedAssetIds.forEach(
                assetId -> ruleState.nextRecurAssetIdMap.put(assetId, nextRecur));
          } else {
            ruleState.nextRecur = nextRecur;
          }
        }

        ruleState
            .conditionStateMap
            .values()
            .forEach(
                ruleConditionState -> {
                  // Store last evaluation results in state
                  if (ruleConditionState.lastEvaluationResult != null) {

                    ruleConditionState.previouslyMatchedAssetStates.addAll(
                        ruleConditionState.lastEvaluationResult.matchedAssetStates);

                    if (ruleConditionState.trackUnmatched) {
                      ruleConditionState.previouslyUnmatchedAssetStates.addAll(
                          ruleConditionState.lastEvaluationResult.unmatchedAssetStates);
                    }
                  }

                  // Clear last results
                  ruleConditionState.lastEvaluationResult = null;
                });
      }
    };
  }

  public void executeRuleActions(
      JsonRule rule,
      RuleAction[] ruleActions,
      String actionsName,
      boolean useUnmatched,
      RulesFacts facts,
      RuleState ruleState,
      Assets assetsFacade,
      Users usersFacade,
      Notifications notificationsFacade,
      Webhooks webhooksFacade,
      Alarms alarmsFacade,
      PredictedDatapoints predictedDatapointsFacade,
      BiConsumer<Runnable, Long> scheduledActionConsumer) {

    if (ruleActions != null && ruleActions.length > 0) {

      long delay = 0L;

      for (int i = 0; i < ruleActions.length; i++) {

        RuleAction ruleAction = ruleActions[i];
        JsonRulesBuilder.RuleActionExecution actionExecution =
            buildRuleActionExecution(
                rule,
                ruleAction,
                actionsName,
                i,
                useUnmatched,
                facts,
                ruleState,
                assetsFacade,
                usersFacade,
                notificationsFacade,
                webhooksFacade,
                alarmsFacade,
                predictedDatapointsFacade);

        if (actionExecution != null) {
          delay += actionExecution.delay;

          if (delay > 0L) {
            log(
                Level.FINE,
                "Delaying rule action for "
                    + delay
                    + "ms for rule action: "
                    + rule.name
                    + " '"
                    + actionsName
                    + "' action index "
                    + i);
            scheduledActionConsumer.accept(actionExecution.runnable, delay);
          } else {
            actionExecution.runnable.run();
          }
        }
      }
    }
  }

  protected static Collection<String> getUserIds(Users users, UserQuery userQuery) {
    return users.getResults(userQuery).collect(Collectors.toList());
  }

  protected static Collection<String> getAssetIds(Assets assets, AssetQuery assetQuery) {
    return assets.getResults(assetQuery).map(Asset::getId).collect(Collectors.toList());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  protected RuleActionExecution buildRuleActionExecution(
      JsonRule rule,
      RuleAction ruleAction,
      String actionsName,
      int index,
      boolean useUnmatched,
      RulesFacts facts,
      RuleState ruleState,
      Assets assetsFacade,
      Users usersFacade,
      Notifications notificationsFacade,
      Webhooks webhooksFacade,
      Alarms alarmsFacade,
      PredictedDatapoints predictedDatapointsFacade) {

    if (ruleAction instanceof RuleActionNotification notificationAction) {

      if (notificationAction.notification == null
          || notificationAction.notification.getMessage() == null) {
        LOG.info(
            "Notification action has no notification and/or message set so cannot complete action: "
                + jsonRuleset);
        return null;
      }

      Notification notification = ValueUtil.clone(notificationAction.notification);
      String body;
      boolean linkedUsersTarget =
          ruleAction.target != null && Boolean.TRUE.equals(ruleAction.target.linkedUsers);
      boolean isLocalized =
          Objects.equals(notification.getMessage().getType(), LocalizedNotificationMessage.TYPE);
      boolean isEmail =
          Objects.equals(notification.getMessage().getType(), EmailNotificationMessage.TYPE);
      boolean isPush =
          Objects.equals(notification.getMessage().getType(), PushNotificationMessage.TYPE);
      boolean isHtml;

      if (isLocalized) {
        LocalizedNotificationMessage localizedMsg =
            (LocalizedNotificationMessage) notification.getMessage();
        isHtml = false;
        localizedMsg
            .getMessages()
            .forEach(
                (lang, msg) -> {
                  if (msg instanceof PushNotificationMessage pushMsg) {
                    PushNotificationAction action = pushMsg.getAction();
                    if (action != null && action.getUrl() != null) {
                      String newUrl =
                          replaceAssetIdPlaceholder(
                              action.getUrl(), ruleState, useUnmatched, "notification URL", true);
                      action.setUrl(newUrl);
                      pushMsg.setAction(action);
                    }

                    if (pushMsg.getBody() != null) {
                      String newBody =
                          replaceAssetIdPlaceholder(
                              pushMsg.getBody(),
                              ruleState,
                              useUnmatched,
                              "notification body",
                              false);
                      pushMsg.setBody(newBody);
                    }
                  }
                });
        body = null;
      } else if (isEmail) {
        EmailNotificationMessage email = (EmailNotificationMessage) notification.getMessage();
        isHtml = !TextUtil.isNullOrEmpty(email.getHtml());
        body = isHtml ? email.getHtml() : email.getText();
      } else {
        isHtml = false;
        if (isPush) {
          PushNotificationMessage pushMsg = (PushNotificationMessage) notification.getMessage();
          PushNotificationAction action;
          body = pushMsg.getBody();
          action = pushMsg.getAction();

          if (action != null && action.getUrl() != null) {
            String newUrl =
                replaceAssetIdPlaceholder(
                    action.getUrl(), ruleState, useUnmatched, "notification URL", true);
            action.setUrl(newUrl);
            pushMsg.setAction(action);
          }

          if (body != null) {
            String newBody =
                replaceAssetIdPlaceholder(
                    body, ruleState, useUnmatched, "notification body", false);
            pushMsg.setBody(newBody);
          }
        } else {
          body = null;
        }
      }

      // Transfer the rule action target into notification targets
      Notification.TargetType targetType = Notification.TargetType.ASSET;
      if (ruleAction.target != null) {
        if ((ruleAction.target.users != null || Boolean.TRUE.equals(ruleAction.target.linkedUsers))
            && ruleAction.target.conditionAssets == null
            && ruleAction.target.assets == null
            && ruleAction.target.matchedAssets == null) {
          targetType = Notification.TargetType.USER;
        } else if (ruleAction.target.custom != null
            && ruleAction.target.conditionAssets == null
            && ruleAction.target.assets == null
            && ruleAction.target.matchedAssets == null) {
          targetType = Notification.TargetType.CUSTOM;
        }
      }

      Collection<String> targetIds;
      boolean bodyContainsTriggeredAssetInfo;

      // In case of a localized message, we build a Map of all bodies in each language.
      // We also cache a list of languages that are using HTML, to use later
      Map<String, String> localizedBodies = new HashMap<>();
      Map<String, Boolean> localizedIsHtml = new HashMap<>();
      if (isLocalized) {
        ((LocalizedNotificationMessage) notification.getMessage())
            .getMessages()
            .forEach(
                (lang, msg) -> {
                  if (msg instanceof PushNotificationMessage pushMsg) {
                    localizedBodies.put(lang, pushMsg.getBody());
                    localizedIsHtml.put(lang, false);
                  } else if (msg instanceof EmailNotificationMessage emailMsg) {
                    boolean isMsgHtml = !TextUtil.isNullOrEmpty(emailMsg.getHtml());
                    localizedBodies.put(lang, isMsgHtml ? emailMsg.getHtml() : emailMsg.getText());
                    localizedIsHtml.put(lang, true);
                  }
                });
      }

      // Check if the body containers the PLACEHOLDER_TRIGGER_ASSETS
      if (isLocalized) {
        bodyContainsTriggeredAssetInfo =
            localizedBodies.values().stream()
                .anyMatch(
                    text ->
                        !TextUtil.isNullOrEmpty(text) && text.contains(PLACEHOLDER_TRIGGER_ASSETS));
      } else {
        bodyContainsTriggeredAssetInfo =
            !TextUtil.isNullOrEmpty(body) && body.contains(PLACEHOLDER_TRIGGER_ASSETS);
      }

      if (linkedUsersTarget) {

        // Find users linked to the matched assets applying any additional use query in the action
        Set<String> assetIds =
            useUnmatched ? ruleState.otherwiseMatchedAssetIds : ruleState.thenMatchedAssetIds;
        UserQuery userQuery =
            ruleAction.target.users != null ? ruleAction.target.users : new UserQuery();
        List<String> userIds =
            assetIds == null || assetIds.isEmpty()
                ? Collections.emptyList()
                : usersFacade
                    .getResults(userQuery.assets(assetIds.toArray(String[]::new)))
                    .toList();

        if (userIds.isEmpty()) {
          LOG.info(
              "No users linked to matched assets for triggered rule so nothing to do: "
                  + jsonRuleset);
          return null;
        }

        if (!bodyContainsTriggeredAssetInfo) {
          // Nothing user specific in the notification body so same notification can be sent to all
          // users
          targetIds = userIds;
        } else {
          // Linked users target requires special handling when asset trigger info is included in
          // the body, in this
          // situation a notification is produced for each linked user with the body containing only
          // assets that they are linked to.
          LOG.finer(() -> "Mapped target user IDs: " + String.join(",", userIds));

          // Get the user(s) asset links so we can group the matched assets by user
          String realm = getRealm();
          List<UserAssetLink> userAssetLinks =
              assetStorageService.findUserAssetLinks(realm, userIds, assetIds);

          // Generate a custom notification for each linked user
          String finalBody = body;
          Collection<Notification> customNotifications =
              userIds.stream()
                  .map(
                      userId -> {
                        // Extract asset states for matched asset IDs that are linked to this user
                        Map<String, Set<AttributeInfo>> assetStates =
                            getMatchedAssetStates(ruleState, useUnmatched, userAssetLinks, userId);

                        Notification customNotification = ValueUtil.clone(notification);

                        if (isLocalized) {
                          LocalizedNotificationMessage localizedMsg =
                              ((LocalizedNotificationMessage) customNotification.getMessage());
                          localizedMsg
                              .getMessages()
                              .forEach(
                                  (lang, msg) -> {
                                    String newBody =
                                        insertTriggeredAssetInfo(
                                            localizedBodies.get(lang),
                                            assetStates,
                                            localizedIsHtml.get(lang),
                                            false);
                                    localizedMsg.setMessage(
                                        lang,
                                        insertBodyInMessage(
                                            msg, localizedIsHtml.get(lang), newBody));
                                  });

                        } else {
                          String newBody =
                              insertTriggeredAssetInfo(finalBody, assetStates, isHtml, false);
                          customNotification.setMessage(
                              insertBodyInMessage(
                                  customNotification.getMessage(), isHtml, newBody));
                        }

                        customNotification.setTargets(
                            new Notification.Target(Notification.TargetType.USER, userId));
                        return customNotification;
                      })
                  .toList();

          return new RuleActionExecution(
              () ->
                  customNotifications.forEach(
                      customNotification -> {
                        log(
                            Level.FINE,
                            "Sending custom user notification for rule action: "
                                + rule.name
                                + " '"
                                + actionsName
                                + "' action index "
                                + index
                                + " [Targets="
                                + (customNotification.getTargets() != null
                                    ? customNotification.getTargets().stream()
                                        .map(Object::toString)
                                        .collect(Collectors.joining(","))
                                    : "null")
                                + "]");
                        notificationsFacade.send(customNotification);
                      }),
              0);
        }
      } else {
        targetIds =
            getRuleActionTargetIds(
                ruleAction.target, useUnmatched, ruleState, assetsFacade, usersFacade, facts);
      }

      if (targetIds == null) {
        notification.setTargets((List<Notification.Target>) null);
      } else {
        Notification.TargetType finalTargetType = targetType;
        notification.setTargets(
            targetIds.stream()
                .map(id -> new Notification.Target(finalTargetType, id))
                .collect(Collectors.toList()));
      }

      // Inject triggered asset info if needed
      if (bodyContainsTriggeredAssetInfo) {
        // Extract asset states for matched asset IDs
        Map<String, Set<AttributeInfo>> assetStates =
            getMatchedAssetStates(ruleState, useUnmatched, null, null);

        if (isLocalized) {
          LocalizedNotificationMessage localizedMsg =
              ((LocalizedNotificationMessage) notification.getMessage());
          localizedMsg
              .getMessages()
              .forEach(
                  (lang, msg) -> {
                    String newBody =
                        insertTriggeredAssetInfo(
                            localizedBodies.get(lang),
                            assetStates,
                            localizedIsHtml.get(lang),
                            false);
                    localizedMsg.setMessage(
                        lang, insertBodyInMessage(msg, localizedIsHtml.get(lang), newBody));
                  });

        } else {
          String newBody = insertTriggeredAssetInfo(body, assetStates, isHtml, false);
          notification.setMessage(insertBodyInMessage(notification.getMessage(), isHtml, newBody));
        }
      }

      log(
          Level.FINE,
          "Sending notification for rule action: "
              + rule.name
              + " '"
              + actionsName
              + "' action index "
              + index
              + " [Targets="
              + (notification.getTargets() != null
                  ? notification.getTargets().stream()
                      .map(Object::toString)
                      .collect(Collectors.joining(","))
                  : "null")
              + "]");
      return new RuleActionExecution(() -> notificationsFacade.send(notification), 0);
    }

    if (ruleAction instanceof RuleActionWebhook webhookAction) {
      if (webhookAction.webhook.getUrl() == null || webhookAction.webhook.getHttpMethod() == null) {
        LOG.info(
            "Webhook action has no URL and/or HTTP method set so cannot complete action: "
                + jsonRuleset);
        return null;
      }

      // Clone webhook due to mutation
      Webhook webhook = ValueUtil.clone(webhookAction.webhook);

      // Replace %TRIGGER_ASSETS% with the triggered assets in JSON format.
      if (!TextUtil.isNullOrEmpty(webhook.getPayload())
          && webhook.getPayload().contains(PLACEHOLDER_TRIGGER_ASSETS)) {
        Map<String, Set<AttributeInfo>> assetStates =
            getMatchedAssetStates(ruleState, useUnmatched, null, null);
        String triggeredAssetInfoPayload =
            insertTriggeredAssetInfo(webhook.getPayload(), assetStates, false, true);
        webhook.setPayload(triggeredAssetInfoPayload);
      }

      if (webhookAction.mediaType == null) {
        Optional<Map.Entry<String, List<String>>> contentTypeHeader =
            webhook.getHeaders().entrySet().stream()
                .filter((entry) -> entry.getKey().equalsIgnoreCase("content-type"))
                .findFirst();
        String contentType =
            contentTypeHeader.isPresent()
                ? contentTypeHeader.get().getValue().getFirst()
                : MediaType.APPLICATION_JSON;
        webhookAction.mediaType = MediaType.valueOf(contentType);
      }

      if (webhookAction.target == null) {
        webhookAction.target = webhooksFacade.buildTarget(webhook);
      }

      return new RuleActionExecution(
          () -> webhooksFacade.send(webhook, webhookAction.mediaType, webhookAction.target), 0);
    }

    if (ruleAction instanceof RuleActionAlarm alarmAction && (alarmAction.alarm != null)) {
      Alarm alarm = alarmAction.alarm;
      List<String> assetIds =
          new ArrayList<>(
              getRuleActionTargetIds(
                  ruleAction.target, useUnmatched, ruleState, assetsFacade, usersFacade, facts));
      if (alarm.getContent() != null) {
        String content = alarm.getContent();
        if (!TextUtil.isNullOrEmpty(content) && (content.contains(PLACEHOLDER_TRIGGER_ASSETS))) {
          // Need to clone the alarm
          alarm = ValueUtil.clone(alarm);
          Map<String, Set<AttributeInfo>> assetStates =
              getMatchedAssetStates(ruleState, useUnmatched, null, null);
          alarm.setContent(getAlarmContent(content, assetStates));
        }
      } else {
        log(
            Level.WARNING,
            "Alarm content is missing for rule action: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }
      if (alarm.getSeverity() == null) {
        log(
            Level.WARNING,
            "Alarm severity is missing for rule action: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }
      if (alarm.getTitle() == null) {
        log(
            Level.WARNING,
            "Alarm title is missing for rule action: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }

      alarm.setAssigneeId(alarmAction.assigneeId);
      alarm.setSourceId(Long.toString(jsonRuleset.getId()));

      Alarm finalAlarm = alarm;
      return new RuleActionExecution(() -> alarmsFacade.create(finalAlarm, assetIds), 0);
    }

    if (ruleAction instanceof RuleActionWriteAttribute attributeAction) {

      if (targetIsNotAssets(ruleAction.target)) {
        return null;
      }

      if (TextUtil.isNullOrEmpty(attributeAction.attributeName)) {
        log(
            Level.WARNING,
            "Attribute name is missing for rule action: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }

      Collection<String> ids =
          getRuleActionTargetIds(
              ruleAction.target, useUnmatched, ruleState, assetsFacade, usersFacade, facts);

      if (ids == null || ids.isEmpty()) {
        log(
            Level.INFO,
            "No targets for write attribute rule action so skipping: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }

      log(
          Level.FINE,
          "Writing attribute '"
              + attributeAction.attributeName
              + "' for "
              + ids.size()
              + " asset(s) for rule action: "
              + rule.name
              + " '"
              + actionsName
              + "' action index "
              + index);
      return new RuleActionExecution(
          () ->
              ids.forEach(
                  id ->
                      assetsFacade.dispatch(
                          id, attributeAction.attributeName, attributeAction.value)),
          0);
    }

    if (ruleAction instanceof RuleActionWait) {
      long millis = ((RuleActionWait) ruleAction).millis;
      if (millis > 0) {
        return new RuleActionExecution(null, millis);
      }
      log(
          Level.FINEST,
          "Invalid delay for wait rule action so skipping: "
              + rule.name
              + " '"
              + actionsName
              + "' action index "
              + index);
    }

    if (ruleAction instanceof RuleActionUpdateAttribute attributeUpdateAction) {

      if (targetIsNotAssets(ruleAction.target)) {
        log(
            Level.FINEST,
            "Invalid target update attribute rule action so skipping: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }

      if (TextUtil.isNullOrEmpty(attributeUpdateAction.attributeName)) {
        log(
            Level.WARNING,
            "Attribute name is missing for rule action: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }

      List<String> matchingAssetIds;

      if (ruleAction.target == null || ruleAction.target.assets == null) {
        if (targetIsNotAssets(ruleAction.target)) {
          throw new IllegalStateException(
              "Cannot use action type '"
                  + RuleActionUpdateAttribute.class.getSimpleName()
                  + "' with user target");
        }
        matchingAssetIds =
            new ArrayList<>(
                getRuleActionTargetIds(
                    ruleAction.target, useUnmatched, ruleState, assetsFacade, usersFacade, facts));
      } else {
        matchingAssetIds =
            facts
                .matchAssetState(ruleAction.target.assets)
                .map(AttributeInfo::getId)
                .distinct()
                .collect(Collectors.toList());
      }

      if (matchingAssetIds.isEmpty()) {
        log(
            Level.INFO,
            "No targets for update attribute rule action so skipping: "
                + rule.name
                + " '"
                + actionsName
                + "' action index "
                + index);
        return null;
      }

      // Look for the current value within the asset state facts (asset/attribute has to be in scope
      // of this rule engine and have a rule state meta item)
      List<AttributeInfo> matchingAssetStates =
          matchingAssetIds.stream()
              .map(
                  assetId ->
                      facts.getAssetStates().stream()
                          .filter(
                              state ->
                                  state.getId().equals(assetId)
                                      && state
                                          .getName()
                                          .equals(attributeUpdateAction.attributeName))
                          .findFirst()
                          .orElseGet(
                              () -> {
                                log(
                                    Level.WARNING,
                                    "Failed to find attribute in rule states for attribute update: "
                                        + new AttributeRef(
                                            assetId, attributeUpdateAction.attributeName));
                                return null;
                              }))
              .filter(Objects::nonNull)
              .collect(Collectors.toList());

      if (matchingAssetStates.isEmpty()) {
        log(Level.WARNING, "No asset states matched to apply update attribute action to");
        return null;
      }

      return new RuleActionExecution(
          () ->
              matchingAssetStates.forEach(
                  assetState -> {
                    Object value = assetState.getValue().orElse(null);
                    Class<?> valueType = assetState.getTypeClass();
                    boolean isArray = ValueUtil.isArray(valueType);

                    if (!isArray && !ValueUtil.isMap(valueType)) {
                      log(
                          Level.WARNING,
                          "Rule action target asset cannot determine value type or incompatible value type for attribute: "
                              + assetState);
                    } else {
                      if (isArray) {
                        List<Object> list = new ArrayList<>();
                        if (value != null) {
                          Collections.addAll(list, value);
                        }

                        switch (attributeUpdateAction.updateAction) {
                          case ADD -> {
                            list.add(attributeUpdateAction.value);
                          }
                          case ADD_OR_REPLACE, REPLACE -> {
                            if (attributeUpdateAction.index != null
                                && list.size() >= attributeUpdateAction.index) {
                              list.set(attributeUpdateAction.index, attributeUpdateAction.value);
                            } else {
                              list.add(attributeUpdateAction.value);
                            }
                          }
                          case DELETE -> {
                            if (attributeUpdateAction.index != null
                                && list.size() >= attributeUpdateAction.index) {
                              list.remove(attributeUpdateAction.index);
                            }
                          }
                          case CLEAR -> {
                            value = Collections.emptyList();
                          }
                        }

                        value = list;
                      } else {
                        Map map = new HashMap();
                        if (value != null) {
                          map.putAll((Map) value);
                        }

                        switch (attributeUpdateAction.updateAction) {
                          case ADD -> {
                            map.put(attributeUpdateAction.key, attributeUpdateAction.value);
                          }
                          case ADD_OR_REPLACE, REPLACE -> {
                            if (!TextUtil.isNullOrEmpty(attributeUpdateAction.key)) {
                              map.put(attributeUpdateAction.key, attributeUpdateAction.value);
                            } else {
                              log(
                                  Level.WARNING,
                                  "JSON Rule: Rule action missing required 'key': "
                                      + ValueUtil.asJSON(attributeUpdateAction));
                            }
                          }
                          case DELETE -> {
                            map.remove(attributeUpdateAction.key);
                          }
                          case CLEAR -> {
                            map = Collections.emptyMap();
                          }
                        }

                        value = map;
                      }

                      log(
                          Level.FINE,
                          "Updating attribute for rule action: "
                              + rule.name
                              + " '"
                              + actionsName
                              + "' action index "
                              + index
                              + ": "
                              + assetState);
                      assetsFacade.dispatch(
                          assetState.getId(), attributeUpdateAction.attributeName, value);
                    }
                  }),
          0);
    }

    log(
        Level.FINE,
        "Unsupported rule action: " + rule.name + " '" + actionsName + "' action index " + index);
    return null;
  }

  protected Map<String, Set<AttributeInfo>> getMatchedAssetStates(
      RuleState ruleState,
      boolean useUnmatched,
      Collection<UserAssetLink> userAssetLinks,
      String userId) {
    Set<String> assetIds =
        useUnmatched ? ruleState.otherwiseMatchedAssetIds : ruleState.thenMatchedAssetIds;

    return assetIds == null || assetIds.isEmpty()
        ? null
        : ruleState.conditionStateMap.values().stream()
            .filter(conditionState -> conditionState.lastEvaluationResult.matches)
            .flatMap(
                conditionState -> {
                  Collection<AttributeInfo> as =
                      useUnmatched
                          ? conditionState.lastEvaluationResult.unmatchedAssetStates
                          : conditionState.lastEvaluationResult.matchedAssetStates;
                  return as.stream();
                })
            // Get the asset states that are in the assetId list and optionally linked to this user
            .filter(
                assetState ->
                    assetIds.contains(assetState.getId())
                        && (userAssetLinks == null
                            || userAssetLinks.stream()
                                .anyMatch(
                                    ual ->
                                        ual.getId().getAssetId().equals(assetState.getId())
                                            && ual.getId().getUserId().equals(userId))))
            .collect(Collectors.groupingBy(AttributeInfo::getId, Collectors.toSet()));
  }

  protected String getRealm() {
    String realm = null;
    if (jsonRuleset instanceof RealmRuleset realmRuleset) {
      realm = realmRuleset.getRealm();
    } else if (jsonRuleset instanceof AssetRuleset assetRuleset) {
      realm = assetRuleset.getRealm();
    }
    return realm;
  }

  protected String getAlarmContent(String sourceText, Map<String, Set<AttributeInfo>> assetStates) {
    StringBuilder sb = new StringBuilder();

    assetStates.forEach(
        (key, value) ->
            value.forEach(
                assetState -> {
                  sb.append("ID: ").append(assetState.getId()).append("\n");
                  sb.append("Asset name: ").append(assetState.getAssetName()).append("\n");
                  sb.append("Attribute name: ").append(assetState.getName()).append("\n");
                  sb.append("Value: ")
                      .append(assetState.getValue().flatMap(ValueUtil::asJSON).orElse(""))
                      .append("\n");
                }));

    return sourceText.replace(PLACEHOLDER_TRIGGER_ASSETS, sb.toString());
  }

  protected String replaceAssetIdPlaceholder(
      String text, RuleState ruleState, boolean useUnmatched, String context, boolean firstOnly) {
    if (TextUtil.isNullOrEmpty(text) || !text.contains(PLACEHOLDER_ASSET_ID)) {
      return text;
    }

    Set<String> matchedIds =
        useUnmatched ? ruleState.otherwiseMatchedAssetIds : ruleState.thenMatchedAssetIds;

    if (matchedIds != null && !matchedIds.isEmpty()) {
      String replacement = firstOnly ? matchedIds.iterator().next() : String.join(",", matchedIds);

      String result = text.replace(PLACEHOLDER_ASSET_ID, replacement);
      log(Level.FINEST, "Replaced asset ID(s) in " + context + ": " + result);
      return result;
    } else {
      log(Level.WARNING, "Asset ID placeholder used but no matched assets found for " + context);
      return text;
    }
  }

  protected String insertTriggeredAssetInfo(
      String sourceText,
      Map<String, Set<AttributeInfo>> assetStates,
      boolean isHtml,
      boolean isJson) {

    StringBuilder sb = new StringBuilder();
    if (isHtml) {
      sb.append("<table cellpadding=\"30\">");
      sb.append("<tr><th>Asset ID</th><th>Asset Name</th><th>Attribute</th><th>Value</th></tr>");
      assetStates.forEach(
          (key, value) ->
              value.forEach(
                  assetState -> {
                    sb.append("<tr><td>");
                    sb.append(assetState.getId());
                    sb.append("</td><td>");
                    sb.append(assetState.getAssetName());
                    sb.append("</td><td>");
                    sb.append(assetState.getName());
                    sb.append("</td><td>");
                    sb.append(assetState.getValue().flatMap(ValueUtil::asJSON).orElse(""));
                    sb.append("</td></tr>");
                  }));
      sb.append("</table>");
    } else if (isJson) {
      try {
        return ValueUtil.JSON
            .writerWithView(AttributeEvent.Enhanced.class)
            .writeValueAsString(assetStates);
      } catch (Exception e) {
        LOG.warning(e.getMessage());
      }
    } else {
      sb.append("Asset ID\t\tAsset Name\t\tAttribute\t\tValue");
      assetStates.forEach(
          (key, value) ->
              value.forEach(
                  assetState -> {
                    sb.append(assetState.getId());
                    sb.append("\t\t");
                    sb.append(assetState.getAssetName());
                    sb.append("\t\t");
                    sb.append(assetState.getName());
                    sb.append("\t\t");
                    sb.append(
                        assetState
                            .getValue()
                            .map(v -> ValueUtil.convert(v, String.class))
                            .orElse(""));
                  }));
    }

    return sourceText.replace(PLACEHOLDER_TRIGGER_ASSETS, sb.toString());
  }

  protected AbstractNotificationMessage insertBodyInMessage(
      AbstractNotificationMessage sourceMessage, boolean isHtml, String body) {
    if (sourceMessage instanceof EmailNotificationMessage emailMsg) {
      if (isHtml) {
        emailMsg.setHtml(body);
      } else {
        emailMsg.setText(body);
      }
    } else if (sourceMessage instanceof PushNotificationMessage pushMsg) {
      pushMsg.setBody(body);
    }
    return sourceMessage;
  }

  protected static Collection<String> getRuleActionTargetIds(
      RuleActionTarget target,
      boolean useUnmatched,
      RuleState ruleState,
      Assets assetsFacade,
      Users usersFacade,
      RulesFacts facts) {

    Map<String, RuleConditionState> conditionStateMap = ruleState.conditionStateMap;

    if (target != null) {
      if (!TextUtil.isNullOrEmpty(target.conditionAssets) && conditionStateMap != null) {
        RuleConditionState triggerState = conditionStateMap.get(target.conditionAssets);
        if (!useUnmatched) {
          return triggerState != null ? triggerState.getMatchedAssetIds() : Collections.emptyList();
        }

        return triggerState != null ? triggerState.getUnmatchedAssetIds() : Collections.emptyList();
      }

      if (conditionStateMap != null && target.matchedAssets != null) {
        List<String> compareAssetIds =
            conditionStateMap.values().stream()
                .flatMap(
                    triggerState ->
                        useUnmatched
                            ? triggerState.getUnmatchedAssetIds().stream()
                            : triggerState.getMatchedAssetIds().stream())
                .toList();

        if (target.matchedAssets != null) {
          return facts
              .matchAssetState(target.matchedAssets)
              .map(AttributeInfo::getId)
              .distinct()
              .filter(compareAssetIds::contains)
              .collect(Collectors.toList());
        }
      }

      if (target.assets != null) {
        return getAssetIds(assetsFacade, target.assets);
      }

      if (target.users != null) {
        return getUserIds(usersFacade, target.users);
      }

      if (target.custom != null) {
        return Collections.singleton(target.custom);
      }
    }

    if (conditionStateMap != null) {
      if (!useUnmatched) {
        return conditionStateMap.values().stream()
            .flatMap(
                triggerState ->
                    triggerState != null
                        ? triggerState.getMatchedAssetIds().stream()
                        : Stream.empty())
            .distinct()
            .collect(Collectors.toList());
      }

      return conditionStateMap.values().stream()
          .flatMap(
              triggerState ->
                  triggerState != null
                      ? triggerState.getUnmatchedAssetIds().stream()
                      : Stream.empty())
          .distinct()
          .collect(Collectors.toList());
    }

    return Collections.emptyList();
  }

  protected static boolean targetIsNotAssets(RuleActionTarget target) {
    return target != null
        && (target.users != null || (target.linkedUsers != null && target.linkedUsers));
  }

  protected void log(Level level, String message) {
    LOG.log(level, LOG_PREFIX + jsonRuleset.getName() + "': " + message);
  }

  protected void log(Level level, String message, Throwable t) {
    LOG.log(level, LOG_PREFIX + jsonRuleset.getName() + "': " + message, t);
  }

  protected static SunTimes.Parameters getSunCalculator(
      Ruleset ruleset, SunPositionTrigger sunPositionTrigger, TimerService timerService)
      throws IllegalStateException {
    SunPositionTrigger.Position position = sunPositionTrigger.getPosition();
    GeoJSONPoint location = sunPositionTrigger.getLocation();

    if (position == null) {
      throw new IllegalStateException(
          LOG_PREFIX + ruleset.getName() + "': Rule condition sun requires a position value");
    }
    if (location == null) {
      throw new IllegalStateException(
          LOG_PREFIX + ruleset.getName() + "': Rule condition sun requires a location value");
    }

    SunTimes.Twilight twilight = null;
    if (position.name().startsWith(SunPositionTrigger.TWILIGHT_PREFIX)) {
      String lookupValue =
          position
              .name()
              .replace(SunPositionTrigger.MORNING_TWILIGHT_PREFIX, "")
              .replace(SunPositionTrigger.EVENING_TWILIGHT_PREFIX, "")
              .replace(SunPositionTrigger.TWILIGHT_PREFIX, "");
      twilight =
          EnumUtil.enumFromString(SunTimes.Twilight.class, lookupValue)
              .orElseThrow(
                  () -> {
                    throw new IllegalStateException(
                        LOG_PREFIX
                            + ruleset.getName()
                            + "': Rule condition un-supported twilight position value '"
                            + lookupValue
                            + "'");
                  });
    }

    SunTimes.Parameters sunCalculator =
        SunTimes.compute().on(timerService.getNow()).utc().at(location.getX(), location.getY());

    if (twilight != null) {
      sunCalculator.twilight(twilight);
    }

    return sunCalculator;
  }

  /**
   * Creates a function that matches attributes similar to {@link
   * AssetQueryPredicate#asAttributeMatcher}, but with duration logic applied per {@link
   * AttributePredicate} and only supports a single list of predicates.
   */
  @SuppressWarnings("unchecked")
  protected static Function<Collection<AttributeInfo>, Set<AttributeInfo>> asAttributeMatcher(
      Supplier<Long> currentMillisProducer,
      List<AttributePredicate> attributePredicates,
      Map<Integer, String> predicateDurationStrings,
      Map<Pair<AttributeInfo, Integer>, Long> durationMatchTimes) {
    if (attributePredicates == null || attributePredicates.isEmpty()) {
      return as -> Collections.EMPTY_SET;
    }

    List<Pair<Predicate<AttributeInfo>, Long>> attributePredicatesAndDurations =
        IntStream.range(0, attributePredicates.size())
            .mapToObj(
                i -> {
                  AttributePredicate p = attributePredicates.get(i);
                  Long predicateDuration = null;

                  // Parse duration string only once during build not each time during eval for each
                  // asset state as this is computationally expensive
                  if (predicateDurationStrings != null && predicateDurationStrings.get(i) != null) {
                    String durationStr = predicateDurationStrings.get(i);
                    predicateDuration = TimeUtil.parseTimeDuration(durationStr);
                  }

                  Predicate<AttributeInfo> predicate =
                      (Predicate<AttributeInfo>)
                          (Predicate) AssetQueryPredicate.asPredicate(currentMillisProducer, p);

                  if (p.meta != null) {
                    final Predicate<NameValueHolder<?>> innerMetaPredicate =
                        Arrays.stream(p.meta)
                            .map(
                                metaPred ->
                                    AssetQueryPredicate.asPredicate(
                                        currentMillisProducer, metaPred))
                            .reduce(x -> true, Predicate::and);

                    predicate =
                        predicate.and(
                            assetState -> {
                              MetaMap metaItems = assetState.getMeta();
                              return metaItems.stream()
                                  .anyMatch(metaItem -> innerMetaPredicate.test(assetState));
                            });
                  }

                  if (p.previousValue != null) {
                    Predicate<Object> innerOldValuePredicate =
                        p.previousValue.asPredicate(currentMillisProducer);
                    predicate =
                        predicate.and(
                            nameValueHolder ->
                                innerOldValuePredicate.test(nameValueHolder.getOldValue()));
                  }

                  return new Pair<>(predicate, predicateDuration);
                })
            .toList();

    return assetStates -> {
      Set<AttributeInfo> matchedAssetStates = new HashSet<>();
      // Can't just fail on first nonmatch as we need to keep duration times up to date for each
      // predicate
      boolean allPredicatesMatch =
          IntStream.range(0, attributePredicatesAndDurations.size())
                  .mapToObj(
                      i -> {
                        Pair<Predicate<AttributeInfo>, Long> predicateAndDuration =
                            attributePredicatesAndDurations.get(i);
                        Long duration = predicateAndDuration.getValue();

                        if (duration == null) {
                          // Find the first match as an attribute predicate shouldn't match more
                          // than one asset state
                          return assetStates.stream()
                              .filter(predicateAndDuration.getKey())
                              .findFirst()
                              .map(
                                  matchedAssetState -> {
                                    matchedAssetStates.add(matchedAssetState);
                                    return true;
                                  })
                              .orElse(false);
                        } else {
                          AtomicBoolean matchFound = new AtomicBoolean();
                          // Need to clear any duration timer for each asset state that doesn't
                          // match
                          assetStates.forEach(
                              assetState -> {
                                if (predicateAndDuration.getKey().test(assetState)) {
                                  boolean durationMatches = false;

                                  Pair<AttributeInfo, Integer> assetStatePredicateIndex =
                                      new Pair<>(assetState, i);
                                  // Look for existing duration end time
                                  Long previousMatchTime =
                                      durationMatchTimes.get(assetStatePredicateIndex);

                                  if (previousMatchTime != null) {
                                    // Check if previous match time is longer than duration millis
                                    // ago
                                    durationMatches =
                                        previousMatchTime + duration <= currentMillisProducer.get();
                                  } else {
                                    // Insert the current time for this asset state and predicate
                                    // index
                                    durationMatchTimes.put(
                                        assetStatePredicateIndex, currentMillisProducer.get());
                                  }

                                  // Store first match
                                  if (durationMatches && !matchFound.get()) {
                                    matchFound.set(true);
                                    matchedAssetStates.add(assetState);
                                  }
                                } else {
                                  durationMatchTimes.remove(new Pair<>(assetState, i));
                                }
                              });

                          return matchFound.get();
                        }
                      })
                  .filter(predicateMatches -> predicateMatches.equals(true))
                  .count()
              == attributePredicatesAndDurations.size();
      return allPredicatesMatch ? matchedAssetStates : null;
    };
  }
}
