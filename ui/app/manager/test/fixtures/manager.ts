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
import path from "node:path";

import rest, { type RestApi } from "@openremote/rest";
import { users, type Usernames } from "./data/users";
import type { DefaultAssets } from "./data/assets";

import type { UserModel } from "../../src/pages/page-users";
import { type Asset, AssetModelUtil, type ManagerAppConfig, type Role } from "@openremote/model";
import {
  test as base,
  expect,
  type APIRequestContext,
  type Page,
  type SharedComponentTestFixtures,
  type Shared,
  type TestFixture,
  withPage,
} from "@openremote/test";
import { playwrightRequestAdapter } from "./request-adapter";
import {
  AlarmsPage,
  AssetsPage,
  InsightsPage,
  NotificationsPage,
  RealmsPage,
  RolesPage,
  RulesPage,
  UsersPage,
} from "./pages";
import { AssetViewer } from "../../../../component/or-asset-viewer/test/fixtures";
import { CollapsiblePanel } from "../../../../component/or-components/test/fixtures";
import { MwcInput, MwcMenu } from "../../../../component/or-mwc-components/test/fixtures";
import { JsonForms } from "../../../../component/or-json-forms/test/fixtures";
import { AssetTree } from "../../../../component/or-asset-tree/test/fixtures";
import { isAxiosError, type AxiosRequestConfig } from "axios";
const { admin, smartcity } = users;

export const adminStatePath = path.join(__dirname, "../../build/.auth/admin.json");
export const userStatePath = path.join(__dirname, "../../build/.auth/user.json");

/** Logs a warning for a failed HTTP request; any other error is a test bug and is rethrown to fail the test. */
function warnOnHttpError(e: unknown, message: string, ...context: unknown[]) {
  if (!isAxiosError(e)) throw e;
  console.warn(message, ...context, e.response?.status ?? e.message);
}

export class Manager {
  private readonly clientId = "openremote";
  private readonly managerHost: string;
  private authServerUrl?: string;
  readonly api: RestApi["api"];
  readonly axios: RestApi["_axiosInstance"];

  public realm?: string;
  public user?: UserModel;
  public role?: Role;
  public assets: Asset[] = [];
  public rules: number[] = [];
  public dashboards: string[] = [];
  public provisionedUsers: { realm: string; id: string }[] = [];

  constructor(
    readonly page: Page,
    readonly baseURL: string,
    request?: APIRequestContext
  ) {
    this.managerHost = process.env.managerUrl || "http://127.0.0.1:8080";
    rest.initialise(`${this.managerHost}/api/master/`);
    this.api = rest.api;
    this.axios = rest.axiosInstance;
    // Route the rest client through Playwright's request context so setup calls appear in the trace viewer
    if (request) {
      this.axios.defaults.adapter = playwrightRequestAdapter(request);
    }
  }

  /**
   * Init {@link AssetModelUtil} with asset infos, meta items and value descriptors.
   */
  async initAssetModel() {
    const assetInfosResponse = await this.api.AssetModelResource.getAssetInfos();
    const metaItemDescriptorResponse = await this.api.AssetModelResource.getMetaItemDescriptors();
    const valueDescriptorResponse = await this.api.AssetModelResource.getValueDescriptors();

    AssetModelUtil._assetTypeInfos = assetInfosResponse.data;
    AssetModelUtil._metaItemDescriptors = Object.values(metaItemDescriptorResponse.data);
    AssetModelUtil._valueDescriptors = Object.values(valueDescriptorResponse.data);
  }

  /**
   * Fulfill the `manager_config.json` response with a custom app config.
   * @param config The manager app config to merge with the default.
   */
  async configureAppConfig(config: ManagerAppConfig) {
    const realms = {
      default: {
        appTitle: "OpenRemote Manager Test",
        language: "en",
      },
    };
    await this.page.route(
      "/api/master/configuration/manager",
      async (route) => await route.fulfill({ json: { realms, ...config } })
    );
  }

  async goToRealmStartPage(realm: string) {
    await this.page.goto(this.getAppUrl(realm));
  }

  /**
   * Navigate to a settings page inside the manager using the settings menu at the top right
   * @param setting Name of the setting menu item
   */
  async navigateToMenuItem(setting: string) {
    await this.page.click("#drawer-menu");
    const menu = this.page.locator("#drawer-menu").getByRole("menuitem").filter({ hasText: setting });
    await menu.waitFor({ state: "visible" });
    await menu.click();
  }

