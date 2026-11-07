/*
 * Copyright 2021, OpenRemote Inc.
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
import static org.openremote.model.Constants.RESTRICTED_USER_REALM_ROLE;
import static org.openremote.model.syslog.SyslogCategory.API;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.MqttQoS;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.EdECKey;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.provisioning.ProvisioningService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.protocol.mqtt.Topic;
import org.openremote.model.provisioning.*;
import org.openremote.model.security.ClientRole;
import org.openremote.model.security.User;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.UniqueIdentifierGenerator;
import org.openremote.model.util.ValueUtil;

/**
 * This {@link MQTTHandler} is responsible for provisioning service users and assets and
 * authenticating the client against the configured {@link
 * org.openremote.model.provisioning.ProvisioningConfig}s.
 *
 * <h2>X.509 provisioning</h2>
 *
 * <p>A public certificate is not a credential, so a client must prove it holds the certificate's
 * private key on the same MQTT connection, for both initial enrollment and every reconnect:
 *
 * <ol>
 *   <li>Subscribe to {@code provisioning/{CN}/response}.
 *   <li>Publish an {@link X509ProvisioningChallengeRequestMessage} to {@code
 *       provisioning/{CN}/request}; the certificate is validated and an {@link
 *       X509ProvisioningChallengeResponseMessage} is returned.
 *   <li>Publish an {@link X509ProvisioningMessage} with the certificate and the signed challenge;
 *       only once the signature verifies is the service user and asset looked up or created and the
 *       connection authenticated.
 * </ol>
 *
 * <p>A challenge is a protocol prefix, the request topic and a random 256-bit nonce. It is bound to
 * the {@link RemotingConnection}, topic and certificate, expires after 60 seconds, is replaced by a
 * new request and is consumed by any verification attempt, so a proof can never be replayed. The
 * signature algorithm follows the certificate's key: SHA256withRSA, SHA256withECDSA for every
 * curve, Ed25519 or Ed448; RSASSA-PSS keys are rejected. Certificate-only {@code x509} requests are
 * rejected with {@code UNAUTHORIZED}; there is no fallback.
 *
 * <h3>Response topic ownership</h3>
 *
 * <p>Responses go to a device-wide topic, so whichever connection receives them must be the one
 * making the requests, otherwise an attacker could have the real device sign the attacker's
 * challenge or push errors into its exchange:
 *
 * <ul>
 *   <li>{@link #canSubscribe} claims the response topic atomically, and a connection may own only
 *       one, so a single connection cannot lock many devices out of provisioning.
 *   <li>{@link #canPublish} only lets the owner publish to the matching request topic.
 *   <li>Requests are processed from a queue after {@link #canPublish} authorized them, so ownership
 *       is checked again before processing, and a message whose connection has lost the topic is
 *       dropped without a reply.
 *   <li>Artemis runs the disconnect callbacks before it closes the transport, so cleanup marks the
 *       connection first and a concurrent {@link #canSubscribe} backs out of its claim.
 * </ul>
 *
 * <p>The client-facing protocol, including what a device must check before signing, is documented
 * at <a href="https://docs.openremote.io/docs/user-guide/gateways-and-devices/auto-provisioning">
 * Auto provisioning devices and users</a>.
 */
public class UserAssetProvisioningMQTTHandler extends MQTTHandler {

  protected static class ProvisioningPersistenceRouteBuilder extends RouteBuilder {

    UserAssetProvisioningMQTTHandler mqttHandler;

    public ProvisioningPersistenceRouteBuilder(UserAssetProvisioningMQTTHandler mqttHandler) {
      this.mqttHandler = mqttHandler;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void configure() throws Exception {

      from(PERSISTENCE_TOPIC)
          .routeId("Persistence-ProvisioningConfig")
          .filter(isPersistenceEventForEntityType(ProvisioningConfig.class))
          .process(
              exchange -> {
                PersistenceEvent<ProvisioningConfig<?, ?>> persistenceEvent =
                    (PersistenceEvent<ProvisioningConfig<?, ?>>)
                        exchange.getIn().getBody(PersistenceEvent.class);

                boolean forceDisconnect =
                    persistenceEvent.getCause() == PersistenceEvent.Cause.DELETE;

                if (persistenceEvent.getCause() == PersistenceEvent.Cause.UPDATE) {
                  // Force disconnect if the certain properties have changed
                  forceDisconnect =
                      persistenceEvent.hasPropertyChanged(ProvisioningConfig.DISABLED_PROPERTY_NAME)
                          || persistenceEvent.hasPropertyChanged(
                              ProvisioningConfig.DATA_PROPERTY_NAME);
                }

                if (forceDisconnect) {
                  LOG.fine(
                      "Provisioning config modified or deleted so forcing connected clients to disconnect: "
                          + persistenceEvent.getEntity());
                  mqttHandler.forceClientDisconnects(persistenceEvent.getEntity().getId());
                }
              });
    }
  }

