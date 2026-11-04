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
package org.openremote.model.util

import com.fasterxml.jackson.annotation.*
import org.openremote.model.util.JSONSchemaUtil.*
import org.openremote.model.value.ValueType
import org.reflections.Reflections
import spock.lang.Snapshot
import spock.lang.Snapshotter
import spock.lang.Specification

import java.time.*

class JSONSchemaUtilTest extends Specification {

  private static final Reflections reflections = new Reflections("org.openremote")

  @Snapshot(extension = "json")
  Snapshotter snapshotter

  def setupSpec() {
    ValueUtil.doInitialise()
  }

  private void schemaMatchesSnapshot(Class<?> type) {
    snapshotter.assertThat(ValueUtil.getSchema(type).toPrettyString()).matchesSnapshot()
  }

  static class Title {}

  def "schema has a generated title"() {
    expect:
    schemaMatchesSnapshot(Title)
  }

  @JsonSchemaTitle(value = "Test Title", i18n = false)
  static class ItemType {}

  static class MembersShouldNotHaveTitle {
    public Map<String, String> test
    public ItemType[] test1
  }

  def "schema members do not have generated titles"() {
    expect:
    schemaMatchesSnapshot(MembersShouldNotHaveTitle)
  }

  def "schema remaps Byte"() {
    expect:
    schemaMatchesSnapshot(Byte)
  }

  static class AdditionalProperties {}

  def "schema allows additional properties"() {
    expect:
    schemaMatchesSnapshot(AdditionalProperties)
  }

  static class RemapTypes {
    @JsonSchemaTypeRemap(type = String)
    public boolean test1

    @JsonSchemaTypeRemap(type = boolean)
    public String test2

    @JsonSchemaTypeRemap(type = Date)
    public boolean test3

    @JsonSchemaSupplier(
    supplier = SchemaNodeMapper.SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_ANY_KEY_ANY_TYPE)
    public Boolean test4
  }

  def "schema remaps annotated types"() {
    expect:
    schemaMatchesSnapshot(RemapTypes)
  }

  def "schema handles map type #mapType.simpleName"() {
    expect:
    schemaMatchesSnapshot(mapType)

    where:
    mapType << [
      ValueType.BooleanMap,
      ValueType.DoubleMap,
      ValueType.IntegerMap,
      ValueType.ObjectMap,
      ValueType.StringMap,
      ValueType.MultivaluedStringMap
    ]
  }

  static class JacksonAnnotations {
    @JsonPropertyDescription("This property should have a description.")
    public Boolean test1

    @JsonProperty("renamed")
    public Boolean test2

    @JsonProperty(value = "renamed1", required = true)
    public Boolean test3
  }

  def "schema handles Jackson annotations"() {
    expect:
    schemaMatchesSnapshot(JacksonAnnotations)
  }

  static class Primitives {
    public boolean test1
    public int test2
    public long test3
    public float test4
    public double test5
    public byte test6
    public char test7
  }

  def "schema requires primitive fields"() {
    expect:
    schemaMatchesSnapshot(Primitives)
  }

  static class AnnotationsForFields {
    @JsonSchemaTitle(value = "test", i18n = false)
    @JsonSchemaDescription(value = "test", i18n = false)
    @JsonSchemaFormat("test")
    @JsonSchemaDefault("false")
    @JsonSchemaExamples(["test"])
    public Boolean all
  }

  def "schema applies custom annotations to fields"() {
    expect:
    schemaMatchesSnapshot(AnnotationsForFields)
  }

  @JsonSchemaTitle(value = "test", i18n = false)
  @JsonSchemaDescription(value = "test", i18n = false)
  @JsonSchemaFormat("test")
  @JsonSchemaDefault("{}")
  @JsonSchemaExamples(["test"])
  static class AnnotationsForTypes {}

  def "schema applies custom annotations to types"() {
    expect:
    schemaMatchesSnapshot(AnnotationsForTypes)
  }

  @JsonSchemaTitle("test")
  @JsonSchemaDescription("test")
  static class I18nAnnotations {}

  def "schema applies i18n annotations"() {
    expect:
    schemaMatchesSnapshot(I18nAnnotations)
  }

  @JsonSchemaTitle(value = "test", i18n = false)
  @JsonSchemaDescription("Translated description")
  static class I18nAnnotationsPartiallyDisabled {}

  def "schema applies partially disabled i18n annotations"() {
    expect:
    schemaMatchesSnapshot(I18nAnnotationsPartiallyDisabled)
  }

  @JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes([
    @JsonSubTypes.Type(SubType),
    @JsonSubTypes.Type(SubTypeSuperclass),
  ])
  abstract static class PolymorphicType<T extends PolymorphicType<?>> implements Serializable {}

  @JsonTypeName("SubType")
  static class SubType extends PolymorphicType<SubType> {}

  @JsonTypeName("SubTypeSuperclass")
  static class SubTypeSuperclass extends SubType {}

  def "schema includes subtypes with a type property"() {
    expect:
    schemaMatchesSnapshot(PolymorphicType)
  }

