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
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * An {@link AttributeConfigurationDocument} to import, together with the asset draft it is imported
 * into. The document is submitted as text so that unparsable files are reported like any other
 * invalid document.
 */
@Schema(
    description =
        "An attribute configuration document to validate against an asset draft, as file text.")
public class AttributeConfigurationImportRequest {

  @Schema(description = "The asset draft currently being edited, including unsaved changes.")
  @NotNull protected Asset<?> draft;

  @Schema(description = "Contents of the selected attribute configuration file.")
  @NotNull protected String document;

  @Schema(
      description =
          "Values of the generic parameters of the document, keyed by parameter name; absent until the user has supplied them.")
  protected Map<String, JsonNode> genericParameterValues;

  public AttributeConfigurationImportRequest(Asset<?> draft, String document) {
    this(draft, document, null);
  }

  @JsonCreator
  public AttributeConfigurationImportRequest(
      @JsonProperty("draft") Asset<?> draft,
      @JsonProperty("document") String document,
      @JsonProperty("genericParameterValues") Map<String, JsonNode> genericParameterValues) {
    this.draft = draft;
    this.document = document;
    this.genericParameterValues = genericParameterValues;
  }

  public Asset<?> getDraft() {
    return draft;
  }

  public String getDocument() {
    return document;
  }

  public Map<String, JsonNode> getGenericParameterValues() {
    return genericParameterValues;
  }
}
