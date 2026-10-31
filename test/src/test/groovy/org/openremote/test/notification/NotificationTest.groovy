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
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.asset.console.ConsoleResourceImpl
import org.openremote.manager.notification.EmailNotificationHandler
import org.openremote.manager.notification.LocalizedNotificationHandler
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.notification.PushNotificationHandler
import org.openremote.manager.rules.geofence.ORConsoleGeofenceAssetAdapter
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.web.ManagerWebService
import org.openremote.model.asset.impl.ConsoleAsset
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.console.ConsoleProvider
import org.openremote.model.console.ConsoleRegistration
import org.openremote.model.console.ConsoleResource
import org.openremote.model.notification.*
import org.openremote.model.security.User
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

    when: "all linked consoles for a user lack an FCM token"
    notificationIds.clear()
    notificationTargetIds.clear()
    def testUser3Console2Asset = assetStorageService.find(testuser3Console2.id) as ConsoleAsset
    testUser3Console2Asset.getConsoleProviders().map {
      it.get(PushNotificationMessage.TYPE)
    }.get().getData().put("token", null)
    assetStorageService.merge(testUser3Console2Asset)
    conditions.eventually {
      assert pushNotificationHandler.consoleFCMTokenMap.get(testUser3Console2Asset.id) == null
    }
    notification.targets = [new Notification.Target(Notification.TargetType.USER, keycloakTestSetup.testuser3Id)]
    advancePseudoClock(1, TimeUnit.HOURS, container)
    adminNotificationResource.sendNotification(null, notification)

    then: "sent notifications record the missing FCM token instead of missing targets"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
    conditions.eventually {
      def console1Notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null)
      def console2Notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null)
      assert console1Notifications.any { it.error?.contains("No FCM token found for console") }
      assert console2Notifications.any { it.error?.contains("No FCM token found for console") }
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
      assert notifications.length == 7
      assert notifications.every {n ->
        PushNotificationMessage pushMessage = n.message as PushNotificationMessage
        pushMessage.getTitle() == "Test Action" &&
                pushMessage.getBody() == "Click to cancel" &&
                pushMessage.getAction() != null &&
                n.deliveredOn == null &&
                n.acknowledgedOn == null
      }
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console2.id, null, null, null, null, null).length == 8
      assert adminNotificationResource.getNotifications(null, null, null, null, null, null, null, anonymousConsole.id, null, null, null, null, null).length == 3
    }

    when: "the admin user marks a Building console notification as delivered and requests the notifications for Building consoles"
    adminNotificationResource.notificationDelivered(null, testuser3Console1.id, notifications.find {n ->
      n.targetId == testuser3Console1.id
    }.id)

    then: "the notification should have been updated"
    conditions.eventually {
      notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null)
      assert notifications.length == 7
      assert notifications.count {n -> n.deliveredOn != null} == 1
    }

    when: "the admin user marks a Building console notification as delivered and requests the notifications for Building consoles"
    adminNotificationResource.notificationAcknowledged(null, testuser3Console1.id, notifications.find {n ->
      n.targetId == testuser3Console1.id && n.deliveredOn != null
    }.id, new TextNode("dismissed"))

    then: "the notification should have been updated"
    conditions.eventually {
      notifications = adminNotificationResource.getNotifications(null, null, null, null, null, null, null, testuser3Console1.id, null, null, null, null, null)
      assert notifications.length == 7
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
    // The missing-token scenario cleared both consoles. Later sends still need a token.
    def fcmToken = "23123213ad2313b0897efd"
    [testuser3Console1, testuser3Console2].each { console ->
      def asset = assetStorageService.find(console.id) as ConsoleAsset
      asset.getConsoleProviders().map {
        it.get(PushNotificationMessage.TYPE)
      }.get().getData().put("token", fcmToken)
      assetStorageService.merge(asset)
    }
    conditions.eventually {
      assert pushNotificationHandler.consoleFCMTokenMap.get(testuser3Console1.id) == fcmToken
      assert pushNotificationHandler.consoleFCMTokenMap.get(testuser3Console2.id) == fcmToken
    }
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
