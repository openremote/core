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
package org.openremote.agent.protocol.tradfri.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.openremote.agent.protocol.tradfri.util.ApiCode;

/** The class that contains the properties of an IKEA TRÅDFRI light */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LightProperties extends DeviceProperties {

  /** The on state of the light (true for on, false for off) */
  @JsonProperty(ApiCode.ON_OFF)
  private Integer on;

  /** The brightness of the light */
  @JsonProperty(ApiCode.BRIGHTNESS)
  private Integer brightness;

  /** The color of the light in hexadecimals */
  @JsonProperty(ApiCode.COLOUR_HEX)
  private String colourHex;

  /** The hue of the light */
  @JsonProperty(ApiCode.HUE)
  private Integer hue;

  /** The saturation of the light */
  @JsonProperty(ApiCode.SATURATION)
  private Integer saturation;

  /** The X value of the color of the light */
  @JsonProperty(ApiCode.COLOUR_X)
  private Integer colourX;

  /** The Y value of the color of the light */
  @JsonProperty(ApiCode.COLOUR_Y)
  private Integer colourY;

  /** The color temperature of the light */
  @JsonProperty(ApiCode.COLOUR_TEMPERATURE)
  private Integer colourTemperature;

  /** The transition time for updating the light */
  @JsonProperty(ApiCode.TRANSITION_TIME)
  private Integer transitionTime;

  /** Construct the LightProperties class */
  public LightProperties() {}

  /**
   * Get the on state of the light
   *
   * @return The on state of the light (true for on, false for off)
   */
  public Boolean getOn() {
    return this.on != null && this.on.equals(1);
  }

  /**
   * Get the brightness of the light
   *
   * @return The brightness of the light
   */
  public Integer getBrightness() {
    return this.brightness;
  }

  /**
   * Get the color of the light in hexadecimals
   *
   * @return The color of the light in hexadecimals
   */
  public String getColourHex() {
    return this.colourHex;
  }

  /**
   * Get the hue of the light
   *
   * @return The hue of the light
   */
  public Integer getHue() {
    return this.hue;
  }

  /**
   * Get the saturation of the light
   *
   * @return The saturation of the light
   */
  public Integer getSaturation() {
    return this.saturation;
  }

  /**
   * Get the X value of the color of the light
   *
   * @return The X value of the color of the light
   */
  public Integer getColourX() {
    return this.colourX;
  }

  /**
   * Get the Y value of the color of the light
   *
   * @return The Y value of the color of the light
   */
  public Integer getColourY() {
    return this.colourY;
  }

  /**
   * Get the color temperature of the light
   *
   * @return The color temperature of the light
   */
  public Integer getColourTemperature() {
    return this.colourTemperature;
  }

  /**
   * Get the transition time for updating the light
   *
   * @return The transition time for updating the light
   */
  public Integer getTransitionTime() {
    return this.transitionTime;
  }

  /**
   * Set the on state of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param on The new on state for the light (true for on, false for off)
   */
  public void setOn(Boolean on) {
    this.on = on != null ? on ? 1 : 0 : null;
  }

  /**
   * Set the brightness of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param brightness The new brightness for the light
   */
  public void setBrightness(Integer brightness) {
    this.brightness = brightness;
  }

  /**
   * Set the color of the light to a predefined hexadecimal color within the LightProperties class
   * <br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param colourHex The new color for the light
   */
  public void setColourHex(String colourHex) {
    this.colourHex = colourHex;
  }

  /**
   * Set the hue of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param hue The new hue for the light
   */
  public void setHue(Integer hue) {
    this.hue = hue;
  }

  /**
   * Set the saturation of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param saturation The new saturation for the light
   */
  public void setSaturation(Integer saturation) {
    this.saturation = saturation;
  }

  /**
   * Set the X value of the color of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param colourX The X value of the new color for the light
   */
  public void setColourX(Integer colourX) {
    this.colourX = colourX;
  }

  /**
   * Set the Y value of the color of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param colourY The Y value of the new color for the light
   */
  public void setColourY(Integer colourY) {
    this.colourY = colourY;
  }

  /**
   * Set the color temperature of the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param colourTemperature The new color temperature for the light
   */
  public void setColourTemperature(Integer colourTemperature) {
    this.colourTemperature = colourTemperature;
  }

  /**
   * Set the transition time for updating the light within the LightProperties class<br>
   * <i>Note: This does not change the actual light</i>
   *
   * @param transitionTime The new transition time for updating the light
   */
  public void setTransitionTime(Integer transitionTime) {
    this.transitionTime = transitionTime;
  }
}