  @JsonTypeInfo(property = "customType", use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes([
    @JsonSubTypes.Type(SubTypeWithCustomProperty),
    @JsonSubTypes.Type(SubTypeSuperClassWithCustomProperty),
  ])
  abstract static class PolymorphicTypeWithCustomProperty<
  T extends PolymorphicTypeWithCustomProperty<?>>
  implements Serializable {}

  @JsonTypeName("SubTypeWithCustomProperty")
  static class SubTypeWithCustomProperty
  extends PolymorphicTypeWithCustomProperty<SubTypeWithCustomProperty> {}

  @JsonTypeName("SubTypeSuperClassWithCustomProperty")
  static class SubTypeSuperClassWithCustomProperty extends SubTypeWithCustomProperty {}

  def "schema includes subtypes with a custom type property"() {
    expect:
    schemaMatchesSnapshot(PolymorphicTypeWithCustomProperty)
  }

  // Note: JsonTypeInfo.As.EXISTING_PROPERTY doesn't necessarily change the behavior mainly the
  // "customType" property on the abstract class matters.
  @JsonTypeInfo(
  property = PolymorphicTypeWithCustomExistingProperty.VALUE_KEY_CUSTOM_TYPE,
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXISTING_PROPERTY)
  @JsonSubTypes([
    @JsonSubTypes.Type(
            name = SubTypeWithCustomExistingProperty.SUB_CUSTOM_TYPE,
            value = SubTypeWithCustomExistingProperty),
    @JsonSubTypes.Type(
            name = SubTypeSuperClassWithCustomExistingProperty.SUPER_CUSTOM_TYPE,
            value = SubTypeSuperClassWithCustomExistingProperty),
  ])
  abstract static class PolymorphicTypeWithCustomExistingProperty<
  T extends PolymorphicTypeWithCustomExistingProperty<?>>
  implements Serializable {
    public static final String VALUE_KEY_CUSTOM_TYPE = "customType"

    @JsonProperty(VALUE_KEY_CUSTOM_TYPE)
    protected String customType

    String getCustomType() {
      customType
    }
  }

  @JsonTypeName(SubTypeWithCustomExistingProperty.SUB_CUSTOM_TYPE)
  static class SubTypeWithCustomExistingProperty
  extends PolymorphicTypeWithCustomExistingProperty<SubTypeWithCustomExistingProperty> {
    public static final String SUB_CUSTOM_TYPE = "sub"
  }

  @JsonTypeName(SubTypeSuperClassWithCustomExistingProperty.SUPER_CUSTOM_TYPE)
  static class SubTypeSuperClassWithCustomExistingProperty
  extends SubTypeWithCustomExistingProperty {
    public static final String SUPER_CUSTOM_TYPE = "super"
  }

  def "schema includes subtypes with an existing custom type property"() {
    expect:
    schemaMatchesSnapshot(PolymorphicTypeWithCustomExistingProperty)
  }

  @JsonTypeInfo(
  property = "type",
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.EXTERNAL_PROPERTY)
  @JsonSubTypes([
    @JsonSubTypes.Type(ExternalSubType),
    @JsonSubTypes.Type(ExternalSubTypeSuperclass),
  ])
  abstract static class ExternalPolymorphicType<T extends ExternalPolymorphicType<?>>
  implements Serializable {}

  static class ExternalSubType extends ExternalPolymorphicType<ExternalSubType> {}

  static class ExternalSubTypeSuperclass extends ExternalSubType {}

  def "schema sets the enum type for an external property"() {
    expect:
    schemaMatchesSnapshot(ExternalPolymorphicType)
  }

  @JsonTypeName("ReflectedPolymorphicType")
  @JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
  abstract static class ReflectedPolymorphicType<T extends ReflectedPolymorphicType<?>>
  implements Serializable {}

  @JsonTypeName("ResolvedSubType")
  static class ResolvedSubType extends ReflectedPolymorphicType<ResolvedSubType> {}

  def "schema resolves subtypes through reflections"() {
    expect:
    schemaMatchesSnapshot(ReflectedPolymorphicType)
  }

  def "schemas do not use allOf"() {
    expect:
    reflections.getTypesAnnotatedWith(JsonTypeInfo).each { clazz ->
      assert !ValueUtil.getSchema(clazz).toString().contains("allOf")
    }
  }

  static class JavaTimeJacksonModule {
    public Duration duration
    public LocalDateTime localDateTime
    public LocalDate localDate
    public LocalTime localTime
    public MonthDay monthDay
    public OffsetTime offsetTime
    public Period period
    public Year year
    public YearMonth yearMonth
    public ZoneId zoneId
    public ZoneOffset zoneOffset
    // Instant variants
    public Instant instant
    public OffsetDateTime offsetDateTime
    public ZonedDateTime zonedDateTime
  }

  def "schema applies Jackson serializers"() {
    expect:
    schemaMatchesSnapshot(JavaTimeJacksonModule)
  }

  enum TypeOption {
    INTEGER(int),
    STRING(String),
    LONG(long),
    FLOAT(Float)

    TypeOption(Class<?> javaType) {}
  }

  def "schema generates enum values"() {
    expect:
    schemaMatchesSnapshot(TypeOption)
  }
}
