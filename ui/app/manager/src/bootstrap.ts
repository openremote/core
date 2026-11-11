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
import manager, { ORError, Util } from "@openremote/core";
import type { ManagerAppConfig } from "@openremote/model";
import { getManagerConfig } from "@openremote/or-app/config";
import { OrAppLoading } from "@openremote/or-app/or-app-loading";

declare let MANAGER_URL: string | undefined;

// Apps are served from a folder below the manager, e.g. https://host/prefix/manager/ for a manager at https://host/prefix
const managerURL = MANAGER_URL || window.location.origin + window.location.pathname.replace(/\/[^/]+\/?$/, "");
const configURL = managerURL + "/api/master/configuration/manager";

async function fetchAppConfig(): Promise<ManagerAppConfig> {
  const result = await fetch(configURL);

  // A reverse proxy answers with a 5xx while the manager restarts, any other status means there is no config
  if (result.status >= 500) {
    throw new Error("The manager app config request failed with status " + result.status);
  }

  const appConfig: ManagerAppConfig = { ...(result.status === 200 ? await result.json() : undefined) };

  // Set locales and load path
  if (!appConfig.manager) {
    appConfig.manager = {};
  }

  if (appConfig.loadLocales) {
    appConfig.manager.loadTranslations = ["app", "or"];

    if (!appConfig.manager.translationsLoadPath) {
      appConfig.manager.translationsLoadPath = "/locales/{{lng}}/{{ns}}.json";
    }
  }

  return appConfig;
}

// Signs in before loading the app, so the redirect to Keycloak and back loads this small bundle twice instead of the
// whole app. Importing anything from the app here would undo that.
async function start() {
  // Shown until the app takes over
  const loading = new OrAppLoading();
  document.body.appendChild(loading);

  let appConfig: ManagerAppConfig;
  try {
    appConfig = await Util.retryWithBackoff(fetchAppConfig);
  } catch (e) {
    console.error("Failed to load the manager app config", e);
    loading.failed = true;
    return;
  }

  if (!(await manager.signIn(getManagerConfig(appConfig.manager)))) {
    if (!manager.error) {
      // On its way to the login page
      return;
    }
    if (manager.error === ORError.MANAGER_FAILED_TO_LOAD) {
      loading.failed = true;
      return;
    }
    // The app shows the other errors
  }

  const app = await import(/* webpackChunkName: "app" */ "./index").catch((e) => {
    console.error("Failed to load the manager app", e);
    return undefined;
  });
  if (!app) {
    // A page cached from before an update asks for app code that no longer exists, which a reload replaces.
    // After a reload, or without navigation timing to tell, reloading again could loop.
    const navigation = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined;
    if (!navigation || navigation.type === "reload") {
      loading.failed = true;
    } else {
      window.location.reload();
    }
    return;
  }

  app.startApp(appConfig, loading);
}

start();
