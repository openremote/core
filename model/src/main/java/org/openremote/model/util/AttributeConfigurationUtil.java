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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AttributeConfiguration;
import org.openremote.model.asset.AttributeConfigurationDocument;
import org.openremote.model.asset.AttributeConfigurationGenericParameter;
import org.openremote.model.asset.AttributeConfigurationImportPreview;
import org.openremote.model.asset.AttributeConfigurationSummary;
import org.openremote.model.asset.AttributeConfigurationTypeMismatch;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeMap;
import org.openremote.model.value.ValueType;

/**
 * Reads and writes {@link AttributeConfigurationDocument}s. Callers are responsible for enforcing
 * realm authorization and for persisting, or discarding, the patched attributes.
 */
public final class AttributeConfigurationUtil {

  /** Separates the segments of a generic parameter path. */
  public static final String PATH_SEPARATOR = ".";

  /** Value descriptor names a generic parameter can have, which are those of JSON scalars. */
  public static final Set<String> GENERIC_PARAMETER_TYPES =
      Set.of(
          ValueType.TEXT.getName(),
          ValueType.BOOLEAN.getName(),
          ValueType.INTEGER.getName(),
          ValueType.LONG.getName(),
          ValueType.NUMBER.getName());

  protected static final String ATTRIBUTES_SEGMENT = "attributes";
  protected static final String META_SEGMENT = "meta";

  private AttributeConfigurationUtil() {}

  /**
   * Builds the export document of an asset, holding the type and metadata of every attribute that
   * has metadata. A null or empty selection exports all of them.
   */
  public static AttributeConfigurationDocument export(
      Asset<?> asset, Collection<String> attributeNames) {
    return export(asset, attributeNames, null);
  }

  /**
   * Builds the export document of an asset like {@link #export(Asset, Collection)}, leaving out the
   * values at the given generic paths. Each generic path is relative to an attribute, e.g. {@code
   * meta.agentLink.id}, and must be one of the {@link #findGenericPathCandidates} of the exported
   * attributes. Its value is recorded as a generic parameter instead, named by {@link
   * #deriveGenericParameterName}.
   *
   * @throws IllegalArgumentException if a generic path is not a candidate
   */
  public static AttributeConfigurationDocument export(
      Asset<?> asset, Collection<String> attributeNames, Collection<String> genericPaths) {
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

    Map<String, AttributeConfigurationGenericParameter> genericParameters =
        extractGenericParameters(attributes, genericPaths);

    return new AttributeConfigurationDocument(
        AttributeConfigurationDocument.VERSION,
        asset.getType(),
        genericParameters.isEmpty() ? null : genericParameters,
        attributes);
  }

  /**
   * Finds the metadata values that can become a generic parameter: text, boolean and number values
   * reached through object properties only, which are equal in every attribute holding them.
   * Returns their paths relative to an attribute, e.g. {@code meta.agentLink.id}, mapped to the
   * names of the attributes holding them.
   */
  public static Map<String, List<String>> findGenericPathCandidates(
      Map<String, AttributeConfiguration> attributes) {
    Map<String, List<String>> holders = new TreeMap<>();
    Map<String, JsonNode> values = new HashMap<>();
    Set<String> differing = new HashSet<>();

    new TreeMap<>(attributes)
        .forEach(
            (name, configuration) ->
                collectScalars(
                    configuration.getMeta(),
                    META_SEGMENT,
                    (path, value) -> {
                      holders.computeIfAbsent(path, key -> new ArrayList<>()).add(name);
                      JsonNode first = values.putIfAbsent(path, value);
                      if (first != null && !first.equals(value)) {
                        differing.add(path);
                      }
                    }));

    differing.forEach(holders::remove);
    return holders;
  }

  /**
   * Derives a generic parameter name from a path relative to an attribute by joining the segments
   * after {@code meta} in camel case, so {@code meta.agentLink.id} becomes {@code agentLinkId}.
   */
  public static String deriveGenericParameterName(String path) {
    String[] segments = splitPath(path);
    StringBuilder name = new StringBuilder(segments[1]);

    for (int i = 2; i < segments.length; i++) {
      name.append(Character.toUpperCase(segments[i].charAt(0))).append(segments[i].substring(1));
    }

    return name.toString();
  }

  /**
   * Validates a document against an asset draft and works out what importing it would do, without
   * changing the draft. The returned {@link
   * AttributeConfigurationImportPreview#getPatchedAttributes()} holds the draft attributes with the
   * metadata of every importable attribute replaced.
   */
  public static AttributeConfigurationImportPreview preview(Asset<?> draft, String document) {
    return preview(draft, document, null);
  }

