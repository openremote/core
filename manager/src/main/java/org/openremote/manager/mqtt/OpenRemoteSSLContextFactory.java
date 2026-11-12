/*
 * Copyright 2024, OpenRemote Inc.
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
package org.openremote.manager.mqtt;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextConfig;
import org.apache.activemq.artemis.spi.core.remoting.ssl.SSLContextFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.model.Container;
import org.openremote.model.util.TextUtil;

/**
 * {@link SSLContextFactory} for the MQTT broker, registered through the Artemis SPI.
 *
 * <p>The server's own key material is resolved from, in order:
 *
 * <ol>
 *   <li>the proxy certificate directory under the storage dir, so a deployment reuses the
 *       haproxy-managed (letsencrypt) certificate it already has;
 *   <li>the keystore configured on the acceptor.
 * </ol>
 *
 * <p>Trust material - the CAs whose client certificates are accepted - always comes from the
 * configured truststore, whichever source the key material came from. Verifying client certificates
 * is the entire point of an acceptor with {@code needClientAuth}, so trust anchors are never
 * derived from the server's own certificate chain and never fall back to the JDK's public CA
 * bundle: either the configured truststore is usable or context creation fails. Acceptors that do
 * not require client authentication are left with the JDK default, which is the normal behaviour
 * for one-way TLS.
 *
 * <p>Artemis holds on to the {@link SSLContext} an acceptor was built with, so a renewed
 * certificate only takes effect once {@link #clearSSLContexts()} is followed by a reload of the
 * acceptor itself; {@link MQTTBrokerService} polls {@link #certificatesHaveChanged()} and does
 * both.
 *
 * <p>Note: this factory is instantiated by Artemis via ServiceLoader, so the {@link Container} is
 * handed over through a static registry set by {@link MQTTBrokerService}.
 */
public class OpenRemoteSSLContextFactory implements SSLContextFactory {

  private static final Logger LOG = Logger.getLogger(OpenRemoteSSLContextFactory.class.getName());
  private static final int HIGH_PRIORITY = 100;
  private static final Pattern NUMBERED_CERT_PATTERN = Pattern.compile("^\\d+-.*");
  private static final String PROXY_CERTS_DIR = "proxy";
  private static final String PROXY_CERTS_SUBDIR = "certs";
  private static final String KEY_ENTRY_ALIAS = "mqtt-server";

  // Static registry for the Container instance (set by MQTTBrokerService)
  private static volatile Container container;

  private volatile SSLContext cachedSSLContext;
  private final Map<String, Long> fileModificationTimes = new ConcurrentHashMap<>();

  /** Register the Container instance; called by MQTTBrokerService before the broker starts. */
  public static void setContainer(Container container) {
    OpenRemoteSSLContextFactory.container = container;
  }

  /** Clears the Container reference; called when the MQTT broker is stopping. */
  public static void clearContainer() {
    container = null;
  }

  /**
   * The directory the proxy writes its certificates into, as seen from the manager. Exposed so
   * callers agree with this factory on where certificates live instead of assuming a storage dir.
   *
   * @return the directory, or {@code null} when there is no container/storage dir to resolve
   *     against
   */
  public static Path resolveProxyCertsDir(Container container) {
    if (container == null) {
      return null;
    }
    PersistenceService persistenceService = container.getService(PersistenceService.class);
    if (persistenceService == null || persistenceService.getStorageDir() == null) {
      return null;
    }
    return persistenceService.getStorageDir().resolve(PROXY_CERTS_DIR).resolve(PROXY_CERTS_SUBDIR);
  }

  @Override
  public SSLContext getSSLContext(SSLContextConfig config, Map<String, Object> additionalOpts)
      throws Exception {
    if (cachedSSLContext != null && !certificatesHaveChanged()) {
      return cachedSSLContext;
    }

    KeyManager[] keyManagers = loadKeyManagers(config);

    if (keyManagers == null) {
      throw new SSLException(
          "Failed to create SSLContext: no server certificate found in the proxy certificate"
              + " directory or the configured keystore");
    }

    TrustManager[] trustManagers = loadTrustManagers(config, needsClientAuth(additionalOpts));

    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(keyManagers, trustManagers, new SecureRandom());
    cachedSSLContext = sslContext;
    return sslContext;
  }

