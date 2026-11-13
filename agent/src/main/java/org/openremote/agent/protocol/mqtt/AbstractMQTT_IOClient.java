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
package org.openremote.agent.protocol.mqtt;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientSslConfig;
import com.hivemq.client.mqtt.MqttClientSslConfigBuilder;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.exceptions.ConnectionClosedException;
import com.hivemq.client.mqtt.exceptions.ConnectionFailedException;
import com.hivemq.client.mqtt.exceptions.MqttClientStateException;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3DisconnectException;
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3ConnectBuilder;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAck;
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAckReturnCode;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.openremote.agent.protocol.io.IOClient;
import org.openremote.container.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.auth.UsernamePassword;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.ValueUtil;

/**
 * HiveMQ client re-subscribing has no callback mechanism so we cannot track which subscriptions
 * fail on re-connect so we have to manually handle reconnect and re-subscribe until this issue is
 * resolved, if this is ever implemented then automaticReconnect could be reinstated - see version
 * history if/when needed (see <a
 * href="https://github.com/hivemq/hivemq-mqtt-client/issues/510"></a>)
 */
public abstract class AbstractMQTT_IOClient<S> implements IOClient<MQTTMessage<S>> {

  protected static final class TopicSubscriptionInfo<S> {
    MqttQos qos;
    CompletableFuture<Boolean> subAckFuture;
    Set<Consumer<MQTTMessage<S>>> consumers = new HashSet<>();

    TopicSubscriptionInfo(
        CompletableFuture<Boolean> subAckFuture, MqttQos qos, Consumer<MQTTMessage<S>> consumer) {
      this.subAckFuture = subAckFuture;
      this.qos = qos;
      this.consumers.add(consumer);
    }

    /** Add a consumer for this topic */
    void addConsumer(Consumer<MQTTMessage<S>> consumer) {
      synchronized (this) {
        consumers.add(consumer);
      }
    }

    /**
     * Remove a consumer
     *
     * @return true if no consumers remaining
     */
    boolean removeConsumer(Consumer<MQTTMessage<S>> consumer) {
      synchronized (this) {
        return consumers.remove(consumer) && consumers.isEmpty();
      }
    }

    void consume(MQTTMessage<S> message) {
      synchronized (this) {
        consumers.forEach(consumer -> consumer.accept(message));
      }
    }

    boolean isSubFailed() {
      return !subAckFuture.getNow(false);
    }

    boolean isSubDone() {
      return subAckFuture.isDone();
    }

    void updateSubAck(@NotNull CompletableFuture<Boolean> subAckFuture) {
      this.subAckFuture = subAckFuture;
    }
  }

