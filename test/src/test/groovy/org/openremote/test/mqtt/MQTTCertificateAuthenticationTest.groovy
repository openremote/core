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

import org.apache.activemq.artemis.spi.core.security.jaas.RolePrincipal
import org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal
import org.openremote.agent.protocol.mqtt.MQTT_IOClient
import org.openremote.container.persistence.PersistenceService
import org.openremote.container.security.TokenPrincipal
import org.openremote.manager.mqtt.MQTTBrokerService
import org.openremote.manager.security.KeyStoreServiceImpl
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.security.ManagerKeycloakIdentityProvider
import org.openremote.manager.security.RemotingConnectionPrincipal
import org.openremote.model.Constants
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.security.ClientRole
import org.openremote.model.security.User
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.security.auth.Subject
import java.nio.file.Paths

/**
 * Covers mapping a client certificate accepted by the mTLS acceptor onto a service user. Kept apart
 * from MqttBrokerTest because it needs a container started with mTLS configuration, while that
 * spec shares one default container across all of its features.
 */
class MQTTCertificateAuthenticationTest extends Specification implements ManagerContainerTrait {

  def "a client certificate authenticates as its service user, and an unknown one stays anonymous"() {
    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.1)
    MQTT_IOClient device1Client

    and: "temporary directories are created"
    def tempDir = new File(System.getProperty("java.io.tmpdir"), "openremote-mtls-test-keystores-" + System.currentTimeMillis())
    def tempManagerDir = new File(System.getProperty("java.io.tmpdir"), "openremote-tmp-" + System.currentTimeMillis())
    tempDir.mkdirs()
    tempManagerDir.mkdirs()
    LOG.info("KeyStore TempDir: $tempDir.absoluteFile")
    LOG.info("Manager TempDir: $tempManagerDir.absoluteFile")

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
    config.put(Constants.OR_MQTT_MTLS_KEYSTORE_PATH, serverKeystorePath)
    config.put(Constants.OR_MQTT_MTLS_KEYSTORE_PASSWORD, keystorePassword)
    config.put(Constants.OR_MQTT_MTLS_TRUSTSTORE_PATH, serverTruststorePath)
    config.put(Constants.OR_MQTT_MTLS_TRUSTSTORE_PASSWORD, keystorePassword)
    config.put(Constants.OR_MQTT_MTLS_DISABLED, "false")
    config.put(KeyStoreServiceImpl.OR_KEYSTORE_PASSWORD, keystorePassword)
    // Keep the grant file at the shared location: a throwaway storage dir makes the identity
    // provider treat this as a first install and rotate the manager-keycloak password, which
    // invalidates the grant the rest of the suite relies on
    config.put(ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE, Paths.get(
                    config.getOrDefault(PersistenceService.OR_STORAGE_DIR, PersistenceService.OR_STORAGE_DIR_DEFAULT))
            .resolve(config.getOrDefault(
                    ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE,
                    ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE_DEFAULT))
            .toAbsolutePath()
            .toString())
    config.put(PersistenceService.OR_STORAGE_DIR, tempManagerDir.getAbsolutePath())


    and: "the container starts"
    def container = startContainer(config, defaultServices())
    def keystoreService = container.getService(KeyStoreServiceImpl.class)
    def mqttBrokerService = container.getService(MQTTBrokerService.class)
    def identityService = container.getService(ManagerIdentityService.class)
    def mqttHost = "localhost"
    def mqttPort = mtlsPort

    and: "the client certificate is added to the KeyStoreService"
    mtlsHelper.addClientCertificateToKeyStoreService(
            keystoreService,
            provisionedAccountKeyAlias,
            keystorePassword,
            clientKeyPair,
            clientCert
            )

    expect: "the keystores should contain the certificates"
    keystoreService.getKeyStore().containsAlias(provisionedAccountKeyAlias)
    keystoreService.getTrustStore().containsAlias(provisionedAccountKeyAlias)

