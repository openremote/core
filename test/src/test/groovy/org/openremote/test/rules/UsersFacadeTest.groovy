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

import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.rules.RulesEngineId
import org.openremote.manager.rules.facade.UsersFacade
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.query.UserQuery
import org.openremote.model.query.filter.PathPredicate
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.query.filter.StringPredicate
import org.openremote.model.security.User
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification

class UsersFacadeTest extends Specification implements ManagerContainerTrait {

  def "Asset scope querying users linked to #targetName with #linkedUserNames returns #expectedUserNames"() {
    given: "real identity and asset storage services"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def identityProvider = identityService.getIdentityProvider()
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def realm = keycloakSetup.realmBuilding.name
    def foreignRealm = keycloakSetup.realmCity.name
    List<String> createdAssetIds = []
    List<User> createdUsers = []

    and: "a scope root, its descendant and an unrelated asset in the same realm"
    def createAsset = { String name, String parentId ->
      def asset = assetStorageService.merge(new ThingAsset("Users facade ${name}")
              .setRealm(realm).setParentId(parentId))
      createdAssetIds.add(asset.id)
      asset
    }
    def scopedAsset = createAsset("scope", null)
    def childAsset = createAsset("child", scopedAsset.id)
    def unrelatedAsset = createAsset("unrelated", null)
    def targetAsset = targetName == "root" ? scopedAsset : childAsset

    and: "regular users in the rule's realm and another realm"
    def users = ["sameRealm", "foreignRealm", "outsideSubtree"].collectEntries { name ->
      def userRealm = name == "foreignRealm" ? foreignRealm : realm
      def user = identityProvider.createUpdateUser(userRealm,
              new User().setUsername("users-facade-${UUID.randomUUID()}").setEnabled(true), null, false)
      createdUsers.add(user)
      def storedUser = identityProvider.getUser(user.id)
      assert storedUser.realm == userRealm
      assert !storedUser.isServiceAccount()
      assert !storedUser.isSystemAccount()
      [(name): storedUser]
    }

    and: "stored links include any foreign user selected by this case"
    // Storage permits cross-realm links independently of REST link-creation checks.
    linkedUserNames.each { name ->
      def user = users[name]
      assetStorageService.storeUserAssetLinks([new UserAssetLink(user.realm, user.id, targetAsset.id)])
    }
    def outsideUser = users.outsideSubtree
    assetStorageService.storeUserAssetLinks([new UserAssetLink(outsideUser.realm, outsideUser.id, unrelatedAsset.id)])
    assert identityProvider.queryUsers(new UserQuery().assets(targetAsset.id))*.id.toSet() ==
    linkedUserNames.collect { users[it].id }.toSet()
    assert identityProvider.queryUsers(new UserQuery().assets(unrelatedAsset.id))*.id == [outsideUser.id]

    def facade = new UsersFacade(new RulesEngineId(realm, scopedAsset.id), assetStorageService,
            container.getService(NotificationService.class), identityService)
    def query = new UserQuery().ids(users.values().collect { it.id } as String[])

    when: "the asset-scoped facade queries all three users"
    def result = facade.getResults(query).toList()

    then: "only users in the rule's realm linked within its subtree are returned"
    result.toSet() == expectedUserNames.collect { users[it].id }.toSet()

    cleanup:
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    targetName | linkedUserNames | expectedUserNames
    "root" | ["sameRealm"] | ["sameRealm"]
    "root" | ["foreignRealm"] | []
    "root" | ["sameRealm", "foreignRealm"] | ["sameRealm"]
    "child" | ["sameRealm"] | ["sameRealm"]
    "child" | ["foreignRealm"] | []
    "child" | ["sameRealm", "foreignRealm"] | ["sameRealm"]
  }

