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
package org.openremote.test.gateway

import io.netty.channel.ChannelHandler
import jakarta.ws.rs.WebApplicationException
import org.apache.http.client.utils.URIBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyClient
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget
import org.openremote.agent.protocol.http.HTTPAgent
import org.openremote.agent.protocol.http.HTTPAgentLink
import org.openremote.agent.protocol.io.AbstractNettyIOClient
import org.openremote.agent.protocol.websocket.WebsocketIOClient
import org.openremote.container.Container
import org.openremote.container.timer.TimerService
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.event.ClientEventService
import org.openremote.manager.gateway.*
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.security.ManagerKeycloakIdentityProvider
import org.openremote.manager.setup.SetupService
import org.openremote.manager.system.VersionInfo
import org.openremote.model.Constants
import org.openremote.model.asset.*
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.*
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.attribute.MetaMap
import org.openremote.model.auth.OAuthClientCredentialsGrant
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.gateway.*
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.security.User
import org.openremote.model.system.StatusResource
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.ValueFormat
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.net.ssl.SSLSession
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.stream.Collectors
import java.util.stream.IntStream

import static org.openremote.manager.gateway.GatewayConnector.mapAssetId
import static org.openremote.manager.gateway.GatewayService.getGatewayClientId
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.util.MapAccess.getString
import static org.openremote.model.util.TextUtil.isNullOrEmpty
import static org.openremote.model.value.MetaItemType.*
import static org.openremote.model.value.ValueType.*

class GatewayTest extends Specification implements ManagerContainerTrait {

  @Shared
  Map<String, GatewayDisconnectEvent.Reason> gatewayDisconnectReasons = new ConcurrentHashMap<>()

  def setupSpec() {
    // Mock the GatewayClientService so we can use a different port for authentication on the GatewayClientConnector
    def services = defaultServices().toList()
    def gatewayClientServiceIndex = services.findIndexOf {it instanceof GatewayClientService}
    def gatewayClientService = services.removeAt(gatewayClientServiceIndex) as GatewayClientService
    def spyGatewayClientService = Spy(gatewayClientService)
    services.add(gatewayClientServiceIndex, spyGatewayClientService) // Must go back in the correct position as already sorted by priority

    spyGatewayClientService.createClientConnector(_ as GatewayConnection) >> { GatewayConnection connection ->
      def identityProvider = container.getService(ManagerIdentityService.class).identityProvider as ManagerKeycloakIdentityProvider
      def keycloakURL = identityProvider.getKeycloakPublicUrl()
      // Use keycloaks public URI as it's on a different port to the manager under test
      connection.@tokenEndpointURI = new URIBuilder(keycloakURL).setPath("auth/realms/" + connection.getRealm() + "/protocol/openid-connect/token").build().toString()
      callRealMethod()
    }

    // Observe gateway rejections before the asynchronous disconnect message races with socket closure.
    def clientEventServiceIndex = services.findIndexOf {it instanceof ClientEventService}
    def clientEventService = services.removeAt(clientEventServiceIndex) as ClientEventService
    def spyClientEventService = Spy(clientEventService)
    services.add(clientEventServiceIndex, spyClientEventService)
    spyClientEventService.sendToWebsocketSession(_ as String, _ as GatewayDisconnectEvent) >> { String sessionId, GatewayDisconnectEvent event ->
      callRealMethod()
      gatewayDisconnectReasons.put(sessionId, event.reason)
    }

    // Start the container with the spy services
    startContainer(defaultConfig(), services)
  }

  def "Gateway asset provisioning and local manager logic test"() {

    given: "some polling conditions and services from the container"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def timerService = container.getService(TimerService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def agentService = container.getService(AgentService.class)
    def gatewayService = container.getService(GatewayService.class)
    def clientEventService = container.getService(ClientEventService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).identityProvider as ManagerKeycloakIdentityProvider

    expect: "the system should settle down"
    conditions.eventually {
      assert noEventProcessedIn(assetProcessingService, 300)
    }

    when: "a gateway is provisioned in this manager"
    GatewayAsset gateway = assetStorageService.merge(new GatewayAsset("Test gateway")
            .setRealm(managerTestSetup.realmBuildingName))

    then: "a keycloak client should have been created for this gateway"
    conditions.eventually {
      def client = identityProvider.getClient(managerTestSetup.realmBuildingName, getGatewayClientId(gateway.getId()))
      assert client != null
    }

    and: "a set of credentials should have been created for this gateway and be stored against the gateway for easy reference"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId(), true) as GatewayAsset
      assert !isNullOrEmpty(gateway.getClientId().orElse(""))
      assert !isNullOrEmpty(gateway.getClientSecret().orElse(""))
    }

