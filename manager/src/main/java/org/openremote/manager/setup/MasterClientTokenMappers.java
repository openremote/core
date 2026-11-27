/*
 * Copyright 2016, OpenRemote Inc.
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
package org.openremote.manager.setup;

import static org.openremote.model.Constants.KEYCLOAK_CLIENT_ID;

import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientScopeResource;
import org.keycloak.admin.client.resource.ClientScopesResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

/**
 * Keeps access tokens issued to {@code master/openremote} from growing when realms are added.
 *
 * <p>Keycloak adds each realm's management client roles to the master realm's composite {@code
 * admin} role. Keycloak expands composites in client scope mappings, so allowing {@code admin} in a
 * restricted scope would still allow all of those roles. The default role mappers would then write
 * the management roles to {@code resource_access} and their client IDs to {@code aud}.
 *
 * <p>OpenRemote uses {@code admin} in {@code realm_access.roles} to authorize master superusers.
 * Keep the existing role scope and realm role claim, including custom roles, and limit the client
 * role claim to {@code openremote} and {@code account}. Replace Audience Resolve with an {@code
 * account} audience gated by account roles so users without those roles keep their existing
 * audience behavior.
 *
 * <p>Clean setup and the upgrade migration both stage and verify the replacement mappers before
 * detaching the stock {@code roles} scope from this client. This keeps authorization claims
 * available if setup is interrupted and preserves the shared scope for other clients.
 */
public final class MasterClientTokenMappers {

  private static final Logger LOG = Logger.getLogger(MasterClientTokenMappers.class.getName());
  private static final String OIDC = "openid-connect";
  private static final String ACCOUNT = "account";
  private static final String ACCOUNT_SCOPE = "openremote-account-audience";
  private static final String CLIENT_ROLE_TARGET = "usermodel.clientRoleMapping.clientId";
  private static final String REALM_ROLE_MAPPER = "oidc-usermodel-realm-role-mapper";
  private static final String CLIENT_ROLE_MAPPER = "oidc-usermodel-client-role-mapper";
  private static final String AUDIENCE_RESOLVE = "oidc-audience-resolve-mapper";
  private static final String AUDIENCE = "oidc-audience-mapper";
  private static final String CLAIM = "claim.name";

  private MasterClientTokenMappers() {}

