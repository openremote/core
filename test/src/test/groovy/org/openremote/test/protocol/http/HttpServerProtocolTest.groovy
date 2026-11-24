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
package org.openremote.test.protocol.http

import com.fasterxml.jackson.databind.node.ObjectNode
import io.undertow.servlet.Servlets
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.*
import org.jboss.resteasy.specimpl.ResteasyUriInfo
import org.jboss.resteasy.util.BasicAuthHelper
import org.openremote.agent.protocol.http.AbstractHTTPServerProtocol
import org.openremote.container.web.OAuthServerResponse
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.model.asset.Asset
import org.openremote.model.asset.agent.Agent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.auth.OAuthGrant
import org.openremote.model.auth.OAuthPasswordGrant
import org.openremote.model.auth.OAuthRefreshTokenGrant
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.util.ValueUtil
import org.openremote.setup.integration.protocol.http.HTTPServerTestAgent
import org.openremote.setup.integration.protocol.http.TestHTTPServerProtocol
import org.openremote.setup.integration.protocol.http.TestResource
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static org.openremote.model.util.MapAccess.getString
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.value.ValueType.TEXT

class HttpServerProtocolTest extends Specification implements ManagerContainerTrait {

  def tryPost(TestResource authenticatedTestResource, Asset testAsset) {
    // There's some weird behavior where sometimes a forbidden exception is thrown and sometimes not
    try {
      authenticatedTestResource.postAsset(testAsset)
    } catch (Exception ex) {
      getLOG().warn("Failed to call post: " + ex.getMessage())
    }
  }

