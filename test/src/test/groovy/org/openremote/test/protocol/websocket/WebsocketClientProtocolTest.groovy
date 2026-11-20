/*
 * Copyright 2019, OpenRemote Inc.
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

import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.openremote.agent.protocol.simulator.SimulatorProtocol
import org.openremote.agent.protocol.websocket.*
import org.openremote.container.web.WebTargetBuilder
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.event.ClientEventService
import org.openremote.manager.setup.SetupService
import org.openremote.model.Constants
import org.openremote.model.asset.AssetFilter
import org.openremote.model.asset.ReadAttributeEvent
import org.openremote.model.asset.agent.Agent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.auth.OAuthPasswordGrant
import org.openremote.model.event.TriggeredEventSubscription
import org.openremote.model.event.shared.EventSubscription
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.filter.StringPredicate
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.*
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.Constants.MASTER_REALM_ADMIN_USER
import static org.openremote.model.util.MapAccess.getString
import static org.openremote.model.value.MetaItemType.AGENT_LINK
import static org.openremote.model.value.ValueType.NUMBER

class WebsocketClientProtocolTest extends Specification implements ManagerContainerTrait {

  @Shared
  def mockServer = new ClientRequestFilter() {

    private boolean agentSubscriptionDone = false
    private boolean attribute1SubscriptionDone = false
    private boolean attribute2SubscriptionDone = false
    private boolean finished = false

    @Override
    void filter(ClientRequestContext requestContext) throws IOException {
      if (finished) {
        return
      }

      def requestUri = requestContext.uri
      def requestPath = requestUri.scheme + "://" + requestUri.host + requestUri.path

      if (!requestPath.contains("mockapi")) {
        return
      }

      // Check auth header is present
      def authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION)
      if (authHeader == null || authHeader.length() < 8) {
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).build())
        return
      }

      switch (requestPath) {

        case "https://mockapi/assets":
          if (requestContext.method == HttpMethod.POST
                  && requestContext.getHeaderString("header1") == "header1Value1"
                  && requestContext.getHeaderString("header2") == "header2Value1,header2Value2"
                  && requestContext.getHeaderString("Content-type") == MediaType.APPLICATION_JSON) {

            String bodyStr = (String)requestContext.getEntity()
            AssetQuery assetQuery = ValueUtil.JSON.readValue(bodyStr, AssetQuery.class)
            if (assetQuery != null && assetQuery.ids != null && assetQuery.ids.size() == 1) {
              agentSubscriptionDone = true
              requestContext.abortWith(Response.ok().build())
              return
            }
          }
          break
        case "https://mockapi/targetTemperature":
          attribute1SubscriptionDone = true
          requestContext.abortWith(Response.ok().build())
          break
        case "https://mockapi/co2Level":
          attribute2SubscriptionDone = true
          requestContext.abortWith(Response.ok().build())
          break
      }

      requestContext.abortWith(Response.serverError().build())
    }
  }

  @SuppressWarnings("GroovyAccessibility")
  def "Check websocket client protocol and linked attribute deployment"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container starts"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def agentService = container.getService(AgentService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def clientEventService = container.getService(ClientEventService.class)

    and: "the built in JAX-RS client is injected with the mock server filter"
    WebTargetBuilder.client.register(mockServer, Integer.MAX_VALUE)

    and: "a Websocket client agent is created to connect to this tests manager"
    def agent = new WebsocketAgent("Test agent")
            .setRealm(Constants.MASTER_REALM)
            .setConnectURI("ws://127.0.0.1:$serverPort/websocket/events?Realm=master")
            .setOAuthGrant(new OAuthPasswordGrant(getTokenUri("master"),
            KEYCLOAK_CLIENT_ID,
            null,
            null,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)))
            .setConnectSubscriptions([
              new WebsocketSubscriptionImpl().body(EventSubscription.SUBSCRIBE_MESSAGE_PREFIX + ValueUtil.asJSON(
                      new EventSubscription(
                              AttributeEvent.class,
                              new AssetFilter<AttributeEvent>().setAssetIds(managerTestSetup.apartment1LivingroomId),
                              "1")).orElse(null)),
              new WebsocketHTTPSubscription()
              .contentType(MediaType.APPLICATION_JSON)
              .method(WebsocketHTTPSubscription.Method.POST)
              .headers(new ValueType.MultivaluedStringMap([
                "header1" : ["header1Value1"],
                "header2" : ["header2Value1", "header2Value2"]
              ]))
              .uri("https://mockapi/assets")
              .body(
                      ValueUtil.asJSON(new AssetQuery().ids(managerTestSetup.apartment1LivingroomId)).orElse(null)
                      )
            ] as WebsocketSubscription[]
    )

    when: "the agent is added to the asset service"
    agent = assetStorageService.merge(agent)

    then: "the protocol should authenticate and the agent status should become CONNECTED"
    conditions.eventually {
      agent = assetStorageService.find(agent.id, Agent.class)
      assert agent.getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
    }

    and: "the subscriptions should have been executed"
    conditions.eventually {
      assert mockServer.agentSubscriptionDone
    }

    when: "an asset is created with attributes linked to the agent"
    def asset = new ThingAsset("Test Asset")
            .setParent(agent)
            .addOrReplaceAttributes(
            // write attribute value
            new Attribute<>("readWriteTargetTemp", NUMBER)
            .addMeta(
                    new MetaItem<>(AGENT_LINK, new WebsocketAgentLink(agent.id)
                    .setWriteValue(SharedEvent.MESSAGE_PREFIX +
                    ValueUtil.asJSON(new AttributeEvent(
                            managerTestSetup.apartment1LivingroomId,
                            "targetTemperature",
                            0.12345))
                    .orElse(ValueUtil.NULL_LITERAL)
                    .replace("0.12345", "%VALUE%")
                    )
                    .setMessageMatchFilters(
                            [
                              new RegexValueFilter(TriggeredEventSubscription.MESSAGE_PREFIX + "(.*)", true, false).setMatchGroup(1),
                              new JsonPathFilter("\$..ref.name", true, false)
                            ] as ValueFilter[]
                            )
                    .setMessageMatchPredicate(
                            new StringPredicate("targetTemperature")
                            )
                    .setValueFilters(
                            [
                              new SubStringValueFilter(TriggeredEventSubscription.MESSAGE_PREFIX.length()),
                              new JsonPathFilter("\$..events[?(@.ref.name == \"targetTemperature\")].value", true, false)
                            ] as ValueFilter[]
                            )
                    .setWebsocketSubscriptions(
                            [
                              new WebsocketSubscriptionImpl().body(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(
                                      new ReadAttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature")
                                      ).orElse(null)),
                              new WebsocketHTTPSubscription()
                              .contentType(MediaType.APPLICATION_JSON)
                              .method(WebsocketHTTPSubscription.Method.GET)
                              .uri("https://mockapi/targetTemperature")
                            ] as WebsocketSubscription[]
                            ))
                    ),
            new Attribute<>("readCo2Level", NUMBER)
            .addMeta(
                    new MetaItem<>(AGENT_LINK, new WebsocketAgentLink(agent.id)
                    .setMessageMatchFilters(
                            [
                              new SubStringValueFilter(TriggeredEventSubscription.MESSAGE_PREFIX.length()),
                              new JsonPathFilter("\$..ref.name", true, false)
                            ] as ValueFilter[]
                            )
                    .setMessageMatchPredicate(
                            new StringPredicate("co2Level")
                            )
                    .setValueFilters(
                            [
                              new SubStringValueFilter(TriggeredEventSubscription.MESSAGE_PREFIX.length()),
                              new JsonPathFilter("\$..events[?(@.ref.name == \"co2Level\")].value", true, false),
                            ] as ValueFilter[]
                            )
                    .setWebsocketSubscriptions(
                            [
                              new WebsocketSubscriptionImpl().body(SharedEvent.MESSAGE_PREFIX + ValueUtil.asJSON(
                                      new ReadAttributeEvent(managerTestSetup.apartment1LivingroomId, "co2Level")
                                      ).orElse(null)),
                              new WebsocketHTTPSubscription()
                              .contentType(MediaType.APPLICATION_JSON)
                              .method(WebsocketHTTPSubscription.Method.GET)
                              .uri("https://mockapi/co2Level")
                            ] as WebsocketSubscription[]
                            ))
                    )
    )

    and: "the asset is merged into the asset service"
    asset = assetStorageService.merge(asset)

    then: "the attribute websocket subscriptions should have been executed"
    conditions.eventually {
      assert mockServer.attribute1SubscriptionDone
      assert mockServer.attribute2SubscriptionDone
    }

    when: "the source attribute are updated"
    ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "targetTemperature", 99d))
    ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(new AttributeEvent(managerTestSetup.apartment1LivingroomId, "co2Level", 50d))

    then: "the values should be stored in the database"
    conditions.eventually {
      def livingRoom = assetStorageService.find(managerTestSetup.apartment1LivingroomId)
      assert livingRoom != null
      assert livingRoom.getAttribute("targetTemperature").flatMap{it.value}.orElse(0d) == 99d
      assert livingRoom.getAttribute("co2Level").flatMap{it.value}.orElse(0i) == 50i
    }

    then: "the linked attributes should also have the updated values of the subscribed attributes"
    conditions.eventually {
      asset = assetStorageService.find(asset.getId(), true)
      assert asset.getAttribute("readCo2Level").get().getValue().orElse(null) == 50d
      assert asset.getAttribute("readWriteTargetTemp").get().getValue().orElse(null) == 99d
    }

    when: "a linked attribute value is updated"
    def attributeEvent = new AttributeEvent(asset.id,
            "readWriteTargetTemp",
            19.5)
    assetProcessingService.sendAttributeEvent(attributeEvent)

    then: "the linked targetTemperature attribute should contain this written value (it should have been written to the target temp attribute and then read back again)"
    conditions.eventually {
      asset = assetStorageService.find(asset.getId(), true)
      assert asset.getAttribute("readWriteTargetTemp").flatMap{
        it.getValue()
      }.orElse(null) == 19.5d
    }

    when: "the co2level changes"
    def co2LevelIncrement = new AttributeEvent(
            managerTestSetup.apartment1LivingroomId, "co2Level", 600
    )
    ((SimulatorProtocol)agentService.getProtocolInstance(managerTestSetup.apartment1ServiceAgentId)).updateSensor(co2LevelIncrement)

    then: "the linked co2Level attribute should get the new value"
    conditions.eventually {
      asset = assetStorageService.find(asset.getId(), true)
      assert asset.getAttribute("readCo2Level").flatMap{it.getValue()}.orElse(null) == 600d
    }

    cleanup: "disable mock filter"
    mockServer.finished = true
  }
}
