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
import { html, LitElement, type TemplateResult, type PropertyValues } from "lit";
import { customElement, property } from "lit/decorators.js";
import manager, { type Manager, type OREvent, subscribe, Util } from "@openremote/core";
import "@openremote/or-icon";
import "@openremote/or-translate";
import { createSvgIconSet, IconSets } from "@openremote/or-icon";
import { i18next } from "@openremote/or-translate";

import {
  Auth,
  type AttributeEvent,
  WellknownMetaItems,
  type SharedEvent,
  type Asset,
  WellknownAssets,
  WellknownAttributes,
  AssetModelUtil,
} from "@openremote/model";
import { getBuildingAsset, getElectricityChargerAsset, getElectricityConsumerAsset } from "./util";

@customElement("or-demo")
class OrDemo extends subscribe(manager)(LitElement) {
  @property()
  protected alarmEnabled = false;

  @property()
  protected _asset?: Asset;

  @property()
  public electricityConsumerAsset?: Asset;

  @property()
  public electricityChargerAsset?: Asset;

  protected loggedInTemplate = (openremote: Manager) =>
    html`<span>Welcome ${manager.username} </span><button @click="${() => manager.logout()}">logout</button>`;

  protected loggedOutTemplate = (openremote: Manager) => html`<button @click="${() => manager.login()}">login</button>`;

  protected assetTemplate = (alarmEnabled: boolean) => html`<p><b>Alarm Enabled: </b> ${alarmEnabled}</p>`;

  protected render(): TemplateResult | void {
    return html`
      <p><b>Message:</b> ${manager.authenticated ? this.loggedInTemplate(manager) : this.loggedOutTemplate(manager)}</p>
      <br />
      <p><b>Initialized: </b> ${manager.ready}</p>
      <p><b>Manager Version: </b> ${manager.managerVersion}</p>
      <p><b>Authenticated: </b> ${manager.authenticated}</p>
      <p><b>Username: </b> ${manager.username}</p>
      <p><b>Roles: </b> ${JSON.stringify(manager.roles, null, 2)}</p>
      <p><b>Is Super User: </b> ${manager.isSuperUser()}</p>
      <p><b>Is Manager Same Origin: </b> ${manager.isManagerSameOrigin()}</p>
      <p><b>Connection Status: </b> ${manager.connectionStatus}</p>
      <p><b>Is Error: </b> ${manager.isError}</p>
      <p><b>Error:</b> ${manager.error}</p>
      <p><b>Config: </b> ${manager.config ? JSON.stringify(manager.config, null, 2) : ""}</p>
      <p>
        <b>Console Registration: </b>${manager.console ? JSON.stringify(manager.console.registration, null, 2) : ""}
      </p>
      <p><b>Icon Example (Material Design icon set): </b><or-icon icon="access-point" /></p>
      <p>
        <b>Icon Example (OR icon set): </b><or-icon icon="or:logo"></or-icon><or-icon icon="or:logo-plain"></or-icon
        ><or-icon style="fill: #C4D600;" icon="or:marker"></or-icon>
      </p>
      <p>
        <b>Icon Example (dynamic Set click to add): </b><button @click="${() => this.createIconSet()}">Load</button>:
        <or-icon icon="test:x"></or-icon>
      </p>
      <p>
        <b>Translation Example: </b> <or-translate value="temperature"></or-translate>
        <button @click="${() => this.toggleLanguage()}">${i18next.language}</button>
      </p>

      ${
        this.electricityConsumerAsset
          ? html`
              <p>
                <b
                  >Electricity consumer power total
                  (${Util.resolveUnits(Util.getAttributeUnits(this.electricityConsumerAsset.attributes![WellknownAttributes.POWER], AssetModelUtil.getAttributeDescriptor(WellknownAttributes.POWER, WellknownAssets.ELECTRICITYCONSUMERASSET), WellknownAssets.ELECTRICITYCONSUMERASSET))}):
                </b>
                ${Util.getAttributeValueAsString(this.electricityConsumerAsset.attributes![WellknownAttributes.POWER], AssetModelUtil.getAttributeDescriptor(WellknownAttributes.POWER, WellknownAssets.ELECTRICITYCONSUMERASSET), WellknownAssets.ELECTRICITYCONSUMERASSET, true, "-")}
              </p>
            `
          : ``
      }
      ${
        this.electricityChargerAsset
          ? html`
              <p>
                <b
                  >Electricity charger import tariff
                  (${Util.resolveUnits(Util.getAttributeUnits(this.electricityChargerAsset.attributes![WellknownAttributes.TARIFFIMPORT], AssetModelUtil.getAttributeDescriptor(WellknownAttributes.TARIFFIMPORT, WellknownAssets.ELECTRICITYCHARGERASSET), WellknownAssets.ELECTRICITYCHARGERASSET))}):
                </b>
                ${Util.getAttributeValueAsString(this.electricityChargerAsset.attributes![WellknownAttributes.TARIFFIMPORT], AssetModelUtil.getAttributeDescriptor(WellknownAttributes.TARIFFIMPORT, WellknownAssets.ELECTRICITYCHARGERASSET), WellknownAssets.ELECTRICITYCHARGERASSET, true, "-")}
              </p>
            `
          : ``
      }

      <p><b>Asset Type Infos: </b>${JSON.stringify(AssetModelUtil.getAssetTypeInfos(), null, 2)}</p>
      <p><b>Value Descriptors: </b>${JSON.stringify(AssetModelUtil.getValueDescriptors(), null, 2)}</p>
      <p><b>Meta Item Descriptors: </b>${JSON.stringify(AssetModelUtil.getMetaItemDescriptors(), null, 2)}</p>
      <p>
        <b>Rule State Meta Item: </b
        >${JSON.stringify(AssetModelUtil.getMetaItemDescriptor(WellknownMetaItems.RULESTATE), null, 2)}
      </p>
    `;
  }

