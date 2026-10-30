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

import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.rules.RulesEngine
import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.model.Constants
import org.openremote.model.asset.impl.LightAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.rules.RealmRuleset
import org.openremote.model.rules.Ruleset
import org.openremote.model.value.MetaItemType
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class ChildAssetControlRulesTest extends Specification implements ManagerContainerTrait {

  def "Child asset control rule test"() {

    given: "the container environment is started"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesService = container.getService(RulesService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    RulesEngine engine = null

    and: "a light asset and child light assets are added"
    def parentAsset = new LightAsset("Lights")
            .setId(UniqueIdentifierGenerator.generateId("GroupAssetLights"))
            .setRealm(Constants.MASTER_REALM)
            .addAttributes(
            new Attribute<>(LightAsset.ON_OFF).addMeta(new MetaItem<>(MetaItemType.RULE_STATE)),
            new Attribute<>(LightAsset.BRIGHTNESS).addMeta(new MetaItem<>(MetaItemType.RULE_STATE)),
            new Attribute<>(LightAsset.COLOUR_RGB).addMeta(new MetaItem<>(MetaItemType.RULE_STATE)),
            new Attribute<>(LightAsset.COLOUR_TEMPERATURE).addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
    )
    parentAsset = assetStorageService.merge(parentAsset)

    for (int i = 1; i <= 100; i++) {
      def lightAsset = new LightAsset("Light ${i}")
              .setId(UniqueIdentifierGenerator.generateId("Light ${i}"))
              .setParent(parentAsset)

      lightAsset.getAttribute(LightAsset.ON_OFF).ifPresentOrElse(attr -> {
        attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
      }, null)
      lightAsset.getAttribute(LightAsset.BRIGHTNESS).ifPresentOrElse(attr -> {
        attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
      }, null)
      lightAsset.getAttribute(LightAsset.COLOUR_RGB).ifPresentOrElse(attr -> {
        attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
      }, null)
      lightAsset.getAttribute(LightAsset.COLOUR_TEMPERATURE).ifPresentOrElse(attr -> {
        attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
      }, null)
      lightAsset = assetStorageService.merge(lightAsset)
    }

    and: "the child asset control ruleset is added"
    Ruleset ruleset = new RealmRuleset(
            Constants.MASTER_REALM,
            "Child asset control",
            Ruleset.Lang.GROOVY,
            getClass().getResource("/org/openremote/test/rules/ChildAssetControl.groovy").text)
    ruleset = rulesetStorageService.merge(ruleset)

    expect: "the rule engines to become available and be running"
    conditions.eventually {
      engine = rulesService.realmEngines.get(Constants.MASTER_REALM)
      assert engine != null
      assert engine.isRunning()
      assert engine.facts.assetStates.count { it.id == parentAsset.id} == 4
      assert engine.lastFireTimestamp> ruleset.createdOn.toEpochMilli()
      assert engine.deployments.get(ruleset.id) != null
    }

    when: "The on/off attribute of the parent asset is written to"
    assetProcessingService.sendAttributeEvent(
            new AttributeEvent(parentAsset.id, LightAsset.ON_OFF, true)
    )

    then: "the parent asset attribute should be updated"
    conditions.eventually {
      parentAsset = assetStorageService.find(parentAsset.id, true) as LightAsset
      assert parentAsset.getAttribute(LightAsset.ON_OFF).flatMap{it.value}.orElse(false)
    }

    and: "all child lights on/off attributes should now show the same value"
    conditions.eventually {
      for (int i = 1; i <= 100; i++) {
        def light = assetStorageService.find(UniqueIdentifierGenerator.generateId("Light ${i}"), true) as LightAsset
        assert light.getOnOff().orElse(false)
        assert light.getAttribute(LightAsset.ON_OFF).flatMap {
          it.getTimestamp()
        }.orElse(0) == parentAsset.getAttribute(LightAsset.ON_OFF).flatMap {
          it.getTimestamp()
        }.orElse(-1)
      }
    }

    when: "a new child asset is added to the root"
    def lightAsset = new LightAsset("Light 101")
            .setId(UniqueIdentifierGenerator.generateId("Light 101"))
            .setRealm(Constants.MASTER_REALM)

    lightAsset.getAttribute(LightAsset.ON_OFF).ifPresentOrElse(attr -> {
      attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
    }, null)
    lightAsset.getAttribute(LightAsset.BRIGHTNESS).ifPresentOrElse(attr -> {
      attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
    }, null)
    lightAsset.getAttribute(LightAsset.COLOUR_RGB).ifPresentOrElse(attr -> {
      attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
    }, null)
    lightAsset.getAttribute(LightAsset.COLOUR_TEMPERATURE).ifPresentOrElse(attr -> {
      attr.addMeta(new MetaItem<>(MetaItemType.RULE_STATE))
    }, null)
    lightAsset.getAttribute(LightAsset.ON_OFF).ifPresent { it.setValue(true)}
    lightAsset = assetStorageService.merge(lightAsset)

    then: "the child asset states should be loaded into the rule engine"
    conditions.eventually {
      assert engine.facts.assetStates.count { it.id == lightAsset.id} == 4
    }

    when: "the child asset is moved under the group parent"
    lightAsset.setParent(parentAsset)
    lightAsset = assetStorageService.merge(lightAsset)

    then: "the child asset states should be loaded into the rule engine"
    conditions.eventually {
      assert engine.facts.assetStates.count {
        it.id == lightAsset.id && it.parentId == parentAsset.id
      } == 4
    }

    when: "The on/off attribute of the parent asset is written to"
    assetProcessingService.sendAttributeEvent(
            new AttributeEvent(parentAsset.id, LightAsset.ON_OFF, false)
    )

    then: "the parent asset attribute should be updated"
    conditions.eventually {
      parentAsset = assetStorageService.find(parentAsset.id, true) as LightAsset
      assert !parentAsset.getAttribute(LightAsset.ON_OFF).flatMap{it.value}.orElse(true)
    }

    and: "all child lights on/off attributes should now show the same value"
    conditions.eventually {
      for (int i = 1; i <= 101; i++) {
        def light = assetStorageService.find(UniqueIdentifierGenerator.generateId("Light ${i}"), true) as LightAsset
        assert !light.getOnOff().orElse(true)
        assert light.getAttribute(LightAsset.ON_OFF).flatMap {
          it.getTimestamp()
        }.orElse(0) == parentAsset.getAttribute(LightAsset.ON_OFF).flatMap {
          it.getTimestamp()
        }.orElse(-1)
      }
    }
  }
}
