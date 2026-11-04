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
package org.openremote.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.query.filter.*;
import org.openremote.model.util.ValueUtil;

/** Encapsulate asset query restriction, projection, and ordering of results. */
@Schema(
    description =
        "Composable asset search request. Populated filters are combined to restrict results; limit and offset provide pagination.")
public class AssetQuery implements Serializable {

  @Schema(
      name = "AssetQuerySelect",
      description = "Response projection for controlling which asset attributes are returned.")
  public static class Select {
    protected static final String[] EMPTY_ATTRIBUTES = new String[0];

    @Schema(
        description =
            "Attribute names to include. An empty array excludes all attributes; null returns all attributes.",
        example = "[\"temperature\",\"humidity\"]")
    public String[] attributes;

    public Select attributes(String... attributeNames) {
      this.attributes = attributeNames;
      return this;
    }

    public Select excludeAttributes() {
      this.attributes = EMPTY_ATTRIBUTES;
      return this;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "{"
          + ", attributeNames="
          + Arrays.toString(attributes)
          + '}';
    }
  }

  @Schema(name = "AssetQueryOrderBy", description = "Asset result ordering.")
  public static class OrderBy {

    public enum Property {
      CREATED_ON,
      NAME,
      ASSET_TYPE,
      PARENT_ID,
      REALM
    }

    @Schema(description = "Asset property used for ordering.", example = "NAME")
    public Property property;

    @Schema(description = "Reverse the default ascending order.", example = "false")
    public boolean descending;

    public OrderBy() {}

    public OrderBy(Property property) {
      this.property = property;
    }

    public OrderBy(Property property, boolean descending) {
      this.property = property;
      this.descending = descending;
    }

    public OrderBy property(Property property) {
      this.property = property;
      return this;
    }

    public OrderBy descending(boolean descending) {
      this.descending = descending;
      return this;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "{"
          + "property="
          + property
          + ", descending="
          + descending
          + '}';
    }
  }

  public enum Access {
    PRIVATE,
    PROTECTED,
    PUBLIC
  }

  /** String matching options */
  public enum Match {
    EXACT,
    BEGIN,
    END,
    CONTAINS;

    public String prepare(String string) {
      if (string == null) return null;
      switch (this) {
        case BEGIN:
          return string + "%";
        case CONTAINS:
          return "%" + string + "%";
        case END:
          return "%" + string;
      }
      return string;
    }
  }

  /** Binary operators */
  public enum Operator {
    EQUALS,
    GREATER_THAN,
    GREATER_EQUALS,
    LESS_THAN,
    LESS_EQUALS,
    BETWEEN;

    public <T> boolean compare(Comparator<T> comparator, T x, T y) {
      return compare(comparator, x, y, null);
    }

    public <T> boolean compare(Comparator<T> comparator, T x, T y, T z) {
      // Get null friendly comparator
      comparator = Comparator.nullsFirst(comparator);
      int comparatorResult = comparator.compare(x, y);

      switch (this) {
        case EQUALS:
          return comparatorResult == 0;
        case GREATER_THAN:
          return comparatorResult > 0;
        case GREATER_EQUALS:
          return comparatorResult >= 0;
        case LESS_THAN:
          return comparatorResult < 0;
        case LESS_EQUALS:
          return comparatorResult <= 0;
        case BETWEEN:
          int comparatorResultUpper = comparator.compare(x, z);
          return comparatorResult >= 0 && comparatorResultUpper < 1;
      }
      return false;
    }
  }

  @Schema(
      description = "When parent or path filters are used, include matching descendants.",
      example = "true")
  public boolean recursive;

  // Projection
  public Select select;

  // Restriction predicates
  @Schema(description = "Requested visibility class relative to the caller.")
  public Access access;

  @Schema(description = "Match these exact asset identifiers.")
  public String[] ids;

  public StringPredicate[] names;
  public ParentPredicate[] parents;
  public PathPredicate[] paths;
  public RealmPredicate realm;

  @Schema(description = "Match assets linked to any of these users.")
  public String[] userIds;

  @Schema(
      description =
          "Match registered asset type names; base types include their concrete subtypes.",
      example = "[\"ThingAsset\"]")
  public String[] types;

  public LogicGroup<AttributePredicate> attributes;
  public Boolean includeDeletePending;
  // Ordering
  public OrderBy orderBy;

  @Schema(description = "Maximum number of results; zero uses the server default.", example = "100")
  public int limit;

  @Schema(description = "Number of matching results to skip.", example = "0")
  public int offset;

  public AssetQuery() {}

