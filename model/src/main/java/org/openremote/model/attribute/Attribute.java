/*
 * Copyright 2020, OpenRemote Inc.
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
package org.openremote.model.attribute;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.type.TypeFactory;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nonnull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetTypeInfo;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.*;

/** Stores a named value with associated {@link MetaItem}s. */
@JsonDeserialize(using = Attribute.AttributeDeserializer.class)
@JsonSerialize(using = Attribute.AttributeSerializer.class)
@Schema(
    description =
        "Named typed asset value. Attribute JSON includes name, type, value, optional Unix-millisecond timestamp, and optional metadata.")
public class Attribute<T> extends AbstractNameValueHolder<T> implements MetaHolder {

  /**
   * Custom deserializer that can use asset type info from deserialization context when it is passed
   * in from the asset deserializer
   */
  public static class AttributeDeserializer extends StdDeserializer<Attribute<?>> {

    protected static final JavaType META_MAP_TYPE =
        TypeFactory.defaultInstance().constructType(MetaMap.class);
    protected static final JavaType OBJECT_TYPE =
        TypeFactory.defaultInstance().constructType(Object.class);
    public static final System.Logger LOG =
        System.getLogger(
            AttributeDeserializer.class.getName() + "." + SyslogCategory.MODEL_AND_VALUES);
    protected static JsonDeserializer<Object> metaDeserialiser = null;

    protected AttributeDeserializer() {
      super(Attribute.class);
    }

    public static Object deserialiseValue(
        ValueDescriptor<?> valueDescriptor, JsonParser jp, DeserializationContext ctxt)
        throws IOException {
      JsonDeserializer<Object> valueTypeDeserializer;
      if (valueDescriptor != null) {
        valueTypeDeserializer =
            ctxt.findRootValueDeserializer(
                TypeFactory.defaultInstance().constructType(valueDescriptor.getType()));
      } else {
        valueTypeDeserializer = ctxt.findRootValueDeserializer(OBJECT_TYPE);
      }
      return valueTypeDeserializer.deserialize(jp, ctxt);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Attribute<?> deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
      if (!jp.isExpectedStartObjectToken()) {
        throw new InvalidFormatException(
            jp, "Attribute must be an object", jp.nextValue(), Attribute.class);
      }

      AssetTypeInfo assetTypeInfo =
          (AssetTypeInfo) ctxt.getAttribute(Asset.AssetDeserializer.ASSET_TYPE_INFO_ATTRIBUTE);
      String attributeName = jp.currentName();
      AttributeDescriptor<?> attributeDescriptor =
          assetTypeInfo != null ? assetTypeInfo.getAttributeDescriptors().get(attributeName) : null;
      Attribute<?> attribute;

      if (attributeDescriptor != null) {
        attribute = new Attribute<>(attributeName, attributeDescriptor.getType());
      } else {
        attribute = new Attribute<>(attributeName);
      }

      while (jp.nextToken() != JsonToken.END_OBJECT) {
        String propName = jp.currentName();
        if (jp.currentToken() == JsonToken.FIELD_NAME) {
          jp.nextToken();
        }
        if (jp.currentToken() == JsonToken.VALUE_NULL) {
          continue;
        }
        switch (propName) {
          case "type" -> {
            // Attribute descriptor value type takes priority
            if (attributeDescriptor == null) {
              String valueType = jp.getValueAsString();
              // Try and find matching value descriptor
              ValueUtil.getValueDescriptor(valueType)
                  .ifPresent(vd -> ((Attribute) attribute).setTypeInternal(vd));
            }
          }
          case "meta" -> {
            // findNonContextualValueDeserializer is slow so caching the result to speed up
            // deserialisation
            if (metaDeserialiser == null) {
              metaDeserialiser = ctxt.findNonContextualValueDeserializer(META_MAP_TYPE);
            }
            try {
              attribute.meta = (MetaMap) metaDeserialiser.deserialize(jp, ctxt);
            } catch (JsonMappingException e) {
              throw JsonMappingException.wrapWithPath(e, Attribute.class, "meta");
            }
          }
          case "name" -> {
            String name = jp.getValueAsString();
            if (!name.equals(attribute.name)) {
              throw new JsonParseException("Attribute name doesn't match attribute map key");
            }
          }
          case "timestamp" -> attribute.timestamp = jp.getValueAsLong();
          case "value" -> {
            //                        // Can only deserialise value once we know its' type
            //                        if (!typeFound) {
            //                            valueBuffer = new TokenBuffer(jp, ctxt);
            //                            valueBuffer.copyCurrentStructure(jp);
            //                            continue;
            //                        }
            ValueDescriptor<?> valueDescriptor = attribute.getType();
            if (valueDescriptor == null) {
              // We don't know the type so store the value as a string and hydrate on demand when
              // value
              // type may be known (this occurs when hydrating assets from the DB)
              attribute.valueStr = jp.getCodec().readTree(jp).toString();
            } else {
              try {
                ((Attribute) attribute).value = deserialiseValue(valueDescriptor, jp, ctxt);
              } catch (Exception ignored) {
              }
            }
          }
        }
      }

      //            if (valueBuffer != null) {
      //                if (!typeFound) {
      //                    throw new JsonParseException("Asset type is missing");
      //                }
      //
      //                attribute.value = deserialiseValue(attribute.getType(),
      // valueBuffer.asParser(), ctxt);
      //            }
      return attribute;
    }
  }

