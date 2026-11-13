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

import static org.openremote.model.rules.RulesetStatus.COMPILATION_ERROR;
import static org.openremote.model.rules.RulesetStatus.DEPLOYED;
import static org.openremote.model.rules.RulesetStatus.DISABLED;
import static org.openremote.model.rules.RulesetStatus.EMPTY;
import static org.openremote.model.rules.RulesetStatus.VALIDITY_PERIOD_ERROR;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jeasy.rules.api.Rule;
import org.jeasy.rules.api.Rules;
import org.kohsuke.groovy.sandbox.GroovyValueFilter;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.model.calendar.CalendarEvent;
import org.openremote.model.rules.Alarms;
import org.openremote.model.rules.AssetRuleset;
import org.openremote.model.rules.Assets;
import org.openremote.model.rules.HistoricDatapoints;
import org.openremote.model.rules.Notifications;
import org.openremote.model.rules.PredictedDatapoints;
import org.openremote.model.rules.RealmRuleset;
import org.openremote.model.rules.Ruleset;
import org.openremote.model.rules.RulesetStatus;
import org.openremote.model.rules.Users;
import org.openremote.model.rules.Webhooks;
import org.openremote.model.rules.flow.NodeCollection;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

public class RulesetDeployment {

  // TODO Finish groovy sandbox
  static class GroovyDenyAllFilter extends GroovyValueFilter {
    @Override
    public Object filterReceiver(Object receiver) {
      throw new SecurityException("Not allowed: " + receiver);
    }
  }

  public static final int DEFAULT_RULE_PRIORITY = 1000;

  protected static final GroovyShell groovyShell;

  static {
    /* TODO Sharing a static GroovyShell doesn't work, redeploying a ruleset which defines classes (e.g. Flight) is broken:
    java.lang.RuntimeException: Error evaluating condition of rule '-Update flight facts when estimated landing time of flight asset is updated':
    No signature of method: org.openremote.manager.setup.database.Script1$_run_closure2$_closure14$_closure17.doCall() is applicable for argument types: (org.openremote.manager.setup.database.Flight) values: [...]
    Possible solutions: doCall(org.openremote.manager.setup.database.Flight), findAll(), findAll(), isCase(java.lang.Object), isCase(java.lang.Object)
    The following classes appear as argument class and as parameter class, but are defined by different class loader:
    org.openremote.manager.setup.database.Flight (defined by 'groovy.lang.GroovyClassLoader$InnerLoader@2cc34cd5' and 'groovy.lang.GroovyClassLoader$InnerLoader@1af957bc')
    If one of the method suggestions matches the method you wanted to call,
    then check your class loader setup.
     */
    groovyShell =
        new GroovyShell(
            // See https://github.com/openremote/core/issues/2843, can't use
            // SandboxTransformer until it's Groovy 5 compatible
            //                new CompilerConfiguration().addCompilationCustomizers(new
            // SandboxTransformer())
            );
  }

  protected static final Pair<Long, Long> ALWAYS_ACTIVE = new Pair<>(0L, Long.MAX_VALUE);
  protected static final Pair<Long, Long> EXPIRED = new Pair<>(0L, 0L);
  protected final Ruleset ruleset;
  protected final Rules rules = new Rules();
  protected final AssetStorageService assetStorageService;
  protected final TimerService timerService;
  protected final ExecutorService executorService;
  protected final ScheduledExecutorService scheduledExecutorService;
  protected final Assets assetsFacade;
  protected final Users usersFacade;
  protected final Notifications notificationsFacade;
  protected final Webhooks webhooksFacade;
  protected final Alarms alarmsFacade;
  protected final HistoricDatapoints historicDatapointsFacade;
  protected final PredictedDatapoints predictedDatapointsFacade;
  protected final boolean groovyRulesExecutionEnabled;
  protected final List<ScheduledFuture<?>> scheduledRuleActions =
      Collections.synchronizedList(new ArrayList<>());
  protected final RulesEngine<?> rulesEngine;
  protected final Logger LOG;
  protected boolean running;
  protected RulesetStatus status = RulesetStatus.READY;
  protected Throwable error;
  protected JsonRulesBuilder jsonRulesBuilder;
  protected FlowRulesBuilder flowRulesBuilder;
  protected CalendarEvent validity;
  protected Pair<Long, Long> nextValidity;

