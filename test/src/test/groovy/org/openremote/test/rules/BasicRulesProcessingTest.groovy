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

import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.rules.RulesEngine
import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.impl.RoomAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.rules.AssetRuleset
import org.openremote.model.rules.RealmRuleset
import org.openremote.model.rules.Ruleset
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Ignore
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static org.openremote.model.rules.RulesetStatus.*
import static org.openremote.setup.integration.ManagerTestSetup.*
import static org.openremote.test.rules.BasicRulesImport.assertRulesFired

class BasicRulesProcessingTest extends Specification implements ManagerContainerTrait {

  def "Check firing when deploying a new ruleset"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def rulesService = container.getService(RulesService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)

    and: "some test rulesets have been imported"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    expect: "the rules engines to be ready"
    conditions.eventually {
      assert rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    when: "a LHS filtering test rule definition is loaded into the Smart Building asset"
    def assetRuleset = new AssetRuleset(
            managerTestSetup.smartBuildingId,
            "Some Smart Building asset rules",
            Ruleset.Lang.GROOVY,
            getClass().getResource("/org/openremote/test/rules/BasicSmartHomeMatchAllAssetStates.groovy").text)
    rulesetStorageService.merge(assetRuleset)
    RulesEngine smartHomeEngine = null

    then: "the Smart Building rule engine should have ben created, loaded the new rule definition and facts and started"
    conditions.eventually {
      smartHomeEngine = rulesService.assetEngines.get(managerTestSetup.smartBuildingId)
      assert smartHomeEngine != null
      assert smartHomeEngine.isRunning()
      assert smartHomeEngine.deployments.size() == 1
      assert smartHomeEngine.deployments.values().any({
        it.name == "Some Smart Building asset rules" && it.status == DEPLOYED
      })
    }

    and: "the new rule engine is fully initialized"
    conditions.eventually {
      assert smartHomeEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_BUILDING
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.globalEngine, ["All"])
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, ["All"])
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment2Engine, ["All"])
      assertRulesFired(rulesImport.apartment3Engine, 0)
      assertRulesFired(smartHomeEngine, 7)
      assertRulesFired(smartHomeEngine, [
        "Living Room All",
        "Kitchen All",
        "Kitchen Number Attributes",
        "Asset Type Room",
        "Boolean attributes",
        "String attributes",
        "Number value types"
      ])
    }

    when: "an attribute event occurs"
    rulesImport.resetRulesFired(smartHomeEngine)
    def apartment2LivingRoomPresenceDetectedChange = new AttributeEvent(
            managerTestSetup.apartment2LivingroomId, "presenceDetected", true
    )
    assetProcessingService.sendAttributeEvent(apartment2LivingRoomPresenceDetectedChange)

    then: "the engines in scope should have fired"
    conditions.eventually {
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.globalEngine, ["All"])
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, ["All"])
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment2Engine, ["All"])
      assertRulesFired(rulesImport.apartment3Engine, 0)
      assertRulesFired(smartHomeEngine, 7)
      assertRulesFired(smartHomeEngine, [
        "Living Room All",
        "Kitchen All",
        "Kitchen Number Attributes",
        "Asset Type Room",
        "Boolean Attributes",
        "String attributes",
        "Number value types"
      ])
    }
  }

  def "Handle attribute event with no meta, asset create, update, delete"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def rulesService = container.getService(RulesService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)

    and: "some test rulesets have been imported"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    expect: "the rules engines to be ready"
    conditions.eventually {
      assert rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    when: "a Kitchen room asset is inserted into apartment that contains a RULE_STATE = true meta flag"
    def apartment2 = assetStorageService.find(managerTestSetup.apartment2Id)
    def asset = new RoomAsset("Kitchen")
            .setParent(apartment2)
            .addOrReplaceAttributes(
            new Attribute<>("testString", ValueType.TEXT, "test")
            .addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.RULE_STATE, true)
                    )
    )
    asset = assetStorageService.merge(asset)

    then: "the rules engines should have executed at least once"
    conditions.eventually {
      assert rulesImport.globalEngine.lastFireTimestamp> 0
      assert rulesImport.masterEngine.lastFireTimestamp> 0
      assert rulesImport.realmBuildingEngine.lastFireTimestamp> 0
      assert rulesImport.apartment2Engine.lastFireTimestamp> 0
      assert rulesImport.apartment3Engine.lastFireTimestamp> 0
    }

    then: "after a few seconds the engines in scope should have facts and rules should have fired"
    conditions.eventually {
      assert rulesService.attributeEvents.size() == DEMO_RULE_STATES_GLOBAL + 1
      assert rulesImport.globalEngine.facts.assetStates.size() == DEMO_RULE_STATES_GLOBAL + 1
      assert rulesImport.masterEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_OFFICE
      assert rulesImport.realmBuildingEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_BUILDING + 1
      assert rulesImport.apartment2Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_2 + 1
      assert rulesImport.apartment3Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_3
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.globalEngine, ["All"])
      assertRulesFired(rulesImport.masterEngine, 1)
      assertRulesFired(rulesImport.masterEngine, ["All"])
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, ["All"])
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment2Engine, ["All"])
      assertRulesFired(rulesImport.apartment3Engine, 0)
    }

    when: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "the Kitchen room asset is modified to add a new attribute but RULE_STATE = true meta is not changed"
    def globalLastFireTimestamp = rulesImport.globalEngine.lastFireTimestamp
    def masterLastFireTimestamp = rulesImport.masterEngine.lastFireTimestamp
    def realmALastFireTimestamp = rulesImport.realmBuildingEngine.lastFireTimestamp
    def apartment2LastFireTimestamp = rulesImport.apartment2Engine.lastFireTimestamp
    def apartment3LastFireTimestamp = rulesImport.apartment3Engine.lastFireTimestamp
    asset.addOrReplaceAttributes(
            new Attribute<>("testString", ValueType.TEXT, "test")
            .addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.RULE_STATE, true)
                    ),
            new Attribute<>("testInteger", ValueType.NUMBER, 0d)
    )
    asset = assetStorageService.merge(asset)

    then: "the rules engines should have executed at least one more time"
    conditions.eventually {
      assert rulesImport.globalEngine.lastFireTimestamp> globalLastFireTimestamp
      assert rulesImport.masterEngine.lastFireTimestamp> masterLastFireTimestamp
      assert rulesImport.realmBuildingEngine.lastFireTimestamp> realmALastFireTimestamp
      assert rulesImport.apartment2Engine.lastFireTimestamp> apartment2LastFireTimestamp
      assert rulesImport.apartment3Engine.lastFireTimestamp> apartment3LastFireTimestamp
    }

    then: "no rules should have fired"
    conditions.eventually {
      assert rulesService.attributeEvents.size() == DEMO_RULE_STATES_GLOBAL + 1
      assert rulesImport.globalEngine.facts.assetStates.size() == DEMO_RULE_STATES_GLOBAL + 1
      assert rulesImport.masterEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_OFFICE
      assert rulesImport.realmBuildingEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_BUILDING + 1
      assert rulesImport.apartment2Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_2 + 1
      assert rulesImport.apartment3Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_3
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.masterEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment3Engine, 0)
    }

    when: "the Kitchen room asset is modified to set the RULE_STATE to false"
    rulesImport.resetRulesFired()
    asset.addOrReplaceAttributes(
            new Attribute<>("testString", ValueType.TEXT, "test")
            .addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.RULE_STATE, false)
                    ),
            new Attribute<>("testInteger", ValueType.NUMBER, 0d)
    )
    asset = assetStorageService.merge(asset)

    then: "the facts should be removed from the rule engines and rules should have fired"
    conditions.eventually {
      assert rulesService.attributeEvents.size() == DEMO_RULE_STATES_GLOBAL
      assert rulesImport.globalEngine.facts.assetStates.size() == DEMO_RULE_STATES_GLOBAL
      assert rulesImport.masterEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_OFFICE
      assert rulesImport.realmBuildingEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_BUILDING
      assert rulesImport.apartment2Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_2
      assert rulesImport.apartment3Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_3
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.globalEngine, ["All"])
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, ["All"])
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment2Engine, ["All"])
      assertRulesFired(rulesImport.apartment3Engine, 0)
    }

    when: "the Kitchen room asset is modified to set all attributes to RULE_STATE = true"
    rulesImport.resetRulesFired()
    asset.addOrReplaceAttributes(
            new Attribute<>("testString", ValueType.TEXT, "test")
            .addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.RULE_STATE, true)
                    ),
            new Attribute<>("testInteger", ValueType.NUMBER, 0d)
            .addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.RULE_STATE, true)
                    )
    )
    asset = assetStorageService.merge(asset)

    then: "the facts should be added to the rule engines and rules should have fired"
    conditions.eventually {
      assert rulesService.attributeEvents.size() == DEMO_RULE_STATES_GLOBAL + 2
      assert rulesImport.globalEngine.facts.assetStates.size() == DEMO_RULE_STATES_GLOBAL + 2
      assert rulesImport.masterEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_OFFICE
      assert rulesImport.realmBuildingEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_BUILDING + 2
      assert rulesImport.apartment2Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_2 + 2
      assert rulesImport.apartment3Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_3
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.globalEngine, ["All"])
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, ["All"])
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment2Engine, ["All"])
      assertRulesFired(rulesImport.apartment3Engine, 0)
    }

    when: "the Kitchen room asset is deleted"
    rulesImport.resetRulesFired()
    assetStorageService.delete([asset.getId()], false)

    then: "the facts should be removed from the rule engines and rules should have fired"
    conditions.eventually {
      assert rulesService.attributeEvents.size() == DEMO_RULE_STATES_GLOBAL
      assert rulesImport.globalEngine.facts.assetStates.size() == DEMO_RULE_STATES_GLOBAL
      assert rulesImport.masterEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_OFFICE
      assert rulesImport.realmBuildingEngine.facts.assetStates.size() == DEMO_RULE_STATES_SMART_BUILDING
      assert rulesImport.apartment2Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_2
      assert rulesImport.apartment3Engine.facts.assetStates.size() == DEMO_RULE_STATES_APARTMENT_3
      assertRulesFired(rulesImport.globalEngine, 1)
      assertRulesFired(rulesImport.globalEngine, ["All"])
      assertRulesFired(rulesImport.realmBuildingEngine, 1)
      assertRulesFired(rulesImport.realmBuildingEngine, ["All"])
      assertRulesFired(rulesImport.apartment2Engine, 1)
      assertRulesFired(rulesImport.apartment2Engine, ["All"])
      assertRulesFired(rulesImport.apartment3Engine, 0)
    }
  }

  // TODO: Decide if continue on error should be supported
  @Ignore
  def "Stop processing when engine in error state"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def rulesService = container.getService(RulesService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)

    and: "some test rulesets have been imported"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    expect: "the rules engines to be ready"
    conditions.eventually {
      assert rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    when: "a broken RHS rule is loaded into the building engine"
    def ruleset = new RealmRuleset(
            keycloakTestSetup.realmBuilding.name,
            "Some broken test rules",
            Ruleset.Lang.GROOVY,
            getClass().getResource("/org/openremote/test/rules/BasicBrokenRules.groovy").text)
    rulesetStorageService.merge(ruleset)

    then: "the building engine should not run and the rule engine status should indicate the issue"
    conditions.eventually {
      assert rulesImport.realmBuildingEngine.deployments.size() == 2
      assert !rulesImport.realmBuildingEngine.running
      assert rulesImport.realmBuildingEngine.deployments.values().any({
        it.name == "Some building realm demo rules" && it.status == READY
      })
      assert rulesImport.realmBuildingEngine.deployments.values().any({
        it.name == "Some broken test rules" && it.status == COMPILATION_ERROR && it.error instanceof RuntimeException
      })
    }

    when: "an attribute event occurs"
    def globalLastFireTimestamp = rulesImport.globalEngine.lastFireTimestamp
    def masterLastFireTimestamp = rulesImport.masterEngine.lastFireTimestamp
    def realmALastFireTimestamp = rulesImport.realmBuildingEngine.lastFireTimestamp
    def apartment2LastFireTimestamp = rulesImport.apartment2Engine.lastFireTimestamp
    def apartment3LastFireTimestamp = rulesImport.apartment3Engine.lastFireTimestamp
    def apartment2LivingRoomPresenceDetectedChange = new AttributeEvent(
            managerTestSetup.apartment2LivingroomId, "presenceDetected", true
    )
    assetProcessingService.sendAttributeEvent(apartment2LivingRoomPresenceDetectedChange)

    then: "the rules engines should have executed at least one more time"
    conditions.eventually {
      assert rulesImport.globalEngine.lastFireTimestamp> globalLastFireTimestamp
      assert rulesImport.masterEngine.lastFireTimestamp> masterLastFireTimestamp
      assert rulesImport.realmBuildingEngine.lastFireTimestamp> realmALastFireTimestamp
      assert rulesImport.apartment2Engine.lastFireTimestamp> apartment2LastFireTimestamp
      assert rulesImport.apartment3Engine.lastFireTimestamp> apartment3LastFireTimestamp
    }
  }
}