  /**
   * Custom serializer that can serialize valueStr (for attributes that haven't been fully hydrated)
   */
  @SuppressWarnings("rawtypes")
  public static class AttributeSerializer extends StdSerializer<Attribute> {

    public AttributeSerializer() {
      super(Attribute.class);
    }

    @Override
    public void serialize(Attribute value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartObject();
      gen.writeFieldName("name");
      gen.writeString(value.getName());
      if (value.getType() != null) {
        gen.writeFieldName("type");
        gen.writeString(value.getType().getName());
      }
      provider.defaultSerializeField("meta", value.meta, gen);
      if (value.valueStr != null) {
        gen.writeFieldName("value");
        gen.writeRawValue(value.valueStr);
      } else {
        provider.defaultSerializeField("value", value.value, gen);
      }
      if (value.timestamp > 0) {
        gen.writeFieldName("timestamp");
        gen.writeNumber(value.timestamp);
      }
      gen.writeEndObject();
    }
  }

  @JsonIgnore
  @Valid @Schema(description = "Optional behavior and integration metadata keyed by meta-item name.")
  protected MetaMap meta;

  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  @Schema(
      description =
          "Value timestamp in Unix milliseconds; zero means no explicit timestamp was supplied.",
      example = "1767225600000")
  protected long timestamp;

  Attribute() {}

  public Attribute(AttributeDescriptor<T> attributeDescriptor) {
    this(attributeDescriptor, null);
  }

  public Attribute(AttributeDescriptor<T> attributeDescriptor, T value) {
    super(attributeDescriptor.getName(), attributeDescriptor.getType(), value);

    // TODO: We should not do this attribute descriptor meta should be read from the descriptor even
    // for DB queries
    // Auto merge meta from attribute descriptor
    if (attributeDescriptor.getMeta() != null) {
      getMeta().addOrReplace(attributeDescriptor.getMeta());
    }
  }

  public Attribute(AttributeDescriptor<T> attributeDescriptor, T value, long timestamp) {
    this(attributeDescriptor, value);
    setTimestamp(timestamp);
  }

  @SuppressWarnings("unchecked")
  public Attribute(String name, ValueDescriptor<?> valueDescriptor) {
    this(name, (ValueDescriptor<T>) valueDescriptor, null);
  }

  public Attribute(String name, ValueDescriptor<T> valueDescriptor, T value) {
    super(name, valueDescriptor, value);
  }

