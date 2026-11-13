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
package org.openremote.agent.protocol.tradfri.util;

import org.openremote.model.value.impl.ColourRGB;

/** The class that contains XY values that make up a color */
public class ColourXY {

  /** The X value of the color */
  private Integer X;

  /** The Y value of the color */
  private Integer Y;

  /**
   * Construct the ColourXY class
   *
   * @param X The X value of the color
   * @param Y The Y value of the color
   */
  public ColourXY(Integer X, Integer Y) {
    this.X = X;
    this.Y = Y;
  }

  /** Construct the ColourXY class */
  public ColourXY() {}

  /**
   * Get the X value of the color
   *
   * @return The X value of the color
   */
  public Integer getX() {
    return this.X;
  }

  /**
   * Get the Y value of the color
   *
   * @return The Y value of the color
   */
  public Integer getY() {
    return this.Y;
  }

  /**
   * Set the X value of the color
   *
   * @param X The new X value of the color
   */
  public void setX(Integer X) {
    this.X = X;
  }

  /**
   * Set the Y value of the color
   *
   * @param Y The new Y value of the color
   */
  public void setY(Integer Y) {
    this.Y = Y;
  }

  /**
   * Construct the ColourXY class from the {@link ColourRGB} class
   *
   * @param colourRGB The {@link ColourRGB} class
   * @return The ColourXY class
   */
  public static ColourXY fromRGB(ColourRGB colourRGB) {
    double red = Math.max(Math.min(colourRGB.getR(), 255), 0);
    double green = Math.max(Math.min(colourRGB.getG(), 255), 0);
    double blue = Math.max(Math.min(colourRGB.getB(), 255), 0);

    red = red / 255;
    green = green / 255;
    blue = blue / 255;

    red = (red > 0.04045) ? Math.pow((red + 0.055) / (1.0 + 0.055), 2.4) : (red / 12.92);
    green = (green > 0.04045) ? Math.pow((green + 0.055) / (1.0 + 0.055), 2.4) : (green / 12.92);
    blue = (blue > 0.04045) ? Math.pow((blue + 0.055) / (1.0 + 0.055), 2.4) : (blue / 12.92);

    double X = red * 0.4124564 + green * 0.3575761 + blue * 0.1804375;
    double Y = red * 0.2126729 + green * 0.7151522 + blue * 0.0721750;
    double Z = red * 0.0193339 + green * 0.1191920 + blue * 0.9503041;
    double total = X + Y + Z;

    double x = 0;
    double y = 0;
    if (total != 0) {
      x = X / total;
      y = Y / total;
    }

    int xNormalised = (int) (x * 65535 + 0.5);
    int yNormalised = (int) (y * 65535 + 0.5);

    return new ColourXY(xNormalised, yNormalised);
  }
}
