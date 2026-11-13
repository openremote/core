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

import static org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil.MQTT_QOS_LEVEL_KEY;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.MqttQoS;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.Subject;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.protocol.mqtt.MQTTUtil;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.reader.MessageUtil;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.security.IdentityProvider;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Container;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.protocol.mqtt.Topic;
import org.openremote.model.util.ValueUtil;

/**
 * This allows custom handlers to be discovered by the {@link MQTTBrokerService} during system
 * startup using the {@link java.util.ServiceLoader} mechanism. This allows topic(s) can be handled
 * in a custom way. Any instances must have a no-arg constructor.
 */
public abstract class MQTTHandler {

  public static final String TOKEN_MULTI_LEVEL_WILDCARD = "#";
  public static final String TOKEN_SINGLE_LEVEL_WILDCARD = "+";
  protected ClientEventService clientEventService;
  protected MQTTBrokerService mqttBrokerService;
  protected MessageBrokerService messageBrokerService;
  protected ManagerKeycloakIdentityProvider identityProvider;
  protected ExecutorService executorService;
  protected TimerService timerService;
  protected boolean isKeycloak;
  protected ClientSession clientSession;
  protected ClientProducer producer;

  /**
   * Gets the priority of this handler which is used to determine the call order; handlers with a
   * lower priority are initialized and started first.
   */
  public int getPriority() {
    return 0;
  }

  /** Provides a name to identify this custom handler for logging purposes etc. */
  public String getName() {
    return this.getClass().getSimpleName();
  }

  /** Called when the system starts to allow for initialization. */
  public void init(Container container, Configuration serverConfiguration) throws Exception {
    mqttBrokerService = container.getService(MQTTBrokerService.class);
    clientEventService = container.getService(ClientEventService.class);
    messageBrokerService = container.getService(MessageBrokerService.class);
    ManagerIdentityService identityService = container.getService(ManagerIdentityService.class);
    executorService = container.getExecutor();
    timerService = container.getService(TimerService.class);

    if (!identityService.isKeycloakEnabled()) {
      getLogger()
          .warning("MQTT connections are not supported when not using Keycloak identity provider");
      isKeycloak = false;
    } else {
      isKeycloak = true;
      identityProvider = (ManagerKeycloakIdentityProvider) identityService.getIdentityProvider();
    }

    addPublishTopicServerConfiguration(container, serverConfiguration);
  }

  /** Called when the system starts to allow for initialization. */
  public void start(Container container) throws Exception {

    clientSession = mqttBrokerService.createSession();

    // Create producer
    producer = clientSession.createProducer();

    Set<String> publishListenerTopics = getPublishListenerTopics();
    if (publishListenerTopics != null) {
      for (String publishListenerTopic : publishListenerTopics) {
        addPublishConsumer(publishListenerTopic);
      }
    }
  }

  /** Called when the system stops to allow for any cleanup. */
  public void stop() throws Exception {
    if (clientSession != null) {
      clientSession.close();
      clientSession = null;
    }
  }

  protected void addPublishTopicServerConfiguration(
      Container container, Configuration serverConfiguration) {
    Set<String> publishListenerTopics = getPublishListenerTopics();
    if (publishListenerTopics != null) {
      publishListenerTopics.forEach(
          topic -> {
            String coreTopic =
                MQTTUtil.getCoreAddressFromMqttTopic(
                    topic, mqttBrokerService.wildcardConfiguration);
            // Use literal address to avoid wildcard matching to other addresses
            AddressSettings addressSettings = getPublishTopicAddressSettings(container, topic);
            if (addressSettings != null) {
              serverConfiguration.addAddressSetting("(" + coreTopic + ")", addressSettings);
            }
          });
    }
  }

  /**
   * Just enable metrics on the topic by default but allow custom handlers to add more configuration
   * as required
   */
  protected AddressSettings getPublishTopicAddressSettings(
      Container container, String publishTopic) {
    if (container.getMeterRegistry() != null) {
      return new AddressSettings().setEnableMetrics(true);
    }

    return null;
  }

