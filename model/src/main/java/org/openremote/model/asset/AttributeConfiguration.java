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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The configuration of a single {@link org.openremote.model.attribute.Attribute} within an {@link
 * AttributeConfigurationDocument}. The attribute name is the document map key, so it is not
 * repeated here.
 */
@Schema(
    description =
        "Configuration of a single attribute; the attribute name is the key within the document.")
public class AttributeConfiguration {

  @Schema(description = "Value descriptor name of the attribute, e.g. number.")
  protected String type;

  @Schema(description = "Metadata keyed by meta-item name, exactly as stored on the attribute.")
  protected ObjectNode meta;

  @JsonCreator
  public AttributeConfiguration(
      @JsonProperty("type") String type, @JsonProperty("meta") ObjectNode meta) {
    this.type = type;
    this.meta = meta;
  }

  public String getType() {
    return type;
  }

  public ObjectNode getMeta() {
    return meta;
  }
}
