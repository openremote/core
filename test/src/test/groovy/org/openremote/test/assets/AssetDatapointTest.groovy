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
package org.openremote.test.assets

import jakarta.ws.rs.WebApplicationException
import io.netty.channel.ChannelHandler
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.util.CharsetUtil
import org.apache.http.client.utils.URIBuilder
import org.openremote.agent.protocol.io.AbstractNettyIOClient
import org.openremote.agent.protocol.simulator.SimulatorProtocol
import org.openremote.agent.protocol.websocket.WebsocketIOClient
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.datapoint.AssetPredictedDatapointService
import org.openremote.manager.event.ClientEventService
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.BuildingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.model.auth.OAuthPasswordGrant
import org.openremote.model.datapoint.AssetPredictedDatapointEvent
import org.openremote.model.datapoint.AssetPredictedDatapointResource
import org.openremote.model.datapoint.ValueDatapoint
import org.openremote.model.datapoint.query.AssetDatapointIntervalQuery
import org.openremote.model.event.TriggeredEventSubscription
import org.openremote.model.event.shared.EventSubscription
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.event.shared.UnauthorizedEventSubscription
import org.openremote.model.security.ClientRole
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import static java.util.concurrent.TimeUnit.*
import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID
import static org.openremote.model.Constants.MASTER_REALM
import static org.openremote.model.value.MetaItemType.ACCESS_PUBLIC_WRITE
import static org.openremote.setup.integration.ManagerTestSetup.thingLightToggleAttributeName
import static spock.util.matcher.HamcrestMatchers.closeTo

class AssetDatapointTest extends Specification implements ManagerContainerTrait {

  protected static <T> T messageFromString(String message) {
    try {
      def isSubscription = message.startsWith(EventSubscription.SUBSCRIBED_MESSAGE_PREFIX)
      def isTriggered = !isSubscription && message.startsWith(TriggeredEventSubscription.MESSAGE_PREFIX)
      def isUnauthorized = !isSubscription && !isTriggered && message.startsWith(UnauthorizedEventSubscription.MESSAGE_PREFIX)
      message = message.substring(message.indexOf(":") + 1)
      return ValueUtil.JSON.readValue(message,
              isSubscription ? EventSubscription.class : isTriggered ? TriggeredEventSubscription.class : isUnauthorized ? UnauthorizedEventSubscription.class : SharedEvent.class)
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

  protected WebsocketIOClient<String> createPredictedDatapointWebsocketClient(
          int serverPort,
          String websocketRealm,
          String authRealm,
          String username,
          String password
  ) {
    def client = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=$websocketRealm").build(),
            null,
            new OAuthPasswordGrant(
                    "http://127.0.0.1:8081/auth/realms/$authRealm/protocol/openid-connect/token",
                    KEYCLOAK_CLIENT_ID,
                    null,
                    null,
                    username,
                    password
                    )
    )
    client.setEncoderDecoderProvider({
      [
        new StringEncoder(CharsetUtil.UTF_8),
        new StringDecoder(CharsetUtil.UTF_8),
        new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client)
      ].toArray(new ChannelHandler[0])
    })
    return client
  }

  def "Test number and toggle attribute storage, retrieval and purging"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    when: "the demo agent and thing have been deployed"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def agentService = container.getService(AgentService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetDatapointService = container.getService(AssetDatapointService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def clientEventService = container.getService(ClientEventService.class)

    and: "the clock is stopped for testing purposes and advanced to the next hour"
    stopPseudoClock()
    advancePseudoClock(Instant.ofEpochMilli(getClockTimeOf(container)).truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS).toEpochMilli() - getClockTimeOf(container), TimeUnit.MILLISECONDS, container)

    and: "a resource client is created"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )
    // Resteasy client has issues with @Suspended annotation so not used for now
    //def datapointResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(AssetDatapointResource.class)
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(AssetPredictedDatapointResource.class)

