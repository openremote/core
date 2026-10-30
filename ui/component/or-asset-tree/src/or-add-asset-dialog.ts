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
import { css, html, type TemplateResult, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query } from "lit/decorators.js";
import {
  type AgentDescriptor,
  type Asset,
  type AssetDescriptor,
  type AttributeDescriptor,
  AssetModelUtil,
} from "@openremote/model";
import { type AssetTreeConfig, OrAssetTreeSelectionEvent } from "./index";
import { i18next } from "@openremote/or-translate";
import { ifDefined } from "lit/directives/if-defined.js";
import { styleMap } from "lit/directives/style-map.js";
import "@openremote/or-vaadin-components/or-vaadin-list-box";
import "@openremote/or-vaadin-components/or-vaadin-item";
import type { OrVaadinTextField } from "@openremote/or-vaadin-components/or-vaadin-text-field";
import type { OrVaadinCheckbox } from "@openremote/or-vaadin-components/or-vaadin-checkbox";
import type { OrVaadinListBox, ListItem } from "@openremote/or-vaadin-components/or-vaadin-list-box";
import { when } from "lit/directives/when.js";
import { DefaultColor2, DefaultColor3, DefaultColor5, Util } from "@openremote/core";
import debounce from "lodash.debounce";

export type OrAddAssetDetail = {
  name: string | undefined;
  descriptor: AssetDescriptor | AgentDescriptor | undefined;
};

export class OrAddChangedEvent extends CustomEvent<OrAddAssetDetail> {
  public static readonly NAME = "or-add-asset-changed";

  constructor(addAssetDetail: OrAddAssetDetail) {
    super(OrAddChangedEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: addAssetDetail,
    });
  }
}

declare global {
  export interface HTMLElementEventMap {
    [OrAddChangedEvent.NAME]: OrAddChangedEvent;
  }
}

@customElement("or-add-asset-dialog")
export class OrAddAssetDialog extends OrElement {
  @property({ attribute: false })
  public config!: AssetTreeConfig;

  @property({ attribute: false })
  public agentTypes!: AgentDescriptor[];

  @property({ attribute: false })
  public assetTypes!: AssetDescriptor[];

  @property({ attribute: false })
  public parent?: Asset;

  @property({ attribute: false })
  public selectedType?: AgentDescriptor | AssetDescriptor;

  @property({ attribute: false })
  public selectedAttributes: AttributeDescriptor[] = [];

  @property({ attribute: false })
  protected showParentAssetSelector: boolean = false;

  @property({ attribute: false })
  selectedChildAssetType: string = "";

  @property({ type: String })
  private typeFilter: string = "";

  public name: string = "New Asset";

  @query("#name-input")
  protected nameInput!: OrVaadinTextField;

  @query("#agent-list")
  protected agentList?: OrVaadinListBox;

  @query("#asset-list")
  protected assetList?: OrVaadinListBox;

  @query("#filterInput")
  private _filterInput!: OrVaadinTextField;

  public static get styles() {
    // language=CSS
    return css`
      :host {
        border-style: solid;
        border-color: var(--or-app-color5, ${unsafeCSS(DefaultColor5)});
        border-width: 1px 0;
      }

      #name-wrapper {
        display: flex;
        flex-direction: column;
        margin-top: 12px;
      }

      #toggle-parent-selector,
      #remove-parent {
        margin: 4px 0 0 5px;
      }

      #name-input,
      #parent-wrapper {
        margin: 10px 0;
      }

      #parent-wrapper {
        display: flex;
        align-items: end;
      }

      #parent {
        flex: 1 1 auto;
      }

      #parent-selector {
        max-width: 250px;
        border-left: 1px solid var(--or-app-color5, ${unsafeCSS(DefaultColor5)});
      }

      #mdc-dialog-form-add {
        display: flex;
        height: 600px;
        max-height: 600px;
        width: 1000px;
      }

      .msg {
        display: flex;
        justify-content: center;
        align-items: center;
        text-align: center;
        height: 100%;
        font-family: "Segoe UI", Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol";
        font-size: 14px;
      }

      #asset-type-option-container {
        padding: 7px;
        flex: 1 1 auto;
        overflow: auto;
        max-width: 100%;
        font-size: 16px;
      }

      #asset-type-option-content {
        padding: 7px 14px;
        box-sizing: border-box;
        height: 100%;
        overflow: auto;
        background: white;
        border-radius: 7px;
      }

      #type-list-filter {
        padding: 7px;
      }

      #filterInput {
        width: 100%;
      }

      #type-list-content {
        padding: 0 7px;
        overflow: hidden;
      }

      #type-list {
        padding: 0 7px;
        background: white;
        border-radius: 7px;
        height: 100%;
        overflow: auto;
        text-transform: capitalize;
      }

      #type-list-container {
        display: flex;
        flex-direction: column;
        width: 320px;
        gap: 4px;
      }

      #type-title {
        display: flex;
        align-items: center;
        margin: 9px 4px;
      }

      #type-description {
        text-transform: capitalize;
        color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
        margin-left: 10px;
        font-size: 18px;
        font-weight: bold;
        font-family: "Segoe UI", Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol";
      }

      .heading,
      .mdc-list-group__subheader {
        text-transform: uppercase;
        font-family: "Segoe UI", Helvetica, Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol";
        font-weight: bolder;
        line-height: 1em;
        color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
        letter-spacing: 0.025em;
        font-size: 14px;
        margin: 20px 0 10px;
      }

      .mdc-list-group__subheader {
        margin: 20px 0 0 16px;
      }
    `;
  }

