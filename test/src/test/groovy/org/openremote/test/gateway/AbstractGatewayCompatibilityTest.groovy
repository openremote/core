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
import org.apache.http.client.utils.URIBuilder
import org.openremote.agent.protocol.io.AbstractNettyIOClient
import org.openremote.agent.protocol.websocket.WebsocketIOClient
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.gateway.GatewayConnector
import org.openremote.manager.gateway.GatewayService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.security.ManagerKeycloakIdentityProvider
import org.openremote.manager.setup.SetupService
import org.openremote.manager.system.VersionInfo
import org.openremote.model.asset.ReadAssetsEvent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.GatewayAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.auth.OAuthClientCredentialsGrant
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.gateway.GatewayCapabilitiesRequestEvent
import org.openremote.model.gateway.GatewayInitDoneEvent
import org.openremote.model.gateway.GatewayInitStartEvent
import org.openremote.model.query.AssetQuery
import org.openremote.model.util.ValueUtil
import org.openremote.test.ManagerContainerTrait
import org.openremote.setup.integration.ManagerTestSetup
import spock.lang.Specification
import spock.util.concurrent.PollingConditions


abstract class AbstractGatewayCompatibilityTest extends Specification implements ManagerContainerTrait {

  private static String mapAssetId(String gatewayId, String assetId, boolean outbound) {
    GatewayConnector.mapAssetId(gatewayId, assetId, outbound, 2)
  }

  // Frozen AssetsEvent JSON captured from BETA (c706a7fb78542967c68eb97dcf6ef06673475ff7,
  // be467d6^), 1.0.0 (be467d6dfaa3875c5d48b65146cecb5bc5b8e30b), and 1.1.0
  // (c7c1c4c962c6954282d1ec38a1361fb3a99177ad). All three serialize these assets identically.
  // Keep these as wire fixtures: serializing current model objects would hide compatibility regressions.
  private static final String INITIAL_ASSETS_JSON = '''
    {"eventType":"assets","assets":[{
      "id":"0123456789ABCDEFGHIJKL","version":0,"createdOn":1769688000000,
      "name":"Compatibility asset","accessPublicRead":false,"realm":"master","type":"ThingAsset",
      "path":["0123456789ABCDEFGHIJKL"]
    }],"timestamp":0,"messageID":"INITIAL"}
  '''
  private static final String BATCH_ASSETS_JSON = '''
    {"eventType":"assets","assets":[{
      "id":"0123456789ABCDEFGHIJKL","version":0,"createdOn":1769688000000,
      "name":"Compatibility asset","accessPublicRead":false,"realm":"master","type":"ThingAsset",
      "path":["0123456789ABCDEFGHIJKL"],
      "attributes":{"temperature":{"name":"temperature","type":"number","meta":null,"value":21.5}}
    }],"timestamp":0,"messageID":"BATCH0"}
  '''

  def setupSpec() {
    startContainer(defaultConfig(), defaultServices())
  }

  protected GatewayAsset provisionCompatibilityGateway(String version) {
    def storage = container.getService(AssetStorageService)
    def realm = container.getService(SetupService).getTaskOfType(ManagerTestSetup).realmBuildingName
    def gateway = storage.merge(new GatewayAsset("Compatibility gateway $version").setRealm(realm))
    def conditions = new PollingConditions(timeout: 15, delay: 0.1)
    conditions.eventually {
      gateway = storage.find(gateway.id, true) as GatewayAsset
      assert gateway.clientId.present
      assert gateway.clientSecret.present
      assert container.getService(GatewayService).gatewayConnectorMap.containsKey(gateway.id.toLowerCase(Locale.ROOT))
    }
    return gateway
  }

