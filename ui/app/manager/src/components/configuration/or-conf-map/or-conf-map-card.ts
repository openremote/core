/*
 * Copyright 2022, OpenRemote Inc.
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
import { OrElement } from "@openremote/or-element";
import { customElement, property, state } from "lit/decorators.js";
import { when } from "lit/directives/when.js";
import "@openremote/or-components/or-file-uploader";
import { i18next } from "@openremote/or-translate";
import type { MapRealmConfig } from "@openremote/model";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import type { OrVaadinToggle } from "@openremote/or-vaadin-components/or-vaadin-toggle";
import type { OrMapLongPressEvent } from "@openremote/or-map";
import type { LngLat } from "maplibre-gl";
import "./or-conf-map-geojson";

@customElement("or-conf-map-card")
export class OrConfMapCard extends OrElement {
  static styles = css`
    or-collapsible-panel {
      margin-bottom: 10px;
    }
  `;

  @property({ attribute: false })
  public map: MapRealmConfig = {};

  @property({ attribute: true })
  public name: string = "";

  @property({ type: Boolean })
  expanded: boolean = false;

  @property()
  public canRemove: boolean = false;

  @state()
  protected zoom: number = 1;

  protected notifyConfigChange(config: MapRealmConfig) {
    this.dispatchEvent(new CustomEvent("change", { detail: config }));
  }

  protected _showRemoveMapDialog() {
    showConfirmDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-confirm-dialog @confirm=${() => this.dispatchEvent(new CustomEvent("remove"))}>
          ${getConfirmDialogContent("error", "delete", "configuration.deleteMapCustomizationConfirm", "delete", "cancel")}
        </or-vaadin-confirm-dialog>
      `
    );
  }

  protected setBoundary(key: number, value: any) {
    this.map.bounds[key] = parseFloat(parseFloat(value).toFixed(2));
    this.requestUpdate("map");
    this.notifyConfigChange(this.map);
  }

  protected setCenter(cor: LngLat | string) {
    let centerCoordinate: LngLat;
    if (typeof cor === "string") {
      centerCoordinate = {
        lat: parseFloat(cor.split(",")[1]),
        lng: parseFloat(cor.split(",")[0]),
      } as never;
    } else {
      centerCoordinate = cor;
    }
    const BetweenNorthSouth = this.map.bounds[3] > centerCoordinate.lat && this.map.bounds[1] < centerCoordinate.lat;
    const BetweenWestEast = this.map.bounds[0] < centerCoordinate.lng && this.map.bounds[2] > centerCoordinate.lng;
    if (!(BetweenNorthSouth && BetweenWestEast)) {
      this.map.bounds = [
        parseFloat((centerCoordinate.lng - 0.2).toFixed(3)),
        parseFloat((centerCoordinate.lat - 0.1).toFixed(3)),
        parseFloat((centerCoordinate.lng + 0.2).toFixed(3)),
        parseFloat((centerCoordinate.lat + 0.1).toFixed(3)),
      ];
    }
    this.map.center = [centerCoordinate.lng, centerCoordinate.lat];
    this.requestUpdate("map");
    this.notifyConfigChange(this.map);
  }

  protected setZoom(nr: number) {
    this.zoom = nr;
  }

  protected async getMapContentTemplate(map: MapRealmConfig): Promise<TemplateResult> {
    return html` <style>
        #remove-map {
          margin: 12px 0 0 0;
        }

        .subheader {
          padding: 10px 0 4px;
          font-weight: bolder;
        }

        .d-inline-flex {
          display: inline-flex;
        }

        .panel-content {
          padding: 0 24px 24px;
        }

        .description {
          font-size: 12px;
        }

        .boundary-container {
          width: 50%;
        }

        .boundary-item,
        .input {
          width: 100%;
          max-width: 800px;
          padding: 10px 0;
        }

        .input or-vaadin-number-field {
          width: 80%;
        }

        .zoom-group {
          width: 50%;
          padding-left: 12px;
        }

        .map-container {
          width: 100%;
        }
        .settings-container {
          display: inline-flex;
        }

        @media screen and (max-width: 768px) {
          .zoom-group,
          .boundary-container {
            width: 100%;
            padding: unset;
          }
          .settings-container {
            display: block;
          }
        }
      </style>
      <div slot="content" class="panel-content">
        <div class="map-container">
          <div class="subheader">${i18next.t("map")}</div>
          <or-map
            id="vectorMap"
            .showBoundaryBoxControl="${true}"
            .zoom="${this.zoom}"
            .boundary="${map.bounds}"
            @or-map-long-press="${(ev: OrMapLongPressEvent) => {
              this.setCenter(ev.detail.lngLat);
            }}"
            .showGeoCodingControl="${true}"
            .showGeoJson="${map.geoJson != undefined}"
            .geoJson="${map.geoJson}"
            .useZoomControl="${false}"
            style="height: 500px; width: 100%;"
          >
            ${when(
              map.center,
              () => html`
                <or-map-marker
                  id="geo-json-point-marker"
                  .lng="${map.center[0]}"
                  .lat="${map.center[1]}"
                ></or-map-marker>
              `
            )}
          </or-map>
        </div>

        <div class="settings-container">
          <div class="boundary-container">
            <div class="subheader">${i18next.t("configuration.mapBounds")}</div>
            <span>${i18next.t("configuration.mapBoundsDescription")}</span>
            <or-vaadin-number-field
              class="boundary-item"
              value=${map?.bounds[3]}
              step="0.01"
              @change=${(ev: Event) => this.setBoundary(3, (ev.currentTarget as HTMLInputElement).value)}
            >
              <or-translate slot="label" value="north"></or-translate>
            </or-vaadin-number-field>
            <or-vaadin-number-field
              class="boundary-item"
              value=${map?.bounds[2]}
              step="0.01"
              @change=${(ev: Event) => this.setBoundary(2, (ev.currentTarget as HTMLInputElement).value)}
            >
              <or-translate slot="label" value="east"></or-translate>
            </or-vaadin-number-field>
            <or-vaadin-number-field
              class="boundary-item"
              value=${map?.bounds[1]}
              step="0.01"
              @change=${(ev: Event) => this.setBoundary(1, (ev.currentTarget as HTMLInputElement).value)}
            >
              <or-translate slot="label" value="south"></or-translate>
            </or-vaadin-number-field>
            <or-vaadin-number-field
              class="boundary-item"
              value=${map?.bounds[0]}
              step="0.01"
              @change=${(ev: Event) => this.setBoundary(0, (ev.currentTarget as HTMLInputElement).value)}
            >
              <or-translate slot="label" value="west"></or-translate>
            </or-vaadin-number-field>

            <div class="subheader">${i18next.t("configuration.center")}</div>
            <span>${i18next.t("configuration.centerDescription")}</span>
            <or-vaadin-text-field
              class="boundary-item"
              value=${Array.isArray(map.center) ? map.center.join() : undefined}
              @change=${(ev: Event) => this.setCenter((ev.currentTarget as HTMLInputElement).value)}
            >
            </or-vaadin-text-field>
            <div class="subheader">${i18next.t("configuration.geoJson")}</div>
            <span>${i18next.t("configuration.geoJsonDescription")}</span>
            <div class="input" style="height: 56px; display: flex; align-items: center;">
              <or-conf-map-geojson
                .geoJson="${map.geoJson}"
                @update="${(e: CustomEvent) => {
                  map.geoJson = e.detail.value;
                  this.requestUpdate();
                  this.notifyConfigChange(map);
                }}"
              ></or-conf-map-geojson>
            </div>
          </div>

          <div class="zoom-group">
            <div class="subheader">${i18next.t("configuration.mapZoom")}</div>
            <span>${i18next.t("configuration.mapZoomDescription")}</span>
            <div class="input">
              <or-vaadin-number-field
                value=${map.zoom}
                step="1"
                min="1"
                max="100"
                step-buttons-visible
                @change=${(ev: Event) => {
                  map.zoom = Number((ev.currentTarget as HTMLInputElement).value);
                  this.notifyConfigChange(map);
                }}
              >
                <or-translate slot="label" value="default"></or-translate>
              </or-vaadin-number-field>
              <or-vaadin-button theme="icon" @click=${() => this.setZoom(map.zoom)}>
                <or-icon icon="eye" style="margin: 0;"></or-icon>
              </or-vaadin-button>
            </div>
            <div class="input">
              <or-vaadin-number-field
                value=${map.minZoom}
                min="0"
                max=${map.maxZoom}
                step="1"
                step-buttons-visible
                @change=${(ev: Event) => {
                  map.minZoom = Number((ev.currentTarget as HTMLInputElement).value);
                  this.requestUpdate();
                  this.notifyConfigChange(map);
                }}
              >
                <or-translate slot="label" value="configuration.minZoom"></or-translate>
              </or-vaadin-number-field>
              <or-vaadin-button theme="icon" @click=${() => this.setZoom(map.minZoom)}>
                <or-icon icon="eye" style="margin: 0;"></or-icon>
              </or-vaadin-button>
            </div>
            <div class="input">
              <or-vaadin-number-field
                value=${map.maxZoom}
                min=${map.minZoom}
                max="100"
                step="1"
                step-buttons-visible
                @change=${(ev: Event) => {
                  map.maxZoom = Number((ev.currentTarget as HTMLInputElement).value);
                  this.requestUpdate("map");
                  this.notifyConfigChange(map);
                }}
              >
                <or-translate slot="label" value="configuration.maxZoom"></or-translate>
              </or-vaadin-number-field>
              <or-vaadin-button theme="icon" @click=${() => this.setZoom(map.maxZoom)}>
                <or-icon icon="eye" style="margin: 0;"></or-icon>
              </or-vaadin-button>
            </div>

            <div class="input" style="height: 56px;">
              <or-vaadin-toggle
                ?checked="${map.boxZoom}"
                @change="${(e: Event) => {
                  map.boxZoom = (e.currentTarget as OrVaadinToggle).checked;
                  this.notifyConfigChange(map);
                }}"
              >
                <or-translate slot="label" value="configuration.boxZoom"></or-translate>
              </or-vaadin-toggle>
            </div>
          </div>
        </div>

        ${when(
          this.canRemove,
          () => html`
            <or-vaadin-button id="remove-map" @click=${() => this._showRemoveMapDialog()}>
              <or-translate value="configuration.deleteMapCustomization"></or-translate>
            </or-vaadin-button>
          `
        )}
      </div>`;
  }

  render() {
    return html`
      <or-collapsible-panel .expanded="${this.expanded}" .lazycontent=${this.getMapContentTemplate(this.map)}>
        <div slot="header" class="header-container">${this.name}</div>
      </or-collapsible-panel>
    `;
  }
}