  public set asset(asset: Asset) {
    this._asset = asset;
    super.assetIds = asset!.id ? [asset!.id] : undefined;
  }

  protected _onOrEvent = (event: OREvent) => {
    this.requestUpdate();
    console.log("OR Event:" + event);
  };

  connectedCallback() {
    super.connectedCallback();
    manager.addListener((e) => this._onOrEvent(e));
  }

  protected updated(_changedProperties: PropertyValues) {
    super.updated(_changedProperties);
  }

  public createIconSet() {
    const testIconSet = {
      x: '<path d="m0.3125,39.74088l37.33242,0l11.53601,-37.43277l11.53601,37.43277l37.33242,0l-30.20251,23.13446l11.5366,37.43277l-30.20252,-23.13509l-30.20252,23.13509l11.53661,-37.43277l-30.20252,-23.13446z" stroke-width=\'5\' stroke="#000"/>',
    };
    IconSets.addIconSet("test", createSvgIconSet(100, testIconSet));
  }

  public toggleLanguage() {
    manager.language = i18next.language === "en" ? "nl" : "en";
    this.requestUpdate();
  }

  public onEvent(event: SharedEvent) {
    console.log("Asset Event Received:" + JSON.stringify(event, null, 2));

    if (event.eventType === "attribute") {
      const attributeEvent = event as AttributeEvent;
      if (attributeEvent && attributeEvent.ref!.name === "alarmEnabled") {
        this.alarmEnabled = attributeEvent.value;
      }
    }
  }
}

manager
  .init({
    managerUrl: "http://127.0.0.1:8080",
    auth: Auth.KEYCLOAK,
    autoLogin: false,
    realm: "smartcity",
    configureTranslationsOptions: (options: any) => {
      options.lng = "nl"; // Change initial language to dutch
    },
  })
  .then((success) => {
    if (success) {
      if (manager.authenticated) {
        getBuildingAsset()
          .then((asset) => {
            if (asset) {
              console.log("Asset received: " + JSON.stringify(asset, null, 2));
              (document.getElementById("or-demo") as OrDemo).asset = asset;
            }
          })
          .then(() => {
            getElectricityConsumerAsset().then((asset) => {
              console.log("Electricity consumer asset received " + JSON.stringify(asset, null, 2));
              (document.getElementById("or-demo") as OrDemo).electricityConsumerAsset = asset;
            });
          })
          .then(() => {
            getElectricityChargerAsset().then((asset) => {
              console.log("Electricity charger asset received " + JSON.stringify(asset, null, 2));
              (document.getElementById("or-demo") as OrDemo).electricityChargerAsset = asset;
            });
          });
      }
    }
  });
