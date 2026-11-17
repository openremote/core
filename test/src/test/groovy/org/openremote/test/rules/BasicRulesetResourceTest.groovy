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

import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.manager.setup.SetupService
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.model.rules.*
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import jakarta.ws.rs.WebApplicationException

import static org.openremote.model.util.MapAccess.getString
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.rules.Ruleset.Lang.GROOVY
import static org.openremote.model.rules.Ruleset.Lang.FLOW
import static org.openremote.model.rules.Ruleset.Lang.JAVASCRIPT
import static org.openremote.model.rules.Ruleset.Lang.JSON

class BasicRulesetResourceTest extends Specification implements ManagerContainerTrait {

  def "Access rules as superuser"() {
    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def rulesService = container.getService(RulesService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "some test rulesets have been imported"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

    expect: "the rules engines to be ready"
    conditions.eventually {
      rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    /* ############################################## READ ####################################### */

    when: "the global rules are retrieved"
    def ruleDefinitions = rulesetResource.getGlobalRulesets(null, null, false)

    then: "result should match"
    ruleDefinitions.length == 2
    ruleDefinitions[0].name == "Some global demo rules"
    ruleDefinitions[0].lang == GROOVY
    ruleDefinitions[0].rules == null // Don't retrieve the (large) rules data when getting a list of rule definitions

    when: "some realm rules are retrieved"
    ruleDefinitions = rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmMaster.name, null, false)

    then: "result should match"
    ruleDefinitions.length == 1
    ruleDefinitions[0].name == "Some master realm demo rules"
    ruleDefinitions[0].lang == GROOVY

    when: "some realm rules in a non-authenticated realm are retrieved"
    ruleDefinitions = rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmBuilding.name, null, false)

    then: "result should match"
    ruleDefinitions.length == 1
    ruleDefinitions[0].name == "Some building realm demo rules"
    ruleDefinitions[0].enabled

    when: "some asset rules are retrieved"
    ruleDefinitions = rulesetResource.getAssetRulesets(null, managerTestSetup.apartment2Id, null, false)

    then: "result should match"
    ruleDefinitions.length == 1
    ruleDefinitions[0].name == "Some apartment 2 demo rules"
    ruleDefinitions[0].lang == GROOVY
    (ruleDefinitions[0] as AssetRuleset).isShowOnList()

    /* ############################################## WRITE ####################################### */

    when: "global ruleset is created"
    def globalRuleset = new GlobalRuleset("Test global definition", GROOVY, "SomeRulesCode")
    rulesetResource.createGlobalRuleset(null, globalRuleset)
    def rulesetId = rulesetResource.getGlobalRulesets(null, null, false)[2].id
    globalRuleset = rulesetResource.getGlobalRuleset(null, rulesetId)
    def lastModified = globalRuleset.lastModified

    then: "result should match"
    globalRuleset.id == rulesetId
    globalRuleset.version == 0
    globalRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    globalRuleset.name == "Test global definition"
    globalRuleset.rules == "SomeRulesCode"
    globalRuleset.lang == GROOVY

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.globalEngine.get().deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == globalRuleset.version
    }

    when: "a global ruleset is updated"
    globalRuleset.name = "Renamed test global definition"
    globalRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateGlobalRuleset(null, rulesetId, globalRuleset)
    globalRuleset = rulesetResource.getGlobalRuleset(null, rulesetId)

