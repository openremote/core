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
import { html, type TemplateResult } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property } from "lit/decorators.js";
import { when } from "lit/directives/when.js";
import type { ManagerAppConfig, MapConfig } from "@openremote/model";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import "@openremote/or-components/or-loading-indicator";
import "./or-conf-map/or-conf-map-card";
import "./or-conf-realm/or-conf-realm-card";
import type { OrConfRealmCard } from "./or-conf-realm/or-conf-realm-card";
import type { OrConfMapCard } from "./or-conf-map/or-conf-map-card";
import type { OrVaadinSelect } from "@openremote/or-vaadin-components/or-vaadin-select";
import { createRef, ref, type Ref } from "lit/directives/ref.js";

@customElement("or-conf-panel")
export class OrConfPanel extends OrElement {
  @property()
  public config?: MapConfig | ManagerAppConfig = {};

  @property()
  public realmOptions: { name: string; displayName: string; canDelete: boolean }[] = [];

  protected _addedRealm: null | string = null;

  public getCardElements(): OrConfRealmCard[] | OrConfMapCard[] | undefined {
    if (this.isManagerConfig(this.config)) {
      return Array.from(this.shadowRoot?.querySelectorAll("or-conf-realm-card")) as OrConfRealmCard[];
    } else if (this.isMapConfig(this.config)) {
      return Array.from(this.shadowRoot?.querySelectorAll("or-conf-map-card")) as OrConfMapCard[];
    }
  }

  protected getRealmsProperty(config: unknown): { [index: string]: any } | undefined {
    if (this.isManagerConfig(config)) {
      return config.realms;
    } else if (this.isMapConfig(config)) {
      return config.options;
    }
    return undefined;
  }

  protected isMapConfig(object: unknown): object is MapConfig {
    if (typeof object === "object" && "options" in object) {
      const keys = Object.keys(object.options);
      // Instead of looking for the exact same entries, ensure that at least one of them can be mapped.
      // This also shows realm configs that don't have parent realms, but are still existing in the mapsettings.json
      return this.realmOptions.filter((o) => keys.includes(o.name)).length > 0;
    }
    return false;
  }

  protected isManagerConfig(object: any): object is ManagerAppConfig {
    return "realms" in object;
  }

  protected notifyConfigChange(config: MapConfig | ManagerAppConfig) {
    this.dispatchEvent(new CustomEvent("change", { detail: config }));
  }

  /* ------------------------------ */

  render(): TemplateResult {
    // Define the type of config
    let type: "managerconfig" | "mapconfig" | undefined;
    if (this.isManagerConfig(this.config)) {
      type = "managerconfig";
    } else if (this.isMapConfig(this.config)) {
      type = "mapconfig";
    }

    // Render the panels
    const realmConfigs = this.getRealmsProperty(this.config);
    const availableRealms = this.getAvailableRealms(this.config, this.realmOptions);
    return html`
      <div class="panels">
        ${Object.entries(realmConfigs === undefined ? {} : realmConfigs).map(([key, value]) => {
          const realmOption = this.realmOptions.find((r) => r.name === key);
          switch (type) {
            case "managerconfig":
              return html`
                <or-conf-realm-card
                  .expanded="${this._addedRealm === key}"
                  .name="${key}"
                  .realm="${value}"
                  .canRemove="${realmOption?.canDelete}"
                  @change="${() => this.notifyConfigChange(this.config)}"
                  @remove="${() => this._removeRealm(key)}"
                ></or-conf-realm-card>
              `;
            case "mapconfig":
              return html`
                <or-conf-map-card
                  .expanded="${this._addedRealm === key}"
                  .name="${key}"
                  .map="${value}"
                  .canRemove="${realmOption?.canDelete}"
                  @change="${() => this.notifyConfigChange(this.config)}"
                  @remove="${() => this._removeRealm(key)}"
                ></or-conf-map-card>
              `;
            default:
              return html`Unknown error.`;
          }
        })}
      </div>
      <!-- Show an "ADD REALM" button if there are realms available to be added -->
      <div style="display: flex; justify-content: space-between;">
        ${when(
          availableRealms.length > 0,
          () => html`
            <or-vaadin-button id="btn-add-realm" @click=${() => this._showAddingRealmDialog()}>
              <or-icon slot="prefix" icon="plus"></or-icon>
              <or-translate
                value=${type === "mapconfig" ? "configuration.addMapCustomization" : "configuration.addRealmCustomization"}
              ></or-translate>
            </or-vaadin-button>
          `
        )}
      </div>
    `;
  }

  /* ----------------------------------- */

  protected _removeRealm(realm: string) {
    const realms = this.getRealmsProperty(this.config);
    if (realms) {
      delete realms[realm];
      this.requestUpdate("config");
      this.notifyConfigChange(this.config);
    } else {
      console.error("No config found when attempting to remove realm.");
    }
  }

  // Filter the list of realms that are not present in the config.
  // Most used for the "add realm" dialog, to hide the realms that are already present.
  protected getAvailableRealms(
    config?: ManagerAppConfig | MapConfig,
    realmOptions?: { name: string; displayName: string; canDelete: boolean }[]
  ): { name: string; displayName: string; canDelete: boolean }[] {
    const realms = this.getRealmsProperty(config);
    if (realms) {
      return realmOptions
        .filter((r) => {
          if (r.name in realms) {
            return null;
          }
          return r;
        })
        .sort((a, b) => {
          if (a.displayName && b.displayName) {
            return a.displayName > b.displayName ? 1 : -1;
          }
          return -1;
        });
    } else {
      console.error("Could not filter available realms!");
      return [];
    }
  }

  /* ------------------------------- */

  // Show the dialog to "add realm", that allows users to override realm options.
  protected _showAddingRealmDialog() {
    this._addedRealm = null;
    let dialog: OrVaadinDialog | undefined;
    const onOk = () => {
      if (this._addedRealm) {
        let realms = this.getRealmsProperty(this.config);
        if (!realms) {
          realms = {};
        }
        if (this.isManagerConfig(this.config)) {
          realms[this._addedRealm] = {}; // empty object since no fields are required
        } else if (this.isMapConfig(this.config)) {
          realms[this._addedRealm] = {
            bounds: [4.42, 51.88, 4.55, 51.94],
            center: [4.485222, 51.911712],
            zoom: 14,
            minZoom: 14,
            maxZoom: 19,
            boxZoom: false,
          };
        }
        this.requestUpdate("config");
        this.notifyConfigChange(this.config);
        dialog?.close();
      }
    };
    const headingKey = this.isMapConfig(this.config)
      ? "configuration.addMapCustomization"
      : "configuration.addRealmCustomization";
    const realmItems = this.getAvailableRealms(this.config, this.realmOptions).map((item) => ({
      value: item.name,
      label: item.displayName,
    }));

    const selectRef: Ref<OrVaadinSelect> = createRef();
    const buttonRef: Ref<OrVaadinButton> = createRef();
    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value=${headingKey}></or-translate>
          </h2>
          <or-vaadin-select
            ${ref(selectRef)}
            required
            class="selector"
            .items=${realmItems}
            @change=${(ev: Event) => {
              this._addedRealm = (ev.currentTarget as OrVaadinSelect).value;
              buttonRef.value!.disabled = !selectRef.value?.checkValidity();
            }}
          >
            <or-translate slot="label" value="realm"></or-translate>
          </or-vaadin-select>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" ${ref(buttonRef)} disabled @click=${onOk}>
              <or-translate value="ok"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }
}
