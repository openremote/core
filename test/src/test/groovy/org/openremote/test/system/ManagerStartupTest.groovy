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
package org.openremote.test.system

import org.apache.camel.component.direct.DirectComponent
import org.apache.camel.component.direct.DirectConsumer
import org.openremote.agent.protocol.mqtt.MQTT_IOClient
import org.openremote.container.message.MessageBrokerService
import org.openremote.container.timer.TimerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetPredictedDatapointService
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.manager.rules.RulesService
import org.openremote.manager.setup.SetupService
import org.openremote.model.Container
import org.openremote.model.ContainerService
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.auth.UsernamePassword
import org.openremote.model.datapoint.ValueDatapoint
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CountDownLatch

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static org.openremote.container.Container.OR_EXECUTOR_THREADS_MAX
import static org.openremote.container.Container.OR_EXECUTOR_THREADS_MIN
import static org.openremote.container.web.WebService.OR_WEBSERVER_LISTEN_HOST
import static org.openremote.container.web.WebService.OR_WEBSERVER_LISTEN_PORT
import static org.openremote.manager.asset.AssetProcessingService.ATTRIBUTE_EVENT_PROCESSOR
import static org.openremote.manager.mqtt.MQTTBrokerService.MQTT_SERVER_LISTEN_HOST
import static org.openremote.manager.mqtt.MQTTBrokerService.MQTT_SERVER_LISTEN_PORT
import static org.openremote.model.security.User.SERVICE_ACCOUNT_PREFIX
import static org.openremote.model.value.MetaItemType.APPLY_PREDICTED_DATA_POINTS
import static org.openremote.model.value.MetaItemType.HAS_PREDICTED_DATA_POINTS
import static org.openremote.model.value.MetaItemType.USER_CONNECTED
import static org.openremote.model.value.ValueType.BOOLEAN

class ManagerStartupTest extends Specification implements ManagerContainerTrait {

  @Shared
  private int mqttPort
  @Shared
  private StartupMqttBroker mqttBroker
  @Shared
  private StartupMessageBroker messageBroker
  @Shared
  private AssetStorageService storage
  @Shared
  private LateStartupProbe lateStartupProbe

  def setupSpec() {
    mqttPort = findEphemeralPort()
    messageBroker = new StartupMessageBroker(mqttPort)
    mqttBroker = new StartupMqttBroker(messageBroker.direct)

    def config = defaultConfig() << [
      (MQTT_SERVER_LISTEN_HOST): "127.0.0.1",
      (MQTT_SERVER_LISTEN_PORT): mqttPort.toString(),
      (OR_WEBSERVER_LISTEN_HOST): "127.0.0.1",
      (OR_EXECUTOR_THREADS_MIN): "1",
      (OR_EXECUTOR_THREADS_MAX): "1"
    ]
    lateStartupProbe = new LateStartupProbe(config[OR_WEBSERVER_LISTEN_PORT].toInteger(), mqttPort)
    List<ContainerService> services = defaultServices(lateStartupProbe).collect {
      if (it instanceof MessageBrokerService) {
        return messageBroker
      }
      if (it instanceof MQTTBrokerService) {
        return mqttBroker
      }
      return it
    }
    // Exercise a custom service order in which Camel is already running when rules are prepared.
    services.remove(messageBroker)
    services.add(services.findIndexOf { it instanceof RulesService }, messageBroker)

    def manager = startContainer(config, services)
    storage = manager.getService(AssetStorageService.class)
  }

  def cleanupSpec() {
    stopContainer()
  }

  def "internal producers start after consumers and before public listeners"() {
    expect: "rules wait for the producer phase even when Camel starts first"
    verifyAll {
      !lateStartupProbe.rulesStartedDuringStart
      lateStartupProbe.rulesStartedDuringProducerPhase
    }

    and: "both endpoints stay closed through the producer phase and accept connections after startup"
    verifyAll {
      !messageBroker.mqttPortOpenBeforeCamel
      !lateStartupProbe.httpPortOpenDuringStart
      !lateStartupProbe.mqttPortOpenDuringStart
      !lateStartupProbe.httpPortOpenDuringProducerPhase
      !lateStartupProbe.mqttPortOpenDuringProducerPhase
      canConnectToPort(lateStartupProbe.httpPort)
      canConnectToPort(mqttPort)
    }
  }

