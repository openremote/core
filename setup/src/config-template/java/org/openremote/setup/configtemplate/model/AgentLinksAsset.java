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
package org.openremote.setup.configtemplate.model;

import jakarta.persistence.Entity;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.ValueType;

/**
 * Asset type of the config template demo with an attribute for every agent link type, so a template
 * can hold a link of each type.
 */
@Entity
public class AgentLinksAsset extends Asset<AgentLinksAsset> {

  public static final AttributeDescriptor<Boolean> BLUETOOTH_MESH =
      new AttributeDescriptor<>("bluetoothMesh", ValueType.BOOLEAN);
  public static final AttributeDescriptor<String> DEFAULT =
      new AttributeDescriptor<>("default", ValueType.TEXT);
  public static final AttributeDescriptor<String> HTTP =
      new AttributeDescriptor<>("http", ValueType.TEXT);
  public static final AttributeDescriptor<Boolean> KNX =
      new AttributeDescriptor<>("knx", ValueType.BOOLEAN);
  public static final AttributeDescriptor<String> MAIL =
      new AttributeDescriptor<>("mail", ValueType.TEXT);
  public static final AttributeDescriptor<Double> MODBUS =
      new AttributeDescriptor<>("modbus", ValueType.NUMBER);
  public static final AttributeDescriptor<String> MQTT =
      new AttributeDescriptor<>("mqtt", ValueType.TEXT);
  public static final AttributeDescriptor<Double> OPEN_WEATHER_MAP =
      new AttributeDescriptor<>("openWeatherMap", ValueType.NUMBER);
  public static final AttributeDescriptor<Double> SIMULATOR =
      new AttributeDescriptor<>("simulator", ValueType.NUMBER);
  public static final AttributeDescriptor<String> SNMP =
      new AttributeDescriptor<>("snmp", ValueType.TEXT);
  public static final AttributeDescriptor<Boolean> VELBUS =
      new AttributeDescriptor<>("velbus", ValueType.BOOLEAN);
  public static final AttributeDescriptor<String> WEBSOCKET =
      new AttributeDescriptor<>("websocket", ValueType.TEXT);
  public static final AttributeDescriptor<Boolean> Z_WAVE =
      new AttributeDescriptor<>("zWave", ValueType.BOOLEAN);

  public static final AssetDescriptor<AgentLinksAsset> DESCRIPTOR =
      new AssetDescriptor<>("link-variant", "607d8b", AgentLinksAsset.class);

  /** For use by hydrators (i.e. JPA/Jackson) */
  protected AgentLinksAsset() {}

  public AgentLinksAsset(String name) {
    super(name);
  }
}
