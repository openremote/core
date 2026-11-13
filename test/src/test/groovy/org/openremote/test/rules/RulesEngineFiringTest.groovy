/*
 * Copyright 2026, OpenRemote Inc.
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
package org.openremote.test.rules

import jakarta.persistence.EntityManager
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.rules.RulesEngine
import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.model.Constants
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.rules.RealmRuleset
import org.openremote.model.rules.Ruleset
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import static org.openremote.model.rules.Ruleset.Lang.GROOVY

class RulesEngineFiringTest extends Specification implements ManagerContainerTrait {

  /*
   * See https://github.com/openremote/core/issues/1953
   * When rules evaluation takes longer than the firing period AND an attribute event occurs during that time,
   * the rules engine would fire even if rules where still being evaluated.
   */
  @SuppressWarnings("GroovyAccessibility")
  def "Check rules engine are not running in parallel"() {
    given: "the container is started"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesService = container.getService(RulesService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    RulesEngine engine = null

    and: "some rulesets with a long running rule"
    Ruleset ruleset = new RealmRuleset(
            Constants.MASTER_REALM,
            "Parallel Engine check rules",
            GROOVY,
            getClass().getResource("/org/openremote/test/rules/ParallelEngineCheck.groovy").text)
    Long masterRulesetId = rulesetStorageService.merge(ruleset).id

    and: "assets are added"
    def counterAsset = new ThingAsset("CounterAsset")
            .setId(UniqueIdentifierGenerator.generateId("CounterAsset"))
            .setRealm(Constants.MASTER_REALM)
            .addAttributes(
            new Attribute<>("count", ValueType.INTEGER, null).addMeta(new MetaItem<>(MetaItemType.RULE_STATE)),
    )
    counterAsset = assetStorageService.merge(counterAsset)

    expect: "the rule engines to become available and be running"
    conditions.eventually {
      engine = rulesService.realmEngines.get(Constants.MASTER_REALM)
      assert engine != null
      assert engine.isRunning()
    }

    when: "a mock rules engine is created"
    // We want to stop rules evaluation while doing this setup, otherwise our count of attribute event change is biased
    rulesService.stop(container)
    RulesEngine mockRulesEngine = Spy(engine)
    AtomicBoolean rulesExecuting = new AtomicBoolean(false)
    AtomicBoolean parallelExecutionOccurred = new AtomicBoolean(false)
    mockRulesEngine.fireAllDeployments() >> {
      if (rulesExecuting.getAndSet(true)) {
        parallelExecutionOccurred.set(true)
      }
      callRealMethod()
      rulesExecuting.set(false)
    }
    rulesService.realmEngines.put(Constants.MASTER_REALM, mockRulesEngine)
    rulesService.start(container)

    and: "An attribute has changed value"
    def receivedEvents = new ArrayList<AttributeEvent>()
    assetProcessingService.addEventInterceptor { EntityManager em, AttributeEvent event ->
      receivedEvents.add(event)
      false
    }

    then: "A new attribute event is sent"
    conditions.eventually {
      // The exact value does not matter, as the rule when is always true if the attribute exists
      // it just needs to be long enough for a potential co-execution to happen
      // and short enough to be reached before the timeout
      assert receivedEvents.size() == 4
    }
    assert !parallelExecutionOccurred.get(): "Rules engine triggered while rules are still executing"
  }

  @SuppressWarnings("GroovyAccessibility")
  def "Check schedule fire tolerates concurrent timer clearing"() {
    given: "the container is started"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesService = container.getService(RulesService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    RulesEngine engine = null

    and: "a simple realm ruleset is deployed"
    Ruleset ruleset = new RealmRuleset(
            Constants.MASTER_REALM,
            "Concurrent timer clearing",
            GROOVY,
            getClass().getResource("/org/openremote/test/rules/BasicMatchAllAssetStates.groovy").text)
    rulesetStorageService.merge(ruleset)

    expect: "the rules engine to become available and running"
    conditions.eventually {
      engine = rulesService.realmEngines.get(Constants.MASTER_REALM)
      assert engine != null
      assert engine.isRunning()
    }

    when: "the timer reference is cleared after the running check but before the delay lookup"
    engine.stop()
    engine.@running = true

    ScheduledFuture<?> timer = Mock(ScheduledFuture)
    timer.isDone() >> {
      engine.@fireTimer = null
      false
    }
    timer.getDelay(TimeUnit.MILLISECONDS) >> 0L
    engine.@fireTimer = timer

    engine.scheduleFire(true)

    then: "the quick fire check does not dereference a null timer"
    noExceptionThrown()

    cleanup:
    stopContainer()
  }

  /*
   The exact timing issue is:
   - fireTimer triggers and is executing the rules (fireAllDeployments)
   - stop is called, canceling the fireTimer and setting it to null
   - start is called, creating a new fireTimer
   - in this case, that new timer does not fire yet and fireAllDeployments returns,
   the fire lambda sets fireTimer to null and creates a new timer
   As a result, two timers are active at the same time
   */
  @SuppressWarnings("GroovyAccessibility")
  def "Check rules engine are not running in parallel on engine restart with new firing after rule execution"() {
    given: "the container is started"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesService = container.getService(RulesService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    RulesEngine engine = null

    and: "some rulesets with a long running rule"
    Ruleset ruleset = new RealmRuleset(
            Constants.MASTER_REALM,
            "Parallel Engine check rules",
            GROOVY,
            getClass().getResource("/org/openremote/test/rules/ParallelEngineCheck.groovy").text)
    Long masterRulesetId = rulesetStorageService.merge(ruleset).id

    and: "assets are added"
    def counterAsset = new ThingAsset("CounterAsset")
            .setId(UniqueIdentifierGenerator.generateId("CounterAsset"))
            .setRealm(Constants.MASTER_REALM)
            .addAttributes(
            new Attribute<>("count", ValueType.INTEGER, null).addMeta(new MetaItem<>(MetaItemType.RULE_STATE)),
    )
    counterAsset = assetStorageService.merge(counterAsset)

    expect: "the rule engines to become available and be running"
    conditions.eventually {
      engine = rulesService.realmEngines.get(Constants.MASTER_REALM)
      assert engine != null
      assert engine.isRunning()
    }

    when: "a mock rules engine is created"
    // We want to stop rules evaluation while doing this setup, otherwise our count of attribute event change is biased
    rulesService.stop(container)
    RulesEngine mockRulesEngine = Spy(engine)
    AtomicBoolean rulesExecuting = new AtomicBoolean(false)
    AtomicBoolean parallelExecutionOccurred = new AtomicBoolean(false)
    mockRulesEngine.fireAllDeployments() >> {
      println("Rules executing")
      if (rulesExecuting.getAndSet(true)) {
        parallelExecutionOccurred.set(true)
      }
      callRealMethod()
      rulesExecuting.set(false)
    }
    rulesService.realmEngines.put(Constants.MASTER_REALM, mockRulesEngine)
    rulesService.start(container)

    and: "An attribute has changed value"
    def receivedEvents = new ArrayList<AttributeEvent>()
    assetProcessingService.addEventInterceptor { EntityManager em, AttributeEvent event ->
      receivedEvents.add(event)
      false
    }


    and: "the engine is restarted while rules are evaluating"
    conditions.eventually {
      assert(rulesExecuting.get())
    }
    mockRulesEngine.stop()
    // We don't want to start immediately, we want the start code to execute while the rules are evaluating
    // but the firing of the timer created by this call to happen after the rules have evaluated
    Thread.sleep(300)
    mockRulesEngine.start()

    then: "A new attribute event is sent"
    conditions.eventually {
      assert receivedEvents.size() == 4
    }
    assert !parallelExecutionOccurred.get(): "Rules engine triggered while rules are still executing"
  }

  /*
   The exact timing issue is:
   - fireTimer triggers and is executing the rules (fireAllDeployments)
   - stop is called, canceling the fireTimer and setting it to null
   - start is called, creating a new fireTimer
   - in this case, that new timer already fires while fireAllDeployments is still running
   As a result, the newly created timer triggers while the original rules execution is still happening
   */
  @SuppressWarnings("GroovyAccessibility")
  def "Check rules engine are not running in parallel on engine restart with new firing during current rule execution"() {
    given: "the container is started"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesService = container.getService(RulesService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    RulesEngine engine = null

    and: "some rulesets with a long running rule"
    Ruleset ruleset = new RealmRuleset(
            Constants.MASTER_REALM,
            "Parallel Engine check rules",
            GROOVY,
            getClass().getResource("/org/openremote/test/rules/ParallelEngineCheck.groovy").text)
    Long masterRulesetId = rulesetStorageService.merge(ruleset).id

    and: "assets are added"
    def counterAsset = new ThingAsset("CounterAsset")
            .setId(UniqueIdentifierGenerator.generateId("CounterAsset"))
            .setRealm(Constants.MASTER_REALM)
            .addAttributes(
            new Attribute<>("count", ValueType.INTEGER, null).addMeta(new MetaItem<>(MetaItemType.RULE_STATE)),
    )
    counterAsset = assetStorageService.merge(counterAsset)

    expect: "the rule engines to become available and be running"
    conditions.eventually {
      engine = rulesService.realmEngines.get(Constants.MASTER_REALM)
      assert engine != null
      assert engine.isRunning()
    }

    when: "a mock rules engine is created"
    // We want to stop rules evaluation while doing this setup, otherwise our count of attribute event change is biased
    rulesService.stop(container)
    RulesEngine mockRulesEngine = Spy(engine)
    AtomicBoolean rulesExecuting = new AtomicBoolean(false)
    AtomicBoolean parallelExecutionOccurred = new AtomicBoolean(false)
    mockRulesEngine.fireAllDeployments() >> {
      println("Rules executing")
      if (rulesExecuting.getAndSet(true)) {
        parallelExecutionOccurred.set(true)
      }
      callRealMethod()
      rulesExecuting.set(false)
    }
    rulesService.realmEngines.put(Constants.MASTER_REALM, mockRulesEngine)
    rulesService.start(container)

    and: "An attribute has changed value"
    def receivedEvents = new ArrayList<AttributeEvent>()
    assetProcessingService.addEventInterceptor { EntityManager em, AttributeEvent event ->
      receivedEvents.add(event)
      false
    }


    and: "the engine is restarted while rules are evaluating"
    conditions.eventually {
      assert(rulesExecuting.get())
    }
    mockRulesEngine.stop()
    // With an immediate stop/start, the rules taking 600 ms to executing and the firing period being 500ms
    // the newly created timer should fire while the initial rules are still evaluating
    mockRulesEngine.start()

    then: "A new attribute event is sent"
    conditions.eventually {
      assert receivedEvents.size() == 4
    }
    assert !parallelExecutionOccurred.get(): "Rules engine triggered while rules are still executing"
  }
}
