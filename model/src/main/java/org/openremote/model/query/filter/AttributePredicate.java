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

import java.util.Arrays;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.query.LogicGroup;
import org.openremote.model.value.NameHolder;

/**
 * Adds additional predicate logic to {@link NameValuePredicate}, allowing predicating on {@link
 * Attribute#getMeta} presence/absence and/or values; there is an implicit OR between meta
 * predicates (and condition can be achieved by creating multiple {@link AttributePredicate}s in an
 * {@link LogicGroup.Operator#AND} {@link LogicGroup}. Can also predicate on the previous value of
 * the {@link Attribute} which is only relevant when applied to {@link
 * org.openremote.model.attribute.AttributeInfo}.
 */
public class AttributePredicate extends NameValuePredicate {

  public NameValuePredicate[] meta;
  public ValuePredicate previousValue;

  // ISO 8601 duration expression (e.g. PT5M)
  public String timestampOlderThan;

  public AttributePredicate() {}

  /** Copies this predicate, including metadata and previous-value restrictions. */
  public AttributePredicate(AttributePredicate source) {
    super(source);
    meta =
        source.meta == null
            ? null
            : Arrays.stream(source.meta)
                .map(p -> p == null ? null : new NameValuePredicate(p))
                .toArray(NameValuePredicate[]::new);
    previousValue = ValuePredicate.copy(source.previousValue);
    timestampOlderThan = source.timestampOlderThan;
  }

  public AttributePredicate(NameHolder nameHolder, ValuePredicate value) {
    super(nameHolder, value);
  }

  public AttributePredicate(String name, ValuePredicate value) {
    super(name, value);
  }

  public AttributePredicate(StringPredicate name, ValuePredicate value) {
    super(name, value);
  }

  public AttributePredicate(
      NameHolder nameHolder, ValuePredicate value, boolean negated, Path path) {
    super(nameHolder, value, negated, path);
  }

  public AttributePredicate(String name, ValuePredicate value, boolean negated, Path path) {
    super(name, value, negated, path);
  }

  public AttributePredicate(
      StringPredicate name, ValuePredicate value, boolean negated, Path path) {
    super(name, value, negated, path);
  }

  @Override
  public AttributePredicate name(StringPredicate name) {
    this.name = name;
    return this;
  }

  @Override
  public AttributePredicate name(String name) {
    this.name = new StringPredicate(name);
    return this;
  }

  @Override
  public AttributePredicate name(NameHolder name) {
    this.name = new StringPredicate(name);
    return this;
  }

  @Override
  public AttributePredicate value(ValuePredicate value) {
    this.value = value;
    return this;
  }

  @Override
  public AttributePredicate negate() {
    super.negate();
    return this;
  }

  @Override
  public AttributePredicate path(Path path) {
    super.path(path);
    return this;
  }

  public AttributePredicate previousValue(ValuePredicate previousValue) {
    this.previousValue = previousValue;
    return this;
  }

  public AttributePredicate meta(NameValuePredicate... meta) {
    this.meta = meta;
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
        + ", meta="
        + Arrays.toString(meta)
        + ", previousValue="
        + previousValue
        + '}';
  }
}
