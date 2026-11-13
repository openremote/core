/*
 * Copyright 2023, OpenRemote Inc.
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
package org.openremote.manager.mqtt;

import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC;
import static org.openremote.container.persistence.PersistenceService.isPersistenceEventForEntityType;
import static org.openremote.manager.gateway.GatewayService.isNotForGateway;
import static org.openremote.manager.mqtt.MQTTBrokerService.connectionToString;
import static org.openremote.model.syslog.SyslogCategory.API;
import static org.openremote.model.value.MetaItemType.USER_CONNECTED;

import io.netty.buffer.ByteBuf;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.security.IdentityProvider;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.security.ManagerIdentityProvider;
import org.openremote.model.Container;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeMap;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.protocol.mqtt.Topic;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.NameValuePredicate;
import org.openremote.model.security.User;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;
import org.openremote.model.value.ValueHolder;
import org.openremote.model.value.ValueType;

/**
 * This {@link MQTTHandler} just monitors connected users and handles updating of {@link
 * org.openremote.model.attribute.Attribute}s with {@link
 * org.openremote.model.value.MetaItemType#USER_CONNECTED} {@link
 * org.openremote.model.attribute.MetaItem}. It doesn't handle any publishes or subscriptions.
 */
public class ConnectionMonitorHandler extends MQTTHandler {

  protected static final Logger LOG = SyslogCategory.getLogger(API, ConnectionMonitorHandler.class);
  protected MQTTBrokerService mqttBrokerService;
  protected ExecutorService executorService;
  protected AssetStorageService assetStorageService;
  protected AssetProcessingService assetProcessingService;
  protected GatewayService gatewayService;
  protected PersistenceService persistenceService;
  protected ConcurrentMap<String, Set<AttributeRef>> userIDAttributeRefs =
      new ConcurrentHashMap<>();

  @Override
  public void init(Container container, Configuration serverConfiguration) throws Exception {
    super.init(container, serverConfiguration);
    executorService = container.getExecutor();
    mqttBrokerService = container.getService(MQTTBrokerService.class);
    assetStorageService = container.getService(AssetStorageService.class);
    assetProcessingService = container.getService(AssetProcessingService.class);
    gatewayService = container.getService(GatewayService.class);
    persistenceService = container.getService(PersistenceService.class);
    MessageBrokerService messageBrokerService = container.getService(MessageBrokerService.class);
    messageBrokerService
        .getContext()
        .addRoutes(
            new RouteBuilder() {
              @SuppressWarnings("unchecked")
              @Override
              public void configure() throws Exception {
                from(PERSISTENCE_TOPIC)
                    .routeId("Persistence-MQTTConnectedAttributes")
                    .filter(isPersistenceEventForEntityType(Asset.class))
                    .filter(isNotForGateway(gatewayService))
                    .process(
                        exchange -> {
                          PersistenceEvent<Asset<?>> persistenceEvent =
                              (PersistenceEvent<Asset<?>>)
                                  exchange.getIn().getBody(PersistenceEvent.class);

                          if (persistenceEvent.hasPropertyChanged("attributes")) {
                            Asset<?> asset = persistenceEvent.getEntity();
                            AttributeMap oldAttributes =
                                persistenceEvent.getPreviousState("attributes");
                            AttributeMap newAttributes =
                                persistenceEvent.getCurrentState("attributes");

                            if (oldAttributes != null) {
                              oldAttributes.stream()
                                  .filter(ConnectionMonitorHandler::attributeMatches)
                                  .forEach(
                                      attr ->
                                          attr.getMetaItem(USER_CONNECTED)
                                              .flatMap(ValueHolder::getValue)
                                              .ifPresent(
                                                  userID ->
                                                      removeSessionAttribute(
                                                          userID,
                                                          new AttributeRef(
                                                              asset.getId(), attr.getName()))));
                            }

                            if (newAttributes != null) {
                              List<Pair<String, Attribute<?>>> connectedAttributes =
                                  newAttributes.stream()
                                      .filter(ConnectionMonitorHandler::attributeMatches)
                                      .map(
                                          attr ->
                                              new Pair<String, Attribute<?>>(asset.getId(), attr))
                                      .toList();

                              addSessionAttributes(asset.getRealm(), connectedAttributes);
                            }
                          }
                        })
                    .end();
              }
            });
    // TODO: Register with broker service for persistence events
  }

