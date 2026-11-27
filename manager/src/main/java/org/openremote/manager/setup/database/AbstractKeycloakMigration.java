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
package org.openremote.manager.setup.database;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.UriBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.keycloak.admin.client.Keycloak;
import org.openremote.container.persistence.PersistenceService;
import org.openremote.container.security.IdentityProvider;
import org.openremote.container.security.IdentityService;
import org.openremote.container.security.keycloak.KeycloakIdentityProvider;
import org.openremote.manager.security.ManagerKeycloakIdentityProvider;
import org.openremote.model.Constants;
import org.openremote.model.auth.OAuthGrant;
import org.openremote.model.auth.OAuthPasswordGrant;
import org.openremote.model.util.Config;
import org.openremote.model.util.ValueUtil;

/**
 * Base class for Flyway Java migrations that need to interact with the Keycloak Admin API. Provides
 * shared credential resolution ({@link #resolveCredentials()}), a pre-authenticated {@link
 * Keycloak} client ({@link #openKeycloak()}), and the identity-provider guard ({@link
 * #isKeycloakDeployment()}).
 *
 * <p>All subclasses talk to Keycloak over HTTP rather than through the migration's DB connection,
 * so {@link #canExecuteInTransaction()} returns {@code false}.
 *
 * <p>Subclasses must stay Java migrations and not be converted to {@code .sql} files: Flyway
 * records a {@code BaseJavaMigration} as type JDBC with a null checksum, whereas a {@code .sql}
 * file for the same version is type SQL with a real checksum, so converting one would fail
 * validation on any database that has already applied that version. The bodies may be edited freely
 * — Java migrations have no checksum.
 */
public abstract class AbstractKeycloakMigration extends BaseJavaMigration {

  protected final Logger LOG = Logger.getLogger(getClass().getName());

  protected boolean isKeycloakDeployment() {
    String identityProvider =
        Config.getString(
            IdentityService.OR_IDENTITY_PROVIDER, IdentityService.OR_IDENTITY_PROVIDER_DEFAULT);
    return IdentityService.OR_IDENTITY_PROVIDER_DEFAULT.equals(identityProvider);
  }

  protected Keycloak openKeycloak() {
    OAuthPasswordGrant credentials = resolveCredentials();
    Keycloak keycloak =
        Keycloak.getInstance(
            buildKeycloakUrl(),
            Constants.MASTER_REALM,
            credentials.getUsername(),
            credentials.getPassword(),
            KeycloakIdentityProvider.ADMIN_CLI_CLIENT_ID);

    try {
      keycloak.tokenManager().grantToken();
    } catch (WebApplicationException e) {
      String msg = "Unknown error";
      if (e.getResponse().hasEntity()) {
        msg = e.getResponse().readEntity(String.class);
        e.getResponse().close();
      }
      LOG.warning("Failed to connect to Keycloak: error=" + msg);
      keycloak.close();

      // Falling back to admin credentials
      LOG.warning("Falling back to admin credentials");
      credentials = loadAdminFallbackCredentials();

      keycloak =
          Keycloak.getInstance(
              buildKeycloakUrl(),
              Constants.MASTER_REALM,
              credentials.getUsername(),
              credentials.getPassword(),
              KeycloakIdentityProvider.ADMIN_CLI_CLIENT_ID);
    }

    return keycloak;
  }

  /**
   * Resolves the credentials used to authenticate against Keycloak: prefers the stored manager
   * grant written by the identity provider on first startup, falls back to {@code
   * OR_ADMIN_PASSWORD} (or the default admin password when that env var is unset), so a clean
   * install can still complete the migration.
   */
  protected OAuthPasswordGrant resolveCredentials() {
    OAuthPasswordGrant stored = loadStoredCredentials();
    return stored != null ? stored : loadAdminFallbackCredentials();
  }

  private OAuthPasswordGrant loadStoredCredentials() {
    String storageDir =
        Config.getString(
            PersistenceService.OR_STORAGE_DIR, PersistenceService.OR_STORAGE_DIR_DEFAULT);
    String grantFile =
        Config.getString(
            ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE,
            ManagerKeycloakIdentityProvider.OR_KEYCLOAK_GRANT_FILE_DEFAULT);

    if (grantFile == null || grantFile.isBlank()) {
      return null;
    }

    Path grantPath = Paths.get(storageDir).resolve(grantFile);
    if (!Files.isReadable(grantPath)) {
      return null;
    }

    try {
      String grantJson = Files.readString(grantPath, StandardCharsets.UTF_8);
      OAuthGrant grant = ValueUtil.parse(grantJson, OAuthGrant.class).orElse(null);
      if (grant instanceof OAuthPasswordGrant passwordGrant) {
        LOG.info("Loaded stored keycloak credentials from: " + grantPath);
        return passwordGrant;
      }
      LOG.warning(
          "Stored keycloak credentials at " + grantPath + " are not a password grant; skipping");
    } catch (Exception ex) {
      LOG.warning(
          "Failed to read stored keycloak credentials at " + grantPath + ": " + ex.getMessage());
    }
    return null;
  }

  private OAuthPasswordGrant loadAdminFallbackCredentials() {
    String adminPassword = Config.getString(IdentityProvider.OR_ADMIN_PASSWORD, null);
    if (adminPassword == null) {
      adminPassword = IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT;
      LOG.warning(
          "Stored keycloak credentials not available and "
              + IdentityProvider.OR_ADMIN_PASSWORD
              + " is not set; using the default admin password for Keycloak migration");
    } else {
      LOG.warning(
          "Stored keycloak credentials not available; falling back to "
              + IdentityProvider.OR_ADMIN_PASSWORD
              + " for Keycloak migration");
    }
    return new OAuthPasswordGrant(
        null,
        KeycloakIdentityProvider.ADMIN_CLI_CLIENT_ID,
        null,
        "openid",
        Constants.MASTER_REALM_ADMIN_USER,
        adminPassword);
  }

  protected String buildKeycloakUrl() {
    UriBuilder uriBuilder =
        UriBuilder.fromPath("/")
            .scheme("http")
            .host(
                Config.getString(
                    KeycloakIdentityProvider.OR_KEYCLOAK_HOST,
                    KeycloakIdentityProvider.OR_KEYCLOAK_HOST_DEFAULT))
            .port(
                Config.getInt(
                    KeycloakIdentityProvider.OR_KEYCLOAK_PORT,
                    KeycloakIdentityProvider.OR_KEYCLOAK_PORT_DEFAULT));

    String path =
        Config.getString(
            KeycloakIdentityProvider.OR_KEYCLOAK_PATH,
            KeycloakIdentityProvider.OR_KEYCLOAK_PATH_DEFAULT);
    if (path != null && !path.isBlank()) {
      uriBuilder.path(path);
    }

    return uriBuilder.build().toString();
  }

  @Override
  public boolean canExecuteInTransaction() {
    return false;
  }
}