  public static void configure(RealmResource master) {
    var clients = master.clients();
    var match = clients.findByClientId(KEYCLOAK_CLIENT_ID);
    if (match.isEmpty()) {
      return; // Clean installation: KeycloakInitSetup will configure the client after creation.
    }

    ClientResource client = clients.get(match.getFirst().getId());
    List<ClientScopeRepresentation> defaults = client.getDefaultClientScopes();
    var stockRoles = defaults.stream().filter(scope -> "roles".equals(scope.getName())).findFirst();
    if (stockRoles.isEmpty()) {
      return; // Already configured or replaced by the deployment.
    }

    // A scope named "roles" may have been customized. Detach it only when the stock
    // mappers and scope settings are present.
    var scopes = master.clientScopes();
    var stock = scopes.get(stockRoles.get().getId());
    var stockMappers = stock.getProtocolMappers().getMappers();
    var realmMapper =
        stockMappers.stream()
            .filter(m -> isStockMapper(m, REALM_ROLE_MAPPER, "realm_access.roles"))
            .findFirst();
    var clientMapper =
        stockMappers.stream()
            .filter(m -> isStockMapper(m, CLIENT_ROLE_MAPPER, "resource_access.${client_id}.roles"))
            .findFirst();
    var audienceResolver =
        stockMappers.stream().filter(m -> isStockMapper(m, AUDIENCE_RESOLVE, null)).findFirst();
    var settings = client.toRepresentation();
    if (!Boolean.TRUE.equals(settings.isFullScopeAllowed())
        || Boolean.TRUE.equals(settings.isConsentRequired())
        || !"false".equals(stock.toRepresentation().getAttributes().get("include.in.token.scope"))
        || stockMappers.size() != 3
        || realmMapper.isEmpty()
        || clientMapper.isEmpty()
        || audienceResolver.isEmpty()
        || hasMappings(stock.getScopeMappings().getAll())) {
      LOG.warning("Skipping token mapper update for customized master/openremote role scope");
      return;
    }

    // Reuse the stock claim names and token settings. Setting a client ID on each
    // client-role mapper limits which client's roles it emits.
    var replacements =
        List.of(
            copyMapper(realmMapper.get(), "openremote realm roles", null),
            copyMapper(clientMapper.get(), "openremote client roles", KEYCLOAK_CLIENT_ID),
            copyMapper(clientMapper.get(), "openremote account roles", ACCOUNT));
    var directMappers = client.getProtocolMappers().getMappers();
    // Other default or requested optional scopes can still write these claims.
    // An exact match for a replacement can be left over from an interrupted run.
    var linkedMappers =
        Stream.concat(defaults.stream(), client.getOptionalClientScopes().stream())
            .filter(
                scope ->
                    !scope.getId().equals(stockRoles.get().getId())
                        && !ACCOUNT_SCOPE.equals(scope.getName()))
            .flatMap(scope -> scopes.get(scope.getId()).getProtocolMappers().getMappers().stream());
    if (Stream.concat(directMappers.stream(), linkedMappers)
        .anyMatch(
            mapper ->
                (writesRolesOrAudience(mapper)
                        || replacements.stream()
                            .anyMatch(
                                replacement -> replacement.getName().equals(mapper.getName())))
                    && replacements.stream()
                        .noneMatch(replacement -> sameMapper(mapper, replacement)))) {
      LOG.warning(
          "Skipping token mapper update for customized master/openremote role or audience mappers");
      return;
    }

    ClientRepresentation account = clients.findByClientId(ACCOUNT).getFirst();
    String audienceScope =
        ensureAccountAudience(scopes, clients.get(account.getId()), account.getId());
    // Keep the existing scope attached until every replacement is in place.
    for (var mapper : replacements) {
      if (directMappers.stream().noneMatch(existing -> sameMapper(existing, mapper))) {
        try (Response response = client.getProtocolMappers().createMapper(mapper)) {
          requireCreated(response, mapper.getName());
        }
      }
    }
    if (!hasScope(defaults, audienceScope)) {
      client.addDefaultClientScope(audienceScope);
    }
    // Read back both kinds of configuration before removing the old claim source.
    var installed = client.getProtocolMappers().getMappers();
    if (!hasScope(client.getDefaultClientScopes(), audienceScope)
        || replacements.stream()
            .anyMatch(
                mapper -> installed.stream().noneMatch(existing -> sameMapper(existing, mapper)))) {
      throw new IllegalStateException(
          "Could not verify replacement token mappers for master/openremote");
    }
    client.removeDefaultClientScope(stockRoles.get().getId());
    if (hasScope(client.getDefaultClientScopes(), stockRoles.get().getId())) {
      throw new IllegalStateException(
          "Could not detach the stock roles scope from master/openremote");
    }
    LOG.info("Configured compact access token claims for master/openremote");
  }

  private static boolean hasScope(List<ClientScopeRepresentation> scopes, String id) {
    return scopes.stream().anyMatch(scope -> id.equals(scope.getId()));
  }

  private static boolean isStockMapper(
      ProtocolMapperRepresentation mapper, String type, String claim) {
    var config = mapper.getConfig();
    return OIDC.equals(mapper.getProtocol())
        && type.equals(mapper.getProtocolMapper())
        && Objects.equals(claim, config.get(CLAIM))
        && "true".equals(config.get("access.token.claim"))
        && "true".equals(config.get("introspection.token.claim"))
        && config.entrySet().stream()
            .allMatch(
                entry ->
                    switch (entry.getKey()) {
                      case CLAIM, "access.token.claim", "introspection.token.claim" -> true;
                      case "multivalued" -> "true".equals(entry.getValue());
                      case "jsonType.label" -> "String".equals(entry.getValue());
                      // Keycloak's stock role mappers carry this unused placeholder.
                      case "user.attribute" -> "foo".equals(entry.getValue());
                      case "id.token.claim", "userinfo.token.claim", "lightweight.claim" ->
                          "false".equals(entry.getValue());
                      default -> entry.getValue() == null || entry.getValue().isEmpty();
                    })
        && (claim == null || "true".equals(config.get("multivalued")));
  }

  private static boolean hasMappings(MappingsRepresentation mappings) {
    return (mappings.getRealmMappings() != null && !mappings.getRealmMappings().isEmpty())
        || (mappings.getClientMappings() != null && !mappings.getClientMappings().isEmpty());
  }

  private static boolean writesRolesOrAudience(ProtocolMapperRepresentation mapper) {
    String claim = Objects.toString(mapper.getConfig().get(CLAIM), "");
    return claim.startsWith("realm_access")
        || claim.startsWith("resource_access")
        || claim.equals("aud")
        || AUDIENCE_RESOLVE.equals(mapper.getProtocolMapper())
        || AUDIENCE.equals(mapper.getProtocolMapper());
  }