  /** Copies query options and nested predicates without changing null or empty selections. */
  public AssetQuery(AssetQuery source) {
    recursive = source.recursive;
    select =
        source.select == null
            ? null
            : new Select()
                .attributes(
                    source.select.attributes == null ? null : source.select.attributes.clone());
    access = source.access;
    ids = source.ids == null ? null : source.ids.clone();
    names =
        source.names == null
            ? null
            : Arrays.stream(source.names)
                .map(p -> (StringPredicate) ValuePredicate.copy(p))
                .toArray(StringPredicate[]::new);
    parents =
        source.parents == null
            ? null
            : Arrays.stream(source.parents)
                .map(p -> p == null ? null : new ParentPredicate(p.id))
                .toArray(ParentPredicate[]::new);
    paths =
        source.paths == null
            ? null
            : Arrays.stream(source.paths)
                .map(
                    p ->
                        p == null
                            ? null
                            : new PathPredicate(p.path == null ? null : p.path.clone()))
                .toArray(PathPredicate[]::new);
    realm = source.realm == null ? null : new RealmPredicate(source.realm.name);
    userIds = source.userIds == null ? null : source.userIds.clone();
    types = source.types == null ? null : source.types.clone();
    attributes = copyAttributes(source.attributes);
    includeDeletePending = source.includeDeletePending;
    orderBy =
        source.orderBy == null
            ? null
            : new OrderBy(source.orderBy.property, source.orderBy.descending);
    limit = source.limit;
    offset = source.offset;
  }

  private static LogicGroup<AttributePredicate> copyAttributes(
      LogicGroup<AttributePredicate> source) {
    if (source == null) return null;
    return new LogicGroup<>(
        source.operator,
        source.groups == null
            ? null
            : source.groups.stream()
                .map(AssetQuery::copyAttributes)
                .collect(Collectors.toCollection(ArrayList::new)),
        source.items == null
            ? null
            : source.items.stream()
                .map(
                    p ->
                        p == null
                            ? null
                            : p instanceof LocationAttributePredicate location
                                ? new LocationAttributePredicate(location)
                                : new AttributePredicate(p))
                .collect(Collectors.toCollection(ArrayList::new)));
  }

  /** Resolves the concrete {@link Asset} types that are covered by the supplied asset types */
  @SuppressWarnings("unchecked")
  public static String[] getResolvedAssetTypes(String[] assetTypes) {

    List<Class<? extends Asset<?>>> assetClasses = Arrays.asList(ValueUtil.getAssetClasses(null));
    Set<String> assetSubTypes = new HashSet<>(Arrays.asList(assetTypes));

    Arrays.stream(assetTypes)
        .map(
            assetType ->
                ValueUtil.getAssetClass(assetType)
                    .orElseGet(
                        () -> {
                          // Try and resolve this type to a concrete asset super type class - should
                          // be in the type hierarchy
                          // of one of the registered asset types if it is a valid type
                          Class<? extends Asset<?>> matchedClass = null;

                          for (Class<?> c : assetClasses) {
                            Class<?> currentClass = c;
                            while (Asset.class.isAssignableFrom(currentClass)) {
                              if (currentClass.getSimpleName().equals(assetType)) {
                                matchedClass = (Class) currentClass;
                                break;
                              }
                              currentClass = currentClass.getSuperclass();
                            }
                            if (matchedClass != null) {
                              break;
                            }
                          }

                          return matchedClass;
                        }))
        .filter(Objects::nonNull)
        .flatMap(
            assetClass ->
                assetClasses.stream()
                    .filter(assetClass::isAssignableFrom)
                    .map(Class::getSimpleName))
        .forEach(assetSubTypes::add);

    return assetSubTypes.toArray(String[]::new);
  }

  public AssetQuery recursive(boolean recursive) {
    this.recursive = recursive;
    return this;
  }

  public AssetQuery access(Access access) {
    this.access = access;
    return this;
  }

  public AssetQuery select(Select select) {
    if (select == null) {
      select = new Select();
    }

    this.select = select;
    return this;
  }

  public AssetQuery ids(String... ids) {
    this.ids = ids;
    return this;
  }

  public AssetQuery names(String... names) {
    if (names == null || names.length == 0) {
      this.names = null;
      return this;
    }
    this.names = Arrays.stream(names).map(StringPredicate::new).toArray(StringPredicate[]::new);
    return this;
  }

  public AssetQuery names(StringPredicate... names) {
    this.names = names;
    return this;
  }

  public AssetQuery parents(String... ids) {
    if (ids == null || ids.length == 0) {
      this.names = null;
      return this;
    }
    this.parents = Arrays.stream(ids).map(ParentPredicate::new).toArray(ParentPredicate[]::new);
    return this;
  }

