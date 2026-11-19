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
 * Second asset type of the config template demo, sharing some attribute names with the pump. The
 * descriptors carry no metadata, so a new mixer starts without configuration items.
 */
@Entity
public class MixerAsset extends Asset<MixerAsset> {

  public static final AttributeDescriptor<Double> FLOW_RATE =
      new AttributeDescriptor<>("flowRate", ValueType.NUMBER);
  public static final AttributeDescriptor<Double> PRESSURE =
      new AttributeDescriptor<>("pressure", ValueType.NUMBER);
  // A number, where the pump holds text
  public static final AttributeDescriptor<Double> SERIAL_NUMBER =
      new AttributeDescriptor<>("serialNumber", ValueType.NUMBER);

  public static final AssetDescriptor<MixerAsset> DESCRIPTOR =
      new AssetDescriptor<>("blender", "6d4c41", MixerAsset.class);

  /** For use by hydrators (i.e. JPA/Jackson) */
  protected MixerAsset() {}

  public MixerAsset(String name) {
    super(name);
  }
}
