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
package org.openremote.test.notification


import com.fasterxml.jackson.databind.node.TextNode
import jakarta.mail.Message
import jakarta.mail.internet.InternetAddress
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.WebApplicationException
import org.apache.camel.builder.NotifyBuilder
import org.openremote.container.message.MessageBrokerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.asset.console.ConsoleResourceImpl
import org.openremote.manager.notification.EmailNotificationHandler
import org.openremote.manager.notification.LocalizedNotificationHandler
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.notification.PushNotificationHandler
import org.openremote.manager.rules.RulesEngineId
import org.openremote.manager.rules.facade.NotificationsFacade
import org.openremote.manager.rules.geofence.ORConsoleGeofenceAssetAdapter
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.web.ManagerWebService
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.ConsoleAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.console.ConsoleProvider
import org.openremote.model.console.ConsoleRegistration
import org.openremote.model.console.ConsoleResource
import org.openremote.model.notification.*
import org.openremote.model.security.User
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import static org.openremote.model.util.MapAccess.getString
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.notification.PushNotificationAction.writeAttributeValueAction
import static org.openremote.model.util.ValueUtil.parse

class NotificationTest extends Specification implements ManagerContainerTrait {

  def "Check push notification functionality"() {

    List<String> notificationIds = new CopyOnWriteArrayList<>()
    List<Notification.TargetType> notificationTargetTypes = new CopyOnWriteArrayList<>()
    List<String> notificationTargetIds = new CopyOnWriteArrayList<>()
    List<AbstractNotificationMessage> notificationMessages = new CopyOnWriteArrayList<>()

    given: "the container environment is started with the mock handler"
    def conditions = new PollingConditions(timeout: 10, initialDelay: 0.1, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def pushNotificationHandler = container.getService(PushNotificationHandler.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def consoleResource = (ConsoleResourceImpl)container.getService(ManagerWebService.class).apiSingletons.find {
      it instanceof ConsoleResourceImpl
    }

    and: "a mock push notification handler"
    def throwPushHandlerException = false
    PushNotificationHandler mockPushNotificationHandler = Spy(pushNotificationHandler)
    mockPushNotificationHandler.isValid() >> true
    mockPushNotificationHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, source, sourceId, target, message ->
      if (throwPushHandlerException) {
        throw new Exception("Failed to send notification")
      }
      notificationIds << id
      notificationTargetTypes << target.type
      notificationTargetIds << target.id
      notificationMessages << message
      callRealMethod()
    }
    // Assume sent to FCM
    mockPushNotificationHandler.sendMessage(_ as com.google.firebase.messaging.Message) >> { message ->
      return NotificationSendResult.success()
    }

    notificationService.notificationHandlerMap.put(pushNotificationHandler.getTypeName(), mockPushNotificationHandler)

    and: "an authenticated test user"
    def realm = keycloakTestSetup.realmBuilding.name
    def testuser1AccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )
    def testuser2AccessToken = authenticate(
            container,
            realm,
            KEYCLOAK_CLIENT_ID,
            "testuser2",
            "testuser2"
    )
    def testuser3AccessToken = authenticate(
            container,
            realm,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )

    and: "an authenticated superuser"
    def adminAccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    def notification = new Notification("TestAction",
            new PushNotificationMessage("Test Action",
            "Click to cancel",
            writeAttributeValueAction(
                    new AttributeRef(
                            managerTestSetup.apartment1LivingroomId,
                            "alarmEnabled"
                            ),
                    false), null, null), null, null, null)

    and: "the notification resource"
    def testuser1NotificationResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, testuser1AccessToken).proxy(NotificationResource.class)
    def testuser2NotificationResource = getClientApiTarget(serverUri(serverPort), realm, testuser2AccessToken).proxy(NotificationResource.class)
    def testuser3NotificationResource = getClientApiTarget(serverUri(serverPort), realm, testuser3AccessToken).proxy(NotificationResource.class)
    def adminNotificationResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminAccessToken).proxy(NotificationResource.class)
    def anonymousNotificationResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name).proxy(NotificationResource.class)
    def testuser1ConsoleResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, testuser1AccessToken).proxy(ConsoleResource.class)
    def testuser2ConsoleResource = getClientApiTarget(serverUri(serverPort), realm, testuser2AccessToken).proxy(ConsoleResource.class)
    def testuser3ConsoleResource = getClientApiTarget(serverUri(serverPort), realm, testuser3AccessToken).proxy(ConsoleResource.class)
    def adminConsoleResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminAccessToken).proxy(ConsoleResource.class)
    def anonymousConsoleResource = getClientApiTarget(serverUri(serverPort), realm).proxy(ConsoleResource.class)
    SentNotification[] notifications = new CopyOnWriteArrayList<>()

    when: "various consoles are registered"
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
                            (Map) parse("{\"token\": \"23123213ad2313b0897efd\"}").orElse(null)
                            )
                    ),
            "",
            ["manager"] as String[])
    def testuser2Console = testuser2ConsoleResource.register(null, consoleRegistration)
    def testuser3Console1 = testuser3ConsoleResource.register(null, consoleRegistration)
    def testuser3Console2 = testuser3ConsoleResource.register(null, consoleRegistration)
    def adminConsole = adminConsoleResource.register(null, consoleRegistration)
    def anonymousConsole = anonymousConsoleResource.register(null, consoleRegistration)

    then: "the consoles should have been created"
    testuser2Console.id != null
    testuser3Console1.id != null
    testuser3Console2.id != null
    adminConsole.id != null
    anonymousConsole.id != null

    when: "the admin user sends a push notification to an entire realm"
    notification.targets = [new Notification.Target(Notification.TargetType.REALM, keycloakTestSetup.realmBuilding.name)]
    adminNotificationResource.sendNotification(null, notification)

    then: "all consoles in that realm should have been sent a notification (excluding testuser2 as they have disabled push notifications)"
    conditions.eventually {
      assert notificationIds.size() == 3
      assert notificationTargetTypes.count { t -> t == Notification.TargetType.ASSET } == 3
      assert !notificationTargetIds.contains(testuser2Console.id)
      assert notificationTargetIds.contains(testuser3Console1.id)
      assert notificationTargetIds.contains(testuser3Console2.id)
      assert notificationTargetIds.contains(anonymousConsole.id)
      assert notificationMessages.count { m ->
        m instanceof PushNotificationMessage && m.title == "Test Action" && m.body == "Click to cancel" && m.action != null
      } == 3
    }

    when: "a regular user without write:notifications sends a push notification to an entire realm"
    testuser2NotificationResource.sendNotification(null, notification)

    then: "the request should be forbidden (no write:notifications role)"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "the admin user sends a notification to a user in a different realm with emailNotificationsDisabled set to true"
    notification.targets = [new Notification.Target(Notification.TargetType.USER, keycloakTestSetup.testuser2Id)]
    advancePseudoClock(1, TimeUnit.HOURS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "no notification should have been sent"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
    notificationIds.size() == 3

    when: "the admin user sends a notification to a user in a different realm with emailNotificationsDisabled set to false"
    notification.targets = [new Notification.Target(Notification.TargetType.USER, keycloakTestSetup.testuser3Id)]
    advancePseudoClock(1, TimeUnit.HOURS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "the notification should have been sent"
    conditions.eventually {
      assert notificationIds.size() == 5
    }

    when: "a regular user sends a push notification to a user in a different realm"
    notification.targets = [new Notification.Target(Notification.TargetType.USER, keycloakTestSetup.testuser2Id)]
    testuser1NotificationResource.sendNotification(null, notification)

    then: "no notification should have been sent"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a restricted user sends a push notification to another user in the same realm"
    notification.targets = [new Notification.Target(Notification.TargetType.USER, keycloakTestSetup.testuser2Id)]
    testuser3NotificationResource.sendNotification(null, notification)

    then: "no notification should have been sent"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "an anonymous user sends a push notification to a user"
    anonymousNotificationResource.sendNotification(null, notification)

    then: "the request should be forbidden (no write:notifications role)"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "the admin user sends a push notification to the console assets in building realm"
    notificationIds.clear()
    notification.targets = [
      new Notification.Target(Notification.TargetType.ASSET, testuser2Console.id),
      new Notification.Target(Notification.TargetType.ASSET, testuser3Console1.id),
      new Notification.Target(Notification.TargetType.ASSET, testuser3Console2.id),
      new Notification.Target(Notification.TargetType.ASSET, anonymousConsole.id)
    ]
    advancePseudoClock(1, TimeUnit.HOURS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "the notification should have been sent (inc. testuser2 as message was direct to console)"
    conditions.eventually {
      assert notificationIds.size() == 4
      assert notificationTargetIds.contains(testuser2Console.id)
      assert notificationTargetIds.contains(testuser3Console1.id)
      assert notificationTargetIds.contains(testuser3Console2.id)
      assert notificationTargetIds.contains(anonymousConsole.id)
    }

    when: "a regular user sends a push notification to the console assets in a different realm"
    testuser1NotificationResource.sendNotification(null, notification)

    then: "no notification should have been sent"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a regular user without write:notifications sends a push notification to the console assets in the same realm"
    notificationIds.clear()
    advancePseudoClock(1, TimeUnit.HOURS, container)
    testuser2NotificationResource.sendNotification(null, notification)

    then: "the request should be forbidden and no notification sent (write:notifications is now required to send)"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
    notificationIds.size() == 0

    when: "a notification is sent using the same mechanism as an asset ruleset"
    notificationIds.clear()
    notificationService.sendNotification(notification, Notification.Source.ASSET_RULESET, consoleResource.getConsoleParentAssetId(realm))

    then: "the notification should have been sent (inc. testuser2 as message was direct to console)"
    conditions.eventually {
      assert notificationIds.size() == 4
      assert notificationTargetIds.contains(testuser2Console.id)
      assert notificationTargetIds.contains(testuser3Console1.id)
      assert notificationTargetIds.contains(testuser3Console2.id)
      assert notificationTargetIds.contains(anonymousConsole.id)
    }

    when: "a restricted user sends a push notification to the console assets in the same realm"
    testuser3NotificationResource.sendNotification(null, notification)

    then: "no notification should have been sent"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "a restricted user sends a push notification to some consoles linked to them and some not linked to them"
    notificationIds.clear()
    notification.targets = [
      new Notification.Target(Notification.TargetType.ASSET, testuser2Console.id),
      new Notification.Target(Notification.TargetType.ASSET, testuser3Console1.id),
      new Notification.Target(Notification.TargetType.ASSET, testuser3Console2.id),
      new Notification.Target(Notification.TargetType.ASSET, anonymousConsole.id)
    ]
    testuser3NotificationResource.sendNotification(null, notification)

    then: "no notification should have been sent"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and: "no new notifications should have been sent"
    notificationIds.size() == 0

    when: "a restricted user sends a push notification to some consoles linked to them"
    advancePseudoClock(1, TimeUnit.HOURS, container)
    notification.targets = [
      new Notification.Target(Notification.TargetType.ASSET, testuser3Console1.id),
      new Notification.Target(Notification.TargetType.ASSET, testuser3Console2.id)
    ]
    testuser3NotificationResource.sendNotification(null, notification)

    then: "the notifications should have been sent"
    conditions.eventually {
      assert notificationIds.size() == 2
      assert notificationTargetIds.contains(testuser3Console1.id)
      assert notificationTargetIds.contains(testuser3Console2.id)
    }

    when: "the FCM token is set to null for a console asset"
    notificationIds.clear()
    notificationTargetIds.clear()
    def testUser3Console1Asset = assetStorageService.find(testuser3Console1.id) as ConsoleAsset
    testUser3Console1Asset.getConsoleProviders().map{
      it.get(PushNotificationMessage.TYPE)
    }.get().getData().put("token", null)
    testUser3Console1Asset = assetStorageService.merge(testUser3Console1Asset)

    then: "the cached FCM token should be removed from the handler"
    conditions.eventually {
      assert pushNotificationHandler.consoleFCMTokenMap.get(testUser3Console1Asset.id) == null
    }

    when: "the admin user sends a notification to a user linked to the console without an FCM token"
    notification.targets = [new Notification.Target(Notification.TargetType.USER, keycloakTestSetup.testuser3Id)]
    advancePseudoClock(1, TimeUnit.HOURS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "the notification should have been sent"
    conditions.eventually {
      assert notificationIds.size() == 1
      assert notificationTargetIds.contains(testuser3Console2.id)
      assert !notificationTargetIds.contains(testuser3Console1.id)
    }

    when: "a notification handler throws an exception"
    throwPushHandlerException = true
    advancePseudoClock(1, TimeUnit.HOURS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "a bad request exception should be thrown"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and: "the notification should be recorded in the DB with the exception set as the error"
    conditions.eventually {
      notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null)
      notifications.first().error == "Failed to send notification"
    }


    // -----------------------------------------------
    //    Check notification resource
    // -----------------------------------------------

    and: "all notifications sent to consoles in the building realm should be available via the REST API"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser2Console.id, null, null, null, null, null).length == 2
      notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null)
      assert notifications.length == 5
      assert notifications.every {n ->
        PushNotificationMessage pushMessage = n.message as PushNotificationMessage
        pushMessage.getTitle() == "Test Action" &&
                pushMessage.getBody() == "Click to cancel" &&
                pushMessage.getAction() != null &&
                n.deliveredOn == null &&
                n.acknowledgedOn == null
      }
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 7
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, anonymousConsole.id, null, null, null, null, null).length == 3
    }

    when: "the admin user marks a Building console notification as delivered and requests the notifications for Building consoles"
    adminNotificationResource.notificationDelivered(null, testuser3Console1.id, notifications.find {n ->
      n.targetId == testuser3Console1.id
    }.id)

    then: "the notification should have been updated"
    conditions.eventually {
      notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null)
      assert notifications.length == 5
      assert notifications.count {n -> n.deliveredOn != null} == 1
    }

    when: "the admin user marks a Building console notification as delivered and requests the notifications for Building consoles"
    adminNotificationResource.notificationAcknowledged(null, testuser3Console1.id, notifications.find {n ->
      n.targetId == testuser3Console1.id && n.deliveredOn != null
    }.id, new TextNode("dismissed"))

    then: "the notification should have been updated"
    conditions.eventually {
      notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null)
      assert notifications.length == 5
      assert notifications.count {n ->
        n.deliveredOn != null && n.acknowledgedOn != null && n.acknowledgement == "\"dismissed\""
      } == 1
    }

    when: "a regular user marks a console notification from another realm as delivered"
    testuser1NotificationResource.notificationDelivered(null, testuser3Console1.id, notifications.find {n ->
      n.targetId == testuser3Console1.id
    }.id)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "a regular user marks a console notification for their own console as delivered"
    testuser3NotificationResource.notificationDelivered(null, testuser3Console1.id, notifications.find {n ->
      n.targetId == testuser3Console1.id
    }.id)

    then: "the notification should have been updated"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null).count {
        it.deliveredOn != null
      } == 1
    }

    // TODO: Update once console permissions model finalised
    //        when: "an anonymous user marks a console notification from another console as delivered"
    //        anonymousNotificationResource.notificationDelivered(null, testuser3Console1.id, notifications.find {n -> n.targetId == testuser3Console1.id}.id)
    //
    //        then: "access should be forbidden"
    //        ex = thrown()
    //        ex.response.withCloseable { r ->
    //            assert r.status == 403
    //        }

    when: "an anonymous user marks a console notification for their own console as delivered"
    notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, anonymousConsole.id, null, null, null, null, null)
    anonymousNotificationResource.notificationDelivered(null, anonymousConsole.id, notifications.find {n ->
      n.targetId == anonymousConsole.id
    }.id)

    then: "the notification should have been updated"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, anonymousConsole.id, null, null, null, null, null).count {
        it.deliveredOn != null
      } == 1
    }

    when: "a regular user removes notifications of their own realm"
    testuser1NotificationResource.removeNotifications(null, null, PushNotificationMessage.TYPE, null, null, MASTER_REALM, null, null)

    then: "write:notifications grants the delete and the other realm's notifications are untouched"
    noExceptionThrown()
    adminNotificationResource.getNotifications(null, null, PushNotificationMessage.TYPE, null, null, null, null, null, null, null, null, null, null).length> 0

    when: "a restricted user tries to remove notifications"
    testuser3NotificationResource.removeNotifications(null, null, PushNotificationMessage.TYPE, null, null, realm, null, null)

    then: "access should be forbidden"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when: "the admin user removes notifications by timestamp and the notifications are retrieved again"
    notifications = adminNotificationResource.getNotifications(null, null, PushNotificationMessage.TYPE, null, null, null, null, null, null, null, null, null, null)
    def sentNotification = notifications[0]
    def removeCount = notifications.count {it.sentOn >= sentNotification.sentOn}
    adminNotificationResource.removeNotifications(null, null, PushNotificationMessage.TYPE, sentNotification.sentOn.toEpochMilli(), null, null, null, null)

    then: "notifications sent after or at that time should have been removed"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, PushNotificationMessage.TYPE, null, null, null, null, null, null, null, null, null, null).length == (notifications.length - removeCount)
    }

    when: "the admin user removes notifications sent to specific console assets without other constraints and the notifications are retrieved again"
    adminNotificationResource.removeNotifications(null, null, null, null, null, null, null, testuser3Console1.id)
    adminNotificationResource.removeNotifications(null, null, null, null, null, null, null, testuser3Console2.id)

    then: "all notifications sent to those consoles should have been removed"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null).length == 0
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 0
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, anonymousConsole.id, null, null, null, null, null).length == 3
    }

    when: "the admin user removes notifications by type and the notifications are retrieved again"
    adminNotificationResource.removeNotifications(null, null, PushNotificationMessage.TYPE, null, null, null, null, null)

    then: "the notifications should have been removed"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, PushNotificationMessage.TYPE, null, null, null, null, anonymousConsole.id, null, null, null, null, null).length == 0
    }

    when: "the admin user removes notifications without sufficient constraints"
    adminNotificationResource.removeNotifications(null, null, null, null, null, null, null, null)

    then: "request should not be allowed"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    // -----------------------------------------------
    //    Check notification repeat frequency
    // -----------------------------------------------

    when: "a repeat frequency is set and a notification is sent"
    // Clear exception flag
    throwPushHandlerException = false
    // Move clock to beginning of next hour
    def advancement = Instant.ofEpochMilli(getClockTimeOf(container))
            .truncatedTo(ChronoUnit.HOURS)
            .plus(59, ChronoUnit.MINUTES)

    advancePseudoClock(ChronoUnit.MILLIS.between(Instant.ofEpochMilli(getClockTimeOf(container)), advancement), TimeUnit.MILLISECONDS, container)
    notification.targets = [new Notification.Target(Notification.TargetType.ASSET, testuser3Console2.id)]
    notification.setRepeatFrequency(RepeatFrequency.HOURLY)
    testuser3NotificationResource.sendNotification(null, notification)

    then: "the notification should have been sent to the console"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 1
    }

    when: "a repeat frequency is set and a notification with the same name and scope as a previous notification is sent within the repeat window"
    testuser3NotificationResource.sendNotification(null, notification)

    then: "no new notifications should have been sent"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 1
    }

    when: "a repeat frequency is set and a notification with the same name but different scope is sent within the repeat window"
    adminNotificationResource.sendNotification(null, notification)

    then: "new notifications should have been sent"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 2
    }

    when: "time advances less than the repeat frequency and the notification is sent again"
    advancePseudoClock(30, TimeUnit.SECONDS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "no new notifications should have been sent"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 2
    }

    when: "time advances more than the repeat frequency and the notification is sent again"
    advancePseudoClock(2, TimeUnit.MINUTES, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "new notifications should have been sent"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 3
    }

    when: "a repeat interval is used and a notification with the same name and scope is sent within the repeat window"
    notification.setRepeatInterval("P1M")
    advancePseudoClock(10, TimeUnit.DAYS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "no new notifications should have been sent"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 3
    }

    when: "time advances more than the repeat interval and the notification is sent again"
    advancePseudoClock(25, TimeUnit.DAYS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "new notifications should have been sent"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 4
    }

    and: "notifications are retrieved only for the past day only the relevant notifications should have been returned"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, getClockTimeOf(container)-(3600000*24), null, null, null, testuser3Console2.id, null, null, null, null, null).length == 1
    }

    and: "notifications are retrieved only for the past 40 days only the relevant notifications should have been returned"
    conditions.eventually {
      assert adminNotificationResource.getNotifications(null, null, null, getClockTimeOf(container)-(3600000L*24*40), null, null, null, testuser3Console2.id, null, null, null, null, null).length == 4
    }

    cleanup: "the mock is removed"
    notificationService.notificationHandlerMap.put(pushNotificationHandler.getTypeName(), pushNotificationHandler)
  }

  def "#source device push with mode #pushTargetType and targets #targetNames is allowed: #allowed"() {
    given: "a real notification route and target mapper with only delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Custom push target ${UniqueIdentifierGenerator.generateId()}"
    List<String> deliveredTargetIds = new CopyOnWriteArrayList<>()
    List<String> createdAssetIds = []
    def storedNotifications = {
      notificationService.getNotifications(null, [PushNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.isValid() >> true
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargetIds.add(target.id)
            }
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "push consoles inside the rule scope, outside its subtree and in another realm"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def createConsole = { String name, String consoleRealm, String parentId ->
      def console = assetStorageService.merge(new ConsoleAsset(name).setRealm(consoleRealm).setParentId(parentId)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      console
    }
    def allowedConsole = createConsole("${notificationName} allowed", realm, scopedAsset.id)
    createConsole("${notificationName} unrelated", realm, null)
    createConsole("${notificationName} foreign", keycloakSetup.realmCity.name, null)
    def targets = [
      valid: new Notification.Target(Notification.TargetType.ASSET, allowedConsole.id),
      custom: new Notification.Target(Notification.TargetType.CUSTOM, "unsupported-device-target")
    ]
    def notification = new Notification(notificationName,
            new PushNotificationMessage().setTitle(notificationName).setTargetType(pushTargetType),
            targetNames.collect { targets[it] }, null, null)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm

    when: "the service receives the same source headers as the rules facade"
    // Use request/reply on the real queue so all processing has completed before checking absence
    // of delivery and persistence. No FCM calls are made by the capture above.
    def result = notificationService.sendNotification(notification, source, sourceId)

    then: "unsupported targets reject the entire request before any delivery or stored record"
    def persisted = storedNotifications()
    if (allowed) {
      verifyAll {
        result
        deliveredTargetIds == [allowedConsole.id]
        persisted.size() == 1
        persisted[0].target == Notification.TargetType.ASSET
        persisted[0].targetId == allowedConsole.id
        persisted[0].realm == realm
        persisted[0].source == source
        persisted[0].sourceId == sourceId
        persisted[0].error == null
      }
    } else {
      verifyAll {
        !result
        deliveredTargetIds.isEmpty()
        persisted.isEmpty()
      }
    }

    cleanup:
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, pushHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }

    where:
    [source, pushTargetType, targetNames, allowed] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [null, PushNotificationMessage.TargetType.DEVICE].collectMany { pushTargetType ->
        [
          [["valid"], true],
          [["custom"], false],
          [["valid", "custom"], false],
          [["custom", "valid"], false]
        ].collect { row -> [source, pushTargetType] + row }
      }
    }
  }

  def "#source push with mode #pushTargetType and #targetSelection targets is allowed: #allowed"() {
    given: "the real notification route and push mapper with delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Scoped push audience ${UniqueIdentifierGenerator.generateId()}"
    List<Map> deliveredTargets = new CopyOnWriteArrayList<>()
    List<String> createdAssetIds = []
    def storedNotifications = {
      notificationService.getNotifications(null, [PushNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.isValid() >> true
    // Intercept before FCM token lookup: a later delivery failure must not mask missing authorization.
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargets.add([type: target.type, id: target.id])
            }
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "a console that is a valid target for both realm and asset rules"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def console = assetStorageService.merge(new ConsoleAsset("${notificationName} console")
            .setRealm(realm).setParentId(scopedAsset.id)
            .setConsoleProvider(PushNotificationMessage.TYPE,
            new ConsoleProvider("fcm", true, true, true, true, false,
            [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
    createdAssetIds.add(console.id)
    def targets = [
      absent: null,
      empty: [],
      inScope: [new Notification.Target(Notification.TargetType.ASSET, console.id)]
    ][targetSelection]
    def audience = pushTargetType == PushNotificationMessage.TargetType.TOPIC ? "maintenance" :
            pushTargetType == PushNotificationMessage.TargetType.CONDITION ? "'maintenance' in topics && 'staff' in topics" : null
    def notification = new Notification(notificationName,
            new PushNotificationMessage().setTitle(notificationName).setTargetType(pushTargetType).setTarget(audience),
            targets, null, null)

    and: "the appropriate rules facade and a completion observer for its notification route"
    def engineId = new RulesEngineId()
    def sourceId = ""
    if (source == Notification.Source.REALM_RULESET) {
      engineId = new RulesEngineId(realm)
      sourceId = realm
    } else if (source == Notification.Source.ASSET_RULESET) {
      engineId = new RulesEngineId(realm, scopedAsset.id)
      sourceId = scopedAsset.id
    }
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the notification is submitted by its source"
    if (source == Notification.Source.INTERNAL) {
      notificationService.sendNotificationAsync(notification, source, sourceId)
    } else {
      facade.send(notification)
    }

    then: "scoped topic and condition requests are rejected before persistence or delivery"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    if (allowed) {
      def expectedType = audience == null ? Notification.TargetType.ASSET : Notification.TargetType.CUSTOM
      def expectedId = audience == null ? console.id : "${pushTargetType}: ${audience}".toString()
      verifyAll {
        deliveredTargets == [[type: expectedType, id: expectedId]]
        persisted.size() == 1
        persisted[0].target == expectedType
        persisted[0].targetId == expectedId
        persisted[0].realm == (audience == null ? realm : MASTER_REALM)
        persisted[0].source == source
        persisted[0].sourceId == sourceId
        persisted[0].error == null
      }
    } else {
      verifyAll {
        deliveredTargets.isEmpty()
        persisted.isEmpty()
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, pushHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }

    where:
    [source, pushTargetType, targetSelection, allowed] << (
    [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [PushNotificationMessage.TargetType.TOPIC, PushNotificationMessage.TargetType.CONDITION].collectMany { pushTargetType ->
        ["absent", "empty", "inScope"].collect { targetSelection ->
          [source, pushTargetType, targetSelection, false]
        }
      } + [null, PushNotificationMessage.TargetType.DEVICE].collect { pushTargetType ->
        [source, pushTargetType, "inScope", true]
      }
    } + [Notification.Source.GLOBAL_RULESET, Notification.Source.INTERNAL].collectMany { source ->
      [PushNotificationMessage.TargetType.TOPIC, PushNotificationMessage.TargetType.CONDITION].collect { pushTargetType ->
        [source, pushTargetType, "absent", true]
      }
    })
  }

  def "Global rules facade sends push notifications and stores them in each recipient realm"() {
    given: "a global notifications facade using the real notification route"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def facade = new NotificationsFacade(new RulesEngineId(), notificationService)
    def notificationName = "Global rules push ${UniqueIdentifierGenerator.generateId()}"
    List<String> deliveredTargetIds = new CopyOnWriteArrayList<>()
    List<String> createdAssetIds = []
    def storedNotifications = {
      notificationService.getNotifications(null, [PushNotificationMessage.TYPE], null, null,
      null, null, null, Notification.Source.GLOBAL_RULESET, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }

    and: "only push delivery is intercepted, so no FCM calls are made"
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.isValid() >> true
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, source, sourceId, target, message ->
              deliveredTargetIds.add(target.id)
            }
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "the notification targets a console in each of two realms"
    def consoles = [keycloakSetup.realmBuilding.name, keycloakSetup.realmCity.name].collect { realm ->
      def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${realm}").setRealm(realm)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      console
    }
    def expectedRealms = consoles.collectEntries { [(it.id): it.realm] }
    def notification = new Notification(notificationName,
            new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE),
            consoles.collect {
              new Notification.Target(Notification.TargetType.ASSET, it.id)
            }, null, null)

    when: "the global rule submits the notification through its facade"
    facade.send(notification)

    then: "submission succeeds without a realm or asset source ID"
    noExceptionThrown()

    and: "both recipients receive a notification stored under their own realm"
    conditions.eventually {
      assert deliveredTargetIds.size() == expectedRealms.size()
      assert deliveredTargetIds.toSet() == expectedRealms.keySet()
      def persisted = storedNotifications()
      assert persisted.size() == expectedRealms.size()
      assert persisted.collectEntries { [(it.targetId): it.realm] } == expectedRealms
      assert persisted.every {
        it.target == Notification.TargetType.ASSET &&
                it.source == Notification.Source.GLOBAL_RULESET &&
                it.sourceId == "" && it.error == null
      }
    }

    cleanup:
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, pushHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
  }

  def "Asset rules #messageType notification to users #targetNames is allowed: #allowed"() {
    given: "an asset-scoped facade with real identity, asset and notification services"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Asset rules users ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<Map> createdUsers = []
    List<String> deliveredTargetIds = new CopyOnWriteArrayList<>()
    List<String> expectedTargetIds = []
    def storedNotifications = {
      notificationService.getNotifications(null, [messageType], null, null,
      null, null, null, Notification.Source.ASSET_RULESET, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }

    and: "only delivery is intercepted, leaving access checks and target mapping intact"
    def push = messageType == PushNotificationMessage.TYPE
    def handler = container.getService(push ? PushNotificationHandler : EmailNotificationHandler)
    def capturedHandler = Spy(handler)
    capturedHandler.isValid() >> true
    capturedHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, source, sourceId, target, message ->
              deliveredTargetIds.add(target.id)
            }
    notificationService.notificationHandlerMap.put(messageType, capturedHandler)

    and: "the scoped asset has an ancestor, a descendant and an unrelated asset in the same realm"
    def createAsset = { String name, String parentId ->
      def asset = assetStorageService.merge(new ThingAsset("${notificationName} ${name}").setRealm(realm).setParentId(parentId))
      createdAssetIds.add(asset.id)
      asset
    }
    def ancestor = createAsset("ancestor", null)
    def scopedAsset = createAsset("scope", ancestor.id)
    def child = createAsset("child", scopedAsset.id)
    def unrelated = createAsset("unrelated", null)
    def facade = new NotificationsFacade(new RulesEngineId(realm, scopedAsset.id), notificationService)
    def scopeLinks = [restrictedScope: scopedAsset.id, restrictedChild: child.id,
      restrictedAncestor: ancestor.id, restrictedUnrelated: unrelated.id]

    and: "recipients have the specified restricted role and direct asset links"
    def targets = targetNames.collect { targetName ->
      def userRealm = targetName == "foreign" ? keycloakSetup.realmCity.name : realm
      def restricted = targetName.startsWith("restricted")
      def username = "scoped-notif-${UniqueIdentifierGenerator.generateId()}"
      def user = identityProvider.createUpdateUser(userRealm,
              new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true), null, false)
      createdUsers.add([realm: userRealm, id: user.id])
      identityProvider.updateUserClientRoles(userRealm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
      if (restricted) {
        identityProvider.updateUserRealmRoles(userRealm, user.id,
                identityProvider.addUserRealmRoles(userRealm, user.id, RESTRICTED_USER_REALM_ROLE))
      }
      assert identityProvider.getUserRealmRoles(userRealm, user.id).contains(RESTRICTED_USER_REALM_ROLE) == restricted
      if (scopeLinks[targetName] != null) {
        assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, scopeLinks[targetName])])
      }

      if (push) {
        // A linked console enables delivery; it does not grant access to the scoped asset itself.
        def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${targetName}")
                .setRealm(userRealm).setParentId(userRealm == realm ? scopedAsset.id : null)
                .setConsoleProvider(PushNotificationMessage.TYPE,
                new ConsoleProvider("fcm", true, true, true, true, false,
                [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
        createdAssetIds.add(console.id)
        assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, console.id)])
        expectedTargetIds.add(console.id)
      } else {
        expectedTargetIds.add(user.id)
      }
      new Notification.Target(Notification.TargetType.USER, user.id)
    }
    def notificationMessage = push
            ? new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE)
            : new EmailNotificationMessage().setSubject(notificationName).setText("Asset rule notification")
    def notification = new Notification(notificationName, notificationMessage, targets, null, null)

    and: "route completion is observed before asserting the absence of side effects"
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == Notification.Source.ASSET_RULESET &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == scopedAsset.id
            }
            .whenDone(1).create()

    when: "the asset rule sends directly to these users"
    facade.send(notification)

    then: "the entire request is accepted or rejected before delivery and persistence"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    if (allowed) {
      verifyAll {
        deliveredTargetIds.size() == expectedTargetIds.size()
        deliveredTargetIds.toSet() == expectedTargetIds.toSet()
        persisted.size() == expectedTargetIds.size()
        persisted.collect { it.targetId }.toSet() == expectedTargetIds.toSet()
        persisted.every {
          it.target == (push ? Notification.TargetType.ASSET : Notification.TargetType.USER) &&
                  it.realm == realm && it.source == Notification.Source.ASSET_RULESET &&
                  it.sourceId == scopedAsset.id && it.error == null
        }
      }
    } else {
      verifyAll {
        deliveredTargetIds.isEmpty()
        persisted.isEmpty()
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(messageType, handler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    [messageType, targetNames, allowed] << [PushNotificationMessage.TYPE, EmailNotificationMessage.TYPE].collectMany { messageType ->
      [
        [["unrestricted"], true],
        [["restrictedScope"], true],
        [["unrestricted", "restrictedScope"], true],
        [["restrictedUnlinked"], false],
        [["restrictedChild"], false],
        [["restrictedAncestor"], false],
        [["restrictedUnrelated"], false],
        [["foreign"], false],
        [["unrestricted", "restrictedUnlinked"], false],
        [["restrictedUnlinked", "unrestricted"], false],
        [["restrictedScope", "restrictedChild"], false],
        [["restrictedChild", "restrictedScope"], false],
        [["unrestricted", "foreign"], false],
        [["foreign", "unrestricted"], false]
      ].collect { row -> [messageType] + row }
    }
  }

  def "#source #messageType notification with user targets #targetNames is allowed: #allowed"() {
    given: "real rule notification processing with only delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Missing notification user ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<String> createdUserIds = []
    List<String> deliveredTargetIds = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [messageType], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    def push = messageType == PushNotificationMessage.TYPE
    def handler = container.getService(push ? PushNotificationHandler : EmailNotificationHandler)
    def capturedHandler = Spy(handler)
    capturedHandler.isValid() >> true
    capturedHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargetIds.add(target.id)
            }
    notificationService.notificationHandlerMap.put(messageType, capturedHandler)

    and: "a valid same-realm recipient and a user ID that does not exist"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def username = "missing-notif-${UniqueIdentifierGenerator.generateId()}"
    def user = identityProvider.createUpdateUser(realm,
            new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true), null, false)
    createdUserIds.add(user.id)
    identityProvider.updateUserClientRoles(realm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
    assert !identityProvider.getUserRealmRoles(realm, user.id).contains(RESTRICTED_USER_REALM_ROLE)
    def missingUserId = UUID.randomUUID().toString()
    assert identityProvider.getUser(missingUserId) == null
    def expectedTargetId = user.id
    if (push) {
      def console = assetStorageService.merge(new ConsoleAsset("${notificationName} console")
              .setRealm(realm).setParentId(scopedAsset.id)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, console.id)])
      expectedTargetId = console.id
    }
    def userIds = [valid: user.id, missing: missingUserId]
    def notificationMessage = push
            ? new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE)
            : new EmailNotificationMessage().setSubject(notificationName).setText("Missing recipient test")
    def notification = new Notification(notificationName, notificationMessage,
            targetNames.collect {
              new Notification.Target(Notification.TargetType.USER, userIds[it])
            }, null, null)

    and: "a facade for the requested scope and an observer that waits for processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule submits the user targets"
    facade.send(notification)

    then: "a missing user rejects the whole request before any delivery or stored record"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    if (allowed) {
      verifyAll {
        deliveredTargetIds == [expectedTargetId]
        persisted.size() == 1
        persisted[0].target == (push ? Notification.TargetType.ASSET : Notification.TargetType.USER)
        persisted[0].targetId == expectedTargetId
        persisted[0].realm == realm
        persisted[0].source == source
        persisted[0].sourceId == sourceId
        persisted[0].error == null
      }
    } else {
      verifyAll {
        deliveredTargetIds.isEmpty()
        persisted.isEmpty()
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(messageType, handler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUserIds.each { identityProvider.deleteUser(realm, it) }

    where:
    [source, messageType, targetNames, allowed] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [PushNotificationMessage.TYPE, EmailNotificationMessage.TYPE].collectMany { messageType ->
        [
          [["valid"], true],
          [["missing"], false],
          [["valid", "missing"], false],
          [["missing", "valid"], false]
        ].collect { row -> [source, messageType] + row }
      }
    }
  }

  def "#source push to #targetType with linked consoles #consoleNames and restricted user #restricted keeps #expectedConsoleNames"() {
    given: "real rule notification processing with only push delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Rule push expansion ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<String> createdUserIds = []
    List<String> deliveredTargetIds = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [PushNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.isValid() >> true
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargetIds.add(target.id)
            }
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "push consoles exist inside the scope, outside its subtree and in another realm"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def createConsole = { String name, String consoleRealm, String parentId ->
      def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${name}")
              .setRealm(consoleRealm).setParentId(parentId)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      console
    }
    def consoles = [scope: createConsole("scope", realm, scopedAsset.id),
      outside: createConsole("outside", realm, null),
      foreign: createConsole("foreign", keycloakSetup.realmCity.name, null)]

    and: "an authorized same-realm user is linked to the selected consoles"
    def username = "push-expansion-${UniqueIdentifierGenerator.generateId()}"
    def user = identityProvider.createUpdateUser(realm,
            new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true), null, false)
    createdUserIds.add(user.id)
    identityProvider.updateUserClientRoles(realm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
    if (restricted) {
      identityProvider.updateUserRealmRoles(realm, user.id,
              identityProvider.addUserRealmRoles(realm, user.id, RESTRICTED_USER_REALM_ROLE))
      assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, scopedAsset.id)])
    }
    assert identityProvider.getUserRealmRoles(realm, user.id).contains(RESTRICTED_USER_REALM_ROLE) == restricted
    // Deliberately seed cross-realm links through the storage service: REST creation rejects them,
    // but notification expansion must also respect the realm when such links exist in stored data.
    assetStorageService.storeUserAssetLinks(consoleNames.collect {
      new UserAssetLink(realm, user.id, consoles[it].id)
    })
    def expectedTargetIds = expectedConsoleNames.collect { consoles[it].id }
    def targets = targetType == Notification.TargetType.USER
            ? [new Notification.Target(Notification.TargetType.USER, user.id)]
            : consoleNames.collect {
              new Notification.Target(Notification.TargetType.ASSET, consoles[it].id)
            }
    def notification = new Notification(notificationName,
            new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE),
            targets, null, null)

    and: "a facade for the requested scope and an observer that waits for processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends to the user or explicitly to their consoles"
    facade.send(notification)

    then: "only the expected consoles receive a notification and have a stored record"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    verifyAll {
      deliveredTargetIds.size() == expectedTargetIds.size()
      deliveredTargetIds.toSet() == expectedTargetIds.toSet()
      persisted.size() == expectedTargetIds.size()
      persisted.collect { it.targetId }.toSet() == expectedTargetIds.toSet()
      persisted.every {
        it.target == Notification.TargetType.ASSET && it.realm == realm &&
                it.source == source && it.sourceId == sourceId && it.error == null
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, pushHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUserIds.each { identityProvider.deleteUser(realm, it) }

    where:
    [source, restricted, targetType, consoleNames, expectedConsoleNames] << (
    [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [false, true].collectMany { restricted ->
        [
          [["scope"], ["scope"]],
          [["outside"], ["outside"]],
          [["foreign"], []],
          [["scope", "foreign"], ["scope"]],
          [["outside", "foreign"], ["outside"]]
        ].collect { row -> [source, restricted, Notification.TargetType.USER] + row }
      }
    } + [
      // A user link permits delivery through a USER target; it does not widen direct ASSET scope.
      [Notification.Source.ASSET_RULESET, false, Notification.TargetType.ASSET, ["outside"], []],
      [Notification.Source.ASSET_RULESET, false, Notification.TargetType.ASSET, ["scope", "outside"], []],
      [Notification.Source.ASSET_RULESET, false, Notification.TargetType.ASSET, ["outside", "scope"], []]
    ])
  }

  def "#source email to an asset with linked users #userNames and asset email #hasAssetEmail keeps users #expectedUserNames"() {
    given: "real rule notification processing with only email delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def emailHandler = container.getService(EmailNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Rule email expansion ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<Map> createdUsers = []
    List<Map> deliveredRecipients = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [EmailNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    EmailNotificationHandler capturedEmailHandler = Spy(emailHandler)
    capturedEmailHandler.isValid() >> true
    capturedEmailHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredRecipients.add([id: target.id, type: target.type,
                address: (target.data as EmailNotificationMessage.Recipient)?.address])
            }
    notificationService.notificationHandlerMap.put(EmailNotificationMessage.TYPE, capturedEmailHandler)

    and: "the target is a descendant asset with an optional email address of its own"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def assetEmail = "asset-${UUID.randomUUID()}@example.com"
    def targetAsset = new ThingAsset("${notificationName} target").setRealm(realm).setParentId(scopedAsset.id)
    if (hasAssetEmail) {
      targetAsset.setEmail(assetEmail)
    }
    targetAsset = assetStorageService.merge(targetAsset)
    createdAssetIds.add(targetAsset.id)

    and: "unrestricted users from the selected realms are linked to the target asset"
    def users = userNames.collectEntries { userName ->
      def userRealm = userName == "foreign" ? keycloakSetup.realmCity.name : realm
      def username = "email-expansion-${UniqueIdentifierGenerator.generateId()}"
      def user = identityProvider.createUpdateUser(userRealm,
              new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true), null, false)
      createdUsers.add([realm: userRealm, id: user.id])
      identityProvider.updateUserClientRoles(userRealm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
      assert !identityProvider.getUserRealmRoles(userRealm, user.id).contains(RESTRICTED_USER_REALM_ROLE)
      // Seed cross-realm links through storage to exercise filtering of existing data, independently
      // of the REST checks that prevent creating such links.
      assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, targetAsset.id)])
      [(userName): user]
    }
    def expectedRecipients = expectedUserNames.collectEntries { userName ->
      def user = users[userName]
      [(user.id): [type: Notification.TargetType.USER, address: user.email]]
    }
    if (hasAssetEmail) {
      expectedRecipients[targetAsset.id] = [type: Notification.TargetType.ASSET, address: assetEmail]
    }
    def notification = new Notification(notificationName,
            new EmailNotificationMessage().setSubject(notificationName).setText("Asset recipient expansion"),
            [new Notification.Target(Notification.TargetType.ASSET, targetAsset.id)], null, null)

    and: "a facade for the requested scope and an observer that waits for processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends an email notification to the asset"
    facade.send(notification)

    then: "foreign users are filtered while valid users and the asset email retain delivery and stored records"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    verifyAll {
      deliveredRecipients.size() == expectedRecipients.size()
      deliveredRecipients.collectEntries {
        [(it.id): [type: it.type, address: it.address]]
      } == expectedRecipients
      persisted.size() == expectedRecipients.size()
      persisted.collectEntries {
        [(it.targetId): it.target]
      } == expectedRecipients.collectEntries { id, recipient ->
        [(id): recipient.type]
      }
      persisted.every {
        it.realm == realm && it.source == source && it.sourceId == sourceId && it.error == null
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(EmailNotificationMessage.TYPE, emailHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    [source, hasAssetEmail, userNames, expectedUserNames] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [false, true].collectMany { hasAssetEmail ->
        [
          [[], []],
          [["valid"], ["valid"]],
          [["foreign"], []],
          [["valid", "foreign"], ["valid"]]
        ].collect { row -> [source, hasAssetEmail] + row }
      }
    }
  }

  def "#source push to an asset with linked users #userNames keeps consoles for #expectedUserNames"() {
    given: "real rule notification processing with only push delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Asset push user realms ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<Map> createdUsers = []
    List<Notification.Target> deliveredTargets = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [PushNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.isValid() >> true
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargets.add(target)
            }
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "the target is a descendant asset in the rule's realm"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def targetAsset = assetStorageService.merge(new ThingAsset("${notificationName} target")
            .setRealm(realm).setParentId(scopedAsset.id))
    createdAssetIds.add(targetAsset.id)

    and: "users from the selected realms are linked to the target and each has an in-scope console"
    def consoles = userNames.collectEntries { userName ->
      def userRealm = userName == "foreign" ? keycloakSetup.realmCity.name : realm
      def username = "asset-push-realm-${UniqueIdentifierGenerator.generateId()}"
      def user = identityProvider.createUpdateUser(userRealm,
              new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true), null, false)
      createdUsers.add([realm: userRealm, id: user.id])
      identityProvider.updateUserClientRoles(userRealm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
      assert !identityProvider.getUserRealmRoles(userRealm, user.id).contains(RESTRICTED_USER_REALM_ROLE)

      // Every console is in the rule's realm and subtree: only the intermediate user's realm
      // distinguishes eligible recipients from foreign users.
      def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${userName}")
              .setRealm(realm).setParentId(targetAsset.id)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      // Seed cross-realm links through storage to exercise existing inconsistent data,
      // independently of the REST checks that prevent creating these links.
      assetStorageService.storeUserAssetLinks([
        new UserAssetLink(userRealm, user.id, targetAsset.id),
        new UserAssetLink(userRealm, user.id, console.id)
      ])
      [(userName): console]
    }
    def expectedTargets = expectedUserNames.collectEntries { userName ->
      [(consoles[userName].id): Notification.TargetType.ASSET]
    }
    def notification = new Notification(notificationName,
            new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE),
            [new Notification.Target(Notification.TargetType.ASSET, targetAsset.id)], null, null)

    and: "a facade for the requested scope and an observer that waits for processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends to the asset and its linked users are expanded into console targets"
    facade.send(notification)

    then: "only consoles reached through same-realm users receive notifications and have stored records"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    verifyAll {
      deliveredTargets.size() == expectedTargets.size()
      deliveredTargets.collectEntries { [(it.id): it.type] } == expectedTargets
      persisted.size() == expectedTargets.size()
      persisted.collectEntries { [(it.targetId): it.target] } == expectedTargets
      persisted.every {
        it.realm == realm && it.source == source && it.sourceId == sourceId && it.error == null
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, pushHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    [source, userNames, expectedUserNames] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [
        [["valid"], ["valid"]],
        [["foreign"], []],
        [["valid", "foreign"], ["valid"]],
        [["foreign", "valid"], ["valid"]]
      ].collect { row -> [source] + row }
    }
  }

  def "#source push expansion from an asset to #accountTypes keeps accounts #expectedAccountTypes"() {
    given: "real rule notification processing with only push delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Asset push account types ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<String> createdUserIds = []
    List<Notification.Target> deliveredTargets = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [PushNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.isValid() >> true
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargets.add(target)
            }
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "the notification targets a descendant asset in the rule's realm"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def targetAsset = assetStorageService.merge(new ThingAsset("${notificationName} target")
            .setRealm(realm).setParentId(scopedAsset.id))
    createdAssetIds.add(targetAsset.id)

    and: "the selected account types are linked to the asset and each has its own in-scope console"
    def consoles = accountTypes.collectEntries { accountType ->
      def username = "asset-push-account-${UniqueIdentifierGenerator.generateId()}"
      def user = identityProvider.createUpdateUser(realm,
              new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true)
              .setServiceAccount(accountType == "service").setSystemAccount(accountType == "system"), null, false)
      createdUserIds.add(user.id)
      identityProvider.updateUserClientRoles(realm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
      def storedUser = identityProvider.getUser(user.id)
      assert storedUser.realm == realm
      assert storedUser.isServiceAccount() == (accountType == "service")
      assert storedUser.isSystemAccount() == (accountType == "system")
      assert !identityProvider.getUserRealmRoles(realm, user.id).contains(RESTRICTED_USER_REALM_ROLE)

      // Realm and asset scope are valid for every console; account eligibility must be checked
      // before expanding the users into these delivery targets.
      def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${accountType}")
              .setRealm(realm).setParentId(targetAsset.id)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      assetStorageService.storeUserAssetLinks([
        new UserAssetLink(realm, user.id, targetAsset.id),
        new UserAssetLink(realm, user.id, console.id)
      ])
      [(accountType): console]
    }
    def expectedTargets = expectedAccountTypes.collectEntries { accountType ->
      [(consoles[accountType].id): Notification.TargetType.ASSET]
    }
    def notification = new Notification(notificationName,
            new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE),
            [new Notification.Target(Notification.TargetType.ASSET, targetAsset.id)], null, null)

    and: "a facade for the requested scope and an observer that waits for processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends to the asset and the handler expands its linked accounts"
    facade.send(notification)

    then: "only regular users' consoles receive notifications and have stored records"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    verifyAll {
      deliveredTargets.size() == expectedTargets.size()
      deliveredTargets.collectEntries { [(it.id): it.type] } == expectedTargets
      persisted.size() == expectedTargets.size()
      persisted.collectEntries { [(it.targetId): it.target] } == expectedTargets
      persisted.every {
        it.realm == realm && it.source == source && it.sourceId == sourceId && it.error == null
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(PushNotificationMessage.TYPE, pushHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUserIds.each { identityProvider.deleteUser(realm, it) }

    where:
    [source, accountTypes, expectedAccountTypes] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [
        [["regular"], ["regular"]],
        [["service"], []],
        [["system"], []],
        [["regular", "service"], ["regular"]],
        [["regular", "system"], ["regular"]]
      ].collect { row -> [source] + row }
    }
  }

  def "#source #messageType expansion from a descendant asset to #userNames with asset email #hasAssetEmail keeps #expectedUserNames"() {
    given: "real rule notification processing with only delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Restricted asset recipients ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<String> createdUserIds = []
    List<Notification.Target> deliveredTargets = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [messageType], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    def push = messageType == PushNotificationMessage.TYPE
    def handler = container.getService(push ? PushNotificationHandler : EmailNotificationHandler)
    def capturedHandler = Spy(handler)
    capturedHandler.isValid() >> true
    capturedHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargets.add(target)
            }
    notificationService.notificationHandlerMap.put(messageType, capturedHandler)

    and: "the notification targets a descendant rather than the engine's scoped asset"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def targetAsset = new ThingAsset("${notificationName} target").setRealm(realm).setParentId(scopedAsset.id)
    if (hasAssetEmail) {
      targetAsset.setEmail("asset-${UUID.randomUUID()}@example.com")
    }
    targetAsset = assetStorageService.merge(targetAsset)
    createdAssetIds.add(targetAsset.id)

    and: "all recipients are linked to the target, but only some restricted users are linked to the scope"
    def userTargets = userNames.collectEntries { userName ->
      def restricted = userName.startsWith("restricted")
      def username = "asset-recipient-${UniqueIdentifierGenerator.generateId()}"
      def user = identityProvider.createUpdateUser(realm,
              new User().setUsername(username).setEmail("${username}@example.com").setEnabled(true), null, false)
      createdUserIds.add(user.id)
      identityProvider.updateUserClientRoles(realm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
      if (restricted) {
        identityProvider.updateUserRealmRoles(realm, user.id,
                identityProvider.addUserRealmRoles(realm, user.id, RESTRICTED_USER_REALM_ROLE))
      }
      assert identityProvider.getUserRealmRoles(realm, user.id).contains(RESTRICTED_USER_REALM_ROLE) == restricted
      assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, targetAsset.id)])
      if (userName == "restrictedScope") {
        assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, scopedAsset.id)])
      }
      assert assetStorageService.isUserAssets(user.id, [scopedAsset.id]) == (userName == "restrictedScope")

      def deliveryTarget = new Notification.Target(Notification.TargetType.USER, user.id)
      if (push) {
        // Every console is in scope; authorization must follow the recipient's own scope link.
        def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${userName}")
                .setRealm(realm).setParentId(targetAsset.id)
                .setConsoleProvider(PushNotificationMessage.TYPE,
                new ConsoleProvider("fcm", true, true, true, true, false,
                [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
        createdAssetIds.add(console.id)
        assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, console.id)])
        deliveryTarget = new Notification.Target(Notification.TargetType.ASSET, console.id)
      }
      [(userName): deliveryTarget]
    }
    def expectedTargets = expectedUserNames.collectEntries { userName ->
      def target = userTargets[userName]
      [(target.id): target.type]
    }
    if (hasAssetEmail) {
      expectedTargets[targetAsset.id] = Notification.TargetType.ASSET
    }
    def notificationMessage = push
            ? new PushNotificationMessage().setTitle(notificationName).setTargetType(PushNotificationMessage.TargetType.DEVICE)
            : new EmailNotificationMessage().setSubject(notificationName).setText("Restricted asset recipients")
    def notification = new Notification(notificationName, notificationMessage,
            [new Notification.Target(Notification.TargetType.ASSET, targetAsset.id)], null, null)

    and: "a facade for the requested scope and an observer that waits for processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends to the descendant asset and the handler expands its linked users"
    facade.send(notification)

    then: "only eligible recipients receive notifications and have stored records"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    verifyAll {
      deliveredTargets.size() == expectedTargets.size()
      deliveredTargets.collectEntries { [(it.id): it.type] } == expectedTargets
      persisted.size() == expectedTargets.size()
      persisted.collectEntries { [(it.targetId): it.target] } == expectedTargets
      persisted.every {
        it.realm == realm && it.source == source && it.sourceId == sourceId && it.error == null
      }
    }

    cleanup:
    processed?.destroy()
    notificationService.notificationHandlerMap.put(messageType, handler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUserIds.each { identityProvider.deleteUser(realm, it) }

    where:
    [source, messageType, hasAssetEmail, userNames, expectedUserNames] << (
    [Notification.Source.ASSET_RULESET, Notification.Source.REALM_RULESET].collectMany { source ->
      [PushNotificationMessage.TYPE, EmailNotificationMessage.TYPE].collectMany { messageType ->
        [
          [["unrestricted"], ["unrestricted"]],
          [["restrictedScope"], ["restrictedScope"]],
          [["restrictedChild"], []],
          [["unrestricted", "restrictedChild"], ["unrestricted"]],
          [["restrictedScope", "restrictedChild"], ["restrictedScope"]]
        ].collect { row ->
          [source, messageType, false, row[0], source == Notification.Source.ASSET_RULESET ? row[1] : row[0]]
        }
      }
    } + [
      [Notification.Source.ASSET_RULESET, EmailNotificationMessage.TYPE, true, ["restrictedChild"], []],
      [
        Notification.Source.ASSET_RULESET,
        EmailNotificationMessage.TYPE,
        true,
        ["restrictedScope", "restrictedChild"],
        ["restrictedScope"]
      ]
    ])
  }

  def "Check email notification functionality"() {

    List<Message> sentEmails = new CopyOnWriteArrayList<>()

    given: "the container environment is started with the mock handler"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig() << [(OR_EMAIL_X_HEADERS): "Test 1: Hello World 1\nTest2: Hello World 2"], defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def emailNotificationHandler = container.getService(EmailNotificationHandler.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)

    and: "a mock email notification handler"
    EmailNotificationHandler mockEmailNotificationHandler = Spy(emailNotificationHandler)
    mockEmailNotificationHandler.isValid() >> true

    // Log email and assume sent to SMTP Server
    mockEmailNotificationHandler.sendMessage(_ as Message) >> { Message email ->
      sentEmails << email
      return NotificationSendResult.success()
    }
    notificationService.notificationHandlerMap.put(emailNotificationHandler.getTypeName(), mockEmailNotificationHandler)

    when: "an email notification is sent to a realm through same mechanism as rules"
    def notification = new Notification(
            "Test",
            new EmailNotificationMessage().setSubject("Test").setText("Hello world!"),
            Collections.singletonList(new Notification.Target(Notification.TargetType.REALM, managerTestSetup.realmBuildingName)), null, null)
    notificationService.sendNotification(notification, Notification.Source.REALM_RULESET, managerTestSetup.realmBuildingName)

    then: "the email should have been sent to the realm users with email addresses and email notifications enabled"
    conditions.eventually {
      assert sentEmails.size() == 2
      assert sentEmails.every {it.content == "Hello world!"}
      assert sentEmails.every { it.getSubject() == "Test"}
      assert sentEmails.every {
        it.allHeaders != null && it.getHeader("Test 1")[0] == "Hello World 1" && it.getHeader("Test2")[0] == "Hello World 2"
      }
      assert sentEmails.any {
        it.allRecipients.length == 1 && (it.allRecipients[0] as InternetAddress).address == "testuser2@openremote.local"
      }
      assert !sentEmails.any {
        it.allRecipients.length == 1 && (it.allRecipients[0] as InternetAddress).address == "testuser3@openremote.local"
      }
      assert sentEmails.any {
        it.allRecipients.length == 1 && (it.allRecipients[0] as InternetAddress).address == "building@openremote.local"
      }
    }

    when: "an email attribute is added to an asset"
    sentEmails.clear()
    def kitchen = assetStorageService.find(managerTestSetup.apartment1KitchenId)
    kitchen.setEmail("kitchen@openremote.local")
    kitchen = assetStorageService.merge(kitchen)

    and: "an email notification is sent to a parent asset"
    ((EmailNotificationMessage)notification.message).subject = "Test 2"
    notification.setTargets([new Notification.Target(Notification.TargetType.ASSET, managerTestSetup.apartment1KitchenId)])
    notificationService.sendNotification(notification)

    then: "the child asset with the email attribute should have been sent an email"
    conditions.eventually {
      assert sentEmails.size() == 1
      assert sentEmails.any { it.getSubject() == "Test 2"}
      assert sentEmails.any {
        it.allRecipients.length == 1 && (it.allRecipients[0] as InternetAddress).address == "kitchen@openremote.local"
      }
    }

    when: "an email is sent to a custom target"
    sentEmails.clear()
    ((EmailNotificationMessage)notification.message).subject = "Test Custom"
    notification.setTargets([
      new Notification.Target(Notification.TargetType.CUSTOM, "custom1@openremote.local;to:custom2@openremote.local;cc:custom3@openremote.local;bcc:custom4@openremote.local")
    ])
    notificationService.sendNotification(notification)

    then: "the email should have been sent to all custom recipients"
    conditions.eventually {
      assert sentEmails.size() == 1
      assert sentEmails.any {
        it.getSubject() == "Test Custom" && it.allRecipients.length == 4
      }
      assert sentEmails.any {
        it.getSubject() == "Test Custom" && it.getRecipients(Message.RecipientType.TO).any{
          (it as InternetAddress).address == "custom1@openremote.local"
        }
      }
      assert sentEmails.any {
        it.getSubject() == "Test Custom" && it.getRecipients(Message.RecipientType.TO).any{
          (it as InternetAddress).address == "custom2@openremote.local"
        }
      }
      assert sentEmails.any {
        it.getSubject() == "Test Custom" && it.getRecipients(Message.RecipientType.CC).any{
          (it as InternetAddress).address == "custom3@openremote.local"
        }
      }
      assert sentEmails.any {
        it.getSubject() == "Test Custom" && it.getRecipients(Message.RecipientType.BCC).any{
          (it as InternetAddress).address == "custom4@openremote.local"
        }
      }
    }

    cleanup: "the mock is removed"
    notificationService.notificationHandlerMap.put(emailNotificationHandler.getTypeName(), emailNotificationHandler)
  }

  def "#source localized push with #pushTargetType in nl and default #defaultLanguage is allowed: #allowed"() {
    given: "real scoped notification processing and localization with only push delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def localizedHandler = container.getService(LocalizedNotificationHandler.class)
    def pushHandler = container.getService(PushNotificationHandler.class)
    def realm = keycloakSetup.realmBuilding.name
    def notificationName = "Localized rule push ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<Map> deliveredTargets = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [LocalizedNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    PushNotificationHandler capturedPushHandler = Spy(pushHandler)
    capturedPushHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargets.add([id: id, source: notificationSource, sourceId: notificationSourceId,
                target: target.type, targetId: target.id, title: (message as PushNotificationMessage).title,
                pushTargetType: (message as PushNotificationMessage).targetType])
            }
    def previousLocalizedHandler = notificationService.notificationHandlerMap.put(LocalizedNotificationMessage.TYPE, localizedHandler)
    // The localized handler has its own handler map; keep its validation, mapping and dispatch real.
    def previousPushHandler = localizedHandler.notificationHandlerMap.put(PushNotificationMessage.TYPE, capturedPushHandler)

    and: "an in-scope console without user links so dispatch uses the notification's default language"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def console = assetStorageService.merge(new ConsoleAsset("${notificationName} console")
            .setRealm(realm).setParentId(scopedAsset.id)
            .setConsoleProvider(PushNotificationMessage.TYPE,
            new ConsoleProvider("fcm", true, true, true, true, false,
            [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
    createdAssetIds.add(console.id)

    and: "a valid device message followed by the tested message in another locale"
    def audience = pushTargetType == PushNotificationMessage.TargetType.TOPIC ? "maintenance" :
            pushTargetType == PushNotificationMessage.TargetType.CONDITION ? "'maintenance' in topics && 'staff' in topics" : null
    Map<String, AbstractNotificationMessage> messages = [
      en: new PushNotificationMessage().setTitle("${notificationName} en").setTargetType(PushNotificationMessage.TargetType.DEVICE),
      nl: new PushNotificationMessage().setTitle("${notificationName} nl").setTargetType(pushTargetType).setTarget(audience)
    ]
    def localizedMessage = new LocalizedNotificationMessage(defaultLanguage, messages)
    assert localizedHandler.isMessageValid(localizedMessage)
    def notification = new Notification(notificationName, localizedMessage,
            [new Notification.Target(Notification.TargetType.ASSET, console.id)], null, null)

    and: "a scoped rules facade and an observer that waits for notification processing to finish"
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends the localized notification"
    facade.send(notification)

    then: "device messages are localized, while any forbidden locale rejects all delivery and persistence"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    if (allowed) {
      assert persisted.size() == 1
      verifyAll {
        deliveredTargets == [
          [id: persisted[0].id, source: source, sourceId: sourceId,
            target: Notification.TargetType.ASSET, targetId: console.id,
            title: "${notificationName} ${defaultLanguage}".toString(), pushTargetType: PushNotificationMessage.TargetType.DEVICE]
        ]
        persisted[0].target == Notification.TargetType.ASSET
        persisted[0].targetId == console.id
        persisted[0].realm == realm
        persisted[0].source == source
        persisted[0].sourceId == sourceId
        persisted[0].type == LocalizedNotificationMessage.TYPE
        persisted[0].error == null
      }
    } else {
      verifyAll {
        deliveredTargets.isEmpty()
        persisted.isEmpty()
      }
    }

    cleanup:
    processed?.destroy()
    localizedHandler.notificationHandlerMap.put(PushNotificationMessage.TYPE, previousPushHandler)
    notificationService.notificationHandlerMap.put(LocalizedNotificationMessage.TYPE, previousLocalizedHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }

    where:
    [source, pushTargetType, defaultLanguage] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [
        PushNotificationMessage.TargetType.DEVICE,
        PushNotificationMessage.TargetType.TOPIC,
        PushNotificationMessage.TargetType.CONDITION
      ].collectMany { pushTargetType ->
        ["en", "nl"].collect { defaultLanguage -> [source, pushTargetType, defaultLanguage] }
      }
    }
    allowed = pushTargetType == PushNotificationMessage.TargetType.DEVICE
  }

  def "#source localized #messageType expansion to #userNames keeps #expectedUserNames in their preferred language"() {
    given: "real rule processing, recipient expansion and localization with only delivery intercepted"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def assetStorageService = container.getService(AssetStorageService.class)
    def notificationService = container.getService(NotificationService.class)
    def localizedHandler = container.getService(LocalizedNotificationHandler.class)
    def push = messageType == PushNotificationMessage.TYPE
    def handler = container.getService(push ? PushNotificationHandler : EmailNotificationHandler)
    def realm = keycloakSetup.realmBuilding.name
    def foreignRealm = keycloakSetup.realmCity.name
    def notificationName = "Localized rule recipients ${UniqueIdentifierGenerator.generateId()}"
    List<String> createdAssetIds = []
    List<Map> createdUsers = []
    List<Map> deliveredTargets = new CopyOnWriteArrayList<>()
    def storedNotifications = {
      notificationService.getNotifications(null, [LocalizedNotificationMessage.TYPE], null, null,
      null, null, null, source, null, false, null, null, null).findAll {
        it.name == notificationName
      }
    }
    def capturedHandler = Spy(handler)
    capturedHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String,
            _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, notificationSource, notificationSourceId, target, message ->
              deliveredTargets.add([id: id, source: notificationSource, sourceId: notificationSourceId,
                target: target.type, targetId: target.id, messageType: message.type,
                content: push ? (message as PushNotificationMessage).title : (message as EmailNotificationMessage).subject,
                address: push ? null : (target.data as EmailNotificationMessage.Recipient).address])
            }
    def previousLocalizedHandler = notificationService.notificationHandlerMap.put(LocalizedNotificationMessage.TYPE, localizedHandler)
    def previousHandler = localizedHandler.notificationHandlerMap.put(messageType, capturedHandler)

    and: "a descendant asset with no email address of its own"
    def scopedAsset = assetStorageService.merge(new ThingAsset(notificationName).setRealm(realm))
    createdAssetIds.add(scopedAsset.id)
    def targetAsset = assetStorageService.merge(new ThingAsset("${notificationName} target")
            .setRealm(realm).setParentId(scopedAsset.id))
    createdAssetIds.add(targetAsset.id)
    def createConsole = { String name, String consoleRealm, String parentId ->
      def console = assetStorageService.merge(new ConsoleAsset("${notificationName} ${name}")
              .setRealm(consoleRealm).setParentId(parentId)
              .setConsoleProvider(PushNotificationMessage.TYPE,
              new ConsoleProvider("fcm", true, true, true, true, false,
              [token: "test-${UniqueIdentifierGenerator.generateId()}"])))
      createdAssetIds.add(console.id)
      console
    }

    and: "users with different eligibility and language preferences are linked to the target"
    def recipients = userNames.collectEntries { userName ->
      def userRealm = userName == "foreign" ? foreignRealm : realm
      def restricted = userName.startsWith("restricted")
      def language = userName == "restrictedScope" ? "en" : "nl"
      def username = "localized-recipient-${UUID.randomUUID()}"
      def email = "${username}@example.com".toString()
      def user = identityProvider.createUpdateUser(userRealm,
              new User().setUsername(username).setEmail(email).setEnabled(true)
              .setServiceAccount(userName == "service").setSystemAccount(userName == "system")
              .setAttribute(User.LOCALE_ATTRIBUTE, language), null, false)
      createdUsers.add([realm: userRealm, id: user.id])
      if (userName == "service") {
        // Keycloak creates service users without the supplied email; give it a usable address so
        // account exclusion, rather than a missing address, determines eligibility.
        user = identityProvider.createUpdateUser(userRealm, user.setEmail(email), null, true)
      }
      identityProvider.updateUserClientRoles(userRealm, user.id, KEYCLOAK_CLIENT_ID, READ_ASSETS_ROLE, READ_NOTIFICATIONS_ROLE)
      if (restricted) {
        identityProvider.updateUserRealmRoles(userRealm, user.id,
                identityProvider.addUserRealmRoles(userRealm, user.id, RESTRICTED_USER_REALM_ROLE))
      }
      def storedUser = identityProvider.getUser(user.id)
      assert storedUser.realm == userRealm
      assert storedUser.email == email
      assert storedUser.isServiceAccount() == (userName == "service")
      assert storedUser.isSystemAccount() == (userName == "system")
      assert storedUser.attributeMap[User.LOCALE_ATTRIBUTE] == [language]
      assert identityProvider.getUserRealmRoles(userRealm, user.id).contains(RESTRICTED_USER_REALM_ROLE) == restricted

      // Storage permits seeding cross-realm links, independently of REST link-creation checks.
      assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, targetAsset.id)])
      if (userName == "restrictedScope") {
        assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, scopedAsset.id)])
      }
      assert assetStorageService.isUserAssets(user.id, [scopedAsset.id]) == (userName == "restrictedScope")

      def deliveryTarget = new Notification.Target(Notification.TargetType.USER, user.id)
      if (push) {
        def console = createConsole(userName, realm, targetAsset.id)
        assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, console.id)])
        deliveryTarget = new Notification.Target(Notification.TargetType.ASSET, console.id)
        if (userName == "regular") {
          // An eligible user's foreign console must also stay excluded after locale merging.
          def foreignConsole = createConsole("foreign console", foreignRealm, null)
          assetStorageService.storeUserAssetLinks([new UserAssetLink(userRealm, user.id, foreignConsole.id)])
        }
      }
      [(userName): [targetId: deliveryTarget.id, target: deliveryTarget.type,
          content: "${notificationName} ${language}".toString(), address: push ? null : email]]
    }
    def expectedTargets = expectedUserNames.collectEntries { userName ->
      def recipient = recipients[userName]
      [(recipient.targetId): [target: recipient.target, content: recipient.content, address: recipient.address]]
    }

    and: "both languages use the same channel, with English as the fallback"
    Map<String, AbstractNotificationMessage> messages = ["en", "nl"].collectEntries { language ->
      def message = push
              ? new PushNotificationMessage().setTitle("${notificationName} ${language}").setTargetType(PushNotificationMessage.TargetType.DEVICE)
              : new EmailNotificationMessage().setSubject("${notificationName} ${language}").setText("Localized recipients ${language}")
      [(language): message]
    }
    def localizedMessage = new LocalizedNotificationMessage("en", messages)
    assert localizedHandler.isMessageValid(localizedMessage)
    def notification = new Notification(notificationName, localizedMessage,
            [new Notification.Target(Notification.TargetType.ASSET, targetAsset.id)], null, null)
    def engineId = source == Notification.Source.ASSET_RULESET ? new RulesEngineId(realm, scopedAsset.id) : new RulesEngineId(realm)
    def sourceId = source == Notification.Source.ASSET_RULESET ? scopedAsset.id : realm
    def facade = new NotificationsFacade(engineId, notificationService)
    def processed = new NotifyBuilder(container.getService(MessageBrokerService.class).context)
            .fromRoute("NotificationQueue")
            .filter { exchange ->
              exchange.in.getHeader(Notification.HEADER_SOURCE) == source &&
                      exchange.in.getHeader(Notification.HEADER_SOURCE_ID) == sourceId
            }
            .whenDone(1).create()

    when: "the rule sends the localized notification to the asset"
    facade.send(notification)

    then: "eligible recipients receive their preferred language once, and filtered recipients leave no records"
    noExceptionThrown()
    assert processed.matches(10, TimeUnit.SECONDS)
    def persisted = storedNotifications()
    verifyAll {
      deliveredTargets.size() == expectedTargets.size()
      deliveredTargets.collectEntries {
        [(it.targetId): [target: it.target, content: it.content, address: it.address]]
      } == expectedTargets
      deliveredTargets.every {
        it.source == source && it.sourceId == sourceId && it.messageType == messageType
      }
      persisted.size() == expectedTargets.size()
      persisted.collectEntries {
        [(it.targetId): it.target]
      } == expectedTargets.collectEntries { id, recipient ->
        [(id): recipient.target]
      }
      deliveredTargets.collect { it.id }.toSet() == persisted.collect { it.id }.toSet()
      persisted.every {
        it.realm == realm && it.source == source && it.sourceId == sourceId &&
                it.type == LocalizedNotificationMessage.TYPE && it.error == null
      }
    }

    cleanup:
    processed?.destroy()
    localizedHandler.notificationHandlerMap.put(messageType, previousHandler)
    notificationService.notificationHandlerMap.put(LocalizedNotificationMessage.TYPE, previousLocalizedHandler)
    storedNotifications().each { notificationService.removeNotification(it.id) }
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    [source, messageType, userNames, expectedUserNames] << [Notification.Source.REALM_RULESET, Notification.Source.ASSET_RULESET].collectMany { source ->
      [PushNotificationMessage.TYPE, EmailNotificationMessage.TYPE].collectMany { messageType ->
        [
          [["regular", "restrictedScope"], ["regular", "restrictedScope"]],
          [["restrictedChild"], source == Notification.Source.ASSET_RULESET ? [] : ["restrictedChild"]],
          [
            ["regular", "restrictedScope", "restrictedChild", "foreign", "service", "system"],
            source == Notification.Source.ASSET_RULESET ? ["regular", "restrictedScope"] : ["regular", "restrictedScope", "restrictedChild"]
          ],
          [["foreign", "service", "system"], []]
        ].collect { row -> [source, messageType] + row }
      }
    }
  }

  def "Check localized notification functionality"() {

    List<String> localizedNotificationIds = new CopyOnWriteArrayList<>()
    List<Notification.TargetType> localizedNotificationTargetTypes = new CopyOnWriteArrayList<>()
    List<String> localizedNotificationTargetIds = new CopyOnWriteArrayList<>()
    List<AbstractNotificationMessage> localizedNotificationMessages = new CopyOnWriteArrayList<>()

    List<String> pushNotificationIds = new CopyOnWriteArrayList<>()
    List<Notification.TargetType> pushNotificationTargetTypes = new CopyOnWriteArrayList<>()
    List<String> pushNotificationTargetIds = new CopyOnWriteArrayList<>()
    List<AbstractNotificationMessage> pushNotificationMessages = new CopyOnWriteArrayList<>()

    given: "the container environment is started with the mock handler"
    def conditions = new PollingConditions(timeout: 10, initialDelay: 0.1, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def identityService = container.getService(ManagerIdentityService.class)
    def notificationService = container.getService(NotificationService.class)
    def pushNotificationHandler = container.getService(PushNotificationHandler.class)
    def localizedNotificationHandler = container.getService(LocalizedNotificationHandler.class)

    /* ----- */

    and: "a mock push notification handler"
    def throwPushHandlerException = false
    PushNotificationHandler mockPushNotificationHandler = Spy(pushNotificationHandler)
    mockPushNotificationHandler.isValid() >> true

    // Intercept the messages sent
    mockPushNotificationHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, source, sourceId, target, message ->
      if (throwPushHandlerException) {
        throw new Exception("Failed to send notification")
      }
      pushNotificationIds << id
      pushNotificationTargetTypes << target.type
      pushNotificationTargetIds << target.id
      pushNotificationMessages << message
      callRealMethod()
    }
    // Auto accept the messages sent to Firebase (without calling its logic)
    mockPushNotificationHandler.sendMessage(_ as com.google.firebase.messaging.Message) >> { message ->
      return NotificationSendResult.success()
    }

    notificationService.notificationHandlerMap.put(mockPushNotificationHandler.getTypeName(), mockPushNotificationHandler)

    /* ----- */

    and: "a mock localized notification handler"
    def throwLocalizedHandlerException = false
    LocalizedNotificationHandler mockLocalizedNotificationHandler = Spy(localizedNotificationHandler)
    mockLocalizedNotificationHandler.isValid() >> true

    // Intercept the messages sent
    mockLocalizedNotificationHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, source, sourceId, target, message ->
      if (throwLocalizedHandlerException) {
        throw new Exception("Failed to send notification")
      }
      localizedNotificationIds << id
      localizedNotificationTargetTypes << target.type
      localizedNotificationTargetIds << target.id
      localizedNotificationMessages << message
      callRealMethod()
    }
    // the notificationHandlerMap in the localized handler, should also use the same mock handlers
    mockLocalizedNotificationHandler.notificationHandlerMap = notificationService.notificationHandlerMap

    notificationService.notificationHandlerMap.put(localizedNotificationHandler.getTypeName(), mockLocalizedNotificationHandler)

    /* ----- */

    and: "an authenticated superuser"
    def adminAccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    def notification = new Notification(
            "MultiLanguageAction",
            new LocalizedNotificationMessage(
                    "en",
                    new HashMap<String, AbstractNotificationMessage>(
                            nl: new PushNotificationMessage("Nederlandse titel", "Nederlandse body", null, null, null),
                            en: new PushNotificationMessage("English title", "English body", null, null, null)
                            )
                    ),
            null,
            null,
            null)

    and: "the admin notification resource"
    def adminNotificationResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminAccessToken).proxy(NotificationResource.class)
    def adminConsoleResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminAccessToken).proxy(ConsoleResource.class)

    when: "the admin console is registered"
    def consoleRegistration = new ConsoleRegistration(null,
            "Admin Console",
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
                            (Map) parse("{\"token\": \"23123213ad2313b0897efd\"}").orElse(null)
                            )
                    ),
            "",
            ["manager"] as String[])
    def adminConsole = adminConsoleResource.register(null, consoleRegistration)

    then: "the admin console should have been created"
    adminConsole.id != null

    and: "the push notification handler should have picked up the console token"
    // The token map is updated from the asset persistence event, and sending to a console that is not in it yet
    // fails the whole notification request
    conditions.eventually {
      assert pushNotificationHandler.consoleFCMTokenMap.containsKey(adminConsole.id)
    }

    when: "the admin user sends a push notification to the entire MASTER realm (which is only himself)"
    notification.targets = [new Notification.Target(Notification.TargetType.REALM, MASTER_REALM)]
    adminNotificationResource.sendNotification(null, notification)

    then: "all consoles in that realm should have been sent a notification"
    conditions.eventually {
      assert localizedNotificationIds.size() == 1
      assert localizedNotificationTargetIds.size() == 1
      assert localizedNotificationTargetIds.contains(adminConsole.id)
      assert pushNotificationIds.size() == 1
      assert pushNotificationTargetIds.size() == 1
      assert pushNotificationTargetIds.contains(adminConsole.id)
      assert pushNotificationMessages.size() == 1
      assert pushNotificationMessages.get(0).type == "push"
      assert ((PushNotificationMessage)pushNotificationMessages.get(0)).title == "English title"
      assert ((PushNotificationMessage)pushNotificationMessages.get(0)).body == "English body"
    }

    and: "we clear the cached notifications"
    localizedNotificationIds.clear()
    localizedNotificationTargetTypes.clear()
    localizedNotificationTargetIds.clear()
    localizedNotificationMessages.clear()
    pushNotificationIds.clear()
    pushNotificationTargetTypes.clear()
    pushNotificationTargetIds.clear()
    pushNotificationMessages.clear()

    /* ------------ */

    when: "a new user gets added"
    def testuser1AccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )

    and: "their language is updated to dutch"
    def testuser1 = identityService.getIdentityProvider().getUserByUsername(MASTER_REALM, "testuser1")
    testuser1.setAttribute(User.LOCALE_ATTRIBUTE, "nl")
    identityService.getIdentityProvider().createUpdateUser(MASTER_REALM, testuser1, null, true)

    and: "the console of the dutch user is registered"
    def testuser1ConsoleResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, testuser1AccessToken).proxy(ConsoleResource.class)
    def testuser1ConsoleRegistration = new ConsoleRegistration(null,
            "Test user 1 Console",
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
                            (Map) parse("{\"token\": \"23123213ad2313b0897efd\"}").orElse(null)
                            )
                    ),
            "",
            ["manager"] as String[])
    def testuser1Console = testuser1ConsoleResource.register(null, testuser1ConsoleRegistration)

    and: "the console is created and its token picked up by the push notification handler"
    conditions.eventually {
      assert testuser1Console.id != null
      assert pushNotificationHandler.consoleFCMTokenMap.containsKey(testuser1Console.id)
    }

    and: "the same notification is sent, now to both users"
    adminNotificationResource.sendNotification(null, notification)

    then: "both consoles in that realm should have been sent a notification"
    conditions.eventually {
      assert localizedNotificationIds.size() == 2
      assert localizedNotificationTargetIds.size() == 2
      assert pushNotificationIds.size() == 2
      assert pushNotificationTargetIds.size() == 2
    }

    and: "we clear the cached notifications again"
    localizedNotificationIds.clear()
    localizedNotificationTargetTypes.clear()
    localizedNotificationTargetIds.clear()
    localizedNotificationMessages.clear()
    pushNotificationIds.clear()
    pushNotificationTargetTypes.clear()
    pushNotificationTargetIds.clear()
    pushNotificationMessages.clear()

    /* ------------ */

    when: "testuser1 their language is updated to an unknown language, like italian"
    testuser1.setAttribute(User.LOCALE_ATTRIBUTE, "it")
    identityService.getIdentityProvider().createUpdateUser(MASTER_REALM, testuser1, null, true)

    and: "the same notification is sent again, to both users"
    adminNotificationResource.sendNotification(null, notification)

    then: "testuser1 should still receive the notification, but in the default language"
    conditions.eventually {
      assert localizedNotificationMessages.size() == 2
      assert pushNotificationMessages.size() == 2
      assert pushNotificationMessages.stream().allMatch { msg ->
        (msg as PushNotificationMessage).title == "English title" && (msg as PushNotificationMessage).body == "English body"
      }
    }

    and: "we clear the cached notifications again"
    localizedNotificationIds.clear()
    localizedNotificationTargetTypes.clear()
    localizedNotificationTargetIds.clear()
    localizedNotificationMessages.clear()
    pushNotificationIds.clear()
    pushNotificationTargetTypes.clear()
    pushNotificationTargetIds.clear()
    pushNotificationMessages.clear()

    when: "testuser1 sets their language back to dutch"
    testuser1.setAttribute(User.LOCALE_ATTRIBUTE, "nl")
    testuser1 = identityService.getIdentityProvider().createUpdateUser(MASTER_REALM, testuser1, null, true)

    then: "we should see the new value in the user attributes"
    testuser1.getAttributeMap().get(User.LOCALE_ATTRIBUTE).getFirst() == "nl"

    /* ----------- */

    when: "a complex notification with different types per language is set up"
    def complexNotification = new Notification(
            "MultiTypeAction",
            new LocalizedNotificationMessage(
                    "en",
                    new HashMap<String, AbstractNotificationMessage>(
                            en: new PushNotificationMessage("English title", "English body", null, null, null),
                            nl: new EmailNotificationMessage().setSubject("Nederlandse titel").setText("Nederlandse tekst")
                            )
                    ),
            null,
            null,
            null)

    complexNotification.targets = [new Notification.Target(Notification.TargetType.REALM, MASTER_REALM)]

    and: "the mock email notification handler has been added"
    List<Message> sentEmails = new CopyOnWriteArrayList<>()
    def emailNotificationHandler = container.getService(EmailNotificationHandler.class)
    EmailNotificationHandler mockEmailNotificationHandler = Spy(emailNotificationHandler)
    mockEmailNotificationHandler.isValid() >> true

    // Log email and assume sent to SMTP Server
    mockEmailNotificationHandler.sendMessage(_ as Message) >> { Message email ->
      sentEmails << email
      return NotificationSendResult.success()
    }

    // Add email handler to the list of handlers
    notificationService.notificationHandlerMap.put(emailNotificationHandler.getTypeName(), mockEmailNotificationHandler)
    mockLocalizedNotificationHandler.notificationHandlerMap = notificationService.notificationHandlerMap

    and: "testuser1 configures their email address"
    testuser1.setEmail("testuser1@email.com")
    identityService.getIdentityProvider().createUpdateUser(MASTER_REALM, testuser1, null, true)

    and: "the complex notification is sent"
    adminNotificationResource.sendNotification(null, complexNotification)

    then: "it should return a BAD_REQUEST because the admin user doesn't get the email notification and the test user doesn't get the push notification"
    thrown(BadRequestException)

    and: "the other notifications should be sent correctly through email and with push"
    conditions.eventually {
      assert localizedNotificationMessages.size() == 3
      assert localizedNotificationTargetIds.size() == 3
      assert pushNotificationMessages.size() == 1
      assert sentEmails.size() == 1
    }

    and: "we clear the cached notifications once again"
    localizedNotificationIds.clear()
    localizedNotificationTargetTypes.clear()
    localizedNotificationTargetIds.clear()
    localizedNotificationMessages.clear()
    pushNotificationIds.clear()
    pushNotificationTargetTypes.clear()
    pushNotificationTargetIds.clear()
    pushNotificationMessages.clear()


    /* ------------ */

    when: "an invalid notification is created"
    def invalidNotification = new Notification(
            "InvalidNotification",
            new LocalizedNotificationMessage(
                    "en",
                    new HashMap<String, AbstractNotificationMessage>(
                            nl: new PushNotificationMessage(),
                            en: new PushNotificationMessage("English title", "English body", null, null, null)
                            )
                    ),
            null,
            null,
            null)

    and: "the invalid notification is sent to all users"
    adminNotificationResource.sendNotification(null, invalidNotification)

    then: "it should return a BAD_REQUEST, because the message is invalid"
    thrown(BadRequestException)

    /* ------------------------ */

    /*when: "if the notification became valid, and the TRIGGER_ASSETS placeholder is inserted"
     ((LocalizedNotificationMessage) invalidNotification.getMessage()).setMessage("nl", new PushNotificationMessage())*/
  }

  def "Check notification realm access control"() {

    List<Message> sentEmails = new CopyOnWriteArrayList<>()

    given: "the container environment is started with a mock email handler"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def notificationService = container.getService(NotificationService.class)
    def emailNotificationHandler = container.getService(EmailNotificationHandler.class)

    and: "a mock email notification handler that records sent emails (so notifications get persisted without SMTP)"
    EmailNotificationHandler mockEmailNotificationHandler = Spy(emailNotificationHandler)
    mockEmailNotificationHandler.isValid() >> true
    mockEmailNotificationHandler.sendMessage(_ as Message) >> { Message email ->
      sentEmails << email
      return NotificationSendResult.success()
    }
    notificationService.notificationHandlerMap.put(emailNotificationHandler.getTypeName(), mockEmailNotificationHandler)

    and: "the building realm and the ids of two of its users"
    def buildingRealm = managerTestSetup.realmBuildingName
    def testuser2Id = keycloakTestSetup.testuser2Id
    // building user: has read:notifications and receives the realm email; testuser2: receives the email but has no read:notifications
    def buildingUserId = keycloakTestSetup.buildingUserId

    and: "a transient building user whose ONLY granting role is read:notifications"
    // Created in-test (not in KeycloakTestSetup) so the shared fixture and the user-count assertions of other
    // specs are untouched; startContainer purges users that aren't part of the fixture baseline between features.
    def identityProvider = container.getService(ManagerIdentityService.class).getIdentityProvider()
    def notifUserInput = new User()
    notifUserInput.username = "notifuser"
    notifUserInput.firstName = "Notif"
    notifUserInput.lastName = "User"
    notifUserInput.email = "notifuser@openremote.local"
    notifUserInput.enabled = true
    def notifUser = identityProvider.createUpdateUser(buildingRealm, notifUserInput, "notifuser", true)
    identityProvider.updateUserClientRoles(buildingRealm, notifUser.id, KEYCLOAK_CLIENT_ID, READ_NOTIFICATIONS_ROLE)
    def notifUserId = notifUser.id

    and: "a transient master realm user with an email address, so a master realm notification gets persisted"
    def masterUserInput = new User()
    masterUserInput.username = "masternotifuser"
    masterUserInput.firstName = "Master"
    masterUserInput.lastName = "User"
    masterUserInput.email = "masternotifuser@openremote.local"
    masterUserInput.enabled = true
    identityProvider.createUpdateUser(MASTER_REALM, masterUserInput, "masternotifuser", true)

    and: "authenticated users with differing permissions"
    def adminAccessToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, MASTER_REALM_ADMIN_USER, getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT))
    def testuser2AccessToken = authenticate(container, buildingRealm, KEYCLOAK_CLIENT_ID, "testuser2", "testuser2")
    def buildingUserAccessToken = authenticate(container, buildingRealm, KEYCLOAK_CLIENT_ID, "building", "building")
    def notifUserAccessToken = authenticate(container, buildingRealm, KEYCLOAK_CLIENT_ID, "notifuser", "notifuser")
    // testuser4 is a building realm admin (write:admin), i.e. it may delete but only within its own realm
    def testuser4AccessToken = authenticate(container, buildingRealm, KEYCLOAK_CLIENT_ID, "testuser4", "testuser4")

    and: "a helper that requests notifications for a realm over REST as the given user"
    // realm is selected via the realmId query param (the consolidated endpoint has no realm path segment)
    def requestNotificationsByRealm = { String authRealm, String token, String realmId ->
      getClientApiTarget(serverUri(serverPort), authRealm, token)
              .path("notification").queryParam("realmId", realmId)
              .request().get()
    }

    and: "a helper that reads the notifications of a realm with a given name as the superuser"
    def notificationsNamed = { String realmId, String name ->
      def response = requestNotificationsByRealm(MASTER_REALM, adminAccessToken, realmId)
      def sent = response.readEntity(SentNotification[].class).findAll { it.name == name }
      response.close()
      return sent
    }

    and: "helpers that delete notifications over REST as the given user"
    def deleteNotifications = { String authRealm, String token, Map<String, Object> queryParams ->
      def target = getClientApiTarget(serverUri(serverPort), authRealm, token).path("notification")
      queryParams.each { name, value -> target = target.queryParam(name, value) }
      target.request().delete()
    }
    def deleteNotificationById = { String authRealm, String token, Long notificationId ->
      getClientApiTarget(serverUri(serverPort), authRealm, token)
              .path("notification").path(notificationId.toString())
              .request().delete()
    }

    when: "an email notification is sent to the entire building realm"
    def notification = new Notification(
            "RealmAccessTest",
            new EmailNotificationMessage().setSubject("Realm access test").setText("Hello building"),
            Collections.singletonList(new Notification.Target(Notification.TargetType.REALM, buildingRealm)), null, null)
    notificationService.sendNotification(notification, Notification.Source.REALM_RULESET, buildingRealm)

    then: "all three recipients' notifications are persisted correctly and a superuser can read them in full, cross-realm from master"
    conditions.eventually {
      assert sentEmails.size() == 3
      def response = requestNotificationsByRealm(MASTER_REALM, adminAccessToken, buildingRealm)
      assert response.status == 200
      def sent = response.readEntity(SentNotification[].class).findAll {
        it.name == "RealmAccessTest"
      }
      response.close()

      // one persisted notification per email recipient, each fully populated and unredacted for the superuser
      assert sent.size() == 3
      assert sent.collect {
        it.targetId
      }.toSet() == [testuser2Id, buildingUserId, notifUserId].toSet()
      assert sent.every { it.id != null }
      assert sent.every { it.name == "RealmAccessTest" }
      assert sent.every { it.type == EmailNotificationMessage.TYPE }
      assert sent.every { it.target == Notification.TargetType.USER }
      assert sent.every { it.source == Notification.Source.REALM_RULESET }
      assert sent.every { it.sourceId == buildingRealm }
      assert sent.every { it.realm == buildingRealm }
      assert sent.every {
        it.sentOn != null && it.deliveredOn == null && it.acknowledgedOn == null && it.error == null
      }
      assert sent.every {
        (it.message as EmailNotificationMessage).subject == "Realm access test"
      }
      assert sent.every { (it.message as EmailNotificationMessage).text == "Hello building" }
    }

    when: "a user without read:notifications or read:admin requests notifications for their realm"
    def forbiddenResponse = requestNotificationsByRealm(buildingRealm, testuser2AccessToken, buildingRealm)

    then: "access is forbidden by the endpoint role check"
    forbiddenResponse.status == 403
    forbiddenResponse.close()

    when: "a restricted user (read:notifications + read:users) requests notifications for their own realm"
    def ownRealmResponse = requestNotificationsByRealm(buildingRealm, buildingUserAccessToken, buildingRealm)
    def ownNotifications = ownRealmResponse.readEntity(SentNotification[].class)
    ownRealmResponse.close()
    def ownSent = ownNotifications.findAll { it.name == "RealmAccessTest" }

    then: "a restricted user only sees the notification that targets them, with full details"
    ownRealmResponse.status == 200
    ownSent.size() == 1
    ownSent[0].name == "RealmAccessTest"
    ownSent[0].type == EmailNotificationMessage.TYPE
    ownSent[0].target == Notification.TargetType.USER
    ownSent[0].targetId == buildingUserId
    ownSent[0].source == Notification.Source.REALM_RULESET
    ownSent[0].sourceId == buildingRealm
    ownSent[0].realm == buildingRealm
    (ownSent[0].message as EmailNotificationMessage).subject == "Realm access test"
    // being restricted, they don't see notifications addressed to other users in the realm
    !ownNotifications.any { it.targetId == testuser2Id }
    !ownNotifications.any { it.targetId == notifUserId }

    when: "a non-restricted user whose only granting role is read:notifications requests their realm's notifications"
    def notifUserResponse = requestNotificationsByRealm(buildingRealm, notifUserAccessToken, buildingRealm)
    def notifNotifications = notifUserResponse.readEntity(SentNotification[].class)
    notifUserResponse.close()
    def notifSent = notifNotifications.findAll { it.name == "RealmAccessTest" }

    then: "read:notifications lets a non-restricted user read ALL of the realm's notifications, but ids are sanitized without read:users/read:assets"
    notifUserResponse.status == 200
    // unlike the restricted user, they see every recipient's notification in the realm, not just their own
    notifSent.size() == 3
    notifSent.collect { it.target }.toSet() == [Notification.TargetType.USER].toSet()
    notifSent.every { it.name == "RealmAccessTest" }
    notifSent.every { it.type == EmailNotificationMessage.TYPE }
    notifSent.every { it.source == Notification.Source.REALM_RULESET }
    notifSent.every { it.realm == buildingRealm }
    notifSent.every { (it.message as EmailNotificationMessage).subject == "Realm access test" }
    // without read:users the recipient ids are stripped; realm ruleset source ids are realm names and stay visible
    notifSent.every { it.targetId == null }
    notifSent.every { it.sourceId == buildingRealm }

    when: "a non-restricted building user requests notifications for the master realm they cannot access"
    def otherRealmResponse = requestNotificationsByRealm(buildingRealm, notifUserAccessToken, MASTER_REALM)

    then: "access is forbidden — even a non-restricted user cannot read another realm's notifications"
    otherRealmResponse.status == 403
    otherRealmResponse.close()

    when: "a user with read:notifications requests notifications without specifying a realm"
    def noRealmResponse = getClientApiTarget(serverUri(serverPort), buildingRealm, buildingUserAccessToken)
            .path("notification")
            .request().get()
    def noRealmSent = noRealmResponse.readEntity(SentNotification[].class).findAll {
      it.name == "RealmAccessTest"
    }

    then: "the query defaults to the caller's own realm rather than failing"
    noRealmResponse.status == 200
    // the restricted building user still only sees the notification targeting them
    noRealmSent.size() == 1
    noRealmSent[0].targetId == buildingUserId
    noRealmResponse.close()

    when: "an email notification is sent to the entire master realm"
    def masterNotification = new Notification(
            "MasterRealmAccessTest",
            new EmailNotificationMessage().setSubject("Master realm test").setText("Hello master"),
            Collections.singletonList(new Notification.Target(Notification.TargetType.REALM, MASTER_REALM)), null, null)
    notificationService.sendNotification(masterNotification, Notification.Source.REALM_RULESET, MASTER_REALM)

    then: "it is persisted against the master realm"
    conditions.eventually {
      assert notificationsNamed(MASTER_REALM, "MasterRealmAccessTest").size() > 0
    }

    when: "a realm admin of the building realm tries to delete the master realm's notifications"
    def masterCount = notificationsNamed(MASTER_REALM, "MasterRealmAccessTest").size()
    def crossRealmDelete = deleteNotifications(buildingRealm, testuser4AccessToken, [realmId: MASTER_REALM, type: EmailNotificationMessage.TYPE])

    then: "the request is forbidden and the master realm's notifications are untouched"
    crossRealmDelete.status == 403
    crossRealmDelete.close()
    notificationsNamed(MASTER_REALM, "MasterRealmAccessTest").size() == masterCount

    when: "a realm admin of the building realm tries to delete a single master realm notification by id"
    def masterNotificationId = notificationsNamed(MASTER_REALM, "MasterRealmAccessTest")[0].id
    def byIdDelete = deleteNotificationById(buildingRealm, testuser4AccessToken, masterNotificationId)

    then: "the request is forbidden and the notification remains"
    byIdDelete.status == 403
    byIdDelete.close()
    notificationsNamed(MASTER_REALM, "MasterRealmAccessTest").any {
      it.id == masterNotificationId
    }

    when: "the superuser deletes a notification that doesn't exist"
    def missingDelete = deleteNotificationById(MASTER_REALM, adminAccessToken, Long.MAX_VALUE)

    then: "the notification is reported as not found"
    missingDelete.status == 404
    missingDelete.close()

    when: "a realm admin of the building realm deletes notifications by type without specifying a realm"
    def ownRealmDelete = deleteNotifications(buildingRealm, testuser4AccessToken, [type: EmailNotificationMessage.TYPE])

    then: "only their own realm's notifications are deleted"
    ownRealmDelete.status == 204
    ownRealmDelete.close()
    notificationsNamed(buildingRealm, "RealmAccessTest").isEmpty()
    notificationsNamed(MASTER_REALM, "MasterRealmAccessTest").size() == masterCount

    when: "the superuser deletes notifications by type"
    def superUserDelete = deleteNotifications(MASTER_REALM, adminAccessToken, [type: EmailNotificationMessage.TYPE])

    then: "the delete is not confined to a realm"
    superUserDelete.status == 204
    superUserDelete.close()
    notificationsNamed(MASTER_REALM, "MasterRealmAccessTest").isEmpty()

    cleanup: "the mock is removed"
    notificationService.notificationHandlerMap.put(emailNotificationHandler.getTypeName(), emailNotificationHandler)
  }
}
