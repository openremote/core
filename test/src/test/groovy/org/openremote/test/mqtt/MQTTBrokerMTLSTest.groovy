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
package org.openremote.test.mqtt

import org.openremote.agent.protocol.mqtt.MQTT_IOClient
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.manager.mqtt.OpenRemoteSSLContextFactory
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.nio.file.Files

import static org.openremote.model.Constants.*

/**
 * Covers the mTLS transport itself: which client certificates the acceptor accepts, and whether it
 * comes up at all. Authentication of the accepted certificate is a separate concern, so at this
 * layer an accepted client is simply an anonymous connection.
 */
class MQTTBrokerMTLSTest extends Specification implements ManagerContainerTrait {

  static final String KEYSTORE_PASSWORD = "secret1"

  def "the acceptor only accepts client certificates issued by the truststore's CA"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.1)

    and: "a device CA, and a second CA the broker knows nothing about"
    def deviceCA = new MTLSCertificateHelper()
    def foreignCA = new MTLSCertificateHelper()
    def (serverKeyPair, serverCert) = deviceCA.generateServerCertificate()
    def (deviceKeyPair, deviceCert) = deviceCA.generateClientCertificate("mtlsdevice", MASTER_REALM)
    // Same subject as the trusted device, so only the issuer distinguishes them
    def (foreignKeyPair, foreignCert) = foreignCA.generateClientCertificate("mtlsdevice", MASTER_REALM)

    and: "the broker's keystore and truststore are written to disk"
    def certDir = Files.createTempDirectory("openremote-mtls-broker-test")
    def keystorePath = certDir.resolve("server_keystore.p12").toString()
    def truststorePath = certDir.resolve("server_truststore.p12").toString()
    deviceCA.createAndSaveServerKeystores(
            keystorePath, truststorePath, KEYSTORE_PASSWORD, "server", serverKeyPair, serverCert)

    and: "the broker starts with the mTLS acceptor enabled"
    def mtlsPort = findEphemeralPort()
    def config = defaultConfig()
    config.put(OR_MQTT_MTLS_DISABLED, "false")
    config.put(OR_MQTT_MTLS_SERVER_LISTEN_PORT, mtlsPort.toString())
    config.put(OR_MQTT_MTLS_KEYSTORE_PATH, keystorePath)
    config.put(OR_MQTT_MTLS_KEYSTORE_PASSWORD, KEYSTORE_PASSWORD)
    config.put(OR_MQTT_MTLS_TRUSTSTORE_PATH, truststorePath)
    config.put(OR_MQTT_MTLS_TRUSTSTORE_PASSWORD, KEYSTORE_PASSWORD)
    def container = startContainer(config, defaultServices())
    def mqttBrokerService = container.getService(MQTTBrokerService.class)

    expect: "the acceptor to have been added"
    mqttBrokerService.mtlsAcceptorEnabled

    when: "a client presents a certificate issued by the CA in the truststore"
    def trustedClient = new MQTT_IOClient(
            "trusted-device", "localhost", mtlsPort, true, true, null, null, null,
            deviceCA.createClientKeyManagerFactory(deviceKeyPair, deviceCert, KEYSTORE_PASSWORD),
            deviceCA.createClientTrustManagerFactory())
    trustedClient.connect()

    then: "the connection is established"
    conditions.eventually {
      assert trustedClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    when: "a client presents an equivalent certificate issued by the other CA"
    def untrustedClient = new MQTT_IOClient(
            "untrusted-device", "localhost", mtlsPort, true, true, null, null, null,
            foreignCA.createClientKeyManagerFactory(foreignKeyPair, foreignCert, KEYSTORE_PASSWORD),
            deviceCA.createClientTrustManagerFactory())
    untrustedClient.connect()

    then: "it never becomes connected, however long it retries"
    // A trust chain built from the server's own certificate, or from the JDK's public CA
    // bundle, would let this through - only the configured truststore rejects it
    def deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() <deadline) {
      assert untrustedClient.getConnectionStatus() != ConnectionStatus.CONNECTED
      Thread.sleep(100)
    }

    cleanup: "the clients are disconnected and the certificates removed"
    trustedClient?.disconnect()
    untrustedClient?.disconnect()
    certDir.toFile().deleteDir()
  }

  def "client certificates are verified against the truststore, not against whoever issued the broker's own certificate"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.1)

    and: "one CA issuing the broker's certificate, and a separate one issuing device certificates"
    // This is the real deployment shape: the server certificate comes from the proxy (a public
    // CA), while devices are issued by a private CA that only the truststore names
    def proxyCA = new MTLSCertificateHelper()
    def deviceCA = new MTLSCertificateHelper()
    def (serverKeyPair, serverCert) = proxyCA.generateServerCertificate()
    def (deviceKeyPair, deviceCert) = deviceCA.generateClientCertificate("mtlsdevice", MASTER_REALM)
    def (proxyIssuedKeyPair, proxyIssuedCert) = proxyCA.generateClientCertificate("mtlsdevice", MASTER_REALM)

    and: "the broker's certificate is published where the proxy publishes it"
    def probeContainer = startContainer(defaultConfig(), defaultServices())
    def proxyCertsDir = OpenRemoteSSLContextFactory.resolveProxyCertsDir(probeContainer)
    Files.createDirectories(proxyCertsDir)
    proxyCertsDir.resolve("00-cert").text = proxyCA.getCombinedPem(serverKeyPair, serverCert)

    and: "the truststore names only the device CA"
    def certDir = Files.createTempDirectory("openremote-mtls-proxy-cert-test")
    def truststorePath = certDir.resolve("server_truststore.p12").toString()
    deviceCA.saveServerTruststore(truststorePath, KEYSTORE_PASSWORD)

    and: "the broker starts with no keystore of its own, so it serves the proxy certificate"
    def mtlsPort = findEphemeralPort()
    def config = defaultConfig()
    config.put(OR_MQTT_MTLS_DISABLED, "false")
    config.put(OR_MQTT_MTLS_SERVER_LISTEN_PORT, mtlsPort.toString())
    config.put(OR_MQTT_MTLS_TRUSTSTORE_PATH, truststorePath)
    config.put(OR_MQTT_MTLS_TRUSTSTORE_PASSWORD, KEYSTORE_PASSWORD)
    def container = startContainer(config, defaultServices())
    def mqttBrokerService = container.getService(MQTTBrokerService.class)

    expect: "the acceptor to have been added on the strength of the proxy certificate alone"
    mqttBrokerService.mtlsAcceptorEnabled

    when: "a device issued by the CA in the truststore connects"
    def deviceClient = new MQTT_IOClient(
            "device-ca-client", "localhost", mtlsPort, true, true, null, null, null,
            deviceCA.createClientKeyManagerFactory(deviceKeyPair, deviceCert, KEYSTORE_PASSWORD),
            proxyCA.createClientTrustManagerFactory())
    deviceClient.connect()

    then: "it is accepted"
    conditions.eventually {
      assert deviceClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    when: "a client issued by the same CA as the broker's own certificate connects"
    def proxyIssuedClient = new MQTT_IOClient(
            "proxy-ca-client", "localhost", mtlsPort, true, true, null, null, null,
            proxyCA.createClientKeyManagerFactory(proxyIssuedKeyPair, proxyIssuedCert, KEYSTORE_PASSWORD),
            proxyCA.createClientTrustManagerFactory())
    proxyIssuedClient.connect()

    then: "it is rejected, because the server's own issuer is not a client certificate issuer"
    def deadline = System.currentTimeMillis() + 3000
    while (System.currentTimeMillis() <deadline) {
      assert proxyIssuedClient.getConnectionStatus() != ConnectionStatus.CONNECTED
      Thread.sleep(100)
    }

    cleanup:
    deviceClient?.disconnect()
    proxyIssuedClient?.disconnect()
    certDir.toFile().deleteDir()
    proxyCertsDir?.parent?.toFile()?.deleteDir()
  }

  def "the acceptor is left out when there is no truststore to verify clients against"() {
    given: "a broker keystore, but no truststore"
    def deviceCA = new MTLSCertificateHelper()
    def (serverKeyPair, serverCert) = deviceCA.generateServerCertificate()
    def certDir = Files.createTempDirectory("openremote-mtls-no-truststore-test")
    def keystorePath = certDir.resolve("server_keystore.p12").toString()
    deviceCA.saveServerKeystore(keystorePath, KEYSTORE_PASSWORD, "server", serverKeyPair, serverCert)

    and: "the broker starts with mTLS enabled and the truststore pointing at nothing"
    def config = defaultConfig()
    config.put(OR_MQTT_MTLS_DISABLED, "false")
    config.put(OR_MQTT_MTLS_SERVER_LISTEN_PORT, findEphemeralPort().toString())
    config.put(OR_MQTT_MTLS_KEYSTORE_PATH, keystorePath)
    config.put(OR_MQTT_MTLS_KEYSTORE_PASSWORD, KEYSTORE_PASSWORD)
    config.put(OR_MQTT_MTLS_TRUSTSTORE_PATH, certDir.resolve("absent_truststore.p12").toString())
    config.put(OR_MQTT_MTLS_TRUSTSTORE_PASSWORD, KEYSTORE_PASSWORD)

    when: "the container starts"
    def container = startContainer(config, defaultServices())
    def mqttBrokerService = container.getService(MQTTBrokerService.class)

    then: "the broker runs, but without an acceptor that would accept any CA's certificates"
    mqttBrokerService.active
    !mqttBrokerService.mtlsAcceptorEnabled

    cleanup:
    certDir.toFile().deleteDir()
  }

  def "no acceptor is added on a deployment that has not been set up for mTLS"() {
    when: "the container starts with nothing but the defaults"
    def container = startContainer(defaultConfig(), defaultServices())
    def mqttBrokerService = container.getService(MQTTBrokerService.class)

    then: "mTLS is enabled but stays dormant for want of certificates"
    !mqttBrokerService.mtlsDisabled
    !mqttBrokerService.mtlsAcceptorEnabled

    and: "the plain MQTT acceptor is unaffected"
    mqttBrokerService.active
  }
}
