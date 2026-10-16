/*
 * Copyright 2021, OpenRemote Inc.
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

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsonFormatVisitors.*;
import com.fasterxml.jackson.databind.node.*;
import com.github.victools.jsonschema.generator.*;
import com.github.victools.jsonschema.generator.Module;
import com.github.victools.jsonschema.generator.impl.DefinitionKey;
import com.github.victools.jsonschema.generator.impl.module.SimpleTypeModule;
import com.github.victools.jsonschema.generator.naming.DefaultSchemaDefinitionNamingStrategy;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JsonSubTypesResolver;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationOption;
import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.reflections.Reflections;

public class JSONSchemaUtil {

  public static class SchemaNodeMapper {

    public static class AnyType {}

    public static final String SCHEMA_SUPPLIER_NAME_ANY_TYPE = "anyType";
    public static final String SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_ANY_KEY_ANY_TYPE =
        "patternPropertiesAnyKeyAnyType";
    public static final String SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_SIMPLE_KEY_ANY_TYPE =
        "patternPropertiesSimpleKeyAnyType";
    public static final String PATTERN_PROPERTIES_MATCH_ANY = ".+";
    public static final String PATTERN_PROPERTIES_MATCH_SIMPLE = "^[a-zA-Z][a-zA-Z0-9]*";
    public static final String TYPE_NULL = "null";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_INTEGER = "integer";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_STRING = "string";
    public static final String TYPE_ARRAY = "array";
    public static final String TYPE_OBJECT = "object";
    public static final String[] TYPES_ALL =
        new String[] {
          TYPE_NULL, TYPE_NUMBER, TYPE_INTEGER, TYPE_BOOLEAN, TYPE_STRING, TYPE_ARRAY, TYPE_OBJECT
        };

    private static final JsonNodeFactory NF = JsonNodeFactory.instance;

    public static ObjectNode getSchemaPatternProperties(String keyPattern, String... types) {
      ObjectNode node = NF.objectNode();
      node.put("type", "object");
      ObjectNode patternNode = node.putObject("patternProperties").putObject(keyPattern);
      patternNode.set("type", getTypesNode(Arrays.asList(types)));
      return node;
    }

    public static ObjectNode getSchemaPatternPropertiesAnyKeyAnyType() {
      return getSchemaPatternProperties(PATTERN_PROPERTIES_MATCH_ANY, TYPES_ALL);
    }

    public static ObjectNode getSchemaPatternPropertiesSimpleKeyAnyType() {
      return getSchemaPatternProperties(PATTERN_PROPERTIES_MATCH_SIMPLE, TYPES_ALL);
    }

    public static JsonNode getTypesNode(List<String> types) {
      if (types.size() == 1) {
        return NF.textNode(types.getFirst());
      }
      ArrayNode arr = NF.arrayNode();
      types.forEach(arr::add);
      return arr;
    }

    public static ObjectNode getSchemaType(String... types) {
      ObjectNode node = NF.objectNode();
      node.put("title", "Any Type");
      node.set("type", getTypesNode(Arrays.asList(types)));
      node.put("additionalProperties", true);
      return node;
    }

    public static ObjectNode getSuppliedNode(JsonSchemaSupplier annotation)
        throws RuntimeException {
      try {
        switch (annotation.getClass().getMethod("supplier").invoke(annotation).toString()) {
          case SchemaNodeMapper.SCHEMA_SUPPLIER_NAME_ANY_TYPE:
            return SchemaNodeMapper.getSchemaType(SchemaNodeMapper.TYPES_ALL);
          case SchemaNodeMapper.SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_ANY_KEY_ANY_TYPE:
            return SchemaNodeMapper.getSchemaPatternPropertiesAnyKeyAnyType();
          case SchemaNodeMapper.SCHEMA_SUPPLIER_NAME_PATTERN_PROPERTIES_SIMPLE_KEY_ANY_TYPE:
            return SchemaNodeMapper.getSchemaPatternPropertiesSimpleKeyAnyType();
        }
      } catch (Exception e) {
        throw new RuntimeException("Failed to apply " + annotation.getClass().getSimpleName(), e);
      }
      return null;
    }
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaTitle {
    String keyword() default "title";

    String value();

    /**
     * Whether to put the title on the root of the schema even when the class is wrapped in an
     * array.
     */
    boolean container() default true;

    boolean i18n() default true;

    String i18nSuffix() default "label";
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaDescription {
    String keyword() default "description";

    String value();

    /**
     * Whether to put the title on the root of the schema even when the class is wrapped in an
     * array.
     */
    boolean container() default true;

    boolean i18n() default true;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaFormat {
    String keyword() default "format";

    String value();

    /**
     * Whether to put the title on the root of the schema even when the class is wrapped in an
     * array.
     */
    boolean container() default true;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaDefault {
    String keyword() default "default";

    String value();

    /**
     * Whether to put the title on the root of the schema even when the class is wrapped in an
     * array.
     */
    boolean container() default true;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaExamples {
    String keyword() default "examples";

    String[] value();

    /**
     * Whether to put the title on the root of the schema even when the class is wrapped in an
     * array.
     */
    boolean container() default true;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaTypeRemap {
    Class<?> type();
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.FIELD})
  public @interface JsonSchemaSupplier {
    String supplier();
  }

  /**
   * Marks a field whose value a configuration template asks for on import, such as the agent of an
   * agent link. Only mark fields that the standard JSON Forms renderers can render, as the import
   * renders them outside of their custom renderers.
   */
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface JsonSchemaTemplateParameter {
    String keyword() default "templateParameter";

    String value() default "true";
  }

  private static class CustomModule implements Module {

    private final ObjectMapper mapper;
    private final JacksonResolvers jacksonResolvers = new JacksonResolvers();
    private static final ConcurrentHashMap<Class<?>, List<ResolvedType>> subtypeCache =
        new ConcurrentHashMap<>();
    private static final Reflections reflections = new Reflections("org.openremote");

    CustomModule(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    @Override
    public void applyToConfigBuilder(SchemaGeneratorConfigBuilder builder) {
      // Set title on root of schema and for object definitions
      JSONSchemaTitleProvider titleProvider = new JSONSchemaTitleProvider();
      builder
          .forTypesInGeneral()
          .withCustomDefinitionProvider(titleProvider)
          .withTypeAttributeOverride(titleProvider);

      // Remap Byte to type integer, see
      // https://github.com/victools/jsonschema-generator/blob/995a71eaf7a9a05cc2e335f8a7821b4a9019fa1b/CHANGELOG.md?plain=1#L530
      builder.with(new SimpleTypeModule().withIntegerType(Byte.class));

      // TODO: check whether we can set this to false as this does not align with most of our APIs
      // Apply additionalProperties true to all object values that haven't already set
      // additionalProperties
      builder
          .forTypesInGeneral()
          .withTypeAttributeOverride(
              (attrs, typeScope, context) -> {
                if (attrs.has("type")
                    && !attrs.has("additionalProperties")
                    && Objects.equals(attrs.get("type").textValue(), "object")) {
                  attrs.put("additionalProperties", Boolean.TRUE);
                }
              });

      // General direct class type remapping
      builder
          .forTypesInGeneral()
          // TODO: consider using withTargetTypeOverridesResolver instead
          .withDefinitionNamingStrategy(
              new DefaultSchemaDefinitionNamingStrategy() {
                @Override
                public String getDefinitionNameForKey(
                    DefinitionKey key, SchemaGenerationContext generationContext) {
                  TypeContext typeContext = generationContext.getTypeContext();
                  ResolvedType type = key.getType();
                  Class<?> erasedType = type.getErasedType();
                  if (erasedType.equals(Object.class)) {
                    return typeContext.getSimpleTypeDescription(
                        typeContext.resolve(SchemaNodeMapper.AnyType.class));
                  }
                  return typeContext.getSimpleTypeDescription(type);
                }
              })
          .withCustomDefinitionProvider(
              (resolvedType, context) -> {
                Class<?> erasedType = resolvedType.getErasedType();
                // Does not behave like before where this is the fallback if a class could not be
                // resolved
                if (erasedType.equals(Object.class)) {
                  return new CustomDefinition(
                      SchemaNodeMapper.getSchemaType(SchemaNodeMapper.TYPES_ALL));
                }
                if (erasedType.equals(ObjectNode.class)) {
                  return new CustomDefinition(
                      SchemaNodeMapper.getSchemaPatternPropertiesSimpleKeyAnyType());
                }
                // Value type parameter "Object" is not handled on HashMap<String,Object> by
                // MAP_VALUES_AS_ADDITIONAL_PROPERTIES
                // TODO: ideally we add a reference to the AnyType definition rather than always
                // inlining
                if (erasedType.getSuperclass() != null
                    && erasedType.getSuperclass().equals(HashMap.class)
                    && erasedType.getGenericSuperclass() instanceof ParameterizedType t
                    && t.getActualTypeArguments()[1].equals(Object.class)) {
                  return new CustomDefinition(
                      JsonNodeFactory.instance
                          .objectNode()
                          .put(context.getKeyword(SchemaKeyword.TAG_TYPE), "object")
                          .set(
                              context.getKeyword(SchemaKeyword.TAG_ADDITIONAL_PROPERTIES),
                              SchemaNodeMapper.getSchemaType(SchemaNodeMapper.TYPES_ALL)));
                }
                return null;
              });

      // Field type remapping
      builder
          .forFields()
          // Handle Jackson annotations
          .withReadOnlyCheck(jacksonResolvers::getReadOnlyCheck)
          .withWriteOnlyCheck(jacksonResolvers::getWriteOnlyCheck)
          .withPropertyNameOverrideResolver(
              jacksonResolvers::getPropertyNameOverrideBasedOnJsonPropertyAnnotation)
          // Primitive types cannot be null, thus they are always required and handle Jackson
          // required properties
          .withRequiredCheck(
              (f) ->
                  f.getType().getErasedType().isPrimitive()
                      || jacksonResolvers.getRequiredCheckBasedOnJsonPropertyAnnotation(f))
          // Explicitly set default to false for primitive booleans
          .withDefaultResolver(CustomModule::resolveBooleanDefault)
          // direct class to class mapping through annotations
          .withTargetTypeOverridesResolver(this::remapFieldType)
          // remapping using supplier through annotations
          .withCustomDefinitionProvider(
              (fieldScope, context) -> {
                // We ignore this custom definition provider to avoid infinite recursion
                ObjectNode attrs =
                    context.createStandardDefinition(
                        fieldScope.getType(),
                        builder.forTypesInGeneral().getCustomDefinitionProviders().getFirst());
                // Apply supplied definition
                JsonSchemaSupplier ann = fieldScope.getAnnotation(JsonSchemaSupplier.class);
                if (ann != null) {
                  attrs = SchemaNodeMapper.getSuppliedNode(ann);
                }
                // Avoids annotation also being applied to the `items` in an array. See
                // https://victools.github.io/jsonschema-generator/#generator-individual-configurations
                if (!fieldScope.isFakeContainerItemScope()) {
                  applyFieldAnnotation(fieldScope, JsonSchemaFormat.class, attrs);
                  applyFieldAnnotation(fieldScope, JsonSchemaDefault.class, attrs);
                  applyFieldAnnotation(fieldScope, JsonSchemaExamples.class, attrs);
                  applyFieldAnnotation(fieldScope, JsonSchemaTemplateParameter.class, attrs);

                  String key = getCanonicalMemberKey(fieldScope);
                  if (!applyI18nAnnotation(
                      fieldScope.getAnnotation(JsonSchemaTitle.class), key, attrs)) {
                    applyFieldAnnotation(fieldScope, JsonSchemaTitle.class, attrs);
                  }
                  if (!applyI18nAnnotation(
                      fieldScope.getAnnotation(JsonSchemaDescription.class), key, attrs)) {
                    applyFieldAnnotation(fieldScope, JsonSchemaDescription.class, attrs);
                  }
                }
                String description = jacksonResolvers.resolveDescription(fieldScope);
                if (description != null) {
                  ObjectNode targetNode;
                  JsonNode allOfNode = attrs.get(context.getKeyword(SchemaKeyword.TAG_ALLOF));
                  if (allOfNode instanceof ArrayNode allOf && !allOf.isEmpty()) {
                    targetNode = (ObjectNode) allOf.get(0);
                  } else {
                    targetNode = attrs;
                  }
                  targetNode.put(context.getKeyword(SchemaKeyword.TAG_DESCRIPTION), description);
                }
                // TODO: handle allOf wrapping in JSON Forms instead
                // Inline definitions in member scope to avoid $ref collisions
                return new CustomPropertyDefinition(attrs, CustomDefinition.AttributeInclusion.YES);
              });

      // Class subtype resolver for abstract classes
      builder
          .forTypesInGeneral()
          .withCustomDefinitionProvider(
              new JsonSubTypesResolver(
                  List.of(
                      JacksonOption.ALWAYS_REF_SUBTYPES,
                      JacksonOption.INLINE_TRANSFORMED_SUBTYPES)))
          .withCustomDefinitionProvider(
              (resolvedType, context) -> {
                List<ResolvedType> subTypes = findSubtypes(resolvedType, context);
                if (subTypes == null || subTypes.isEmpty()) {
                  return null;
                }

                ObjectNode definition = context.getGeneratorConfig().createObjectNode();
                definition.put(
                    context.getKeyword(SchemaKeyword.TAG_TYPE),
                    context.getKeyword(SchemaKeyword.TAG_TYPE_OBJECT));
                ArrayNode oneOfArray =
                    definition.withArray(context.getKeyword(SchemaKeyword.TAG_ONEOF));

                for (ResolvedType subType : subTypes) {
                  oneOfArray.add(context.createDefinitionReference(subType));
                }

                Class<?> rawType = resolvedType.getErasedType();

                // This adds a discriminator property to the root of a polymorphic type that should
                // be used to
                // determine the subtype, and also adds missing subtype references (in favor of
                // com.github.victools.jsonschema.module.jackson.JsonSubTypesResolver.createSubtypeDefinition).
                // Note: The "discriminator" property is not part of the standard JSON Schema spec
                // (DRAFT-7)
                if (isPolymorphicType(rawType)) {
                  String discriminator = rawType.getAnnotation(JsonTypeInfo.class).property();
                  definition.putObject("discriminator").put("propertyName", discriminator);
                  // Removing and adding the `oneOf` property on the schema ensures the definitions
                  // are referenced
                  definition.remove(context.getKeyword(SchemaKeyword.TAG_ONEOF));
                  for (ResolvedType subType : subTypes) {
                    // Add back subtype definitions to `oneOf` property
                    definition
                        .withArray(context.getKeyword(SchemaKeyword.TAG_ONEOF))
                        .add(context.createDefinitionReference(subType));
                  }
                  return new CustomDefinition(
                      definition); // Setting ALWAYS_REF here doesn't affect referencing
                }

                // Always inline the super class schema to avoid allOf wrapping, which cannot be
                // cleaned up as
                // JSON Schema draft-7 does not permit other properties alongside a $ref, see
                // https://json-schema.org/draft-07/draft-handrews-json-schema-01#rfc.section.8.3
                return new CustomDefinition(
                    definition,
                    CustomDefinition.DefinitionType.INLINE,
                    CustomDefinition.AttributeInclusion.YES);
              });

      // Remove duplicate discriminator "type" keywords and add missing discriminator properties for
      // classes using
      // JsonTypeInfo.As.EXTERNAL_PROPERTY
      builder
          .forTypesInGeneral()
          .withTypeAttributeOverride(
              (attrs, typeScope, context) -> {
                Class<?> erasedType = typeScope.getType().getErasedType();
                if (erasedType.getSuperclass() == Object.class) {
                  return;
                }
                JsonTypeInfo annotation = resolveTypeInfoAnnotation(erasedType);
                normalizeDiscriminator(attrs, erasedType, context, annotation);
              });

      // Effectively disable const generation (on the root of subtypes)
      builder.forTypesInGeneral().withEnumResolver(typeScope -> null);

      // Custom annotations injection
      builder
          .forTypesInGeneral()
          .withTypeAttributeOverride(
              (attrs, typeScope, context) -> {
                Class<?> erasedType = typeScope.getType().getErasedType();
                ObjectNode targetNode;

                // If there is an allOf array, inject into the first object inside it to allow for
                // cleanup with `Option.ALLOF_CLEANUP_AT_THE_END`
                JsonNode allOfNode = attrs.get(context.getKeyword(SchemaKeyword.TAG_ALLOF));
                if (allOfNode instanceof ArrayNode allOf && !allOf.isEmpty()) {
                  targetNode = (ObjectNode) allOf.get(0);
                } else {
                  targetNode = attrs;
                }

                // These will be overwritten by member scope when colliding
                if (!erasedType.isArray()) {
                  applyTypeAnnotation(erasedType, JsonSchemaFormat.class, targetNode);
                  applyTypeAnnotation(erasedType, JsonSchemaDefault.class, targetNode);
                  applyTypeAnnotation(erasedType, JsonSchemaExamples.class, targetNode);

                  // TODO: Is it possible to avoid object merging here?
                  String key = erasedType.getCanonicalName();
                  if (!(attrs.has("type")
                      && applyI18nAnnotation(
                          erasedType.getAnnotation(JsonSchemaTitle.class), key, attrs))) {
                    applyTypeAnnotation(erasedType, JsonSchemaTitle.class, targetNode);
                  }
                  if (!(attrs.has("type")
                      && applyI18nAnnotation(
                          erasedType.getAnnotation(JsonSchemaDescription.class), key, attrs))) {
                    applyTypeAnnotation(erasedType, JsonSchemaDescription.class, targetNode);
                  }
                }
              });

      // Apply Jackson serializers
      builder
          .forTypesInGeneral()
          .withCustomDefinitionProvider(
              (resolvedType, context) -> {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                JavaType javaType = mapper.constructType(resolvedType.getErasedType());

                try {
                  JsonFormatVisitorWrapper visitor =
                      new JsonFormatVisitorWrapper.Base() {
                        @Override
                        public JsonStringFormatVisitor expectStringFormat(JavaType type)
                            throws JsonMappingException {
                          node.put("type", "string");
                          return new JsonStringFormatVisitor() {
                            public void format(JsonValueFormat format) {
                              setFormat(node, format.toString());
                            }

                            public void enumTypes(Set<String> enums) {
                              setEnum(node, enums);
                            }
                          };
                        }

                        @Override
                        public JsonNumberFormatVisitor expectNumberFormat(JavaType type)
                            throws JsonMappingException {
                          node.put("type", "number");
                          return new JsonNumberFormatVisitor() {
                            public void numberType(JsonParser.NumberType numberType) {}

                            public void format(JsonValueFormat format) {
                              setFormat(node, format.toString());
                            }

                            public void enumTypes(Set<String> enums) {
                              setEnum(node, enums);
                            }
                          };
                        }

                        @Override
                        public JsonIntegerFormatVisitor expectIntegerFormat(JavaType type)
                            throws JsonMappingException {
                          node.put("type", "integer");
                          return new JsonIntegerFormatVisitor() {
                            public void numberType(JsonParser.NumberType numberType) {}

                            public void format(JsonValueFormat format) {
                              setFormat(node, format.toString());
                            }

                            public void enumTypes(Set<String> enums) {
                              setEnum(node, enums);
                            }
                          };
                        }

                        @Override
                        public JsonBooleanFormatVisitor expectBooleanFormat(JavaType type)
                            throws JsonMappingException {
                          node.put("type", "boolean");
                          return new JsonBooleanFormatVisitor() {
                            public void format(JsonValueFormat format) {
                              setFormat(node, format.toString());
                            }

                            public void enumTypes(Set<String> enums) {
                              setEnum(node, enums);
                            }
                          };
                        }
                      };

                  // Let Jackson traverse the type and call the visitor
                  mapper.acceptJsonFormatVisitor(javaType, visitor);

                  if (node.has("type")) {
                    return new CustomDefinition(
                        node,
                        CustomDefinition.DefinitionType.INLINE,
                        CustomDefinition.AttributeInclusion.NO);
                  }
                } catch (Exception ignored) {
                }
                return null;
              });
    }

    private String getCanonicalMemberKey(FieldScope fieldScope) {
      return fieldScope.getMember().getDeclaringType().getErasedType().getCanonicalName()
          + "."
          + fieldScope.getMember().getName();
    }

    private static Object resolveBooleanDefault(FieldScope field) {
      if (field.getType().isInstanceOf(boolean.class)) {
        JsonSchemaDefault annotation = field.getAnnotation(JsonSchemaDefault.class);
        // If annotation is present, parse the string; otherwise, fallback to false
        return annotation != null && Boolean.parseBoolean(annotation.value());
      }
      // Return null for non-boolean fields so other resolvers can run
      return null;
    }

    private static boolean isPolymorphicType(Class<?> clazz) {
      return clazz.isAnnotationPresent(JsonTypeInfo.class)
          && (clazz.getAnnotation(JsonTypeInfo.class).include() == JsonTypeInfo.As.PROPERTY
              || clazz.getAnnotation(JsonTypeInfo.class).include()
                  == JsonTypeInfo.As.EXISTING_PROPERTY
              || clazz.getAnnotation(JsonTypeInfo.class).include()
                  == JsonTypeInfo.As.EXTERNAL_PROPERTY);
    }

    private static void setFormat(ObjectNode node, String format) {
      node.put("format", format);
    }

    private void setEnum(ObjectNode node, Set<String> values) {
      node.set(
          "enum",
          this.mapper.createArrayNode().addAll(values.stream().map(TextNode::new).toList()));
    }

    private static class JacksonResolvers extends JacksonModule {
      protected String resolveDescription(MemberScope<?, ?> member) {
        return super.resolveDescription(member);
      }

      protected String getPropertyNameOverrideBasedOnJsonPropertyAnnotation(
          MemberScope<?, ?> member) {
        return super.getPropertyNameOverrideBasedOnJsonPropertyAnnotation(member);
      }

      protected boolean getReadOnlyCheck(MemberScope<?, ?> member) {
        return super.getRequiredCheckBasedOnJsonPropertyAnnotation(member);
      }

      protected boolean getWriteOnlyCheck(MemberScope<?, ?> member) {
        return super.getRequiredCheckBasedOnJsonPropertyAnnotation(member);
      }

      protected boolean getRequiredCheckBasedOnJsonPropertyAnnotation(MemberScope<?, ?> member) {
        return super.getRequiredCheckBasedOnJsonPropertyAnnotation(member);
      }
    }

    private static class JSONSchemaTitleProvider
        implements CustomDefinitionProviderV2, TypeAttributeOverrideV2 {
      private final ThreadLocal<ResolvedType> rootType = new ThreadLocal<>();

      @Override
      public CustomDefinition provideCustomSchemaDefinition(
          ResolvedType javaType, SchemaGenerationContext context) {
        if (this.rootType.get() == null) {
          this.rootType.set(javaType);
        }
        return null;
      }

      /**
       * Matches <a
       * href="https://github.com/mbknor/mbknor-jackson-jsonSchema/blob/e370f80d5dd20eb9396455ab2ddfd7083d0e25fb/src/main/scala/com/kjetland/jackson/jsonSchema/JsonSchemaGenerator.scala#L1355">
       * mbknor-jackson-jsonSchema </a>
       *
       * @param attrs node to modify (the part that may be referenced multiple times)
       * @param scope the type representation associated with the JSON Schema node
       * @param context generation context
       */
      @Override
      public void overrideTypeAttributes(
          ObjectNode attrs, TypeScope scope, SchemaGenerationContext context) {
        if (shouldReceiveTitle(attrs, scope, context)) {
          // Code found here:
          // http://stackoverflow.com/questions/2559759/how-do-i-convert-camelcase-into-human-readable-names-in-java
          String title =
              scope
                  .getType()
                  .getErasedType()
                  .getSimpleName()
                  .replaceAll(
                      String.format(
                          "%s|%s|%s",
                          "(?<=[A-Z])(?=[A-Z][a-z])",
                          "(?<=[^A-Z])(?=[A-Z])",
                          "(?<=[A-Za-z])(?=[^A-Za-z])"),
                      " ");
          // Make the first letter uppercase
          attrs.put(
              context.getKeyword(SchemaKeyword.TAG_TITLE),
              title.substring(0, 1).toUpperCase() + title.substring(1));
        }
      }

      @Override
      public void resetAfterSchemaGenerationFinished() {
        this.rootType.remove();
      }

      private boolean shouldReceiveTitle(
          ObjectNode attrs, TypeScope scope, SchemaGenerationContext context) {
        ResolvedType type = scope.getType();
        Class<?> erasedType = type.getErasedType();
        if (attrs.has(context.getKeyword(SchemaKeyword.TAG_TITLE))
            || (type.isInstanceOf(Map.class)
                && !erasedType.getPackageName().startsWith("org.openremote.model"))
            || erasedType.isAnnotationPresent(JsonSchemaTitle.class)) {
          return false;
        }
        if (this.rootType.get() == type) {
          return true;
        }
        boolean isObject =
            attrs.has(context.getKeyword(SchemaKeyword.TAG_TYPE))
                && attrs.get(context.getKeyword(SchemaKeyword.TAG_TYPE)).isTextual()
                && attrs
                    .get(context.getKeyword(SchemaKeyword.TAG_TYPE))
                    .textValue()
                    .equals(context.getKeyword(SchemaKeyword.TAG_TYPE_OBJECT));
        return isObject || attrs.has(context.getKeyword(SchemaKeyword.TAG_ONEOF));
      }
    }

    private JsonTypeInfo resolveTypeInfoAnnotation(Class<?> type) {
      JsonTypeInfo annotation = null;

      while (annotation == null && type.getSuperclass() != Object.class) {
        type = type.getSuperclass();

        if (type == null) return annotation;

        annotation = type.getAnnotation(JsonTypeInfo.class);
      }

      return annotation;
    }

    /**
     * Find and modify the main subtype object of a subtype to add the {@code const} discriminator
     * property.
     *
     * @param attrs The {@link ObjectNode} representation of the subtype
     * @param context The schema generator {@link SchemaGenerationContext}
     */
    private void normalizeDiscriminator(
        ObjectNode attrs, Class<?> type, SchemaGenerationContext context, JsonTypeInfo annotation) {
      if (annotation == null) return;

      // If EXTERNAL_PROPERTY specified, manually add 'const', 'default' and 'required' properties
      if (annotation.include() == JsonTypeInfo.As.EXTERNAL_PROPERTY) {
        attrs
            .withArray(context.getKeyword(SchemaKeyword.TAG_REQUIRED))
            .add(context.getKeyword(SchemaKeyword.TAG_TYPE));
        attrs
            .withObject(context.getKeyword(SchemaKeyword.TAG_PROPERTIES))
            .withObject(context.getKeyword(SchemaKeyword.TAG_TYPE))
            .put(context.getKeyword(SchemaKeyword.TAG_CONST), type.getSimpleName());
        return;
      }

      String typeKey = context.getKeyword(SchemaKeyword.TAG_TYPE);
      String customTypeKey = annotation.property();
      JsonNode allOfNode = attrs.get(context.getKeyword(SchemaKeyword.TAG_ALLOF));
      if (!(allOfNode instanceof ArrayNode)) {
        JsonNode props = attrs.get(context.getKeyword(SchemaKeyword.TAG_PROPERTIES));
        if (attrs.has(typeKey)) {
          attrs.remove(typeKey);
        }
        if (props instanceof ObjectNode propsObj) {
          // Remove the type property on the type property for subtypes to enable definition merging
          propsObj.remove(typeKey);
          // If the custom type property is already present on the abstract class, the generator
          // creates a
          // duplicate which needs to be removed.
          propsObj.remove(customTypeKey);
        }
      }
    }

    private List<ResolvedType> remapFieldType(FieldScope fieldScope) {
      JsonSchemaTypeRemap ann = fieldScope.getAnnotation(JsonSchemaTypeRemap.class);
      if (ann != null) {
        try {
          return Collections.singletonList(
              fieldScope.getContext().resolve((Type) ann.getClass().getMethod("type").invoke(ann)));
        } catch (Exception e) {
          throw new RuntimeException("Failed to apply " + ann.getClass().getSimpleName(), e);
        }
      }
      return null;
    }

    private <A extends Annotation> void applyTypeAnnotation(
        Class<?> type, Class<A> annotationClass, ObjectNode schema) {
      A annotation;

      if (type.isArray() && type.getComponentType() != null) {
        annotation = type.getComponentType().getDeclaredAnnotation(annotationClass);
      } else {
        annotation = type.getDeclaredAnnotation(annotationClass);
      }

      if (annotation != null) {
        applyAnnotation(annotationClass, annotation, schema);
      }
    }

    private <A extends Annotation> void applyFieldAnnotation(
        FieldScope fieldScope, Class<A> annotationClass, ObjectNode schema) {
      A annotation = fieldScope.getAnnotation(annotationClass);
      if (annotation != null) {
        applyAnnotation(annotationClass, annotation, schema);
      }
    }

    private <A extends Annotation> void applyAnnotation(
        Class<?> annotationClass, A annotation, ObjectNode schema) {
      try {
        String keyword = (String) annotationClass.getMethod("keyword").invoke(annotation);
        Method valueMethod = annotationClass.getMethod("value");
        Class<?> returnType = valueMethod.getReturnType();
        Object value = valueMethod.invoke(annotation);

        if (returnType.isArray()) {
          ArrayNode arrayNode = schema.putArray(keyword);
          for (Object element : (Object[]) value) {
            arrayNode.add(parseJsonOrString(this.mapper, element.toString()));
          }
        } else {
          schema.set(keyword, parseJsonOrString(this.mapper, value.toString()));
        }
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to apply annotation " + annotationClass.getSimpleName(), e);
      }
    }

    private static JsonNode parseJsonOrString(ObjectMapper mapper, String input) {
      try {
        return mapper.readTree(input);
      } catch (Exception e) {
        return new TextNode(input);
      }
    }

    // TODO: handle other annotations from Jackson (or replace existing annotations)
    private static <A extends Annotation> boolean applyI18nAnnotation(
        A annotation, String key, ObjectNode schema) {
      if (annotation == null) return false;
      Class<?> annotationClass = annotation.getClass();
      try {
        if ((boolean) annotationClass.getMethod("i18n").invoke(annotation)) {
          schema.set("i18n", new TextNode(key));
          return true;
        }
      } catch (Exception e) {
        throw new RuntimeException(
            "Failed to apply i18n annotation " + annotationClass.getSimpleName(), e);
      }
      return false;
    }

    private static List<ResolvedType> findSubtypes(
        ResolvedType resolvedType, SchemaGenerationContext context) {
      Class<?> rawType = resolvedType.getErasedType();

      // Only attempt subtype discovery if @JsonTypeInfo is present
      if (!rawType.isAnnotationPresent(JsonTypeInfo.class)) {
        return null;
      }

      // Reuse Jackson Module JsonSubTypesResolver to get explicitly declared types
      if (rawType.isAnnotationPresent(JsonSubTypes.class)) {
        return new JsonSubTypesResolver().findSubtypes(resolvedType, context);
      }

      // Cached lookup
      return subtypeCache.computeIfAbsent(
          rawType,
          baseType -> {
            Set<Class<?>> found =
                reflections.getSubTypesOf(baseType).stream()
                    .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                    .collect(Collectors.toSet());

            return found.stream()
                .map(
                    sub -> {
                      try {
                        return context.getTypeContext().resolveSubtype(resolvedType, sub);
                      } catch (IllegalArgumentException e) {
                        return context.getTypeContext().resolve(sub);
                      }
                    })
                .collect(Collectors.toList());
          });
    }
  }

  public static SchemaGeneratorConfig getJsonSchemaConfig(ObjectMapper mapper) {
    return new SchemaGeneratorConfigBuilder(
            mapper,
            SchemaVersion.DRAFT_7,
            new OptionPreset(
                Option.SCHEMA_VERSION_INDICATOR,
                Option.ADDITIONAL_FIXED_TYPES,
                Option.FLATTENED_ENUMS,
                Option.VALUES_FROM_CONSTANT_FIELDS,
                Option.PUBLIC_NONSTATIC_FIELDS,
                Option.NONPUBLIC_NONSTATIC_FIELDS_WITH_GETTERS,
                Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES,
                Option.ALLOF_CLEANUP_AT_THE_END,
                Option.DUPLICATE_MEMBER_ATTRIBUTE_CLEANUP_AT_THE_END))
        .with(
            new JakartaValidationModule(
                JakartaValidationOption.NOT_NULLABLE_FIELD_IS_REQUIRED,
                JakartaValidationOption.INCLUDE_PATTERN_EXPRESSIONS))
        .with(new CustomModule(mapper))
        .build();
  }
}
