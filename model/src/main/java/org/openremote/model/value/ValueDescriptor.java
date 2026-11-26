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
package org.openremote.model.value;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.function.Function;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.util.TsIgnoreTypeParams;
import org.openremote.model.util.ValueUtil;

/**
 * A simple wrapper around a {@link Class} that describes a value that can be used by {@link
 * Attribute}s and {@link MetaItem}s; it can also store {@link ValueConstraint} and {@link
 * ValueFormat} information.
 *
 * <p>The {@link ValueDescriptor} applies to the {@link Asset} type it is associated with and all
 * subtypes of this type (i.e. a {@link ValueDescriptor} associated with the base {@link Asset} type
 * will be available to all {@link Asset} types (e.g. {@link ValueType#NUMBER} can be applied to any
 * {@link org.openremote.model.asset.Asset}'s {@link Attribute} and/or {@link MetaItemDescriptor}).
 *
 * <p>{@link ValueDescriptor}s for arrays don't need to be explicitly defined but can be obtained at
 * the point of consumption by simply calling {@link ValueDescriptor#asArray}.
 *
 * <p>{@link ValueDescriptor#getName} must be globally unique within the context of the manager it
 * is registered with.
 */
@TsIgnoreTypeParams
public class ValueDescriptor<T> implements NameHolder, Serializable {

  /**
   * Custom deserializer that will return the same instance of {@link ValueDescriptor} using the
   * {@link #VALUE_DESCRIPTOR_PROVIDER} attribute; otherwise it will construct a new instance
   */
  public static class ValueDescriptorDeserializer extends StdDeserializer<ValueDescriptor<?>>
      implements ResolvableDeserializer {

    public static final String VALUE_DESCRIPTOR_PROVIDER = "value-descriptor-provider";
    protected static Function<String, ValueDescriptor<?>> DEFAULT_VALUE_DESCRIPTOR_PROVIDER =
        (name) -> ValueUtil.getValueDescriptor(name).orElse(null);
    protected JsonDeserializer<ValueDescriptor<?>> defaultDeserializer;

    public ValueDescriptorDeserializer(JsonDeserializer<ValueDescriptor<?>> deserializer) {
      super(ValueDescriptor.class);
      this.defaultDeserializer = deserializer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public ValueDescriptor<?> deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JacksonException {
      Function<String, ValueDescriptor<?>> valueDescriptorProvider =
          (Function<String, ValueDescriptor<?>>) ctxt.getAttribute(VALUE_DESCRIPTOR_PROVIDER);
      if (valueDescriptorProvider == null) {
        valueDescriptorProvider = DEFAULT_VALUE_DESCRIPTOR_PROVIDER;
      }

      if (p.currentToken() == JsonToken.VALUE_NULL) {
        return null;
      }

      if (p.currentToken() == JsonToken.VALUE_STRING) {
        String name = p.getText();
        return valueDescriptorProvider.apply(name);
      }

      if (!p.isExpectedStartObjectToken()) {
        throw JsonMappingException.from(p, "Value descriptor must be a string or object");
      }

      return defaultDeserializer.deserialize(p, ctxt);
    }

    // The wrapped deserializer might need some post-processing so check whether it implements it or
    // not
    @Override
    public void resolve(DeserializationContext ctxt) throws JsonMappingException {
      if (defaultDeserializer instanceof ResolvableDeserializer resolvableDeserializer) {
        resolvableDeserializer.resolve(ctxt);
      }
    }
  }

  @Pattern(regexp = "^\\w+(\\[\\])?$")
  protected String name;

  protected Class<T> type;
  protected Integer arrayDimensions;
  protected ValueConstraint[] constraints;
  protected ValueFormat format;
  protected String[] units;

  /**
   * A flag to indicate that this {@link ValueDescriptor} is intended for use in a {@link MetaItem};
   * this can be used for UI purposes but nothing actually stops the values being used in an {@link
   * Attribute} also.
   */
  protected Boolean metaUseOnly;

  @SuppressWarnings("unchecked")
  public ValueDescriptor(String name, Class<T> type, ValueConstraint... constraints) {
    if (type.isArray()) {
      throw new IllegalArgumentException("Value descriptor type should be the inner array type");
    }
    this.name = name;
    this.type = type;
    if (type.isEnum()
        && (constraints == null
            || Arrays.stream(constraints)
                .noneMatch(c -> c instanceof ValueConstraint.AllowedValues))) {
      ValueConstraint.AllowedValues allowedValues =
          ValueConstraint.AllowedValues.fromEnumValues((Enum[]) type.getEnumConstants());
      if (constraints != null) {
        constraints = Arrays.copyOf(constraints, constraints.length + 1);
        constraints[constraints.length - 1] = allowedValues;
      } else {
        constraints = new ValueConstraint[] {allowedValues};
      }
    }
    this.constraints = constraints;
  }

  @JsonCreator
  protected ValueDescriptor(
      String name,
      Class<T> type,
      ValueConstraint[] constraints,
      ValueFormat format,
      String[] units,
      Integer arrayDimensions) {
    this.name = name;
    this.type = type;
    this.arrayDimensions = arrayDimensions;
    this.constraints = constraints;
    this.format = format;
    this.units = units;
  }

  @Pattern(regexp = "^\\w+(\\[\\])?$")
  public String getName() {
    return name;
  }

  @SuppressWarnings("unchecked")
  public Class<T> getType() {
    return type != null ? type : (Class<T>) Object.class;
  }

  /** If this descriptor is an array type then this will get the inner type of the array(s) */
  public Class<?> getBaseType() {
    Class<?> clazz = getType();
    while (clazz.isArray()) {
      clazz = clazz.getComponentType();
    }
    return clazz;
  }

  /**
   * Will get the JSON type of the inner most type; can be used in combination with {@link
   * #arrayDimensions} to know the structure of this value descriptor
   */
  @JsonProperty
  public String getJsonType() {
    Class<?> type = getBaseType();

    if (ValueUtil.isBoolean(type)) {
      return "boolean";
    }
    if (ValueUtil.isNumber(type)) {
      if (BigInteger.class.isAssignableFrom(type)) {
        return "bigint";
      }
      return "number";
    }
    if (ValueUtil.isString(type) || type.isEnum()) {
      return "string";
    }
    if (ValueUtil.isArray(type)) {
      return "array";
    }
    if (Date.class.isAssignableFrom(type)) {
      return "date";
    }
    if (type == Object.class) {
      return "unknown";
    }
    return "object";
  }

  public ValueDescriptor<T> withFormat(ValueFormat format) {
    return new ValueDescriptor<>(name, type, constraints, format, units, arrayDimensions);
  }

  public ValueDescriptor<T> withConstraints(ValueConstraint... constraints) {
    return new ValueDescriptor<>(name, type, constraints, format, units, arrayDimensions);
  }

  public ValueDescriptor<T> withUnits(String... units) {
    return new ValueDescriptor<>(name, type, constraints, format, units, arrayDimensions);
  }

  public ValueDescriptor<T> forMetaUseOnly() {
    ValueDescriptor<T> newValueDescriptor =
        new ValueDescriptor<>(name, type, constraints, format, units, arrayDimensions);
    newValueDescriptor.metaUseOnly = true;
    return newValueDescriptor;
  }

  public void updateConstraints(ValueConstraint... valueConstraints) {
    constraints = valueConstraints;
  }

  public Integer getArrayDimensions() {
    return arrayDimensions;
  }

  public ValueConstraint[] getConstraints() {
    return constraints;
  }

  public ValueFormat getFormat() {
    return format;
  }

  public String[] getUnits() {
    return units;
  }

  public boolean isArray() {
    return arrayDimensions != null && arrayDimensions > 0;
  }

  public boolean isMetaUseOnly() {
    return metaUseOnly != null ? metaUseOnly : false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || !(ValueDescriptor.class.isAssignableFrom(obj.getClass()))) return false;
    ValueDescriptor<?> that = (ValueDescriptor<?>) obj;
    return Objects.equals(name, that.name) && Objects.equals(type, that.type);
  }

