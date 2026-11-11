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

import static org.skyscreamer.jsonassert.JSONAssert.assertEquals

import com.fasterxml.jackson.annotation.*
import org.openremote.model.asset.agent.AgentLink
import org.openremote.model.util.JSONSchemaUtil.*
import org.openremote.model.value.ValueType
import org.reflections.Reflections
import spock.lang.Specification

import java.time.*

class JSONSchemaUtilTest extends Specification {

  private static final Reflections reflections = new Reflections("org.openremote")

  def setupSpec() {
    ValueUtil.doInitialise()
  }

  private static InputStream loadResourceAsStream(String resourcePath) {
    JSONSchemaUtilTest.getResourceAsStream(
            "/org/openremote/model/util/" + resourcePath + ".json")
  }

  static class Title {}

  def "schema has a generated title"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "title": "Title",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(Title)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  @JsonSchemaTitle(value = "Test Title", i18n = false)
  static class ItemType {}

  static class MembersShouldNotHaveTitle {
    public Map<String, String> test
    public ItemType[] test1
  }

  def "schema members do not have generated titles"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "test": { "type": "object", "additionalProperties": { "type": "string" } },
                    "test1": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "title": "Test Title",
                        "additionalProperties": true
                      }
                    }
                  },
                  "title": "Members Should Not Have Title",
                  "additionalProperties": true
                }
            ''')

    def actual = ValueUtil.getSchema(MembersShouldNotHaveTitle)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  def "schema remaps Byte"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(loadResourceAsStream(Byte.name))
    def actual = ValueUtil.getSchema(Byte)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  static class AdditionalProperties {}

  def "schema allows additional properties"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "title": "Additional Properties",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(AdditionalProperties)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema":"http://json-schema.org/draft-07/schema#",
                "type":"object",
                "properties": {
                    "test1":{"type":"string"},
                    "test2":{"type":"boolean"},
                    "test3":{"type":"integer","format":"utc-millisec"},
                    "test4":{"type":"object","patternProperties":{".+":{"type":["null","number","integer","boolean","string","array","object"]}}}}
                },
                "required":["test1","test3"],
                "title":"Remap Types",
                "additionalProperties":true
            }''')

    def actual = ValueUtil.getSchema(RemapTypes)
    assertEquals(expected.toString(), actual.toString(), false)
  }

  def "schema handles map type #mapType.simpleName"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(loadResourceAsStream(mapType.name))
    def actual = ValueUtil.getSchema(mapType)
    assertEquals(expected.toString(), actual.toString(), true)

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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "renamed": {
                        "type": "boolean"
                    },
                    "renamed1": {
                        "type": "boolean",
                        "readOnly": true,
                        "writeOnly": true
                    },
                    "test1": {
                        "type": "boolean",
                        "description": "This property should have a description."
                    }
                },
                "required": [ "renamed1" ],
                "title": "Jackson Annotations",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(JacksonAnnotations)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "test1": { "type": "boolean", "default": false },
                    "test2": { "type": "integer" },
                    "test3": { "type": "integer" },
                    "test4": { "type": "number" },
                    "test5": { "type": "number" },
                    "test6": { "type": "integer" },
                    "test7": { "type": "string" }
                },
                "required": [
                    "test1",
                    "test2",
                    "test3",
                    "test4",
                    "test5",
                    "test6",
                    "test7"
                ],
                "title": "Primitives",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(Primitives)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  static class AnnotationsForFields {
    @JsonSchemaTitle(value = "test", i18n = false)
    @JsonSchemaDescription(value = "test", i18n = false)
    @JsonSchemaFormat("test")
    @JsonSchemaDefault("false")
    @JsonSchemaExamples(["test"])
    @JsonSchemaTemplateParameter
    public Boolean all
  }

  def "schema applies custom annotations to fields"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "all":{
                        "type":"boolean",
                        "title":"test",
                        "description":"test",
                        "default": false,
                        "format":"test",
                        "examples":["test"],
                        "templateParameter": true
                    }
                },
                "title": "Annotations For Fields",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(AnnotationsForFields)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  @JsonSchemaTitle(value = "test", i18n = false)
  @JsonSchemaDescription(value = "test", i18n = false)
  @JsonSchemaFormat("test")
  @JsonSchemaDefault("{}")
  @JsonSchemaExamples(["test"])
  static class AnnotationsForTypes {}

  def "schema applies custom annotations to types"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "title": "test",
                "additionalProperties": true,
                "description": "test",
                "format": "test",
                "default": {},
                "examples": [
                    "test"
                ]
            }''')

    def actual = ValueUtil.getSchema(AnnotationsForTypes)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  @JsonSchemaTitle("test")
  @JsonSchemaDescription("test")
  static class I18nAnnotations {}

  def "schema applies i18n annotations"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "i18n": "org.openremote.model.util.JSONSchemaUtilTest.I18nAnnotations",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(I18nAnnotations)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  @JsonSchemaTitle(value = "test", i18n = false)
  @JsonSchemaDescription("Translated description")
  static class I18nAnnotationsPartiallyDisabled {}

  def "schema applies partially disabled i18n annotations"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "title": "test",
                "i18n": "org.openremote.model.util.JSONSchemaUtilTest.I18nAnnotationsPartiallyDisabled",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(I18nAnnotationsPartiallyDisabled)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "definitions": {
                    "SubType": {
                        "title": "Sub Type",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "type": {
                                "const": "SubType"
                            }
                        },
                        "required": [
                            "type"
                        ]
                    },
                    "SubTypeSuperclass": {
                        "title": "Sub Type Superclass",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "type": {
                                "const": "SubTypeSuperclass"
                            }
                        },
                        "required": [
                            "type"
                        ]
                    }
                },
                "discriminator": {
                    "propertyName": "type"
                },
                "oneOf": [
                    { "$ref": "#/definitions/SubType" },
                    { "$ref": "#/definitions/SubTypeSuperclass" }
                ],
                "type": "object",
                "additionalProperties": true,
                "title": "Polymorphic Type"
            }''')

    def actual = ValueUtil.getSchema(PolymorphicType)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "definitions": {
                    "SubTypeWithCustomProperty": {
                        "title": "Sub Type With Custom Property",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "customType": {
                                "const": "SubTypeWithCustomProperty"
                            }
                        },
                        "required": [
                            "customType"
                        ]
                    },
                    "SubTypeSuperClassWithCustomProperty": {
                        "title": "Sub Type Super Class With Custom Property",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "customType": {
                                "const": "SubTypeSuperClassWithCustomProperty"
                            }
                        },
                        "required": [
                            "customType"
                        ]
                    }
                },
                "discriminator": {
                    "propertyName": "customType"
                },
                "oneOf": [
                    { "$ref": "#/definitions/SubTypeWithCustomProperty" },
                    { "$ref": "#/definitions/SubTypeSuperClassWithCustomProperty" }
                ],
                "type": "object",
                "additionalProperties": true,
                "title": "Polymorphic Type With Custom Property"
            }''')

    def actual = ValueUtil.getSchema(PolymorphicTypeWithCustomProperty)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "definitions": {
                    "SubTypeWithCustomExistingProperty": {
                        "title": "Sub Type With Custom Existing Property",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "customType": {
                                "const": "sub"
                            }
                        },
                        "required": [
                            "customType"
                        ]
                    },
                    "SubTypeSuperClassWithCustomExistingProperty": {
                        "title": "Sub Type Super Class With Custom Existing Property",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "customType": {
                                "const": "super"
                            }
                        },
                        "required": [
                            "customType"
                        ]
                    }
                },
                "discriminator": {
                    "propertyName": "customType"
                },
                "oneOf": [
                    { "$ref": "#/definitions/SubTypeWithCustomExistingProperty" },
                    { "$ref": "#/definitions/SubTypeSuperClassWithCustomExistingProperty" }
                ],
                "type": "object",
                "additionalProperties": true,
                "title": "Polymorphic Type With Custom Existing Property"
            }''')

    def actual = ValueUtil.getSchema(PolymorphicTypeWithCustomExistingProperty)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "definitions": {
                    "ExternalSubType": {
                        "title": "External Sub Type",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "type": {
                                "const": "ExternalSubType"
                            }
                        },
                        "required": [
                            "type"
                        ]
                    },
                    "ExternalSubTypeSuperclass": {
                        "title": "External Sub Type Superclass",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "type": {
                                "const": "ExternalSubTypeSuperclass"
                            }
                        },
                        "required": [
                            "type"
                        ]
                    }
                },
                "discriminator": {
                    "propertyName": "type"
                },
                "oneOf": [
                    { "$ref": "#/definitions/ExternalSubType" },
                    { "$ref": "#/definitions/ExternalSubTypeSuperclass" }
                ],
                "type": "object",
                "additionalProperties": true,
                "title": "External Polymorphic Type"
            }''')

    def actual = ValueUtil.getSchema(ExternalPolymorphicType)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  @JsonTypeName("ReflectedPolymorphicType")
  @JsonTypeInfo(property = "type", use = JsonTypeInfo.Id.NAME)
  abstract static class ReflectedPolymorphicType<T extends ReflectedPolymorphicType<?>>
  implements Serializable {}

  @JsonTypeName("ResolvedSubType")
  static class ResolvedSubType extends ReflectedPolymorphicType<ResolvedSubType> {}

  def "schema resolves subtypes through reflections"() {
    expect:
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "definitions": {
                    "ResolvedSubType": {
                        "title": "Resolved Sub Type",
                        "type": "object",
                        "additionalProperties": true,
                        "properties": {
                            "type": {
                                "const": "ResolvedSubType"
                            }
                        },
                        "required": [
                            "type"
                        ]
                    }
                },
                "discriminator": {
                    "propertyName": "type"
                },
                "title": "Reflected Polymorphic Type",
                "oneOf": [
                    { "$ref": "#/definitions/ResolvedSubType" }
                ],
                "type": "object",
                "additionalProperties": true,
                "properties": {
                    "type": {
                        "const": "ReflectedPolymorphicType"
                    }
                },
                "required": [
                    "type"
                ]
            }''')

    def actual = ValueUtil.getSchema(ReflectedPolymorphicType)
    assertEquals(expected.toString(), actual.toString(), true)
  }

  def "schema marks the agent id of an agent link as a template parameter"() {
    when:
    def properties = ValueUtil.getSchema(AgentLink).at("/definitions/DefaultAgentLink/properties")

    then:
    properties.at("/id/templateParameter").asBoolean()
    !properties.at("/type").has("templateParameter")
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "duration": {
                        "type": "integer",
                        "format": "utc-millisec"
                    },
                    "instant": {
                        "type": "integer",
                        "format": "utc-millisec"
                    },
                    "localDate": { "type": "string" },
                    "localDateTime": { "type": "string" },
                    "localTime": { "type": "string" },
                    "monthDay": {
                        "title": "Month Day",
                        "type": "object",
                        "properties": {
                            "month": {
                                "type": "integer"
                            }
                        },
                        "required": [
                            "month"
                        ],
                        "additionalProperties": true
                    },
                    "offsetDateTime": {
                        "type": "integer",
                        "format": "utc-millisec"
                    },
                    "offsetTime": { "type": "string" },
                    "period": { "type": "string" },
                    "year": { "type": "integer" },
                    "yearMonth": {
                        "title": "Year Month",
                        "type": "object",
                        "properties": {
                            "month": {
                                "type": "integer"
                            },
                            "year": {
                                "type": "integer"
                            }
                        },
                        "required": [
                            "month",
                            "year"
                        ],
                        "additionalProperties": true
                    },
                    "zoneId": { "type": "string" },
                    "zoneOffset": { "type": "string" },
                    "zonedDateTime": {
                        "type": "integer",
                        "format": "utc-millisec"
                    }
                },
                "title": "Java Time Jackson Module",
                "additionalProperties": true
            }''')

    def actual = ValueUtil.getSchema(JavaTimeJacksonModule)
    assertEquals(expected.toString(), actual.toString(), true)
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
    def expected =
            ValueUtil.JSON.readTree(
            '''
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "string",
                "enum": [
                    "INTEGER",
                    "STRING",
                    "LONG",
                    "FLOAT"
                ],
                "title": "Type Option"
            }''')

    def actual = ValueUtil.getSchema(TypeOption)
    assertEquals(expected.toString(), actual.toString(), true)
  }
}
