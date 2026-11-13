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

import static org.openremote.manager.asset.AssetProcessingService.ATTRIBUTE_EVENT_PROCESSOR;
import static org.openremote.manager.mqtt.MQTTBrokerService.connectionToString;
import static org.openremote.manager.mqtt.MQTTBrokerService.getConnectionIDString;
import static org.openremote.model.syslog.SyslogCategory.API;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.mqtt.MqttQoS;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.utils.collections.ConcurrentHashSet;
import org.openremote.container.security.AuthContext;
import org.openremote.manager.event.ClientEventService;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetEvent;
import org.openremote.model.asset.AssetFilter;
import org.openremote.model.asset.UserAssetLink;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.Event;
import org.openremote.model.event.shared.EventSubscription;
import org.openremote.model.protocol.mqtt.Topic;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.ValueUtil;

/**
 * This handler uses the {@link ClientEventService} to publish and subscribe to asset and attribute
 * events; converting subscription topics into {@link AssetFilter}s to ensure only the correct
 * events are returned for the subscription.
 */
public class DefaultMQTTHandler extends MQTTHandler {

  public static final int PRIORITY = Integer.MIN_VALUE + 1000;
  public static final String ASSET_TOPIC = "asset";
  public static final String ATTRIBUTE_TOPIC = "attribute";
  public static final String ATTRIBUTE_VALUE_TOPIC = "attributevalue";
  public static final String ATTRIBUTE_VALUE_WRITE_TOPIC = "writeattributevalue";
  public static final String ATTRIBUTE_WRITE_TOPIC = "writeattribute";
  private static final Logger LOG = SyslogCategory.getLogger(API, DefaultMQTTHandler.class);
  protected final Map<String, Map<String, Consumer<? extends Event>>> sessionSubscriptionConsumers =
      new ConcurrentHashMap<>();
  // An authorization cache for publishing
  // TODO: Switch to caffeine library once ActiveMQ has migrated
  protected final Cache<String, ConcurrentHashSet<String>> authorizationCache =
      CacheBuilder.newBuilder()
          .maximumSize(100000)
          .expireAfterWrite(Duration.ofMillis(300000))
          .build();

  // Intermediary cache for authorized event subscriptions created during the canSubscribe method to
  // be used later in the onSubscribe method
  protected final Cache<String, EventSubscription<?>> eventSubscriptionCache =
      CacheBuilder.newBuilder().expireAfterWrite(Duration.ofMillis(30000)).build();

  @Override
  public int getPriority() {
    // This handler is intended to be the final handler but this can obviously be overridden by
    // another handler
    return PRIORITY;
  }

  @Override
  public void onConnect(RemotingConnection connection) {
    super.onConnect(connection);
  }

  @Override
  public void onDisconnect(RemotingConnection connection) {
    super.onDisconnect(connection);
    String sessionKey = getSessionKey(connection);
    LOG.log(
        Level.FINER, "Removing subscriptions for connection: " + connectionToString(connection));
    sessionSubscriptionConsumers.computeIfPresent(
        sessionKey,
        (s, subscriptionConsumers) -> {
          subscriptionConsumers.forEach(
              (subscriptionKey, consumer) -> clientEventService.removeSubscription(consumer));
          return null;
        });
    authorizationCache.invalidate(getConnectionIDString(connection));
  }

  @Override
  public void onConnectionLost(RemotingConnection connection) {
    super.onConnectionLost(connection);
    String sessionKey = getSessionKey(connection);
    LOG.log(
        Level.FINER, "Removing subscriptions for connection: " + connectionToString(connection));
    sessionSubscriptionConsumers.computeIfPresent(
        sessionKey,
        (s, subscriptionConsumers) -> {
          subscriptionConsumers.forEach(
              (subscriptionKey, consumer) -> clientEventService.removeSubscription(consumer));
          return null;
        });
    authorizationCache.invalidate(getConnectionIDString(connection));
  }

  @Override
  public boolean topicMatches(Topic topic) {
    return isAttributeTopic(topic)
        || isAssetTopic(topic)
        || isAttributeValueWriteTopic(topic)
        || isAttributeWriteTopic(topic);
  }

