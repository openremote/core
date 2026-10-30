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
import { createSelector } from "reselect";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import { customElement, property, state } from "lit/decorators.js";
import manager, { DefaultColor3 } from "@openremote/core";
import "@openremote/or-components/or-panel";
import "@openremote/or-translate";
import type { Store } from "@reduxjs/toolkit";
import { type AppStateKeyed, Page, type PageProvider } from "@openremote/or-app";
import { ClientRole, type Realm } from "@openremote/model";
import { i18next } from "@openremote/or-translate";
import type { OrIcon } from "@openremote/or-icon";
import {
  getConfirmDialogContent,
  type OrVaadinConfirmDialog,
  showConfirmDialog,
} from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import type { OrVaadinTextField } from "@openremote/or-vaadin-components/or-vaadin-text-field";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import type { OrVaadinToggle } from "@openremote/or-vaadin-components/or-vaadin-toggle";

const tableStyle = require("@material/data-table/dist/mdc.data-table.css");

export function pageRealmsProvider(store: Store<AppStateKeyed>): PageProvider<AppStateKeyed> {
  return {
    name: "realms",
    routes: ["realms"],
    pageCreator: () => {
      return new PageRealms(store);
    },
  };
}

@customElement("page-realms")
export class PageRealms extends Page<AppStateKeyed> {
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
          overflow: auto;
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
          width: calc(100% - 90px);
          max-width: 1310px;
          background-color: white;
          border: 1px solid #e5e5e5;
          border-radius: 5px;
          position: relative;
          margin: 0 auto;
          padding: 12px 24px 24px;
        }

        .panel-title {
          display: flex;
          align-items: center;
          text-transform: uppercase;
          font-weight: bolder;
          line-height: 1em;
          color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
          margin-bottom: 10px;
          margin-top: 0;
          flex: 0 0 auto;
          letter-spacing: 0.025em;
          min-height: 36px;
        }

        .panel-title p {
          margin: 0;
        }

        #table-roles,
        #table-roles table {
          width: 100%;
          white-space: nowrap;
        }

        .mdc-data-table__row {
          border-top-color: #d3d3d3;
        }

        td,
        th {
          width: 25%;
          border: none;
        }

        td.large,
        th.large {
          width: 50%;
        }

        .realm-container {
          flex-direction: row;
          overflow: hidden;
          max-height: 0;
          transition: max-height 0.25s ease-out;
          padding: 0 16px;
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
          margin: 10px 0;
          flex: 1 1 0;
          gap: 16px;
        }

        .column {
          display: flex;
          flex-direction: column;
          margin: 0px;
          flex: 1 1 0;
          gap: 16px;
        }

        .mdc-data-table__header-cell {
          font-weight: bold;
          color: ${unsafeCSS(DefaultColor3)};
        }

        .mdc-data-table__header-cell:first-child {
          padding-left: 36px;
        }

        .mdc-data-table__row {
          cursor: pointer;
        }

        .padded-cell {
          overflow-wrap: break-word;
          word-wrap: break-word;
        }

        .realm-row td {
          padding: 0;
        }

        .realm-row {
          max-width: 0px;
        }

        .realm-row.expanded .realm-container {
          max-height: 1000px;
          max-width: none;
          transition: max-height 1s ease-in;
        }

        .button {
          cursor: pointer;
          display: flex;
          flex-direction: row;
          align-content: center;
          padding: 16px;
          align-items: center;
          font-size: 14px;
          text-transform: uppercase;
          color: var(--or-app-color4);
        }

        .button or-icon {
          --or-icon-fill: var(--or-app-color4);
        }

        @media screen and (max-width: 1024px) {
          .row {
            display: block;
            flex-direction: column;
          }
        }

        @media screen and (max-width: 768px) {
          #title {
            padding: 0;
            width: 100%;
          }
          .panel {
            border-left: 0px;
            border-right: 0px;
            width: calc(100% - 48px);
            border-radius: 0;
          }
          .hide-mobile {
            display: none;
          }
          td,
          th {
            width: 50%;
          }
        }
      `,
    ];
  }

  @property()
  protected _realms: Realm[] = [];

  @state()
  protected _realmFilter: (realms: Realm[]) => Realm[] = (realms) => realms;

  protected _realmSelector = (state: AppStateKeyed) => state.app.realm || manager.displayRealm;

  get name(): string {
    return "realm_plural";
  }

  protected _onRealmNameChanged(e: Event, realm: Realm): void {
    const elem = e.currentTarget as OrVaadinTextField;
    if (!elem.checkValidity()) {
      console.debug("The realm name was invalid.");
      elem.errorMessage = i18next.t("invalidRealm");
      elem.invalid = true;
      return;
    }
    const isDuplicate = this._realms.some((r) => r !== realm && r.name === elem.value);
    if (isDuplicate) {
      console.debug("The realm name was a duplicate.");
      elem.errorMessage = i18next.t("realmAlreadyExists");
      elem.invalid = true;
      return;
    }
    elem.invalid = false;
    realm.name = elem.value;
    this.requestUpdate();
  }

  protected getRealmState = createSelector([this._realmSelector], async (realm) => {
    this.requestUpdate();
  });

  constructor(store: Store<AppStateKeyed>) {
    super(store);
    this._getRealms();
  }

  public shouldUpdate(_changedProperties: PropertyValues): boolean {
    if (_changedProperties.has("realm")) {
      this._getRealms();
    }

    return super.shouldUpdate(_changedProperties);
  }

  protected render(): TemplateResult | void {
    if (!manager.authenticated) {
      return html` <or-translate value="notAuthenticated"></or-translate> `;
    }

    if (!manager.isKeycloak()) {
      return html` <or-translate value="notSupported"></or-translate> `;
    }

    const readonly = !manager.hasRole(ClientRole.WRITE_ADMIN);
    return html`
      <div id="wrapper">
        <div id="title"><or-icon icon="domain"></or-icon>${i18next.t("realm_plural")}</div>
        <div class="panel">
          <div class="panel-title" style="justify-content: space-between;">
            <p><or-translate value="realm_plural"></or-translate></p>
            <or-vaadin-text-field
              placeholder=${i18next.t("search")}
              style="width: 240px;"
              @input=${(ev: InputEvent) => this.onRealmSearch(ev)}
            >
              <or-icon slot="suffix" icon="magnify"></or-icon>
            </or-vaadin-text-field>
          </div>
          <div id="table-roles" class="mdc-data-table">
            <table class="mdc-data-table__table" aria-label="realm list">
              <thead>
                <tr class="mdc-data-table__header-row">
                  <th class="mdc-data-table__header-cell" role="columnheader" scope="col">
                    <or-translate value="name"></or-translate>
                  </th>
                  <th class="mdc-data-table__header-cell" role="columnheader" scope="col">
                    <or-translate value="displayName"></or-translate>
                  </th>
                  <th class="mdc-data-table__header-cell hide-mobile large" role="columnheader" scope="col">
                    <or-translate value="status"></or-translate>
                  </th>
                </tr>
              </thead>
              <tbody class="mdc-data-table__content">
                ${this._realmFilter(this._realms).map((realm, index) => {
                  return html`
                    <tr
                      id="mdc-data-table-row-${index}"
                      class="mdc-data-table__row"
                      @click="${(ev) => this.expanderToggle(ev, index)}"
                    >
                      <td class="padded-cell mdc-data-table__cell">
                        <or-icon id="mdc-data-table-icon-${index}" icon="chevron-right"></or-icon>
                        <span>${realm.name}</span>
                      </td>
                      <td class="padded-cell mdc-data-table__cell">${realm.displayName}</td>
                      <td class="padded-cell hide-mobile mdc-data-table__cell large">
                        ${realm.enabled ? "Enabled" : "Disabled"}
                      </td>
                    </tr>
                    <tr id="realm-row-${index}" class="realm-row${!realm.id ? " expanded" : ""}">
                      <td colspan="100%">
                        <div class="realm-container">
                          <div class="row">
                            <div class="column">
                              <or-vaadin-text-field
                                ?readonly=${realm.id}
                                minlength="2"
                                maxlength="255"
                                required
                                manual-validation
                                value=${realm.name}
                                @change=${(ev: Event) => this._onRealmNameChanged(ev, realm)}
                              >
                                <or-translate slot="label" value="realm"></or-translate>
                              </or-vaadin-text-field>
                              <or-vaadin-text-field
                                ?readonly=${readonly}
                                maxlength="255"
                                value=${realm.loginTheme}
                                @change=${(ev: Event) => (realm.loginTheme = (ev.currentTarget as HTMLInputElement).value)}
                              >
                                <or-translate slot="label" value="loginTheme"></or-translate>
                              </or-vaadin-text-field>
                              <or-vaadin-toggle
                                ?readonly="${readonly}"
                                ?checked="${realm.resetPasswordAllowed}"
                                @change="${(e: Event) => (realm.resetPasswordAllowed = (e.currentTarget as OrVaadinToggle).checked)}"
                              >
                                <or-translate slot="label" value="resetPasswordAllowed"></or-translate>
                              </or-vaadin-toggle>
                              <or-vaadin-toggle
                                ?readonly="${readonly}"
                                ?checked="${realm.enabled}"
                                @change="${(e: Event) => (realm.enabled = (e.currentTarget as OrVaadinToggle).checked)}"
                              >
                                <or-translate slot="label" value="enabled"></or-translate>
                              </or-vaadin-toggle>
                              <or-vaadin-toggle
                                ?readonly="${readonly}"
                                ?checked="${realm.rememberMe}"
                                @change="${(e: Event) => (realm.rememberMe = (e.currentTarget as OrVaadinToggle).checked)}"
                              >
                                <or-translate slot="label" value="rememberMe"></or-translate>
                              </or-vaadin-toggle>
                            </div>
                            <div class="column">
                              <or-vaadin-text-field
                                ?readonly=${readonly}
                                minlength="1"
                                maxlength="255"
                                required
                                value=${realm.displayName}
                                error-message=${i18next.t("invalidRealm")}
                                @change=${(ev: Event) => {
                                  realm.displayName = (ev.currentTarget as HTMLInputElement).value;
                                  this.requestUpdate();
                                }}
                              >
                                <or-translate slot="label" value="displayName"></or-translate>
                              </or-vaadin-text-field>
                              <or-vaadin-text-field
                                ?readonly=${readonly}
                                maxlength="255"
                                value=${realm.emailTheme}
                                @change=${(ev: Event) => (realm.emailTheme = (ev.currentTarget as HTMLInputElement).value)}
                              >
                                <or-translate slot="label" value="emailTheme"></or-translate>
                              </or-vaadin-text-field>
                            </div>
                          </div>

                          <div class="row" style="justify-content: space-between; margin-bottom: 8px;">
                            ${
                              realm.id && !readonly
                                ? html`
                                    ${
                                      realm.name !== "master" && manager.isSuperUser()
                                        ? html`
                                            <or-vaadin-button
                                              ?disabled=${manager.displayRealm === realm.name}
                                              @click=${() => this._deleteRealm(realm)}
                                            >
                                              <or-translate value="delete"></or-translate>
                                            </or-vaadin-button>
                                          `
                                        : ``
                                    }
                                    <or-vaadin-button
                                      theme="primary"
                                      style="margin-left: auto;"
                                      @click=${() => this._updateRealm(realm)}
                                    >
                                      <or-translate value="save"></or-translate>
                                    </or-vaadin-button>
                                  `
                                : !readonly
                                  ? html`
                                      <or-vaadin-button
                                        @click=${() => {
                                          this._realms.splice(-1, 1);
                                          this._realms = [...this._realms];
                                        }}
                                      >
                                        <or-translate value="cancel"></or-translate>
                                      </or-vaadin-button>
                                      <or-vaadin-button
                                        theme="primary"
                                        ?disabled=${!realm.name || !realm.displayName || this._realms.some((r) => r !== realm && r.name === realm.name)}
                                        @click=${() => this._createRealm(realm)}
                                      >
                                        <or-translate value="create"></or-translate>
                                      </or-vaadin-button>
                                    `
                                  : ``
                            }
                          </div>
                        </div>
                      </td>
                    </tr>
                  `;
                })}
                ${
                  this._realms.length > 0 && !!this._realms[this._realms.length - 1].id && !readonly
                    ? html`
                        <tr class="mdc-data-table__row">
                          <td colspan="100%">
                            <a class="button" @click="${() => (this._realms = [...this._realms, { enabled: true }])}"
                              ><or-icon icon="plus"></or-icon>${i18next.t("add")} ${i18next.t("realm")}</a
                            >
                          </td>
                        </tr>
                      `
                    : ``
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>
    `;
  }

  public stateChanged(state: AppStateKeyed) {
    this.getRealmState(state);
  }

  protected async _getRealms() {
    const response = await manager.rest.api.RealmResource.getAll();
    this._realms = response.data;
    return this._realms;
  }

  private async _updateRealm(realm) {
    const response = await manager.rest.api.RealmResource.update(realm.name, realm);
    if (response.status === 204) {
      showSnackbar(undefined, "saveRealmSucceeded");
      this.dispatchEvent(new CustomEvent("realms-changed", { bubbles: true, composed: true }));
    } else {
      showSnackbar(undefined, "saveRealmFailed");
    }
  }

  private async _createRealm(realm) {
    const response = await manager.rest.api.RealmResource.create(realm);
    if (response.status === 204) {
      showSnackbar(undefined, "saveRealmSucceeded");
      await this._getRealms();
      this.dispatchEvent(new CustomEvent("realms-changed", { bubbles: true, composed: true }));
    } else {
      showSnackbar(undefined, "saveRealmFailed");
    }
  }

  private _deleteRealm(realm: Realm) {
    let confirmedName = "";
    const okBtnRef: Ref<OrVaadinButton> = createRef();

    const doDelete = async (dialog?: OrVaadinConfirmDialog) => {
      if (okBtnRef.value!.disabled) return;
      try {
        await manager.rest.api.RealmResource.delete(realm.name);
        this._realms = this._realms.filter((r) => r !== realm);
        this.dispatchEvent(new CustomEvent("realms-changed", { bubbles: true, composed: true }));
      } catch (e) {
        const snackbarKey = (e as any)?.response?.status === 409 ? "realmDeleteFailedHasAssets" : "realmDeleteFailed";
        showSnackbar(undefined, snackbarKey, "dismiss");
      } finally {
        dialog?.close();
      }
    };

    const inputChanged = (value: string) => {
      confirmedName = value;
      if (okBtnRef.value) {
        okBtnRef.value.disabled = confirmedName !== realm.name;
      }
    };

    const dialogContent = html`<div>
      <p style="text-align: justify; font-weight: bold;">
        ${i18next.t("realmDeleteConfirm", { realmName: realm.name })}
      </p>
      <or-vaadin-text-field @input=${(ev: Event) => inputChanged((ev.currentTarget as HTMLInputElement).value)}>
        <or-translate slot="label" value="realm"></or-translate>
      </or-vaadin-text-field>
    </div>`;

    const dialog = showConfirmDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-confirm-dialog @confirm=${() => doDelete(dialog)}>
          ${getConfirmDialogContent(
            "error",
            "deleteRealmConfirm",
            dialogContent,
            html`
              <or-vaadin-button theme="primary error" ${ref(okBtnRef)} disabled slot="confirm-button">
                <or-translate value="delete"></or-translate>
              </or-vaadin-button>
            `,
            "cancel"
          )}
        </or-vaadin-confirm-dialog>
      `
    );
  }

  protected onRealmSearch(ev: InputEvent) {
    const value = (ev.target as HTMLInputElement).value?.toLowerCase();
    if (!value) {
      this._realmFilter = (realms) => realms;
    } else {
      this._realmFilter = (realms) =>
        realms.filter(
          (r) =>
            (r.name as string)?.toLowerCase().includes(value) ||
            (r.displayName as string)?.toLowerCase().includes(value)
        );
    }
  }

  private expanderToggle(ev: MouseEvent, index: number) {
    const metaRow = this.shadowRoot.getElementById("realm-row-" + index);
    const expanderIcon = this.shadowRoot.getElementById("mdc-data-table-icon-" + index) as OrIcon;
    if (metaRow.classList.contains("expanded")) {
      metaRow.classList.remove("expanded");
      expanderIcon.icon = "chevron-right";
    } else {
      metaRow.classList.add("expanded");
      expanderIcon.icon = "chevron-down";
    }
  }
}
