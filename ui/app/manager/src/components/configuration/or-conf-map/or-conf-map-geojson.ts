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
import type { GeoJsonConfig } from "@openremote/model";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { html } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, state } from "lit/decorators.js";
import "@openremote/or-components/or-ace-editor";
import type { OrAceEditor, OrAceEditorChangedEvent } from "@openremote/or-components/or-ace-editor";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import { createRef, type Ref, ref } from "lit/directives/ref.js";

@customElement("or-conf-map-geojson")
export class OrConfMapGeoJson extends OrElement {
  @property()
  protected geoJson?: GeoJsonConfig;

  @state()
  protected _jsonValid: boolean = true;

  protected _dialog: OrVaadinDialog;
  // Value of or-ace-editor without state to prevent UI update
  protected _aceEditorValue: string;

  /* -------------- */

  protected render() {
    return html`
      <or-vaadin-button @click=${() => this._showDialog()}>
        <or-icon slot="prefix" icon="pencil"></or-icon>
        <or-translate value="configuration.geoJson"></or-translate>
      </or-vaadin-button>
    `;
  }

  protected _showDialog() {
    const buttonRef: Ref<OrVaadinButton> = createRef();
    const editorRef: Ref<OrAceEditor> = createRef();
    const onOk = () => {
      this.geoJson = this.parseGeoJson(this._aceEditorValue); // update with new value
      this.dispatchEvent(new CustomEvent("update", { detail: { value: this.geoJson } }));
      this._dialog?.close();
    };
    this._dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="768px" no-close-on-esc no-close-on-outside-click>
          <h2 slot="header-content">
            <span>GeoJSON editor</span>
          </h2>
          <or-ace-editor
            ${ref(editorRef)}
            .value="${this.geoJson?.source}"
            style="width: 100%; aspect-ratio: 1/1;"
            @or-ace-editor-changed="${(ev: OrAceEditorChangedEvent) => {
              this._jsonValid = ev.detail.valid;
              buttonRef.value!.disabled = !this._jsonValid;
              if (this._jsonValid) {
                this._aceEditorValue = ev.detail.value;
              }
            }}"
          ></or-ace-editor>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${() => this._dialog?.close()}>
              <or-translate value="close"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" ${ref(buttonRef)} disabled @click=${onOk}>
              <or-translate value="update"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  protected parseGeoJson(jsonString: string): GeoJsonConfig {
    let geoJsonObj;
    try {
      geoJsonObj = JSON.parse(jsonString);
    } catch (err) {
      console.warn(err);
      this._jsonValid = false;
      return;
    }
    return {
      source: geoJsonObj,
      layers: [],
    } as GeoJsonConfig;
  }
}
