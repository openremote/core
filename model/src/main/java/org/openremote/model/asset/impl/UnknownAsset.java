/*
 * Copyright 2022, OpenRemote Inc.
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
package org.openremote.model.asset.impl;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import org.openremote.model.asset.Asset;

/**
 * This is only needed so JPA can deserialize an Asset whose type doesn't match an entity otherwise
 * it is un-used
 */
@Entity
@DiscriminatorValue("not null")
public class UnknownAsset extends Asset<ThingAsset> {
  /** For use by hydrators (i.e. JPA/Jackson) */
  protected UnknownAsset() {}

  public UnknownAsset(String name) {
    super(name);
  }
}
