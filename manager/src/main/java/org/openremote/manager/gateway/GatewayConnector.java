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
package org.openremote.manager.gateway;

import static org.openremote.model.syslog.SyslogCategory.GATEWAY;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.openremote.container.Container;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.system.VersionInfo;
import org.openremote.model.asset.*;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.impl.GatewayAsset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.gateway.*;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import org.openremote.model.util.VersionUtil;

/** Handles all communication between a gateway and this manager instance */
public class GatewayConnector {

  private static final Logger LOG =
      SyslogCategory.getLogger(GATEWAY, GatewayConnector.class.getName());
  public static final String EVENT_SOURCE = GatewayService.class.getSimpleName();
  public static int MAX_SYNC_RETRIES = 5;
  public static int SYNC_ASSET_BATCH_SIZE = 20;
  public static final String ASSET_READ_EVENT_NAME_INITIAL = "INITIAL";
  public static final String ASSET_READ_EVENT_NAME_BATCH = "BATCH";
  public static final long RESPONSE_TIMEOUT_MILLIS = 15000;
  protected static final Map<String, Pair<Function<String, String>, Function<String, String>>>
      ASSET_ID_MAPPERS = new ConcurrentHashMap<>();
  protected final String realm;
  protected final String gatewayId;
  protected final int assetIdMappingVersion;
  protected final AssetStorageService assetStorageService;
  protected final ExecutorService executorService;
  protected final ScheduledExecutorService scheduledExecutorService;
  protected final AssetProcessingService assetProcessingService;
  protected final GatewayService gatewayService;
  protected final TimerService timerService;
  protected List<AssetEvent> cachedAssetEvents;
  protected List<AttributeEvent> cachedAttributeEvents;
  protected Consumer<Object> gatewayMessageConsumer;
  protected Runnable requestDisconnect;
  protected final AtomicReference<String> sessionId = new AtomicReference<>();
  protected boolean disabled;
  protected boolean initialSyncInProgress;
  protected ScheduledFuture<?> syncProcessorFuture;
  protected ScheduledFuture<?> obsoleteReadFuture;
  protected Future<?> capabilitiesFuture;
  List<String> syncAssetIds;
  protected GatewayAsset gatewayAsset;
  int syncIndex;
  int syncErrors;
  String expectedSyncResponseName;
  protected boolean tunnellingSupported;
  protected String gatewayVersion;
  protected boolean tunnelTimeoutManagementSupported;
  protected final Map<Class<? extends SharedEvent>, Consumer<SharedEvent>> eventConsumerMap =
      new HashMap<>();
  protected static List<Integer> ALPHA_NUMERIC_CHARACTERS = new ArrayList<>(62);

  static {
    ALPHA_NUMERIC_CHARACTERS.addAll(
        Stream.concat(
                Stream.concat(
                    IntStream.rangeClosed('a', 'z').boxed(),
                    IntStream.rangeClosed('A', 'Z').boxed()),
                IntStream.rangeClosed('0', '9').boxed())
            .toList());
  }

  protected GatewayConnector(
      AssetStorageService assetStorageService,
      AssetProcessingService assetProcessingService,
      ExecutorService executorService,
      ScheduledExecutorService scheduledExecutorService,
      GatewayService gatewayService,
      TimerService timerService,
      GatewayAsset gateway,
      Instant mappingV2ActivatedOn) {

    this.assetStorageService = assetStorageService;
    this.assetProcessingService = assetProcessingService;
    this.executorService = executorService;
    this.scheduledExecutorService = scheduledExecutorService;
    this.gatewayService = gatewayService;
    this.timerService = timerService;
    this.disabled = gateway.getDisabled().orElse(false);
    this.realm = gateway.getRealm();
    this.gatewayId = gateway.getId();
    this.assetIdMappingVersion =
        getAssetIdMappingVersion(gateway.getCreatedOn(), mappingV2ActivatedOn);
    this.gatewayAsset = gateway;

    // Setup static inbound event handling
    synchronized (eventConsumerMap) {
      eventConsumerMap.put(AssetEvent.class, (e) -> onAssetEvent((AssetEvent) e));
      eventConsumerMap.put(AttributeEvent.class, (e) -> onAttributeEvent((AttributeEvent) e));
    }
    LOG.finest(
        "Setting connection status=" + ConnectionStatus.DISCONNECTED + ": " + getGatewayIdString());
    publishAttributeEvent(
        new AttributeEvent(
            gatewayId, GatewayAsset.STATUS, ConnectionStatus.DISCONNECTED, timerService.getNow()));
  }

