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
package org.openremote.model.query.filter;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.validation.constraints.NotNull;
import java.util.Arrays;
import org.openremote.model.value.AbstractNameValueHolder;
import org.openremote.model.value.NameHolder;

/**
 * A predicate that can be applied to a {@link NameValuePredicate}; there is an implicit AND
 * condition between the name and the value; one or both the name and value should be supplied and
 * the following logic applies:
 *
 * <ul>
 *   <li>Both - A {@link AbstractNameValueHolder} whose {@link AbstractNameValueHolder#getName}
 *       matches {@link #name} and whose {@link AbstractNameValueHolder#getValue} matches {@link
 *       #value}
 *   <li>Name only - A {@link AbstractNameValueHolder} whose {@link AbstractNameValueHolder#getName}
 *       matches {@link #name}
 *   <li>Value only - A {@link AbstractNameValueHolder} whose {@link
 *       AbstractNameValueHolder#getValue} matches {@link #value}
 * </ul>
 *
 * When the predicate is applied the {@link AbstractNameValueHolder#getValue} is converted to JSON
 * representation if not already this ensures consistency of results between in memory predicate
 * evaluation and those applied to the DB.
 *
 * <p>The predicate can be negated to enforce name and/or value does not exist. An optional {@link
 * #path} can be supplied to apply the predicate to a value within the {@link
 * AbstractNameValueHolder#getValue} (i.e. when {@link AbstractNameValueHolder#getValue} is an
 * object and/or array), the {@link #path} is made up of an array of int (for array index) and
 * string (for object key) values.
 */
public class NameValuePredicate {

  public static class Path {
    @JsonValue Object[] paths; // ints for index, string for keys

    public Path(Path source) {
      paths = source.paths == null ? null : source.paths.clone();
    }

    @JsonCreator
    protected Path(@JsonProperty("paths") Object[] paths) {
      this.paths = paths;
    }

    public Path(String... keys) {
      paths = keys;
    }

    public Path(Integer... indexes) {
      paths = indexes;
    }

    public Path append(@NotNull String key) {
      return append((Object) key);
    }

    public Path append(@NotNull Integer key) {
      return append((Object) key);
    }

    protected Path append(Object path) {
      int index = paths.length;
      paths = Arrays.copyOf(paths, index + 1);
      paths[index] = path;
      return this;
    }

    public Object[] getPaths() {
      return paths;
    }
  }

  public StringPredicate name;
  public boolean negated;
  public Path path;
  public ValuePredicate value;

  public NameValuePredicate() {}

  /** Copies the name, value and path so later edits do not affect the source predicate. */
  public NameValuePredicate(NameValuePredicate source) {
    name = (StringPredicate) ValuePredicate.copy(source.name);
    negated = source.negated;
    path = source.path == null ? null : new Path(source.path);
    value = ValuePredicate.copy(source.value);
  }

  public NameValuePredicate(NameHolder nameHolder, ValuePredicate value) {
    this(nameHolder.getName(), value);
  }

  public NameValuePredicate(String name, ValuePredicate value) {
    this(new StringPredicate(name), value);
  }

  public NameValuePredicate(StringPredicate name, ValuePredicate value) {
    this.name = name;
    this.value = value;
  }

  public NameValuePredicate(
      NameHolder nameHolder, ValuePredicate value, boolean negated, Path path) {
    this(nameHolder.getName(), value, negated, path);
  }

  public NameValuePredicate(String name, ValuePredicate value, boolean negated, Path path) {
    this(new StringPredicate(name), value, negated, path);
  }

  public NameValuePredicate(
      StringPredicate name, ValuePredicate value, boolean negated, Path path) {
    this.name = name;
    this.negated = negated;
    this.path = path;
    this.value = value;
  }

  public NameValuePredicate name(StringPredicate name) {
    this.name = name;
    return this;
  }

  public NameValuePredicate name(String name) {
    this.name = new StringPredicate(name);
    return this;
  }

  public NameValuePredicate name(NameHolder name) {
    this.name = new StringPredicate(name);
    return this;
  }

  public NameValuePredicate value(ValuePredicate value) {
    this.value = value;
    return this;
  }

  public NameValuePredicate negate() {
    this.negated = !this.negated;
    return this;
  }

  public NameValuePredicate path(Path path) {
    this.path = path;
    return this;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "name="
        + name
        + ", value="
        + value
        + ", negated="
        + negated
        + ", path="
        + (path == null ? "null" : Arrays.toString(path.paths))
        + '}';
  }
}
