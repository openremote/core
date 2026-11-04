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
import org.openremote.manager.notification.NotificationService
import org.openremote.manager.rules.RulesEngineId
import org.openremote.manager.security.ManagerIdentityProvider
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.UserQuery
import org.openremote.model.query.filter.PathPredicate
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.query.filter.StringPredicate
import org.openremote.model.security.User
import spock.lang.Specification

class UsersFacadeQueryTest extends Specification {

  def "Reusing a #scope query with #filterCase keeps system-account exclusion bounded"() {
    given: "the query object retained across rule firings"
    def callerFilters = createFilters() as UserQuery.AttributeValuePredicate[]
    def query = new UserQuery().attributes(callerFilters)
    def expectedFilters = snapshot(callerFilters)
    if (!alreadyExcluded) {
      expectedFilters.add(exclusion().toString())
    }
    List<List<String>> submittedFilters = []
    def provider = Stub(ManagerIdentityProvider) {
      queryUsers(_ as UserQuery) >> { UserQuery submitted ->
        // Snapshot at execution time; retaining the mutable query would hide earlier growth.
        submittedFilters.add(snapshot(submitted.attributes))
        new User[0]
      }
    }
    def identityService = Stub(ManagerIdentityService) {
      getIdentityProvider() >> provider
    }
    def engineId = scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId("building") : new RulesEngineId("building", "scope-asset")
    def facade = new UsersFacade(engineId, Stub(AssetStorageService), Stub(NotificationService), identityService)

    when: "the same query is executed repeatedly"
    100.times { facade.getResults(query).toList() }
    def filterCounts = submittedFilters.collect { it.size() }.unique()

    then: "every execution preserves the caller filters and has just one full system-account exclusion"
    submittedFilters.size() == 100
    filterCounts == [expectedFilters.size()]
    submittedFilters.each { assert it == expectedFilters }

    where:
    [scope, filterCase, createFilters, alreadyExcluded] << ["global", "realm", "asset"].collectMany { scope ->
      [
        [
          "no attributes",
          { -> null },
          false
        ],
        [
          "empty attributes",
          { -> [] },
          false
        ],
        [
          "a caller filter",
          { -> [department()] },
          false
        ],
        [
          "an existing full exclusion",
          { -> [department(), exclusion()] },
          true
        ],
        [
          "a value-specific exclusion",
          { -> [department(), exclusion("true")] },
          false
        ],
        [
          "a positive system filter",
          {
            -> [
              department(),
              new UserQuery.AttributeValuePredicate(false, new StringPredicate(User.SYSTEM_ACCOUNT_ATTRIBUTE))
            ]
          },
          false
        ]
      ].collect { row -> [scope] + row }
    }
  }

  def "Reusing an asset query with #pathCase keeps the scope path bounded"() {
    given: "an asset-scoped rule retaining its user query across firings"
    def query = new UserQuery().assetPath(callerPath)
    List<List<String>> submittedPaths = []
    def provider = Stub(ManagerIdentityProvider) {
      queryUsers(_ as UserQuery) >> { UserQuery submitted ->
        submittedPaths.add(submitted.pathPredicate.path.toList())
        new User[0]
      }
    }
    def identityService = Stub(ManagerIdentityService) {
      getIdentityProvider() >> provider
    }
    def facade = new UsersFacade(new RulesEngineId("building", "scope-asset"),
            Stub(AssetStorageService), Stub(NotificationService), identityService)

    when: "the same query is executed repeatedly"
    100.times { facade.getResults(query).toList() }
    def pathLengths = submittedPaths.collect { it.size() }.unique()

    then: "every execution retains the caller's path and includes the scope asset only once"
    submittedPaths.size() == 100
    pathLengths == [expectedPath.size()]
    submittedPaths.each { assert it == expectedPath }

    where:
    pathCase | callerPath | expectedPath
    "no predicate" | null | ["scope-asset"]
    "null path values" | new PathPredicate() | ["scope-asset"]
    "empty path values" | new PathPredicate([] as String[]) | ["scope-asset"]
    "a descendant filter" | new PathPredicate("child") | ["child", "scope-asset"]
    "an outside filter" | new PathPredicate("outside") | ["outside", "scope-asset"]
    "the scope already present" | new PathPredicate("scope-asset") | ["scope-asset"]
    "the scope at the start" | new PathPredicate("scope-asset", "child") | ["scope-asset", "child"]
    "the scope at the end" | new PathPredicate("child", "scope-asset") | ["child", "scope-asset"]
  }

  def "#scope scope leaves the caller query unchanged with populated filters: #populated"() {
    given: "a query owned by the caller"
    def query = populated ? populatedQuery() : new UserQuery()
    def original = queryState(query)
    def engineId = engineFor(scope)
    List<Map> submittedStates = []
    def provider = Stub(ManagerIdentityProvider) {
      queryUsers(_ as UserQuery) >> { UserQuery submitted ->
        submittedStates.add(queryState(submitted))
        // The real identity query normalizes username matching in place.
        submitted.usernames?.each { it.caseSensitive = false }
        new User[0]
      }
    }

    when:
    facadeFor(engineId, provider).getResults(query).toList()

    then: "scope checks apply to the execution without changing caller filters or nested predicates"
    verifyAll {
      queryState(query) == original
      submittedStates == [scopedState(original, engineId)]
    }

    where:
    [scope, populated] << ["global", "realm", "asset"].collectMany { scope ->
      [false, true].collect { populated -> [scope, populated] }
    }
  }

