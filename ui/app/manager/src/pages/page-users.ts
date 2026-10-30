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
import { css, html, type PropertyValues, type TemplateResult, unsafeCSS } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import manager, { DefaultColor3, DefaultColor4, OPENREMOTE_CLIENT_ID, Util } from "@openremote/core";
import "@openremote/or-components/or-panel";
import "@openremote/or-translate";
import type { Store } from "@reduxjs/toolkit";
import { type AppStateKeyed, Page, type PageProvider, router } from "@openremote/or-app";
import {
  ClientRole,
  type Credential,
  type Role,
  type User,
  type UserAssetLink,
  type UserQuery,
  type UserSession,
} from "@openremote/model";
import { i18next } from "@openremote/or-translate";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import { type GenericAxiosResponse, isAxiosError } from "@openremote/rest";
import type { OrAssetTreeRequestSelectionEvent, OrAssetTreeSelectionEvent } from "@openremote/or-asset-tree";
import { getNewUserRoute, getUsersRoute } from "../routes";
import { when } from "lit/directives/when.js";
import { until } from "lit/directives/until.js";
import { map } from "lit/directives/map.js";
import type { OrMwcTableRowClickEvent, TableColumn, TableRow } from "@openremote/or-mwc-components/or-mwc-table";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import type { OrVaadinPasswordField } from "@openremote/or-vaadin-components/or-vaadin-password-field";
import type { OrVaadinMultiSelectComboBox } from "@openremote/or-vaadin-components/or-vaadin-multi-select-combo-box";
import type { OrVaadinCheckbox } from "@openremote/or-vaadin-components/or-vaadin-checkbox";
import type { OrVaadinTextField } from "@openremote/or-vaadin-components/or-vaadin-text-field";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";

const tableStyle = require("@material/data-table/dist/mdc.data-table.css");

export function pageUsersProvider(store: Store<AppStateKeyed>): PageProvider<AppStateKeyed> {
  return {
    name: "users",
    routes: ["users", "users/:id", "users/new/:type"],
    pageCreator: () => {
      return new PageUsers(store);
    },
  };
}

export interface UserModel extends User {
  password?: string;
  loaded?: boolean;
  loading?: boolean;
  previousRoles?: string[];
  roles?: string[];
  previousRealmRoles?: string[];
  realmRoles?: string[];
  previousAssetLinks?: UserAssetLink[];
  userAssetLinks?: UserAssetLink[];
}

@customElement("page-users")
export class PageUsers extends Page<AppStateKeyed> {
  static get styles() {
    // language=CSS
    return [
      unsafeCSS(tableStyle),
      css`
        #wrapper {
          height: 100%;
          width: 100%;
          display: flex;
          flex-direction: column;
        }

        #title {
          padding: 0 20px;
          font-size: 18px;
          font-weight: bold;
          width: calc(100% - 40px);
          max-width: 1360px;
          margin: 20px auto;
          align-items: center;
          display: flex;
          color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
        }

        #title or-icon {
          margin-right: 10px;
          margin-left: 14px;
        }

        .panel {
          flex: 0;
          width: 100%;
          box-sizing: border-box;
          max-width: 1360px;
          background-color: white;
          border: 1px solid #e5e5e5;
          border-radius: 5px;
          position: relative;
          margin: 0 auto 16px;
          padding: 12px 24px 24px;
          display: flex;
          flex-direction: column;
        }

        .panel-title {
          display: flex;
          text-transform: uppercase;
          font-weight: bolder;
          color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
          line-height: 1em;
          margin-bottom: 10px;
          margin-top: 0;
          flex: 0 0 auto;
          letter-spacing: 0.025em;
          align-items: center;
          min-height: 36px;
        }

        or-mwc-input,
        or-vaadin-text-field,
        or-vaadin-email-field,
        or-vaadin-password-field,
        or-vaadin-multi-select-combo-box {
          margin-bottom: 20px;
        }

        or-icon {
          vertical-align: middle;
          --or-icon-width: 20px;
          --or-icon-height: 20px;
          margin-right: 2px;
          margin-left: -5px;
        }

        .row {
          display: flex;
          flex-direction: row;
          flex: 1 1 0;
          gap: 24px;
        }

        .column {
          display: flex;
          flex-direction: column;
          margin: 0px;
          flex: 1 1 0;
        }

        .hidden {
          display: none;
        }

        .breadcrumb-container {
          padding: 0 20px;
          width: calc(100% - 40px);
          max-width: 1360px;
          margin: 12px auto 0;
          display: flex;
          align-items: center;
        }

        .breadcrumb-clickable {
          cursor: pointer;
          color: var(--or-app-color4, ${unsafeCSS(DefaultColor4)});
        }

        .breadcrumb-arrow {
          margin: 0 5px -3px 5px;
          --or-icon-width: 16px;
          --or-icon-height: 16px;
        }

        #session-table {
          --or-mwc-table-column-width-3: 0;
        }

        .button-row {
          display: flex !important;
          flex-direction: row !important;
          margin-bottom: 0;
          justify-content: space-between;
        }

        @media screen and (max-width: 768px) {
          #title {
            padding: 0;
            width: 100%;
          }

          .row {
            display: block;
            flex-direction: column;
            gap: 0;
          }

          .panel {
            border-radius: 0;
            border-left: 0px;
            border-right: 0px;
          }
        }
      `,
    ];
  }

  @property()
  public realm?: string;

  @property()
  public userId?: string;

  @property()
  public creationState?: {
    userModel: UserModel;
  };

  @state()
  protected _users: UserModel[] = [];

  @state()
  protected _serviceUsers: UserModel[] = [];

  @state()
  protected _userFilter = this.getDefaultUserFilter(false);

  @state()
  protected _serviceUserFilter = this.getDefaultUserFilter(true);

  @state()
  protected _passwordPolicy: string[] = [];

  @state()
  protected _roles: Role[] = [];

  @state()
  protected _realmRoles: string[] = [];

  @state()
  protected _registrationEmailAsUsername: boolean = false;

  @state()
  protected _compositeRoles: Role[] = [];

  @state()
  protected _loadDataPromise?: Promise<any>;

  @state()
  protected _saveUserPromise?: Promise<any>;

  @state()
  private _sessionLoader?: Promise<TemplateResult>;

  get name(): string {
    return "user_plural";
  }

