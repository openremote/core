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
package org.openremote.manager.rules.facade

import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.rules.RulesEngineId
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.LogicGroup
import org.openremote.model.query.filter.ArrayPredicate
import org.openremote.model.query.filter.AttributePredicate
import org.openremote.model.query.filter.NameValuePredicate
import org.openremote.model.query.filter.NumberPredicate
import org.openremote.model.query.filter.ParentPredicate
import org.openremote.model.query.filter.PathPredicate
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.query.filter.StringPredicate
import spock.lang.Specification

class AssetsFacadeQueryTest extends Specification {

  def "#scope scope leaves the caller query unchanged with populated filters: #populated"() {
    given:
    def query = populated ? populatedQuery() : new AssetQuery()
    def original = queryState(query)
    def engine = engineFor(scope)
    List<Map> submittedStates = []
    def storage = capturingStorage(submittedStates)

    when:
    facadeFor(engine, storage).getResults(query).toList()

    then: "execution restrictions and storage defaults do not modify caller-owned options"
    verifyAll {
      queryState(query) == original
      submittedStates == [scopedState(original, engine)]
    }

    where:
    [scope, populated] << ["global", "realm", "asset"].collectMany { scope ->
      [false, true].collect { populated -> [scope, populated] }
    }
  }

  def "Reusing a query across engines does not retain previous scope restrictions with populated filters: #populated"() {
    given:
    def query = populated ? populatedQuery() : new AssetQuery()
    def original = queryState(query)
    def engines = [
      new RulesEngineId("realm-a", "asset-a"),
      new RulesEngineId("realm-b", "asset-b"),
      new RulesEngineId("realm-c"),
      new RulesEngineId()
    ]
    List<Map> submittedStates = []
    def storage = capturingStorage(submittedStates)

    when:
    engines.each { facadeFor(it, storage).getResults(query).toList() }

    then:
    verifyAll {
      submittedStates == engines.collect { scopedState(original, it) }
      queryState(query) == original
    }

    where:
    populated << [false, true]
  }

  def "Later caller edits are isolated from earlier executions in #scope scope"() {
    given:
    def query = populatedQuery()
    def original = queryState(query)
    def engine = engineFor(scope)
    List<AssetQuery> submittedQueries = []
    List<Map> submittedStates = []
    def storage = capturingStorage(submittedStates, submittedQueries)
    def facade = facadeFor(engine, storage)
    facade.getResults(query).toList()
    def firstExecution = queryState(submittedQueries[0])

    when: "the caller changes arrays, nested predicates and query options before reusing the query"
    query.realm.name = "next-realm"
    query.ids[0] = "next-asset"
    query.names[0].value = "Next name"
    query.parents[0].id = "next-parent"
    query.paths[0].path[0] = "next-path"
    query.userIds[0] = "next-user"
    query.types[0] = "NextType"
    query.attributes.items[0].value.value = "next-value"
    query.attributes.groups[0].items[0].name.value = "next-attribute"
    query.select.attributes[0] = "next-selected-attribute"
    query.orderBy.descending = false
    query.access = AssetQuery.Access.PUBLIC
    query.recursive = false
    query.includeDeletePending = false
    query.limit = 10
    query.offset = 3
    def edited = queryState(query)
    facade.getResults(query).toList()

    then: "each execution retains its own filters and the caller retains its edits"
    verifyAll {
      submittedQueries.size() == 2
      queryState(submittedQueries[0]) == firstExecution
      submittedStates == [scopedState(original, engine), scopedState(edited, engine)]
      queryState(query) == edited
    }

    where:
    scope << ["global", "realm", "asset"]
  }

  def "#scope scope preserves empty selection arrays when isolating a query"() {
    given: "empty arrays must remain distinct from absent filters"
    def query = new AssetQuery(
            ids: [] as String[], names: [] as StringPredicate[], parents: [] as ParentPredicate[],
            paths: [] as PathPredicate[], userIds: [] as String[], types: [] as String[])
    def original = queryState(query)
    def engine = engineFor(scope)
    List<Map> submittedStates = []
    def storage = capturingStorage(submittedStates)

    when:
    facadeFor(engine, storage).getResults(query).toList()

    then:
    submittedStates == [scopedState(original, engine)]

    where:
    scope << ["global", "realm", "asset"]
  }