  def "MQTT connection monitoring reconciles state during startup"() {
    given: "the startup probe ran under executor saturation with stale online values in storage"
    def conditions = statusConditions()
    MQTT_IOClient client

    expect: "startup completed without consumer waits and both stored statuses are offline"
    verifyAll {
      mqttBroker.startupConsumerTimeouts == 0
      connectionStatuses(storage, mqttBroker.monitoredAssetId) == [false, false]
    }

    when: "the monitored client connects after startup"
    client = new MQTT_IOClient(UniqueIdentifierGenerator.generateId(), "127.0.0.1", mqttPort,
            false, true, mqttBroker.credentials, null, null)
    client.connect()

    then: "both monitored attributes report the client online"
    conditions.eventually {
      assert client.connectionStatus == ConnectionStatus.CONNECTED
      assert connectionStatuses(storage, mqttBroker.monitoredAssetId) == [true, true]
    }

    when: "that client disconnects"
    client.disconnect()

    then: "both monitored attributes report the client offline"
    conditions.eventually {
      assert connectionStatuses(storage, mqttBroker.monitoredAssetId) == [false, false]
    }

    cleanup:
    client?.disconnect()
  }

  def "a Java attribute submission fails fast before Camel starts and can be retried"() {
    given:
    def assetId = messageBroker.submittedAssetId
    def conditions = statusConditions()

    expect: "the early submission was rejected without waiting for a consumer"
    verifyAll {
      messageBroker.submissionConsumerTimeouts == 0
      messageBroker.submissionFailure?.message == "Attribute event processor is not ready"
      attributeValue(storage, assetId, "submitted") == false
    }

    when: "the caller retries after Camel starts"
    container.getService(AssetProcessingService.class)
            .sendAttributeEvent(new AttributeEvent(assetId, "submitted", true))

    then: "the retried event is applied"
    conditions.eventually {
      assert attributeValue(storage, assetId, "submitted") == true
    }
  }

  def "a Java attribute submission fails fast while only its route is stopped"() {
    given:
    def routeController = messageBroker.context.routeController
    routeController.stopRoute("AttributeEvent-Processor")

    when:
    container.getService(AssetProcessingService.class)
            .sendAttributeEvent(new AttributeEvent(messageBroker.submittedAssetId, "submitted", true))

    then:
    IllegalStateException failure = thrown()
    failure.message == "Attribute event processor is not ready"
    messageBroker.isStarted()

    cleanup:
    routeController?.startRoute("AttributeEvent-Processor")
  }

  def "a prediction already due during startup is applied once Camel is ready"() {
    expect:
    def conditions = statusConditions()
    conditions.eventually {
      assert attributeValue(storage, mqttBroker.monitoredAssetId, "startupPrediction") == true
    }
  }

  private static KeycloakTestSetup keycloakSetup(Container container) {
    container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
  }

  private static Boolean attributeValue(AssetStorageService storage, String assetId, String name) {
    storage.find(assetId).getAttribute(name).get().value.orElse(null)
  }

  private static List<Boolean> connectionStatuses(AssetStorageService storage, String assetId) {
    def asset = storage.find(assetId)
    [
      asset.getAttribute("firstConnected").get().value.orElse(null),
      asset.getAttribute("secondConnected").get().value.orElse(null)
    ]
  }

  private static PollingConditions statusConditions() {
    def conditions = new PollingConditions(timeout: 10)
    conditions.onTimeout { failure -> failure.message }
    return conditions
  }

  private static boolean canConnectToPort(int port) {
    try {
      new Socket().withCloseable {
        it.connect(new InetSocketAddress("127.0.0.1", port), 1000)
      }
      return true
    } catch (ConnectException refused) {
      return false
    }
  }

  private static void withSaturatedExecutor(Container container, Closure action) {
    def workerOccupied = new CountDownLatch(1)
    def releaseWorker = new CountDownLatch(1)
    def startupThread = Thread.currentThread()

    try {
      new PollingConditions(timeout: 10).eventually {
        if (workerOccupied.count> 0) {
          container.executor.execute {
            // Retry if another startup task occupies the worker and this submission runs inline.
            if (Thread.currentThread() != startupThread) {
              workerOccupied.countDown()
              releaseWorker.await()
            }
          }
        }
        assert workerOccupied.await(100, MILLISECONDS)
      }
      action.call()
    } catch (AssertionError failure) {
      // Let the container's startup error handling stop partially started services.
      throw new IllegalStateException("Could not arrange the startup probe", failure)
    } finally {
      releaseWorker.countDown()
    }
  }

  private static class StartupMqttBroker extends MQTTBrokerService {
    private final StartupDirectComponent direct
    String monitoredAssetId
    UsernamePassword credentials
    int startupConsumerTimeouts

    StartupMqttBroker(StartupDirectComponent direct) {
      this.direct = direct
    }

