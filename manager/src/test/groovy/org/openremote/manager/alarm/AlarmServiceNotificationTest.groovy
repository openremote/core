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
package org.openremote.manager.alarm

import java.time.Instant
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.security.ManagerIdentityProvider
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.model.Container
import org.openremote.model.alarm.Alarm
import org.openremote.model.alarm.AlarmAssetLink
import org.openremote.model.alarm.SentAlarm
import org.openremote.model.notification.EmailNotificationMessage
import org.openremote.model.notification.Notification
import org.openremote.model.notification.PushNotificationMessage
import org.openremote.model.query.UserQuery
import org.openremote.model.security.User
import spock.lang.Specification

import static org.openremote.model.Constants.RESTRICTED_USER_REALM_ROLE
import static org.openremote.model.Constants.SUPER_USER_REALM_ROLE
import static org.openremote.model.Constants.WRITE_ALARMS_ROLE

class AlarmServiceNotificationTest extends Specification {

  private static final String REALM = "building"

  def "unassigned alarm recipients use one restricted-user query for assets #assetIds"() {
    given:
    def unrestricted = user("unrestricted")
    def fullyLinked = user("fully-linked")
    def partlyLinked = user("partly-linked")
    def unlinked = user("unlinked")
    def candidates = [unrestricted, fullyLinked, partlyLinked, unlinked]
    def restricted = [fullyLinked, partlyLinked, unlinked]
    List<UserQuery> queries = []
    def provider = Stub(ManagerIdentityProvider) {
      queryUsers(_ as UserQuery) >> { UserQuery query ->
        queries.add(query)
        (query.realmRoles*.value == [RESTRICTED_USER_REALM_ROLE] ? restricted : candidates) as User[]
      }
      getUserRealmRoles(_, _) >> {
        throw new AssertionError("Recipient filtering must not request roles separately for each user")
      }
    }
    def storage = Mock(AssetStorageService)
    List<Notification> notifications = []
    def alarm = alarm()
    def service = service(provider, storage, alarm, assetIds, notifications)

    when:
    service.sendAssigneeNotification(alarm, [] as Set)

    then: "role detection is batched and limited to recipients in this alarm's realm"
    queries.size() == 2
    verifyAll(queries[0]) {
      realmPredicate.name == REALM
      clientRoles*.value == [WRITE_ALARMS_ROLE]
      realmRoles*.value == [SUPER_USER_REALM_ROLE]
      serviceUsers == false
    }
    verifyAll(queries[1]) {
      realmPredicate.name == REALM
      ids.toSet() == candidates*.id.toSet()
      realmRoles*.value == [RESTRICTED_USER_REALM_ROLE]
      !realmRoles[0].negate
      clientRoles == null
      assets == null
    }

    and: "only restricted users need links to every distinct asset"
    1 * storage.isUserAssets(fullyLinked.id, assetIds.unique(false)) >> !assetIds.empty
    1 * storage.isUserAssets(partlyLinked.id, assetIds.unique(false)) >> (assetIds.unique(false).size() == 1)
    1 * storage.isUserAssets(unlinked.id, assetIds.unique(false)) >> false
    0 * storage.isUserAssets(unrestricted.id, _)
    def expected = [unrestricted] + (assetIds.empty ? [] : [fullyLinked]) +
    (assetIds.unique(false).size() == 1 ? [partlyLinked] : [])
    assertRecipients(notifications, expected)

    where:
    assetIds << [[], ["child"], ["child", "grandchild"], ["child", "child"]]
  }

  def "unassigned alarm with no candidates does not query restricted users or send notifications"() {
    given:
    def provider = Mock(ManagerIdentityProvider)
    def storage = Mock(AssetStorageService)
    List<Notification> notifications = []
    def alarm = alarm()
    def service = service(provider, storage, alarm, [], notifications)

    when:
    service.sendAssigneeNotification(alarm, [] as Set)

    then:
    1 * provider.queryUsers(_ as UserQuery) >> new User[0]
    0 * provider._
    0 * storage._
    notifications.empty
  }

  def "assigned alarm keeps the assignee lookup without querying restricted users"() {
    given:
    def assignee = user("assignee")
    def provider = Mock(ManagerIdentityProvider)
    def storage = Mock(AssetStorageService)
    List<Notification> notifications = []
    def alarm = alarm().setAssigneeId(assignee.id)
    def service = service(provider, storage, alarm, ["child"], notifications)

    when:
    service.sendAssigneeNotification(alarm, [] as Set)

    then:
    1 * provider.getUser(assignee.id) >> assignee
    0 * provider._
    0 * storage._
    assertRecipients(notifications, [assignee])
  }

  private AlarmService service(ManagerIdentityProvider provider, AssetStorageService storage,
          SentAlarm alarm, List<String> assetIds, List<Notification> notifications) {
    def links = assetIds.collect { new AlarmAssetLink(REALM, alarm.id, it) }
    def service = Spy(AlarmService) {
      getAssetLinks(alarm.id, REALM) >> links
    }
    def container = Stub(Container) {
      getConfig() >> [:]
    }
    def identityService = Stub(ManagerIdentityService) {
      getIdentityProvider() >> provider
    }
    def notificationService = Stub(NotificationService) {
      sendNotificationAsync(_ as Notification, _ as Notification.Source, _ as String) >> { Notification notification, Notification.Source source, String sourceId ->
        notifications.add(notification)
      }
    }
    [container: container, identityService: identityService, assetStorageService: storage,
      notificationService: notificationService].each { name, value ->
      def field = AlarmService.getDeclaredField(name)
      field.accessible = true
      field.set(service, value)
    }
    service
  }

  private static User user(String id) {
    new User().setId(id).setRealm(REALM).setFirstName(id).setEmail("${id}@example.com")
  }

  private static SentAlarm alarm() {
    new SentAlarm().setId(42L).setRealm(REALM).setTitle("Test alarm").setContent("Test content")
            .setSeverity(Alarm.Severity.HIGH).setStatus(Alarm.Status.OPEN)
            .setSource(Alarm.Source.CLIENT).setCreatedOn(Instant.parse("2026-10-30T10:00:00Z"))
  }

  private void assertRecipients(List<Notification> notifications, List<User> expected) {
    assert notifications.size() == 2
    def push = notifications.find { it.message instanceof PushNotificationMessage }
    def email = notifications.find { it.message instanceof EmailNotificationMessage }
    assert push.targets*.id.toSet() == expected*.id.toSet()
    assert email.message.to*.address.toSet() == expected*.email.toSet()
  }
}
