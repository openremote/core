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
import java.util.Map;

/**
 * What importing an {@link AttributeConfigurationDocument} into an asset draft would do, for the
 * user to confirm or cancel. With a {@link #failure} nothing can be imported and there are no
 * patched attributes, but the other fields still hold what could be worked out, such as the skipped
 * attributes or the generic parameters that need a value.
 */
@Schema(
    description =
        "Outcome of validating an attribute configuration document against an asset draft, with the patched attributes to apply on confirmation.")
public class AttributeConfigurationImportPreview {

  @Schema(description = "Why the document cannot be imported; absent when it can.")
  protected AttributeConfigurationImportFailure failure;

  @Schema(description = "Asset type the document was exported from.")
  protected String importedAssetType;

  @Schema(description = "Asset type of the target draft.")
  protected String targetAssetType;

  @Schema(description = "Whether the document was exported from a different asset type.")
  protected boolean assetTypeMismatch;

  @Schema(description = "Attributes whose metadata will be replaced.")
  protected List<AttributeConfigurationSummary> importableAttributes;

  @Schema(description = "Attributes skipped because the target asset does not have them.")
  protected List<AttributeConfigurationSummary> missingAttributes;

  @Schema(description = "Attributes skipped because the target attribute has a different type.")
  protected List<AttributeConfigurationTypeMismatch> typeMismatches;

  @Schema(
      description =
          "Generic parameters that need a value, keyed by parameter name, listing only the paths of importable attributes.")
  protected Map<String, AttributeConfigurationGenericParameter> genericParameters;

  @Schema(
      description =
          "Names of the generic parameters whose value does not match their type or the schema of the metadata they are applied to.")
  protected List<String> invalidGenericParameters;

  @Schema(
      description =
          "The submitted draft attributes with the metadata of every importable attribute replaced, keyed by attribute name.")
  protected ObjectNode patchedAttributes;

  @JsonCreator
  public AttributeConfigurationImportPreview(
      @JsonProperty("failure") AttributeConfigurationImportFailure failure,
      @JsonProperty("importedAssetType") String importedAssetType,
      @JsonProperty("targetAssetType") String targetAssetType,
      @JsonProperty("assetTypeMismatch") boolean assetTypeMismatch,
      @JsonProperty("importableAttributes")
          List<AttributeConfigurationSummary> importableAttributes,
      @JsonProperty("missingAttributes") List<AttributeConfigurationSummary> missingAttributes,
      @JsonProperty("typeMismatches") List<AttributeConfigurationTypeMismatch> typeMismatches,
      @JsonProperty("genericParameters")
          Map<String, AttributeConfigurationGenericParameter> genericParameters,
      @JsonProperty("invalidGenericParameters") List<String> invalidGenericParameters,
      @JsonProperty("patchedAttributes") ObjectNode patchedAttributes) {
    this.failure = failure;
    this.importedAssetType = importedAssetType;
    this.targetAssetType = targetAssetType;
    this.assetTypeMismatch = assetTypeMismatch;
    this.importableAttributes = importableAttributes;
    this.missingAttributes = missingAttributes;
    this.typeMismatches = typeMismatches;
    this.genericParameters = genericParameters;
    this.invalidGenericParameters = invalidGenericParameters;
    this.patchedAttributes = patchedAttributes;
  }

  public static AttributeConfigurationImportPreview failed(
      AttributeConfigurationImportFailure failure) {
    return new AttributeConfigurationImportPreview(
        failure, null, null, false, List.of(), List.of(), List.of(), Map.of(), List.of(), null);
  }

  public AttributeConfigurationImportFailure getFailure() {
    return failure;
  }

  public String getImportedAssetType() {
    return importedAssetType;
  }

  public String getTargetAssetType() {
    return targetAssetType;
  }

  public boolean isAssetTypeMismatch() {
    return assetTypeMismatch;
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

  public Map<String, AttributeConfigurationGenericParameter> getGenericParameters() {
    return genericParameters;
  }

  public List<String> getInvalidGenericParameters() {
    return invalidGenericParameters;
  }

  public ObjectNode getPatchedAttributes() {
    return patchedAttributes;
  }
}
