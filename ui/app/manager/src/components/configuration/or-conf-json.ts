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
import { html } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property } from "lit/decorators.js";
import "@openremote/or-components/or-ace-editor";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import type { OrAceEditor, OrAceEditorChangedEvent } from "@openremote/or-components/or-ace-editor";
import type { ManagerAppConfig, MapConfig } from "@openremote/model";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";

@customElement("or-conf-json")
export class OrConfJson extends OrElement {
  @property({ attribute: false })
  public config: ManagerAppConfig | MapConfig = {};

  public heading: string;

  protected _aceEditor: Ref<OrAceEditor> = createRef();
  protected _updateButton: Ref<OrVaadinButton> = createRef();

  public beforeSave(): false | string | undefined {
    if (!this._aceEditor.value) {
      return false;
    }
    const value = this._aceEditor.value.getValue();
    try {
      return JSON.parse(value || "{}");
    } catch (e) {
      return false;
    }
  }

  protected _showConfigDialog() {
    let dialog: OrVaadinDialog | undefined;
    const _saveConfig = () => {
      const config = this.beforeSave();
      if (config) {
        this.config = config as ManagerAppConfig | MapConfig;
        this.dispatchEvent(new CustomEvent("saveLocalConfig", { detail: { value: this.config } }));
        return true;
      }
      return false;
    };

    const onCancel = () => {
      dialog?.close();
    };
    const onOk = () => {
      if (_saveConfig()) {
        dialog?.close();
      }
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="768px" no-close-on-esc no-close-on-outside-click>
          <h2 slot="header-content">${this.heading}</h2>
          <or-ace-editor
            ${ref(this._aceEditor)}
            .value="${this.config}"
            style="aspect-ratio: 1/1;"
            @or-ace-editor-changed="${(ev: OrAceEditorChangedEvent) => {
              if (this._updateButton.value) {
                this._updateButton.value.disabled = !ev.detail.valid;
                dialog?.requestUpdate();
              }
            }}"
          ></or-ace-editor>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${onCancel}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" ${ref(this._updateButton)} @click=${onOk}>
              <or-translate value="update"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  render() {
    return html`
      <or-vaadin-button @click=${() => this._showConfigDialog()}>
        <or-icon slot="prefix" icon="pencil"></or-icon>
        <or-translate value="JSON"></or-translate>
      </or-vaadin-button>
    `;
  }
}