    @Override
    void start(Container container) {
      def keycloak = keycloakSetup(container)
      def realm = keycloak.realmBuilding.name
      def user = keycloak.serviceUser
      credentials = new UsernamePassword("${realm}:${user.username}".toString(), user.secret)
      long now = container.getService(TimerService.class).getCurrentTimeMillis()
      def asset = new ThingAsset("Startup attributes").setRealm(realm).addAttributes(
              new Attribute<>("firstConnected", BOOLEAN, true).addMeta(new MetaItem<>(USER_CONNECTED, user.username)),
              new Attribute<>("secondConnected", BOOLEAN, true)
              .addMeta(new MetaItem<>(USER_CONNECTED, SERVICE_ACCOUNT_PREFIX + user.username)),
              new Attribute<>("startupPrediction", BOOLEAN, false).setTimestamp(now - 2000)
              .addOrReplaceMeta(new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true),
              new MetaItem<>(APPLY_PREDICTED_DATA_POINTS, true))
      )
      monitoredAssetId = container.getService(AssetStorageService.class).merge(asset).id
      container.getService(AssetPredictedDatapointService.class)
              .updateValues(monitoredAssetId, "startupPrediction", [new ValueDatapoint<>(now - 1000, true)])

      int timeoutsBeforeMqtt = direct.startupConsumerTimeouts
      withSaturatedExecutor(container) {
        super.start(container)
      }
      startupConsumerTimeouts = direct.startupConsumerTimeouts - timeoutsBeforeMqtt
    }
  }

  private static class StartupMessageBroker extends MessageBrokerService {
    final StartupDirectComponent direct = new StartupDirectComponent()
    private final int mqttPort
    boolean mqttPortOpenBeforeCamel
    String submittedAssetId
    IllegalStateException submissionFailure
    int submissionConsumerTimeouts

    StartupMessageBroker(int mqttPort) {
      this.mqttPort = mqttPort
      context.addComponent("direct", direct)
    }

    @Override
    void init(Container container) {
      direct.startupThread = Thread.currentThread()
      super.init(container)
    }

    @Override
    void start(Container container) {
      try {
        def keycloak = keycloakSetup(container)
        def storage = container.getService(AssetStorageService.class)
        def asset = new ThingAsset("Startup attribute event").setRealm(keycloak.realmBuilding.name)
                .addAttributes(new Attribute<>("submitted", BOOLEAN, false))
        submittedAssetId = storage.merge(asset).id

        withSaturatedExecutor(container) {
          mqttPortOpenBeforeCamel = canConnectToPort(mqttPort)
          int timeoutsBeforeSubmission = direct.startupConsumerTimeouts
          try {
            container.getService(AssetProcessingService.class)
                    .sendAttributeEvent(new AttributeEvent(submittedAssetId, "submitted", true))
          } catch (IllegalStateException failure) {
            submissionFailure = failure
          }
          submissionConsumerTimeouts = direct.startupConsumerTimeouts - timeoutsBeforeSubmission
        }
        super.start(container)
      } finally {
        direct.startupThread = null
      }
    }
  }

  private static class LateStartupProbe implements ContainerService {
    final int httpPort
    private final int mqttPort
    boolean rulesStartedDuringStart
    boolean rulesStartedDuringProducerPhase
    boolean httpPortOpenDuringStart
    boolean mqttPortOpenDuringStart
    boolean httpPortOpenDuringProducerPhase
    boolean mqttPortOpenDuringProducerPhase

    LateStartupProbe(int httpPort, int mqttPort) {
      this.httpPort = httpPort
      this.mqttPort = mqttPort
    }

    @Override
    int getPriority() {
      Integer.MAX_VALUE
    }

    @Override
    void init(Container container) {}

    @Override
    void start(Container container) {
      rulesStartedDuringStart = container.getService(RulesService.class).startDone
      httpPortOpenDuringStart = canConnectToPort(httpPort)
      mqttPortOpenDuringStart = canConnectToPort(mqttPort)
    }

    @Override
    void startProducers(Container container) {
      rulesStartedDuringProducerPhase = container.getService(RulesService.class).startDone
      httpPortOpenDuringProducerPhase = canConnectToPort(httpPort)
      mqttPortOpenDuringProducerPhase = canConnectToPort(mqttPort)
    }

    @Override
    void stop(Container container) {}
  }

  private static class StartupDirectComponent extends DirectComponent {
    volatile Thread startupThread
    int startupConsumerTimeouts

    @Override
    protected DirectConsumer getConsumer(String key, boolean block, long timeout) {
      if (key != ATTRIBUTE_EVENT_PROCESSOR || !block || timeout <= 0) {
        return super.getConsumer(key, block, timeout)
      }

      // Keep the real consumer wait and its failure path, with a short timeout for this spec.
      def consumer = super.getConsumer(key, true, Math.min(timeout, 100L))
      if (consumer == null && Thread.currentThread() == startupThread) {
        startupConsumerTimeouts++
      }
      return consumer
    }
  }
}
