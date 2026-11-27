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
package org.openremote.manager.setup.database;

import static org.openremote.model.Constants.MASTER_REALM;

import org.flywaydb.core.api.migration.Context;
import org.openremote.manager.setup.MasterClientTokenMappers;

/**
 * Updates the existing {@code master/openremote} client through the Keycloak Admin API so its
 * access tokens preserve OpenRemote's authorization claims without including per-realm Keycloak
 * management roles and audiences.
 *
 * <p>On a clean installation, the client may not exist when Flyway runs. {@link
 * org.openremote.manager.setup.KeycloakInitSetup} applies the same mapper configuration after
 * creating the client.
 */
public class V20261120_1540__RestrictMasterClientTokenClaims extends AbstractKeycloakMigration {

  @Override
  public void migrate(Context context) {
    if (!isKeycloakDeployment()) {
      return;
    }

    try (var keycloak = openKeycloak()) {
      MasterClientTokenMappers.configure(keycloak.realm(MASTER_REALM));
    }
  }
}