  public shouldUpdate(changedProperties: PropertyValues): boolean {
    if (changedProperties.has("realm") && changedProperties.get("realm") != undefined) {
      this.reset();
      this.loadData();
    }
    if (changedProperties.has("userId")) {
      this._sessionLoader = undefined; // Reset the MQTT sessions view
      this._updateRoute();
    } else if (changedProperties.has("creationState")) {
      this._updateNewUserRoute();
    }
    return super.shouldUpdate(changedProperties);
  }

  public connectedCallback() {
    super.connectedCallback();
    this.loadData();
  }

  public disconnectedCallback() {
    super.disconnectedCallback();
  }

  protected responseAndStateOK(
    stateChecker: () => boolean,
    response: GenericAxiosResponse<any>,
    errorMsg: string
  ): boolean {
    if (!stateChecker()) {
      return false;
    }

    if (!response.data) {
      showSnackbar(undefined, errorMsg, "dismiss");
      console.error(errorMsg + ": response = " + response.statusText);
      return false;
    }

    return true;
  }

  protected async loadData(): Promise<void> {
    if (!this._loadDataPromise) {
      this._loadDataPromise = this.fetchUsers();
      this._loadDataPromise.finally(() => {
        this._loadDataPromise = undefined;
      });
    }
    return this._loadDataPromise;
  }

  protected async fetchUsers(): Promise<void> {
    if (!this.realm || !this.isConnected) {
      return;
    }

    this._compositeRoles = [];
    this._roles = [];
    this._realmRoles = [];
    this._users = [];
    this._serviceUsers = [];

    if (!manager.authenticated || !manager.hasRole(ClientRole.READ_USERS)) {
      console.warn("Not authenticated or insufficient access");
      return;
    }

    // After async op check that the response still matches current state and that the component is still loaded in the UI
    const stateChecker = () => {
      return this.getState().app.realm === this.realm && this.isConnected;
    };

    const roleResponse = await manager.rest.api.UserResource.getClientRoles(manager.displayRealm, OPENREMOTE_CLIENT_ID);

    if (!this.responseAndStateOK(stateChecker, roleResponse, "loadFailedRoles")) {
      return;
    }

    const realmResponse = await manager.rest.api.RealmResource.get(manager.displayRealm);

    if (!this.responseAndStateOK(stateChecker, realmResponse, "loadFailedRoles")) {
      return;
    }

    const usersResponse = await manager.rest.api.UserResource.query({
      realmPredicate: { name: manager.displayRealm },
    } as UserQuery);

    if (!this.responseAndStateOK(stateChecker, usersResponse, "loadFailedUsers")) {
      return;
    }

    this._compositeRoles = roleResponse.data
      .filter((role) => role.composite)
      .sort(Util.sortByString((role) => role.name));
    this._roles = roleResponse.data.filter((role) => !role.composite).sort(Util.sortByString((role) => role.name));
    this._registrationEmailAsUsername = realmResponse.data.registrationEmailAsUsername;
    this._realmRoles = (realmResponse.data.realmRoles || []).map((role) => role.name).sort();
    this._passwordPolicy = realmResponse.data.passwordPolicy;
    this._users = usersResponse.data.filter((user) => !user.serviceAccount).sort(Util.sortByString((u) => u.username));
    this._serviceUsers = usersResponse.data
      .filter((user) => user.serviceAccount)
      .sort(Util.sortByString((u) => u.username));
  }

  private async _createUpdateUser(user: UserModel, action: "update" | "create"): Promise<boolean> {
    let result = false;

    if (this._registrationEmailAsUsername && !user.serviceAccount && !user.email) {
      showSnackbar(undefined, "noEmailSet", "dismiss");
      return false;
    } else if ((!this._registrationEmailAsUsername || user.serviceAccount) && !user.username) {
      showSnackbar(undefined, "noUsernameSet", "dismiss");
      return false;
    }
    // New service users with the 'gateway-' prefix are not allowed
    if (user.serviceAccount && user.username.startsWith("gateway-")) {
      showSnackbar(undefined, "noGatewayUsername", "dismiss");
      return false;
    }

    if (user.password === "") {
      // Means a validation failure shouldn't get here
      return false;
    }

    const isUpdate = !!user.id;

    try {
      const response =
        action === "update"
          ? await manager.rest.api.UserResource.update(manager.displayRealm, user)
          : await manager.rest.api.UserResource.create(manager.displayRealm, user);

      // Ensure user ID is set
      user.id = response.data.id;

      await this._updateRoles(user, false);
      await this._updateRoles(user, true);
      await this._updateUserAssetLinks(user);
      result = true;
    } catch (e) {
      if (isAxiosError(e)) {
        console.error((isUpdate ? "save user failed" : "create user failed") + ": response = " + e.response.statusText);
        if (e.response.status === 400) {
          showSnackbar(undefined, isUpdate ? "saveUserFailed" : "createUserFailed", "dismiss");
        } else if (e.response.status === 403) {
          showSnackbar(undefined, "userAlreadyExists");
        }
      }
      result = false;
      throw e; // Throw exception anyhow to handle individual cases
    } finally {
      await this.loadData();

      // After updating the user, reset the password if it has changed.
      // This is handled asynchronously, so it will not 'wait' before the request has succeeded.
      if (user.password) {
        const credentials = { value: user.password } as Credential;
        manager.rest.api.UserResource.updatePassword(manager.displayRealm, user.id, credentials).catch((e) => {
          if (isAxiosError(e) && e.response.status !== 404) {
            showSnackbar(undefined, "savePasswordFailed");
          }
        });
      }

      return result;
    }
  }

  /**
   * Backend only uses name of role not the ID so although service client roles are not the same as composite roles
   * the names will match so that's ok
   */
  private async _updateRoles(user: UserModel, realmRoles: boolean) {
    if (realmRoles) {
      if (!Util.objectsEqual(user.realmRoles, user.previousRealmRoles)) {
        await manager.rest.api.UserResource.updateUserRealmRoles(manager.displayRealm, user.id, user.realmRoles);
      }
    } else {
      const roles = user.roles;
      const previousRoles = user.previousRoles;
      const removedRoles = previousRoles.filter((previousRole) => !roles.some((role) => role === previousRole));
      const addedRoles = roles.filter((role) => !previousRoles.some((previousRole) => previousRole === role));

      if (removedRoles.length === 0 && addedRoles.length === 0) {
        return;
      }
      await manager.rest.api.UserResource.updateUserClientRoles(
        manager.displayRealm,
        user.id,
        OPENREMOTE_CLIENT_ID,
        roles
      );
    }
  }

