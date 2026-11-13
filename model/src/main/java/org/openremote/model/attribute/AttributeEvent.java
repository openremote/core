/*
 * Copyright 2023, OpenRemote Inc.
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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.annotation.Nonnull;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetInfo;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.util.TsIgnore;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.validation.AttributeInfoValid;
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.ValueDescriptor;

/** Represents an {@link Attribute} value at a point in time. */
@SuppressWarnings({"rawtypes", "unchecked"})
@AttributeInfoValid
public class AttributeEvent extends SharedEvent implements AttributeInfo {

  static {
    // Set the default view for serialization
    ValueUtil.JSON.setConfig(ValueUtil.JSON.getSerializationConfig().withView(Basic.class));
  }

  @TsIgnore
  protected interface Basic {}

  @TsIgnore
  public interface Enhanced {}

  protected AttributeRef ref;
  protected Object value;
  protected boolean deleted;
  @JsonIgnore protected String source;
  protected String realm;

  @JsonView(Enhanced.class)
  protected String parentId;

  @JsonIgnore protected ValueDescriptor<?> valueType;

  @JsonView(Enhanced.class)
  protected Object oldValue;

  @JsonView(Enhanced.class)
  protected Long oldValueTimestamp;

  @JsonView(Enhanced.class)
  protected String[] path;

  @JsonView(Enhanced.class)
  protected String assetName;

  @JsonView(Enhanced.class)
  protected String assetType;

  @JsonIgnore protected Class<? extends Asset> assetClass;

  @JsonView(Enhanced.class)
  protected Instant createdOn;

  @JsonIgnore protected MetaMap meta;

  public <T> AttributeEvent(String assetId, AttributeDescriptor<T> attributeDescriptor, T value) {
    this(assetId, attributeDescriptor.getName(), value);
  }

  public <T> AttributeEvent(
      String assetId, AttributeDescriptor<T> attributeDescriptor, T value, Long timestamp) {
    this(assetId, attributeDescriptor.getName(), value, timestamp);
  }

  public <T> AttributeEvent(
      String assetId, AttributeDescriptor<T> attributeDescriptor, T value, Instant timestamp) {
    this(assetId, attributeDescriptor.getName(), value, timestamp);
  }

  public AttributeEvent(String assetId, String attributeName, Object value) {
    this(new AttributeRef(assetId, attributeName), value);
  }

  public AttributeEvent(String assetId, String attributeName, Object value, Long timestamp) {
    this(new AttributeRef(assetId, attributeName), value, timestamp);
  }

  public AttributeEvent(String assetId, String attributeName, Object value, Instant timestamp) {
    this(new AttributeRef(assetId, attributeName), value, timestamp.toEpochMilli());
  }

  public AttributeEvent(AttributeState attributeState) {
    this(attributeState.getRef(), attributeState.getValue().orElse(null));
  }

  public AttributeEvent(AttributeState attributeState, Long timestamp) {
    this(attributeState.getRef(), attributeState.getValue().orElse(null), timestamp);
  }

  public AttributeEvent(AttributeState attributeState, Instant timestamp) {
    this(attributeState.getRef(), attributeState.getValue().orElse(null), timestamp.toEpochMilli());
  }

  public AttributeEvent(AttributeRef attributeRef, Object value) {
    this(attributeRef, value, 0L);
  }

  public AttributeEvent(AttributeRef ref, Object value, Instant timestamp) {
    this(ref, value, timestamp.toEpochMilli());
  }

  public AttributeEvent(AttributeRef ref, Object value, Long timestamp) {
    super(timestamp);
    Objects.requireNonNull(ref);
    this.ref = ref;
    this.value = value;
  }

  @JsonCreator
  // This allows backwards compatibility with old attribute event JSON that used attributeState
  protected AttributeEvent(
      AttributeState attributeState, AttributeRef ref, Object value, Long timestamp) {
    super(timestamp);
    if (attributeState != null) {
      this.ref = attributeState.getRef();
      this.value = attributeState.getValue().orElse(null);
    } else {
      this.ref = ref;
      this.value = value;
    }
  }

  public AttributeEvent(
      AssetInfo asset,
      Attribute<?> attribute,
      String source,
      Object value,
      Long valueTimestamp,
      Object oldValue,
      Long oldValueTimestamp) {
    this(new AttributeRef(asset.getId(), attribute.getName()), value, valueTimestamp);

    this.oldValue = oldValue;
    this.oldValueTimestamp = oldValueTimestamp;
    this.source = source;

    this.valueType = attribute.getType();
    this.meta = attribute.getMeta();

    this.path = asset.getPath();
    this.createdOn = asset.getCreatedOn();
    this.assetName = asset.getAssetName();
    this.assetType = asset.getAssetType();
    this.assetClass = asset.getAssetClass();
    this.parentId = asset.getParentId();
    this.realm = asset.getRealm();
  }

