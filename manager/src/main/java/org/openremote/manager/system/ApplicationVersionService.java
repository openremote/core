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
package org.openremote.manager.system;

import java.time.Instant;
import java.util.Optional;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.util.VersionUtil;

/** Records the first activation of each normalized application version for other services. */
public class ApplicationVersionService implements ContainerService {

  protected PersistenceService persistenceService;

  /**
   * Schedules version recording after persistence initialization and migration.
   *
   * @return the container lifecycle priority for this service
   */
  @Override
  public int getPriority() {
    return PersistenceService.PRIORITY + 10;
  }

  /**
   * Resolves the persistence dependency.
   *
   * @param container the container that provides the persistence service
   */
  @Override
  public void init(Container container) {
    persistenceService = container.getService(PersistenceService.class);
  }

  /**
   * Records the running manager version after database migrations have completed.
   *
   * @param container the container starting this service
   */
  @Override
  public void start(Container container) {
    // Persistence has completed migrations and started before version history is initialized.
    recordApplicationVersion(VersionInfo.getManagerVersion());
  }

  /**
   * Completes shutdown without altering activation history.
   *
   * @param container the container stopping this service
   */
  @Override
  public void stop(Container container) {}

  /**
   * Finds the earliest activation of a version at or above the supplied threshold. Versions are
   * compared numerically and qualifiers and build metadata are ignored.
   *
   * @param applicationVersion the minimum version in three-component form, optionally qualified
   * @return the earliest qualifying activation timestamp, or an empty optional if none is recorded
   * @throws IllegalArgumentException if the threshold version is null or lacks exactly three
   *     components
   */
  public Optional<Instant> getFirstActivationAtOrAfter(String applicationVersion) {
    String version = VersionUtil.normalizeVersion(applicationVersion);
    return persistenceService.doReturningTransaction(
        em ->
            Optional.ofNullable(
                (Instant)
                    em.createNativeQuery(
                            """
            SELECT MIN(updated_on) FROM application_version
            WHERE CAST(string_to_array(version, '.') AS NUMERIC[]) >=
                  CAST(string_to_array(:version, '.') AS NUMERIC[])
            """,
                            Instant.class)
                        .setParameter("version", version)
                        .getSingleResult()));
  }

  /**
   * Records a normalized version's first activation and preserves the timestamp if the version has
   * already been recorded.
   *
   * @param applicationVersion the running version in three-component form, optionally qualified
   * @throws IllegalArgumentException if the version is null or lacks exactly three components
   */
  protected void recordApplicationVersion(String applicationVersion) {
    String version = VersionUtil.normalizeVersion(applicationVersion);
    // Use the same application clock as Hibernate's asset creation timestamps.
    Instant activatedOn = Instant.now();
    // Atomic insert preserves the first timestamp across concurrent startups.
    persistenceService.doTransaction(
        em ->
            em.createNativeQuery(
                    """
                    INSERT INTO application_version (version, updated_on)
                    VALUES (:version, :activatedOn)
                    ON CONFLICT (version) DO NOTHING
                    """)
                .setParameter("version", version)
                .setParameter("activatedOn", activatedOn)
                .executeUpdate());
  }
}
