/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.manager.security;

import static org.openremote.model.Constants.*;
import static org.openremote.model.util.MapAccess.getBoolean;
import static org.openremote.model.util.MapAccess.getString;
import static org.openremote.model.util.ValueUtil.convert;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import io.undertow.util.Headers;
import jakarta.persistence.Query;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.utils.URIBuilder;
import org.keycloak.admin.client.resource.*;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.common.enums.SslRequired;
import org.keycloak.representations.idm.*;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.security.AuthContext;
import org.openremote.container.security.keycloak.KeycloakIdentityProvider;
import org.openremote.container.timer.TimerService;
import org.openremote.container.web.CORSConfig;
import org.openremote.container.web.WebService;
import org.openremote.manager.apps.ConsoleAppService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.PersistenceEvent;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.auth.OAuthPasswordGrant;
import org.openremote.model.gateway.GatewayConnection;
import org.openremote.model.provisioning.ProvisioningConfig;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.UserQuery;
import org.openremote.model.query.filter.RealmPredicate;
import org.openremote.model.rules.RealmRuleset;
import org.openremote.model.security.*;
import org.openremote.model.util.TextUtil;
import org.openremote.model.util.UniqueIdentifierGenerator;
import org.openremote.model.util.ValueUtil;

/**
 * All keycloak interaction is done through the admin-cli client; security is implemented downstream
 * of here; anything in the same process as this service has privileged access to keycloak.
 */