  protected void addPublishConsumer(String topic) throws Exception {
    try {
      getLogger().info("Adding publish consumer for topic '" + topic + "': handler=" + getName());
      String coreTopic =
          MQTTUtil.getCoreAddressFromMqttTopic(topic, mqttBrokerService.wildcardConfiguration);
      clientSession.createQueue(
          QueueConfiguration.of(coreTopic)
              .setDurable(false)
              .setRoutingType(RoutingType.MULTICAST)
              .setPurgeOnNoConsumers(true)
              .setAutoCreateAddress(true)
              .setAutoCreated(true));
      ClientConsumer consumer = clientSession.createConsumer(coreTopic);
      consumer.setMessageHandler(
          message -> {
            Topic publishTopic =
                Topic.parse(
                    MQTTUtil.getMqttTopicFromCoreAddress(
                        message.getAddress(), mqttBrokerService.wildcardConfiguration));
            String clientID = message.getStringProperty(MessageUtil.CONNECTION_ID_PROPERTY_NAME);
            RemotingConnection connection = mqttBrokerService.getConnectionFromClientID(clientID);

            // TODO: This is not ideal as publish has been accepted and then we drop the message if
            // the client has disconnected before it is processed
            // Need to be able to get connection/auth from the message somehow
            // Cannot use connection.getTransportConnection().isOpen() check as well due to last
            // will publishes
            if (connection == null) {
              getLogger()
                  .finer(
                      () ->
                          "Client is no longer connected so dropping publish to topic '"
                              + topic
                              + "': clientID="
                              + clientID);
              return;
            }

            getLogger()
                .finer(
                    () ->
                        "onPublish '"
                            + publishTopic
                            + "': "
                            + MQTTBrokerService.connectionToString(connection));

            try {
              onPublish(connection, publishTopic, message.getReadOnlyBodyBuffer().byteBuf());
            } catch (Exception e) {
              getLogger()
                  .info(
                      "An error occurred while handling onPublish to topic '"
                          + topic
                          + "': clientID="
                          + clientID);
            }
          });
    } catch (Exception e) {
      getLogger()
          .log(
              Level.SEVERE,
              "Failed to create handler consumer for topic '" + topic + "': handler=" + getName(),
              e);
      throw e;
    }
  }

  /**
   * Will be called when any client connects; if returns false then subsequent handlers will not be
   * called
   */
  public void onConnect(RemotingConnection connection) {}

  /** Will be called when any client disconnects */
  public void onDisconnect(RemotingConnection connection) {}

  /** Will be called when any client loses connection */
  public void onConnectionLost(RemotingConnection connection) {}

  /**
   * Called when an existing {@link RemotingConnection} is authenticated sometime after initially
   * connecting (see {@link UserAssetProvisioningMQTTHandler}); the authenticated {@link Subject}
   * can be retrieved using {@link RemotingConnection#getSubject}
   */
  public void onConnectionAuthenticated(RemotingConnection connection) {}

  /**
   * Checks that topic is valid and has at least 3 tokens (generally first two tokens should be
   * {realm}/{clientId} but it is up to the {@link #canSubscribe} and/or {@link #canPublish} methods
   * to validate this).
   */
  public boolean handlesTopic(Topic topic) {
    if (!topicTokenCountGreaterThan(topic, 2)) {
      return false;
    }
    if (!topicMatches(topic)) {
      return false;
    }
    return true;
  }