  private async _updateUserAssetLinks(user: UserModel) {
    if (!user.previousAssetLinks) {
      return;
    }

    const removedLinks = user.previousAssetLinks.filter(
      (assetLink) => !user.userAssetLinks.some((newLink) => assetLink.id.assetId === newLink.id.assetId)
    );
    const addedLinks = user.userAssetLinks
      .filter((assetLink) => !user.previousAssetLinks.some((oldLink) => assetLink.id.assetId === oldLink.id.assetId))
      .map((link) => {
        // Ensure user ID is added as new users wouldn't have had an ID at the time the links were created in the UI
        link.id.userId = user.id;
        return link;
      });

    if (removedLinks.length > 0) {
      await manager.rest.api.AssetResource.deleteUserAssetLinks(removedLinks);
    }
    if (addedLinks.length > 0) {
      await manager.rest.api.AssetResource.createUserAssetLinks(addedLinks);
    }
  }

  private _deleteUser(user) {
    showConfirmDialog(
      this.shadowRoot,
      html`
        <or-vaadin-confirm-dialog @confirm=${() => this.doDelete(user)}>
          ${getConfirmDialogContent(
            "error",
            "deleteUser",
            html`<or-translate value="deleteUserConfirm" .options=${{ userName: user.username }}></or-translate>`,
            "delete",
            "cancel"
          )}
        </or-vaadin-confirm-dialog>
      `
    );
  }

  private doDelete(user) {
    manager.rest.api.UserResource.delete(manager.displayRealm, user.id).then((response) => {
      if (user.serviceAccount) {
        this._serviceUsers = [...this._serviceUsers.filter((u) => u.id !== user.id)];
        this.reset();
      } else {
        this._users = [...this._users.filter((u) => u.id !== user.id)];
        this.reset();
      }
    });
  }

  protected render(): TemplateResult | void {
    if (!manager.authenticated) {
      return html` <or-translate value="notAuthenticated"></or-translate> `;
    }
    // Apply filter (such as search input)
    const users = this._userFilter(this._users);
    const serviceUsers = this._serviceUserFilter(this._serviceUsers);

    const compositeRoleOptions: { value: any; label: string }[] = this._compositeRoles.map((cr) => ({
      value: cr.name,
      label: i18next.t(cr.name),
    }));
    const realmRoleOptions: { value: any; label: string }[] = this._realmRoles.map((r) => ({
      value: r,
      label: i18next.t("realmRole." + r, Util.camelCaseToSentenceCase(r.replace("_", " ").replace("-", " "))),
    }));
    const readonly = !manager.hasRole(ClientRole.WRITE_ADMIN);

    // Content of User Table
    const userTableColumns: TableColumn[] = [
      { title: i18next.t("username") },
      { title: i18next.t("firstName") },
      { title: i18next.t("surname") },
      { title: i18next.t("email"), hideMobile: true },
      { title: i18next.t("status") },
      { title: i18next.t("tag"), isSortable: true },
    ];
    const userTableRows: TableRow[] = users.map((user) => {
      return {
        content: [
          user.username,
          user.firstName,
          user.lastName,
          user.email,
          user.enabled ? i18next.t("enabled") : i18next.t("disabled"),
          user.attributes?.Tag?.[0],
        ] as string[],
        clickable: true,
      };
    });

    // Content of Service user Table
    const serviceUserTableColumns: TableColumn[] = [
      { title: i18next.t("username") },
      { title: i18next.t("status") },
      { title: i18next.t("tag"), isSortable: true },
    ];
    const serviceUserTableRows: TableRow[] = serviceUsers.map((user) => {
      return {
        content: [
          user.username,
          user.enabled ? i18next.t("enabled") : i18next.t("disabled"),
          user.attributes?.Tag?.[0],
        ] as string[],
        clickable: true,
      };
    });

    // Configuration
    const tableConfig = {
      columnFilter: [],
      stickyFirstColumn: false,
      pagination: {
        enable: true,
      },
    };

    const mergedUserList: UserModel[] = [...users, ...serviceUsers];
    const index: number | undefined = this.userId
      ? mergedUserList.findIndex((user) => user.id === this.userId)
      : undefined;

    return html`
      <div id="wrapper">
        <!-- Breadcrumb on top of the page-->
        ${when(
          (this.userId && index !== undefined) || this.creationState,
          () => html`
            <div class="breadcrumb-container">
              <span class="breadcrumb-clickable" @click="${() => this.reset()}">${i18next.t("user_plural")}</span>
              <or-icon class="breadcrumb-arrow" icon="chevron-right"></or-icon>
              <span style="margin-left: 2px;"
                >${index !== undefined ? mergedUserList[index]?.username : this.creationState.userModel.serviceAccount ? i18next.t("creating_serviceUser") : i18next.t("creating_regularUser")}</span
              >
            </div>
          `
        )}

        <div id="title">
          <or-icon icon="account-group"></or-icon>
          <span
            >${this.userId && index !== undefined ? mergedUserList[index]?.username : i18next.t("user_plural")}</span
          >
        </div>

        <!-- User Specific page -->
        ${when(
          (this.userId && index !== undefined) || this.creationState,
          () => html`
            ${when(mergedUserList[index] !== undefined || this.creationState, () => {
              const user: UserModel = index !== undefined ? mergedUserList[index] : this.creationState.userModel;
              const showMqttSessions = user.serviceAccount && this.userId;
              return html`
                <div id="content" class="panel">
                  <p class="panel-title">
                    ${user.serviceAccount ? i18next.t("serviceUser") : i18next.t("user")} ${i18next.t("settings")}
                  </p>
                  ${this.getSingleUserView(user, compositeRoleOptions, realmRoleOptions, index != undefined ? "user" + index : "", readonly || this._saveUserPromise != undefined)}
                </div>

                ${when(showMqttSessions, () => this.getMQTTSessionTemplate(user))}
              `;
            })}

            <!-- List of Users page -->
          `,
          () => html`
            <div id="content" class="panel">
              <div class="panel-title" style="justify-content: space-between;">
                <p>${i18next.t("regularUser_plural")}</p>
                <div style="display: flex; align-items: center; gap: 12px;">
                  <or-vaadin-text-field
                    placeholder=${i18next.t("search")}
                    style="margin: 0;"
                    @input=${(ev: InputEvent) => this.onRegularUserSearch(ev)}
                  >
                    <or-translate slot="placeholder" value="search"></or-translate>
                    <or-icon slot="suffix" icon="magnify"></or-icon>
                  </or-vaadin-text-field>
                  <or-vaadin-button @click=${() => (this.creationState = { userModel: this.getNewUserModel(false) })}>
                    <or-icon slot="prefix" icon="plus"></or-icon>
                    <or-translate value="add"></or-translate>
                    <or-translate value="user"></or-translate>
                  </or-vaadin-button>
                </div>
              </div>
              ${until(
                this.getUsersTable(userTableColumns, userTableRows, tableConfig, (ev) => {
                  this.userId = users[ev.detail.index].id;
                }),
                html`${i18next.t("loading")}`
              )}
            </div>

            <div id="content" class="panel">
              <div class="panel-title" style="justify-content: space-between;">
                <p>${i18next.t("serviceUser_plural")}</p>
                <div style="display: flex; align-items: center; gap: 12px;">
                  <or-vaadin-text-field
                    placeholder=${i18next.t("search")}
                    style="margin: 0;"
                    @input=${(ev: InputEvent) => this.onServiceUserSearch(ev)}
                  >
                    <or-translate slot="placeholder" value="search"></or-translate>
                    <or-icon slot="suffix" icon="magnify"></or-icon>
                  </or-vaadin-text-field>
                  <or-vaadin-button @click=${() => (this.creationState = { userModel: this.getNewUserModel(true) })}>
                    <or-icon slot="prefix" icon="plus"></or-icon>
                    <or-translate value="add"></or-translate>
                    <or-translate value="user"></or-translate>
                  </or-vaadin-button>
                </div>
              </div>
              ${until(
                this.getUsersTable(serviceUserTableColumns, serviceUserTableRows, tableConfig, (ev) => {
                  this.userId = serviceUsers[ev.detail.index].id;
                }),
                html`${i18next.t("loading")}`
              )}
            </div>
          `
        )}
      </div>
    `;
  }

