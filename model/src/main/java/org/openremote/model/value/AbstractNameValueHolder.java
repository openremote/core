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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.ValueUtil;

public abstract class AbstractNameValueHolder<T> implements NameValueHolder<T>, Serializable {
  @JsonIgnore
  @Schema(
      description = "OpenRemote value type name used to interpret and validate value.",
      example = "number")
  protected ValueDescriptor<T> type;

  @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
  @Valid @Schema(
      description = "Typed JSON value; its shape is determined by type and may be null.",
      nullable = true)
  protected T value;

  @JsonIgnore protected String valueStr; // This is for lazy value initialization when deserializing

  @NotBlank(message = "{Asset.valueHolder.name.NotBlank}") @Pattern(regexp = "^\\w+$") @Schema(description = "Machine-readable name.", example = "temperature")
  protected String name;

  protected AbstractNameValueHolder() {}

  public AbstractNameValueHolder(@Nonnull String name, ValueDescriptor<T> type, T value) {
    if (TextUtil.isNullOrEmpty(name)) {
      throw new IllegalArgumentException("name cannot be null or empty");
    }
    this.name = name;
    this.type = type;
    this.value = value;
  }

  @JsonSerialize(converter = ValueDescriptor.NameHolderToStringConverter.class)
  @Override
  @Nullable public ValueDescriptor<T> getType() {
    return type;
  }

  @Override
  public Class<?> getTypeClass() {
    return getType() != null ? getType().getType() : Object.class;
  }

  @SuppressWarnings("unchecked")
  @JsonProperty
  @Override
  public Optional<T> getValue() {
    return (Optional<T>) getValue(getTypeClass());
  }

  @SuppressWarnings("unchecked")
  @Override
  public <U> Optional<U> getValue(@Nonnull Class<U> valueType) {
    return ValueUtil.getValueCoerced(value, valueType);
  }

  @JsonProperty
  public void setValue(T value) {
    this.value = value;
    valueStr = null;
  }

  @Override
  public String getName() {
    return name;
  }

  protected void setName(String name) {
    this.name = name;
  }

  /** {@link #type} is transient so don't use it for equality checks */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    AbstractNameValueHolder<?> that = (AbstractNameValueHolder<?>) o;
    return name.equals(that.name) && ValueUtil.objectsEqualsWithJSONFallback(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value, name);
  }
}