    when: "A new service user with the corresponding certificate's fields is created"
    User serviceUser = new User()
            .setServiceAccount(true)
            .setEnabled(true)
            .setRealm("master")
            .setUsername(ProvisionedAccountUserName)
    serviceUser = identityService.getIdentityProvider().createUpdateUser("master", serviceUser, null, true)

    identityService.getIdentityProvider().updateUserClientRoles(
            Constants.MASTER_REALM,
            serviceUser.getId(),
            Constants.KEYCLOAK_CLIENT_ID,
            [ClientRole.READ_ASSETS.value, ClientRole.WRITE_ASSETS.value, ClientRole.WRITE_ATTRIBUTES.value] as String[]
            )

    then: "the service user should exist"
    def fetchedUser = identityService.getIdentityProvider().getUserByUsername("master", "$User.SERVICE_ACCOUNT_PREFIX$ProvisionedAccountUserName")
    assert fetchedUser != null

    when: "an MQTT client is created with mTLS configuration"
    def device1UniqueId = "device1"
    def mqttDevice1ClientId = device1UniqueId
    device1Client = new MQTT_IOClient(
            mqttDevice1ClientId,
            mqttHost,
            mqttPort,
            true,
            false,
            null,
            null,
            null,
            keystoreService.getKeyManagerFactory(provisionedAccountKeyAlias),
            keystoreService.getTrustManagerFactory()
            )

    device1Client.connect()

    then: "the client should connect successfully using mTLS"
    conditions.eventually {
      assert device1Client.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    and: "the authenticated client should have the correct subject"
    conditions.eventually {
      Subject sub = mqttBrokerService.getConnectionFromClientID(mqttDevice1ClientId).getSubject()
      // New architecture creates a RemotingConnectionPrincipal and a TokenPrincipal/UserPrincipal for the service user
      assert sub.getPrincipals().size() == 3
      assert sub.getPrincipals(UserPrincipal).size() == 1
      // ActiveMQORSecurityManager names the UserPrincipal with the keycloak subject id, so the
      // username is asserted on the TokenPrincipal, which exposes the preferred_username claim
      assert sub.getPrincipals(TokenPrincipal.class)[0].getUsername() == "$User.SERVICE_ACCOUNT_PREFIX$ProvisionedAccountUserName"
      assert sub.getPrincipals(RemotingConnectionPrincipal.class).size() == 1
      assert sub.getPrincipals(RemotingConnectionPrincipal.class)[0].getRemotingConnection().getClientID() == mqttDevice1ClientId
      assert sub.getPrincipals(TokenPrincipal.class).size() == 1
      assert sub.getPublicCredentials().size() == 0
    }

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

    and: "A new MQTT client connects, using those correct yet unprovisioned certificates"
    def unprovisionedClientId = unprovisionedUsername
    MQTT_IOClient unprovisionedClient = new MQTT_IOClient(
            unprovisionedClientId,
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

    unprovisionedClient.connect()

    then: "the connection is accepted"
    conditions.eventually {
      assert unprovisionedClient.getConnectionStatus() == ConnectionStatus.CONNECTED
    }

    and: "the authenticated client should be anonymous"
    conditions.eventually {
      Subject unprovisionedJaasSubject = mqttBrokerService.getConnectionFromClientID(unprovisionedClientId).getSubject()
      assert unprovisionedJaasSubject != null
      // Anonymous: RemotingConnectionPrincipal + anonymous UserPrincipal + anonymous RolePrincipal
      assert unprovisionedJaasSubject.getPrincipals().size() == 3
      assert unprovisionedJaasSubject.getPrincipals(RolePrincipal.class)[0].getName() == "anonymous"
      assert unprovisionedJaasSubject.getPrincipals(UserPrincipal)[0].getName() == "anonymous"
    }

    cleanup: "disconnect the clients, remove the service user and the temporary files"
    device1Client?.disconnect()
    unprovisionedClient?.disconnect()
    if (serviceUser?.id != null) {
      identityService.getIdentityProvider().deleteUser(Constants.MASTER_REALM, serviceUser.id)
    }

    and: "delete the created temporary directories"
    tempDir.deleteDir()
    tempManagerDir.deleteDir()
  }
}
