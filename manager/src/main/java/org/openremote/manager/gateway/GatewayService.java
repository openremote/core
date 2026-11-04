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

import static org.apache.camel.builder.PredicateBuilder.or;
import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.container.persistence.PersistenceService.isPersistenceEventForEntityType;
import static org.openremote.manager.gateway.GatewayConnector.mapAssetId;
import static org.openremote.model.Constants.*;
import static org.openremote.model.syslog.SyslogCategory.GATEWAY;
import static org.openremote.model.util.MapAccess.getInteger;
import static org.openremote.model.util.MapAccess.getString;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingException;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.AttributeEventInterceptor;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.rules.RulesService;
import org.openremote.manager.rules.RulesetStorageService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.impl.GatewayAsset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeMap;
import org.openremote.model.attribute.AttributeWriteFailure;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.gateway.GatewayDisconnectEvent;
import org.openremote.model.gateway.GatewayTunnelInfo;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.rules.Ruleset;
import org.openremote.model.security.Realm;
import org.openremote.model.security.User;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.TextUtil;
import org.openremote.model.value.MetaItemType;

/**
 * Manages {@link org.openremote.model.asset.impl.GatewayAsset}s in the local instance by creating
 * Keycloak clients for them and handles the connection logic of gateways; it is the gateways
 * responsibility to connect to this instance, it is then up to this instance to authenticate the
 * gateway and to initiate synchronisation of gateway assets.
 *
 * <p>Also adds an {@link AttributeEventInterceptor} to consume any {@link AttributeEvent} destined
 * for a {@link GatewayAsset} descendant {@link Asset} and routes them to the underlying gateway for
 * handling; the underlying gateway then notifies us if/when the action has succeeded.
 */
public class GatewayService extends RouteBuilder implements ContainerService {

  // Need a high priority so that event authorizer can get the events before other authorizers
  public static final int PRIORITY = HIGH_PRIORITY + 100;
  public static final String GATEWAY_CLIENT_ID_PREFIX = "gateway-";
  public static final String OR_GATEWAY_TUNNEL_SSH_KEY_FILE = "OR_GATEWAY_TUNNEL_SSH_KEY_FILE";
  private static final Logger LOG =
      SyslogCategory.getLogger(GATEWAY, GatewayService.class.getName());
  public static final String OR_GATEWAY_TUNNEL_SSH_HOSTNAME = "OR_GATEWAY_TUNNEL_SSH_HOSTNAME";
  public static final String OR_GATEWAY_TUNNEL_SSH_PORT = "OR_GATEWAY_TUNNEL_SSH_PORT";
  public static final String OR_GATEWAY_TUNNEL_TCP_START = "OR_GATEWAY_TUNNEL_TCP_START";
  public static final String OR_GATEWAY_TUNNEL_HOSTNAME = "OR_GATEWAY_TUNNEL_HOSTNAME";
  public static final String OR_GATEWAY_TUNNEL_AUTO_CLOSE_MINUTES =
      "OR_GATEWAY_TUNNEL_AUTO_CLOSE_MINUTES";
  public static final int OR_GATEWAY_TUNNEL_TCP_START_DEFAULT = 9000;
  protected AssetStorageService assetStorageService;
  protected AssetProcessingService assetProcessingService;
  protected ManagerIdentityService identityService;
  protected ManagerKeycloakIdentityProvider identityProvider;
  protected ClientEventService clientEventService;
  protected RulesetStorageService rulesetStorageService;
  protected RulesService rulesService;
  protected ExecutorService executorService;
  protected ScheduledExecutorService scheduledExecutorService;
  protected TimerService timerService;
  protected String tunnelSSHHostname;
  protected String tunnelHostname;
  protected int tunnelSSHPort;
  protected int tunnelTCPStart;
  protected int tunnelAutoCloseMinutes;

  /**
   * Maps gateway asset IDs to connections; note that gateway asset IDs are stored lower case so
   * that they can be matched up to the service user client ID (which needs to be all lower case);
   * this could technically cause an ID collision but for now the odds of that are low enough to not
   * be a concern.
   */
  protected final Map<String, GatewayConnector> gatewayConnectorMap = new ConcurrentHashMap<>();

  protected final Map<String, String> assetIdGatewayIdMap = new ConcurrentHashMap<>();
  protected boolean active;
  protected List<String> realmIds = new ArrayList<>();
  protected Map<String, GatewayTunnelInfo> tunnelInfos = new ConcurrentHashMap<>();
  protected AtomicInteger pendingTunnelCounter = new AtomicInteger();