    then: "result should match"
    globalRuleset.id == rulesetId
    globalRuleset.version> 0
    globalRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    globalRuleset.lastModified.toEpochMilli() > lastModified.toEpochMilli()
    globalRuleset.name == "Renamed test global definition"
    globalRuleset.rules == "SomeRulesCodeModified"

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.globalEngine.get().deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == globalRuleset.version
    }

    when: "a global ruleset is deleted"
    rulesetResource.deleteGlobalRuleset(null, rulesetId)
    globalRuleset = rulesetResource.getGlobalRuleset(null, rulesetId)

    then: "the result should be not found"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    and: "the ruleset should be removed from the engine"
    conditions.eventually {
      assert rulesService.globalEngine.get() != null
      def deployment = rulesService.globalEngine.get().deployments.get(rulesetId)
      assert deployment == null
    }

    when: "a non-existent global ruleset is updated"
    rulesetResource.updateGlobalRuleset(null, 1234567890l, globalRuleset)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    when: "a realm ruleset is created in the authenticated realm"
    def realmRuleset = new RealmRuleset(MASTER_REALM, "Test realm definition", GROOVY, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)
    rulesetId = rulesetResource.getRealmRulesets(null, MASTER_REALM, null, false)[1].id
    realmRuleset = rulesetResource.getRealmRuleset(null, rulesetId)
    lastModified = realmRuleset.lastModified

    then: "result should match"
    realmRuleset.id == rulesetId
    realmRuleset.version == 0
    realmRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    realmRuleset.name == "Test realm definition"
    realmRuleset.rules == "SomeRulesCode"
    realmRuleset.realm == keycloakTestSetup.realmMaster.name

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(MASTER_REALM).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == realmRuleset.version
    }

    when: "a realm ruleset is updated"
    realmRuleset.name = "Renamed test realm definition"
    realmRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateRealmRuleset(null, rulesetId, realmRuleset)
    realmRuleset = rulesetResource.getRealmRuleset(null, rulesetId)

    then: "result should match"
    realmRuleset.id == rulesetId
    realmRuleset.version == 1
    realmRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    realmRuleset.lastModified.toEpochMilli() > lastModified.toEpochMilli()
    realmRuleset.name == "Renamed test realm definition"
    realmRuleset.rules == "SomeRulesCodeModified"
    realmRuleset.realm == keycloakTestSetup.realmMaster.name

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(MASTER_REALM).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == realmRuleset.version
    }

    when: "a realm ruleset is updated with an invalid realm"
    realmRuleset.realm = "thisdoesnotexist"
    rulesetResource.updateRealmRuleset(null, rulesetId, realmRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a realm ruleset is updated with an invalid id"
    realmRuleset.realm = MASTER_REALM
    realmRuleset.id = 1234567890l
    rulesetResource.updateRealmRuleset(null, rulesetId, realmRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a non-existent realm ruleset is updated"
    rulesetResource.updateRealmRuleset(null, 1234567890l, realmRuleset)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    when: "a realm ruleset is deleted"
    rulesetResource.deleteRealmRuleset(null, rulesetId)
    rulesetResource.getRealmRuleset(null, rulesetId)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    and: "the ruleset should be removed from the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(MASTER_REALM).deployments.get(rulesetId)
      assert deployment == null
    }

    when: "a realm ruleset is created in a non-authenticated realm"
    realmRuleset = new RealmRuleset(keycloakTestSetup.realmBuilding.name, "Test realm definition", GROOVY, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)
    rulesetId = rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmBuilding.name, null, false)[1].id
    realmRuleset = rulesetResource.getRealmRuleset(null, rulesetId)
    lastModified = realmRuleset.lastModified

    then: "result should match"
    realmRuleset.id == rulesetId
    realmRuleset.version == 0
    realmRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    realmRuleset.name == "Test realm definition"
    realmRuleset.rules == "SomeRulesCode"
    realmRuleset.realm == keycloakTestSetup.realmBuilding.name

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(keycloakTestSetup.realmBuilding.name).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == realmRuleset.version
    }

    when: "an asset ruleset is created in the authenticated realm"
    def assetRuleset = new AssetRuleset(managerTestSetup.smartOfficeId, "Test asset definition", GROOVY, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)
    rulesetId = rulesetResource.getAssetRulesets(null, managerTestSetup.smartOfficeId, null, false)[0].id
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
    lastModified = assetRuleset.lastModified

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 0
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.name == "Test asset definition"
    assetRuleset.rules == "SomeRulesCode"
    assetRuleset.assetId == managerTestSetup.smartOfficeId

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.smartOfficeId).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }

    when: "an asset ruleset is updated"
    assetRuleset.name = "Renamed test asset definition"
    assetRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 1
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.lastModified.toEpochMilli() > lastModified.toEpochMilli()
    assetRuleset.name == "Renamed test asset definition"
    assetRuleset.rules == "SomeRulesCodeModified"
    assetRuleset.assetId == managerTestSetup.smartOfficeId

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.smartOfficeId).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }

    when: "an asset ruleset is updated with an invalid asset ID"
    assetRuleset.assetId = "thisdoesnotexist"
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "an asset ruleset is updated with an invalid id"
    assetRuleset.assetId = managerTestSetup.smartOfficeId
    assetRuleset.id = 1234567890l
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a non-existent asset ruleset is updated"
    rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    when: "an asset ruleset is deleted"
    rulesetResource.deleteAssetRuleset(null, rulesetId)
    rulesetResource.getAssetRuleset(null, rulesetId)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    and: "the ruleset should be removed from the engine"
    conditions.eventually {
      assert rulesService.assetEngines.get(managerTestSetup.smartOfficeId) == null
    }

    when: "an asset ruleset is created in a non-authenticated realm"
    assetRuleset = new AssetRuleset(managerTestSetup.apartment2Id, "Test asset definition", GROOVY, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)
    rulesetId = rulesetResource.getAssetRulesets(null, managerTestSetup.apartment2Id, null, false)[1].id
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
    lastModified = assetRuleset.lastModified

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 0
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.name == "Test asset definition"
    assetRuleset.rules == "SomeRulesCode"
    assetRuleset.assetId == managerTestSetup.apartment2Id

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.apartment2Id).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }
  }

  def "Access rules as testuser1"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def rulesService = container.getService(RulesService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "some imported rulesets"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    and: "an authenticated test user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

    expect: "the rules engines to be ready"
    conditions.eventually {
      rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    /* ############################################## READ ####################################### */

    when: "the global rules are retrieved"
    rulesetResource.getGlobalRulesets(null, null, false)

    then: "access should be forbidden"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "some realm rules are retrieved"
    def ruleDefinitions = rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmMaster.name, null, false)

    then: "result should match"
    ruleDefinitions.length == 1
    ruleDefinitions[0].name == "Some master realm demo rules"

    when: "some realm rules in a non-authenticated realm are retrieved"
    rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmBuilding.name, null, false)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "some asset rules in a non-authenticated realm are retrieved"
    rulesetResource.getAssetRulesets(null, managerTestSetup.apartment2Id, null, false)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    /* ############################################## WRITE ####################################### */

    when: "a global rule is created"
    def ruleDefinition = new GlobalRuleset("Test definition", GROOVY, "SomeRulesCode")
    rulesetResource.createGlobalRuleset(null, ruleDefinition)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "a global rule is updated"
    rulesetResource.updateGlobalRuleset(null, 1234567890l, ruleDefinition)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "a global rule is deleted"
    rulesetResource.deleteGlobalRuleset(null, 1234567890l)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "a realm ruleset is created in the authenticated realm"
    def realmRuleset = new RealmRuleset(MASTER_REALM, "Test realm definition", Ruleset.Lang.JSON, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)
    def rulesetId = rulesetResource.getRealmRulesets(null, MASTER_REALM, null, false)[1].id
    realmRuleset = rulesetResource.getRealmRuleset(null, rulesetId)
    def lastModified = realmRuleset.lastModified

    then: "result should match"
    realmRuleset.id == rulesetId
    realmRuleset.version == 0
    realmRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    realmRuleset.name == "Test realm definition"
    realmRuleset.rules == "SomeRulesCode"
    realmRuleset.realm == MASTER_REALM

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(MASTER_REALM).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == realmRuleset.version
    }

    when: "a realm ruleset is updated"
    realmRuleset.name = "Renamed test realm definition"
    realmRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateRealmRuleset(null, rulesetId, realmRuleset)
    realmRuleset = rulesetResource.getRealmRuleset(null, rulesetId)

    then: "result should match"
    realmRuleset.id == rulesetId
    realmRuleset.version == 1
    realmRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    realmRuleset.lastModified.toEpochMilli() > lastModified.toEpochMilli()
    realmRuleset.name == "Renamed test realm definition"
    realmRuleset.rules == "SomeRulesCodeModified"
    realmRuleset.realm == keycloakTestSetup.realmMaster.name

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(MASTER_REALM).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == realmRuleset.version
    }

    when: "a realm ruleset is updated with an invalid realm"
    realmRuleset.realm = "thisdoesnotexist"
    rulesetResource.updateRealmRuleset(null, rulesetId, realmRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a realm ruleset is updated with an invalid id"
    realmRuleset.realm = keycloakTestSetup.realmMaster.name
    realmRuleset.id = 1234567890l
    rulesetResource.updateRealmRuleset(null, rulesetId, realmRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a non-existent realm ruleset is updated"
    rulesetResource.updateRealmRuleset(null, 1234567890l, realmRuleset)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    when: "a realm ruleset is deleted"
    rulesetResource.deleteRealmRuleset(null, rulesetId)
    rulesetResource.getRealmRuleset(null, rulesetId)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    and: "the ruleset should be removed from the engine"
    conditions.eventually {
      def deployment = rulesService.realmEngines.get(MASTER_REALM).deployments.get(rulesetId)
      assert deployment == null
    }

    when: "a groovy realm ruleset is created"
    realmRuleset = new RealmRuleset(MASTER_REALM, "Test realm definition", GROOVY, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is created in a non-authenticated realm"
    realmRuleset = new RealmRuleset(keycloakTestSetup.realmBuilding.name, "Test realm definition", FLOW, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "an asset ruleset is created in the authenticated realm"
    def assetRuleset = new AssetRuleset(managerTestSetup.smartOfficeId, "Test asset definition", FLOW, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)
    rulesetId = rulesetResource.getAssetRulesets(null, managerTestSetup.smartOfficeId, null, false)[0].id
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
    lastModified = assetRuleset.lastModified

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 0
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.name == "Test asset definition"
    assetRuleset.rules == "SomeRulesCode"
    assetRuleset.assetId == managerTestSetup.smartOfficeId

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.smartOfficeId).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }

    when: "an asset ruleset is updated"
    assetRuleset.name = "Renamed test asset definition"
    assetRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 1
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.lastModified.toEpochMilli() > lastModified.toEpochMilli()
    assetRuleset.name == "Renamed test asset definition"
    assetRuleset.rules == "SomeRulesCodeModified"
    assetRuleset.assetId == managerTestSetup.smartOfficeId

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.smartOfficeId).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }

    when: "an asset ruleset is updated with an invalid asset ID"
    assetRuleset.assetId = "thisdoesnotexist"
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      true
    }

    when: "an asset ruleset is updated with an invalid id"
    assetRuleset.assetId = managerTestSetup.smartOfficeId
    assetRuleset.id = 1234567890l
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      true
    }

    when: "a non-existent asset ruleset is updated"
    rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    when: "an asset ruleset is deleted"
    rulesetResource.deleteAssetRuleset(null, rulesetId)
    rulesetResource.getAssetRuleset(null, rulesetId)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    and: "the ruleset should reach the engine"
    conditions.eventually {
      assert rulesService.assetEngines.get(managerTestSetup.smartOfficeId) == null
    }

    when: "an asset ruleset is created in a non-authenticated realm"
    assetRuleset = new AssetRuleset(managerTestSetup.apartment2Id, "Test asset definition", JSON, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }
  }

  def "Access rules as testuser2"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def rulesService = container.getService(RulesService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "some imported rulesets"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    and: "an authenticated test user"
    def accessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser2",
            "testuser2"
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(RulesResource.class)

    expect: "the rules engines to be ready"
    conditions.eventually {
      rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    /* ############################################## READ ####################################### */

    when: "the global rules are retrieved"
    rulesetResource.getGlobalRulesets(null, null, false)

    then: "access should be forbidden"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "some realm rules in a non-authenticated realm are retrieved"
    rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmMaster.name, null, false)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "some realm rules in the authenticated realm are retrieved by user without rules read role"
    def rulesets = rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmBuilding.name, null, false)

    then: "no rulesets should be returned"
    rulesets.length == 0

    when: "some asset rules in the authenticated realm are retrieved by the user without rules read role"
    rulesets = rulesetResource.getAssetRulesets(null, managerTestSetup.apartment2Id, null, false)

    then: "no rulesets should be returned"
    rulesets.length == 0

    /* ############################################## WRITE ####################################### */

    when: "a global rule is created"
    def ruleDefinition = new GlobalRuleset("Test definition", GROOVY, "SomeRulesCode")
    rulesetResource.createGlobalRuleset(null, ruleDefinition)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a global rule is updated"
    rulesetResource.updateGlobalRuleset(null, 1234567890l, ruleDefinition)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a global rule is deleted"
    rulesetResource.deleteGlobalRuleset(null, 1234567890l)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is created in the authenticated realm"
    def realmRuleset = new RealmRuleset(keycloakTestSetup.realmBuilding.name, "Test realm definition", GROOVY, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is updated"
    rulesetResource.updateRealmRuleset(null, 1234567890l, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is deleted"
    rulesetResource.deleteRealmRuleset(null, 1234567890l)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is created in a non-authenticated realm"
    realmRuleset = new RealmRuleset(keycloakTestSetup.realmCity.name, "Test realm definition", GROOVY, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is created in the authenticated realm"
    def assetRuleset = new AssetRuleset(keycloakTestSetup.realmBuilding.name, "Test asset definition", GROOVY, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is updated"
    rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is deleted"
    rulesetResource.deleteAssetRuleset(null, 1234567890l)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is created in a non-authenticated realm"
    assetRuleset = new AssetRuleset(keycloakTestSetup.realmCity.name, "Test asset definition", GROOVY, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }
  }

  def "Access rules as testuser3"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def rulesService = container.getService(RulesService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "some imported rulesets"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    and: "an authenticated test user"
    def accessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(RulesResource.class)

    expect: "the rules engines to be ready"
    conditions.eventually {
      rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    /* ############################################## READ ####################################### */

    when: "the global rules are retrieved"
    rulesetResource.getGlobalRulesets(null, null, false)

    then: "access should be forbidden"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "some realm rules in a non-authenticated realm are retrieved"
    rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmMaster.name, null, false)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "some realm rules in the authenticated realm are retrieved by the restricted user"
    def rulesets = rulesetResource.getRealmRulesets(null, keycloakTestSetup.realmBuilding.name, null, false)

    then: "no rulesets should be returned"
    rulesets.length == 0

    when: "some asset rules of a protected assigned asset are retrieved"
    def ruleDefinitions = rulesetResource.getAssetRulesets(null, managerTestSetup.apartment1Id, null, false)

    then: "result should match"
    ruleDefinitions.length == 1
    ruleDefinitions[0].name == "Some apartment 1 demo rules"

    when: "some asset rules of a protected but not assigned asset are retrieved"
    rulesetResource.getAssetRulesets(null, managerTestSetup.apartment2Id, null, false)

    then: "no rulesets should be returned"
    rulesets.length == 0

    /* ############################################## WRITE ####################################### */

    when: "a global rule is created"
    def ruleDefinition = new GlobalRuleset("Test definition", GROOVY, "SomeRulesCode")
    rulesetResource.createGlobalRuleset(null, ruleDefinition)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a global rule is updated"
    rulesetResource.updateGlobalRuleset(null, 1234567890l, ruleDefinition)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a global rule is deleted"
    rulesetResource.deleteGlobalRuleset(null, 1234567890l)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is created in the authenticated realm"
    def realmRuleset = new RealmRuleset(keycloakTestSetup.realmBuilding.name, "Test realm definition", GROOVY, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "a realm ruleset is updated"
    rulesetResource.updateRealmRuleset(null, 1234567890l, realmRuleset)

    then: "result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    when: "a realm ruleset is deleted"
    rulesetResource.deleteRealmRuleset(null, 1234567890l)
    rulesetResource.getRealmRuleset(null, 1234567890l)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    when: "a realm ruleset is created in a non-authenticated realm"
    realmRuleset = new RealmRuleset(keycloakTestSetup.realmCity.name, "Test realm definition", FLOW, "SomeRulesCode")
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "an asset ruleset is created in the authenticated realm"
    def assetRuleset = new AssetRuleset(
            managerTestSetup.apartment1Id,
            "Test asset definition",
            FLOW, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)
    def rulesetId = rulesetResource.getAssetRulesets(null, managerTestSetup.apartment1Id, null, false)[1].id
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)
    def lastModified = assetRuleset.lastModified

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 0
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    lastModified.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.name == "Test asset definition"
    assetRuleset.rules == "SomeRulesCode"
    assetRuleset.assetId == managerTestSetup.apartment1Id

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.apartment1Id).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }

    when: "an asset ruleset is updated"
    assetRuleset.name = "Renamed test asset definition"
    assetRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)
    assetRuleset = rulesetResource.getAssetRuleset(null, rulesetId)

    then: "result should match"
    assetRuleset.id == rulesetId
    assetRuleset.version == 1
    assetRuleset.createdOn.toEpochMilli() <System.currentTimeMillis()
    assetRuleset.lastModified.toEpochMilli() > lastModified.toEpochMilli()
    assetRuleset.name == "Renamed test asset definition"
    assetRuleset.rules == "SomeRulesCodeModified"
    assetRuleset.assetId == managerTestSetup.apartment1Id

    and: "the ruleset should reach the engine"
    conditions.eventually {
      def deployment = rulesService.assetEngines.get(managerTestSetup.apartment1Id).deployments.get(rulesetId)
      assert deployment != null
      assert deployment.ruleset.version == assetRuleset.version
    }

    when: "an asset ruleset is updated with a changed asset ID"
    assetRuleset.assetId = managerTestSetup.apartment3Id
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      true
    }

    when: "an asset ruleset is updated with a changed invalid asset ID"
    assetRuleset.assetId = "thisdoesnotexist"
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      true
    }

    when: "an asset ruleset is updated with an invalid id"
    assetRuleset.assetId = managerTestSetup.apartment1Id
    assetRuleset.id = 1234567890l
    rulesetResource.updateAssetRuleset(null, rulesetId, assetRuleset)

    then: "the request should be bad"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      true
    }

    when: "a non-existent asset ruleset is updated"
    rulesetResource.updateAssetRuleset(null, 1234567890l, assetRuleset)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    when: "an asset ruleset is deleted"
    rulesetResource.deleteAssetRuleset(null, rulesetId)
    rulesetResource.getAssetRuleset(null, rulesetId)

    then: "the result should be not found"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      true
    }

    and: "the ruleset should be removed from the engine"
    conditions.eventually {
      assert rulesService.assetEngines.get(managerTestSetup.apartment1Id) == null
    }

    when: "an asset ruleset is created in the authenticated realm but on a forbidden asset"
    assetRuleset = new AssetRuleset(managerTestSetup.apartment3Id, "Test asset definition", FLOW, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }

    when: "an asset ruleset is created in a non-authenticated realm"
    assetRuleset = new AssetRuleset(managerTestSetup.smartOfficeId, "Test asset definition", FLOW, "SomeRulesCode")
    rulesetResource.createAssetRuleset(null, assetRuleset)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      true
    }
  }

  def "JavaScript ruleset create requests are rejected"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

    when: "a global JavaScript ruleset is created"
    rulesetResource.createGlobalRuleset(null, new GlobalRuleset("Test JS global definition", JAVASCRIPT, "SomeRulesCode"))

    then: "the request should be rejected"
    WebApplicationException ex = thrown()
    ex.response.status == 400

    when: "a realm JavaScript ruleset is created"
    rulesetResource.createRealmRuleset(null, new RealmRuleset(MASTER_REALM, "Test JS realm definition", JAVASCRIPT, "SomeRulesCode"))

    then: "the request should be rejected"
    ex = thrown()
    ex.response.status == 400

    when: "an asset JavaScript ruleset is created"
    rulesetResource.createAssetRuleset(null, new AssetRuleset(managerTestSetup.smartOfficeId, "Test JS asset definition", JAVASCRIPT, "SomeRulesCode"))

    then: "the request should be rejected"
    ex = thrown()
    ex.response.status == 400
  }

  def "JavaScript ruleset update requests are rejected"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

    and: "existing rulesets are stored directly"
    def globalRuleset = rulesetStorageService.merge(new GlobalRuleset("Test global definition", JSON, "SomeRulesCode"))
    def realmRuleset = rulesetStorageService.merge(new RealmRuleset(MASTER_REALM, "Test JS realm definition", JAVASCRIPT, "SomeRulesCode"))
    def assetRuleset = rulesetStorageService.merge(new AssetRuleset(managerTestSetup.smartOfficeId, "Test JS asset definition", JAVASCRIPT, "SomeRulesCode"))

    when: "a stored global ruleset is updated to JavaScript"
    def updatedGlobalRuleset = rulesetResource.getGlobalRuleset(null, globalRuleset.id)
    updatedGlobalRuleset.lang = JAVASCRIPT
    updatedGlobalRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateGlobalRuleset(null, globalRuleset.id, updatedGlobalRuleset)

    then: "the request should be rejected"
    WebApplicationException ex = thrown()
    ex.response.status == 400

    when: "a stored JavaScript realm ruleset is updated to a non-JavaScript language"
    def updatedRealmRuleset = rulesetResource.getRealmRuleset(null, realmRuleset.id)
    updatedRealmRuleset.lang = JSON
    updatedRealmRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateRealmRuleset(null, realmRuleset.id, updatedRealmRuleset)

    then: "the request should be rejected"
    ex = thrown()
    ex.response.status == 400

    when: "a stored JavaScript asset ruleset is updated to a non-JavaScript language"
    def updatedAssetRuleset = rulesetResource.getAssetRuleset(null, assetRuleset.id)
    updatedAssetRuleset.lang = FLOW
    updatedAssetRuleset.rules = "SomeRulesCodeModified"
    rulesetResource.updateAssetRuleset(null, assetRuleset.id, updatedAssetRuleset)

    then: "the request should be rejected"
    ex = thrown()
    ex.response.status == 400
  }

  def "Legacy JavaScript rulesets remain readable and deletable"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

    and: "legacy JavaScript rulesets are stored directly"
    def globalRuleset = rulesetStorageService.merge(new GlobalRuleset("Legacy JS global definition", JAVASCRIPT, "SomeRulesCode"))
    def realmRuleset = rulesetStorageService.merge(new RealmRuleset(MASTER_REALM, "Legacy JS realm definition", JAVASCRIPT, "SomeRulesCode"))
    def assetRuleset = rulesetStorageService.merge(new AssetRuleset(managerTestSetup.smartOfficeId, "Legacy JS asset definition", JAVASCRIPT, "SomeRulesCode"))

    when: "legacy JavaScript rulesets are listed"
    def globalRulesets = rulesetResource.getGlobalRulesets(null, null, false)
    def realmRulesets = rulesetResource.getRealmRulesets(null, MASTER_REALM, null, false)
    def assetRulesets = rulesetResource.getAssetRulesets(null, managerTestSetup.smartOfficeId, null, false)

    then: "they remain visible in list endpoints"
    def listedGlobalRuleset = globalRulesets.find { it.id == globalRuleset.id }
    def listedRealmRuleset = realmRulesets.find { it.id == realmRuleset.id }
    def listedAssetRuleset = assetRulesets.find { it.id == assetRuleset.id }

    listedGlobalRuleset != null
    listedGlobalRuleset.lang == JAVASCRIPT
    listedGlobalRuleset.name == "Legacy JS global definition"
    listedGlobalRuleset.rules == null

    listedRealmRuleset != null
    listedRealmRuleset.lang == JAVASCRIPT
    listedRealmRuleset.name == "Legacy JS realm definition"
    listedRealmRuleset.rules == null

    listedAssetRuleset != null
    listedAssetRuleset.lang == JAVASCRIPT
    listedAssetRuleset.name == "Legacy JS asset definition"
    listedAssetRuleset.rules == null

    when: "legacy JavaScript rulesets are fetched"
    def fetchedGlobalRuleset = rulesetResource.getGlobalRuleset(null, globalRuleset.id)
    def fetchedRealmRuleset = rulesetResource.getRealmRuleset(null, realmRuleset.id)
    def fetchedAssetRuleset = rulesetResource.getAssetRuleset(null, assetRuleset.id)

    then: "they remain readable through detail endpoints"
    fetchedGlobalRuleset.lang == JAVASCRIPT
    fetchedGlobalRuleset.rules == "SomeRulesCode"
    fetchedRealmRuleset.lang == JAVASCRIPT
    fetchedRealmRuleset.rules == "SomeRulesCode"
    fetchedAssetRuleset.lang == JAVASCRIPT
    fetchedAssetRuleset.rules == "SomeRulesCode"

    when: "legacy JavaScript rulesets are deleted"
    rulesetResource.deleteGlobalRuleset(null, globalRuleset.id)
    rulesetResource.deleteRealmRuleset(null, realmRuleset.id)
    rulesetResource.deleteAssetRuleset(null, assetRuleset.id)

    then: "the delete requests succeed"
    notThrown(WebApplicationException)

    when: "a deleted global ruleset is fetched"
    rulesetResource.getGlobalRuleset(null, globalRuleset.id)

    then: "it is no longer found"
    WebApplicationException ex = thrown()
    ex.response.status == 404

    when: "a deleted realm ruleset is fetched"
    rulesetResource.getRealmRuleset(null, realmRuleset.id)

    then: "it is no longer found"
    ex = thrown()
    ex.response.status == 404

    when: "a deleted asset ruleset is fetched"
    rulesetResource.getAssetRuleset(null, assetRuleset.id)

    then: "it is no longer found"
    ex = thrown()
    ex.response.status == 404
  }

  def "Realm ruleset create cannot overwrite an existing ruleset in another realm"() {

    given: "the server container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def rulesetStorageService = container.getService(RulesetStorageService.class)
    def rulesService = container.getService(RulesService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "some imported rulesets"
    def rulesImport = new BasicRulesImport(rulesetStorageService, keycloakTestSetup, managerTestSetup)

    and: "an authenticated test user in the master realm who can write rules"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )

    and: "the ruleset resource"
    def rulesetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(RulesResource.class)

    expect: "the rules engines to be ready"
    conditions.eventually {
      rulesImport.assertEnginesReady(rulesService, keycloakTestSetup, managerTestSetup)
    }

    when: "the user creates a realm ruleset in their own realm carrying the ID and version of the building realm ruleset"
    def existingRuleset = rulesetStorageService.find(RealmRuleset.class, rulesImport.realmBuildingRulesetId, true)
    def realmRuleset = new RealmRuleset(MASTER_REALM, "Overwritten realm definition", JSON, "{}")
    realmRuleset.setId(existingRuleset.id)
    realmRuleset.setVersion(existingRuleset.version)
    rulesetResource.createRealmRuleset(null, realmRuleset)

    then: "the request should be rejected"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and: "the building realm ruleset should be unchanged"
    def storedRuleset = rulesetStorageService.find(RealmRuleset.class, rulesImport.realmBuildingRulesetId, true)
    storedRuleset.realm == keycloakTestSetup.realmBuilding.name
    storedRuleset.name == "Some building realm demo rules"
    storedRuleset.lang == GROOVY
    storedRuleset.rules == existingRuleset.rules
    storedRuleset.version == existingRuleset.version
  }
}
