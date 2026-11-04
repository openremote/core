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

import jakarta.persistence.EntityNotFoundException
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.openremote.manager.alarm.AlarmService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.rules.RulesEngineId
import org.openremote.manager.rules.facade.AlarmFacade
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.model.alarm.Alarm
import org.openremote.model.alarm.SentAlarm
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.notification.EmailNotificationMessage
import org.openremote.model.notification.Notification
import org.openremote.model.notification.PushNotificationMessage
import org.openremote.model.security.ClientRole
import org.openremote.model.security.User
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification

import static org.openremote.model.Constants.RESTRICTED_USER_REALM_ROLE

/** Exercises creation, linking and notification recipients against real assets, roles and persistence. */
class AlarmFacadeTest extends Specification implements ManagerContainerTrait {

  private static final String SOURCE_ID = "4242"

  @Shared
  AlarmService alarmService

  @Shared
  AssetStorageService assetStorageService

  @Shared
  ManagerIdentityService identityService

  @Shared
  NotificationService originalNotificationService

  @Shared
  Map<String, ThingAsset> testAssets = [:]

  @Shared
  Map<String, User> testUsers = [:]

  @Shared
  String realm

  @Shared
  String otherRealm

  String alarmTitle
  List<UserAssetLink> createdUserLinks = []
  List<Notification> queuedNotifications = []

  def setupSpec() {
    def container = startContainer(defaultConfig(), defaultServices())
    alarmService = container.getService(AlarmService.class)
    assetStorageService = container.getService(AssetStorageService.class)
    originalNotificationService = container.getService(NotificationService.class)
    identityService = container.getService(ManagerIdentityService.class)
    def identityProvider = identityService.identityProvider
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    realm = keycloakSetup.realmBuilding.name
    otherRealm = keycloakSetup.realmCity.name

    testAssets.ancestor = createAsset("Ancestor", realm)
    testAssets.scoped = createAsset("Scoped", realm, testAssets.ancestor.id)
    testAssets.child = createAsset("Child", realm, testAssets.scoped.id)
    testAssets.grandchild = createAsset("Grandchild", realm, testAssets.child.id)
    testAssets.sibling = createAsset("Sibling", realm, testAssets.ancestor.id)
    testAssets.unrelated = createAsset("Unrelated", realm)
    testAssets.otherRealm = createAsset("Other realm", otherRealm)
    testAssets.otherRealmChild = createAsset("Other realm child", otherRealm, testAssets.otherRealm.id)

    testUsers.unrestricted = identityProvider.getUser(keycloakSetup.testuser2Id)
    testUsers.restricted = identityProvider.getUser(keycloakSetup.testuser3Id)
    testUsers.otherRealm = identityProvider.getUser(keycloakSetup.testuser1Id)
    testUsers.otherAlarmWriter = identityProvider.getUser(keycloakSetup.testuser4Id)
    testUsers.notificationWriter = keycloakSetup.createUser(
            realm, "alarm-facade-writer", UniqueIdentifierGenerator.generateId(), "Alarm", "Writer",
            "alarm-facade-writer@openremote.local", true, [ClientRole.WRITE_ALARMS] as ClientRole[])
    assert testUsers.unrestricted.realm == realm
    assert testUsers.restricted.realm == realm
    assert testUsers.otherRealm.realm != realm
    assert !identityProvider.getUserRealmRoles(realm, testUsers.unrestricted.id).contains(RESTRICTED_USER_REALM_ROLE)
    assert identityProvider.getUserRealmRoles(realm, testUsers.restricted.id).contains(RESTRICTED_USER_REALM_ROLE)
  }

  def setup() {
    alarmTitle = "Alarm facade ${UniqueIdentifierGenerator.generateId()}"
    // Capture the synchronous enqueue boundary, so absence checks do not race the notification
    // worker. Nothing from this specification is delivered to email or push providers.
    alarmService.notificationService = Stub(NotificationService) {
      sendNotificationAsync(_ as Notification, _ as Notification.Source, _ as String) >> { Notification notification, Notification.Source source, String sourceId ->
        queuedNotifications.add(notification)
      }
    }
  }

  def cleanup() {
    alarmService.notificationService = originalNotificationService
    // Look up by this iteration's unique title, including alarms persisted by a failing rejection
    // test. Do not depend on the facade returning the ID before an assertion or exception fails.
    [realm, otherRealm].each { alarmRealm ->
      createdAlarms(alarmRealm).each { alarmService.removeAlarm(it) }
    }
    if (!createdUserLinks.isEmpty()) {
      assetStorageService.deleteUserAssetLinks(createdUserLinks)
    }
  }

  def cleanupSpec() {
    if (testUsers.notificationWriter != null) {
      identityService.identityProvider.deleteUser(realm, testUsers.notificationWriter.id)
    }
    if (assetStorageService != null && !testAssets.isEmpty()) {
      assert assetStorageService.delete(testAssets.values().collect { it.id })
    }
  }