  @SuppressWarnings("unchecked")
  public static Predicate isNotForGateway(GatewayService gatewayService) {
    return exchange -> {
      if (isPersistenceEventForEntityType(Asset.class).matches(exchange)) {
        PersistenceEvent<Asset<?>> persistenceEvent =
            (PersistenceEvent<Asset<?>>) exchange.getIn().getBody(PersistenceEvent.class);
        Asset<?> asset = persistenceEvent.getEntity();

        // Check if asset parent is a gateway or a gateway descendant, if so ignore it
        // Need to look at parent as this asset may not have been acknowledged by the gateway
        // service yet
        return gatewayService.getLocallyRegisteredGatewayId(asset.getId(), asset.getParentId())
            == null;
      }
      if (isPersistenceEventForEntityType(Realm.class).matches(exchange)) {
        PersistenceEvent<Realm> persistenceEvent =
            (PersistenceEvent<Realm>) exchange.getIn().getBody(PersistenceEvent.class);
        Realm realm = persistenceEvent.getEntity();

        if (persistenceEvent.getCause() == PersistenceEvent.Cause.DELETE) {
          // Ruleset won't exist in storage so check cache
          return gatewayService.realmIds.remove(realm.getId());
        }
        Realm localRealm = gatewayService.identityProvider.getRealm(realm.getName());
        if (localRealm != null && localRealm.getId().equals(realm.getId())) {
          gatewayService.realmIds.add(realm.getId());
          return true;
        }
        return false;
      }
      if (isPersistenceEventForEntityType(Ruleset.class).matches(exchange)) {
        PersistenceEvent<Ruleset> persistenceEvent =
            (PersistenceEvent<Ruleset>) exchange.getIn().getBody(PersistenceEvent.class);
        Ruleset ruleset = persistenceEvent.getEntity();

        if (persistenceEvent.getCause() == PersistenceEvent.Cause.DELETE) {
          // Ruleset won't exist in storage so need to check engines
          return gatewayService.rulesService.isRulesetKnown(ruleset);
        }
        return gatewayService.rulesetStorageService.find(ruleset.getClass(), ruleset.getId())
            != null;
      }
      return true;
    };
  }

  protected static boolean isGatewayClientId(String clientId) {
    return clientId != null && clientId.startsWith(GATEWAY_CLIENT_ID_PREFIX);
  }

