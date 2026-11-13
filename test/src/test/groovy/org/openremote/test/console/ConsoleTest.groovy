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
package org.openremote.test.console


import com.google.firebase.messaging.Message
import jakarta.ws.rs.WebApplicationException
import org.openremote.container.timer.TimerService
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.notification.PushNotificationHandler
import org.openremote.manager.rules.RulesEngine
import org.openremote.manager.rules.RulesService
import org.openremote.manager.rules.RulesetStorageService
import org.openremote.manager.rules.geofence.ORConsoleGeofenceAssetAdapter
import org.openremote.manager.setup.SetupService
import org.openremote.manager.apps.ConsoleAppService
import org.openremote.manager.web.ManagerWebService
import org.openremote.model.asset.Asset
import org.openremote.model.asset.AssetResource
import org.openremote.model.asset.impl.ConsoleAsset
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.model.console.ConsoleProvider
import org.openremote.model.console.ConsoleRegistration
import org.openremote.model.console.ConsoleResource
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.notification.Notification
import org.openremote.model.notification.NotificationSendResult
import org.openremote.model.notification.PushNotificationMessage
import org.openremote.model.query.filter.RadialGeofencePredicate
import org.openremote.model.rules.AssetRuleset
import org.openremote.model.rules.RealmRuleset
import org.openremote.model.rules.RulesResource
import org.openremote.model.rules.Ruleset
import org.openremote.model.value.MetaItemType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.stream.IntStream

import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.Constants.MASTER_REALM
import static org.openremote.model.asset.AssetResource.Util.WRITE_ATTRIBUTE_HTTP_METHOD
import static org.openremote.model.asset.AssetResource.Util.getWriteAttributeUrl
import static org.openremote.model.rules.RulesetStatus.DEPLOYED
import static org.openremote.model.util.TextUtil.isNullOrEmpty
import static org.openremote.model.util.ValueUtil.parse
import static org.openremote.setup.integration.ManagerTestSetup.DEMO_RULE_STATES_SMART_BUILDING
import static org.openremote.setup.integration.ManagerTestSetup.SMART_BUILDING_LOCATION

class ConsoleTest extends Specification implements ManagerContainerTrait {

  def "Console config endpoint returns 404 when console_config.json is missing"() {
    given: "a custom app doc root without a console config file"
    def customAppDocRoot = Files.createTempDirectory("console-config-missing-")
    def container = startContainer(defaultConfig() << [(ManagerWebService.OR_CUSTOM_APP_DOCROOT): customAppDocRoot.toString()], defaultServices())
    def requestTarget = getClientApiTarget(serverUri(serverPort), MASTER_REALM).path("apps").path("consoleConfig")

    when: "requesting the console config"
    def response = requestTarget.request().get()

    then: "the endpoint should return 404"
    response.withCloseable {r ->
      assert r.status == 404
      return true
    }

    cleanup:
    if (response != null) {
      response.close()
    }
    Files.deleteIfExists(customAppDocRoot)
  }

  def "Console config endpoint returns 200 when console_config.json exists"() {
    given: "a custom app doc root with a console config file"
    def customAppDocRoot = Files.createTempDirectory("console-config-present-")
    def consoleConfigPath = customAppDocRoot.resolve("console_config.json")
    Files.write(consoleConfigPath, "{}".getBytes(StandardCharsets.UTF_8))

    def container = startContainer(defaultConfig() << [(ManagerWebService.OR_CUSTOM_APP_DOCROOT): customAppDocRoot.toString()], defaultServices())
    def requestTarget = getClientApiTarget(serverUri(serverPort), MASTER_REALM).path("apps").path("consoleConfig")

    when: "requesting the console config"
    def response = requestTarget.request().get()

    then: "the endpoint should return 200"
    response.withCloseable { r ->
      assert r.status == 200
      return true
    }

    cleanup:
    if (response != null) {
      response.close()
    }
    Files.deleteIfExists(consoleConfigPath)
    Files.deleteIfExists(customAppDocRoot)
  }

  def "Apps endpoint returns built-in and custom apps"() {
    given: "built-in and custom app doc roots with app folders"
    def builtInDocRoot = Files.createTempDirectory("apps-builtin-")
    def customDocRoot = Files.createTempDirectory("apps-custom-")
    Files.createDirectories(builtInDocRoot.resolve("appBuiltin"))
    Files.createDirectories(customDocRoot.resolve("appCustom"))

    def container = startContainer(defaultConfig() << [
      (ManagerWebService.OR_APP_DOCROOT) : builtInDocRoot.toString(),
      (ManagerWebService.OR_CUSTOM_APP_DOCROOT): customDocRoot.toString()
    ], defaultServices())
    def requestTarget = getClientApiTarget(serverUri(serverPort), MASTER_REALM).path("apps")

    when: "requesting the apps list"
    def response = requestTarget.request().get()

    then: "both apps are returned"
    response.withCloseable { r ->
      assert r.status == 200
      def appList = parse(r.readEntity(String.class)).orElse("") as String
      appList.contains("appBuiltin")
      appList.contains("appCustom")
      return true
    }

    cleanup:
    if (response != null) {
      response.close()
    }
    Files.deleteIfExists(builtInDocRoot.resolve("appBuiltin"))
    Files.deleteIfExists(builtInDocRoot)
    Files.deleteIfExists(customDocRoot.resolve("appCustom"))
    Files.deleteIfExists(customDocRoot)
  }