  def "Reusing a caller query across engines does not carry scope restrictions forward with populated filters: #populated"() {
    given: "one query used by two asset engines, a realm engine and a global engine"
    def query = populated ? populatedQuery() : new UserQuery()
    def original = queryState(query)
    def engines = [
      new RulesEngineId("realm-a", "asset-a"),
      new RulesEngineId("realm-b", "asset-b"),
      new RulesEngineId("realm-c"),
      new RulesEngineId()
    ]
    List<Map> submittedStates = []
    def provider = Stub(ManagerIdentityProvider) {
      queryUsers(_ as UserQuery) >> { UserQuery submitted ->
        submittedStates.add(queryState(submitted))
        new User[0]
      }
    }

    when:
    engines.each { facadeFor(it, provider).getResults(query).toList() }

    then: "each engine applies only its own scope to the original caller query"
    verifyAll {
      queryState(query) == original
      submittedStates == engines.collect { scopedState(original, it) }
    }

    where:
    populated << [false, true]
  }

  def "Later caller edits are isolated from earlier executions in #scope scope"() {
    given: "a query with nested filters and an identity provider retaining submitted queries"
    def query = populatedQuery()
    def engineId = engineFor(scope)
    List<UserQuery> submittedQueries = []
    def provider = Stub(ManagerIdentityProvider) {
      queryUsers(_ as UserQuery) >> { UserQuery submitted ->
        submittedQueries.add(submitted)
        new User[0]
      }
    }
    def facade = facadeFor(engineId, provider)
    facade.getResults(query).toList()
    def firstExecution = queryState(submittedQueries[0])

    when: "the caller updates nested filters and executes the same query again"
    query.realmPredicate.name = "next-realm"
    query.assets[0] = "next-asset"
    query.pathPredicate.path[0] = "next-path"
    query.ids[0] = "next-user"
    query.usernames[0].value = "NextOperator"
    query.attributes[0].name.value = "team"
    query.attributes[0].value.value = "operations"
    query.attributes[1].negated = false
    query.clientRoles[0].value = "write:assets"
    query.realmRoles[0].value = "next-role"
    query.select.basic = false
    query.orderBy.descending = false
    query.limit = 10
    query.offset = 3
    def updatedInput = queryState(query)
    facade.getResults(query).toList()

    then: "the first execution retains its filters, and the next execution uses the edited input"
    verifyAll {
      submittedQueries.size() == 2
      queryState(submittedQueries[0]) == firstExecution
      queryState(submittedQueries[1]) == scopedState(updatedInput, engineId)
      queryState(query) == updatedInput
    }

    where:
    scope << ["global", "realm", "asset"]
  }

  private UsersFacade facadeFor(RulesEngineId engineId, ManagerIdentityProvider provider) {
    def identityService = Stub(ManagerIdentityService) {
      getIdentityProvider() >> provider
    }
    new UsersFacade(engineId, Stub(AssetStorageService), Stub(NotificationService), identityService)
  }

  private static RulesEngineId engineFor(String scope) {
    scope == "global" ? new RulesEngineId() :
            scope == "realm" ? new RulesEngineId("building") : new RulesEngineId("building", "scope-asset")
  }

  private static UserQuery populatedQuery() {
    new UserQuery().realm(new RealmPredicate("caller-realm"))
            .assets("caller-asset").assetPath(new PathPredicate("caller-path")).ids("caller-user")
            .usernames(new StringPredicate(AssetQuery.Match.BEGIN, true, "Operator").negate(true))
            .attributes(department(), new UserQuery.AttributeValuePredicate(true, new StringPredicate("blocked")))
            .clientRoles(new StringPredicate("read:assets").negate(true))
            .realmRoles(new StringPredicate("restricted-user"))
            .serviceUsers(true).limit(7).offset(2)
            .select(new UserQuery.Select().basic(true))
            .orderBy(new UserQuery.OrderBy(UserQuery.OrderBy.Property.USERNAME, true))
  }

  private static Map queryState(UserQuery query) {
    [realm: query.realmPredicate?.name, assets: query.assets?.toList(), path: query.pathPredicate?.path?.toList(),
      ids: query.ids?.toList(), usernames: query.usernames?.collect { it.toString() },
      attributes: query.attributes == null ? null : snapshot(query.attributes),
      clientRoles: query.clientRoles?.collect {
        it.toString()
      }, realmRoles: query.realmRoles?.collect {
        it.toString()
      },
      serviceUsers: query.serviceUsers, limit: query.limit, offset: query.offset,
      select: query.select?.toString(), orderBy: query.orderBy?.toString()]
  }

  private static Map scopedState(Map original, RulesEngineId engineId) {
    original + [realm: engineId.realm.orElse(original.realm),
      path: engineId.assetId.present ? (original.path ?: []) + [engineId.assetId.get()] : original.path,
      attributes: (original.attributes ?: []) + [exclusion().toString()], serviceUsers: false]
  }

  private static UserQuery.AttributeValuePredicate department() {
    new UserQuery.AttributeValuePredicate(false, new StringPredicate("department"), new StringPredicate("maintenance"))
  }

  private static UserQuery.AttributeValuePredicate exclusion(String value = null) {
    new UserQuery.AttributeValuePredicate(true, new StringPredicate(User.SYSTEM_ACCOUNT_ATTRIBUTE),
            value == null ? null : new StringPredicate(value))
  }

  private static List<String> snapshot(UserQuery.AttributeValuePredicate[] attributes) {
    attributes == null ? [] : attributes.collect { it.toString() }
  }
}