  def "#scope scope creating an alarm for #targetName is allowed: #allowed"() {
    given:
    def facade = facadeFor(scope)
    def targetId = testAssets[targetName].id
    def alarm = newAlarm()

    when:
    def alarmId = facade.create(alarm, [targetId])

    then:
    if (allowed) {
      assertCreated(alarmId, scope, null, [targetId])
    } else {
      verifyAll {
        alarmId == null
        createdAlarms().isEmpty()
        queuedNotifications.isEmpty()
      }
    }

    where:
    scope   | targetName   | allowed
    "realm" | "ancestor"   | true
    "realm" | "sibling"    | true
    "realm" | "unrelated"  | true
    "asset" | "scoped"     | true
    "asset" | "child"      | true
    "asset" | "grandchild" | true
    "asset" | "ancestor"   | false
    "asset" | "sibling"    | false
    "asset" | "unrelated"  | false
  }

  def "#scope scope creating an alarm without targets #targetIds for #assignee is allowed: #allowed"() {
    given:
    def user = assignee == "none" ? null : testUsers[assignee]
    if (assignee == "restricted") {
      // Access to the engine's asset does not make it an implicit alarm target.
      linkNotificationRecipient(["scoped"])
    }
    def alarm = newAlarm(user?.id, Alarm.Severity.HIGH)

    when:
    def alarmId = facadeFor(scope).create(alarm, targetIds)

    then:
    if (allowed) {
      assertCreated(alarmId, scope, user?.id, [])
      assertNotificationRecipients(user == null ? unrestrictedNotificationRecipients() : [user])
    } else {
      verifyAll {
        alarmId == null
        createdAlarms().isEmpty()
        queuedNotifications.isEmpty()
      }
    }

    where:
    scope   | targetIds | assignee       | allowed
    "asset" | null      | "none"         | false
    "asset" | []        | "none"         | false
    "asset" | null      | "unrestricted" | false
    "asset" | []        | "unrestricted" | false
    "asset" | null      | "restricted"   | false
    "asset" | []        | "restricted"   | false
    "realm" | null      | "none"         | true
    "realm" | []        | "none"         | true
    "realm" | null      | "unrestricted" | true
    "realm" | []        | "unrestricted" | true
    "realm" | null      | "restricted"   | false
    "realm" | []        | "restricted"   | false
  }

  def "#scope scope rejects a foreign-realm asset before creating the alarm"() {
    when:
    facadeFor(scope).create(newAlarm(), [testAssets.otherRealm.id])

    then:
    thrown(AlarmService.AlarmAssetLinkRealmMismatchException)
    createdAlarms().isEmpty()
    queuedNotifications.isEmpty()

    where:
    scope << ["realm", "asset"]
  }

  def "asset scope rejects the complete mixed target list #targetNames before notifying"() {
    given:
    def alarm = newAlarm(testUsers.unrestricted.id, Alarm.Severity.HIGH)
    def targetIds = targetNames.collect { testAssets[it].id }

    when:
    def alarmId = facadeFor("asset").create(alarm, targetIds)

    then:
    verifyAll {
      alarmId == null
      createdAlarms().isEmpty()
      queuedNotifications.isEmpty()
    }

    where:
    targetNames << [["child", "sibling"], ["sibling", "child"]]
  }

  def "#scope scope accepts duplicate allowed asset IDs and keeps one link per asset"() {
    given:
    def targetId = testAssets.child.id

    when:
    def alarmId = facadeFor(scope).create(newAlarm(), [targetId, targetId])

    then:
    assertCreated(alarmId, scope, null, [targetId])
    queuedNotifications.isEmpty()

    where:
    scope << ["realm", "asset"]
  }

  def "#scope scope overrides the supplied alarm realm with the engine realm"() {
    given:
    def alarm = newAlarm().setRealm(otherRealm)

    when:
    def alarmId = facadeFor(scope).create(alarm, [testAssets.child.id])

    then:
    assertCreated(alarmId, scope, null, [testAssets.child.id])

    where:
    scope << ["realm", "asset"]
  }

  def "#scope scope assigning #assignee to #targetNames with links #linkedNames is allowed: #allowed"() {
    given:
    def user = testUsers[assignee]
    def targetIds = targetNames.collect { testAssets[it].id }
    linkedNames.each { name ->
      createdUserLinks.add(new UserAssetLink(realm, user.id, testAssets[name].id))
    }
    assetStorageService.storeUserAssetLinks(createdUserLinks)
    // These are newly created assets: verify precisely which targets the assignee can access.
    targetNames.each { name ->
      assert assetStorageService.isUserAsset(user.id, testAssets[name].id) == linkedNames.contains(name)
    }
    def alarm = newAlarm(user.id, Alarm.Severity.HIGH)

    when:
    def alarmId = facadeFor(scope).create(alarm, targetIds)

    then:
    if (allowed) {
      assertCreated(alarmId, scope, user.id, targetIds)
      // Positive control: the capture is connected to the real alarm service's notification path.
      assert queuedNotifications.size() == 2
      assert queuedNotifications.any { notification ->
        notification.targets?.any { it.type == Notification.TargetType.USER && it.id == user.id }
      }
    } else {
      verifyAll {
        alarmId == null
        createdAlarms().isEmpty()
        queuedNotifications.isEmpty()
      }
    }

    where:
    [scope, assignee, targetNames, linkedNames, allowed] << ["realm", "asset"].collectMany { scope ->
      assigneeCases().collect { row -> [scope] + row }
    }
  }

