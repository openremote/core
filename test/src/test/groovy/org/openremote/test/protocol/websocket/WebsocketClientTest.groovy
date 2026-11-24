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
package org.openremote.test.protocol.websocket

import io.netty.channel.ChannelHandler
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.util.CharsetUtil
import org.apache.http.client.utils.URIBuilder
import org.openremote.agent.protocol.io.AbstractNettyIOClient
import org.openremote.agent.protocol.websocket.WebsocketIOClient
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.Asset
import org.openremote.model.asset.AssetEvent
import org.openremote.model.asset.AssetFilter
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.BuildingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.auth.OAuthPasswordGrant
import org.openremote.model.event.TriggeredEventSubscription
import org.openremote.model.event.shared.EventSubscription
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList

import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.Constants.MASTER_REALM_ADMIN_USER
import static org.openremote.model.util.MapAccess.getString

/**
 * This tests the {@link WebsocketIOClient} by connecting to the manager web socket API which means it also tests
 * the API itself
 */
class WebsocketClientTest extends Specification implements ManagerContainerTrait {

  protected static <T> T messageFromString(String message) {
    try {
      def isSubscription = message.startsWith(EventSubscription.SUBSCRIBED_MESSAGE_PREFIX)
      def isTriggered = !isSubscription && message.startsWith(TriggeredEventSubscription.MESSAGE_PREFIX)
      message = message.substring(message.indexOf(":")+1)
      return ValueUtil.JSON.readValue(message, isSubscription ? EventSubscription.class : isTriggered ? TriggeredEventSubscription.class : SharedEvent.class)
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to parse message")
    }
  }

  protected static String messageToString(String prefix, Object message) {
    try {
      String str = ValueUtil.asJSON(message).orElse(null)
      return prefix + str
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to serialize message")
    }
  }