  /**
   * Resolves the server's key material, preferring the proxy certificate so that a deployment
   * presents the same certificate on MQTT as it does over HTTPS.
   */
  private KeyManager[] loadKeyManagers(SSLContextConfig config) throws Exception {
    Path proxyCertificate = findProxyCertificate();

    if (proxyCertificate != null) {
      try (BufferedReader reader = Files.newBufferedReader(proxyCertificate)) {
        KeyManager[] keyManagers =
            createKeyManagersFromPEM(reader, proxyCertificate.toString(), config);
        updateFileModificationTimes(proxyCertificate);
        LOG.log(Level.INFO, "Loaded server certificate from proxy: {0}", proxyCertificate);
        return keyManagers;
      } catch (Exception e) {
        LOG.log(
            Level.WARNING,
            "Failed to load the proxy certificate, falling back to the configured keystore: "
                + proxyCertificate,
            e);
      }
    }

    if (TextUtil.isNullOrEmpty(config.getKeystorePath())) {
      return null;
    }

    LOG.log(Level.INFO, "Loading server certificate from keystore: {0}", config.getKeystorePath());
    KeyStore keyStore =
        loadKeyStore(
            config.getKeystorePath(),
            config.getKeystorePassword(),
            config.getKeystoreType() != null ? config.getKeystoreType() : "PKCS12");
    return createKeyManagers(keyStore, config.getKeystorePassword());
  }

  /**
   * Resolves the CAs whose client certificates are accepted. Only the configured truststore is
   * consulted; when an acceptor requires client authentication and no usable truststore is
   * configured this fails rather than silently trusting the JDK's public CA bundle.
   */
  private TrustManager[] loadTrustManagers(SSLContextConfig config, boolean needClientAuth)
      throws Exception {
    if (TextUtil.isNullOrEmpty(config.getTruststorePath())) {
      if (needClientAuth) {
        throw new SSLException(
            "Refusing to create an SSLContext requiring client authentication with no truststore"
                + " configured: client certificates would be verified against the JDK's public CA"
                + " bundle rather than the intended issuer");
      }
      TrustManagerFactory trustManagerFactory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);
      return trustManagerFactory.getTrustManagers();
    }

