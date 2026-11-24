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

/**
 * The class that contains constants to find codes for the properties in the requests and responses
 * for the IKEA TRÅDFRI API
 */
public class ApiCode {

  /** Construct the ApiCode class */
  private ApiCode() {}

  // General

  /**
   * The IKEA TRÅDFRI API code for a name property<br>
   * <i>Value: {@value}</i>
   */
  public static final String NAME = "9001";

  /**
   * The IKEA TRÅDFRI API code for a creation date property<br>
   * <i>Value: {@value}</i>
   */
  public static final String CREATION_DATE = "9002";

  /**
   * The IKEA TRÅDFRI API code for an instance id property<br>
   * <i>Value: {@value}</i>
   */
  public static final String INSTANCE_ID = "9003";

  /**
   * The IKEA TRÅDFRI API code for a on / off state property<br>
   * <i>Value: {@value}</i>
   */
  public static final String ON_OFF = "5850";

  /**
   * The IKEA TRÅDFRI API code for a brightness property<br>
   * <i>Value: {@value}</i>
   */
  public static final String BRIGHTNESS = "5851";

  /**
   * The IKEA TRÅDFRI API code for a transition time property<br>
   * <i>Value: {@value}</i>
   */
  public static final String TRANSITION_TIME = "5712";

  // Devices

  /**
   * The IKEA TRÅDFRI API code for a motion sensor property<br>
   * <i>Value: {@value}</i>
   */
  public static final String MOTION_SENSOR = "3300";

  /**
   * The IKEA TRÅDFRI API code for a light property<br>
   * <i>Value: {@value}</i>
   */
  public static final String LIGHT = "3311";

  /**
   * The IKEA TRÅDFRI API code for a plug property<br>
   * <i>Value: {@value}</i>
   */
  public static final String PLUG = "3312";

  /**
   * The IKEA TRÅDFRI API code for a blind property<br>
   * <i>Value: {@value}</i>
   */
  public static final String BLIND = "15015";

  // Device Information

  /**
   * The IKEA TRÅDFRI API code for device information<br>
   * <i>Value: {@value}</i>
   */
  public static final String DEVICE_INFORMATION = "3";

  /**
   * The IKEA TRÅDFRI API code for a device manufacturer<br>
   * <i>Value: {@value}</i>
   */
  public static final String MANUFACTURER = "0";

  /**
   * The IKEA TRÅDFRI API code for a device model name<br>
   * <i>Value: {@value}</i>
   */
  public static final String MODEL_NAME = "1";

  /**
   * The IKEA TRÅDFRI API code for a device serial<br>
   * <i>Value: {@value}</i>
   */
  public static final String SERIAL = "2";

  /**
   * The IKEA TRÅDFRI API code for a device firmware version<br>
   * <i>Value: {@value}</i>
   */
  public static final String FIRMWARE_VERSION = "3";

  /**
   * The IKEA TRÅDFRI API code for a device power source<br>
   * <i>Value: {@value}</i>
   */
  public static final String POWER_SOURCE = "6";

  /**
   * The IKEA TRÅDFRI API code for a device manufacturer<br>
   * <i>Value: {@value}</i>
   */
  public static final String BATTERY_LEVEL = "9";

  // Lights

  /**
   * The IKEA TRÅDFRI API code for a hexadecimal color property<br>
   * <i>Value: {@value}</i>
   */
  public static final String COLOUR_HEX = "5706";

  /**
   * The IKEA TRÅDFRI API code for a hue property<br>
   * <i>Value: {@value}</i>
   */
  public static final String HUE = "5707";

  /**
   * The IKEA TRÅDFRI API code for a saturation property<br>
   * <i>Value: {@value}</i>
   */
  public static final String SATURATION = "5708";

  /**
   * The IKEA TRÅDFRI API code for a X value of a color property<br>
   * <i>Value: {@value}</i>
   */
  public static final String COLOUR_X = "5709";

  /**
   * The IKEA TRÅDFRI API code for a Y value of a color property<br>
   * <i>Value: {@value}</i>
   */
  public static final String COLOUR_Y = "5710";

  /**
   * The IKEA TRÅDFRI API code for a color temperature property<br>
   * <i>Value: {@value}</i>
   */
  public static final String COLOUR_TEMPERATURE = "5711";

  // Blinds

  /**
   * The IKEA TRÅDFRI API code for a blind position property<br>
   * <i>Value: {@value}</i>
   */
  public static final String POSITION = "5536";

  // Groups

  /**
   * The IKEA TRÅDFRI API code for a accessory link remote property<br>
   * <i>Value: {@value}</i>
   */
  public static final String ACCESSORY_LINK_REMOTE = "9018";

  /**
   * The IKEA TRÅDFRI API code for a scene id property<br>
   * <i>Value: {@value}</i>
   */
  public static final String SCENE_ID = "9039";

  // Scenes

  /**
   * The IKEA TRÅDFRI API code for a scene active state property<br>
   * <i>Value: {@value}</i>
   */
  public static final String SCENE_ACTIVE_STATE = "9058";

  /**
   * The IKEA TRÅDFRI API code for a device index id property<br>
   * <i>Value: {@value}</i>
   */
  public static final String DEVICE_INDEX_ID = "9057";

  /**
   * The IKEA TRÅDFRI API code for a predefined scene property<br>
   * <i>Value: {@value}</i>
   */
  public static final String IS_SCENE_PREDEFINED = "9068";

  /**
   * The IKEA TRÅDFRI API code for a light settings property<br>
   * <i>Value: {@value}</i>
   */
  public static final String LIGHT_SETTINGS = "15013";

  // Gateway

  /**
   * The IKEA TRÅDFRI API code for an identity property<br>
   * <i>Value: {@value}</i>
   */
  public static final String IDENTITY = "9090";

  /**
   * The IKEA TRÅDFRI API code for a preshared key property<br>
   * <i>Value: {@value}</i>
   */
  public static final String PRESHARED_KEY = "9091";

  /**
   * The IKEA TRÅDFRI API code for a gateway firmware version property<br>
   * <i>Value: {@value}</i>
   */
  public static final String GATEWAY_FIRMWARE_VERSION = "9029";
}
