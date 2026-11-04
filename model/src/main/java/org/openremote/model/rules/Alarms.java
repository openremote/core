/*
 * Copyright 2024, OpenRemote Inc.
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
package org.openremote.model.rules;

import java.util.List;
import org.openremote.model.alarm.Alarm;
import org.openremote.model.util.TsIgnore;

/** Facade for sending {@link Alarm}s from rules RHS. */
@TsIgnore
public abstract class Alarms {
  /**
   * Creates an alarm for assets within the engine's scope. Realm- and asset-scoped engines use
   * their own realm. Asset-scoped engines require at least one target, and every target must be the
   * scoped asset itself or a descendant. Realm-scoped engines may omit targets.
   *
   * <p>Global engines use the supplied alarm realm, or infer it from the target assets when it is
   * null or blank. All targets must belong to the resolved realm. The supplied alarm definition is
   * preserved for global engines so it can be reused with targets in another realm.
   *
   * <p>An assignee is optional; when provided, the user must belong to the alarm's realm.
   * Restricted assignees require at least one target and explicit links to every target asset. The
   * caller must set the alarm's source ID to identify the ruleset.
   *
   * @return the created alarm ID, or {@code null} if the realm cannot be resolved, an asset-scoped
   *     engine has no targets, an asset is outside the engine's asset scope, or the assignee cannot
   *     access the targets
   * @throws IllegalArgumentException if an asset ID is invalid or belongs to a different realm
   */
  public abstract Long create(Alarm alarm, List<String> assetIds);

  /**
   * Adds asset links to an existing alarm. Realm- and asset-scoped engines require the alarm to
   * belong to the engine's realm. Global engines use the existing alarm's realm. Every requested
   * asset must belong to the alarm's realm.
   *
   * <p>Asset-scoped engines require at least one existing link, and all existing and requested
   * assets must be within the engine's subtree. An assignee is optional; when provided, the user
   * must belong to the alarm's realm. Restricted assignees must have explicit links to every
   * existing and requested asset.
   *
   * <p>No links are changed if scope or assignee access is denied. Null or empty asset lists are
   * ignored. Duplicate and existing links are accepted without changing their timestamps.
   *
   * @throws IllegalArgumentException if the alarm ID or an asset ID is invalid, or the alarm or a
   *     requested asset belongs to a different realm
   */
  public abstract void linkAssets(List<String> assetIds, Long alarmId);
}