  public RulesetDeployment(
      Ruleset ruleset,
      RulesEngine<?> rulesEngine,
      TimerService timerService,
      AssetStorageService assetStorageService,
      ExecutorService executorService,
      ScheduledExecutorService scheduledExecutorService,
      Assets assetsFacade,
      Users usersFacade,
      Notifications notificationsFacade,
      Webhooks webhooksFacade,
      Alarms alarmsFacade,
      HistoricDatapoints historicDatapointsFacade,
      PredictedDatapoints predictedDatapointsFacade,
      boolean groovyRulesExecutionEnabled) {
    this.ruleset = ruleset;
    this.rulesEngine = rulesEngine;
    this.timerService = timerService;
    this.assetStorageService = assetStorageService;
    this.executorService = executorService;
    this.scheduledExecutorService = scheduledExecutorService;
    this.assetsFacade = assetsFacade;
    this.usersFacade = usersFacade;
    this.notificationsFacade = notificationsFacade;
    this.webhooksFacade = webhooksFacade;
    this.alarmsFacade = alarmsFacade;
    this.historicDatapointsFacade = historicDatapointsFacade;
    this.predictedDatapointsFacade = predictedDatapointsFacade;
    this.groovyRulesExecutionEnabled = groovyRulesExecutionEnabled;

    String ruleCategory = ruleset.getClass().getSimpleName() + "-" + ruleset.getId();
    LOG =
        SyslogCategory.getLogger(
            SyslogCategory.RULES, RulesEngine.class.getName() + "." + ruleCategory);
  }

  protected void init() throws IllegalStateException {
    if (ruleset.getMeta().containsKey(Ruleset.VALIDITY)) {
      validity = ruleset.getValidity();

      if (validity == null) {
        LOG.log(
            Level.WARNING,
            "Ruleset '"
                + ruleset.getName()
                + "' has invalid validity value: "
                + ruleset.getMeta().get(Ruleset.VALIDITY));
        status = VALIDITY_PERIOD_ERROR;
        return;
      }
    }

    if (TextUtil.isNullOrEmpty(ruleset.getRules())) {
      LOG.finest("Ruleset is empty so no rules to deploy: " + ruleset.getName());
      status = EMPTY;
      return;
    }

    if (!ruleset.isEnabled()) {
      LOG.finest("Ruleset is disabled: " + ruleset.getName());
      status = DISABLED;
    }

    if (ruleset.getLang() == Ruleset.Lang.GROOVY && !groovyRulesExecutionEnabled) {
      LOG.info(
          "Groovy ruleset execution is disabled; skipping compilation for ruleset: "
              + ruleset.getName());
      status = DISABLED;
      return;
    }

    if (!compile()) {
      LOG.log(Level.SEVERE, "Ruleset compilation error: " + ruleset.getName(), getError());
      status = COMPILATION_ERROR;
    }
  }

  public long getId() {
    return ruleset.getId();
  }

  public String getName() {
    return ruleset.getName();
  }

  public long getVersion() {
    return ruleset.getVersion();
  }

  public Ruleset getRuleset() {
    return ruleset;
  }

  public Rules getRules() {
    return rules;
  }

  protected void updateValidity() {
    Pair<Long, Long> fromTo =
        validity.getNextOrActiveFromTo(new Date(timerService.getCurrentTimeMillis()));
    if (fromTo == null) {
      nextValidity = EXPIRED;
      LOG.log(Level.INFO, "Ruleset deployment '" + getName() + "' has expired");
    } else {
      nextValidity = fromTo;
      LOG.log(
          Level.INFO,
          "Ruleset deployment '" + getName() + "' paused until: " + new Date(fromTo.key));
    }
  }

