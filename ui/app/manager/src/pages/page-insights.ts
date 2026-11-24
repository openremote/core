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
import { css, html, type TemplateResult } from "lit";
import { customElement, property, query, state } from "lit/decorators.js";
import { Page, type PageProvider, router } from "@openremote/or-app";
import type { AppStateKeyed } from "@openremote/or-app";
import type { Store } from "@reduxjs/toolkit";
import { createSelector } from "reselect";
import { manager } from "@openremote/core";
import "@openremote/or-dashboard-builder";
import { ClientRole, type Dashboard } from "@openremote/model";
import { getInsightsRoute } from "../routes";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";

export interface PageInsightsConfig {}

export function pageInsightsProvider(
  store: Store<AppStateKeyed>,
  config?: PageInsightsConfig
): PageProvider<AppStateKeyed> {
  return {
    name: "insights",
    routes: ["insights", "insights/:editMode", "insights/:editMode/:id"],
    pageCreator: () => {
      const page = new PageInsights(store);
      if (config) page.config = config;
      return page;
    },
  };
}

@customElement("page-insights")
export class PageInsights extends Page<AppStateKeyed> {
  static get styles() {
    // language=CSS
    return css`
      :host {
        overflow: hidden;
      }

      #builder {
        z-index: 0;
        background: transparent;
      }
    `;
  }

  @property()
  public config?: PageInsightsConfig;

  @property()
  protected _editMode: boolean = true;

  @property()
  protected _fullscreen: boolean = true;

  @property()
  private _dashboardId: string;

  @state()
  private _userId?: string;

  /* ------------------ */

  updated(changedProperties: Map<string, any>) {
    if (changedProperties.has("_dashboardId")) {
      this._updateRoute();
    }
  }

  protected _realmSelector = (state: AppStateKeyed) => state.app.realm || manager.displayRealm;

  get name(): string {
    return "insights";
  }

  protected getRealmState = createSelector([this._realmSelector], async () => {
    this._updateRoute(true);
    this.requestUpdate();
  });

  constructor(store: Store<AppStateKeyed>) {
    super(store);
    manager.rest.api.UserResource.getCurrent()
      .then((response: any) => {
        this._userId = response.data.id;
      })
      .catch((ex) => {
        console.error(ex);
        showSnackbar(undefined, "errorOccurred");
      });
  }

  public connectedCallback() {
    super.connectedCallback();
  }

  protected render(): TemplateResult | void {
    return html`
      <div style="width: 100%;">
        <or-dashboard-builder
          id="builder"
          .editMode="${this._editMode}"
          .fullscreen="${this._fullscreen}"
          .selectedId="${this._dashboardId}"
          .realm="${manager.displayRealm}"
          .userId="${this._userId}"
          .readonly="${!manager.hasRole(ClientRole.WRITE_INSIGHTS)}"
          @selected="${(event: CustomEvent) => {
            this._dashboardId = (event.detail as Dashboard)?.id;
          }}"
          @editToggle="${(event: CustomEvent) => {
            this._editMode = event.detail;
            this._fullscreen = true;
            this._updateRoute(false);
          }}"
          @fullscreenToggle="${(event: CustomEvent) => {
            this._fullscreen = event.detail;
          }}"
        ></or-dashboard-builder>
      </div>
    `;
  }

  stateChanged(state: AppStateKeyed) {
    // State is only utilized for initial loading
    this.getRealmState(state); // Order is important here!
    this._editMode =
      state.app.params && state.app.params.editMode
        ? state.app.params.editMode === "true" && manager.hasRole(ClientRole.WRITE_INSIGHTS)
        : false;
    this._dashboardId = state.app.params && state.app.params.id ? state.app.params.id : undefined;
  }

  protected _updateRoute(silent: boolean = true) {
    // This if condition prevents updating the URL to /insights/false when no edit mode or dashboard is selected.
    // This avoids polluting the browser history and ensures users don't get redirected to Insights on refresh.
    if (this._editMode || this._dashboardId) {
      router.navigate(getInsightsRoute(this._editMode, this._dashboardId), {
        callHooks: !silent,
        callHandler: !silent,
      });
    }
  }
}