  protected static final Logger LOG =
      SyslogCategory.getLogger(API, UserAssetProvisioningMQTTHandler.class);
  public static final String PROVISIONING_TOKEN = "provisioning";
  public static final String REQUEST_TOKEN = "request";
  public static final String RESPONSE_TOKEN = "response";
  public static final String UNIQUE_ID_PLACEHOLDER = "%UNIQUE_ID%";
  public static final String PROVISIONING_USER_PREFIX = "ps-";
  protected ProvisioningService provisioningService;
  protected TimerService timerService;
  protected AssetStorageService assetStorageService;
  protected ManagerKeycloakIdentityProvider identityProvider;
  protected boolean isKeycloak;
  protected final ConcurrentMap<Long, Set<RemotingConnection>>
      provisioningConfigAuthenticatedConnectionMap = new ConcurrentHashMap<>();
  protected Timer provisioningTimer;

  /**
   * The single source of truth for which connection owns a provisioning response topic; ownership
   * is claimed atomically in {@link #canSubscribe} and is required to publish the matching request
   * topic.
   */
  protected final ConcurrentMap<String, RemotingConnection> responseTopicOwners =
      new ConcurrentHashMap<>();

  /**
   * Reverse index of {@link #responseTopicOwners} so disconnects don't scan the whole map. A
   * connection may own at most one response topic, see {@link #canSubscribe}.
   */
  protected final ConcurrentMap<RemotingConnection, String> connectionResponseTopics =
      new ConcurrentHashMap<>();

  /**
   * Connections whose disconnect cleanup has started. Artemis calls the disconnect callbacks before
   * it closes the transport, so a subscribe being authorized at the same time cannot tell from the
   * transport that the connection is going; {@link #canSubscribe} checks this after claiming and
   * backs out. Weak keys let entries go once Artemis drops the connection.
   */
  protected final Set<RemotingConnection> closedConnections =
      Collections.newSetFromMap(
          CacheBuilder.newBuilder().weakKeys().<RemotingConnection, Boolean>build().asMap());

  protected final SecureRandom challengeRandom = new SecureRandom();
  protected final Cache<RemotingConnection, X509Challenge> provisioningChallenges;

  protected record X509Challenge(
      String topic, X509Certificate certificate, String challenge, String algorithm) {}

  public UserAssetProvisioningMQTTHandler() {
    this(Ticker.systemTicker());
  }

  protected UserAssetProvisioningMQTTHandler(Ticker ticker) {
    provisioningChallenges =
        CacheBuilder.newBuilder()
            .weakKeys()
            // Challenges live for at most 60s and are dropped with their connection; the size cap
            // only bounds memory under a burst, where an evicted device simply requests a new one.
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .ticker(ticker)
            .build();
  }

  @Override
  public void init(Container container, Configuration serverConfiguration) throws Exception {
    super.init(container, serverConfiguration);

    if (container.getMeterRegistry() != null) {
      provisioningTimer = container.getMeterRegistry().timer("or.provisioning", Tags.empty());
    }
  }

  @Override
  public void start(Container container) throws Exception {
    super.start(container);
    provisioningService = container.getService(ProvisioningService.class);
    timerService = container.getService(TimerService.class);
    assetStorageService = container.getService(AssetStorageService.class);
    ManagerIdentityService identityService = container.getService(ManagerIdentityService.class);

    if (!identityService.isKeycloakEnabled()) {
      LOG.warning("MQTT connections are not supported when not using Keycloak identity provider");
      isKeycloak = false;
    } else {
      isKeycloak = true;
      identityProvider = (ManagerKeycloakIdentityProvider) identityService.getIdentityProvider();
      container
          .getService(MessageBrokerService.class)
          .getContext()
          .addRoutes(new ProvisioningPersistenceRouteBuilder(this));
    }
  }

