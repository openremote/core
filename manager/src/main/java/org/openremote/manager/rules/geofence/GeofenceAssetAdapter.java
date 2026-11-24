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
package org.openremote.manager.rules.geofence;

import java.util.List;
import org.openremote.manager.rules.RulesEngine;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.Asset;
import org.openremote.model.query.filter.LocationAttributePredicate;
import org.openremote.model.rules.geofence.GeofenceDefinition;

/**
 * Defines an adapter that can take a collection of {@link LocationAttributePredicate} that apply to
 * a given {@link Asset} and can convert the {@link LocationAttributePredicate}s into Geofences that
 * can be implemented by the asset(s) itself.
 *
 * <p>The adapter is notified when the {@link Asset}s {@link RulesEngine.AssetLocationPredicates}
 * change; if the {@link RulesEngine.AssetLocationPredicates#getLocationPredicates()} is null or
 * empty then it means that there are no longer any associated with that {@link Asset} so the
 * adapter should clear any that already exist on that {@link Asset}, it is the adapters job to
 * maintain state if required (see initializing flag on {@link #processLocationPredicates}).
 *
 * <p>How the geofences are implemented and 'pushed' to the {@link Asset}s is up to the adapter but
 * when a geofence is triggered on an asset then the asset should update its own location by posting
 * to the asset/location endpoint, the location value sent should be as follows:
 *
 * <ul>
 *   <li>Geofence Enter - Send geofence center point as location (center point should have been
 *       provided in the geofence definition retrieved from the backend)
 *   <li>Geofence Exit - Send null (this will clear the devices location and indicate that the asset
 *       has left the geofence)
 * </ul>
 */
public interface GeofenceAssetAdapter extends ContainerService {

  /** Get the name of this adapter */
  String getName();

  /**
   * If an adapter handles the location predicates for a particular asset then the adapter should
   * remove that item from the list to prevent other adapters from also handling it. If an {@link
   * RulesEngine.AssetLocationPredicates#getLocationPredicates} is empty then it means there are no
   * longer any location predicates associated with that asset
   */
  void processLocationPredicates(
      List<RulesEngine.AssetLocationPredicates> modifiedAssetLocationPredicates);

  /**
   * Called to return the active geofences for the specified {@link Asset}; if this adapter supports
   * the requested asset then it should return a non null value to prevent the request from being
   * sent to other geofence adapters.
   */
  GeofenceDefinition[] getAssetGeofences(String assetId);
}
