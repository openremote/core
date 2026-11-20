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
package org.openremote.test.system

import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.system.ApplicationVersionService
import org.openremote.manager.system.VersionInfo
import org.openremote.model.util.VersionUtil
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApplicationVersionServiceTest extends Specification implements ManagerContainerTrait {
  def setupSpec() {
    startContainer(defaultConfig(), defaultServices())
  }

  def "activation history records each normalized version once across upgrades, restarts and rollbacks"() {
    given:
    def persistence = container.getService(PersistenceService)
    def versions = container.getService(ApplicationVersionService)
    def savedRows = snapshotHistory(persistence)
    def rows = {
      -> persistence.doReturningTransaction { em ->
        em.createNativeQuery("SELECT version, updated_on FROM application_version").resultList.collectEntries {
          [(it[0]): it[1]]
        }
      }
    }

    expect: "startup has recorded the running application after migrations"
    rows().containsKey(VersionUtil.normalizeVersion(VersionInfo.managerVersion))

    when:
    persistence.doTransaction {
      it.createNativeQuery("DELETE FROM application_version").executeUpdate()
    }
    versions.recordApplicationVersion("1.0.0")
    def first = rows()
    def firstActivation = versions.getFirstActivation("1.0.0").orElseThrow()
    versions.recordApplicationVersion("1.0.0")

    then:
    rows() == first
    first.keySet() == ["1.0.0"] as Set
    versions.getFirstActivation("1.0.0").orElseThrow() == firstActivation

    when:
    versions.recordApplicationVersion("1.1.0")
    def upgraded = rows()
    versions.recordApplicationVersion("1.1.0")

    then:
    rows() == upgraded
    upgraded["1.1.0"] > first["1.0.0"]

    when:
    versions.recordApplicationVersion("1.0.0")

    then: "rollback preserves the original activation"
    rows() == upgraded
    rows().size() == 2
    versions.getFirstActivation("1.0.0").orElseThrow() == firstActivation

    when:
    ["1.2.0-SNAPSHOT", "1.2.0-SNAPSHOT", "1.2.0-M1", "1.2.0-RC1", "1.2.0-beta.2", "1.2.0+build.1"].each { version ->
      def before = rows()["1.2.0"]
      versions.recordApplicationVersion(version)
      assert before == null || rows()["1.2.0"] == before
    }
    def qualified = rows()
    versions.recordApplicationVersion("1.2.0")

    then: "the release retains the first activation of its normalized version"
    rows() == qualified

    when:
    versions.recordApplicationVersion("1.3.0")

    then:
    rows()["1.3.0"] > qualified["1.2.0"]

    cleanup:
    restoreHistory(persistence, savedRows)
  }

  def "release queries handle skipped versions, numeric ordering, qualifiers and rollbacks"() {
    given:
    def persistence = container.getService(PersistenceService)
    def versions = container.getService(ApplicationVersionService)
    def savedRows = snapshotHistory(persistence)
    persistence.doTransaction {
      it.createNativeQuery("DELETE FROM application_version").executeUpdate()
    }

    expect:
    versions.getFirstActivation("1.34.0").empty
    versions.getFirstActivationAtOrAfter("1.34.0").empty

    when: "an earlier release does not satisfy a numerically higher release query"
    versions.recordApplicationVersion("1.9.0")

    then:
    versions.getFirstActivationAtOrAfter("1.34.0").empty

    when: "this installation upgrades directly to a later development release"
    versions.recordApplicationVersion("1.40.0-SNAPSHOT")
    def cutoff = versions.getFirstActivation("1.40.0").orElseThrow()
    versions.recordApplicationVersion("1.40.0-SNAPSHOT")
    versions.recordApplicationVersion("1.34.0-M1")
    versions.recordApplicationVersion("1.34.0")
    versions.recordApplicationVersion("1.9.0")

    then: "the earliest qualifying activation remains fixed across restarts and rollbacks"
    versions.getFirstActivationAtOrAfter("1.34.0-RC1+build.2").orElseThrow() == cutoff
    versions.getFirstActivation("1.40.0-beta.2").orElseThrow() == cutoff
    versions.getFirstActivation("1.34.0").orElseThrow() > cutoff
    versions.getFirstActivationAtOrAfter("2.0.0").empty
    versions.getFirstActivation("1.9.0").present

    when: "another service lifecycle restart records the running version"
    versions.stop(container)
    versions.start(container)

    then:
    versions.getFirstActivationAtOrAfter("1.34.0").orElseThrow() == cutoff

    cleanup:
    restoreHistory(persistence, savedRows)
  }

  def "concurrent activations preserve one timestamp for a normalized version"() {
    given:
    def persistence = container.getService(PersistenceService)
    def versions = container.getService(ApplicationVersionService)
    def savedRows = snapshotHistory(persistence)
    persistence.doTransaction {
      it.createNativeQuery("DELETE FROM application_version").executeUpdate()
    }
    def executor = Executors.newFixedThreadPool(4)

    when:
    def startups = (["2.0.0-SNAPSHOT", "2.0.0-RC1", "2.0.0"] * 4).collect { version ->
      executor.submit({
        ->
        versions.recordApplicationVersion(version)
        versions.getFirstActivation("2.0.0").orElseThrow()
      } as Callable)
    }
    def activations = startups.collect { it.get(10, TimeUnit.SECONDS) }

    then:
    activations.toSet().size() == 1
    snapshotHistory(persistence).size() == 1

    cleanup:
    executor?.shutdownNow()
    executor?.awaitTermination(10, TimeUnit.SECONDS)
    restoreHistory(persistence, savedRows)
  }

  private List snapshotHistory(PersistenceService persistence) {
    persistence.doReturningTransaction { em ->
      em.createNativeQuery("SELECT version, updated_on FROM application_version").resultList
    }
  }

  private void restoreHistory(PersistenceService persistence, List rows) {
    if (persistence == null || rows == null) {
      return
    }
    persistence.doTransaction { em ->
      em.createNativeQuery("DELETE FROM application_version").executeUpdate()
      rows.each { row ->
        em.createNativeQuery("INSERT INTO application_version (version, updated_on) VALUES (:version, :activation)")
                .setParameter("version", row[0]).setParameter("activation", row[1]).executeUpdate()
      }
    }
  }
}
