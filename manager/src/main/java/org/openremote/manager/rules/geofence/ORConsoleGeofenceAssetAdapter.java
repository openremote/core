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

import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.container.persistence.PersistenceService.isPersistenceEventForEntityType;
import static org.openremote.manager.gateway.GatewayService.isNotForGateway;
import static org.openremote.model.asset.AssetResource.Util.WRITE_ATTRIBUTE_HTTP_METHOD;
import static org.openremote.model.asset.AssetResource.Util.getWriteAttributeUrl;
import static org.openremote.model.syslog.SyslogCategory.RULES;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.notification.NotificationService;
import org.openremote.manager.rules.RulesEngine;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.model.Container;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.impl.ConsoleAsset;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.console.ConsoleProvider;
import org.openremote.model.notification.Notification;
import org.openremote.model.notification.PushNotificationMessage;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.*;
import org.openremote.model.rules.geofence.GeofenceDefinition;
import org.openremote.model.syslog.SyslogCategory;

/**
 * This implementation is for handling geofences on console assets that support the OR Console
 * Geofence Provider (i.e. Android and iOS Consoles):
 *
 * <p>This adapter utilizes push notifications to notify assets when their geofences change; a data
 * only (silent) push notification is sent to affected consoles/assets. Consoles can also manually
 * request their geofences (e.g. on startup)
 */
public class ORConsoleGeofenceAssetAdapter extends RouteBuilder implements GeofenceAssetAdapter {

  private static final Logger LOG =
      SyslogCategory.getLogger(RULES, ORConsoleGeofenceAssetAdapter.class.getName());
  public static final String NAME = "ORConsole";
  public static int NOTIFY_ASSETS_DEBOUNCE_MILLIS = 60000;
  public static int NOTIFY_ASSETS_BATCH_MILLIS = 10000;
  protected Map<String, RulesEngine.AssetLocationPredicates> assetLocationPredicatesMap =
      new HashMap<>();
  protected NotificationService notificationService;
  protected AssetStorageService assetStorageService;
  protected GatewayService gatewayService;
  protected ManagerIdentityService identityService;
  protected ScheduledExecutorService scheduledExecutorService;
  protected ConcurrentMap<String, String> consoleIdRealmMap = new ConcurrentHashMap<>();
  protected ScheduledFuture<?> notifyAssetsScheduledFuture;
  protected final Set<String> notifyAssets = new HashSet<>();

  @Override
  public int getPriority() {
    return MessageBrokerService.PRIORITY
        + 200; // Start after MessageBrokerService so we can add routes
  }

  @Override
  public void init(Container container) throws Exception {
    this.assetStorageService = container.getService(AssetStorageService.class);
    this.notificationService = container.getService(NotificationService.class);
    this.identityService = container.getService(ManagerIdentityService.class);
    scheduledExecutorService = container.getScheduledExecutor();
    gatewayService = container.getService(GatewayService.class);
    container.getService(MessageBrokerService.class).getContext().addRoutes(this);
  }

  @Override
  public void start(Container container) throws Exception {

    assetStorageService
        .findAll(
            new AssetQuery()
                .select(
                    new AssetQuery.Select().attributes(ConsoleAsset.CONSOLE_PROVIDERS.getName()))
                .types(ConsoleAsset.class)
                .attributes(
                    new AttributePredicate(
                        ConsoleAsset.CONSOLE_PROVIDERS,
                        new ValueEmptyPredicate().negate(true),
                        false,
                        new NameValuePredicate.Path("geofence"))))
        .stream()
        .map(asset -> (ConsoleAsset) asset)
        .filter(ORConsoleGeofenceAssetAdapter::isLinkedToORConsoleGeofenceAdapter)
        .forEach(asset -> consoleIdRealmMap.put(asset.getId(), asset.getRealm()));
  }

  @Override
  public void stop(Container container) throws Exception {}

