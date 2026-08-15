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

import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants
import org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextConfig
import org.openremote.manager.mqtt.OpenRemoteSSLContextFactory
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification

import javax.net.ssl.SSLException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Covers how {@link OpenRemoteSSLContextFactory} resolves key and trust material.
 *
 * Assertions have to distinguish between the sources, because "an SSLContext came back" is true of
 * any of them. The proxy path is identified by a side effect of taking it: reading the proxy
 * certificate records its modification time, which is the state {@code certificatesHaveChanged}
 * reads, so a factory that loaded from the proxy reports no pending change while one that fell
 * through to the keystore reports the certificate as still unseen.
 */
class OpenRemoteSSLContextFactoryTest extends Specification implements ManagerContainerTrait {

  static final String KEYSTORE_PASSWORD = "secret1"

  MTLSCertificateHelper certificateHelper
  Path certDir
  Path proxyCertsDir
  String keystorePath
  String truststorePath
  String combinedPem

  def setup() {
    def container = startContainer(defaultConfig(), defaultServices())
    certificateHelper = new MTLSCertificateHelper()
    certDir = Files.createTempDirectory("openremote-sslcontextfactory-test")

    truststorePath = certDir.resolve("truststore.p12").toString()
    certificateHelper.saveServerTruststore(truststorePath, KEYSTORE_PASSWORD)

    def (serverKeyPair, serverCert) = certificateHelper.generateServerCertificate()
    keystorePath = certDir.resolve("keystore.p12").toString()
    certificateHelper.saveServerKeystore(
            keystorePath, KEYSTORE_PASSWORD, "server", serverKeyPair, serverCert)
    combinedPem = certificateHelper.getCombinedPem(serverKeyPair, serverCert)

    proxyCertsDir = OpenRemoteSSLContextFactory.resolveProxyCertsDir(container)
  }

  def cleanup() {
    certDir?.toFile()?.deleteDir()
    // The proxy directory lives in the storage dir shared with every other spec, so it must not
    // be left behind for them to pick up
    proxyCertsDir?.parent?.toFile()?.deleteDir()
  }

  def "a context requiring client auth is refused when no truststore is configured"() {
    given: "a keystore but no truststore"
    def config = SSLContextConfig.builder()
            .keystorePath(keystorePath)
            .keystorePassword(KEYSTORE_PASSWORD)
            .build()
    def factory = new OpenRemoteSSLContextFactory()

    when: "an acceptor that requires client certificates asks for a context"
    factory.getSSLContext(config, needClientAuth())

    then: "it fails rather than falling back to the JDK's public CA bundle"
    def e = thrown(SSLException)
    e.message.contains("no truststore configured")
  }

  def "a context that does not require client auth still gets the JDK default trust"() {
    given: "a keystore but no truststore"
    def config = SSLContextConfig.builder()
            .keystorePath(keystorePath)
            .keystorePassword(KEYSTORE_PASSWORD)
            .build()
    def factory = new OpenRemoteSSLContextFactory()

    when: "a plain TLS acceptor asks for a context"
    def sslContext = factory.getSSLContext(config, [:])

    then: "one-way TLS is unaffected by the client-auth rule"
    sslContext != null
  }

  def "the configured keystore is used when the proxy has no certificate"() {
    given: "an empty proxy certificate directory"
    Files.createDirectories(proxyCertsDir)
    def factory = new OpenRemoteSSLContextFactory()

    when: "a context is requested"
    def sslContext = factory.getSSLContext(configWithTruststore(), needClientAuth())

    then: "it is built from the keystore"
    sslContext != null

    and: "no proxy certificate was tracked, because there was none to read"
    !factory.certificatesHaveChanged()
  }

  def "the proxy certificate takes precedence over the configured keystore"() {
    given: "a proxy certificate on disk"
    Files.createDirectories(proxyCertsDir)
    def proxyCert = proxyCertsDir.resolve("00-cert")
    proxyCert.text = combinedPem
    def factory = new OpenRemoteSSLContextFactory()

    when: "a context is requested"
    def sslContext = factory.getSSLContext(configWithTruststore(), needClientAuth())

    then: "it is built, and the proxy certificate is the source that was read"
    sslContext != null
    !factory.certificatesHaveChanged()

    when: "the proxy renews the certificate"
    Files.setLastModifiedTime(proxyCert,
            FileTime.fromMillis(Files.getLastModifiedTime(proxyCert).toMillis() + 10000))

    then: "the change is detected, so the acceptor can be reloaded"
    factory.certificatesHaveChanged()
  }

  def "the lowest numbered proxy certificate is the one served"() {
    given: "several numbered certificates, the lowest of which is the valid one"
    Files.createDirectories(proxyCertsDir)
    proxyCertsDir.resolve("00-primary").text = combinedPem
    proxyCertsDir.resolve("01-other").text = "not a PEM file"
    def factory = new OpenRemoteSSLContextFactory()

    when: "a context is requested"
    def sslContext = factory.getSSLContext(configWithTruststore(), needClientAuth())

    then: "the lowest numbered file was read"
    sslContext != null
    !factory.certificatesHaveChanged()
  }

  def "an unreadable proxy certificate falls back to the configured keystore"() {
    given: "a proxy certificate that cannot be parsed"
    Files.createDirectories(proxyCertsDir)
    proxyCertsDir.resolve("00-broken").text = "not a PEM file"
    def factory = new OpenRemoteSSLContextFactory()

    when: "a context is requested"
    def sslContext = factory.getSSLContext(configWithTruststore(), needClientAuth())

    then: "the keystore is used rather than failing the acceptor"
    sslContext != null

    and: "nothing was tracked, because the proxy certificate was never successfully read"
    factory.certificatesHaveChanged()
  }

  def "context creation fails when there is no key material at all"() {
    given: "a truststore, but neither a proxy certificate nor a keystore"
    def config = SSLContextConfig.builder()
            .truststorePath(truststorePath)
            .truststorePassword(KEYSTORE_PASSWORD)
            .build()
    def factory = new OpenRemoteSSLContextFactory()

    when: "a context is requested"
    factory.getSSLContext(config, needClientAuth())

    then: "it fails instead of serving a certificate nobody configured"
    def e = thrown(SSLException)
    e.message.contains("no server certificate found")
  }

  def "the context is cached until it is explicitly cleared"() {
    given: "a proxy certificate on disk"
    Files.createDirectories(proxyCertsDir)
    proxyCertsDir.resolve("00-cert").text = combinedPem
    def factory = new OpenRemoteSSLContextFactory()

    when: "a context is requested twice"
    def first = factory.getSSLContext(configWithTruststore(), needClientAuth())
    def second = factory.getSSLContext(configWithTruststore(), needClientAuth())

    then: "the same instance comes back"
    first.is(second)

    when: "the cache is cleared and a context is requested again"
    factory.clearSSLContexts()
    def third = factory.getSSLContext(configWithTruststore(), needClientAuth())

    then: "a fresh instance is built"
    !first.is(third)
  }

  private SSLContextConfig configWithTruststore() {
    return SSLContextConfig.builder()
            .keystorePath(keystorePath)
            .keystorePassword(KEYSTORE_PASSWORD)
            .truststorePath(truststorePath)
            .truststorePassword(KEYSTORE_PASSWORD)
            .build()
  }

  private static Map<String, Object> needClientAuth() {
    return [(TransportConstants.NEED_CLIENT_AUTH_PROP_NAME): "true"] as Map<String, Object>
  }
}