  /**
   * Limit the number of messages allowed in the address queue and fail when over; this will
   * disconnect the client. Approx 20ms per request with 20s timeout on auto provisioning request =
   * 1000
   */
  @Override
  protected AddressSettings getPublishTopicAddressSettings(
      Container container, String publishTopic) {
    AddressSettings addressSettings = super.getPublishTopicAddressSettings(container, publishTopic);
    if (addressSettings != null) {
      addressSettings.setMaxSizeMessages(1000L);
    }
    return addressSettings;
  }

  @Override
  public boolean handlesTopic(Topic topic) {
    // Skip standard checks
    return topicMatches(topic);
  }

  @Override
  public boolean checkCanSubscribe(
      RemotingConnection connection, AuthContext authContext, Topic topic) {
    // Skip standard checks
    if (!canSubscribe(connection, authContext, topic)) {
      getLogger()
          .fine(
              "Cannot subscribe to this topic, topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }
    return true;
  }

  @Override
  public boolean checkCanPublish(
      RemotingConnection connection, AuthContext authContext, Topic topic) {
    // Skip standard checks
    if (!canPublish(connection, authContext, topic)) {
      getLogger()
          .fine(
              "Cannot publish to this topic, topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }
    return true;
  }

  @Override
  public boolean topicMatches(Topic topic) {
    return isProvisioningTopic(topic)
        && topic.getTokens().length == 3
        && (isRequestTopic(topic) || isResponseTopic(topic));
  }

  @Override
  protected Logger getLogger() {
    return LOG;
  }

  @Override
  public boolean canSubscribe(RemotingConnection connection, AuthContext authContext, Topic topic) {
    if (!isKeycloak) {
      LOG.fine("Identity provider is not keycloak");
      return false;
    }

    boolean allowed =
        isResponseTopic(topic)
            && !TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 1))
            && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 1));

    if (!allowed) {
      return false;
    }

    String responseTopic = topic.toString();

    // A provisioning client only ever needs its own response topic, so a connection may own one.
    // Without this cap a single anonymous connection could claim the topics of many devices and
    // lock them all out of provisioning until it disconnects.
    String existingTopic = connectionResponseTopics.putIfAbsent(connection, responseTopic);
    if (existingTopic != null && !existingTopic.equals(responseTopic)) {
      LOG.info(
          "Connection already owns a provisioning response topic: existing="
              + existingTopic
              + ", requested="
              + responseTopic
              + ", "
              + MQTTBrokerService.connectionToString(connection));
      return false;
    }

    // Claim the response topic atomically during authorization. Otherwise two concurrent
    // subscribers could both pass this check and receive each other's challenges and responses.
    //
    // The claim deliberately outlives a subscribe that Artemis fails after authorizing it; it is
    // released by unsubscribe or disconnect. Deferring it to onSubscribe would reopen the window
    // above, and the leftover claim only keeps this one topic from other connections while the
    // connection that asked for it stays open. That connection can simply retry, as re-claiming
    // a topic it already owns is allowed.
    RemotingConnection owner =
        responseTopicOwners.compute(
            responseTopic,
            (t, existing) ->
                existing == null || existing == connection || !isOpen(existing)
                    ? connection
                    : existing);

    if (owner != connection) {
      // Give back the slot reserved above so the connection can still claim its own topic
      connectionResponseTopics.remove(connection, responseTopic);
      LOG.warning("Subscription already exists possible eavesdropping");
      return false;
    }

    // If disconnect cleanup started while claiming, it may already have run and would never remove
    // these entries, leaking the connection; clearConnectionState marks the connection before it
    // removes anything, so either this check sees the mark or the cleanup sees the claims.
    if (closedConnections.contains(connection)) {
      releaseResponseTopic(connection, responseTopic);
      return false;
    }

    return true;
  }

  @Override
  public void onSubscribe(RemotingConnection connection, Topic topic) {
    // Ownership was already claimed in canSubscribe, which is the only place it is granted; see
    // there for why a failed subscribe keeps its claim
  }

  @Override
  public void onUnsubscribe(RemotingConnection connection, Topic topic) {
    releaseResponseTopic(connection, topic.toString());
  }

