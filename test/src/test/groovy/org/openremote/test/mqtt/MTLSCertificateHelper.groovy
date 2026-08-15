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

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.openremote.manager.security.KeyStoreServiceImpl

import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * Generates the certificates the mTLS tests need. Each instance owns a fresh root CA, so two
 * instances give you two mutually untrusted PKIs - which is what a "signed by the wrong CA"
 * test needs.
 *
 * Certificates are generated rather than checked in on purpose: a private key in the repository is
 * a private key in every deployment that ships the jar, and generating also keeps the tests honest
 * about what a working setup actually looks like.
 */
class MTLSCertificateHelper {

  private static final long DAY_MILLIS = 86400000L

  KeyPair rootCAKeyPair
  X509Certificate rootCACert
  X500Name rootIssuer
  long timestamp

  MTLSCertificateHelper() {
    this.timestamp = System.currentTimeMillis()
    generateRootCA()
  }

  /**
   * Generate Root CA key pair and certificate (self-signed)
   */
  private void generateRootCA() {
    KeyPairGenerator rootKeyGen = KeyPairGenerator.getInstance("RSA")
    rootKeyGen.initialize(2048)
    rootCAKeyPair = rootKeyGen.generateKeyPair()

    X500Name rootCASubject = new X500Name("CN=OpenRemote Root CA $timestamp".toString())
    Date rootStartDate = new Date(timestamp)
    Date rootEndDate = new Date(timestamp + 3650L * DAY_MILLIS) // ~10 years
    BigInteger rootSerialNumber = BigInteger.valueOf(timestamp)

    X509v3CertificateBuilder rootCertBuilder = new JcaX509v3CertificateBuilder(
            rootCASubject,
            rootSerialNumber,
            rootStartDate,
            rootEndDate,
            rootCASubject,
            rootCAKeyPair.getPublic()
            )

    rootCertBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
    rootCertBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))

    ContentSigner rootSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(rootCAKeyPair.getPrivate())
    rootCACert = new JcaX509CertificateConverter().getCertificate(rootCertBuilder.build(rootSigner))
    rootIssuer = new X500Name(rootCACert.getSubjectX500Principal().getName())
  }

  /**
   * Generate a server certificate signed by the root CA with SANs for localhost and auth.local
   */
  Tuple2<KeyPair, X509Certificate> generateServerCertificate() {
    KeyPairGenerator serverKeyGen = KeyPairGenerator.getInstance("RSA")
    serverKeyGen.initialize(2048)
    KeyPair serverKeyPair = serverKeyGen.generateKeyPair()

    X500Name serverSubject = new X500Name("CN=auth.local")
    Date serverStartDate = new Date(timestamp)
    Date serverEndDate = new Date(timestamp + 825L * DAY_MILLIS) // ~27 months
    BigInteger serverSerialNumber = BigInteger.valueOf(timestamp + 1)

    X509v3CertificateBuilder serverCertBuilder = new JcaX509v3CertificateBuilder(
            rootIssuer,
            serverSerialNumber,
            serverStartDate,
            serverEndDate,
            serverSubject,
            serverKeyPair.getPublic()
            )

    // The IP is needed as well as the names: tests connect to the loopback address directly,
    // and hostname verification will not match a DNS SAN against it
    GeneralName[] sans = [
      new GeneralName(GeneralName.dNSName, "localhost"),
      new GeneralName(GeneralName.dNSName, "auth.local"),
      new GeneralName(GeneralName.iPAddress, "127.0.0.1")
    ]
    serverCertBuilder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(sans))
    serverCertBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            )
    serverCertBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))

    ContentSigner serverSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(rootCAKeyPair.getPrivate())
    X509Certificate serverCert = new JcaX509CertificateConverter().getCertificate(serverCertBuilder.build(serverSigner))

    return new Tuple2<>(serverKeyPair, serverCert)
  }

  /**
   * Generate a client certificate signed by the root CA with specified CN and OU
   */
  Tuple2<KeyPair, X509Certificate> generateClientCertificate(String commonName, String organizationalUnit, long serialOffset = 2) {
    KeyPairGenerator clientKeyGen = KeyPairGenerator.getInstance("RSA")
    clientKeyGen.initialize(2048)
    KeyPair clientKeyPair = clientKeyGen.generateKeyPair()

    X500Name clientSubject = new X500Name("CN=$commonName,OU=$organizationalUnit".toString())
    Date clientStartDate = new Date(timestamp)
    Date clientEndDate = new Date(timestamp + 365L * DAY_MILLIS) // 1 year
    BigInteger clientSerialNumber = BigInteger.valueOf(timestamp + serialOffset)

    X509v3CertificateBuilder clientCertBuilder = new JcaX509v3CertificateBuilder(
            rootIssuer,
            clientSerialNumber,
            clientStartDate,
            clientEndDate,
            clientSubject,
            clientKeyPair.getPublic()
            )

    clientCertBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            )
    clientCertBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))

    ContentSigner clientSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(rootCAKeyPair.getPrivate())
    X509Certificate clientCert = new JcaX509CertificateConverter().getCertificate(clientCertBuilder.build(clientSigner))

    return new Tuple2<>(clientKeyPair, clientCert)
  }

  /**
   * Generate a self-signed (invalid) client certificate NOT signed by the root CA
   */
  Tuple2<KeyPair, X509Certificate> generateSelfSignedCertificate(String commonName, String organizationalUnit, long serialOffset = 3) {
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA")
    keyGen.initialize(2048)
    KeyPair keyPair = keyGen.generateKeyPair()

    X500Name subject = new X500Name("CN=$commonName,OU=$organizationalUnit".toString())
    Date startDate = new Date(timestamp)
    Date endDate = new Date(timestamp + 365L * DAY_MILLIS)
    BigInteger serialNumber = BigInteger.valueOf(timestamp + serialOffset)

    X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject, // self-signed, same subject and issuer
            serialNumber,
            startDate,
            endDate,
            subject,
            keyPair.getPublic()
            )

    certBuilder.addExtension(Extension.keyUsage, true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
            )
    certBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))

    ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate())
    X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer))

    return new Tuple2<>(keyPair, cert)
  }

  /**
   * Create and save server keystores (keystore and truststore) to disk
   */
  void createAndSaveServerKeystores(
          String keystorePath,
          String truststorePath,
          String password,
          String keyAlias,
          KeyPair serverKeyPair,
          X509Certificate serverCert
  ) {
    saveServerKeystore(keystorePath, password, keyAlias, serverKeyPair, serverCert)
    saveServerTruststore(truststorePath, password)
  }

  /**
   * Save just the server keystore, for tests that deliberately leave the truststore out
   */
  void saveServerKeystore(
          String keystorePath,
          String password,
          String keyAlias,
          KeyPair serverKeyPair,
          X509Certificate serverCert
  ) {
    KeyStore serverKeystore = KeyStore.getInstance("PKCS12")
    serverKeystore.load(null, null)
    Certificate[] serverCertChain = [serverCert, rootCACert] as Certificate[]
    serverKeystore.setKeyEntry(keyAlias, serverKeyPair.getPrivate(), password.toCharArray(), serverCertChain)

    new FileOutputStream(keystorePath).withCloseable { fos ->
      serverKeystore.store(fos, password.toCharArray())
    }
  }

  /**
   * Save a truststore containing this helper's root CA, i.e. the issuer whose client
   * certificates the broker should accept
   */
  void saveServerTruststore(String truststorePath, String password) {
    KeyStore serverTruststore = KeyStore.getInstance("PKCS12")
    serverTruststore.load(null, null)
    serverTruststore.setCertificateEntry("client-ca", rootCACert)

    new FileOutputStream(truststorePath).withCloseable { fos ->
      serverTruststore.store(fos, password.toCharArray())
    }
  }

  /**
   * Add a client certificate to the KeyStoreService's keystore and truststore
   */
  void addClientCertificateToKeyStoreService(
          KeyStoreServiceImpl keystoreService,
          String keyAlias,
          String password,
          KeyPair clientKeyPair,
          X509Certificate clientCert
  ) {
    addCertificateToKeyStoreService(keystoreService, keyAlias, password, clientKeyPair, clientCert, true)
  }

  /**
   * Add a certificate (with optional chain) to the KeyStoreService's keystore and truststore
   * This is useful for adding invalid or self-signed certificates for testing
   */
  void addCertificateToKeyStoreService(
          KeyStoreServiceImpl keystoreService,
          String keyAlias,
          String password,
          KeyPair keyPair,
          X509Certificate cert,
          boolean includeRootCA = true
  ) {
    KeyStore clientKeystore = keystoreService.getKeyStore()
    KeyStore clientTruststore = keystoreService.getTrustStore()

    clientTruststore.setCertificateEntry(keyAlias, rootCACert)

    Certificate[] certChain = includeRootCA ? [cert, rootCACert] as Certificate[] : [cert] as Certificate[]
    clientKeystore.setKeyEntry(keyAlias, keyPair.getPrivate(), password.toCharArray(), certChain)

    keystoreService.storeKeyStore(clientKeystore)
    keystoreService.storeTrustStore(clientTruststore)
  }

  /**
   * A KeyManagerFactory presenting the given client certificate, for tests that drive a client
   * directly rather than through the KeyStoreService
   */
  KeyManagerFactory createClientKeyManagerFactory(KeyPair keyPair, X509Certificate cert, String password) {
    KeyStore keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null, null)
    keyStore.setKeyEntry("client", keyPair.getPrivate(), password.toCharArray(), [cert, rootCACert] as Certificate[])

    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    keyManagerFactory.init(keyStore, password.toCharArray())
    return keyManagerFactory
  }

  /**
   * A TrustManagerFactory trusting this helper's root CA, i.e. what a client needs to accept the
   * server certificate this helper issued
   */
  TrustManagerFactory createClientTrustManagerFactory() {
    KeyStore trustStore = KeyStore.getInstance("PKCS12")
    trustStore.load(null, null)
    trustStore.setCertificateEntry("server-ca", rootCACert)

    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(trustStore)
    return trustManagerFactory
  }

  /**
   * The private key and certificate chain in a single PEM, the shape the proxy writes its
   * certificates in
   */
  String getCombinedPem(KeyPair keyPair, X509Certificate certificate) {
    return getPemBlock("PRIVATE KEY", keyPair.getPrivate().getEncoded()) +
            getPemString(certificate) +
            getPemString(rootCACert)
  }

  X509Certificate getRootCACertificate() {
    return rootCACert
  }

  X500Name getRootIssuer() {
    return rootIssuer
  }

  static String getPemString(X509Certificate certificate) throws Exception {
    return getPemBlock("CERTIFICATE", certificate.getEncoded())
  }

  private static String getPemBlock(String type, byte[] der) {
    String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
    return "-----BEGIN $type-----\n$base64\n-----END $type-----\n"
  }
}