  protected async getUsersTable(
    columns: TemplateResult | TableColumn[] | string[],
    rows: TemplateResult | TableRow[] | string[][],
    config: any,
    onRowClick: (event: OrMwcTableRowClickEvent) => void
  ): Promise<TemplateResult> {
    if (this._loadDataPromise) {
      await this._loadDataPromise;
    }
    return html`
      <or-mwc-table
        .columns="${columns instanceof Array ? columns : undefined}"
        .columnsTemplate="${!(columns instanceof Array) ? columns : undefined}"
        .rows="${rows instanceof Array ? rows : undefined}"
        .rowsTemplate="${!(rows instanceof Array) ? rows : undefined}"
        .config="${config}"
        @or-mwc-table-row-click="${rows instanceof Array ? onRowClick : undefined}"
      ></or-mwc-table>
    `;
  }

  protected getNewUserModel(serviceAccount: boolean): UserModel {
    return {
      enabled: true,
      password: undefined,
      realm: manager.displayRealm,
      roles: [],
      previousRoles: [],
      realmRoles: [],
      previousRealmRoles: [],
      userAssetLinks: [],
      serviceAccount,
    };
  }

  protected getDefaultUserFilter(serviceUser: boolean): (users: UserModel[]) => UserModel[] {
    return (users) => users;
  }

  protected onRegularUserSearch(ev: InputEvent) {
    const value = (ev.target as HTMLInputElement).value?.toLowerCase();
    if (!value) {
      this._userFilter = this.getDefaultUserFilter(false);
    } else {
      this._userFilter = (users) => {
        return users.filter(
          (u) =>
            (u.username as string)?.toLowerCase().includes(value) ||
            (u.firstName as string)?.toLowerCase().includes(value) ||
            (u.lastName as string)?.toLowerCase().includes(value) ||
            (u.email as string)?.toLowerCase().includes(value) ||
            (u.attributes?.Tag?.[0] as string)?.toLowerCase().includes(value)
        );
      };
    }
  }

  protected onServiceUserSearch(ev: InputEvent) {
    const value = (ev.target as HTMLInputElement).value?.toLowerCase();
    if (!value) {
      this._serviceUserFilter = this.getDefaultUserFilter(true);
    } else {
      this._serviceUserFilter = (users) => {
        return users.filter(
          (u) =>
            (u.username as string)?.includes(value) || (u.attributes?.Tag?.[0] as string)?.toLowerCase().includes(value)
        );
      };
    }
  }

  protected async loadUser(user: UserModel) {
    if (user.roles || user.realmRoles) {
      return;
    }

    // Load users assigned roles
    const userRolesResponse = await manager.rest.api.UserResource.getUserClientRoles(
      manager.displayRealm,
      user.id,
      OPENREMOTE_CLIENT_ID
    );
    if (!this.responseAndStateOK(() => true, userRolesResponse, "loadFailedUserInfo")) {
      return;
    }

    const userRealmRolesResponse = await manager.rest.api.UserResource.getUserRealmRoles(manager.displayRealm, user.id);
    if (!this.responseAndStateOK(() => true, userRolesResponse, "loadFailedUserInfo")) {
      return;
    }

    const userAssetLinksResponse = await manager.rest.api.AssetResource.getUserAssetLinks({
      realm: manager.displayRealm,
      userId: user.id,
    });
    if (!this.responseAndStateOK(() => true, userAssetLinksResponse, "loadFailedUserInfo")) {
      return;
    }

    user.roles = userRolesResponse.data;
    user.realmRoles = userRealmRolesResponse.data;
    user.previousRealmRoles = [...user.realmRoles];
    user.previousRoles = [...user.roles];
    user.userAssetLinks = userAssetLinksResponse.data;
    user.loaded = true;
    user.loading = false;

    // Update the dom
    this.requestUpdate();
  }