  protected static boolean isOpen(RemotingConnection connection) {
    return connection.getTransportConnection() != null
        && connection.getTransportConnection().isOpen();
  }

  /**
   * Releases the topic only if this connection still owns it; a client that disconnects and quickly
   * reconnects has no guaranteed ordering of subscribe/unsubscribe calls.
   */
  protected void releaseResponseTopic(RemotingConnection connection, String responseTopic) {
    responseTopicOwners.remove(responseTopic, connection);
    connectionResponseTopics.remove(connection, responseTopic);
  }

  @Override
  public Set<String> getPublishListenerTopics() {
    return Set.of(PROVISIONING_TOKEN + "/" + Topic.SINGLE_LEVEL_TOKEN + "/" + REQUEST_TOKEN);
  }

  @Override
  public boolean canPublish(RemotingConnection connection, AuthContext authContext, Topic topic) {
    if (!isKeycloak) {
      LOG.fine("Identity provider is not keycloak");
      return false;
    }

    if (!isRequestTopic(topic)
        || TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 1))
        || TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 1))) {
      return false;
    }

    // Requests and their responses are two halves of one exchange on a device wide topic pair, so
    // only the connection that owns the response topic may publish requests. Without this an
    // unauthorised client could push errors into a legitimate device's handshake, or make the
    // manager do certificate validation work on messages it will never answer to that client.
    if (!ownsResponseTopic(connection, topic)) {
      LOG.fine(
          () ->
              "Connection does not own the provisioning response topic: topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }

    return true;
  }

  /**
   * The connection must be subscribed to {@code provisioning/{id}/response} to publish requests.
   */
  protected boolean ownsResponseTopic(RemotingConnection connection, Topic requestTopic) {
    return responseTopicOwners.get(getResponseTopic(requestTopic)) == connection;
  }

  @Override
  public void onPublish(RemotingConnection connection, Topic topic, ByteBuf body) {
    // Offload messages to the thread pool to improve processing rate
    if (!connection.getTransportConnection().isOpen()) {
      // Drop provisioning requests for closed connections
      LOG.finest(
          () ->
              "Skipping provisioning request as connection is now closed: "
                  + MQTTBrokerService.connectionToString(connection));
      return;
    }
    // When no more threads available in the executorService the calling ActiveMQ client thread will
    // execute which
    // will cause provisioning messages to wait in the address queue eventually hitting the message
    // limit and
    // additional requests will be failed until the queue drains, this provides natural rate
    // limiting.
    executorService.submit(
        () -> {
          if (provisioningTimer != null) {
            provisioningTimer.record(() -> processProvisioningRequest(connection, topic, body));
          } else {
            processProvisioningRequest(connection, topic, body);
          }
        });
  }

  @Override
  public void onConnectionLost(RemotingConnection connection) {
    clearConnectionState(connection);
    provisioningConfigAuthenticatedConnectionMap
        .values()
        .forEach(
            connections -> {
              if (connections.remove(connection)) {
                // Remove sessions as durable sessions will never reconnect if client ID already
                // subscribed to attribute topics
                mqttBrokerService.doForceDisconnect(connection);
              }
            });
  }

  @Override
  public void onDisconnect(RemotingConnection connection) {
    clearConnectionState(connection);
    provisioningConfigAuthenticatedConnectionMap
        .values()
        .forEach(
            connections -> {
              if (connections.remove(connection)) {
                // Remove sessions as durable sessions will never reconnect if client ID already
                // subscribed to attribute topics
                mqttBrokerService.doForceDisconnect(connection);
              }
            });
  }

  protected void processProvisioningRequest(
      RemotingConnection connection, Topic topic, ByteBuf body) {
    String payloadContent = body.toString(StandardCharsets.UTF_8);

    ProvisioningMessage provisioningMessage =
        ValueUtil.parse(payloadContent, ProvisioningMessage.class)
            .orElseGet(
                () -> {
                  LOG.info(
                      "Failed to parse provisioning request message from client: "
                          + MQTTBrokerService.connectionToString(connection));
                  publishMessage(
                      getResponseTopic(topic),
                      new ErrorResponseMessage(ErrorResponseMessage.Error.MESSAGE_INVALID),
                      MqttQoS.AT_MOST_ONCE);
                  return null;
                });

    if (provisioningMessage == null) {
      return;
    }

    switch (provisioningMessage) {
      case X509ProvisioningChallengeRequestMessage message ->
          processX509ProvisioningMessage(connection, topic, message.getCert(), null, true);
      case X509ProvisioningMessage message ->
          processX509ProvisioningMessage(
              connection, topic, message.getCert(), message.getSignature(), false);
      default -> {}
    }
  }

  protected static boolean isProvisioningTopic(Topic topic) {
    return PROVISIONING_TOKEN.equals(topicTokenIndexToString(topic, 0));
  }

  protected static boolean isRequestTopic(Topic topic) {
    return REQUEST_TOKEN.equals(topicTokenIndexToString(topic, 2));
  }

  protected static boolean isResponseTopic(Topic topic) {
    return RESPONSE_TOKEN.equals(topicTokenIndexToString(topic, 2));
  }

  protected String getResponseTopic(Topic topic) {
    return PROVISIONING_TOKEN + "/" + topicTokenIndexToString(topic, 1) + "/" + RESPONSE_TOKEN;
  }

  /**
   * Handles both steps of the X.509 exchange; {@code challengeRequest} selects the first one. No
   * lock is held here: the one use semantics of a challenge come from removing it atomically from
   * {@link #provisioningChallenges}, and the provisioning work that follows involves Keycloak I/O
   * that must not block the Artemis threads running the disconnect callbacks.
   */
  protected void processX509ProvisioningMessage(
      RemotingConnection connection,
      Topic topic,
      String cert,
      String signature,
      boolean challengeRequest) {

    LOG.fine(
        () ->
            "Processing X.509 provisioning message: "
                + MQTTBrokerService.connectionToString(connection));

    // canPublish checked ownership when the message arrived, but it is processed later from a queue
    // and the topic may have changed hands since. Drop it silently: any reply would go to the
    // response topic of whichever connection owns it now.
    if (!ownsResponseTopic(connection, topic)) {
      LOG.fine(
          () ->
              "Dropping X.509 provisioning message as the connection no longer owns the response"
                  + " topic: topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return;
    }

    if (TextUtil.isNullOrEmpty(cert)) {
      LOG.info(
          "Certificate is missing from X509 provisioning message"
              + MQTTBrokerService.connectionToString(connection));
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.CERTIFICATE_INVALID),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    // Parse client cert
    X509Certificate clientCertificate;
    try {
      clientCertificate = ProvisioningUtil.getX509Certificate(cert);
    } catch (CertificateException e) {
      LOG.log(
          Level.INFO,
          "Failed to parse X.509 certificate: " + MQTTBrokerService.connectionToString(connection),
          e);
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.CERTIFICATE_INVALID),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    X509ProvisioningConfig matchingConfig =
        getMatchingX509ProvisioningConfig(connection, clientCertificate);

    if (matchingConfig == null) {
      LOG.fine(
          "No matching provisioning config found for X.509 certificate: "
              + MQTTBrokerService.connectionToString(connection));
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.UNAUTHORIZED),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    // Check if config is disabled
    if (matchingConfig.isDisabled()) {
      LOG.fine(
          "Matching provisioning config is disabled for X.509 certificate: "
              + MQTTBrokerService.connectionToString(connection));
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.CONFIG_DISABLED),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    // Validate unique ID
    String certUniqueId =
        ProvisioningUtil.getSubjectCN(clientCertificate.getSubjectX500Principal());
    String uniqueId = topicTokenIndexToString(topic, 1);

    if (TextUtil.isNullOrEmpty(certUniqueId)) {
      LOG.info(
          () ->
              "X.509 certificate missing unique ID in subject CN: "
                  + MQTTBrokerService.connectionToString(connection));
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.UNIQUE_ID_MISMATCH),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    if (TextUtil.isNullOrEmpty(uniqueId) || !certUniqueId.equals(uniqueId)) {
      LOG.info(
          () ->
              "X.509 certificate unique ID doesn't match topic unique ID: "
                  + MQTTBrokerService.connectionToString(connection));
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.UNIQUE_ID_MISMATCH),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    if (challengeRequest) {
      issueX509Challenge(connection, topic, clientCertificate);
      return;
    }

    // A CA-valid public certificate is not an authentication credential. Require a one-time proof
    // on this connection before looking up or creating any user or asset, including on reconnect.
    if (!verifyX509Proof(connection, topic, clientCertificate, signature)) {
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.UNAUTHORIZED),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    String realm = matchingConfig.getRealm();

    // Get/create service user
    User serviceUser;

    try {
      LOG.finest("Checking service user: " + uniqueId);
      serviceUser = getCreateClientServiceUser(realm, identityProvider, uniqueId, matchingConfig);
    } catch (Exception e) {
      LOG.log(
          Level.WARNING,
          "Failed to retrieve/create service user: "
              + MQTTBrokerService.connectionToString(connection),
          e);
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.SERVER_ERROR),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    if (!serviceUser.getEnabled()) {
      LOG.info(
          () ->
              "Service user exists and has been disabled so cannot continue:  "
                  + MQTTBrokerService.connectionToString(connection));
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.USER_DISABLED),
          MqttQoS.AT_MOST_ONCE);
      return;
    }
    LOG.finest("Service user exists and is enabled");

    Asset<?> asset;

    try {
      LOG.finest(() -> "Checking provisioned asset: " + uniqueId);
      asset =
          getCreateClientAsset(assetStorageService, realm, uniqueId, serviceUser, matchingConfig);

      if (asset != null) {
        if (!matchingConfig.getRealm().equals(asset.getRealm())) {
          LOG.warning("Client asset realm mismatch");
          publishMessage(
              getResponseTopic(topic),
              new ErrorResponseMessage(ErrorResponseMessage.Error.ASSET_ERROR),
              MqttQoS.AT_MOST_ONCE);
          return;
        }
      }
    } catch (Exception e) {
      LOG.log(
          Level.WARNING,
          "Failed to retrieve/create asset: "
              + MQTTBrokerService.connectionToString(connection)
              + ", config="
              + matchingConfig,
          e);
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.SERVER_ERROR),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    // Authenticate the connection using this service user's credentials - this will also update the
    // connection's subject
    mqttBrokerService.authenticateConnection(
        connection, realm, serviceUser.getUsername(), serviceUser.getSecret());

    provisioningConfigAuthenticatedConnectionMap.compute(
        matchingConfig.getId(),
        (id, connections) -> {
          if (connections == null) {
            connections = ConcurrentHashMap.newKeySet();
          }
          connections.add(connection);
          return connections;
        });

    LOG.fine("Client successfully provisioned: " + uniqueId);
    publishMessage(
        getResponseTopic(topic), new SuccessResponseMessage(realm, asset), MqttQoS.AT_MOST_ONCE);
  }

  protected void issueX509Challenge(
      RemotingConnection connection, Topic topic, X509Certificate certificate) {
    provisioningChallenges.invalidate(connection);
    // Responses use a device wide topic. Only its owning connection may request a challenge,
    // otherwise an attacker could cause the legitimate subscriber to sign the attacker's challenge.
    // canPublish and processX509ProvisioningMessage already check this; it is repeated right before
    // the challenge is issued as ownership can still change during processing. Nothing is published
    // on failure, as the response topic belongs to another connection.
    if (!ownsResponseTopic(connection, topic)) {
      LOG.fine(
          () ->
              "Not issuing X.509 challenge as the connection does not own the response topic: "
                  + MQTTBrokerService.connectionToString(connection));
      return;
    }

    // Signature algorithms are chosen from the key algorithm as reported by the provider. EC keys
    // always get SHA-256 whatever the curve, and keys that report as RSASSA-PSS are not supported
    // and are rejected as UNAUTHORIZED.
    String algorithm =
        switch (certificate.getPublicKey().getAlgorithm()) {
          case "RSA" -> "SHA256withRSA";
          case "EC" -> "SHA256withECDSA";
          case "EdDSA" -> ((EdECKey) certificate.getPublicKey()).getParams().getName();
          case "Ed25519", "Ed448" -> certificate.getPublicKey().getAlgorithm();
          default -> null;
        };
    if (algorithm == null) {
      publishMessage(
          getResponseTopic(topic),
          new ErrorResponseMessage(ErrorResponseMessage.Error.UNAUTHORIZED),
          MqttQoS.AT_MOST_ONCE);
      return;
    }

    byte[] nonce = new byte[32];
    challengeRandom.nextBytes(nonce);
    String challenge =
        X509ProvisioningChallengeResponseMessage.CHALLENGE_PREFIX
            + topic
            + "\n"
            + Base64.getEncoder().encodeToString(nonce);
    provisioningChallenges.put(
        connection, new X509Challenge(topic.toString(), certificate, challenge, algorithm));
    publishMessage(
        getResponseTopic(topic),
        new X509ProvisioningChallengeResponseMessage(challenge, algorithm),
        MqttQoS.AT_MOST_ONCE);
  }

  protected boolean verifyX509Proof(
      RemotingConnection connection, Topic topic, X509Certificate certificate, String signature) {
    // Atomically consume the challenge even if the proof is invalid. Replays must never succeed.
    X509Challenge challenge = provisioningChallenges.asMap().remove(connection);
    if (challenge == null
        || TextUtil.isNullOrEmpty(signature)
        || !challenge.topic().equals(topic.toString())
        || !challenge.certificate().equals(certificate)
        || !ownsResponseTopic(connection, topic)) {
      return false;
    }
    try {
      Signature verifier = Signature.getInstance(challenge.algorithm());
      verifier.initVerify(certificate.getPublicKey());
      verifier.update(challenge.challenge().getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.getDecoder().decode(signature));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      LOG.fine(
          "Invalid X.509 provisioning proof: " + MQTTBrokerService.connectionToString(connection));
      return false;
    }
  }

  protected void clearConnectionState(RemotingConnection connection) {
    // Mark first so a concurrent canSubscribe backs out of any claim made after the removes below
    closedConnections.add(connection);
    provisioningChallenges.invalidate(connection);
    String responseTopic = connectionResponseTopics.remove(connection);

    if (responseTopic != null) {
      responseTopicOwners.remove(responseTopic, connection);
    }
  }

  @Override
  public void stop() throws Exception {
    provisioningChallenges.invalidateAll();
    super.stop();
  }

  protected X509ProvisioningConfig getMatchingX509ProvisioningConfig(
      RemotingConnection connection, X509Certificate clientCertificate) {
    return provisioningService.getProvisioningConfigs().stream()
        .filter(config -> config instanceof X509ProvisioningConfig)
        .map(config -> (X509ProvisioningConfig) config)
        .filter(
            config -> {
              try {
                X509Certificate caCertificate = config.getCertificate();
                if (caCertificate != null) {
                  if (caCertificate
                      .getSubjectX500Principal()
                      .getName()
                      .equals(clientCertificate.getIssuerX500Principal().getName())) {
                    LOG.finest(
                        () ->
                            "Client certificate issuer matches provisioning config CA certificate subject: "
                                + MQTTBrokerService.connectionToString(connection)
                                + ", config="
                                + config);
                    Date now = Date.from(timerService.getNow());

                    try {
                      clientCertificate.verify(caCertificate.getPublicKey());
                      LOG.finest(
                          () ->
                              "Client certificate verified against CA certificate: "
                                  + MQTTBrokerService.connectionToString(connection)
                                  + ", config="
                                  + config);

                      if (!config.getData().isIgnoreExpiryDate()) {
                        LOG.finest(
                            () ->
                                "Validating client certificate validity: "
                                    + MQTTBrokerService.connectionToString(connection)
                                    + ", timestamp="
                                    + now);
                        clientCertificate.checkValidity(now);
                      }

                      return true;
                    } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                      LOG.log(
                          Level.INFO,
                          "Client certificate failed validity check: "
                              + MQTTBrokerService.connectionToString(connection)
                              + ", timestamp="
                              + now,
                          e);
                    } catch (Exception e) {
                      LOG.log(
                          Level.INFO,
                          "Client certificate failed verification against CA certificate: "
                              + MQTTBrokerService.connectionToString(connection)
                              + ", config="
                              + config,
                          e);
                    }
                  }
                }
              } catch (Exception e) {
                LOG.log(
                    Level.WARNING,
                    "Failed to extract certificate from provisioning config: "
                        + MQTTBrokerService.connectionToString(connection)
                        + ", config="
                        + config,
                    e);
              }
              return false;
            })
        .findFirst()
        .orElse(null);
  }

  public static User getCreateClientServiceUser(
      String realm,
      ManagerKeycloakIdentityProvider identityProvider,
      String uniqueId,
      ProvisioningConfig<?, ?> provisioningConfig)
      throws RuntimeException {
    String username = (PROVISIONING_USER_PREFIX + uniqueId);
    User serviceUser =
        identityProvider.getUserByUsername(realm, User.SERVICE_ACCOUNT_PREFIX + username);

    if (serviceUser != null) {
      LOG.fine("Service user found: realm=" + realm + ", username=" + username);
      return serviceUser;
    }

    LOG.finest("Creating service user: realm=" + realm + ", username=" + username);

    serviceUser = new User().setServiceAccount(true).setEnabled(true).setUsername(username);

    String secret = UniqueIdentifierGenerator.generateId();
    serviceUser = identityProvider.createUpdateUser(realm, serviceUser, secret, true);

    if (provisioningConfig.getUserRoles() != null && provisioningConfig.getUserRoles().length > 0) {
      LOG.finest(
          "Setting user roles: realm="
              + realm
              + ", username="
              + username
              + ", roles="
              + Arrays.toString(provisioningConfig.getUserRoles()));
      identityProvider.updateUserClientRoles(
          realm,
          serviceUser.getId(),
          Constants.KEYCLOAK_CLIENT_ID,
          Arrays.stream(provisioningConfig.getUserRoles())
              .map(ClientRole::getValue)
              .toArray(String[]::new));
    } else {
      LOG.finest("No user roles defined: realm=" + realm + ", username=" + username);
    }

    if (provisioningConfig.isRestrictedUser()) {
      LOG.finest("User will be made restricted: realm=" + realm + ", username=" + username);
      identityProvider.updateUserRealmRoles(
          realm,
          serviceUser.getId(),
          identityProvider.addUserRealmRoles(
              realm, serviceUser.getId(), RESTRICTED_USER_REALM_ROLE));
    }

    // Inject secret
    serviceUser.setSecret(secret);
    return serviceUser;
  }

  public static Asset<?> getCreateClientAsset(
      AssetStorageService assetStorageService,
      String realm,
      String uniqueId,
      User serviceUser,
      ProvisioningConfig<?, ?> provisioningConfig)
      throws RuntimeException {
    // Prepend realm name to unique ID to generate asset ID to further improve uniqueness
    String assetId = UniqueIdentifierGenerator.generateId(realm + uniqueId);
    Asset<?> asset = assetStorageService.find(assetId);

    if (asset != null) {
      LOG.finest("Asset exists");
      return asset;
    }

    LOG.finest("Creating client asset: realm=" + realm + ", username=" + serviceUser.getUsername());

    if (TextUtil.isNullOrEmpty(provisioningConfig.getAssetTemplate())) {
      LOG.finest("Provisioning config doesn't contain an asset template: " + provisioningConfig);
      return null;
    }

    // Replace any placeholders in the template
    String assetTemplate = provisioningConfig.getAssetTemplate();
    assetTemplate = assetTemplate.replaceAll(UNIQUE_ID_PLACEHOLDER, uniqueId);

    // Try and parse provisioning config asset template
    asset =
        ValueUtil.parse(assetTemplate, Asset.class)
            .orElseThrow(
                () ->
                    new RuntimeException(
                        "Failed to de-serialise asset template into an asset instance: "
                            + provisioningConfig));

    // Set ID and realm
    asset.setId(assetId);
    asset.setRealm(realm);

    asset = assetStorageService.merge(asset);

    if (provisioningConfig.isRestrictedUser()) {
      assetStorageService.storeUserAssetLinks(
          Collections.singletonList(new UserAssetLink(realm, serviceUser.getId(), assetId)));
    }

    return asset;
  }

  protected void forceClientDisconnects(long provisioningConfigId) {

    Set<RemotingConnection> connections =
        provisioningConfigAuthenticatedConnectionMap.remove(provisioningConfigId);

    // Force disconnect of each connection and the disconnect handler will remove the connection
    // from the map
    connections.forEach(
        connection -> {
          try {
            mqttBrokerService.doForceDisconnect(connection);
          } catch (Exception e) {
            getLogger()
                .log(
                    Level.WARNING,
                    "Failed to disconnect client: "
                        + MQTTBrokerService.connectionToString(connection),
                    e);
          }
        });
  }
}
