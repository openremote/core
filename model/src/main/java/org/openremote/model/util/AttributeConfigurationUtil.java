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
package org.openremote.model.util;

import static org.openremote.model.asset.AttributeConfigurationImportFailure.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AttributeConfiguration;
import org.openremote.model.asset.AttributeConfigurationDocument;
import org.openremote.model.asset.AttributeConfigurationImportPreview;
import org.openremote.model.asset.AttributeConfigurationSummary;
import org.openremote.model.asset.AttributeConfigurationTypeMismatch;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeMap;

/**
 * Reads and writes {@link AttributeConfigurationDocument}s. Callers are responsible for enforcing
 * realm authorization and for persisting, or discarding, the patched attributes.
 */
public final class AttributeConfigurationUtil {

  private AttributeConfigurationUtil() {}

  /**
   * Builds the export document of an asset, holding the type and metadata of every attribute that
   * has metadata. A null or empty selection exports all of them.
   */
  public static AttributeConfigurationDocument export(
      Asset<?> asset, Collection<String> attributeNames) {
    boolean selectAll = attributeNames == null || attributeNames.isEmpty();
    Map<String, AttributeConfiguration> attributes = new TreeMap<>();

    if (asset.getAttributes() != null) {
      asset.getAttributes().values().stream()
          .filter(attribute -> attribute.getMeta() != null && !attribute.getMeta().isEmpty())
          .filter(attribute -> selectAll || attributeNames.contains(attribute.getName()))
          .forEach(
              attribute ->
                  attributes.put(
                      attribute.getName(),
                      new AttributeConfiguration(
                          typeName(attribute), toObjectNode(attribute.getMeta()))));
    }

    return new AttributeConfigurationDocument(
        AttributeConfigurationDocument.VERSION, asset.getType(), attributes);
  }

  /**
   * Validates a document against an asset draft and works out what importing it would do, without
   * changing the draft. The returned {@link
   * AttributeConfigurationImportPreview#getPatchedAttributes()} holds the draft attributes with the
   * metadata of every importable attribute replaced.
   */
  public static AttributeConfigurationImportPreview preview(Asset<?> draft, String document) {
    JsonNode root;

    try {
      root = ValueUtil.JSON.readTree(document);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return AttributeConfigurationImportPreview.failed(INVALID_JSON);
    }

    if (root == null || !root.isObject()) {
      return AttributeConfigurationImportPreview.failed(INVALID_JSON);
    }

    JsonNode version = root.get("version");
    JsonNode assetType = root.get("assetType");
    JsonNode attributes = root.get("attributes");

    if (isAbsent(version) || isAbsent(assetType) || isAbsent(attributes)) {
      return AttributeConfigurationImportPreview.failed(MISSING_REQUIRED_FIELD);
    }
    if (!version.isIntegralNumber() || version.asInt() != AttributeConfigurationDocument.VERSION) {
      return AttributeConfigurationImportPreview.failed(UNSUPPORTED_VERSION);
    }
    if (!assetType.isTextual() || assetType.asText().isBlank()) {
      return AttributeConfigurationImportPreview.failed(MISSING_REQUIRED_FIELD);
    }
    if (!attributes.isObject()) {
      return AttributeConfigurationImportPreview.failed(MALFORMED_ATTRIBUTE_ENTRY);
    }

    // The map key is the attribute name, so an entry's own name field, if any, is ignored
    Map<String, AttributeConfiguration> imported = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : attributes.properties()) {
      JsonNode type = entry.getValue().isObject() ? entry.getValue().get("type") : null;
      JsonNode meta = entry.getValue().isObject() ? entry.getValue().get("meta") : null;

      if (type == null || !type.isTextual() || type.asText().isBlank()) {
        return AttributeConfigurationImportPreview.failed(MALFORMED_ATTRIBUTE_ENTRY);
      }
      if (meta == null || !meta.isObject()) {
        return AttributeConfigurationImportPreview.failed(MALFORMED_ATTRIBUTE_ENTRY);
      }

      imported.put(
          entry.getKey(), new AttributeConfiguration(type.asText(), (ObjectNode) meta.deepCopy()));
    }

    AttributeMap targetAttributes =
        draft.getAttributes() != null ? draft.getAttributes() : new AttributeMap();
    List<AttributeConfigurationSummary> importable = new ArrayList<>();
    List<AttributeConfigurationSummary> missing = new ArrayList<>();
    List<AttributeConfigurationTypeMismatch> typeMismatches = new ArrayList<>();

    imported.forEach(
        (name, configuration) -> {
          Optional<Attribute<?>> target = targetAttributes.get(name);

          if (target.isEmpty()) {
            missing.add(new AttributeConfigurationSummary(name, configuration.getType()));
            return;
          }

          String targetType = typeName(target.get());

          if (!Objects.equals(configuration.getType(), targetType)) {
            typeMismatches.add(
                new AttributeConfigurationTypeMismatch(name, configuration.getType(), targetType));
            return;
          }

          importable.add(new AttributeConfigurationSummary(name, configuration.getType()));
        });

    boolean assetTypeMismatch = !Objects.equals(assetType.asText(), draft.getType());

    // Report what was skipped even when that leaves nothing to import
    if (importable.isEmpty()) {
      return new AttributeConfigurationImportPreview(
          NO_IMPORTABLE_ATTRIBUTES,
          assetType.asText(),
          draft.getType(),
          assetTypeMismatch,
          importable,
          missing,
          typeMismatches,
          null);
    }

    ObjectNode patchedAttributes = toObjectNode(targetAttributes);
    importable.forEach(
        attribute ->
            ((ObjectNode) patchedAttributes.get(attribute.getName()))
                .set("meta", imported.get(attribute.getName()).getMeta()));

    return new AttributeConfigurationImportPreview(
        null,
        assetType.asText(),
        draft.getType(),
        assetTypeMismatch,
        importable,
        missing,
        typeMismatches,
        patchedAttributes);
  }

  protected static boolean isAbsent(JsonNode node) {
    return node == null || node.isNull();
  }

  protected static String typeName(Attribute<?> attribute) {
    return attribute.getType() != null ? attribute.getType().getName() : null;
  }

  /**
   * Serializes through text rather than a token buffer, so that attribute values held as raw JSON
   * are materialized into the returned tree.
   */
  protected static ObjectNode toObjectNode(Object value) {
    try {
      return (ObjectNode) ValueUtil.JSON.readTree(ValueUtil.JSON.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize attribute configuration", e);
    }
  }
}
