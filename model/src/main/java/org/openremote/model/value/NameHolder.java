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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.util.StdConverter;

/**
 * Identifies an item that holds a named property, the name should be very simple, as we use them in
 * SQL path expressions, etc. and must manually escape. The name should be immutable to aid in
 * tracking.
 */
public interface NameHolder {

  /** This class handles serializing {@link ValueDescriptor}s as strings */
  class NameHolderToStringConverter extends StdConverter<NameHolder, String> {

    @Override
    public String convert(NameHolder value) {
      return value.getName();
    }
  }

  @JsonProperty
  String getName();
}
