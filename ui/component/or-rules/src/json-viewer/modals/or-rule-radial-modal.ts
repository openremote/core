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
import { html } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query } from "lit/decorators.js";
import type { AssetDescriptor, AttributePredicate, AssetQuery, RadialGeofencePredicate } from "@openremote/model";
import type { OrVaadinDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { i18next, translate } from "@openremote/or-translate";
import { OrRulesJsonRuleChangedEvent } from "../or-rule-json-viewer";
import { type OrMap, OrMapClickedEvent, type LngLatLike } from "@openremote/or-map";
import "@openremote/or-map";
import type { OrVaadinNumberField } from "@openremote/or-vaadin-components/or-vaadin-number-field";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";

@customElement("or-rule-radial-modal")
export class OrRuleRadialModal extends translate(i18next)(OrElement) {
  @property({ type: Object })
  public assetDescriptor?: AssetDescriptor;

  @property({ type: Object })
  public attributePredicate?: AttributePredicate;

  @property({ type: Boolean })
  public readonly = false;

  @property({ type: Object })
  public query?: AssetQuery;

  @query("#radial-modal")
  protected _mapDialogElem?: OrVaadinDialog;

  @query("#radial-modal-button")
  protected _mapDialogButton?: OrVaadinButton;

  initRadialMap() {
    if (!this._mapDialogElem) {
      console.warn("Could not find radial map dialog element!");
      return;
    }

    const map = this._mapDialogElem!.querySelector(".or-map") as OrMap;
    if (map) {
      map.addEventListener(OrMapClickedEvent.NAME, (evt: CustomEvent) => {
        const lngLat: any = evt.detail.lngLat;
        const latElement = this._mapDialogElem!.querySelector(".location-lat") as HTMLInputElement;
        const lngElement = this._mapDialogElem!.querySelector(".location-lng") as HTMLInputElement;
        latElement.value = lngLat.lat;
        lngElement.value = lngLat.lng;

        const event = new Event("change");
        latElement.dispatchEvent(event);
        lngElement.dispatchEvent(event);
        this.setValuePredicateProperty("lat", lngLat.lat);
        this.setValuePredicateProperty("lng", lngLat.lng);
      });

      const latElement = this._mapDialogElem!.querySelector(".location-lat") as HTMLInputElement;
      const lngElement = this._mapDialogElem!.querySelector(".location-lng") as HTMLInputElement;
      if (lngElement.value && latElement.value) {
        const LngLat: LngLatLike = [parseFloat(lngElement.value), parseFloat(latElement.value)];
        map.flyTo(LngLat, 15);
      } else {
        map.flyTo();
      }
    }
  }

  protected setValuePredicateProperty(propertyName: string, value: any) {
    if (!this.attributePredicate) return;
    if (!this.attributePredicate.value) return;

    const valuePredicate = this.attributePredicate.value;

    (valuePredicate as any)[propertyName] = value;
    this.attributePredicate = { ...this.attributePredicate };
    this.dispatchEvent(new OrRulesJsonRuleChangedEvent());
    this.requestUpdate();
  }

  getDialogHTML(value: RadialGeofencePredicate) {
    return html` <div style="display:grid">
      <or-map class="or-map" type="VECTOR" style="border: 1px solid #d5d5d5; aspect-ratio: 1/1;">
        <or-map-marker
          active
          color="#FF0000"
          icon="information"
          lat="${value.lat}"
          lng="${value.lng}"
          radius="${value.radius}"
        ></or-map-marker>
      </or-map>

      <div class="layout horizontal">
        <input
          hidden
          class="location-lng"
          required
          placeholder=" "
          type="text"
          .value="${value && value.lng ? value.lng : null}"
        />
        <input
          hidden
          class="location-lat"
          required
          placeholder=" "
          type="text"
          .value="${value && value.lat ? value.lat : null}"
        />
      </div>

      <or-vaadin-number-field
        style="max-width: 50%; margin-top: var(--lumo-space-m);"
        min="100"
        required
        value=${value.radius ?? 100}
        @change=${(ev: Event) => {
          const elem = ev.currentTarget as OrVaadinNumberField;
          if (elem.checkValidity()) {
            this.setValuePredicateProperty("radius", parseInt(elem.value));
          }
          this._mapDialogButton!.disabled = !elem.checkValidity();
        }}
      >
        <or-translate slot="label" value="radiusMin"></or-translate>
      </or-vaadin-number-field>
    </div>`;
  }

  protected render() {
    if (!this.attributePredicate) return html``;
    if (!this.query) return html``;

    const valuePredicate = this.attributePredicate.value;
    if (!this.assetDescriptor || !valuePredicate) {
      return html``;
    }
    // @ts-ignore
    const value: RadialGeofencePredicate = valuePredicate || undefined;

    const radialPickerModalOpen = () => {
      this._mapDialogElem?.open();
      this.initRadialMap();
    };

    return html`
      <or-vaadin-button ?disabled=${this.readonly} @click=${() => radialPickerModalOpen()}>
        <or-translate value="area"></or-translate>
      </or-vaadin-button>
      <or-vaadin-dialog id="radial-modal" width="512px">
        <h2 slot="header-content">
          <or-translate value="area"></or-translate>
        </h2>
        ${this.getDialogHTML(value)}
        <div slot="footer" style="width: 100%; display: flex; justify-content: end;">
          <or-vaadin-button id="radial-modal-button" theme="primary" @click=${() => this._mapDialogElem?.close()}>
            <or-translate value="ok"></or-translate>
          </or-vaadin-button>
        </div>
      </or-vaadin-dialog>
    `;
  }
}