  def "#scope scope rejects a missing assignee before creating or notifying"() {
    when:
    def alarmId = facadeFor(scope).create(
            newAlarm(UniqueIdentifierGenerator.generateId(), Alarm.Severity.HIGH), [testAssets.child.id])

    then:
    verifyAll {
      alarmId == null
      createdAlarms().isEmpty()
      queuedNotifications.isEmpty()
    }

    where:
    scope << ["realm", "asset"]
  }

  def "global scope creates in #expectedRealm with declared realm #declaredRealm and targets #targetNames"() {
    given:
    def targetIds = targetNames == null ? null : assetIdsFor(targetNames)
    def alarm = newAlarm().setRealm(declaredRealm).setSource(Alarm.Source.ASSET_RULESET)

    when:
    def alarmId = facadeFor("global").create(alarm, targetIds)

    then:
    assertCreated(alarmId, "global", null, targetIds ?: [], expectedRealm)
    queuedNotifications.isEmpty()

    where:
    declaredRealm | targetNames          | expectedRealm
    realm         | ["child"]            | realm
    otherRealm    | ["otherRealm"]       | otherRealm
    realm         | null                 | realm
    realm         | []                   | realm
    otherRealm    | []                   | otherRealm
    null          | ["child", "sibling"] | realm
    null          | ["otherRealm"]       | otherRealm
    null          | ["child", "child"]   | realm
    ""            | ["child"]            | realm
  }

  def "global scope rejects conflicting realms for declared realm #declaredRealm and targets #targetNames"() {
    given:
    def alarm = newAlarm(null, Alarm.Severity.HIGH).setRealm(declaredRealm)

    when:
    facadeFor("global").create(alarm, assetIdsFor(targetNames))

    then:
    thrown(AlarmService.AlarmAssetLinkRealmMismatchException)
    [realm, otherRealm].every { createdAlarms(it).isEmpty() }
    queuedNotifications.isEmpty()

    where:
    declaredRealm | targetNames
    realm         | ["otherRealm"]
    otherRealm    | ["child"]
    realm         | ["child", "otherRealm"]
    realm         | ["otherRealm", "child"]
    null          | ["child", "otherRealm"]
    null          | ["otherRealm", "child"]
  }

  def "global scope rejects missing realm #declaredRealm and missing targets #targetIds"() {
    given: "an assignee's realm cannot substitute for an explicit realm or target assets"
    def alarm = newAlarm(testUsers.unrestricted.id, Alarm.Severity.HIGH).setRealm(declaredRealm)

    when:
    def alarmId = facadeFor("global").create(alarm, targetIds)

    then:
    verifyAll {
      alarmId == null
      [realm, otherRealm].every { createdAlarms(it).isEmpty() }
      queuedNotifications.isEmpty()
    }

    where:
    declaredRealm | targetIds
    null          | null
    null          | []
    ""            | null
    ""            | []
  }

  def "global scope with #realmMode realm assigning #assignee to #targetNames with links #linkedNames is allowed: #allowed"() {
    given:
    def user = testUsers[assignee]
    if (assignee == "restricted") {
      linkNotificationRecipient(linkedNames)
    }
    def targetIds = assetIdsFor(targetNames)
    def alarm = newAlarm(user.id, Alarm.Severity.HIGH).setRealm(realmMode == "explicit" ? realm : null)

    when:
    def alarmId = facadeFor("global").create(alarm, targetIds)

    then:
    if (allowed) {
      assertCreated(alarmId, "global", user.id, targetIds)
      assertNotificationRecipients([user])
    } else {
      verifyAll {
        alarmId == null
        [realm, otherRealm].every { createdAlarms(it).isEmpty() }
        queuedNotifications.isEmpty()
      }
    }

    where:
    [realmMode, assignee, targetNames, linkedNames, allowed] << ["explicit", "inferred"].collectMany { realmMode ->
      [
        ["unrestricted", ["child", "sibling"], [], true],
        ["restricted", ["child"], ["child"], true],
        ["restricted", ["child", "child"], ["child"], true],
        ["restricted", ["child", "grandchild"], ["child", "grandchild"], true],
        ["restricted", ["child"], [], false],
        ["restricted", ["child"], ["scoped"], false],
        ["restricted", ["child", "grandchild"], ["child"], false],
        ["otherRealm", ["child"], [], false]
      ].collect { row -> [realmMode] + row }
    }
  }

