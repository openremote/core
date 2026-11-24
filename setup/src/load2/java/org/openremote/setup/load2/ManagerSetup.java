/*
 * Copyright 2025, OpenRemote Inc.
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
package org.openremote.setup.load2;

import static org.openremote.model.Constants.MASTER_REALM;
import static org.openremote.model.syslog.SyslogCategory.DATA;
import static org.openremote.model.util.MapAccess.getBoolean;
import static org.openremote.model.util.MapAccess.getDouble;
import static org.openremote.model.util.MapAccess.getInteger;
import static org.openremote.model.util.MapAccess.getString;
import static org.openremote.setup.load2.KeycloakSetup.OR_SETUP_USERS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import org.openremote.container.util.AxialCoordinate;
import org.openremote.manager.provisioning.ProvisioningService;
import org.openremote.model.Container;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.asset.impl.BuildingAsset;
import org.openremote.model.asset.impl.LightAsset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.geo.GeoJSONPoint;
import org.openremote.model.security.User;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.UniqueIdentifierGenerator;
import org.openremote.model.value.MetaItemType;
import org.openremote.model.value.ValueType;

public class ManagerSetup extends org.openremote.manager.setup.ManagerSetup {
  public static final String OR_SETUP_ASSETS = "OR_SETUP_ASSETS";
  public static final String OR_SETUP_STORE_DATA_POINTS = "OR_SETUP_STORE_DATA_POINTS";
  public static final String OR_SETUP_ASSETS_WITH_LOCATIONS = "OR_SETUP_ASSETS_WITH_LOCATIONS";
  public static final String OR_SETUP_LOCATION_CLUSTERS = "OR_SETUP_LOCATION_CLUSTERS";
  public static final String OR_SETUP_LOCATION_MAIN_CENTER = "OR_SETUP_LOCATION_MAIN_CENTER";
  public static final String OR_SETUP_LOCATION_CENTER_DISTANCE =
      "OR_SETUP_LOCATION_CENTER_DISTANCE";
  public static final String OR_SETUP_LOCATION_ASSET_RADIUS = "OR_SETUP_LOCATION_ASSET_RADIUS";

  protected ProvisioningService provisioningService;
  protected Container container;
  protected Executor executor;

  private static final Logger LOG = SyslogCategory.getLogger(DATA, ManagerSetup.class);

  public ManagerSetup(Container container, Executor executor) {
    super(container);
    this.container = container;
    this.executor = executor;
    provisioningService = container.getService(ProvisioningService.class);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void onStart() throws Exception {
    int accounts = getInteger(container.getConfig(), OR_SETUP_USERS, 0);
    int assets = getInteger(container.getConfig(), OR_SETUP_ASSETS, 0);
    boolean storeDataPoints = getBoolean(container.getConfig(), OR_SETUP_STORE_DATA_POINTS, false);
    int assetsWithLocations = getInteger(container.getConfig(), OR_SETUP_ASSETS_WITH_LOCATIONS, 0);
    int locationClusters = getInteger(container.getConfig(), OR_SETUP_LOCATION_CLUSTERS, 0);
    String mainCenterRaw =
        getString(container.getConfig(), OR_SETUP_LOCATION_MAIN_CENTER, "").trim();
    double clusterCenterDistance =
        getDouble(container.getConfig(), OR_SETUP_LOCATION_CENTER_DISTANCE, 0d);
    double assetsMaxRadius = getDouble(container.getConfig(), OR_SETUP_LOCATION_ASSET_RADIUS, 0d);

    LocationConfig locationConfig =
        buildLocationConfig(
            assets,
            assetsWithLocations,
            locationClusters,
            mainCenterRaw,
            clusterCenterDistance,
            assetsMaxRadius);

    AtomicInteger createdAccounts = new AtomicInteger(0);
    if (accounts > 1) {
      IntStream.rangeClosed(1, accounts)
          .forEach(
              i -> {
                executor.execute(
                    () -> {
                      createAssets(i, assets, storeDataPoints, locationConfig);
                      createdAccounts.incrementAndGet();
                    });
              });

      // Wait until all devices created
      int waitCounter = 0;
      int waitCounterLimit = Math.max(60, (accounts * assets) / 150);
      while (createdAccounts.get() < accounts) {
        if (waitCounter > waitCounterLimit) {
          throw new IllegalStateException(
              "Failed to provision all requested devices in the specified time ("
                  + (waitCounterLimit * 10)
                  + " seconds).");
        }
        waitCounter++;
        Thread.sleep(10000);
      }
    }
  }

  private void createAssets(
      int account, int assets, boolean storeDataPoints, LocationConfig locationConfig) {
    String userName = "user" + account;
    String serviceUserName = User.SERVICE_ACCOUNT_PREFIX + "serviceuser" + account;

    User user = identityService.getIdentityProvider().getUserByUsername(MASTER_REALM, userName);
    String userId = user.getId();
    User serviceUser =
        identityService.getIdentityProvider().getUserByUsername(MASTER_REALM, serviceUserName);
    String serviceUserId = serviceUser.getId();

    Asset building = new BuildingAsset("Building " + account);
    building.setRealm(MASTER_REALM);
    building = assetStorageService.merge(building);
    LOG.info("Created building asset: " + building.getName());
    assetStorageService.storeUserAssetLinks(
        Collections.singletonList(new UserAssetLink(MASTER_REALM, userId, building.getId())));

    for (int i = 1; i <= assets; i++) {
      String uniqueId = "light-" + account + "-" + i;
      String assetId = UniqueIdentifierGenerator.generateId(MASTER_REALM + uniqueId);
      Asset<?> asset = new LightAsset("Light - " + account + " - " + i);
      asset
          .getAttribute(LightAsset.BRIGHTNESS)
          .ifPresent(
              attribute -> {
                attribute.addMeta(new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_WRITE));
                if (storeDataPoints) {
                  attribute.addMeta(new MetaItem<>(MetaItemType.STORE_DATA_POINTS));
                }
              });
      if (i == 1) {
        asset.addAttributes(
            new Attribute<>("connected", ValueType.BOOLEAN, false)
                .addMeta(new MetaItem<>(MetaItemType.USER_CONNECTED, serviceUser.getUsername())));
      }
      asset.setId(assetId);
      asset.setRealm(MASTER_REALM);
      asset.setParent(building);
      if (locationConfig.hasLocationForAsset(i)) {
        int clusterIndex = locationConfig.clusterIndexForAsset(i);
        GeoJSONPoint clusterCenter = locationConfig.clusterCenters.get(clusterIndex);
        asset.setLocation(randomLocationAround(clusterCenter, locationConfig.assetsMaxRadius));
      }
      asset = assetStorageService.merge(asset);
      LOG.info("Created light asset: " + asset.getName());

      assetStorageService.storeUserAssetLinks(
          Collections.singletonList(new UserAssetLink(MASTER_REALM, serviceUserId, assetId)));
      assetStorageService.storeUserAssetLinks(
          Collections.singletonList(new UserAssetLink(asset.getRealm(), userId, asset.getId())));
    }
  }

  private LocationConfig buildLocationConfig(
      int assets,
      int assetsWithLocations,
      int locationClusters,
      String mainCenterRaw,
      double clusterCenterDistance,
      double assetsMaxRadius) {
    if (assetsWithLocations <= 0) {
      return LocationConfig.disabled();
    }
    if (assetsWithLocations > assets) {
      LOG.info("Requested assets with locations exceeds total assets; trimming to " + assets);
      assetsWithLocations = assets;
    }
    if (locationClusters <= 0
        || clusterCenterDistance <= 0d
        || assetsMaxRadius < 0d
        || mainCenterRaw.isEmpty()) {
      LOG.warning("Location configuration incomplete; assets will be created without locations.");
      return LocationConfig.disabled();
    }
    GeoJSONPoint mainCenter = GeoJSONPoint.parseRawLocation(mainCenterRaw);
    if (mainCenter == null) {
      LOG.warning("Invalid main center point; assets will be created without locations.");
      return LocationConfig.disabled();
    }
    List<GeoJSONPoint> centers =
        buildClusterCenters(mainCenter, locationClusters, clusterCenterDistance);
    return new LocationConfig(assetsWithLocations, assetsMaxRadius, centers);
  }

  private List<GeoJSONPoint> buildClusterCenters(
      GeoJSONPoint mainCenter, int clusters, double spacingMeters) {
    List<GeoJSONPoint> centers = new ArrayList<>(clusters);
    int ring = 0;
    while (centers.size() < clusters) {
      if (ring == 0) {
        centers.add(mainCenter);
        ring++;
        continue;
      }
      for (AxialCoordinate coordinate : AxialCoordinate.ring(ring)) {
        if (centers.size() >= clusters) {
          break;
        }
        double eastMeters = spacingMeters * coordinate.xHex();
        double northMeters = spacingMeters * coordinate.yHex();
        centers.add(mainCenter.offsetByMeters(eastMeters, northMeters));
      }
      ring++;
    }
    return centers;
  }

  private GeoJSONPoint randomLocationAround(GeoJSONPoint center, double maxRadiusMeters) {
    if (maxRadiusMeters <= 0d) {
      return center;
    }
    ThreadLocalRandom random = ThreadLocalRandom.current();
    double distance = Math.sqrt(random.nextDouble()) * maxRadiusMeters;
    double angle = random.nextDouble() * Math.PI * 2d;
    double eastMeters = Math.cos(angle) * distance;
    double northMeters = Math.sin(angle) * distance;
    return center.offsetByMeters(eastMeters, northMeters);
  }

  private static class LocationConfig {
    final int assetsWithLocations;
    final double assetsMaxRadius;
    final List<GeoJSONPoint> clusterCenters;

    private LocationConfig(
        int assetsWithLocations, double assetsMaxRadius, List<GeoJSONPoint> clusterCenters) {
      this.assetsWithLocations = assetsWithLocations;
      this.assetsMaxRadius = assetsMaxRadius;
      this.clusterCenters = clusterCenters;
    }

    static LocationConfig disabled() {
      return new LocationConfig(0, 0d, Collections.emptyList());
    }

    boolean hasLocationForAsset(int assetNumber) {
      return assetNumber > 0 && assetNumber <= assetsWithLocations && !clusterCenters.isEmpty();
    }

    int clusterIndexForAsset(int assetNumber) {
      if (assetsWithLocations <= 0 || clusterCenters.isEmpty()) {
        throw new IllegalStateException(
            "Cannot resolve cluster index without location configuration.");
      }

      if (assetNumber <= 0 || assetNumber > assetsWithLocations) {
        throw new IllegalArgumentException(
            "Asset number out of range for located assets: " + assetNumber);
      }

      // Assign the first assets evenly across clusters, giving one extra to the first remainder
      // clusters.
      int clusters = clusterCenters.size();
      int base = assetsWithLocations / clusters;
      int remainder = assetsWithLocations % clusters;

      int locatedAssetIndex = assetNumber - 1;
      int largerClusterSize = base + 1;
      int largerClusterAssets = remainder * largerClusterSize;

      if (locatedAssetIndex < largerClusterAssets) {
        return locatedAssetIndex / largerClusterSize;
      }

      return remainder + ((locatedAssetIndex - largerClusterAssets) / base);
    }
  }
}