  def "Check HTTP server protocol and JAX-RS deployment"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, initialDelay: 1)

    and: "the container starts"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def agentService = container.getService(AgentService.class)

    and: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )

    when: "a test HTTP server agent with a test deployment is created"
    def agent = new HTTPServerTestAgent("Test agent")
            .setRealm(MASTER_REALM)
            .setRoleBasedSecurity(true)

    and: "the agent is added to the asset service"
    agent = assetStorageService.merge(agent)
    TestHTTPServerProtocol protocolInstance
    def testAsset = new ThingAsset("Test Asset")
            .setId("12345")
            .addOrReplaceAttributes(
            new Attribute<>("attribute1", TEXT, "Test")
            )
            .setLocation(new GeoJSONPoint(1d, 2d))

    then: "the protocol should be deployed"
    conditions.eventually {
      assert agentService.getProtocolInstance(agent.id) != null
      protocolInstance = agentService.getProtocolInstance(agent.id) as TestHTTPServerProtocol
      agent = assetStorageService.find(agent.id)
      assert agent.getAttribute(Agent.STATUS).flatMap {
        it.value
      }.orElse(null) == ConnectionStatus.CONNECTED
      assert Servlets.defaultContainer().getDeployment(AbstractHTTPServerProtocol.getDeploymentName(TestHTTPServerProtocol.class, agent.id)) != null
    }

    when: "the client proxy for the test resource (to be deployed by the protocol) is defined"
    def authenticatedTestResource = getClientTarget(
            serverUri(serverPort)
            .path(protocolInstance.getDeploymentPath()),
            accessToken).proxy(TestResource.class)
    def testResource = getClientTarget(
            serverUri(serverPort)
            .path(protocolInstance.getDeploymentPath()),
            null).proxy(TestResource.class)

    then: "it should be possible to post an asset to this protocol's endpoint"
    conditions.eventually {
      tryPost(authenticatedTestResource, testAsset)
      assert protocolInstance.resource1.postedAssets.size() == 1
    }

    then: "the asset should be stored in the test protocol"
    conditions.eventually {
      def testServerProtocol = (TestHTTPServerProtocol)agentService.getProtocolInstance(agent.id)
      assert testServerProtocol != null
      assert testServerProtocol.resource1.postedAssets.size() == 1
      assert testServerProtocol.resource1.postedAssets.get(0).name == "Test Asset"
      assert testServerProtocol.resource1.postedAssets.get(0).type == ThingAsset.DESCRIPTOR.getName()
      assert testServerProtocol.resource1.postedAssets.get(0).getLocation().map{
        it.x
      }.orElse(null) == 1d
      assert testServerProtocol.resource1.postedAssets.get(0).getLocation().map{
        it.y
      }.orElse(null) == 2d
    }

    when: "the authenticated test resource is used to get an asset"
    def asset = authenticatedTestResource.getAsset("12345")

    then: "the asset should match the posted asset"
    assert asset.name == testAsset.name
    assert asset.type == testAsset.type
    assert asset.getLocation().map{
      it.coordinates.x
    }.orElse(-1d) == testAsset.getLocation().map{
      it.coordinates.x
    }.orElse(0d)
    assert asset.getLocation().map{
      it.coordinates.y
    }.orElse(-1d) == testAsset.getLocation().map{
      it.coordinates.y
    }.orElse(0d)

    when: "the un-authenticated test resource is used to post an asset"
    testResource.postAsset(testAsset)

    then: "an exception should be thrown"
    thrown ForbiddenException

    when: "an additional instance of the Test HTTP Server protocol is deployed without security enabled"
    def agent2 = new HTTPServerTestAgent("Test agent 2")
            .setRealm(MASTER_REALM)
            .setRoleBasedSecurity(false)
    TestHTTPServerProtocol protocolInstance2
    agent2 = assetStorageService.merge(agent2)

    then: "the protocol should be deployed"
    conditions.eventually {
      protocolInstance2 = agentService.getProtocolInstance(agent2.id) as TestHTTPServerProtocol
      assert protocolInstance2 != null
      assert Servlets.defaultContainer().getDeployment(AbstractHTTPServerProtocol.getDeploymentName(TestHTTPServerProtocol.class, agent2.id)) != null
    }

    when: "an un-authenticated test resource proxy is created for the new deployment"
    def testResource2 = getClientTarget(
            serverUri(serverPort)
            .path(protocolInstance2.getDeploymentPath()),
            null).proxy(TestResource.class)

    and: "the new test resource is used to post an asset"
    testAsset = new ThingAsset("Test Asset 2")
    testAsset.setId(UniqueIdentifierGenerator.generateId())
    testAsset.addOrReplaceAttributes(
            new Attribute<>("attribute2", TEXT, "Test")
    )
    testAsset.setLocation(new GeoJSONPoint(3d, 4d))
    testResource2.postAsset(testAsset)

    then: "the asset should be stored in the test protocol"
    conditions.eventually {
      def testServerProtocol = (TestHTTPServerProtocol)agentService.getProtocolInstance(agent2.id)
      assert testServerProtocol != null
      assert testServerProtocol.resource1.postedAssets.size() == 1
      assert testServerProtocol.resource1.postedAssets.get(0).name == "Test Asset 2"
      assert testServerProtocol.resource1.postedAssets.get(0).id == testAsset.id
      assert testServerProtocol.resource1.postedAssets.get(0).type == ThingAsset.DESCRIPTOR.getName()
      assert testServerProtocol.resource1.postedAssets.get(0).getLocation().map{
        it.x
      }.orElse(null) == 3d
      assert testServerProtocol.resource1.postedAssets.get(0).getLocation().map{
        it.y
      }.orElse(null) == 4d
    }

    when: "the new test resource is used to get an asset"
    asset = testResource2.getAsset(testAsset.id)

    then: "the asset should match the posted asset"
    assert asset.name == testAsset.name
    assert asset.type == testAsset.type
    assert asset.getLocation().map{
      it.coordinates.x
    }.orElse(-1d) == testAsset.getLocation().map{
      it.coordinates.x
    }.orElse(0d)
    assert asset.getLocation().map{
      it.coordinates.y
    }.orElse(-1d) == testAsset.getLocation().map{
      it.coordinates.y
    }.orElse(0d)

    when: "an agent is deleted"
    assetStorageService.delete([agent.id])

    then: "the associated protocol instance should be un-deployed"
    // Un-deploying takes the agent lock and tears down a servlet deployment, so it can be held up by other agents
    // being deployed at the same time
    new PollingConditions(timeout: 30, initialDelay: 1).eventually {
      assert agentService.getProtocolInstance(agent.id) == null
      assert Servlets.defaultContainer().getDeployment(AbstractHTTPServerProtocol.getDeploymentName(TestHTTPServerProtocol.class, agent.id)) == null
    }

    then: "if an attempt is made to use the removed endpoint then no asset should be posted"
    conditions.eventually {
      def postCount = protocolInstance.resource1.postedAssets.size()
      tryPost(authenticatedTestResource, testAsset)
      assert postCount == protocolInstance.resource1.postedAssets.size()
    }
  }
}