  def "global scope without targets #targetIds assigning #assignee is allowed: #allowed"() {
    given:
    def user = testUsers[assignee]
    if (assignee == "restricted") {
      linkNotificationRecipient(["scoped"])
    }

    when:
    def alarmId = facadeFor("global").create(newAlarm(user.id, Alarm.Severity.HIGH), targetIds)

    then:
    if (allowed) {
      assertCreated(alarmId, "global", user.id, [])
      assertNotificationRecipients([user])
    } else {
      verifyAll {
        alarmId == null
        [realm, otherRealm].every { createdAlarms(it).isEmpty() }
        queuedNotifications.isEmpty()
      }
    }

    where:
    targetIds | assignee       | allowed
    null      | "unrestricted" | true
    []        | "unrestricted" | true
    null      | "restricted"   | false
    []        | "restricted"   | false
  }

  def "global scope with #realmMode realm rejects a missing assignee"() {
    given:
    def alarm = newAlarm(UniqueIdentifierGenerator.generateId(), Alarm.Severity.HIGH)
            .setRealm(realmMode == "explicit" ? realm : null)

    when:
    def alarmId = facadeFor("global").create(alarm, assetIdsFor(["child"]))

    then:
    verifyAll {
      alarmId == null
      [realm, otherRealm].every { createdAlarms(it).isEmpty() }
      queuedNotifications.isEmpty()
    }

    where:
    realmMode << ["explicit", "inferred"]
  }

  def "global scope with #realmMode realm rejects invalid target #invalidTarget before creating or notifying"() {
    given:
    def alarm = newAlarm(null, Alarm.Severity.HIGH).setRealm(realmMode == "explicit" ? realm : null)
    def invalidId = invalidTarget == "missing" ? UniqueIdentifierGenerator.generateId() : invalidTarget

    when:
    facadeFor("global").create(alarm, [testAssets.child.id, invalidId])

    then:
    thrown(exceptionType)
    [realm, otherRealm].every { createdAlarms(it).isEmpty() }
    queuedNotifications.isEmpty()

    where:
    [realmMode, invalidTarget, exceptionType] << ["explicit", "inferred"].collectMany { realmMode ->
      [
        [null, IllegalArgumentException],
        ["", IllegalArgumentException],
        ["missing", EntityNotFoundException]
      ].collect { row -> [realmMode] + row }
    }
  }

  def "global scope infers the realm again when an alarm definition is reused"() {
    given: "JSON actions can reuse the same alarm definition across executions"
    def facade = facadeFor("global")
    def alarm = newAlarm().setRealm(null)

    when:
    def firstId = facade.create(alarm, assetIdsFor(["child"]))

    then:
    assertCreated(firstId, "global", null, assetIdsFor(["child"]), realm)

    when: "a later execution targets another realm"
    def secondId = facade.create(alarm, assetIdsFor(["otherRealm"]))

    then:
    assertCreated(secondId, "global", null, assetIdsFor(["otherRealm"]), otherRealm)
    secondId != firstId
    queuedNotifications.isEmpty()
  }

  def "unassigned HIGH alarm on #operation notifies a restricted user linked to #linkedNames for targets #targetNames: #allowed"() {
    given:
    linkNotificationRecipient(linkedNames)
    def expectedRecipients = unrestrictedNotificationRecipients()
    if (allowed) {
      expectedRecipients.add(testUsers.restricted)
    }

    when:
    def alarmId = createOrUpdateUnassignedHighAlarm(operation, targetNames)

    then:
    alarmService.getAlarm(alarmId).assigneeId == null
    assertNotificationRecipients(expectedRecipients)

    where:
    [operation, targetNames, linkedNames, allowed] << ["realm create", "asset create", "update"].collectMany { operation ->
      [
        [["child"], [], false],
        [["child"], ["scoped"], false],
        [["child"], ["sibling"], false],
        [["child"], ["child"], true],
        [["child", "grandchild"], ["child"], false],
        [["grandchild", "child"], ["child"], false],
        [["child", "grandchild"], ["child", "grandchild"], true],
        [["child", "child"], ["child"], true]
      ].collect { row -> [operation] + row }
    }
  }

  def "unassigned HIGH alarm with no asset links on #operation notifies only unrestricted users"() {
    given: "a restricted recipient has an unrelated asset link"
    linkNotificationRecipient(["child"])

    when:
    def alarmId = createOrUpdateUnassignedHighAlarm(operation, targetNames)

    then:
    alarmService.getAssetLinks(alarmId, realm).isEmpty()
    assertNotificationRecipients(unrestrictedNotificationRecipients())

    where:
    operation      | targetNames
    "realm create" | null
    "realm create" | []
    "update"       | []
  }

  def "updates recheck restricted recipient access after an asset link is revoked"() {
    given:
    linkNotificationRecipient(["child", "grandchild"])
    def alarmId = facadeFor("asset").create(newAlarm(null, Alarm.Severity.HIGH), assetIdsFor(["child", "grandchild"]))
    assertNotificationRecipients(unrestrictedNotificationRecipients() + testUsers.restricted)
    queuedNotifications.clear()
    def revokedLink = createdUserLinks.find { it.id.assetId == testAssets.grandchild.id }
    assetStorageService.deleteUserAssetLinks([revokedLink])
    createdUserLinks.remove(revokedLink)

    when:
    def oldAlarm = alarmService.getAlarm(alarmId)
    def updatedAlarm = alarmService.getAlarm(alarmId)
    updatedAlarm.content = "Updated after access was revoked"
    alarmService.updateAlarm(oldAlarm, updatedAlarm)

    then:
    assertNotificationRecipients(unrestrictedNotificationRecipients())
  }

