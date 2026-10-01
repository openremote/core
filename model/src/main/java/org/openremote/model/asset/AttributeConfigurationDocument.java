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
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * A user editable document holding the configuration of the {@link
 * org.openremote.model.attribute.Attribute}s of an {@link Asset}, exchanged as a file by the
 * Manager UI. Attributes are keyed by attribute name.
 */
@Schema(
    description =
        "Versioned document holding attribute configuration of an asset, keyed by attribute name.")
public class AttributeConfigurationDocument {

  /** The only document version this release can read and write. */
  public static final int VERSION = 1;

  @Schema(description = "Document format version.", example = "1")
  protected int version;

  @Schema(description = "Asset type the configuration was exported from.")
  protected String assetType;

  @Schema(description = "Attribute configuration keyed by attribute name.")
  protected Map<String, AttributeConfiguration> attributes;

  @JsonCreator
  public AttributeConfigurationDocument(
      @JsonProperty("version") int version,
      @JsonProperty("assetType") String assetType,
      @JsonProperty("attributes") Map<String, AttributeConfiguration> attributes) {
    this.version = version;
    this.assetType = assetType;
    this.attributes = attributes;
  }

  public int getVersion() {
    return version;
  }

  public String getAssetType() {
    return assetType;
  }

  public Map<String, AttributeConfiguration> getAttributes() {
    return attributes;
  }
}