  @Override
  protected Logger getLogger() {
    return LOG;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public boolean canSubscribe(RemotingConnection connection, AuthContext authContext, Topic topic) {

    if (!isKeycloak) {
      LOG.finest("Identity provider is not keycloak");
      return false;
    }

    if (authContext == null) {
      LOG.finest(
          "Anonymous connection not supported: topic="
              + topic
              + ", "
              + connectionToString(connection));
      return false;
    }

    boolean isAttributeTopic = isAttributeTopic(topic);
    boolean isAssetTopic = isAssetTopic(topic);

    if (!isAssetTopic && !isAttributeTopic) {
      LOG.finest(
          "Topic must have 3 or more tokens and third token must be 'asset, attribute or attributevalue': topic="
              + topic
              + ", "
              + mqttBrokerService.connectionToString(connection));
      return false;
    }

    if (isAssetTopic) {
      if (topic.getTokens().length < 4 || topic.getTokens().length > 5) {
        LOG.finest(
            "Asset subscribe token count should be 4 or 5: topic="
                + topic
                + ", "
                + connectionToString(connection));
        return false;
      }
      if (topic.getTokens().length == 4) {
        if (!Asset.matchesAssetIdPattern(topicTokenIndexToString(topic, 3))
            && !TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3))
            && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3))) {
          LOG.fine(
              "Asset subscribe forth token must be an asset ID or wildcard: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
      } else if (topic.getTokens().length == 5) {
        if (!Asset.matchesAssetIdPattern(topicTokenIndexToString(topic, 3))) {
          LOG.fine(
              "Asset subscribe forth token must be an asset ID: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
        if (!TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))
            && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))) {
          LOG.fine(
              "Asset subscribe fifth token must be a wildcard: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
      }
    } else {
      // Attribute topic
      if (topic.getTokens().length < 5 || topic.getTokens().length > 6) {
        LOG.fine(
            "Attribute subscribe token count should be 5 or 6: topic="
                + topic
                + ", "
                + mqttBrokerService.connectionToString(connection));
        return false;
      }
      if (topic.getTokens().length == 5) {
        if (TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3))) {
          LOG.fine(
              "Attribute subscribe multi level wildcard must be last token: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
        if (!Asset.matchesAssetIdPattern(topicTokenIndexToString(topic, 4))
            && !TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))
            && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 4))) {
          LOG.fine(
              "Attribute subscribe fifth token must be an asset ID or a wildcard: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
      } else if (topic.getTokens().length == 6) {
        if (!Asset.matchesAssetIdPattern(topicTokenIndexToString(topic, 4))) {
          LOG.fine(
              "Attribute subscribe fifth token must be an asset ID: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
        if (!TOKEN_MULTI_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 5))
            && !TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 5))) {
          LOG.fine(
              "Attribute subscribe sixth token must be a wildcard: topic="
                  + topic
                  + ", "
                  + mqttBrokerService.connectionToString(connection));
          return false;
        }
      }
    }

    // Build filter for the topic and verify that the filter is OK for given auth context
    AssetFilter<?> filter = buildAssetFilter(topic);

    if (filter == null) {
      LOG.info(
          "Failed to process subscription topic: topic="
              + topic
              + ", "
              + mqttBrokerService.connectionToString(connection));
      return false;
    }

    EventSubscription<?> subscription =
        new EventSubscription(isAssetTopic ? AssetEvent.class : AttributeEvent.class, filter);

    if (!clientEventService.authorizeEventSubscription(
        topicRealm(topic), authContext, subscription)) {
      return false;
    }

    String subscriptionId = topic + authContext.getUserId();

    // Add the event subscription to the intermediary cache, override any existing entry
    eventSubscriptionCache.put(subscriptionId, subscription);

    return true;
  }

  // We make heavy use of authorization caching as clients can hit this a lot and it is currently
  // quite slow with DB calls
  @Override
  public boolean canPublish(RemotingConnection connection, AuthContext authContext, Topic topic) {

    if (!isKeycloak) {
      LOG.fine("Identity provider is not keycloak");
      return false;
    }

    if (authContext == null) {
      LOG.finer(
          "Anonymous publish not supported: topic="
              + topic
              + ", connection="
              + connectionToString(connection));
      return false;
    }

    if (isAttributeValueWriteTopic(topic) || isAttributeWriteTopic(topic)) {
      if (topic.getTokens().length != 5
          || !Asset.matchesAssetIdPattern(topicTokenIndexToString(topic, 4))) {
        LOG.finer(
            "Invalid publish topic: topic="
                + topic
                + ", connection="
                + connectionToString(connection));
        return false;
      }
    } else {
      return false;
    }

    String cacheKey = getConnectionIDString(connection);

    // Check cache
    ConcurrentHashSet<String> act = authorizationCache.getIfPresent(cacheKey);
    if (act != null && act.contains(topic.toString())) {
      return true;
    }

    // We don't know the value at this point so just use a null value for authorization (value type
    // will be handled
    // when the event is processed)
    if (!clientEventService.authorizeEventWrite(
        topicRealm(topic), authContext, buildAttributeEvent(topic.getTokens(), null, null))) {
      LOG.fine(
          "Publish was not authorized for this user and topic: topic="
              + topic
              + ", subject="
              + authContext);
      return false;
    }

    // Add to cache
    ConcurrentHashSet<String> set;
    synchronized (authorizationCache) {
      act = authorizationCache.getIfPresent(cacheKey);
      if (act != null) {
        set = act;
      } else {
        set = new ConcurrentHashSet<>();
        authorizationCache.put(cacheKey, set);
      }
    }
    set.add(topic.toString());

    return true;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Override
  public void onSubscribe(RemotingConnection connection, Topic topic) {

    AuthContext authContext = getAuthContextFromConnection(connection).get();
    String subscriptionId = topic + authContext.getUserId();

    // Get the authorized event subscription from the intermediary cache
    EventSubscription<?> subscription = eventSubscriptionCache.getIfPresent(subscriptionId);
    if (subscription == null) {
      LOG.info("Subscription not found in intermediary cache: " + subscriptionId);
      return;
    }
    // Evict the subscription from the cache
    eventSubscriptionCache.invalidate(subscriptionId);

    String sessionKey = getSessionKey(connection);

    Consumer<Event> consumer = getSubscriptionEventConsumer(connection, topic);

    // Create subscription consumer and track it for future removal requests
    Map<String, Consumer<? extends Event>> subscriptionConsumers =
        sessionSubscriptionConsumers.computeIfAbsent(sessionKey, (s) -> new ConcurrentHashMap<>());
    subscriptionConsumers.put(topic.toString(), consumer);
    clientEventService.addSubscription(subscription, consumer);
    LOG.finest(
        () ->
            "Client event subscription created for topic '"
                + topic
                + "': "
                + connectionToString(connection));
  }

  @Override
  public void onUnsubscribe(RemotingConnection connection, Topic topic) {
    String subscriptionId = topic.toString();
    String sessionKey = getSessionKey(connection);

    sessionSubscriptionConsumers.computeIfPresent(
        sessionKey,
        (connectionID, subscriptionConsumers) -> {
          Consumer<? extends Event> consumer = subscriptionConsumers.remove(subscriptionId);
          if (consumer != null) {
            clientEventService.removeSubscription(consumer);
          }
          if (subscriptionConsumers.isEmpty()) {
            return null;
          }
          return subscriptionConsumers;
        });
  }

  @Override
  public Set<String> getPublishListenerTopics() {
    return Set.of(
        TOKEN_SINGLE_LEVEL_WILDCARD
            + "/"
            + TOKEN_SINGLE_LEVEL_WILDCARD
            + "/"
            + ATTRIBUTE_VALUE_WRITE_TOPIC
            + "/"
            + TOKEN_MULTI_LEVEL_WILDCARD,
        TOKEN_SINGLE_LEVEL_WILDCARD
            + "/"
            + TOKEN_SINGLE_LEVEL_WILDCARD
            + "/"
            + ATTRIBUTE_WRITE_TOPIC
            + "/"
            + TOKEN_MULTI_LEVEL_WILDCARD);
  }

  @Override
  public void onPublish(RemotingConnection connection, Topic topic, ByteBuf body) {
    String[] topicTokens = topic.getTokens();
    String payloadContent = body.toString(StandardCharsets.UTF_8);
    AttributeEvent attributeEvent;

    if (isAttributeWriteTopic(topic)) {
      attributeEvent =
          ValueUtil.parse(payloadContent, ObjectNode.class)
              .map(
                  valueWithTimestamp -> {
                    if (valueWithTimestamp.has("value") && valueWithTimestamp.has("timestamp")) {
                      Object value = valueWithTimestamp.get("value");
                      long timestamp = valueWithTimestamp.get("timestamp").asLong();
                      if (timestamp > 0L) {
                        return buildAttributeEvent(topicTokens, value, timestamp);
                      }
                    }
                    return null;
                  })
              .orElse(null);
      if (attributeEvent == null) {
        LOG.info(
            () ->
                "Invalid publish to write attribute topic '"
                    + topic
                    + "': "
                    + connectionToString(connection));
      }
    } else {
      Object value = ValueUtil.parse(payloadContent).orElse(null);
      attributeEvent = buildAttributeEvent(topicTokens, value, timerService.getCurrentTimeMillis());
    }

    if (attributeEvent != null) {
      // This is called by a single ActiveMQ client thread (the session) and async offloaded to the
      // container executor,
      // once the container executor has no free threads the caller will execute (i.e. the client
      // thread) which will
      // effectively limit rate of publish consumption eventually filling the attribute queue in the
      // broker and
      // preventing additional attribute events from being added to the queue. This gives us a
      // consistent failure mode
      // and natural rate limiting.
      messageBrokerService
          .getFluentProducerTemplate()
          .withBody(attributeEvent)
          .to(ATTRIBUTE_EVENT_PROCESSOR)
          .asyncSend();
    }
  }

  @Override
  public void onUserAssetLinksChanged(
      RemotingConnection connection, List<PersistenceEvent<UserAssetLink>> changes) {
    String sessionKey = getSessionKey(connection);
    if (sessionSubscriptionConsumers.containsKey(sessionKey)) {
      LOG.info(
          "User asset links have changed for a connected user with active subscriptions so force disconnecting them: "
              + connectionToString(connection));
      mqttBrokerService.doForceDisconnect(connection);
    }
  }

  protected static AttributeEvent buildAttributeEvent(
      String[] topicTokens, Object value, Long timestamp) {
    String attributeName = topicTokens[3];
    String assetId = topicTokens[4];
    return new AttributeEvent(assetId, attributeName, value, timestamp)
        .setSource(DefaultMQTTHandler.class.getSimpleName());
  }

  protected static AssetFilter<?> buildAssetFilter(Topic topic) {
    boolean isAssetTopic = isAssetTopic(topic);

    String realm = topicRealm(topic);
    List<String> assetIds = new ArrayList<>();
    List<String> parentIds = new ArrayList<>();
    List<String> paths = new ArrayList<>();
    List<String> attributeNames = new ArrayList<>();
    String firstTokenStr = topicTokenIndexToString(topic, 3);

    if (isAssetTopic) {
      if (topic.getTokens().length == 4) {
        if (TOKEN_MULTI_LEVEL_WILDCARD.equals(firstTokenStr)) {
          // realm/clientId/asset/#
          // No filtering required
        } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(firstTokenStr)) {
          // realm/clientId/asset/+
          parentIds.add(null);
        } else {
          // realm/clientId/asset/{assetId}
          assetIds.add(firstTokenStr);
        }
      } else if (topic.getTokens().length == 5) {
        String secondTokenStr = topicTokenIndexToString(topic, 4);

        if (TOKEN_MULTI_LEVEL_WILDCARD.equals(secondTokenStr)) {
          // realm/clientId/asset/assetId/#
          paths.add(firstTokenStr);
        } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(secondTokenStr)) {
          // realm/clientId/asset/assetId/+
          parentIds.add(firstTokenStr);
        }
      } else {
        return null;
      }
    } else {
      if (!TOKEN_SINGLE_LEVEL_WILDCARD.equals(firstTokenStr)) {
        attributeNames.add(firstTokenStr);
      }
      if (topic.getTokens().length == 5) {
        String secondTokenStr = topicTokenIndexToString(topic, 4);
        // realm/clientId/attribute/{attributeName|+}/{assetId|+|*}
        if (TOKEN_MULTI_LEVEL_WILDCARD.equals(secondTokenStr)) {
          // realm/clientId/attribute/+/#
          // No filtering required
        } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(secondTokenStr)) {
          // realm/clientId/attribute/+/+
          parentIds.add(null);
        } else {
          // realm/clientId/attribute/+/{assetId}
          assetIds.add(secondTokenStr);
        }
      } else if (topic.getTokens().length == 6) {
        // realm/clientId/attribute/{attributeName|+}/{assetId}/{+|*}
        String thirdTokenStr = topicTokenIndexToString(topic, 5);

        if (TOKEN_MULTI_LEVEL_WILDCARD.equals(thirdTokenStr)) {
          paths.add(topicTokenIndexToString(topic, 4));
        } else if (TOKEN_SINGLE_LEVEL_WILDCARD.equals(thirdTokenStr)) {
          parentIds.add(topicTokenIndexToString(topic, 4));
        }
      } else {
        return null;
      }
    }

    AssetFilter<?> assetFilter = new AssetFilter<>().setRealm(realm).setValueChanged(true);
    if (!assetIds.isEmpty()) {
      assetFilter.setAssetIds(assetIds.toArray(new String[0]));
    }
    if (!parentIds.isEmpty()) {
      assetFilter.setParentIds(parentIds.toArray(new String[0]));
    }
    if (!paths.isEmpty()) {
      assetFilter.setPath(paths.toArray(new String[0]));
    }
    if (!attributeNames.isEmpty()) {
      assetFilter.setAttributeNames(attributeNames.toArray(new String[0]));
    }
    // Force subscription to filter only value changed attribute events
    return assetFilter.setValueChanged(true);
  }

  protected <T extends Event> Consumer<T> getSubscriptionEventConsumer(
      RemotingConnection connection, Topic topic) {
    boolean isValueSubscription =
        ATTRIBUTE_VALUE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
    boolean isAssetTopic = isAssetTopic(topic);

    // Always publish asset/attribute messages with QoS 0
    MqttQoS mqttQoS = MqttQoS.AT_MOST_ONCE;

    // Build topic expander (replace wildcards) so it isn't computed for each event
    Function<T, String> topicExpander;

    if (isAssetTopic) {
      String topicStr = topic.toString();
      String replaceToken =
          topicStr.endsWith(TOKEN_MULTI_LEVEL_WILDCARD)
              ? TOKEN_MULTI_LEVEL_WILDCARD
              : topicStr.endsWith(TOKEN_SINGLE_LEVEL_WILDCARD) ? TOKEN_SINGLE_LEVEL_WILDCARD : null;
      topicExpander =
          ev ->
              replaceToken != null
                  ? topicStr.replace(replaceToken, ((AssetEvent) ev).getId())
                  : topicStr;
    } else {
      String topicStr = topic.toString();
      boolean injectAttributeName =
          TOKEN_SINGLE_LEVEL_WILDCARD.equals(topicTokenIndexToString(topic, 3));

      if (injectAttributeName) {
        topicStr = topicStr.replaceFirst("\\" + TOKEN_SINGLE_LEVEL_WILDCARD, "\\$");
      }

      String replaceToken =
          topicStr.endsWith(TOKEN_MULTI_LEVEL_WILDCARD)
              ? TOKEN_MULTI_LEVEL_WILDCARD
              : topicStr.endsWith(TOKEN_SINGLE_LEVEL_WILDCARD) ? TOKEN_SINGLE_LEVEL_WILDCARD : null;
      String finalTopicStr = topicStr;
      topicExpander =
          ev -> {
            String expanded =
                replaceToken != null
                    ? finalTopicStr.replace(replaceToken, ((AttributeEvent) ev).getId())
                    : finalTopicStr;
            if (injectAttributeName) {
              expanded = expanded.replace("$", ((AttributeEvent) ev).getName());
            }
            return expanded;
          };
    }

    return ev -> {
      if (isAssetTopic) {
        if (ev instanceof AssetEvent) {
          publishMessage(topicExpander.apply(ev), ev, mqttQoS);
        }
      } else {
        if (ev instanceof AttributeEvent attributeEvent) {
          if (isValueSubscription) {
            publishMessage(
                topicExpander.apply(ev), attributeEvent.getValue().orElse(null), mqttQoS);
          } else {
            publishMessage(topicExpander.apply(ev), ev, mqttQoS);
          }
        }
      }
    };
  }

  protected static boolean isAttributeTopic(Topic topic) {
    return ATTRIBUTE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2))
        || ATTRIBUTE_VALUE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
  }

  protected static boolean isAttributeValueWriteTopic(Topic topic) {
    return ATTRIBUTE_VALUE_WRITE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
  }

  protected static boolean isAttributeWriteTopic(Topic topic) {
    return ATTRIBUTE_WRITE_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
  }

  protected static boolean isAssetTopic(Topic topic) {
    return ASSET_TOPIC.equalsIgnoreCase(topicTokenIndexToString(topic, 2));
  }

  protected static String getSessionKey(RemotingConnection connection) {
    return getConnectionIDString(connection);
  }
}
