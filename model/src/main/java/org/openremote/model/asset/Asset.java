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
package org.openremote.model.asset;

import static jakarta.persistence.DiscriminatorType.STRING;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import com.fasterxml.jackson.databind.deser.ResolvableDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.hibernate.annotations.*;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.openremote.model.Constants;
import org.openremote.model.IdentifiableEntity;
import org.openremote.model.asset.impl.ThingAsset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeMap;
import org.openremote.model.attribute.MetaMap;
import org.openremote.model.geo.GeoJSONPoint;
import org.openremote.model.jackson.AssetTypeIdResolver;
import org.openremote.model.persistence.LTreeType;
import org.openremote.model.util.HibernateUniqueIdentifierTypeAssignable;
import org.openremote.model.util.TsIgnoreTypeParams;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.validation.AssetValid;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.ValueFormat;
import org.openremote.model.value.ValueType;

// @formatter:off

/**
 * The main model class of this software.
 *
 * <p>An asset is an identifiable item in a composite relationship with other assets. This tree of
 * assets can be managed through a <code>null</code> {@link #parentId} property for root assets, and
 * a valid parent identifier for sub-assets.
 *
 * <p>An asset is stored in and therefore access-controlled through a {@link #realm}.
 *
 * <p>The {@link #createdOn} value is milliseconds since the Unix epoch.
 *
 * <p>The {@link #getType()}} of the asset is the same value as {@link Class#getSimpleName()} and
 * should correspond with an {@link AssetDescriptor} registered within the running instance. If the
 * corresponding {@link AssetDescriptor} cannot be found then the fallback generic {@link
 * ThingAsset#DESCRIPTOR} will be assumed.
 *
 * <p>The {@link #id} is BASE-62 encoded UUID (22 characters long) and can be supplied or auto
 * generated; by allowing it to be supplied services have a predictable way to find a given asset
 * and can use the {@link org.openremote.model.util.UniqueIdentifierGenerator#generateId( String)}
 * to generate a constant ID from a given input string.
 *
 * <p>The {@link #path} is a list of parent asset identifiers, starting with the identifier of this
 * asset, followed by parent asset identifiers, and ending with the identifier of the root asset in
 * the tree. This is a transient property and only resolved and usable when the asset is loaded from
 * storage and as calculating it is costly, might be empty when certain optimized loading operations
 * are used. An asset may have 0-N {@link #attributes}; the {@link AssetDescriptor} associated with
 * an asset type describes the standard {@link Attribute}s that can be found and what the value type
 * of these {@link Attribute}s should be but additional {@link Attribute}s can also be added but
 * obviously no validation can be performed on such dynamic {@link Attribute}s. Use the {@link
 * Attribute} etc. class to work with this API. This property can be empty when certain optimized
 * loading operations are used.
 *
 * <p>For more details on restricted access of user-assigned assets, see {@link UserAssetLink}.
 *
 * <p>Example JSON representation of an asset tree:
 *
 * <blockquote>
 *
 * <pre>{@code
 * {
 * "id": "0oI7Gf_kTh6WyRJFUTr8Lg",
 * "version": 0,
 * "createdOn": 1489042784142,
 * "name": "Smart building",
 * "type": "urn:openremote:asset:building",
 * "accessPublicRead": false,
 * "realm": "building",
 * "realmDisplayName": "Building",
 * "path": [
 * "0oI7Gf_kTh6WyRJFUTr8Lg"
 * ],
 * "coordinates": [
 * 5.469751699216005,
 * 51.44760787406028
 * ]
 * }
 * }</pre>
 *
 * </blockquote>
 *
 * <blockquote>
 *
 * <pre>{@code
 * {
 * "id": "B0x8ZOqZQHGjq_l0RxAJBA",
 * "version": 0,
 * "createdOn": 1489042784148,
 * "name": "Apartment 1",
 * "type": "urn:openremote:asset:residence",
 * "accessPublicRead": false,
 * "parentId": "0oI7Gf_kTh6WyRJFUTr8Lg",
 * "parentName": "Smart building",
 * "parentType": "urn:openremote:asset:building",
 * "realm": "building",
 * "path": [
 * "B0x8ZOqZQHGjq_l0RxAJBA",
 * "0oI7Gf_kTh6WyRJFUTr8Lg"
 * ],
 * "coordinates": [
 * 5.469751699216005,
 * 51.44760787406028
 * ]
 * }
 * }</pre>
 *
 * </blockquote>
 *
 * <blockquote>
 *
 * <pre>{@code
 * {
 * "id": "bzlRiJmSSMCl8HIUt9-lMg",
 * "version": 0,
 * "createdOn": 1489042784157,
 * "name": "Living Room",
 * "type": "urn:openremote:asset:room",
 * "accessPublicRead": false,
 * "parentId": "B0x8ZOqZQHGjq_l0RxAJBA",
 * "parentName": "Apartment 1",
 * "parentType": "urn:openremote:asset:residence",
 * "realm": "building",
 * "path": [
 * "bzlRiJmSSMCl8HIUt9-lMg",
 * "B0x8ZOqZQHGjq_l0RxAJBA",
 * "0oI7Gf_kTh6WyRJFUTr8Lg"
 * ],
 * "coordinates": [
 * 5.469751699216005,
 * 51.44760787406028
 * ]
 * }
 * }</pre>
 *
 * </blockquote>
 *
 * <blockquote>
 *
 * <pre>{@code
 * {
 * "id": "W7GV_lFeQVyHLlgHgE3dEQ",
 * "version": 0,
 * "createdOn": 1489042784164,
 * "name": "Living Room Thermostat",
 * "type": "urn:openremote:asset:thing",
 * "accessPublicRead": false,
 * "parentId": "bzlRiJmSSMCl8HIUt9-lMg",
 * "parentName": "Living Room",
 * "parentType": "urn:openremote:asset:room",
 * "realm": "building",
 * "path": [
 * "W7GV_lFeQVyHLlgHgE3dEQ",
 * "bzlRiJmSSMCl8HIUt9-lMg",
 * "B0x8ZOqZQHGjq_l0RxAJBA",
 * "0oI7Gf_kTh6WyRJFUTr8Lg"
 * ],
 * "coordinates": [
 * 5.460315214821094,
 * 51.44541688237109
 * ],
 * "attributes": {
 * "currentTemperature": {
 * "meta": [
 * {
 * "name": "urn:openremote:asset:meta:label",
 * "value": "Current Temp"
 * },
 * {
 * "name": "urn:openremote:asset:meta:accessRestrictedRead",
 * "value": true
 * },
 * {
 * "name": "urn:openremote:asset:meta:readOnly",
 * "value": true
 * },
 * {
 * "name": "urn:openremote:foo:bar",
 * "value": "FOO"
 * },
 * {
 * "name": "urn:thirdparty:bar",
 * "value": "BAR"
 * }
 * ],
 * "type": "Decimal",
 * "value": 19.2,
 * "valueTimestamp": 1.489670166115E12
 * },
 * "somethingPrivate": {
 * "type": "String",
 * "value": "Foobar",
 * "valueTimestamp": 1.489670156115E12
 * }
 * }
 * }
 * }</pre>
 *
 * </blockquote>
 */