  def "automatic recipient filtering preserves the exclusion of a manual alarm's creator"() {
    given:
    linkNotificationRecipient(["child"])
    def alarm = newAlarm(null, Alarm.Severity.HIGH)
            .setSource(Alarm.Source.MANUAL).setSourceId(testUsers.notificationWriter.id)

    when:
    alarmService.sendAlarm(alarm, assetIdsFor(["child"]))

    then:
    assertNotificationRecipients([testUsers.otherAlarmWriter, testUsers.restricted])
  }

  def "automatic recipient filtering preserves the exclusion of the previous assignee"() {
    given:
    linkNotificationRecipient(["child"])
    def oldAlarm = createExistingAlarm(["child"], testUsers.restricted.id)
    def updatedAlarm = alarmService.getAlarm(oldAlarm.id)
    updatedAlarm.assigneeId = null
    updatedAlarm.severity = Alarm.Severity.HIGH

    when:
    alarmService.updateAlarm(oldAlarm, updatedAlarm)

    then:
    assertNotificationRecipients(unrestrictedNotificationRecipients())
  }

  def "global scope links targets #targetNames using the existing alarm realm #alarmRealm with links #existingNames"() {
    given:
    def alarm = createExistingAlarm(existingNames, null, alarmRealm)
    def previousLinks = linkTimestamps(alarm)
    def targetIds = assetIdsFor(targetNames)

    when:
    facadeFor("global").linkAssets(targetIds, alarm.id)

    then: "global engines can link anywhere in the alarm's realm, including an initially unlinked alarm"
    noExceptionThrown()
    assertLinkState(alarm, assetIdsFor(existingNames) + targetIds, previousLinks)
    alarmService.getAlarm(alarm.id).realm == alarmRealm

    where:
    alarmRealm | existingNames        | targetNames
    realm      | ["scoped"]           | ["ancestor", "child", "sibling", "unrelated"]
    realm      | ["child", "sibling"] | ["grandchild"]
    realm      | []                   | ["child", "sibling"]
    otherRealm | ["otherRealm"]       | ["otherRealmChild"]
    otherRealm | []                   | ["otherRealm", "otherRealmChild"]
  }

  def "global scope rejects targets #targetNames outside the existing alarm realm #alarmRealm with links #existingNames"() {
    given:
    def alarm = createExistingAlarm(existingNames, null, alarmRealm)
    def previousLinks = linkTimestamps(alarm)

    when:
    facadeFor("global").linkAssets(assetIdsFor(targetNames), alarm.id)

    then: "the alarm's realm is authoritative even when it has no current links"
    thrown(AlarmService.AlarmAssetLinkRealmMismatchException)
    assertLinkState(alarm, assetIdsFor(existingNames), previousLinks)
    alarmService.getAlarm(alarm.id).realm == alarmRealm
    alarmService.getAssetLinks(alarm.id, alarmRealm == realm ? otherRealm : realm).isEmpty()

    where:
    alarmRealm | existingNames  | targetNames
    otherRealm | ["otherRealm"] | ["child"]
    otherRealm | ["otherRealm"] | ["otherRealmChild", "child"]
    otherRealm | ["otherRealm"] | ["child", "otherRealmChild"]
    realm      | []             | ["otherRealm"]
    otherRealm | []             | ["child"]
  }

  def "global scope linking an unlinked alarm with #assignee assignee linked to #linkedNames is allowed: #allowed"() {
    given:
    def user = testUsers[assignee]
    if (assignee == "restricted") {
      linkNotificationRecipient(linkedNames)
    }
    def alarm = createExistingAlarm([], user.id)
    def previousLinks = linkTimestamps(alarm)
    def targetIds = assetIdsFor(["child", "sibling"])

    when:
    facadeFor("global").linkAssets(targetIds, alarm.id)

    then:
    noExceptionThrown()
    assertLinkState(alarm, allowed ? targetIds : [], previousLinks)

    where:
    assignee       | linkedNames          | allowed
    "unrestricted" | []                   | true
    "restricted"   | ["child", "sibling"] | true
    "restricted"   | []                   | false
    "restricted"   | ["child"]            | false
    "restricted"   | ["ancestor"]         | false
    "otherRealm"   | []                   | false
  }

  def "global scope checks the assignee against the existing alarm's realm"() {
    given: "an unrestricted assignee from a different realm than the alarm and its targets"
    def alarm = createExistingAlarm(["otherRealm"], testUsers.unrestricted.id, otherRealm)
    def previousLinks = linkTimestamps(alarm)

    when:
    facadeFor("global").linkAssets(assetIdsFor(["otherRealmChild"]), alarm.id)

    then:
    noExceptionThrown()
    assertLinkState(alarm, assetIdsFor(["otherRealm"]), previousLinks)
  }

