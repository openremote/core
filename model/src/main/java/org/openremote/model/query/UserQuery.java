/*
 * Copyright 2026, OpenRemote Inc.
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

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import org.openremote.model.query.filter.PathPredicate;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.query.filter.StringPredicate;

@Schema(
    description =
        "Composable identity-provider user search request. Populated restrictions are combined, except role arrays which match any supplied role.")
public class UserQuery {

  @Schema(
      description =
          "Predicate for matching an identity-provider user attribute by name and optional value.")
  public static class AttributeValuePredicate {
    @Schema(description = "Invert this attribute predicate.", example = "false")
    public boolean negated;

    @Schema(description = "Attribute-name matcher.")
    public StringPredicate name;

    @Schema(description = "Optional attribute-value matcher.")
    public StringPredicate value;

    public AttributeValuePredicate(boolean negated, StringPredicate name) {
      this.negated = negated;
      this.name = name;
    }

    @JsonCreator
    public AttributeValuePredicate(boolean negated, StringPredicate name, StringPredicate value) {
      this.negated = negated;
      this.name = name;
      this.value = value;
    }

    public boolean isNegated() {
      return negated;
    }

    public AttributeValuePredicate setNegated(boolean negated) {
      this.negated = negated;
      return this;
    }

    public StringPredicate getName() {
      return name;
    }

    public AttributeValuePredicate setName(StringPredicate name) {
      this.name = name;
      return this;
    }

    public StringPredicate getValue() {
      return value;
    }

    public AttributeValuePredicate setValue(StringPredicate value) {
      this.value = value;
      return this;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "{"
          + "negated="
          + negated
          + ", name="
          + name
          + ", value="
          + value
          + '}';
    }
  }

  @Schema(name = "UserQuerySelect", description = "User response projection.")
  public static class Select {
    @Schema(
        description = "Return basic identity fields only, omitting administrative details.",
        example = "true")
    public boolean basic;

    public Select basic(boolean basic) {
      this.basic = basic;
      return this;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName() + "{" + ", basic=" + basic + '}';
    }
  }

  @Schema(name = "UserQueryOrderBy", description = "User result ordering.")
  public static class OrderBy {

    public enum Property {
      CREATED_ON,
      FIRST_NAME,
      LAST_NAME,
      USERNAME,
      EMAIL
    }

    @Schema(description = "User property used for ordering.", example = "USERNAME")
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

  // Restriction predicates
  public RealmPredicate realmPredicate;

  @Schema(description = "Match users linked to any of these asset identifiers.")
  public String[] assets;

  public PathPredicate pathPredicate;

  @Schema(description = "Match these exact identity-provider user identifiers.")
  public String[] ids;

  public Select select;
  public StringPredicate[] usernames;

  /** AND condition is assumed between values */
  @Schema(description = "Attribute predicates combined with AND.")
  public AttributeValuePredicate[] attributes;

  /**
   * OR condition is assumed between values (AND filtering can be applied by the caller on the
   * results)
   */
  public StringPredicate[] clientRoles;

  public StringPredicate[] realmRoles;

  @Schema(
      description = "True selects service users, false selects human users, null includes both.",
      nullable = true)
  public Boolean serviceUsers;

  @Schema(description = "Maximum number of results.", example = "100")
  public Integer limit;

  @Schema(description = "Number of matching results to skip.", example = "0")
  public Integer offset;

  public OrderBy orderBy;

  public UserQuery() {}

  /**
   * Copies all query options, including nested mutable predicates. Empty arrays are preserved: an
   * empty asset selection matches no users, whereas a null selection imposes no asset filter.
   */
  public UserQuery(UserQuery source) {
    realmPredicate =
        source.realmPredicate == null ? null : new RealmPredicate(source.realmPredicate.name);
    assets = source.assets == null ? null : source.assets.clone();
    pathPredicate =
        source.pathPredicate == null
            ? null
            : new PathPredicate(
                source.pathPredicate.path == null ? null : source.pathPredicate.path.clone());
    ids = source.ids == null ? null : source.ids.clone();
    select = source.select == null ? null : new Select().basic(source.select.basic);
    usernames = copyPredicates(source.usernames);
    attributes =
        source.attributes == null
            ? null
            : Arrays.stream(source.attributes)
                .map(
                    attribute ->
                        attribute == null
                            ? null
                            : new AttributeValuePredicate(
                                attribute.negated,
                                copyPredicate(attribute.name),
                                copyPredicate(attribute.value)))
                .toArray(AttributeValuePredicate[]::new);
    clientRoles = copyPredicates(source.clientRoles);
    realmRoles = copyPredicates(source.realmRoles);
    serviceUsers = source.serviceUsers;
    limit = source.limit;
    offset = source.offset;
    orderBy =
        source.orderBy == null
            ? null
            : new OrderBy(source.orderBy.property, source.orderBy.descending);
  }

  private static StringPredicate[] copyPredicates(StringPredicate[] predicates) {
    return predicates == null
        ? null
        : Arrays.stream(predicates).map(UserQuery::copyPredicate).toArray(StringPredicate[]::new);
  }

  private static StringPredicate copyPredicate(StringPredicate predicate) {
    return predicate == null
        ? null
        : new StringPredicate(predicate.match, predicate.caseSensitive, predicate.value)
            .negate(predicate.negate);
  }

  public UserQuery realm(RealmPredicate realmPredicate) {
    this.realmPredicate = realmPredicate;
    return this;
  }

  public UserQuery assets(String... assetIds) {
    this.assets = assetIds;
    return this;
  }

  public UserQuery assetPath(PathPredicate pathPredicate) {
    this.pathPredicate = pathPredicate;
    return this;
  }

  public UserQuery ids(String... ids) {
    this.ids = ids;
    return this;
  }

  public UserQuery usernames(StringPredicate... usernames) {
    this.usernames = usernames;
    return this;
  }

  public UserQuery attributes(AttributeValuePredicate... attributes) {
    this.attributes = attributes;
    return this;
  }

  public UserQuery clientRoles(StringPredicate... clientRoles) {
    this.clientRoles = clientRoles;
    return this;
  }

  public UserQuery realmRoles(StringPredicate... realmRoles) {
    this.realmRoles = realmRoles;
    return this;
  }

  public UserQuery serviceUsers(Boolean serviceUsers) {
    this.serviceUsers = serviceUsers;
    return this;
  }

  public UserQuery limit(int limit) {
    this.limit = limit;
    return this;
  }

  public UserQuery offset(int offset) {
    this.offset = offset;
    return this;
  }

  public UserQuery orderBy(OrderBy orderBy) {
    this.orderBy = orderBy;
    return this;
  }

  public UserQuery select(Select select) {
    this.select = select;
    return this;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "{"
        + "realmPredicate="
        + realmPredicate
        + ", assets="
        + assets
        + ", pathPredicate="
        + pathPredicate
        + ", ids="
        + (ids != null ? Arrays.toString(ids) : "null")
        + ", usernames="
        + (usernames != null ? Arrays.toString(usernames) : "null")
        + ", serviceUsers="
        + serviceUsers
        + ", attributes="
        + (attributes != null ? Arrays.toString(attributes) : "null")
        + ", clientRoles="
        + (clientRoles != null ? Arrays.toString(clientRoles) : "null")
        + ", realmRoles="
        + (realmRoles != null ? Arrays.toString(realmRoles) : "null")
        + ", limit="
        + limit
        + ", offset="
        + offset
        + ", orderBy="
        + orderBy
        + ", select="
        + select
        + '}';
  }
}