  /**
   * Checks that authenticated session and topic realm matches the authenticated user and also that
   * topic client ID matches the connection client ID.
   */
  public boolean checkCanSubscribe(
      RemotingConnection connection, AuthContext authContext, Topic topic) {
    if (authContext == null) {
      getLogger()
          .finest(
              "Anonymous connection subscriptions not supported by this handler, topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }
    if (!topicRealmAllowed(authContext, topic) || !topicClientIdMatches(connection, topic)) {
      getLogger()
          .finest(
              "Topic realm and client ID tokens must match the connection, topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }
    return canSubscribe(connection, authContext, topic);
  }

  /**
   * Checks that authenticated sessions and topic realm matches the authenticated user and also that
   * topic client ID matches the connection client ID.
   */
  public boolean checkCanPublish(
      RemotingConnection connection, AuthContext authContext, Topic topic) {
    if (authContext == null) {
      getLogger()
          .finest(
              "Anonymous connection publishes not supported by this handler topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }
    if (!topicRealmAllowed(authContext, topic) || !topicClientIdMatches(connection, topic)) {
      getLogger()
          .finest(
              "Topic realm and client ID tokens must match the connection topic="
                  + topic
                  + ", "
                  + MQTTBrokerService.connectionToString(connection));
      return false;
    }
    return canPublish(connection, authContext, topic);
  }

  /**
   * Called when {@link org.openremote.model.asset.UserAssetLink}s for a restricted user are changed
   * and that user has an active connection (subject can be accessed from the connection).
   */
  public void onUserAssetLinksChanged(
      RemotingConnection connection, List<PersistenceEvent<UserAssetLink>> changes) {}

  @SuppressWarnings("SynchronizeOnNonFinalField")
  public void publishMessage(String topic, Object data, MqttQoS qoS) {
    try {
      if (clientSession != null) {
        // Artemis' sessions are not threadsafe
        synchronized (clientSession) {
          ClientMessage message = clientSession.createMessage(false);
          message.putIntProperty(MQTT_QOS_LEVEL_KEY, qoS.value());
          message.writeBodyBufferBytes(
              ValueUtil.asJSON(data)
                  .map(String::getBytes)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Failed to convert payload to JSON string: " + data)));
          producer.send(
              MQTTUtil.getCoreAddressFromMqttTopic(
                  topic, mqttBrokerService.getWildcardConfiguration()),
              message);
        }
      }
    } catch (Exception e) {
      getLogger().log(Level.WARNING, "Couldn't publish to MQTT client: topic=" + topic, e);
    }
  }

  /**
   * Indicates if this handler will handle the specified topic; independent of whether it is a
   * publish or subscribe. Should generally check the third token onwards unless {@link
   * #handlesTopic} has been overridden.
   */
  protected abstract boolean topicMatches(Topic topic);

  protected abstract Logger getLogger();

  /**
   * Called to authorize a subscription if {@link #handlesTopic} returned true; should return true
   * if the subscription is allowed otherwise return false.
   */
  public abstract boolean canSubscribe(
      RemotingConnection connection, AuthContext authContext, Topic topic);

  /**
   * Called to authorize a publish if {@link #handlesTopic} returned true; should return true if the
   * publish is allowed otherwise return false.
   */
  public abstract boolean canPublish(
      RemotingConnection connection, AuthContext authContext, Topic topic);

  /** Called to handle subscribe if {@link #canSubscribe} returned true. */
  public abstract void onSubscribe(RemotingConnection connection, Topic topic);

  /** Called to handle unsubscribe if {@link #handlesTopic} returned true. */
  public abstract void onUnsubscribe(RemotingConnection connection, Topic topic);

  /**
   * Get the set of topics this handler wants to subscribe to for incoming publish messages;
   * messages that match these topics will be passed to {@link #onPublish}.
   */
  public abstract Set<String> getPublishListenerTopics();

  /** Called to handle publish if {@link #canPublish} returned true. */
  public abstract void onPublish(RemotingConnection connection, Topic topic, ByteBuf body);

  public static String topicRealm(Topic topic) {
    return topicTokenIndexToString(topic, 0);
  }

  public static String topicClientID(Topic topic) {
    return topicTokenIndexToString(topic, 1);
  }

  public static boolean topicRealmAllowed(AuthContext authContext, Topic topic) {
    return authContext != null
        && (authContext.getAuthenticatedRealmName().equals(topicRealm(topic))
            || authContext.isSuperUser());
  }

  public static boolean topicClientIdMatches(RemotingConnection connection, Topic topic) {
    return connection != null
        && Objects.equals(connection.getClientID(), topicTokenIndexToString(topic, 1));
  }

  public static boolean topicTokenCountGreaterThan(Topic topic, int size) {
    return topic.getTokens() != null && topic.getTokens().length > size;
  }

  public static String topicTokenIndexToString(Topic topic, int tokenNumber) {
    return topicTokenCountGreaterThan(topic, tokenNumber) ? topic.getTokens()[tokenNumber] : null;
  }

  protected static Subject getSubjectFromConnection(RemotingConnection connection) {
    return connection != null ? connection.getSubject() : null;
  }

  protected static Optional<AuthContext> getAuthContextFromConnection(
      RemotingConnection connection) {
    return Optional.ofNullable(getSubjectFromConnection(connection))
        .map(IdentityProvider::getTokenPrincipal);
  }
}
