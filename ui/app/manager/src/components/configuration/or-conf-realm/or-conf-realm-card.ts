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
import { css, html, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { InputType, type OrInputChangedEvent } from "@openremote/or-mwc-components/or-mwc-input";
import { customElement, property } from "lit/decorators.js";
import "@openremote/or-components/or-file-uploader";
import {
  DEFAULT_LANGUAGES,
  DefaultColor1,
  DefaultColor2,
  DefaultColor3,
  DefaultColor4,
  DefaultColor5,
  DefaultColor6,
  DefaultColor8,
  manager,
  Util,
} from "@openremote/core";
import { i18next, translate } from "@openremote/or-translate";
import type { FileInfo, ManagerAppRealmConfig } from "@openremote/model";
import { when } from "lit/directives/when.js";
import { DefaultHeaderMainMenu, DefaultHeaderSecondaryMenu } from "../../../index";
import type { OrVaadinMultiSelectComboBox } from "@openremote/or-vaadin-components/or-vaadin-multi-select-combo-box";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";

@customElement("or-conf-realm-card")
export class OrConfRealmCard extends translate(i18next)(OrElement) {
  static styles = css`
    .language {
      width: 100%;
      padding: 10px 0px;
      max-width: 800px;
    }

    .appTitle {
      width: 100%;
      max-width: 800px;
      padding: 10px 0px;
    }

    .header-group .header-item {
      width: 100%;
      padding: 10px 0px;
      max-width: 800px;
    }

    .color-group .color-item {
      width: 100%;
    }

    .logo-group {
      width: 100%;
    }

    @media screen and (max-width: 768px) {
      .logo-group or-file-uploader {
        min-width: calc(50% - 6px);
        padding: 0 12px 12px 0 !important;
      }

      .logo-group or-file-uploader:nth-child(2n + 2) {
        padding: 0 0 12px 0 !important;
      }

      .logo-group .d-inline-flex {
        display: flex;
        flex-wrap: wrap;
      }
    }

    .logo-group or-file-uploader {
      padding: 0 24px 12px 0;
    }

    #remove-realm {
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

    or-collapsible-panel {
      margin-bottom: 10px;
    }
  `;

  @property({ attribute: false })
  public realm: ManagerAppRealmConfig = {};

  @property({ attribute: true })
  public name: string = "";

  @property({ type: Boolean })
  expanded: boolean = false;

  @property()
  public canRemove: boolean = false;

  protected logo: string = this.realm.logo;
  protected logoMobile: string = this.realm.logoMobile;
  protected favicon: string = this.realm.favicon;
  protected headerListPrimary: { value: any; label: string }[] = Object.keys(DefaultHeaderMainMenu).map((k) => ({
    value: k,
    label: Util.camelCaseToSentenceCase(k),
  }));

  protected headerListSecondary: { value: any; label: string }[] = Object.keys(DefaultHeaderSecondaryMenu).map((k) => ({
    value: k,
    label: Util.camelCaseToSentenceCase(k),
  }));

  protected _languages = Object.entries(DEFAULT_LANGUAGES)
    .map(([code, key]) => ({ value: code, label: i18next.t(key) }))
    .sort((a, b) => a.label.localeCompare(b.label));

  protected get commonLanguages(): string[] {
    return this._languages.map((entry) => entry[1]);
  }

  protected _getColors() {
    const colors: { [name: string]: string } = {
      "--or-app-color1": unsafeCSS(DefaultColor1).toString(),
      "--or-app-color2": unsafeCSS(DefaultColor2).toString(),
      "--or-app-color3": unsafeCSS(DefaultColor3).toString(),
      "--or-app-color4": unsafeCSS(DefaultColor4).toString(),
      "--or-app-color5": unsafeCSS(DefaultColor5).toString(),
      "--or-app-color6": unsafeCSS(DefaultColor6).toString(),
      "--or-app-color8": unsafeCSS(DefaultColor8).toString(),
    };
    if (this.realm?.styles) {
      // TODO use regex for filtering and getting color codes CSS
      const css = this.realm.styles.slice(this.realm.styles.indexOf("{") + 1, this.realm.styles.indexOf("}"));
      css.split(";").forEach(function (value) {
        const col = value.split(":");
        if (col.length >= 2) {
          colors[col[0].trim()] = col[1].trim();
        }
      });
    }
    return colors;
  }

  protected _setColor(key: string, value: string) {
    const colors = this._getColors();
    colors[key] = value;
    let css = ":host > * {";
    Object.entries(colors).forEach(([key, value]) => {
      css += key + ":" + value + ";";
    });
    css += "}";
    this.realm.styles = css;
    this.notifyConfigChange(this.realm);
  }

  protected _setHeader(keys: string[], list: string[]) {
    console.debug(keys, list);
    if (!this.realm.headers) {
      this.realm.headers = this.headerListSecondary
        .map((x) => x.value)
        .concat(this.headerListPrimary.map((x) => x.value));
    }
    this.realm.headers = this.realm.headers?.filter(function (ele) {
      return !list.includes(ele);
    });
    this.realm.headers = this.realm.headers?.concat(keys);
    this.notifyConfigChange(this.realm);
  }

  protected _getImagePath(file: File, fileName: string) {
    if (file.type.startsWith("image/")) {
      const extension = file.name.slice(file.name.lastIndexOf("."), file.name.length);
      return this.name + "/" + fileName + extension;
    }
    return null;
  }

  protected files: { [name: string]: FileInfo } = {};

  protected async _setImageForUpload(file: File, fileName: string) {
    const path = this._getImagePath(file, fileName);
    if (path) {
      this.files[fileName] = {
        name: fileName,
        path,
        contents: await Util.blobToBase64(file),
      } as FileInfo;
      this.realm[fileName] = path;
      this[fileName] = this.files[fileName].contents;
      this.requestUpdate();
      this.notifyConfigChange(this.realm);
      return this.files[fileName].contents;
    }
  }

  // Public GET method for getting the files uploaded by the user.
  public getFiles() {
    return this.files;
  }

  protected _showRemoveRealmDialog() {
    showConfirmDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-confirm-dialog @confirm=${() => this.dispatchEvent(new CustomEvent("remove"))}>
          ${getConfirmDialogContent("error", "delete", "configuration.deleteRealmCustomizationConfirm", "delete", "cancel")}
        </or-vaadin-confirm-dialog>
      `
    );
  }

  protected notifyConfigChange(config: ManagerAppRealmConfig) {
    this.dispatchEvent(new CustomEvent("change", { detail: config }));
  }

  render() {
    const colors = this._getColors();
    const app = this;
    const managerUrl = manager.managerUrl ?? "";

    return html`
      <or-collapsible-panel .expanded="${app.expanded}">
        <div slot="header" class="header-container">${app.name}</div>
        <div slot="content" class="panel-content">
          <div class="subheader"><or-translate value="configuration.main"></or-translate></div>
          <or-vaadin-text-field
            class="appTitle"
            value=${app.realm?.appTitle}
            @change=${(ev: Event) => {
              app.realm.appTitle = (ev.currentTarget as HTMLInputElement).value;
              app.notifyConfigChange(app.realm);
            }}
          >
            <or-translate slot="label" value="configuration.realmTitle"></or-translate>
          </or-vaadin-text-field>
          <or-vaadin-select
            class="language"
            value=${app.realm?.language}
            .items=${Object.entries(DEFAULT_LANGUAGES).map(([key, value]) => ({ value: key, label: i18next.t(value) }))}
            @change=${(ev: Event) => {
              app.realm.language = (ev.currentTarget as HTMLInputElement).value;
              app.notifyConfigChange(app.realm);
            }}
          >
            <or-translate slot="label" value="configuration.defaultLanguage"></or-translate>
          </or-vaadin-select>
          <div class="logo-group">
            <div class="subheader"><or-translate value="configuration.images"></or-translate></div>
            <div class="d-inline-flex">
              <or-file-uploader
                .title="${i18next.t("configuration.logo")}"
                @change="${async (e: CustomEvent) => await app._setImageForUpload(e.detail.value[0], "logo")}"
                .src="${app.logo ? app.logo : app.realm.logo}"
                .managerUrl="${managerUrl}"
              ></or-file-uploader>
              <or-file-uploader
                .title="${i18next.t("configuration.logoMobile")}"
                @change="${async (e: CustomEvent) => await app._setImageForUpload(e.detail.value[0], "logoMobile")}"
                .src="${app.logoMobile ? app.logoMobile : app.realm.logoMobile}"
                .managerUrl="${managerUrl}"
              ></or-file-uploader>
              <or-file-uploader
                .title="${html`Favicon`}"
                @change="${async (e: CustomEvent) => await app._setImageForUpload(e.detail.value[0], "favicon")}"
                .src="${app.favicon ? app.favicon : app.realm.favicon}"
                .managerUrl="${managerUrl}"
              ></or-file-uploader>
            </div>
          </div>
          <div class="color-group">
            <div class="subheader"><or-translate value="configuration.realmColors"></or-translate></div>
            <div>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color4"]}"
                label="${i18next.t("configuration.--or-app-color4")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color4", e.detail.value)}"
              ></or-mwc-input>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color5"]}"
                label="${i18next.t("configuration.--or-app-color5")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color5", e.detail.value)}"
              ></or-mwc-input>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color6"]}"
                label="${i18next.t("configuration.--or-app-color6")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color6", e.detail.value)}"
              ></or-mwc-input>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color1"]}"
                label="${i18next.t("configuration.--or-app-color1")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color1", e.detail.value)}"
              ></or-mwc-input>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color2"]}"
                label="${i18next.t("configuration.--or-app-color2")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color2", e.detail.value)}"
              ></or-mwc-input>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color3"]}"
                label="${i18next.t("configuration.--or-app-color3")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color3", e.detail.value)}"
              ></or-mwc-input>
              <or-mwc-input
                class="color-item"
                .type="${InputType.COLOUR}"
                value="${colors["--or-app-color8"]}"
                label="${i18next.t("configuration.--or-app-color8")}"
                @or-mwc-input-changed="${(e: OrInputChangedEvent) => app._setColor("--or-app-color8", e.detail.value)}"
              ></or-mwc-input>
            </div>
          </div>
          <div class="header-group">
            <div class="subheader"><or-translate value="configuration.navigation"></or-translate></div>
            <span><or-translate value="configuration.navigationDescription"></or-translate></span>
            <span>${app.realm.headers ?? "none"}</span>
            <div>
              <or-vaadin-multi-select-combo-box
                class="header-item"
                .items=${app.headerListPrimary}
                .selectedItems=${app.realm.headers ? app.headerListPrimary.filter((h) => app.realm.headers?.includes(h.value)) : app.headerListPrimary}
                @change=${(ev: Event) => {
                  app._setHeader(
                    (ev.currentTarget as OrVaadinMultiSelectComboBox).selectedItems.map((i) => i.value),
                    app.headerListPrimary.map((i) => i.value)
                  );
                }}
              >
                <or-translate slot="label" value="configuration.primaryNavigation"></or-translate>
              </or-vaadin-multi-select-combo-box>
              <or-vaadin-multi-select-combo-box
                class="header-item"
                .items=${app.headerListSecondary}
                .selectedItems=${app.realm.headers ? app.headerListSecondary.filter((h) => app.realm.headers?.includes(h.value)) : app.headerListSecondary}
                @change=${(ev: Event) => {
                  app._setHeader(
                    (ev.currentTarget as OrVaadinMultiSelectComboBox).selectedItems.map((i) => i.value),
                    app.headerListSecondary.map((i) => i.value)
                  );
                }}
              >
                <or-translate slot="label" value="configuration.secondaryNavigation"></or-translate>
              </or-vaadin-multi-select-combo-box>
            </div>
          </div>
          <div class="header-group">
            <div class="subheader"><or-translate value="configuration.notificationLanguages"></or-translate></div>
            <span><or-translate value="configuration.notificationLanguagesDesc"></or-translate></span>
            <div>
              <or-vaadin-multi-select-combo-box
                class="header-item"
                .items=${this._languages}
                .selectedItems=${this._languages.filter((l) => app.realm.notifications?.languages.includes(l.value))}
                @change=${(ev: Event) => {
                  const newLanguages: string[] | undefined = (
                    ev.currentTarget as OrVaadinMultiSelectComboBox
                  ).selectedItems.map((i) => i.value);
                  const currentDefault: string | undefined = app.realm.notifications?.defaultLanguage;
                  app.realm.notifications = {
                    languages: newLanguages,
                    defaultLanguage: newLanguages?.includes(currentDefault) ? currentDefault : newLanguages?.[0],
                  };
                  app.notifyConfigChange(app.realm);
                  this.requestUpdate(); // force render
                }}
              >
                <or-translate slot="label" value="configuration.notificationLanguages"></or-translate>
              </or-vaadin-multi-select-combo-box>

              <or-vaadin-select
                class="header-item"
                ?disabled=${!app.realm.notifications?.languages?.length}
                .items=${this._languages.filter((l) => app.realm.notifications?.languages.includes(l.value))}
                value=${app.realm.notifications?.defaultLanguage}
                @change=${(ev: Event) => {
                  app.realm.notifications.defaultLanguage = (ev.currentTarget as HTMLInputElement).value;
                  app.notifyConfigChange(app.realm);
                  this.requestUpdate(); // force render
                }}
              >
                <or-translate slot="label" value="configuration.defaultLanguage"></or-translate>
              </or-vaadin-select>
            </div>
          </div>

          ${when(
            app.canRemove,
            () => html`
              <or-vaadin-button id="remove-realm" @click=${() => app._showRemoveRealmDialog()}>
                <or-translate value="configuration.deleteRealmCustomization"></or-translate>
              </or-vaadin-button>
            `
          )}
        </div>
      </or-collapsible-panel>
    `;
  }
}