  @Override
  public void configure() throws Exception {

    // If any console asset was modified in the database, detect geofence provider changes
    from(PERSISTENCE_TOPIC)
        .routeId("Persistence-GeofenceAdapterConsoleAsset")
        .filter(isPersistenceEventForEntityType(ConsoleAsset.class))
        .filter(isNotForGateway(gatewayService))
        .process(
            exchange -> {
              @SuppressWarnings("unchecked")
              PersistenceEvent<ConsoleAsset> persistenceEvent =
                  (PersistenceEvent<ConsoleAsset>) exchange.getIn().getBody(PersistenceEvent.class);
              processConsoleAssetChange(persistenceEvent);
            });
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void processLocationPredicates(
      List<RulesEngine.AssetLocationPredicates> modifiedAssetLocationPredicates) {
    AtomicBoolean notifierDebounce = new AtomicBoolean(false);

    synchronized (notifyAssets) {
      // Remove all entries that relate to consoles that are compatible with this adapter
      modifiedAssetLocationPredicates.removeIf(
          assetStateLocationPredicates -> {
            boolean remove =
                consoleIdRealmMap.containsKey(assetStateLocationPredicates.getAssetId());

            if (remove) {
              // Keep only radial location predicates (only these are supported on iOS and Android)
              assetStateLocationPredicates
                  .getLocationPredicates()
                  .removeIf(
                      locationPredicate -> !(locationPredicate instanceof RadialGeofencePredicate));

              RulesEngine.AssetLocationPredicates existingPredicates =
                  assetLocationPredicatesMap.get(assetStateLocationPredicates.getAssetId());
              if (existingPredicates == null
                  || !existingPredicates
                      .getLocationPredicates()
                      .equals(assetStateLocationPredicates.getLocationPredicates())) {
                // We're not comparing before and after state as RulesService has done that although
                // it could be
                // that rectangular location predicates have changed but this will do for now
                notifyAssets.add(assetStateLocationPredicates.getAssetId());
                notifierDebounce.set(true);
              }

              if (assetStateLocationPredicates.getLocationPredicates().isEmpty()) {
                if (assetLocationPredicatesMap.remove(assetStateLocationPredicates.getAssetId())
                    != null) {
                  LOG.fine(
                      "Clearing location predicates for asset: "
                          + assetStateLocationPredicates.getAssetId());
                  notifyAssets.add(assetStateLocationPredicates.getAssetId());
                  notifierDebounce.set(true);
                }
              } else {
                LOG.fine(
                    "Setting "
                        + assetStateLocationPredicates.getLocationPredicates().size()
                        + " location predicate(s) for asset: "
                        + assetStateLocationPredicates.getAssetId());

                assetLocationPredicatesMap.put(
                    assetStateLocationPredicates.getAssetId(), assetStateLocationPredicates);
              }
            } else if (assetLocationPredicatesMap.remove(assetStateLocationPredicates.getAssetId())
                != null) {
              // Used to be in this map so must have been deleted so ask console to delete its
              // geofences also
              LOG.fine(
                  "Clearing location predicates for asset: "
                      + assetStateLocationPredicates.getAssetId());
              notifyAssets.add(assetStateLocationPredicates.getAssetId());
              notifierDebounce.set(true);
            }

            return remove;
          });

      if (notifierDebounce.get()) {
        if (notifyAssetsScheduledFuture == null || notifyAssetsScheduledFuture.cancel(false)) {
          notifyAssetsScheduledFuture =
              scheduledExecutorService.schedule(
                  () -> {
                    synchronized (notifyAssets) {
                      notifyAssetGeofencesChanged(notifyAssets);
                      notifyAssets.clear();
                      notifyAssetsScheduledFuture = null;
                    }
                  },
                  NOTIFY_ASSETS_DEBOUNCE_MILLIS,
                  TimeUnit.MILLISECONDS);
        }
      }
    }
  }

  @Override
  public GeofenceDefinition[] getAssetGeofences(String assetId) {
    String realm = consoleIdRealmMap.get(assetId);

    if (realm == null) {
      LOG.fine("Console ID not found in map so cannot retrieve geofences");
      // Asset<?> not supported by this adapter
      return null;
    }

    RulesEngine.AssetLocationPredicates assetStateLocationPredicates =
        assetLocationPredicatesMap.get(assetId);

    if (assetStateLocationPredicates == null) {
      // No geofences exist for this asset
      LOG.finest("Request for console '" + assetId + "' geofences: 0 found");
      return new GeofenceDefinition[0];
    }

    GeofenceDefinition[] geofences =
        assetStateLocationPredicates.getLocationPredicates().stream()
            .map(
                locationPredicate ->
                    locationPredicateToGeofenceDefinition(
                        assetStateLocationPredicates.getAssetId(), locationPredicate))
            .toArray(GeofenceDefinition[]::new);

    LOG.finest("Request for console '" + assetId + "' geofences: " + geofences.length + " found");
    return geofences;
  }

  protected GeofenceDefinition locationPredicateToGeofenceDefinition(
      String assetId, GeofencePredicate geofencePredicate) {
    RadialGeofencePredicate radialLocationPredicate = (RadialGeofencePredicate) geofencePredicate;
    String id = assetId + "_" + radialLocationPredicate.hashCode();
    String url = getWriteAttributeUrl(new AttributeRef(assetId, Asset.LOCATION.getName()));
    return new GeofenceDefinition(
        id,
        radialLocationPredicate.getLat(),
        radialLocationPredicate.getLng(),
        radialLocationPredicate.getRadius(),
        WRITE_ATTRIBUTE_HTTP_METHOD,
        url);
  }

  /** Send a silent push notification to the console to get it to refresh its geofences */
  protected void notifyAssetGeofencesChanged(Set<String> assetIds) {
    if (assetIds == null) {
      return;
    }

    List<String> ids = new ArrayList<>(assetIds);
    Map<String, Object> data = new HashMap<>();
    data.put("action", "GEOFENCE_REFRESH");

    // Break into batches of 10 sent every 10s to avoid consoles bombarding the backend
    int rows = (int) Math.ceil((((float) ids.size()) / 10));
    IntStream.range(0, rows)
        .forEach(
            i -> {
              final List<Notification.Target> subTargets =
                  ids.subList(10 * i, Math.min(10 + (10 * i), ids.size())).stream()
                      .map(id -> new Notification.Target(Notification.TargetType.ASSET, id))
                      .collect(Collectors.toList());
              final Notification notification =
                  new Notification(
                      "GeofenceRefresh",
                      new PushNotificationMessage().setData(data),
                      null,
                      null,
                      null);
              notification.setTargets(subTargets);

              scheduledExecutorService.schedule(
                  () -> {
                    LOG.fine(
                        "Notifying consoles that geofences have changed: "
                            + notification.getTargets());
                    notificationService.sendNotification(notification);
                  },
                  (long) i * NOTIFY_ASSETS_BATCH_MILLIS,
                  TimeUnit.MILLISECONDS);
            });
  }

  protected void processConsoleAssetChange(PersistenceEvent<ConsoleAsset> persistenceEvent) {
    ConsoleAsset asset = persistenceEvent.getEntity();

    switch (persistenceEvent.getCause()) {
      case CREATE:
      case UPDATE:
        if (isLinkedToORConsoleGeofenceAdapter(asset)) {
          consoleIdRealmMap.put(asset.getId(), asset.getRealm());
        } else {
          consoleIdRealmMap.remove(asset.getId());
        }
        break;
      case DELETE:
        consoleIdRealmMap.remove(asset.getId());
        break;
    }
  }

  protected static boolean isLinkedToORConsoleGeofenceAdapter(ConsoleAsset asset) {
    return asset
        .getConsoleProviders()
        .flatMap(
            consoleProviders ->
                Optional.ofNullable(consoleProviders.get("geofence"))
                    .map(ConsoleProvider::getVersion)
                    .map(NAME::equals))
        .orElse(false);
  }
}
