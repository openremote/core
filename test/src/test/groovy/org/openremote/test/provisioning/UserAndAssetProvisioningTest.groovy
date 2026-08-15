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
package org.openremote.test.provisioning

import com.hivemq.client.internal.mqtt.mqtt3.Mqtt3AsyncClientView
import com.hivemq.client.internal.mqtt.mqtt3.Mqtt3ClientConfigView
import com.hivemq.client.mqtt.MqttClientConfig
import com.hivemq.client.mqtt.MqttClientConnectionConfig
import com.hivemq.client.mqtt.datatypes.MqttQos
import io.netty.channel.socket.SocketChannel
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection
import org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.RoleRepresentation
import org.openremote.agent.protocol.mqtt.MQTTMessage
import org.openremote.agent.protocol.mqtt.MQTT_IOClient
import org.openremote.container.persistence.PersistenceService
import org.openremote.container.security.keycloak.KeycloakIdentityProvider
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.event.ClientEventService
import org.openremote.manager.mqtt.DefaultMQTTHandler
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.manager.mqtt.UserAssetProvisioningMQTTHandler
import org.openremote.manager.provisioning.ProvisioningService
import org.openremote.manager.security.KeyStoreServiceImpl
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.security.ManagerKeycloakIdentityProvider
import org.openremote.manager.setup.SetupService
import org.openremote.model.Constants
import org.openremote.model.asset.Asset
import org.openremote.model.asset.AssetEvent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ShipAsset
import org.openremote.model.asset.impl.WeatherAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.MetaItem
import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.protocol.mqtt.Topic
import org.openremote.model.provisioning.*
import org.openremote.model.security.ClientRole
import org.openremote.model.security.Realm
import org.openremote.model.security.User
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import org.openremote.test.mqtt.MTLSCertificateHelper
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.security.auth.Subject
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.Signature
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import static org.openremote.manager.mqtt.MQTTBrokerService.getConnectionIDString
import static org.openremote.manager.mqtt.UserAssetProvisioningMQTTHandler.*
import static org.openremote.model.value.ValueType.BOOLEAN
import static org.openremote.model.value.ValueType.NUMBER

class UserAndAssetProvisioningTest extends Specification implements ManagerContainerTrait {