  def "Apps endpoint treats missing app doc root as empty"() {
    given: "a built-in app doc root and a missing custom app doc root"
    def builtInDocRoot = Files.createTempDirectory("apps-builtin-")
    def customDocRootParent = Files.createTempDirectory("apps-missing-custom-")
    def customDocRoot = customDocRootParent.resolve("missing")
    Files.createDirectories(builtInDocRoot.resolve("appBuiltin"))

    def container = startContainer(defaultConfig() << [
      (ManagerWebService.OR_APP_DOCROOT) : builtInDocRoot.toString(),
      (ManagerWebService.OR_CUSTOM_APP_DOCROOT): customDocRoot.toString()
    ], defaultServices())
    def requestTarget = getClientApiTarget(serverUri(serverPort), MASTER_REALM).path("apps")

    when: "requesting the apps list"
    def response = requestTarget.request().get()

    then: "the missing root is ignored and apps from the available root are returned"
    response.withCloseable { r ->
      assert r.status == 200
      def appList = parse(r.readEntity(String.class)).orElse("") as String
      appList.contains("appBuiltin")
      return true
    }

    cleanup:
    if (response != null) {
      response.close()
    }
    Files.deleteIfExists(builtInDocRoot.resolve("appBuiltin"))
    Files.deleteIfExists(builtInDocRoot)
    Files.deleteIfExists(customDocRootParent)
  }

  def "Apps info endpoint returns info for custom apps"() {
    given: "a custom app doc root with an info.json file"
    def customDocRoot = Files.createTempDirectory("apps-info-custom-")
    def appDir = customDocRoot.resolve("appInfo")
    Files.createDirectories(appDir)
    Files.write(appDir.resolve("info.json"), "{\"name\":\"appInfo\"}".getBytes(StandardCharsets.UTF_8))

    def container = startContainer(defaultConfig() << [
      (ManagerWebService.OR_CUSTOM_APP_DOCROOT): customDocRoot.toString()
    ], defaultServices())
    def consoleAppService = container.getService(ConsoleAppService.class)
    consoleAppService.@consoleAppDocRoot = customDocRoot

    def requestTarget = getClientApiTarget(serverUri(serverPort), MASTER_REALM).path("apps").path("info")

    when: "requesting the apps info"
    def response = requestTarget.request().get()

    then: "the response includes the app info"
    response.withCloseable { r ->
      assert r.status == 200
      Map infoMap = parse(response.readEntity(String.class)).orElse([:])
      infoMap.containsKey("appInfo")
      return true
    }

    cleanup:
    Files.deleteIfExists(appDir.resolve("info.json"))
    Files.deleteIfExists(appDir)
    Files.deleteIfExists(customDocRoot)
  }