  def "Query copies preserve nested attribute paths, empty values and null map entries"() {
    given:
    def payload = [empty: [] as Object[], absent: null]
    def predicate = new AttributePredicate("readings", new ArrayPredicate(payload, 0, null, null, null, false))
            .path(new NameValuePredicate.Path("measurements"))
            .meta(new NameValuePredicate("unit", new StringPredicate("celsius")))
            .previousValue(new NumberPredicate(new BigDecimal("1234567890.123456789")))
    def query = new AssetQuery().attributes(predicate)
    List<AssetQuery> submittedQueries = []
    def storage = Stub(AssetStorageService) {
      findAll(_ as AssetQuery) >> { AssetQuery submitted ->
        submittedQueries.add(submitted)
        []
      }
    }

    when:
    facadeFor(new RulesEngineId(), storage).getResults(query).toList()
    def copied = submittedQueries[0].attributes.items[0]
    predicate.path.append("next")
    predicate.meta[0].value.value = "fahrenheit"
    payload.empty = ["next"] as Object[]
    payload.absent = "next"
    predicate.previousValue.value = 0

    then:
    copied.path.paths.toList() == ["measurements"]
    copied.meta[0].value.value == "celsius"
    copied.value.value.empty.length == 0
    copied.value.value.containsKey("absent")
    copied.value.value.absent == null
    copied.previousValue.value == new BigDecimal("1234567890.123456789")
  }

  private AssetStorageService capturingStorage(List<Map> submittedStates, List<AssetQuery> submittedQueries = []) {
    def capture = { AssetQuery submitted, PathPredicate requiredPath ->
      submittedStates.add(queryState(submitted) + [requiredPath: requiredPath?.path?.toList()])
      submittedQueries.add(submitted)
      // The real storage query fills in these defaults in place.
      if (submitted.access == null) {
        submitted.access = AssetQuery.Access.PRIVATE
      }
      if (submitted.orderBy == null && submitted.ids == null) {
        submitted.orderBy = new AssetQuery.OrderBy(AssetQuery.OrderBy.Property.CREATED_ON)
      }
      []
    }
    Stub(AssetStorageService) {
      findAll(_ as AssetQuery) >> { AssetQuery submitted -> capture(submitted, null) }
      findAll(_ as AssetQuery, _ as PathPredicate) >> { AssetQuery submitted, PathPredicate requiredPath ->
        capture(submitted, requiredPath)
      }
    }
  }

  private AssetsFacade facadeFor(RulesEngineId engine, AssetStorageService storage) {
    if (engine.assetId.present) {
      def scopedAsset = Stub(ThingAsset) {
        getPath() >> ([engine.assetId.get()] as String[])
      }
      storage.find(engine.assetId.get(), true) >> scopedAsset
    }
    new AssetsFacade(engine, storage, { throw new AssertionError("Unexpected dispatch") })
  }

  private static RulesEngineId engineFor(String scope) {
    scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId("building") : new RulesEngineId("building", "scope-asset")
  }

  private static AssetQuery populatedQuery() {
    new AssetQuery().realm(new RealmPredicate("caller-realm"))
            .ids("caller-asset").names(new StringPredicate(AssetQuery.Match.BEGIN, true, "Caller").negate(true))
            .parents(new ParentPredicate("caller-parent")).paths(new PathPredicate("caller-path"))
            .userIds("caller-user").types(ThingAsset)
            .attributes(new LogicGroup<AttributePredicate>(LogicGroup.Operator.OR,
            [new LogicGroup<AttributePredicate>(new AttributePredicate("nested", new StringPredicate("value")))],
            [new AttributePredicate("status", new StringPredicate("active"))]))
            .select(new AssetQuery.Select().attributes("temperature"))
            .orderBy(new AssetQuery.OrderBy(AssetQuery.OrderBy.Property.NAME, true))
            .access(AssetQuery.Access.PROTECTED).recursive(true).limit(7).offset(2)
            .includeDeletePending(true)
  }

  private static Map queryState(AssetQuery query) {
    [realm: query.realm?.name, ids: query.ids?.toList(), names: query.names?.collect {
        it.toString()
      },
      parents: query.parents?.collect { it.id }, paths: query.paths?.collect { it.path?.toList() },
      userIds: query.userIds?.toList(), types: query.types?.toList(), attributes: query.attributes?.toString(),
      select: query.select == null ? null : [attributes: query.select.attributes?.toList()],
      orderBy: query.orderBy == null ? null : [property: query.orderBy.property, descending: query.orderBy.descending],
      access: query.access, recursive: query.recursive, includeDeletePending: query.@includeDeletePending,
      limit: query.limit, offset: query.offset]
  }

  private static Map scopedState(Map original, RulesEngineId engine) {
    original + [realm: engine.realm.orElse(original.realm),
      requiredPath: engine.assetId.map { [it] }.orElse(null),
      select: [attributes: []]]
  }
}
