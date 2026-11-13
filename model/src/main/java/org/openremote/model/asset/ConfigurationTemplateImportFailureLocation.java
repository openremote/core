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

/**
 * Where a {@link ConfigurationTemplate} cannot be imported, from the attribute entry down to the
 * parameter of a metadata item. A part is absent when the failure lies above it, so a parameter
 * without an attribute is a field of the document itself.
 */
@Schema(
    description =
        "Where a configuration template cannot be imported; a part is absent when the failure lies above it.")
public class ConfigurationTemplateImportFailureLocation {

  @Schema(description = "Attribute name of the entry.", example = "temperature")
  protected String attribute;

  @Schema(description = "Name of the metadata item within the entry.", example = "forecast")
  protected String metaItem;

  @Schema(
      description =
          "Path of the parameter within the metadata item, or of the field within the entry or the document.",
      example = "pastCount")
  protected String parameter;

  @JsonCreator
  public ConfigurationTemplateImportFailureLocation(
      String attribute, String metaItem, String parameter) {
    this.attribute = attribute;
    this.metaItem = metaItem;
    this.parameter = parameter;
  }

  public String getAttribute() {
    return attribute;
  }

  public String getMetaItem() {
    return metaItem;
  }

  public String getParameter() {
    return parameter;
  }
}