  /**
   * Validates a document against an asset draft like {@link #preview(Asset, String)}, applying the
   * given generic parameter values to the imported metadata. Every generic parameter with a path in
   * an importable attribute needs a value of its type, which must also read as the value type of
   * the meta item it is applied to.
   */
  public static AttributeConfigurationImportPreview preview(
      Asset<?> draft, String document, Map<String, JsonNode> genericParameterValues) {
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
    JsonNode genericParameters = root.get("genericParameters");

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

    Map<String, AttributeConfigurationGenericParameter> declared = new LinkedHashMap<>();
    if (!isAbsent(genericParameters)) {
      if (!genericParameters.isObject()) {
        return AttributeConfigurationImportPreview.failed(MALFORMED_GENERIC_PARAMETER);
      }

      Set<String> declaredPaths = new HashSet<>();
      for (Map.Entry<String, JsonNode> entry : genericParameters.properties()) {
        AttributeConfigurationGenericParameter parameter =
            readGenericParameter(entry.getValue(), imported, declaredPaths);

        if (parameter == null) {
          return AttributeConfigurationImportPreview.failed(MALFORMED_GENERIC_PARAMETER);
        }

        declared.put(entry.getKey(), parameter);
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
          Map.of(),
          List.of(),
          null);
    }

    // Skipped attributes are not patched, so only parameters of importable ones need a value
    Set<String> importableNames =
        importable.stream().map(AttributeConfigurationSummary::getName).collect(Collectors.toSet());
    Map<String, AttributeConfigurationGenericParameter> required = new TreeMap<>();
    declared.forEach(
        (name, parameter) -> {
          List<String> paths =
              parameter.getPaths().stream()
                  .filter(path -> importableNames.contains(splitPath(path)[1]))
                  .toList();
          if (!paths.isEmpty()) {
            required.put(
                name, new AttributeConfigurationGenericParameter(parameter.getType(), paths));
          }
        });

    Map<String, JsonNode> values =
        genericParameterValues != null ? genericParameterValues : Map.of();

    if (required.keySet().stream().anyMatch(name -> isAbsent(values.get(name)))) {
      return new AttributeConfigurationImportPreview(
          GENERIC_PARAMETER_VALUES_REQUIRED,
          assetType.asText(),
          draft.getType(),
          assetTypeMismatch,
          importable,
          missing,
          typeMismatches,
          required,
          List.of(),
          null);
    }

    List<String> invalid =
        required.entrySet().stream()
            .filter(entry -> !isOfType(values.get(entry.getKey()), entry.getValue().getType()))
            .map(Map.Entry::getKey)
            .toList();

    if (invalid.isEmpty()) {
      required.forEach(
          (name, parameter) ->
              parameter
                  .getPaths()
                  .forEach(
                      path ->
                          resolveParent(imported, path)
                              .set(leafName(path), values.get(name).deepCopy())));
      invalid = findSchemaViolations(imported, required);
    }

    if (!invalid.isEmpty()) {
      return new AttributeConfigurationImportPreview(
          INVALID_GENERIC_PARAMETER_VALUE,
          assetType.asText(),
          draft.getType(),
          assetTypeMismatch,
          importable,
          missing,
          typeMismatches,
          required,
          invalid,
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
        required,
        List.of(),
        patchedAttributes);
  }

  /**
   * Removes the values at the generic paths from the attributes and records them as generic
   * parameters, keyed by a name that is unique within the document.
   */
  protected static Map<String, AttributeConfigurationGenericParameter> extractGenericParameters(
      Map<String, AttributeConfiguration> attributes, Collection<String> genericPaths) {
    Map<String, AttributeConfigurationGenericParameter> parameters = new TreeMap<>();

    if (genericPaths == null || genericPaths.isEmpty()) {
      return parameters;
    }

    // Find every candidate before removing any value, as the removal changes what is held
    Map<String, List<String>> candidates = findGenericPathCandidates(attributes);

    for (String path : new TreeSet<>(genericPaths)) {
      List<String> holders = candidates.get(path);

      if (holders == null) {
        throw new IllegalArgumentException(
            "Not a value that is equal in every exported attribute holding it: " + path);
      }

      String[] segments = splitPath(path);
      int leaf = segments.length - 1;
      String type = null;
      List<String> paths = new ArrayList<>();

      for (String holder : holders) {
        ObjectNode parent =
            (ObjectNode) resolve(attributes.get(holder).getMeta(), segments, 1, leaf);
        type = scalarType(parent.remove(segments[leaf]));
        paths.add(String.join(PATH_SEPARATOR, ATTRIBUTES_SEGMENT, holder, path));
      }

      String name = deriveGenericParameterName(path);
      String uniqueName = name;
      for (int i = 2; parameters.containsKey(uniqueName); i++) {
        uniqueName = name + i;
      }

      parameters.put(uniqueName, new AttributeConfigurationGenericParameter(type, paths));
    }

    return parameters;
  }

  /**
   * Reads a generic parameter declaration, or returns null if it is malformed. Each path must lead
   * to a value of the document that is absent or a scalar, and may only be declared once.
   */
  protected static AttributeConfigurationGenericParameter readGenericParameter(
      JsonNode node, Map<String, AttributeConfiguration> attributes, Set<String> declaredPaths) {
    JsonNode type = node.isObject() ? node.get("type") : null;
    JsonNode paths = node.isObject() ? node.get("paths") : null;

    if (type == null || !type.isTextual() || !GENERIC_PARAMETER_TYPES.contains(type.asText())) {
      return null;
    }
    if (paths == null || !paths.isArray() || paths.isEmpty()) {
      return null;
    }

    List<String> result = new ArrayList<>();
    for (JsonNode path : paths) {
      if (!path.isTextual() || !declaredPaths.add(path.asText())) {
        return null;
      }

      ObjectNode parent = resolveParent(attributes, path.asText());
      JsonNode value = parent != null ? parent.get(leafName(path.asText())) : null;

      if (parent == null || (value != null && value.isContainerNode())) {
        return null;
      }

      result.add(path.asText());
    }

    return new AttributeConfigurationGenericParameter(type.asText(), result);
  }

  /**
   * Names of the parameters applied to metadata that does not read as the value type of its meta
   * item descriptor. Metadata without a descriptor has no schema to check against.
   */
  protected static List<String> findSchemaViolations(
      Map<String, AttributeConfiguration> attributes,
      Map<String, AttributeConfigurationGenericParameter> parameters) {
    Set<String> invalid = new TreeSet<>();

    parameters.forEach(
        (name, parameter) ->
            parameter
                .getPaths()
                .forEach(
                    path -> {
                      String[] segments = splitPath(path);
                      JsonNode metaValue = attributes.get(segments[1]).getMeta().get(segments[3]);

                      ValueUtil.getMetaItemDescriptor(segments[3])
                          .ifPresent(
                              descriptor -> {
                                try {
                                  ValueUtil.JSON.treeToValue(
                                      metaValue, descriptor.getType().getType());
                                } catch (JsonProcessingException | IllegalArgumentException e) {
                                  invalid.add(name);
                                }
                              });
                    }));

    return new ArrayList<>(invalid);
  }

  /**
   * The object holding the value at a document path of the form {@code
   * attributes.<attribute>.meta.<meta item>[.<property>...]}, or null if the path has another form
   * or does not lead through objects of the given attributes.
   */
  protected static ObjectNode resolveParent(
      Map<String, AttributeConfiguration> attributes, String path) {
    String[] segments = splitPath(path);

    if (segments.length < 4
        || !ATTRIBUTES_SEGMENT.equals(segments[0])
        || !META_SEGMENT.equals(segments[2])
        || Arrays.stream(segments).anyMatch(String::isEmpty)
        || !attributes.containsKey(segments[1])) {
      return null;
    }

    JsonNode parent =
        resolve(attributes.get(segments[1]).getMeta(), segments, 3, segments.length - 1);
    return parent != null && parent.isObject() ? (ObjectNode) parent : null;
  }

  /**
   * The node reached through the object properties named by the segments from the first index up to
   * the second, or null.
   */
  protected static JsonNode resolve(JsonNode node, String[] segments, int from, int to) {
    for (int i = from; i < to && node != null; i++) {
      node = node.isObject() ? node.get(segments[i]) : null;
    }
    return node;
  }

  /**
   * Calls the consumer with the path and value of each scalar reached through object properties.
   */
  protected static void collectScalars(
      JsonNode node, String path, BiConsumer<String, JsonNode> consumer) {
    if (node != null && node.isObject()) {
      node.properties().stream()
          .filter(property -> !property.getKey().isEmpty())
          .filter(property -> !property.getKey().contains(PATH_SEPARATOR))
          .forEach(
              property ->
                  collectScalars(
                      property.getValue(), path + PATH_SEPARATOR + property.getKey(), consumer));
    } else if (scalarType(node) != null) {
      consumer.accept(path, node);
    }
  }

  /** The generic parameter type of a JSON scalar, or null if the node is not one. */
  protected static String scalarType(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual()) {
      return ValueType.TEXT.getName();
    }
    if (node.isBoolean()) {
      return ValueType.BOOLEAN.getName();
    }
    if (node.isIntegralNumber() && node.canConvertToInt()) {
      return ValueType.INTEGER.getName();
    }
    if (node.isIntegralNumber() && node.canConvertToLong()) {
      return ValueType.LONG.getName();
    }
    if (node.isNumber()) {
      return ValueType.NUMBER.getName();
    }
    return null;
  }

  /** Whether a value fits a generic parameter type, a wider number type taking narrower ones. */
  protected static boolean isOfType(JsonNode value, String type) {
    if (value == null) {
      return false;
    }
    if (ValueType.NUMBER.getName().equals(type)) {
      return value.isNumber();
    }
    if (ValueType.LONG.getName().equals(type)) {
      return value.isIntegralNumber() && value.canConvertToLong();
    }
    return Objects.equals(type, scalarType(value));
  }

  protected static String[] splitPath(String path) {
    return path.split(Pattern.quote(PATH_SEPARATOR), -1);
  }

  protected static String leafName(String path) {
    String[] segments = splitPath(path);
    return segments[segments.length - 1];
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