  private static boolean sameMapper(
      ProtocolMapperRepresentation left, ProtocolMapperRepresentation right) {
    return Objects.equals(left.getName(), right.getName())
        && Objects.equals(left.getProtocolMapper(), right.getProtocolMapper())
        && Objects.equals(left.getProtocol(), right.getProtocol())
        && Objects.equals(left.getConfig(), right.getConfig());
  }

  private static ProtocolMapperRepresentation copyMapper(
      ProtocolMapperRepresentation source, String name, String clientId) {
    // Keycloak assigns a new ID for the dedicated mapper. Copy the config so the
    // client filter applies only to the new mapper.
    var copy = new ProtocolMapperRepresentation();
    copy.setName(name);
    copy.setProtocol(source.getProtocol());
    copy.setProtocolMapper(source.getProtocolMapper());
    copy.setConfig(new HashMap<>(source.getConfig()));
    if (clientId != null) {
      copy.getConfig().put(CLIENT_ROLE_TARGET, clientId);
    }
    return copy;
  }

  private static String ensureAccountAudience(
      ClientScopesResource scopes, ClientResource account, String accountId) {
    var audience = new ProtocolMapperRepresentation();
    audience.setName("account audience");
    audience.setProtocol(OIDC);
    audience.setProtocolMapper(AUDIENCE);
    audience.setConfig(
        Map.of(
            "included.client.audience", ACCOUNT,
            "access.token.claim", "true",
            "introspection.token.claim", "true",
            "id.token.claim", "false",
            "userinfo.token.claim", "false"));

    // This scope gates a mapper internally and adds no OAuth scope or consent prompt.
    var attributes =
        Map.of("include.in.token.scope", "false", "display.on.consent.screen", "false");
    var existing =
        scopes.findAll().stream()
            .filter(scope -> ACCOUNT_SCOPE.equals(scope.getName()))
            .findFirst();
    String id;
    // A retry can reuse a scope created before the role mappings were added.
    // Verify its contents before using a scope with the expected name.
    if (existing.isPresent()) {
      id = existing.get().getId();
      var mappers = scopes.get(id).getProtocolMappers().getMappers();
      if (!OIDC.equals(existing.get().getProtocol())
          || !attributes.equals(existing.get().getAttributes())
          || mappers.size() != 1
          || !sameMapper(mappers.getFirst(), audience)) {
        throw new IllegalStateException("Client scope name already in use: " + ACCOUNT_SCOPE);
      }
    } else {
      var scope = new ClientScopeRepresentation();
      scope.setName(ACCOUNT_SCOPE);
      scope.setProtocol(OIDC);
      scope.setAttributes(attributes);
      scope.setProtocolMappers(List.of(audience));
      try (Response response = scopes.create(scope)) {
        requireCreated(response, ACCOUNT_SCOPE);
        String path = response.getLocation().getPath();
        id = path.substring(path.lastIndexOf('/') + 1);
      }
    }

    ClientScopeResource scope = scopes.get(id);
    var mappings = scope.getScopeMappings();
    var all = mappings.getAll();
    if ((all.getRealmMappings() != null && !all.getRealmMappings().isEmpty())
        || (all.getClientMappings() != null
            && all.getClientMappings().keySet().stream().anyMatch(name -> !ACCOUNT.equals(name)))) {
      throw new IllegalStateException("Unexpected role mappings on client scope " + ACCOUNT_SCOPE);
    }
    // Keycloak enables a role-mapped client scope only for users with a matching
    // effective role. The stock Audience Resolve mapper also required an account role.
    List<RoleRepresentation> accountRoles = account.roles().list();
    if (accountRoles.isEmpty()) {
      throw new IllegalStateException("No account roles available to gate the account audience");
    }
    var roleScope = mappings.clientLevel(accountId);
    var assigned = roleScope.listAll().stream().map(RoleRepresentation::getId).toList();
    var missing = accountRoles.stream().filter(role -> !assigned.contains(role.getId())).toList();
    if (!missing.isEmpty()) {
      roleScope.add(missing);
    }
    return id;
  }

  private static void requireCreated(Response response, String name) {
    if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
      throw new IllegalStateException(
          "Could not create Keycloak " + name + " (HTTP " + response.getStatus() + ")");
    }
  }
}
