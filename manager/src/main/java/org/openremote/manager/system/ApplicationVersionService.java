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
import java.util.List;
import java.util.Optional;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.manager.persistence.ApplicationVersion;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;

/** Records the first activation of each normalized application version for other services. */
public class ApplicationVersionService implements ContainerService {

  protected PersistenceService persistenceService;

  @Override
  public int getPriority() {
    return PersistenceService.PRIORITY + 10;
  }

  @Override
  public void init(Container container) {
    persistenceService = container.getService(PersistenceService.class);
  }

  @Override
  public void start(Container container) {
    // Persistence has completed migrations and started before version history is initialized.
    recordApplicationVersion(VersionInfo.getManagerVersion());
  }

  @Override
  public void stop(Container container) {}

  /**
   * Returns the first recorded activation of a normalized version, ignoring any build suffixes. The
   * timestamp is stored in {@code updated_on} and is never refreshed on reactivation.
   */
  public Optional<Instant> getFirstActivation(String applicationVersion) {
    String version = ApplicationVersion.normalize(applicationVersion);
    return persistenceService.doReturningTransaction(
        em -> {
          List<?> timestamps =
              em.createNativeQuery(
                      "SELECT updated_on FROM application_version WHERE version = :version",
                      Instant.class)
                  .setParameter("version", version)
                  .getResultList();
          return timestamps.isEmpty()
              ? Optional.empty()
              : Optional.of((Instant) timestamps.getFirst());
        });
  }

  /**
   * Returns when this installation first ran the given version or a later version. This also
   * supports installations that skipped the requested release. Versions are compared numerically;
   * qualifiers and build metadata are ignored. Empty means no qualifying activation was recorded.
   */
  public Optional<Instant> getFirstActivationAtOrAfter(String applicationVersion) {
    String version = ApplicationVersion.normalize(applicationVersion);
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

  protected void recordApplicationVersion(String applicationVersion) {
    String version = ApplicationVersion.normalize(applicationVersion);
    // Atomic insert preserves the first timestamp across concurrent startups.
    persistenceService.doTransaction(
        em ->
            em.createNativeQuery(
                    """
        INSERT INTO application_version (version, updated_on)
        VALUES (:version, clock_timestamp())
        ON CONFLICT (version) DO NOTHING
        """)
                .setParameter("version", version)
                .executeUpdate());
  }
}