public class ManagerKeycloakIdentityProvider extends KeycloakIdentityProvider
    implements ManagerIdentityProvider {

  private static final Logger LOG =
      Logger.getLogger(ManagerKeycloakIdentityProvider.class.getName());
  public static final String REALM_KEYCLOAK_THEME_SUFFIX = "_REALM_KEYCLOAK_THEME";
  public static final String DEFAULT_REALM_KEYCLOAK_THEME = "DEFAULT_REALM_KEYCLOAK_THEME";
  public static final String DEFAULT_REALM_KEYCLOAK_THEME_DEFAULT = "openremote";
  public static final String OR_KEYCLOAK_GRANT_FILE = "OR_KEYCLOAK_GRANT_FILE";
  public static final String OR_KEYCLOAK_GRANT_FILE_DEFAULT = "manager/keycloak-credentials.json";
  public static final String OR_KEYCLOAK_PUBLIC_URI = "OR_KEYCLOAK_PUBLIC_URI";
  public static final String OR_KEYCLOAK_ENABLE_DIRECT_ACCESS_GRANT =
      "OR_KEYCLOAK_ENABLE_DIRECT_ACCESS_GRANT";
  public static final int REALM_CACHE_EXPIRY_MINS = 10;
  public static final List<String> BUILT_IN_REALM_ROLES =
      List.of("admin", "create-realm", "offline_access", "uma_authorization");
  protected PersistenceService persistenceService;
  protected AssetStorageService assetStorageService;
  protected TimerService timerService;
  protected MessageBrokerService messageBrokerService;
  protected ClientEventService clientEventService;
  protected ConsoleAppService consoleAppService;
  protected String keycloakAdminPassword;
  protected Container container;
  protected String frontendURI;
  protected List<String> validRedirectUris;
  protected Cache<String, Realm> realmCache;

  @Override
  public void init(Container container) {
    super.init(container);
    this.container = container;

    realmCache =
        CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(
                Duration.ofMinutes(container.isDevMode() ? 0 : REALM_CACHE_EXPIRY_MINS))
            .build();

    String keycloakPublicUri = getString(container.getConfig(), OR_KEYCLOAK_PUBLIC_URI, null);
    if (TextUtil.isNullOrEmpty(keycloakPublicUri)) {
      // Use full public URI from keycloak backend - in our docker image this is set to '/auth'
      // indicating that
      // keycloak is available on the same hostname port and schema as the manager - needed for
      // gateway tunnelling
      // scenarios specifically
      keycloakPublicUri = getKeycloakPublicUrl();
    }
    try {
      URIBuilder uriBuilder = new URIBuilder(keycloakPublicUri);
      frontendURI = uriBuilder.build().toString();
    } catch (URISyntaxException e) {
      LOG.log(Level.SEVERE, "Failed to build Keycloak public URI", e);
      throw new RuntimeException(e);
    }

    keycloakAdminPassword =
        container.getConfig().getOrDefault(OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT);
    timerService = container.getService(TimerService.class);
    persistenceService = container.getService(PersistenceService.class);
    messageBrokerService = container.getService(MessageBrokerService.class);
    clientEventService = container.getService(ClientEventService.class);
    consoleAppService = container.getService(ConsoleAppService.class);
    assetStorageService = container.getService(AssetStorageService.class);

    // Allow all external hostnames with wildcard and same host with wildcard
    validRedirectUris = new ArrayList<>();
    validRedirectUris.add("/*");
    validRedirectUris.addAll(
        WebService.getExternalHostnames().stream().map(host -> "https://" + host + "/*").toList());
  }

  @Override
  protected OAuthGrant getStoredCredentials(Container container) {
    // Try and load keycloak proxy credentials from the file system
    String grantFile =
        getString(container.getConfig(), OR_KEYCLOAK_GRANT_FILE, OR_KEYCLOAK_GRANT_FILE_DEFAULT);
    Path grantPath =
        TextUtil.isNullOrEmpty(grantFile) ? null : persistenceService.resolvePath(grantFile);
    OAuthGrant grant = null;

    if (grantPath != null && Files.isReadable(grantPath)) {
      LOG.info("Loading OR_KEYCLOAK_GRANT_FILE: " + grantPath);

      try (InputStream is = Files.newInputStream(grantPath)) {
        String grantJson = IOUtils.toString(is, StandardCharsets.UTF_8);
        grant =
            ValueUtil.parse(grantJson, OAuthGrant.class)
                .orElseGet(
                    () -> {
                      LOG.warning("Failed to load OR_KEYCLOAK_GRANT_FILE: " + grantPath);
                      return null;
                    });
      } catch (Exception ex) {
        throw new ExceptionInInitializerError(ex);
      }
    }
    return grant;
  }

  @Override
  protected OAuthGrant generateStoredCredentials(Container container) {
    String grantFile =
        getString(container.getConfig(), OR_KEYCLOAK_GRANT_FILE, OR_KEYCLOAK_GRANT_FILE_DEFAULT);

    if (TextUtil.isNullOrEmpty(grantFile)) {
      return null;
    }

    Path grantPath = persistenceService.resolvePath(grantFile);

    // Create a new super user for the keycloak proxy so admin user can be modified if desired
    User keycloakProxyUser =
        new User().setUsername(MANAGER_CLIENT_ID).setEnabled(true).setSystemAccount(true);
    // Make password complex enough to hopefully meet any password policy that is set in keycloak
    String password = UniqueIdentifierGenerator.generateId() + "$*#@$";

    try {
      keycloakProxyUser = createUpdateUser(MASTER_REALM, keycloakProxyUser, password, true);

      // Make this proxy user a super user by giving them admin realm role
      updateUserRealmRoles(
          MASTER_REALM,
          keycloakProxyUser.getId(),
          addUserRealmRoles(MASTER_REALM, keycloakProxyUser.getId(), SUPER_USER_REALM_ROLE));

      // Use same details as default keycloak grant but change the username and password to our new
      // user
      OAuthPasswordGrant grant = getDefaultKeycloakGrant(container);
      grant.setUsername(keycloakProxyUser.getUsername()).setPassword(password);

      // Ensure the directory path exists
      grantPath.getParent().toFile().mkdirs();

      Files.write(
          grantPath,
          ValueUtil.asJSON(grant).orElse("null").getBytes(),
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);

      return grant;
    } catch (Exception e) {
      LOG.info("Failed to write " + OR_KEYCLOAK_GRANT_FILE + ": " + grantPath);
      return null;
    }
  }

  // TODO: Remove this once #3156 is implemented
  @Override
  public void onSetupDone(Container container) {
    // Need to add the attribute to the keycloak user now the master realm is properly configured
    User keycloakProxyUser = getUserByUsername(MASTER_REALM, MANAGER_CLIENT_ID);
    if (keycloakProxyUser != null) {
      keycloakProxyUser.setSystemAccount(true);
      createUpdateUser(MASTER_REALM, keycloakProxyUser, null, true);
    }
  }

  @Override
  protected void addClientRedirectUris(String client, List<String> redirectUrls, boolean devMode) {
    // Callback URL used by Manager web client authentication, any relative path to "ourselves" is
    // fine
    String realmManagerCallbackUrl =
        UriBuilder.fromUri("/").path(client).path("*").build().toString();
    redirectUrls.add(realmManagerCallbackUrl);
  }

  protected <T> T withClientResource(
      String realm,
      String client,
      RealmsResource realmsResource,
      BiFunction<ClientRepresentation, ClientResource, T> clientResourceConsumer,
      Supplier<T> notFoundProvider)
      throws ClientErrorException {
    ClientRepresentation clientRepresentation = null;
    ClientResource clientResource = null;

    try {
      ClientsResource clientsResource = realmsResource.realm(realm).clients();
      List<ClientRepresentation> clientRepresentations = clientsResource.findByClientId(client);
      if (clientRepresentations != null && !clientRepresentations.isEmpty()) {
        if (clientRepresentations.size() > 1) {
          throw new IllegalStateException(
              "More than one matching client found realm=" + realm + ", client=" + client);
        }
        clientRepresentation = clientRepresentations.getFirst();
        clientResource = clientsResource.get(clientRepresentation.getId());
      }
    } catch (Exception e) {
      LOG.log(Level.INFO, "withClientResource failed", e);
    }
    if (clientResource != null) {
      return clientResourceConsumer.apply(clientRepresentation, clientResource);
    } else if (notFoundProvider != null) {
      return notFoundProvider.get();
    }
    return null;
  }

  @Override
  public User[] queryUsers(UserQuery userQuery) {
    return ManagerIdentityProvider.getUsersFromDb(persistenceService, userQuery);
  }

  @Override
  public User getUser(String userId) {
    return ManagerIdentityProvider.getUserByIdFromDb(persistenceService, userId);
  }

  @Override
  public User getUserByUsername(String realm, String username) {
    if (username.length() > 255) {
      // Keycloak has a 255 character limit on clientId
      username = username.substring(0, 254);
    }
    username =
        username
            .toLowerCase(); // Keycloak clients are case sensitive but pretends not to be so always
    // force lowercase
    return ManagerIdentityProvider.getUserByUsernameFromDb(persistenceService, realm, username);
  }

  @Override
  public User createUpdateUser(
      String realm, final User user, String passwordSecret, boolean allowUpdate)
      throws ClientErrorException {
    return getRealms(
        realmsResource -> {

          // Force lowercase username
          if (user.getUsername() != null) {
            user.setUsername(user.getUsername().toLowerCase(Locale.ROOT));
          }
          if (user.getUsername().length() > 255) {
            // Keycloak has a 255 character limit on clientId which affects service users
            user.setUsername(user.getUsername().substring(0, 254));
          }

          if (!user.isServiceAccount() && allowUpdate) {
            if (getRealm(realm).getRegistrationEmailAsUsername()
                ? user.getEmail() == null
                : user.getUsername() == null) {
              throw new BadRequestException(
                  "Attempt to create/update user but no username or email provided: User=" + user);
            }
          }

          boolean isUpdate = false;
          User existingUser =
              user.getId() != null
                  ? getUser(user.getId())
                  : getUserByUsername(realm, user.getUsername());
          ClientRepresentation clientRepresentation;
          UserRepresentation userRepresentation;

          if (existingUser != null && !allowUpdate) {
            String msg = "Attempt to create user but it already exists: User=" + user;
            LOG.warning(msg);
            throw new ForbiddenException(msg);
          }

          if (existingUser == null && user.isServiceAccount()) {
            // Could be a service user
            userRepresentation =
                withClientResource(
                    realm,
                    user.getUsername(),
                    realmsResource,
                    (clientRep, clientResource) -> {
                      UserRepresentation userRep = clientResource.getServiceAccountUser();
                      if (userRep == null) {
                        String msg =
                            "Attempt to update/create service user but a regular client with same client ID as this username already exists: User="
                                + user;
                        LOG.warning(msg);
                        throw new NotAllowedException(msg);
                      }
                      return userRep;
                    },
                    null);

            if (userRepresentation != null) {
              existingUser = convert(userRepresentation, User.class);
            }
          }

          if (existingUser != null
              && user.getId() != null
              && !existingUser.getId().equals(user.getId())) {
            String msg =
                "Attempt to update user but retrieved user ID doesn't match supplied so ignoring: User="
                    + user;
            LOG.warning(msg);
            throw new BadRequestException(msg);
          }

          if (existingUser != null) {
            isUpdate = true;

            if (existingUser.isServiceAccount() != user.isServiceAccount()) {
              String msg = "Attempt to update user service account flag not allowed: User=" + user;
              LOG.warning(msg);
              throw new NotAllowedException(msg);
            }

            if (existingUser.isServiceAccount()
                && !existingUser.getUsername().equals(user.getUsername())) {
              String msg = "Attempt to update username of service user not allowed: User=" + user;
              LOG.warning(msg);
              throw new NotAllowedException(msg);
            }
          }

          // For service users we don't actually create the user - keycloak does that when the
          // client is created
          if (isUpdate) {

            // User only has a subset of user representation so overlay on actual user
            // representation
            UserResource userResource =
                realmsResource.realm(realm).users().get(existingUser.getId());
            userRepresentation = userResource.toRepresentation();
            userRepresentation.setFirstName(user.getFirstName());
            userRepresentation.setLastName(user.getLastName());
            userRepresentation.setEmail(user.getEmail());
            userRepresentation.setEnabled(user.getEnabled());
            userRepresentation.setAttributes(user.getAttributeMap());
            userResource.update(userRepresentation);

          } else {

            if (user.isServiceAccount()) {

              // Just create client with service account and user will be generated
              clientRepresentation = new ClientRepresentation();
              clientRepresentation.setStandardFlowEnabled(false);
              clientRepresentation.setImplicitFlowEnabled(false);
              clientRepresentation.setDirectAccessGrantsEnabled(false);
              clientRepresentation.setServiceAccountsEnabled(true);
              clientRepresentation.setClientAuthenticatorType("client-secret");
              clientRepresentation.setClientId(user.getUsername());
              clientRepresentation.setSecret(passwordSecret);
              clientRepresentation = createUpdateClient(realm, clientRepresentation);
              userRepresentation =
                  realmsResource
                      .realm(realm)
                      .clients()
                      .get(clientRepresentation.getId())
                      .getServiceAccountUser();
              userRepresentation.setEnabled(user.getEnabled());
              userRepresentation.setAttributes(user.getAttributeMap());
              realmsResource
                  .realm(realm)
                  .users()
                  .get(userRepresentation.getId())
                  .update(userRepresentation);

            } else {

              userRepresentation = convert(user, UserRepresentation.class);
              RealmResource realmResource = realmsResource.realm(realm);
              String location;
              String error = null;
              Response.StatusType status;

              try (Response response = realmResource.users().create(userRepresentation)) {
                location = response.getHeaderString(Headers.LOCATION_STRING);
                status = response.getStatusInfo();
                if (!status.equals(Response.Status.CREATED) || TextUtil.isNullOrEmpty(location)) {
                  error = response.readEntity(String.class);
                }
              }

              if (!status.equals(Response.Status.CREATED) || TextUtil.isNullOrEmpty(location)) {
                throw new BadRequestException(
                    "Failed to create user: User=" + user + ", Error=" + error);
              }
              String[] locationArr = location.split("/");
              String userId = locationArr.length > 0 ? locationArr[locationArr.length - 1] : null;
              userRepresentation = realmResource.users().get(userId).toRepresentation();
            }
          }

          if (passwordSecret != null || (!isUpdate && user.isServiceAccount())) {
            if (user.isServiceAccount()) {
              resetSecret(realm, userRepresentation.getId(), passwordSecret);
            } else {
              Credential credential = new Credential(passwordSecret, false);
              resetPassword(realm, userRepresentation.getId(), credential);
            }
          }

          User updatedUser = convert(userRepresentation, User.class);
          if (updatedUser != null) {
            updatedUser.setRealm(realm);
            if (updatedUser.isServiceAccount()) {
              updatedUser.setSecret(passwordSecret);
            }

            if (existingUser != null) {
              // Push realm ID into updated user
              updatedUser.setRealmId(existingUser.getRealmId());
            }
          }

          persistenceService.publishPersistenceEvent(
              (isUpdate ? PersistenceEvent.Cause.UPDATE : PersistenceEvent.Cause.CREATE),
              updatedUser,
              existingUser,
              User.class,
              Collections.singletonList("attributes"),
              null);

          return updatedUser;
        });
  }

  @Override
  public void deleteUser(String realm, String userId) throws ClientErrorException {

    User user = getUser(userId);

    if (user == null) {
      return;
    }

    if (user.getUsername().equals(MASTER_REALM_ADMIN_USER)
        && user.getRealm().equals(MASTER_REALM)) {
      throw new IllegalStateException("Cannot delete master realm admin user");
    }

    getRealms(
        realmsResource -> {
          if (user.isServiceAccount()) {
            // Delete the client
            deleteClient(realm, user.getUsername());
          } else {
            Response response = realmsResource.realm(realm).users().delete(userId);
            response.close();
            if (!response.getStatusInfo().equals(Response.Status.NO_CONTENT)) {
              throw new IllegalStateException("Failed to delete user: " + userId);
            }
          }
          return null;
        });

    persistenceService.publishPersistenceEvent(
        PersistenceEvent.Cause.DELETE,
        null,
        user,
        User.class,
        Collections.singletonList("attributes"),
        null);
  }

  @Override
  public void requestPasswordReset(String realm, String userId) throws ClientErrorException {
    getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          if (realmResource.toRepresentation().getSmtpServer().isEmpty())
            throw new IllegalStateException("SMTP server is not configured for realm: " + realm);
          realmResource
              .users()
              .get(userId)
              .executeActionsEmail(Collections.singletonList("UPDATE_PASSWORD"));
          return null;
        });
  }

  @Override
  public void resetPassword(String realm, String userId, Credential credential)
      throws ClientErrorException {
    getRealms(
        realmsResource -> {
          realmsResource
              .realm(realm)
              .users()
              .get(userId)
              .resetPassword(convert(credential, CredentialRepresentation.class));
          return null;
        });
  }

  @Override
  public String resetSecret(String realm, String userId, String secret)
      throws ClientErrorException {
    return getRealms(
        realmsResource -> {
          UserRepresentation userRepresentation = null;
          try {
            userRepresentation = realmsResource.realm(realm).users().get(userId).toRepresentation();
          } catch (Exception ignored) {
          }
          if (userRepresentation == null) {
            return null;
          }

          return withClientResource(
              realm,
              userRepresentation.getUsername().substring(User.SERVICE_ACCOUNT_PREFIX.length()),
              realmsResource,
              (clientRep, clientResource) -> {
                if (TextUtil.isNullOrEmpty(secret)) {
                  CredentialRepresentation credentialRepresentation =
                      clientResource.generateNewSecret();
                  return credentialRepresentation.getValue();
                } else {
                  clientRep.setSecret(secret);
                  clientResource.update(clientRep);
                  return secret;
                }
              },
              null);
        });
  }

  @Override
  public Role[] getClientRoles(String realm, String client) throws ClientErrorException {
    return getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          ClientsResource clientsResource = realmResource.clients();
          ClientResource clientResource = null;

          if (client != null) {
            ClientRepresentation clientRepresentation = getClient(realm, client);

            if (clientRepresentation == null) {
              throw new IllegalStateException("Cannot find specified client: " + client);
            }
            clientResource = clientsResource.get(clientRepresentation.getId());
          }

          List<RoleRepresentation> roleRepresentations =
              clientResource != null ? clientResource.roles().list() : realmResource.roles().list();
          List<Role> roles = new ArrayList<>();

          for (RoleRepresentation clientRole : roleRepresentations) {
            String[] composites =
                clientRole.isComposite()
                    ? realmResource.rolesById().getRoleComposites(clientRole.getId()).stream()
                        .map(RoleRepresentation::getId)
                        .toArray(String[]::new)
                    : null;
            roles.add(
                new Role(
                        clientRole.getId(),
                        clientRole.getName(),
                        clientRole.isComposite(),
                        null,
                        composites)
                    .setDescription(clientRole.getDescription()));
          }

          return roles.toArray(new Role[0]);
        });
  }

  @Override
  public void updateClientRoles(String realm, String clientId, Role[] roles)
      throws ClientErrorException {

    getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          ClientsResource clientsResource = realmResource.clients();
          ClientRepresentation clientRepresentation = getClient(realm, clientId);
          if (clientRepresentation == null) {
            throw new IllegalStateException("Cannot find specified client: " + clientId);
          }
          ClientResource clientResource = clientsResource.get(clientRepresentation.getId());
          List<RoleRepresentation> existingRoles = new ArrayList<>(clientResource.roles().list());

          List<RoleRepresentation> removedRoles =
              existingRoles.stream()
                  .filter(
                      existingRole ->
                          Arrays.stream(roles)
                              .noneMatch(r -> existingRole.getId().equals(r.getId())))
                  .toList();

          removedRoles.forEach(
              removedRole -> {
                realmResource.rolesById().deleteRole(removedRole.getId());
                existingRoles.remove(removedRole);
              });

          Arrays.stream(roles)
              .forEach(
                  role -> {
                    RoleRepresentation existingRole;
                    boolean compositesModified = false;
                    Set<RoleRepresentation> existingComposites = new HashSet<>();
                    Set<RoleRepresentation> requestedComposites = new HashSet<>();

                    if (role.getId() == null) {
                      existingRole = saveClientRole(realmResource, clientResource, role, null);
                      existingRoles.add(existingRole);
                      compositesModified =
                          role.getCompositeRoleIds() != null
                              && role.getCompositeRoleIds().length > 0;
                      if (compositesModified) {
                        requestedComposites.addAll(
                            Arrays.stream(role.getCompositeRoleIds())
                                .map(
                                    id ->
                                        existingRoles.stream()
                                            .filter(er -> er.getId().equals(id))
                                            .findFirst()
                                            .orElse(null))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()));
                      }
                    } else {
                      existingRole =
                          existingRoles.stream()
                              .filter(r -> r.getId().equals(role.getId()))
                              .findFirst()
                              .orElseThrow(
                                  () ->
                                      new IllegalStateException(
                                          "One or more supplied roles have an ID that doesn't exist"));

                      boolean isComposite =
                          role.isComposite()
                              && role.getCompositeRoleIds() != null
                              && role.getCompositeRoleIds().length > 0;

                      boolean rolePropertiesModified =
                          !Objects.equals(existingRole.getName(), role.getName())
                              || !Objects.equals(
                                  existingRole.getDescription(), role.getDescription());

                      if (isComposite || existingRole.isComposite()) {
                        existingComposites.addAll(
                            Optional.ofNullable(
                                    realmResource
                                        .rolesById()
                                        .getClientRoleComposites(
                                            existingRole.getId(), clientRepresentation.getId()))
                                .orElse(new HashSet<>()));
                        requestedComposites.addAll(
                            Arrays.stream(role.getCompositeRoleIds())
                                .map(
                                    id ->
                                        existingRoles.stream()
                                            .filter(er -> er.getId().equals(id))
                                            .findFirst()
                                            .orElse(null))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toSet()));

                        if (requestedComposites.size() != role.getCompositeRoleIds().length) {
                          throw new IllegalStateException(
                              "One or more composite roles contain an invalid role ID");
                        }

                        compositesModified =
                            !Objects.equals(existingComposites, requestedComposites);
                      }

                      if (rolePropertiesModified) {
                        // Merge the role property changes
                        saveClientRole(realmResource, clientResource, role, existingRole);
                      }
                    }

                    if (compositesModified) {
                      List<RoleRepresentation> removed =
                          existingComposites.stream()
                              .filter(existing -> !requestedComposites.contains(existing))
                              .collect(Collectors.toList());
                      List<RoleRepresentation> added =
                          requestedComposites.stream()
                              .filter(existing -> !existingComposites.contains(existing))
                              .collect(Collectors.toList());
                      if (!removed.isEmpty()) {
                        realmResource.rolesById().deleteComposites(existingRole.getId(), removed);
                      }
                      if (!added.isEmpty()) {
                        realmResource.rolesById().addComposites(existingRole.getId(), added);
                      }
                    }
                  });

          return null;
        });
  }

  protected RoleRepresentation saveClientRole(
      RealmResource realmResource,
      ClientResource clientResource,
      Role role,
      RoleRepresentation representation)
      throws ClientErrorException {
    if (representation == null) {
      representation = new RoleRepresentation();
    }
    representation.setName(role.getName());
    representation.setDescription(role.getDescription());
    representation.setClientRole(true);
    if (representation.getId() == null) {
      clientResource.roles().create(representation);
    } else {
      realmResource.rolesById().updateRole(representation.getId(), representation);
    }

    return clientResource.roles().get(representation.getName()).toRepresentation();
  }

  @Override
  public String[] getUserClientRoles(String realm, String userId, String client)
      throws ClientErrorException {
    return getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          RoleMappingResource roleMappingResource = realmResource.users().get(userId).roles();

          return withClientResource(
              realm,
              client,
              realmsResource,
              (clientRepresentation, clientResource) ->
                  roleMappingResource
                      .clientLevel(clientRepresentation.getId())
                      .listEffective()
                      .stream()
                      .map(RoleRepresentation::getName)
                      .toArray(String[]::new),
              () -> new String[0]);
        });
  }

  @Override
  public String[] getUserRealmRoles(String realm, String userId) throws ClientErrorException {
    return getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          RoleMappingResource roleMappingResource = realmResource.users().get(userId).roles();
          return roleMappingResource.realmLevel().listEffective().stream()
              .filter(
                  rr ->
                      (MASTER_REALM.equals(realm) && SUPER_USER_REALM_ROLE.equals(rr.getName()))
                          || !isBuiltInRealmRole(rr.getName()))
              .map(RoleRepresentation::getName)
              .toArray(String[]::new);
        });
  }

  @Override
  public void updateUserClientRoles(
      @NotNull String realm, @NotNull String userId, @NotNull String client, String... roles)
      throws ClientErrorException, IllegalStateException {
    getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          UserRepresentation user = realmResource.users().get(userId).toRepresentation();

          if (user == null) {
            throw new IllegalStateException("Multiple users with the same username found");
          }

          RoleMappingResource roleMappingResource = realmResource.users().get(user.getId()).roles();
          ClientRepresentation clientRepresentation = getClient(realm, client);

          if (clientRepresentation == null) {
            throw new IllegalStateException("Invalid client: " + client);
          }

          ClientResource clientResource = realmResource.clients().get(clientRepresentation.getId());
          List<String> assignedRoles =
              roles != null ? Arrays.asList(roles) : Collections.emptyList();
          // Get all configurable client roles
          List<RoleRepresentation> availableRoles = clientResource.roles().list();

          // Delete any that are not assigned and add all that are assigned
          availableRoles.stream()
              .collect(Collectors.partitioningBy(rr -> assignedRoles.contains(rr.getName())))
              .forEach(
                  (isAssigned, realmRoles) -> {
                    if (isAssigned) {
                      // Assigned roles
                      if (!realmRoles.isEmpty()) {
                        roleMappingResource
                            .clientLevel(clientRepresentation.getId())
                            .add(
                                realmRoles.stream()
                                    .map(
                                        v -> {
                                          RoleRepresentation rr = new RoleRepresentation();
                                          rr.setId(v.getId());
                                          rr.setName(v.getName());
                                          return rr;
                                        })
                                    .toList());
                      }
                    } else {
                      // Unassigned roles
                      if (!realmRoles.isEmpty()) {
                        roleMappingResource
                            .clientLevel(clientRepresentation.getId())
                            .remove(
                                realmRoles.stream()
                                    .map(
                                        v -> {
                                          RoleRepresentation rr = new RoleRepresentation();
                                          rr.setId(v.getId());
                                          rr.setName(v.getName());
                                          return rr;
                                        })
                                    .toList());
                      }
                    }
                  });
          return null;
        });
  }

  @Override
  public void updateUserRealmRoles(String realm, String userId, String... roles)
      throws ClientErrorException, IllegalStateException {
    getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          UserRepresentation user = realmResource.users().get(userId).toRepresentation();

          if (user == null) {
            throw new IllegalStateException("Multiple users with the same username found");
          }

          RoleMappingResource roleMappingResource = realmResource.users().get(user.getId()).roles();
          List<String> assignedRoles =
              roles != null ? Arrays.asList(roles) : Collections.emptyList();

          // Get all configurable realm roles from the realm cache
          Set<RealmRole> availableRealmRoles = getRealm(realm).getRealmRoles();

          // Delete any that are not assigned and add any that are assigned
          availableRealmRoles.stream()
              .collect(Collectors.partitioningBy(rr -> assignedRoles.contains(rr.getName())))
              .forEach(
                  (isAssigned, realmRoles) -> {
                    if (isAssigned) {
                      // Assigned roles
                      if (!realmRoles.isEmpty()) {
                        roleMappingResource
                            .realmLevel()
                            .add(
                                realmRoles.stream()
                                    .map(
                                        v -> {
                                          RoleRepresentation rr = new RoleRepresentation();
                                          rr.setId(v.getId());
                                          rr.setName(v.getName());
                                          return rr;
                                        })
                                    .toList());
                      }
                    } else {
                      // Unassigned roles
                      if (!realmRoles.isEmpty()) {
                        roleMappingResource
                            .realmLevel()
                            .remove(
                                realmRoles.stream()
                                    .map(
                                        v -> {
                                          RoleRepresentation rr = new RoleRepresentation();
                                          rr.setId(v.getId());
                                          rr.setName(v.getName());
                                          return rr;
                                        })
                                    .toList());
                      }
                    }
                  });
          return null;
        });
  }

  @Override
  public boolean isMasterRealmAdmin(String userId) throws ClientErrorException {
    Optional<UserRepresentation> adminUser =
        getRealms(
                realmsResource ->
                    realmsResource
                        .realm(MASTER_REALM)
                        .users()
                        .search(MASTER_REALM_ADMIN_USER, null, null))
            .stream()
            .filter(user -> user.getUsername().equals(MASTER_REALM_ADMIN_USER))
            .findFirst();

    if (adminUser.isEmpty()) {
      throw new IllegalStateException("Can't load master realm admin user");
    }
    return adminUser.map(UserRepresentation::getId).map(id -> id.equals(userId)).orElse(false);
  }

  @Override
  public Realm[] getRealms() {
    return ManagerIdentityProvider.getRealmsFromDb(persistenceService);
  }

  @Override
  public Realm getRealm(String name) {
    // This gets hit a lot for event authorization so caching added
    try {
      return realmCache.get(
          name,
          () -> {
            try {
              Realm realm = ManagerIdentityProvider.getRealmFromDb(persistenceService, name);
              // Filter out built in roles
              realm.setRealmRoles(
                  realm.getRealmRoles().stream()
                      .filter(
                          rr ->
                              (MASTER_REALM.equals(name)
                                      && SUPER_USER_REALM_ROLE.equals(rr.getName()))
                                  || !isBuiltInRealmRole(rr.getName()))
                      .collect(Collectors.toSet()));
              return realm;
            } catch (Exception ex) {
              LOG.log(Level.INFO, "Failed to get realm by name: " + name, ex);
            }
            return null;
          });
    } catch (CacheLoader.InvalidCacheLoadException ignored) {
    } catch (Exception e) {
      LOG.log(Level.INFO, "Failed to get realm by name: " + name, e);
    }
    return null;
  }

  @Override
  public void updateRealm(Realm realm) throws ClientErrorException {
    LOG.fine("Update realm: " + realm);
    getRealms(
        realmsResource -> {
          if (TextUtil.isNullOrEmpty(realm.getId())) {
            throw new IllegalStateException(
                "Realm must already exist, ID does not match an existing realm");
          }

          RealmResource realmResource = realmsResource.realm(realm.getName());
          RealmRepresentation realmRepresentation = realmResource.toRepresentation();
          Set<RoleRepresentation> existingRealmRoles =
              realmResource.roles().list().stream()
                  .filter(rr -> !isBuiltInRealmRole(rr.getName()))
                  .collect(Collectors.toSet());

          if (realmRepresentation == null) {
            throw new IllegalStateException("Realm does not exist: " + realm.getName());
          }

          Realm existingRealm = convert(realmRepresentation, Realm.class);
          // Inject name as it is called realm in the realmRepresentation
          existingRealm.setName(realmRepresentation.getRealm());
          // Inject roles as don't exist in realmRepresentation
          existingRealm.setRealmRoles(
              existingRealmRoles.stream()
                  .map(err -> new RealmRole(err.getId(), err.getName(), err.getDescription()))
                  .collect(Collectors.toSet()));

          // Realm only has a subset of realm representation so overlay on actual realm
          // representation
          realmRepresentation.setDisplayName(realm.getDisplayName());
          realmRepresentation.setAccountTheme(realm.getAccountTheme());
          realmRepresentation.setAdminTheme(realm.getAdminTheme());
          realmRepresentation.setEmailTheme(realm.getEmailTheme());
          realmRepresentation.setLoginTheme(realm.getLoginTheme());
          realmRepresentation.setRememberMe(realm.getRememberMe());
          realmRepresentation.setVerifyEmail(realm.getVerifyEmail());
          realmRepresentation.setLoginWithEmailAllowed(realm.getLoginWithEmail());
          realmRepresentation.setRegistrationAllowed(realm.getRegistrationAllowed());
          realmRepresentation.setRegistrationEmailAsUsername(
              realm.getRegistrationEmailAsUsername());
          realmRepresentation.setEnabled(realm.getEnabled());
          realmRepresentation.setDuplicateEmailsAllowed(realm.getDuplicateEmailsAllowed());
          realmRepresentation.setResetPasswordAllowed(realm.getResetPasswordAllowed());
          realmRepresentation.setPasswordPolicy(realm.getPasswordPolicyString());
          realmRepresentation.setNotBefore(
              realm.getNotBefore() != null ? realm.getNotBefore().intValue() : null);
          configureRealm(realmRepresentation, realmResource);
          realmResource.update(realmRepresentation);

          realmCache.invalidate(realm.getName());

          Set<RealmRole> realmRoles =
              (realm.getRealmRoles() != null ? realm.getRealmRoles() : new HashSet<RealmRole>())
                  .stream()
                      .filter(rr -> !isBuiltInRealmRole(rr.getName()))
                      .collect(Collectors.toSet());
          realmRoles = new HashSet<>(realmRoles);
          realmRoles.addAll(Realm.DEFAULT_REALM_ROLES);

          // Remove any obsolete roles
          Set<RealmRole> finalRealmRoles = realmRoles;
          existingRealmRoles.forEach(
              existingRealmRole -> {
                if (finalRealmRoles.stream()
                    .noneMatch(
                        realmRole -> realmRole.getName().equals(existingRealmRole.getName()))) {
                  realmResource.roles().deleteRole(existingRealmRole.getName());
                }
              });

          // Create new roles
          realmRoles.forEach(
              realmRole -> {
                if (existingRealmRoles.stream()
                    .noneMatch(
                        existingRealmRole ->
                            existingRealmRole.getName().equals(realmRole.getName()))) {
                  realmResource
                      .roles()
                      .create(
                          new RoleRepresentation(
                              realmRole.getName(), realmRole.getDescription(), false));
                }
              });

          Realm updatedRealm = convert(realmRepresentation, Realm.class);
          // Inject name as it is called realm in the realmRepresentation
          updatedRealm.setName(realmRepresentation.getRealm());
          // Inject roles as don't exist in realmRepresentation
          updatedRealm.setRealmRoles(realmRoles);
          persistenceService.publishPersistenceEvent(
              PersistenceEvent.Cause.UPDATE, updatedRealm, existingRealm, Realm.class, null, null);
          return null;
        });
  }

  @Override
  public Realm createRealm(Realm realm) throws ClientErrorException {
    LOG.fine("Create realm: " + realm);
    return getRealms(
        realmsResource -> {
          RealmRepresentation realmRepresentation = convert(realm, RealmRepresentation.class);
          // Inject name as it is called realm in the realmRepresentation
          realmRepresentation.setRealm(realm.getName());

          try {
            realmsResource.create(realmRepresentation);
            RealmResource realmResource = realmsResource.realm(realm.getName());
            realmRepresentation = realmResource.toRepresentation();

            // Need a committed realmRepresentation to update the security
            configureRealm(realmRepresentation, realmResource);
            realmResource.update(realmRepresentation);

            // Create realm roles inserting
            Set<RealmRole> realmRoles =
                (realm.getRealmRoles() != null ? realm.getRealmRoles() : new HashSet<RealmRole>())
                    .stream()
                        .filter(rr -> !isBuiltInRealmRole(rr.getName()))
                        .collect(Collectors.toSet());
            realmRoles = new HashSet<>(realmRoles);
            realmRoles.addAll(Realm.DEFAULT_REALM_ROLES);
            realmRoles.forEach(
                realmRole -> {
                  LOG.finest("Adding realm role + " + realmRole);
                  realmResource
                      .roles()
                      .create(
                          new RoleRepresentation(
                              realmRole.getName(), realmRole.getDescription(), false));
                });

            // Since Keycloak 26.7.0 Verify Profile is enabled for new realms but not the master
            // realm
            RequiredActionProviderRepresentation requiredActionConfigRepresentation =
                realmResource.flows().getRequiredAction("VERIFY_PROFILE");
            requiredActionConfigRepresentation.setEnabled(false);
            realmResource
                .flows()
                .updateRequiredAction("VERIFY_PROFILE", requiredActionConfigRepresentation);

            // Auto create the standard openremote client
            ClientRepresentation clientRepresentation = generateOpenRemoteClientRepresentation();
            createUpdateClient(realm.getName(), clientRepresentation);

            Realm createdRealm = convert(realmRepresentation, Realm.class);
            // Inject name as it is called realm in the realmRepresentation
            createdRealm.setName(realmRepresentation.getRealm());
            // Inject roles as don't exist in realmRepresentation
            createdRealm.setRealmRoles(realm.getRealmRoles());
            persistenceService.publishPersistenceEvent(
                PersistenceEvent.Cause.CREATE, realm, null, Realm.class, null, null);
            return createdRealm;
          } catch (Exception e) {
            LOG.log(Level.INFO, "Failed to create realm: " + realm, e);
            throw e;
          }
        });
  }

  @Override
  public void deleteRealm(String realmName) throws ClientErrorException {
    Realm realm = getRealm(realmName);

    if (realm == null) {
      throw new NotFoundException("Realm does not exist: " + realmName);
    }

    int assetCount =
        assetStorageService.count(
            new AssetQuery().realm(new RealmPredicate(realmName)).includeDeletePending(true));

    if (assetCount > 0) {
      throw new IllegalStateException(
          "Cannot delete realm '" + realmName + "' because it still contains assets");
    }

    realmCache.invalidate(realmName);

    persistenceService.doTransaction(
        entityManager -> {

          // Delete gateway connections
          Query query =
              entityManager.createQuery(
                  "delete from "
                      + GatewayConnection.class.getSimpleName()
                      + " gc "
                      + "where gc.localRealm = ?1");

          query.setParameter(1, realmName);
          query.executeUpdate();

          // Delete provisioning configs
          query =
              entityManager.createQuery(
                  "delete from "
                      + ProvisioningConfig.class.getSimpleName()
                      + " pc "
                      + "where pc.realm = ?1");

          query.setParameter(1, realmName);
          query.executeUpdate();

          // Delete Rules
          query =
              entityManager.createQuery(
                  "delete from "
                      + RealmRuleset.class.getSimpleName()
                      + " rs "
                      + "where rs.realm = ?1");
          query.setParameter(1, realmName);
          query.executeUpdate();
        });

    LOG.fine("Deleting realm: " + realmName);
    getRealms(
        realmsResource -> {
          realmsResource.realm(realmName).remove();
          return null;
        });
    persistenceService.publishPersistenceEvent(
        PersistenceEvent.Cause.DELETE, null, realm, Realm.class, null, null);
  }

  public ClientRepresentation generateOpenRemoteClientRepresentation() {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(KEYCLOAK_CLIENT_ID);
    client.setName("OpenRemote");
    client.setPublicClient(true);

    boolean enableDirectAccessGrant =
        getBoolean(
            container.getConfig(), OR_KEYCLOAK_ENABLE_DIRECT_ACCESS_GRANT, container.isDevMode());

    if (enableDirectAccessGrant) {
      // We need direct access for integration/load tests
      LOG.info(
          "### Allowing direct access grants for client id '"
              + client.getClientId()
              + "', this must NOT be used in production! ###");
      client.setDirectAccessGrantsEnabled(true);
    }

    if (container.isDevMode()) {
      // Allow any web origin (this will add CORS headers to token requests etc.)
      client.setWebOrigins(Collections.singletonList("*"));
      client.setRedirectUris(Collections.singletonList("*"));
    } else {
      client.setWebOrigins(Collections.singletonList("+"));
      client.setRedirectUris(validRedirectUris);
    }

    return client;
  }

  // TODO: Provide an implementation agnostic client
  public ClientRepresentation getClient(String realm, String client) throws ClientErrorException {
    return getRealms(
        realmsResource ->
            withClientResource(
                realm,
                client,
                realmsResource,
                (clientRepresentation, clientResource) -> clientRepresentation,
                null));
  }

  // TODO: Provide an implementation agnostic client
  public ClientRepresentation[] getClients(String realm) throws ClientErrorException {
    return getRealms(
        realmsResource ->
            realmsResource.realm(realm).clients().findAll().toArray(new ClientRepresentation[0]));
  }

  // TODO: Provide an implementation agnostic client
  public ClientRepresentation createUpdateClient(String realm, ClientRepresentation client)
      throws ClientErrorException {

    if (client == null || client.getClientId() == null) {
      throw new IllegalArgumentException("Client is null or clientId is missing");
    }

    return getRealms(
        realmsResource ->
            withClientResource(
                realm,
                client.getClientId(),
                realmsResource,
                (clientRepresentation, clientResource) -> {
                  clientResource.update(client);
                  return client;
                },
                () -> {
                  ClientsResource clientsResource = realmsResource.realm(realm).clients();
                  Response response = clientsResource.create(client);
                  response.close();
                  if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                    LOG.fine(
                        "Failed to create client response="
                            + response.getStatusInfo().getStatusCode()
                            + ": "
                            + client);
                    return null;
                  }

                  ClientRepresentation newClient =
                      clientsResource.findByClientId(client.getClientId()).getFirst();
                  ClientResource clientResource = clientsResource.get(newClient.getId());
                  addDefaultRoles(clientResource.roles());
                  return newClient;
                }));
  }

  /**
   * Imports the identity provider configuration using the given import data. This simplifies
   * configuring an OIDC or SAML identity provider from which some of the configuration parameters
   * can be imported from the identity provider itself.
   *
   * @param realm the realm used for importing the identity provider configuration.
   * @param importData contains the details required for importing the identity provider
   *     configuration. E.g. the following values can be used:
   *     <ul>
   *       <li>{@code fromUrl}: The URL used for importing the configuration parameters
   *       <li>{@code providerId}: The identity provider ({@code oidc} or {@code saml}) to import
   *           the configuration for.
   *     </ul>
   *
   * @return the imported configuration parameters which can be added to the {@link Map} provided to
   *     {@link #createUpdateIdentityProvider(String, String, String, String, Map)} when creating or
   *     updating an identity provider.
   */
  public Map<String, String> getIdentityProviderImportConfig(
      String realm, Map<String, Object> importData) throws ClientErrorException {
    if (importData == null || importData.isEmpty()) {
      throw new IllegalArgumentException("Import data is null or empty");
    }

    return getRealms(
        realmsResource -> realmsResource.realm(realm).identityProviders().importFrom(importData));
  }

  public List<IdentityProviderRepresentation> getIdentityProviders(String realm)
      throws ClientErrorException {
    return getRealms(
        realmsResource -> {
          IdentityProvidersResource identityProvidersResource =
              realmsResource.realm(realm).identityProviders();
          return identityProvidersResource.findAll();
        });
  }

  public void deleteIdentityProvider(String realm, String alias) throws ClientErrorException {
    getRealms(
        realmsResource -> {
          IdentityProvidersResource identityProvidersResource =
              realmsResource.realm(realm).identityProviders();
          identityProvidersResource.get(alias).remove();
          return null;
        });
  }

  public void createUpdateIdentityProvider(
      String realm, String alias, String providerId, String displayName, Map<String, String> config)
      throws ClientErrorException {
    IdentityProviderRepresentation representation = new IdentityProviderRepresentation();
    representation.setAlias(alias);
    representation.setProviderId(providerId);
    representation.setDisplayName(displayName);
    representation.setConfig(config);

    getRealms(
        realmsResource -> {
          IdentityProvidersResource identityProvidersResource =
              realmsResource.realm(realm).identityProviders();

          if (identityProvidersResource.findAll().stream()
              .anyMatch(ipr -> alias.equals(ipr.getAlias()))) {
            identityProvidersResource.get(alias).update(representation);
          } else {
            try (Response response = identityProvidersResource.create(representation)) {
              if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new IllegalStateException(
                    "Failed to create identity provider response="
                        + response.getStatusInfo().getStatusCode()
                        + ": "
                        + representation);
              }
            }
          }

          return identityProvidersResource.get(alias).toRepresentation();
        });
  }

  public void createUpdateIdentityProviderMapper(
      String realm,
      String idpAlias,
      String mapperName,
      String idpMapper,
      Map<String, String> mapperConfig)
      throws ClientErrorException {
    getRealms(
        realmsResource -> {
          IdentityProviderResource idpResource =
              realmsResource.realm(realm).identityProviders().get(idpAlias);

          IdentityProviderMapperRepresentation mapper = new IdentityProviderMapperRepresentation();
          mapper.setName(mapperName);
          mapper.setConfig(mapperConfig);
          mapper.setIdentityProviderAlias(idpAlias);
          mapper.setIdentityProviderMapper(idpMapper);

          Optional<IdentityProviderMapperRepresentation> existingMapper =
              idpResource.getMappers().stream()
                  .filter(m -> mapperName.equals(m.getName()))
                  .findFirst();
          if (existingMapper.isPresent()) {
            mapper.setId(existingMapper.get().getId());
            idpResource.update(existingMapper.get().getId(), mapper);
          } else {
            try (Response response = idpResource.addMapper(mapper)) {
              if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                throw new IllegalStateException(
                    "Failed to create identity provider mapper '"
                        + mapperName
                        + "' response="
                        + response.getStatusInfo().getStatusCode()
                        + ": "
                        + response.getStatusInfo().getReasonPhrase());
              }
            }
          }

          return null;
        });
  }

  public void addAuthenticationExecutionConfig(
      String realm,
      String flowAlias,
      String executionProviderId,
      String authenticatorAlias,
      Map<String, String> authenticatorConfig)
      throws ClientErrorException {
    getRealms(
        realmsResource -> {
          AuthenticationManagementResource authenticationManagementResource =
              realmsResource.realm(realm).flows();
          List<AuthenticationExecutionInfoRepresentation> executions =
              authenticationManagementResource.getExecutions(flowAlias);
          for (AuthenticationExecutionInfoRepresentation execution : executions) {
            if (executionProviderId.equals(execution.getProviderId())) {
              AuthenticatorConfigRepresentation config = new AuthenticatorConfigRepresentation();
              config.setAlias(authenticatorAlias);
              config.setConfig(authenticatorConfig);

              try (Response response =
                  authenticationManagementResource.newExecutionConfig(execution.getId(), config)) {
                if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
                  throw new IllegalStateException(
                      "Failed to create execution config '"
                          + authenticatorAlias
                          + "' response="
                          + response.getStatusInfo().getStatusCode()
                          + ": "
                          + response.getStatusInfo().getReasonPhrase());
                }
              }
              return null;
            }
          }
          return null;
        });
  }

  public void deleteClient(String realm, String clientId) throws ClientErrorException {

    if (TextUtil.isNullOrEmpty(realm) || TextUtil.isNullOrEmpty(clientId)) {
      throw new IllegalArgumentException(
          "Invalid client credentials realm and client ID must be specified");
    }

    getRealms(
        realmsResource -> {
          LOG.fine("Deleting client: realm=" + realm + ", client ID=" + clientId);
          return withClientResource(
              realm,
              clientId,
              realmsResource,
              (clientRepresentation, clientResource) -> {
                clientResource.remove();
                return null;
              },
              () -> {
                // Do nothing as could have been deleted by cleanup of previous test
                return null;
              });
        });
  }

  /**
   * @return <code>true</code> if the user is the superuser (admin) or if the user is authenticated
   *     in the same realm as the realm and the realm is active.
   */
  @Override
  public boolean isRealmActiveAndAccessible(AuthContext authContext, Realm realm) {
    if (realm == null) {
      return false;
    }

    boolean isSuperUser = authContext != null && authContext.isSuperUser();
    boolean isUsersRealm =
        isSuperUser || authContext == null || authContext.isRealmAccessibleByUser(realm.getName());

    return isSuperUser || (isUsersRealm && realm.isActive(timerService.getCurrentTimeMillis()));
  }

  /**
   * @return <code>true</code> if the user is the superuser (admin) or if the user is authenticated
   *     in the same realm and the realm is active.
   */
  @Override
  public boolean isRealmActiveAndAccessible(AuthContext authContext, String realm) {
    return isRealmActiveAndAccessible(authContext, getRealm(realm));
  }

  @Override
  public boolean realmExists(String realm) {
    return ManagerIdentityProvider.realmExistsFromDb(persistenceService, realm);
  }

  @Override
  public boolean isRestrictedUser(AuthContext authContext) {
    return authContext != null && authContext.isRestrictedUser();
  }

  @Override
  public boolean isUserInRealm(String userId, String realm) {
    return ManagerIdentityProvider.userInRealmFromDb(persistenceService, userId, realm);
  }

  @Override
  public String getFrontendURI() {
    return frontendURI;
  }

  protected void configureRealm(
      RealmRepresentation realmRepresentation, RealmResource realmResource) {

    realmRepresentation.setAccessTokenLifespan(Constants.ACCESS_TOKEN_LIFESPAN_SECONDS);

    String themeName =
        getString(
            container.getConfig(),
            realmRepresentation.getRealm().toUpperCase(Locale.ROOT) + REALM_KEYCLOAK_THEME_SUFFIX,
            getString(
                container.getConfig(),
                DEFAULT_REALM_KEYCLOAK_THEME,
                DEFAULT_REALM_KEYCLOAK_THEME_DEFAULT));

    if (TextUtil.isNullOrEmpty(realmRepresentation.getLoginTheme())) {
      realmRepresentation.setLoginTheme(themeName);
    }
    if (TextUtil.isNullOrEmpty(realmRepresentation.getAccountTheme())) {
      realmRepresentation.setAccountTheme(themeName);
    }
    if (TextUtil.isNullOrEmpty(realmRepresentation.getEmailTheme())) {
      realmRepresentation.setEmailTheme(themeName);
    }

    realmRepresentation.setDisplayNameHtml(
        realmRepresentation.getDisplayName().replaceAll("[^A-Za-z0-9]", ""));
    realmRepresentation.setSsoSessionIdleTimeout(sessionTimeoutSeconds);
    realmRepresentation.setSsoSessionMaxLifespan(sessionMaxSeconds);
    realmRepresentation.setOfflineSessionIdleTimeout(sessionOfflineTimeoutSeconds);

    // Service-internal network (between manager and keycloak service containers) does not use SSL
    realmRepresentation.setSslRequired(SslRequired.NONE.toString());

    // Internationalization must be enabled for locale attribute to be persisted
    realmRepresentation.setInternationalizationEnabled(true);

    // Since Keycloak 26.7.0 Unmanaged user attributes are disabled for all realms
    UserProfileResource userProfileResource = realmResource.users().userProfile();
    UPConfig upConfig = userProfileResource.getConfiguration();
    upConfig.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ADMIN_EDIT);
    userProfileResource.update(upConfig);

    // Configure SMTP
    String host = container.getConfig().getOrDefault(OR_EMAIL_HOST, null);
    if (!TextUtil.isNullOrEmpty(host)
        && (realmRepresentation.getSmtpServer() == null
            || realmRepresentation.getSmtpServer().isEmpty())) {
      LOG.info("Configuring Keycloak SMTP settings for realm: " + realmRepresentation.getRealm());
      Map<String, String> emailConfig = new HashMap<>();
      emailConfig.put("host", host);
      emailConfig.put(
          "port",
          container
              .getConfig()
              .getOrDefault(OR_EMAIL_PORT, Integer.toString(OR_EMAIL_PORT_DEFAULT)));
      emailConfig.put("user", container.getConfig().getOrDefault(OR_EMAIL_USER, null));
      emailConfig.put("password", container.getConfig().getOrDefault(OR_EMAIL_PASSWORD, null));
      emailConfig.put("auth", container.getConfig().containsKey(OR_EMAIL_USER) ? "true" : "false");
      emailConfig.put(
          "starttls",
          Boolean.toString(getBoolean(container.getConfig(), OR_EMAIL_TLS, OR_EMAIL_TLS_DEFAULT)));
      emailConfig.put(
          "ssl",
          Boolean.toString(
              !getBoolean(container.getConfig(), OR_EMAIL_TLS, OR_EMAIL_TLS_DEFAULT)
                  && getString(container.getConfig(), OR_EMAIL_PROTOCOL, OR_EMAIL_PROTOCOL_DEFAULT)
                      .equals("smtps")));
      emailConfig.put(
          "from", getString(container.getConfig(), OR_EMAIL_FROM, OR_EMAIL_FROM_DEFAULT));
      realmRepresentation.setSmtpServer(emailConfig);
    }

    // Configure CSP header
    Map<String, String> headers = realmRepresentation.getBrowserSecurityHeaders();
    if (headers == null) {
      headers = new HashMap<>();
      realmRepresentation.setBrowserSecurityHeaders(headers);
    }

    if (container.isDevMode()) {
      headers.computeIfPresent(
          "contentSecurityPolicy",
          (hdrName, hdrValue) -> "frame-src *; frame-ancestors *; object-src 'none'");
    } else {
      String allowedOriginsStr = String.join(" ", CORSConfig.getCorsAllowedOriginsFromConfig());
      if (!TextUtil.isNullOrEmpty(allowedOriginsStr)) {
        headers.compute(
            "contentSecurityPolicy",
            (hdrName, hdrValue) ->
                "frame-src 'self' "
                    + allowedOriginsStr.replace(';', ' ')
                    + "; frame-ancestors 'self' "
                    + allowedOriginsStr.replace(';', ' ')
                    + "; object-src 'none'");
      }
    }
  }

  protected void addDefaultRoles(RolesResource rolesResource) {

    for (ClientRole clientRole : ClientRole.values()) {
      rolesResource.create(clientRole.getRepresentation());
    }

    for (ClientRole clientRole : ClientRole.values()) {
      if (clientRole.getComposites() == null) continue;
      List<RoleRepresentation> composites = new ArrayList<>();
      for (ClientRole composite : clientRole.getComposites()) {
        composites.add(rolesResource.get(composite.getValue()).toRepresentation());
      }
      rolesResource.get(clientRole.getValue()).addComposites(composites);
    }
  }

  public String addLDAPConfiguration(String realm, ComponentRepresentation componentRepresentation)
      throws ClientErrorException {

    return getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          Response response = realmResource.components().add(componentRepresentation);
          response.close();

          if (!response.getStatusInfo().equals(Response.Status.CREATED)) {
            throw new IllegalStateException("Failed to add LDAP configuration");
          } else {
            ComponentRepresentation newComponentRepresentation =
                realmResource
                    .components()
                    .query(
                        componentRepresentation.getParentId(),
                        componentRepresentation.getProviderType(),
                        componentRepresentation.getName())
                    .getFirst();
            syncUsers(newComponentRepresentation.getId(), realm, "triggerFullSync");
            return newComponentRepresentation.getId();
          }
        });
  }

  public String addLDAPMapper(String realm, ComponentRepresentation componentRepresentation)
      throws ClientErrorException {
    return getRealms(
        realmsResource -> {
          RealmResource realmResource = realmsResource.realm(realm);
          Response response = realmResource.components().add(componentRepresentation);
          response.close();

          if (!response.getStatusInfo().equals(Response.Status.CREATED)) {
            throw new IllegalStateException("Failed to add LDAP mapper");
          } else {
            ComponentRepresentation newComponentRepresentation =
                realmResource
                    .components()
                    .query(
                        componentRepresentation.getParentId(),
                        componentRepresentation.getProviderType(),
                        componentRepresentation.getName())
                    .getFirst();
            realmResource
                .userStorage()
                .syncMapperData(
                    newComponentRepresentation.getParentId(),
                    newComponentRepresentation.getId(),
                    "fedToKeycloak");
            return newComponentRepresentation.getId();
          }
        });
  }

  public static boolean isBuiltInRealmRole(String realmRole) {
    return realmRole.startsWith("default-roles-") || BUILT_IN_REALM_ROLES.contains(realmRole);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{}";
  }
}
