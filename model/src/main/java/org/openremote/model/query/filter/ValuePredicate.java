/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.model.query.filter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.hibernate.internal.util.SerializationHelper;
import org.hibernate.type.SerializationException;
import org.openremote.model.util.ValueUtil;

@JsonSubTypes({
  @JsonSubTypes.Type(value = StringPredicate.class, name = StringPredicate.name),
  @JsonSubTypes.Type(value = BooleanPredicate.class, name = BooleanPredicate.name),
  @JsonSubTypes.Type(value = DateTimePredicate.class, name = DateTimePredicate.name),
  @JsonSubTypes.Type(value = NumberPredicate.class, name = NumberPredicate.name),
  @JsonSubTypes.Type(value = RadialGeofencePredicate.class, name = RadialGeofencePredicate.name),
  @JsonSubTypes.Type(
      value = RectangularGeofencePredicate.class,
      name = RectangularGeofencePredicate.name),
  @JsonSubTypes.Type(value = GeoJSONGeofencePredicate.class, name = GeoJSONGeofencePredicate.name),
  @JsonSubTypes.Type(value = ArrayPredicate.class, name = ArrayPredicate.name),
  @JsonSubTypes.Type(value = ValueAnyPredicate.class, name = ValueAnyPredicate.name),
  @JsonSubTypes.Type(value = ValueEmptyPredicate.class, name = ValueEmptyPredicate.name),
  @JsonSubTypes.Type(value = CalendarEventPredicate.class, name = CalendarEventPredicate.name)
})
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "predicateType")
public abstract class ValuePredicate implements Serializable {

  /**
   * Copies a predicate and its mutable values, retaining their types and null/empty values where
   * possible. Array payloads that cannot be reconstructed use an independent JSON snapshot for
   * database filtering; such snapshots do not retain Java object equality for in-memory predicates.
   */
  public static ValuePredicate copy(ValuePredicate source) {
    return switch (source) {
      case null -> null;
      case StringPredicate p when p.getClass() == StringPredicate.class ->
          new StringPredicate(p.match, p.caseSensitive, p.value).negate(p.negate);
      case BooleanPredicate p when p.getClass() == BooleanPredicate.class ->
          new BooleanPredicate(p.value);
      case NumberPredicate p when p.getClass() == NumberPredicate.class -> {
        NumberPredicate result = new NumberPredicate((Number) copyValue(p.value), p.operator);
        result.rangeValue = (Number) copyValue(p.rangeValue);
        result.negate = p.negate;
        yield result;
      }
      case DateTimePredicate p when p.getClass() == DateTimePredicate.class -> {
        DateTimePredicate result = new DateTimePredicate(p.operator, p.value);
        result.rangeValue = p.rangeValue;
        result.negate = p.negate;
        yield result;
      }
      case ArrayPredicate p when p.getClass() == ArrayPredicate.class ->
          new ArrayPredicate(
              copyArrayValue(p.value),
              p.index,
              p.lengthEquals,
              p.lengthGreaterThan,
              p.lengthLessThan,
              p.negated);
      case CalendarEventPredicate p when p.getClass() == CalendarEventPredicate.class ->
          new CalendarEventPredicate(p.timestamp == null ? null : (Date) p.timestamp.clone());
      case RadialGeofencePredicate p when p.getClass() == RadialGeofencePredicate.class ->
          new RadialGeofencePredicate(p.radius, p.lat, p.lng, p.negated);
      case RectangularGeofencePredicate p when p.getClass() == RectangularGeofencePredicate.class ->
          new RectangularGeofencePredicate(p.latMin, p.lngMin, p.latMax, p.lngMax, p.negated);
      case GeoJSONGeofencePredicate p when p.getClass() == GeoJSONGeofencePredicate.class ->
          new GeoJSONGeofencePredicate(p.geoJSON, p.negated);
      case ValueEmptyPredicate p when p.getClass() == ValueEmptyPredicate.class ->
          new ValueEmptyPredicate().negate(p.negate);
      case ValueAnyPredicate p when p.getClass() == ValueAnyPredicate.class ->
          new ValueAnyPredicate();
      // ValuePredicate's Serializable contract also allows custom predicate implementations.
      default -> (ValuePredicate) SerializationHelper.clone(source);
    };
  }