  def "#scope scope linking targets #targetNames is allowed: #allowed"() {
    given: "an alarm already linked to the scope asset"
    def alarm = createExistingAlarm(["scoped"])
    def previousLinks = linkTimestamps(alarm)
    def targetIds = assetIdsFor(targetNames)

    when:
    facadeFor(scope).linkAssets(targetIds, alarm.id)

    then: "the complete request is accepted or all existing links are preserved"
    noExceptionThrown()
    assertLinkState(alarm, allowed ? assetIdsFor(["scoped"]) + targetIds : assetIdsFor(["scoped"]), previousLinks)

    where:
    scope   | targetNames          | allowed
    "realm" | ["ancestor"]         | true
    "realm" | ["sibling"]          | true
    "realm" | ["unrelated"]        | true
    "asset" | ["scoped"]           | true
    "asset" | ["child"]            | true
    "asset" | ["grandchild"]       | true
    "asset" | ["ancestor"]         | false
    "asset" | ["sibling"]          | false
    "asset" | ["unrelated"]        | false
    "asset" | ["child", "sibling"] | false
    "asset" | ["sibling", "child"] | false
  }

  def "#scope scope linking to an alarm with existing targets #existingNames is allowed: #allowed"() {
    given: "an alarm whose current links determine its scope"
    def alarm = createExistingAlarm(existingNames)
    def previousLinks = linkTimestamps(alarm)
    def targetIds = assetIdsFor(["grandchild"])

    when: "the requested target is inside the engine's subtree"
    facadeFor(scope).linkAssets(targetIds, alarm.id)

    then: "every existing link must also be in scope, and asset scope cannot claim an unlinked alarm"
    noExceptionThrown()
    assertLinkState(alarm, allowed ? assetIdsFor(existingNames) + targetIds : assetIdsFor(existingNames), previousLinks)

    where:
    scope   | existingNames         | allowed
    "realm" | ["ancestor"]          | true
    "realm" | ["sibling"]           | true
    "realm" | ["unrelated"]         | true
    "realm" | ["child", "sibling"]  | true
    "realm" | []                    | true
    "asset" | ["scoped"]            | true
    "asset" | ["child"]             | true
    "asset" | ["scoped", "child"]   | true
    "asset" | ["ancestor"]          | false
    "asset" | ["sibling"]           | false
    "asset" | ["unrelated"]         | false
    "asset" | ["child", "sibling"]  | false
    "asset" | ["sibling", "child"]  | false
    "asset" | []                    | false
  }

  def "#scope scope linking #targetNames with #assignee assignee linked to #linkedNames is allowed: #allowed"() {
    given:
    def user = testUsers[assignee]
    linkedNames.each { name ->
      createdUserLinks.add(new UserAssetLink(realm, user.id, testAssets[name].id))
    }
    assetStorageService.storeUserAssetLinks(createdUserLinks)
    (["scoped"] + targetNames).unique().each { name ->
      assert assetStorageService.isUserAsset(user.id, testAssets[name].id) == linkedNames.contains(name)
    }
    def alarm = createExistingAlarm(["scoped"], user.id)
    def previousLinks = linkTimestamps(alarm)
    def targetIds = assetIdsFor(targetNames)

    when:
    facadeFor(scope).linkAssets(targetIds, alarm.id)

    then: "restricted assignees need access to the union of current and requested targets"
    noExceptionThrown()
    assertLinkState(alarm, allowed ? assetIdsFor(["scoped"]) + targetIds : assetIdsFor(["scoped"]), previousLinks)

    where:
    [scope, assignee, targetNames, linkedNames, allowed] << ["realm", "asset", "global"].collectMany { scope ->
      linkAssigneeCases().collect { row -> [scope] + row }
    }
  }

  def "#scope scope rejects linking foreign-realm assets in #targetNames without changing existing links"() {
    given:
    def alarm = createExistingAlarm(["scoped"])
    def previousLinks = linkTimestamps(alarm)

    when:
    facadeFor(scope).linkAssets(assetIdsFor(targetNames), alarm.id)

    then:
    thrown(AlarmService.AlarmAssetLinkRealmMismatchException)
    assertLinkState(alarm, assetIdsFor(["scoped"]), previousLinks)

    where:
    [scope, targetNames] << ["realm", "asset", "global"].collectMany { scope ->
      [["otherRealm"], ["child", "otherRealm"], ["otherRealm", "child"]].collect { targets ->
        [scope, targets]
      }
    }
  }

  def "#scope scope rejects linking to a foreign-realm alarm without changing its links"() {
    given:
    def alarm = createExistingAlarm(["otherRealm"], null, otherRealm)
    def previousLinks = linkTimestamps(alarm)

    when:
    facadeFor(scope).linkAssets(assetIdsFor(["child"]), alarm.id)

    then:
    thrown(AlarmService.AlarmAssetLinkRealmMismatchException)
    assertLinkState(alarm, assetIdsFor(["otherRealm"]), previousLinks)
    alarmService.getAssetLinks(alarm.id, realm).isEmpty()

    where:
    scope << ["realm", "asset"]
  }