  public static String getGatewayIdFromClientId(String clientId) {
    return clientId.substring(GATEWAY_CLIENT_ID_PREFIX.length());
  }

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    assetStorageService = container.getService(AssetStorageService.class);
    assetProcessingService = container.getService(AssetProcessingService.class);
    identityService = container.getService(ManagerIdentityService.class);
    clientEventService = container.getService(ClientEventService.class);
    executorService = container.getExecutor();
    scheduledExecutorService = container.getScheduledExecutor();
    rulesetStorageService = container.getService(RulesetStorageService.class);
    rulesService = container.getService(RulesService.class);
    timerService = container.getService(TimerService.class);

    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new GatewayServiceResourceImpl(
                timerService, identityService, this, assetStorageService));

    if (!identityService.isKeycloakEnabled()) {
      LOG.warning(
          "Incoming edge gateway connections disabled: Not supported when not using Keycloak identity provider");
      active = false;
    } else {
      active = true;
      identityProvider = (ManagerKeycloakIdentityProvider) identityService.getIdentityProvider();
      container.getService(MessageBrokerService.class).getContext().addRoutes(this);
      clientEventService.setGatewayInterceptor(this::onGatewayMessageIntercept);

      assetProcessingService.addEventInterceptor(
          new AttributeEventInterceptor() {
            @Override
            public int getPriority() {
              // Needs to be the first interceptor
              return AttributeEventInterceptor.DEFAULT_PRIORITY - 1000;
            }

            @Override
            public boolean intercept(EntityManager em, AttributeEvent event)
                throws AssetProcessingException {
              return onAttributeEventIntercepted(em, event);
            }
          });
    }

    // Check tunnelling support
    tunnelSSHHostname = getString(container.getConfig(), OR_GATEWAY_TUNNEL_SSH_HOSTNAME, null);
    tunnelSSHPort = getInteger(container.getConfig(), OR_GATEWAY_TUNNEL_SSH_PORT, 0);
    tunnelTCPStart =
        getInteger(
            container.getConfig(),
            OR_GATEWAY_TUNNEL_TCP_START,
            OR_GATEWAY_TUNNEL_TCP_START_DEFAULT);
    tunnelHostname = getString(container.getConfig(), OR_GATEWAY_TUNNEL_HOSTNAME, null);
    tunnelAutoCloseMinutes =
        getInteger(container.getConfig(), OR_GATEWAY_TUNNEL_AUTO_CLOSE_MINUTES, 0);
  }

  @Override
  public void start(Container container) throws Exception {

    if (!active) {
      return;
    }

    List<GatewayAsset> gateways =
        assetStorageService.findAll(new AssetQuery().types(GatewayAsset.class)).stream()
            .map(asset -> (GatewayAsset) asset)
            .collect(Collectors.toList());
    List<String> gatewayIds = gateways.stream().map(Asset::getId).toList();
    gateways =
        gateways.stream()
            .filter(
                gateway ->
                    Arrays.stream(gateway.getPath())
                        .noneMatch(p -> !p.equals(gateway.getId()) && gatewayIds.contains(p)))
            .toList();

    if (!gateways.isEmpty()) {
      LOG.info("Directly registered gateways found = " + gateways.size());

      gateways.forEach(
          gateway -> {

            // Check if client has been created
            boolean hasClientId = gateway.getClientId().isPresent();
            boolean hasClientSecret = gateway.getClientSecret().isPresent();

            if (!hasClientId || !hasClientSecret) {
              createUpdateGatewayServiceUser(gateway);
            }

            // Create connector
            GatewayConnector connector =
                new GatewayConnector(
                    assetStorageService,
                    assetProcessingService,
                    executorService,
                    scheduledExecutorService,
                    this,
                    timerService,
                    gateway);
            gatewayConnectorMap.put(gateway.getId().toLowerCase(Locale.ROOT), connector);

            // Get IDs of all assets under this gateway
            List<Asset<?>> gatewayAssets =
                assetStorageService.findAll(
                    new AssetQuery()
                        .parents(gateway.getId())
                        .select(new AssetQuery.Select().excludeAttributes())
                        .recursive(true));

            gatewayAssets.forEach(asset -> assetIdGatewayIdMap.put(asset.getId(), gateway.getId()));
          });
    }
  }

  @Override
  public void stop(Container container) throws Exception {
    gatewayConnectorMap
        .values()
        .forEach(connector -> connector.disconnect(GatewayDisconnectEvent.Reason.TERMINATING));
    gatewayConnectorMap.clear();
    assetIdGatewayIdMap.clear();
    tunnelInfos.clear();
  }

  @Override
  public void configure() throws Exception {

    if (active) {
      from(PERSISTENCE_TOPIC)
          .routeId("Persistence-GatewayAsset")
          .filter(isPersistenceEventForEntityType(Asset.class))
          .process(
              exchange -> {
                @SuppressWarnings("unchecked")
                PersistenceEvent<Asset<?>> persistenceEvent =
                    exchange.getIn().getBody(PersistenceEvent.class);
                Asset<?> eventAsset = persistenceEvent.getEntity();

                if (!(eventAsset instanceof GatewayAsset) && gatewayConnectorMap.isEmpty()) {
                  return;
                }

                // Only gateways locally registered to this manager are of interest or gateway
                // descendant assets
                if (eventAsset instanceof GatewayAsset
                    && (isLocallyRegisteredGateway(eventAsset.getId())
                        || getLocallyRegisteredGatewayId(
                                eventAsset.getId(), eventAsset.getParentId())
                            == null)) {

                  if (persistenceEvent.getCause() != PersistenceEvent.Cause.DELETE) {
                    eventAsset = assetStorageService.find(eventAsset.getId(), true);
                    if (eventAsset == null) {
                      return;
                    }
                  }

                  processGatewayChange((GatewayAsset) eventAsset, persistenceEvent);

                } else {

                  String gatewayId =
                      getLocallyRegisteredGatewayId(eventAsset.getId(), eventAsset.getParentId());
                  if (gatewayId != null) {

                    if (persistenceEvent.getCause() != PersistenceEvent.Cause.DELETE) {
                      eventAsset = assetStorageService.find(eventAsset.getId(), true);
                      if (eventAsset == null) {
                        return;
                      }
                    }

                    processGatewayChildAssetChange(gatewayId, eventAsset, persistenceEvent);
                  }
                }
              });
    }
  }

  /**
   * This method by-passes the standard client event authorization so all consumers within the
   * GatewayConnector must handle authorization and/or ensure operations can only be conducted on
   * gateway descendants.
   */
  protected void onGatewayMessageIntercept(Exchange exchange) {
    String clientId = ClientEventService.getClientId(exchange);
    String realm = ClientEventService.getRealm(exchange);

    if (!isGatewayClientId(clientId)) {
      return;
    }

    if (header(SESSION_OPEN).matches(exchange)) {
      String sessionKey = ClientEventService.getSessionKey(exchange);
      processGatewayConnected(realm, clientId, sessionKey);
      return;
    }

    if (or(header(SESSION_CLOSE), header(SESSION_CLOSE_ERROR)).matches(exchange)) {
      String sessionKey = ClientEventService.getSessionKey(exchange);
      processGatewayDisconnected(realm, clientId, sessionKey);
      return;
    }

    // Inbound shared events
    if (body().isInstanceOf(SharedEvent.class).matches(exchange)) {
      exchange.setRouteStop(true);
      String sessionKey = ClientEventService.getSessionKey(exchange);
      String gatewayId = getGatewayIdFromClientId(clientId);
      processGatewayMessage(
          realm, gatewayId, sessionKey, exchange.getIn().getBody(SharedEvent.class));
    }
  }

  public boolean onAttributeEventIntercepted(EntityManager em, AttributeEvent event)
      throws AssetProcessingException {

    // If the event came from a gateway then we don't want to process it here
    if (getClass().getSimpleName().equals(event.getSource())) {
      // Clear out agentlink meta so agent interceptor doesn't try intercepting the event
      event.getMeta().remove(MetaItemType.AGENT_LINK);
      return false;
    }

    GatewayConnector connector = gatewayConnectorMap.get(event.getId().toLowerCase(Locale.ROOT));

    if (connector != null) {
      LOG.fine(
          "Attribute event for a locally registered gateway asset (Asset ID="
              + event.getId()
              + "): "
              + event.getRef());

      // This is a change to a locally registered gateway
      if (GatewayAsset.DISABLED.getName().equals(event.getName())) {
        boolean disabled = (Boolean) event.getValue().orElse(false);
        boolean isAlreadyDisabled = (Boolean) event.getOldValue().orElse(false);

        if (disabled != isAlreadyDisabled) {
          GatewayAsset gatewayAsset = assetStorageService.find(event.getId(), GatewayAsset.class);
          if (gatewayAsset == null) {
            String msg = "Gateway asset not found: ref=" + event.getRef();
            LOG.info(msg);
            throw new AssetProcessingException(AttributeWriteFailure.CANNOT_PROCESS, msg);
          }
          LOG.fine(
              "Gateway client disabled attribute updated so updating gateway service user enabled flag: (gatewayId="
                  + event.getId()
                  + ")");
          // Push value into asset for service user method
          gatewayAsset.setDisabled(disabled);
          createUpdateGatewayServiceUser(gatewayAsset);
          connector.setDisabled(disabled);
        }
      } else if (GatewayAsset.CLIENT_SECRET.getName().equals(event.getName())) {
        String newSecret = (String) event.getValue().orElse(null);
        GatewayAsset gatewayAsset = assetStorageService.find(event.getId(), GatewayAsset.class);
        if (gatewayAsset == null) {
          String msg = "Gateway asset not found: ref=" + event.getRef();
          LOG.warning(msg);
          throw new AssetProcessingException(AttributeWriteFailure.CANNOT_PROCESS, msg);
        }
        LOG.fine(
            "Gateway client secret attribute updated so updating gateway service user secret: (gatewayId="
                + event.getId()
                + ")");
        User gatewayServiceUser =
            identityProvider.getUserByUsername(
                event.getRealm(),
                User.SERVICE_ACCOUNT_PREFIX
                    + gatewayAsset
                        .getClientId()
                        .orElseThrow(
                            () -> {
                              String msg = "Gateway asset client ID is missing";
                              LOG.warning(msg);
                              return new AssetProcessingException(
                                  AttributeWriteFailure.CANNOT_PROCESS, msg);
                            }));
        if (gatewayServiceUser == null) {
          String msg =
              "Couldn't retrieve gateway service user to update secret: (gatewayId="
                  + event.getId()
                  + ")";
          LOG.warning(msg);
          throw new AssetProcessingException(AttributeWriteFailure.CANNOT_PROCESS, msg);
        }
        newSecret =
            identityProvider.resetSecret(event.getRealm(), gatewayServiceUser.getId(), newSecret);

        // Disconnect current session
        connector.disconnect(GatewayDisconnectEvent.Reason.TERMINATING);

        // Update the event value with the potentially newly generated secret
        event.setValue(newSecret);
      }

      return false;
    }

    String gatewayId = assetIdGatewayIdMap.get(event.getId());

    if (gatewayId != null) {
      LOG.fine(
          "Attribute event for a gateway descendant asset (assetId="
              + event.getId()
              + ", gatewayId="
              + gatewayId
              + ")");
      connector = gatewayConnectorMap.get(gatewayId.toLowerCase(Locale.ROOT));
      if (connector == null) {
        String msg =
            "Gateway not found for descendant asset, this should not happen!!! assetId="
                + event.getId()
                + ", gatewayId="
                + gatewayId
                + ")";
        LOG.warning(msg);
        throw new AssetProcessingException(AttributeWriteFailure.CANNOT_PROCESS, msg);
      }

      if (!connector.isConnected()) {
        LOG.info(
            "Gateway is not connected so attribute event for descendant asset will be dropped (assetId="
                + event.getId()
                + ", gatewayId="
                + gatewayId
                + ")");
        throw new AssetProcessingException(
            AttributeWriteFailure.CANNOT_PROCESS,
            "Gateway is not connected: gatewayId=" + connector.gatewayId);
      }

      LOG.fine(
          "Attribute event for a gateway descendant asset being forwarded to the gateway (assetRef="
              + event.getRef()
              + ", gatewayId="
              + gatewayId
              + ")");
      connector.sendMessageToGateway(
          new AttributeEvent(
                  mapAssetId(gatewayId, event.getId(), true),
                  event.getName(),
                  event.getValue().orElse(null),
                  event.getTimestamp())
              .setParentId(mapAssetId(gatewayId, event.getParentId(), true))
              .setRealm(event.getRealm()));

      // Consume this event as it is for a gateway descendant and we've sent it to that gateway for
      // processing
      return true;
    }

    // Don't consume event for non gateway descendant assets
    return false;
  }

  public AssetStorageService.DeleteResult deleteGatewayUntilFinished(String gatewayId) {
    GatewayConnector connector = gatewayConnectorMap.get(gatewayId.toLowerCase(Locale.ROOT));

    if (connector == null) {
      String msg = "Gateway is not known: Gateway ID=" + gatewayId;
      LOG.info(msg);
      throw new IllegalStateException(msg);
    }

    if (connector.isConnected()) {
      connector.setDisabled(true);
    }

    // Get all child gateway assets
    List<String> gatewayAssetIds =
        assetIdGatewayIdMap.entrySet().stream()
            .filter(entry -> entry.getValue().equals(gatewayId))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

    gatewayAssetIds.add(gatewayId);
    LOG.info("Deleting gateway asset and descendants: IDs=" + String.join(",", gatewayAssetIds));
    return assetStorageService.deleteWithResult(gatewayAssetIds, true);
  }

  public Collection<GatewayTunnelInfo> getTunnelInfos() {
    return this.tunnelInfos.values();
  }

  public GatewayTunnelInfo[] getGatewayTunnelInfos(String realm, String gatewayID) {
    return getTunnelInfos().stream()
        .filter(tunnel -> tunnel.getGatewayId().equals(gatewayID))
        .filter(tunnel -> tunnel.getRealm().equals(realm))
        .toArray(GatewayTunnelInfo[]::new);
  }

  protected boolean tunnellingSupported() {
    return !TextUtil.isNullOrEmpty(tunnelSSHHostname) && tunnelSSHPort > 0;
  }

  public GatewayTunnelInfo startTunnel(GatewayTunnelInfo tunnelInfo)
      throws IllegalArgumentException, IllegalStateException {
    if (!tunnellingSupported()) {
      String msg = "Failed to start tunnel: reason=tunnelling is not supported";
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    if (TextUtil.isNullOrEmpty(tunnelInfo.getGatewayId())) {
      String msg = "Failed to start tunnel: reason=gateway ID cannot be null or empty";
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    String gatewayId = tunnelInfo.getGatewayId().toLowerCase(Locale.ROOT);
    String realm = tunnelInfo.getRealm();
    final GatewayConnector connector = gatewayConnectorMap.get(gatewayId);

    if (connector == null || !realm.equals(connector.getRealm())) {
      String msg =
          "Failed to start tunnel: reason=Gateway disconnected or doesn't exist, id=" + gatewayId;
      LOG.info(msg);
      throw new IllegalStateException(msg);
    }

    if (!connector.isTunnellingSupported()) {
      String msg = "Failed to start tunnel: reason=Not supported by gateway, id=" + gatewayId;
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    if (!connector.isConnected()) {
      String msg = "Failed to start tunnel: reason=Not connected, id=" + gatewayId;
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    if (tunnelInfo.getType() == GatewayTunnelInfo.Type.TCP) {
      // This is pretty crude but should be robust enough
      int assignedPort =
          tunnelTCPStart
              + Math.toIntExact(
                  pendingTunnelCounter.get()
                      + tunnelInfos.values().stream()
                          .filter(ti -> ti.getType() == GatewayTunnelInfo.Type.TCP)
                          .count());
      tunnelInfo.setAssignedPort(assignedPort);
    }
    if (!TextUtil.isNullOrEmpty(tunnelHostname)) {
      tunnelInfo.setHostname(tunnelHostname);
    }
    if (tunnelAutoCloseMinutes > 0) {
      tunnelInfo.setAutoCloseTime(
          timerService.getNow().plus(Duration.ofMinutes(tunnelAutoCloseMinutes)));
    }

    CompletableFuture<Void> startFuture = connector.startTunnel(tunnelInfo);
    try {
      pendingTunnelCounter.incrementAndGet();
      startFuture.get();
      tunnelInfos.put(tunnelInfo.getId(), tunnelInfo);
      if (tunnelInfo.getAutoCloseTime() != null) {
        Duration delay = Duration.between(timerService.getNow(), tunnelInfo.getAutoCloseTime());
        scheduledExecutorService.schedule(
            () -> autoCloseTunnel(tunnelInfo.getId()), delay.toMillis(), TimeUnit.MILLISECONDS);
        LOG.fine(
            "Scheduled job to automatically close tunnel '"
                + tunnelInfo.getId()
                + "' at "
                + tunnelInfo.getAutoCloseTime());
      }
      return tunnelInfo;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof TimeoutException) {
        String msg =
            "Failed to start tunnel: A timeout occurred whilst waiting for the tunnel to be started: id="
                + gatewayId;
        LOG.log(Level.WARNING, msg);
      } else {
        String msg =
            "Failed to start tunnel: An error occurred whilst waiting for the tunnel to be started: id="
                + gatewayId
                + ", error="
                + e.getCause().getMessage();
        LOG.log(Level.WARNING, msg);
      }
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } finally {
      pendingTunnelCounter.decrementAndGet();
    }
  }

  public void stopTunnel(GatewayTunnelInfo tunnelInfo)
      throws IllegalArgumentException, IllegalStateException {
    if (!tunnellingSupported()) {
      String msg = "Failed to stop tunnel: reason=tunnelling is not supported";
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    if (TextUtil.isNullOrEmpty(tunnelInfo.getGatewayId())) {
      String msg = "Failed to stop tunnel: reason=gateway ID cannot be null or empty";
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    String gatewayId = tunnelInfo.getGatewayId().toLowerCase(Locale.ROOT);
    String realm = tunnelInfo.getRealm();
    GatewayConnector connector = gatewayConnectorMap.get(gatewayId);

    if (connector == null || !realm.equals(connector.getRealm())) {
      String msg =
          "Failed to stop tunnel: reason=Gateway disconnected or doesn't exist, id="
              + tunnelInfo.getGatewayId();
      LOG.info(msg);
      throw new IllegalStateException(msg);
    }

    if (!connector.isTunnellingSupported()) {
      String msg =
          "Failed to stop tunnel: reason=Not supported by gateway, id=" + tunnelInfo.getGatewayId();
      LOG.info(msg);
      throw new IllegalArgumentException(msg);
    }

    if (!connector.isConnected()) {
      LOG.info(
          "Just removing tunnel from list: reason=Not connected, id=" + tunnelInfo.getGatewayId());
      tunnelInfos.remove(tunnelInfo.getId(), tunnelInfo);
      return;
    }

    // Wait for up to 20 seconds for the tunnel to stop
    CompletableFuture<Void> stopFuture = connector.stopTunnel(tunnelInfo);
    try {
      stopFuture.get(20, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      String msg =
          "Failed to stop tunnel: An error occurred whilst waiting for the tunnel to be stopped: id="
              + tunnelInfo.getGatewayId();
      LOG.log(Level.WARNING, msg, e.getCause());
      throw new RuntimeException(msg, e.getCause());
    } catch (InterruptedException | TimeoutException e) {
      String msg =
          "Failed to stop tunnel: An error occurred whilst waiting for the tunnel to be stopped: id="
              + tunnelInfo.getGatewayId();
      LOG.warning(msg);
      throw new RuntimeException(msg);
    } finally {
      tunnelInfos.remove(tunnelInfo.getId(), tunnelInfo);
    }
  }

  /** Check if asset ID is a gateway asset registered locally on this manager */
  public boolean isLocallyRegisteredGateway(String assetId) {
    return gatewayConnectorMap.containsKey(assetId.toLowerCase(Locale.ROOT));
  }

  /** Check if the specified asset ID or parent ID is a known gateway or descendant of a gateway. */
  public String getLocallyRegisteredGatewayId(String assetId, String parentId) {
    // ConcurrentHashMap rejects null keys so preserve HashMap behaviour of mapping to null
    String gatewayId = assetId != null ? assetIdGatewayIdMap.get(assetId) : null;

    if (gatewayId != null) {
      return gatewayId;
    }

    if (parentId != null) {
      GatewayConnector connector = gatewayConnectorMap.get(parentId.toLowerCase(Locale.ROOT));

      if (connector != null) {
        return connector.gatewayId;
      }

      return getLocallyRegisteredGatewayId(parentId, null);
    }

    return null;
  }

  protected void processGatewayConnected(String realm, String gatewayClientId, String sessionId) {
    String gatewayId = getGatewayIdFromClientId(gatewayClientId);
    GatewayConnector connector = gatewayConnectorMap.get(gatewayId.toLowerCase(Locale.ROOT));

    if (connector == null) {
      LOG.warning(
          "Gateway connected but not recognised which shouldn't happen: GatewayID=" + gatewayId);
      clientEventService.sendToWebsocketSession(
          sessionId, new GatewayDisconnectEvent(GatewayDisconnectEvent.Reason.UNRECOGNISED));
      clientEventService.closeWebsocketSession(sessionId);
      return;
    }

    if (!Objects.equals(realm, connector.getRealm())) {
      LOG.warning("Gateway connected with incorrect realm: GatewayID=" + gatewayId);
      clientEventService.sendToWebsocketSession(
          sessionId, new GatewayDisconnectEvent(GatewayDisconnectEvent.Reason.UNRECOGNISED));
      clientEventService.closeWebsocketSession(sessionId);
      return;
    }

    if (connector.isDisabled()) {
      LOG.warning("Gateway is currently disabled so will be ignored: GatewayID=" + gatewayId);
      clientEventService.sendToWebsocketSession(
          sessionId, new GatewayDisconnectEvent(GatewayDisconnectEvent.Reason.DISABLED));
      clientEventService.closeWebsocketSession(sessionId);
      return;
    }

    connector.connected(
        sessionId,
        createConnectorMessageConsumer(sessionId),
        () -> {
          try {
            clientEventService.closeWebsocketSession(sessionId);
          } catch (Exception ignored) {
          }
        });
  }

  protected void processGatewayDisconnected(
      String realm, String gatewayClientId, String sessionId) {
    String gatewayId = getGatewayIdFromClientId(gatewayClientId);
    GatewayConnector connector = gatewayConnectorMap.get(gatewayId.toLowerCase(Locale.ROOT));

    // If a client is trying to cross realm boundaries do nothing
    if (connector != null && !Objects.equals(realm, connector.getRealm())) {
      LOG.warning("Gateway disconnected with incorrect realm: GatewayID=" + gatewayId);
      return;
    }

    try {
      if (connector != null) {
        connector.disconnected(sessionId);
      }
    } finally {
      if (connector == null || connector.getGatewayVersion() == null) {
        // Assume all gateway tunnels have been terminated
        LOG.fine(
            "Gateway doesn't support tunnel re-sync so removing all tunnel sessions: " + connector);
        tunnelInfos
            .values()
            .removeIf(tunnelInfo -> tunnelInfo.getGatewayId().equalsIgnoreCase(gatewayId));
      } else {
        LOG.fine(
            "Gateway supports tunnel re-sync so leaving tunnel sessions in place: " + connector);
      }
    }
  }

  protected void processGatewayMessage(
      String realm, String gatewayId, String sessionId, SharedEvent event) {
    GatewayConnector connector = gatewayConnectorMap.get(gatewayId.toLowerCase(Locale.ROOT));
    if (connector == null || !Objects.equals(realm, connector.getRealm())) {
      return;
    }
    if (!connector.isConnected() || !sessionId.equals(connector.getSessionId())) {
      LOG.finest("Gateway event received for an obsolete session so ignoring: " + this);
      return;
    }
    connector.onGatewayEvent(event);
  }

  protected void processGatewayChange(
      GatewayAsset gateway, PersistenceEvent<Asset<?>> persistenceEvent) {

    switch (persistenceEvent.getCause()) {
      case CREATE -> {
        createUpdateGatewayServiceUser(gateway);
        GatewayConnector connector =
            new GatewayConnector(
                assetStorageService,
                assetProcessingService,
                executorService,
                scheduledExecutorService,
                this,
                timerService,
                gateway);
        gatewayConnectorMap.put(gateway.getId().toLowerCase(Locale.ROOT), connector);
      }
      case UPDATE -> {
        // Check if this gateway has a connector
        GatewayConnector connector =
            gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT));
        if (connector == null) {
          break;
        }

        // Check if disabled
        boolean isNowDisabled = gateway.getDisabled().orElse(false);
        connector.setDisabled(isNowDisabled);

        if (persistenceEvent.hasPropertyChanged("attributes")) {
          // Check if disabled attribute has changed
          AttributeMap oldAttributes = persistenceEvent.getPreviousState("attributes");
          boolean wasDisabled = oldAttributes.getValue(GatewayAsset.DISABLED).orElse(false);

          if (wasDisabled != isNowDisabled) {
            createUpdateGatewayServiceUser(gateway);
          }
        }
      }
      case DELETE -> {
        // Check if this gateway has a connector
        GatewayConnector connector =
            gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT));
        if (connector == null) {
          break;
        }

        tunnelInfos
            .values()
            .forEach(
                tunnelInfo -> {
                  if (tunnelInfo.getGatewayId().equals(gateway.getId())) {
                    try {
                      this.stopTunnel(tunnelInfo);
                    } catch (IllegalArgumentException | IllegalStateException ignored) {

                    }
                  }
                });

        connector = gatewayConnectorMap.remove(gateway.getId().toLowerCase(Locale.ROOT));

        if (connector != null) {
          connector.disconnect(GatewayDisconnectEvent.Reason.UNRECOGNISED);
        }

        removeGatewayServiceUser(gateway);
      }
    }
  }

  protected void processGatewayChildAssetChange(
      String gatewayId, Asset<?> childAsset, PersistenceEvent<Asset<?>> persistenceEvent) {
    // The asset would have been modified by the gateway connector so all we need to do here is
    // update the id map
    switch (persistenceEvent.getCause()) {
      // ConcurrentHashMap operations are individually thread safe so no synchronization needed
      case CREATE, UPDATE -> assetIdGatewayIdMap.put(childAsset.getId(), gatewayId);
      case DELETE -> assetIdGatewayIdMap.remove(childAsset.getId());
    }
  }

  protected boolean isGatewayConnected(String gatewayId) {
    AtomicBoolean connected = new AtomicBoolean(false);

    gatewayConnectorMap.computeIfPresent(
        gatewayId.toLowerCase(Locale.ROOT),
        (id, gatewayConnector) -> {
          connected.set(gatewayConnector.isConnected());
          return gatewayConnector;
        });

    return connected.get();
  }

  public static String getGatewayClientId(String gatewayAssetId) {
    String clientId = GATEWAY_CLIENT_ID_PREFIX + gatewayAssetId.toLowerCase(Locale.ROOT);
    if (clientId.length() > 255) {
      clientId = clientId.substring(0, 254);
    }
    return clientId;
  }

  protected void createUpdateGatewayServiceUser(GatewayAsset gateway) {

    LOG.info("Creating/updating gateway service user for gateway id: " + gateway.getId());
    String clientId = getGatewayClientId(gateway.getId());
    String secret = gateway.getClientSecret().orElseGet(() -> UUID.randomUUID().toString());

    try {
      User gatewayUser =
          identityProvider.getUserByUsername(
              gateway.getRealm(), User.SERVICE_ACCOUNT_PREFIX + clientId);
      boolean userExists = gatewayUser != null;
      boolean createUpdateGatewayUser =
          gatewayUser == null
              || gatewayUser.getEnabled() == gateway.getDisabled().orElse(false)
              || Objects.equals(gatewayUser.getSecret(), gateway.getClientSecret().orElse(null));

      if (createUpdateGatewayUser) {
        gatewayUser =
            identityProvider.createUpdateUser(
                gateway.getRealm(),
                new User()
                    .setServiceAccount(true)
                    .setSystemAccount(true)
                    .setUsername(clientId)
                    .setEnabled(!gateway.getDisabled().orElse(false)),
                secret,
                true);
      }

      if (!userExists && gatewayUser != null) {
        // Make the user restricted with no permissions
        // Gateway events are handled directly by the GatewayConnector
        identityProvider.updateUserRealmRoles(
            gateway.getRealm(),
            gatewayUser.getId(),
            identityProvider.addUserRealmRoles(
                gateway.getRealm(), gatewayUser.getId(), RESTRICTED_USER_REALM_ROLE));
      }

      if (!clientId.equals(gateway.getClientId().orElse(null))
          || !secret.equals(gateway.getClientSecret().orElse(null))) {
        gateway.setClientId(clientId);
        gateway.setClientSecret(secret);
        assetStorageService.merge(gateway);
      }

      try {
        LOG.info("Created gateway keycloak client for gateway id: " + gateway.getId());
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Failed to merge registered gateway: " + gateway.getId(), e);
      }
    } catch (Exception e) {
      LOG.warning("Failed to create client for gateway: " + gateway.getId());
    }
  }

  protected void removeGatewayServiceUser(GatewayAsset gateway) {
    String id = gateway.getClientId().orElse(null);
    if (TextUtil.isNullOrEmpty(id)) {
      LOG.warning(
          "Cannot find gateway keycloak client ID so cannot remove keycloak client for gateway: "
              + gateway.getId());
      return;
    }
    identityProvider.deleteClient(gateway.getRealm(), id);
  }

  protected Consumer<Object> createConnectorMessageConsumer(String sessionId) {
    return msg -> clientEventService.sendToWebsocketSession(sessionId, msg);
  }

  String getTunnelSSHHostname() {
    return tunnelSSHHostname;
  }

  int getTunnelSSHPort() {
    return tunnelSSHPort;
  }

  public int getTunnelTCPStart() {
    return tunnelTCPStart;
  }

  protected void autoCloseTunnel(String tunnelId) {
    GatewayTunnelInfo tunnelInfo = tunnelInfos.get(tunnelId);
    if (tunnelInfo == null) {
      LOG.fine("Tunnel '" + tunnelId + "' not found so it cannot be automatically closed");
      return;
    }

    try {
      LOG.info("Automatically closing tunnel: " + tunnelId);
      stopTunnel(tunnelInfo);
    } catch (IllegalArgumentException | IllegalStateException e) {
      LOG.log(Level.WARNING, "Failed to automatically close tunnel: " + tunnelId, e);
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" + "active=" + active + '}';
  }
}