  protected _openAssetSelector(ev: Event, user: UserModel, readonly: boolean, suffix: string) {
    const openBtn = ev.target as OrVaadinButton;
    openBtn.disabled = true;
    user.previousAssetLinks = [...user.userAssetLinks];

    const onAssetSelectionChanged = (e: OrAssetTreeSelectionEvent) => {
      user.userAssetLinks = e.detail.newNodes.map((node) => {
        const userAssetLink: UserAssetLink = {
          id: {
            userId: user.id,
            realm: user.realm,
            assetId: node.asset.id,
          },
        };
        return userAssetLink;
      });
    };

    let dialog: OrVaadinDialog | undefined;
    const onCancel = () => {
      user.userAssetLinks = user.previousAssetLinks;
      user.previousAssetLinks = undefined;
      openBtn.disabled = false;
      dialog?.close();
    };
    const onOk = () => {
      openBtn.disabled = false;
      this.onUserChanged(suffix);
      this.requestUpdate();
      dialog?.close();
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px" no-close-on-esc no-close-on-outside-click>
          <h2 slot="header-content">
            <or-translate value="linkedAssets"></or-translate>
          </h2>
          <or-asset-tree
            id="chart-asset-tree"
            readonly
            .selectedIds="${user.userAssetLinks.map((ual) => ual.id.assetId)}"
            .showSortBtn="${false}"
            expandNodes
            checkboxes
            @or-asset-tree-request-selection="${(e: OrAssetTreeRequestSelectionEvent) => {
              if (readonly) {
                e.detail.allow = false;
              }
            }}"
            @or-asset-tree-selection="${(e: OrAssetTreeSelectionEvent) => {
              if (!readonly) {
                onAssetSelectionChanged(e);
              }
            }}"
          ></or-asset-tree>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${onCancel}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" @click=${onOk}>
              <or-translate value="ok"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  protected onUserChanged(suffix: string) {
    // Don't have form-associated custom element support in lit at time of writing which would be the way to go here
    const validateArray = Array.from(this.shadowRoot.querySelectorAll(".validate"))
      .map((element) => element.querySelector("input"))
      .filter((input) => input !== null);
    const saveBtn = this.shadowRoot.getElementById("savebtn-" + suffix) as OrVaadinButton;
    const saveDisabled = Array.from(validateArray).some((input) => !(input as HTMLInputElement).checkValidity());
    saveBtn.disabled = saveDisabled;
  }

  protected _onPasswordChanged(user: UserModel, suffix: string) {
    const passwordComponent = this.shadowRoot.getElementById("new-password-" + suffix) as OrVaadinPasswordField;
    const repeatPasswordComponent = this.shadowRoot.getElementById(
      "new-repeatPassword-" + suffix
    ) as OrVaadinPasswordField;

    if (repeatPasswordComponent.value !== passwordComponent.value) {
      console.debug("The passwords are a mismatch.");
      repeatPasswordComponent.errorMessage = i18next.t("passwordMismatch");
      repeatPasswordComponent.invalid = true;
      user.password = "";
    } else if (!passwordComponent.checkValidity() || !repeatPasswordComponent.checkValidity()) {
      console.debug("The passwords are invalid.");
      repeatPasswordComponent.errorMessage = i18next.t("passwordInvalid");
      repeatPasswordComponent.invalid = true;
      user.password = "";
    } else {
      repeatPasswordComponent.invalid = false;
      user.password = passwordComponent.value;
    }
  }

  protected async _regenerateSecret(ev: Event, user: UserModel, secretInputId: string) {
    const btnElem = ev.currentTarget as OrVaadinButton;
    const secretElem = this.shadowRoot.getElementById(secretInputId) as OrVaadinPasswordField;
    if (!btnElem || !secretElem) {
      showSnackbar(undefined, "errorOccurred");
      return;
    }
    btnElem.disabled = true;
    secretElem.disabled = true;
    const resetResponse = await manager.rest.api.UserResource.resetSecret(manager.displayRealm, user.id);
    if (resetResponse.data) {
      secretElem.value = resetResponse.data;
    }
    btnElem.disabled = false;
    secretElem.disabled = false;
  }

  protected _updateUserSelectedRoles(user: UserModel, suffix: string) {
    const roleCheckboxes = [
      ...((this.shadowRoot.getElementById("role-list-" + suffix) as HTMLDivElement).children as any),
    ] as OrVaadinCheckbox[];
    const implicitRoleNames = this.getImplicitUserRoles(user);
    roleCheckboxes.forEach((checkbox) => {
      const roleName = checkbox.label;
      const r = this._roles.find((role) => roleName === role.name);
      checkbox.disabled = !!implicitRoleNames.find((name) => r.name === name);
      checkbox.checked =
        !!user.roles.find((userRole) => userRole === r.name) ||
        implicitRoleNames.some((implicitRoleName) => implicitRoleName === r.name);
    });
  }

  protected getImplicitUserRoles(user: UserModel) {
    return this._compositeRoles
      .filter((role) => user.roles.some((ur) => ur === role.name))
      .flatMap((role) => role.compositeRoleIds)
      .map((id) => this._roles.find((r) => r.id === id).name);
  }

  protected getSingleUserView(
    user: UserModel,
    compositeRoleOptions: { value: any; label: string }[],
    realmRoleOptions: { value: any; label: string }[],
    suffix: string,
    readonly: boolean = true
  ): TemplateResult {
    return html`
      ${when(
        user.loaded || (user.roles && user.realmRoles),
        () => {
          return this.getSingleUserTemplate(user, compositeRoleOptions, realmRoleOptions, suffix, readonly);
        },
        () => {
          const getTemplate = async () => {
            await this.loadUser(user);
            return this.getSingleUserTemplate(user, compositeRoleOptions, realmRoleOptions, suffix, readonly);
          };
          const content: Promise<TemplateResult> = getTemplate();
          return html` ${until(content, html`${i18next.t("loading")}`)} `;
        }
      )}
    `;
  }

  protected getMQTTSessionTemplate(user: UserModel): TemplateResult {
    if (!this._sessionLoader) {
      this._sessionLoader = this.getSessionLoader(user);
    }

    return html`
      <div id="content" class="panel">
        <p class="panel-title">${i18next.t("mqttSessions")}</p>
        ${until(this._sessionLoader, html`${i18next.t("loading")}`)}
      </div>
    `;
  }

  protected async getSessionLoader(user: UserModel): Promise<TemplateResult> {
    const userSessionsResponse = await manager.rest.api.UserResource.getUserSessions(manager.displayRealm, user.id);

    if (
      !this.responseAndStateOK(() => userSessionsResponse.status === 200, userSessionsResponse, "loadFailedUserInfo")
    ) {
      return html``;
    }

    const cols = [i18next.t("address"), i18next.t("since"), ""];
    const rows = userSessionsResponse.data.map((session) => {
      return [
        session.remoteAddress,
        new Date(session.startTimeMillis),
        html`
          <or-vaadin-button
            @click=${() => {
              this.disconnectSession(user, session);
            }}
          >
            <or-translate value="disconnect"></or-translate>
          </or-vaadin-button>
        `,
      ];
    });
    if (rows.length < 1) {
      return html`<or-mwc-table
        .rows="${[["This user has no active MQTT sessions", null]]}"
        .config="${{ stickyFirstColumn: false }}"
        .columns="${cols}"
      ></or-mwc-table>`;
    }

    return html`<or-mwc-table
      id="session-table"
      .rows="${rows}"
      .config="${{ stickyFirstColumn: false }}"
      .columns="${cols}"
    ></or-mwc-table>`;
  }

  protected getSingleUserTemplate(
    user: UserModel,
    compositeRoleOptions: { value: any; label: string }[],
    realmRoleOptions: { value: any; label: string }[],
    suffix: string,
    readonly: boolean = true
  ): TemplateResult {
    const isServiceUser = user.serviceAccount;
    const isSameUser = user.username === manager.username;
    const isGatewayServiceUser = isServiceUser && user.username?.startsWith("gateway-");
    const implicitRoleNames = user.loaded ? this.getImplicitUserRoles(user) : [];
    return html`
            <div class="row">
                <div class="column">
                    <h5><or-translate value="details"></h5>
                    <!-- user details -->
                    <or-vaadin-text-field id="new-username-${suffix}"
                                          class="validate" minlength="3" maxlength="255"
                                          ?readonly=${!!user.id || readonly || (!isServiceUser && this._registrationEmailAsUsername)}
                                          ?required=${isServiceUser || !this._registrationEmailAsUsername}
                                          pattern="[^<>&&quot;'\\s\\v$%!#?§,;:*~\\/\\\\\\|^=\\[\\]\\{\\}\\(\\)\`\\p{Control}]+"
                                          value=${user.username} autocomplete="false"
                                          error-message=${i18next.t("invalidCharacters")}
                                          @change=${(ev: Event) => {
                                            user.username = (ev.currentTarget as HTMLInputElement).value;
                                            this.onUserChanged(suffix);
                                          }}>
                        <or-translate slot="label" value="username"></or-translate>
                    </or-vaadin-text-field>
                    <!-- if identity provider is set to use email as username, make it required -->
                    <or-vaadin-email-field id="new-email" class=${isServiceUser ? "hidden" : "validate"}
                                           ?readonly=${(!!user.id && this._registrationEmailAsUsername) || readonly}
                                           ?required=${!isServiceUser && this._registrationEmailAsUsername}
                                           value=${user.email} autocomplete="false"
                                           error-message=${i18next.t("invalidEmail")}
                                           @change=${(ev: Event) => {
                                             if (this._registrationEmailAsUsername) {
                                               user.username = (ev.currentTarget as HTMLInputElement).value;
                                             }
                                             user.email = (ev.currentTarget as HTMLInputElement).value;
                                             this.onUserChanged(suffix);
                                             this.requestUpdate(); // in case of username update, we trigger a state change
                                           }}>
                        <or-translate slot="label" value="email"></or-translate>
                    </or-vaadin-email-field>
                    <or-vaadin-text-field id="new-firstName" class="${isServiceUser ? "hidden" : "validate"}"
                                          ?readonly="${readonly}" minlength="1" maxlength="255"
                                          pattern='[^<>&"\\v$%!#?§;*~\\/\\\\\\|^=\\[\\]\\{\\}\\(\\)\\p{Control}]+'
                                          error-message=${i18next.t("invalidCharacters")}
                                          value=${user.firstName} autocomplete="false"
                                          @change=${(ev: Event) => {
                                            user.firstName = (ev.currentTarget as HTMLInputElement).value;
                                            this.onUserChanged(suffix);
                                          }}>
                        <or-translate slot="label" value="firstName"></or-translate>
                    </or-vaadin-text-field>
                    <or-vaadin-text-field id="new-surname" class="${isServiceUser ? "hidden" : "validate"}"
                                          ?readonly="${readonly}" minlength="1" maxlength="255"
                                          pattern='[^<>&"\\v$%!#?§;*~\\/\\\\\\|^=\\[\\]\\{\\}\\(\\)\\p{Control}]+'
                                          error-message=${i18next.t("invalidCharacters")}
                                          value=${user.lastName} autocomplete="false"
                                          @change=${(ev: Event) => {
                                            user.lastName = (ev.currentTarget as HTMLInputElement).value;
                                            this.onUserChanged(suffix);
                                          }}>
                        <or-translate slot="label" value="surname"></or-translate>
                    </or-vaadin-text-field>
                    <or-vaadin-text-field id="new-tag" class="validate"
                                          ?readonly=${readonly || isGatewayServiceUser}
                                          minlength="1" maxlength="255"
                                          value=${user.attributes?.Tag?.[0]} autocomplete="false"
                                          @change=${(ev: Event) => {
                                            // Ensure 'attributes' and 'Tag' exist before assigning
                                            if (!user.attributes) {
                                              user.attributes = {};
                                            }
                                            if (!Array.isArray(user.attributes.Tag)) {
                                              user.attributes.Tag = [];
                                            }
                                            user.attributes.Tag[0] = (ev.currentTarget as HTMLInputElement).value;
                                            this.onUserChanged(suffix);
                                          }}>
                        <or-translate slot="label" value="tag"></or-translate>
                    </or-vaadin-text-field>
                    <!-- password -->
                    <h5><or-translate value="password"></or-translate></h5>
                    ${
                      isServiceUser
                        ? html`
                            ${when(
                              user.secret,
                              () => html`
                                <or-vaadin-password-field
                                  id="new-password-${suffix}"
                                  readonly
                                  class="validate"
                                  value=${user.secret}
                                >
                                  <or-translate slot="label" value="secret"></or-translate>
                                </or-vaadin-password-field>
                                <or-vaadin-button
                                  ?disabled=${!user.id || readonly || isGatewayServiceUser}
                                  @click=${(ev: Event) => {
                                    this._regenerateSecret(ev, user, "new-password-" + suffix).catch(() =>
                                      showSnackbar(undefined, "errorOccurred")
                                    );
                                    this.onUserChanged(suffix);
                                  }}
                                >
                                  <or-translate value="regenerateSecret"></or-translate>
                                </or-vaadin-button>
                              `,
                              () => html` <or-translate value="generateSecretInfo"></or-translate> `
                            )}
                          `
                        : html`
                            <or-vaadin-password-field
                              id="new-password-${suffix}"
                              class="validate"
                              ?readonly=${readonly}
                              autocomplete="false"
                              minlength="1"
                              maxlength="255"
                              manual-validation
                              @change=${(ev: Event) => {
                                this._onPasswordChanged(user, suffix);
                                this.onUserChanged(suffix);
                              }}
                            >
                              <or-translate slot="label" value="password"></or-translate>
                            </or-vaadin-password-field>
                            <or-vaadin-password-field
                              id="new-repeatPassword-${suffix}"
                              class="validate"
                              ?readonly="${readonly}"
                              autocomplete="false"
                              minlength="1"
                              maxlength="255"
                              manual-validation
                              style="${this._passwordPolicy ? "margin-bottom: 0" : undefined}"
                              @change=${(ev: Event) => {
                                this._onPasswordChanged(user, suffix);
                                this.onUserChanged(suffix);
                              }}
                            >
                              <or-translate slot="label" value="repeatPassword"></or-translate>
                            </or-vaadin-password-field>
                            ${when(this._passwordPolicy, () => until(this._getPasswordPolicyTemplate(user, this._passwordPolicy)))}
                          `
                    }
                </div>

                <div class="column">
                    <h5>${i18next.t("settings")}</h5>
                    <!-- enabled -->
                    <div style="height: 56px; display: flex; align-items: center; margin-bottom: 20px;">
                        <or-vaadin-checkbox class="validate"
                                            ?readonly=${readonly || isGatewayServiceUser}
                                            ?checked=${user.enabled}
                                            @change=${(ev: Event) => {
                                              user.enabled = (ev.currentTarget as HTMLInputElement).checked;
                                              this.onUserChanged(suffix);
                                            }}>
                            <label slot="label"><or-translate value="active"></or-translate></label>
                        </or-vaadin-checkbox>
                    </div>

                    <!-- realm roles -->
                    <or-vaadin-multi-select-combo-box class="validate"
                                                      ?readonly="${readonly || isSameUser || isGatewayServiceUser}"
                                                      .items=${realmRoleOptions}
                                                      .selectedItems=${user.realmRoles.map((r) => realmRoleOptions.find((o) => o.value === r))}
                                                      @change=${(ev: Event) => {
                                                        this.onUserChanged(suffix);
                                                        user.realmRoles = (
                                                          ev.currentTarget as OrVaadinMultiSelectComboBox
                                                        ).selectedItems.map((i) => i.value);
                                                      }}>
                        <or-translate slot="label" value="realm_role_plural"></or-translate>
                    </or-vaadin-multi-select-combo-box>

                    <!-- composite client roles -->
                    <or-vaadin-multi-select-combo-box class="validate"
                                                      ?readonly=${readonly || isSameUser || isGatewayServiceUser}
                                                      .items=${compositeRoleOptions}
                                                      .selectedItems=${user.roles
                                                        ?.filter((r) =>
                                                          this._compositeRoles.some((cr) => cr.name === r)
                                                        )
                                                        ?.map((r) => compositeRoleOptions.find((o) => o.value === r))}
                                                      @change=${(ev: Event) => {
                                                        user.roles = (
                                                          ev.currentTarget as OrVaadinMultiSelectComboBox
                                                        ).selectedItems.map((i) => i.value);
                                                        console.debug(user.roles);
                                                        this._updateUserSelectedRoles(user, suffix);
                                                        this.onUserChanged(suffix);
                                                      }}>
                        <or-translate slot="label" value="manager_role_plural"></or-translate>
                    </or-vaadin-multi-select-combo-box>

                    <!-- roles -->
                    <div style="display:flex;flex-wrap:wrap;margin-bottom: 20px;"
                         id="role-list-${suffix}">
                        ${this._roles.map((r) => {
                          return html`
                            <or-vaadin-checkbox
                              class="validate"
                              style="flex: 0 1 160px; overflow: hidden; margin: 4px 0;"
                              ?disabled=${readonly || implicitRoleNames.find((name) => r.name === name) || isGatewayServiceUser}
                              title=${r.description}
                              label=${r.name}
                              ?checked=${!!user.roles.find((userRole) => userRole === r.name) || implicitRoleNames.some((implicitRoleName) => implicitRoleName === r.name)}
                              @change=${(ev: Event) => {
                                if ((ev.currentTarget as HTMLInputElement).checked) {
                                  user.roles.push(r.name);
                                } else {
                                  user.roles = user.roles.filter((ur) => ur !== r.name);
                                }
                                this.onUserChanged(suffix);
                              }}
                            >
                            </or-vaadin-checkbox>
                          `;
                        })}
                    </div>

                    <!-- Asset-User links -->
                    <div>
                        <or-translate value="linkedAssets"></or-translate>
                        <or-vaadin-button ?disabled=${readonly || isGatewayServiceUser} style="margin-left: 4px;"
                                          @click=${(ev: Event) => this._openAssetSelector(ev, user, readonly, suffix)}>
                            <span>${i18next.t("selectRestrictedAssets", { number: user.userAssetLinks.length })}</span>
                        </or-vaadin-button>
                    </div>
                </div>
            </div>
            <!-- Bottom controls (save/update and delete button) -->
            ${when(
              !(readonly && !this._saveUserPromise),
              () => html`
                <div class="row button-row">
                  ${when(
                    !isSameUser && !isGatewayServiceUser && user.id,
                    () => html`
                      <or-vaadin-button ?disabled=${readonly} @click=${() => this._deleteUser(user)}>
                        <or-translate value="delete"></or-translate>
                      </or-vaadin-button>
                    `
                  )}
                  <div style="display: flex; align-items: center; gap: 16px; margin: 0 0 0 auto;">
                    <!-- Button disabled until an input has input, and by that a valid check is done -->
                    <or-vaadin-button
                      id="savebtn-${suffix}"
                      theme="primary"
                      disabled
                      @click=${() => {
                        let error: { status?: number; text: string };
                        this._saveUserPromise = this._createUpdateUser(user, user.id ? "update" : "create")
                          .then((result) => {
                            // Return to the users page on successful user create/update
                            if (result) {
                              showSnackbar(undefined, "saveUserSucceeded");
                              this.reset();
                            }
                          })
                          .catch((ex) => {
                            console.error(ex);
                            if (isAxiosError(ex)) {
                              error = {
                                status: ex.response.status,
                                text:
                                  ex.response.status == 403
                                    ? i18next.t("userAlreadyExists")
                                    : i18next.t("errorOccurred"),
                              };
                            }
                          })
                          .finally(() => {
                            this._saveUserPromise = undefined;
                            if (error) {
                              this.updateComplete.then(() => {
                                showSnackbar(undefined, error.text);
                                if (error.status === 403) {
                                  const elem = this.shadowRoot.getElementById(
                                    "username-" + suffix
                                  ) as OrVaadinTextField;
                                  elem.errorMessage = error.text;
                                  (elem.shadowRoot.getElementById("elem") as HTMLInputElement).reportValidity(); // manually reporting was required since we're not editing the username at all.
                                  this.onUserChanged(suffix); // onUserChanged to trigger validation of all fields again.
                                }
                              });
                            }
                          });
                      }}
                    >
                      <or-translate value=${user.id ? "save" : "create"}></or-translate>
                    </or-vaadin-button>
                  </div>
                </div>
              `
            )}
        `;
  }

  protected async _getPasswordPolicyTemplate(
    user: UserModel,
    passwordPolicy = this._passwordPolicy
  ): Promise<TemplateResult> {
    const policyMap = new Map(
      passwordPolicy.map((policyStr) => {
        const name = policyStr.split("(")[0];
        const value = policyStr.split("(")[1].split(")")[0];
        return [name, value];
      })
    );
    const policies = Array.from(policyMap.keys());
    const policyTexts: TemplateResult[] = [];

    // Minimum / maximum length warning
    if (policies.includes("length") && policies.includes("maxLength")) {
      policyTexts.push(
        html`<or-translate
          value="password-policy-invalid-length"
          .options="${{ 0: policyMap.get("length"), 1: policyMap.get("maxLength") }}"
        ></or-translate>`
      );
    } else if (policies.includes("length")) {
      policyTexts.push(
        html`<or-translate
          value="password-policy-invalid-length-too-short"
          .options="${{ 0: policyMap.get("length") }}"
        ></or-translate>`
      );
    } else if (policies.includes("maxLength")) {
      policyTexts.push(
        html`<or-translate
          value="password-policy-invalid-length-too-long"
          .options="${{ 0: policyMap.get("maxLength") }}"
        ></or-translate>`
      );
    }

    // Special characters
    if (policies.includes("specialChars")) {
      const value = policyMap.get("specialChars");
      const translation = value == "1" ? "password-policy-special-chars-single" : "password-policy-special-chars";
      policyTexts.push(html`<or-translate value="${translation}" .options="${{ 0: value }}"></or-translate>`);
    }

    // Digits/numbers
    if (policies.includes("digits")) {
      const value = policyMap.get("digits");
      const translation = value == "1" ? "password-policy-digits-single" : "password-policy-digits";
      policyTexts.push(html`<or-translate value="${translation}" .options="${{ 0: value }}"></or-translate>`);
    }

    // Uppercase / lowercase letters
    if (policies.includes("upperCase")) {
      const value = policyMap.get("upperCase");
      const translation = value == "1" ? "password-policy-uppercase-single" : "password-policy-uppercase";
      policyTexts.push(html`<or-translate value="${translation}" .options="${{ 0: value }}"></or-translate>`);
    }

    // Warn for recently used passwords
    if (policies.includes("passwordHistory")) {
      policyTexts.push(html`<or-translate value="password-policy-recently-used"></or-translate>`);
    }

    // Cannot be username and/or email
    if (policies.includes("notUsername") && policies.includes("notEmail")) {
      policyTexts.push(html`<or-translate value="password-policy-not-email-username"></or-translate>`);
    } else if (policies.includes("notUsername")) {
      policyTexts.push(html`<or-translate value="password-policy-not-username"></or-translate>`);
    } else if (policies.includes("notEmail")) {
      policyTexts.push(html`<or-translate value="password-policy-not-email"></or-translate>`);
    }

    return html`
      <ul>
        ${map(policyTexts, (text) => html`<li>${text}</li>`)}
      </ul>
    `;
  }

  // Reset selected user or creation page, and go back to the list
  protected reset() {
    this.userId = undefined;
    this.creationState = undefined;
    this._userFilter = this.getDefaultUserFilter(false);
    this._serviceUserFilter = this.getDefaultUserFilter(true);
  }

  public stateChanged(state: AppStateKeyed) {
    if (state.app.page == "users") {
      // it seems that the check is necessary here
      this.realm = state.app.realm;
      this.userId = state.app.params && state.app.params.id ? state.app.params.id : undefined;

      // If the user creation parameters have changed from `/regular` to `/serviceuser` (or the other way around)
      // we need to update the creationState to a modified user model.
      const userType: string | undefined = state.app.params?.type;
      if (userType && this.creationState?.userModel.serviceAccount !== (userType === "serviceuser")) {
        this.creationState = { userModel: this.getNewUserModel(userType === "serviceuser") };
      }
    }
  }

  protected _updateRoute(silent: boolean = false) {
    router.navigate(getUsersRoute(this.userId), {
      callHooks: !silent,
      callHandler: !silent,
    });
  }

  protected _updateNewUserRoute(silent: boolean = false) {
    router.navigate(getNewUserRoute(this.creationState?.userModel.serviceAccount), {
      callHooks: !silent,
      callHandler: !silent,
    });
  }

  protected disconnectSession(user: UserModel, session: UserSession) {
    this._sessionLoader = manager.rest.api.UserResource.disconnectUserSession(manager.displayRealm, session.ID)
      .then(() => showSnackbar(undefined, "userDisconnected"))
      .catch((e) => {
        showSnackbar(undefined, "userDisconnectFailed");
        console.error("Failed to disconnect user", e);
      })
      .then(() => this.getSessionLoader(user));
  }
}