  def "#scope scope linking duplicate or existing targets #targetNames is idempotent"() {
    given:
    def alarm = createExistingAlarm(["scoped"])
    def previousLinks = linkTimestamps(alarm)
    def targetIds = assetIdsFor(targetNames)

    when:
    facadeFor(scope).linkAssets(targetIds, alarm.id)

    then: "one link per asset is retained and the original link timestamp is preserved"
    assertLinkState(alarm, assetIdsFor(["scoped", "child"]), previousLinks)

    when: "the same request is repeated at a later time"
    def firstLinks = linkTimestamps(alarm)
    advancePseudoClock(1, TimeUnit.SECONDS)
    facadeFor(scope).linkAssets(targetIds, alarm.id)

    then:
    assertLinkState(alarm, assetIdsFor(["scoped", "child"]), firstLinks)

    where:
    [scope, targetNames] << ["realm", "asset", "global"].collectMany { scope ->
      [["child", "child"], ["scoped", "child", "child"]].collect { targets -> [scope, targets] }
    }
  }

  def "#scope scope linking empty input #targetIds remains a no-op"() {
    given:
    def alarm = createExistingAlarm(["scoped"])
    def previousLinks = linkTimestamps(alarm)
    def facade = facadeFor(scope)

    when:
    facade.linkAssets(targetIds, alarm.id)
    facade.linkAssets(targetIds, null)

    then: "empty requests do not change links or require a valid alarm ID"
    noExceptionThrown()
    assertLinkState(alarm, assetIdsFor(["scoped"]), previousLinks)

    where:
    scope   | targetIds
    "realm" | null
    "realm" | []
    "asset" | null
    "asset" | []
    "global" | null
    "global" | []
  }

  def "#scope scope rejects invalid target #invalidTarget without partially linking"() {
    given:
    def alarm = createExistingAlarm(["scoped"])
    def previousLinks = linkTimestamps(alarm)
    def invalidId = invalidTarget == "missing" ? UniqueIdentifierGenerator.generateId() : invalidTarget

    when:
    facadeFor(scope).linkAssets([testAssets.child.id, invalidId], alarm.id)

    then:
    thrown(exceptionType)
    assertLinkState(alarm, assetIdsFor(["scoped"]), previousLinks)

    where:
    [scope, invalidTarget, exceptionType] << ["realm", "asset", "global"].collectMany { scope ->
      [
        [null, IllegalArgumentException],
        ["", IllegalArgumentException],
        ["missing", EntityNotFoundException]
      ].collect { row ->
        [scope] + row
      }
    }
  }

  def "#scope scope retains validation of alarm ID #invalidAlarmId when linking"() {
    given:
    def alarm = createExistingAlarm(["scoped"])
    def previousLinks = linkTimestamps(alarm)

    when:
    facadeFor(scope).linkAssets(assetIdsFor(["child"]), invalidAlarmId)

    then:
    thrown(exceptionType)
    assertLinkState(alarm, assetIdsFor(["scoped"]), previousLinks)

    where:
    [scope, invalidAlarmId, exceptionType] << ["realm", "asset", "global"].collectMany { scope ->
      [
        [null, IllegalArgumentException],
        [-1L, IllegalArgumentException],
        [Long.MAX_VALUE, EntityNotFoundException]
      ].collect { row ->
        [scope] + row
      }
    }
  }

  private void linkNotificationRecipient(List<String> assetNames) {
    createdUserLinks.addAll(assetNames.collect { name ->
      new UserAssetLink(realm, testUsers.restricted.id, testAssets[name].id)
    })
    if (!createdUserLinks.isEmpty()) {
      assetStorageService.storeUserAssetLinks(createdUserLinks)
    }
  }

  private List<User> unrestrictedNotificationRecipients() {
    // The other fixture users either lack the alarm role, belong to another realm, or are
    // restricted and have no links to these new assets. Neither writer needs any asset links.
    [testUsers.notificationWriter, testUsers.otherAlarmWriter]
  }

  private Long createOrUpdateUnassignedHighAlarm(String operation, List<String> assetNames) {
    if (operation == "update") {
      def oldAlarm = createExistingAlarm(assetNames)
      assert queuedNotifications.isEmpty()
      def updatedAlarm = alarmService.getAlarm(oldAlarm.id)
      updatedAlarm.severity = Alarm.Severity.HIGH
      alarmService.updateAlarm(oldAlarm, updatedAlarm)
      return oldAlarm.id
    }
    facadeFor(operation == "asset create" ? "asset" : "realm")
            .create(newAlarm(null, Alarm.Severity.HIGH), assetNames == null ? null : assetIdsFor(assetNames))
  }

  private void assertNotificationRecipients(List<User> expectedUsers) {
    assert queuedNotifications.size() == 2
    def push = queuedNotifications.find { it.message instanceof PushNotificationMessage }
    def email = queuedNotifications.find { it.message instanceof EmailNotificationMessage }
    assert push != null
    assert email != null
    verifyAll {
      push.targets.every { it.type == Notification.TargetType.USER }
      push.targets.collect { it.id }.toSet() == expectedUsers.collect { it.id }.toSet()
      email.message.to.collect {
        it.address
      }.toSet() == expectedUsers.findAll {
        it.email
      }.collect {
        it.email
      }.toSet()
    }
  }