  def "Console registration rejects existing console mutation without current ownership"() {
    given: "the container environment is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "console registration clients for two users and an anonymous caller"
    def testUser3AccessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )
    def testUser2AccessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser2",
            "testuser2"
    )
    def testUser3ConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, testUser3AccessToken).proxy(ConsoleResource.class)
    def testUser2ConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, testUser2AccessToken).proxy(ConsoleResource.class)
    def anonymousConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name).proxy(ConsoleResource.class)

    when: "a console is registered by one authenticated user"
    def originalRegistration = createConsoleRegistration(null, "Owner Console", "owner-token")
    def registeredConsole = testUser3ConsoleResource.register(null, originalRegistration)
    def consoleId = registeredConsole.id
    ConsoleAsset console = assetStorageService.find(consoleId, true)

    then: "the console is linked to the registering user and has the original provider data"
    assert console != null
    assert console.getConsoleName().orElse(null) == "Owner Console"
    assert getPushToken(console) == "owner-token"
    assert assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser3Id, consoleId).size() == 1
    assert assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser2Id, consoleId).isEmpty()

    when: "an anonymous caller tries to mutate the existing console by ID"
    anonymousConsoleResource.register(null, createConsoleRegistration(consoleId, "Anonymous Attacker Console", "anonymous-attacker-token"))

    then: "the request should be rejected"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403 || r.status == 409
      return true
    }

    and: "the console provider data should remain unchanged"
    ConsoleAsset consoleAfterAnonymousAttempt = assetStorageService.find(consoleId, true)
    assert consoleAfterAnonymousAttempt.getConsoleName().orElse(null) == "Owner Console"
    assert getPushToken(consoleAfterAnonymousAttempt) == "owner-token"
    assert assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser3Id, consoleId).size() == 1
    assert assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser2Id, consoleId).isEmpty()

    when: "a different authenticated user tries to claim the existing console by ID"
    testUser2ConsoleResource.register(null, createConsoleRegistration(consoleId, "Other User Console", "other-user-token"))

    then: "the request should be rejected"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403 || r.status == 409
      return true
    }

    and: "the original user link and provider data should remain unchanged"
    ConsoleAsset consoleAfterOtherUserAttempt = assetStorageService.find(consoleId, true)
    assert consoleAfterOtherUserAttempt.getConsoleName().orElse(null) == "Owner Console"
    assert getPushToken(consoleAfterOtherUserAttempt) == "owner-token"
    assert assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser3Id, consoleId).size() == 1
    assert assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser2Id, consoleId).isEmpty()
  }

  def "Console registration rejects client supplied IDs for new legacy consoles"() {
    given: "the container environment is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "an authenticated console registration client"
    def accessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )
    def authenticatedConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(ConsoleResource.class)

    when: "a client tries to create a console with its own unused ID"
    def unusedId = UniqueIdentifierGenerator.generateId("UnusedConsoleIdRejectedByLegacyRegister")
    authenticatedConsoleResource.register(null, createConsoleRegistration(unusedId, "Client Supplied ID Console", "client-token"))

    then: "the request should be rejected"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400 || r.status == 409
      return true
    }

    and: "no console asset should be created with the supplied ID"
    assert assetStorageService.find(unusedId, true) == null
  }

  def "Check full console behavior"() {
    def notificationIds = new CopyOnWriteArrayList()
    def targetTypes = new CopyOnWriteArrayList()
    def targetIds = new CopyOnWriteArrayList()
    def messages = new CopyOnWriteArrayList()

    given: "the container environment is started"
    def conditions = new PollingConditions(timeout: 20, delay: 0.2)
    ORConsoleGeofenceAssetAdapter.NOTIFY_ASSETS_DEBOUNCE_MILLIS = 100
    ORConsoleGeofenceAssetAdapter.NOTIFY_ASSETS_BATCH_MILLIS = 200
    def container = startContainer(defaultConfig(), defaultServices())
    def pushNotificationHandler = container.getService(PushNotificationHandler.class)
    def notificationService = container.getService(NotificationService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def timerService = container.getService(TimerService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def rulesService = container.getService(RulesService.class)
    def rulesetStorageService = container.getService(RulesetStorageService.class)

    and: "a mock push notification handler is injected"
    PushNotificationHandler mockPushNotificationHandler = Spy(pushNotificationHandler)
    mockPushNotificationHandler.isValid() >> true
    mockPushNotificationHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.Target, _ as PushNotificationMessage) >> { Long id, Notification.Source source, String sourceId, Notification.Target target, PushNotificationMessage message ->

      message.title = target.id // Makes it easier to test/debug
      message.body = timerService.getCurrentTimeMillis() // Makes it easier to test/debug
      notificationIds << id
      targetTypes << target.type
      targetIds << target.id
      messages << "${message.title}_${message.data.get("action")}"
      callRealMethod()
    }
    // Assume sent to FCM
    mockPushNotificationHandler.sendMessage(_ as Message) >> { message ->
      return NotificationSendResult.success()
    }
    notificationService.notificationHandlerMap.put(pushNotificationHandler.getTypeName(), mockPushNotificationHandler)

    def geofenceAdapter = (ORConsoleGeofenceAssetAdapter) rulesService.geofenceAssetAdapters.find {
      it.name == "ORConsole"
    }
    Asset testUser3Console1, testUser3Console2, anonymousConsole1

    and: "the demo location predicate console rules are loaded"
    Ruleset ruleset = new RealmRuleset(
            keycloakTestSetup.realmBuilding.name,
            "Demo Realm Building - Console Location",
            Ruleset.Lang.GROOVY,
            getClass().getResource("/org/openremote/test/rules/ConsoleLocation.groovy").text)
    rulesetStorageService.merge(ruleset)

    expect: "the rule engine to become available and be running"
    conditions.eventually {
      def realmBuildingEngine = rulesService.realmEngines.get(keycloakTestSetup.realmBuilding.name)
      assert realmBuildingEngine != null
      assert realmBuildingEngine.isRunning()
      assert realmBuildingEngine.facts.getAssetStates().size() == DEMO_RULE_STATES_SMART_BUILDING
    }

    and: "an authenticated user"
    def accessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )

    and: "authenticated and anonymous console, rules and asset resources"
    def authenticatedConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(ConsoleResource.class)
    def authenticatedRulesResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(RulesResource.class)
    def authenticatedAssetResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(AssetResource.class)
    def anonymousConsoleResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name).proxy(ConsoleResource.class)
    def anonymousRulesResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name).proxy(RulesResource.class)

    when: "a console registers with an authenticated user"
    def consoleRegistration = new ConsoleRegistration(null,
            "Test Console",
            "1.0",
            "Android 7.0",
            new HashMap<String, ConsoleProvider>(
                    geofence: new ConsoleProvider(
                            ORConsoleGeofenceAssetAdapter.NAME,
                            true,
                            false,
                            false,
                            false,
                            false,
                            null
                            ),
                    push: new ConsoleProvider(
                            "fcm",
                            true,
                            true,
                            true,
                            true,
                            false,
                            (Map)parse("{\"token\": \"23123213ad2313b0897efd\"}").orElse(null)
                            )
                    ),
            "",
            ["manager"] as String[])
    def returnedConsoleRegistration = authenticatedConsoleResource.register(null, consoleRegistration)
    def consoleId = returnedConsoleRegistration.getId()
    ConsoleAsset console = assetStorageService.find(consoleId, true)

    then: "the console asset should have been created and be correctly configured"
    assert console != null
    assert console.getConsoleName().orElse(null) == "Test Console"
    assert console.getConsoleVersion().orElse(null) == "1.0"
    assert console.getConsolePlatform().orElse(null) == "Android 7.0"
    def consoleGeofenceProvider = console.getConsoleProviders().map{
      it.get("geofence")
    }.orElse(null)
    def consolePushProvider = console.getConsoleProviders().map{it.get("push")}.orElse(null)
    assert consoleGeofenceProvider != null
    assert consoleGeofenceProvider.version == ORConsoleGeofenceAssetAdapter.NAME
    assert consoleGeofenceProvider.requiresPermission
    assert !consoleGeofenceProvider.hasPermission
    assert !consoleGeofenceProvider.disabled
    assert consoleGeofenceProvider.data == null
    assert consolePushProvider != null
    assert consolePushProvider.version == "fcm"
    assert consolePushProvider.requiresPermission
    assert consolePushProvider.hasPermission
    assert !consolePushProvider.disabled
    assert consolePushProvider.data.get("token") == "23123213ad2313b0897efd"

    and: "the console should have been linked to the authenticated user"
    def userAssets = assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name, keycloakTestSetup.testuser3Id, consoleId)
    assert userAssets.size() == 1
    assert userAssets.get(0).assetName == "Test Console"

    when: "the console registration is updated"
    returnedConsoleRegistration.providers.get("geofence").hasPermission = true
    returnedConsoleRegistration.providers.put("test", new ConsoleProvider(
                    "Test 1.0",
                    false,
                    false,
                    false,
                    false,
                    true,
                    null,
            ))
    returnedConsoleRegistration = authenticatedConsoleResource.register(null, returnedConsoleRegistration)
    console = assetStorageService.find(consoleId, true)
    testUser3Console1 = console
    consoleGeofenceProvider = console.getConsoleProviders().map{it.get("geofence")}.orElse(null)
    consolePushProvider = console.getConsoleProviders().map{it.get("push")}.orElse(null)
    def consoleTestProvider = console.getConsoleProviders().map{it.get("test")}.orElse(null)

    then: "the returned console should contain the updated data and have the same id"
    assert returnedConsoleRegistration.getId() == consoleId
    assert console != null
    assert consoleGeofenceProvider != null
    assert consoleGeofenceProvider.version == ORConsoleGeofenceAssetAdapter.NAME
    assert consoleGeofenceProvider.requiresPermission
    assert consoleGeofenceProvider.hasPermission
    assert !consoleGeofenceProvider.disabled
    assert consoleGeofenceProvider.data == null
    assert consolePushProvider != null
    assert consolePushProvider.version == "fcm"
    assert consolePushProvider.requiresPermission
    assert consolePushProvider.hasPermission
    assert !consolePushProvider.disabled
    assert consolePushProvider.data.get("token") == "23123213ad2313b0897efd"
    assert consoleTestProvider != null
    assert consoleTestProvider.version == "Test 1.0"
    assert !consoleTestProvider.requiresPermission
    assert !consoleTestProvider.hasPermission
    assert consoleTestProvider.disabled
    assert consoleTestProvider.data == null

    when: "the console registration is updated anonymously"
    returnedConsoleRegistration.providers.get("test").disabled = false
    anonymousConsoleResource.register(null, returnedConsoleRegistration)

    then: "the result should be forbidden"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "the console registration is reloaded after the rejected anonymous update"
    console = assetStorageService.find(consoleId, true)
    testUser3Console1 = console
    consoleTestProvider = console.getConsoleProviders().map{it.get("test")}.orElse(null)

    then: "the console registration should be unchanged"
    assert console != null
    assert consoleGeofenceProvider != null
    assert consoleGeofenceProvider.version == ORConsoleGeofenceAssetAdapter.NAME
    assert consoleGeofenceProvider.requiresPermission
    assert consoleGeofenceProvider.hasPermission
    assert !consoleGeofenceProvider.disabled
    assert consoleGeofenceProvider.data == null
    assert consolePushProvider != null
    assert consolePushProvider.version == "fcm"
    assert consolePushProvider.requiresPermission
    assert consolePushProvider.hasPermission
    assert !consolePushProvider.disabled
    assert consolePushProvider.data.get("token") == "23123213ad2313b0897efd"
    assert consoleTestProvider != null
    assert consoleTestProvider.version == "Test 1.0"
    assert !consoleTestProvider.requiresPermission
    assert !consoleTestProvider.hasPermission
    assert consoleTestProvider.disabled
    assert consoleTestProvider.data == null

    when: "a console registers with the id of another existing asset"
    consoleRegistration.setId(managerTestSetup.thingId)
    authenticatedConsoleResource.register(null, consoleRegistration)

    then: "the result should be bad request"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a console registers with an id that doesn't exist"
    def unusedId = UniqueIdentifierGenerator.generateId("UnusedConsoleId")
    consoleRegistration.setId(unusedId)
    authenticatedConsoleResource.register(null, consoleRegistration)

    then: "the result should be conflict"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 409
      return true
    }
    assert assetStorageService.find(unusedId, true) == null

    when: "a second console is registered without a client supplied id"
    consoleRegistration.setId(null)
    returnedConsoleRegistration = authenticatedConsoleResource.register(null, consoleRegistration)
    console = assetStorageService.find(returnedConsoleRegistration.getId(), true)
    testUser3Console2 = console
    consoleGeofenceProvider = console.getConsoleProviders().map{it.get("geofence")}.orElse(null)
    consolePushProvider = console.getConsoleProviders().map{it.get("push")}.orElse(null)

    then: "the console should be registered successfully and the returned console should match the supplied console"
    assert console != null
    assert console.getConsoleName().orElse(null) == "Test Console"
    assert console.getConsoleVersion().orElse(null) == "1.0"
    assert console.getConsolePlatform().orElse(null) == "Android 7.0"
    assert consoleGeofenceProvider != null
    assert consoleGeofenceProvider.version == ORConsoleGeofenceAssetAdapter.NAME
    assert consoleGeofenceProvider.requiresPermission
    assert !consoleGeofenceProvider.hasPermission
    assert !consoleGeofenceProvider.disabled
    assert consoleGeofenceProvider.data == null
    assert consolePushProvider != null
    assert consolePushProvider.version == "fcm"
    assert consolePushProvider.requiresPermission
    assert consolePushProvider.hasPermission
    assert !consolePushProvider.disabled
    assert consolePushProvider.data.get("token") == "23123213ad2313b0897efd"

    when: "an invalid console registration is registered"
    def invalidRegistration = new ConsoleRegistration(null, null, "1.0", null, null, null, null)
    authenticatedConsoleResource.register(null, invalidRegistration)

    then: "the result should be bad request"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a console is registered anonymously"
    consoleRegistration.id = null
    returnedConsoleRegistration = anonymousConsoleResource.register(null, consoleRegistration)
    consoleId = returnedConsoleRegistration.getId()
    console = assetStorageService.find(consoleId, true)
    anonymousConsole1 = console
    consoleGeofenceProvider = console.getConsoleProviders().map{it.get("geofence")}.orElse(null)
    consolePushProvider = console.getConsoleProviders().map{it.get("push")}.orElse(null)
    userAssets = assetStorageService.findUserAssetLinks(null, null, consoleId)

    then: "the console asset should have been created and be correctly configured"
    assert !isNullOrEmpty(consoleId)
    assert console != null
    assert console.getConsoleName().orElse(null) == "Test Console"
    assert console.getConsoleVersion().orElse(null) == "1.0"
    assert console.getConsolePlatform().orElse(null) == "Android 7.0"
    assert consoleGeofenceProvider != null
    assert consoleGeofenceProvider.version == ORConsoleGeofenceAssetAdapter.NAME
    assert consoleGeofenceProvider.requiresPermission
    assert !consoleGeofenceProvider.hasPermission
    assert !consoleGeofenceProvider.disabled
    assert consoleGeofenceProvider.data == null
    assert consolePushProvider != null
    assert consolePushProvider.version == "fcm"
    assert consolePushProvider.requiresPermission
    assert consolePushProvider.hasPermission
    assert !consolePushProvider.disabled
    assert consolePushProvider.data.get("token") == "23123213ad2313b0897efd"

    and: "the console should not have been linked to any users"
    assert userAssets.isEmpty()

    when: "the location of each console is marked as RULE_STATE"
    testUser3Console1.getAttribute(Asset.LOCATION).ifPresent {it ->
      it.addMeta(
              new MetaItem<>(MetaItemType.RULE_STATE),
              new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_WRITE),
              new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ)
      )
    }
    testUser3Console1 = assetStorageService.merge(testUser3Console1)
    testUser3Console2.getAttribute(Asset.LOCATION).ifPresent {it ->
      it.addMeta(
              new MetaItem<>(MetaItemType.RULE_STATE),
              new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_WRITE),
              new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ)
      )
    }
    testUser3Console2 = assetStorageService.merge(testUser3Console2)
    anonymousConsole1.getAttribute(Asset.LOCATION).ifPresent {it ->
      it.addMeta(
              new MetaItem<>(MetaItemType.RULE_STATE),
              new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_WRITE),
              new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ)
      )
    }
    anonymousConsole1 = assetStorageService.merge(anonymousConsole1)

    then: "each created consoles should have been sent notifications to refresh their geofences"
    conditions.eventually {
      assert notificationIds.size() == 3
      assert messages.any {it == "${testUser3Console1.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${testUser3Console2.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${anonymousConsole1.id}_GEOFENCE_REFRESH"}
    }

    ////////////////////////////////////////////////////
    // LOCATION PREDICATE AND ALERT NOTIFICATION TESTS
    ////////////////////////////////////////////////////

    when: "notifications are cleared"
    notificationIds.clear()
    targetIds.clear()
    targetTypes.clear()
    messages.clear()

    and: "an authenticated user updates the location of a linked console"
    authenticatedAssetResource.writeAttributeValue(null, testUser3Console1.id, Asset.LOCATION.name, new GeoJSONPoint(0d, 0d, 0d))

    then: "the consoles location should have been updated"
    conditions.eventually {
      testUser3Console1 = assetStorageService.find(testUser3Console1.id, true)
      assert testUser3Console1 != null
      def assetLocation = testUser3Console1.getLocation().orElse(null)
      assert assetLocation != null
      assert assetLocation.x == 0d
      assert assetLocation.y == 0d
      assert assetLocation.z == 0d
    }
    // TODO: Update once console permissions model finalized
    //        when: "an anonymous user updates the location of a console linked to users"
    //        anonymousAssetResource.writeAttributeValue(null, testUser3Console1.id, LOCATION.name, new GeoJSONPoint(0d, 0d, 0d).objectToValue())
    //
    //        then: "the result should be forbidden"
    //        ex = thrown()
    //        ex.response.withCloseable { r ->
    //            assert r.status == 403
    //        }

    when: "a console's location is updated to be at the Smart Building"
    authenticatedAssetResource.writeAttributeValue(null, testUser3Console2.id, Asset.LOCATION.name, SMART_BUILDING_LOCATION)
    long timestamp = Long.MAX_VALUE

    then: "a welcome home alert should be sent to the console"
    conditions.eventually {
      assert notificationIds.size() == 1
      def asset = assetStorageService.find(testUser3Console2.id, true)
      assert asset != null
      timestamp = asset.getAttribute(Asset.LOCATION.name).flatMap {
        it.timestamp
      }.orElse(Long.MAX_VALUE)
    }

    when: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "a console's location is updated to be at the Smart Building again"
    authenticatedAssetResource.writeAttributeValue(null, testUser3Console2.id, Asset.LOCATION.name, SMART_BUILDING_LOCATION)

    then: "no more alerts should have been sent"
    conditions.eventually {
      def asset = assetStorageService.find(testUser3Console2.id, true)
      assert asset != null
      assert asset.getAttribute(Asset.LOCATION.name).flatMap {
        it.timestamp
      }.orElse(Long.MIN_VALUE) > timestamp
      timestamp = asset.getAttribute(Asset.LOCATION.name).flatMap {
        it.timestamp
      }.orElse(Long.MIN_VALUE)
      assert notificationIds.size() == 1
    }

    when: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "a console's location is updated to be null"
    authenticatedAssetResource.writeAttributeValue(null, testUser3Console2.id, Asset.LOCATION.name, null)

    then: "no more alerts should have been sent and the welcome reset rule should have fired on the realm rule engine"
    conditions.eventually {
      def asset = assetStorageService.find(testUser3Console2.id, true)
      assert asset != null
      assert asset.getAttribute(Asset.LOCATION.name).flatMap {
        it.timestamp
      }.orElse(Long.MIN_VALUE) > timestamp
      assert notificationIds.size() == 1
      def realmBuildingEngine = rulesService.realmEngines.get(keycloakTestSetup.realmBuilding.name)
      assert realmBuildingEngine != null
      assert realmBuildingEngine.isRunning()
      assert !realmBuildingEngine.facts.getOptional("welcomeHome_${testUser3Console2.id}").isPresent()
    }

    when: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "a console's location is updated to be at the Smart Building again"
    authenticatedAssetResource.writeAttributeValue(null, testUser3Console2.id, Asset.LOCATION.name, SMART_BUILDING_LOCATION)

    then: "another alert should have been sent"
    conditions.eventually {
      assert notificationIds.size() == 2
    }

    ///////////////////////////////////////
    // GEOFENCE PROVIDER TESTS
    ///////////////////////////////////////

    when: "the geofences of a user linked console are requested by the authenticated user"
    notificationIds.clear()
    messages.clear()
    def geofences = authenticatedRulesResource.getAssetGeofences(null, testUser3Console1.id)
    def expectedLocationPredicate = new RadialGeofencePredicate(100, SMART_BUILDING_LOCATION.y, SMART_BUILDING_LOCATION.x)

    then: "the welcome home geofence should be retrieved"
    assert expectedLocationPredicate.centrePoint.size() == 2
    assert expectedLocationPredicate.centrePoint[0] == expectedLocationPredicate.lng
    assert expectedLocationPredicate.centrePoint[1] == expectedLocationPredicate.lat
    assert geofences.size() == 1
    assert geofences[0].id == testUser3Console1.id + "_" + Integer.toString(expectedLocationPredicate.hashCode())
    assert geofences[0].lat == expectedLocationPredicate.lat
    assert geofences[0].lng == expectedLocationPredicate.lng
    assert geofences[0].radius == expectedLocationPredicate.radius
    assert geofences[0].httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
    assert geofences[0].url == getWriteAttributeUrl(new AttributeRef(testUser3Console1.id, Asset.LOCATION.name))

    // TODO: Update once console permissions model finalized
    //        when: "an anonymous user tries to retrieve the geofences of a console linked to users"
    //        geofences = anonymousRulesResource.getAssetGeofences(null, testUser3Console1.id)
    //
    //        then: "the result should be a forbidden request"
    //        ex = thrown()
    //        ex.response.withCloseable { r ->
    //            assert r.status == 403
    //        }

    when: "the geofences of testUser3Console2 are retrieved"
    geofences = authenticatedRulesResource.getAssetGeofences(null, testUser3Console2.id)

    then: "the welcome home geofence should be retrieved"
    assert geofences.size() == 1
    assert geofences[0].id == testUser3Console2.id + "_" + Integer.toString(expectedLocationPredicate.hashCode())
    assert geofences[0].lat == expectedLocationPredicate.lat
    assert geofences[0].lng == expectedLocationPredicate.lng
    assert geofences[0].radius == expectedLocationPredicate.radius
    assert geofences[0].httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
    assert geofences[0].url == getWriteAttributeUrl(new AttributeRef(testUser3Console2.id, Asset.LOCATION.name))

    when: "the geofences of anonymousConsole1 are retrieved"
    geofences = anonymousRulesResource.getAssetGeofences(null, anonymousConsole1.id)

    then: "the welcome home geofence should be retrieved"
    assert geofences.size() == 1
    assert geofences[0].id == anonymousConsole1.id + "_" + Integer.toString(expectedLocationPredicate.hashCode())
    assert geofences[0].lat == expectedLocationPredicate.lat
    assert geofences[0].lng == expectedLocationPredicate.lng
    assert geofences[0].radius == expectedLocationPredicate.radius
    assert geofences[0].httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
    assert geofences[0].url == getWriteAttributeUrl(new AttributeRef(anonymousConsole1.id, Asset.LOCATION.name))

    when: "the geofences of a non-existent console are retrieved"
    geofences = anonymousRulesResource.getAssetGeofences(null, UniqueIdentifierGenerator.generateId("nonExistentConsole"))

    then: "an empty array should be returned"
    assert geofences.size() == 0

    when: "a console is deleted"
    assetStorageService.delete([testUser3Console2.id],false)

    then: "the deleted console should be removed from ORConsoleGeofenceAssetAdapter"
    conditions.eventually {
      assert !geofenceAdapter.consoleIdRealmMap.containsKey(testUser3Console2.id)
    }

    then: "no geofences should be returned for this deleted console"
    conditions.eventually {
      geofences = anonymousRulesResource.getAssetGeofences(null, testUser3Console2.id)
      assert geofences.size() == 0
    }

    when: "the notifications are cleared"
    notificationIds.clear()
    messages.clear()

    and: "a new ruleset is deployed on the console parent asset with multiple location predicate rules (including a duplicate and a rectangular predicate)"
    def newRuleset = new AssetRuleset(
            testUser3Console1.parentId,
            "Console test location predicates",
            Ruleset.Lang.GROOVY,
            getClass().getResource("/org/openremote/test/rules/BasicLocationPredicates.groovy").text)
    newRuleset = rulesetStorageService.merge(newRuleset)
    RulesEngine consoleParentEngine = null
    def newLocationPredicate = new RadialGeofencePredicate(50, 0, -60)

    then: "the new rule engine should be created and be running"
    conditions.eventually {
      consoleParentEngine = rulesService.assetEngines.get(testUser3Console1.parentId)
      assert consoleParentEngine != null
      assert consoleParentEngine.isRunning()
      assert consoleParentEngine.deployments.size() == 1
      assert consoleParentEngine.deployments.values().any({
        it.name == "Console test location predicates" && it.status == DEPLOYED
      })
    }

    then: "a push notification should have been sent to the two remaining consoles telling them to refresh their geofences"
    conditions.eventually {
      assert notificationIds.size() == 2
      assert messages.any {it == "${testUser3Console1.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${anonymousConsole1.id}_GEOFENCE_REFRESH"}
    }

    then: "the geofences of testUser3Console1 should contain the welcome home geofence and new radial geofence (but not the rectangular and duplicate predicates)"
    conditions.eventually {
      geofences = authenticatedRulesResource.getAssetGeofences(null, testUser3Console1.id)
      assert geofences.size() == 2
      def geofence1 = geofences.find {
        it.id == testUser3Console1.id + "_" + Integer.toString(
                expectedLocationPredicate.hashCode())
      }
      def geofence2 = geofences.find {
        it.id == testUser3Console1.id + "_" + Integer.toString(
                newLocationPredicate.hashCode())
      }
      assert geofence1 != null && geofence2 != null
      assert geofence1.lat == expectedLocationPredicate.lat
      assert geofence1.lng == expectedLocationPredicate.lng
      assert geofence1.radius == expectedLocationPredicate.radius
      assert geofence1.httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
      assert geofence1.url == getWriteAttributeUrl(new AttributeRef(testUser3Console1.id, Asset.LOCATION.name))
      assert geofence2.lat == newLocationPredicate.lat
      assert geofence2.lng == newLocationPredicate.lng
      assert geofence2.radius == newLocationPredicate.radius
      assert geofence2.httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
      assert geofence2.url == getWriteAttributeUrl(new AttributeRef(testUser3Console1.id, Asset.LOCATION.name))
    }

    when: "the notifications are cleared"
    notificationIds.clear()
    messages.clear()

    and: "an existing ruleset containing a radial location predicate is updated"
    newRuleset.rules = getClass().getResource("/org/openremote/test/rules/BasicLocationPredicates2.groovy").text
    newRuleset = rulesetStorageService.merge(newRuleset)
    newLocationPredicate = new RadialGeofencePredicate(150, 10, 40)

    then: "a push notification should have been sent to the two remaining consoles telling them to refresh their geofences (from the realm engine and asset engine)"
    conditions.eventually {
      assert messages.any {it == "${anonymousConsole1.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${testUser3Console1.id}_GEOFENCE_REFRESH"}
    }

    then: "the geofences of testUser3Console1 should still contain two geofences but the location of the second should have been updated"
    conditions.eventually {
      geofences = authenticatedRulesResource.getAssetGeofences(null, testUser3Console1.id)
      assert geofences.size() == 2
      def geofence1 = geofences.find {
        it.id == testUser3Console1.id + "_" + Integer.toString(
                expectedLocationPredicate.hashCode())
      }
      def geofence2 = geofences.find {
        it.id == testUser3Console1.id + "_" + Integer.toString(
                newLocationPredicate.hashCode())
      }
      assert geofence1 != null && geofence2 != null
      assert geofence1.lat == expectedLocationPredicate.lat
      assert geofence1.lng == expectedLocationPredicate.lng
      assert geofence1.radius == expectedLocationPredicate.radius
      assert geofence1.httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
      assert geofence1.url == getWriteAttributeUrl(new AttributeRef(testUser3Console1.id, Asset.LOCATION.name))
      assert geofence2.lat == newLocationPredicate.lat
      assert geofence2.lng == newLocationPredicate.lng
      assert geofence2.radius == newLocationPredicate.radius
      assert geofence2.httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
      assert geofence2.url == getWriteAttributeUrl(new AttributeRef(testUser3Console1.id, Asset.LOCATION.name))
    }

    when: "previous notification messages are cleared"
    notificationIds.clear()
    messages.clear()

    and: "a location predicate ruleset is removed"
    rulesetStorageService.delete(AssetRuleset.class, newRuleset.id)

    then: "only the welcome home geofence should remain"
    conditions.eventually {
      geofences = authenticatedRulesResource.getAssetGeofences(null, testUser3Console1.id)
      assert geofences.size() == 1
      assert geofences[0].id == testUser3Console1.id + "_" + Integer.toString(
      expectedLocationPredicate.hashCode())
      assert geofences[0].lat == expectedLocationPredicate.lat
      assert geofences[0].lng == expectedLocationPredicate.lng
      assert geofences[0].radius == expectedLocationPredicate.radius
      assert geofences[0].httpMethod == WRITE_ATTRIBUTE_HTTP_METHOD
      assert geofences[0].url == getWriteAttributeUrl(new AttributeRef(testUser3Console1.id, Asset.LOCATION.name))
    }

    and: "the consoles should have been notified to refresh their geofences"
    conditions.eventually {
      assert messages.any {it == "${testUser3Console1.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${anonymousConsole1.id}_GEOFENCE_REFRESH"}
    }

    when: "previous notification messages are cleared"
    notificationIds.clear()
    messages.clear()

    and: "another 10 consoles are added to the system"
    def testUser3Console1Id = testUser3Console1.id
    def extraConsoles = IntStream.rangeClosed(3, 12).mapToObj({
      testUser3Console1.id = null
      testUser3Console1.name = "Test Console $it"
      return assetStorageService.merge(testUser3Console1)
    }).collect({ it as ConsoleAsset })
    testUser3Console1.id = testUser3Console1Id

    then: "the extra consoles should have been added"
    assert extraConsoles.size() == 10

    and: "a push notifications should have been sent to each new console to refresh their geofence rules"
    conditions.eventually {
      assert messages.any {it == "${extraConsoles.get(0).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(1).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(2).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(3).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(4).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(5).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(6).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(7).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(8).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(9).id}_GEOFENCE_REFRESH"}
    }

    when: "previous notification messages are cleared"
    notificationIds.clear()
    messages.clear()

    and: "a new ruleset is created containing a radial location predicate"
    newRuleset = new AssetRuleset(
            testUser3Console1.parentId,
            "Console test location predicates",
            Ruleset.Lang.GROOVY,
            getClass().getResource("/org/openremote/test/rules/BasicLocationPredicates.groovy").text)
    newRuleset = rulesetStorageService.merge(newRuleset)

    then: "a push notification should have been sent to all consoles telling them to refresh their geofences"
    conditions.eventually {
      assert messages.any {it == "${testUser3Console1.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${anonymousConsole1.id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(0).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(1).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(2).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(3).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(4).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(5).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(6).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(7).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(8).id}_GEOFENCE_REFRESH"}
      assert messages.any {it == "${extraConsoles.get(9).id}_GEOFENCE_REFRESH"}
    }

    when: "the RULE_STATE meta is set to false on a console's location attribute"
    testUser3Console1 = assetStorageService.find(testUser3Console1.id, true)
    testUser3Console1.getAttribute(Asset.LOCATION.name).ifPresent{
      it.addMeta(new MetaItem<>(MetaItemType.RULE_STATE, false))
    }
    testUser3Console1 = assetStorageService.merge(testUser3Console1)

    then: "no geofences should remain for this console"
    conditions.eventually {
      geofences = authenticatedRulesResource.getAssetGeofences(null, testUser3Console1.id)
      assert geofences.size() == 0
    }

    cleanup: "the mock is removed"
    if (notificationService != null) {
      notificationService.notificationHandlerMap.put(pushNotificationHandler.getTypeName(), pushNotificationHandler)
    }
  }

  protected static ConsoleRegistration createConsoleRegistration(String id, String name, String pushToken) {
    return new ConsoleRegistration(
            id,
            name,
            "1.0",
            "Android 7.0",
            new HashMap<String, ConsoleProvider>(
                    push: new ConsoleProvider(
                            "fcm",
                            true,
                            true,
                            true,
                            true,
                            false,
                            [token: pushToken]
                            )
                    ),
            "",
            ["manager"] as String[]
    )
  }

  protected static String getPushToken(ConsoleAsset console) {
    return console.getConsoleProviders()
            .map { it.get("push") }
            .map { it.data.get("token") as String }
            .orElse(null)
  }
}