    and: "a gateway connector should have been created for this gateway"
    conditions.eventually {
      assert gatewayService.gatewayConnectorMap.size() == 1
      assert gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT)).gatewayId == gateway.getId()
    }

    when: "the gateway service user credentials are used to try and access the asset resources"
    def accessToken = authenticate(
            container,
            managerTestSetup.realmBuildingName,
            gateway.getClientId().orElse(""),
            gateway.getClientSecret().orElse("")
    )
    def assetResource = getClientApiTarget(serverUri(serverPort), managerTestSetup.realmBuildingName, accessToken).proxy(AssetResource.class)

    and: "the realm assets are requested"
    def realmAssets = assetResource.queryAssets(null, null)

    then: "an exception should be thrown"
    WebApplicationException ex = thrown()
    ex.response.withCloseable {
      assert ex.response.status == 403
      true
    }

    when: "the Gateway client is created"
    def gatewayClient = new WebsocketIOClient(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=$managerTestSetup.realmBuildingName").build(),
            null,
            new OAuthClientCredentialsGrant("http://127.0.0.1:8081/auth/realms/$managerTestSetup.realmBuildingName/protocol/openid-connect/token",
            gateway.getClientId().orElse(""),
            gateway.getClientSecret().orElse(""),
            null).setBasicAuthHeader(true))
    gatewayClient.setEncoderDecoderProvider({
      [new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, gatewayClient)].toArray(new ChannelHandler[0])
    })

    and: "we add callback consumers to the client"
    def connectionStatus = gatewayClient.getConnectionStatus()
    List<String> clientReceivedMessages = new CopyOnWriteArrayList<>()
    gatewayClient.addMessageConsumer({message ->
      getLOG().info("Message received from central manager: $message")
      clientReceivedMessages.add(message as String)
    })
    gatewayClient.addConnectionStatusConsumer({status ->
      getLOG().info("Gateway client connection staus changed: $status")
      connectionStatus = status
    })

    and: "the gateway connects to this manager"
    gatewayClient.connect()

    then: "the gateway netty client status should become CONNECTED"
    conditions.eventually {
      assert connectionStatus == ConnectionStatus.CONNECTED
      assert gatewayClient.connectionStatus == ConnectionStatus.CONNECTED
    }

    and: "the gateway asset connection status should be CONNECTING"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId()) as GatewayAsset
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTING
    }

    and: "the server should have sent an init start message"
    conditions.eventually {
      assert clientReceivedMessages.size() >= 1
      def request = ValueUtil.JSON.readValue(clientReceivedMessages[0].substring(SharedEvent.MESSAGE_PREFIX.length()), GatewayInitStartEvent.class)
      assert request.version == VersionInfo.getGatewayApiVersion()
      assert request.activeTunnels == null
    }

    when: "the gateway client assets are defined"
    List<String> agentAssetIds = new CopyOnWriteArrayList<>()
    List<HTTPAgent> agentAssets = new CopyOnWriteArrayList<>()
    List<String> assetIds = new CopyOnWriteArrayList<>()
    List<Asset> assets = new CopyOnWriteArrayList<>()

    IntStream.rangeClosed(1, 5).forEach { i ->
      agentAssetIds.add(UniqueIdentifierGenerator.generateId("Test Agent $i"))
      def agent = new HTTPAgent("Test Agent $i")
              .setId(agentAssetIds[i - 1])
              .setBaseURI("https://google.co.uk")
              .setRealm(MASTER_REALM)
              .setCreatedOn(timerService.getNow())
      agent.path = (String[]) [agentAssetIds[i - 1]].toArray(new String[0])

      agentAssets.add(agent)

      assetIds.add(UniqueIdentifierGenerator.generateId("Test Building $i"))

      // Add assets out of order to test gateway connector re-ordering logic
      IntStream.rangeClosed(1, 4).forEach { j ->

        assetIds.add(UniqueIdentifierGenerator.generateId("Test Building $i Room $j"))

        def roomAsset = new RoomAsset("Test Building $i Room $j")
                .setId(assetIds[(i - 1) * 5 + j])
                .setCreatedOn(timerService.getNow())
                .setParentId(assetIds[(i - 1) * 5])
                .setRealm(MASTER_REALM)

        roomAsset.path = (String[]) [assetIds[(i - 1) * 5 + j], assetIds[(i - 1) * 5]].toArray(new String[0])

        roomAsset.addOrReplaceAttributes(
                new Attribute<>(Asset.LOCATION, new GeoJSONPoint(10, 11)).addOrReplaceMeta(
                        new MetaItem<>(ACCESS_PUBLIC_READ)
                        ),
                new Attribute<>("temp", NUMBER).addOrReplaceMeta(
                        new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[i - 1])
                        .setPath("")),
                        new MetaItem<>(UNITS, Constants.units(UNITS_CELSIUS))
                        ),
                new Attribute<>("tempSetpoint", NUMBER).addMeta(
                        new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[i - 1])
                        .setPath("")),
                        new MetaItem<>(UNITS, Constants.units(UNITS_CELSIUS))
                        )
        )

        assets.add(roomAsset)
      }

      def buildingAsset = new BuildingAsset("Test Building $i")
              .setId(assetIds[(i - 1) * 5])
              .setCreatedOn(timerService.getNow())
              .setRealm(MASTER_REALM)

      buildingAsset.path = (String[]) [assetIds[(i - 1) * 5]].toArray(new String[0])

      buildingAsset.addOrReplaceAttributes(
              new Attribute<>(Asset.LOCATION, new GeoJSONPoint(10, 11)).addMeta(
                      new MetaItem<>(ACCESS_PUBLIC_READ)
                      )
      )

      assets.add(buildingAsset)
    }

    and: "the gateway client replies to the central manager with the assets of the gateway"
    List<Asset> sendAssets = new CopyOnWriteArrayList<>()
    sendAssets.addAll(agentAssets)
    sendAssets.addAll(assets)
    def readAssetsReplyEvent = new AssetsEvent(sendAssets)
    readAssetsReplyEvent.setMessageID(GatewayConnector.ASSET_READ_EVENT_NAME_INITIAL)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(readAssetsReplyEvent).get())

    then: "the central manager should have requested the full loading of the first batch of assets"
    String messageId = null
    ReadAssetsEvent readAssetsEvent = null
    conditions.eventually {
      def readEventStr = clientReceivedMessages.find {
        it.contains("read-assets") && it.contains(GatewayConnector.ASSET_READ_EVENT_NAME_BATCH + "0")
      }
      assert readEventStr != null
      readAssetsEvent = ValueUtil.JSON.readValue(readEventStr.substring(SharedEvent.MESSAGE_PREFIX.length()), ReadAssetsEvent.class)
      messageId = readAssetsEvent.messageID
      assert readAssetsEvent.messageID == GatewayConnector.ASSET_READ_EVENT_NAME_BATCH + "0"
      assert readAssetsEvent.assetQuery != null
      assert readAssetsEvent.assetQuery.ids != null
      assert readAssetsEvent.assetQuery.ids.length == GatewayConnector.SYNC_ASSET_BATCH_SIZE
      assert agentAssetIds.stream().filter {
        readAssetsEvent.assetQuery.ids.contains(it)
      }.count() == agentAssetIds.size()
      assert assetIds.stream().filter {
        readAssetsEvent.assetQuery.ids.contains(it)
      }.count() == GatewayConnector.SYNC_ASSET_BATCH_SIZE - agentAssetIds.size()
    }

    when: "the gateway returns the requested assets"
    sendAssets = new CopyOnWriteArrayList<>()
    sendAssets.addAll(agentAssets)
    sendAssets.addAll(Arrays.stream(readAssetsEvent.assetQuery.ids).filter {
      !agentAssetIds.contains(it)
    }.map { id ->
      assets.stream().filter { asset ->
        asset.id == id
      }.findFirst().orElse(null)
    }.collect(Collectors.toList()))
    readAssetsReplyEvent = new AssetsEvent(sendAssets)
    readAssetsReplyEvent.setMessageID(messageId)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(readAssetsReplyEvent).get())

    then: "the central manager should have added the first batch of assets under the gateway asset"
    conditions.eventually {
      def syncedAssets = assetStorageService.findAll(new AssetQuery().parents(gateway.getId()).recursive(true))
      assert syncedAssets.size() == sendAssets.size()
      assert syncedAssets.stream().filter { syncedAsset ->
        sendAssets.stream().anyMatch {
          mapAssetId(gateway.getId(), it.id, false) == syncedAsset.id
        }
      }.count() == sendAssets.size()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[0]
      }.getName() == "Test Agent 1"
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[0]
      }.getType() == HTTPAgent.DESCRIPTOR.name
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[0]
      }.getRealm() == managerTestSetup.realmBuildingName
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[0]
      }.getParentId() == gateway.getId()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[4]
      }.getName() == "Test Agent 5"
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[4]
      }.getType() == HTTPAgent.DESCRIPTOR.name
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[4]
      }.getRealm() == managerTestSetup.realmBuildingName
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == agentAssetIds[4]
      }.getParentId() == gateway.getId()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getName() == "Test Building 1"
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getType() == BuildingAsset.DESCRIPTOR.name
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getRealm() == managerTestSetup.realmBuildingName
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getParentId() == gateway.getId()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getLocation().get().x == 10
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getLocation().get().y == 11
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[0]
      }.getAttribute(Asset.LOCATION).flatMap{
        it.getMetaItem(ACCESS_PUBLIC_READ)
      }.isPresent()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[1]
      }.getName() == "Test Building 1 Room 1"
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[1]
      }.getType() == RoomAsset.DESCRIPTOR.getName()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[1]
      }.getRealm() == managerTestSetup.realmBuildingName
      assert mapAssetId(gateway.getId(), syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[1]
      }.getParentId(), true) == assetIds[0]
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[1]
      }.getAttribute("temp").map{
        it.hasMeta(AGENT_LINK)
      }.orElse(false)
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[1]
      }.getAttribute("tempSetpoint").map{
        it.hasMeta(AGENT_LINK)
      }.orElse(false)
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getName() == "Test Building 2"
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getType() == BuildingAsset.DESCRIPTOR.getName()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getRealm() == managerTestSetup.realmBuildingName
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getParentId() == gateway.getId()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getLocation().get().x == 10
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getLocation().get().y == 11
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[5]
      }.getAttribute(Asset.LOCATION).flatMap{
        it.getMetaItem(ACCESS_PUBLIC_READ)
      }.isPresent()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[6]
      }.getName() == "Test Building 2 Room 1"
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[6]
      }.getType() == RoomAsset.DESCRIPTOR.getName()
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[6]
      }.getRealm() == managerTestSetup.realmBuildingName
      assert mapAssetId(gateway.getId(), syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[6]
      }.getParentId(), true) == assetIds[5]
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[6]
      }.getAttribute("temp").map{
        it.hasMeta(AGENT_LINK)
      }.orElse(false)
      assert syncedAssets.find {
        mapAssetId(gateway.getId(), it.id, true) == assetIds[6]
      }.getAttribute("tempSetpoint").map{
        it.hasMeta(AGENT_LINK)
      }.orElse(false)
    }

    and: "the central manager should have requested the full loading of the second batch of assets"
    conditions.eventually {
      def readEventStr = clientReceivedMessages.find {
        it.contains("read-assets") && it.contains(GatewayConnector.ASSET_READ_EVENT_NAME_BATCH + GatewayConnector.SYNC_ASSET_BATCH_SIZE)
      }
      assert readEventStr != null
      readAssetsEvent = ValueUtil.JSON.readValue(readEventStr.substring(SharedEvent.MESSAGE_PREFIX.length()), ReadAssetsEvent.class)
      messageId = readAssetsEvent.messageID
      assert readAssetsEvent.messageID == GatewayConnector.ASSET_READ_EVENT_NAME_BATCH + GatewayConnector.SYNC_ASSET_BATCH_SIZE
      assert readAssetsEvent.assetQuery != null
      assert readAssetsEvent.assetQuery.ids != null
      assert readAssetsEvent.assetQuery.ids.length == agentAssetIds.size() + assetIds.size() - GatewayConnector.SYNC_ASSET_BATCH_SIZE
      assert assetIds.stream().filter { id ->
        sendAssets.stream().noneMatch {
          it.id == id
        }
      }.count() == readAssetsEvent.assetQuery.ids.length
    }

    when: "the gateway returns the requested assets"
    sendAssets = new CopyOnWriteArrayList<>()
    sendAssets.addAll(Arrays.stream(readAssetsEvent.assetQuery.ids).map { id ->
      assets.stream().filter { asset ->
        asset.id == id
      }.findFirst().orElse(null)
    }.collect(Collectors.toList()))
    readAssetsReplyEvent = new AssetsEvent(sendAssets)
    readAssetsReplyEvent.setMessageID(messageId)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(readAssetsReplyEvent).get())

    then: "the central manager should have requested the capabilities of the gateway"
    conditions.eventually {
      def capabilitiesEventStr = clientReceivedMessages.find {
        it.contains(GatewayCapabilitiesRequestEvent.TYPE)
      }
      def gatewayCapabilitiesRequest = ValueUtil.JSON.readValue(capabilitiesEventStr.substring(SharedEvent.MESSAGE_PREFIX.length()), GatewayCapabilitiesRequestEvent.class)
      messageId = gatewayCapabilitiesRequest.messageID
    }

    when: "the gateway returns the capabilities"
    def capabilitiesReplyEvent = new GatewayCapabilitiesResponseEvent(VersionInfo.getGatewayApiVersion(), true)
    capabilitiesReplyEvent.setMessageID(messageId)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(capabilitiesReplyEvent).get())

    then: "the gateway connector capabilities should be updated"
    conditions.eventually {
      def gatewayConnector = gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT))
      assert gatewayConnector.isConnected()
      assert !gatewayConnector.isInitialSyncInProgress()
      assert gatewayConnector.isTunnellingSupported()
    }

    and: "the central manager should have responded to indicate sync is done"
    conditions.eventually {
      def initDoneEventStr = clientReceivedMessages.find {it.contains(GatewayInitDoneEvent.TYPE)}
      def gatewayInitDoneEvent = ValueUtil.JSON.readValue(initDoneEventStr.substring(SharedEvent.MESSAGE_PREFIX.length()), GatewayInitDoneEvent.class)
      assert gatewayInitDoneEvent != null
    }

    and: "the gateway asset connection status should be CONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId()) as GatewayAsset
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
    }

    and: "all the gateway assets should be replicated underneath the gateway"
    conditions.eventually {
      assert assetStorageService.findAll(new AssetQuery().parents(gateway.getId()).recursive(true)).size() == agentAssets.size() + assets.size()
    }

    and: "the http client protocol of the gateway agents should not have been linked to the central manager"
    Thread.sleep(500)
    conditions.eventually {
      assert !agentService.protocolInstanceMap.containsKey(agentAssetIds[0])
      assert !agentService.agents.containsKey(agentAssetIds[0])
      assert !agentService.protocolInstanceMap.containsKey(agentAssetIds[4])
      assert !agentService.agents.containsKey(agentAssetIds[4])
    }

    when: "the previously received messages are cleared"
    clientReceivedMessages.clear()

    and: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "an attribute event for a gateway descendant asset (building 1 room 1) is sent to the local manager"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(mapAssetId(gateway.id, assetIds[1], false), "tempSetpoint", 20d))

    then: "the event should have been forwarded to the gateway"
    conditions.eventually {
      assert clientReceivedMessages.size() == 1
      assert clientReceivedMessages.get(0).startsWith(SharedEvent.MESSAGE_PREFIX)
      assert clientReceivedMessages.get(0).contains("attribute")
    }

    when: "the gateway handles the forwarded attribute event and sends a follow up attribute event to the local manager"
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(new AttributeEvent(assetIds[1], "tempSetpoint", 20d)).get())

    then: "the descendant asset in the local manager should contain the new attribute value"
    conditions.eventually {
      def building1Room1Asset = assetStorageService.find(mapAssetId(gateway.id, assetIds[1], false))
      assert building1Room1Asset.getAttribute("tempSetpoint").flatMap {
        it.getValue()
      }.orElse(0d) == 20d
    }

    when: "an asset is added on the gateway and the local manager is notified"
    def building1Room5AssetId = UniqueIdentifierGenerator.generateId("Test Building 1 Room 5")
    def building1Room5Asset = new RoomAsset("Test Building 1 Room 5")
            .setId(building1Room5AssetId)
            .setCreatedOn(timerService.getNow())
            .setParentId(assetIds[0])
            .setRealm(MASTER_REALM)
    building1Room5Asset.path = (String[]) [building1Room5AssetId, assetIds[0]]

    building1Room5Asset.addOrReplaceAttributes(
            new Attribute<>(Asset.LOCATION, new GeoJSONPoint(10, 11)).addOrReplaceMeta(
                    new MetaItem<>(ACCESS_PUBLIC_READ)
                    ),
            new Attribute<>("temp", NUMBER).addOrReplaceMeta(
                    new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[0])
                    .setPath("")),
                    new MetaItem<>(UNITS, Constants.units(UNITS_CELSIUS))
                    ),
            new Attribute<>("tempSetpoint", NUMBER).addMeta(
                    new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[0])
                    .setPath("")),
                    new MetaItem<>(UNITS, Constants.units(UNITS_CELSIUS))
                    )
    )

    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(new AssetEvent(AssetEvent.Cause.CREATE, building1Room5Asset, null)).get())

    then: "the asset should be replicated in the local manager"
    RoomAsset localBuilding1Room5Asset
    conditions.eventually {
      localBuilding1Room5Asset = assetStorageService.find(mapAssetId(gateway.id, building1Room5AssetId, false)) as RoomAsset
      assert localBuilding1Room5Asset != null
      assert localBuilding1Room5Asset.getName() == "Test Building 1 Room 5"
      assert localBuilding1Room5Asset.getType() == RoomAsset.DESCRIPTOR.getName()
      assert localBuilding1Room5Asset.getRealm() == managerTestSetup.realmBuildingName
      assert localBuilding1Room5Asset.getParentId() == mapAssetId(gateway.id, assetIds[0], false)
      assert localBuilding1Room5Asset.getAttributes().size() == 6
    }

    when: "an asset is modified on the gateway and the local manager is notified"
    building1Room5Asset.setName("Test Building 1 Room 5 Updated")
    building1Room5Asset.setVersion(1)
    building1Room5Asset.addAttributes(
            new Attribute<>("co2Level", POSITIVE_INTEGER, 500)
            .addMeta(
                    new MetaItem<>(UNITS, Constants.units(UNITS_PART_PER_MILLION)),
                    new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[0]).setPath(""))
                    )
    )
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(new AssetEvent(AssetEvent.Cause.UPDATE, building1Room5Asset, (String[]) ["name", "attributes"].toArray(new String[0]))).get())

    then: "the asset should also be updated in the local manager"
    conditions.eventually {
      localBuilding1Room5Asset = assetStorageService.find(mapAssetId(gateway.id, building1Room5AssetId, false))
      assert localBuilding1Room5Asset != null
      assert localBuilding1Room5Asset.getVersion() == 1
      assert localBuilding1Room5Asset.getName() == "Test Building 1 Room 5 Updated"
      assert localBuilding1Room5Asset.getType() == RoomAsset.DESCRIPTOR.getName()
      assert localBuilding1Room5Asset.getRealm() == managerTestSetup.realmBuildingName
      assert localBuilding1Room5Asset.getParentId() == mapAssetId(gateway.id, assetIds[0], false)
      assert localBuilding1Room5Asset.getAttributes().size() == 7
      assert localBuilding1Room5Asset.getAttribute("co2Level").flatMap {
        it.getValue()
      }.orElse(0i) == 500i
    }

    when: "an asset is deleted on the gateway and the local manager is notified"
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(new AssetEvent(AssetEvent.Cause.DELETE, building1Room5Asset, null)).get())

    then: "the asset should also be deleted in the local manager"
    conditions.eventually {
      def asset = assetStorageService.find(mapAssetId(gateway.id, building1Room5AssetId, false))
      assert asset == null
    }

    when: "the client received messages are cleared"
    clientReceivedMessages.clear()

    and: "an attempt is made to add a child asset to the gateway in the local manager"
    assetStorageService.merge(localBuilding1Room5Asset)

    then: "an exception should be thrown"
    thrown(IllegalStateException)

    when: "the client received messages are cleared"
    clientReceivedMessages.clear()

    and: "an attempt is made to modify a child asset of the gateway in the local manager"
    localBuilding1Room5Asset.setName("Test Building 1 Room 5")
    localBuilding1Room5Asset.getAttributes().remove("co2Level")
    localBuilding1Room5Asset = assetStorageService.merge(localBuilding1Room5Asset)

    then: "an exception should be thrown"
    thrown(IllegalStateException)

    when: "an attempt is made to delete a child asset of the gateway in the local manager"
    assetStorageService.delete([mapAssetId(gateway.id, assetIds[0], false)])

    then: "an exception should be thrown"
    thrown(IllegalStateException)

    then: "the asset should not have been deleted"
    assert assetStorageService.find(mapAssetId(gateway.id, assetIds[0], false)) != null

    when: "a rogue client user is provisioned in another realm with the gateway ID from the building realm"
    def rogueClientId = getGatewayClientId(gateway.getId())
    def secret = UUID.randomUUID().toString()
    def rogueGatewayUser =
            identityProvider.createUpdateUser(
            managerTestSetup.realmCityName,
            new User()
            .setServiceAccount(true)
            .setSystemAccount(true)
            .setUsername(rogueClientId)
            .setEnabled(true),
            secret,
            true)

    and: "the rogue client connects"
    def rogueGatewayClient = new WebsocketIOClient(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=$managerTestSetup.realmCityName").build(),
            null,
            new OAuthClientCredentialsGrant("http://127.0.0.1:8081/auth/realms/$managerTestSetup.realmCityName/protocol/openid-connect/token",
            rogueClientId,
            secret,
            null).setBasicAuthHeader(true))
    rogueGatewayClient.setEncoderDecoderProvider({
      [new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, rogueGatewayClient)].toArray(new ChannelHandler[0])
    })

    and: "we observe the manager's rejection of the rogue session"
    gatewayDisconnectReasons.clear()
    def registeredGatewayConnector = gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT))
    def legitimateSessionId = registeredGatewayConnector.getSessionId()
    def legitimateDisconnect = registeredGatewayConnector.requestDisconnect
    assert legitimateSessionId != null

    and: "we record messages received by the rogue client"
    List<String> rogueClientReceivedMessages = new CopyOnWriteArrayList<>()
    rogueGatewayClient.addMessageConsumer({message ->
      getLOG().info("Message received from central manager: $message")
      rogueClientReceivedMessages.add(message as String)
    })

    and: "the rogue client connects"
    rogueGatewayClient.connect()

    then: "the manager rejects the rogue session and finishes processing its closure"
    // Removal from sessionStates follows both the session open and close handlers.
    conditions.eventually {
      assert gatewayDisconnectReasons.any { sessionId, reason ->
        reason == GatewayDisconnectEvent.Reason.UNRECOGNISED && !clientEventService.sessionStates.containsKey(sessionId)
      }
    }

    and: "the legitimate gateway session is preserved and the rogue client cannot start synchronization"
    assert registeredGatewayConnector.getSessionId() == legitimateSessionId
    assert registeredGatewayConnector.requestDisconnect.is(legitimateDisconnect)
    assert gatewayClient.connectionStatus == ConnectionStatus.CONNECTED
    assert assetStorageService.find(gateway.id).getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
    assert !clientReceivedMessages.any {it.contains(GatewayDisconnectEvent.TYPE)}
    assert !rogueClientReceivedMessages.any {it.contains(GatewayInitStartEvent.TYPE)}

    when: "the rogue client is disconnected"
    rogueGatewayClient.disconnect()

    then: "all its session events are processed before testing another connection attempt"
    conditions.eventually {
      assert clientEventService.sessionStates.keySet() == [legitimateSessionId] as Set
    }

    when: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "the gateway asset is marked as disabled"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(gateway.getId(), GatewayAsset.DISABLED, true))

    then: "the gateway asset status should become DISCONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId(), true)
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.DISCONNECTED
    }

    and: "the gateway connector should be marked as disconnected and the gateway client should have been disconnected"
    conditions.eventually {
      assert gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT)).disabled
      assert !gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT)).connected
    }

    when: "an attempt is made to add a descendant asset to a gateway that isn't connected"
    gatewayClient.disconnect()
    def failedAsset = new BuildingAsset("Failed Asset")
            .setId(UniqueIdentifierGenerator.generateId("Failed asset"))
            .setCreatedOn(timerService.getNow())
            .setParentId(gateway.id)
            .setRealm(managerTestSetup.realmBuildingName)
    failedAsset.path = (String[]) [UniqueIdentifierGenerator.generateId("Failed asset")].toArray(new String[0])
    failedAsset.addOrReplaceAttributes(
            new Attribute<>(Asset.LOCATION, new GeoJSONPoint(10, 11)).addMeta(
                    new MetaItem<>(ACCESS_PUBLIC_READ)
                    )
    )
    assetStorageService.merge(failedAsset)

    then: "an error should occur"
    thrown(IllegalStateException)

    when: "gateway assets are modified whilst the gateway is disconnected (building1Room5 re-added, building5 and descendants removed and building1 modified and building1Room1 attribute updated)"
    assets[4].setName("Test Building 1 Updated")
    assets[4].setVersion(2L)
    assets[0].getAttribute("temp").ifPresent { it.setValue(10) }
    assets = assets.subList(0, 20)

    and: "the client received messages are cleared"
    clientReceivedMessages.clear()

    and: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "the gateway is enabled again"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(gateway.getId(), GatewayAsset.DISABLED.getName(), false))

    then: "the gateway connector should be enabled"
    conditions.eventually {
      assert !gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT)).disabled
    }

    when: "the rogue client connects now the legitimate gateway client is disconnected"
    gatewayDisconnectReasons.clear()
    rogueClientReceivedMessages.clear()
    rogueGatewayClient.connect()

    then: "the manager rejects the new rogue session and finishes processing its closure"
    conditions.eventually {
      assert gatewayDisconnectReasons.any { sessionId, reason ->
        reason == GatewayDisconnectEvent.Reason.UNRECOGNISED && !clientEventService.sessionStates.containsKey(sessionId)
      }
    }

    and: "the rogue client cannot establish a gateway session or start synchronization"
    assert registeredGatewayConnector.getSessionId() == null
    assert !registeredGatewayConnector.connected
    assert registeredGatewayConnector.requestDisconnect == null
    assert assetStorageService.find(gateway.id).getGatewayStatus().orElse(null) == ConnectionStatus.DISCONNECTED
    assert !rogueClientReceivedMessages.any {it.contains(GatewayInitStartEvent.TYPE)}

    when: "the rogue client is disconnected"
    rogueGatewayClient.disconnect()

    and: "the gateway asset client secret attribute is updated"
    def newSecret = UUID.randomUUID().toString()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(gateway.getId(), GatewayAsset.CLIENT_SECRET, newSecret))

    then: "the service user secret should be updated"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId()) as GatewayAsset
      assert gateway.getClientSecret().orElse(null) == newSecret
      def user = identityProvider.getUserByUsername(gateway.getRealm(), User.SERVICE_ACCOUNT_PREFIX + gateway.getClientId().orElse(""))
      assert user != null
      assert user.secret == newSecret
    }

    when: "the gateway client reconnects with the new secret"
    gatewayClient = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=$managerTestSetup.realmBuildingName").build(),
            null,
            new OAuthClientCredentialsGrant("http://127.0.0.1:8081/auth/realms/$managerTestSetup.realmBuildingName/protocol/openid-connect/token",
            gateway.getClientId().orElse(""),
            gateway.getClientSecret().orElse(""),
            null).setBasicAuthHeader(true))
    gatewayClient.setEncoderDecoderProvider({
      [new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, gatewayClient)].toArray(new ChannelHandler[0])
    })
    gatewayClient.addMessageConsumer({ message ->
      clientReceivedMessages.add(message)
    })
    gatewayClient.addConnectionStatusConsumer({ status ->
      connectionStatus = status
    })
    gatewayClient.connect()

    then: "the gateway netty client status should become CONNECTING"
    conditions.eventually {
      assert connectionStatus == ConnectionStatus.CONNECTING
      assert gatewayClient.connectionStatus == ConnectionStatus.CONNECTING
    }

    and: "the gateway asset connection status should be CONNECTING"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId())
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTING
    }

    and: "the local manager should have sent an asset read request"
    conditions.eventually {
      assert clientReceivedMessages.size() >= 1
      assert clientReceivedMessages.find {
        it.startsWith(SharedEvent.MESSAGE_PREFIX) && it.contains("read-assets")
      } != null
    }

    when: "the previously received messages are cleared"
    clientReceivedMessages.clear()

    and: "the gateway client replies to the central manager with the assets of the gateway"
    sendAssets = [building1Room5Asset]
    sendAssets.addAll(agentAssets)
    sendAssets.addAll(assets)
    readAssetsReplyEvent = new AssetsEvent(sendAssets)
    readAssetsReplyEvent.setMessageID(GatewayConnector.ASSET_READ_EVENT_NAME_INITIAL)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(readAssetsReplyEvent).get())

    then: "the central manager should have requested the full loading of the first batch of assets"
    conditions.eventually {
      assert clientReceivedMessages.size() == 1
      assert clientReceivedMessages.get(0).contains("read-assets")
      readAssetsEvent = ValueUtil.JSON.readValue(clientReceivedMessages[0].substring(SharedEvent.MESSAGE_PREFIX.length()), ReadAssetsEvent.class)
      messageId = readAssetsEvent.messageID
      assert messageId == GatewayConnector.ASSET_READ_EVENT_NAME_BATCH + "0"
      assert readAssetsEvent.assetQuery != null
      assert readAssetsEvent.assetQuery.ids != null
      assert readAssetsEvent.assetQuery.ids.length == GatewayConnector.SYNC_ASSET_BATCH_SIZE
      assert agentAssetIds.stream().filter {
        readAssetsEvent.assetQuery.ids.contains(it)
      }.count() == agentAssetIds.size()
      assert assetIds.stream().filter {
        readAssetsEvent.assetQuery.ids.contains(it)
      }.count() + 1 == GatewayConnector.SYNC_ASSET_BATCH_SIZE - agentAssetIds.size()
    }

    when: "another asset is added to the gateway during the initial sync process"
    def building2Room5Asset = new RoomAsset("Test Building 2 Room 5")
            .setId(UniqueIdentifierGenerator.generateId("Test Building 2 Room 5"))
            .setCreatedOn(timerService.getNow())
            .setParentId(assetIds[5])
            .setRealm(MASTER_REALM)
    building2Room5Asset.path = (String[]) [UniqueIdentifierGenerator.generateId("Test Building 2 Room 5"), assetIds[5]]

    building2Room5Asset.addOrReplaceAttributes(
            new Attribute<>(Asset.LOCATION, new GeoJSONPoint(10, 11)).addOrReplaceMeta(
                    new MetaItem<>(ACCESS_PUBLIC_READ)
                    ),
            new Attribute<>("temp", NUMBER).addOrReplaceMeta(
                    new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[1])
                    .setPath("")),
                    new MetaItem<>(UNITS, Constants.units(UNITS_CELSIUS))
                    ),
            new Attribute<>("tempSetpoint", NUMBER).addMeta(
                    new MetaItem<>(AGENT_LINK, new HTTPAgentLink(agentAssetIds[1])
                    .setPath("")),
                    new MetaItem<>(UNITS, Constants.units(UNITS_CELSIUS))
                    )
    )

    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(new AssetEvent(AssetEvent.Cause.CREATE, building2Room5Asset, null)).get())

    and: "another asset is deleted from the gateway during the initial sync process (Building 3 Room 1)"
    def removedAsset = assets.remove(10)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(new AssetEvent(AssetEvent.Cause.DELETE, removedAsset, null)).get())

    and: "the gateway returns the requested assets (minus the deleted Building 3 Room 1 asset)"
    sendAssets = [building1Room5Asset]
    sendAssets.addAll(agentAssets)
    sendAssets.addAll(Arrays.stream(readAssetsEvent.assetQuery.ids).filter {
      !agentAssetIds.contains(it) && it != removedAsset.id && it != building1Room5Asset.id
    }.map { id ->
      assets.stream().filter {
        it.id == id
      }.findFirst().orElse(null)
    }.collect(Collectors.toList()))
    readAssetsReplyEvent = new AssetsEvent(sendAssets)
    readAssetsReplyEvent.setMessageID(messageId)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(readAssetsReplyEvent).get())

    then: "the central manager should have requested the full loading of the second batch of assets"
    conditions.eventually {
      assert clientReceivedMessages.size() == 2
      assert clientReceivedMessages.get(1).contains("read-assets")
      readAssetsEvent = ValueUtil.JSON.readValue(clientReceivedMessages[1].substring(SharedEvent.MESSAGE_PREFIX.length()), ReadAssetsEvent.class)
      messageId = readAssetsEvent.messageID
      assert messageId == GatewayConnector.ASSET_READ_EVENT_NAME_BATCH + (GatewayConnector.SYNC_ASSET_BATCH_SIZE - 1)
      assert readAssetsEvent.assetQuery != null
      assert readAssetsEvent.assetQuery.ids != null
      assert readAssetsEvent.assetQuery.ids.length == agentAssetIds.size() + assets.size() + 1 - GatewayConnector.SYNC_ASSET_BATCH_SIZE + 1
      assert assets.stream().filter { asset ->
        sendAssets.stream().noneMatch {
          it.id == asset.id
        }
      }.count() == readAssetsEvent.assetQuery.ids.length
    }

    when: "the gateway returns the requested assets"
    sendAssets = new CopyOnWriteArrayList<>()
    sendAssets.addAll(Arrays.stream(readAssetsEvent.assetQuery.ids).map { id ->
      assets.stream().filter { asset ->
        asset.id == id
      }.findFirst().orElse(null)
    }.collect(Collectors.toList()))
    readAssetsReplyEvent = new AssetsEvent(sendAssets)
    readAssetsReplyEvent.setMessageID(messageId)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(readAssetsReplyEvent).get())

    then: "the central manager should have requested the capabilities of the gateway"
    conditions.eventually {
      assert clientReceivedMessages.size() == 3
      assert clientReceivedMessages.get(2).contains(GatewayCapabilitiesRequestEvent.TYPE)
      def gatewayCapabilitiesRequest = ValueUtil.JSON.readValue(clientReceivedMessages[2].substring(SharedEvent.MESSAGE_PREFIX.length()), GatewayCapabilitiesRequestEvent.class)
      messageId = gatewayCapabilitiesRequest.messageID
    }

    when: "The Gateway also sends a Gateway Capabilities response indicating tunnelling is supported"
    capabilitiesReplyEvent.setMessageID(messageId)
    capabilitiesReplyEvent = new GatewayCapabilitiesResponseEvent(VersionInfo.getGatewayApiVersion(), true)
    gatewayClient.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(capabilitiesReplyEvent).get())

    then: "the gateway connector sync should be completed"
    conditions.eventually {
      def gatewayConnector = gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT))
      assert gatewayConnector.isConnected()
      assert !gatewayConnector.isInitialSyncInProgress()
    }

    and: "the gateway netty client status should become CONNECTED"
    conditions.eventually {
      assert connectionStatus == ConnectionStatus.CONNECTED
      assert gatewayClient.connectionStatus == ConnectionStatus.CONNECTED
    }

    and: "the gateway asset connection status should be CONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId())
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
      assert gateway.getTunnelingSupported().get() == true
    }

    and: "the gateway should have the correct assets"
    def gatewayAssets = assetStorageService.findAll(new AssetQuery().parents(gateway.getId()).recursive(true))
    assert gatewayAssets.size() == 2 + agentAssets.size() + assets.size()

    when: "the gateway asset is deleted"
    def deleted = assetStorageService.delete([gateway.id])
    def gatewayId = gateway.id
    gateway = null

    then: "all descendant assets should have been removed"
    conditions.eventually {
      assert assetStorageService.find(mapAssetId(gatewayId, assets[0].id, true)) == null
    }

    then: "the keycloak client should also be removed"
    assert deleted
    conditions.eventually {
      assert identityProvider.getClient(managerTestSetup.realmBuildingName, getGatewayClientId(gatewayId)) == null
    }

    cleanup: "cleanup the gateway client"
    if (gatewayClient != null) {
      gatewayClient.disconnect()
      gatewayClient.removeAllMessageConsumers()
    }
    if (rogueGatewayClient != null) {
      rogueGatewayClient.disconnect()
      rogueGatewayClient.removeAllMessageConsumers()
    }
    if (gateway != null) {
      assetStorageService.delete([gateway.id])
    }
  }

  def "Verify gateway client service"() {

    given: "some polling conditions and services from the container"
    def conditions = new PollingConditions(timeout: 30, delay: 0.2)
    def delayedConditions = new PollingConditions(initialDelay: 1, delay: 1, timeout: 10)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def gatewayService = container.getService(GatewayService.class)
    def gatewayClientService = container.getService(GatewayClientService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def clientEventService = container.getService(ClientEventService.class)
    def originalMicrophone1 = assetStorageService.find(managerTestSetup.microphone1Id)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )


    and: "the gateway client resource"
    def gatewayClientResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(GatewayClientResource.class)

    expect: "the system should settle down"
    conditions.eventually {
      assert noEventProcessedIn(assetProcessingService, 300)
    }

    when: "a gateway is provisioned in this manager in the building realm"
    GatewayAsset gateway = assetStorageService.merge(new GatewayAsset("Test gateway")
            .setRealm(managerTestSetup.realmBuildingName))

    then: "a set of credentials should have been created for this gateway and be stored against the gateway for easy reference"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId(), true) as GatewayAsset
      assert !isNullOrEmpty(gateway.getClientId().orElse(null))
      assert !isNullOrEmpty(gateway.getClientSecret().orElse(null))
    }

    and: "a gateway connector should have been created for this gateway"
    conditions.eventually {
      assert gatewayService.gatewayConnectorMap.size() == 1
      assert gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT)).gatewayId == gateway.getId()
    }

    when: "a gateway client connection is created to connect the city realm to the gateway in the building realm"
    def gatewayConnection = new GatewayConnection(
            managerTestSetup.realmCityName,
            "127.0.0.1",
            serverPort,
            managerTestSetup.realmBuildingName,
            gateway.getClientId().orElse(""),
            gateway.getClientSecret().orElse(""),
            false,
            null,
            Map.of("test", new GatewayAssetSyncRule()),
            false
    )
    gatewayClientResource.setConnection(null, managerTestSetup.realmCityName, gatewayConnection)

    then: "the gateway client should become connected"
    conditions.eventually {
      assert gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName) != null
    }

    and: "the gateway asset connection status should become CONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId())
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
    }

    and: "the assets should have been created under the gateway asset"
    conditions.eventually {
      def gatewayAssets = assetStorageService.findAll(new AssetQuery().parents(gateway.id).recursive(true))
      def cityAssets = assetStorageService.findAll(new AssetQuery().realm(new RealmPredicate(managerTestSetup.realmCityName)))
      assert gatewayAssets.size() == cityAssets.size()
    }

    when: "the gateway client is abruptly disconnected"
    // Overwrite the client connection status to prevent reconnection
    gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName).client.connectionStatus = ConnectionStatus.DISCONNECTED
    gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName).client.channel.close()

    then: "the gateway asset connection status should become DISCONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId())
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.DISCONNECTED
    }

    when: "the connection is reestablished"
    gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName).client.connect()

    then: "the gateway asset connection status should become CONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId())
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
    }

    when: "a gateway client asset is modified"
    MicrophoneAsset microphone1 = assetStorageService.find(managerTestSetup.microphone1Id)
    microphone1.setName("Microphone 1 Updated")
    microphone1.addAttributes(
            new Attribute<>("test", NUMBER, 100d)
    )
    microphone1 = assetStorageService.merge(microphone1)

    then: "the mirrored asset under the gateway should have also been updated"
    conditions.eventually {
      def mirroredMicrophone = assetStorageService.find(mapAssetId(gateway.id, managerTestSetup.microphone1Id, false))
      assert mirroredMicrophone != null
      assert mirroredMicrophone.getAttributes().get(MicrophoneAsset.SOUND_LEVEL).flatMap {
        it.getMetaItem(READ_ONLY)
      }.flatMap {
        it.getValue()
      }.orElse(false)
      assert mirroredMicrophone.getAttribute("test").isPresent()
      assert mirroredMicrophone.getAttribute("test").flatMap {
        it.getValue()
      }.orElse(0d) == 100d
    }

    when: "a gateway client asset is added"
    MicrophoneAsset microphone2 = new MicrophoneAsset("Microphone 2")
            .setRealm(managerTestSetup.realmCityName)
            .setParentId(managerTestSetup.area1Id)
            .addAttributes(
            new Attribute<>("test", TEXT, "testValue")
    )
    microphone2 = assetStorageService.merge(microphone2)

    then: "the new asset should have been created in the gateway and also mirrored under the gateway asset"
    assert microphone2.id != null
    conditions.eventually {
      def mirroredMicrophone2 = assetStorageService.find(mapAssetId(gateway.id, microphone2.id, false))
      assert mirroredMicrophone2 != null
      assert mirroredMicrophone2.getAttributes().get(MicrophoneAsset.SOUND_LEVEL).isPresent()
      assert mirroredMicrophone2.getAttribute("test").flatMap {
        it.getValue()
      }.orElse("") == "testValue"
    }

    when: "an attempt is made to add an asset under the gateway asset"
    def microphone3 = ValueUtil.clone(microphone1)
            .setName("Microphone 3")
            .setId(null)
            .setRealm(managerTestSetup.realmBuildingName)
            .setParentId(mapAssetId(gateway.id, managerTestSetup.area1Id, false))
    microphone3 = assetStorageService.merge(microphone3)

    then: "an exception should be thrown"
    thrown(IllegalStateException)

    when: "an asset with a type unknown on the central instance is added on the gateway"
    // We simulate this as both edge and central are in the same instance here in the test
    def msgEvent = new AssetEvent(
            AssetEvent.Cause.CREATE,
            new BuildingAsset("UnknownAssetType")
            .setId(UniqueIdentifierGenerator.generateId()),
            null
    )
    msgEvent.asset.type = "CustomBuildingAsset"
    gatewayClientService.connectionRealmMap.get(gatewayConnection.getLocalRealm()).sendCentralManagerMessage(msgEvent)

    then: "it should be added to the central instance as a thing asset"
    conditions.eventually {
      def mirroredCustomAssetType = assetStorageService.find(mapAssetId(gateway.id, msgEvent.asset.id, false))
      assert mirroredCustomAssetType != null
      assert mirroredCustomAssetType instanceof UnknownAsset
      assert mirroredCustomAssetType.type == "CustomBuildingAsset"
    }

    when: "an attribute is updated on the gateway client"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(microphone2.id, "test", "newValue"))

    then: "the mirrored asset attribute should also be updated"
    conditions.eventually {
      def mirroredMicrophone2 = assetStorageService.find(mapAssetId(gateway.id, microphone2.id, false))
      assert mirroredMicrophone2 != null
      assert mirroredMicrophone2.getAttribute("test").flatMap {
        it.getValue()
      }.orElse("") == "newValue"
    }

    when: "time advances"
    advancePseudoClock(1, TimeUnit.SECONDS, container)

    and: "a mirrored asset attribute is updated"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(mapAssetId(gateway.id, microphone2.id, false), "test", "newerValue"))

    then: "the attribute should be updated on the gateway client and the mirrored asset"
    conditions.eventually {
      microphone2 = assetStorageService.find(microphone2.id)
      def mirroredMicrophone2 = assetStorageService.find(mapAssetId(gateway.id, microphone2.id, false))
      assert microphone2 != null
      assert mirroredMicrophone2 != null
      assert microphone2.getAttribute("test").flatMap {
        it.getValue()
      }.orElse("") == "newerValue"
      assert mirroredMicrophone2.getAttribute("test").flatMap {
        it.getValue()
      }.orElse("") == "newerValue"
    }

    when: "we subscribe to attribute events"
    List<AttributeEvent> attributeEvents = new CopyOnWriteArrayList<>()
    Consumer<AttributeEvent> eventConsumer = { attributeEvent ->
      attributeEvents.add(attributeEvent)
    }
    clientEventService.addSubscription(AttributeEvent.class, null, eventConsumer)

    and: "an attribute with an attribute link is updated on the gateway"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.light2Id, LightAsset.ON_OFF, true))

    then: "only four attribute events should have been generated, one for each of light1 and light2 and one for each of their mirrored assets below the gateway asset"
    delayedConditions.eventually {
      assert attributeEvents.size() == 4
      assert attributeEvents.any {
        it.id == managerTestSetup.light2Id && it.name == LightAsset.ON_OFF.name && it.value.orElse(false)
      }
      assert attributeEvents.any {
        it.id == managerTestSetup.light1Id && it.name == LightAsset.ON_OFF.name && it.value.orElse(false)
      }
      assert attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.light2Id, false) && it.name == LightAsset.ON_OFF.name && it.value.orElse(false)
      }
      assert attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.light1Id, false) && it.name == LightAsset.ON_OFF.name && it.value.orElse(false)
      }
    }

    when: "a gateway client asset is deleted"
    def deleted = assetStorageService.delete(Collections.singletonList(managerTestSetup.microphone1Id))

    then: "the client asset should have been deleted and also the mirrored asset under the gateway should also be deleted"
    assert deleted
    conditions.eventually {
      assert assetStorageService.find(mapAssetId(gateway.id, managerTestSetup.microphone1Id, false)) == null
    }

    when: "attribute filters are added to the gateway connection and persisted"
    gatewayConnection.setAttributeFilters([
      new GatewayAttributeFilter().setMatcher(new AssetQuery().types(LightAsset.class)).setValueChange(true),
      new GatewayAttributeFilter().setMatcher(new AssetQuery().types(PeopleCounterAsset.class).attributeNames(PeopleCounterAsset.COUNT_TOTAL.name, PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE.name))
      .setDelta(1.5d),
      new GatewayAttributeFilter().setMatcher(new AssetQuery().names("Microphone 2")).setDuration("PT1M"),
      // Ignore any other attribute
      new GatewayAttributeFilter().setSkipAlways(true)
    ])
    def oldConnector = gatewayClientService.connectionRealmMap.get(gatewayConnection.getLocalRealm())
    gatewayClientResource.setConnection(null, managerTestSetup.realmCityName, gatewayConnection)

    then: "the gateway connection IO client should have been replaced and synchronisation should be complete"
    conditions.eventually {
      assert gatewayClientService.connectionRealmMap.get(gatewayConnection.getLocalRealm()) != null
      assert gatewayClientService.connectionRealmMap.get(gatewayConnection.getLocalRealm()) != oldConnector
      GatewayConnector connector = gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT))
      assert connector != null
      assert !connector.initialSyncInProgress
      assert attributeEvents.any {
        it.id == gateway.id && it.value.get() == ConnectionStatus.CONNECTED
      }
    }

    when: "an attribute event occurs in the edge gateway realm for an attribute with a value change filter and the attribute value has not changed"
    attributeEvents.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.light2Id, LightAsset.ON_OFF, true))

    then: "the value should not have been forwarded to the central instance"
    delayedConditions.eventually {
      assert attributeEvents.any {
        it.id == managerTestSetup.light2Id && it.name == LightAsset.ON_OFF.name && it.value.orElse(false)
      }
      assert !attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.light2Id, false) && it.name == LightAsset.ON_OFF.name
      }
    }

    when: "an attribute event occurs in the edge gateway realm for an attribute with a value change filter and the attribute value has changed"
    attributeEvents.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.light2Id, LightAsset.ON_OFF, false))

    then: "the value should now have been forwarded to the central instance"
    conditions.eventually {
      def mirroredLight2 = assetStorageService.find(mapAssetId(gateway.id, managerTestSetup.light2Id, false))
      assert mirroredLight2 != null
      assert !mirroredLight2.getAttribute(LightAsset.ON_OFF).flatMap {
        it.getValue()
      }.orElse(true)
      assert attributeEvents.any {
        it.id == managerTestSetup.light2Id && it.name == LightAsset.ON_OFF.name && !it.value.orElse(true)
      }
      assert attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.light2Id, false) && it.name == LightAsset.ON_OFF.name && !it.value.orElse(true)
      }
    }

    when: "an attribute event occurs in the edge gateway realm for an attribute with a delta filter and the attribute value has changed by less than delta"
    attributeEvents.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.peopleCounter1AssetId, PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE, 0.5d))

    then: "the value should not have been forwarded to the central instance"
    delayedConditions.eventually {
      assert attributeEvents.any {
        it.id == managerTestSetup.peopleCounter1AssetId && it.name == PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE.name && Math.abs(it.value.orElse(0d) - 0.5d) < 0.000001d
      }
      assert !attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.peopleCounter1AssetId, false) && it.name == PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE.name
      }
    }

    when: "an attribute event occurs in the edge gateway realm for an attribute with a delta filter and the attribute value has changed by more than delta"
    attributeEvents.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.peopleCounter1AssetId, PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE, -1.1d))

    then: "the value should have been forwarded to the central instance"
    conditions.eventually {
      assert attributeEvents.any {
        it.id == managerTestSetup.peopleCounter1AssetId && it.name == PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE.name && Math.abs(it.value.orElse(0d) + 1.1d) < 0.000001d
      }
      assert attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.peopleCounter1AssetId, false) && it.name == PeopleCounterAsset.COUNT_GROWTH_PER_MINUTE.name && Math.abs(it.value.orElse(0d) + 1.1d) < 0.000001d
      }
    }

    when: "an attribute event occurs in the edge gateway realm for an attribute with a duration filter"
    attributeEvents.clear()
    microphone2 = assetStorageService.find(microphone2.id)
    assetProcessingService.sendAttributeEvent(new AttributeEvent(microphone2.id, MicrophoneAsset.SOUND_LEVEL, 50d))

    then: "the value should have been forwarded to the central instance (as no prior value has been sent since the filter was created)"
    conditions.eventually {
      assert attributeEvents.any {
        it.id == microphone2.id && it.name == MicrophoneAsset.SOUND_LEVEL.name && Math.abs(it.value.orElse(0d) - 50d) < 0.000001d
      }
      assert attributeEvents.any {
        it.id == mapAssetId(gateway.id, microphone2.id, false) && it.name == MicrophoneAsset.SOUND_LEVEL.name && Math.abs(it.value.orElse(0d) - 50d) < 0.000001d
      }
    }

    when: "an attribute event occurs in the edge gateway realm for the same attribute before the duration filter has elapsed"
    attributeEvents.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(microphone2.id, MicrophoneAsset.SOUND_LEVEL, 60d))

    then: "the value should not have been forwarded to the central instance"
    delayedConditions.eventually {
      assert attributeEvents.any {
        it.id == microphone2.id && it.name == MicrophoneAsset.SOUND_LEVEL.name && Math.abs(it.value.orElse(0d) - 60d) < 0.000001d
      }
      assert !attributeEvents.any {
        it.id == mapAssetId(gateway.id, microphone2.id, false) && it.name == MicrophoneAsset.SOUND_LEVEL.name
      }
    }

    when: "an attribute event occurs in the edge gateway realm for an attribute that matches the catch all and skip filter"
    attributeEvents.clear()
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.area1Id, Asset.NOTES, "Some test notes"))

    then: "the value should not have been forwarded to the central instance"
    delayedConditions.eventually {
      assert attributeEvents.any {
        it.id == managerTestSetup.area1Id && it.name == Asset.NOTES.name && "Some test notes".equals(it.value.orElse(null))
      }
      assert !attributeEvents.any {
        it.id == mapAssetId(gateway.id, managerTestSetup.area1Id, false) && it.name == Asset.NOTES.name
      }
    }

    cleanup: "Remove any subscriptions created"
    if (gatewayClientResource != null) {
      gatewayClientResource.deleteConnection(null, managerTestSetup.realmCityName)
    }
    if (clientEventService != null) {
      clientEventService.removeSubscription(eventConsumer)
    }
    if (gateway != null) {
      assetStorageService.delete([gateway.id])
    }
    if (microphone2 != null) {
      assetStorageService.delete([microphone2.id])
    }
    if (originalMicrophone1 != null) {
      assetStorageService.merge(originalMicrophone1)
    }
  }

  def "Verify GatewayAssetSyncRules"() {

    given: "some polling conditions and services from the container"
    def conditions = new PollingConditions(timeout: 30, delay: 0.2)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def gatewayService = container.getService(GatewayService.class)
    def gatewayClientService = container.getService(GatewayClientService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "the gateway client resource"
    def gatewayClientResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(GatewayClientResource.class)

    expect: "the system should settle down"
    conditions.eventually {
      assert noEventProcessedIn(assetProcessingService, 300)
    }

    when: "a gateway asset is provisioned in this manager in the building realm"
    GatewayAsset gateway = assetStorageService.merge(new GatewayAsset("Test gateway")
            .setRealm(managerTestSetup.realmBuildingName))

    then: "a set of credentials should have been created for this gateway and be stored against the gateway for easy reference"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId(), true) as GatewayAsset
      assert !isNullOrEmpty(gateway.getClientId().orElse(null))
      assert !isNullOrEmpty(gateway.getClientSecret().orElse(null))
    }

    and: "a gateway connector should have been created for this gateway"
    conditions.eventually {
      assert gatewayService.gatewayConnectorMap.size() == 1
      assert gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT)).gatewayId == gateway.getId()
    }

    when: "a gateway client connection is created to connect the city realm to the gateway in the building realm"
    def gatewayConnection = new GatewayConnection(
            managerTestSetup.realmCityName,
            "127.0.0.1",
            serverPort,
            managerTestSetup.realmBuildingName,
            gateway.getClientId().orElse(""),
            gateway.getClientSecret().orElse(""),
            false,
            null,
            [
              "*" : new GatewayAssetSyncRule().setExcludeAttributeMeta([
                "*": [
                  ACCESS_PUBLIC_READ.name,
                  ACCESS_PUBLIC_WRITE.name,
                  STORE_DATA_POINTS.name
                ]
              ]).setAddAttributeMeta([
                "*": new MetaMap([new MetaItem<>(ACCESS_RESTRICTED_READ, true)])
              ]).setExcludeAttributes([Asset.NOTES.name]),
              "LightAsset": new GatewayAssetSyncRule().setExcludeAttributeMeta([
                "*": [
                  ATTRIBUTE_LINKS.name,
                  ACCESS_PUBLIC_WRITE.name,
                  FORMAT.name
                ]
              ]).setAddAttributeMeta([
                "onOff": new MetaMap([new MetaItem<>(FORMAT, ValueFormat.BOOLEAN_AS_PRESSED_RELEASED())])
              ]).setExcludeAttributes([LightAsset.COLOUR_RGB.name]).setAccessPublicRead(false)
            ] as Map<String, GatewayAssetSyncRule>,
            false
    )
    gatewayClientResource.setConnection(null, managerTestSetup.realmCityName, gatewayConnection)

    then: "the gateway client should become connected"
    conditions.eventually {
      assert gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName) != null
    }

    and: "the gateway asset connection status should become CONNECTED"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId())
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
    }

    and: "the assets should have been created under the gateway asset"
    conditions.eventually {
      def gatewayAssets = assetStorageService.findAll(new AssetQuery().parents(gateway.id).recursive(true))
      def cityAssets = assetStorageService.findAll(new AssetQuery().realm(new RealmPredicate(managerTestSetup.realmCityName)))
      assert gatewayAssets.size() == cityAssets.size()
    }

    and: "the asset sync rules should have been applied"
    conditions.eventually {
      def mirroredMicrophone = assetStorageService.find(mapAssetId(gateway.id, managerTestSetup.microphone1Id, false))
      def mirroredLight = assetStorageService.find(mapAssetId(gateway.id, managerTestSetup.light1Id, false))
      assert mirroredMicrophone != null
      assert mirroredLight != null
      assert mirroredMicrophone.getAttributes().get(MicrophoneAsset.SOUND_LEVEL).flatMap {
        it.getMetaItem(READ_ONLY)
      }.flatMap {
        it.getValue()
      }.orElse(false)
      assert mirroredMicrophone.getAttributes().get(MicrophoneAsset.SOUND_LEVEL).flatMap {
        it.getMetaItem(ACCESS_RESTRICTED_READ)
      }.flatMap {
        it.getValue()
      }.orElse(false)
      assert !mirroredMicrophone.getAttributes().get(MicrophoneAsset.SOUND_LEVEL).flatMap {
        it.getMetaItem(STORE_DATA_POINTS)
      }.flatMap {
        it.getValue()
      }.orElse(false)
      assert mirroredMicrophone.getAttributes().get(MicrophoneAsset.LOCATION).flatMap {
        it.getMetaItem(ACCESS_RESTRICTED_READ)
      }.flatMap {
        it.getValue()
      }.orElse(false)
      assert !mirroredMicrophone.getAttribute(Asset.NOTES).isPresent()
      assert mirroredLight.getAttributes().get(LightAsset.ON_OFF).flatMap {
        it.getMetaItem(FORMAT)
      }.flatMap {
        it.getValue()
      }.map {
        it.asPressedReleased
      }.orElse(false) == ValueFormat.BOOLEAN_AS_PRESSED_RELEASED().asPressedReleased
      assert !mirroredLight.getAttribute(LightAsset.COLOUR_RGB).isPresent()
      assert mirroredLight.getAttribute(Asset.NOTES).isPresent()
      assert !mirroredLight.isAccessPublicRead()
    }

    cleanup: "the gateway connection is removed"
    if (gatewayClientResource != null) {
      gatewayClientResource.deleteConnection(null, managerTestSetup.realmCityName)
    }
  }

  def "Verify GatewayResource realm boundary checks"() {
    given: "some polling conditions and services from the container"
    def gatewayService = container.getService(GatewayService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def identityProvider = container.getService(ManagerIdentityService.class).identityProvider as ManagerKeycloakIdentityProvider

    and: "a user in the building realm holding the tunnel read role the endpoint requires"
    def tunnelReader = identityProvider.createUpdateUser(
            managerTestSetup.realmBuildingName,
            new User().setUsername("gatewayrealmtunnelreader").setEnabled(true),
            "gatewayrealmtunnelreader",
            true
    )
    identityProvider.updateUserClientRoles(
            managerTestSetup.realmBuildingName,
            tunnelReader.id,
            KEYCLOAK_CLIENT_ID,
            READ_TUNNELS_ROLE
    )

    and: "an authenticated session for that user"
    def accessToken = authenticate(
            container,
            managerTestSetup.realmBuildingName,
            KEYCLOAK_CLIENT_ID,
            "gatewayrealmtunnelreader",
            "gatewayrealmtunnelreader"
    )

    and: "the gateway service resource"
    def gatewayResource = getClientApiTarget(serverUri(serverPort), managerTestSetup.realmBuildingName, accessToken).proxy(GatewayServiceResource.class)

    when: "a fake tunnel for a fake gateway ID in the smart city realm is injected into the gateway service"
    gatewayService.@tunnelInfos.put("fake-tunnel-id",
            new GatewayTunnelInfo(managerTestSetup.realmCityName, "fake-gateway-id", GatewayTunnelInfo.Type.HTTPS, "127.0.0.1", 443)
    )

    and: "the building user then retrieves the list of tunnels for the fake gateway ID"
    def activeTunnels = gatewayResource.getGatewayActiveTunnelInfos(null, managerTestSetup.realmBuildingName, "fake-gateway-id")

    then: "the building user should not be able to see any tunnels for the fake gateway ID in the smart city realm"
    assert activeTunnels.length == 0

    cleanup: "the fake tunnel and the user are removed"
    if (gatewayService != null) {
      gatewayService.@tunnelInfos.remove("fake-tunnel-id")
    }
    if (tunnelReader != null) {
      identityProvider.deleteUser(managerTestSetup.realmBuildingName, tunnelReader.id)
    }
  }

  def "Verify gateway websocket cannot write attributes outside gateway realm"() {
    given: "some polling conditions and services from the container"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def gatewayService = container.getService(GatewayService.class)
    def gatewayClientService = container.getService(GatewayClientService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "a target asset exists in another realm"
    def crossRealmTarget = new ThingAsset("Cross realm target")
            .setRealm(managerTestSetup.realmCityName)
            .addAttributes(new Attribute<>("test", TEXT, "original"))
    crossRealmTarget = assetStorageService.merge(crossRealmTarget)

    when: "a gateway is provisioned in the building realm and the city realm"
    GatewayAsset gateway = assetStorageService.merge(new GatewayAsset("Attacker controlled gateway")
            .setRealm(managerTestSetup.realmBuildingName))
    GatewayAsset cityGateway = assetStorageService.merge(new GatewayAsset("Cross realm gateway")
            .setRealm(managerTestSetup.realmCityName))

    then: "the gateway service account credentials are stored on the gateway assets"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId(), true) as GatewayAsset
      cityGateway = assetStorageService.find(cityGateway.getId(), true) as GatewayAsset
      assert !isNullOrEmpty(gateway.getClientId().orElse(null))
      assert !isNullOrEmpty(gateway.getClientSecret().orElse(null))
      assert !isNullOrEmpty(cityGateway.getClientId().orElse(null))
      assert !isNullOrEmpty(cityGateway.getClientSecret().orElse(null))
    }

    when: "a gateway client connector is created to connect the city realm to the building realm gateway"
    def gatewayConnection = new GatewayConnection(
            managerTestSetup.realmCityName,
            "127.0.0.1",
            serverPort,
            managerTestSetup.realmBuildingName,
            gateway.getClientId().orElse(""),
            gateway.getClientSecret().orElse(""),
            false,
            null,
            Map.of("test", new GatewayAssetSyncRule()),
            false
    )
    gatewayClientService.setConnection(gatewayConnection)

    then: "the gateway client connector should be created"
    conditions.eventually {
      assert gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName) != null
    }

    and: "the gateway initial sync should complete"
    conditions.eventually {
      def connector = gatewayService.gatewayConnectorMap.get(gateway.getId().toLowerCase(Locale.ROOT))
      assert connector != null
      assert connector.connected
      assert !connector.initialSyncInProgress
    }

    and: "the gateway asset connection status should become connected"
    conditions.eventually {
      gateway = assetStorageService.find(gateway.getId(), true) as GatewayAsset
      assert gateway.getGatewayStatus().orElse(null) == ConnectionStatus.CONNECTED
    }

    when: "the gateway sends an attribute event using the mapped ID of the cross-realm target"
    def mappedTargetId = mapAssetId(gateway.id, crossRealmTarget.id, true)

    gatewayClientService.connectionRealmMap
            .get(managerTestSetup.realmCityName)
            .sendCentralManagerMessage(new AttributeEvent(mappedTargetId, "test", "crossRealmWrite"))

    then: "the cross-realm target asset must not be modified after some time"
    new PollingConditions(initialDelay: 5).eventually {
      def reloadedTarget = assetStorageService.find(crossRealmTarget.id, true)
      assert reloadedTarget != null
      assert reloadedTarget.getAttribute("test").flatMap {
        it.getValue()
      }.orElse(null) == "original"
    }

    when: "the gateway sends an attribute event using the mapped ID of a cross realm gateway asset to update the client secret"
    def mappedGatewayId = mapAssetId(gateway.id, cityGateway.id, true)

    gatewayClientService.connectionRealmMap
            .get(managerTestSetup.realmCityName)
            .sendCentralManagerMessage(new AttributeEvent(mappedGatewayId, GatewayAsset.CLIENT_SECRET, UUID.randomUUID().toString()))

    then: "the update should not occur"
    new PollingConditions(initialDelay: 5).eventually {
      def reloadedTarget = assetStorageService.find(cityGateway.id, true) as GatewayAsset
      assert reloadedTarget != null
      assert reloadedTarget.getClientSecret().orElse(null) == cityGateway.getClientSecret().orElse(null)
    }

    cleanup: "cleanup created assets and websocket client"
    if (gatewayClientService != null) {
      gatewayClientService.deleteConnections([managerTestSetup.realmCityName])
    }
    if (gateway != null) {
      assetStorageService.delete([gateway.id])
    }
    if (crossRealmTarget != null) {
      assetStorageService.delete([crossRealmTarget.id])
    }
  }

  /**
   * This test requires a manager instance with tunnelling configured, so is manual for now unfortunately.
   * Change the test url and key path to match the instance to connect to.
   * Recommended to run profile/dev-proxy.yml profile.
   */
  @Ignore
  def "Verify gateway tunnel factory"() {
    given: "an ssh private key and the URL of a manager instance with tunnelling configured"
    def keyPath = Paths.get(System.getProperty("user.home"), ".ssh", "test_key")
    def tunnelSSHHost = "custom-project-test.openremote.app"
    def tunnelSSHPort = 2222
    def tmpDir = new File("tmp")
    def lockFile = new File(tmpDir, "lock.file")

    and: "an instance of the gateway tunnel factory is created"
    def container = new Container(Collections.emptyMap(), Collections.emptyList())
    def conditions = new PollingConditions(timeout: 15, delay: 1)
    def tunnelFactory = new MINAGatewayTunnelFactory(container.EXECUTOR, Container.SCHEDULED_EXECUTOR, keyPath.toFile(), null)
    tunnelFactory.start()

    expect: "the SSH client to be ready"
    conditions.eventually {
      tunnelFactory.client.isStarted()
    }

    and: "A configured GatewayTunnelInfo for HTTPS on 127.0.0.1:443"
    def tunnelInfo = new GatewayTunnelInfo(Constants.MASTER_REALM,
            "abcedf123456",
            GatewayTunnelInfo.Type.HTTPS,
            "127.0.0.1", 443)

    and: "A completion variable for the close callback"
    AtomicBoolean closed = new AtomicBoolean(false)

    when: "Create session is called"
    GatewayTunnelSession session = tunnelFactory.createSession(tunnelSSHHost, tunnelSSHPort, tunnelInfo, { t ->
      closed.set(true)
    })

    then: "The session connection future should complete successfully"
    new PollingConditions(timeout: 60).eventually {
      assert session.getConnectFuture().isDone()
      assert !session.getConnectFuture().isCompletedExceptionally()
    }

    then: "we keep the tunnel open for manual testing until lock file is deleted"
    if (!tmpDir.exists()) {
      tmpDir.mkdirs()
    }
    if (!lockFile.exists()) {
      lockFile.createNewFile()
    }
    getLOG().info("---------------------------------------------------------------------------------")
    getLOG().info("TEST PAUSED FOR TUNNEL TESTING")
    getLOG().info("Delete the lock file to continue: ${lockFile.absolutePath}")
    getLOG().info("Tunnel should be accessible at: gw-54tnxwr2oobjafque1jndh.${tunnelSSHHost}")
    getLOG().info("---------------------------------------------------------------------------------")

    while (lockFile.exists()) {
      getLOG().info("Tunnel is open")
      Thread.sleep(5000)
    }

    when: "we close the tunnel gracefully"
    session.disconnect()

    then: "the tunnel should be closed without error"
    noExceptionThrown()

    cleanup: "cleanup"
    if (tunnelFactory != null) {
      tunnelFactory.stop()
    }
  }

  /**
   * This test requires a manager instance with tunnelling configured, so is manual for now unfortunately.
   * Refer to "Gateway Tunnelling Setup" in the docs for setting up the required environment.
   *
   * Make sure to make the relevant gateway in the central instance, and change the rest of the variables below
   * to reflect your setup, but most of these should be unchanged if you use the same setup as the documentation.
   */
  @Ignore
  def "Gateway Tunneling Edge Gateway Integration test"() {
    given: "the container environment is started"
    def sshKeyPath = Paths.get("deployment/sish/client/client")
    def conditions = new PollingConditions(timeout: 6000, delay: 0.2)
    // The port where this test's manager webserver will run. cannot use random ephemeral port because of
    // its randomization, which when generating the gateway tunnel ID, will then generate a different gateway ID each time.
    def webserverPort = 12345
    def config = defaultConfig(webserverPort)
    def container = startContainer(config << [(GatewayService.OR_GATEWAY_TUNNEL_SSH_KEY_FILE): sshKeyPath.toAbsolutePath().toString()], defaultServices())
    def gatewayClientService = container.getService(GatewayClientService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "Central Instance information with tunnelling enabled"

    def centralInstanceHostname = "127.0.0.1"
    def centralInstancePort = 443
    def isCentralInstanceSecure = (centralInstancePort == 443)
    def centralInstanceRealm = managerTestSetup.realmMasterName
    def centralInstanceAutoCloseMinutes = 2
    def gatewayClientId = "gateway-5bpoensnt4kaoobkp1fwzo"
    def gatewayClientSecret = "86a4fdff-3fc0-4b42-a276-82cb9862a623"
    def gatewayAssetId = "5bPOENSnt4kaoObkP1FWZO"

    def accessToken = authenticate(
            (isCentralInstanceSecure),
            centralInstanceHostname,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    and: "the gateway client resource"
    def gatewayResource = getClientApiTarget(serverUri((isCentralInstanceSecure), centralInstanceHostname, centralInstancePort), MASTER_REALM, accessToken).proxy(GatewayServiceResource.class)

    when: "a new gateway client connection is created to connect to the central instance"

    def gatewayConnection = new GatewayConnection(
            managerTestSetup.realmCityName,
            centralInstanceHostname,
            centralInstancePort,
            centralInstanceRealm,
            gatewayClientId,
            gatewayClientSecret,
            (isCentralInstanceSecure),
            null,
            Map.of("test", new GatewayAssetSyncRule()),
            false
    )

    gatewayClientService.setConnection(gatewayConnection)

    then: "the gateway client should become connected"
    conditions.eventually {
      assert gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName) != null
    }

    and: "the gateway connection status should become CONNECTED"
    conditions.eventually {
      assert gatewayClientService.getConnectionStatus(managerTestSetup.realmCityName) == ConnectionStatus.CONNECTED
    }

    and: "Tunnelling is supported in this gateway"
    assert gatewayClientService.connectionRealmMap.get(managerTestSetup.realmCityName).tunnelFactory != null

    when: "We suspend the thread to allow the tunnel to settle"
    Thread.sleep(5000)

    and: "we request (from the central manager) for a new tunnel to be created"
    def tunnelInfo = new GatewayTunnelInfo(
            centralInstanceRealm,
            gatewayAssetId,
            GatewayTunnelInfo.Type.HTTP,
            "127.0.0.1",
            webserverPort)

    def centralManagerTunnelInfo = gatewayResource.startTunnel(tunnelInfo)

    then: "gateway should have been opened"
    conditions.eventually {
      def res = gatewayResource.getAllActiveTunnelInfos(null, centralInstanceRealm)
      print(res)
      assert Arrays.stream(res).anyMatch({ info -> info.id == tunnelInfo.getId() })
      assert gatewayClientService.activeTunnels.mappingCount() == 1
    }

    /*
     At this point, a gateway has been created from the central instance to the gateway that is running on this test.
     We can now request `/auth` from the tunnel URL, and the request route would look like this:
     This Groovy test --> Central Instance --> Sish --> Gateway Proxy --> Keycloak/Manager
     For the "Sish --> Gateway Proxy" request to be routed correctly, we need to edit our local `/etc/hosts` file
     to route the <tunnelid>.<tunnelSSHHost> to 127.0.0.2, like this:
     127.0.0.1       gw-5fj1sxvwwfp7wvgqgve91n.127.0.0.1
     If that is setup, the request correctly routes through the tunnel to the gateway, and we can request the edge manager.
     To assert proper connectivity, we are going to request a new admin token from the tunnel URL to make sure the
     Keycloak connection works, but also request the server info, which proves that the request has reached the manager
     webserver.
     * */
    and: "We request a new admin token from the tunnel URL"
    def token = authenticate(true,
            centralManagerTunnelInfo.id + "." + centralManagerTunnelInfo.hostname,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT))

    when: "We request the info endpoint of the currently running manager"

    String scheme = isCentralInstanceSecure ? "https" : "http"
    String basePath = "/api/master"
    String baseUrl = "${scheme}://$centralManagerTunnelInfo.id.$centralManagerTunnelInfo.hostname$basePath"
    // TODO: Use WebTarget class and built in client for this
    ResteasyClient client = ResteasyClientBuilder.newBuilder().hostnameVerifier { String h, SSLSession s ->
      true
    }.build()
    ResteasyWebTarget target = (ResteasyWebTarget) client.target(baseUrl)

    then: "The request should be successful"
    conditions.eventually {
      def info = target.proxy(StatusResource.class).getInfo()
      assert info != null
      assert info.size() > 0
    }


    and: "The test has been correctly configured to auto-stop by the gateway itself"
    conditions.eventually {
      assert gatewayClientService.tunnelAutoCloseTasks.mappingCount() == 1
      assert gatewayClientService.tunnelAutoCloseTasks.get(centralManagerTunnelInfo.getId()) != null
    }

    when: "The timer is advanced forward by central instance's OR_GATEWAY_TUNNEL_AUTO_CLOSE_MINUTES to trigger auto-close"
    //TODO: This actually doesn't work.
    //the autoClose tunnel task is scheduled by the ScheduledExecutor, which is on a timeout basis, not a clock basis.

    advancePseudoClock(Duration.ofMinutes(centralInstanceAutoCloseMinutes).toMillis(), TimeUnit.MILLISECONDS)

    then: "The tunnel should be closed automatically by the gateway"
    conditions.eventually {
      assert gatewayClientService.tunnelAutoCloseTasks.mappingCount() == 0
    }

    cleanup: "the gateway connection is removed"
    if (gatewayResource != null) {
      gatewayResource.deleteConnection(null, managerTestSetup.realmCityName)
    }
  }
}
