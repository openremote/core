/*
 * Copyright 2016, OpenRemote Inc.
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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import org.openremote.model.value.AbstractNameValueHolder;
import org.openremote.model.value.MetaItemDescriptor;
import org.openremote.model.value.ValueDescriptor;

/**
 * A named value whose name must match the name of a {@link MetaItemDescriptor} and whose value must
 * match the value type of the {@link MetaItemDescriptor}.
 */
@JsonSerialize(using = MetaItem.MetaItemSerializer.class)
public class MetaItem<T> extends AbstractNameValueHolder<T> {

  /** Serialize the meta item as just the value to make it less verbose */
  @SuppressWarnings("rawtypes")
  public static class MetaItemSerializer extends StdSerializer<MetaItem> {

    protected MetaItemSerializer() {
      super(MetaItem.class);
    }

    @Override
    public void serialize(MetaItem value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeObject(value.value);
    }
  }

  MetaItem() {}

  public MetaItem(String name) {
    this(name, null);
  }

  @SuppressWarnings("unchecked")
  public MetaItem(String name, ValueDescriptor<?> valueDescriptor) {
    this(name, (ValueDescriptor<T>) valueDescriptor, null);
  }

  public MetaItem(String name, ValueDescriptor<T> valueDescriptor, T value) {
    super(name, valueDescriptor, value);
  }

  @SuppressWarnings("unchecked")
  public MetaItem(MetaItemDescriptor<T> metaDescriptor) {
    // If it's a boolean meta descriptor assume the caller wants it to be true as a default
    this(metaDescriptor, (T) (metaDescriptor.getType().getType() != Boolean.class ? null : true));
  }

  public MetaItem(MetaItemDescriptor<T> metaDescriptor, T value) {
    super(metaDescriptor.getName(), metaDescriptor.getType(), value);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "name='"
        + name
        + '\''
        + ", value='"
        + value
        + '\''
        + '}';
  }
}
