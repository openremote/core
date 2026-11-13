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
import org.openremote.agent.protocol.tradfri.util.ColourXY;
import org.openremote.model.value.impl.ColourRGB;

/** The class that represents a light color changed event that occurred to an IKEA TRÅDFRI light */
public class LightChangeColourEvent extends LightChangeEvent {

  /**
   * Construct the LightChangeColourEvent class
   *
   * @param light The light for which the event occurred
   * @param oldProperties The old properties of the light (from before the event occurred)
   * @param newProperties The new properties of the light (from after the event occurred)
   */
  public LightChangeColourEvent(
      Light light, LightProperties oldProperties, LightProperties newProperties) {
    super(light, oldProperties, newProperties);
  }

  /**
   * Get the old hue of the light (from before the event occurred)
   *
   * @return The old hue of the light
   */
  public int getOldHue() {
    return getOldProperties().getHue();
  }

  /**
   * Get the new hue of the light (from after the event occurred)
   *
   * @return The new hue of the light
   */
  public int getNewHue() {
    return getNewProperties().getHue();
  }

  /**
   * Get the old saturation of the light (from before the event occurred)
   *
   * @return The old saturation of the light
   */
  public int getOldSaturation() {
    return getOldProperties().getSaturation();
  }

  /**
   * Get the new saturation of the light (from after the event occurred)
   *
   * @return The new saturation of the light
   */
  public int getNewSaturation() {
    return getNewProperties().getSaturation();
  }

  /**
   * Get the old X value of the color of the light (from before the event occurred)
   *
   * @return The old X value of the color of the light
   */
  public int getOldColourX() {
    return getOldProperties().getColourX();
  }

  /**
   * Get the new X value of the color of the light (from after the event occurred)
   *
   * @return The new X value of the color of the light
   */
  public int getNewColourX() {
    return getNewProperties().getColourX();
  }

  /**
   * Get the old Y value of the color of the light (from before the event occurred)
   *
   * @return The old Y value of the color of the light
   */
  public int getOldColourY() {
    return getOldProperties().getColourY();
  }

  /**
   * Get the new Y value of the color of the light (from after the event occurred)
   *
   * @return The new Y value of the color of the light
   */
  public int getNewColourY() {
    return getNewProperties().getColourY();
  }

  /**
   * Get the old XY color of the light (from before the event occurred)
   *
   * @return The old XY color of the light
   */
  public ColourXY getOldColourXY() {
    return new ColourXY(getOldProperties().getColourX(), getOldProperties().getColourY());
  }

  /**
   * Get the new XY color of the light (from after the event occurred)
   *
   * @return The new XY color of the light
   */
  public ColourXY getNewColourXY() {
    return new ColourXY(getNewProperties().getColourX(), getNewProperties().getColourY());
  }

  /**
   * Get the old RGB color of the light (from before the event occurred)
   *
   * @return The old RGB color of the light
   */
  public ColourRGB getOldColourRGB() {
    return ColourRGB.fromHS(
        getOldProperties().getHue() != null ? getOldProperties().getHue() : 0,
        getOldProperties().getSaturation() != null ? getOldProperties().getSaturation() : 0);
  }

  /**
   * Get the new RGB color of the light (from after the event occurred)
   *
   * @return The new RGB color of the light
   */
  public ColourRGB getNewColourRGB() {
    return ColourRGB.fromHS(
        getNewProperties().getHue() != null ? getNewProperties().getHue() : 0,
        getNewProperties().getSaturation() != null ? getNewProperties().getSaturation() : 0);
  }
}
