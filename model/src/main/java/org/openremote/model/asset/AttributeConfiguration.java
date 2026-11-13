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
package org.openremote.model.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openremote.model.attribute.MetaMap;
import org.openremote.model.value.MetaHolder;

/**
 * The configuration of a single {@link org.openremote.model.attribute.Attribute} within an {@link
 * ConfigurationTemplate}. The attribute name is the document map key, so it is not repeated here.
 */
@Schema(
    description =
        "Configuration of a single attribute; the attribute name is the key within the document.")
public class AttributeConfiguration implements MetaHolder {

  @Schema(description = "Value descriptor name of the attribute, e.g. number.")
  protected String type;

  @Schema(description = "Metadata keyed by meta-item name, exactly as stored on the attribute.")
  protected MetaMap meta;

  @JsonCreator
  public AttributeConfiguration(String type, MetaMap meta) {
    this.type = type;
    this.meta = meta;
  }

  public String getType() {
    return type;
  }

  @Override
  public MetaMap getMeta() {
    return meta;
  }
}