  public AssetQuery parents(ParentPredicate... parentPredicates) {
    this.parents = parentPredicates;
    return this;
  }

  public AssetQuery paths(PathPredicate... pathPredicates) {
    this.paths = pathPredicates;
    return this;
  }

  public AssetQuery realm(RealmPredicate realmPredicate) {
    this.realm = realmPredicate;
    return this;
  }

  public AssetQuery userIds(String... userIds) {
    this.userIds = userIds;
    return this;
  }

  @SuppressWarnings("unchecked")
  @SafeVarargs
  public final <T extends Asset<?>> AssetQuery types(AssetDescriptor<T>... types) {
    if (types == null || types.length == 0) {
      this.types = null;
      return this;
    }

    this.types =
        Arrays.stream(types)
            .map(AssetDescriptor::getType)
            .map(Class::getSimpleName)
            .toArray(String[]::new);
    return this;
  }

  public final <T extends Asset<?>> AssetQuery types(Class<T> type) {
    this.types = new String[] {type.getSimpleName()};
    return this;
  }

  @SafeVarargs
  public final AssetQuery types(Class<? extends Asset<?>>... types) {
    if (types == null || types.length == 0) {
      this.types = null;
      return this;
    }

    this.types = Arrays.stream(types).map(Class::getSimpleName).toArray(String[]::new);
    return this;
  }

  public AssetQuery attributes(AttributePredicate... attributePredicates) {
    if (attributePredicates == null || attributePredicates.length == 0) {
      this.attributes = null;
      return this;
    }

    this.attributes = new LogicGroup<>(Arrays.asList(attributePredicates));
    return this;
  }

  public AssetQuery attributes(LogicGroup<AttributePredicate> attributePredicateGroup) {
    this.attributes = attributePredicateGroup;
    return this;
  }

  public AssetQuery attributeNames(String... attributeNames) {
    LogicGroup<AttributePredicate> predicateLogicGroup =
        new LogicGroup<>(
            Arrays.stream(attributeNames)
                .map(name -> new AttributePredicate(name, null))
                .collect(Collectors.toList()));
    predicateLogicGroup.operator = LogicGroup.Operator.OR;
    return attributes(predicateLogicGroup);
  }

  public AssetQuery attributeName(String attributeName) {
    return attributes(new AttributePredicate(attributeName, null));
  }

  public AssetQuery attributeValue(String name, ValuePredicate valuePredicate) {
    return attributes(new AttributePredicate(new StringPredicate(name), valuePredicate));
  }

  public AssetQuery attributeValue(String name) {
    return attributeValue(name, new ValueEmptyPredicate().negate(true));
  }

  public AssetQuery attributeValue(String name, boolean b) {
    return attributeValue(name, new BooleanPredicate(b));
  }

  public AssetQuery attributeValue(String name, String s) {
    return attributeValue(name, new StringPredicate(s));
  }

  public AssetQuery attributeValue(String name, Match match, String s) {
    return attributeValue(name, new StringPredicate(match, s));
  }

  public AssetQuery attributeValue(String name, Match match, boolean caseSensitive, String s) {
    return attributeValue(name, new StringPredicate(match, caseSensitive, s));
  }

  public AssetQuery attributeValue(String name, double d) {
    return attributeValue(name, new NumberPredicate(d));
  }

  public AssetQuery attributeValue(String name, Operator operator, double d) {
    return attributeValue(name, new NumberPredicate(d, operator));
  }

  public AssetQuery includeDeletePending(boolean includeDeletePending) {
    this.includeDeletePending = includeDeletePending;
    return this;
  }

  public boolean isIncludeDeletePending() {
    return includeDeletePending != null && includeDeletePending;
  }

  public AssetQuery orderBy(OrderBy orderBy) {
    this.orderBy = orderBy;
    return this;
  }

  public AssetQuery limit(int limit) {
    this.limit = limit;
    return this;
  }

  public AssetQuery offset(int offset) {
    this.offset = offset;
    return this;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "select="
        + select
        + ", ids="
        + (ids != null ? Arrays.toString(ids) : "null")
        + ", name="
        + Arrays.toString(names)
        + ", parent="
        + Arrays.toString(parents)
        + ", path="
        + Arrays.toString(paths)
        + ", realm="
        + realm
        + ", userId='"
        + Arrays.toString(userIds)
        + '\''
        + ", type="
        + Arrays.toString(types)
        + ", attribute="
        + (attributes != null ? attributes.toString() : "null")
        + ", includeDeletePending="
        + includeDeletePending
        + ", orderBy="
        + orderBy
        + ", limit="
        + limit
        + ", offset="
        + offset
        + ", recursive="
        + recursive
        + '}';
  }
}
