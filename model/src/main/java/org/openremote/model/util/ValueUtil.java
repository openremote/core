/*
 * Copyright 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.openremote.model.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.ConstructorDetector;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.github.victools.jsonschema.generator.*;
import jakarta.persistence.Entity;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.codec.binary.BinaryCodec;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.ArrayUtils;
import org.hibernate.internal.util.SerializationHelper;
import org.hibernate.validator.constraintvalidation.HibernateConstraintValidatorContext;
import org.openremote.model.*;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.asset.AssetTypeInfo;
import org.openremote.model.asset.agent.Agent;
import org.openremote.model.asset.agent.AgentDescriptor;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.asset.agent.Protocol;
import org.openremote.model.asset.impl.UnknownAsset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.value.*;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.Serializable;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;
import static org.openremote.model.syslog.SyslogCategory.MODEL_AND_VALUES;

/**
 * Utilities for working with values/JSON and asset model
 * <p>
 * Custom descriptors can be added by simply adding new {@link Asset}/{@link Agent} sub types and following the discovery
 * rules described in {@link StandardModelProvider}; alternatively a custom {@link AssetModelProvider} implementation
 * can be created and discovered with the {@link ServiceLoader} or manually added to this class via
 * {@link ValueUtil#getModelProviders()} collection.
 */
@SuppressWarnings({"unchecked", "deprecation"})
@TsIgnore
public class ValueUtil {

    /**
     * A {@link FunctionalInterface} for populating the {@link ConstraintViolation#getPropertyPath()} for the value being
     * validated (e.g. for the value of an {@link Attribute} called 'customAttribute' the path provided should be
     * attributes["customAttribute"].value
     */
    @TsIgnore
    @FunctionalInterface
    public interface ConstraintViolationPathProvider {
        void accept(ConstraintValidatorContext.ConstraintViolationBuilder constraintViolationBuilder);
    }

    /**
     * Copied from: <a href="https://puredanger.github.io/tech.puredanger.com/2006/11/29/writing-a-class-hierarchy-comparator/">https://puredanger.github.io/tech.puredanger.com/2006/11/29/writing-a-class-hierarchy-comparator</a>
     */
    protected static class ClassHierarchyComparator implements Comparator<Class<?>> {

        public int compare(Class<?> c1, Class<?> c2) {
            if(c1 == null) {
                if(c2 == null) {
                    return 0;
                } else {
                    // Sort nulls first
                    return 1;
                }
            } else if(c2 == null) {
                // Sort nulls first
                return -1;
            }

            // At this point, we know that c1 and c2 are not null
            if(c1.equals(c2)) {
                return 0;
            }

            // At this point, c1 and c2 are not null and not equal, here we
            // compare them to see which is "higher" in the class hierarchy
            boolean c1Lower = c2.isAssignableFrom(c1);
            boolean c2Lower = c1.isAssignableFrom(c2);

            if(c1Lower && !c2Lower) {
                return 1;
            } else if(c2Lower && !c1Lower) {
                return -1;
            }

            // Doesn't matter, sort consistently on classname
            return c1.getName().compareTo(c2.getName());
        }
    }

    // TODO: consider canonicalization based on: https://www.rfc-editor.org/rfc/rfc8785
    public record SchemaResult(String schema, String hash) {
        public SchemaResult (String schema) {
            this(schema, hash(schema));
        }
        private static String hash(String schema) {
            MessageDigest md = MESSAGE_DIGEST_HOLDER.get(); md.reset();
            return bytesToHexString(md.digest(schema.getBytes(StandardCharsets.UTF_8)));
        }
    }