  constructor() {
    super();
    this.addEventListener(OrAssetTreeSelectionEvent.NAME, (event: OrAssetTreeSelectionEvent) => {
      this.parent = event.detail.newNodes[0].asset;
    });
  }

  protected render() {
    const mapDescriptors: (descriptors: (AssetDescriptor | AgentDescriptor)[]) => ListItem[] = (descriptors) =>
      descriptors
        .map((descriptor) => {
          return {
            styleMap: {
              "--or-icon-fill": descriptor.colour ? "#" + descriptor.colour : "unset",
            },
            icon: descriptor.icon,
            text: Util.getAssetTypeLabel(descriptor),
            value: descriptor.name!,
            data: descriptor,
          };
        })
        .sort(Util.sortByString((listItem) => listItem.text));

    const agentItems = mapDescriptors(this.agentTypes);
    const assetItems = mapDescriptors(this.assetTypes);
    const searchProvider = (item: ListItem) => (item.text ?? "").toLowerCase().includes(this.typeFilter.toLowerCase());
    const filteredAgentItems = agentItems.filter(searchProvider);
    const filteredAssetItems = assetItems.filter(searchProvider);

    const getListTemplate = (items: ListItem[], isAgent = false): TemplateResult => html`
      <or-vaadin-list-box
        id="${isAgent ? "agent-list" : "asset-list"}"
        style="padding: var(--lumo-space-s) 0;"
        @selected-changed=${(ev: CustomEvent) => this.onTypeChanged(isAgent, items[ev.detail.value])}
      >
        <b style="padding: 0 var(--lumo-space-s);"
          ><or-translate value=${isAgent ? "agents" : "assets"}></or-translate
        ></b>
        ${items.map(
          (item) => html`
            <or-vaadin-item style=${styleMap(item.styleMap ?? {})}>
              <div style="display: flex; align-items: center; gap: 8px;">
                <or-icon icon=${ifDefined(item.icon)}></or-icon>
                <span style="overflow: hidden; white-space: nowrap; text-overflow: ellipsis;">
                  ${item.text ?? item.value ?? html`<or-translate value="unknown"></or-translate>`}
                </span>
              </div>
            </or-vaadin-item>
          `
        )}
      </or-vaadin-list-box>
    `;

    const parentStr = this.parent ? this.parent.name + " (" + this.parent.id + ")" : i18next.t("none");

    return html`
      <div class="col" style="height: 100%;">
        <form id="mdc-dialog-form-add" class="row">
          <div id="type-list-container" class="col">
            <div id="type-list-filter">
              <or-vaadin-text-field
                id="filterInput"
                placeholder=${i18next.t("search")}
                @input=${debounce(() => (this.typeFilter = this._filterInput.value ?? ""), 200)}
              >
                <or-icon slot="suffix" icon="magnify"></or-icon>
              </or-vaadin-text-field>
            </div>
            <div id="type-list-content">
              <div id="type-list">
                ${when(agentItems.length > 0, () => getListTemplate(filteredAgentItems, true))}
                <hr style="color: white;" />
                ${when(assetItems.length > 0, () => getListTemplate(filteredAssetItems, false))}
              </div>
            </div>
          </div>

          <div id="asset-type-option-container" class="col">
            <div id="asset-type-option-content">
              ${
                !this.selectedType
                  ? html`<div class="msg"><or-translate value="noAssetTypeSelected"></or-translate></div>`
                  : this.getTypeTemplate(this.selectedType, parentStr)
              }
            </div>
          </div>
          ${
            !this.showParentAssetSelector
              ? html``
              : html`<or-asset-tree
                  id="parent-selector"
                  class="col"
                  .showDeselectBtn="${false}"
                  .showSortBtn="${false}"
                  selectedNodes
                  readonly
                ></or-asset-tree>`
          }
        </form>
      </div>
    `;
  }

