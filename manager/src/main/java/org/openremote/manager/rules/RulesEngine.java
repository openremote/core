/*
 * Copyright 2017, OpenRemote Inc.
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

import static org.openremote.model.rules.RulesetStatus.*;

import io.micrometer.core.instrument.Timer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rule;
import org.jeasy.rules.api.RuleListener;
import org.jeasy.rules.api.RulesEngineParameters;
import org.jeasy.rules.core.AbstractRulesEngine;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.alarm.AlarmService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.datapoint.AssetDatapointService;
import org.openremote.manager.datapoint.AssetPredictedDatapointService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.notification.NotificationService;
import org.openremote.manager.rules.facade.*;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.webhook.WebhookService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeInfo;
import org.openremote.model.query.filter.GeofencePredicate;
import org.openremote.model.query.filter.LocationAttributePredicate;
import org.openremote.model.rules.*;
import org.openremote.model.syslog.SyslogCategory;

public class RulesEngine<T extends Ruleset> {

  /** Allows rule deployments to track changes to the {@link AttributeInfo}s in scope */
  public static final class AssetStateChangeEvent {
    public PersistenceEvent.Cause cause;
    public AttributeInfo assetState;

    public AssetStateChangeEvent(PersistenceEvent.Cause cause, AttributeInfo assetState) {
      this.cause = cause;
      this.assetState = assetState;
    }
  }

  /**
   * Identifies a set of {@link LocationAttributePredicate}s associated with a particular {@link
   * Asset}
   */
  public static final class AssetLocationPredicates {

    final String assetId;
    final Set<GeofencePredicate> locationPredicates;

    public AssetLocationPredicates(String assetId, Set<GeofencePredicate> locationPredicates) {
      this.assetId = assetId;
      this.locationPredicates = locationPredicates;
    }

    public String getAssetId() {
      return assetId;
    }

    public Set<GeofencePredicate> getLocationPredicates() {
      return locationPredicates;
    }
  }

  protected final Logger LOG;

  // Separate logger for periodic stats printer
  public static final Logger STATS_LOG = Logger.getLogger("org.openremote.rules.RulesEngineStats");
  protected final TimerService timerService;
  protected final RulesService rulesService;
  protected final ExecutorService executorService;
  protected final ScheduledExecutorService scheduledExecutorService;
  protected final AssetStorageService assetStorageService;
  protected final ClientEventService clientEventService;

  protected final RulesEngineId<T> id;
  protected final Assets assetsFacade;
  protected final Users usersFacade;
  protected final Notifications notificationFacade;
  protected final Webhooks webhooksFacade;
  protected final Alarms alarmsFacade;
  protected final PredictedDatapoints predictedFacade;
  protected final HistoricDatapoints historicFacade;
  protected final AssetLocationPredicateProcessor assetLocationPredicatesConsumer;

  protected final Map<Long, RulesetDeployment> deployments = new ConcurrentHashMap<>();
  protected final Map<Long, RulesetStatus> deploymentStatusMap = new ConcurrentHashMap<>();
  protected final RulesFacts facts;
  protected final AbstractRulesEngine engine;

  protected volatile boolean running;
  protected AtomicBoolean executing = new AtomicBoolean(false);
  protected boolean previouslyFired;
  protected long lastFireTimestamp;
  protected boolean trackLocationPredicates;
  protected ScheduledFuture<?> fireTimer;
  protected ScheduledFuture<?> statsTimer;
  protected final Set<AttributeInfo> updateInfos = new HashSet<>();
  protected final Set<AttributeInfo> insertInfos = new HashSet<>();
  protected final Set<AttributeInfo> retractInfos = new HashSet<>();

  // Only used to optimize toString(), contains the details of this engine
  protected String deploymentInfo;
  protected Timer rulesFiringTimer;

  public RulesEngine(
      TimerService timerService,
      RulesService rulesService,
      ManagerIdentityService identityService,
      ExecutorService executorService,
      ScheduledExecutorService scheduledExecutorService,
      AssetStorageService assetStorageService,
      AssetProcessingService assetProcessingService,
      NotificationService notificationService,
      WebhookService webhookService,
      AlarmService alarmService,
      ClientEventService clientEventService,
      AssetDatapointService assetDatapointService,
      AssetPredictedDatapointService assetPredictedDatapointService,
      RulesEngineId<T> id,
      AssetLocationPredicateProcessor assetLocationPredicatesConsumer,
      Timer rulesFiringTimer) {
    this.timerService = timerService;
    this.rulesService = rulesService;
    this.previouslyFired = rulesService.startDone;
    this.executorService = executorService;
    this.scheduledExecutorService = scheduledExecutorService;
    this.assetStorageService = assetStorageService;
    this.clientEventService = clientEventService;
    this.rulesFiringTimer = rulesFiringTimer;
    this.id = id;

    String ruleEngineCategory =
        id.scope.getSimpleName().replace("Ruleset", "Engine-") + id.getId().orElse("");
    LOG =
        SyslogCategory.getLogger(
            SyslogCategory.RULES, RulesEngine.class.getName() + "." + ruleEngineCategory);

    AssetsFacade<T> assetsFacade =
        new AssetsFacade<>(
            id,
            assetStorageService,
            attributeEvent -> {
              try {
                assetProcessingService.sendAttributeEvent(
                    attributeEvent, getClass().getSimpleName());
              } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to dispatch attribute event");
              }
            });
    this.assetsFacade = assetsFacade;
    this.usersFacade =
        new UsersFacade<>(id, assetStorageService, notificationService, identityService);
    this.notificationFacade = new NotificationsFacade<>(id, notificationService);
    this.webhooksFacade = new WebhooksFacade<>(id, webhookService);
    this.alarmsFacade = new AlarmFacade<>(id, alarmService, assetStorageService, identityService);
    this.historicFacade = new HistoricFacade<>(id, assetStorageService, assetDatapointService);
    this.predictedFacade =
        new PredictedFacade<>(id, assetStorageService, assetPredictedDatapointService);
    this.assetLocationPredicatesConsumer = assetLocationPredicatesConsumer;

    this.facts = new RulesFacts(timerService, assetStorageService, assetsFacade, this, LOG);
    engine =
        new DefaultRulesEngine(
            // Skip any other rules after the first failed rule (exception thrown in condition or
            // action)
            new RulesEngineParameters(
                false, true, false, RulesEngineParameters.DEFAULT_RULE_PRIORITY_THRESHOLD));
    engine.registerRuleListener(facts);

    // Add listener to rethrow runtime exceptions which are otherwise swallowed by the
    // DefaultRulesEngine
    engine.registerRuleListener(
        new RuleListener() {
          @Override
          public void onEvaluationError(Rule rule, Facts facts, Exception exception) {
            RuntimeException ex;
            if (exception instanceof RuntimeException) {
              ex = (RuntimeException) exception;
            } else {
              ex = new RuntimeException(exception);
            }
            throw ex;
          }

          @Override
          public void onFailure(Rule rule, Facts facts, Exception exception) {
            RuntimeException ex;
            if (exception instanceof RuntimeException) {
              ex = (RuntimeException) exception;
            } else {
              ex = new RuntimeException(exception);
            }
            throw ex;
          }
        });
  }

  public RulesEngineId<T> getId() {
    return id;
  }

  public boolean isRunning() {
    return running;
  }

  public boolean isError() {
    for (RulesetDeployment deployment : deployments.values()) {
      if (deployment.isError()) {
        return true;
      }
    }
    return false;
  }

  public int getExecutionErrorDeploymentCount() {
    return (int)
        deployments.values().stream()
            .filter(
                deployment ->
                    deployment.getStatus() == EXECUTION_ERROR
                        || deployment.getStatus() == LOOP_ERROR)
            .count();
  }

  public int getCompilationErrorDeploymentCount() {
    return (int)
        deployments.values().stream()
            .filter(deployment -> deployment.getStatus() == COMPILATION_ERROR)
            .count();
  }

  public RuntimeException getError() {
    long executionErrorCount = getExecutionErrorDeploymentCount();
    long compilationErrorCount = getCompilationErrorDeploymentCount();

    if (executionErrorCount > 0 || compilationErrorCount > 0) {
      return new RuntimeException(
          "Ruleset deployments have errors, failed compilation: "
              + compilationErrorCount
              + ", failed execution: "
              + executionErrorCount
              + " - on: "
              + this);
    }
    return null;
  }

  public void addRuleset(T ruleset) {

    // Check for previous version of this ruleset
    RulesetDeployment deployment = deployments.get(ruleset.getId());
    boolean wasRunning = this.running;

    stop();

    if (deployment != null) {
      removeRuleset(deployment.ruleset);
    }

    deployment =
        new RulesetDeployment(
            ruleset,
            this,
            timerService,
            assetStorageService,
            executorService,
            scheduledExecutorService,
            assetsFacade,
            usersFacade,
            notificationFacade,
            webhooksFacade,
            alarmsFacade,
            historicFacade,
            predictedFacade,
            rulesService.isGroovyRulesExecutionEnabled());
    deployment.init();
    deployments.put(ruleset.getId(), deployment);
    publishRulesetStatus(deployment);
    updateDeploymentInfo();

    if (wasRunning) {
      start();
    }
  }

  /**
   * @return <code>true</code> if this rules engine has no deployments.
   */
  public boolean removeRuleset(Ruleset ruleset) {
    RulesetDeployment deployment = deployments.get(ruleset.getId());

    if (deployment == null) {
      LOG.fine("Ruleset cannot be retracted as it was never deployed: " + ruleset);
    } else {
      boolean wasRunning = this.running;
      stop();
      stopRuleset(deployment);
      deployments.values().remove(deployment);
      updateDeploymentInfo();
      if (wasRunning && !deployments.isEmpty()) {
        start();
      }
    }

    return deployments.isEmpty();
  }

  public void start() {
    synchronized (this) {
      if (running) {
        return;
      }
      running = true;
    }

    if (deployments.isEmpty()) {
      LOG.finest("No rulesets so nothing to start");
      return;
    }

    boolean canAnyStart = deployments.values().stream().noneMatch(RulesetDeployment::canStart);

    if (canAnyStart) {
      LOG.info("Cannot start rules engine as no rulesets are able to be started");
      return;
    }

    LOG.info("Starting: " + id);
    trackLocationPredicates(true);

    deployments.values().forEach(this::startRuleset);

    updateDeploymentInfo();
    publishRulesEngineStatus();
    scheduleFire(true);

    // Start a background stats printer if INFO level logging is enabled
    if (STATS_LOG.isLoggable(Level.FINE) || STATS_LOG.isLoggable(Level.FINEST)) {
      if (STATS_LOG.isLoggable(Level.FINEST)) {
        LOG.info(
            "Enabling periodic statistics output at FINEST level every 30 seconds on category: "
                + STATS_LOG.getName());
      } else {
        LOG.info(
            "Enabling periodic full memory dump at FINE level every 30 seconds on category: "
                + STATS_LOG.getName());
      }
      statsTimer =
          scheduledExecutorService.scheduleAtFixedRate(
              this::printSessionStats, 3, 30, TimeUnit.SECONDS);
    }
  }

  protected void trackLocationPredicates(boolean track) {
    if (trackLocationPredicates == track) {
      return;
    }
    trackLocationPredicates = track;
    if (track) {
      facts.startTrackingLocationRules();
    } else {
      if (assetLocationPredicatesConsumer != null) {
        processLocationRules(facts.stopTrackingLocationRules());
      }
    }
  }

  public void stop() {
    ScheduledFuture<?> currentFireTimer;
    ScheduledFuture<?> currentStatsTimer;

    synchronized (this) {
      if (!running) {
        return;
      }
      running = false;
      currentFireTimer = fireTimer;
      fireTimer = null;
      currentStatsTimer = statsTimer;
      statsTimer = null;
    }

    LOG.info("Stopping: " + id);
    if (currentFireTimer != null) {
      currentFireTimer.cancel(true);
    }
    if (currentStatsTimer != null) {
      currentStatsTimer.cancel(true);
    }

    new HashSet<>(deployments.values()).forEach(this::stopRuleset);

    if (assetLocationPredicatesConsumer != null) {
      assetLocationPredicatesConsumer.accept(this, null);
    }

    publishRulesEngineStatus();
  }

  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  protected void startRuleset(RulesetDeployment deployment) {
    if (!running) {
      return;
    }

    synchronized (this) {
      if (deployment.start(facts)) {
        publishRulesetStatus(deployment);
      }
    }
  }

  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  protected void stopRuleset(RulesetDeployment deployment) {
    synchronized (this) {
      if (deployment.stop(facts)) {
        publishRulesetStatus(deployment);
      }
    }
  }

  // TODO: Add ability to subscribe to specific events from a ruleset so the ruleset triggers only
  // when appropriate
  /**
   * Queues actual firing of rules; if facts have changed then firing occurs in a shorter time frame
   * than if we just need to re-evaluate {@link TemporaryFact}s. This effectively limits how often
   * the rules engine will fire, only once within the guaranteed minimum expiration time.
   */
  protected synchronized void scheduleFire(boolean quickFire) {
    if (!running) {
      return;
    }

    ScheduledFuture<?> currentTimer = fireTimer;
    boolean timerRunning = currentTimer != null && !currentTimer.isDone();

    if (timerRunning) {
      if (!quickFire) {
        // Firing is already scheduled
        return;
      } else {
        if (currentTimer.getDelay(TimeUnit.MILLISECONDS) <= rulesService.quickFireMillis) {
          // Firing is already going to occur within time frame
          return;
        } else {
          // Cancel the existing timer
          currentTimer.cancel(false);
        }
      }
    }

    long fireTimeMillis =
        quickFire ? rulesService.quickFireMillis : rulesService.tempFactExpirationMillis;

    LOG.finest("Scheduling rules firing in " + fireTimeMillis + "ms");
    AtomicReference<ScheduledFuture<?>> scheduledTimer = new AtomicReference<>();
    scheduledTimer.set(
        scheduledExecutorService.schedule(
            () -> onFireTimerTriggered(scheduledTimer.get()),
            fireTimeMillis,
            TimeUnit.MILLISECONDS));
    fireTimer = scheduledTimer.get();
  }

  protected void onFireTimerTriggered(ScheduledFuture<?> scheduledTimer) {
    if (!running) {
      return;
    }

    // If we're not already executing rules, process rules for all deployments
    if (executing.compareAndSet(false, true)) {
      try {
        fireAllDeployments();
      } finally {
        executing.set(false);
      }
    }

    if (!running) {
      return;
    }

    if (Thread.currentThread().isInterrupted()) {
      LOG.finest("Timer interrupted during rules execution - not scheduling next fire");
      return;
    }

    synchronized (this) {
      // Ignore stale timer callbacks after a stop/start cycle or a reschedule.
      if (fireTimer != scheduledTimer) {
        return;
      }
      fireTimer = null;
    }

    scheduleFire(false);
  }

  protected void fireAllDeployments() {
    if (!running) {
      return;
    }

    Set<AttributeInfo> insertInfosCopy;
    Set<AttributeInfo> updateInfosCopy;
    Set<AttributeInfo> retractInfosCopy;

    // Synchronise attribute events and states
    synchronized (insertInfos) {
      insertInfosCopy = new HashSet<>(insertInfos);
      updateInfosCopy = new HashSet<>(updateInfos);
      retractInfosCopy = new HashSet<>(retractInfos);

      insertInfos.clear();
      updateInfos.clear();
      retractInfos.clear();
    }

    retractInfosCopy.forEach(
        attributeInfo -> {
          facts.removeAssetState(attributeInfo);
          // Make sure location predicate tracking is activated before notifying the deployments
          // otherwise they won't report location predicates
          trackLocationPredicates(
              trackLocationPredicates || attributeInfo.getName().equals(Asset.LOCATION.getName()));
          notifyAssetStatesChanged(
              new AssetStateChangeEvent(PersistenceEvent.Cause.DELETE, attributeInfo));
        });

    insertInfosCopy.forEach(
        attributeInfo -> {
          facts.putAssetState(attributeInfo);
          // Make sure location predicate tracking is activated before notifying the deployments
          // otherwise they won't report location predicates
          trackLocationPredicates(
              trackLocationPredicates || attributeInfo.getName().equals(Asset.LOCATION.getName()));
          notifyAssetStatesChanged(
              new AssetStateChangeEvent(PersistenceEvent.Cause.CREATE, attributeInfo));
        });

    updateInfosCopy.forEach(
        attributeInfo -> {
          facts.putAssetState(attributeInfo);
          notifyAssetStatesChanged(
              new AssetStateChangeEvent(PersistenceEvent.Cause.UPDATE, attributeInfo));
        });

    if (trackLocationPredicates && assetLocationPredicatesConsumer != null) {
      facts.startTrackingLocationRules();
    }

    // Remove any expired temporary facts
    facts.removeExpiredTemporaryFacts();
    long executionTotalMillis = timerService.getCurrentTimeMillis();

    if (rulesFiringTimer != null) {
      rulesFiringTimer.record(this::doFire);
    } else {
      doFire();
    }

    trackLocationPredicates(false);
    executionTotalMillis = (timerService.getCurrentTimeMillis() - executionTotalMillis);

    if (executionTotalMillis > 500) {
      LOG.warning("Rules firing took " + executionTotalMillis + "ms");
    } else {
      LOG.fine("Rules firing took " + executionTotalMillis + "ms");
    }
  }

  protected void doFire() {
    for (RulesetDeployment deployment : deployments.values()) {
      try {

        RulesetStatus status = deployment.getStatus();
        publishRulesetStatus(deployment);

        if (status == DEPLOYED) {

          LOG.finest("Executing rules of: " + deployment);

          // If full detail logging is enabled
          // Log asset states and events before firing
          facts.logFacts(LOG, Level.FINEST);

          // Reset facts for this firing (loop detection etc.)
          facts.reset();

          long startTimestamp = timerService.getCurrentTimeMillis();

          engine.fire(deployment.getRules(), facts);
          long executionMillis = (timerService.getCurrentTimeMillis() - startTimestamp);
          LOG.fine(
              "Rules deployment '"
                  + deployment.getName()
                  + "' executed in: "
                  + executionMillis
                  + "ms");
        } else {
          LOG.fine(
              "Rules deployment '" + deployment.getName() + "' skipped as status is: " + status);
        }
      } catch (Exception ex) {
        LOG.log(Level.SEVERE, "Error executing rules of: " + deployment, ex);

        deployment.setStatus(ex instanceof RulesLoopException ? LOOP_ERROR : EXECUTION_ERROR);
        deployment.setError(ex);
        publishRulesetStatus(deployment);

        if (ex instanceof RulesLoopException || !deployment.ruleset.isContinueOnError()) {
          stop();
          break;
        }
      } finally {
        // Reset facts after this firing (loop detection etc.)
        facts.reset();
        lastFireTimestamp = timerService.getCurrentTimeMillis();
      }
    }
    previouslyFired = true;
  }

  protected String getEngineId() {
    if (id.scope == GlobalRuleset.class) {
      return "";
    }
    if (id.scope == RealmRuleset.class) {
      return id.realm;
    }
    return id.realm + ":" + id.assetId;
  }

  protected void notifyAssetStatesChanged(AssetStateChangeEvent event) {
    for (RulesetDeployment deployment : deployments.values()) {
      if (!deployment.isError()) {
        deployment.onAssetStatesChanged(facts, event);
      }
    }
  }

  public void insertOrUpdateAttributeInfo(AttributeInfo attributeInfo, boolean insert) {

    synchronized (insertInfos) {
      insert = insert || insertInfos.remove(attributeInfo);
      updateInfos.remove(attributeInfo);
      retractInfos.remove(attributeInfo);

      if (insert) {
        insertInfos.add(attributeInfo);
      } else {
        updateInfos.add(attributeInfo);
      }
    }

    if (running) {
      scheduleFire(true);
    }
  }

  public void retractAttributeInfo(AttributeInfo attributeInfo) {

    synchronized (insertInfos) {
      insertInfos.remove(attributeInfo);
      updateInfos.remove(attributeInfo);
      retractInfos.remove(attributeInfo);
      retractInfos.add(attributeInfo);
    }

    if (running) {
      scheduleFire(true);
    }
  }

  protected void updateDeploymentInfo() {
    deploymentInfo =
        Arrays.toString(
            deployments.values().stream().map(RulesetDeployment::toString).toArray(String[]::new));
  }

  protected synchronized void printSessionStats() {
    Collection<AttributeInfo> assetStateFacts = facts.getAssetStates();
    Map<String, Object> namedFacts = facts.getNamedFacts();
    Collection<Object> anonFacts = facts.getAnonymousFacts();
    long temporaryFactsCount = facts.getTemporaryFacts().count();
    long total = assetStateFacts.size() + namedFacts.size() + anonFacts.size();
    STATS_LOG.fine(
        "Engine stats for '"
            + this
            + "', in memory facts are Total: "
            + total
            + ", AssetState: "
            + assetStateFacts.size()
            + ", Named: "
            + namedFacts.size()
            + ", Anonymous: "
            + anonFacts.size()
            + ", Temporary: "
            + temporaryFactsCount);

    // Additional details if FINEST is enabled
    facts.logFacts(STATS_LOG, Level.FINEST);
  }

  /**
   * This is called with all the asset's that have a location attribute currently loaded into this
   * engine.
   */
  protected void processLocationRules(List<AssetLocationPredicates> assetStateLocationPredicates) {
    if (assetLocationPredicatesConsumer != null) {
      assetLocationPredicatesConsumer.accept(this, assetStateLocationPredicates);
    }
  }

  public boolean hasPreviouslyFired() {
    return previouslyFired;
  }

  protected RulesEngineStatus getStatus() {
    if (isRunning()) {
      return RulesEngineStatus.RUNNING;
    }

    return deployments.values().stream().anyMatch(RulesetDeployment::isError)
        ? RulesEngineStatus.ERROR
        : RulesEngineStatus.STOPPED;
  }

  protected void publishRulesEngineStatus() {
    String engineId = id == null ? null : id.getRealm().orElse(id.getAssetId().orElse(null));
    int compilationErrors = getCompilationErrorDeploymentCount();
    int executionErrors = getExecutionErrorDeploymentCount();
    RulesEngineInfo engineInfo =
        new RulesEngineInfo(getStatus(), compilationErrors, executionErrors);

    RulesEngineStatusEvent event =
        new RulesEngineStatusEvent(timerService.getCurrentTimeMillis(), engineId, engineInfo);

    LOG.finest("Publishing rules engine status event: " + event);

    // Notify clients
    clientEventService.publishEvent(event);
  }

  protected void publishRulesetStatus(RulesetDeployment deployment) {
    if (!running) {
      return;
    }

    Ruleset ruleset = deployment.ruleset;
    RulesetStatus previousStatus = deploymentStatusMap.get(ruleset.getId());
    String engineId = id == null ? null : id.getRealm().orElse(id.getAssetId().orElse(null));
    RulesetStatus currentStatus = deployment.getStatus();

    if (currentStatus != previousStatus) {
      deploymentStatusMap.put(ruleset.getId(), currentStatus);
      ruleset.setStatus(deployment.getStatus());
      ruleset.setError(deployment.getErrorMessage());

      RulesetChangedEvent event =
          new RulesetChangedEvent(timerService.getCurrentTimeMillis(), engineId, ruleset);

      LOG.finest("Ruleset '" + deployment.getName() + "': status=" + currentStatus);

      // Notify clients
      clientEventService.publishEvent(event);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "id='"
        + id
        + '\''
        + ", running='"
        + isRunning()
        + '\''
        + ", deployments='"
        + deploymentInfo
        + '\''
        + '}';
  }
}