    then: "the simulator protocol instance should have been initialized and attributes linked"
    conditions.eventually {
      assert agentService.protocolInstanceMap.get(managerTestSetup.agentId) != null
      assert ((SimulatorProtocol) agentService.protocolInstanceMap.get(managerTestSetup.agentId)).linkedAttributes.size() == 4
      assert ((SimulatorProtocol) agentService.protocolInstanceMap.get(managerTestSetup.agentId)).linkedAttributes.get(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption")).getValue(Double.class).orElse(0d) == 12.345d
    }

    when: "an attribute linked to the simulator agent receives some values"
    def simulatorProtocol = ((SimulatorProtocol) agentService.protocolInstanceMap.get(managerTestSetup.agentId))
    advancePseudoClock(60, SECONDS, container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 13.3d)
    advancePseudoClock(60, SECONDS, container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), null)
    advancePseudoClock(60, SECONDS, container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 13.3d)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert thing.getAttribute("light1PowerConsumption").flatMap {
        it.getValue(Double.class)
      }.orElse(null) == 13.3d
    }

    when: "a simulated sensor receives a new value"
    advancePseudoClock(60, SECONDS, container)
    def datapoint1ExpectedTimestamp = getClockTimeOf(container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 13.5d)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert thing.getAttribute("light1PowerConsumption").flatMap {
        it.getValue(Double.class)
      }.orElse(null) == 13.5d
    }

    when: "a simulated sensor receives a new value"
    advancePseudoClock(60, SECONDS, container)
    def datapoint2ExpectedTimestamp = getClockTimeOf(container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 14.4d)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert thing.getAttribute("light1PowerConsumption").flatMap {
        it.getValue(Double.class)
      }.orElse(null) == 14.4d
    }

    when: "a simulated sensor receives a new value"
    advancePseudoClock(60, SECONDS, container)
    def datapoint3ExpectedTimestamp = getClockTimeOf(container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), 15.5d)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert thing.getAttribute("light1PowerConsumption").flatMap {
        it.getValue(Double.class)
      }.orElse(null) == 15.5d
    }

    when: "a simulated sensor receives a new value"
    advancePseudoClock(60, SECONDS, container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"), null)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert !thing.getAttribute("light1PowerConsumption").flatMap {
        it.getValue()
      }.isPresent()
    }

    expect: "the datapoints to be stored"
    conditions.eventually {
      //def datapoints = datapointResource.getDatapoints(null, managerTestSetup.thingId, "light1PowerConsumption", null) as List
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))
      // Can include initial value when container is re-used between tests
      assert datapoints.size() >= 5

      // Note that the "No value" sensor update should not have created a datapoint, the first
      // datapoint is the last sensor update with an actual value

      assert datapoints.any{ it.value == 15.5d && it.timestamp == datapoint3ExpectedTimestamp}
      assert datapoints.any{ it.value == 14.4d && it.timestamp == datapoint2ExpectedTimestamp}
      assert datapoints.any{ it.value == 13.5d && it.timestamp == datapoint1ExpectedTimestamp}
      assert datapoints.count{ it.value == 13.3d} == 2
    }

    and: "the aggregated datapoints should match"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      //def aggregatedDatapoints = datapointResource.getDatapoints(
      def aggregatedDatapoints = assetDatapointService.queryDatapoints(
              thing.getId(),
              "light1PowerConsumption",
              new AssetDatapointIntervalQuery(
                      Instant.ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.systemDefault()).toLocalDateTime().minus(1, ChronoUnit.HOURS),
                      Instant.ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                      "minute",
                      AssetDatapointIntervalQuery.Formula.AVG,
                      true
                      )
      )
      assert aggregatedDatapoints.size() == 61
      assert aggregatedDatapoints[54].value == 13.3
      assert aggregatedDatapoints[55].value == null
      assert aggregatedDatapoints[56].value == 13.3
      assert aggregatedDatapoints[57].value == 13.5
      assert aggregatedDatapoints[58].value == 14.4
      assert aggregatedDatapoints[59].value == 15.5
      assert aggregatedDatapoints[60].value == null
    }

    and: "when the step size is set on the datapoint retrieval then the datapoints should match"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      //def aggregatedDatapoints = datapointResource.getDatapoints(
      def aggregatedDatapoints = assetDatapointService.queryDatapoints(
              thing.getId(),
              "light1PowerConsumption",
              new AssetDatapointIntervalQuery(
                      Instant.ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.systemDefault()).toLocalDateTime().minus(1, ChronoUnit.HOURS),
                      Instant.ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                      "5 minutes",
                      AssetDatapointIntervalQuery.Formula.AVG,
                      true
                      )
      )
      assert aggregatedDatapoints.size() == 13
      assert aggregatedDatapoints[11].value, closeTo(13.36666, 0.0001)
      assert aggregatedDatapoints[12].value == 14.95
    }


    // ------------------------------------
    // Test boolean data point storage
    // ------------------------------------

    when: "a simulated boolean sensor receives a new value"
    advancePseudoClock(1, MINUTES, container)
    datapoint1ExpectedTimestamp = getClockTimeOf(container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), false)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert !thing.getAttribute(thingLightToggleAttributeName).flatMap {
        it.getValue(Boolean.class)
      }.orElse(null)
    }

    when: "a simulated sensor receives a new value"
    advancePseudoClock(1, MINUTES, container)
    datapoint2ExpectedTimestamp = getClockTimeOf(container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), true)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert thing.getAttribute(thingLightToggleAttributeName).flatMap {
        it.getValue(Boolean.class)
      }.orElse(null)
    }

    when: "a simulated sensor receives a new value"
    advancePseudoClock(1, MINUTES, container)
    datapoint3ExpectedTimestamp = getClockTimeOf(container)
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), false)

    then: "the attribute should be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert !thing.getAttribute(thingLightToggleAttributeName).flatMap {
        it.getValue(Boolean.class)
      }.orElse(null)
    }

    expect: "the datapoints to be stored"
    conditions.eventually {
      //def datapoints = datapointResource.getDatapoints(null, managerTestSetup.thingId, thingLightToggleAttributeName, null) as List
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
      assert datapoints.size() >= 3

      assert !ValueUtil.getBoolean(datapoints.get(0).value).orElse(null)
      assert datapoints.get(0).timestamp == datapoint3ExpectedTimestamp

      assert datapoints.any {
        it.timestamp == datapoint3ExpectedTimestamp && !(it.value as Boolean)
      }
      assert datapoints.any {
        it.timestamp == datapoint2ExpectedTimestamp && (it.value as Boolean)
      }
      assert datapoints.any {
        it.timestamp == datapoint3ExpectedTimestamp && !(it.value as Boolean)
      }
    }

    and: "the aggregated datapoints should match"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      //def aggregatedDatapoints = datapointResource.getDatapoints(
      def aggregatedDatapoints = assetDatapointService.queryDatapoints(
              thing.getId(),
              thingLightToggleAttributeName,
              new AssetDatapointIntervalQuery(
                      Instant.ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.systemDefault()).toLocalDateTime().minus(1, ChronoUnit.HOURS),
                      Instant.ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                      "MINUTE",
                      AssetDatapointIntervalQuery.Formula.AVG,
                      true
                      )
      )
      assert aggregatedDatapoints.size() == 61
      assert aggregatedDatapoints[58].value == 0
      assert aggregatedDatapoints[59].value == 1d
      assert aggregatedDatapoints[60].value == 0
    }

    // ------------------------------------
    // Test logging of outdated data points
    // ------------------------------------

    when: "a simulated sensor receives a new outdated value"
    simulatorProtocol.updateSensor(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName), true, getClockTimeOf(container)-5000)

    then: "the datapoint should be stored"
    conditions.eventually {
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))
      assert datapoints.any {
        it.timestamp == getClockTimeOf(container)-5000 && (it.value as Boolean)
      }
    }

    and: "the attribute should not be updated"
    conditions.eventually {
      def thing = assetStorageService.find(managerTestSetup.thingId, true)
      assert !thing.getAttribute(thingLightToggleAttributeName).flatMap {
        it.getValue(Boolean.class)
      }.orElse(true)
      assert thing.getAttribute(thingLightToggleAttributeName).flatMap {
        it.getTimestamp()
      }.orElse(0) == getClockTimeOf(container)
    }

    when: "we subscribe to predicted data points events"
    List<AssetPredictedDatapointEvent> predictedEvents = new CopyOnWriteArrayList<>()
    Consumer<AssetPredictedDatapointEvent> predictedEventConsumer = { event ->
      predictedEvents.add(event)
    }
    clientEventService.addSubscription(AssetPredictedDatapointEvent.class, null, predictedEventConsumer)

    and: "predicted data points are added"
    predictedDatapointResource.writePredictedDatapoints(null, managerTestSetup.thingId, "light1PowerConsumption",
            [
              new ValueDatapoint<>(getClockTimeOf(container)+60000, 10d),
              new ValueDatapoint<>(getClockTimeOf(container)+120000, 20d),
              new ValueDatapoint<>(getClockTimeOf(container)+180000, 30d),
              new ValueDatapoint<>(getClockTimeOf(container)+240000, 40d),
              new ValueDatapoint<>(getClockTimeOf(container)+300000, 50d)
            ] as ValueDatapoint<?>[]
    )
    predictedDatapointResource.writePredictedDatapoints(null, managerTestSetup.thingId, thingLightToggleAttributeName,
            [
              new ValueDatapoint<>(getClockTimeOf(container)+60000, true),
              new ValueDatapoint<>(getClockTimeOf(container)+120000, true),
              new ValueDatapoint<>(getClockTimeOf(container)+180000, true),
              new ValueDatapoint<>(getClockTimeOf(container)+240000, false),
              new ValueDatapoint<>(getClockTimeOf(container)+300000, false)
            ] as ValueDatapoint<?>[]
    )

    then: "the predicted data should be available"
    def predictedData = assetPredictedDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))
    assert predictedData.size() == 5
    assert predictedData.any {it.value == 20d}

    and: "an event should be published for predicted datapoints"
    conditions.eventually {
      assert predictedEvents.any {
        it.ref.id == managerTestSetup.thingId &&
                it.ref.name == "light1PowerConsumption"
      }
      assert predictedEvents.any {
        it.ref.id == managerTestSetup.thingId &&
                it.ref.name == thingLightToggleAttributeName
      }
    }

    when: "the predicted data is purged and then retrieved"
    assetPredictedDatapointService.purgeValues(managerTestSetup.thingId, "light1PowerConsumption")
    predictedData = assetPredictedDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, "light1PowerConsumption"))

    then: "no predicted data should remain for this attribute"
    assert predictedData.isEmpty()

    and: "an event should be published"
    conditions.eventually {
      assert predictedEvents.any {
        it.ref.id == managerTestSetup.thingId &&
                it.ref.name == "light1PowerConsumption"
      }
    }

    when: "other predicted data is retrieved"
    predictedData = assetPredictedDatapointService.getDatapoints(new AttributeRef(managerTestSetup.thingId, thingLightToggleAttributeName))

    then: "predicted data should remain for this attribute"
    assert predictedData.size() == 5
    assert predictedData.count {it.value == false} == 2
  }

  def "Test anonymous predicted datapoint writes are allowed for public write attributes"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the manager container and test setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)

    and: "a public-write-only attribute exists on a public asset"
    def attributeName = "predictedPublicWrite"
    def apartment1 = assetStorageService.find(managerTestSetup.apartment1Id, true)
    apartment1.getAttributes().addOrReplace(
            new Attribute<>(attributeName, ValueType.NUMBER, 0d)
            .addMeta(new MetaItem<>(ACCESS_PUBLIC_WRITE, true))
    )
    assetStorageService.merge(apartment1)

    and: "an anonymous predicted datapoint API client"
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name).proxy(AssetPredictedDatapointResource.class)

    when: "an anonymous predicted datapoint write is made"
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment1Id,
            attributeName,
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 12d)] as ValueDatapoint<?>[]
    )

    then: "the predicted datapoint should be stored"
    conditions.eventually {
      def predictedData = assetPredictedDatapointService.getDatapoints(new AttributeRef(managerTestSetup.apartment1Id, attributeName))
      assert predictedData.size() == 1
      assert predictedData[0].value == 12d
    }
  }

  def "Test predicted datapoint writes are denied without WRITE_ATTRIBUTES role"() {
    given: "the manager container and test setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "a building user with READ_ASSETS but without WRITE_ATTRIBUTES"
    def accessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser2",
            "testuser2"
    )
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(AssetPredictedDatapointResource.class)

    when: "the user writes predicted datapoints"
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment1LivingroomId,
            "targetTemperature",
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 19d)] as ValueDatapoint<?>[]
    )

    then: "access should be forbidden"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  def "Test restricted predicted datapoint writes require restricted write meta"() {
    given: "the manager container and test setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)

    and: "a restricted user with linked assets"
    def accessToken = authenticate(
            container,
            keycloakTestSetup.realmBuilding.name,
            KEYCLOAK_CLIENT_ID,
            "testuser3",
            "testuser3"
    )
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), keycloakTestSetup.realmBuilding.name, accessToken).proxy(AssetPredictedDatapointResource.class)

    when: "the restricted user writes a predicted datapoint for an attribute without restricted write access"
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment1Id,
            BuildingAsset.STREET.name,
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, "should fail")] as ValueDatapoint<?>[]
    )

    then: "access should be forbidden"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 403
      return true
    }
  }

  def "Test predicted datapoint change events are received via websocket API"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the manager container and test setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)

    and: "an authenticated predicted datapoint API client"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            "testuser1",
            "testuser1"
    )
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(AssetPredictedDatapointResource.class)

    and: "a websocket client authenticated in the same realm"
    def client = new WebsocketIOClient<String>(
            new URIBuilder("ws://127.0.0.1:$serverPort/websocket/events?Realm=master").build(),
            null,
            new OAuthPasswordGrant(
                    "http://127.0.0.1:8081/auth/realms/master/protocol/openid-connect/token",
                    KEYCLOAK_CLIENT_ID,
                    null,
                    null,
                    "testuser1",
                    "testuser1"
                    )
    )
    client.setEncoderDecoderProvider({
      [
        new StringEncoder(CharsetUtil.UTF_8),
        new StringDecoder(CharsetUtil.UTF_8),
        new AbstractNettyIOClient.MessageToMessageDecoder<String>(String.class, client)
      ].toArray(new ChannelHandler[0])
    })

    and: "message and connection listeners are configured"
    def connectionStatus = client.getConnectionStatus()
    List<Object> receivedMessages = new CopyOnWriteArrayList<>()
    client.addMessageConsumer(message -> receivedMessages.add(messageFromString(message)))
    client.addConnectionStatusConsumer(status -> connectionStatus = status)

    when: "the websocket client connects and subscribes to predicted datapoint events"
    client.connect()

    then: "the websocket should be connected"
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
      assert connectionStatus == ConnectionStatus.CONNECTED
    }

    when: "the predicted datapoint event subscription is sent"
    client.sendMessage(messageToString(
                    EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
                    new EventSubscription(AssetPredictedDatapointEvent.class, null, "predicted-datapoints")
            ))

    then: "the server should confirm subscription"
    conditions.eventually {
      assert receivedMessages.any {
        it instanceof EventSubscription &&
                it.subscriptionId == "predicted-datapoints"
      }
    }

    when: "a predicted datapoint change is written"
    receivedMessages.clear()
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.thingId,
            "light1PowerConsumption",
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 42d)] as ValueDatapoint<?>[]
    )

    then: "the websocket subscription should receive the corresponding triggered event"
    conditions.eventually {
      assert receivedMessages.any {
        it instanceof TriggeredEventSubscription &&
                it.subscriptionId == "predicted-datapoints" &&
                it.events.any { event ->
                  event instanceof AssetPredictedDatapointEvent &&
                          event.ref.id == managerTestSetup.thingId &&
                          event.ref.name == "light1PowerConsumption"
                }
      }
    }

    cleanup: "the websocket client is disconnected"
    if (client != null) {
      client.disconnect()
    }
  }

  def "Test predicted datapoint websocket subscription is denied without READ_ASSETS role"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the manager container and setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityService = container.getService(ManagerIdentityService.class)

    and: "a regular building user has READ_ASSETS removed"
    identityService.getIdentityProvider().updateUserClientRoles(
            keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.testuser2Id,
            KEYCLOAK_CLIENT_ID,
            ClientRole.WRITE_USER.value,
            ClientRole.READ_MAP.value
    )

    and: "a websocket client authenticated as that user"
    def client = createPredictedDatapointWebsocketClient(serverPort, "building", "building", "testuser2", "testuser2")
    List<Object> receivedMessages = new CopyOnWriteArrayList<>()
    client.addMessageConsumer(message -> receivedMessages.add(messageFromString(message)))

    when: "the websocket client connects and subscribes to predicted datapoint events"
    client.connect()
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
    }

    client.sendMessage(messageToString(
                    EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
                    new EventSubscription(AssetPredictedDatapointEvent.class, null, "predicted-no-read-assets")
            ))

    then: "the server should reject subscription"
    conditions.eventually {
      assert receivedMessages.any {
        it instanceof UnauthorizedEventSubscription &&
                it.subscription.subscriptionId == "predicted-no-read-assets"
      }
    }

    cleanup: "the websocket client is disconnected and original roles are restored"
    identityService.getIdentityProvider().updateUserClientRoles(
            keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.testuser2Id,
            KEYCLOAK_CLIENT_ID,
            ClientRole.WRITE_USER.value,
            ClientRole.READ_MAP.value,
            ClientRole.READ_ASSETS.value
    )
    if (client != null) {
      client.disconnect()
    }
  }

  def "Test predicted datapoint websocket subscription is denied for a different realm"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the manager container is available"
    def container = startContainer(defaultConfig(), defaultServices())

    and: "a building realm websocket client requests subscriptions in master realm"
    def client = createPredictedDatapointWebsocketClient(serverPort, "master", "building", "testuser2", "testuser2")
    List<Object> receivedMessages = new CopyOnWriteArrayList<>()
    client.addMessageConsumer(message -> receivedMessages.add(messageFromString(message)))

    when: "the websocket client connects and subscribes to predicted datapoint events"
    client.connect()

    then: "the websocket session should never be established in a realm where the user does not belong"
    assert client.connectionStatus != ConnectionStatus.CONNECTED
    assert receivedMessages.isEmpty()

    cleanup: "the websocket client is disconnected"
    if (client != null) {
      client.disconnect()
    }
  }

  def "Test restricted user does not receive predicted datapoint events for unlinked assets"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the manager container and setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityService = container.getService(ManagerIdentityService.class)

    and: "the limited building user has attribute write permission for producing predicted datapoint events"
    identityService.getIdentityProvider().updateUserClientRoles(
            keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.testuser2Id,
            KEYCLOAK_CLIENT_ID,
            ClientRole.WRITE_USER.value,
            ClientRole.READ_MAP.value,
            ClientRole.READ_ASSETS.value,
            ClientRole.WRITE_ATTRIBUTES.value
    )

    and: "a limited building realm predicted datapoint API client"
    def accessToken = authenticate(
            container,
            "building",
            KEYCLOAK_CLIENT_ID,
            "testuser2",
            "testuser2"
    )
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), "building", accessToken).proxy(AssetPredictedDatapointResource.class)

    and: "a restricted websocket client in the same realm"
    def client = createPredictedDatapointWebsocketClient(serverPort, "building", "building", "testuser3", "testuser3")
    List<Object> receivedMessages = new CopyOnWriteArrayList<>()
    client.addMessageConsumer(message -> receivedMessages.add(messageFromString(message)))

    when: "the websocket client connects and subscribes to predicted datapoint events"
    client.connect()
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
    }

    client.sendMessage(messageToString(
                    EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
                    new EventSubscription(AssetPredictedDatapointEvent.class, null, "predicted-restricted-unlinked")
            ))

    and: "a predicted datapoint update is written on a linked and restricted attribute"
    receivedMessages.clear()
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment1LivingroomId,
            "targetTemperature",
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 22d)] as ValueDatapoint<?>[]
    )

    then: "the restricted user should receive the linked attribute event"
    conditions.eventually {
      assert receivedMessages.any {
        it instanceof TriggeredEventSubscription &&
                it.subscriptionId == "predicted-restricted-unlinked" &&
                it.events.any { event ->
                  event instanceof AssetPredictedDatapointEvent &&
                          event.ref.id == managerTestSetup.apartment1LivingroomId &&
                          event.ref.name == "targetTemperature"
                }
      }
    }

    when: "a predicted datapoint update is written on an unlinked asset"
    receivedMessages.clear()
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment2LivingroomId,
            "targetTemperature",
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 21d)] as ValueDatapoint<?>[]
    )

    then: "no triggered event should be received by the restricted user"
    assert receivedMessages.every {
      !(it instanceof TriggeredEventSubscription) || it.subscriptionId != "predicted-restricted-unlinked"
    }

    cleanup: "the websocket client is disconnected and original roles are restored"
    identityService.getIdentityProvider().updateUserClientRoles(
            keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.testuser2Id,
            KEYCLOAK_CLIENT_ID,
            ClientRole.WRITE_USER.value,
            ClientRole.READ_MAP.value,
            ClientRole.READ_ASSETS.value
    )
    if (client != null) {
      client.disconnect()
    }
  }

  def "Test restricted user does not receive predicted datapoint events for non-restricted attributes"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 15, delay: 0.2)

    and: "the manager container and setup are available"
    def container = startContainer(defaultConfig(), defaultServices())
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def identityService = container.getService(ManagerIdentityService.class)

    and: "a non-restricted attribute exists on an asset linked to the restricted user"
    def apartment1 = assetStorageService.find(managerTestSetup.apartment1Id, true)
    apartment1.getAttributes().addOrReplace(new Attribute<>("predictedNonRestricted", ValueType.NUMBER, 0d))
    assetStorageService.merge(apartment1)

    and: "the limited building user has attribute write permission for producing predicted datapoint events"
    identityService.getIdentityProvider().updateUserClientRoles(
            keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.testuser2Id,
            KEYCLOAK_CLIENT_ID,
            ClientRole.WRITE_USER.value,
            ClientRole.READ_MAP.value,
            ClientRole.READ_ASSETS.value,
            ClientRole.WRITE_ATTRIBUTES.value
    )

    and: "a limited building realm predicted datapoint API client"
    def accessToken = authenticate(
            container,
            "building",
            KEYCLOAK_CLIENT_ID,
            "testuser2",
            "testuser2"
    )
    def predictedDatapointResource = getClientApiTarget(serverUri(serverPort), "building", accessToken).proxy(AssetPredictedDatapointResource.class)

    and: "a restricted websocket client in the same realm"
    def client = createPredictedDatapointWebsocketClient(serverPort, "building", "building", "testuser3", "testuser3")
    List<Object> receivedMessages = new CopyOnWriteArrayList<>()
    client.addMessageConsumer(message -> receivedMessages.add(messageFromString(message)))

    when: "the websocket client connects and subscribes to predicted datapoint events"
    client.connect()
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
    }

    client.sendMessage(messageToString(
                    EventSubscription.SUBSCRIBE_MESSAGE_PREFIX,
                    new EventSubscription(AssetPredictedDatapointEvent.class, null, "predicted-restricted-attr")
            ))

    and: "a predicted datapoint update is written on a linked restricted attribute"
    receivedMessages.clear()
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment1LivingroomId,
            "targetTemperature",
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 19d)] as ValueDatapoint<?>[]
    )

    then: "the restricted user should receive the restricted attribute event"
    conditions.eventually {
      assert receivedMessages.any {
        it instanceof TriggeredEventSubscription &&
                it.subscriptionId == "predicted-restricted-attr" &&
                it.events.any { event ->
                  event instanceof AssetPredictedDatapointEvent &&
                          event.ref.id == managerTestSetup.apartment1LivingroomId &&
                          event.ref.name == "targetTemperature"
                }
      }
    }

    when: "a predicted datapoint update is written on a non-restricted attribute"
    receivedMessages.clear()
    predictedDatapointResource.writePredictedDatapoints(
            null,
            managerTestSetup.apartment1Id,
            "predictedNonRestricted",
            [new ValueDatapoint<>(getClockTimeOf(container) + 60000, 10d)] as ValueDatapoint<?>[]
    )

    then: "no triggered event should be received by the restricted user"
    assert receivedMessages.every {
      !(it instanceof TriggeredEventSubscription) || it.subscriptionId != "predicted-restricted-attr"
    }

    cleanup: "the websocket client is disconnected and original roles are restored"
    identityService.getIdentityProvider().updateUserClientRoles(
            keycloakTestSetup.realmBuilding.name,
            keycloakTestSetup.testuser2Id,
            KEYCLOAK_CLIENT_ID,
            ClientRole.WRITE_USER.value,
            ClientRole.READ_MAP.value,
            ClientRole.READ_ASSETS.value
    )
    if (client != null) {
      client.disconnect()
    }
  }
}
