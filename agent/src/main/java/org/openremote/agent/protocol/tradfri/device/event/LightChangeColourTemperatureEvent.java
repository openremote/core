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
package org.openremote.agent.protocol.tradfri.device.event;

import org.openremote.agent.protocol.tradfri.device.Light;
import org.openremote.agent.protocol.tradfri.device.LightProperties;

/**
 * The class that represents a light color temperature changed event that occurred to an IKEA
 * TRÅDFRI light
 */
public class LightChangeColourTemperatureEvent extends LightChangeEvent {

  /**
   * Construct the LightChangeColourTemperatureEvent class
   *
   * @param light The light for which the event occurred
   * @param oldProperties The old properties of the light (from before the event occurred)
   * @param newProperties The new properties of the light (from after the event occurred)
   */
  public LightChangeColourTemperatureEvent(
      Light light, LightProperties oldProperties, LightProperties newProperties) {
    super(light, oldProperties, newProperties);
  }

  /**
   * Get the old color temperature of the light (from before the event occurred)
   *
   * @return The old color temperature of the light
   */
  public int getOldColourTemperature() {
    return getOldProperties().getColourTemperature();
  }

  /**
   * Get the new color temperature of the light (from after the event occurred)
   *
   * @return The new color temperature of the light
   */
  public int getNewColourTemperature() {
    return getNewProperties().getColourTemperature();
  }
}
