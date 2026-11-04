/*
 * Copyright 2026, OpenRemote Inc.
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
package org.openremote.test.mqtt

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.hivemq.client.internal.mqtt.mqtt3.Mqtt3AsyncClientView
import com.hivemq.client.internal.mqtt.mqtt3.Mqtt3ClientConfigView
import com.hivemq.client.mqtt.MqttClientConfig
import com.hivemq.client.mqtt.MqttClientConnectionConfig
import io.netty.channel.socket.SocketChannel
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection
import org.openremote.agent.protocol.mqtt.MQTTLastWill
import org.openremote.agent.protocol.mqtt.MQTTMessage
import org.openremote.agent.protocol.mqtt.MQTT_IOClient
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.mqtt.DefaultMQTTHandler
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.manager.mqtt.MQTTHandler
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.AssetEvent
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.RoomAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.AttributeMap
import org.openremote.model.attribute.MetaItem
import org.openremote.model.auth.UsernamePassword
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.util.ValueUtil
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import static org.openremote.model.util.MapAccess.getInteger
import static org.openremote.model.util.MapAccess.getString
import static org.openremote.manager.mqtt.MQTTBrokerService.*
import static org.openremote.model.value.MetaItemType.ACCESS_RESTRICTED_READ
import static org.openremote.model.value.ValueType.TEXT

class MqttBrokerTest extends Specification implements ManagerContainerTrait {

  private ManagerTestSetup managerTestSetup
  private KeycloakTestSetup keycloakTestSetup
  private MQTTBrokerService mqttBrokerService
  private AssetStorageService assetStorageService
  private AssetProcessingService assetProcessingService
  private DefaultMQTTHandler defaultMQTTHandler
  private Cache<String, RemotingConnection> originalDisconnectedConnectionCache
  private String mqttHost
  private int mqttPort
  private UsernamePassword mqttCredentials
  private UsernamePassword restrictedMqttCredentials
  private final List<MQTT_IOClient> clients = []
  private final Map<String, AttributeMap> originalAttributes = [:]
  private final List<String> createdAssetIds = []
  private List<UserAssetLink> originalUserAssetLinks
  private final PollingConditions conditions = new PollingConditions(timeout: 15, initialDelay: 0.1, delay: 0.2)

  // MQTT callbacks add events asynchronously. Blocking queues let absence checks use timed poll()
  // to wait for unexpected events, returning null only after the full observation period.
  // PollingConditions.eventually { ...isEmpty() } would succeed on the first empty check.
  private final BlockingQueue<SharedEvent> receivedEvents = new LinkedBlockingQueue<>()
  private final Consumer<MQTTMessage<String>> eventConsumer = eventConsumerFor(receivedEvents)

  def setupSpec() {
    startContainer(defaultConfig(), defaultServices())
  }

  def setup() {
    managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    mqttBrokerService = container.getService(MQTTBrokerService.class)
    assetStorageService = container.getService(AssetStorageService.class)
    assetProcessingService = container.getService(AssetProcessingService.class)
    defaultMQTTHandler = mqttBrokerService.getCustomHandlers().find {
      it instanceof DefaultMQTTHandler
    } as DefaultMQTTHandler
    originalDisconnectedConnectionCache = mqttBrokerService.@disconnectedConnectionCache
    mqttHost = getString(container.getConfig(), MQTT_SERVER_LISTEN_HOST, "0.0.0.0")
    mqttPort = getInteger(container.getConfig(), MQTT_SERVER_LISTEN_PORT, 1883)
    mqttCredentials = new UsernamePassword(
            "${keycloakTestSetup.realmBuilding.name}:${keycloakTestSetup.serviceUser.username}".toString(),
            keycloakTestSetup.serviceUser.secret)
    restrictedMqttCredentials = new UsernamePassword(
            "${keycloakTestSetup.realmBuilding.name}:${keycloakTestSetup.serviceUser2.username}".toString(),
            keycloakTestSetup.serviceUser2.secret)
  }

  def cleanup() {
    try {
      clients.each { it.disconnect() }
      if (mqttBrokerService != null) {
        conditions.eventually {
          assert clients.every { it.getConnectionStatus() == ConnectionStatus.DISCONNECTED }
          assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser.id).isEmpty()
          assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).isEmpty()
          assert defaultMQTTHandler.sessionSubscriptionConsumers.isEmpty()
        }
      }

      // Restore fixtures only after clients and subscriptions are gone, so cleanup cannot trigger reconnects
      // or deliver restoration events to a test client.
      if (!createdAssetIds.isEmpty()) {
        assert assetStorageService.deleteUntilFinished(createdAssetIds).get(15, TimeUnit.SECONDS)
      }
      originalAttributes.each { assetId, attributes ->
        def asset = assetStorageService.find(assetId)
        asset.setAttributes(attributes)
        assetStorageService.merge(asset)
      }
      if (originalUserAssetLinks != null) {
        def currentLinks = assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name,
                keycloakTestSetup.serviceUser2.id, null as String)
        assetStorageService.deleteUserAssetLinks(currentLinks.findAll { current ->
          !originalUserAssetLinks.any { it.id == current.id }
        })
        assetStorageService.storeUserAssetLinks(originalUserAssetLinks.findAll { original ->
          !currentLinks.any { it.id == original.id }
        })
      }
      if (!originalAttributes.isEmpty()) {
        conditions.eventually {
          assert noEventProcessedIn(assetProcessingService, 300)
        }
      }
    } finally {
      if (originalDisconnectedConnectionCache != null) {
        mqttBrokerService.@disconnectedConnectionCache = originalDisconnectedConnectionCache
      }
    }
  }

  def "MQTT connections reject invalid credentials and release resources on disconnect"() {
    given: "MQTT client credentials and a connection status listener"
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    List<ConnectionStatus> clientConnectionStatuses = new CopyOnWriteArrayList<>()
    MQTT_IOClient client

    when: "a mqtt client connects with invalid credentials"
    def wrongUsername = "master:" + keycloakTestSetup.serviceUser.username
    client = createMqttClient(mqttClientId, new UsernamePassword(wrongUsername, mqttCredentials.password))
    client.addConnectionStatusConsumer {clientConnectionStatuses.add(it)}
    client.connect()

    then: "the client should fail connection and retry"
    conditions.eventually {
      assert clientConnectionStatuses.contains(ConnectionStatus.CONNECTING)
      assert clientConnectionStatuses.contains(ConnectionStatus.WAITING)
    }

    when: "the client disconnects"
    client.disconnect()

    then: "the status should be reported and reconnect cancelled"
    conditions.eventually {
      assert clientConnectionStatuses.contains(ConnectionStatus.DISCONNECTED)
    }

    when: "a mqtt client connects with valid credentials"
    mqttClientId = UniqueIdentifierGenerator.generateId()
    client = createMqttClient(mqttClientId)
    client.connect()

    then: "mqtt connection should exist"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser.id).size() == 1
    }

    when: "the client disconnects"
    client.disconnect()

    then: "the client resources should be freed"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.DISCONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser.id).isEmpty()
    }
  }

  def "MQTT subscriptions reject another realm and missing or mismatched client IDs"() {
    given: "a connected client that removes consumers after subscription failures"
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    List<String> subFailures = new CopyOnWriteArrayList<>()
    def client = createMqttClient(mqttClientId)
    client.setTopicSubscribeFailureConsumer { subFailures.add(it) }
    client.setRemoveConsumersOnSubscriptionFailure(true)
    client.connect()
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser.id).size() == 1
    }

    when: "a mqtt client subscribes to an asset in another realm"
    def topic = "${keycloakTestSetup.realmCity.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.thingId".toString()
    client.addMessageConsumer(topic, {msg -> })

    then: "no subscription should exist"
    conditions.eventually {
      assertSubscriptionFailed(client, topic, subFailures)
      assertNoBrokerSubscriptions(keycloakTestSetup.serviceUser.id)
    }

    when: "a mqtt client subscribes with clientId missing"
    topic = "${keycloakTestSetup.realmBuilding.name}/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    client.addMessageConsumer(topic, {msg ->})

    then: "no subscription should exist"
    conditions.eventually {
      assertSubscriptionFailed(client, topic, subFailures)
      assertNoBrokerSubscriptions(keycloakTestSetup.serviceUser.id)
    }

    when: "a mqtt client subscribes with different clientId"
    def newClientId = UniqueIdentifierGenerator.generateId()
    topic = "${keycloakTestSetup.realmBuilding.name}/$newClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    client.addMessageConsumer(topic, {msg ->})

    then: "no subscription should exist"
    conditions.eventually {
      assertSubscriptionFailed(client, topic, subFailures)
      assertNoBrokerSubscriptions(keycloakTestSetup.serviceUser.id)
    }
  }

  def "MQTT attribute subscriptions receive events and writes and stop after unsubscribe"() {
    given: "a connected MQTT client"
    preserveAttributes(managerTestSetup.apartment1HallwayId)
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    def client = connectMqttClient(mqttClientId)

    when: "a mqtt client subscribes to all attributes of an asset"
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser.id, 1)
    }

    when: "An attribute event occurs for a subscribed attribute"
    def attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "motionSensor", 50)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "a publish event message should be sent"
    conditions.eventually {
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 50)
    }
    receivedEvents.clear()

    when: "another asset attribute changed the client is subscribed on"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "presenceDetected", true)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "a second publish event message should be sent"
    conditions.eventually {
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "presenceDetected", true)
    }
    receivedEvents.clear()

    when: "a mqtt client publishes to an asset attribute"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/motionSensor/${managerTestSetup.apartment1HallwayId}".toString()
    def payload = "70"
    client.sendMessage(new MQTTMessage<String>(topic, payload))

    then: "the value of the attribute should be updated and the client should have received the event"
    new PollingConditions(initialDelay: 1, timeout: 10, delay: 1).eventually {
      assert assetStorageService.find(managerTestSetup.apartment1HallwayId).getAttribute("motionSensor").get().value.orElse(0) == 70d
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 70d)
    }
    receivedEvents.clear()

    when: "a mqtt client publishes to an asset attribute"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/lights/${managerTestSetup.apartment1HallwayId}".toString()
    payload = Boolean.FALSE.toString()
    client.sendMessage(new MQTTMessage<String>(topic, payload))

    then: "the value of the attribute should be updated and the client should have received the event"
    conditions.eventually {
      assert !assetStorageService.find(managerTestSetup.apartment1HallwayId).getAttribute("lights").get().value.orElse(true)
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "lights", false)
    }
    receivedEvents.clear()

    when: "a mqtt client publishes to an asset attribute value"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/lights/$managerTestSetup.apartment1HallwayId".toString()
    payload = ValueUtil.asJSON(true).orElse(null)
    client.sendMessage(new MQTTMessage<String>(topic, payload))

    then: "the value of the attribute should be updated and the client should have received the event"
    conditions.eventually {
      assert assetStorageService.find(managerTestSetup.apartment1HallwayId).getAttribute("lights").get().value.orElse(false)
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "lights", true)
    }
    receivedEvents.clear()

    when: "a mqtt client unsubscribes from an asset"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    client.removeMessageConsumer(topic, eventConsumer)

    then: "no subscription should exist"
    conditions.eventually {
      assertUnsubscribed(client, topic, keycloakTestSetup.serviceUser.id)
    }

    when: "another asset attribute changed without any subscriptions"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "presenceDetected", false)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "no publish event message should be sent"
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null

    when: "a mqtt client subscribes to a specific asset attribute"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/motionSensor/$managerTestSetup.apartment1HallwayId".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "the subscription should be created"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser.id, 1)
    }

    when: "that attribute changes"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "motionSensor", 30)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "a publish event message should be sent to the client"
    conditions.eventually {
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 30)
    }
    receivedEvents.clear()

    when: "another asset attribute changed without any subscriptions on that attribute"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "presenceDetected", true)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "no publish event message should be sent"
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null

    when: "a mqtt client unsubscribes from an asset attribute"
    client.removeMessageConsumer(topic, eventConsumer)

    then: "no subscription should exist"
    conditions.eventually {
      assertUnsubscribed(client, topic, keycloakTestSetup.serviceUser.id)
    }
  }

  def "MQTT wildcard and value subscriptions filter events and survive reconnects"() {
    given: "a connected MQTT client and a collector for raw attribute values"
    preserveAttributes(managerTestSetup.apartment1HallwayId)
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    def client = connectMqttClient(mqttClientId)
    List<Object> receivedValues = new CopyOnWriteArrayList<>()

    when: "a mqtt client subscribes to attributes for descendants of an asset"
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId/$MQTTHandler.TOKEN_MULTI_LEVEL_WILDCARD".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser.id, 1)
    }

    when: "a child asset of the subscription attribute event occurs"
    def attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "motionSensor", 40)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "a publish event message should be sent"
    conditions.eventually {
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 40)
    }
    receivedEvents.clear()

    when: "a mqtt client subscribes to an asset attribute value"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_TOPIC/motionSensor/$managerTestSetup.apartment1HallwayId".toString()
    Consumer<MQTTMessage<String>> valueConsumer = { msg ->
      receivedValues.add(msg.payload)
    }
    client.addMessageConsumer(topic, valueConsumer)

    then: "the subscription should be created"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser.id, 2)
    }

    when: "a subscribed attribute changes"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "motionSensor", 50)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "a publish value message should be sent to both subscriptions"
    conditions.eventually {
      assert receivedValues.size() == 1
      assert receivedValues.get(0) == "50"
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 50)
    }
    receivedEvents.clear()
    receivedValues.clear()

    when: "a subscribed attribute changes"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "presenceDetected", true)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "only the wildcard subscription should have received a publish"
    conditions.eventually {
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "presenceDetected", true)
      assert receivedValues.size() == 0
    }

    when: "the client unsubscribes from the attribute value topic"
    client.removeMessageConsumer(topic, valueConsumer)

    then: "only one subscription should remain"
    conditions.eventually {
      assertUnsubscribed(client, topic, keycloakTestSetup.serviceUser.id, 1)
    }

    when: "a client disconnects"
    client.disconnect()

    then: "the client status should be disconnected"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.DISCONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser.id).size() == 0
    }

    when: "the client reconnects"
    receivedEvents.clear()
    client.connect()

    then: "the connection should exist and the previous subscription should be recreated"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert client.topicConsumerMap.size() == 1
      client.topicConsumerMap.keySet().each { subscriptionTopic ->
        assertSubscribed(client, subscriptionTopic, keycloakTestSetup.serviceUser.id, 1)
      }
    }
  }

  def "MQTT asset subscriptions receive realm children and descendants and stop after unsubscribe"() {
    given: "a connected MQTT client"
    preserveAttributes(managerTestSetup.smartBuildingId)
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    def client = connectMqttClient(mqttClientId)

    when: "a mqtt client subscribes to assets that are direct children of the realm"
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ASSET_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser.id, 1)
    }

    when: "an asset is updated with a new attribute"
    def asset1 = assetStorageService.find(managerTestSetup.smartBuildingId)
    asset1.addAttributes(new Attribute<>("temp", TEXT, "hello world"))
    assetStorageService.merge(asset1)

    then: "a publish event message should be sent"
    conditions.eventually {
      def event = assertSingleEvent(receivedEvents, AssetEvent)
      assert event.id == managerTestSetup.smartBuildingId
      assert event.asset.getAttribute("temp").flatMap { it.value }.orElse(null) == "hello world"
    }
    receivedEvents.clear()

    when: "the client subscribes to descendant assets of a specific asset"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ASSET_TOPIC/$managerTestSetup.apartment1Id/$MQTTHandler.TOKEN_MULTI_LEVEL_WILDCARD".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "the subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser.id, 2)
    }

    when: "an asset is added as a descendant to the subscribed asset"
    def childAsset = new ThingAsset("child")
            .setId(UniqueIdentifierGenerator.generateId())
            .setParentId(managerTestSetup.apartment1LivingroomId)
            .setRealm(managerTestSetup.realmBuildingName)
    createdAssetIds.add(childAsset.id)
    childAsset = assetStorageService.merge(childAsset)

    then: "another event should be sent"
    conditions.eventually {
      def event = assertSingleEvent(receivedEvents, AssetEvent)
      assert event.id == childAsset.id
      assert event.assetName == childAsset.name
    }
    receivedEvents.clear()

    when: "the mqtt client unsubscribes from the multilevel topic"
    client.removeMessageConsumer(topic, eventConsumer)

    then: "only the realm children subscription should be left"
    conditions.eventually {
      assertUnsubscribed(client, topic, keycloakTestSetup.serviceUser.id, 1)
    }

    when: "the descendant asset is modified"
    childAsset.addAttributes(new Attribute<>("temp2", TEXT))
    childAsset = assetStorageService.merge(childAsset)

    then: "a publish event message should not be sent"
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null
  }

  def "MQTT last wills update authorised attributes and clients can publish after reconnect"() {
    given: "an independent observer subscribed to the last will attribute"
    preserveAttributes(managerTestSetup.apartment1HallwayId)
    def observerId = UniqueIdentifierGenerator.generateId()
    def observer = connectMqttClient(observerId)
    def observerTopic = "${keycloakTestSetup.realmBuilding.name}/$observerId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    observer.addMessageConsumer(observerTopic, eventConsumer)
    conditions.eventually {
      assertSubscribed(observer, observerTopic, keycloakTestSetup.serviceUser.id, 1)
    }
    def mqttClientId = UniqueIdentifierGenerator.generateId()

    when: "another client connects with a last will configured to write to an attribute"
    def client = createMqttClient(mqttClientId, restrictedMqttCredentials, new MQTTLastWill("${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/motionSensor/$managerTestSetup.apartment1HallwayId".toString(), "1000", false))
    client.connect()

    then: "the client should be connected"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    when: "the client publishes to an attribute topic"
    receivedEvents.clear()
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/motionSensor/${managerTestSetup.apartment1HallwayId}".toString()
    def payload = "170"
    client.sendMessage(new MQTTMessage<String>(topic, payload))

    then: "the attribute should be updated and the observer should receive the event"
    new PollingConditions(initialDelay: 1, timeout: 10, delay: 1).eventually {
      assert assetStorageService.find(managerTestSetup.apartment1HallwayId).getAttribute("motionSensor").get().value.orElse(0) == 170d
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 170d)
    }
    receivedEvents.clear()

    when: "the client's connection is closed abruptly"
    widenDisconnectedConnectionWindow(mqttBrokerService)
    def existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
    closeClientChannel(client)

    then: "the client should reconnect"
    conditions.eventually {
      assertReconnected(client, keycloakTestSetup.serviceUser2.id, existingConnection)
    }

    and: "the last will should update the attribute and the observer should receive the event"
    // The broker processes the last will asynchronously, so verify its effect separately from the reconnect.
    new PollingConditions(initialDelay: 1, timeout: 30, delay: 1).eventually {
      assert assetStorageService.find(managerTestSetup.apartment1HallwayId).getAttribute("motionSensor").get().value.orElse(0) == 1000d
      assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 1000d)
    }

    when: "the client publishes again with a specified timestamp"
    def specifiedTimestamp = (receivedEvents.peek() as AttributeEvent).timestamp + 5
    receivedEvents.clear()
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_WRITE_TOPIC/motionSensor/${managerTestSetup.apartment1HallwayId}".toString()
    payload = "{\"value\": 170, \"timestamp\": ${specifiedTimestamp}}".toString()
    client.sendMessage(new MQTTMessage<String>(topic, payload))

    then: "the attribute should be updated and the observer should receive the event with that timestamp"
    new PollingConditions(initialDelay: 1, timeout: 10, delay: 1).eventually {
      assert assetStorageService.find(managerTestSetup.apartment1HallwayId).getAttribute("motionSensor").get().value.orElse(0) == 170d
      def event = assertAttributeEvent(receivedEvents, managerTestSetup.apartment1HallwayId, "motionSensor", 170d)
      assert event.timestamp == specifiedTimestamp
    }
    receivedEvents.clear()

    when: "the client disconnects"
    client.disconnect()

    then: "the status should show as disconnected"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.DISCONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).isEmpty()
    }
  }

  def "MQTT last wills cannot update unauthorised attributes"() {
    given: "a client ID for the restricted user"
    preserveAttributes(managerTestSetup.apartment2LivingroomId)
    def mqttClientId = UniqueIdentifierGenerator.generateId()

    when: "a restricted client connects with a last will topic for an unauthorised asset/attribute"
    def asset = assetStorageService.find(managerTestSetup.apartment2LivingroomId)
    def currentCO2Level = asset.getAttribute("co2Level").flatMap{it.getValue()}.orElse(0d)
    def client = createMqttClient(mqttClientId, restrictedMqttCredentials, new MQTTLastWill("${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/co2Level/$managerTestSetup.apartment2LivingroomId".toString(), "1000", false))
    client.connect()

    then: "the client should be connected"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).size() == 1
    }

    when: "the client's connection is closed abruptly"
    widenDisconnectedConnectionWindow(mqttBrokerService)
    def existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
    closeClientChannel(client)

    then: "the client should reconnect"
    conditions.eventually {
      assertReconnected(client, keycloakTestSetup.serviceUser2.id, existingConnection)
    }

    then: "the last will message should not have updated the value of the attribute"
    new PollingConditions(initialDelay: 1, timeout: 10, delay: 1).eventually {
      def asst = assetStorageService.find(managerTestSetup.apartment2LivingroomId)
      assert asst.getAttribute("co2Level").get().value.orElse(-100) == currentCO2Level
    }
  }

  def "MQTT restricted subscriptions are retried when user asset links change"() {
    given: "a connected restricted client that retains failed subscriptions"
    preserveUserAssetLinks()
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    List<String> subFailures = new CopyOnWriteArrayList<>()
    def client = createMqttClient(mqttClientId, restrictedMqttCredentials)
    client.setTopicSubscribeFailureConsumer { subFailures.add(it) }
    client.connect()
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).size() == 1
    }

    when: "a restricted mqtt client subscribes to an unlinked asset"
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1BathroomId".toString()
    def bathroomTopic = topic
    client.addMessageConsumer(topic, {msg ->})

    then: "the subscription should fail but the consumer should still exist (as setRemoveConsumersOnSubscriptionFailure=false)"
    conditions.eventually {
      assertSubscriptionFailed(client, topic, subFailures)
      assertNoBrokerSubscriptions(keycloakTestSetup.serviceUser2.id)
    }

    when: "a restricted mqtt client subscribes to a linked asset"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    def hallwayTopic = topic
    client.addMessageConsumer(topic, {msg ->})

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser2.id, 1)
    }

    when: "a user asset link is added for a connected restricted user"
    def existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
    assetStorageService.storeUserAssetLinks(List.of(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.getName(),
                    keycloakTestSetup.serviceUser2.getId(),
                    managerTestSetup.apartment1BathroomId)))

    then: "the existing connection should be terminated and the client should reconnect and the previously failed subscription should now succeed"
    conditions.eventually {
      assertReconnected(client, keycloakTestSetup.serviceUser2.id, existingConnection)
      assertSubscribed(client, bathroomTopic, keycloakTestSetup.serviceUser2.id)
      assertSubscribed(client, hallwayTopic, keycloakTestSetup.serviceUser2.id)
    }

    when: "a user asset link is removed for a connected restricted user"
    existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
    subFailures.clear()
    assetStorageService.deleteUserAssetLinks(List.of(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.getName(),
                    keycloakTestSetup.serviceUser2.getId(),
                    managerTestSetup.apartment1HallwayId)))

    then: "the existing connection should be terminated and the client should reconnect and the previously failed subscription should be tried again but also fail"
    conditions.eventually {
      assertReconnected(client, keycloakTestSetup.serviceUser2.id, existingConnection)
      assertSubscribed(client, bathroomTopic, keycloakTestSetup.serviceUser2.id)
      assertSubscriptionFailed(client, hallwayTopic, subFailures)
    }

    when: "the restricted mqtt client removes all subscriptions"
    client.removeAllMessageConsumers() // Clear subscriptions as otherwise it won't hit the server again

    then: "no subscriptions should remain in the client or broker"
    conditions.eventually {
      assertNoSubscriptions(client, keycloakTestSetup.serviceUser2.id)
    }

    when: "the restricted mqtt client subscribes to a now unlinked asset"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$managerTestSetup.apartment1HallwayId".toString()
    subFailures.clear()
    client.addMessageConsumer(topic, eventConsumer)

    then: "no subscription should exist"
    conditions.eventually {
      assertSubscriptionFailed(client, topic, subFailures)
      assertNoBrokerSubscriptions(keycloakTestSetup.serviceUser2.id)
    }

    when: "the previously failed message consumer is removed"
    client.removeMessageConsumer(topic, eventConsumer)

    then: "the failed subscription should be removed from the client"
    assertUnsubscribed(client, topic, keycloakTestSetup.serviceUser2.id)
  }

  def "MQTT restricted attribute subscriptions filter asset links and restricted read metadata"() {
    given: "a restricted client linked to the bathroom but not the hallway"
    preserveAttributes(managerTestSetup.apartment1HallwayId, managerTestSetup.apartment1BathroomId)
    preserveUserAssetLinks()
    assetStorageService.storeUserAssetLinks(List.of(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.name,
                    keycloakTestSetup.serviceUser2.id, managerTestSetup.apartment1BathroomId)))
    assetStorageService.deleteUserAssetLinks(List.of(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.name,
                    keycloakTestSetup.serviceUser2.id, managerTestSetup.apartment1HallwayId)))
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    def client = connectMqttClient(mqttClientId, restrictedMqttCredentials)

    when: "a restricted mqtt client subscribes to the attribute wildcard topic"
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/$MQTTHandler.TOKEN_SINGLE_LEVEL_WILDCARD/$MQTTHandler.TOKEN_MULTI_LEVEL_WILDCARD".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser2.id)
    }

    when: "an unlinked asset attribute event occurs"
    def attributeEvent = new AttributeEvent(managerTestSetup.apartment1HallwayId, "motionSensor", 40)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "the restricted client should not receive the event"
    conditions.eventually {
      assert assetStorageService.find(attributeEvent.id).getAttribute(attributeEvent.name).get().value == attributeEvent.value
    }
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null

    when: "a linked asset attribute event occurs"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1BathroomId, RoomAsset.ROOM_NUMBER, 1)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "the restricted client should not receive the event since the attribute is not restricted"
    conditions.eventually {
      assert assetStorageService.find(attributeEvent.id).getAttribute(attributeEvent.name).get().value == attributeEvent.value
    }
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null

    when: "a linked asset attribute has its restricted meta configuration set to true"
    def bathroomAsset = assetStorageService.find(managerTestSetup.apartment1BathroomId)
    bathroomAsset.getAttributes().get(RoomAsset.ROOM_NUMBER).get().addOrReplaceMeta(new MetaItem<>(ACCESS_RESTRICTED_READ, true))
    assetStorageService.merge(bathroomAsset)

    then: "the restricted client should still be connected"
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id).size() == 1
    }

    then: "the restricted client should receive the triggered attribute event"
    conditions.eventually {
      assert receivedEvents.size() == 1
    }
    receivedEvents.clear()

    when: "another attribute event occurs on the restricted read attribute"
    attributeEvent = new AttributeEvent(managerTestSetup.apartment1BathroomId, RoomAsset.ROOM_NUMBER, 2)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "the restricted client should receive the triggered attribute event"
    conditions.eventually {
      assert receivedEvents.size() == 1
      assert receivedEvents.peek() == attributeEvent
    }
    receivedEvents.clear()

    when: "the restricted read meta item is removed"
    bathroomAsset = assetStorageService.find(managerTestSetup.apartment1BathroomId)
    bathroomAsset.getAttributes().addOrReplace(new Attribute<>(RoomAsset.ROOM_NUMBER, 2))
    assetStorageService.merge(bathroomAsset)

    then: "the restricted client should not receive the triggered attribute event"
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null
    client.removeAllMessageConsumers()
    receivedEvents.clear()
  }

  def "MQTT restricted asset subscriptions filter attributes and stop after unlinking"() {
    given: "a restricted client linked to an asset with an attribute without restricted read access"
    preserveAttributes(managerTestSetup.apartment1BathroomId)
    preserveUserAssetLinks()
    assetStorageService.storeUserAssetLinks(List.of(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.name,
                    keycloakTestSetup.serviceUser2.id, managerTestSetup.apartment1BathroomId)))
    def bathroomAsset = assetStorageService.find(managerTestSetup.apartment1BathroomId)
    bathroomAsset.attributes.addOrReplace(new Attribute<>(RoomAsset.ROOM_NUMBER, 2))
    assetStorageService.merge(bathroomAsset)
    def mqttClientId = UniqueIdentifierGenerator.generateId()
    def client = connectMqttClient(mqttClientId, restrictedMqttCredentials)

    when: "a restricted mqtt client subscribes to the asset wildcard topic"
    def topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ASSET_TOPIC/$MQTTHandler.TOKEN_MULTI_LEVEL_WILDCARD".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser2.id)
    }

    when: "a linked asset event occurs"
    bathroomAsset = assetStorageService.find(managerTestSetup.apartment1BathroomId)
    bathroomAsset.getAttributes().get(RoomAsset.ROOM_NUMBER).get().setValue(2)
    assetStorageService.merge(bathroomAsset)

    then: "the restricted client should receive the triggered asset event"
    conditions.eventually {
      assert receivedEvents.size() == 1
    }

    and: "the event should only contain attributes that are read restricted"
    conditions.eventually {
      def event = assertSingleEvent(receivedEvents, AssetEvent)
      assert !event.asset.hasAttribute(RoomAsset.ROOM_NUMBER)
    }
    receivedEvents.clear()

    when: "the restricted read meta item is added again"
    bathroomAsset = assetStorageService.find(managerTestSetup.apartment1BathroomId)
    bathroomAsset.getAttributes().get(RoomAsset.ROOM_NUMBER).get().addOrReplaceMeta(new MetaItem<>(ACCESS_RESTRICTED_READ, true))
    assetStorageService.merge(bathroomAsset)

    then: "the restricted client should receive the triggered asset event"
    conditions.eventually {
      def event = assertSingleEvent(receivedEvents, AssetEvent)
      assert event.asset.getAttribute(RoomAsset.ROOM_NUMBER).flatMap { it.value }.orElse(null) == 2
    }
    receivedEvents.clear()

    when: "the asset is unlinked"
    def existingConnection = mqttBrokerService.getUserConnections(keycloakTestSetup.serviceUser2.id)[0]
    assetStorageService.deleteUserAssetLinks(List.of(
                    new UserAssetLink(keycloakTestSetup.realmBuilding.getName(),
                    keycloakTestSetup.serviceUser2.getId(),
                    managerTestSetup.apartment1BathroomId)))


    then: "the existing connection should be terminated and the client should reconnect"
    conditions.eventually {
      assertReconnected(client, keycloakTestSetup.serviceUser2.id, existingConnection)
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser2.id)
    }

    when: "the restricted mqtt client removes all subscriptions"
    client.removeAllMessageConsumers() // Clear subscriptions as otherwise it won't hit the server again

    then: "no subscriptions should remain in the client or broker"
    conditions.eventually {
      assertNoSubscriptions(client, keycloakTestSetup.serviceUser2.id)
    }

    when: "the restricted mqtt client subscribes to the asset wildcard topic again"
    topic = "${keycloakTestSetup.realmBuilding.name}/$mqttClientId/$DefaultMQTTHandler.ASSET_TOPIC/$MQTTHandler.TOKEN_MULTI_LEVEL_WILDCARD".toString()
    client.addMessageConsumer(topic, eventConsumer)

    then: "a subscription should exist"
    conditions.eventually {
      assertSubscribed(client, topic, keycloakTestSetup.serviceUser2.id)
    }

    when: "an asset event occurs on the now unlinked asset"
    bathroomAsset = assetStorageService.find(managerTestSetup.apartment1BathroomId)
    bathroomAsset.getAttributes().get(RoomAsset.ROOM_NUMBER).get().setValue(2)
    assetStorageService.merge(bathroomAsset)

    then: "the restricted client should not receive the triggered asset event"
    assert receivedEvents.poll(1, TimeUnit.SECONDS) == null
    receivedEvents.clear()
    client.removeAllMessageConsumers()
  }

  private void preserveAttributes(String... assetIds) {
    assetIds.each { assetId ->
      originalAttributes.putIfAbsent(assetId, ValueUtil.clone(assetStorageService.find(assetId).attributes))
    }
  }

  private void preserveUserAssetLinks() {
    originalUserAssetLinks = assetStorageService.findUserAssetLinks(keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.serviceUser2.id, null as String)
  }

  private MQTT_IOClient createMqttClient(String clientId, UsernamePassword credentials = null,
          MQTTLastWill lastWill = null) {
    def client = new MQTT_IOClient(clientId, mqttHost, mqttPort, false, true,
            credentials ?: mqttCredentials, null, lastWill)
    clients.add(client)
    return client
  }

  private MQTT_IOClient connectMqttClient(String clientId, UsernamePassword credentials = null) {
    def client = createMqttClient(clientId, credentials)
    client.connect()
    conditions.eventually {
      assert client.getConnectionStatus() == ConnectionStatus.CONNECTED
    }
    return client
  }

  private static Consumer<MQTTMessage<String>> eventConsumerFor(Queue<SharedEvent> events) {
    return { msg -> events.add(ValueUtil.parse(msg.payload as String, SharedEvent.class).get()) }
  }

  private static <T extends SharedEvent> T assertSingleEvent(Queue<SharedEvent> events, Class<T> eventType) {
    assert events.size() == 1
    def event = events.peek()
    assert eventType.isInstance(event)
    return eventType.cast(event)
  }

  private static AttributeEvent assertAttributeEvent(Queue<SharedEvent> events, String assetId,
          String name, Object value) {
    def event = assertSingleEvent(events, AttributeEvent)
    assert event.id == assetId
    assert event.name == name
    assert event.value.orElse(null) == value
    return event
  }

  private static void closeClientChannel(MQTT_IOClient mqttClient) {
    def clientView = mqttClient.client as Mqtt3AsyncClientView
    def configView = clientView.clientConfig as Mqtt3ClientConfigView
    def clientConfig = configView.delegate as MqttClientConfig
    def connectionConfig = clientConfig.connectionConfig.get() as MqttClientConnectionConfig
    (connectionConfig.channel as SocketChannel).close()
  }

  private Map<String, ?> brokerSubscriptions(String userId) {
    def connections = mqttBrokerService.getUserConnections(userId)
    assert connections.size() == 1
    return defaultMQTTHandler.sessionSubscriptionConsumers.get(getConnectionIDString(connections[0]))
  }

  private void assertNoBrokerSubscriptions(String userId) {
    assert brokerSubscriptions(userId) == null
  }

  private void assertNoSubscriptions(MQTT_IOClient mqttClient, String userId) {
    synchronized (mqttClient.topicConsumerMap) {
      assert mqttClient.topicConsumerMap.isEmpty()
    }
    assertNoBrokerSubscriptions(userId)
  }

  private void assertUnsubscribed(MQTT_IOClient mqttClient, String subscriptionTopic, String userId,
          int expectedRemainingSubscriptions = 0) {
    synchronized (mqttClient.topicConsumerMap) {
      assert mqttClient.topicConsumerMap.get(subscriptionTopic) == null
      assert mqttClient.topicConsumerMap.size() == expectedRemainingSubscriptions
    }
    if (expectedRemainingSubscriptions == 0) {
      assertNoBrokerSubscriptions(userId)
    } else {
      def subscriptions = brokerSubscriptions(userId)
      assert subscriptions != null
      assert !subscriptions.containsKey(subscriptionTopic)
      assert subscriptions.size() == expectedRemainingSubscriptions
    }
  }

  private static void assertSubscriptionFailed(MQTT_IOClient mqttClient, String subscriptionTopic,
          Collection<String> failedTopics) {
    // Reconnects can report a failure more than once, so only check that this topic was rejected.
    assert failedTopics.contains(subscriptionTopic)
    synchronized (mqttClient.topicConsumerMap) {
      def subscription = mqttClient.topicConsumerMap.get(subscriptionTopic)
      if (mqttClient.isRemoveConsumersOnSubscriptionFailure()) {
        assert subscription == null
      } else {
        assert subscription != null
        assert subscription.isSubDone()
        assert subscription.isSubFailed()
        assert subscription.consumers.size() == 1
      }
    }
  }

  private void assertSubscribed(MQTT_IOClient mqttClient, String subscriptionTopic, String userId,
          Integer expectedSubscriptionCount = null) {
    assert mqttClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    synchronized (mqttClient.topicConsumerMap) {
      def subscription = mqttClient.topicConsumerMap.get(subscriptionTopic)
      assert subscription != null
      assert subscription.subAckFuture.getNow(false)
      assert subscription.consumers.size() == 1
    }
    def subscriptions = brokerSubscriptions(userId)
    assert subscriptions?.containsKey(subscriptionTopic)
    if (expectedSubscriptionCount != null) {
      assert subscriptions.size() == expectedSubscriptionCount
    }
  }

  private void assertReconnected(MQTT_IOClient mqttClient, String userId, RemotingConnection previousConnection) {
    assert mqttClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    def connections = mqttBrokerService.getUserConnections(userId)
    assert connections.size() == 1
    assert connections[0] !== previousConnection
  }

  /** Allow slow CI consumers to process last wills without hitting the independent cache expiry. */
  private static void widenDisconnectedConnectionWindow(MQTTBrokerService mqttBrokerService) {
    mqttBrokerService.@disconnectedConnectionCache = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build()
  }
}