  def "Check restricted client"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def agentService = container.getService(AgentService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "a simple Websocket client with restricted user"
    def client = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=building").build(),
            null,
            new OAuthPasswordGrant("http://127.0.0.1:8081/auth/realms/building/protocol/openid-connect/token",
            KEYCLOAK_CLIENT_ID,
            null,
            null,
            "testuser3",
            "testuser3"))
    client.setEncoderDecoderProvider({
      [
        new StringEncoder(CharsetUtil.UTF_8),
        new StringDecoder(CharsetUtil.UTF_8),
        new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client)
      ].toArray(new ChannelHandler[0])
    })

    and: "another Websocket client with another restricted user"
    def client2 = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=building").build(),
            null,
            new OAuthPasswordGrant("http://127.0.0.1:8081/auth/realms/building/protocol/openid-connect/token",
            KEYCLOAK_CLIENT_ID,
            null,
            null,
            "building",
            "building"))
    client2.setEncoderDecoderProvider({
      [
        new StringEncoder(CharsetUtil.UTF_8),
        new StringDecoder(CharsetUtil.UTF_8),
        new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client2)
      ].toArray(new ChannelHandler[0])
    })

    and: "another Websocket client with no credentials"
    def client3 = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=building").build(),
            null, null)
    client3.setEncoderDecoderProvider({
      [
        new StringEncoder(CharsetUtil.UTF_8),
        new StringDecoder(CharsetUtil.UTF_8),
        new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client3)
      ].toArray(new ChannelHandler[0])
    })

    and: "we add callback consumers to the clients"
    def connectionStatus = client.getConnectionStatus()
    def connectionStatus2 = client2.getConnectionStatus()
    def connectionStatus3 = client3.getConnectionStatus()
    List<Object> receivedMessages = new CopyOnWriteArrayList<>()
    List<Object> receivedMessages2 = new CopyOnWriteArrayList<>()
    List<Object> receivedMessages3 = new CopyOnWriteArrayList<>()
    client.addMessageConsumer({
      getLOG().info("Client1 message received: $it")
      receivedMessages.add(messageFromString(it))
    })
    client.addConnectionStatusConsumer({
      getLOG().info("Client1 status change: $it")
      connectionStatus = it
    })
    client2.addMessageConsumer({
      getLOG().info("Client2 message received: $it")
      receivedMessages2.add(messageFromString(it))
    })
    client2.addConnectionStatusConsumer({
      getLOG().info("Client2 status change: $it")
      connectionStatus2 = it
    })
    client3.addMessageConsumer({
      getLOG().info("Client3 message received: $it")
      receivedMessages3.add(messageFromString(it))
    })
    client3.addConnectionStatusConsumer({
      getLOG().info("Client3 status change: $it")
      connectionStatus3 = it
    })

    and: "the system settles down"
    noEventProcessedIn(assetProcessingService, 500)

    when: "we call connect on the clients"
    client.connect()
    client2.connect()
    client3.connect()

    then: "the clients status should become CONNECTED"
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
      assert connectionStatus == ConnectionStatus.CONNECTED
      assert client2.connectionStatus == ConnectionStatus.CONNECTED
      assert connectionStatus2 == ConnectionStatus.CONNECTED
      assert client3.connectionStatus == ConnectionStatus.CONNECTED
      assert connectionStatus3 == ConnectionStatus.CONNECTED
    }

    when: "we subscribe to attribute events produced by the server"
    client.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AttributeEvent.class,
                    null,
                    "attributes")))
    client2.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AttributeEvent.class,
                    null,
                    "attributes2")))
    client3.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AttributeEvent.class,
                    null,
                    "attributes3")))

    and: "we subscribe to asset events produced by the server"
    client.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AssetEvent.class,
                    null,
                    "assets")))
    client2.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AssetEvent.class,
                    null,
                    "assets2")))
    client3.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AssetEvent.class,
                    null,
                    "assets3")))

    then: "the server should confirm the subscriptions"
    conditions.eventually {
      assert receivedMessages.size() == 2
      assert receivedMessages.any {
        it instanceof EventSubscription && it.subscriptionId == "attributes"
      }
      assert receivedMessages.any {
        it instanceof EventSubscription && it.subscriptionId == "assets"
      }
      assert receivedMessages2.size() == 2
      assert receivedMessages2.any {
        it instanceof EventSubscription && it.subscriptionId == "attributes2"
      }
      assert receivedMessages2.any {
        it instanceof EventSubscription && it.subscriptionId == "assets2"
      }
      assert receivedMessages3.size() == 2
      assert receivedMessages3.any {
        it instanceof EventSubscription && it.subscriptionId == "attributes3"
      }
      assert receivedMessages3.any {
        it instanceof EventSubscription && it.subscriptionId == "assets3"
      }
    }

    when: "apartment 1 living room temp changes"
    receivedMessages.clear()
    receivedMessages2.clear()
    receivedMessages3.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature", 5))

    then: "the testuser3 client receives the event"
    conditions.eventually {
      assert receivedMessages.size() == 1
      TriggeredEventSubscription triggeredEvent = receivedMessages[0] as TriggeredEventSubscription
      assert triggeredEvent.subscriptionId == "attributes"
      assert triggeredEvent.events.size() == 1
      assert ((AttributeEvent) triggeredEvent.events[0]).id == managerTestSetup.apartment1LivingroomId
      assert ((AttributeEvent) triggeredEvent.events[0]).name == "targetTemperature"
      assert ((AttributeEvent) triggeredEvent.events[0]).value.orElse(0) == 5
    }

    then: "the building user should not have received the event"
    conditions.eventually {
      assert receivedMessages2.isEmpty()
    }

    and: "the anonymous user should not have received the event"
    conditions.eventually {
      assert receivedMessages3.isEmpty()
    }

    when: "apartment 2 living room temp changes"
    receivedMessages.clear()
    receivedMessages2.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment2LivingroomId, "targetTemperature", 15))

    then: "the building client receives the event"
    conditions.eventually {
      assert receivedMessages2.size() == 1
      TriggeredEventSubscription triggeredEvent = receivedMessages2[0] as TriggeredEventSubscription
      assert triggeredEvent.subscriptionId == "attributes2"
      assert triggeredEvent.events.size() == 1
      assert ((AttributeEvent) triggeredEvent.events[0]).id == managerTestSetup.apartment2LivingroomId
      assert ((AttributeEvent) triggeredEvent.events[0]).name == "targetTemperature"
      assert ((AttributeEvent) triggeredEvent.events[0]).value.orElse(0) == 15
    }

    then: "the testuser3 user should not have received the event"
    assert receivedMessages.isEmpty()

    when: "apartment 1 living room asset is modified"
    receivedMessages.clear()
    receivedMessages2.clear()
    receivedMessages3.clear()
    def apartment1Livingroom = assetStorageService.find(managerTestSetup.apartment1LivingroomId)
    apartment1Livingroom.addAttributes(
            new Attribute<>("testAttribute", ValueType.BOOLEAN).addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ, false),
                    new MetaItem<>(MetaItemType.ACCESS_PUBLIC_READ, false)
                    )
    )
    apartment1Livingroom.getAttribute(Asset.NOTES).get().addOrReplaceMeta(
            new MetaItem<>(MetaItemType.ACCESS_PUBLIC_READ)
    )
    apartment1Livingroom = assetStorageService.merge(apartment1Livingroom)

    then: "the testuser3 client should be notified with only accessible attributes"
    conditions.eventually {
      assert receivedMessages.size() == 2
      def attrSub = receivedMessages.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "attributes"
      } as TriggeredEventSubscription<AttributeEvent>
      def assetSub = receivedMessages.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "assets"
      } as TriggeredEventSubscription<AssetEvent>
      assert assetSub != null
      assert assetSub.events.size() == 1
      assert assetSub.events.get(0).id == managerTestSetup.apartment1LivingroomId
      assert assetSub.events.get(0).attributeNames.size() == 8
      assert !assetSub.events.get(0).attributeNames.contains("testAttribute")
      assert attrSub != null
      assert attrSub.events.size() == 1
      assert attrSub.events.get(0).id == managerTestSetup.apartment1LivingroomId
      assert attrSub.events.get(0).name == Asset.NOTES.name
    }

    and: "the anonymous client should be notified with only accessible attributes"
    conditions.eventually {
      assert receivedMessages3.size() == 2
      def attrSub = receivedMessages3.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "attributes3"
      } as TriggeredEventSubscription<AttributeEvent>
      def assetSub = receivedMessages3.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "assets3"
      } as TriggeredEventSubscription<AssetEvent>
      assert assetSub != null
      assert assetSub.events.size() == 1
      assert assetSub.events.get(0).id == managerTestSetup.apartment1LivingroomId
      assert assetSub.events.get(0).attributeNames.size() == 1
      assert assetSub.events.get(0).attributeNames.contains(Asset.NOTES.name)
      assert attrSub != null
      assert attrSub.events.size() == 1
      assert attrSub.events.get(0).id == managerTestSetup.apartment1LivingroomId
      assert attrSub.events.get(0).name == Asset.NOTES.name
    }

    and: "the building user should not have received the event"
    assert receivedMessages2.isEmpty()

    when: "apartment 1 asset is modified"
    receivedMessages.clear()
    receivedMessages2.clear()
    receivedMessages3.clear()
    def apartment1 = assetStorageService.find(managerTestSetup.apartment1Id)
    apartment1.getAttribute(Asset.LOCATION).get().setValue(new GeoJSONPoint(10, 10))
    apartment1.getAttribute(BuildingAsset.STREET).get().setValue("Test Street")
    apartment1 = assetStorageService.merge(apartment1)

    then: "the testuser3 client should be notified with only accessible attributes"
    conditions.eventually {
      assert receivedMessages.size() == 3
      def attr1Sub = receivedMessages.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "attributes" && it.events.any {
          ((AttributeEvent)it).ref.name == BuildingAsset.STREET.name
        }
      } as TriggeredEventSubscription<AttributeEvent>
      def attr2Sub = receivedMessages.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "attributes" && it.events.any {
          ((AttributeEvent)it).ref.name == Asset.LOCATION.name
        }
      } as TriggeredEventSubscription<AttributeEvent>
      def assetSub = receivedMessages.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "assets"
      } as TriggeredEventSubscription<AssetEvent>
      assert assetSub != null
      assert assetSub.events.size() == 1
      assert assetSub.events.get(0).id == managerTestSetup.apartment1Id
      assert assetSub.events.get(0).attributeNames.size() == 8
      assert assetSub.events.get(0).attributeNames.contains(BuildingAsset.STREET.name)
      assert attr1Sub != null
      assert attr1Sub.events.size() == 1
      assert attr1Sub.events.get(0).id == managerTestSetup.apartment1Id
      assert attr1Sub.events.get(0).name == BuildingAsset.STREET.name
      assert attr2Sub != null
      assert attr2Sub.events.size() == 1
      assert attr2Sub.events.get(0).id == managerTestSetup.apartment1Id
      assert attr2Sub.events.get(0).name == Asset.LOCATION.name
    }

    then: "the anonymous client should be notified with only accessible attributes"
    conditions.eventually {
      assert receivedMessages3.size() == 2
      def attrSub = receivedMessages3.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "attributes3"
      } as TriggeredEventSubscription<AttributeEvent>
      def assetSub = receivedMessages3.find{
        it instanceof TriggeredEventSubscription && it.subscriptionId == "assets3"
      } as TriggeredEventSubscription<AssetEvent>
      assert assetSub != null
      assert assetSub.events.size() == 1
      assert assetSub.events.get(0).id == managerTestSetup.apartment1Id
      assert assetSub.events.get(0).attributeNames.size() == 1
      assert assetSub.events.get(0).attributeNames.contains(Asset.LOCATION.name)
      assert attrSub != null
      assert attrSub.events.size() == 1
      assert attrSub.events.get(0).id == managerTestSetup.apartment1Id
      assert attrSub.events.get(0).name == BuildingAsset.LOCATION.name
    }

    and: "the building user should not have received the event"
    assert receivedMessages2.isEmpty()

    cleanup: "the server should be stopped"
    if (client != null) {
      client.disconnect()
    }
    if (client2 != null) {
      client2.disconnect()
    }
    if (client3 != null) {
      client3.disconnect()
    }
  }

  def "Check client"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 20, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def agentService = container.getService(AgentService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "a simple Websocket client"
    def client = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=master").build(),
            null,
            new OAuthPasswordGrant("http://127.0.0.1:8081/auth/realms/master/protocol/openid-connect/token",
            KEYCLOAK_CLIENT_ID,
            null,
            null,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)))
    client.setEncoderDecoderProvider({
      [new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client)].toArray(new ChannelHandler[0])
    })

    and: "we add callback consumers to the client"
    def connectionStatus = client.getConnectionStatus()
    List<String> receivedMessages = new CopyOnWriteArrayList<>()
    client.addMessageConsumer(
            message -> receivedMessages.add(messageFromString(message))
    )
    client.addConnectionStatusConsumer({ status ->
      connectionStatus = status
    })

    and: "the system settles down"
    noEventProcessedIn(assetProcessingService, 500)

    when: "we call connect on the client"
    client.connect()

    then: "the client status should become CONNECTED"
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
      assert connectionStatus == ConnectionStatus.CONNECTED
    }

    // TODO: Remove this once client supports some better connection logic
    and: "some time passes to allow the connection to be fully initialized"
    sleep(3000)

    when: "we subscribe to attribute events produced by the server"
    client.sendMessage(messageToString(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
            new EventSubscription(
                    AttributeEvent.class,
                    new AssetFilter<AttributeEvent>().setAssetIds(managerTestSetup.apartment1LivingroomId),
                    "1")))

    then: "the server should return a subscribed event"
    conditions.eventually {
      assert receivedMessages.size() == 1
      EventSubscription subscription = receivedMessages[0] as EventSubscription
      assert subscription != null
      assert subscription.subscriptionId == "1"
    }

    when: "when apartment 1 living room temp changes"
    receivedMessages.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature", 5))

    then: "the client receives the event"
    conditions.eventually {
      assert receivedMessages.size() == 1
      TriggeredEventSubscription triggeredEvent = receivedMessages[0] as TriggeredEventSubscription
      assert triggeredEvent.subscriptionId == "1"
      assert triggeredEvent.events.size() == 1
      assert ((AttributeEvent)triggeredEvent.events[0]).id == managerTestSetup.apartment1LivingroomId
      assert ((AttributeEvent)triggeredEvent.events[0]).name == "targetTemperature"
      assert ((AttributeEvent)triggeredEvent.events[0]).value.orElse(0) == 5
    }

    when: "when apartment 1 living room temp is set to the same value again"
    receivedMessages.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature", 5))

    then: "the client should receive the event"
    conditions.eventually {
      assert receivedMessages.size() == 1
      TriggeredEventSubscription triggeredEvent = receivedMessages[0] as TriggeredEventSubscription
      assert triggeredEvent.subscriptionId == "1"
      assert triggeredEvent.events.size() == 1
      assert ((AttributeEvent)triggeredEvent.events[0]).id == managerTestSetup.apartment1LivingroomId
      assert ((AttributeEvent)triggeredEvent.events[0]).name == "targetTemperature"
      assert ((AttributeEvent)triggeredEvent.events[0]).value.orElse(0) == 5
    }

    when: "when apartment 1 living room temp is set to null"
    receivedMessages.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature", null))

    then: "the client receives the event"
    conditions.eventually {
      assert receivedMessages.size() == 1
      TriggeredEventSubscription triggeredEvent = receivedMessages[0] as TriggeredEventSubscription
      assert triggeredEvent.subscriptionId == "1"
      assert triggeredEvent.events.size() == 1
      assert ((AttributeEvent)triggeredEvent.events[0]).id == managerTestSetup.apartment1LivingroomId
      assert ((AttributeEvent)triggeredEvent.events[0]).name == "targetTemperature"
      assert !((AttributeEvent)triggeredEvent.events[0]).value.isPresent()
    }

    when: "when apartment 1 bathroom temp changes"
    receivedMessages.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment1BathroomId, "targetTemperature", 10))

    then: "the bathroom target temp should have changed"
    conditions.eventually {
      def bathroom = assetStorageService.find(managerTestSetup.apartment1BathroomId)
      assert bathroom.getAttribute("targetTemperature").flatMap{it.value}.orElse(0d) == 10d
    }

    and: "the client should not have received the event"
    conditions.eventually {
      assert receivedMessages.isEmpty()
    }

    cleanup: "the server should be stopped"
    if (client != null) {
      client.disconnect()
    }
  }
}
