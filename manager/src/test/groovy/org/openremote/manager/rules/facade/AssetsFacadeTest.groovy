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
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.filter.PathPredicate
import spock.lang.Specification

class AssetsFacadeTest extends Specification {

  def "#scope scope dispatches every event in order for #batchCase"() {
    given: "two assets accessible to the rule engine"
    def root = Stub(ThingAsset) {
      getId() >> "root"
      getPath() >> (["root"] as String[])
    }
    def child = Stub(ThingAsset) {
      getId() >> "child"
    }
    def availableAssets = [root, child]
    List<AssetQuery> queries = []
    List<PathPredicate> requiredPaths = []
    def lookup = { AssetQuery query, PathPredicate requiredPath ->
      queries.add(query)
      requiredPaths.add(requiredPath)
      // An asset-ID query returns each matching asset once, even for repeated IDs.
      availableAssets.findAll { query.ids.contains(it.id) }
    }
    def storage = Stub(AssetStorageService) {
      find("root", true) >> root
      findAll(_ as AssetQuery) >> { AssetQuery query -> lookup(query, null) }
      findAll(_ as AssetQuery, _ as PathPredicate) >> { AssetQuery query, PathPredicate requiredPath ->
        lookup(query, requiredPath)
      }
    }
    def engineId = scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId("building") : new RulesEngineId("building", "root")
    List<AttributeEvent> forwarded = []
    def facade = new AssetsFacade(engineId, storage, { AttributeEvent event ->
      forwarded.add(event)
    })
    def events = targetIds.withIndex().collect { String id, int index ->
      new AttributeEvent(id, "attribute${index}", index)
    }

    when: "one call dispatches updates to different attributes on the permitted targets"
    facade.dispatch(events as AttributeEvent[])

    then: "target lookup retains the engine scope"
    queries.size() == 1
    queries[0].ids.toSet() == targetIds.toSet()
    queries[0].realm?.name == (scope == "global" ? null : "building")
    queries[0].paths == null
    requiredPaths.collect { it?.path?.toList() } == [scope == "asset" ? ["root"] : null]

    and: "every original event is forwarded exactly once, in the supplied order"
    forwarded.size() == events.size()
    events.indices.every { forwarded[it].is(events[it]) }

    where:
    [scope, batchCase, targetIds] << ["global", "realm", "asset"].collectMany { scope ->
      [
        ["a single event", ["root"]],
        ["distinct targets", ["root", "child"]],
        ["two events for one target", ["root", "root"]],
        ["mixed repeated and distinct targets", ["root", "child", "root"]]
      ].collect { row -> [scope] + row }
    }
  }

  def "#scope scope rejects the whole batch for #rejectedTarget with repeated permitted targets: #repeated"() {
    given: "the scoped asset lookup excludes a missing or inaccessible target"
    def root = Stub(ThingAsset) {
      getId() >> "root"
      getPath() >> (["root"] as String[])
    }
    def child = Stub(ThingAsset) {
      getId() >> "child"
    }
    List<AssetQuery> queries = []
    List<PathPredicate> requiredPaths = []
    def lookup = { AssetQuery query, PathPredicate requiredPath ->
      queries.add(query)
      requiredPaths.add(requiredPath)
      // Model a partial result from the scoped lookup: only permitted assets were found.
      [root, child].findAll { query.ids.contains(it.id) }
    }
    def storage = Stub(AssetStorageService) {
      find("root", true) >> root
      findAll(_ as AssetQuery) >> { AssetQuery query -> lookup(query, null) }
      findAll(_ as AssetQuery, _ as PathPredicate) >> { AssetQuery query, PathPredicate requiredPath ->
        lookup(query, requiredPath)
      }
    }
    def engineId = scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId("building") : new RulesEngineId("building", "root")
    List<AttributeEvent> forwarded = []
    def facade = new AssetsFacade(engineId, storage, { AttributeEvent event ->
      forwarded.add(event)
    })
    def targetIds = (repeated ? ["root", "child", "root"] : ["root", "child"]) + [rejectedTarget]
    def events = targetIds.withIndex().collect { String id, int index ->
      new AttributeEvent(id, "attribute${index}", index)
    }

    when: "the rejected target is last, after events for permitted assets"
    facade.dispatch(events as AttributeEvent[])

    then: "the lookup includes every target and retains the engine scope"
    queries.size() == 1
    queries[0].ids.toSet() == targetIds.toSet()
    queries[0].realm?.name == (scope == "global" ? null : "building")
    queries[0].paths == null
    requiredPaths.collect { it?.path?.toList() } == [scope == "asset" ? ["root"] : null]

    and: "no events are forwarded, including those for permitted assets"
    forwarded.empty

    where:
    [scope, rejectedTarget, repeated] << [
      ["global", "missing"],
      ["realm", "missing"],
      ["realm", "foreign-realm"],
      ["asset", "missing"],
      ["asset", "foreign-realm"],
      ["asset", "outside-subtree"]
    ].collectMany { row -> [false, true].collect { repeated -> row + [repeated] } }
  }
}