  /**
   * Returns the current or next time window in which this rule is active
   *
   * @return null if deployment has expired
   */
  public Pair<Long, Long> getNextOrActiveFromTo() {
    if (validity == null) {
      return ALWAYS_ACTIVE;
    }

    if (nextValidity == EXPIRED) {
      return nextValidity;
    }

    if (nextValidity == null || nextValidity.value <= timerService.getCurrentTimeMillis()) {
      updateValidity();
    }

    return nextValidity;
  }

  public boolean compile() {
    LOG.info("Compiling ruleset deployment: " + ruleset);
    if (error != null) {
      return false;
    }

    switch (ruleset.getLang()) {
      case JAVASCRIPT:
        LOG.warning("JavaScript not supported for ruleset: " + ruleset.getName());
        return false;
      case GROOVY:
        return compileRulesGroovy(
            ruleset,
            assetsFacade,
            usersFacade,
            notificationsFacade,
            historicDatapointsFacade,
            predictedDatapointsFacade);
      case JSON:
        return compileRulesJson(ruleset);
      case FLOW:
        return compileRulesFlow(
            ruleset,
            assetsFacade,
            usersFacade,
            notificationsFacade,
            historicDatapointsFacade,
            predictedDatapointsFacade);
    }
    return false;
  }

  public boolean canStart() {
    return status != COMPILATION_ERROR && status != DISABLED && status != RulesetStatus.EXPIRED;
  }

  /** Called when a ruleset is started (allows for initialization tasks) */
  public boolean start(RulesFacts facts) {
    if (!canStart()) {
      return false;
    }

    if (jsonRulesBuilder != null) {
      jsonRulesBuilder.start(facts);
    }

    running = true;
    return true;
  }

  /**
   * Called when this deployment is stopped, could be the ruleset is being updated, removed or an
   * error has occurred during execution
   */
  public boolean stop(RulesFacts facts) {
    if (!running) {
      return false;
    }

    running = false;

    synchronized (scheduledRuleActions) {
      scheduledRuleActions.removeIf(
          scheduledFuture -> {
            scheduledFuture.cancel(true);
            return true;
          });
    }

    if (jsonRulesBuilder != null) {
      jsonRulesBuilder.stop(facts);
    }

    return true;
  }

  public void onAssetStatesChanged(RulesFacts facts, RulesEngine.AssetStateChangeEvent event) {
    if (jsonRulesBuilder != null) {
      jsonRulesBuilder.onAssetStatesChanged(facts, event);
    }
  }

  protected void scheduleRuleAction(Runnable action, long delayMillis) {
    ScheduledFuture<?> future =
        scheduledExecutorService.schedule(
            () -> {
              scheduledRuleActions.removeIf(Future::isDone);
              action.run();
            },
            delayMillis,
            TimeUnit.MILLISECONDS);
    scheduledRuleActions.add(future);
  }

  protected boolean compileRulesJson(Ruleset ruleset) {

    try {
      jsonRulesBuilder =
          new JsonRulesBuilder(
              LOG,
              ruleset,
              rulesEngine,
              timerService,
              assetStorageService,
              assetsFacade,
              usersFacade,
              notificationsFacade,
              webhooksFacade,
              alarmsFacade,
              historicDatapointsFacade,
              predictedDatapointsFacade,
              this::scheduleRuleAction);

      for (Rule rule : jsonRulesBuilder.build()) {
        LOG.finest("Registering JSON rule: " + rule.getName());
        rules.register(rule);
      }

      return true;
    } catch (Exception e) {
      setError(e);
      return false;
    }
  }

