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
import org.openremote.agent.protocol.io.AbstractNettyIOClient
import org.openremote.agent.protocol.websocket.WebsocketIOClient
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.gateway.GatewayConnector
import org.openremote.manager.gateway.GatewayService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.security.ManagerKeycloakIdentityProvider
import org.openremote.manager.setup.SetupService
import org.openremote.manager.system.VersionInfo
import org.openremote.model.asset.AssetsEvent
import org.openremote.model.asset.UserAssetLink
import org.openremote.model.asset.impl.GatewayAsset
import org.openremote.model.auth.OAuthClientCredentialsGrant
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.gateway.GatewayCapabilitiesRequestEvent
import org.openremote.model.gateway.GatewayCapabilitiesResponseEvent
import org.openremote.model.gateway.GatewayInitStartEvent
import org.openremote.model.gateway.GatewayServiceResource
import org.openremote.model.gateway.GatewayTunnelInfo
import org.openremote.model.gateway.GatewayTunnelStartRequestEvent
import org.openremote.model.gateway.GatewayTunnelStartResponseEvent
import org.openremote.model.gateway.GatewayTunnelStopRequestEvent
import org.openremote.model.gateway.GatewayTunnelStopResponseEvent
import org.openremote.model.security.ClientRole
import org.openremote.model.security.User
import org.openremote.model.util.ValueUtil
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList

import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.util.MapAccess.getString

/**
 * Covers the role gate on the gateway tunnel endpoints. A tunnel puts the manager on a host inside
 * the gateway's own network, so every tunnel endpoint requires a tunnel role, and the admin roles do
 * not confer one.
 *
 * <p>Reads run against tunnels placed directly in the gateway service. Writes run against a gateway
 * simulated on the websocket that accepts every tunnel request, so a permitted write opens and closes
 * a real tunnel.
 */
class GatewayTunnelAuthorizationTest extends Specification implements ManagerContainerTrait {

  private static final String OTHER_REALM_GATEWAY_ID = "otherRealmGateway"

  @Shared
  static Map<String, GatewayServiceResource> resources = [:]

  @Shared
  static GatewayService gatewayService

  @Shared
  static AssetStorageService assetStorageService

  @Shared
  static ManagerKeycloakIdentityProvider identityProvider

  @Shared
  static String realm

  @Shared
  static String otherRealm

  /** Connected through the simulated gateway */
  @Shared
  static GatewayAsset gateway

  /** Never connected */
  @Shared
  static GatewayAsset otherGateway

  @Shared
  static WebsocketIOClient gatewayClient

  /** Tunnels the gateway was asked to open */
  @Shared
  static List<GatewayTunnelInfo> openRequests = new CopyOnWriteArrayList<>()

  /** Tunnels the gateway was asked to close */
  @Shared
  static List<GatewayTunnelInfo> closeRequests = new CopyOnWriteArrayList<>()

  @Shared
  static GatewayTunnelInfo httpsTunnel

  @Shared
  static GatewayTunnelInfo sshTunnel

  @Shared
  static GatewayTunnelInfo otherGatewayTunnel

  @Shared
  static GatewayTunnelInfo otherRealmTunnel

  @Shared
  static String originalSSHHostname

  @Shared
  static int originalSSHPort

  @Shared
  static List<String> createdUserIds = []

