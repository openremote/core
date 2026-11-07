/*
 * Copyright 2017, OpenRemote Inc.
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

import org.openremote.model.event.shared.RealmScopedEvent;

/** Published by the server when a rules engine changes its {@link RulesEngineStatus}. */
public class RulesEngineStatusEvent extends RealmScopedEvent {

  protected String engineId;
  protected RulesEngineInfo engineInfo;

  protected RulesEngineStatusEvent() {}

  public RulesEngineStatusEvent(
      long timestamp, String realm, String engineId, RulesEngineInfo engineInfo) {
    super(timestamp, realm);
    this.engineId = engineId;
    this.engineInfo = engineInfo;
  }

  public String getEngineId() {
    return engineId;
  }

  public RulesEngineInfo getEngineInfo() {
    return engineInfo;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "engineId='"
        + engineId
        + '\''
        + ", engineInfo="
        + engineInfo
        + '}';
  }
}