  /**
   * Switch the app language using the language dialog in the settings menu at the top right.
   * This also stores it as the user's locale, so reset it with {@link resetLocale} afterwards.
   * @param language Name of the language as the dialog lists it, in the current app language
   */
  async switchLanguage(language: string) {
    await this.navigateToMenuItem("Language");
    await this.page.locator("or-vaadin-list-box:visible").getByRole("option").filter({ hasText: language }).click();
  }

  /**
   * Switch to a realm using the realm picker
   * @param name Name of the realm
   */
  async switchToRealmByRealmPicker(realm: string) {
    await this.page.click("#realm-picker");
    await this.page.locator("#realm-picker").getByRole("option").filter({ hasText: realm }).click();
  }

  /**
   * Navigate to a certain tab page
   * @param tab Tab name
   */
  async navigateToTab(tab: string) {
    await this.page.click(`#desktop-left a:has-text("${tab}")`);
  }

  /**
   * Login as user, waits for username and password fields to be visible.
   * @param user Username (admin or other)
   * @param password Password to log in with, defaulting to the one held for the known test users
   */
  async login(user: Usernames | string, password: string = users[user as Usernames]?.password) {
    const usernameField = this.page.getByRole("textbox", { name: "Username or email" });
    const passwordField = this.page.getByRole("textbox", { name: "Password" });
    await usernameField.waitFor();
    if ((await usernameField.isVisible()) && (await passwordField.isVisible())) {
      await usernameField.fill(user);
      await passwordField.fill(password);
      await this.page.keyboard.press("Enter");
    }
  }

  /**
   * Create a user with the given client roles in a realm (REST), then log in as them through the UI.
   *
   * Used to exercise a page under a specific permission set without disturbing the stored admin session.
   * Requires a clean `storageState`, otherwise the stored session is used instead of the login form.
   * @param realm The realm to create the user in
   * @param user The username and client roles to give them
   */
  async provisionUserAndLogin(realm: string, { username, roles }: { username: string; roles?: string[] }) {
    // set an initial password (== username) so the throwaway user can log in via the UI
    await this.provisionUser(realm, { username, roles, password: username });

    await this.goToRealmStartPage(realm);
    await this.login(username, username);
    await this.page.waitForURL("**/manager/**");
  }

  /**
   * Logout from the manager.
   *
   * After logout waits until redirection finished.
   */
  async logout() {
    if (await this.page.isVisible("#menu-btn-desktop")) {
      await this.page.click("#menu-btn-desktop");
      await this.page.locator("#menu > #list > li").filter({ hasText: "Log out" }).click();
    }
    // Wait for navigation to login page to prevent simultaneous navigation
    await this.page.waitForURL(`${await this.getAuthServerUrl()}/realms/**`);
  }

  async resetLocale(realm: string, username: Usernames, password: string) {
    const token = await this.getAccessToken(realm, username, password);
    await this.axios.put(`${this.managerHost}/api/${realm}/user/locale`, JSON.stringify("en"), {
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    });
  }

  /**
   * Resolve the auth server URL from the manager info endpoint, the same source the apps use at startup.
   *
   * The manager reports either an absolute URL or one relative to itself, so it is resolved against the manager
   * host and cached for the lifetime of the fixture.
   */
  async getAuthServerUrl() {
    if (!this.authServerUrl) {
      const { authServerUrl } = (await this.api.StatusResource.getInfo()).data;
      this.authServerUrl = new URL(authServerUrl || "/auth", this.managerHost).toString().replace(/\/+$/, "");
    }
    return this.authServerUrl;
  }