  @Override
  public AttributeState getState() {
    return new AttributeState(ref, value);
  }

  @Override
  public long getTimestamp() {
    return super.getTimestamp();
  }

  public Optional<Object> getOldValue() {
    return Optional.ofNullable(oldValue);
  }

  public <U> Optional<U> getOldValue(@Nonnull Class<U> valueType) {
    return ValueUtil.getValueCoerced(oldValue, valueType);
  }

  public long getOldValueTimestamp() {
    return oldValueTimestamp;
  }

  @Override
  public AttributeRef getRef() {
    return ref;
  }

  @Override
  public String getId() {
    return getRef().getId();
  }

  @Override
  public String getRealm() {
    return realm;
  }

  public AttributeEvent setRealm(String realm) {
    this.realm = realm;
    return this;
  }

  public String getSource() {
    return source;
  }

  public AttributeEvent setSource(String source) {
    this.source = source;
    return this;
  }

  public boolean isOutdated() {
    return oldValueTimestamp - getTimestamp() > 0;
  }

  @Override
  public String getParentId() {
    return parentId;
  }

  public AttributeEvent setParentId(String parentId) {
    this.parentId = parentId;
    return this;
  }

  @Override
  public String[] getPath() {
    return path;
  }

  @Override
  public String[] getAttributeNames() {
    return new String[] {getRef().getName()};
  }

  @Override
  public String getAssetName() {
    return assetName;
  }

  @Override
  public String getAssetType() {
    return assetType;
  }

  @Override
  public Class<? extends Asset> getAssetClass() {
    return assetClass;
  }

  @Override
  public Instant getCreatedOn() {
    return createdOn;
  }

  @JsonView(Enhanced.class)
  @JsonSerialize(converter = ValueDescriptor.NameHolderToStringConverter.class)
  @Override
  public ValueDescriptor getType() {
    return valueType;
  }

  @Override
  public Class<?> getTypeClass() {
    return getType() != null ? getType().getType() : Object.class;
  }

  public AttributeEvent setValue(Object value) {
    this.value = value;
    return this;
  }

  @Override
  public Optional<Object> getValue() {
    return Optional.ofNullable(value);
  }

  @Override
  public <U> Optional<U> getValue(@Nonnull Class<U> valueType) {
    return ValueUtil.getValueCoerced(value, valueType);
  }

  @JsonIgnore
  @Override
  public MetaMap getMeta() {
    return meta;
  }

  public AttributeEvent setMeta(MetaMap meta) {
    this.meta = meta;
    return this;
  }

  @JsonIgnore
  @Override
  public String getName() {
    return ref.getName();
  }

  public boolean isDeleted() {
    return deleted;
  }

  public AttributeEvent setDeleted(boolean deleted) {
    this.deleted = deleted;
    return this;
  }

  /** Compares entity identifier, attribute name, value, source, and optional timestamp. */
  public boolean matches(AttributeEvent event) {
    return getId().equals(event.getId())
        && getName().equals(event.getName())
        && isDeleted() == event.isDeleted()
        && getTimestamp() == event.getTimestamp();
  }

  @Override
  public int compareTo(AttributeInfo that) {
    int result = getId().compareTo(that.getId());
    if (result == 0) result = getName().compareTo(that.getName());
    if (result == 0) result = Long.compare(getTimestamp(), that.getTimestamp());
    return result;
  }

  public boolean valueChanged() {
    // Just use the timestamp for performance
    return oldValueTimestamp != getTimestamp();
  }

  /** Simple equality comparing {@link #getId()} and {@link #getName()} */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AttributeEvent that = (AttributeEvent) o;
    return Objects.equals(getName(), that.getName()) && Objects.equals(getId(), that.getId());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getName(), getId());
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "timestamp="
        + (timestamp != null ? timestamp : "null")
        + ", ref="
        + ref
        + ", realm="
        + realm
        + ", source="
        + source
        + ", valueType="
        + (value != null ? value.getClass().getName() : "null")
        + "}";
  }

  public String toStringWithValue() {
    String valueStr = Objects.toString(value);
    return getClass().getSimpleName()
        + "{"
        + "timestamp="
        + timestamp
        + ", ref="
        + ref
        + ", realm="
        + realm
        + ", source="
        + source
        + ", value="
        + (valueStr.length() > 100 ? valueStr.substring(0, 100) + "..." : valueStr)
        + "}";
  }
}