  def setupSpec() {
    startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    realm = keycloakTestSetup.realmBuilding.name
    otherRealm = keycloakTestSetup.realmCity.name
    gatewayService = container.getService(GatewayService.class)
    assetStorageService = container.getService(AssetStorageService.class)
    identityProvider = container.getService(ManagerIdentityService.class).identityProvider as ManagerKeycloakIdentityProvider

    // Tunnelling is not configured on the test container
    originalSSHHostname = gatewayService.@tunnelSSHHostname
    originalSSHPort = gatewayService.@tunnelSSHPort
    gatewayService.@tunnelSSHHostname = "localhost"
    gatewayService.@tunnelSSHPort = 2222

    gateway = provisionGateway("Connected gateway")
    otherGateway = provisionGateway("Other gateway")
    gatewayClient = connectSimulatedGateway(gateway)

    httpsTunnel = new GatewayTunnelInfo(realm, gateway.id, GatewayTunnelInfo.Type.HTTPS, "localhost", 443)
    sshTunnel = new GatewayTunnelInfo(realm, gateway.id, GatewayTunnelInfo.Type.TCP, "10.0.0.1", 22)
    otherGatewayTunnel = new GatewayTunnelInfo(realm, otherGateway.id, GatewayTunnelInfo.Type.TCP, "10.0.0.1", 22)
    otherRealmTunnel = new GatewayTunnelInfo(otherRealm, OTHER_REALM_GATEWAY_ID, GatewayTunnelInfo.Type.TCP, "10.0.0.1", 22)

    // testuser2 holds write:user, read:map and read:assets, and is not restricted
    resources.noTunnelRoles = resourceFor(realm, "testuser2", "testuser2")
    resources.adminWithoutTunnelRoles = resourceForNewUser("tunneladmin", [READ_ADMIN_ROLE, WRITE_ADMIN_ROLE])
    resources.tunnelReader = resourceForNewUser("tunnelreader", [READ_TUNNELS_ROLE])
    resources.tunnelWriter = resourceForNewUser("tunnelwriter", [READ_TUNNELS_ROLE, WRITE_TUNNELS_ROLE])
    resources.compositeReader = resourceForNewUser("tunnelcompositesreader", [ClientRole.READ.value])
    resources.compositeWriter = resourceForNewUser("tunnelcompositeswriter", [ClientRole.READ.value, ClientRole.WRITE.value])
    resources.restrictedLinked = resourceForNewUser("tunnelrestrictedlinked", [READ_TUNNELS_ROLE, WRITE_TUNNELS_ROLE], true, gateway.id)
    resources.restrictedUnlinked = resourceForNewUser("tunnelrestrictedunlinked", [READ_TUNNELS_ROLE, WRITE_TUNNELS_ROLE], true)
    resources.superUser = resourceFor(MASTER_REALM, MASTER_REALM_ADMIN_USER, getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT))
  }

  def setup() {
    // Every feature starts from the same active tunnels
    removeTunnels()
    [httpsTunnel, sshTunnel, otherGatewayTunnel, otherRealmTunnel].each {
      gatewayService.@tunnelInfos.put(it.id, it)
    }
    openRequests.clear()
    closeRequests.clear()
  }

  def cleanupSpec() {
    gatewayClient?.disconnect()
    gatewayClient?.removeAllMessageConsumers()
    if (gatewayService != null) {
      removeTunnels()
      gatewayService.@tunnelSSHHostname = originalSSHHostname
      gatewayService.@tunnelSSHPort = originalSSHPort
    }
    def gatewayIds = [gateway, otherGateway].findAll()*.id
    if (!gatewayIds.isEmpty()) {
      def conditions = new PollingConditions(timeout: 15, delay: 0.2)
      assetStorageService.delete(gatewayIds)
      conditions.eventually {
        assert gatewayIds.every {
          !gatewayService.gatewayConnectorMap.containsKey(it.toLowerCase(Locale.ROOT))
        }
      }
    }
    createdUserIds.each { identityProvider.deleteUser(realm, it) }
    createdUserIds.clear()
    resources.clear()
  }

  private void removeTunnels() {
    def gatewayIds = [gateway?.id, otherGateway?.id, OTHER_REALM_GATEWAY_ID]
    gatewayService.@tunnelInfos.values().removeIf { it.gatewayId in gatewayIds }
  }

  private GatewayServiceResource resourceFor(String userRealm, String username, String password) {
    def accessToken = authenticate(container, userRealm, KEYCLOAK_CLIENT_ID, username, password)
    return getClientApiTarget(serverUri(serverPort), userRealm, accessToken).proxy(GatewayServiceResource.class)
  }

  /** Provisions a user in the realm and returns the tunnel resource authenticated as them. */
  private GatewayServiceResource resourceForNewUser(String username, List<String> roles, boolean restricted = false, String linkedAssetId = null) {
    def user = identityProvider.createUpdateUser(realm, new User().setUsername(username).setEnabled(true), username, true)
    createdUserIds << user.id
    identityProvider.updateUserClientRoles(realm, user.id, KEYCLOAK_CLIENT_ID, roles as String[])

    if (restricted) {
      identityProvider.updateUserRealmRoles(
              realm,
              user.id,
              identityProvider.addUserRealmRoles(realm, user.id, RESTRICTED_USER_REALM_ROLE))
    }

    if (linkedAssetId != null) {
      assetStorageService.storeUserAssetLinks([new UserAssetLink(realm, user.id, linkedAssetId)])
    }

    return resourceFor(realm, username, username)
  }

  private GatewayAsset provisionGateway(String name) {
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)
    def asset = assetStorageService.merge(new GatewayAsset(name).setRealm(realm))
    conditions.eventually {
      asset = assetStorageService.find(asset.id, true) as GatewayAsset
      assert asset.clientId.present
      assert asset.clientSecret.present
    }
    return asset
  }

  /** Connects as the gateway, which then accepts everything the manager asks of it. */
  private WebsocketIOClient connectSimulatedGateway(GatewayAsset asset) {
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)
    def tokenUri = new URIBuilder(identityProvider.keycloakPublicUrl)
            .setPath("auth/realms/${asset.realm}/protocol/openid-connect/token").build().toString()
    def client = new WebsocketIOClient(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=${asset.realm}").build(),
            null,
            new OAuthClientCredentialsGrant(tokenUri, asset.clientId.get(), asset.clientSecret.get(), null)
            .setBasicAuthHeader(true))
    client.setEncoderDecoderProvider({
      [new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client)].toArray(new ChannelHandler[0])
    })
    client.addMessageConsumer({ String message ->
      if (message.startsWith(SharedEvent.MESSAGE_PREFIX)) {
        def response = responseTo(ValueUtil.JSON.readValue(message.substring(SharedEvent.MESSAGE_PREFIX.length()), SharedEvent))
        if (response != null) {
          client.sendMessage(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(response).get())
        }
      }
    })
    client.connect()

    conditions.eventually {
      def connector = gatewayService.gatewayConnectorMap.get(asset.id.toLowerCase(Locale.ROOT))
      assert connector != null
      assert connector.isConnected()
      assert !connector.isInitialSyncInProgress()
      assert connector.isTunnellingSupported()
    }
    return client
  }

  private static SharedEvent responseTo(SharedEvent request) {
    SharedEvent response
    String messageId = request.messageID
    if (request instanceof GatewayInitStartEvent) {
      // The gateway has no assets of its own to synchronize
      response = new AssetsEvent([])
      messageId = GatewayConnector.ASSET_READ_EVENT_NAME_INITIAL
    } else if (request instanceof GatewayCapabilitiesRequestEvent) {
      response = new GatewayCapabilitiesResponseEvent(VersionInfo.getGatewayApiVersion(), true)
    } else if (request instanceof GatewayTunnelStartRequestEvent) {
      openRequests << request.info
      response = new GatewayTunnelStartResponseEvent(null)
    } else if (request instanceof GatewayTunnelStopRequestEvent) {
      closeRequests << request.info
      response = new GatewayTunnelStopResponseEvent(null)
    } else {
      return null
    }
    response.messageID = messageId
    return response
  }

  /** A tunnel to a host on the gateway's network that is not already active. */
  private GatewayTunnelInfo newTunnel(String gatewayId) {
    return new GatewayTunnelInfo(realm, gatewayId, GatewayTunnelInfo.Type.TCP, "10.0.0.2", 5432)
  }

  private static Set<String> ids(GatewayTunnelInfo... tunnels) {
    return tunnels.collect { it.id } as Set
  }

  /** The status the call was refused with, or null if it succeeded. */
  private static Integer refusalOf(Closure<?> call) {
    try {
      call.call()
      return null
    } catch (WebApplicationException e) {
      return e.response.withCloseable { r -> r.status }
    }
  }

  def "#user is refused every tunnel endpoint"() {

    expect: "every tunnel endpoint is refused without a tunnel role, admin roles included"
    refusalOf { resource.getAllActiveTunnelInfos(null, realm) } == 403
    refusalOf { resource.getGatewayActiveTunnelInfos(null, realm, gateway.id) } == 403
    refusalOf { resource.getActiveTunnelInfo(null, realm, gateway.id, "10.0.0.1", 22) } == 403
    refusalOf { resource.startTunnel(newTunnel(gateway.id)) } == 403
    refusalOf { resource.stopTunnel(sshTunnel) } == 403

    and: "the gateway was never asked to open or close a tunnel"
    openRequests.isEmpty()
    closeRequests.isEmpty()

    where:
    user << ["noTunnelRoles", "adminWithoutTunnelRoles"]
    resource = resources[user]
  }

  def "#user retrieves active tunnels by realm, gateway and endpoint"() {

    expect: "the realm's tunnels are retrieved without those of other realms"
    ids(resource.getAllActiveTunnelInfos(null, realm)) == ids(httpsTunnel, sshTunnel, otherGatewayTunnel)

    and: "each gateway's tunnels are retrieved without those of other gateways"
    ids(resource.getGatewayActiveTunnelInfos(null, realm, gateway.id)) == ids(httpsTunnel, sshTunnel)
    ids(resource.getGatewayActiveTunnelInfos(null, realm, otherGateway.id)) == ids(otherGatewayTunnel)

    and: "a tunnel is retrieved by gateway and endpoint, and an endpoint without one returns nothing"
    resource.getActiveTunnelInfo(null, realm, gateway.id, "10.0.0.1", 22).id == sshTunnel.id
    resource.getActiveTunnelInfo(null, realm, otherGateway.id, "10.0.0.1", 22).id == otherGatewayTunnel.id
    resource.getActiveTunnelInfo(null, realm, gateway.id, "10.0.0.1", 23) == null

    where:
    user << ["tunnelReader", "compositeReader", "compositeWriter", "superUser"]
    resource = resources[user]
  }

  def "A user holding only the tunnel read role cannot open or close tunnels"() {

    expect: "both writes are refused"
    refusalOf { resource.startTunnel(newTunnel(gateway.id)) } == 403
    refusalOf { resource.stopTunnel(sshTunnel) } == 403

    and: "the gateway was never asked to open or close a tunnel"
    openRequests.isEmpty()
    closeRequests.isEmpty()

    where:
    user << ["tunnelReader", "compositeReader"]
    resource = resources[user]
  }

  def "A restricted user retrieves and reaches only the gateway linked to them"() {

    given: "a restricted user holding both tunnel roles and linked to the connected gateway"
    def resource = resources.restrictedLinked

    expect: "the realm's tunnels are filtered down to the linked gateway's"
    ids(resource.getAllActiveTunnelInfos(null, realm)) == ids(httpsTunnel, sshTunnel)

    and: "the linked gateway's tunnels are retrieved"
    ids(resource.getGatewayActiveTunnelInfos(null, realm, gateway.id)) == ids(httpsTunnel, sshTunnel)
    resource.getActiveTunnelInfo(null, realm, gateway.id, "10.0.0.1", 22).id == sshTunnel.id

    and: "a gateway in the same realm that is not linked to them is refused"
    refusalOf { resource.getGatewayActiveTunnelInfos(null, realm, otherGateway.id) } == 404
    refusalOf { resource.getActiveTunnelInfo(null, realm, otherGateway.id, "10.0.0.1", 22) } == 404
    refusalOf { resource.startTunnel(newTunnel(otherGateway.id)) } == 404
    refusalOf { resource.stopTunnel(otherGatewayTunnel) } == 404
  }

  def "A restricted user linked to no gateway retrieves no tunnels and reaches no gateway"() {

    given: "a restricted user holding both tunnel roles and linked to nothing"
    def resource = resources.restrictedUnlinked

    expect: "the realm's tunnels are filtered down to none"
    resource.getAllActiveTunnelInfos(null, realm).length == 0

    and: "every gateway is refused"
    refusalOf { resource.getGatewayActiveTunnelInfos(null, realm, gateway.id) } == 404
    refusalOf { resource.getActiveTunnelInfo(null, realm, gateway.id, "10.0.0.1", 22) } == 404
    refusalOf { resource.startTunnel(newTunnel(gateway.id)) } == 404
    refusalOf { resource.stopTunnel(sshTunnel) } == 404

    and: "the gateway was never asked to open or close a tunnel"
    openRequests.isEmpty()
    closeRequests.isEmpty()
  }

  def "A tunnel role does not reach another realm"() {

    given: "a user holding both tunnel roles"
    def resource = resources.tunnelWriter

    expect: "a super user retrieves the other realm's tunnel"
    ids(resources.superUser.getAllActiveTunnelInfos(null, otherRealm)) == ids(otherRealmTunnel)

    and: "the user is refused every request naming the other realm"
    refusalOf { resource.getAllActiveTunnelInfos(null, otherRealm) } == 404
    refusalOf { resource.getGatewayActiveTunnelInfos(null, otherRealm, gateway.id) } == 404
    refusalOf { resource.getActiveTunnelInfo(null, otherRealm, gateway.id, "10.0.0.1", 22) } == 404
    refusalOf { resource.startTunnel(newTunnel(gateway.id).setRealm(otherRealm)) } == 404
    refusalOf { resource.stopTunnel(otherRealmTunnel) } == 404
  }

  def "#user opens and closes a tunnel through the gateway"() {

    given: "a tunnel to a host on the gateway's network"
    def tunnel = newTunnel(gateway.id)

    when: "the user opens it"
    def opened = resource.startTunnel(tunnel)

    then: "the gateway was asked to open it and it is active"
    opened == tunnel
    openRequests == [tunnel]
    ids(resource.getGatewayActiveTunnelInfos(null, realm, gateway.id)) == ids(httpsTunnel, sshTunnel, tunnel)

    when: "the user closes it"
    resource.stopTunnel(opened)

    then: "the gateway was asked to close it and it is no longer active"
    closeRequests == [tunnel]
    ids(resource.getGatewayActiveTunnelInfos(null, realm, gateway.id)) == ids(httpsTunnel, sshTunnel)

    where:
    user << ["tunnelWriter", "compositeWriter", "restrictedLinked", "superUser"]
    resource = resources[user]
  }
}