  public Attribute(String name, ValueDescriptor<T> valueDescriptor, T value, long timestamp) {
    super(name, valueDescriptor, value);
    setTimestamp(timestamp);
  }

  public Attribute(String name) {
    this(name, null);
  }

  /**
   * @return All attributes that exist only in the new list or are different than any attribute in
   *     the old list.
   */
  public static Stream<Attribute<?>> getAddedOrModifiedAttributes(
      Collection<Attribute<?>> oldAttributes, Collection<Attribute<?>> newAttributes) {
    return getAddedOrModifiedAttributes(oldAttributes, newAttributes, null);
  }

  /**
   * @return All attributes that exist only in the new list or are different than any attribute in
   *     the old list.
   */
  public static Stream<Attribute<?>> getAddedOrModifiedAttributes(
      Collection<Attribute<?>> oldAttributes,
      Collection<Attribute<?>> newAttributes,
      Predicate<String> ignoredAttributeNames) {
    return getAddedOrModifiedAttributes(oldAttributes, newAttributes, null, ignoredAttributeNames);
  }

  /**
   * @return All attributes that exist only in the new list or are different than any attribute in
   *     the old list
   */
  public static Stream<Attribute<?>> getAddedOrModifiedAttributes(
      Collection<Attribute<?>> oldAttributes,
      Collection<Attribute<?>> newAttributes,
      Predicate<String> limitToAttributeNames,
      Predicate<String> ignoredAttributeNames) {
    return newAttributes.stream()
        .filter(
            newAttribute -> {
              if (limitToAttributeNames != null
                  && !limitToAttributeNames.test(newAttribute.getName())) {
                return false;
              }

              if (ignoredAttributeNames != null
                  && ignoredAttributeNames.test(newAttribute.getName())) {
                return false;
              }

              return oldAttributes.stream()
                  .filter(attribute -> attribute.getName().equals(newAttribute.getName()))
                  .findFirst()
                  .map(
                      attribute -> {
                        // Attribute may have been modified do basic equality check
                        return !attribute.equals(newAttribute);
                      })
                  .orElse(true); // Attribute is new
            });
  }

  // For JPA/Hydrators
  void setNameInternal(String name) {
    this.name = name;
  }

  public MetaMap getMeta() {
    if (meta == null) {
      meta = new MetaMap();
    }

    return meta;
  }

  public Attribute<T> setMeta(MetaMap meta) {
    this.meta = meta;
    return this;
  }

  public Attribute<T> addMeta(@NotNull MetaMap meta) {
    getMeta().addAll(meta);
    return this;
  }

  public Attribute<T> addMeta(@NotNull MetaItem<?>... meta) {
    getMeta().addAll(meta);
    return this;
  }

  public Attribute<T> addMeta(@NotNull Collection<MetaItem<?>> meta) {
    getMeta().addAll(meta);
    return this;
  }

  public Attribute<T> addOrReplaceMeta(@NotNull MetaMap meta) {
    getMeta().addAll(meta);
    return this;
  }

  public Attribute<T> addOrReplaceMeta(@NotNull MetaItem<?>... meta) {
    return addOrReplaceMeta(Arrays.asList(meta));
  }

