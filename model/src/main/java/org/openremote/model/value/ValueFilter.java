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
package org.openremote.model.value;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;

/**
 * Interface for a filter that can be applied to a value, the filter can return a different value
 * type to the supplied value (i.e. value conversion as well as filtering). Filters can be chained
 * and if a null value is supplied to a filter then the filter must also return null.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RegexValueFilter.class),
  @JsonSubTypes.Type(value = SubStringValueFilter.class),
  @JsonSubTypes.Type(value = JsonPathFilter.class),
  @JsonSubTypes.Type(value = MathExpressionValueFilter.class)
})
// TODO: Standardize inbound/outbound value processing as ordered list of filters and/or converters
public abstract class ValueFilter implements Serializable {

  public abstract Object filter(Object value);
}
