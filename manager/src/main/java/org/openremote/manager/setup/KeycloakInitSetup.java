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

import static org.openremote.model.Constants.*;

import java.util.logging.Logger;
import org.openremote.model.Container;
import org.openremote.model.security.ClientRole;
import org.openremote.model.security.Realm;
import org.openremote.model.security.User;

public class KeycloakInitSetup extends AbstractKeycloakSetup {

  private static final Logger LOG = Logger.getLogger(KeycloakInitSetup.class.getName());

  public KeycloakInitSetup(Container container) {
    super(container);
  }

  @Override
  public void onStart() throws Exception {
    super.onStart();

    // Update the master realm which is auto created by Keycloak itself
    // This will cause the keycloak provider to configure it appropriately
    // e.g. Set SMTP server, theme, timeouts, etc.
    Realm masterRealm = keycloakProvider.getRealm(MASTER_REALM);
    masterRealm.setDisplayName("Master");
    masterRealm.setRealmRoles(null); // Clear out any existing custom realm roles
    keycloakProvider.updateRealm(masterRealm);

    // Create our client application with its default roles in the master realm
    if (keycloakProvider.createUpdateClient(
            masterRealm.getName(), keycloakProvider.generateOpenRemoteClientRepresentation())
        == null) {
      throw new IllegalStateException("Could not create the master OpenRemote client");
    }
    // Flyway runs before this client exists on a clean install. Apply the same token mapper
    // configuration after creation to keep admin authorization while limiting token growth.
    keycloakProvider.getRealms(
        realms -> {
          MasterClientTokenMappers.configure(realms.realm(MASTER_REALM));
          return null;
        });

    // Update master user name
    User adminUser = keycloakProvider.getUserByUsername(MASTER_REALM, MASTER_REALM_ADMIN_USER);
    adminUser.setFirstName("System");
    adminUser.setLastName("Administrator");
    keycloakProvider.createUpdateUser(MASTER_REALM, adminUser, null, true);

    // Give admin all roles on application client level
    keycloakProvider.updateUserClientRoles(
        MASTER_REALM,
        adminUser.getId(),
        KEYCLOAK_CLIENT_ID,
        ClientRole.READ.getValue(),
        ClientRole.WRITE.getValue());

    keycloakProvider.onSetupDone(container);
  }
}