  public Attribute<T> addOrReplaceMeta(@NotNull Collection<MetaItem<?>> meta) {
    getMeta().addAll(meta);
    return this;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Optional<T> getValue() {
    if (valueStr != null) {
      value = (T) ValueUtil.parse(valueStr, getTypeClass()).orElse(null);
      valueStr = null;
    }
    return Optional.ofNullable(value);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <U> Optional<U> getValue(@Nonnull Class<U> valueType) {
    if (valueType.isAssignableFrom(getTypeClass())) {
      return (Optional<U>) getValue();
    }
    return getValue().flatMap(v -> ValueUtil.getValueCoerced(v, valueType));
  }

  @Override
  public void setValue(T value) {
    super.setValue(value);
    timestamp = 0L;
  }

  public void setValue(T value, long timestamp) {
    super.setValue(value);
    this.timestamp = timestamp;
  }

  public Optional<Long> getTimestamp() {
    return hasExplicitTimestamp() ? Optional.of(Math.abs(timestamp)) : Optional.empty();
  }

  public Attribute<T> setTimestamp(long timestamp) {
    this.timestamp = timestamp;
    return this;
  }

  public boolean hasExplicitTimestamp() {
    return timestamp > 0;
  }

  // type is effectively transient so don't use in equality checks as JPA will think the entity is
  // dirty just because
  // the type has been injected PostLoad
  // TODO: Restructure packages so this can be package visible
  public void setTypeInternal(ValueDescriptor<T> type) {
    this.type = type;
  }

  @Override
  public String toString() {
    String valStr = value != null ? value.toString() : null;
    valStr = valStr != null && valStr.length() > 100 ? valStr.substring(0, 100) : valStr;
    return getClass().getSimpleName()
        + "{"
        + "name='"
        + name
        + '\''
        + ", type='"
        + (type != null ? type.getName() : "null")
        + '\''
        + ", value='"
        + valStr
        + '\''
        + ", timestamp='"
        + getTimestamp().orElse(0L)
        + '\''
        + "} ";
  }

  public String toStringAll() {
    return getClass().getSimpleName()
        + "{"
        + "name='"
        + name
        + '\''
        + ", type='"
        + (type != null ? type.getName() : "null")
        + '\''
        + ", value='"
        + value
        + '\''
        + ", timestamp='"
        + getTimestamp().orElse(0L)
        + '\''
        + ", meta='"
        + (meta == null
            ? ""
            : getMeta().values().stream().map(MetaItem::toString).collect(Collectors.joining(",")))
        + '\''
        + "} ";
  }

  @Override
  public int hashCode() {
    return Objects.hash(value, name, timestamp, meta);
  }

  /**
   * Super fast equality checking using the name, type and timestamp; when an {@link Attribute}
   * value is updated then the timestamp must be greater than the existing value timestamp; and also
   * when merging an {@link Asset} any modified {@link Attribute} should have a newer timestamp; the
   * backend should take care to update the {@link Attribute} timestamp when merging an {@link
   * Asset} by using {@link #deepEquals}.
   */
  @Override
  public boolean equals(Object obj) {
    if (obj == null) return false;
    if (!(obj instanceof Attribute)) return false;
    Attribute<?> that = (Attribute<?>) obj;

    return Objects.equals(timestamp, that.timestamp)
        && Objects.equals(name, that.name)
        && Objects.equals(type, that.type);
  }

  public boolean deepEquals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Attribute<?> that = (Attribute<?>) o;

    return Objects.equals(timestamp, that.timestamp)
        && Objects.equals(name, that.name)
        && Objects.equals(type, that.type)
        // Use uninitialized json value if available
        && (valueStr != null && that.valueStr != null
            ? Objects.equals(valueStr, that.valueStr)
            : ValueUtil.objectsEqualsWithJSONFallback(value, that.value))
        // null or empty meta are considered equal
        && ((meta == null && that.meta != null && that.meta.isEmpty())
            || (that.meta == null && meta != null && meta.isEmpty())
            || Objects.equals(meta, that.meta));
  }

  public boolean equals(Object obj, Comparator<Attribute<?>> comparator) {
    if (comparator == null) {
      return equals(obj);
    }
    if (obj == null) return false;
    if (!(obj instanceof Attribute)) return false;
    Attribute<?> that = (Attribute<?>) obj;
    return comparator.compare(this, that) == 0;
  }

  public Attribute<T> shallowClone() {
    Attribute<T> cloned = new Attribute<>();
    cloned.name = name;
    cloned.meta = meta;
    cloned.type = type;
    cloned.timestamp = timestamp;
    cloned.value = value;
    return cloned;
  }
}