  /**
   * Returns an instance of this {@link ValueDescriptor} where the {@link #arrayDimensions} are
   * incremented by one and the type becomes an array type of the original type (e.g. String ->
   * String[] -> String[][])
   */
  @SuppressWarnings("unchecked")
  public ValueDescriptor<T[]> asArray() {
    try {
      Class<T[]> arrayClass = (Class<T[]>) ValueUtil.getArrayClass(type);
      return new ValueDescriptor<>(
          name + "[]",
          arrayClass,
          constraints,
          format,
          units,
          arrayDimensions == null ? 1 : arrayDimensions + 1);
    } catch (ClassNotFoundException ignored) {
      // Can't happen as we have the source class already
    }

    return null;
  }

  public ValueDescriptor<?> asNonArray() {
    if (arrayDimensions == null || arrayDimensions == 0) {
      return this;
    }
    return new ValueDescriptor<>(
        name.replaceAll("\\[\\]", ""), type, constraints, format, units, null);
  }

  @Override
  public String toString() {
    return ValueDescriptor.class.getSimpleName()
        + "{"
        + "name='"
        + name
        + '\''
        + ", type="
        + type
        + ", arrayDimensions="
        + arrayDimensions
        + ", constraints="
        + Arrays.toString(constraints)
        + ", format="
        + format
        + ", metaUseOnly="
        + isMetaUseOnly()
        + ", units="
        + Arrays.toString(units)
        + '}';
  }
}
