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

import static org.openremote.model.asset.ConfigurationTemplateImportFailure.*;
import static org.openremote.model.value.MetaItemType.*;

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
import java.util.Set;
import java.util.TreeMap;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AttributeConfiguration;
import org.openremote.model.asset.AttributeConfigurationSummary;
import org.openremote.model.asset.AttributeConfigurationTypeMismatch;
import org.openremote.model.asset.ConfigurationTemplate;
import org.openremote.model.asset.ConfigurationTemplateImportPreview;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeMap;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.attribute.MetaMap;

/**
 * Reads and writes {@link ConfigurationTemplate}s. Callers are responsible for enforcing realm
 * authorization and for persisting, or discarding, the patched attributes.
 */
public final class ConfigurationTemplateUtil {

  /**
   * Metadata that stays with an asset rather than travel in a document, as it grants access or
   * links attributes by asset id. Export leaves it out and import keeps that of the target.
   */
  public static final Set<String> ASSET_BOUND_META_ITEMS =
      Set.of(
          ACCESS_PUBLIC_READ.getName(),
          ACCESS_PUBLIC_WRITE.getName(),
          ACCESS_RESTRICTED_READ.getName(),
          ACCESS_RESTRICTED_WRITE.getName(),
          ATTRIBUTE_LINKS.getName());

  private ConfigurationTemplateUtil() {}

  /**
   * Builds the export document of an asset, holding the type and metadata of every attribute that
   * has metadata other than {@link #ASSET_BOUND_META_ITEMS}. A null or empty selection exports all
   * of them.
   */
  public static ConfigurationTemplate export(Asset<?> asset, Collection<String> attributeNames) {
    boolean selectAll = attributeNames == null || attributeNames.isEmpty();
    Map<String, AttributeConfiguration> attributes = new TreeMap<>();

    if (asset.getAttributes() != null) {
      asset.getAttributes().values().stream()
          .filter(attribute -> selectAll || attributeNames.contains(attribute.getName()))
          .forEach(
              attribute -> {
                MetaMap meta = withoutAssetBoundMeta(attribute.getMeta());

                if (!meta.isEmpty()) {
                  attributes.put(
                      attribute.getName(), new AttributeConfiguration(typeName(attribute), meta));
                }
              });
    }

    return new ConfigurationTemplate(
        ConfigurationTemplate.CURRENT_VERSION, asset.getType(), attributes);
  }

