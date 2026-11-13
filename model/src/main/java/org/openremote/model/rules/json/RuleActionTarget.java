/*
 * Copyright 2019, OpenRemote Inc.
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
package org.openremote.model.rules.json;

import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.UserQuery;

/**
 * Controls which {@link org.openremote.model.asset.Asset}s the {@link RuleAction} is applied to; if
 * not supplied or none of the options are supplied then the default behavior is to use all {@link
 * org.openremote.model.asset.Asset}s that caused the rule to trigger. The precedence is:
 *
 * <ol>
 *   <li>{@link #conditionAssets} - Assets that matched a specific when condition
 *   <li>{@link #matchedAssets} - Assets that matched with additional {@link AssetQuery} applied
 *   <li>{@link #assets}
 *   <li>{@link #users}
 *   <li>{@link #linkedUsers} - Users linked to matched assets
 *   <li>{@link #custom} - A custom string that the Rule Action interprets to generate targets
 * </ol>
 */
public class RuleActionTarget {
  public String conditionAssets;
  public AssetQuery matchedAssets;
  public AssetQuery assets;
  public UserQuery users;
  public Boolean linkedUsers;
  public String custom;
}