// @formatter:on
@Entity
@Table(name = "ASSET", check = @CheckConstraint(constraint = "ID != PARENT_ID"))
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "TYPE", discriminatorType = STRING)
@JsonTypeInfo(
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
    visible = true,
    use = JsonTypeInfo.Id.CUSTOM,
    defaultImpl = ThingAsset.class)
@JsonTypeIdResolver(AssetTypeIdResolver.class)
@AssetValid
@Valid @DynamicUpdate
@TsIgnoreTypeParams
@SuppressWarnings("unchecked")
@Schema(
    description =
        "Polymorphic OpenRemote asset. The type discriminator selects a registered asset subtype; attributes are keyed by attribute name.")
public abstract class Asset<T extends Asset<?>> implements IdentifiableEntity<T>, AssetInfo {

  public static final String DELETE_PENDING_PROPERTY_NAME = "deletePending";
  private static final java.util.regex.Pattern ASSET_ID_PATTERN =
      java.util.regex.Pattern.compile(Constants.ASSET_ID_REGEXP);

  /**
   * The purpose of this is to provide {@link
   * org.openremote.model.attribute.Attribute.AttributeDeserializer} access to the asset type so the
   * {@link AttributeDescriptor} can be looked up to control value deserialization; this isn't used
   * when hydrating from the DB as JPA uses its' own hydration mechanism so we also have a lazy
   * loading of attribute value mechanism.
   */
  public static class AssetDeserializer extends StdDeserializer<Asset<?>>
      implements ResolvableDeserializer {
    public static final String ASSET_TYPE_INFO_ATTRIBUTE = "assetTypeInfo";
    protected final JsonDeserializer<Asset<?>> defaultDeserializer;

    public AssetDeserializer(JsonDeserializer<Asset<?>> defaultDeserializer, Class<?> clazz) {
      super(clazz);
      this.defaultDeserializer = defaultDeserializer;
    }

    @Override
    public Asset<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
      JavaType jType = getValueType(ctxt);
      if (jType != null) {
        // Make the asset type info available to the attribute deserializer
        ctxt.setAttribute(
            ASSET_TYPE_INFO_ATTRIBUTE,
            ValueUtil.getAssetInfo(jType.getRawClass().getSimpleName()).orElse(null));
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

  /*
   * ATTRIBUTE DESCRIPTORS DESCRIBING FIXED NAME ATTRIBUTES AND THEIR VALUE TYPE - ALL SUB TYPES OF THIS ASSET TYPE
   * WILL INHERIT THESE DESCRIPTORS ALSO; IT IS REQUIRED THAT EACH DESCRIPTOR HAS CORRESPONDING GETTER WITH OPTIONAL
   * SETTER, THIS ENSURES BASIC COMPILE TIME CHECKING OF CONFLICTS BUT JUST MAKES GOOD SENSE FOR CONSUMERS
   */
  public static final AttributeDescriptor<GeoJSONPoint> LOCATION =
      new AttributeDescriptor<>("location", ValueType.GEO_JSON_POINT);

  public static final AttributeDescriptor<String> EMAIL =
      new AttributeDescriptor<>("email", ValueType.EMAIL).withOptional(true);

  public static final AttributeDescriptor<String[]> TAGS =
      new AttributeDescriptor<>("tags", ValueType.TEXT.asArray()).withOptional(true);

  public static final AttributeDescriptor<String> NOTES =
      new AttributeDescriptor<>("notes", ValueType.TEXT).withFormat(ValueFormat.TEXT_MULTILINE());
  public static final AttributeDescriptor<String> MANUFACTURER =
      new AttributeDescriptor<>("manufacturer", ValueType.TEXT).withOptional(true);
  public static final AttributeDescriptor<String> MODEL =
      new AttributeDescriptor<>("model", ValueType.TEXT).withOptional(true);

  @Id
  @HibernateUniqueIdentifierTypeAssignable
  @Column(name = "ID", length = 22, columnDefinition = "char(22)")
  @Pattern(regexp = Constants.ASSET_ID_REGEXP, message = "{Asset.id.Pattern}") @Schema(
      description = "Stable 22-character asset identifier. Omit when creating an asset.",
      example = "7A6p4AnLTkKxJUCQAAABAA")
  protected String id;

  @Version
  @Min(value = 0L, message = "{Asset.version.Min}") @Column(name = "VERSION", nullable = false)
  @Schema(
      description =
          "Optimistic-lock version. Omit when creating an asset and preserve the latest value when updating it.",
      example = "3")
  protected long version;

  @Column(
      name = "CREATED_ON",
      updatable = false,
      nullable = false,
      columnDefinition = "TIMESTAMP WITH TIME ZONE")
  @org.hibernate.annotations.CreationTimestamp
  @Schema(
      description = "Server-assigned asset creation time.",
      accessMode = Schema.AccessMode.READ_ONLY,
      example = "2026-01-01T00:00:00Z")
  protected Instant createdOn;

  @NotBlank(message = "{Asset.name.NotBlank}") @Size(min = 1, max = 1023, message = "{Asset.name.Size}") @Column(name = "NAME", nullable = false, length = 1023)
  @Schema(description = "Human-readable asset name.", example = "Boiler room sensor")
  protected String name;

  @Column(name = "ACCESS_PUBLIC_READ", nullable = false)
  @Schema(
      description =
          "Allows anonymous callers to read this asset where an endpoint supports public access.",
      example = "false")
  protected boolean accessPublicRead;

  @Column(name = "DELETE_PENDING", nullable = false)
  protected boolean deletePending;

  @Column(name = "PARENT_ID", length = 22, columnDefinition = "char(22)")
  @Pattern(regexp = Constants.ASSET_ID_REGEXP, message = "{Asset.parentId.Pattern}") @Schema(
      description = "Parent asset identifier. Omit for a root asset.",
      example = "1ZyG7BmsTQ2cGmbuaMsi0A")
  protected String parentId;

  @NotBlank(message = "{Asset.realm.NotBlank}") @Size(min = 1, max = 255, message = "{Asset.realm.Size}") @Column(name = "REALM", nullable = false, updatable = false)
  @Schema(
      description = "Realm that owns the asset; immutable after creation.",
      example = "building")
  protected String realm;

  @Column(name = "TYPE", nullable = false, updatable = false, insertable = false)
  @Schema(description = "Registered asset subtype discriminator.", example = "ThingAsset")
  protected String type = getClass().getSimpleName();

  @Column(name = "PATH", updatable = false, insertable = false, columnDefinition = LTreeType.TYPE)
  @Type(LTreeType.class)
  @Generated(event = {EventType.INSERT, EventType.UPDATE})
  @Schema(
      description = "Ancestor identifiers from the root through the parent.",
      accessMode = Schema.AccessMode.READ_ONLY)
  protected String[] path;

  @Column(name = "ATTRIBUTES")
  @JdbcTypeCode(SqlTypes.JSON)
  @Valid @Schema(
      description =
          "Attributes keyed by attribute name. Each entry contains type, value, timestamp, and optional metadata.")
  protected AttributeMap attributes;

  /** For use by hydrators (i.e. JPA/Jackson) */
  protected Asset() {}

  protected Asset(String name) {
    setName(name);

    // Initialize required attributes
    ValueUtil.initialiseAssetAttributes(this);
  }

  public String getId() {
    return id;
  }

  @Override
  public T setId(String id) {
    this.id = id;
    return (T) this;
  }

  public long getVersion() {
    return version;
  }

  public T setVersion(long version) {
    this.version = version;
    return (T) this;
  }

  public Instant getCreatedOn() {
    return createdOn;
  }

  public T setCreatedOn(Instant createdOn) {
    this.createdOn = createdOn;
    return (T) this;
  }

  public String getName() {
    return name;
  }

  public String getAssetName() {
    return name;
  }

  public T setName(String name) throws IllegalArgumentException {
    Objects.requireNonNull(name);
    this.name = name;
    return (T) this;
  }

  public String getType() {
    return type;
  }

  public boolean isAccessPublicRead() {
    return accessPublicRead;
  }

  public T setAccessPublicRead(boolean accessPublicRead) {
    this.accessPublicRead = accessPublicRead;
    return (T) this;
  }

  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public boolean isDeletePending() {
    return deletePending;
  }

  @JsonIgnore
  public T setDeletePending(boolean deletePending) {
    this.deletePending = deletePending;
    return (T) this;
  }

  public T setParent(Asset<?> parent) {
    if (parent == null) {
      parentId = null;
    } else {
      parentId = parent.id;
      realm = parent.realm;
    }
    return (T) this;
  }

  public String getParentId() {
    return parentId;
  }

  public T setParentId(String parentId) {
    this.parentId = parentId;
    return (T) this;
  }

  public String getRealm() {
    return realm;
  }

  public T setRealm(String realm) {
    this.realm = realm;
    return (T) this;
  }

  /**
   * The identifiers of all parents representing the path in the tree. The first element is the root
   * asset without a parent, the last is the identifier of this instance.
   */
  public String[] getPath() {
    return path;
  }

  public boolean pathContains(String assetId) {
    return path != null && Arrays.asList(getPath()).contains(assetId);
  }

  public AttributeMap getAttributes() {
    if (attributes == null) {
      attributes = new AttributeMap();
    }
    return attributes;
  }

  public T setAttributes(AttributeMap attributes) {
    this.attributes = attributes;
    return (T) this;
  }

  public Asset<?> setAttributes(Attribute<?>... attributes) {
    return setAttributes(Arrays.asList(attributes));
  }

  public T setAttributes(Collection<Attribute<?>> attributes) {
    this.attributes = new AttributeMap(attributes);
    return (T) this;
  }

  public <T> Optional<Attribute<T>> getAttribute(AttributeDescriptor<T> descriptor) {
    return getAttributes().get(descriptor);
  }

  public <U> Optional<Attribute<U>> getAttribute(String attributeName) {
    return getAttributes().get(attributeName).map(attribute -> (Attribute<U>) attribute);
  }

  public boolean hasAttribute(AttributeDescriptor<?> attributeDescriptor) {
    return getAttributes().has(attributeDescriptor);
  }

  public boolean hasAttribute(String attributeName) {
    return getAttributes().has(attributeName);
  }

  public T addAttributes(Attribute<?>... attributes) {
    getAttributes().addAll(attributes);
    return (T) this;
  }

  public T addOrReplaceAttributes(Attribute<?>... attributes) {
    getAttributes().addOrReplace(attributes);
    return (T) this;
  }

  @Override
  public String[] getAttributeNames() {
    return getAttributes().keySet().toArray(new String[0]);
  }

  @Override
  public String getAssetType() {
    return type;
  }

  @Override
  public Class<? extends Asset> getAssetClass() {
    return getClass();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "id='"
        + id
        + '\''
        + ", name='"
        + name
        + '\''
        + ", type ='"
        + type
        + '\''
        + ", parentId='"
        + parentId
        + '\''
        + ", realm='"
        + realm
        + '\''
        + '}';
  }

  public String toStringAll() {
    return getClass().getSimpleName()
        + "{"
        + "id='"
        + id
        + '\''
        + ", version="
        + version
        + ", createdOn="
        + createdOn
        + ", name='"
        + name
        + '\''
        + ", type='"
        + type
        + '\''
        + ", accessPublicRead='"
        + accessPublicRead
        + '\''
        + ", parentId='"
        + parentId
        + '\''
        + ", realm='"
        + realm
        + '\''
        + ", path="
        + Arrays.toString(path)
        + ", attributes="
        + getAttributesString()
        + '}';
  }

  protected String getAttributesString() {
    if (attributes == null || attributes.isEmpty()) {
      return "";
    }
    return "["
        + attributes.values().stream()
            .map(
                attr ->
                    "attr="
                        + attr.getName()
                        + ",timestamp="
                        + attr.getTimestamp().orElse(null)
                        + ",meta="
                        + getMetaString(attr.getMeta()))
            .collect(Collectors.joining("; "))
        + "]";
  }

  protected String getMetaString(MetaMap meta) {
    if (meta == null || meta.isEmpty()) {
      return "[]";
    }

    return "["
        + meta.entrySet().stream()
            .map(
                nameAndValue ->
                    "meta="
                        + nameAndValue.getKey()
                        + ",value="
                        + ValueUtil.asJSON(nameAndValue.getValue().getValue()).orElse(null))
            .collect(Collectors.joining("; "))
        + "]";
  }

  /* WELL KNOWN ATTRIBUTE GETTER / SETTERS */

  public Optional<GeoJSONPoint> getLocation() {
    return getAttributes().getValue(LOCATION);
  }

  public T setLocation(GeoJSONPoint location) {
    getAttributes().getOrCreate(LOCATION).setValue(location);
    return (T) this;
  }

  public Optional<String[]> getTags() {
    return getAttributes().getValue(TAGS);
  }

  public T setTags(String[] tags) {
    getAttributes().getOrCreate(TAGS).setValue(tags);
    return (T) this;
  }

  public Optional<String> getEmail() {
    return getAttributes().getValue(EMAIL);
  }

  public T setEmail(String email) {
    getAttributes().getOrCreate(EMAIL).setValue(email);
    return (T) this;
  }

  public Optional<String> getNotes() {
    return getAttributes().getValue(NOTES);
  }

  public T setNotes(String notes) {
    getAttributes().getOrCreate(NOTES).setValue(notes);
    return (T) this;
  }

  public Optional<String> getManufacturer() {
    return getAttributes().getValue(MANUFACTURER);
  }

  public T setManufacturer(String manufacturer) {
    getAttributes().getOrCreate(MANUFACTURER).setValue(manufacturer);
    return (T) this;
  }

  public Optional<String> getModel() {
    return getAttributes().getValue(MODEL);
  }

  public T setModel(String model) {
    getAttributes().getOrCreate(MODEL).setValue(model);
    return (T) this;
  }

  @PostLoad
  protected void postLoadCallback() {
    if (attributes != null) {
      // Make sure the attribute types are correctly set based on current asset descriptor not what
      // is in the DB
      ValueUtil.getAssetInfo(getType())
          .ifPresent(
              assetTypeInfo ->
                  attributes
                      .values()
                      .forEach(
                          attribute -> {
                            AttributeDescriptor<?> attributeDescriptor =
                                assetTypeInfo.getAttributeDescriptors().get(attribute.getName());
                            if (attributeDescriptor != null) {
                              ((Attribute) attribute)
                                  .setTypeInternal(attributeDescriptor.getType());
                            }
                          }));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Asset<?> asset = (Asset<?>) o;
    return version == asset.version
        && accessPublicRead == asset.accessPublicRead
        && deletePending == asset.deletePending
        && Objects.equals(id, asset.id)
        && Objects.equals(createdOn, asset.createdOn)
        && Objects.equals(name, asset.name)
        && Objects.equals(parentId, asset.parentId)
        && Objects.equals(realm, asset.realm)
        && Objects.equals(type, asset.type)
        && Objects.deepEquals(path, asset.path)
        && Objects.equals(attributes, asset.attributes);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        id,
        version,
        createdOn,
        name,
        accessPublicRead,
        deletePending,
        parentId,
        realm,
        type,
        Arrays.hashCode(path),
        attributes);
  }

  /**
   * Indicates if the given string matches the pattern for an Asset id.
   *
   * @param assetId String to validate. Must not be null or will throw a NullPointerException
   * @return true if matches Asset id pattern, false otherwise.
   */
  public static boolean matchesAssetIdPattern(String assetId) {
    return ASSET_ID_PATTERN.matcher(assetId).matches();
  }
}