  async getAccessToken(realm: string, username: Usernames, password: string) {
    const data = new URLSearchParams();
    data.append("client_id", this.clientId);
    data.append("username", username);
    data.append("password", password);
    data.append("grant_type", "password");
    const authServerUrl = await this.getAuthServerUrl();
    const { access_token: accessToken } = (
      await this.axios.post(`${authServerUrl}/realms/${realm}/protocol/openid-connect/token`, data, {
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
        },
      })
    ).data;
    return accessToken;
  }

  /**
   * Build an axios request config authenticated as the master-realm admin, for REST setup calls.
   */
  async adminConfig(): Promise<AxiosRequestConfig<any>> {
    const token = await this.getAccessToken("master", admin.username, admin.password);
    return { headers: { Authorization: `Bearer ${token}` } };
  }

  /**
   * Provision a user with client roles (and optionally a password/email) in the given realm via REST, returning
   * the created user. Unlike {@link createUser} this is stateless (it doesn't touch `this.realm`/`this.user`), so
   * it can set up arbitrary users for e.g. access-control tests. Defaults to the admin config when none is given.
   * @param realm The realm to create the user in
   * @param user The username, client roles, and optional initial password/email
   * @param config The axios request config
   */
  async provisionUser(
    realm: string,
    {
      username,
      roles = [],
      password,
      email,
    }: { username: string; roles?: string[]; password?: string; email?: string },
    config?: AxiosRequestConfig<any>
  ): Promise<UserModel | undefined> {
    config ??= await this.adminConfig();
    const user = await this.api.UserResource.create(realm, { username, email, enabled: true } as UserModel, config)
      .then((r) => r.data)
      .catch(() => undefined);

    if (user?.id) {
      // track for teardown so provisioned users don't leak between tests/runs
      this.provisionedUsers.push({ realm, id: user.id });
      if (roles.length > 0) {
        await this.api.UserResource.updateUserClientRoles(realm, user.id, this.clientId, roles, config);
      }
      if (password) {
        await this.api.UserResource.updatePassword(realm, user.id, { value: password }, config);
      }
    }
    return user;
  }

  /**
   * Deletes all users provisioned via {@link provisionUser} (across realms). Called by {@link cleanUp}.
   * @param config The axios request config
   */
  async deleteUsers(config?: AxiosRequestConfig<any>) {
    config ??= await this.adminConfig();
    for (const { realm, id } of this.provisionedUsers) {
      try {
        const response = await this.api.UserResource.delete(realm, id, config);
        expect(response.status).toBe(204);
      } catch (e) {
        warnOnHttpError(e, "Could not delete user: ", id);
      }
    }
    this.provisionedUsers = [];
  }

  /**
   * When an application initialises a WebSocket this will assign that instance to the `window.ws` object.
   *
   * Must only be used inside a `await page.addInitScript` before the WebSocket gets initialised.
   */
  hijackWebSocket() {
    return () => {
      const OriginalWS = WebSocket;
      window.WebSocket = function (url: string | URL, protocols?: string | string[]) {
        return ((window as any).ws = new OriginalWS(url, protocols));
      } as any;
    };
  }

  /**
   * Send a WebSocket event to the server using an existing WebSocket connection.
   *
   * You must add `await page.addInitScript(manager.hijackWebSocket())`
   *
   * @param payload The playload to send to WebSocket server.
   * @see {@link hijackWebSocket}
   */
  async sendWebSocketEvent(payload: any) {
    await this.page.evaluate(
      (message) => {
        if ("ws" in window) {
          const ws = window.ws as WebSocket;
          if (ws && ws.readyState === ws.OPEN) {
            return ws.send(message);
          }
        }
        console.warn("No active WebSocket found.");
      },
      `EVENT:${JSON.stringify(payload)}`
    );
  }

  /**
   * Get the roles of the current realm
   *
   * Expects a realm to be configured
   * @param config The axios request config
   */
  async getClientRoles(config?: AxiosRequestConfig<any>) {
    try {
      const response = await rest.api.UserResource.getClientRoles(this.realm!, this.clientId, config);
      expect(response.status).toBe(200);
      return response.data;
    } catch (e) {
      warnOnHttpError(e, "Failed to get roles");
    }
  }

  /**
   * Create an new role
   *
   * The `compositeRoleIds` are mapped by name to the corresponding role id.
   *
   * Expects a realm to be configured
   * @param newRole The role to create
   * @param roles The current stored roles to add the new role to
   * @param config The axios request config
   */
  async createRole(newRole: Role, roles: Role[], config?: AxiosRequestConfig<any>) {
    if (newRole.compositeRoleIds) {
      newRole.compositeRoleIds = newRole.compositeRoleIds
        .map((name) => roles.find((r) => r.name === name)?.id)
        .filter(Boolean) as string[];
    }
    roles.push(newRole);
    try {
      const response = await rest.api.UserResource.updateRoles(this.realm!, roles, config);
      expect(response.status).toBe(204);
      this.role = newRole;
    } catch (e) {
      warnOnHttpError(e, "Failed to create role");
    }
  }

  /**
   * Create an new user
   *
   * Expects a realm to be configured
   * @param user The user to create
   * @param config The axios request config
   */
  async createUser(user: UserModel, config?: AxiosRequestConfig<any>) {
    try {
      const response = await rest.api.UserResource.create(this.realm!, user, config);
      expect(response.status).toBe(200);
      this.user = response.data;
    } catch (e) {
      warnOnHttpError(e, "Failed to create user");
    }
  }

  /**
   * Add a role to the current user
   *
   * Expects a realm and user to be configured
   * @param roles A list of roles to add to the user
   * @param config The axios request config
   */
  async addUserRoles(roles: string[], config?: AxiosRequestConfig<any>) {
    try {
      const response = await rest.api.UserResource.updateUserClientRoles(
        this.realm!,
        this.user!.id!,
        this.clientId,
        roles,
        config
      );
      expect(response.status).toBe(204);
    } catch (e) {
      warnOnHttpError(e, "Failed to update users' roles");
    }
  }

  /**
   * Reset the user password with the smartcity users' password
   *
   * Expects a realm and user to be configured
   * @param config The axios request config
   */
  async resetUserPassword(config?: AxiosRequestConfig<any>) {
    try {
      const response = await rest.api.UserResource.updatePassword(
        this.realm!,
        this.user!.id!,
        { value: smartcity.password },
        config
      );
      expect(response.status).toBe(204);
    } catch (e) {
      warnOnHttpError(e, "Failed to reset user password");
    }
  }

  /**
   * Create an asset
   * @param asset The asset to create
   * @param config The axios request config
   */
  async createAsset(asset: Asset, config?: AxiosRequestConfig<any>) {
    if (!config) {
      config = await this.adminConfig();
    }
    await rest.api.AssetResource.create(asset, config)
      .then((response) => {
        expect(response.status).toBe(200);
        this.assets.push(response.data);
      })
      .catch((e) => {
        if (!isAxiosError(e)) throw e;
        expect(e.response?.status, { message: "Failed to create asset" }).toBe(409);
      });
  }

  /**
   * Updates an asset
   * @param asset The asset to update
   * @param config The axios request config
   */
  async updateAsset(asset: Asset, config?: AxiosRequestConfig<any>) {
    if (!config) {
      config = await this.adminConfig();
    }
    await rest.api.AssetResource.update(asset.id!, asset, config)
      .then((response) => {
        expect(response.status).toBe(200);
        this.assets = [...this.assets.filter((a) => a.id !== response.data.id), response.data as Asset];
      })
      .catch((e) => {
        if (!isAxiosError(e)) throw e;
        expect(e.response?.status, { message: "Failed to update asset" }).toBe(409);
      });
  }

  /**
   * Setup the testing environment by giving the realm name and additional parameters
   * @param realm Realm to create
   * @param [options] - Optional parameters for setup
   * @param [options.user] - The user to create within the realm
   * @param [options.role] - The role to assign or create
   * @param [options.assets] - Assets to create for the realm
   */
  async setup(
    realm: string,
    { user, role, assets }: { user?: UserModel; role?: Role; assets?: Asset[] | DefaultAssets } = {}
  ) {
    const config = await this.adminConfig();

    this.realm = realm;

    // Provision role
    if (role) {
      const roles = await this.getClientRoles(config);
      if (roles) {
        await this.createRole(role, roles, config);
      }
    }

    // Provision user
    if (user) {
      await this.createUser(user, config);
      await this.addUserRoles(user.roles!, config);
      await this.resetUserPassword(config);
    }

    // Provision assets
    if (assets) {
      for (const asset of assets) {
        await this.createAsset(asset, config);
      }
    }
  }

  /**
   * Deletes dashboards in the active realm
   * @param config The axios request config
   */
  async deleteDashboards(config?: AxiosRequestConfig<any>, realm = this.realm) {
    for (const [i, id] of this.dashboards.entries()) {
      try {
        const response = await rest.api.DashboardResource.delete(realm!, id, config);
        expect(response.status).toBe(204);
        this.dashboards.splice(i, 1);
      } catch (e) {
        warnOnHttpError(e, "Could not delete dashboard: ", id);
      }
    }
  }

  /**
   * Deletes rules in the active realm
   * @param config The axios request config
   */
  async deleteRealmRulesets(config?: AxiosRequestConfig<any>) {
    for (const [i, id] of this.rules.entries()) {
      try {
        const response = await rest.api.RulesResource.deleteRealmRuleset(id!, config);
        expect(response.status).toBe(204);
        this.rules.splice(i, 1);
      } catch (e) {
        warnOnHttpError(e, "Could not delete realm rule: ", id);
      }
    }
  }

  /**
   * Deletes assets
   * @param config The axios request config
   */
  async deleteAssets(config?: AxiosRequestConfig<any>) {
    const assetIds = this.assets.map(({ id }) => id!);
    try {
      const response = await rest.api.AssetResource.delete({ assetId: assetIds }, config);
      expect(response.status).toBe(204);
      this.assets = [];
    } catch (e) {
      warnOnHttpError(e, "Could not delete asset(s): ", assetIds);
    }
  }

  /**
   * Deletes all assets in a realm
   * @param realm The realm
   * @param config The axios request config
   */
  async deleteAssetsInRealm(realm: string, config?: AxiosRequestConfig<any>) {
    config = config ?? (await this.adminConfig());

    const assetIdRetreiver = async () => {
      const assetResponse = await rest.api.AssetResource.queryAssets(
        {
          select: { attributes: [] },
          realm: { name: realm },
        },
        config
      );
      expect(assetResponse.status).toBe(200);
      const assets = assetResponse.data;
      return assets.map(({ id }) => id!);
    };

    let assetIds = await assetIdRetreiver();

    while (assetIds.length > 0) {
      const response = await rest.api.AssetResource.delete({ assetId: assetIds }, config);
      expect(response.status).toBe(204);
      // Wait for these assets to be deleted
      await expect
        .poll(async () => {
          const countResponse = await rest.api.AssetResource.queryCount(
            {
              select: { attributes: [] },
              ids: assetIds,
              realm: { name: realm },
              includeDeletePending: true,
            },
            config
          );
          expect(countResponse.status).toBe(200);
          return countResponse.data;
        })
        .toBe(0);
      assetIds = await assetIdRetreiver();
    }
  }

  /**
   * Delete role
   *
   * Expects a realm to be configured
   * @param roles The stored roles
   * @param config The axios request config
   */
  async deleteRole(roles: Role[], config) {
    roles = roles.filter((r) => r.id !== this.role!.id);
    try {
      const response = await rest.api.UserResource.updateRoles(this.realm!, roles, config);
      expect(response.status).toBe(204);
      delete this.role;
    } catch (e) {
      warnOnHttpError(e, "Could not update roles: ", this.role);
    }
  }

  /**
   *  Clean up the environment
   */
  async cleanUp() {
    const config = await this.adminConfig();

    if (this.dashboards.length > 0) {
      await this.deleteDashboards(config);
    }

    if (this.rules.length > 0) {
      await this.deleteRealmRulesets(config);
    }

    if (this.assets.length > 0) {
      await this.deleteAssets(config);
    }

    if (this.provisionedUsers.length > 0) {
      await this.deleteUsers(config);
    }

    if (this.role && this.realm) {
      const roles = await this.getClientRoles(config);
      if (roles) {
        await this.deleteRole(roles, config);
      }
    }
  }

  getAppUrl(realm: string) {
    return `${new URL(this.baseURL).origin}/manager/?realm=${realm}`;
  }
}