  @SuppressWarnings("GroovyAccessibility")
  def "Check basic functionality"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 20, delay: 0.2)
    MQTT_IOClient device1Client
    MQTT_IOClient device1SnoopClient
    MQTT_IOClient deviceNClient

    and: "the container starts"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def provisioningService = container.getService(ProvisioningService.class)
    def mqttBrokerService = container.getService(MQTTBrokerService.class)
    def defaultMQTTHandler = mqttBrokerService.getCustomHandlers().find{
      it instanceof DefaultMQTTHandler
    } as DefaultMQTTHandler
    def clientEventService = container.getService(ClientEventService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def userAssetProvisioningMQTTHandler = mqttBrokerService.customHandlers.find {
      it instanceof UserAssetProvisioningMQTTHandler
    } as UserAssetProvisioningMQTTHandler
    def mqttHost = mqttBrokerService.host
    def mqttPort = mqttBrokerService.port

    // A consumer in the client topic map only means the client asked for the subscription; the broker registers it
    // asynchronously and a response published before then is lost, so requests must await the broker side
    def responseSubscribed = { String clientId, String responseTopic ->
      def brokerConnection = mqttBrokerService.getConnectionFromClientID(clientId)
      brokerConnection != null && userAssetProvisioningMQTTHandler.responseTopicOwners.get(responseTopic) === brokerConnection
    }

    and: "a realm is created with some custom realm roles"
    def realm = new Realm()
    realm.setName("test")
    realm.setDisplayName("Test")
    realm.setEnabled(true)
    realm.setDuplicateEmailsAllowed(true)
    realm.setRememberMe(true)
    realm = identityService.getIdentityProvider().createRealm(realm)
    (identityService.getIdentityProvider() as KeycloakIdentityProvider).getRealms(realmsResource -> {
      RealmResource realmResource = realmsResource.realm(realm.getName())
      realmResource.roles().create(new RoleRepresentation("installer", "Installer", false))
      realmResource.roles().create(new RoleRepresentation("home-owner", "Home owner", false))
    })

    and: "an internal attribute event subscriber is added for test validation purposes"
    List<AttributeEvent> internalAttributeEvents = new CopyOnWriteArrayList<>()
    Consumer<AttributeEvent> internalConsumer = { ev ->
      internalAttributeEvents.add(ev)
    }
    clientEventService.addSubscription(AttributeEvent.class, null, internalConsumer)

    // TODO: Switch to use provisioning resource once implemented
    when: "a provisioning realm config is added to the system"
    def provisioningConfig = new X509ProvisioningConfig("Valid Test Config",
            new X509ProvisioningData()
            .setCACertPEM(getClass().getResource("/org/openremote/test/provisioning/ca.pem").text)
            ).setAssetTemplate(
            ValueUtil.asJSON(
                    new WeatherAsset("Weather Asset")
                    .addAttributes(
                            new Attribute<>("customAttribute", NUMBER).addMeta(
                                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ),
                                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_WRITE)
                                    ),
                            new Attribute<>("serialNumber", ValueType.TEXT, UNIQUE_ID_PLACEHOLDER),
                            new Attribute<>("connected", BOOLEAN).addMeta(
                                    new MetaItem<>(MetaItemType.USER_CONNECTED, PROVISIONING_USER_PREFIX + "device1")
                                    )
                            )
                    ).orElse("")
            ).setRealm("building")
            .setRestrictedUser(true)
            .setUserRoles([
              ClientRole.WRITE_ASSETS,
              ClientRole.WRITE_ATTRIBUTES,
              ClientRole.READ_ASSETS
            ] as ClientRole[])
    provisioningConfig = provisioningService.merge(provisioningConfig)

    then: "then the config should be available in the system"
    conditions.eventually {
      def storedConfigs = provisioningService.getProvisioningConfigs()
      assert storedConfigs.size() == 1
      assert storedConfigs.get(0) instanceof X509ProvisioningConfig
      assert ((X509ProvisioningConfig)storedConfigs.get(0)).getData() != null
      assert ((X509ProvisioningConfig)storedConfigs.get(0)).getData().getCACertPEM() == provisioningConfig.getData().getCACertPEM()
    }

    when: "a mqtt client connects"
    RemotingConnection connection = null
    def device1UniqueId = "device1"
    def mqttDevice1ClientId = UniqueIdentifierGenerator.generateId("device1")
    def mqttDevice1SnoopClientId = UniqueIdentifierGenerator.generateId("device1snoop")
    List<String> subscribeFailures = new CopyOnWriteArrayList<>()
    List<ConnectionStatus> connectionStatuses = new CopyOnWriteArrayList<>()
    Consumer<String> subscribeFailureCallback = {String topic ->
      subscribeFailures.add(topic)
      LOG.info("device1Client failed to subscribe to topic: ${topic}")
    }
    Consumer<String> deviceNSubscribeFailureCallback = { String topic ->
      subscribeFailures.add(topic)
      LOG.info("deviceNClient failed to subscribe to topic: ${topic}")
    }
    device1Client = new MQTT_IOClient(mqttDevice1ClientId, mqttHost, mqttPort, false, false, null, null, null)
    device1SnoopClient = new MQTT_IOClient(mqttDevice1SnoopClientId, mqttHost, mqttPort, false, false, null, null, null)
    device1Client.setTopicSubscribeFailureConsumer(subscribeFailureCallback)
    device1Client.setRemoveConsumersOnSubscriptionFailure(true)
    device1Client.addConnectionStatusConsumer({connectionStatus ->
      LOG.info("Device 1 connection status changed: $connectionStatus")
      connectionStatuses.add(connectionStatus)
    })
    device1Client.connect()

    then: "mqtt client should be connected"
    conditions.eventually {
      assert device1Client.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    when: "the client subscribes to the provisioning endpoints"
    List<ProvisioningMessage> device1Responses = new CopyOnWriteArrayList<>()
    List<X509ProvisioningMessage> device1Proofs = new CopyOnWriteArrayList<>()
    def device1RequestTopic = "$PROVISIONING_TOKEN/$device1UniqueId/$REQUEST_TOKEN".toString()
    def device1ResponseTopic = "$PROVISIONING_TOKEN/$device1UniqueId/$RESPONSE_TOKEN".toString()
    Consumer<MQTTMessage<String>> device1MessageConsumer = { MQTTMessage<String> msg ->
      def response = ValueUtil.parse(msg.payload, ProvisioningMessage.class).orElse(null)
      if (response instanceof X509ProvisioningChallengeResponseMessage) {
        def proof = signChallenge(response, "device1")
        device1Proofs.add(proof)
        device1Client.sendMessage(new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(proof).orElseThrow()))
      } else {
        device1Responses.add(response)
      }
    }
    device1Client.addMessageConsumer(device1ResponseTopic, device1MessageConsumer)

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert device1Client.topicConsumerMap.get(device1ResponseTopic) != null
      assert device1Client.topicConsumerMap.get(device1ResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
    }

    when: "an eavesdropping client connects"
    device1SnoopClient.setTopicSubscribeFailureConsumer(subscribeFailureCallback)
    device1SnoopClient.setRemoveConsumersOnSubscriptionFailure(true)
    device1SnoopClient.connect()

    then: "it should be connected"
    conditions.eventually {
      assert device1SnoopClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    when: "the eavesdropping client subscribes to the provisioning response topic"
    device1SnoopClient.addMessageConsumer(device1ResponseTopic, device1MessageConsumer)

    then: "the subscription should have failed"
    conditions.eventually {
      assert subscribeFailures.last == device1ResponseTopic
      assert device1SnoopClient.topicConsumerMap.get(device1ResponseTopic) == null
      assert subscribeFailures.size() == 1
    }

    and: "the actual client should still be connected and subscribed"
    conditions.eventually {
      assert device1Client.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert device1Client.topicConsumerMap.get(device1ResponseTopic) != null
      assert device1Client.topicConsumerMap.get(device1ResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
    }

    when: "a public certificate alone is submitted before the device has been provisioned"
    device1Client.sendMessage(new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
            ).orElseThrow()))

    then: "enrollment is rejected without creating a service user or asset"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses[0] instanceof ErrorResponseMessage
      assert device1Responses[0].error == ErrorResponseMessage.Error.UNAUTHORIZED
    }
    assert identityService.identityProvider.getUserByUsername(managerTestSetup.realmBuildingName,
    User.SERVICE_ACCOUNT_PREFIX + PROVISIONING_USER_PREFIX + device1UniqueId) == null
    assert assetStorageService.find(UniqueIdentifierGenerator.generateId(provisioningConfig.realm + device1UniqueId)) == null

    when: "the client publishes a valid x509 certificate that has been signed by the CA stored in the provisioning config"
    device1Responses.clear()
    subscribeFailures.clear()
    def existingConnection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    String weatherAssetID
    Asset<WeatherAsset> weatherAsset
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)device1Responses.get(0)).realm == managerTestSetup.realmBuildingName
      weatherAssetID = ((SuccessResponseMessage)device1Responses.get(0)).asset.id
      weatherAsset = ((SuccessResponseMessage)device1Responses.get(0)).asset
      assert weatherAsset != null
      assert weatherAsset instanceof WeatherAsset
      assert weatherAsset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == device1UniqueId
    }

    and: "the connection should have been maintained"
    new PollingConditions(initialDelay: 1, timeout: 10, delay: 0.5).eventually {
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) == existingConnection
    }

    when: "an unrelated anonymous client that does not own the response topic publishes requests"
    def device1ServiceUser = identityService.identityProvider.getUserByUsername(
            managerTestSetup.realmBuildingName,
            User.SERVICE_ACCOUNT_PREFIX + PROVISIONING_USER_PREFIX + device1UniqueId)
    def attackerConnection = mqttBrokerService.getConnectionFromClientID(mqttDevice1SnoopClientId)
    def attackerSubject = attackerConnection.subject
    def device1Pem = getClass().getResource("/org/openremote/test/provisioning/device1.pem").text
    device1Responses.clear()
    def proofCount = device1Proofs.size()
    [
      new X509ProvisioningMessage(device1Pem),
      new X509ProvisioningChallengeRequestMessage(device1Pem),
      device1Proofs.last()
    ].each { message ->
      device1SnoopClient.sendMessage(new MQTTMessage<String>(device1RequestTopic,
              ValueUtil.asJSON(message).orElseThrow()))
    }

    then: "the broker refuses the publishes so the device's own exchange is never disturbed"
    // This asserts an absence, so it is a quiet period rather than a wait for something to happen:
    // the initial delay gives any wrongly processed publish time to produce a response, and the
    // block then passes on its first poll if nothing arrived.
    new PollingConditions(initialDelay: 2, timeout: 10, delay: 0.5).eventually {
      assert device1Responses.isEmpty()
      assert device1Proofs.size() == proofCount
    }

    and: "the attacker remains anonymous"
    assert attackerConnection.subject.is(attackerSubject)
    assert !mqttBrokerService.getUserConnections(device1ServiceUser.id).contains(attackerConnection)
    assert !userAssetProvisioningMQTTHandler.canPublish(attackerConnection, null, Topic.parse(device1RequestTopic))
    assert !defaultMQTTHandler.checkCanPublish(attackerConnection,
    defaultMQTTHandler.getAuthContextFromConnection(attackerConnection).orElse(null), Topic.parse(
            "$provisioningConfig.realm/$mqttDevice1SnoopClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/customAttribute/${weatherAsset.id}"))

    when: "the anonymous attacker attempts the original restricted attribute write"
    def originalAttributeValue = assetStorageService.find(weatherAsset.id).getAttribute("customAttribute")
            .flatMap { it.getValue() }.orElse(null)
    conditions.eventually {
      assert device1SnoopClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    }
    device1SnoopClient.client.publishWith()
            .topic("$provisioningConfig.realm/$mqttDevice1SnoopClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/customAttribute/${weatherAsset.id}")
            .payload("31337".getBytes(StandardCharsets.UTF_8))
            .qos(MqttQos.AT_LEAST_ONCE).send()
            .orTimeout(5, TimeUnit.SECONDS)
            .handle { publish, error -> error }.get(10, TimeUnit.SECONDS)

    then: "the protected attribute remains unchanged"
    assert assetStorageService.find(weatherAsset.id).getAttribute("customAttribute")
    .flatMap { it.getValue() }.orElse(null) == originalAttributeValue

    when: "the attacker connection is removed"
    device1SnoopClient.disconnect()

    then: "the attacker connection is removed from the broker"
    conditions.eventually {
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1SnoopClientId) == null
    }

    when: "the client gets abruptly disconnected"
    device1Responses.clear()
    existingConnection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
    //        ((NioSocketChannel)((MqttClientConnectionConfig)((MqttClientConfig)((Mqtt3ClientConfigView)((Mqtt3AsyncClientView)device1Client.client).clientConfig).delegate).connectionConfig.get()).channel).config().setOption(ChannelOption.SO_LINGER, 0I)
    ((SocketChannel)((MqttClientConnectionConfig)((MqttClientConfig)((Mqtt3ClientConfigView)((Mqtt3AsyncClientView)device1Client.client).clientConfig).delegate).connectionConfig.get()).channel).close()

    then: "the client should reconnect"
    conditions.eventually {
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != null
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) !== existingConnection
    }

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert device1Client.topicConsumerMap.get(device1ResponseTopic) != null
      assert device1Client.topicConsumerMap.get(device1ResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != null
    }

    when: "the reconnected client replays the proof from its previous connection"
    device1Client.sendMessage(new MQTTMessage<String>(device1RequestTopic,
            ValueUtil.asJSON(device1Proofs.last()).orElseThrow()))

    then: "reusing the MQTT client ID does not make an old proof valid"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses[0] instanceof ErrorResponseMessage
      assert device1Responses[0].error == ErrorResponseMessage.Error.UNAUTHORIZED
    }
    assert !mqttBrokerService.getUserConnections(device1ServiceUser.id)
    .contains(mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId))

    when: "the client publishes a valid x509 certificate that has been signed by the CA stored in the provisioning config"
    device1Responses.clear()
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)device1Responses.get(0)).realm == managerTestSetup.realmBuildingName
      weatherAsset = ((SuccessResponseMessage)device1Responses.get(0)).asset
      assert weatherAsset != null
      assert weatherAsset instanceof WeatherAsset
      assert weatherAsset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == device1UniqueId
    }

    and: "the connected attribute of the provisioned asset should show as connected"
    conditions.eventually {
      weatherAsset = assetStorageService.find(weatherAssetID)
      assert weatherAsset.getAttribute("connected").flatMap{it.value}.orElse(false)
    }

    when: "the connected attribute user name is changed to something invalid"
    weatherAsset.getAttribute("connected").ifPresent{
      it.addOrReplaceMeta(new MetaItem<>(MetaItemType.USER_CONNECTED, "invalidUser"))
    }
    weatherAsset = assetStorageService.merge(weatherAsset)

    then: "the connected attribute should now be disconnected"
    conditions.eventually {
      weatherAsset = assetStorageService.find(weatherAssetID)
      assert !weatherAsset.getAttribute("connected").flatMap{it.value}.orElse(true)
    }

    when: "the connected attribute user name is changed to upper case"
    weatherAsset.getAttribute("connected").ifPresent{
      it.addOrReplaceMeta(new MetaItem<>(MetaItemType.USER_CONNECTED, PROVISIONING_USER_PREFIX + device1UniqueId.toUpperCase()))
    }
    weatherAsset = assetStorageService.merge(weatherAsset)

    then: "the connected attribute should still find the right user and be connected"
    conditions.eventually {
      weatherAsset = assetStorageService.find(weatherAssetID)
      assert weatherAsset.getAttribute("connected").flatMap{it.value}.orElse(false)
    }

    when: "the client then subscribes to attribute events for the generated asset and asset events for all assets"
    def asset = ((SuccessResponseMessage)device1Responses.get(0)).asset
    def assetSubscriptionTopic = "$provisioningConfig.realm/$mqttDevice1ClientId/$DefaultMQTTHandler.ASSET_TOPIC/#".toString()
    def attributeSubscriptionTopic = "$provisioningConfig.realm/$mqttDevice1ClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/+/$asset.id".toString()
    List<AssetEvent> assetEvents = new CopyOnWriteArrayList<>()
    List<AttributeEvent> attributeEvents = new CopyOnWriteArrayList<>()
    Consumer<MQTTMessage<String>> eventConsumer = { MQTTMessage<String> msg ->
      def event = ValueUtil.parse(msg.payload, SharedEvent.class).orElse(null)
      if (event instanceof AssetEvent) {
        assetEvents.add(event as AssetEvent)
      } else {
        attributeEvents.add(event as AttributeEvent)
      }
    }
    device1Client.addMessageConsumer(assetSubscriptionTopic, eventConsumer)
    device1Client.addMessageConsumer(attributeSubscriptionTopic, eventConsumer)

    then: "the subscriptions should be in place"
    conditions.eventually {
      assert device1Client.topicConsumerMap.get(assetSubscriptionTopic) != null
      assert device1Client.topicConsumerMap.get(attributeSubscriptionTopic) != null
      assert device1Client.topicConsumerMap.get(assetSubscriptionTopic).consumers.size() == 1
      assert device1Client.topicConsumerMap.get(attributeSubscriptionTopic).consumers.size() == 1
      connection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
      assert connection != null
      assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
      assert defaultMQTTHandler.sessionSubscriptionConsumers.get(getConnectionIDString(connection)).size() == 2
    }

    when: "the client updates one of the provisioned asset's attributes"
    device1Client.sendMessage(
            new MQTTMessage<String>(
                    "$provisioningConfig.realm/$mqttDevice1ClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/customAttribute/${asset.id}",
                    "99"
                    )
    )

    then: "the attribute should have been updated"
    conditions.eventually {
      asset = assetStorageService.find(asset.id)
      assert asset.getAttribute("customAttribute").flatMap{it.getValue()}.orElse(0d) == 99d
    }

    and: "the client should have been notified about the attribute change"
    conditions.eventually {
      assert attributeEvents.size() == 1
      assert attributeEvents.get(0).id == asset.id
      assert attributeEvents.get(0).name == "customAttribute"
      assert attributeEvents.get(0).value.orElse(0d) == 99d
    }

    when: "the provisioned asset is modified"
    asset.name = "New Asset Name"
    asset = assetStorageService.merge(asset)

    then: "the client should have been notified about the asset change and all attributes should have generated an attribute event"
    conditions.eventually {
      assert assetEvents.size() == 1
      assert assetEvents.get(0).id == asset.id
      assert assetEvents.get(0).assetName == asset.name
    }

    when: "another asset's attribute is updated within the system"
    assetProcessingService.sendAttributeEvent(new AttributeEvent(managerTestSetup.apartment2LivingroomId, "lightSwitch", true))

    then: "the internal consumer should have been notified"
    conditions.eventually {
      assert internalAttributeEvents.find{
        it.id == managerTestSetup.apartment2LivingroomId && it.name == "lightSwitch" && it.value.orElse(false)
      } != null
    }

    and: "the client should not have been notified"
    conditions.eventually {
      assert attributeEvents.size() == 1
    }

    when: "the client disconnects"
    connection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
    device1Client.disconnect()

    then: "all subscriptions should be removed and the client should be disconnected"
    conditions.eventually {
      assert !defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
      assert device1Client.getConnectionStatus() == ConnectionStatus.DISCONNECTED
      assert userAssetProvisioningMQTTHandler.responseTopicOwners.isEmpty()
    }

    and: "the connected attribute of the provisioned asset should show as not connected"
    conditions.eventually {
      weatherAsset = assetStorageService.find(weatherAsset.id)
      assert !weatherAsset.getAttribute("connected").flatMap{it.value}.orElse(true)
    }

    when: "the client reconnects"
    device1Client.connect()

    then: "the client should be connected and subscriptions reset"
    conditions.eventually {
      assert device1Client.getConnectionStatus() == ConnectionStatus.CONNECTED
      connection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
      assert connection != null
    }

    and: "the provisioning subscription should be re-instated but the attribute and asset subscriptions should fail"
    conditions.eventually {
      assert device1Client.topicConsumerMap.get(assetSubscriptionTopic) == null
      assert device1Client.topicConsumerMap.get(attributeSubscriptionTopic) == null
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != null
      connection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
      assert connection != null
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
      assert !defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
      assert subscribeFailures.size() == 2
      assert subscribeFailures.contains(assetSubscriptionTopic)
      assert subscribeFailures.contains(attributeSubscriptionTopic)
    }

    when: "the client re-authenticates"
    device1Responses.clear()
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)device1Responses.get(0)).realm == managerTestSetup.realmBuildingName
      asset = ((SuccessResponseMessage)device1Responses.get(0)).asset
      assert asset != null
      assert asset instanceof WeatherAsset
      assert asset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == device1UniqueId
    }

    and: "the connection should be recorded against the provisioning config"
    conditions.eventually {
      assert userAssetProvisioningMQTTHandler.provisioningConfigAuthenticatedConnectionMap.get(provisioningConfig.id) != null
      assert userAssetProvisioningMQTTHandler.provisioningConfigAuthenticatedConnectionMap.get(provisioningConfig.id).size() == 1
    }

    when: "the client subscribes again to the asset and attributes"
    device1Client.addMessageConsumer(assetSubscriptionTopic, eventConsumer)
    device1Client.addMessageConsumer(attributeSubscriptionTopic, eventConsumer)

    then: "the subscriptions should be in place"
    conditions.eventually {
      assert device1Client.topicConsumerMap.get(assetSubscriptionTopic) != null
      assert device1Client.topicConsumerMap.get(attributeSubscriptionTopic) != null
      assert device1Client.topicConsumerMap.get(assetSubscriptionTopic).consumers.size() == 1
      assert device1Client.topicConsumerMap.get(attributeSubscriptionTopic).consumers.size() == 1
      connection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
      assert connection != null
      assert defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(connection))
      assert defaultMQTTHandler.sessionSubscriptionConsumers.get(getConnectionIDString(connection)).size() == 2
    }

    when: "a second device connects"
    def deviceNUniqueId = "device2"
    def deviceNRequestTopic = "$PROVISIONING_TOKEN/$deviceNUniqueId/$REQUEST_TOKEN".toString()
    def deviceNResponseTopic = "$PROVISIONING_TOKEN/$deviceNUniqueId/$RESPONSE_TOKEN".toString()
    def mqttDeviceNClientId = UniqueIdentifierGenerator.generateId("deviceN")
    deviceNClient = new MQTT_IOClient(mqttDeviceNClientId, mqttHost, mqttPort, false, false, null, null, null)
    deviceNClient.setTopicSubscribeFailureConsumer(deviceNSubscribeFailureCallback)
    deviceNClient.connect()

    then: "mqtt connection should exist"
    conditions.eventually {
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client subscribes to the provisioning endpoints"
    List<ProvisioningMessage> deviceNResponses = new CopyOnWriteArrayList<>()
    Consumer<MQTTMessage<String>> deviceNMessageConsumer = { MQTTMessage<String> msg ->
      def response = ValueUtil.parse(msg.payload, ProvisioningMessage.class).orElse(null)
      if (response instanceof X509ProvisioningChallengeResponseMessage) {
        def proof = signChallenge(response, "device3_caexpired")
        deviceNClient.sendMessage(new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(proof).orElseThrow()))
      } else {
        deviceNResponses.add(response)
      }
    }
    deviceNClient.addMessageConsumer(deviceNResponseTopic, deviceNMessageConsumer)

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic) != null
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDeviceNClientId, deviceNResponseTopic)
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client publishes an expired but valid x509 certificate that has been signed by the CA stored in the provisioning config"
    deviceNClient.sendMessage(
            new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device2_expired.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic an error message"
    conditions.eventually {
      assert deviceNResponses.size() == 1
      assert deviceNResponses.get(0) instanceof ErrorResponseMessage
      assert ((ErrorResponseMessage)deviceNResponses.get(0)).error == ErrorResponseMessage.Error.UNAUTHORIZED
    }

    when: "a valid certificate is submitted on a different device's topic"
    deviceNResponses.clear()
    deviceNClient.sendMessage(new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
            ).orElseThrow()))

    then: "the certificate common name must still match the topic"
    conditions.eventually {
      assert deviceNResponses.size() == 1
      assert deviceNResponses[0] instanceof ErrorResponseMessage
      assert deviceNResponses[0].error == ErrorResponseMessage.Error.UNIQUE_ID_MISMATCH
    }

    when: "the client disconnects"
    deviceNClient.removeAllMessageConsumers()
    deviceNClient.disconnect()

    then: "the connection should be removed from the broker"
    conditions.eventually {
      assert mqttBrokerService.clientIDConnectionMap.get(mqttDeviceNClientId) == null
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.DISCONNECTED
    }

    when: "another device connects"
    deviceNResponses.clear()
    deviceNUniqueId = "device4"
    deviceNRequestTopic = "$PROVISIONING_TOKEN/$deviceNUniqueId/$REQUEST_TOKEN".toString()
    deviceNResponseTopic = "$PROVISIONING_TOKEN/$deviceNUniqueId/$RESPONSE_TOKEN".toString()
    deviceNClient = new MQTT_IOClient(mqttDeviceNClientId, mqttHost, mqttPort, false, false, null, null, null)
    deviceNClient.setTopicSubscribeFailureConsumer(deviceNSubscribeFailureCallback)
    deviceNClient.connect()

    then: "mqtt connection should exist"
    conditions.eventually {
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client subscribes to the provisioning endpoints"
    deviceNClient.addMessageConsumer(deviceNResponseTopic, deviceNMessageConsumer)

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic) != null
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDeviceNClientId, deviceNResponseTopic)
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client publishes a forged x509 certificate that has been signed by a forged CA"
    deviceNClient.sendMessage(
            new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device4_forged.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic an error message"
    conditions.eventually {
      assert deviceNResponses.size() == 1
      assert deviceNResponses.get(0) instanceof ErrorResponseMessage
      assert ((ErrorResponseMessage)deviceNResponses.get(0)).error == ErrorResponseMessage.Error.UNAUTHORIZED
    }

    when: "the client disconnects"
    deviceNClient.removeAllMessageConsumers()
    deviceNClient.disconnect()

    then: "the connection should be removed from the broker"
    conditions.eventually {
      assert mqttBrokerService.clientIDConnectionMap.get(mqttDeviceNClientId) == null
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.DISCONNECTED
    }

    when: "another device connects"
    deviceNResponses.clear()
    deviceNUniqueId = "device3"
    deviceNRequestTopic = "$PROVISIONING_TOKEN/$deviceNUniqueId/$REQUEST_TOKEN".toString()
    deviceNResponseTopic = "$PROVISIONING_TOKEN/$deviceNUniqueId/$RESPONSE_TOKEN".toString()
    deviceNClient = new MQTT_IOClient(mqttDeviceNClientId, mqttHost, mqttPort, false, false, null, null, null)
    deviceNClient.setTopicSubscribeFailureConsumer(deviceNSubscribeFailureCallback)
    deviceNClient.connect()

    then: "mqtt connection should exist"
    conditions.eventually {
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client subscribes to the provisioning endpoints"
    deviceNClient.addMessageConsumer(deviceNResponseTopic, deviceNMessageConsumer)

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic) != null
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDeviceNClientId, deviceNResponseTopic)
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client publishes a valid x509 certificate that has been signed by the CA stored in the provisioning config but against an expired certificate"
    deviceNClient.sendMessage(
            new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device3_caexpired.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    conditions.eventually {
      assert deviceNResponses.size() == 1
      assert deviceNResponses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)deviceNResponses.get(0)).realm == managerTestSetup.realmBuildingName
      asset = ((SuccessResponseMessage)deviceNResponses.get(0)).asset
      assert asset != null
      assert asset instanceof WeatherAsset
      assert asset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == deviceNUniqueId
    }

    when: "the client disconnects"
    deviceNClient.removeAllMessageConsumers()
    deviceNClient.disconnect()

    then: "the connection should be removed from the broker"
    conditions.eventually {
      assert mqttBrokerService.clientIDConnectionMap.get(mqttDeviceNClientId) == null
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.DISCONNECTED
    }

    when: "the provisioning config is updated to disabled"
    existingConnection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
    device1Responses.clear()
    connectionStatuses.clear()
    subscribeFailures.clear()
    provisioningConfig.setDisabled(true)
    provisioningConfig = provisioningService.merge(provisioningConfig)

    then: "already connected client that was authenticated should be disconnected, then reconnect and should fail to re-subscribe to asset and attribute events"
    conditions.eventually {
      assert connectionStatuses.size() >= 1
      assert connectionStatuses.get(0) == ConnectionStatus.CONNECTING
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != null
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != existingConnection
      connection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
      assert connection != null
      subscribeFailures.size() == 2
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
    }

    when: "the re-connected client re-authenticates"
    device1Responses.clear()
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic an error message"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof ErrorResponseMessage
      assert ((ErrorResponseMessage)device1Responses.get(0)).error == ErrorResponseMessage.Error.CONFIG_DISABLED
    }

    when: "another device re-connects"
    deviceNResponses.clear()
    deviceNClient = new MQTT_IOClient(mqttDeviceNClientId, mqttHost, mqttPort, false, false, null, null, null)
    deviceNClient.setTopicSubscribeFailureConsumer(deviceNSubscribeFailureCallback)
    deviceNClient.connect()

    then: "mqtt connection should exist"
    conditions.eventually {
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client subscribes to the provisioning endpoints"
    deviceNClient.addMessageConsumer(deviceNResponseTopic, deviceNMessageConsumer)

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic) != null
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDeviceNClientId, deviceNResponseTopic)
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client publishes a valid x509 certificate that has been signed by the CA stored in the provisioning config but against an expired certificate"
    deviceNClient.sendMessage(
            new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device3_caexpired.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic an error message"
    conditions.eventually {
      assert deviceNResponses.size() == 1
      assert deviceNResponses.get(0) instanceof ErrorResponseMessage
      assert ((ErrorResponseMessage)deviceNResponses.get(0)).error == ErrorResponseMessage.Error.CONFIG_DISABLED
    }

    when: "the client disconnects"
    deviceNClient.removeAllMessageConsumers()
    deviceNClient.disconnect()

    then: "the connection should be removed from the broker"
    conditions.eventually {
      assert mqttBrokerService.clientIDConnectionMap.get(mqttDeviceNClientId) == null
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.DISCONNECTED
    }

    when: "the provisioning config is updated to enabled"
    provisioningConfig.setDisabled(false)
    provisioningConfig = provisioningService.merge(provisioningConfig)

    and: "the already connected device publishes its' valid client certificate"
    device1Responses.clear()
    conditions.eventually {
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
    }
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)device1Responses.get(0)).realm == managerTestSetup.realmBuildingName
      asset = ((SuccessResponseMessage)device1Responses.get(0)).asset
      assert asset != null
      assert asset instanceof WeatherAsset
      assert asset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == device1UniqueId
    }

    when: "a connected device user is disabled and an un-connected device user is disabled"
    existingConnection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
    def device1User = identityService.getIdentityProvider().getUserByUsername(managerTestSetup.realmBuildingName, User.SERVICE_ACCOUNT_PREFIX + PROVISIONING_USER_PREFIX + device1UniqueId)
    def deviceNUser = identityService.getIdentityProvider().getUserByUsername(managerTestSetup.realmBuildingName, User.SERVICE_ACCOUNT_PREFIX + PROVISIONING_USER_PREFIX + deviceNUniqueId)
    device1User.setEnabled(false)
    deviceNUser.setEnabled(false)
    device1User = identityService.getIdentityProvider().createUpdateUser(managerTestSetup.realmBuildingName, device1User, null, true)
    deviceNUser = identityService.getIdentityProvider().createUpdateUser(managerTestSetup.realmBuildingName, deviceNUser, null, true)

    then: "already connected client that was authenticated should be disconnected, then reconnect"
    conditions.eventually {
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != null
      assert mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId) != existingConnection
      assert !defaultMQTTHandler.sessionSubscriptionConsumers.containsKey(getConnectionIDString(existingConnection))
      assert responseSubscribed(mqttDevice1ClientId, device1ResponseTopic)
    }

    when: "the re-connected client re-authenticates"
    device1Responses.clear()
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic an error message"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof ErrorResponseMessage
      assert ((ErrorResponseMessage)device1Responses.get(0)).error == ErrorResponseMessage.Error.USER_DISABLED
    }

    when: "a device with disabled user account connects"
    deviceNResponses.clear()
    deviceNClient = new MQTT_IOClient(mqttDeviceNClientId, mqttHost, mqttPort, false, false, null, null, null)
    deviceNClient.setTopicSubscribeFailureConsumer(deviceNSubscribeFailureCallback)
    deviceNClient.connect()

    then: "mqtt connection should exist"
    conditions.eventually {
      assert deviceNClient.getConnectionStatus() == ConnectionStatus.CONNECTED
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client subscribes to the provisioning endpoints"
    deviceNClient.addMessageConsumer(deviceNResponseTopic, deviceNMessageConsumer)

    then: "the subscriptions should succeed"
    conditions.eventually {
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic) != null
      assert deviceNClient.topicConsumerMap.get(deviceNResponseTopic).consumers.size() == 1
      assert responseSubscribed(mqttDeviceNClientId, deviceNResponseTopic)
      assert mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId) != null
    }

    when: "the client publishes a valid x509 certificate that has been signed by the CA stored in the provisioning config but against an expired certificate"
    deviceNClient.sendMessage(
            new MQTTMessage<String>(deviceNRequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device3_caexpired.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic an error message"
    conditions.eventually {
      assert deviceNResponses.size() == 1
      assert deviceNResponses.get(0) instanceof ErrorResponseMessage
      assert ((ErrorResponseMessage)deviceNResponses.get(0)).error == ErrorResponseMessage.Error.USER_DISABLED
    }

    when: "a client user is re-enabled"
    existingConnection = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId)
    mqttBrokerService.getConnectionFromClientID(mqttDeviceNClientId)
    device1User.setEnabled(true)
    device1User = identityService.getIdentityProvider().createUpdateUser(managerTestSetup.realmBuildingName, device1User, null, true)

    and: "the re-enabled client device publishes its' valid client certificate"
    device1Responses.clear()
    device1Client.sendMessage(
            new MQTTMessage<String>(device1RequestTopic, ValueUtil.asJSON(
                    new X509ProvisioningChallengeRequestMessage(getClass().getResource("/org/openremote/test/provisioning/device1.pem").text)
                    ).orElse(null))
    )

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    conditions.eventually {
      assert device1Responses.size() == 1
      assert device1Responses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)device1Responses.get(0)).realm == managerTestSetup.realmBuildingName
      asset = ((SuccessResponseMessage)device1Responses.get(0)).asset
      assert asset != null
      assert asset instanceof WeatherAsset
      assert asset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == device1UniqueId
    }

    cleanup: "disconnect the clients"
    if (device1Client != null) {
      device1Client.disconnect()
    }
    if (device1SnoopClient != null) {
      device1SnoopClient.disconnect()
    }
    if (deviceNClient != null) {
      deviceNClient.disconnect()
    }
    if (internalConsumer != null) {
      clientEventService.removeSubscription {internalConsumer}
    }
  }

  /**
   * The test certificates don't all have their own key; device3_caexpired was issued for the
   * key of device2.
   */
  static final Map<String, String> CERTIFICATE_KEYS = [
    "device1": "device1",
    "device2_expired": "device2",
    "device3_caexpired": "device2",
    "device4_forged": "device4"
  ]

  static X509ProvisioningMessage signChallenge(X509ProvisioningChallengeResponseMessage challenge,
          String certificateName) {
    def keyName = CERTIFICATE_KEYS[certificateName]
    assert keyName != null
    def privateKey
    UserAndAssetProvisioningTest.getResource("/org/openremote/test/provisioning/${keyName}.key").withReader { reader ->
      def keyPair = new PEMParser(reader).readObject() as PEMKeyPair
      privateKey = new JcaPEMKeyConverter().getKeyPair(keyPair).private
    }
    def signer = Signature.getInstance(challenge.algorithm)
    signer.initSign(privateKey)
    signer.update(challenge.challenge.getBytes(StandardCharsets.UTF_8))
    return new X509ProvisioningMessage(
            UserAndAssetProvisioningTest.getResource("/org/openremote/test/provisioning/${certificateName}.pem").text,
            Base64.encoder.encodeToString(signer.sign()))
  }

  @SuppressWarnings("GroovyAccessibility")
  def "mTLS MQTT Autoprovisioning integration test"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.1)

    and: "temporary directories are created"
    def tempDir = new File(System.getProperty("java.io.tmpdir"), "openremote-mtls-test-keystores-" + System.currentTimeMillis())
    def tempManagerDir = new File(System.getProperty("java.io.tmpdir"), "openremote-tmp-" + System.currentTimeMillis())
    tempDir.mkdirs()
    tempManagerDir.mkdirs()
    getLOG().info("KeyStore TempDir: $tempDir.absoluteFile")
    getLOG().info("Manager TempDir: $tempManagerDir.absoluteFile")

    and: "test configuration is set up"
    def serverKeystorePath = new File(tempDir, "server-keystore.p12").absolutePath
    def serverTruststorePath = new File(tempDir, "server-truststore.p12").absolutePath
    def keystorePassword = "secret1"
    def provisionedAccountAliasName = "mtlsclient"
    def provisionedAccountKeyAlias = "$Constants.MASTER_REALM.$provisionedAccountAliasName"
    def ProvisionedAccountUserName = "mtlstest2"

    and: "mTLS certificate helper is initialized"
    def mtlsHelper = new MTLSCertificateHelper()

    and: "server and client certificates are generated"
    def (serverKeyPair, serverCert) = mtlsHelper.generateServerCertificate()
    def (clientKeyPair, clientCert) = mtlsHelper.generateClientCertificate(ProvisionedAccountUserName, Constants.MASTER_REALM)

    and: "server keystores are created and saved to disk"
    mtlsHelper.createAndSaveServerKeystores(
            serverKeystorePath,
            serverTruststorePath,
            keystorePassword,
            provisionedAccountKeyAlias,
            serverKeyPair,
            serverCert
            )

    and: "the container configuration is set up with MTLS environment variables"
    def mtlsPort = findEphemeralPort()
    def config = defaultConfig()
    config.put(Constants.OR_MQTT_MTLS_SERVER_LISTEN_HOST, "localhost")
    config.put(Constants.OR_MQTT_MTLS_SERVER_LISTEN_PORT, mtlsPort.toString())
    config.put(Constants.OR_MQTT_MTLS_DISABLED, "false")
    config.put(Constants.OR_MQTT_MTLS_KEYSTORE_PATH, serverKeystorePath)
    config.put(Constants.OR_MQTT_MTLS_KEYSTORE_PASSWORD, keystorePassword)
    config.put(Constants.OR_MQTT_MTLS_TRUSTSTORE_PATH, serverTruststorePath)
    config.put(Constants.OR_MQTT_MTLS_TRUSTSTORE_PASSWORD, keystorePassword)
    config.put(KeyStoreServiceImpl.OR_KEYSTORE_PASSWORD, keystorePassword)

    and: "the keycloak grant file is pinned to the shared location before the storage dir is redirected"
    // A throwaway storage dir isolates this test's keystores, but it must not take the keycloak
    // grant file with it: the identity provider treats a missing grant file as a first install and
    // rotates the manager-keycloak password, which lives in keycloak's shared database. That would
    // invalidate the grant every other test - and the keycloak Flyway migrations - depend on.
    def sharedGrantFile = Paths.get(
            config.getOrDefault(PersistenceService.OR_STORAGE_DIR, PersistenceService.OR_STORAGE_DIR_DEFAULT))
            .resolve(config.getOrDefault(
                    ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE,
                    ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE_DEFAULT))
            .toAbsolutePath()
            .toString()
    config.put(ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE, sharedGrantFile)
    config.put(PersistenceService.OR_STORAGE_DIR, tempManagerDir.getAbsolutePath())


    and: "the container starts"
    def container = startContainer(config, defaultServices())
    def keystoreService = container.getService(KeyStoreServiceImpl.class)
    def mqttBrokerService = container.getService(MQTTBrokerService.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def provisioningService = container.getService(ProvisioningService.class)
    def testSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def mqttHost = "localhost"
    def mqttPort = mtlsPort
    when: "A new keypair, properly signed, is created, but the service user for it is not created"
    def unprovisionedUsername = "unprovisioneduser"
    def (unprovisionedKeyPair, unprovisionedCert) = mtlsHelper.generateClientCertificate(
            unprovisionedUsername,
            Constants.MASTER_REALM,
            4 // serial offset
            )

    and: "It is added and saved to the KeyStoreService keystore"
    def unprovisionedKeyAlias = Constants.MASTER_REALM + "unprovisionedclient"
    mtlsHelper.addClientCertificateToKeyStoreService(
            keystoreService,
            unprovisionedKeyAlias,
            keystorePassword,
            unprovisionedKeyPair,
            unprovisionedCert
            )

    and: "a client with unprovisioned certificate connects, for testing mTLS autoprovisioning"
    String autoprovisioningClientId = "unprovisionedDevice"
    MQTT_IOClient autoprovisioningClient = new MQTT_IOClient(
            autoprovisioningClientId,
            mqttHost,
            mqttPort,
            true,
            false,
            null,
            null,
            null,
            keystoreService.getKeyManagerFactory(unprovisionedKeyAlias),
            keystoreService.getTrustManagerFactory()
            )

    and: "the client connects"
    autoprovisioningClient.connect()

    then: "The client connects with 3 principals"
    conditions.eventually {
      Subject subject = mqttBrokerService.getConnectionFromClientID(autoprovisioningClientId).getSubject()
      assert subject.getPrincipals().size() == 3
    }

    when: "the provisioning config is created to allow autoprovisioning using mTLS"

    def provisioningConfig = new X509ProvisioningConfig("Valid Test Config",
            new X509ProvisioningData()
            .setCACertPEM(MTLSCertificateHelper.getPemString(mtlsHelper.getRootCACertificate()))
            ).setAssetTemplate(
            ValueUtil.asJSON(
                    new ShipAsset("Ship Asset")
                    .addAttributes(
                            new Attribute<>("customAttribute", NUMBER).addMeta(
                                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_READ),
                                    new MetaItem<>(MetaItemType.ACCESS_RESTRICTED_WRITE)
                                    ),
                            new Attribute<>("serialNumber", ValueType.TEXT, UNIQUE_ID_PLACEHOLDER),
                            new Attribute<>("connected", BOOLEAN).addMeta(
                                    new MetaItem<>(MetaItemType.USER_CONNECTED, PROVISIONING_USER_PREFIX + "device1")
                                    )
                            )
                    ).orElse("")
            ).setRealm(Constants.MASTER_REALM)
            .setRestrictedUser(true)
            .setUserRoles([
              ClientRole.WRITE_ASSETS,
              ClientRole.WRITE_ATTRIBUTES,
              ClientRole.READ_ASSETS
            ] as ClientRole[])
    provisioningConfig = provisioningService.merge(provisioningConfig)


    and: "We publish a message to the autoprovisioning message topic"

    def autoProvisioningRequestTopic = "$PROVISIONING_TOKEN/$unprovisionedUsername/$REQUEST_TOKEN".toString()
    def autoProvisioningResponseTopic = "$PROVISIONING_TOKEN/$unprovisionedUsername/$RESPONSE_TOKEN".toString()
    List<ProvisioningMessage> autoProvisioningResponses = new CopyOnWriteArrayList<>()
    Consumer<MQTTMessage<String>> autoProvisioningMessageConsumer = { MQTTMessage<String> msg ->
      autoProvisioningResponses.add(ValueUtil.parse(msg.payload, ProvisioningMessage.class).orElse(null))
    }
    autoprovisioningClient.addMessageConsumer(autoProvisioningResponseTopic, autoProvisioningMessageConsumer)
    // Only the connection that owns the response topic may publish requests, and the broker
    // registers the subscription asynchronously, so wait for it to claim the topic first
    def provisioningHandler = mqttBrokerService.customHandlers.find {
      it instanceof UserAssetProvisioningMQTTHandler
    } as UserAssetProvisioningMQTTHandler
    conditions.eventually {
      def brokerConnection = mqttBrokerService.getConnectionFromClientID(autoprovisioningClientId)
      assert brokerConnection != null
      assert provisioningHandler.responseTopicOwners.get(autoProvisioningResponseTopic) === brokerConnection
    }
    autoprovisioningClient.sendMessage(
            new MQTTMessage<String>(autoProvisioningRequestTopic, ValueUtil.JSON.writeValueAsString(new MTLSProvisioningMessage()))
            )

    then: "we should receive a success response provisioning message"
    conditions.eventually {
      assert autoProvisioningResponses.size() == 1
      assert autoProvisioningResponses.get(0) instanceof SuccessResponseMessage
    }

    then: "the broker should have published to the response topic a success message containing the provisioned asset"
    ShipAsset provisionedAsset
    conditions.eventually {
      assert autoProvisioningResponses.size() == 1
      assert autoProvisioningResponses.get(0) instanceof SuccessResponseMessage
      assert ((SuccessResponseMessage)autoProvisioningResponses.get(0)).realm == Constants.MASTER_REALM
      provisionedAsset = (ShipAsset)((SuccessResponseMessage)autoProvisioningResponses.get(0)).asset
      assert provisionedAsset != null
      assert provisionedAsset instanceof ShipAsset
      assert provisionedAsset.getAttribute("serialNumber").flatMap{
        it.getValue()
      }.orElse(null) == unprovisionedUsername
    }

    when: "the device disconnects"
    autoprovisioningClient.disconnect()

    then: "the client should reach the disconnected state"
    // disconnect() is asynchronous and connect() is a no-op unless the client is already
    // DISCONNECTED, so reconnecting without waiting here silently never reconnects
    conditions.eventually {
      assert autoprovisioningClient.getConnectionStatus() == ConnectionStatus.DISCONNECTED
    }

    when: "it reconnects again"
    autoProvisioningResponses.clear()
    autoprovisioningClient.connect()

    then: "the connection should be created with the provisioned user and roles"
    conditions.eventually {
      assert autoprovisioningClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    and: "the authenticated client should have the correct subject with roles"
    conditions.eventually {
      Subject unprovisionedJaasSubject = mqttBrokerService.getConnectionFromClientID(autoprovisioningClientId).getSubject()
      assert unprovisionedJaasSubject != null
      // New architecture creates a RemotingConnectionPrincipal, TokenPrincipal and UserPrincipal for the provisioned service user
      assert unprovisionedJaasSubject.getPrincipals().size() == 3
      assert unprovisionedJaasSubject.getPrincipals(org.openremote.container.security.TokenPrincipal.class).size() == 1
      assert unprovisionedJaasSubject.getPrincipals(UserPrincipal.class).size() == 1
      // ActiveMQORSecurityManager names the UserPrincipal with the keycloak subject id, so the
      // username is asserted on the TokenPrincipal, which exposes the preferred_username claim.
      // The provisioning prefix is part of the name: devices created by auto-provisioning are
      // named the same way whichever flow provisioned them, and certificate authentication
      // looks for that name before the bare CN
      assert unprovisionedJaasSubject.getPrincipals(org.openremote.container.security.TokenPrincipal.class)[0].getUsername() == "$User.SERVICE_ACCOUNT_PREFIX$PROVISIONING_USER_PREFIX$unprovisionedUsername"
    }

    and: "the service user is discoverable under the provisioning naming convention"
    identityService.getIdentityProvider().getUserByUsername(
            Constants.MASTER_REALM,
            User.SERVICE_ACCOUNT_PREFIX + PROVISIONING_USER_PREFIX + unprovisionedUsername) != null

    when: "the client then subscribes to attribute events for the generated asset and asset events for all assets"
    def assetSubscriptionTopic = "$provisioningConfig.realm/$autoprovisioningClientId/$DefaultMQTTHandler.ASSET_TOPIC/#".toString()
    def attributeSubscriptionTopic = "$provisioningConfig.realm/$autoprovisioningClientId/$DefaultMQTTHandler.ATTRIBUTE_TOPIC/+/${provisionedAsset.id}".toString()
    List<AssetEvent> assetEvents = new CopyOnWriteArrayList<>()
    List<AttributeEvent> attributeEvents = new CopyOnWriteArrayList<>()
    Consumer<MQTTMessage<String>> eventConsumer = { MQTTMessage<String> msg ->
      def event = ValueUtil.parse(msg.payload, SharedEvent.class).orElse(null)
      if (event instanceof AssetEvent) {
        assetEvents.add(event as AssetEvent)
      } else {
        attributeEvents.add(event as AttributeEvent)
      }
    }
    autoprovisioningClient.addMessageConsumer(assetSubscriptionTopic, eventConsumer)
    autoprovisioningClient.addMessageConsumer(attributeSubscriptionTopic, eventConsumer)

    then: "the subscriptions should be in place"
    conditions.eventually {
      assert autoprovisioningClient.topicConsumerMap.get(assetSubscriptionTopic) != null
      assert autoprovisioningClient.topicConsumerMap.get(attributeSubscriptionTopic) != null
      assert autoprovisioningClient.topicConsumerMap.get(assetSubscriptionTopic).consumers.size() == 1
      assert autoprovisioningClient.topicConsumerMap.get(attributeSubscriptionTopic).consumers.size() == 1
      def connection = mqttBrokerService.getConnectionFromClientID(autoprovisioningClientId)
      assert connection != null
    }

    when: "the client updates one of the provisioned asset's attributes"
    autoprovisioningClient.sendMessage(
            new MQTTMessage<String>(
                    "$provisioningConfig.realm/$autoprovisioningClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/customAttribute/${provisionedAsset.id}",
                    "123"
                    )
            )

    then: "the attribute should have been updated"
    conditions.eventually {
      def asset = assetStorageService.find(provisionedAsset.id)
      assert asset.getAttribute("customAttribute").flatMap{it.getValue()}.orElse(0d) == 123d
    }

    and: "the client should have been notified about the attribute change"
    conditions.eventually {
      assert attributeEvents.size() == 1
      assert attributeEvents.get(0).id == provisionedAsset.id
      assert attributeEvents.get(0).name == "customAttribute"
      assert attributeEvents.get(0).value.orElse(0d) == 123d
    }


    and: "an attribute update is sent for the onOff attribute with a value of true"


    assetProcessingService.sendAttributeEvent(new AttributeEvent(
                    testSetup.light1Id,
                    "onOff",
                    false,
                    System.currentTimeMillis()
                    ))

    when: "the client tries to send an attribute event to that unlinked asset"
    autoprovisioningClient.sendMessage(
            new MQTTMessage<String>(
                    "$provisioningConfig.realm/$autoprovisioningClientId/$DefaultMQTTHandler.ATTRIBUTE_VALUE_WRITE_TOPIC/onOff/${testSetup.light1Id}",
                    "true"
                    )
            )

    then: "the attribute should not have been updated"
    conditions.eventually {
      LOG.info("Waiting for the system to settle down")
      def j = 0
      while (j < 10 && !noEventProcessedIn(assetProcessingService, 100)) {
        Thread.sleep(100)
        j++
      }

      def asset = assetStorageService.find(testSetup.light1Id)
      assert asset.getAttribute("onOff").flatMap{it.getValue()}.orElse(true) == false
    }

    cleanup: "disconnect the client and cleanup temporary files"
    autoprovisioningClient.disconnect()

    and: "delete the created temporary directories"
    tempDir.deleteDir()
    tempManagerDir.deleteDir()
  }
}
