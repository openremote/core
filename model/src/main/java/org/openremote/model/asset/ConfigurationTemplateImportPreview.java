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
import java.util.List;

/**
 * What importing a {@link ConfigurationTemplate} into an asset draft would do, for the user to
 * confirm or cancel. With a {@link #failure} nothing can be imported and there are no patched
 * attributes, but the other fields still hold what could be worked out, such as the skipped
 * attributes.
 */
@Schema(
    description =
        "Outcome of validating a configuration template against an asset draft, with the patched attributes to apply on confirmation.")
public class ConfigurationTemplateImportPreview {

  @Schema(description = "Why the document cannot be imported; absent when it can.")
  protected ConfigurationTemplateImportFailure failure;

  @Schema(description = "Attributes whose metadata will be replaced.")
  protected List<AttributeConfigurationSummary> importableAttributes;

  @Schema(description = "Attributes skipped because the target asset does not have them.")
  protected List<AttributeConfigurationSummary> missingAttributes;

  @Schema(description = "Attributes skipped because the target attribute has a different type.")
  protected List<AttributeConfigurationTypeMismatch> typeMismatches;

  @Schema(
      description =
          "The submitted draft attributes with the metadata of every importable attribute replaced, keyed by attribute name.")
  protected ObjectNode patchedAttributes;

  @JsonCreator
  public ConfigurationTemplateImportPreview(
      @JsonProperty("failure") ConfigurationTemplateImportFailure failure,
      @JsonProperty("importableAttributes")
          List<AttributeConfigurationSummary> importableAttributes,
      @JsonProperty("missingAttributes") List<AttributeConfigurationSummary> missingAttributes,
      @JsonProperty("typeMismatches") List<AttributeConfigurationTypeMismatch> typeMismatches,
      @JsonProperty("patchedAttributes") ObjectNode patchedAttributes) {
    this.failure = failure;
    this.importableAttributes = importableAttributes;
    this.missingAttributes = missingAttributes;
    this.typeMismatches = typeMismatches;
    this.patchedAttributes = patchedAttributes;
  }

  public static ConfigurationTemplateImportPreview failed(
      ConfigurationTemplateImportFailure failure) {
    return new ConfigurationTemplateImportPreview(failure, List.of(), List.of(), List.of(), null);
  }

  public ConfigurationTemplateImportFailure getFailure() {
    return failure;
  }

  public List<AttributeConfigurationSummary> getImportableAttributes() {
    return importableAttributes;
  }

  public List<AttributeConfigurationSummary> getMissingAttributes() {
    return missingAttributes;
  }

  public List<AttributeConfigurationTypeMismatch> getTypeMismatches() {
    return typeMismatches;
  }

  public ObjectNode getPatchedAttributes() {
    return patchedAttributes;
  }
}
