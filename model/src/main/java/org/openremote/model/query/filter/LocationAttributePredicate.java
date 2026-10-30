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

import java.util.ArrayList;
import java.util.List;
import org.openremote.model.asset.Asset;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.LogicGroup;

public class LocationAttributePredicate extends AttributePredicate {

  public LocationAttributePredicate(LocationAttributePredicate source) {
    super(source);
  }

  public LocationAttributePredicate(GeofencePredicate geofencePredicate) {
    super(new StringPredicate(Asset.LOCATION), geofencePredicate);
  }

  public static List<GeofencePredicate> getLocationPredicates(
      LogicGroup<AttributePredicate> attributePredicates) {
    List<GeofencePredicate> geofences = new ArrayList<>();

    attributePredicates.getItems().stream()
        .filter(
            attributePredicate ->
                attributePredicate.name != null
                    && attributePredicate.name.match == AssetQuery.Match.EXACT
                    && Asset.LOCATION.getName().equals(attributePredicate.name.value)
                    && attributePredicate.value instanceof GeofencePredicate)
        .map(attributePredicate -> (GeofencePredicate) attributePredicate.value)
        .forEach(geofences::add);

    if (attributePredicates.groups != null && attributePredicates.groups.size() > 0) {
      attributePredicates.groups.forEach(group -> geofences.addAll(getLocationPredicates(group)));
    }

    return geofences;
  }
}
