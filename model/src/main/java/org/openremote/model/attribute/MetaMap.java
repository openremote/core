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
package org.openremote.model.attribute;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import org.openremote.model.util.ValueUtil;
import org.openremote.model.value.MetaItemDescriptor;

@JsonDeserialize(using = MetaMap.MetaObjectDeserializer.class)
public class MetaMap extends NamedMap<String, MetaItem<?>> {
  /**
   * Deserialise a {@link MetaMap} that is represented as a JSON object where each key is the name
   * of a {@link MetaItemDescriptor}
   */
  public static class MetaObjectDeserializer extends StdDeserializer<MetaMap> {

    public static final String META_DESCRIPTOR_PROVIDER = "meta-descriptor-provider";
    protected static Function<String, MetaItemDescriptor<?>> DEFAULT_META_DESCRIPTOR_PROVIDER =
        (name) -> ValueUtil.getMetaItemDescriptor(name).orElse(null);

    public MetaObjectDeserializer() {
      super(MetaMap.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    public MetaMap deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {

      Function<String, MetaItemDescriptor<?>> metaDescriptorProvider =
          (Function<String, MetaItemDescriptor<?>>) ctxt.getAttribute(META_DESCRIPTOR_PROVIDER);
      if (metaDescriptorProvider == null) {
        metaDescriptorProvider = DEFAULT_META_DESCRIPTOR_PROVIDER;
      }

      List<MetaItem<?>> list = deserialiseMetaMap(jp, ctxt, metaDescriptorProvider);

      MetaMap metaMap = new MetaMap();
      metaMap.putAll(list);
      return metaMap;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public static List<MetaItem<?>> deserialiseMetaMap(
      JsonParser jp,
      DeserializationContext ctxt,
      Function<String, MetaItemDescriptor<?>> metaDescriptorProvider)
      throws IOException {
    if (!jp.isExpectedStartObjectToken()) {
      throw JsonMappingException.from(jp, "MetaMap must be an object");
    }

    List<MetaItem<?>> list = new ArrayList<>();

    while (jp.nextToken() != JsonToken.END_OBJECT) {
      if (jp.currentToken() == JsonToken.FIELD_NAME) {
        jp.nextToken();
      }
      if (jp.currentToken() == JsonToken.VALUE_NULL) {
        continue;
      }

      String metaItemName = jp.currentName();
      MetaItemDescriptor<?> metaItemDescriptor = metaDescriptorProvider.apply(metaItemName);

      MetaItem metaItem;
      if (metaItemDescriptor != null) {
        metaItem = new MetaItem(metaItemDescriptor.getName(), metaItemDescriptor.getType());
      } else {
        metaItem = new MetaItem(metaItemName);
      }

      try {
        metaItem.setValue(
            Attribute.AttributeDeserializer.deserialiseValue(metaItem.getType(), jp, ctxt));
      } catch (JsonMappingException e) {
        throw JsonMappingException.wrapWithPath(e, MetaMap.class, metaItemName);
      }

      list.add(metaItem);
    }

    return list;
  }

  public MetaMap() {}

  public MetaMap(Collection<? extends MetaItem<?>> c) {
    super(c);
  }

  public MetaMap(Map<? extends String, ? extends MetaItem<?>> map) {
    super(map);
  }

  // This works around the crappy type system and avoids the need for a type witness
  public <S> Optional<MetaItem<S>> get(MetaItemDescriptor<S> metaDescriptor) {
    return super.get(metaDescriptor);
  }

  public <U extends MetaItemDescriptor<?>> void remove(U nameHolder) {
    super.remove(nameHolder.getName());
  }

  public <S> MetaItem<S> getOrCreate(MetaItemDescriptor<S> metaDescriptor) {
    MetaItem<S> metaItem = get(metaDescriptor).orElse(new MetaItem<>(metaDescriptor));
    addOrReplace(metaItem);
    return metaItem;
  }
}
