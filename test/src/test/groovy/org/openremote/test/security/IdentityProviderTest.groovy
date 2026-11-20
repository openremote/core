/*
 * Copyright 2025, OpenRemote Inc.
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
package org.openremote.test.security

import groovy.json.JsonSlurper
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.IdentityProviderResource
import org.keycloak.admin.client.resource.IdentityProvidersResource
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation
import org.openremote.manager.security.ManagerIdentityService
import org.openremote.manager.security.ManagerKeycloakIdentityProvider
import org.openremote.manager.setup.database.V20261120_1540__RestrictMasterClientTokenClaims
import org.openremote.model.Constants
import org.openremote.model.security.ClientRole
import org.openremote.model.security.Realm
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification

import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.container.security.keycloak.KeycloakIdentityProvider.*

class IdentityProviderTest extends Specification implements ManagerContainerTrait {

  @Shared
  static ManagerKeycloakIdentityProvider identityProvider

  def setupSpec() {
    def container = startContainer(defaultConfig(), defaultServices())
    identityProvider = container.getService(ManagerIdentityService.class).identityProvider as ManagerKeycloakIdentityProvider
  }

  def "Realm operations"() {
    def realmName = "testrealm"
    def realmDisplayName = "Test Realm"
    def realmEnabled = true

    when: "a realm does not yet exist in the identity provider"
    !identityProvider.realmExists(realmName)

    and: "the realm is created"
    def realm = new Realm(null, realmName, realmDisplayName, realmEnabled)
    identityProvider.createRealm(realm)
    realm = identityProvider.getRealm(realmName)

    then: "the realm exists in the identity provider"
    identityProvider.realmExists(realmName)

    and: "the realm has the correct properties"
    realm.name == realmName
    realm.displayName == realmDisplayName
    realm.enabled == realmEnabled

    when: "the realm is updated"
    realmDisplayName = "Updated Test Realm"
    realm.displayName = realmDisplayName
    identityProvider.updateRealm(realm)

    then: "the realm still exists in the identity provider"
    identityProvider.realmExists(realmName)

    when: "the realm is retrieved"
    realm = identityProvider.getRealm(realmName)

    then: "the realm has the correct properties"
    realm.name == realmName
    realm.displayName == realmDisplayName
    realm.enabled == realmEnabled

    when: "the realm is deleted"
    identityProvider.deleteRealm(realmName)

    then: "the realm no longer exists in the identity provider"
    !identityProvider.realmExists(realmName)

    and: "the realm cannot be retrieved"
    identityProvider.getRealm(realmName) == null
  }

  def "Master OpenRemote access tokens stay compact when realms are added"() {
    def realmNames = (1..3).collect { "token-growth-test-$it" }
    def master = identityProvider.getRealms { it.realm(Constants.MASTER_REALM) }
    def client = master.clients().get(master.clients().findByClientId(Constants.KEYCLOAK_CLIENT_ID).first().id)
    def stockScope = master.clientScopes().findAll().find { it.name == "roles" }

    when: "the manager has provisioned its master client"
    def before = masterAccessToken()
    def beforeClaims = jwtClaims(before)

    then:
    !client.defaultClientScopes.any { it.id == stockScope.id }
    beforeClaims.realm_access.roles.contains(Constants.SUPER_USER_REALM_ROLE)
    beforeClaims.resource_access.openremote.roles.contains(ClientRole.READ_ASSETS.value)
    beforeClaims.resource_access.keySet() == ["openremote", "account"] as Set
    beforeClaims.aud == "account"

    when: "realms are added and a staged upgrade still has the old scope attached"
    realmNames.each { identityProvider.createRealm(new Realm(null, it, it, true)) }
    client.addDefaultClientScope(stockScope.id)
    def expandedClaims = jwtClaims(masterAccessToken())

    then:
    realmNames.every { expandedClaims.resource_access.containsKey("${it}-realm".toString()) }
    realmNames.every { expandedClaims.aud.contains("${it}-realm".toString()) }

    when: "the same configuration routine is rerun"
    def migration = new V20261120_1540__RestrictMasterClientTokenClaims()
    migration.migrate(null)
    migration.migrate(null)
    def after = masterAccessToken()
    def afterClaims = jwtClaims(after)

    then:
    (afterClaims.realm_access.roles as Set) == (beforeClaims.realm_access.roles as Set)
    afterClaims.aud == beforeClaims.aud
    afterClaims.resource_access.keySet() == beforeClaims.resource_access.keySet()
    afterClaims.resource_access.every { clientId, access ->
      (access.roles as Set) == (beforeClaims.resource_access[clientId].roles as Set)
    }
    after.length() == before.length()

    cleanup:
    if (client?.defaultClientScopes?.any { it.id == stockScope.id }) {
      client.removeDefaultClientScope(stockScope.id)
    }
    realmNames.findAll {
      identityProvider.realmExists(it)
    }.each {
      identityProvider.deleteRealm(it)
    }
  }

  private String masterAccessToken() {
    def config = defaultConfig()
    def host = config.getOrDefault(OR_KEYCLOAK_HOST, OR_KEYCLOAK_HOST_DEFAULT)
    def port = config.getOrDefault(OR_KEYCLOAK_PORT, OR_KEYCLOAK_PORT_DEFAULT.toString())
    def path = config.getOrDefault(OR_KEYCLOAK_PATH, OR_KEYCLOAK_PATH_DEFAULT)
    def serverUrl = "http://$host:$port/${path.replaceFirst('^/+', '')}"
    Keycloak.getInstance(serverUrl, Constants.MASTER_REALM, Constants.MASTER_REALM_ADMIN_USER,
            config.getOrDefault(OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT), Constants.KEYCLOAK_CLIENT_ID).withCloseable {
              it.tokenManager().grantToken().token
            }
  }

  private static Map jwtClaims(String token) {
    new JsonSlurper().parse(Base64.urlDecoder.decode(token.split("\\.")[1])) as Map
  }

  def "Configure Keycloak chaining"() {
    def realmName = Constants.MASTER_REALM
    def idpAlias = "keycloak-oidc"

    when: "an identity provider is created in the realm"
    Map<String, String> idpConfig = new HashMap<>()
    idpConfig.put("clientId", "test-client")
    idpConfig.put("clientSecret", "test-client-secret")
    idpConfig.put("clientAuthMethod", "client_secret_basic")
    idpConfig.put("syncMode", "FORCE")
    idpConfig.put("filteredByClaim", "true")
    idpConfig.put("claimFilterName", "claimName")
    idpConfig.put("claimFilterValue", "claimValue")
    identityProvider.createUpdateIdentityProvider(realmName, idpAlias, "oidc", "IdP Name", idpConfig)

    then: "the identity provider is created in the realm"
    identityProvider.getRealms { realmsResource ->
      IdentityProvidersResource identityProvidersResource = realmsResource.realm(realmName).identityProviders()
      def ipr = identityProvidersResource.findAll().stream().filter(ipr -> idpAlias == ipr.getAlias()).findFirst()
      assert ipr.isPresent()
      assert ipr.get().getProviderId() == "oidc"
      assert ipr.get().getDisplayName() == "IdP Name"
      return null
    } == null

    and: "the identity provider configuration matches"
    identityProvider.getRealms { realmsResource ->
      IdentityProvidersResource identityProvidersResource = realmsResource.realm(realmName).identityProviders()
      Map<String, String> storedConfig = identityProvidersResource.findAll().stream().filter(ipr -> idpAlias == ipr.getAlias()).findFirst().get().getConfig()

      assert idpConfig.size() == storedConfig.size()
      idpConfig.each { key, value ->
        if (key != "clientSecret") {
          // clientSecret is not returned by Keycloak for security reasons
          assert storedConfig.get(key) == value
        }
      }

      return null
    } == null

    when: "identity provider role mappers are created for all roles"
    ClientRole.ALL_ROLES.forEach(role -> {
      String mapperName = role.replaceAll(":", " ")

      Map<String, String> mapperConfig = new HashMap<>()
      mapperConfig.put("syncMode", "FORCE")
      mapperConfig.put("claim", "cloudRoles")
      mapperConfig.put("claim.value", role)
      mapperConfig.put("role", "openremote." + role)

      identityProvider.createUpdateIdentityProviderMapper(realmName, idpAlias, mapperName, "oidc-role-idp-mapper", mapperConfig)
    })

    then: "the identity provider role mappers are created"
    identityProvider.getRealms { realmsResource ->
      IdentityProviderResource idpResource = realmsResource.realm(realmName).identityProviders().get(idpAlias)
      List<IdentityProviderMapperRepresentation> mappers = idpResource.getMappers()

      assert mappers.size() == ClientRole.ALL_ROLES.size()

      mappers.each { mapper ->
        Map<String, String> storedConfig = mapper.getConfig()
        String role = storedConfig.get("claim.value")

        assert ClientRole.ALL_ROLES.contains(role)
        assert storedConfig.get("syncMode") == "FORCE"
        assert storedConfig.get("claim") == "cloudRoles"
        assert storedConfig.get("role") == "openremote." + role
      }

      return null
    } == null

    when: "the browser authentication flow is configured to redirect to the identity provider"
    identityProvider.addAuthenticationExecutionConfig(realmName, "browser", "identity-provider-redirector", idpAlias, Map.of("defaultProvider", idpAlias))

    then: "the authentication flow is updated"
    identityProvider.getRealms { realmsResource ->
      def flows = realmsResource.realm(realmName).flows().getFlows()
      def browserFlow = flows.stream().filter { afr ->
        afr.getAlias() == "browser"
      }.findFirst().get()
      def iprExecutions = browserFlow.getAuthenticationExecutions().stream().filter(execution -> execution.getAuthenticator() == "identity-provider-redirector").toList()
      assert iprExecutions.size() == 1
      assert iprExecutions.get(0).getAuthenticatorConfig() == idpAlias
      return null
    } == null

    when: "identity provider role mappers are modified for all roles"
    ClientRole.ALL_ROLES.forEach(role -> {
      String mapperName = role.replaceAll(":", " ")

      Map<String, String> mapperConfig = new HashMap<>()
      mapperConfig.put("syncMode", "INHERIT")
      mapperConfig.put("claim", "cloudRoles")
      mapperConfig.put("claim.value", role)
      mapperConfig.put("role", "openremote." + role)

      identityProvider.createUpdateIdentityProviderMapper(realmName, idpAlias, mapperName, "oidc-role-idp-mapper", mapperConfig)
    })

    then: "the identity provider role mappers are updated"
    identityProvider.getRealms { realmsResource ->
      IdentityProviderResource idpResource = realmsResource.realm(realmName).identityProviders().get(idpAlias)
      List<IdentityProviderMapperRepresentation> mappers = idpResource.getMappers()

      assert mappers.size() == ClientRole.ALL_ROLES.size()

      mappers.each { mapper ->
        Map<String, String> storedConfig = mapper.getConfig()
        String role = storedConfig.get("claim.value")

        assert ClientRole.ALL_ROLES.contains(role)
        assert storedConfig.get("syncMode") == "INHERIT"
        assert storedConfig.get("claim") == "cloudRoles"
        assert storedConfig.get("role") == "openremote." + role
      }

      return null
    } == null
  }
}