  @Override
  public void start(Container container) throws Exception {
    // Don't do super start as don't need to publish or subscribe
    // super.start(container);

    // Don't block start
    executorService.submit(
        () -> {
          // Get all assets that have attributes with user connected meta and initialize them
          List<Asset<?>> assets =
              assetStorageService.findAll(
                  new AssetQuery()
                      .attributes(
                          new AttributePredicate()
                              .meta(new NameValuePredicate(USER_CONNECTED, null))));

          Map<String, List<Asset<?>>> realmAttributeMap =
              assets.stream().collect(Collectors.groupingBy(Asset::getRealm));

          realmAttributeMap.forEach(
              (realm, realmAssets) -> {
                List<Pair<String, Attribute<?>>> assetIdsAttrs =
                    realmAssets.stream()
                        .flatMap(
                            asset ->
                                asset.getAttributes().stream()
                                    .filter(ConnectionMonitorHandler::attributeMatches)
                                    .map(
                                        attr ->
                                            new Pair<String, Attribute<?>>(asset.getId(), attr)))
                        .toList();

                addSessionAttributes(realm, assetIdsAttrs);
              });
        });
  }

  @Override
  public void stop() throws Exception {
    super.stop();
  }

  @Override
  public void onConnect(RemotingConnection connection) {
    super.onConnect(connection);

    Pair<String, Set<AttributeRef>> userIDAndAttributeRefs = getUserIDAndAttributeRefs(connection);
    if (userIDAndAttributeRefs != null) {
      updateUserConnectedStatus(userIDAndAttributeRefs.key, userIDAndAttributeRefs.value, true);
    }
  }

  @Override
  public void onDisconnect(RemotingConnection connection) {
    super.onDisconnect(connection);

    Pair<String, Set<AttributeRef>> userIDAndAttributeRefs = getUserIDAndAttributeRefs(connection);
    if (userIDAndAttributeRefs != null) {
      updateUserConnectedStatus(userIDAndAttributeRefs.key, userIDAndAttributeRefs.value, false);
    }
  }

  @Override
  public void onConnectionLost(RemotingConnection connection) {
    super.onConnectionLost(connection);

    Pair<String, Set<AttributeRef>> userIDAndAttributeRefs = getUserIDAndAttributeRefs(connection);
    if (userIDAndAttributeRefs != null) {
      updateUserConnectedStatus(userIDAndAttributeRefs.key, userIDAndAttributeRefs.value, false);
    }
  }

  @Override
  public void onConnectionAuthenticated(RemotingConnection connection) {
    super.onConnectionAuthenticated(connection);

    Pair<String, Set<AttributeRef>> userIDAndAttributeRefs = getUserIDAndAttributeRefs(connection);
    if (userIDAndAttributeRefs != null) {
      updateUserConnectedStatus(userIDAndAttributeRefs.key, userIDAndAttributeRefs.value, true);
    }
  }

  @Override
  protected boolean topicMatches(Topic topic) {
    return false;
  }

  @Override
  protected Logger getLogger() {
    return LOG;
  }

  @Override
  public boolean canSubscribe(RemotingConnection connection, AuthContext authContext, Topic topic) {
    return false;
  }

  @Override
  public boolean canPublish(RemotingConnection connection, AuthContext authContext, Topic topic) {
    return false;
  }

  @Override
  public Set<String> getPublishListenerTopics() {
    return null;
  }

  @Override
  public void onPublish(RemotingConnection connection, Topic topic, ByteBuf body) {}

  @Override
  public void onSubscribe(RemotingConnection connection, Topic topic) {}

  @Override
  public void onUnsubscribe(RemotingConnection connection, Topic topic) {}