  protected boolean compileRulesGroovy(
      Ruleset ruleset,
      Assets assetsFacade,
      Users usersFacade,
      Notifications notificationFacade,
      HistoricDatapoints historicDatapointsFacade,
      PredictedDatapoints predictedDatapointsFacade) {
    try {
      // TODO Implement sandbox
      // new DenyAll().register();
      Script script = groovyShell.parse(ruleset.getRules());
      Binding binding = new Binding();
      RulesBuilder rulesBuilder = new RulesBuilder();
      binding.setVariable("LOG", LOG);
      binding.setVariable("rules", rulesBuilder);
      binding.setVariable("assets", assetsFacade);
      binding.setVariable("users", usersFacade);
      binding.setVariable("notifications", notificationFacade);
      binding.setVariable("historicDatapoints", historicDatapointsFacade);
      binding.setVariable("predictedDatapoints", predictedDatapointsFacade);

      if (ruleset instanceof RealmRuleset) {
        binding.setVariable("realm", ((RealmRuleset) ruleset).getRealm());
      }

      if (ruleset instanceof AssetRuleset) {
        binding.setVariable("assetId", ((AssetRuleset) ruleset).getAssetId());
      }

      script.setBinding(binding);
      script.run();
      for (Rule rule : rulesBuilder.build()) {
        LOG.finest("Registering groovy rule: " + rule.getName());
        rules.register(rule);
      }

      return true;

    } catch (Exception e) {
      setError(e);
      return false;
    }
  }

  protected boolean compileRulesFlow(
      Ruleset ruleset,
      Assets assetsFacade,
      Users usersFacade,
      Notifications notificationsFacade,
      HistoricDatapoints historicDatapointsFacade,
      PredictedDatapoints predictedDatapointsFacade) {
    try {
      flowRulesBuilder =
          new FlowRulesBuilder(
              LOG,
              timerService,
              assetStorageService,
              assetsFacade,
              usersFacade,
              notificationsFacade,
              historicDatapointsFacade,
              predictedDatapointsFacade);
      NodeCollection nodeCollection =
          ValueUtil.JSON.readValue(ruleset.getRules(), NodeCollection.class);
      flowRulesBuilder.add(nodeCollection);
      for (Rule rule : flowRulesBuilder.build()) {
        LOG.info("Compiling flow rule: " + rule.getName());
        rules.register(rule);
      }
      return true;
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Error evaluating flow rule ruleset: " + ruleset, e);
      setError(e);
      return false;
    }
  }

  public RulesetStatus getStatus() {

    if (isError() || status == DISABLED) {
      return status;
    }

    Pair<Long, Long> validity = getNextOrActiveFromTo();

    if (validity == EXPIRED) {
      return RulesetStatus.EXPIRED;
    }
    if (validity != ALWAYS_ACTIVE) {
      if (validity.key > timerService.getCurrentTimeMillis()) {
        return RulesetStatus.PAUSED;
      }
    }

    if (running) {
      return DEPLOYED;
    }

    return status;
  }

  public void setStatus(RulesetStatus status) {
    this.status = status;
  }

  public Throwable getError() {
    return error;
  }

  public void setError(Throwable error) {
    this.error = error;
  }

  public String getErrorMessage() {
    return getError() != null ? getError().getMessage() : null;
  }

  public boolean isError() {
    return status == RulesetStatus.LOOP_ERROR
        || status == VALIDITY_PERIOD_ERROR
        || ((status == RulesetStatus.EXECUTION_ERROR || status == RulesetStatus.COMPILATION_ERROR)
            && !isContinueOnError());
  }

  public boolean isContinueOnError() {
    return ruleset.isContinueOnError();
  }

  public boolean isTriggerOnPredictedData() {
    return ruleset.isTriggerOnPredictedData();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "id="
        + getId()
        + ", name='"
        + getName()
        + '\''
        + ", version="
        + getVersion()
        + ", status="
        + getStatus()
        + '}';
  }
}
