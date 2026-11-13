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
import { normaliseConfig, Util } from "@openremote/core";
import { Auth, type ManagerConfig } from "@openremote/model";

// Declare MANAGER_URL and KEYCLOAK_URL - Global var injected by webpack
declare const MANAGER_URL: string | undefined;
declare const KEYCLOAK_URL: string | undefined;

const DEFAULT_MANAGER_CONFIG: ManagerConfig = {
  managerUrl: MANAGER_URL || "",
  keycloakUrl: KEYCLOAK_URL || "",
  auth: Auth.KEYCLOAK,
  autoLogin: true,
  realm: undefined,
  consoleAutoEnable: true,
  loadTranslations: ["or"],
};

export function getDefaultManagerConfig() {
  return normaliseConfig(DEFAULT_MANAGER_CONFIG);
}

/** The config `OrApp` starts the manager with, which an app can also sign in with before it loads `OrApp` */
export function getManagerConfig(managerConfig?: ManagerConfig): ManagerConfig {
  const config: ManagerConfig = { ...DEFAULT_MANAGER_CONFIG, ...managerConfig };
  if (!config.realm) {
    // Use realm query parameter if no specific realm provided
    config.realm = Util.getQueryParameter("realm");
  }
  if (!config.defaultLanguage) {
    config.defaultLanguage = Util.getBrowserLanguage();
  }
  return config;
}