    KeyStore trustStore =
        loadKeyStore(
            config.getTruststorePath(),
            config.getTruststorePassword(),
            config.getTruststoreType() != null ? config.getTruststoreType() : "PKCS12");
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(trustStore);
    LOG.log(Level.INFO, "Loaded client certificate issuers from: {0}", config.getTruststorePath());
    return trustManagerFactory.getTrustManagers();
  }

  /**
   * Artemis passes the acceptor's raw configuration as the additional options, which is the only
   * place {@code needClientAuth} is visible to a context factory.
   */
  private boolean needsClientAuth(Map<String, Object> additionalOpts) {
    if (additionalOpts == null) {
      return false;
    }
    Object value = additionalOpts.get(TransportConstants.NEED_CLIENT_AUTH_PROP_NAME);
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    return value != null && Boolean.parseBoolean(value.toString());
  }

  private Path findProxyCertificate() {
    Path certsDir = resolveProxyCertsDir(container);

    if (certsDir == null || !Files.isDirectory(certsDir)) {
      return null;
    }

    try {
      return findFirstNumberedCertFile(certsDir);
    } catch (IOException e) {
      LOG.log(Level.WARNING, "Failed to list the proxy certificate directory: " + certsDir, e);
      return null;
    }
  }

  /**
   * The proxy stores certificates as numbered files ("00-cert", "01-selfsigned"); the lowest number
   * is the one it serves.
   */
  private Path findFirstNumberedCertFile(Path directory) throws IOException {
    try (Stream<Path> stream = Files.list(directory)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> NUMBERED_CERT_PATTERN.matcher(path.getFileName().toString()).matches())
          .min(Comparator.comparing(path -> path.getFileName().toString()))
          .orElse(null);
    }
  }

  private KeyManager[] createKeyManagersFromPEM(
      Reader reader, String source, SSLContextConfig config) throws Exception {
    ParsedPem parsed = parsePemContent(reader);

    if (parsed.privateKey == null) {
      throw new SSLException("No private key found in " + source);
    }
    if (parsed.certificateChain.isEmpty()) {
      throw new SSLException("No certificate found in " + source);
    }

    // The PEM is turned into an in-memory keystore purely to hand it to a KeyManagerFactory; the
    // password never leaves this method
    char[] password =
        config.getKeystorePassword() != null
            ? config.getKeystorePassword().toCharArray()
            : new char[0];

    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    keyStore.setKeyEntry(
        KEY_ENTRY_ALIAS,
        parsed.privateKey,
        password,
        parsed.certificateChain.toArray(new Certificate[0]));

    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, password);
    return keyManagerFactory.getKeyManagers();
  }

  private record ParsedPem(PrivateKey privateKey, List<X509Certificate> certificateChain) {}

  private ParsedPem parsePemContent(Reader reader) throws Exception {
    ensureBouncyCastleProvider();

    PrivateKey privateKey = null;
    List<X509Certificate> certs = new ArrayList<>();

    JcaPEMKeyConverter keyConverter =
        new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);
    JcaX509CertificateConverter certConverter =
        new JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME);

    try (PEMParser parser = new PEMParser(reader)) {
      Object obj;
      while ((obj = parser.readObject()) != null) {
        switch (obj) {
          case PrivateKeyInfo pki -> privateKey = keyConverter.getPrivateKey(pki);
          case PEMKeyPair kp -> privateKey = keyConverter.getPrivateKey(kp.getPrivateKeyInfo());
          case X509CertificateHolder holder -> certs.add(certConverter.getCertificate(holder));
          default -> {}
        }
      }
    }

    return new ParsedPem(privateKey, certs);
  }

  /** BouncyCastle is only needed for PEM parsing, so it is registered where it is used. */
  private static synchronized void ensureBouncyCastleProvider() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
      LOG.log(Level.INFO, "Registered BouncyCastle security provider");
    }
  }

  private KeyManager[] createKeyManagers(KeyStore keyStore, String password) throws Exception {
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, password != null ? password.toCharArray() : null);
    return keyManagerFactory.getKeyManagers();
  }

  private KeyStore loadKeyStore(String path, String password, String type) throws Exception {
    KeyStore keyStore = KeyStore.getInstance(type);
    try (InputStream is = new FileInputStream(path)) {
      keyStore.load(is, password != null ? password.toCharArray() : null);
    }
    return keyStore;
  }

  /**
   * Whether the proxy certificate has changed since it was loaded. Only the proxy directory is
   * watched; a configured keystore is a deliberate act of deployment, not something that rotates
   * underneath a running broker.
   */
  public boolean certificatesHaveChanged() {
    Path proxyCertificate = findProxyCertificate();

    if (proxyCertificate == null) {
      return false;
    }

    try {
      Long previousModTime = fileModificationTimes.get(proxyCertificate.toString());
      long currentModTime = Files.getLastModifiedTime(proxyCertificate).toMillis();
      return previousModTime == null || currentModTime != previousModTime;
    } catch (IOException e) {
      LOG.log(Level.FINE, "Failed to check the proxy certificate for changes", e);
      return false;
    }
  }

  private void updateFileModificationTimes(Path file) throws IOException {
    fileModificationTimes.put(file.toString(), Files.getLastModifiedTime(file).toMillis());
  }

  @Override
  public void clearSSLContexts() {
    LOG.log(Level.INFO, "Clearing SSL context cache");
    cachedSSLContext = null;
    fileModificationTimes.clear();
  }

  @Override
  public int getPriority() {
    return HIGH_PRIORITY;
  }
}