  protected WebsocketIOClient createCompatibilityClient(GatewayAsset gateway, List<SharedEvent> receivedEvents) {
    def identityProvider = container.getService(ManagerIdentityService).identityProvider as ManagerKeycloakIdentityProvider
    def tokenUri = new URIBuilder(identityProvider.keycloakPublicUrl)
            .setPath("auth/realms/${gateway.realm}/protocol/openid-connect/token").build().toString()
    def client = new WebsocketIOClient(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=${gateway.realm}").build(),
            null,
            new OAuthClientCredentialsGrant(tokenUri, gateway.clientId.get(), gateway.clientSecret.get(), null)
            .setBasicAuthHeader(true))
    client.setEncoderDecoderProvider({
      [new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client)].toArray(new ChannelHandler[0])
    })
    client.addMessageConsumer({ String message ->
      if (message.startsWith(SharedEvent.MESSAGE_PREFIX)) {
        receivedEvents.add(ValueUtil.JSON.readValue(message.substring(SharedEvent.MESSAGE_PREFIX.length()), SharedEvent))
      }
    })
    return client
  }

  protected static <T extends SharedEvent> T awaitGatewayEvent(List<SharedEvent> receivedEvents, Class<T> eventType, String messageId = null) {
    T event = null
    def conditions = new PollingConditions(timeout: 15, delay: 0.1)
    conditions.eventually {
      event = receivedEvents.find { eventType.isInstance(it) && it.messageID == messageId } as T
      assert event != null : "Expected ${eventType.simpleName} ($messageId), received: $receivedEvents"
    }
    return event
  }

  protected static void synchronizeAssets(WebsocketIOClient client, List<SharedEvent> receivedEvents) {
    client.sendMessage(SharedEvent.MESSAGE_PREFIX + INITIAL_ASSETS_JSON)
    def batchRequest = awaitGatewayEvent(receivedEvents, ReadAssetsEvent, "BATCH0")
    assert batchRequest.assetQuery.ids.toList() == ["0123456789ABCDEFGHIJKL"]
    client.sendMessage(SharedEvent.MESSAGE_PREFIX + BATCH_ASSETS_JSON)
  }

  protected static void initializeVersionedGateway(WebsocketIOClient client, List<SharedEvent> receivedEvents, String capabilitiesJson) {
    client.connect()
    def initStart = awaitGatewayEvent(receivedEvents, GatewayInitStartEvent)
    assert initStart.version == VersionInfo.getGatewayApiVersion()
    assert initStart.activeTunnels == null
    synchronizeAssets(client, receivedEvents)
    awaitGatewayEvent(receivedEvents, GatewayCapabilitiesRequestEvent)
    assert !receivedEvents.any { it instanceof ReadAssetsEvent && it.messageID == "INITIAL" }
    client.sendMessage(SharedEvent.MESSAGE_PREFIX + capabilitiesJson)
  }

  protected void assertCompatibilityConnected(GatewayAsset gateway, List<SharedEvent> receivedEvents, String version, boolean timeoutManagementSupported) {
    awaitGatewayEvent(receivedEvents, GatewayInitDoneEvent)

    def storage = container.getService(AssetStorageService)
    def gatewayService = container.getService(GatewayService)
    def conditions = new PollingConditions(timeout: 15, delay: 0.1)

    conditions.eventually {
      def connector = gatewayService.gatewayConnectorMap.get(gateway.id.toLowerCase(Locale.ROOT))

      assert connector.isConnected()
      assert !connector.isInitialSyncInProgress()
      assert connector.isTunnellingSupported()
      assert connector.gatewayVersion == version
      assert connector.isTunnelTimeoutManagementSupported() == timeoutManagementSupported

      def connectedGateway = storage.find(gateway.id) as GatewayAsset
      assert connectedGateway.gatewayStatus.orElse(null) == ConnectionStatus.CONNECTED
      assert connectedGateway.tunnelingSupported.orElse(false)

      def syncedAssets = storage.findAll(new AssetQuery().parents(gateway.id).recursive(true))

      assert syncedAssets.size() == 1

      def asset = syncedAssets.first()
      assert asset.id == mapAssetId(gateway.id, "0123456789ABCDEFGHIJKL", false)
      assert asset.parentId == gateway.id
      assert asset.realm == gateway.realm
      assert asset.type == ThingAsset.DESCRIPTOR.name
      assert asset.name == "Compatibility asset"
      assert asset.getAttribute("temperature").flatMap { it.value }.orElse(null) == 21.5

      // Ensure GatewayService has processed the asynchronous persistence event
      assert gatewayService.getLocallyRegisteredGatewayId(asset.id, null) == gateway.id
    }
  }

  protected void cleanupCompatibilityGateway(WebsocketIOClient client, GatewayAsset gateway) {
    client?.disconnect()
    client?.removeAllMessageConsumers()
    if (gateway != null) {
      assert container.getService(AssetStorageService).delete([gateway.id])
      def conditions = new PollingConditions(timeout: 15, delay: 0.1)
      conditions.eventually {
        assert !container.getService(GatewayService).gatewayConnectorMap.containsKey(gateway.id.toLowerCase(Locale.ROOT))
      }
    }
  }
}
