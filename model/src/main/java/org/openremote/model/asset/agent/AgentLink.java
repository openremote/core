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
package org.openremote.model.asset.agent;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.query.filter.ValuePredicate;
import org.openremote.model.util.JSONSchemaUtil.*;
import org.openremote.model.util.TsIgnoreTypeParams;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.ValueFilter;

/**
 * Represents the configuration of an {@link Attribute} linked to an {@link Agent}; each {@link
 * Agent} should have its' own concrete implementation of this class or use {@link DefaultAgentLink}
 * with fields describing each configuration item and standard JSR-380 annotations should be used to
 * provide validation logic.
 */
@JsonTypeInfo(
    property = "type",
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
    defaultImpl = DefaultAgentLink.class)
@JsonSchemaDefault("{\"id\":\"\",\"type\":\"DefaultAgentLink\"}")
@TsIgnoreTypeParams
public abstract class AgentLink<T extends AgentLink<?>> implements Serializable {

  @JsonSchemaTitle("Agent ID")
  @JsonSchemaFormat("or-agent-id")
  @JsonSchemaTemplateParameter
  protected String id;

  @JsonSchemaDescription(
      "Defines ValueFilters to apply to an incoming value before it is written to a"
          + " protocol linked attribute; this is particularly useful for generic protocols. The message should pass through"
          + " the filters in array order")
  protected ValueFilter[] valueFilters;

  @JsonSchemaDescription(
      "Defines a value converter map to allow for basic value type conversion; the incoming value"
          + " will be converted to JSON and if this string matches a key in the converter then the value of that key will be"
          + " pushed through to the attribute. An example use case is an API that returns 'ACTIVE'/'DISABLED' strings but"
          + " you want to connect this to a Boolean attribute")
  @JsonSchemaSupplier(
      supplier = SchemaNodeMapper.SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_ANY_KEY_ANY_TYPE)
  protected Map<String, Object> valueConverter;

  @JsonSchemaDescription(
      "Similar to valueFilter but will be applied to outgoing values allowing for inverse filtering.")
  protected ValueFilter[] writeValueFilters;

  @JsonSchemaDescription(
      "Similar to valueConverter but will be applied to outgoing values allowing for the opposite conversion")
  @JsonSchemaSupplier(
      supplier = SchemaNodeMapper.SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_ANY_KEY_ANY_TYPE)
  protected Map<String, Object> writeValueConverter;

  @JsonSchemaDescription(
      "String to be used for attribute writes and can contain dynamic placeholders to allow dynamic"
          + " value and/or time injection with formatting (see documentation for details) into the string or alternatively"
          + " write the string through to the protocol as is (static string)")
  @JsonSchemaFormat("or-multiline")
  protected String writeValue;

  @JsonSchemaDescription(
      "The predicate to apply to incoming messages to determine if the message is intended for the"
          + " linked attribute; the value used in the predicate can be filtered using the message match filters. This must be defined to"
          + " enable attributes to be updated by the linked agent.")
  protected ValuePredicate messageMatchPredicate;

  @JsonSchemaDescription(
      "ValueFilters to apply to incoming messages prior to comparison with the messageMatchPredicate")
  protected ValueFilter[] messageMatchFilters;

  @JsonSchemaDescription(
      "Don't expect a response from the protocol just update the attribute immediately on write")
  protected Boolean updateOnWrite;

  // For Hydrators
  protected AgentLink() {}

  protected AgentLink(String id) {
    this.id = id;
  }

  public static <T> T getOrThrowAgentLinkProperty(Optional<T> value, String name) {
    return value.orElseThrow(
        () -> {
          String msg = "Required agent link property is undefined: " + name;
          ValueUtil.LOG.warning(msg);
          return new IllegalStateException("msg");
        });
  }

  @JsonSerialize
  protected String getType() {
    return getClass().getSimpleName();
  }

  public String getId() {
    return id;
  }

  public Optional<ValueFilter[]> getValueFilters() {
    return Optional.ofNullable(valueFilters);
  }

  @SuppressWarnings("unchecked")
  public T setValueFilters(ValueFilter[] valueFilters) {
    this.valueFilters = valueFilters;
    return (T) this;
  }

  public Optional<Map<String, Object>> getValueConverter() {
    return Optional.ofNullable(valueConverter);
  }

  @SuppressWarnings("unchecked")
  public T setValueConverter(Map<String, Object> valueConverter) {
    this.valueConverter = valueConverter;
    return (T) this;
  }

  public Optional<ValueFilter[]> getWriteValueFilters() {
    return Optional.ofNullable(writeValueFilters);
  }

  @SuppressWarnings("unchecked")
  public T setWriteValueFilters(ValueFilter[] writeValueFilters) {
    this.writeValueFilters = writeValueFilters;
    return (T) this;
  }

  public Optional<Map<String, Object>> getWriteValueConverter() {
    return Optional.ofNullable(writeValueConverter);
  }

  @SuppressWarnings("unchecked")
  public T setWriteValueConverter(Map<String, Object> writeValueConverter) {
    this.writeValueConverter = writeValueConverter;
    return (T) this;
  }

  public Optional<String> getWriteValue() {
    return Optional.ofNullable(writeValue);
  }

  @SuppressWarnings("unchecked")
  public T setWriteValue(String writeValue) {
    this.writeValue = writeValue;
    return (T) this;
  }

  public Optional<ValuePredicate> getMessageMatchPredicate() {
    return Optional.ofNullable(messageMatchPredicate);
  }

  @SuppressWarnings("unchecked")
  public T setMessageMatchPredicate(ValuePredicate messageMatchPredicate) {
    this.messageMatchPredicate = messageMatchPredicate;
    return (T) this;
  }

  public Optional<ValueFilter[]> getMessageMatchFilters() {
    return Optional.ofNullable(messageMatchFilters);
  }

  @SuppressWarnings("unchecked")
  public T setMessageMatchFilters(ValueFilter[] messageMatchFilters) {
    this.messageMatchFilters = messageMatchFilters;
    return (T) this;
  }

  public Optional<Boolean> getUpdateOnWrite() {
    return Optional.ofNullable(updateOnWrite);
  }

  @SuppressWarnings("unchecked")
  public T setUpdateOnWrite(Boolean updateOnWrite) {
    this.updateOnWrite = updateOnWrite;
    return (T) this;
  }
}