  def "#scope scope querying usernames #queryUserNames returns only regular users #expectedUserNames"() {
    given: "real identity and asset storage services"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def identityProvider = identityService.getIdentityProvider()
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def realm = keycloakSetup.realmBuilding.name
    List<User> createdUsers = []
    def scopedAsset = assetStorageService.merge(new ThingAsset("Users facade username filtering").setRealm(realm))

    and: "two regular users and a real service account, all within the rule's scope"
    def users = ["regular", "unmatched", "service"].collectEntries { name ->
      def user = identityProvider.createUpdateUser(realm,
              new User().setUsername("users-facade-${UUID.randomUUID()}").setEnabled(true)
              .setServiceAccount(name == "service"), null, false)
      createdUsers.add(user)
      def storedUser = identityProvider.getUser(user.id)
      assert storedUser.realm == realm
      assert storedUser.isServiceAccount() == (name == "service")
      assert !storedUser.isSystemAccount()
      assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, scopedAsset.id)])
      [(name): storedUser]
    }
    assert identityProvider.queryUsers(new UserQuery().assets(scopedAsset.id))*.id.toSet() ==
    users.values().collect { it.id }.toSet()

    and: "a username query which independently matches the intended accounts"
    def createQuery = {
      def query = new UserQuery().ids(users.values().collect { it.id } as String[])
      if (!queryUserNames.isEmpty()) {
        query.usernames(queryUserNames.collect { name ->
          def user = users[name]
          // User.getUsername() omits Keycloak's prefix; username predicates match the stored name.
          def storedUsername = user.isServiceAccount() ? User.SERVICE_ACCOUNT_PREFIX + user.username : user.username
          new StringPredicate(storedUsername)
        } as StringPredicate[])
      }
      query
    }
    def matchingNames = queryUserNames.isEmpty() ? users.keySet() : queryUserNames
    assert identityProvider.queryUsers(createQuery())*.id.toSet() ==
    matchingNames.collect { users[it].id }.toSet()

    def engineId = scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId(realm) : new RulesEngineId(realm, scopedAsset.id)
    def facade = new UsersFacade(engineId, assetStorageService,
            container.getService(NotificationService.class), identityService)

    when: "the facade applies its account exclusions to the query"
    def result = facade.getResults(createQuery()).toList()

    then: "service accounts stay excluded and the username filter is respected"
    result.toSet() == expectedUserNames.collect { users[it].id }.toSet()

    cleanup:
    if (scopedAsset != null) {
      assert assetStorageService.delete([scopedAsset.id])
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    [scope, queryUserNames, expectedUserNames] << ["global", "realm", "asset"].collectMany { scope ->
      [
        [[], ["regular", "unmatched"]],
        [["regular"], ["regular"]],
        [["service"], []],
        [["regular", "service"], ["regular"]]
      ].collect { row -> [scope] + row }
    }
  }

  def "#scope scope querying attributes #queryAttributes returns only regular users #expectedUserNames"() {
    given: "real identity and asset storage services"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def identityProvider = identityService.getIdentityProvider()
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def realm = keycloakSetup.realmBuilding.name
    List<User> createdUsers = []
    def scopedAsset = assetStorageService.merge(new ThingAsset("Users facade attribute filtering").setRealm(realm))

    and: "regular users with different attributes and a system account matching both filters"
    def userAttributes = [
      matching: [department: "maintenance", location: "north"],
      otherDepartment: [department: "operations", location: "north"],
      otherLocation: [department: "maintenance", location: "south"],
      system: [department: "maintenance", location: "north"]
    ]
    def users = userAttributes.collectEntries { name, attributes ->
      def newUser = new User().setUsername("users-facade-${UUID.randomUUID()}").setEnabled(true)
              .setSystemAccount(name == "system")
      attributes.each { key, value -> newUser.setAttribute(key, value) }
      def user = identityProvider.createUpdateUser(realm, newUser, null, false)
      createdUsers.add(user)
      def storedUser = identityProvider.getUser(user.id)
      assert storedUser.realm == realm
      assert !storedUser.isServiceAccount()
      assert storedUser.isSystemAccount() == (name == "system")
      attributes.each { key, value -> assert storedUser.attributeMap[key] == [value] }
      assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, scopedAsset.id)])
      [(name): storedUser]
    }
    assert identityProvider.queryUsers(new UserQuery().assets(scopedAsset.id))*.id.toSet() ==
    users.values().collect { it.id }.toSet()

    and: "an attribute query which independently matches the expected regular and system accounts"
    def createQuery = {
      def query = new UserQuery().ids(users.values().collect { it.id } as String[])
      if (!queryAttributes.isEmpty()) {
        query.attributes(queryAttributes.collect { key, value ->
          new UserQuery.AttributeValuePredicate(false, new StringPredicate(key), new StringPredicate(value))
        } as UserQuery.AttributeValuePredicate[])
      }
      query
    }
    assert identityProvider.queryUsers(createQuery())*.id.toSet() ==
    rawUserNames.collect { users[it].id }.toSet()

    def engineId = scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId(realm) : new RulesEngineId(realm, scopedAsset.id)
    def facade = new UsersFacade(engineId, assetStorageService,
            container.getService(NotificationService.class), identityService)

    when: "the facade applies its system-account exclusion to the attribute query"
    def result = facade.getResults(createQuery()).toList()

    then: "caller filters and system-account exclusion both apply"
    verifyAll {
      !result.contains(users.system.id)
      result.toSet() == expectedUserNames.collect { users[it].id }.toSet()
    }

    cleanup:
    if (scopedAsset != null) {
      assert assetStorageService.delete([scopedAsset.id])
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    [scope, queryAttributes, rawUserNames, expectedUserNames] << ["global", "realm", "asset"].collectMany { scope ->
      [
        [
          [:],
          ["matching", "otherDepartment", "otherLocation", "system"],
          ["matching", "otherDepartment", "otherLocation"]
        ],
        [[department: "maintenance"], ["matching", "otherLocation", "system"], ["matching", "otherLocation"]],
        [[department: "maintenance", location: "north"], ["matching", "system"], ["matching"]],
        [[department: "nonexistent"], [], []]
      ].collect { row -> [scope] + row }
    }
  }

  def "#scope scope with realm #realmFilter, assets #assetNames and path #pathNames returns #expectedUserNames"() {
    given: "real identity and asset storage services"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def identityProvider = identityService.getIdentityProvider()
    def keycloakSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def realm = keycloakSetup.realmBuilding.name
    def foreignRealm = keycloakSetup.realmCity.name
    List<String> createdAssetIds = []
    List<User> createdUsers = []

    and: "a scoped subtree, a sibling outside it, and an asset in another realm"
    def createAsset = { String name, String assetRealm, String parentId ->
      def asset = assetStorageService.merge(new ThingAsset("Users facade boundaries ${name}")
              .setRealm(assetRealm).setParentId(parentId))
      createdAssetIds.add(asset.id)
      asset
    }
    def assets = [:]
    assets.ancestor = createAsset("ancestor", realm, null)
    assets.scoped = createAsset("scoped", realm, assets.ancestor.id)
    assets.child = createAsset("child", realm, assets.scoped.id)
    assets.grandchild = createAsset("grandchild", realm, assets.child.id)
    assets.outside = createAsset("outside", realm, assets.ancestor.id)
    assets.foreign = createAsset("foreign", foreignRealm, null)
    assert assetStorageService.find(assets.grandchild.id, true).path.contains(assets.child.id)
    assert !assetStorageService.find(assets.outside.id, true).path.contains(assets.scoped.id)

    and: "one regular user linked to each target asset"
    def users = ["scoped", "child", "grandchild", "outside", "foreign"].collectEntries { name ->
      def asset = assets[name]
      def user = identityProvider.createUpdateUser(asset.realm,
              new User().setUsername("users-facade-${UUID.randomUUID()}").setEnabled(true), null, false)
      createdUsers.add(user)
      def storedUser = identityProvider.getUser(user.id)
      assert storedUser.realm == asset.realm
      assert !storedUser.isServiceAccount()
      assert !storedUser.isSystemAccount()
      assetStorageService.storeUserAssetLinks([new UserAssetLink(asset.realm, user.id, asset.id)])
      assert identityProvider.queryUsers(new UserQuery().assets(asset.id))*.id == [user.id]
      [(name): storedUser]
    }

    and: "a caller query with optional realm, asset and path restrictions"
    def createQuery = {
      def query = new UserQuery().ids(users.values().collect { it.id } as String[])
      if (realmFilter != null) {
        query.realm(new RealmPredicate(foreignRealm))
      }
      if (assetNames != null) {
        query.assets(assetNames.collect { assets[it].id } as String[])
      }
      if (pathNames != null) {
        query.assetPath(new PathPredicate(pathNames.collect { assets[it].id } as String[]))
      }
      query
    }
    // An explicit empty asset selection must match nobody, unlike an absent asset filter.
    // All other cases match seeded users before the facade enforces its scope.
    def directResults = identityProvider.queryUsers(createQuery())
    if (assetNames != null && assetNames.isEmpty()) {
      assert directResults.length == 0
    } else {
      assert directResults.length> 0
    }
    def engineId = scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId(realm) : new RulesEngineId(realm, assets.scoped.id)
    def facade = new UsersFacade(engineId, assetStorageService,
            container.getService(NotificationService.class), identityService)

    when: "the rule queries users through its facade"
    def result = facade.getResults(createQuery()).toList()

    then: "the engine scope is enforced while valid narrower filters are retained"
    result.toSet() == expectedUserNames.collect { users[it].id }.toSet()

    cleanup:
    if (!createdAssetIds.isEmpty()) {
      assert assetStorageService.delete(createdAssetIds)
    }
    createdUsers.each { identityProvider.deleteUser(it.realm, it.id) }

    where:
    scope | realmFilter | assetNames | pathNames | expectedUserNames
    "global" | null | null | null | ["scoped", "child", "grandchild", "outside", "foreign"]
    "global" | null | [] | null | []
    "global" | "foreign" | null | null | ["foreign"]
    "realm" | null | null | null | ["scoped", "child", "grandchild", "outside"]
    "realm" | null | [] | null | []
    "realm" | "foreign" | null | null | ["scoped", "child", "grandchild", "outside"]
    "realm" | null | ["foreign"] | null | []
    "asset" | null | null | null | ["scoped", "child", "grandchild"]
    "asset" | null | [] | null | []
    "asset" | "foreign" | null | null | ["scoped", "child", "grandchild"]
    "asset" | null | ["scoped"] | null | ["scoped"]
    "asset" | null | ["child"] | null | ["child"]
    "asset" | null | ["outside"] | null | []
    "asset" | null | ["foreign"] | null | []
    "asset" | null | ["child", "outside", "foreign"] | null | ["child"]
    "asset" | null | null | ["ancestor"] | ["scoped", "child", "grandchild"]
    "asset" | null | null | ["outside"] | []
    "asset" | null | null | ["foreign"] | []
    "asset" | null | null | ["child"] | ["child", "grandchild"]
    "asset" | null | null | ["grandchild"] | ["grandchild"]
    "asset" | null | ["grandchild", "outside"] | ["child"] | ["grandchild"]
  }
}