    // Preload the Standard model provider so it takes priority over others
    public static Logger LOG = SyslogCategory.getLogger(MODEL_AND_VALUES, ValueUtil.class);
    public static ObjectMapper JSON = configureObjectMapper(new ObjectMapper());
    protected static List<AssetModelProvider> assetModelProviders = new ArrayList<>();
    protected static Map<String, AssetTypeInfo> assetInfoMap = new HashMap<>();
    protected static Map<String, Class<? extends Asset<?>>> assetTypeMap = new HashMap<>();
    protected static Map<String, Class<? extends AgentLink<?>>> agentTypeMap = new HashMap<>();
    protected static Map<String, MetaItemDescriptor<?>> metaItemDescriptors = new HashMap<>();
    protected static Map<String, ValueDescriptor<?>> valueDescriptors = new HashMap<>();
    protected static Map<String, SchemaResult> valueDescriptorSchemas = new ConcurrentHashMap<>();
    protected static Validator validator;
    protected static SchemaGenerator generator;
    private static final ThreadLocal<MessageDigest> MESSAGE_DIGEST_HOLDER = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            LOG.severe("MD5 algorithm not supported");
            throw new RuntimeException(e);
        }
    });

    public static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        objectMapper
            .setConstructorDetector(ConstructorDetector.DEFAULT)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS, false) // see https://github.com/FasterXML/jackson-databind/issues/1547
            .configure(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, false)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .configure(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.USE_JAVA_ARRAY_FOR_JSON_ARRAY, true)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, false)
            .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY)
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new ParameterNamesModule(JsonCreator.Mode.DEFAULT))
            .registerModule(new SimpleModule()
                .setDeserializerModifier(new BeanDeserializerModifier() {
                @Override
                public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                    if (Asset.class.isAssignableFrom(beanDesc.getBeanClass())) {
                        return new Asset.AssetDeserializer((JsonDeserializer<Asset<?>>) deserializer, beanDesc.getBeanClass());
                    }
                    if (ValueDescriptor.class.isAssignableFrom(beanDesc.getBeanClass())) {
                        return new ValueDescriptor.ValueDescriptorDeserializer((JsonDeserializer<ValueDescriptor<?>>) deserializer);
                    }
                    return deserializer;
                }}));

        objectMapper.configOverride(Map.class)
            .setInclude(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));

        SimpleFilterProvider filters = new SimpleFilterProvider();
        filters.setFailOnUnknownId(false);

        objectMapper.setFilterProvider(filters);
        return objectMapper;
    }

    public static final String NULL_LITERAL = "null";

    public static Optional<Object> parse(String jsonString) {
        if (TextUtil.isNullOrEmpty(jsonString) || NULL_LITERAL.equals(jsonString)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(jsonString, Object.class));
        } catch (Exception e) {
            LOG.log(Level.INFO, "Failed to parse JSON string '" + jsonString +"': " + e.getMessage());
        }
        return Optional.empty();
    }

    public static <T> Optional<T> parse(String jsonString, Type type) {
        if (NULL_LITERAL.equals(jsonString)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(jsonString, JSON.constructType(type)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse JSON: " + e.getMessage());
        }
        return Optional.empty();
    }

    public static <T> Optional<T> parse(String jsonString, TypeReference<T> type) {
        return parse(jsonString, JSON.getTypeFactory().constructType(type));
    }

    public static <T> Optional<T> parse(String jsonString, Class<T> type) {
        return parse(jsonString, JSON.constructType(type));
    }

    public static Optional<String> asJSON(Object object) {
        try {
            return Optional.of(asJSONOrThrow(object));
        } catch (JsonProcessingException e) {
            LOG.log(Level.WARNING, "Failed to convert object to JSON string: " + e.getMessage());
            return Optional.empty();
        }
    }

    public static String asJSONOrThrow(Object object) throws JsonProcessingException {
        if (object == null) {
            return NULL_LITERAL;
        }
        return JSON.writeValueAsString(object);
    }

    @SuppressWarnings("rawtypes")
    public static <T> Optional<T> getValue(Object value, Class<T> type, boolean coerce) {
        if (value == null) {
            return Optional.empty();
        }

        if (value instanceof Optional opt) {
            if (opt.isEmpty()) {
                return Optional.empty();
            }
            value = opt.get();
        }

        if (type.isAssignableFrom(value.getClass())) {
            return Optional.of((T) value);
        }

        if (value instanceof CharSequence && String.class.isAssignableFrom(type)) {
            return Optional.of((T) String.valueOf(value));
        }

        if (Instant.class.isAssignableFrom(type)) {
            if (value instanceof Date dateValue) {
                return Optional.of((T) dateValue.toInstant());
            }
            if (value instanceof Long longValue) {
                return Optional.of((T) Instant.ofEpochMilli(longValue));
            }
            if (value instanceof Calendar calendarValue) {
                return Optional.of((T) calendarValue.toInstant());
            }
            if (value instanceof CharSequence charSequence) {
                try {
                    return Optional.of((T) TimeUtil.parseTimeIso8601(charSequence));
                } catch (Exception e) {
                    LOG.info("Failed to parse char sequence as Instant: " + e.getMessage());
                }
            }
        }

        if (value instanceof CharSequence && !coerce) {
            return Optional.empty();
        }

        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            if (Number.class.isAssignableFrom(type)) {
                if (type == Number.class && !coerce) {
                    return Optional.ofNullable((T)node.numberValue());
                } else if (type == Integer.class && (node.isInt() || coerce)) {
                    return Optional.of((T) Integer.valueOf(node.asInt()));
                } else if (type == Double.class && (node.isDouble() || coerce)) {
                    return Optional.of((T) Double.valueOf(node.asDouble()));
                } else if (type == Long.class && (node.isLong() || coerce)) {
                    return Optional.of((T) Long.valueOf(node.asLong()));
                } else if (type == BigDecimal.class && (node.isBigDecimal() || coerce)) {
                    return Optional.of((T) node.decimalValue());
                } else if (type == BigInteger.class && (node.isBigInteger() || coerce)) {
                    return Optional.of((T) node.bigIntegerValue());
                } else if (type == Short.class && (node.isShort() || coerce)) {
                    return Optional.of((T) Short.valueOf(node.shortValue()));
                }
            }
            if (CharSequence.class.isAssignableFrom(type)) {

                if (node.isTextual()) {
                    return Optional.of((T) node.asText());
                }
                if (coerce) {
                    return Optional.of((T) node.toString());
                }
            }
            if (Boolean.class == type && (node.isBoolean() || coerce)) {
                if (!node.isBoolean() && node.isTextual()) {
                    if ("TRUE".equalsIgnoreCase(node.textValue()) || "1".equalsIgnoreCase(node.textValue()) || "ON".equalsIgnoreCase(node.textValue())) {
                        return Optional.of((T) Boolean.TRUE);
                    }
                    return Optional.of((T) Boolean.FALSE);
                }
                return Optional.of((T) Boolean.valueOf(node.asBoolean()));
            }
            if ((type.isArray() && node.isArray()) || (!node.isArray() && node.isObject())) {
                try {
                    return Optional.of(((JsonNode) value).traverse().readValueAs(type));
                } catch (Exception ignored) {
                }
            }
        }

        if (coerce) {
            try {
                if (value instanceof List && type.isArray()) {
                    Class<?> innerType = type.getComponentType();
                    List list = ((List) value);

                    if (list.isEmpty()) {
                        return Optional.of((T)Array.newInstance(type.getComponentType(), 0));
                    }

                    Object[] arr = (Object[])Array.newInstance(type.getComponentType(), list.size());
                    IntStream.range(0, list.size()).forEach(i -> {
                        Object o = list.get(i);
                        if (o != null && !innerType.isAssignableFrom(o.getClass())) {
                            o = getValue(o, innerType, true).orElse(null);
                        }
                        arr[i] = o;
                    });
                    return Optional.of((T)arr);
                }

                return Optional.of(JSON.convertValue(value, type));
            } catch (Exception e) {
                if (value instanceof String) {

                    if (!((String) value).startsWith("\"")) {}

                    // Try and parse the value
                    return parse((String)value, type);
                }

                LOG.log(Level.INFO, "Failed to coerce value to requested type: input=" + value.getClass() + ", output=" + type, e);
                return Optional.empty();
            }
        }

        LOG.info("Failed to get value as requested type: input=" + value.getClass() + ", output=" + type);
        return Optional.empty();
    }

    public static <T> Optional<T> getValue(Object value, Class<T> type) {
        return getValue(value, type, false);
    }

    /**
     * Basic type coercion/casting of values; utilizes Jackson's underlying type coercion/casting mechanism
     */
    public static <T> Optional<T> getValueCoerced(Object value, Class<T> type) {
        return getValue(value, type, true);
    }

    public static Optional<String> getString(Object value) {
        return getValue(value, String.class);
    }

    public static Optional<String> getStringCoerced(Object value) {
        return getValueCoerced(value, String.class);
    }

    public static Optional<Boolean> getBoolean(Object value) {
        return getValue(value, Boolean.class);
    }

    public static Optional<Boolean> getBooleanCoerced(Object value) {
        return getValueCoerced(value, Boolean.class);
    }

    public static Optional<Integer> getInteger(Object value) {
        return getValue(value, Integer.class);
    }

    public static Optional<Integer> getIntegerCoerced(Object value) {
        return getValueCoerced(value, Integer.class);
    }

    public static Optional<Double> getDouble(Object value) {
        return getValue(value, Double.class);
    }

    public static Optional<Double> getDoubleCoerced(Object value) {
        return getValueCoerced(value, Double.class);
    }

    public static Optional<Long> getLong(Object value) {
        return getValue(value, Long.class);
    }

    public static Optional<Long> getLongCoerced(Object value) {
        return getValueCoerced(value, Long.class);
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }

    public static <T> T convert(Object object, Class<T> targetType) {
        if (object == null || object instanceof NullNode) {
            return null;
        }
        if (targetType == object.getClass()) {
            return (T)object;
        }
        if (targetType == String.class) {
            if (object instanceof TextNode) {
                return (T) ((TextNode)object).textValue();
            }
            return (T) asJSON(object).orElse(null);
        }
        return JSON.convertValue(object, targetType);
    }

    public static boolean isArray(Class<?> clazz) {
        return clazz.isArray();
    }

    public static boolean isBoolean(Class<?> clazz) {
        return clazz == Boolean.class;
    }

    public static boolean isNumber(Class<?> clazz) {
        return Number.class.isAssignableFrom(clazz);
    }

    public static boolean isString(Class<?> clazz) {
        return CharSequence.class.isAssignableFrom(clazz);
    }

    public static boolean isMap(Class<?> clazz) {
        return Map.class.isAssignableFrom(clazz);
    }

    public static boolean isCollection(Class<?> clazz) {
        return Collection.class.isAssignableFrom(clazz);
    }

    public static boolean isObject(Class<?> clazz) {
        return isMap(clazz) || (!isArray(clazz) && !isBoolean(clazz) && !isNumber(clazz) && !isString(clazz));
    }

    public static Class<?> getArrayClass(Class<?> componentType) throws ClassNotFoundException {
        String name;
        if (componentType.isArray()) {
            // just add a leading "["
            name = "[" + componentType.getName();
        } else if (componentType == boolean.class) {
            name = "[Z";
        } else if (componentType == byte.class) {
            name = "[B";
        } else if (componentType == char.class) {
            name = "[C";
        } else if (componentType == double.class) {
            name = "[D";
        } else if (componentType == float.class) {
            name = "[F";
        } else if (componentType == int.class) {
            name = "[I";
        } else if (componentType == long.class) {
            name = "[J";
        } else if (componentType == short.class) {
            name = "[S";
        } else {
            // must be an object non-array class
            name = "[L" + componentType.getName() + ";";
        }
        return Class.forName(name);
    }

    /**
     * Apply the specified set of {@link ValueFilter}s to the specified value
     */
    public static Object applyValueFilters(Object value, ValueFilter...filters) {

        if (filters == null) {
            return value;
        }

        if (value == null) {
            return null;
        }

        LOG.finest("Applying value filters to value of type: " + value.getClass().getName());

        for (ValueFilter filter : filters) {

            value = filter.filter(value);

            if (value == null) {
                break;
            }
        }

        return value;
    }

    public static <T> T clone(T object) {
        if (object == null) {
            return null;
        }

        if (object instanceof Serializable) {
            try {
                return (T) SerializationHelper.clone((Serializable) object);
            } catch (Exception e) {
                LOG.log(Level.INFO, "Failed to clone using standard java serialization, falling back to jackson object of type: " + object.getClass(), e);
            }
        }

        try {
            return JSON.readValue(JSON.writeValueAsBytes(object), (Class<T>) object.getClass());
        } catch (Exception e) {
            LOG.log(Level.INFO, "Failed to clone object of type: " + object.getClass(), e);
        }

        return null;
    }

    public static <T> TypeReference<Attribute<T>> getRef(Class<T> clazz) {
        return new TypeReference<Attribute<T>>() {};
    }

    public static Object findFirstNonNullElement(Collection<?> collection) {
        for (java.lang.Object element : collection) {
            if (element != null) {
                return element;
            }
        }
        return null;
    }

    public static Map.Entry<?,?> findFirstNonNullEntry(Map<?,?> map) {
        for (Map.Entry<?,?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                return entry;
            }
        }
        return null;
    }

    public static AssetTypeInfo[] getAssetInfos(String parentType) {
        return assetInfoMap.values().toArray(new AssetTypeInfo[0]);
    }

    public static Class<? extends Asset<?>>[] getAssetClasses(String parentType) {
        return assetTypeMap.values().toArray(new Class[0]);
    }

    public static Optional<Class<? extends Asset<?>>> getAssetClass(String assetType) {
        return Optional.ofNullable(assetTypeMap.get(assetType));
    }

    public static <T extends Asset<?>> Optional<AssetTypeInfo> getAssetInfo(Class<T> assetType) {
        return Optional.ofNullable(assetInfoMap.get(assetType.getSimpleName()));
    }

    public static Optional<AssetTypeInfo> getAssetInfo(String assetType) {
        return Optional.ofNullable(assetInfoMap.get(assetType));
    }

    // TODO: Implement ability to restrict which asset types are allowed to be added to a given parent type
    public static AssetDescriptor<?>[] getAssetDescriptors(String parentType) {
        return Arrays.stream(getAssetInfos(parentType)).map(AssetTypeInfo::getAssetDescriptor).toArray(AssetDescriptor[]::new);
    }

    public static <T extends Asset<?>> Optional<AssetDescriptor<T>> getAssetDescriptor(Class<T> assetType) {
        return getAssetInfo(assetType).map(assetInfo -> (AssetDescriptor<T>)assetInfo.getAssetDescriptor());
    }

    public static Optional<AssetDescriptor<?>> getAssetDescriptor(String assetType) {
        return getAssetInfo(assetType).map(AssetTypeInfo::getAssetDescriptor);
    }

    public static <T extends Agent<T, ?, ?>> Optional<AgentDescriptor<T, ?, ?>> getAgentDescriptor(Class<T> agentType) {
        return getAssetDescriptor(agentType)
            .map(assetDescriptor -> assetDescriptor instanceof AgentDescriptor ? (AgentDescriptor<T, ?, ?>)assetDescriptor : null);
    }

    public static Optional<AgentDescriptor<?, ?, ?>> getAgentDescriptor(String agentType) {
        return getAssetDescriptor(agentType)
            .map(assetDescriptor -> assetDescriptor instanceof AgentDescriptor ? (AgentDescriptor<?, ?, ?>)assetDescriptor : null);
    }

    public static Map<String, MetaItemDescriptor<?>> getMetaItemDescriptors() {
        return metaItemDescriptors;
    }

    public static <T extends Asset<?>> Optional<MetaItemDescriptor<?>[]> getMetaItemDescriptors(Class<T> assetType) {
        return getAssetInfo(assetType).map(AssetTypeInfo::getMetaItemDescriptors);
    }

    public static Optional<MetaItemDescriptor<?>[]> getMetaItemDescriptors(String assetType) {
        return getAssetInfo(assetType).map(AssetTypeInfo::getMetaItemDescriptors);
    }

    public static Optional<MetaItemDescriptor<?>> getMetaItemDescriptor(String name) {
        if (TextUtil.isNullOrEmpty(name)) return Optional.empty();
        return Optional.ofNullable(metaItemDescriptors.get(name));
    }

    public static Map<String, ValueDescriptor<?>> getValueDescriptors() {
        return valueDescriptors;
    }

    public static <T extends Asset<?>> Optional<ValueDescriptor<?>[]> getValueDescriptors(Class<T> assetType) {
        return getAssetInfo(assetType).map(AssetTypeInfo::getValueDescriptors);
    }

    public static Optional<ValueDescriptor<?>[]> getValueDescriptors(String assetType) {
        return getAssetInfo(assetType).map(AssetTypeInfo::getValueDescriptors);
    }

    public static Optional<ValueDescriptor<?>> getValueDescriptor(String name) {
        if (TextUtil.isNullOrEmpty(name)) return Optional.empty();

        int arrayDimensions = 0;

        while(name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2);
            arrayDimensions++;
        }

        int finalArrayDimensions = arrayDimensions;
        return Optional.ofNullable(valueDescriptors.get(name)).map(vd -> {
            int dims = finalArrayDimensions;
            while(dims > 0) {
                vd = (ValueDescriptor) vd.asArray();
                dims--;
            }
            return vd;
        });
    }

    public static ValueDescriptor<?> getValueDescriptorForValue(Object value) {
        if (value == null) {
            return null;
        }

        Class<?> valueClass = value.getClass();
        boolean isArray = valueClass.isArray();
        valueClass = isArray ? valueClass.getComponentType() : valueClass;
        ValueDescriptor<?> valueDescriptor = null;

        if (valueClass == Boolean.class) valueDescriptor = ValueType.BOOLEAN;
        else if (valueClass == String.class) valueDescriptor = ValueType.TEXT;
        else if (valueClass == Integer.class) valueDescriptor = ValueType.INTEGER;
        else if (valueClass == Long.class) valueDescriptor = ValueType.LONG;
        else if (valueClass == Double.class || valueClass == Float.class) valueDescriptor = ValueType.NUMBER;
        else if (valueClass == BigInteger.class) valueDescriptor = ValueType.BIG_INTEGER;
        else if (valueClass == BigDecimal.class) valueDescriptor = ValueType.BIG_NUMBER;
        else if (valueClass == Byte.class) valueDescriptor = ValueType.BYTE;

        return isArray && valueDescriptor != null ? valueDescriptor.asArray() : valueDescriptor;
    }

    public static List<AssetModelProvider> getModelProviders() {
        return assetModelProviders;
    }

    public static void initialise(Container container) throws IllegalStateException {
        assetModelProviders.clear();

        // Load in default model provider
        assetModelProviders.add(new StandardModelProvider());

        // Find all service loader registered asset model providers
        ServiceLoader.load(AssetModelProvider.class).forEach(assetModelProviders::add);

        if (container != null) {
            // Look for any container services that implement model provider
            assetModelProviders.addAll(Arrays.stream(container.getServices())
                .filter(service -> service instanceof AssetModelProvider)
                .map(service -> (AssetModelProvider) service)
                .collect(Collectors.toSet()));
        }

        try {
            doInitialise();
        } catch (IllegalStateException e) {
            LOG.log(Level.SEVERE, "Failed to initialize the asset model", e);
            throw e;
        }
    }

    /**
     * Initialize the asset model and throw an {@link IllegalStateException} exception if a problem is detected; this
     * can be called by applications at startup to fail hard and fast if the asset model is un-usable
     */
    static void doInitialise() throws IllegalStateException {
        generator = null;
        assetInfoMap.clear();
        assetTypeMap.clear();
        agentTypeMap.clear();
        metaItemDescriptors.clear();
        valueDescriptors.clear();

        LOG.info("Initializing asset model...");
        Set<Class<? extends Asset<?>>> assetClasses = new TreeSet<>(new ClassHierarchyComparator());
        Map<String, List<NameHolder>> assetDescriptorsMap = new HashMap<>();

        //noinspection RedundantCast
        assetClasses.add((Class<? extends Asset<?>>)(Class<?>)Asset.class);
        assetDescriptorsMap.put(Asset.class.getSimpleName(), new ArrayList<>(getDescriptorFields(Asset.class)));

        getModelProviders().forEach(assetModelProvider -> {
            LOG.fine("Processing asset model provider: " + assetModelProvider.getClass().getSimpleName());
            LOG.fine("Auto scan = " + assetModelProvider.useAutoScan());

            if (assetModelProvider.useAutoScan()) {

                Set<Class<? extends Asset<?>>> providerAssetClasses = getAssetClasses(assetModelProvider);
                LOG.fine("Found " + providerAssetClasses.size() + " asset class(es)");

                providerAssetClasses.forEach(assetClass -> {
                    assetClasses.add(assetClass);
                    assetDescriptorsMap.computeIfAbsent(assetClass.getSimpleName(), name ->
                        new ArrayList<>(getDescriptorFields(assetClass)));
                });

                ModelDescriptors modelDescriptors = assetModelProvider.getClass().getAnnotation(ModelDescriptors.class);
                if (modelDescriptors != null) {
                    for (ModelDescriptor modelDescriptor : modelDescriptors.value()) {
                        Class<? extends Asset<?>> assetClass = (Class<? extends Asset<?>>)modelDescriptor.assetType();

                        assetClasses.add(assetClass);
                        assetDescriptorsMap.compute(assetClass.getSimpleName(), (name, list) -> {
                            if (list == null) {
                                list = new ArrayList<>();
                            }

                            List<NameHolder> descriptors = getDescriptorFields(modelDescriptor.provider());
                            list.addAll(descriptors);
                            // Push value descriptors straight in so they are accessible to asset model providers
                            descriptors.forEach(d -> {
                                if (d instanceof ValueDescriptor vd) {
                                    ValueUtil.valueDescriptors.put(vd.getName(), vd);
                                }
                            });
                            return list;
                        });
                    }
                }
            }

            Map<String, Collection<ValueDescriptor<?>>> valueDescriptors1 = assetModelProvider.getValueDescriptors();
            if (valueDescriptors1 != null) {
                AtomicInteger count = new AtomicInteger(0);
                valueDescriptors1.forEach((name, valueDescriptors) -> {
                    assetClasses.stream().filter(c -> c.getSimpleName().equals(name)).findFirst().ifPresent(assetClasses::add);
                    assetDescriptorsMap.compute(name, (n, list) -> {
                        if (list == null) {
                            list = new ArrayList<>();
                        }

                        list.addAll(valueDescriptors);
                        count.addAndGet(valueDescriptors.size());
                        // Push value descriptors straight in so they are accessible to asset model providers
                        valueDescriptors.forEach(vd -> ValueUtil.valueDescriptors.put(vd.getName(), vd));
                        return list;
                    });
                });
                LOG.fine("Found " + count.get() + " value descriptors");
            }

            Map<String, Collection<MetaItemDescriptor<?>>> metaItemDescriptors = assetModelProvider.getMetaItemDescriptors();
            if (metaItemDescriptors != null) {
                AtomicInteger count = new AtomicInteger(0);
                metaItemDescriptors.forEach((name, metaDescriptors) -> {
                    assetClasses.stream().filter(c -> c.getSimpleName().equals(name)).findFirst().ifPresent(assetClasses::add);
                    assetDescriptorsMap.compute(name, (n, list) -> {
                        if (list == null) {
                            list = new ArrayList<>();
                        }

                        list.addAll(metaDescriptors);
                        count.addAndGet(metaDescriptors.size());
                        return list;
                    });
                });
                LOG.fine("Found " + count.get() + " meta item descriptors");
            }

            Map<String, Collection<AttributeDescriptor<?>>> attributeDescriptors1 = assetModelProvider.getAttributeDescriptors();
            if (attributeDescriptors1 != null) {
                AtomicInteger count = new AtomicInteger(0);
                attributeDescriptors1.forEach((name, attributeDescriptors) -> {
                    assetClasses.stream().filter(c -> c.getSimpleName().equals(name)).findFirst().ifPresent(assetClasses::add);
                    assetDescriptorsMap.compute(name, (n, list) -> {
                        if (list == null) {
                            list = new ArrayList<>();
                        }

                        list.addAll(attributeDescriptors);
                        count.addAndGet(attributeDescriptors.size());
                        return list;
                    });
                });
                LOG.fine("Found " + count.get() + " attribute descriptors");
            }

            AssetDescriptor<?>[] assetDescriptors = assetModelProvider.getAssetDescriptors();
            if (assetDescriptors != null) {
                LOG.fine("Found " + assetDescriptors.length + " asset descriptors");
                for (AssetDescriptor<?> assetDescriptor : assetDescriptors) {
                    Class<? extends Asset<?>> assetClass = assetDescriptor.getType();
                    if (assetClass != null) {
                        assetClasses.add(assetClass);
                    }
                    assetDescriptorsMap.compute(assetDescriptor.getName(), (name, list) -> {
                        if (list == null) {
                            list = new ArrayList<>();
                        }

                        list.add(assetDescriptor);
                        return list;
                    });
                }
            }
        });

        // Build each asset info checking that no conflicts occur
        Map<String, List<NameHolder>> copy = new HashMap<>(assetDescriptorsMap);
        assetDescriptorsMap.forEach((name, descriptors) -> {
            Class<? extends Asset<?>> assetClass = assetClasses.stream().filter(c -> c.getSimpleName().equals(name)).findFirst().orElse(null);

            if (assetClass == null) {
                AssetTypeInfo assetInfo = buildAssetInfoFromName(name, copy);

                if (assetInfo != null) {
                    assetInfoMap.put(name, assetInfo);
                }
            } else {
                // Skip abstract classes as a start point - they should be in the class hierarchy of concrete class
                if (!Modifier.isAbstract(assetClass.getModifiers())) {

                    AssetTypeInfo assetInfo = buildAssetInfoFromClass(assetClass, copy);

                    if (assetInfo != null) {
                        assetInfoMap.put(assetClass.getSimpleName(), assetInfo);
                        assetTypeMap.put(assetInfo.getAssetDescriptor().getName(), assetClass);

                        if (assetInfo.getAssetDescriptor() instanceof AgentDescriptor<?, ?, ?> agentDescriptor) {
                            String agentLinkName = agentDescriptor.getAgentLinkClass().getSimpleName();
                            if (agentTypeMap.containsKey(agentLinkName) && agentTypeMap.get(agentLinkName) != agentDescriptor.getAgentLinkClass()) {
                                throw new IllegalStateException("AgentLink simple class name must be unique, duplicate found for: " + agentDescriptor.getAgentLinkClass());
                            }
                            agentTypeMap.put(agentLinkName, agentDescriptor.getAgentLinkClass());
                        }
                    }
                }
            }
        });

        // RT: Think this as needed for hibernate-types lib that is no longer used
//        // Check each value type implements serializable interface
//        List<ValueDescriptor<?>> nonSerializableValueDescriptors = new ArrayList<>();
//        valueDescriptors.values().forEach(vd -> {
//            if (!Serializable.class.isAssignableFrom(vd.getType())) {
//                nonSerializableValueDescriptors.add(vd);
//            }
//        });
//
//        if (!nonSerializableValueDescriptors.isEmpty()) {
//            String vds = nonSerializableValueDescriptors.stream().map(ValueDescriptor::toString).collect(Collectors.joining(",\n"));
//            throw new IllegalStateException("One or more value types do not implement java.io.Serializable: " + vds);
//        }

        // Call on finished on each provider
        assetModelProviders.forEach(AssetModelProvider::onAssetModelFinished);

        // Add agent link sub types to object mapper (need to avoid circular dependency)
        NamedType[] agentLinkSubTypes = Arrays.stream(getAgentLinkClasses())
            .map(agentLinkClass -> new NamedType(
                agentLinkClass,
                agentLinkClass.getSimpleName()
            )).toArray(NamedType[]::new);
        JSON.registerSubtypes(agentLinkSubTypes);

        doSchemaInit();
    }

    protected static void doSchemaInit() {
        generator = new SchemaGenerator(JSONSchemaUtil.getJsonSchemaConfig(JSON));
    }

    /**
     * Computes JSON schemas for complex meta items and generates cache keys
     */
    public static void cacheCommonValueDescriptorSchemas() {
        Map<String, MetaItemDescriptor<?>> metaItems = getMetaItemDescriptors();
        valueDescriptorSchemas.putAll(metaItems.values().stream()
                .map(AbstractNameValueDescriptorHolder::getType)
                .filter(v -> v.getJsonType().equals("object") || v.getArrayDimensions() != null && v.getArrayDimensions() > 0)
                .collect(Collectors.toMap(ValueDescriptor::getName, vd -> new SchemaResult(getSchema(vd.getType()).toString())))
        );
    }

    public static SchemaResult getValueDescriptorSchema(String name) {
        if (ValueUtil.getValueDescriptor(name).isEmpty()) {
            return null;
        }
        ValueDescriptor<?> vd = ValueUtil.getValueDescriptor(name).get();
        return valueDescriptorSchemas.computeIfAbsent(vd.getName(), key -> new SchemaResult(getSchema(vd.getType()).toString()));
    }

    protected static Class<?>[] getAgentLinkClasses() {
        return Arrays.stream(getAssetDescriptors(null))
            .filter(descriptor -> descriptor instanceof AgentDescriptor)
            .map(descriptor ->
                ((AgentDescriptor<?, ?, ?>) descriptor).getAgentLinkClass()
            )
            .distinct()
            .toArray(Class<?>[]::new);
    }

    /**
     * Validates the supplied object using standard JSR-380 bean validation; therefore any type passed in here must
     * follow the JSR-380 annotation requirements.
     */
    // TODO: Implement validation using javax bean validation JSR-380
    public static <T> Set<ConstraintViolation<T>> validate(@NotNull T obj, Class<?>... groups) {

        Validator validator = getValidator();
        return validator.validate(obj, groups);
    }

    public static Validator getValidator() {
        if (validator == null) {
            validator = Validation
                .buildDefaultValidatorFactory()
                .getValidator();
        }

        return validator;
    }

    /**
     * Evaluate the validity of a value dynamically by extracting {@link ValueConstraint}s from the supplied parameters;
     * each found constraint is then evaluated against the value supplied to the function.
     * <p>
     * Unfortunately JSR-380 can't be used (even Hibernate validator's programmatic API) because validators are type
     * centric but here the type (e.g. {@link Attribute} or {@link org.openremote.model.attribute.AttributeInfo}) is fixed
     * but the constraints to be applied are dynamic, would be nice if there was a solution to this problem but this
     * works for now.
     */
    // TODO: Implement some sort of caching if performance warrants it
    public static boolean validateValue(AttributeDescriptor<?> attributeDescriptor, ValueDescriptor<?> valueDescriptor, MetaHolder metaHolder, Instant now, ConstraintValidatorContext context, ConstraintViolationPathProvider constraintBuilderProvider, Object value) {
        boolean valid = true;

        if (valueDescriptor != null && valueDescriptor.getConstraints() != null) {
            if (validateConstraints(valueDescriptor.getArrayDimensions(), valueDescriptor.getConstraints(), context, constraintBuilderProvider, now, value)) {
                valid = false;
            }
        }
        if (attributeDescriptor != null && attributeDescriptor.getConstraints() != null) {
            if (validateConstraints(valueDescriptor.getArrayDimensions(), attributeDescriptor.getConstraints(), context, constraintBuilderProvider, now, value)) {
                valid = false;
            }
        }
        if (attributeDescriptor != null && attributeDescriptor.getMeta() != null) {
            if (attributeDescriptor.getMeta().get(MetaItemType.CONSTRAINTS).flatMap(ValueHolder::getValue).map(constraints ->
                Arrays.stream(constraints).map(constraint -> validateValueConstraint(context, constraintBuilderProvider, now, constraint, value)).anyMatch(constraintValid -> !constraintValid)
            ).orElse(false)) {
                valid = false;
            }
        }
        if (metaHolder != null && metaHolder.getMeta() != null) {
            if (metaHolder.getMeta().get(MetaItemType.CONSTRAINTS).flatMap(ValueHolder::getValue).map(constraints ->
                Arrays.stream(constraints).map(constraint -> validateValueConstraint(context, constraintBuilderProvider, now, constraint, value)).anyMatch(constraintValid -> !constraintValid)
            ).orElse(false)) {
                valid = false;
            }
        }
        return valid;
    }

    // TODO: as we recurse, we don't change the path, the error will always be reported on the attribute, not on a specific array index, can we live with that ?
    private static boolean validateConstraints(Integer dimensions, ValueConstraint[] constraints, ConstraintValidatorContext context, ConstraintViolationPathProvider constraintBuilderProvider, Instant now, Object value) {
        if (dimensions == null || dimensions == 0 || value == null) {
            return Arrays.stream(constraints).map(constraint -> validateValueConstraint(context, constraintBuilderProvider, now, constraint, value)).anyMatch(constraintValid -> !constraintValid);
        } else {
            return Arrays.stream((Object[])value).anyMatch(v -> validateConstraints(dimensions - 1, constraints, context, constraintBuilderProvider, now, v));
        }
    }

    public static boolean validateValueConstraint(ConstraintValidatorContext context, ConstraintViolationPathProvider constraintViolationPathProvider, Instant now, ValueConstraint valueConstraint, Object value) {
        if (!valueConstraint.evaluate(value, now)) {
            if (context instanceof HibernateConstraintValidatorContext hibernateContext) {
                Map<String, Object> messageParams = valueConstraint.getParameters();
                if (messageParams != null) {
                    messageParams.forEach(hibernateContext::addMessageParameter);
                }
            }
            ConstraintValidatorContext.ConstraintViolationBuilder constraintBuilder = context.buildConstraintViolationWithTemplate(valueConstraint.getMessage().orElse(ValueConstraint.VALUE_CONSTRAINT_INVALID));
            constraintViolationPathProvider.accept(constraintBuilder);
            constraintBuilder.addConstraintViolation();
            return false;
        }
        return true;
    }

    /**
     * Returns the schema for the specified type
     */
    public static JsonNode getSchema(Class<?> clazz) {
        if (generator == null) {
            return JSON.createObjectNode();
        }
        return generator.generateSchema(clazz);
    }

    public static void initialiseAssetAttributes(Asset<?> asset) throws IllegalStateException {
        AssetTypeInfo assetInfo = getAssetInfo(asset.getType()).orElseThrow(() -> new IllegalStateException("Cannot get asset model info for requested asset type: " + asset.getType()));
        asset.getAttributes().addOrReplace(
            assetInfo.getAttributeDescriptors().values().stream()
            .filter(attributeDescriptor -> !attributeDescriptor.isOptional())
            .map(Attribute::new)
            .collect(Collectors.toList())
        );
    }

    public static Object[] getObjectFieldValues(Object object, String[] fieldNames) {
        return Arrays.stream(fieldNames).map(fieldName -> getObjectFieldValue(object, fieldName)).toArray();
    }

    public static Object[] getObjectFieldValues(Object object, Field[] fields) {
        return Arrays.stream(fields).map(field -> getObjectFieldValue(object, field)).toArray();
    }

    public static Object getObjectFieldValue(Object object, String fieldName) {
        try {
            Field field = object.getClass().getDeclaredField(fieldName);
            return getObjectFieldValue(object, field);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("Specified field could not be found: class=" + object.getClass().getSimpleName() + ", field=" + fieldName);
        }
    }
    public static Object getObjectFieldValue(Object object, Field field) {
        field.setAccessible(true);
        try {
            return field.get(object);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to get field value: class=" + object.getClass().getSimpleName() + ", field=" + field.getName());
        }
    }

    public static boolean objectsEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null || a.getClass() != b.getClass()) return false;

        if (Objects.equals(a, b)) {
            return true;
        }

        if (a.getClass().isArray()) {
            Class<?> arrayType = a.getClass().getComponentType();
            if (arrayType.isPrimitive()) {
                if (arrayType == boolean.class) {
                    return Arrays.equals((boolean[]) a, (boolean[]) b);
                }
                if (arrayType == int.class) {
                    return Arrays.equals((int[]) a, (int[]) b);
                }
                if (arrayType == double.class) {
                    return Arrays.equals((double[]) a, (double[]) b);
                }
                if (arrayType == float.class) {
                    return Arrays.equals((float[]) a, (float[]) b);
                }
                if (arrayType == long.class) {
                    return Arrays.equals((long[]) a, (long[]) b);
                }
                if (arrayType == short.class) {
                    return Arrays.equals((short[]) a, (short[]) b);
                }
                if (arrayType == byte.class) {
                    return Arrays.equals((byte[]) a, (byte[]) b);
                }
                if (arrayType == char.class) {
                    return Arrays.equals((char[]) a, (char[]) b);
                }
                return false;
            }
            return Arrays.deepEquals((Object[]) a,(Object[]) b);
        }
        return false;
    }

    public static boolean objectsEqualsWithJSONFallback(Object a, Object b) {
        return objectsEquals(a, b) || objectsEqualsUsingJSON(a, b);
    }

    public static boolean objectsEqualsUsingJSON(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null || a.getClass() != b.getClass()) return false;
        return Objects.equals(ValueUtil.convert(a, JsonNode.class), ValueUtil.convert(b, JsonNode.class));
    }

    protected static boolean isGetter(Method method) {
        if (Modifier.isPublic(method.getModifiers()) &&
            method.getParameterTypes().length == 0) {
            if (method.getName().matches("^get[A-Z].*") &&
                !method.getReturnType().equals(void.class))
                return true;
            if (method.getName().matches("^is[A-Z].*") &&
                method.getReturnType().equals(boolean.class))
                return true;
        }
        return false;
    }

    protected static Set<Class<? extends Asset<?>>> getAssetClasses(AssetModelProvider assetModelProvider) {

        Set<Class<? extends Asset<?>>> assetClasses;

        // Search for concrete asset classes in the same JAR as the provided AssetModelProvider
        Reflections reflections = new Reflections(new ConfigurationBuilder()
            .setUrls(ClasspathHelper.forClass(assetModelProvider.getClass()))
            .setScanners(
                new SubTypesScanner(true)
            ));

        LOG.fine("Scanning for Asset classes");

        assetClasses = reflections.getSubTypesOf(Asset.class).stream()
            .map(assetClass -> (Class<? extends Asset<?>>)assetClass)
            .filter(assetClass -> assetClass.getAnnotation(ModelIgnore.class) == null)
            .collect(Collectors.toSet());

        LOG.fine("Found asset class count = " + assetClasses.size());

        return assetClasses;
    }

    /**
     * Extract public static field values that are of type {@link AssetDescriptor}, {@link AttributeDescriptor}, {@link MetaItemDescriptor} or {@link ValueDescriptor}.
     */
    protected static List<NameHolder> getDescriptorFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .filter(field ->
                isStatic(field.getModifiers())
                && isPublic(field.getModifiers())
                && (AssetDescriptor.class.isAssignableFrom(field.getType())
                    || AttributeDescriptor.class.isAssignableFrom(field.getType())
                    || MetaItemDescriptor.class.isAssignableFrom(field.getType())
                    || ValueDescriptor.class.isAssignableFrom(field.getType())))
            .map(field -> {
                try {
                    return (NameHolder)field.get(null);
                } catch (IllegalAccessException e) {
                    String msg = "Failed to extract descriptor fields from class: " + type.getName();
                    LOG.log(Level.SEVERE, msg, e);
                    throw new IllegalStateException(msg);
                }
            })
            .collect(Collectors.toList());
    }

    protected static AssetTypeInfo buildAssetInfoFromName(String name, Map<String, List<NameHolder>> classDescriptorMap) throws IllegalStateException {
        List<NameHolder> descriptors = classDescriptorMap.get(name);
        if (descriptors == null) {
            return null;
        }

        AtomicReference<AssetDescriptor<?>> assetDescriptor = new AtomicReference<>();
        Set<AttributeDescriptor<?>> attributeDescriptors = new HashSet<>(10);
        List<MetaItemDescriptor<?>> metaItemDescriptors = new ArrayList<>(50);
        List<ValueDescriptor<?>> valueDescriptors = new ArrayList<>(50);

        descriptors.forEach(descriptor -> {
            if (descriptor instanceof AssetDescriptor<?> ad) {
                if (assetDescriptor.get() != null) {
                    throw new IllegalStateException("Duplicate asset descriptor found: asset type=" + name +", descriptor=" + assetDescriptor.get() + ", duplicate descriptor=" + descriptor);
                }
                assetDescriptor.set(ad);
            } else if (descriptor instanceof AttributeDescriptor) {
                attributeDescriptors.stream().filter(d -> d.equals(descriptor)).findFirst()
                    .ifPresent(existingDescriptor -> {
                        if (!existingDescriptor.getType().equals(((AttributeDescriptor<?>) descriptor).getType())) {
                            throw new IllegalStateException("Attribute descriptor override cannot change the value type found: asset type=" + name + ", descriptor=" + existingDescriptor + ", duplicate descriptor=" + descriptor);
                        }
                        attributeDescriptors.remove(existingDescriptor);
                    });
                attributeDescriptors.add((AttributeDescriptor<?>) descriptor);
            } else if (descriptor instanceof MetaItemDescriptor metaItemDescriptor) {
                MetaItemDescriptor<?> existing = ValueUtil.metaItemDescriptors.get(descriptor.getName());
                if (existing != null && !existing.equals(descriptor)) {
                    throw new IllegalStateException("Duplicate meta item descriptor found: asset type=" + name + ", descriptor=" + existing + ", duplicate descriptor=" + descriptor);
                }
                metaItemDescriptors.add(metaItemDescriptor);
                if (existing == null) {
                    ValueUtil.metaItemDescriptors.put(metaItemDescriptor.getName(), metaItemDescriptor);
                }
            } else if (descriptor instanceof ValueDescriptor valueDescriptor) {
                // Only store basic value type ignore array type for value descriptor as any value descriptor can be an array value descriptor
                valueDescriptor = valueDescriptor.asNonArray();
                ValueDescriptor<?> existing = ValueUtil.valueDescriptors.get(descriptor.getName());
                if (existing != null && !existing.equals(descriptor)) {
                    throw new IllegalStateException("Duplicate value descriptor found: asset type=" + name +", descriptor=" + existing + ", duplicate descriptor=" + descriptor);
                }
                valueDescriptors.add(valueDescriptor);
                if (existing == null) {
                    ValueUtil.valueDescriptors.put(valueDescriptor.getName(), valueDescriptor);
                }
            }
        });

        if (assetDescriptor.get() == null) {
            LOG.log(Level.WARNING, "Asset descriptor not found for this asset type: asset type=" + name);
            return null;
        }

        return new AssetTypeInfo(
            assetDescriptor.get(),
            attributeDescriptors.toArray(new AttributeDescriptor<?>[0]),
            metaItemDescriptors.toArray(new MetaItemDescriptor<?>[0]),
            valueDescriptors.toArray(new ValueDescriptor<?>[0]));
    }

    protected static AssetTypeInfo buildAssetInfoFromClass(Class<? extends Asset<?>> assetClass, Map<String, List<NameHolder>> classDescriptorMap) throws IllegalStateException {

        if (assetClass == UnknownAsset.class) {
            return null;
        }

        Class<?> currentClass = assetClass;
        List<Class<?>> classTree = new ArrayList<>();

        while (Asset.class.isAssignableFrom(currentClass)) {
            classTree.add(currentClass);
            currentClass = currentClass.getSuperclass();
        }

        // Check asset class has JPA entity annotation for JPA polymorphism
        if (assetClass.getAnnotation(Entity.class) == null) {
            throw new IllegalStateException("Asset class must have @Entity JPA annotation for polymorphic JPA support: " + assetClass);
        }

        if (Arrays.stream(assetClass.getDeclaredConstructors()).noneMatch(ctor -> ctor.getParameterCount() == 0)) {
            throw new IllegalStateException("Asset class must have a no args constructor for JPA support: " + assetClass);
        }

        // Order from Asset class downwards
        Collections.reverse(classTree);

        AtomicReference<AssetDescriptor<?>> assetDescriptor = new AtomicReference<>();
        Set<AttributeDescriptor<?>> attributeDescriptors = new HashSet<>(10);
        List<MetaItemDescriptor<?>> metaItemDescriptors = new ArrayList<>(50);
        List<ValueDescriptor<?>> valueDescriptors = new ArrayList<>(50);

        classTree.forEach(aClass -> {
            List<NameHolder> descriptors = classDescriptorMap.get(aClass.getSimpleName());
            if (descriptors != null) {
                descriptors.forEach(descriptor -> {
                    if (aClass == assetClass && descriptor instanceof AssetDescriptor) {
                        if (assetDescriptor.get() != null) {
                            throw new IllegalStateException("Duplicate asset descriptor found: asset type=" + assetClass +", descriptor=" + assetDescriptor.get() + ", duplicate descriptor=" + descriptor);
                        }
                        assetDescriptor.set((AssetDescriptor<?>) descriptor);
                    } else if (descriptor instanceof AttributeDescriptor) {
                        attributeDescriptors.stream().filter(d -> d.equals(descriptor)).findFirst()
                            .ifPresent(existingDescriptor -> {
                                if (!existingDescriptor.getType().equals(((AttributeDescriptor<?>) descriptor).getType())) {
                                    throw new IllegalStateException("Attribute descriptor override cannot change the value type found: asset type=" + assetClass + ", descriptor=" + existingDescriptor + ", duplicate descriptor=" + descriptor);
                                }
                                attributeDescriptors.remove(existingDescriptor);
                            });
                        attributeDescriptors.add((AttributeDescriptor<?>) descriptor);
                    } else if (descriptor instanceof MetaItemDescriptor metaItemDescriptor) {
                        MetaItemDescriptor<?> existing = ValueUtil.metaItemDescriptors.get(descriptor.getName());
                        if (existing != null && !existing.equals(descriptor)) {
                            throw new IllegalStateException("Duplicate meta item descriptor found: asset type=" + assetClass +", descriptor=" + existing + ", duplicate descriptor=" + descriptor);
                        }
                        metaItemDescriptors.add(metaItemDescriptor);
                        if (existing == null) {
                            ValueUtil.metaItemDescriptors.put(metaItemDescriptor.getName(), metaItemDescriptor);
                        }
                    } else if (descriptor instanceof ValueDescriptor valueDescriptor) {
                        // Only store basic value type ignore array type for value descriptor as any value descriptor can be an array value descriptor
                        valueDescriptor = valueDescriptor.asNonArray();
                        ValueDescriptor<?> existing = ValueUtil.valueDescriptors.get(descriptor.getName());
                        if (existing != null && !existing.equals(descriptor)) {
                            throw new IllegalStateException("Duplicate value descriptor found: asset type=" + assetClass +", descriptor=" + existing + ", duplicate descriptor=" + descriptor);
                        }
                        valueDescriptors.add(valueDescriptor);
                        if (existing == null) {
                            ValueUtil.valueDescriptors.put(valueDescriptor.getName(), valueDescriptor);
                        }
                    }
                });
            }
        });

        if (assetDescriptor.get() == null || assetDescriptor.get().getType() != assetClass) {
            throw new IllegalStateException("Asset descriptor not found or is not for this asset type: asset type=" + assetClass +", descriptor=" + assetDescriptor.get());
        }

        return new AssetTypeInfo(
            assetDescriptor.get(),
            attributeDescriptors.toArray(new AttributeDescriptor<?>[0]),
            metaItemDescriptors.toArray(new MetaItemDescriptor<?>[0]),
            valueDescriptors.toArray(new ValueDescriptor<?>[0]));
    }

    public static String bytesToHexString(byte[] bytes) {
        return Hex.encodeHexString(bytes).toUpperCase(Locale.ROOT);
    }

    public static byte[] bytesFromHexString(String hex) {
        try {
            return Hex.decodeHex(hex.toCharArray());
        } catch (Exception e) {
            Protocol.LOG.log(Level.WARNING, "Failed to convert hex string to bytes", e);
            return new byte[0];
        }
    }

    public static String bytesToBinaryString(byte[] bytes) {
        // Need to reverse the array to get a sensible string out
        ArrayUtils.reverse(bytes);
        return BinaryCodec.toAsciiString(bytes);
    }

    public static byte[] bytesFromBinaryString(String binary) {
        try {
            return BinaryCodec.fromAscii(binary.toCharArray());
        } catch (Exception e) {
            Protocol.LOG.log(Level.WARNING, "Failed to convert hex string to bytes", e);
            return new byte[0];
        }
    }

    public static String doDynamicTimeReplace(String str, Instant instant) {
        if (TextUtil.isNullOrEmpty(str)) {
            return str;
        }

        Pattern pattern = Pattern.compile(Constants.DYNAMIC_TIME_PLACEHOLDER_REGEXP);
        Matcher matcher = pattern.matcher(str);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {

            String durationStr = matcher.group(1);
            String formatStr = matcher.group(2);

            // Parse the duration
            if (durationStr != null) {
                Duration duration = Duration.parse(durationStr);
                instant = instant.plus(duration);
            }

            // Apply formatting
            if (formatStr == null) formatStr = "";

            String formattedDate = switch (formatStr) {
                case "" -> instant.toString();
                case "EPOCH_SECONDS" -> String.valueOf(instant.getEpochSecond());
                case "EPOCH_MILLIS" -> String.valueOf(instant.toEpochMilli());
                default -> {
                    ZonedDateTime zonedDateTime = instant.atZone(ZoneId.systemDefault());
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatStr);
                    yield zonedDateTime.format(formatter);
                }
            };

            matcher.appendReplacement(result, formattedDate);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    public static String doDynamicValueReplace(String str, Object value) {
        if (TextUtil.isNullOrEmpty(str)) {
            return str;
        }

        Pattern pattern = Pattern.compile(Constants.DYNAMIC_VALUE_PLACEHOLDER_REGEXP);
        Matcher matcher = pattern.matcher(str);

        StringBuilder result = new StringBuilder();

        while (matcher.find()) {

            String formatStr = matcher.group(1);
            String formattedValue;

            // Apply formatting
            if (TextUtil.isNullOrEmpty(formatStr)) {
                formattedValue = value == null ? NULL_LITERAL : ValueUtil.convert(value, String.class);
            } else {
                formattedValue = String.format(formatStr, value);
            }
            matcher.appendReplacement(result, formattedValue);
        }
        matcher.appendTail(result);

        return result.toString();
    }
}