  protected void addSessionAttributes(
      String realm, List<Pair<String, Attribute<?>>> assetIdsAttrs) {
    LOG.finest(
        "Adding '"
            + assetIdsAttrs.size()
            + "' attributes(s) with user linked attributes in realm: "
            + realm);

    List<String> usernames =
        assetIdsAttrs.stream()
            .map(assetIdAttr -> assetIdAttr.getValue().getMetaValue(USER_CONNECTED).orElse(null))
            .filter(Objects::nonNull)
            .distinct()
            .map(
                username ->
                    username.startsWith(User.SERVICE_ACCOUNT_PREFIX)
                        ? username
                        : User.SERVICE_ACCOUNT_PREFIX + username)
            .toList();

    // Convert usernames to userIds
    List<String> userIds = ManagerIdentityProvider.getUserIds(persistenceService, realm, usernames);

    assetIdsAttrs.forEach(
        assetIdAttr ->
            assetIdAttr
                .getValue()
                .getMetaValue(USER_CONNECTED)
                .ifPresent(
                    username -> {
                      String userID =
                          userIds.get(usernames.indexOf(User.SERVICE_ACCOUNT_PREFIX + username));

                      if (userID == null) {
                        LOG.warning(
                            "Invalid username so skipping add session attributes: realm="
                                + realm
                                + ", username="
                                + username);
                      } else {
                        addSessionAttribute(
                            userID,
                            new AttributeRef(assetIdAttr.key, assetIdAttr.getValue().getName()));
                      }
                    }));
  }

  protected void addSessionAttribute(String userID, AttributeRef attributeRef) {
    LOG.finest("Adding userID '" + userID + "' monitoring for attribute: " + attributeRef);
    updateUserConnectedStatus(
        userID,
        Collections.singletonList(attributeRef),
        !mqttBrokerService.getUserConnections(userID).isEmpty());
    Set<AttributeRef> refs =
        userIDAttributeRefs.computeIfAbsent(userID, ID -> ConcurrentHashMap.newKeySet());
    refs.add(attributeRef);
  }

  protected void removeSessionAttribute(String userID, AttributeRef attributeRef) {
    LOG.finest("Removing userID '" + userID + "' monitoring for attribute: " + attributeRef);
    updateUserConnectedStatus(userID, Collections.singletonList(attributeRef), false);
    userIDAttributeRefs.computeIfPresent(
        userID,
        (ID, refs) -> {
          refs.remove(attributeRef);
          return refs.isEmpty() ? null : refs;
        });
  }

  protected void updateUserConnectedStatus(
      String userID, Collection<AttributeRef> attributeRefs, boolean connected) {

    // Only update statuses if this is the only connection
    Set<RemotingConnection> connections = mqttBrokerService.getUserConnections(userID);

    if (connected) {
      if (connections.size() > 1) {
        LOG.finest("Connections already exist for user so skipping status update: " + userID);
        return;
      }
    } else {
      if (!connections.isEmpty()) {
        LOG.finest("Other connections remain for user so skipping status update: " + userID);
        return;
      }
    }

    LOG.fine(
        "Updating connected status for '"
            + userID
            + "' on "
            + attributeRefs.size()
            + " attribute(s) connected="
            + connected);
    attributeRefs.forEach(
        attributeRef ->
            assetProcessingService.sendAttributeEvent(
                new AttributeEvent(attributeRef, connected), getClass().getSimpleName()));
  }

  protected Pair<String, Set<AttributeRef>> getUserIDAndAttributeRefs(
      RemotingConnection connection) {
    String userID = IdentityProvider.getSubjectId(connection.getSubject());

    if (userID == null) {
      if (LOG.isLoggable(Level.FINEST)) {
        LOG.finest(
            "Anonymous connection so cannot determine userID: " + connectionToString(connection));
      }
      return null;
    }

    Set<AttributeRef> attributeRefs = userIDAttributeRefs.get(userID);
    if (attributeRefs == null) {
      return null;
    }

    return new Pair<>(userID, attributeRefs);
  }

  protected static boolean attributeMatches(Attribute<?> attr) {
    return Objects.equals(attr.getType(), ValueType.BOOLEAN) && attr.hasMeta(USER_CONNECTED);
  }
}