  protected void sendMessageToGateway(Object message) {
    try {
      if (gatewayMessageConsumer != null) {
        gatewayMessageConsumer.accept(message);
        if (LOG.isLoggable(Level.FINEST)) {
          LOG.fine(() -> "Sending message '" + message + "': " + getGatewayIdString());
        } else {
          LOG.fine(() -> "Sending message: " + getGatewayIdString());
        }
      }
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to send message: " + getGatewayIdString(), e.getMessage());
    }
  }

  /** Connection for this gateway has started so initiate synchronisation of assets */
  protected void connected(
      String sessionId, Consumer<Object> gatewayMessageConsumer, Runnable requestDisconnect) {

    LOG.info("Connected: " + getGatewayIdString());

    synchronized (this.sessionId) {
      if (getSessionId() != null) {
        disconnect(GatewayDisconnectEvent.Reason.ALREADY_CONNECTED);
      }
      this.sessionId.set(sessionId);
    }
    this.gatewayMessageConsumer = gatewayMessageConsumer;
    this.requestDisconnect = requestDisconnect;

    // Reinitialise state
    initialSyncInProgress = true;
    syncProcessorFuture = null;
    cachedAssetEvents = new ArrayList<>();
    cachedAttributeEvents = new ArrayList<>();
    syncAssetIds = null;
    syncIndex = 0;
    syncErrors = 0;

    LOG.finest(
        "Setting connection status=" + ConnectionStatus.CONNECTING + ": " + getGatewayIdString());
    publishAttributeEvent(
        new AttributeEvent(
            gatewayId, GatewayAsset.STATUS, ConnectionStatus.CONNECTING, timerService.getNow()));
    startSync();
  }

  /** Connection to the edge gateway instance has been disconnected so stop any synchronisation */
  protected void disconnected(String sessionId) {
    synchronized (this.sessionId) {
      if (!sessionId.equals(this.sessionId.get())) {
        return;
      }
      this.sessionId.set(null);
    }

    if (requestDisconnect != null) {
      requestDisconnect.run();
    }
    requestDisconnect = null;

    LOG.info("Disconnected: " + getGatewayIdString());
    if (syncProcessorFuture != null) {
      LOG.finest("Aborting active sync process: " + getGatewayIdString());
      syncProcessorFuture.cancel(true);
    }
    if (capabilitiesFuture != null) {
      LOG.finest("Aborting capabilities request: " + getGatewayIdString());
      capabilitiesFuture.cancel(true);
    }

    initialSyncInProgress = false;
    LOG.finest(
        "Setting connection status=" + ConnectionStatus.DISCONNECTED + ": " + getGatewayIdString());
    publishAttributeEvent(
        new AttributeEvent(
            gatewayId, GatewayAsset.STATUS, ConnectionStatus.DISCONNECTED, timerService.getNow()));
  }

  protected void disconnect(GatewayDisconnectEvent.Reason reason) {
    synchronized (this.sessionId) {
      if (isConnected()) {
        if (!disabled) {
          sendMessageToGateway(new GatewayDisconnectEvent(reason));
        }
        disconnected(getSessionId());
      }
    }
  }

  protected boolean isConnected() {
    return sessionId.get() != null;
  }

  protected boolean isInitialSyncInProgress() {
    return initialSyncInProgress;
  }

  protected boolean isTunnellingSupported() {
    return tunnellingSupported;
  }