  private static Object copyValue(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Boolean
        || value instanceof Character
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof BigInteger
        || value instanceof BigDecimal
        || value instanceof Enum<?>) {
      return value;
    }
    if (value instanceof JsonNode node) return node.deepCopy();
    if (value instanceof Date date) return date.clone();
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      Object result = Array.newInstance(value.getClass().getComponentType(), length);
      if (value.getClass().getComponentType().isPrimitive()) {
        System.arraycopy(value, 0, result, 0, length);
      } else {
        for (int i = 0; i < length; i++) Array.set(result, i, copyValue(Array.get(value, i)));
      }
      return result;
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> result = new LinkedHashMap<>();
      map.forEach((key, item) -> result.put(copyValue(key), copyValue(item)));
      return result;
    }
    if (value instanceof List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      list.forEach(item -> result.add(copyValue(item)));
      return result;
    }
    if (value instanceof Set<?> set) {
      Set<Object> result = new LinkedHashSet<>();
      set.forEach(item -> result.add(copyValue(item)));
      return result;
    }
    if (value instanceof Serializable serializable) {
      try {
        Object result = SerializationHelper.clone(serializable);
        if (hasSameDatabaseValue(value, result)) return result;
      } catch (SerializationException | IOException e) {
        // A Serializable payload may need Jackson to preserve its database representation.
      }
    }
    try {
      Object result =
          PayloadMapper.JSON.readValue(
              PayloadMapper.JSON.writeValueAsBytes(value), value.getClass());
      // Deserializers can silently discard properties. Never change the database filter that way.
      if (hasSameDatabaseValue(value, result)) return result;
      throw new IllegalArgumentException(
          "Copy changed predicate value of type: " + value.getClass());
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Cannot copy predicate value of type: " + value.getClass().getName(), e);
    }
  }

  private static Object copyArrayValue(Object value) {
    try {
      return copyValue(value);
    } catch (IllegalArgumentException e) {
      // Storage accepts JSON-serializable values even without a matching deserializer. Snapshot the
      // whole payload so nested values and typed arrays do not require incompatible element types.
      try {
        return databaseJson(value);
      } catch (IOException snapshotFailure) {
        snapshotFailure.addSuppressed(e);
        throw new IllegalArgumentException(
            "Cannot copy array predicate value of type: " + value.getClass().getName(),
            snapshotFailure);
      }
    }
  }

  private static JsonNode databaseJson(Object value) throws IOException {
    // Use storage's serialization settings, preserving precision when reading the snapshot.
    return ValueUtil.JSON
        .reader()
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .readTree(ValueUtil.JSON.writeValueAsBytes(value));
  }

  private static boolean hasSameDatabaseValue(Object original, Object copy) throws IOException {
    // Java serialization can lose transient state; Jackson deserializers can discard properties.
    return copy != null && databaseJson(original).equals(databaseJson(copy));
  }

  private static class PayloadMapper {
    // Initialize lazily: the normal explicit copies do not need Jackson or ValueUtil
    // initialization.
    private static final ObjectMapper JSON = createMapper();

    private static ObjectMapper createMapper() {
      ObjectMapper mapper =
          ValueUtil.JSON
              .copy()
              .setSerializationInclusion(JsonInclude.Include.ALWAYS)
              .enable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS)
              .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
      mapper
          .configOverride(Map.class)
          .setInclude(
              JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS));
      return mapper;
    }
  }

  public static Predicate<Object> asPredicateOrTrue(
      Supplier<Long> currentMillisSupplier, ValuePredicate valuePredicate) {
    return obj ->
        Optional.ofNullable(valuePredicate)
            .map(vp -> vp.asPredicate(currentMillisSupplier).test(obj))
            .orElse(true);
  }

  public abstract Predicate<Object> asPredicate(Supplier<Long> currentMillisSupplier);
}
