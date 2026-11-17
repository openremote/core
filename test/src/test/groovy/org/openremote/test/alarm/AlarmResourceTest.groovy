/*
 * Copyright 2024, OpenRemote Inc.
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
package org.openremote.test.alarm

import jakarta.mail.Message
import org.openremote.manager.notification.EmailNotificationHandler
import org.openremote.manager.notification.NotificationService
import org.openremote.model.alarm.Alarm
import org.openremote.model.alarm.Alarm.Severity
import org.openremote.model.alarm.AlarmAssetLink
import org.openremote.model.alarm.SentAlarm
import org.openremote.model.alarm.AlarmResource
import org.openremote.manager.setup.SetupService
import org.openremote.model.notification.AbstractNotificationMessage
import org.openremote.model.notification.Notification
import org.openremote.model.notification.NotificationSendResult
import org.openremote.model.security.ClientRole
import org.openremote.model.security.User
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.lang.Unroll
import spock.lang.Shared
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList

import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.util.MapAccess.getString
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.Constants.MASTER_REALM
import static org.openremote.model.Constants.MASTER_REALM_ADMIN_USER
import jakarta.ws.rs.WebApplicationException

import static org.openremote.model.Constants.SUPER_USER_REALM_ROLE

class AlarmResourceTest extends Specification implements ManagerContainerTrait {
  @Shared
  static AlarmResource adminResource

  @Shared
  static AlarmResource superAdminResource

  @Shared
  static AlarmResource regularUserResource

  @Shared
  static AlarmResource buildingUserResource

  @Shared
  static KeycloakTestSetup keycloakTestSetup

  @Shared
  static ManagerTestSetup managerTestSetup

  @Shared
  static String alarmsReadWriteUserId

  @Shared
  static String testuser4Id

  @Shared
  static NotificationService notificationService

  @Shared
  static List<AbstractNotificationMessage> notificationMessages = new CopyOnWriteArrayList<>()

  def setupSpec() {
    def container = startContainer(defaultConfig(), defaultServices())
    keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    notificationService = container.getService(NotificationService.class)

    def adminAccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    regularUserResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM).proxy(AlarmResource.class)
    adminResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminAccessToken).proxy(AlarmResource.class)

    User alarmsReadWriteUser = keycloakTestSetup.createUser(MASTER_REALM, "alarmsrwuser1", "alarmsrwuser1", "Alarms RW", "User", "alarmsrwuser@openremote.local", true, KeycloakTestSetup.REGULAR_USER_ROLES)
    alarmsReadWriteUserId = alarmsReadWriteUser.getId()

    User superUser = keycloakTestSetup.createUser(MASTER_REALM, "testuser4", "testuser4", "Demo4", "Demo4", null, true, new ClientRole[] {
              ClientRole.WRITE, ClientRole.READ
            })
    testuser4Id = superUser.getId()
    keycloakTestSetup.keycloakProvider.updateUserClientRoles(MASTER_REALM, testuser4Id, "account")
    keycloakTestSetup.keycloakProvider.updateUserRealmRoles(MASTER_REALM, testuser4Id, keycloakTestSetup.keycloakProvider.addUserRealmRoles(MASTER_REALM, testuser4Id, SUPER_USER_REALM_ROLE))

    def superAdminAccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser4",
            "testuser4",
    )

    superAdminResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, superAdminAccessToken).proxy(AlarmResource.class)

    def buildingUserAccessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )

    buildingUserResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, buildingUserAccessToken).proxy(AlarmResource.class)

    def emailNotificationHandler = container.getService(EmailNotificationHandler.class)
    def throwPushHandlerException = false
    EmailNotificationHandler mockPushNotificationHandler = Spy(emailNotificationHandler)
    mockPushNotificationHandler.isValid() >> true
    mockPushNotificationHandler.sendMessage(_ as Long, _ as Notification.Source, _ as String, _ as Notification.Target, _ as AbstractNotificationMessage) >> { id, source, sourceId, target, message ->
      if (throwPushHandlerException) {
        throw new Exception("Failed to send notification")
      }
      notificationMessages << message
      callRealMethod()
    }
    mockPushNotificationHandler.sendMessage(_ as Message) >> { message ->
      return NotificationSendResult.success()
    }

    notificationService.notificationHandlerMap.put(emailNotificationHandler.getTypeName(), mockPushNotificationHandler)
  }

  def cleanup() {
    // Remove all alarms
    [MASTER_REALM, keycloakTestSetup.realmBuilding.name].each { realm ->
      def alarms = superAdminResource.getAlarms(null, realm, null, null, null)
      if (alarms.length> 0) {
        superAdminResource.removeAlarms(null, (List<Long>) alarms.collect { it.id })
      }
    }

    // Clear notifications
    notificationMessages.clear()
  }

  // Create alarm as new super user in other realm
  @Unroll
  def "should create an alarm with title '#title', content '#content', severity '#severity', assigneeId '#assigneeId' and '#emailNotifications' emailNotifications as new super user in other realm"() {
    when: "an alarm is created"
    def input = new Alarm(title, content, severity, assigneeId, keycloakTestSetup.realmBuilding.name)
    def alarm = superAdminResource.createAlarm(null, input, null)

    then:
    alarm != null
    alarm.title == title
    alarm.content == content
    alarm.severity == severity
    alarm.assigneeId == assigneeId
    alarm.status == Alarm.Status.OPEN

    and:
    def conditions = new PollingConditions(timeout: 10, initialDelay: 1, delay: 0.2)
    conditions.eventually {
      assert notificationMessages.size() == emailNotifications
    }

    where:
    title | content | severity | assigneeId | emailNotifications
    "Low Alarm" | "Test Description" | Severity.LOW | null | 0
    "Low Assigned 1 Alarm" | "Test Description" | Severity.LOW | keycloakTestSetup.testuser1Id | 0
    "Low Assigned 2 Alarm" | "Test Description" | Severity.LOW | alarmsReadWriteUserId | 0
    "Medium Unassigned Alarm" | "Another Description" | Severity.MEDIUM | null | 0
    "Medium Assigned 1 Alarm" | "Assigned Description" | Severity.MEDIUM | keycloakTestSetup.testuser1Id | 0
    "Medium Assigned 2 Alarm" | "Assigned Description" | Severity.MEDIUM | alarmsReadWriteUserId | 0
    // This alarm has no linked assets. The building realm's unrestricted writer has no email;
    // its restricted writers must not receive automatic notifications for an unlinked alarm.
    "High Unassigned Alarm" | "Critical Description" | Severity.HIGH | null | 0
    "High Assigned 1 Alarm" | "Critical Description" | Severity.HIGH | keycloakTestSetup.testuser1Id | 0
    "High Assigned 2 Alarm" | "Critical Description" | Severity.HIGH | alarmsReadWriteUserId | 1
  }

  // Create alarm as admin
  @Unroll
  def "should create an alarm with title '#title', content '#content', severity '#severity', assigneeId '#assigneeId' and '#emailNotifications' emailNotifications"() {
    when: "an alarm is created"
    def input = new Alarm(title, content, severity, assigneeId, MASTER_REALM)
    def alarm = adminResource.createAlarm(null, input, null)

    then:
    alarm != null
    alarm.title == title
    alarm.content == content
    alarm.severity == severity
    alarm.assigneeId == assigneeId
    alarm.status == Alarm.Status.OPEN

    and:
    def conditions = new PollingConditions(timeout: 10, initialDelay: 1, delay: 0.2)
    conditions.eventually {
      assert notificationMessages.size() == emailNotifications
    }

    where:
    title | content | severity | assigneeId | emailNotifications
    "Low Alarm" | "Test Description" | Severity.LOW | null | 0
    "Low Assigned 1 Alarm" | "Test Description" | Severity.LOW | keycloakTestSetup.testuser1Id | 0
    "Low Assigned 2 Alarm" | "Test Description" | Severity.LOW | alarmsReadWriteUserId | 0
    "Medium Unassigned Alarm" | "Another Description" | Severity.MEDIUM | null | 0
    "Medium Assigned 1 Alarm" | "Assigned Description" | Severity.MEDIUM | keycloakTestSetup.testuser1Id | 0
    "Medium Assigned 2 Alarm" | "Assigned Description" | Severity.MEDIUM | alarmsReadWriteUserId | 0
    "High Unassigned Alarm" | "Critical Description" | Severity.HIGH | null | 1
    "High Assigned 1 Alarm" | "Critical Description" | Severity.HIGH | keycloakTestSetup.testuser1Id | 0
    "High Assigned 2 Alarm" | "Critical Description" | Severity.HIGH | alarmsReadWriteUserId | 1
  }

  @Unroll
  def "should create an alarm with title '#title', content '#content', severity '#severity', and source '#source'"() {
    when: "an alarm is created"
    def input = new Alarm().setTitle(title).setContent(content).setSeverity(severity).setStatus(Alarm.Status.OPEN).setRealm(MASTER_REALM)
    def alarm = adminResource.createAlarm(null, input, null)

    then:
    alarm != null
    alarm.title == title
    alarm.content == content
    alarm.severity == severity
    alarm.status == Alarm.Status.OPEN
    alarm.source == source

    where:
    title | content | severity | source
    "Test Alarm" | "Test Description" | Severity.LOW | Alarm.Source.MANUAL
    "Another Alarm" | "Another Description" | Severity.MEDIUM | Alarm.Source.MANUAL
    "Critical Alarm" | "Critical Description" | Severity.HIGH | Alarm.Source.MANUAL
  }

  @Unroll
  def "should not create an alarm with title '#title', content '#content', severity '#severity', and status '#status'"() {
    when:
    adminResource.createAlarm(null, new Alarm().setTitle(title).setContent(content).setSeverity(severity).setStatus(status).setRealm(MASTER_REALM), null)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    where:
    title | content | severity | status
    null | "Test Description" | Severity.LOW | Alarm.Status.OPEN
    "Another Test Alarm" | "Another Description" | null | Alarm.Status.RESOLVED
  }

  // Create alarm without write:alarm role
  def "should not create an alarm as regular user"() {
    when:
    regularUserResource.createAlarm(null, new Alarm().setTitle("title").setContent("content").setSeverity(Severity.MEDIUM).setStatus(Alarm.Status.ACKNOWLEDGED).setRealm(MASTER_REALM), null)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  // Get alarms as admin
  def "should return created alarms"() {
    when: "five alarms are added"
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 1').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 2').setContent('content').setStatus(Alarm.Status.ACKNOWLEDGED).setSeverity(Severity.MEDIUM).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 3').setContent('content').setStatus(Alarm.Status.CLOSED).setSeverity(Severity.HIGH).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 4').setContent('content').setStatus(Alarm.Status.IN_PROGRESS).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 5').setContent('content').setStatus(Alarm.Status.RESOLVED).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    def alarms = adminResource.getAlarms(null, MASTER_REALM, null, null, null)

    then: "five alarm can be retrieved"
    alarms != null
    alarms.size() == 5

    and: "each single alarm can also be retrieved"
    for (alarm in alarms) {
      assert adminResource.getAlarm(null, alarm.id) != null
    }
  }

  // Get alarms without read:alarm role
  def "should not return alarms"() {
    when:
    regularUserResource.getAlarms(null, MASTER_REALM, null, null, null)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when:
    regularUserResource.getAlarm(null, 1L)

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  // Update alarm as admin
  @Unroll
  def "should update an alarm with title '#title', content '#content', severity '#severity', status '#status' and assigneeId '#assigneeId'"() {
    when:
    def alarm = adminResource.createAlarm(null, new Alarm().setTitle('Updatable alarm').setContent('Updatable content').setStatus(Alarm.Status.CLOSED).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.updateAlarm(null, alarm.id, new SentAlarm().setTitle(title).setContent(content).setRealm(MASTER_REALM).setSeverity(severity).setStatus(status).setAssigneeId(assigneeId))
    def updated = adminResource.getAlarms(null, MASTER_REALM, null, null, null)[0]

    then:
    updated != null
    updated.title == title
    updated.content == content
    updated.severity == severity
    updated.status == status
    updated.assigneeId == assigneeId

    where:
    title | content | severity | status | assigneeId
    "Updated Alarm" | "Test Description" | Severity.HIGH | Alarm.Status.OPEN | null
    "Another Alarm" | "Updated Description" | Severity.MEDIUM | Alarm.Status.CLOSED | keycloakTestSetup.testuser1Id
  }

  // Update alarm as an new super user in other realm
  @Unroll
  def "should update an alarm with title '#title', content '#content', severity '#severity', status '#status' and assigneeId '#assigneeId' as an new super user in other realm"() {
    when:
    def alarm = superAdminResource.createAlarm(null, new Alarm().setTitle('Updatable alarm').setContent('Updatable content').setStatus(Alarm.Status.CLOSED).setSeverity(Severity.LOW).setRealm(keycloakTestSetup.realmBuilding.name), null)
    superAdminResource.updateAlarm(null, alarm.id, new SentAlarm().setTitle(title).setContent(content).setRealm(keycloakTestSetup.realmBuilding.name).setSeverity(severity).setStatus(status).setAssigneeId(assigneeId))
    def updated = superAdminResource.getAlarms(null, keycloakTestSetup.realmBuilding.name, null, null, null)[0]

    then:
    updated != null
    updated.title == title
    updated.content == content
    updated.severity == severity
    updated.status == status
    updated.assigneeId == assigneeId

    where:
    title | content | severity | status | assigneeId
    "Updated Alarm" | "Test Description" | Severity.HIGH | Alarm.Status.OPEN | null
    "Another Alarm" | "Updated Description" | Severity.MEDIUM | Alarm.Status.CLOSED | keycloakTestSetup.testuser1Id
  }

  @Unroll
  def "should not update an alarm with id 0"() {
    when:
    adminResource.updateAlarm(null, 0, new SentAlarm().setTitle('title').setContent('content').setSeverity(Severity.LOW).setStatus(Alarm.Status.OPEN))

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }
  }

  // Update alarm without write:alarm role
  @Unroll
  def "should not update an alarm with title '#title', content '#content', severity '#severity', and status '#status'"() {
    when:
    def alarm = adminResource.createAlarm(null, new Alarm().setTitle('Some alarm').setContent('Some content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    regularUserResource.updateAlarm(null, alarm.id, new SentAlarm().setTitle(title).setContent(content).setSeverity(severity).setStatus(status))

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    where:
    title | content | severity | status
    "Updated Alarm" | "Test Description" | Severity.HIGH | Alarm.Status.OPEN
    "Another Alarm" | "Updated Description" | Severity.MEDIUM | Alarm.Status.CLOSED
  }

  // Delete alarm without write:alarm role
  def "should not delete alarm without proper permissions"() {
    when:
    adminResource.createAlarm(null, new Alarm().setTitle('Some alarm').setContent('Some content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    def alarm = adminResource.getAlarms(null, MASTER_REALM, null, null, null)[0]
    regularUserResource.removeAlarm(null, alarm.id)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  // Delete alarms without write:alarm role
  def "should not delete alarms without proper permissions"() {
    when:
    adminResource.createAlarm(null, new Alarm().setTitle('Some alarm').setContent('Some content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('Another alarm').setContent('More content').setStatus(Alarm.Status.IN_PROGRESS).setSeverity(Severity.MEDIUM).setRealm(MASTER_REALM), null)
    def alarms = adminResource.getAlarms(null, MASTER_REALM, null, null, null)
    regularUserResource.removeAlarms(null, (List<Long>) alarms.collect { it.id })

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  // Delete alarms from another realm as non-super-user
  def "should not bulk delete alarms from another realm"() {
    given:
    def masterAlarm = adminResource.createAlarm(null, new Alarm("Master alarm", "Master content", Severity.MEDIUM, null, MASTER_REALM), null)

    when:
    buildingUserResource.removeAlarms(null, [masterAlarm.id])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    and:
    adminResource.getAlarm(null, masterAlarm.id) != null

    when:
    buildingUserResource.getAlarm(null, masterAlarm.id)

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    when:
    buildingUserResource.removeAlarm(null, masterAlarm.id)

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  // Delete alarms in a mixed-realm bulk request as non-super-user
  def "should not bulk delete any alarms when one alarm belongs to another realm"() {
    given:
    def buildingRealm = keycloakTestSetup.realmBuilding.name
    def buildingAlarm = buildingUserResource.createAlarm(null, new Alarm("Building alarm", "Building content", Severity.MEDIUM, null, buildingRealm), null)
    def masterAlarm = adminResource.createAlarm(null, new Alarm("Master alarm", "Master content", Severity.MEDIUM, null, MASTER_REALM), null)

    when:
    buildingUserResource.removeAlarms(null, [buildingAlarm.id, masterAlarm.id])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }

    and:
    buildingUserResource.getAlarm(null, buildingAlarm.id) != null
    adminResource.getAlarm(null, masterAlarm.id) != null
  }

  // Delete empty or null alarms
  def "should not delete null or empty alarms"() {
    when:
    adminResource.removeAlarm(null, 0)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    when:
    adminResource.removeAlarms(null, [])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.removeAlarms(null, [null])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
  }

  // Delete invalid alarms
  def "should not delete alarms with invalid ID"() {
    when:
    adminResource.removeAlarm(null, -1L)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.removeAlarms(null, [-1L, 0L, 1L])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
  }

  // Delete non-exising alarms
  def "should not delete non-existing alarms"() {
    when:
    adminResource.removeAlarm(null, 1L)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }

    when:
    adminResource.removeAlarms(null, [1L, 2L, 3L])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 404
      return true
    }
  }

  // Delete one alarm as admin
  def "should delete one alarm as admin"() {
    when:
    for (int i = 0; i < 2; i++) {
      adminResource.createAlarm(null, new Alarm("Alarm " + i, "Content " + i, Severity.MEDIUM, null, MASTER_REALM), null)
    }
    def delete = adminResource.getAlarms(null, MASTER_REALM, null, null, null)[0]
    adminResource.removeAlarm(null, delete.id)

    then: "returns some alarms but not the deleted alarm"
    def alarms = adminResource.getAlarms(null, MASTER_REALM, null, null, null)
    alarms.find { it.id == delete.id } == null
    alarms.length> 0
  }

  // Delete one alarm as an super user in other realm
  def "should delete one alarm as new super user in other realm"() {
    when:
    for (int i = 0; i < 2; i++) {
      superAdminResource.createAlarm(null, new Alarm("Alarm " + i, "Content " + i, Severity.MEDIUM, null, keycloakTestSetup.realmBuilding.name), null)
    }
    def delete = superAdminResource.getAlarms(null, keycloakTestSetup.realmBuilding.name, null, null, null)[0]
    superAdminResource.removeAlarm(null, delete.id)

    then: "returns some alarms but not the deleted alarm"
    def alarms = superAdminResource.getAlarms(null, keycloakTestSetup.realmBuilding.name, null, null, null)
    alarms.find { it.id == delete.id } == null
    alarms.length> 0
  }

  // Delete multiple alarms as admin
  def "should delete multiple alarms as admin"() {
    when:
    for (int i = 0; i < 2; i++) {
      adminResource.createAlarm(null, new Alarm("Alarm " + i, "Content " + i, Severity.MEDIUM, null, MASTER_REALM), null)
    }
    def alarms = adminResource.getAlarms(null, MASTER_REALM, null, null, null)
    adminResource.removeAlarms(null, (List<Long>) alarms.collect { it.id })

    then: "returns no alarms"
    adminResource.getAlarms(null, MASTER_REALM, null, null, null).length == 0
  }

  // Delete multiple alarms as super user in other realm
  def "should delete multiple alarms as super user in other realm"() {
    when:
    for (int i = 0; i < 2; i++) {
      superAdminResource.createAlarm(null, new Alarm("Alarm " + i, "Content " + i, Severity.MEDIUM, null, keycloakTestSetup.realmBuilding.name), null)
    }
    def alarms = superAdminResource.getAlarms(null, keycloakTestSetup.realmBuilding.name, null, null, null)
    superAdminResource.removeAlarms(null, (List<Long>) alarms.collect { it.id })

    then: "returns no alarms"
    superAdminResource.getAlarms(null, keycloakTestSetup.realmBuilding.name, null, null, null).length == 0
  }

  // Get open alarms
  def "should get open alarms"() {
    when: "two open and one closed alarms are added"
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 1').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 2').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.createAlarm(null, new Alarm().setTitle('alarm 3').setContent('content').setStatus(Alarm.Status.CLOSED).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    def openAlarms = adminResource.getAlarms(null, MASTER_REALM, Alarm.Status.OPEN, null, null)

    then: "returns 2 open alarms"
    openAlarms.size() == 2
  }

  // Linking alarms as admin
  def "should be able to link alarms to assets as admin"() {
    when: "two alarms are added"
    def alarm1 = adminResource.createAlarm(null, new Alarm().setTitle('alarm 1').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    def alarm2 = adminResource.createAlarm(null, new Alarm().setTitle('alarm 2').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)

    then: "both can be linked to an asset"
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm1.id, managerTestSetup.smartOfficeId)])
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm2.id, managerTestSetup.smartOfficeId)])

    when: "the alarm asset links are retrieved"
    def alarm1Links = adminResource.getAssetLinks(null, alarm1.id, MASTER_REALM)
    def alarm2Links = adminResource.getAssetLinks(null, alarm2.id, MASTER_REALM)

    then: "the alarm asset links match"
    alarm1Links.size() == 1
    alarm1Links.get(0).id.alarmId == alarm1.id
    alarm1Links.get(0).id.assetId == managerTestSetup.smartOfficeId
    alarm1Links.get(0).id.realm == MASTER_REALM
    alarm2Links.size() == 1
    alarm2Links.get(0).id.alarmId == alarm2.id
    alarm2Links.get(0).id.assetId == managerTestSetup.smartOfficeId
    alarm2Links.get(0).id.realm == MASTER_REALM

    when: "more alarm asset links are added"
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm1.id, managerTestSetup.lobbyId)])
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm2.id, managerTestSetup.lobbyId)])
    alarm1Links = adminResource.getAssetLinks(null, alarm1.id, MASTER_REALM)
    alarm2Links = adminResource.getAssetLinks(null, alarm2.id, MASTER_REALM)

    then: "these alarm asset links also match"
    alarm1Links.size() == 2
    alarm1Links.get(0).id.alarmId == alarm1.id
    alarm1Links.get(0).id.assetId == managerTestSetup.lobbyId
    alarm1Links.get(0).id.realm == MASTER_REALM
    alarm1Links.get(1).id.alarmId == alarm1.id
    alarm1Links.get(1).id.assetId == managerTestSetup.smartOfficeId
    alarm1Links.get(1).id.realm == MASTER_REALM
    alarm2Links.size() == 2
    alarm2Links.get(0).id.alarmId == alarm2.id
    alarm2Links.get(0).id.assetId == managerTestSetup.lobbyId
    alarm2Links.get(0).id.realm == MASTER_REALM
    alarm2Links.get(1).id.alarmId == alarm2.id
    alarm2Links.get(1).id.assetId == managerTestSetup.smartOfficeId
    alarm2Links.get(1).id.realm == MASTER_REALM
  }

  def "should reject asset links for multiple alarms in one request"() {
    given:
    def alarm1 = adminResource.createAlarm(null, new Alarm().setTitle('alarm 1').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    def alarm2 = adminResource.createAlarm(null, new Alarm().setTitle('alarm 2').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)

    when:
    adminResource.setAssetLinks(null, [
      new AlarmAssetLink(MASTER_REALM, alarm1.id, managerTestSetup.smartOfficeId),
      new AlarmAssetLink(MASTER_REALM, alarm2.id, managerTestSetup.smartOfficeId)
    ])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and:
    adminResource.getAssetLinks(null, alarm1.id, MASTER_REALM).isEmpty()
    adminResource.getAssetLinks(null, alarm2.id, MASTER_REALM).isEmpty()
  }

  // Linking alarms across realms
  def "should reject asset links for multiple realms in one request"() {
    given:
    def buildingRealm = keycloakTestSetup.realmBuilding.name
    def alarm = buildingUserResource.createAlarm(null, new Alarm("Building alarm", "Building content", Severity.MEDIUM, null, buildingRealm), null)
    def links = [
      new AlarmAssetLink(buildingRealm, alarm.id, managerTestSetup.apartment1Id),
      new AlarmAssetLink(MASTER_REALM, alarm.id, managerTestSetup.smartOfficeId)
    ]

    when:
    buildingUserResource.setAssetLinks(null, links)

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and:
    buildingUserResource.getAssetLinks(null, alarm.id, buildingRealm).isEmpty()
    superAdminResource.getAssetLinks(null, alarm.id, MASTER_REALM).isEmpty()
  }

  def "should reject asset links pointing to assets from another realm"() {
    given:
    def buildingRealm = keycloakTestSetup.realmBuilding.name
    def alarm = buildingUserResource.createAlarm(null, new Alarm("Building alarm", "Building content", Severity.MEDIUM, null, buildingRealm), null)

    when:
    buildingUserResource.setAssetLinks(null, [new AlarmAssetLink(buildingRealm, alarm.id, managerTestSetup.smartOfficeId)])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and:
    buildingUserResource.getAssetLinks(null, alarm.id, buildingRealm).isEmpty()
  }

  def "should reject asset links pointing to alarms from another realm"() {
    given:
    def buildingRealm = keycloakTestSetup.realmBuilding.name
    def masterAlarm = adminResource.createAlarm(null, new Alarm("Master alarm", "Master content", Severity.MEDIUM, null, MASTER_REALM), null)

    when:
    buildingUserResource.setAssetLinks(null, [new AlarmAssetLink(buildingRealm, masterAlarm.id, managerTestSetup.apartment1Id)])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    and:
    buildingUserResource.getAssetLinks(null, masterAlarm.id, buildingRealm).isEmpty()
  }

  def "should reject creating alarms with linked assets from another realm"() {
    given:
    def buildingRealm = keycloakTestSetup.realmBuilding.name

    when:
    buildingUserResource.createAlarm(null, new Alarm("Building alarm", "Building content", Severity.MEDIUM, null, buildingRealm), [managerTestSetup.smartOfficeId])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
  }

  // Linking alarms without permissions
  def "should not be able to link alarms without proper permissions"() {
    when:
    def alarm = adminResource.createAlarm(null, new Alarm().setTitle('alarm 1').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    regularUserResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm.id, managerTestSetup.smartOfficeId)])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  // Creating invalid alarm links
  def "should not create invalid alarm links"() {
    when:
    def alarm = adminResource.createAlarm(null, new Alarm().setTitle('alarm 1').setContent('content').setStatus(Alarm.Status.OPEN).setSeverity(Severity.LOW).setRealm(MASTER_REALM), null)
    adminResource.setAssetLinks(null, [])

    then:
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.setAssetLinks(null, [null])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.setAssetLinks(null, [new AlarmAssetLink(null, null, null)])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }


    when:
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, null, null)])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, -1L, null)])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm.id, null)])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when:
    adminResource.setAssetLinks(null, [new AlarmAssetLink(MASTER_REALM, alarm.id, "")])

    then:
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
  }
}
