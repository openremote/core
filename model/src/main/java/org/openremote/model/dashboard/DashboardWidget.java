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
package org.openremote.model.dashboard;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.openremote.model.util.ValueUtil;

import java.util.Objects;

public class DashboardWidget {

  // Fields
  protected String id;

  @NotNull(message = "{Dashboard.widget.displayName.NotNull}") protected String displayName;

  @NotNull(message = "{Dashboard.widget.gridItem.NotNull}") @Valid protected DashboardGridItem gridItem;

  @NotNull(message = "{Dashboard.widget.widgetTypeId.NotNull}") protected String widgetTypeId;

  protected Object widgetConfig;

  /* ------------------------------ */

  public DashboardWidget setId(@NotNull @NotEmpty String id) {
    this.id = id;
    return this;
  }

  public DashboardWidget setDisplayName(@NotNull String displayName) {
    this.displayName = displayName;
    return this;
  }

  public DashboardWidget setGridItem(@NotNull DashboardGridItem gridItem) {
    this.gridItem = gridItem;
    return this;
  }

  public DashboardWidget setWidgetTypeId(@NotNull String widgetTypeId) {
    this.widgetTypeId = widgetTypeId;
    return this;
  }

  public DashboardWidget setWidgetConfig(Object widgetConfig) {
    this.widgetConfig = widgetConfig;
    return this;
  }

  public String getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }

  public DashboardGridItem getGridItem() {
    return gridItem;
  }

  public String getWidgetTypeId() {
    return widgetTypeId;
  }

  public Object getWidgetConfig() {
    return widgetConfig;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DashboardWidget that)) return false;
    return Objects.equals(id, that.id)
        && Objects.equals(displayName, that.displayName)
        && Objects.equals(gridItem, that.gridItem)
        && Objects.equals(widgetTypeId, that.widgetTypeId)
        && ValueUtil.objectsEqualsWithJSONFallback(widgetConfig, that.widgetConfig);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, displayName, gridItem, widgetTypeId);
  }
}
