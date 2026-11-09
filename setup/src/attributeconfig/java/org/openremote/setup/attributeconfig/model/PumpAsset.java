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
package org.openremote.setup.attributeconfig.model;

import jakarta.persistence.Entity;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.ValueType;

/**
 * Asset type of the attribute configuration demo. The descriptors deliberately carry no metadata,
 * so a new pump starts without configuration items and is an empty target for an import.
 */
@Entity
public class PumpAsset extends Asset<PumpAsset> {

  public static final AttributeDescriptor<Double> FLOW_RATE =
      new AttributeDescriptor<>("flowRate", ValueType.NUMBER);
  public static final AttributeDescriptor<Double> PRESSURE =
      new AttributeDescriptor<>("pressure", ValueType.NUMBER);
  public static final AttributeDescriptor<Boolean> RUNNING =
      new AttributeDescriptor<>("running", ValueType.BOOLEAN);

  public static final AssetDescriptor<PumpAsset> DESCRIPTOR =
      new AssetDescriptor<>("water-pump", "1565c0", PumpAsset.class);

  /** For use by hydrators (i.e. JPA/Jackson) */
  protected PumpAsset() {}

  public PumpAsset(String name) {
    super(name);
  }
}