function withManager<R>(managerPage: Function): TestFixture<R, { page: Page; shared: Shared; manager: Manager }> {
  return async ({ page: basePage, shared, manager }, use) => {
    expect(manager).toBeInstanceOf(Manager);
    await use(new (managerPage.bind(null, basePage, shared, manager))());
  };
}

interface PageFixtures {
  alarmsPage: AlarmsPage;
  assetsPage: AssetsPage;
  insightsPage: InsightsPage;
  notificationsPage: NotificationsPage;
  realmsPage: RealmsPage;
  rolesPage: RolesPage;
  rulesPage: RulesPage;
  usersPage: UsersPage;
}

interface ComponentFixtures extends SharedComponentTestFixtures {
  assetViewer: AssetViewer;
  assetTree: AssetTree;
  collapsiblePanel: CollapsiblePanel;
  jsonForms: JsonForms;
  mwcInput: MwcInput;
  mwcMenu: MwcMenu;
}

interface Fixtures extends PageFixtures, ComponentFixtures {
  manager: Manager;
}

export const test = base.extend<Fixtures>({
  manager: async ({ page, baseURL, request }, use) => await use(new Manager(page, baseURL!, request)),
  // Pages
  alarmsPage: withManager(AlarmsPage),
  assetsPage: withManager(AssetsPage),
  insightsPage: withManager(InsightsPage),
  notificationsPage: withManager(NotificationsPage),
  realmsPage: withManager(RealmsPage),
  rolesPage: withManager(RolesPage),
  rulesPage: withManager(RulesPage),
  usersPage: withManager(UsersPage),
  // Components
  assetViewer: withPage(AssetViewer),
  assetTree: withPage(AssetTree),
  collapsiblePanel: withPage(CollapsiblePanel),
  jsonForms: withPage(JsonForms),
  mwcInput: withPage(MwcInput),
  mwcMenu: withPage(MwcMenu),
});