  /**
   * Validates a document against an asset draft and works out what importing it would do, without
   * changing the draft. The returned {@link
   * ConfigurationTemplateImportPreview#getPatchedAttributes()} holds the draft attributes with the
   * metadata of every importable attribute replaced, except for {@link #ASSET_BOUND_META_ITEMS},
   * which keep the values of the draft.
   *
   * <p>The document is read as a JSON tree, as binding it cannot tell a malformed entry from an
   * invalid metadata item.
   */
  public static ConfigurationTemplateImportPreview preview(Asset<?> draft, String document) {
    JsonNode root;

    try {
      root = ValueUtil.JSON.readTree(document);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return ConfigurationTemplateImportPreview.failed(INVALID_JSON);
    }

    if (root == null || !root.isObject()) {
      return ConfigurationTemplateImportPreview.failed(INVALID_JSON);
    }

    JsonNode version = root.get("version");
    JsonNode assetType = root.get("assetType");
    JsonNode attributes = root.get("attributes");

    if (isAbsent(version) || isAbsent(assetType) || isAbsent(attributes)) {
      return ConfigurationTemplateImportPreview.failed(MISSING_REQUIRED_FIELD);
    }
    if (!version.isIntegralNumber() || version.asInt() != ConfigurationTemplate.CURRENT_VERSION) {
      return ConfigurationTemplateImportPreview.failed(UNSUPPORTED_VERSION);
    }
    if (!assetType.isTextual() || assetType.asText().isBlank()) {
      return ConfigurationTemplateImportPreview.failed(MISSING_REQUIRED_FIELD);
    }
    if (!attributes.isObject()) {
      return ConfigurationTemplateImportPreview.failed(MALFORMED_ATTRIBUTE_ENTRY);
    }

    // The map key is the attribute name, so an entry's own name field, if any, is ignored
    Map<String, AttributeConfiguration> imported = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : attributes.properties()) {
      JsonNode type = entry.getValue().isObject() ? entry.getValue().get("type") : null;
      JsonNode meta = entry.getValue().isObject() ? entry.getValue().get("meta") : null;

      if (type == null || !type.isTextual() || type.asText().isBlank()) {
        return ConfigurationTemplateImportPreview.failed(MALFORMED_ATTRIBUTE_ENTRY);
      }
      if (meta == null || !meta.isObject()) {
        return ConfigurationTemplateImportPreview.failed(MALFORMED_ATTRIBUTE_ENTRY);
      }

      Optional<MetaMap> importedMeta = readMeta((ObjectNode) meta);

      if (importedMeta.isEmpty()) {
        return ConfigurationTemplateImportPreview.failed(INVALID_META_ITEM);
      }

      imported.put(entry.getKey(), new AttributeConfiguration(type.asText(), importedMeta.get()));
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

    // Report what was skipped even when that leaves nothing to import
    if (importable.isEmpty()) {
      return new ConfigurationTemplateImportPreview(
          NO_IMPORTABLE_ATTRIBUTES, importable, missing, typeMismatches, null);
    }

    AttributeMap patchedAttributes = ValueUtil.clone(targetAttributes);
    importable.forEach(
        summary -> {
          Attribute<?> patched = patchedAttributes.get(summary.getName()).orElseThrow();
          MetaMap meta = imported.get(summary.getName()).getMeta();

          if (patched.getMeta() != null) {
            ASSET_BOUND_META_ITEMS.forEach(
                name -> patched.getMeta().get(name).ifPresent(item -> meta.addOrReplace(item)));
          }
          patched.setMeta(meta);
        });

    return new ConfigurationTemplateImportPreview(
        null, importable, missing, typeMismatches, patchedAttributes);
  }

  private static boolean isAbsent(JsonNode node) {
    return node == null || node.isNull();
  }

  private static MetaMap withoutAssetBoundMeta(MetaMap meta) {
    MetaMap result = meta != null ? new MetaMap(meta) : new MetaMap();
    ASSET_BOUND_META_ITEMS.forEach(result::remove);
    return result;
  }

  /**
   * Reads the metadata of a document entry without the {@link #ASSET_BOUND_META_ITEMS}, or nothing
   * if an item with a known descriptor does not hold a value of its type that meets the bean
   * constraints of that type. Unknown metadata items are taken as they are.
   */
  private static Optional<MetaMap> readMeta(ObjectNode node) {
    ObjectNode items = node.deepCopy();
    items.remove(ASSET_BOUND_META_ITEMS);
    MetaMap meta;

    try {
      meta = ValueUtil.JSON.treeToValue(items, MetaMap.class);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return Optional.empty();
    }

    for (MetaItem<?> item : meta.values()) {
      Object value = item.getValue().orElse(null);

      if (item.getType() != null
          && value != null
          && (!ValueUtil.validate(value).isEmpty()
              || changesType(items.get(item.getName()), value))) {
        return Optional.empty();
      }
    }
    return Optional.of(meta);
  }

  /**
   * Whether a value that names its subtype was read as another one, as happens to an agent link of
   * a type that is not installed, which falls back to the default agent link.
   */
  private static boolean changesType(JsonNode node, Object value) {
    JsonNode type = node.get("type");

    if (type == null || !type.isTextual()) {
      return false;
    }
    JsonNode readType = ValueUtil.JSON.valueToTree(value).get("type");
    return readType != null && !type.asText().equals(readType.asText());
  }

  private static String typeName(Attribute<?> attribute) {
    return attribute.getType() != null ? attribute.getType().getName() : null;
  }
}