  public static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, AbstractMQTT_IOClient.class);
  public static long RECONNECT_DELAY_INITIAL_MILLIS = 1000L;
  public static long RECONNECT_DELAY_MAX_MILLIS = 5 * 60000L;
  protected CompletableFuture<Void> connectRetry;
  protected int connectTimeout = 5000;
  protected final AtomicReference<ConnectionStatus> connectionStatus =
      new AtomicReference<>(ConnectionStatus.DISCONNECTED);
  protected String clientId;
  protected String host;
  protected int port;
  protected boolean secure;
  protected boolean cleanSession;
  protected boolean resubscribeIfSessionPresent;
  protected boolean removeConsumersOnSubscriptionFailure;
  protected boolean retrySubscriptionFailuresOnReconnect = true;
  protected UsernamePassword usernamePassword;
  protected URI websocketURI;
  protected Mqtt3AsyncClient client;
  protected final Set<Consumer<ConnectionStatus>> connectionStatusConsumers =
      new CopyOnWriteArraySet<>();
  protected final Map<String, TopicSubscriptionInfo<S>> topicConsumerMap = new HashMap<>();
  protected Consumer<String> topicSubscribeFailureConsumer;
  protected MqttQos publishQos = MqttQos.AT_MOST_ONCE;
  protected MqttQos subscribeQos = MqttQos.AT_MOST_ONCE;
  protected String clientUri;

  protected AbstractMQTT_IOClient(
      String clientId,
      String host,
      int port,
      boolean secure,
      boolean cleanSession,
      UsernamePassword usernamePassword,
      URI websocketURI,
      MQTTLastWill lastWill,
      KeyManagerFactory keyManagerFactory,
      TrustManagerFactory trustManagerFactory) {
    this.clientId = clientId;
    this.host = host;
    this.port = port;
    this.secure = secure;
    this.cleanSession = cleanSession;
    this.usernamePassword = usernamePassword;
    this.websocketURI = websocketURI;

    if (websocketURI != null) {
      clientUri = "mqtt_" + websocketURI + "?clientId=" + clientId;
    } else {
      clientUri = "mqtt" + (secure ? "s://" : "://") + host + ":" + port + "/?clientId=" + clientId;
    }

    Mqtt3ClientBuilder builder =
        MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId)
            .addDisconnectedListener(
                context -> {
                  // Atomically check if CONNECTED, and if so, swap to CONNECTING and return true
                  boolean reconnect =
                      checkSetConnectionStatus(
                          ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING);

                  if (reconnect) {
                    if (context.getCause() instanceof Mqtt3DisconnectException) {
                      LOG.info(
                          "Connection error uri="
                              + getClientUri()
                              + ", initiator="
                              + context.getSource());
                    } else if (context.getCause() instanceof Mqtt3ConnAckException) {
                      LOG.info(
                          "Connection rejected uri="
                              + getClientUri()
                              + ", reasonCode="
                              + ((Mqtt3ConnAckException) context.getCause())
                                  .getMqttMessage()
                                  .getReturnCode());
                    } else if (context.getCause() instanceof ConnectionClosedException) {
                      LOG.info(
                          "Connection closed uri="
                              + getClientUri()
                              + ", initiator="
                              + context.getSource());
                    } else if (context.getCause() instanceof ConnectionFailedException) {
                      LOG.info(
                          "Connection failed uri="
                              + getClientUri()
                              + ", initiator="
                              + context.getSource()
                              + ", message="
                              + context.getCause().getMessage());
                    }
                    doReconnect();
                  }
                });

    if (secure) {
      MqttClientSslConfigBuilder sslBuilder = MqttClientSslConfig.builder();
      if (keyManagerFactory != null) {
        sslBuilder = sslBuilder.keyManagerFactory(keyManagerFactory);
      }
      if (trustManagerFactory != null) {
        sslBuilder = sslBuilder.trustManagerFactory(trustManagerFactory);
      }
      builder = builder.sslConfig(sslBuilder.build());
    }

    if (websocketURI != null) {
      builder =
          builder
              .serverHost(websocketURI.getHost())
              .serverPort(websocketURI.getPort())
              .webSocketConfig()
              .serverPath(websocketURI.getPath())
              .queryString(websocketURI.getQuery())
              .applyWebSocketConfig();
    } else {
      builder = builder.serverHost(host).serverPort(port);
    }

    if (lastWill != null) {
      builder
          .willPublish()
          .topic(lastWill.getTopic())
          .payload(ValueUtil.getStringCoerced(lastWill.getPayload()).orElse("").getBytes())
          .retain(lastWill.isRetain())
          .applyWillPublish();
    }

    try {
      client = builder.buildAsync();
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Invalid config uri=" + getClientUri(), e);
      client = null;
      connectionStatus.set(ConnectionStatus.ERROR);
    }
  }

  public AbstractMQTT_IOClient<S> setPublishQos(MqttQos publishQos) {
    this.publishQos = publishQos;
    return this;
  }

  public AbstractMQTT_IOClient<S> setSubscribeQos(MqttQos subscribeQos) {
    this.subscribeQos = subscribeQos;
    return this;
  }

  public boolean isResubscribeIfSessionPresent() {
    return resubscribeIfSessionPresent;
  }

  public AbstractMQTT_IOClient<S> setResubscribeIfSessionPresent(
      boolean resubscribeIfSessionPresent) {
    this.resubscribeIfSessionPresent = resubscribeIfSessionPresent;
    return this;
  }

  public boolean isRemoveConsumersOnSubscriptionFailure() {
    return removeConsumersOnSubscriptionFailure;
  }

  public AbstractMQTT_IOClient<S> setRemoveConsumersOnSubscriptionFailure(
      boolean removeConsumersOnSubscriptionFailure) {
    this.removeConsumersOnSubscriptionFailure = removeConsumersOnSubscriptionFailure;
    return this;
  }

  public boolean isRetrySubscriptionFailuresOnReconnect() {
    return retrySubscriptionFailuresOnReconnect;
  }

  public AbstractMQTT_IOClient<S> setRetrySubscriptionFailuresOnReconnect(
      boolean retrySubscriptionFailuresOnReconnect) {
    this.retrySubscriptionFailuresOnReconnect = retrySubscriptionFailuresOnReconnect;
    return this;
  }

  @Override
  public void sendMessage(MQTTMessage<S> message) {
    if (client == null) {
      LOG.info("Cannot send message as client is not valid, uri=" + getClientUri());
      return;
    }

    client
        .publishWith()
        .topic(message.getTopic())
        .payload(messageToBytes(message.getPayload()))
        .qos(
            Optional.ofNullable(message.getQos())
                .map(qos -> qos > 2 || qos < 0 ? null : qos)
                .map(MqttQos::fromCode)
                .orElse(publishQos))
        .send()
        .orTimeout(5000, TimeUnit.MILLISECONDS)
        .whenComplete(
            (publish, throwable) -> {
              if (throwable != null) {
                // Failure
                LOG.log(
                    Level.INFO,
                    "Failed to publish uri=" + getClientUri() + ", topic=" + message.getTopic(),
                    throwable);
              } else {
                // Success
                LOG.finest("Published message to MQTT broker '" + getClientUri() + "'");
              }
            });
  }

  @Override
  public void addMessageConsumer(Consumer<MQTTMessage<S>> messageConsumer) {
    addMessageConsumer("#", null, messageConsumer);
  }

  public void addMessageConsumer(String topic, Consumer<MQTTMessage<S>> messageConsumer) {
    addMessageConsumer(topic, subscribeQos, messageConsumer);
  }

  public void addMessageConsumer(
      String topic, MqttQos qos, Consumer<MQTTMessage<S>> messageConsumer) {
    if (client == null) {
      return;
    }

    LOG.fine("Adding message consumer uri=" + getClientUri() + ", topic=" + topic);

    synchronized (topicConsumerMap) {
      topicConsumerMap.compute(
          topic,
          (t, subInfo) -> {
            if (subInfo == null) {
              subInfo = new TopicSubscriptionInfo<>(doSubscribe(qos, topic), qos, messageConsumer);
            } else {
              subInfo.addConsumer(messageConsumer);
            }
            return subInfo;
          });
    }
  }

  public void setTopicSubscribeFailureConsumer(Consumer<String> topicSubscribeFailureConsumer) {
    this.topicSubscribeFailureConsumer = topicSubscribeFailureConsumer;
  }

  protected void onSubscribeFailed(String topic) {
    if (removeConsumersOnSubscriptionFailure) {
      LOG.fine("Removing subscription consumers uri=" + getClientUri() + ", topic=" + topic);
      synchronized (topicConsumerMap) {
        this.topicConsumerMap.remove(topic);
      }
    }
    if (this.topicSubscribeFailureConsumer != null) {
      this.topicSubscribeFailureConsumer.accept(topic);
    }
  }

  protected CompletableFuture<Boolean> doSubscribe(MqttQos qos, String topic) {
    CompletableFuture<Mqtt3SubAck> subAckFuture =
        client
            .subscribeWith()
            .topicFilter(topic)
            .qos(qos)
            .callback(
                publish -> {
                  try {
                    TopicSubscriptionInfo<S> subscriptionInfo = topicConsumerMap.get(topic);
                    if (subscriptionInfo != null) {
                      String topicStr = publish.getTopic().toString();
                      S payload = messageFromBytes(publish.getPayloadAsBytes());
                      MQTTMessage<S> message = new MQTTMessage<>(topicStr, payload);
                      subscriptionInfo.consume(message);
                    }
                  } catch (Exception e) {
                    LOG.log(
                        Level.WARNING,
                        "Failed to process incoming message uri="
                            + getClientUri()
                            + ", topic="
                            + topic,
                        e);
                  }
                })
            .executor(Container.EXECUTOR)
            .send();

    return subAckFuture.handle(
        (subAck, throwable) -> {
          if (throwable != null) {
            LOG.log(
                Level.SEVERE,
                "Subscribe failed uri="
                    + getClientUri()
                    + ", topic="
                    + topic
                    + ", error="
                    + throwable.getMessage());
            Container.EXECUTOR.execute(() -> onSubscribeFailed(topic));
            if (throwable instanceof CancellationException) {
              subAckFuture.cancel(true);
            }
            return false;
          }
          if (subAck.getReturnCodes().contains(Mqtt3SubAckReturnCode.FAILURE)) {
            LOG.log(
                Level.WARNING,
                "Subscribe rejected by server uri=" + getClientUri() + ", topic=" + topic);
            Container.EXECUTOR.execute(() -> onSubscribeFailed(topic));
            return false;
          }
          LOG.log(Level.FINE, "Subscribe success uri=" + getClientUri() + ", topic=" + topic);
          return true;
        });
  }

  protected void doUnsubscribe(String topic) {
    LOG.finest("Unsubscribing uri=" + getClientUri() + ", topic=" + topic);
    client
        .unsubscribeWith()
        .topicFilter(topic)
        .send()
        .whenComplete(
            (subAck, throwable) -> {
              if (throwable != null) {
                LOG.log(
                    Level.WARNING,
                    "Unsubscribe failed uri=" + getClientUri() + ", topic=" + topic,
                    throwable);
              } else {
                LOG.fine("Unsubscribe success uri=" + getClientUri() + ", topic=" + topic);
              }
            });
  }

  @Override
  public void removeMessageConsumer(Consumer<MQTTMessage<S>> messageConsumer) {
    removeMessageConsumer("#", messageConsumer);
  }

  public void removeMessageConsumer(String topic, Consumer<MQTTMessage<S>> messageConsumer) {
    synchronized (topicConsumerMap) {
      topicConsumerMap.computeIfPresent(
          topic,
          (t, subscriptionInfo) -> {
            if (subscriptionInfo.removeConsumer(messageConsumer)) {
              doUnsubscribe(topic);
              return null;
            }
            return subscriptionInfo;
          });
    }
  }

  public void removeMessageConsumers(String topic) {
    synchronized (topicConsumerMap) {
      Optional.ofNullable(topicConsumerMap.remove(topic))
          .ifPresent(
              topicConsumer -> {
                doUnsubscribe(topic);
              });
    }
  }

  @Override
  public void removeAllMessageConsumers() {
    Set<String> topics = new HashSet<>(topicConsumerMap.keySet());
    topicConsumerMap.clear();
    topics.forEach(this::doUnsubscribe);
  }

  @Override
  public void addConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
    connectionStatusConsumers.add(connectionStatusConsumer);
  }

  @Override
  public void removeConnectionStatusConsumer(Consumer<ConnectionStatus> connectionStatusConsumer) {
    connectionStatusConsumers.remove(connectionStatusConsumer);
  }

  @Override
  public void removeAllConnectionStatusConsumers() {
    connectionStatusConsumers.clear();
  }

  @Override
  public ConnectionStatus getConnectionStatus() {
    return connectionStatus.get();
  }

  @Override
  public void connect() {
    if (checkSetConnectionStatus(ConnectionStatus.DISCONNECTED, ConnectionStatus.CONNECTING)) {
      LOG.fine("Connecting client: " + getClientUri());
      scheduleDoConnect(100);
    } else {
      LOG.finest("Must be disconnected before calling connect: " + getClientUri());
    }
  }

  protected void waitForConnectFuture(Future<Void> connectFuture) throws Exception {
    connectFuture.get(getConnectTimeoutMillis() + 1000L, TimeUnit.MILLISECONDS);
  }

  public int getConnectTimeoutMillis() {
    return this.connectTimeout;
  }

  protected void doReconnect() {
    doDisconnect()
        .whenComplete(
            (unused, throwable) -> {
              ConnectionStatus currentStatus = connectionStatus.get();

              // Guard against explicit disconnects happening while we were cleaning up
              if (currentStatus == ConnectionStatus.DISCONNECTING
                  || currentStatus == ConnectionStatus.DISCONNECTED) {
                LOG.fine(
                    "Explicit disconnect detected, aborting delayed reconnect: " + getClientUri());
                return;
              }

              scheduleDoConnect(5000);
            });
  }

  protected Future<Void> doConnect() {

    // 1. Guard against stale execution
    ConnectionStatus current = connectionStatus.get();
    if (current == ConnectionStatus.DISCONNECTING || current == ConnectionStatus.DISCONNECTED) {
      LOG.fine("Aborting stale connection task, explicit disconnect detected: " + getClientUri());
      CompletableFuture<Void> aborted = new CompletableFuture<>();
      aborted.cancel(true);
      return aborted;
    }

    LOG.info("Establishing connection: " + getClientUri());

    // 2. Safely restore CONNECTING only if we were WAITING (due to retry backoff)
    // If it was already CONNECTING, this safely ignores the update.
    checkSetConnectionStatus(ConnectionStatus.WAITING, ConnectionStatus.CONNECTING);

    Mqtt3ConnectBuilder.Send<CompletableFuture<Mqtt3ConnAck>> completableFutureSend =
        client.connectWith().cleanSession(cleanSession).keepAlive(10);

    if (usernamePassword != null) {
      completableFutureSend =
          completableFutureSend
              .simpleAuth()
              .username(usernamePassword.getUsername())
              .password(usernamePassword.getPassword().getBytes())
              .applySimpleAuth();
    }

    CompletableFuture<Mqtt3ConnAck> connAckFuture = completableFutureSend.send();

    connAckFuture.whenComplete(
        (connAck, throwable) -> {
          if (connAck != null) {
            LOG.fine(
                "Connected code="
                    + connAck.getReturnCode()
                    + ", sessionPresent="
                    + connAck.isSessionPresent());
            boolean retryFailures = isRetrySubscriptionFailuresOnReconnect();
            boolean retryAll = !connAck.isSessionPresent() || resubscribeIfSessionPresent;

            if (retryAll || retryFailures) {
              Container.EXECUTOR.execute(
                  () -> {
                    // Get all failed or completed subscriptions and retry as needed
                    synchronized (topicConsumerMap) {
                      topicConsumerMap.entrySet().stream()
                          .filter(
                              topicSubscriptionInfo ->
                                  topicSubscriptionInfo.getValue().isSubDone()
                                      && (retryAll
                                          || topicSubscriptionInfo.getValue().isSubFailed()))
                          .forEach(
                              topicSubscriptionInfo -> {
                                LOG.finest(
                                    "Resubscribing uri="
                                        + getClientUri()
                                        + ", topic="
                                        + topicSubscriptionInfo.getKey());
                                topicSubscriptionInfo
                                    .getValue()
                                    .updateSubAck(
                                        doSubscribe(
                                            topicSubscriptionInfo.getValue().qos,
                                            topicSubscriptionInfo.getKey()));
                              });
                    }
                  });
            }
          } else if (throwable != null) {
            if (getConnectionStatus() != ConnectionStatus.DISCONNECTED) {
              if (throwable instanceof CancellationException) {
                LOG.info("Connection canceled uri=" + getClientUri());
              } else {
                LOG.info(
                    "Connection failed uri="
                        + getClientUri()
                        + ", error="
                        + throwable.getMessage());
              }
            }
          }
        });

    CompletableFuture<Void> voidFuture = connAckFuture.thenAccept(result -> {});

    // Propagate cancellation from voidFuture to original
    voidFuture.whenComplete(
        (result, exception) -> {
          if (voidFuture.isCancelled()) {
            connAckFuture.cancel(true);
          }
        });
    return voidFuture;
  }

  protected void setConnectionStatus(ConnectionStatus newStatus) {
    ConnectionStatus previousStatus = connectionStatus.getAndSet(newStatus);

    if (previousStatus != newStatus) {
      // State changed
      LOG.finest("Connection status changed notifying consumers: " + connectionStatus);
      notifyConnectionStatusConsumers(newStatus);
    }
  }

  protected boolean checkSetConnectionStatus(
      ConnectionStatus expected, ConnectionStatus newStatus) {
    // Atomically checks if the status is 'expected'. If so, sets it to 'newStatus' and returns
    // true.
    if (connectionStatus.compareAndSet(expected, newStatus)) {
      notifyConnectionStatusConsumers(newStatus);
      return true;
    }
    return false; // The state was not 'expected', so nothing changed
  }

  protected void notifyConnectionStatusConsumers(ConnectionStatus newStatus) {
    if (connectionStatusConsumers.isEmpty()) {
      return;
    }

    LOG.finest("Connection status changed notifying consumers: " + newStatus);
    connectionStatusConsumers.forEach(
        consumer -> {
          try {
            consumer.accept(newStatus);
          } catch (Exception e) {
            LOG.log(
                Level.WARNING,
                "Connection status change handler threw an exception: " + getClientUri(),
                e);
          }
        });
  }

  @Override
  public void disconnect() {
    if (client == null) {
      return;
    }

    while (true) {
      ConnectionStatus current = connectionStatus.get();
      if (current == ConnectionStatus.DISCONNECTED || current == ConnectionStatus.DISCONNECTING) {
        LOG.finest("Already disconnected or disconnecting: " + getClientUri());
        return;
      }

      // Try to atomically swap whatever the current state is to DISCONNECTING
      if (checkSetConnectionStatus(current, ConnectionStatus.DISCONNECTING)) {
        LOG.fine("Disconnecting: " + getClientUri());

        try {
          if (connectRetry != null) {
            connectRetry.cancel(true);
            connectRetry = null;
          }
        } catch (Exception ignored) {
        }

        doDisconnect()
            .whenComplete(
                (unused, throwable) -> setConnectionStatus(ConnectionStatus.DISCONNECTED));

        return; // Exit the loop on success
      }
      // If checkSetConnectionStatus returns false, another thread changed the state.
      // The loop repeats and tries again with the fresh state.
    }
  }

  protected CompletableFuture<Void> doDisconnect() {
    LOG.finest("Performing disconnect: " + getClientUri());
    return client
        .disconnect()
        .whenComplete(
            (unused, throwable) -> {
              if (throwable != null && !(throwable instanceof MqttClientStateException)) {
                LOG.log(
                    Level.WARNING, "Failed to disconnect gracefully: " + getClientUri(), throwable);
              } else {
                LOG.finest("Disconnect done: " + getClientUri());
              }
            });
  }

  @Override
  public String getClientUri() {
    return clientUri;
  }

  public abstract byte[] messageToBytes(S message);

  public abstract S messageFromBytes(byte[] bytes);

  protected void scheduleDoConnect(long initialDelay) {
    long delay = Math.max(initialDelay, RECONNECT_DELAY_INITIAL_MILLIS);
    long maxDelay = Math.max(delay + 1, RECONNECT_DELAY_MAX_MILLIS);

    RetryPolicy<Object> retryPolicy =
        RetryPolicy.builder()
            .withJitter(0.25)
            .withBackoff(Duration.ofMillis(delay), Duration.ofMillis(maxDelay))
            .handle(Exception.class)
            .onRetryScheduled(
                (execution) -> {
                  LOG.info(
                      "Re-connection scheduled in '"
                          + execution.getDelay()
                          + "' for: "
                          + getClientUri());
                  setConnectionStatus(ConnectionStatus.WAITING);
                })
            .onFailedAttempt(
                (execution) -> {
                  LOG.info(
                      "Connection attempt failed '"
                          + execution.getAttemptCount()
                          + "' for: "
                          + getClientUri()
                          + ", error="
                          + (execution.getLastException() != null
                              ? execution.getLastException().getMessage()
                              : null));
                  doDisconnect();
                })
            .withMaxRetries(Integer.MAX_VALUE)
            .build();

    connectRetry =
        Failsafe.with(retryPolicy)
            .with(Container.EXECUTOR)
            .runAsyncExecution(
                (execution) -> {
                  LOG.fine(
                      "Connection attempt '"
                          + (execution.getAttemptCount() + 1)
                          + "' for: "
                          + getClientUri());
                  // Connection future should timeout so we just wait for it but add additional
                  // timeout just in case
                  Future<Void> connectFuture = doConnect();
                  try {
                    waitForConnectFuture(connectFuture);
                    execution.recordResult(null);
                  } catch (Exception e) {
                    // CRITICAL: Abort the underlying HiveMQ connection attempt
                    connectFuture.cancel(true);
                    throw e;
                  }
                });

    connectRetry.whenComplete(
        (result, ex) -> {
          if (ex == null) {
            if (checkSetConnectionStatus(ConnectionStatus.CONNECTING, ConnectionStatus.CONNECTED)) {
              LOG.fine("Connection attempt success: " + getClientUri());
            }
          }
        });
  }
}
