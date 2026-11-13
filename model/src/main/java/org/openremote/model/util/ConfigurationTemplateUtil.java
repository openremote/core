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
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AttributeConfiguration;
import org.openremote.model.asset.AttributeConfigurationSummary;
import org.openremote.model.asset.AttributeConfigurationTypeMismatch;
import org.openremote.model.asset.ConfigurationTemplate;
import org.openremote.model.asset.ConfigurationTemplateImportFailure;
import org.openremote.model.asset.ConfigurationTemplateImportFailureLocation;
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

  /** A violation path on an attribute: its name, then the metadata item and the rest, if any. */
  private static final Pattern ATTRIBUTE_VIOLATION_PATH =
      Pattern.compile("attributes\\[([^\\]]+)\\](?:\\.meta\\[([^\\]]+)\\])?(?:\\.(.*))?");

  private ConfigurationTemplateUtil() {}

  /**
   * Builds the export document of an asset, holding the type and metadata of every attribute that
   * has metadata other than {@link #ASSET_BOUND_META_ITEMS}. A null or empty selection exports all
   * of them.
   */
  public static ConfigurationTemplate export(Asset<?> asset, Collection<String> attributeNames) {
    boolean selectAll = attributeNames == null || attributeNames.isEmpty();
    // Sorted by name, as the attributes of an asset have no order of their own
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
   * which keep the values of the draft. A document that the patched draft would fail validation on
   * when saved is rejected too.
   *
   * <p>The document is read as a JSON tree, so that its fields are checked as written: binding
   * reads a version of 1.5 as 1, and an agent link of a type that is not installed as a default
   * agent link.
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

    for (String field : List.of("version", "assetType", "attributes")) {
      if (isAbsent(root.get(field))) {
        return failed(MISSING_REQUIRED_FIELD, null, null, field);
      }
    }

    JsonNode version = root.get("version");
    JsonNode assetType = root.get("assetType");
    JsonNode attributes = root.get("attributes");

    if (!version.isIntegralNumber() || version.asInt() != ConfigurationTemplate.CURRENT_VERSION) {
      return ConfigurationTemplateImportPreview.failed(UNSUPPORTED_VERSION);
    }
    if (!assetType.isTextual() || assetType.asText().isBlank()) {
      return failed(MISSING_REQUIRED_FIELD, null, null, "assetType");
    }
    if (!attributes.isObject()) {
      return failed(MALFORMED_ATTRIBUTE_ENTRY, null, null, "attributes");
    }

    // The map key is the attribute name, so an entry's own name field, if any, is ignored
    Map<String, AttributeConfiguration> imported = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : attributes.properties()) {
      String attribute = entry.getKey();

      if (!entry.getValue().isObject()) {
        return failed(MALFORMED_ATTRIBUTE_ENTRY, attribute, null, null);
      }

      JsonNode type = entry.getValue().get("type");
      JsonNode meta = entry.getValue().get("meta");

      if (type == null || !type.isTextual() || type.asText().isBlank()) {
        return failed(MALFORMED_ATTRIBUTE_ENTRY, attribute, null, "type");
      }
      if (meta == null || !meta.isObject()) {
        return failed(MALFORMED_ATTRIBUTE_ENTRY, attribute, null, "meta");
      }

      try {
        imported.put(attribute, new AttributeConfiguration(type.asText(), readMeta(meta)));
      } catch (InvalidMetaItemException e) {
        return failed(INVALID_META_ITEM, attribute, e.metaItem, e.parameter);
      }
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
          NO_IMPORTABLE_ATTRIBUTES, null, importable, missing, typeMismatches, null);
    }

    Asset<?> patchedDraft = ValueUtil.clone(draft);
    importable.forEach(
        summary -> {
          Attribute<?> patched = patchedDraft.getAttributes().get(summary.getName()).orElseThrow();
          MetaMap meta = imported.get(summary.getName()).getMeta();

          if (patched.getMeta() != null) {
            ASSET_BOUND_META_ITEMS.forEach(
                name -> patched.getMeta().get(name).ifPresent(item -> meta.addOrReplace(item)));
          }
          patched.setMeta(meta);
        });

    Set<String> importableNames =
        importable.stream().map(AttributeConfigurationSummary::getName).collect(Collectors.toSet());
    Optional<ConfigurationTemplateImportPreview> rejected =
        rejectedOnSave(draft, patchedDraft, importableNames);

    return rejected.orElseGet(
        () ->
            new ConfigurationTemplateImportPreview(
                null, null, importable, missing, typeMismatches, patchedDraft.getAttributes()));
  }

  /**
   * The rejection for a violation that patching adds to an importable attribute, as saving the
   * patched draft would fail on it. Violations the draft already has are not the document's.
   */
  private static Optional<ConfigurationTemplateImportPreview> rejectedOnSave(
      Asset<?> draft, Asset<?> patchedDraft, Set<String> importableNames) {
    Set<String> existing =
        ValueUtil.validate(draft).stream()
            .map(ConfigurationTemplateUtil::describe)
            .collect(Collectors.toSet());

    return ValueUtil.validate(patchedDraft).stream()
        .filter(violation -> !existing.contains(describe(violation)))
        .map(violation -> violation.getPropertyPath().toString())
        .sorted()
        .map(ATTRIBUTE_VIOLATION_PATH::matcher)
        .filter(matcher -> matcher.matches() && importableNames.contains(matcher.group(1)))
        .findFirst()
        .map(
            matcher ->
                matcher.group(2) != null
                    ? failed(
                        INVALID_META_ITEM,
                        matcher.group(1),
                        matcher.group(2),
                        valueParameter(matcher.group(3)))
                    : failed(VALUE_VIOLATES_CONSTRAINTS, matcher.group(1), null, null));
  }

  private static String describe(ConstraintViolation<?> violation) {
    return violation.getPropertyPath() + " " + violation.getMessageTemplate();
  }

  private static ConfigurationTemplateImportPreview failed(
      ConfigurationTemplateImportFailure failure,
      String attribute,
      String metaItem,
      String parameter) {
    return ConfigurationTemplateImportPreview.failed(
        failure, new ConfigurationTemplateImportFailureLocation(attribute, metaItem, parameter));
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
   * Reads the metadata of a document entry without the {@link #ASSET_BOUND_META_ITEMS}. Each item
   * must pass bean validation as it does when an asset is saved, so an item with a known descriptor
   * must hold a value of its type that meets the constraints of that type.
   */
  private static MetaMap readMeta(JsonNode node) throws InvalidMetaItemException {
    ObjectNode items = node.deepCopy();
    items.remove(ASSET_BOUND_META_ITEMS);
    MetaMap meta;

    try {
      meta = ValueUtil.JSON.treeToValue(items, MetaMap.class);
    } catch (JsonMappingException e) {
      // The path starts at the item, the rest lies within its value
      List<JsonMappingException.Reference> path = e.getPath();
      throw new InvalidMetaItemException(
          path.isEmpty() ? null : path.get(0).getFieldName(),
          path.isEmpty() ? null : path(path.subList(1, path.size())));
    } catch (JsonProcessingException | IllegalArgumentException e) {
      throw new InvalidMetaItemException(null, null);
    }

    for (MetaItem<?> item : meta.values()) {
      Optional<String> violation =
          ValueUtil.validate(item).stream()
              .map(constraintViolation -> constraintViolation.getPropertyPath().toString())
              .sorted()
              .findFirst();

      if (violation.isPresent()) {
        throw new InvalidMetaItemException(item.getName(), valueParameter(violation.get()));
      }

      Object value = item.getValue().orElse(null);

      if (item.getType() != null
          && value != null
          && changesType(items.get(item.getName()), value)) {
        throw new InvalidMetaItemException(item.getName(), "type");
      }
    }
    return meta;
  }

  /**
   * The parameter that the violation path of a metadata item names within its value, e.g. pastCount
   * for value.pastCount; null when the path names the item or its value as a whole.
   */
  private static String valueParameter(String path) {
    if (path != null && path.startsWith("value.")) {
      return path.substring("value.".length());
    }
    if (path != null && path.startsWith("value[")) {
      return path.substring("value".length());
    }
    return null;
  }

  /** The path of a binding failure within the value read, or null for the value itself. */
  private static String path(List<JsonMappingException.Reference> references) {
    StringBuilder path = new StringBuilder();

    for (JsonMappingException.Reference reference : references) {
      if (reference.getFieldName() != null) {
        path.append(path.isEmpty() ? "" : ".").append(reference.getFieldName());
      } else if (reference.getIndex() >= 0) {
        path.append('[').append(reference.getIndex()).append(']');
      }
    }
    return path.isEmpty() ? null : path.toString();
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

  /** A metadata item that cannot be read, with the parameter within it where that is known. */
  private static final class InvalidMetaItemException extends Exception {
    private final String metaItem;
    private final String parameter;

    private InvalidMetaItemException(String metaItem, String parameter) {
      super(null, null, false, false);
      this.metaItem = metaItem;
      this.parameter = parameter;
    }
  }
}