  protected getTypeTemplate(descriptor: AgentDescriptor | AssetDescriptor, parentStr: string) {
    if (!descriptor.name) {
      return false;
    }

    const assetTypeInfo = AssetModelUtil.getAssetTypeInfo(descriptor.name);
    const attributes: AttributeDescriptor[] | undefined = assetTypeInfo?.attributeDescriptors?.filter(
      (e) => !e.optional
    );
    const optionalAttributes: AttributeDescriptor[] | undefined = assetTypeInfo?.attributeDescriptors?.filter(
      (e) => !!e.optional
    );

    return html`
      <div id="type-title">
        <or-icon
          style="--or-icon-fill: ${descriptor.colour ? "#" + descriptor.colour : "unset"}"
          id="type-icon"
          .icon="${descriptor.icon}"
        ></or-icon>
        <or-translate id="type-description" .value="${Util.getAssetTypeLabel(descriptor)}"></or-translate>
      </div>
      <div id="name-wrapper">
        <or-vaadin-text-field
          id="name-input"
          minlength="1"
          maxlength="1023"
          required
          value=${this.name}
          @change=${(ev: Event) => {
            const elem = ev.currentTarget as OrVaadinTextField;
            if (elem.checkValidity()) this.onNameChanged(elem.value);
          }}
        >
          <or-translate slot="label" value="name"></or-translate>
        </or-vaadin-text-field>
        <div id="parent-wrapper">
          <or-vaadin-text-field id="parent" readonly value=${parentStr}>
            <or-translate slot="label" value="parent"></or-translate>
          </or-vaadin-text-field>
          <or-vaadin-button
            id="remove-parent"
            theme="icon"
            ?disabled=${!this.parent}
            @click=${() => this._onDeselectClicked()}
          >
            <or-icon icon="close"></or-icon>
          </or-vaadin-button>
          <or-vaadin-button
            id="toggle-parent-selector"
            theme="icon"
            @click=${() => this._onToggleParentAssetSelector()}
          >
            <or-icon icon=${this.showParentAssetSelector ? "pencil-off" : "pencil"}></or-icon>
          </or-vaadin-button>
        </div>
      </div>

      ${
        !attributes
          ? html``
          : html`
              <div>
                <div class="heading"><or-translate value="attribute_plural"></or-translate></div>
                <div style="display: grid">
                  ${attributes
                    .sort(Util.sortByString((attribute) => attribute.name!))
                    .map(
                      (attribute) => html`
                        <or-vaadin-checkbox
                          readonly
                          checked
                          label=${Util.getAttributeLabel(undefined, attribute, undefined, true)}
                        ></or-vaadin-checkbox>
                      `
                    )}
                </div>
              </div>
            `
      }
      ${
        !optionalAttributes
          ? html``
          : html`
              <div>
                <div class="heading">${i18next.t("optional_attributes")}</div>
                <or-vaadin-checkbox-group theme="vertical" .value=${this.selectedAttributes.map((attr) => attr.name)}>
                  ${optionalAttributes.sort(Util.sortByString((attribute) => attribute.name!)).map((attribute) => {
                    return html`
                      <or-vaadin-checkbox
                        value=${attribute.name}
                        label=${Util.getAttributeLabel(undefined, attribute, undefined, true)}
                        @change=${(ev: Event) =>
                          (ev.currentTarget as OrVaadinCheckbox).checked
                            ? this.selectedAttributes.push(attribute)
                            : this.selectedAttributes.splice(
                                this.selectedAttributes.findIndex((s) => s.name === attribute.name),
                                1
                              )}
                      >
                      </or-vaadin-checkbox>
                    `;
                  })}
                </or-vaadin-checkbox-group>
              </div>
            `
      }
    `;
  }

  protected onNameChanged(name: string) {
    this.name = name;
    this.onModified();
  }

  protected async onTypeChanged(isAgent: boolean, listItem: ListItem) {
    await this.updateComplete;

    this.selectedAttributes = [];
    this.selectedType = listItem.data as AssetDescriptor | AgentDescriptor;

    // Deselect other list selection
    const otherList = isAgent ? this.assetList : this.agentList;
    if (otherList) {
      otherList.selected = undefined;
    }
    this.onModified();
  }

  protected onModified() {
    this.dispatchEvent(
      new OrAddChangedEvent({
        name: this.name,
        descriptor: this.selectedType,
      })
    );
  }

  protected _onToggleParentAssetSelector(): void {
    this.showParentAssetSelector = !this.showParentAssetSelector;
  }

  protected _onDeselectClicked() {
    this.parent = undefined;
  }
}