  private static List<List> assigneeCases() {
    [
      // Unrestricted users need no links, even when they have links elsewhere.
      ["unrestricted", ["child"], [], true],
      ["unrestricted", ["child"], ["unrelated"], true],
      ["unrestricted", ["child", "grandchild"], [], true],
      // Restricted users require explicit links to every target, not just a scope/ancestor link.
      ["restricted", ["scoped"], ["scoped"], true],
      ["restricted", ["child"], ["child"], true],
      ["restricted", ["child", "child"], ["child"], true],
      ["restricted", ["child", "grandchild"], ["child", "grandchild"], true],
      ["restricted", ["child"], [], false],
      ["restricted", ["child"], ["scoped"], false],
      ["restricted", ["child"], ["sibling"], false],
      ["restricted", ["child", "grandchild"], ["child"], false],
      ["otherRealm", ["child"], [], false]
    ]
  }

  private static List<List> linkAssigneeCases() {
    [
      ["unrestricted", ["child", "grandchild"], [], true],
      ["unrestricted", ["child"], ["unrelated"], true],
      ["restricted", ["child", "grandchild"], ["scoped", "child", "grandchild"], true],
      // Access to current targets does not grant access to descendants being added.
      ["restricted", ["child"], ["scoped"], false],
      // Access to new targets is insufficient if access to an existing target was revoked.
      ["restricted", ["child"], ["child"], false],
      ["restricted", ["child", "grandchild"], ["scoped", "child"], false],
      ["restricted", ["grandchild", "child"], ["scoped", "child"], false],
      ["restricted", ["child"], ["sibling"], false],
      ["restricted", ["scoped", "child", "child"], ["scoped", "child"], true],
      ["otherRealm", ["child"], [], false]
    ]
  }

  private List<String> assetIdsFor(List<String> names) {
    names.collect { testAssets[it].id }
  }

  private SentAlarm createExistingAlarm(List<String> assetNames, String assigneeId = null, String alarmRealm = realm) {
    // Existing alarms may originate from another entry point or predate the facade checks.
    def alarm = alarmService.sendAlarm(
            newAlarm(assigneeId).setRealm(alarmRealm).setSource(Alarm.Source.REALM_RULESET), assetIdsFor(assetNames))
    // Ensure replacing an existing link would produce an observably different timestamp.
    advancePseudoClock(1, TimeUnit.SECONDS)
    alarm
  }

  private Map<String, Instant> linkTimestamps(SentAlarm alarm) {
    alarmService.getAssetLinks(alarm.id, alarm.realm).collectEntries { link ->
      [(link.id.assetId): link.createdOn]
    }
  }

  private void assertLinkState(SentAlarm alarm, List<String> expectedIds, Map<String, Instant> previousLinks) {
    def links = alarmService.getAssetLinks(alarm.id, alarm.realm)
    assert links.size() == expectedIds.toSet().size()
    assert links.collect { it.id.assetId }.toSet() == expectedIds.toSet()
    previousLinks.each { assetId, createdOn ->
      assert links.find { it.id.assetId == assetId }.createdOn == createdOn
    }
    assert queuedNotifications.isEmpty()
  }

  private ThingAsset createAsset(String name, String assetRealm, String parentId = null) {
    assetStorageService.merge(new ThingAsset("Alarm facade ${name}").setRealm(assetRealm).setParentId(parentId))
  }

  private AlarmFacade facadeFor(String scope) {
    def engineId = scope == "global" ? new RulesEngineId() :
            (scope == "asset" ? new RulesEngineId(realm, testAssets.scoped.id) : new RulesEngineId(realm))
    new AlarmFacade(engineId, alarmService, assetStorageService, identityService)
  }

  private Alarm newAlarm(String assigneeId = null, Alarm.Severity severity = Alarm.Severity.MEDIUM) {
    new Alarm(alarmTitle, "Alarm facade scope regression", severity, assigneeId, realm)
            .setSourceId(SOURCE_ID)
  }

  private List<SentAlarm> createdAlarms(String alarmRealm = realm) {
    alarmService.getAlarms(alarmRealm, null, null, null).findAll { it.title == alarmTitle }
  }

  private void assertCreated(Long alarmId, String scope, String assigneeId, List<String> targetIds, String alarmRealm = realm) {
    assert alarmId != null
    def alarms = createdAlarms(alarmRealm)
    assert alarms.size() == 1
    def alarm = alarms.first()
    assert alarm.id == alarmId
    assert alarm.realm == alarmRealm
    assert alarm.source == [asset: Alarm.Source.ASSET_RULESET, realm: Alarm.Source.REALM_RULESET, global: Alarm.Source.GLOBAL_RULESET][scope]
    assert alarm.sourceId == SOURCE_ID
    assert alarm.assigneeId == assigneeId
    def links = alarmService.getAssetLinks(alarmId, alarmRealm)
    assert links.size() == targetIds.toSet().size()
    assert links.collect { it.id.assetId }.toSet() == targetIds.toSet()
  }
}
