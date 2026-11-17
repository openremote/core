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
import org.openremote.manager.rules.RulesEngineId
import org.openremote.manager.rules.facade.AssetsFacade
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.filter.PathPredicate
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.query.filter.StringPredicate
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification

class AssetsFacadePathFilterTest extends Specification implements ManagerContainerTrait {

  AssetStorageService storage
  Map<String, ThingAsset> testAssets = [:]
  List<String> createdAssetIds = []
  String realm
  String foreignRealm
  String prefix = "Path filter ${UUID.randomUUID()} "

  def setup() {
    def container = startContainer(defaultConfig(), defaultServices())
    stopPseudoClock()
    storage = container.getService(AssetStorageService)
    def keycloak = container.getService(SetupService).getTaskOfType(KeycloakTestSetup)
    realm = keycloak.realmBuilding.name
    foreignRealm = keycloak.realmCity.name

    testAssets.ancestor = createAsset("0 ancestor", realm)
    testAssets.scoped = createAsset("1 scoped", realm, testAssets.ancestor.id)
    testAssets.child = createAsset("2 child", realm, testAssets.scoped.id)
    testAssets.grandchild = createAsset("3 grandchild", realm, testAssets.child.id)
    testAssets.otherChild = createAsset("4 other child", realm, testAssets.scoped.id)
    testAssets.sibling = createAsset("5 sibling", realm, testAssets.ancestor.id)
    testAssets.unrelated = createAsset("6 unrelated", realm)
    testAssets.foreign = createAsset("7 foreign", foreignRealm)

    assert storage.find(testAssets.grandchild.id, true).path.contains(testAssets.scoped.id)
    assert !storage.find(testAssets.sibling.id, true).path.contains(testAssets.scoped.id)
    assert storage.find(testAssets.foreign.id, true).realm == foreignRealm
  }

  def cleanup() {
    if (!createdAssetIds.empty) {
      assert storage.delete(createdAssetIds)
    }
  }

  def "Asset scope preserves caller path selection: #selection"() {
    given:
    def facade = new AssetsFacade(new RulesEngineId(realm, testAssets.scoped.id), storage,
            { throw new AssertionError("Unexpected dispatch") })
    def createQuery = {
      def query = new AssetQuery().names(new StringPredicate(AssetQuery.Match.BEGIN, prefix))
              .orderBy(new AssetQuery.OrderBy(AssetQuery.OrderBy.Property.NAME))
              .limit(limit).offset(offset)
      if (pathNames != null) {
        query.paths(pathNames.collect { path ->
          new PathPredicate(path.collect { testAssets[it].id } as String[])
        } as PathPredicate[])
      }
      if (idNames != null) {
        query.ids(idNames.collect { testAssets[it].id } as String[])
      }
      if (useForeignRealm) {
        query.realm(new RealmPredicate(foreignRealm))
      }
      query
    }

    and: "an empty path array has different semantics from an absent path restriction"
    if (pathNames != null && pathNames.empty) {
      assert storage.findAll(createQuery()).empty
    }

    when:
    def result = facade.getResults(createQuery()).toList()

    then: "the result is the caller's selection intersected with the engine's realm and subtree"
    result.collect { it.id }.toSet() == expected.collect { testAssets[it].id }.toSet()
    result.every { it.realm == realm && it.path.contains(testAssets.scoped.id) }

    where:
    selection | pathNames | idNames | useForeignRealm | limit | offset | expected
    "no path filter" | null | null | false | 0 | 0 | ["scoped", "child", "grandchild", "otherChild"]
    "empty path selection" | [] | null | false | 0 | 0 | []
    "scope path" | [["scoped"]] | null | false | 0 | 0 | ["scoped", "child", "grandchild", "otherChild"]
    "ancestor path" | [["ancestor"]] | null | false | 0 | 0 | ["scoped", "child", "grandchild", "otherChild"]
    "child subtree" | [["child"]] | null | false | 0 | 0 | ["child", "grandchild"]
    "grandchild subtree" | [["grandchild"]] | null | false | 0 | 0 | ["grandchild"]
    "sibling subtree" | [["sibling"]] | null | false | 0 | 0 | []
    "unrelated subtree" | [["unrelated"]] | null | false | 0 | 0 | []
    "foreign realm subtree" | [["foreign"]] | null | false | 0 | 0 | []
    "two permitted alternatives" | [["child"], ["otherChild"]] | null | false | 0 | 0 | ["child", "grandchild", "otherChild"]
    "permitted and outside alternatives" | [["child"], ["sibling"]] | null | false | 0 | 0 | ["child", "grandchild"]
    "permitted and foreign alternatives" | [["child"], ["foreign"]] | null | false | 0 | 0 | ["child", "grandchild"]
    "only outside alternatives" | [["sibling"], ["unrelated"]] | null | false | 0 | 0 | []
    "overlapping alternatives remain OR" | [["ancestor"], ["child"]] | null | false | 0 | 0 | ["scoped", "child", "grandchild", "otherChild"]
    "full descendant path" | [["ancestor", "scoped", "child"]] | null | false | 0 | 0 | ["child", "grandchild"]
    "path combined with target IDs" | [["child"]] | ["grandchild", "otherChild"] | false | 0 | 0 | ["grandchild"]
    "outside target ID" | [["ancestor"]] | ["sibling"] | false | 0 | 0 | []
    "foreign target ID" | null | ["foreign"] | false | 0 | 0 | []
    "realm override retains path selection" | [["child"]] | null | true | 0 | 0 | ["child", "grandchild"]
    "limit applies after path filtering" | [["grandchild"]] | null | false | 1 | 0 | ["grandchild"]
    "offset applies after path filtering" | [["child"]] | null | false | 1 | 1 | ["grandchild"]
  }

  def "Recursive asset scope preserves caller paths #pathNames"() {
    given:
    def facade = new AssetsFacade(new RulesEngineId(realm, testAssets.scoped.id), storage,
            { throw new AssertionError("Unexpected dispatch") })
    def query = new AssetQuery().recursive(true)
            .names(new StringPredicate(AssetQuery.Match.BEGIN, prefix))
    if (pathNames != null) {
      query.paths(pathNames.collect { new PathPredicate(testAssets[it].id) } as PathPredicate[])
    }

    when:
    def result = facade.getResults(query).toList()

    then:
    result.collect { it.id }.toSet() == expected.collect { testAssets[it].id }.toSet()
    result.every { it.realm == realm }

    where:
    pathNames | expected
    null | ["scoped", "child", "grandchild", "otherChild"]
    [] | []
    ["ancestor"] | ["scoped", "child", "grandchild", "otherChild"]
    ["child"] | ["child", "grandchild"]
    ["child", "sibling"] | ["child", "grandchild"]
    ["sibling", "foreign"] | []
  }

  private ThingAsset createAsset(String name, String assetRealm, String parentId = null) {
    def asset = storage.merge(new ThingAsset(prefix + name).setRealm(assetRealm).setParentId(parentId))
    createdAssetIds.add(asset.id)
    asset
  }
}
