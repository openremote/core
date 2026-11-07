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
package org.openremote.manager.mqtt

import com.google.common.base.Ticker
import io.netty.handler.codec.mqtt.MqttQoS
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection
import org.apache.activemq.artemis.spi.core.remoting.Connection
import org.openremote.model.protocol.mqtt.Topic
import org.openremote.model.provisioning.ProvisioningMessage
import org.openremote.model.provisioning.X509ProvisioningChallengeRequestMessage
import org.openremote.model.provisioning.X509ProvisioningChallengeResponseMessage
import org.openremote.model.provisioning.X509ProvisioningMessage
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class X509ProvisioningProofTest extends Specification {

  def ticker = new TestTicker()
  def handler = new TestHandler(ticker)
  def connection = Stub(RemotingConnection) {
    getClientID() >> "device-client"
    getTransportConnection() >> Stub(Connection) {
      isOpen() >> true
    }
  }
  def topic = Topic.parse("provisioning/device1/request")
  def keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair()
  def certificate = certificateFor(keyPair)

  def setup() {
    handler.isKeycloak = true
    assert handler.canSubscribe(connection, null, Topic.parse("provisioning/device1/response"))
    handler.onSubscribe(connection, Topic.parse("provisioning/device1/response"))
  }

  def "a valid #keyAlgorithm proof succeeds exactly once"() {
    given:
    def keys = KeyPairGenerator.getInstance(keyAlgorithm).generateKeyPair()
    def cert = certificateFor(keys)
    handler.issueX509Challenge(connection, topic, cert)
    def challenge = handler.messages.last() as X509ProvisioningChallengeResponseMessage
    def signature = sign(challenge, keys)

    expect:
    handler.verifyX509Proof(connection, topic, cert, signature)
    !handler.verifyX509Proof(connection, topic, cert, signature)

    where:
    keyAlgorithm << ["RSA", "EC", "Ed25519", "Ed448"]
  }

  def "a public certificate without proof never authenticates"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)

    expect:
    !handler.verifyX509Proof(connection, topic, certificate, null)
    handler.provisioningChallenges.size() == 0
  }

  def "a signature is bound to the transport connection even when the client ID is reused"() {
    given:
    def otherConnection = Stub(RemotingConnection) {
      getClientID() >> "device-client"
    }
    handler.issueX509Challenge(connection, topic, certificate)
    def signature = sign(handler.messages.last(), keyPair)

    expect:
    !handler.verifyX509Proof(otherConnection, topic, certificate, signature)
    handler.verifyX509Proof(connection, topic, certificate, signature)
  }

  def "a signature cannot be used with another topic or certificate"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def signature = sign(handler.messages.last(), keyPair)
    def otherCertificate = certificateFor(KeyPairGenerator.getInstance("RSA").generateKeyPair())

    expect:
    !handler.verifyX509Proof(connection, Topic.parse("provisioning/device2/request"), certificate, signature)

    when:
    handler.issueX509Challenge(connection, topic, certificate)
    signature = sign(handler.messages.last(), keyPair)

    then:
    !handler.verifyX509Proof(connection, topic, otherCertificate, signature)
  }

  def "a wrong private key or malformed signature consumes the challenge"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def challenge = handler.messages.last()
    def wrongKey = KeyPairGenerator.getInstance("RSA").generateKeyPair()
    def signature = malformed ? "not base64!" : sign(challenge, wrongKey)

    expect:
    !handler.verifyX509Proof(connection, topic, certificate, signature)
    !handler.verifyX509Proof(connection, topic, certificate, sign(challenge, keyPair))

    where:
    malformed << [true, false]
  }

  def "a superseded challenge cannot be replayed"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def oldChallenge = handler.messages.last()
    handler.issueX509Challenge(connection, topic, certificate)
    def newChallenge = handler.messages.last()

    expect:
    oldChallenge.challenge != newChallenge.challenge
    !handler.verifyX509Proof(connection, topic, certificate, sign(oldChallenge, keyPair))
    !handler.verifyX509Proof(connection, topic, certificate, sign(newChallenge, keyPair))
  }

  def "challenges expire at sixty seconds"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def signature = sign(handler.messages.last(), keyPair)

    when:
    ticker.nanos += TimeUnit.SECONDS.toNanos(60)

    then:
    !handler.verifyX509Proof(connection, topic, certificate, signature)
  }

  def "only the response topic owner can publish provisioning requests"() {
    given:
    def attacker = Stub(RemotingConnection)

    expect: "the owning connection may publish its own request topic"
    handler.canPublish(connection, null, topic)

    and: "nobody else may, so an attacker cannot inject errors into the device's exchange"
    !handler.canPublish(attacker, null, topic)
    !handler.canPublish(connection, null, Topic.parse("provisioning/device2/request"))
    handler.messages.isEmpty()
  }

  def "publishing is refused until the response topic has been claimed"() {
    given:
    def device2 = Stub(RemotingConnection) {
      getTransportConnection() >> Stub(Connection) {
        isOpen() >> true
      }
    }
    def unsubscribed = Topic.parse("provisioning/device2/request")

    expect:
    !handler.canPublish(device2, null, unsubscribed)

    when:
    handler.canSubscribe(device2, null, Topic.parse("provisioning/device2/response"))

    then:
    handler.canPublish(device2, null, unsubscribed)

    when:
    handler.onUnsubscribe(device2, Topic.parse("provisioning/device2/response"))

    then:
    !handler.canPublish(device2, null, unsubscribed)
  }

  def "a connection can own only one response topic"() {
    given:
    def device1Response = Topic.parse("provisioning/device1/response")
    def device2Response = Topic.parse("provisioning/device2/response")

    expect: "re-authorizing the topic it already owns is allowed"
    handler.canSubscribe(connection, null, device1Response)

    and: "claiming a second device's topic is refused and leaves that topic free"
    !handler.canSubscribe(connection, null, device2Response)
    !handler.responseTopicOwners.containsKey(device2Response.toString())
    handler.connectionResponseTopics[connection] == device1Response.toString()

    when: "the connection releases its topic"
    handler.onUnsubscribe(connection, device1Response)

    then: "it may claim another"
    handler.canSubscribe(connection, null, device2Response)
    handler.connectionResponseTopics[connection] == device2Response.toString()
    !handler.responseTopicOwners.containsKey(device1Response.toString())
  }

  def "losing a topic race does not use up the connection's one claim"() {
    given:
    def other = Stub(RemotingConnection) {
      getTransportConnection() >> Stub(Connection) {
        isOpen() >> true
      }
    }

    expect: "another connection cannot take the owned topic"
    !handler.canSubscribe(other, null, Topic.parse("provisioning/device1/response"))

    and: "but can still claim a free one"
    handler.canSubscribe(other, null, Topic.parse("provisioning/device2/response"))
  }

  def "only the response subscriber can obtain a challenge"() {
    given:
    def attacker = Stub(RemotingConnection)

    when:
    handler.issueX509Challenge(attacker, topic, certificate)

    then: "no challenge is issued and nothing is published to the owner's response topic"
    handler.messages.isEmpty()
    handler.provisioningChallenges.size() == 0
  }

  def "a message processed after its connection lost the response topic is dropped silently"() {
    given: "the topic changes hands after the message was authorized but before it is processed"
    def newOwner = openConnection()
    handler.onUnsubscribe(connection, Topic.parse("provisioning/device1/response"))
    assert handler.canSubscribe(newOwner, null, Topic.parse("provisioning/device1/response"))

    when:
    handler.processX509ProvisioningMessage(connection, topic, cert, signature, challengeRequest)

    then: "nothing is published to the new owner's response topic"
    handler.messages.isEmpty()
    handler.provisioningChallenges.size() == 0

    where:
    challengeRequest | cert          | signature
    true             | "certificate" | null
    false            | "certificate" | "signature"
    false            | null          | null
  }

  def "a proof fails after the response subscription changes ownership"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def signature = sign(handler.messages.last(), keyPair)
    handler.onUnsubscribe(connection, Topic.parse("provisioning/device1/response"))
    def otherConnection = Stub(RemotingConnection)
    assert handler.canSubscribe(otherConnection, null, Topic.parse("provisioning/device1/response"))
    handler.onSubscribe(otherConnection, Topic.parse("provisioning/device1/response"))

    expect:
    !handler.verifyX509Proof(connection, topic, certificate, signature)
  }

  def "disconnect and connection loss clear challenges and response topic claims"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def signature = sign(handler.messages.last(), keyPair)

    when:
    if (abrupt) {
      handler.onConnectionLost(connection)
    } else {
      handler.onDisconnect(connection)
    }

    then: "the unfinished challenge is invalidated"
    !handler.verifyX509Proof(connection, topic, certificate, signature)

    and: "the connection's response topic claim is cleared"
    handler.responseTopicOwners.isEmpty()
    handler.connectionResponseTopics.isEmpty()

    where:
    abrupt << [true, false]
  }

  def "a subscribe authorized after disconnect cleanup leaves nothing behind"() {
    given: "a connection whose cleanup has run while its transport still reports open, as Artemis calls the disconnect callbacks before closing it"
    def closing = openConnection()
    def realDevice = openConnection()
    handler.onDisconnect(closing)

    expect: "the late subscribe is refused and no claim survives"
    !handler.canSubscribe(closing, null, Topic.parse("provisioning/device2/response"))
    !handler.responseTopicOwners.containsKey("provisioning/device2/response")
    !handler.connectionResponseTopics.containsKey(closing)

    and: "the topic stays free for the real device"
    handler.canSubscribe(realDevice, null, Topic.parse("provisioning/device2/response"))
  }

  def "concurrent subscribe and disconnect never leak a claim"() {
    given:
    def executor = Executors.newFixedThreadPool(2)
    def responseTopic = Topic.parse("provisioning/device2/response")

    when: "a subscribe and the disconnect cleanup of the same connection race many times"
    def leaks = (1..500).count {
      def racing = openConnection()
      def start = new CountDownLatch(1)
      executor.invokeAll([
        { start.await(); handler.canSubscribe(racing, null, responseTopic) } as Callable,
        { start.countDown(); handler.onDisconnect(racing) } as Callable
      ])
      def leaked = handler.connectionResponseTopics.containsKey(racing) ||
              handler.responseTopicOwners[responseTopic.toString()].is(racing)
      handler.responseTopicOwners.remove(responseTopic.toString())
      leaked
    }

    then:
    leaks == 0

    cleanup:
    executor.shutdownNow()
  }

  def "concurrent submissions cannot reuse a challenge"() {
    given:
    handler.issueX509Challenge(connection, topic, certificate)
    def signature = sign(handler.messages.last(), keyPair)
    def executor = Executors.newFixedThreadPool(2)

    when:
    def results = executor.invokeAll((1..2).collect {
      { handler.verifyX509Proof(connection, topic, certificate, signature) } as Callable<Boolean>
    }).collect { it.get() }

    then:
    results.count(true) == 1
    results.count(false) == 1

    cleanup:
    executor.shutdownNow()
  }

  def "concurrent subscriptions cannot share the response topic"() {
    given:
    handler.isKeycloak = true
    def transport = Stub(Connection) {
      isOpen() >> true
    }
    def connections = (1..2).collect {
      Stub(RemotingConnection) {
        getTransportConnection() >> transport
      }
    }
    def responseTopic = Topic.parse("provisioning/device2/response")
    def executor = Executors.newFixedThreadPool(2)

    when:
    def results = executor.invokeAll(connections.collect { candidate ->
      { handler.canSubscribe(candidate, null, responseTopic) } as Callable<Boolean>
    }).collect { it.get() }

    then:
    results.count(true) == 1
    results.count(false) == 1
    handler.responseTopicOwners[responseTopic.toString()].is(connections[results.indexOf(true)])

    and: "only the winner may publish the matching request topic"
    handler.canPublish(connections[results.indexOf(true)], null, Topic.parse("provisioning/device2/request"))
    !handler.canPublish(connections[results.indexOf(false)], null, Topic.parse("provisioning/device2/request"))

    cleanup:
    executor.shutdownNow()
  }

  def "provisioning messages survive JSON serialization with their wire types"() {
    given:
    def messages = [
      new X509ProvisioningChallengeRequestMessage("public certificate"),
      new X509ProvisioningChallengeResponseMessage("challenge", "SHA256withRSA"),
      new X509ProvisioningMessage("public certificate", "signature"),
      new X509ProvisioningMessage("public certificate")
    ]

    expect:
    messages.every { message ->
      def json = ValueUtil.asJSON(message).orElseThrow()
      def decoded = ValueUtil.parse(json, ProvisioningMessage.class).orElseThrow()
      decoded.class == message.class && ValueUtil.asJSON(decoded).orElseThrow() == json
    }
  }

  RemotingConnection openConnection() {
    Stub(RemotingConnection) {
      getTransportConnection() >> Stub(Connection) {
        isOpen() >> true
      }
    }
  }

  X509Certificate certificateFor(KeyPair keys) {
    Stub(X509Certificate) {
      getPublicKey() >> keys.public
      getEncoded() >> keys.public.encoded
    }
  }

  static String sign(X509ProvisioningChallengeResponseMessage challenge, KeyPair keys) {
    def signer = Signature.getInstance(challenge.algorithm)
    signer.initSign(keys.private)
    signer.update(challenge.challenge.getBytes(StandardCharsets.UTF_8))
    Base64.encoder.encodeToString(signer.sign())
  }

  static class TestTicker extends Ticker {
    long nanos

    @Override
    long read() {
      nanos
    }
  }

  static class TestHandler extends UserAssetProvisioningMQTTHandler {
    List<ProvisioningMessage> messages = []

    TestHandler(Ticker ticker) {
      super(ticker)
    }

    @Override
    void publishMessage(String topic, Object data, MqttQoS qos) {
      messages.add(data as ProvisioningMessage)
    }
  }
}