  /** Request for gateway capabilities such as tunneling support */
  protected void getCapabilities() {

    CompletableFuture<GatewayCapabilitiesResponseEvent> future = new CompletableFuture<>();

    synchronized (eventConsumerMap) {
      if (eventConsumerMap.containsKey(GatewayCapabilitiesResponseEvent.class)) {
        return;
      }

      eventConsumerMap.put(
          GatewayCapabilitiesResponseEvent.class,
          (e) -> {
            GatewayCapabilitiesResponseEvent response = (GatewayCapabilitiesResponseEvent) e;
            future.complete(response);
          });
    }

    LOG.finest("Requesting gateway capabilities: " + getGatewayIdString());
    sendMessageToGateway(new GatewayCapabilitiesRequestEvent());

    future
        .orTimeout(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .whenComplete(
            (response, error) -> {
              synchronized (eventConsumerMap) {
                eventConsumerMap.remove(GatewayCapabilitiesResponseEvent.class); // Cleanup
              }
              if (error != null) {
                if (error instanceof TimeoutException) {
                  LOG.warning(
                      "Capabilities request timeout error, assuming no support: "
                          + getGatewayIdString());
                } else {
                  LOG.warning(
                      "Capabilities request error ["
                          + error.getMessage()
                          + "] assuming no support: "
                          + getGatewayIdString());
                }
              }
              // Let the gateway know init is complete
              LOG.info("Gateway fully initialised: " + getGatewayIdString());

              sendMessageToGateway(new GatewayInitDoneEvent());
              tunnellingSupported = response != null && response.isTunnelingSupported();
              gatewayVersion = response != null ? response.getVersion() : null;
              tunnelTimeoutManagementSupported =
                  gatewayVersion != null
                      && VersionUtil.isVersionGreaterOrEqual(gatewayVersion, "1.1.0");
              if (!Objects.equals(VersionInfo.getGatewayApiVersion(), gatewayVersion)) {
                LOG.warning(
                    "Remote gateway's Gateway API version does not match the central manager's gateway API version. Current Central Instance API version: "
                        + VersionInfo.getGatewayApiVersion()
                        + ", Remote Gateway API version: "
                        + gatewayVersion
                        + ". GatewayId: "
                        + gatewayId);
              }
              LOG.finest(
                  "Tunnelling supported=" + tunnellingSupported + ": " + getGatewayIdString());
              publishAttributeEvent(
                  new AttributeEvent(
                      gatewayId, GatewayAsset.TUNNELING_SUPPORTED, tunnellingSupported));
              LOG.finest(
                  "Setting connection status="
                      + ConnectionStatus.CONNECTED
                      + ": "
                      + getGatewayIdString());
              publishAttributeEvent(
                  new AttributeEvent(
                      gatewayId,
                      GatewayAsset.STATUS,
                      ConnectionStatus.CONNECTED,
                      timerService.getNow()));
            });
  }

  protected CompletableFuture<Void> startTunnel(GatewayTunnelInfo tunnelInfo) {

    if (!isConnected() || isInitialSyncInProgress()) {
      String msg =
          "Start tunnel failed not connected or initial sync in progress: " + getGatewayIdString();
      LOG.info(msg);
      throw new IllegalStateException(msg);
    }

    CompletableFuture<Void> future = new CompletableFuture<>();

    synchronized (eventConsumerMap) {
      if (eventConsumerMap.containsKey(GatewayTunnelStartResponseEvent.class)) {
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("A start tunnel request is already pending"));
      }

      eventConsumerMap.put(
          GatewayTunnelStartResponseEvent.class,
          (e) -> {
            GatewayTunnelStartResponseEvent response = (GatewayTunnelStartResponseEvent) e;
            if (response != null && response.getError() != null) {
              future.completeExceptionally(
                  new RuntimeException(
                      "Failed to start tunnel: error=" + response.getError() + ", " + tunnelInfo));
            } else {
              LOG.info("Tunnel started: " + tunnelInfo);
              future.complete(null);
            }
          });
    }

    sendMessageToGateway(
        new GatewayTunnelStartRequestEvent(
            gatewayService.getTunnelSSHHostname(), gatewayService.getTunnelSSHPort(), tunnelInfo));

    return future
        .orTimeout(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .whenComplete(
            (result, ex) -> {
              synchronized (eventConsumerMap) {
                eventConsumerMap.remove(GatewayTunnelStartResponseEvent.class);
              }
            });
  }

  protected CompletableFuture<Void> stopTunnel(GatewayTunnelInfo tunnelInfo) {

    CompletableFuture<Void> future = new CompletableFuture<>();

    synchronized (eventConsumerMap) {
      if (eventConsumerMap.containsKey(GatewayTunnelStopResponseEvent.class)) {
        return CompletableFuture.failedFuture(
            new IllegalArgumentException("A stop tunnel request is already pending"));
      }

      eventConsumerMap.put(
          GatewayTunnelStopResponseEvent.class,
          (e) -> {
            GatewayTunnelStopResponseEvent response = (GatewayTunnelStopResponseEvent) e;
            if (response != null && response.getError() != null) {
              throw new RuntimeException(
                  "Failed to stop tunnel: error=" + response.getError() + ", " + tunnelInfo);
            }
            future.complete(null);
          });
    }

    sendMessageToGateway(new GatewayTunnelStopRequestEvent(tunnelInfo));

    return future
        .orTimeout(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .whenComplete(
            (result, ex) -> {
              synchronized (eventConsumerMap) {
                eventConsumerMap.remove(GatewayTunnelStopResponseEvent.class);
              }
              if (ex != null && !(ex instanceof TimeoutException)) {
                // Re-throw unexpected exceptions
                throw new RuntimeException("Failed to get gateway response", ex);
              }
            });
  }

  protected String getRealm() {
    return realm;
  }

  protected boolean isDisabled() {
    return disabled;
  }

  protected void setDisabled(boolean disabled) {
    this.disabled = disabled;
    disconnect(GatewayDisconnectEvent.Reason.DISABLED);
  }

  protected String getSessionId() {
    return sessionId.get();
  }

  protected String getGatewayVersion() {
    return gatewayVersion;
  }

  public boolean isTunnelTimeoutManagementSupported() {
    return tunnelTimeoutManagementSupported;
  }

  protected void publishAttributeEvent(AttributeEvent event) {
    assetProcessingService.sendAttributeEvent(event, EVENT_SOURCE);
  }

  protected synchronized void onGatewayEvent(SharedEvent e) {
    LOG.finest(() -> "Received gateway event: event=" + e + ": " + getGatewayIdString());
    try {
      if (initialSyncInProgress) {
        if (e instanceof AssetsEvent) {
          if (obsoleteReadFuture != null) {
            obsoleteReadFuture.cancel(true);
            obsoleteReadFuture = null;
          }
          onSyncAssetsResponse((AssetsEvent) e);
        } else if (e instanceof AttributeEvent) {
          cachedAttributeEvents.add((AttributeEvent) e);
        } else if (e instanceof AssetEvent) {
          cachedAssetEvents.add((AssetEvent) e);
        }
      } else {
        SharedEvent ev = authoriseGatewayEvent(e);

        if (ev != null) {
          synchronized (eventConsumerMap) {
            Consumer<SharedEvent> consumer = eventConsumerMap.get(ev.getClass());
            if (consumer != null) {
              consumer.accept(ev);
            }
          }
        } else {
          LOG.log(
              Level.WARNING, () -> "Invalid event received from gateway:" + getGatewayIdString());
        }
      }
    } catch (Exception ex) {
      LOG.log(
          Level.WARNING,
          ex,
          () ->
              "An error occurred while processing a gateway event: event="
                  + e
                  + ": "
                  + getGatewayIdString());
      disconnect(GatewayDisconnectEvent.Reason.SYNC_ERROR);
    }
  }

  /**
   * Explicitly check event type and authorise accordingly don't blindly accept events from gateway
   * clients as they may be malicious - every event type added to {@link #eventConsumerMap} should
   * be handled
   */
  protected SharedEvent authoriseGatewayEvent(SharedEvent event) {
    // Gateway capabilities response events are always authorised
    if (event instanceof GatewayCapabilitiesResponseEvent) {
      return event;
    }

    // Gateway tunnel start/stop response events are always authorised
    if (event instanceof GatewayTunnelStartResponseEvent
        || event instanceof GatewayTunnelStopResponseEvent) {
      return event;
    }

    // Only some attribute and asset events are authorised
    if (event instanceof AssetInfo assetInfo) {

      // Map the gateway Asset ID to the central instance
      String assetId = mapAssetId(assetInfo.getId(), false);
      // Only use the gateway ID as the parent asset ID if the event is an asset event
      String parentAssetId =
          assetInfo.getParentId() == null
              ? event instanceof AssetEvent ? gatewayId : null
              : mapAssetId(assetInfo.getParentId(), false);

      // Events for the gateway asset are not allowed over the connector
      if (Objects.equals(assetId, gatewayId)) {
        LOG.log(Level.INFO, "Events for the gateway asset itself are not allowed from the gateway");
        return null;
      }

      String eventGatewayId = gatewayService.getLocallyRegisteredGatewayId(assetId, parentAssetId);

      if (eventGatewayId == null || !Objects.equals(eventGatewayId, gatewayId)) {
        LOG.log(Level.INFO, "Event is not for a descendant of this gateway asset");
        return null;
      }

      return event;
    }

    LOG.log(Level.INFO, "Event is not supported");
    return null;
  }

  /** Get list of gateway assets (get basic details and then batch load them to minimise load) */
  protected synchronized void startSync() {
    if (syncAborted()) {
      return;
    }

    expectedSyncResponseName = ASSET_READ_EVENT_NAME_INITIAL;
    sendMessageToGateway(
        new GatewayInitStartEvent(
            gatewayService.getGatewayTunnelInfos(realm, gatewayId),
            VersionInfo.getGatewayApiVersion(),
            gatewayService.getTunnelSSHHostname(),
            gatewayService.getTunnelSSHPort() > 0 ? gatewayService.getTunnelSSHPort() : null));

    // TODO: Remove this once enough time has passed since this commit
    // We delay so the init start has a chance to be responded to
    obsoleteReadFuture =
        Container.SCHEDULED_EXECUTOR.schedule(
            () -> {
              // Send old read assets event for older gateway versions
              ReadAssetsEvent event =
                  new ReadAssetsEvent(
                      new AssetQuery()
                          .select(new AssetQuery.Select().excludeAttributes())
                          .recursive(true));
              event.setMessageID(expectedSyncResponseName);
              sendMessageToGateway(event);
            },
            2000,
            TimeUnit.MILLISECONDS);

    syncProcessorFuture =
        scheduledExecutorService.schedule(
            this::onSyncAssetsTimeout, RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
  }

  /**
   * Called if a response isn't received from the gateway within {@link #RESPONSE_TIMEOUT_MILLIS}
   */
  protected synchronized void onSyncAssetsTimeout() {
    if (!isConnected()) {
      return;
    }

    LOG.info("Asset sync timeout occurred: " + getGatewayIdString());
    syncErrors++;

    if (syncAborted()) {
      return;
    }

    if (syncAssetIds == null) {
      // Haven't received initial list of assets so retry
      startSync();
    } else {
      requestAssets();
    }
  }

  protected boolean syncAborted() {
    if (syncErrors == MAX_SYNC_RETRIES) {
      LOG.warning("Asset sync max retries reached so disconnecting: " + getGatewayIdString());
      disconnect(GatewayDisconnectEvent.Reason.SYNC_ERROR);
      return true;
    }

    return false;
  }

  /**
   * Request assets in batches of {@link #SYNC_ASSET_BATCH_SIZE} to avoid overloading the gateway
   */
  protected void requestAssets() {

    if (syncAborted()) {
      return;
    }

    String[] requestAssetIds =
        syncAssetIds.stream().skip(syncIndex).limit(SYNC_ASSET_BATCH_SIZE).toArray(String[]::new);
    expectedSyncResponseName = ASSET_READ_EVENT_NAME_BATCH + syncIndex;

    LOG.fine(
        "Synchronising assets "
            + (syncIndex + 1)
            + "-"
            + (syncIndex + requestAssetIds.length)
            + " of "
            + syncAssetIds.size()
            + ": "
            + getGatewayIdString());
    ReadAssetsEvent event = new ReadAssetsEvent(new AssetQuery().ids(requestAssetIds));
    event.setMessageID(expectedSyncResponseName);
    sendMessageToGateway(event);
    syncProcessorFuture =
        scheduledExecutorService.schedule(
            this::requestAssets, RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
  }

  protected synchronized void onSyncAssetsResponse(AssetsEvent e) {
    if (!isConnected()) {
      return;
    }

    String messageId = e.getMessageID();

    if (!expectedSyncResponseName.equalsIgnoreCase(messageId)) {
      LOG.warning(
          "Unexpected asset sync response so ignoring (expected="
              + expectedSyncResponseName
              + ", actual ="
              + messageId
              + "): "
              + getGatewayIdString());
      return;
    }

    syncProcessorFuture.cancel(true);
    syncProcessorFuture = null;
    boolean isInitialResponse = ASSET_READ_EVENT_NAME_INITIAL.equalsIgnoreCase(messageId);

    if (isInitialResponse) {
      if (e.getAssets() == null) {
        throw new IllegalStateException("Missing gateway sync inventory");
      }

      // Put assets in hierarchical order
      Map<String, String> gatewayAssetIdParentIdMap =
          e.getAssets().stream()
              .collect(HashMap::new, (m, v) -> m.put(v.getId(), v.getParentId()), HashMap::putAll);

      if (gatewayAssetIdParentIdMap.size() != e.getAssets().size()) {
        throw new IllegalStateException("Duplicate gateway asset IDs in sync inventory");
      }
      gatewayAssetIdParentIdMap.forEach(
          (id, parent) -> {
            validateAssetId(id);
            if (parent != null && !gatewayAssetIdParentIdMap.containsKey(parent)) {
              throw new IllegalStateException("Missing parent in gateway sync inventory");
            }
          });

      ToIntFunction<Asset<?>> assetLevelExtractor =
          asset -> {
            int level = 0;
            String parentId = asset.getParentId();
            Set<String> visited = new HashSet<>();
            visited.add(asset.getId());
            while (parentId != null) {
              if (!visited.add(parentId)) {
                throw new IllegalStateException("Cycle in gateway sync inventory");
              }
              level++;
              parentId = gatewayAssetIdParentIdMap.get(parentId);
            }
            return level;
          };

      // Validate every chain, including single-asset inventories where sorting needs no
      // comparisons.
      e.getAssets().forEach(assetLevelExtractor::applyAsInt);
      syncAssetIds =
          e.getAssets().stream()
              .sorted(Comparator.comparingInt(assetLevelExtractor))
              .map(Asset::getId)
              .collect(Collectors.toList());

      if (syncAssetIds.isEmpty()) {
        deleteObsoleteLocalAssets();
        onInitialSyncComplete();
        return;
      }

      requestAssets();

    } else {

      if (syncAssetIds == null) {
        LOG.warning(
            "Unexpected asset sync message, so requesting disconnect: " + getGatewayIdString());
        disconnect(GatewayDisconnectEvent.Reason.SYNC_ERROR);
        return;
      }

      List<String> requestedAssetIds =
          syncAssetIds.stream()
              .skip(syncIndex)
              .limit(SYNC_ASSET_BATCH_SIZE)
              .collect(Collectors.toList());
      List<Asset<?>> returnedAssets =
          e.getAssets() == null ? Collections.emptyList() : e.getAssets();

      // Remove any assets that have been deleted since requested
      cachedAssetEvents.removeIf(
          assetEvent -> {
            boolean remove =
                requestedAssetIds.stream()
                    .anyMatch(
                        id ->
                            id.equals(assetEvent.getId())
                                && assetEvent.getCause() == AssetEvent.Cause.DELETE);
            if (remove) {
              syncAssetIds.remove(assetEvent.getId());
              requestedAssetIds.remove(assetEvent.getId());
            }
            return remove;
          });

      if (returnedAssets.stream().map(Asset::getId).distinct().count() != returnedAssets.size()
          || returnedAssets.size() != requestedAssetIds.size()
          || !returnedAssets.stream()
              .allMatch(asset -> requestedAssetIds.contains(asset.getId()))) {
        LOG.warning(
            "Unexpected asset sync response invalid batch count or ID mismatch, attempting to re-send the request: "
                + getGatewayIdString());
        syncErrors++;
        requestAssets();
        return;
      }

      // Returned asset order may not match request order so re-order
      returnedAssets =
          returnedAssets.stream()
              .sorted(Comparator.comparingInt(a -> syncAssetIds.indexOf(a.getId())))
              .toList();

      // Merge returned assets ensuring the latest version of each is merged
      returnedAssets.stream()
          .map(
              returnedAsset -> {
                final AtomicReference<Asset<?>> latestAssetVersion =
                    new AtomicReference<>(returnedAsset);
                cachedAssetEvents.removeIf(
                    assetEvent -> {
                      boolean remove =
                          assetEvent.getId().equals(returnedAsset.getId())
                              && (assetEvent.getCause() == AssetEvent.Cause.UPDATE
                                  || assetEvent.getCause() == AssetEvent.Cause.READ);
                      if (remove
                          && assetEvent.getAsset().getVersion()
                              > latestAssetVersion.get().getVersion()) {
                        latestAssetVersion.set(assetEvent.getAsset());
                      }
                      return remove;
                    });
                return latestAssetVersion.get();
              })
          .forEach(this::saveAssetLocally);

      // Request next batch or move on
      syncIndex += requestedAssetIds.size();
      if (syncIndex >= syncAssetIds.size()) {
        LOG.finest("Asset sync completed: " + getGatewayIdString());

        Set<String> refreshAssets = new HashSet<>();

        cachedAssetEvents.forEach(
            assetEvent -> {
              if (assetEvent.getCause() == AssetEvent.Cause.DELETE) {
                syncAssetIds.remove(assetEvent.getId());
              } else if (assetEvent.getCause() == AssetEvent.Cause.CREATE) {
                syncAssetIds.add(assetEvent.getId());
                saveAssetLocally(assetEvent.getAsset());
              } else {
                refreshAssets.add(assetEvent.getId());
              }
            });

        deleteObsoleteLocalAssets();
        onInitialSyncComplete();

        // Refresh attributes that have changed
        cachedAttributeEvents.forEach(
            attributeEvent -> {
              String assetId = attributeEvent.getId();
              if (!refreshAssets.contains(assetId)) {
                LOG.info(
                    "1 or more asset attribute values have changed so requesting the asset again (Asset<?> ID="
                        + assetId
                        + ": "
                        + getGatewayIdString());
                refreshAssets.add(assetId);
              }
            });

        // Refresh assets that have changed
        refreshAssets.forEach(id -> sendMessageToGateway(new ReadAssetEvent(id)));
      } else {
        requestAssets();
      }
    }
  }

  protected void deleteObsoleteLocalAssets() {

    // Find obsolete local assets
    List<Asset<?>> localAssets =
        assetStorageService.findAll(
            new AssetQuery()
                .select(new AssetQuery.Select().excludeAttributes())
                .recursive(true)
                .parents(gatewayId));

    // Delete obsolete assets
    List<String> obsoleteLocalAssetIds =
        localAssets.stream()
            .map(Asset::getId)
            .filter(id -> !syncAssetIds.contains(mapAssetId(id, true)))
            .toList();

    if (!obsoleteLocalAssetIds.isEmpty()) {
      boolean deleted = deleteAssetsLocally(obsoleteLocalAssetIds);
      if (!deleted) {
        throw new IllegalStateException("Failed to delete obsolete gateway assets: " + gatewayId);
      }
    }
  }

  protected void onInitialSyncComplete() {
    LOG.fine("Asset sync complete: " + gatewayId);
    initialSyncInProgress = false;
    cachedAssetEvents.clear();
    cachedAttributeEvents.clear();
    getCapabilities();
  }

  @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
  protected synchronized void onAssetEvent(AssetEvent e) {

    switch (e.getCause()) {
      case CREATE, READ, UPDATE -> saveAssetLocally(e.getAsset());
      case DELETE -> {
        if (!deleteAssetsLocally(Collections.singletonList(mapAssetId(e.getId(), false)))) {
          throw new IllegalStateException("Gateway asset deletion rejected");
        }
      }
    }
  }

  protected void onAttributeEvent(AttributeEvent e) {
    // Just push the event through the processing chain
    assetProcessingService.sendAttributeEvent(
        new AttributeEvent(
            mapAssetId(e.getId(), false), e.getName(), e.getValue().orElse(null), e.getTimestamp()),
        EVENT_SOURCE + ":" + gatewayId);
  }

  protected <T extends Asset<?>> T saveAssetLocally(T asset) {
    String assetId = asset.getId();
    asset.setId(mapAssetId(assetId, false));
    asset.setParentId(
        asset.getParentId() != null ? mapAssetId(asset.getParentId(), false) : gatewayId);
    asset.setRealm(realm);
    LOG.finest(
        "Creating/updating asset: Asset ID="
            + assetId
            + ", Asset ID Mapped="
            + asset.getId()
            + ": "
            + getGatewayIdString());
    return assetStorageService.merge(asset, true, gatewayAsset, null);
  }

  protected boolean deleteAssetsLocally(List<String> assetIds) {
    LOG.finest(
        "Removing gateway asset: Asset IDs="
            + Arrays.toString(assetIds.toArray())
            + ": "
            + getGatewayIdString());
    return assetStorageService.deleteGatewayAssets(assetIds, gatewayId);
  }

  public GatewayAsset getGatewayAsset() {
    return gatewayAsset;
  }

  @Override
  public String toString() {
    return GatewayConnector.class.getSimpleName() + "{" + "gatewayId='" + gatewayId + '\'' + '}';
  }

  protected String getGatewayIdString() {
    return "GatewayID=" + gatewayId;
  }

  /** Gateways created before this installation's V2 upgrade retain V1 for all their assets. */
  public static int getAssetIdMappingVersion(Instant createdOn, Instant mappingV2ActivatedOn) {
    Objects.requireNonNull(createdOn, "Gateway creation timestamp is required");
    Objects.requireNonNull(mappingV2ActivatedOn, "Gateway mapping cutoff is required");
    return createdOn.isBefore(mappingV2ActivatedOn) ? 1 : 2;
  }

  /** Use the mapping selected from the persisted creation timestamp when connecting. */
  public String mapAssetId(String assetId, boolean outbound) {
    return mapAssetId(gatewayId, assetId, outbound, assetIdMappingVersion);
  }

  public static String mapAssetId(String gatewayId, String assetId, boolean outbound, int version) {
    validateAssetId(gatewayId);
    validateAssetId(assetId);
    return switch (version) {
      case 1 -> mapAssetIdV1(gatewayId, assetId, outbound);
      case 2 -> mapAssetIdV2(gatewayId, assetId, outbound);
      default ->
          throw new IllegalArgumentException("Unsupported gateway mapping version: " + version);
    };
  }

  private static void validateAssetId(String id) {
    if (id == null || !id.matches(org.openremote.model.Constants.ASSET_ID_REGEXP)) {
      throw new IllegalArgumentException("Invalid gateway asset ID: " + id);
    }
  }

  /** Legacy V1 mapping. Keep its arithmetic unchanged for existing registrations. */
  private static String mapAssetIdV1(String gatewayId, String assetId, boolean outbound) {
    Pair<Function<String, String>, Function<String, String>> gatewayIdMappers =
        ASSET_ID_MAPPERS.computeIfAbsent(
            gatewayId,
            gwId -> {
              int g1 = gatewayId.charAt(0) % ALPHA_NUMERIC_CHARACTERS.size();
              int g2 = gatewayId.charAt(1) % ALPHA_NUMERIC_CHARACTERS.size();

              BiFunction<Integer, String, String> mapper =
                  (sign, id) -> {
                    int a1 =
                        (ALPHA_NUMERIC_CHARACTERS.indexOf((int) id.charAt(0))
                                + (sign * g1)
                                + ALPHA_NUMERIC_CHARACTERS.size())
                            % ALPHA_NUMERIC_CHARACTERS.size();
                    int a2 =
                        (ALPHA_NUMERIC_CHARACTERS.indexOf((int) id.charAt(1))
                                + (sign * g2)
                                + ALPHA_NUMERIC_CHARACTERS.size())
                            % ALPHA_NUMERIC_CHARACTERS.size();
                    return String.valueOf((char) ALPHA_NUMERIC_CHARACTERS.get(a1).intValue())
                        + ((char) ALPHA_NUMERIC_CHARACTERS.get(a2).intValue())
                        + id.substring(2);
                  };

              return new Pair<>(
                  id -> mapper.apply(1, id), // Inbound
                  id -> mapper.apply(-1, id) // Outbound
                  );
            });

    return outbound ? gatewayIdMappers.value.apply(assetId) : gatewayIdMappers.key.apply(assetId);
  }

  /** Position-by-position Base62 index addition; subtraction reverses the mapping. */
  private static String mapAssetIdV2(String gatewayId, String assetId, boolean outbound) {
    StringBuilder mapped = new StringBuilder(22);
    for (int i = 0; i < 22; i++) {
      int gatewayIndex = ALPHA_NUMERIC_CHARACTERS.indexOf((int) gatewayId.charAt(i));
      int assetIndex = ALPHA_NUMERIC_CHARACTERS.indexOf((int) assetId.charAt(i));
      int index = Math.floorMod(assetIndex + (outbound ? -gatewayIndex : gatewayIndex), 62);
      mapped.append((char) ALPHA_NUMERIC_CHARACTERS.get(index).intValue());
    }
    return mapped.toString();
  }
}
