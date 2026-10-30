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
import { OrElement } from "@openremote/or-element";
import { until } from "lit/directives/until.js";
import { customElement, property } from "lit/decorators.js";
import {
  type ValueInputProviderOptions,
  getValueHolderInputTemplateProvider,
} from "@openremote/or-vaadin-components/value-input-provider";
import { InputType, type OrInputChangedEvent } from "@openremote/or-mwc-components/or-mwc-input";
import { i18next } from "@openremote/or-translate";
import {
  type Asset,
  type Attribute,
  type NameValueHolder,
  AssetModelUtil,
  WellknownMetaItems,
} from "@openremote/model";
import { DefaultColor5, DefaultColor3, Util } from "@openremote/core";
import "@openremote/or-mwc-components/or-mwc-input";
import type { OrIcon } from "@openremote/or-icon";
import { showDialog, type OrVaadinDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import type { OrVaadinCheckboxGroup } from "@openremote/or-vaadin-components/or-vaadin-checkbox-group";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import "./or-add-attribute-panel";
import { getField, getPanel, getPropertyTemplate } from "./index";
import type { OrAddAttributePanelAttributeChangedEvent } from "./or-add-attribute-panel";
import { panelStyles } from "./style";
import type { OrAssetTree, UiAssetTreeNode } from "@openremote/or-asset-tree";
import {
  jsonFormsInputTemplateProvider,
  type OrAttributeInput,
  type OrAttributeInputChangedEvent,
} from "@openremote/or-attribute-input";
import { createRef, ref, type Ref } from "lit/directives/ref.js";
import { when } from "lit/directives/when.js";

// TODO: Add webpack/rollup to build so consumers aren't forced to use the same tooling
const tableStyle = require("@material/data-table/dist/mdc.data-table.css");

// language=CSS
const style = css`
  .panel {
    margin: 10px auto;
    max-width: 1200px;
  }

  #parent-edit-wrapper {
    display: flex;
    align-items: end;
  }

  #parent-edit-wrapper > #property-parentId {
    width: 100%;
  }

  #change-parent-btn {
    margin-left: 20px;
  }

  .mdc-data-table__row:hover {
    background-color: inherit !important;
  }

  .mdc-data-table__row {
    border-top-color: #d3d3d3;
  }

  #attribute-table {
    width: 100%;
  }

  .mdc-data-table__table {
    width: 100%;
  }
  .mdc-data-table__header-cell {
    font-weight: 500;
    color: ${unsafeCSS(DefaultColor3)};
  }

  .mdc-data-table__header-cell:first-child {
    padding-left: 36px;
  }
  .expander-cell {
    --or-icon-width: 20px;
    --or-icon-height: 20px;
    cursor: pointer;
  }
  .expander-cell > * {
    pointer-events: none;
  }
  .expander-cell > or-icon {
    vertical-align: middle;
    margin-right: 6px;
    margin-left: -5px;
  }
  .padded-cell {
    padding: 10px 16px;
  }
  .padded-cell > or-attribute-input {
    width: 100%;
  }
  .actions-cell {
    text-align: right;
    width: 40px;
    padding-right: 4px;
  }
  .meta-item-container {
    padding: 0 4px 0 24px;
    overflow: hidden;
    max-height: 0;
    transition: max-height 0.25s ease-out;
  }
  .attribute-meta-row.expanded .meta-item-container {
    transition: max-height 0.5s ease-in-out;
  }
  .meta-item-container or-mwc-input,
  .meta-item-container or-vaadin-input {
    width: 100%;
  }
  .meta-item-wrapper {
    display: flex;
    margin: 10px 0;
  }
  .meta-item-wrapper:first-child {
    margin-top: 0;
  }
  .meta-item-wrapper:hover .button-clear {
    visibility: visible;
  }
  .item-add {
    margin-bottom: 10px;
  }
  .item-add-attribute {
    margin: 10px 0px 10px 4px;
  }
  .button-clear {
    background: none;
    color: ${unsafeCSS(DefaultColor5)};
    --or-icon-fill: ${unsafeCSS(DefaultColor5)};
    visibility: hidden;
    display: inline-block;
    border: none;
    padding: 0 0 0 4px;
    cursor: pointer;
  }
  .button-clear:hover {
    --or-icon-fill: var(--internal-or-asset-viewer-button-color);
  }
  .button-clear:focus {
    outline: 0;
  }
  .button-clear.hidden {
    visibility: hidden;
  }
  .overflow-visible {
    overflow: visible;
  }
`;

export class OrEditAssetModifiedEvent extends CustomEvent<ValidatorResult[]> {
  public static readonly NAME = "or-edit-asset-modified";

  constructor(validatorResults: ValidatorResult[]) {
    super(OrEditAssetModifiedEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: validatorResults,
    });
  }
}

declare global {
  export interface HTMLElementEventMap {
    [OrEditAssetModifiedEvent.NAME]: OrEditAssetModifiedEvent;
  }
}

const AssetNameRegex = /^\w+$/;

export interface ValidatorResult {
  name: string;
  valid: boolean;
  metaResults?: ValidatorResult[]; // For meta items
}

interface TemplateAndValidator {
  template: TemplateResult;
  validator: () => ValidatorResult;
}

@customElement("or-edit-asset-panel")
export class OrEditAssetPanel extends OrElement {
  @property({ attribute: false })
  protected asset!: Asset;

  protected attributeTemplatesAndValidators: TemplateAndValidator[] = [];
  protected changedAttributes: string[] = [];

  protected expandedAll: boolean = false;

  public static get styles() {
    return [unsafeCSS(tableStyle), panelStyles, style];
  }

  public attributeUpdated(attributeName: string) {
    if (!this.asset) {
      return;
    }
    this.changedAttributes.push(attributeName);

    // Request re-render
    this.requestUpdate();
  }

  shouldUpdate(changedProperties: PropertyValues): boolean {
    if (changedProperties.has("asset")) {
      const oldAsset = changedProperties.get("asset") as Asset;
      if (this.asset.attributes && oldAsset?.attributes && this.asset.id === oldAsset.id) {
        // Handles external attribute changes post saving phase.
        this.changedAttributes = Object.values(oldAsset.attributes)
          .filter(({ name, timestamp: oldTimestamp }) => oldTimestamp !== this.asset.attributes?.[name!].timestamp)
          .map(({ name }) => name!);
      } else {
        this.changedAttributes = [];
      }
    }
    return super.shouldUpdate(changedProperties);
  }

  protected render() {
    const updatePublicRead = (publicRead: boolean) => {
      this.asset.accessPublicRead = publicRead;
      this._onModified();
    };

    // Properties panel fields
    const properties: TemplateResult[] = [
      getField(
        "type",
        undefined,
        getPropertyTemplate(this.asset, "type", this, undefined, undefined, {
          readonly: true,
          label: i18next.t("assetType"),
        })
      ),
      getField("parent", undefined, this._getParentTemplate()),
      html`<div
        @change="${(ev: OrInputChangedEvent) => updatePublicRead(Boolean((ev.currentTarget as HTMLInputElement).value))}"
      >
        ${getField("accessPublicRead", undefined, getPropertyTemplate(this.asset, "accessPublicRead", this, undefined, undefined, { readonly: false, label: i18next.t("accessPublicRead") }))}
      </div>`,
    ];

    const expandToggle = (tdElem: HTMLElement) => {
      const expanderIcon = tdElem.getElementsByTagName("or-icon")[0] as OrIcon;
      const headerRow = tdElem.parentElement! as HTMLTableRowElement;
      const metaRow = (headerRow.parentElement! as HTMLTableElement).rows[headerRow.rowIndex];
      const metaContainer = metaRow.firstElementChild!.firstElementChild as HTMLElement;
      const contentHeight = Math.max(500, metaContainer.firstElementChild!.getBoundingClientRect().height);

      if (expanderIcon.icon === "chevron-right") {
        expanderIcon.icon = "chevron-down";
        metaRow.classList.add("expanded");
        metaContainer.style.maxHeight = contentHeight + "px";
        tdElem.classList.add("expanding");
        // Allow container to grow when expanded once animation has finished
        window.setTimeout(() => {
          tdElem.classList.remove("expanding");
          metaContainer.style.maxHeight = "unset";
        }, 250);
      } else {
        expanderIcon.icon = "chevron-right";
        metaRow.classList.remove("expanded");
        metaContainer.style.maxHeight =
          Math.max(500, metaContainer.firstElementChild!.getBoundingClientRect().height) + "px";
        window.setTimeout(() => {
          metaContainer.style.maxHeight = "";
        });
      }
    };

    const expanderToggle = (ev: MouseEvent) => {
      const tdElem = ev.target as HTMLElement;
      if (tdElem.className.indexOf("expander-cell") < 0 || tdElem.className.indexOf("expanding") >= 0) {
        return;
      }
      expandToggle(tdElem);
    };

    const expandAllToggle = (ev: MouseEvent) => {
      const tdElem = ev.target as HTMLElement;
      const tdElems = tdElem.closest("table")?.getElementsByClassName("expander-cell");

      if (tdElems) {
        for (let i = 0; i < tdElems.length; i++) {
          const expanderIcon = tdElems[i].getElementsByTagName("or-icon")[0] as OrIcon;

          if (
            (expanderIcon.icon === "chevron-right" && !this.expandedAll) ||
            (expanderIcon.icon === "chevron-down" && this.expandedAll)
          ) {
            expandToggle(tdElems[i] as HTMLElement);
          }
        }
        tdElem.setAttribute("value", this.expandedAll ? "expandAll" : "collapseAll");
        this.expandedAll = !this.expandedAll;
      }
    };

    this.attributeTemplatesAndValidators = !this.asset.attributes
      ? []
      : Object.entries(this.asset.attributes!)
          .sort(Util.sortByString(([name, attribute]) => name.toUpperCase()))
          .map(([name, attribute]) => {
            attribute.name = name;
            return this._getAttributeTemplate(this.asset.type!, attribute as Attribute<any>);
          });

    const attributes = html`
      <div id="attribute-table" class="mdc-data-table">
        <table class="mdc-data-table__table" aria-label="attribute list" @click="${expanderToggle}">
          <colgroup>
            <col span="1" style="width: 25%;" />
            <col span="1" style="width: 25%;" />
            <col span="1" style="width: 35%;" />
            <col span="1" style="width: 15%;" />
          </colgroup>
          <thead>
            <tr class="mdc-data-table__header-row">
              <th class="mdc-data-table__header-cell" role="columnheader" scope="col">
                <or-translate value="name"></or-translate>
              </th>
              <th class="mdc-data-table__header-cell" role="columnheader" scope="col">
                <or-translate value="type"></or-translate>
              </th>
              <th class="mdc-data-table__header-cell" role="columnheader" scope="col">
                <or-translate value="value"></or-translate>
              </th>
              <th class="mdc-data-table__header-cell" role="columnheader" scope="col" style="padding-right:8px;">
                <or-vaadin-button style="float: right;" @click=${expandAllToggle}>
                  <or-translate value="expandAll"></or-translate>
                </or-vaadin-button>
              </th>
            </tr>
          </thead>
          <tbody class="mdc-data-table__content">
            ${this.attributeTemplatesAndValidators.map((attrTemplateAndValidator) => attrTemplateAndValidator.template)}
            <tr class="mdc-data-table__row">
              <td colspan="4">
                <div class="item-add-attribute">
                  <or-vaadin-button @click=${() => this._addAttribute()}>
                    <or-icon slot="prefix" icon="plus"></or-icon>
                    <or-translate value="addAttribute"></or-translate>
                  </or-vaadin-button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    `;

    return html` <div id="edit-wrapper">
      ${getPanel("0", { type: "info", title: "properties" }, html`${properties}`) || ``}
      ${getPanel("1", { type: "info", title: "attribute_plural" }, html`${attributes}`) || ``}
    </div>`;
  }

  protected _getAttributeTemplate(assetType: string, attribute: Attribute<any>): TemplateAndValidator {
    const deleteAttribute = () => {
      delete this.asset.attributes![attribute.name!];
      this._onModified();
      this.requestUpdate();
    };

    const descriptor = AssetModelUtil.getAttributeDescriptor(attribute.name!, assetType);
    const canDelete = !descriptor || descriptor.optional;
    const attributeInputRef: Ref<OrAttributeInput> = createRef();
    const metaTemplatesAndValidators = !attribute.meta
      ? []
      : Object.entries(attribute.meta)
          .sort(Util.sortByString(([name, value]) => name!))
          .map(([name, value]) => this._getMetaItemTemplate(attribute, Util.getMetaItemNameValueHolder(name, value)));

    const validator = (): ValidatorResult => {
      let valid = false;

      if (attributeInputRef.value) {
        valid = attributeInputRef.value.checkValidity();
      }

      return {
        name: attribute.name!,
        valid,
        metaResults: metaTemplatesAndValidators.map((metaTemplateAndValidator) => metaTemplateAndValidator.validator()),
      };
    };

    // Booleans formatted asMomentary should be read-only in modify mode
    const formattedAsMomentary =
      attribute.meta &&
      (attribute.meta.hasOwnProperty(WellknownMetaItems.MOMENTARY) ||
        (attribute.meta.hasOwnProperty(WellknownMetaItems.FORMAT) &&
          attribute.meta[WellknownMetaItems.FORMAT]?.asMomentary));

    const template = html`
      <tr class="mdc-data-table__row">
        <td class="padded-cell mdc-data-table__cell expander-cell">
          <or-icon icon="chevron-right"></or-icon><span>${attribute.name}</span>
        </td>
        <td class="padded-cell mdc-data-table__cell">${Util.getValueDescriptorLabel(attribute.type!)}</td>
        <td class="padded-cell overflow-visible mdc-data-table__cell">
          <or-attribute-input
            ${ref(attributeInputRef)}
            .comfortable="${true}"
            .assetType="${assetType}"
            .label=${null}
            .readonly="${formattedAsMomentary}"
            .attribute="${attribute}"
            .assetId="${this.asset.id!}"
            disableWrite
            disableSubscribe
            disableButton
            compact
            @or-attribute-input-changed="${(e: OrAttributeInputChangedEvent) => this._onAttributeModified(attribute, e.detail.value)}"
          ></or-attribute-input>
        </td>
        <td class="padded-cell mdc-data-table__cell actions-cell">
          ${when(
            canDelete,
            () => html`
              <or-vaadin-button theme="icon" @click=${deleteAttribute}>
                <or-icon icon="delete"></or-icon>
              </or-vaadin-button>
            `
          )}
        </td>
      </tr>
      <tr class="attribute-meta-row">
        <td colspan="4">
          <div class="meta-item-container">
            <div>
              <div>
                ${metaTemplatesAndValidators.map((metaTemplateAndValidator) => metaTemplateAndValidator.template)}
              </div>
              <div class="item-add">
                <or-vaadin-button @click=${() => this._addMetaItems(attribute)}>
                  <or-icon slot="prefix" icon="plus"></or-icon>
                  <or-translate value="addMetaItems"></or-translate>
                </or-vaadin-button>
              </div>
            </div>
          </div>
        </td>
      </tr>
    `;

    return {
      template,
      validator,
    };
  }

  protected _onModified() {
    this.dispatchEvent(new OrEditAssetModifiedEvent(this.validate()));
  }

  public validate(): ValidatorResult[] {
    return this.attributeTemplatesAndValidators.map((attrTemplateAndValidator) => attrTemplateAndValidator.validator());
  }

  protected _onAttributeModified(attribute: Attribute<any>, newValue: any) {
    console.debug("Detected attribute modification;", attribute, newValue);

    // Check if modification came from external change
    const index = this.changedAttributes.indexOf(attribute.name!);
    if (index > -1) {
      this.changedAttributes.splice(index, 1);
      return;
    }

    attribute.value = newValue;
    attribute.timestamp = undefined; // Clear timestamp so server will set this
    this._onModified();
  }

  protected _onMetaItemModified(attribute: Attribute<any>, metaItem: NameValueHolder<any>, value: any) {
    console.debug("Detected Meta item modification;", attribute, metaItem);
    metaItem.value = value ?? undefined;
    attribute.meta![metaItem.name!] = metaItem.value;
    this._onModified();
  }

  protected _getMetaItemTemplate(attribute: Attribute<any>, metaItem: NameValueHolder<any>): TemplateAndValidator {
    const descriptor = AssetModelUtil.getMetaItemDescriptor(metaItem.name);
    const valueDescriptor = descriptor ? AssetModelUtil.getValueDescriptor(descriptor.type) : undefined;
    let content: TemplateResult = html``;
    let validator: () => ValidatorResult = () => {
      return {
        name: metaItem.name!,
        valid: true,
      };
    };

    if (!valueDescriptor) {
      console.log(
        "Couldn't find value descriptor for meta item so falling back to simple JSON input: " + metaItem.name
      );
      content = html`<or-mwc-input
        @or-mwc-input-changed="${(ev: OrInputChangedEvent) => this._onMetaItemModified(attribute, metaItem, ev.detail.value)}"
        .type="${InputType.JSON}"
        .value="${metaItem.value}"
      ></or-mwc-input>`;
    } else {
      const options: ValueInputProviderOptions = {
        label: Util.getMetaLabel(metaItem, descriptor!, this.asset.type!, true),
        resizeVertical: true,
      };
      const standardInputProvider = getValueHolderInputTemplateProvider(
        this.asset.type!,
        metaItem,
        descriptor,
        valueDescriptor,
        (value: any) => this._onMetaItemModified(attribute, metaItem, value),
        options
      );
      let provider = jsonFormsInputTemplateProvider(standardInputProvider)(
        this.asset.type!,
        metaItem,
        descriptor,
        valueDescriptor,
        (detail) => this._onMetaItemModified(attribute, metaItem, detail?.value),
        options
      );

      if (!provider) {
        provider = standardInputProvider;
      }

      if (provider && provider.templateFunction) {
        content = html`${until(provider.templateFunction(metaItem.value, false, false, false, false, undefined), ``)}`;
      }
      if (provider.validator) {
        validator = () => {
          return {
            name: metaItem.name!,
            valid: provider.validator!(),
          };
        };
      }
    }

    const removeMetaItem = () => {
      delete attribute.meta![metaItem.name!];
      this._onModified();
      this.requestUpdate();
    };

    const template = html`
            <div class="meta-item-wrapper">
                ${content}
                <button class="button-clear" @click="${removeMetaItem}">
                    <or-icon icon="close-circle"></or-icon>
                    </input>
            </div>
        `;

    return {
      template,
      validator,
    };
  }

  protected _addAttribute() {
    const asset = this.asset!;
    let attr: Attribute<any>;
    const addBtnRef: Ref<OrVaadinButton> = createRef();

    const isDisabled = (attribute: Attribute<any>) => {
      return !(
        attribute &&
        attribute.name &&
        !asset.attributes![attribute.name] &&
        AssetNameRegex.test(attribute.name) &&
        attribute.type
      );
    };

    const onAttributeChanged = (ev: OrAddAttributePanelAttributeChangedEvent) => {
      const attribute = ev.detail;
      addBtnRef.value!.disabled = isDisabled(attribute);
      attr = attribute;
    };

    const onOk = () => {
      if (!isDisabled(attr)) {
        this.asset.attributes![attr.name!] = attr;
        this._onModified();
        this.requestUpdate();
        dialog?.close();
      }
    };

    const dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value="addAttribute"></or-translate>
          </h2>
          <or-add-attribute-panel
            .asset="${asset}"
            @or-add-attribute-panel-attribute-changed="${onAttributeChanged}"
          ></or-add-attribute-panel>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" ${ref(addBtnRef)} disabled @click=${onOk}>
              <or-translate value="add"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  protected _addMetaItems(attribute: Attribute<any>) {
    const assetTypeInfo = AssetModelUtil.getAssetTypeInfo(this.asset!.type!);
    if (!assetTypeInfo || !assetTypeInfo.metaItemDescriptors) {
      return;
    }

    const meta = attribute.meta || {};

    const listRef: Ref<OrVaadinCheckboxGroup> = createRef();
    const onOk = () => {
      const selectedItems = listRef.value?.value;
      if (selectedItems) {
        if (!attribute.meta) {
          attribute.meta = {};
        }
        selectedItems.forEach((item) => {
          const descriptor = AssetModelUtil.getMetaItemDescriptors().find((descriptor) => descriptor.name === item);
          if (descriptor) {
            attribute.meta![descriptor.name!] = descriptor.type === "boolean" ? true : null;
            this._onModified();
            this.requestUpdate();
          }
        });
      }
      dialog?.close();
    };

    const dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value="addMetaItems"></or-translate>
          </h2>
          <or-vaadin-checkbox-group theme="vertical" ${ref(listRef)}>
            ${assetTypeInfo.metaItemDescriptors
              .map((metaName) => AssetModelUtil.getMetaItemDescriptor(metaName)!)
              .filter((descriptor) => !meta.hasOwnProperty(descriptor.name!))
              .map((descriptor) => ({
                descriptor,
                label: Util.getMetaLabel(undefined, descriptor, this.asset!.type!, true),
              }))
              .sort(Util.sortByString((item) => item.label))
              .map(
                (item) => html`
                  <or-vaadin-checkbox value=${item.descriptor.name!} label=${item.label}></or-vaadin-checkbox>
                `
              )}
          </or-vaadin-checkbox-group>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" @click=${onOk}>
              <or-translate value="add"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  protected _getParentTemplate() {
    let dialog: OrVaadinDialog | undefined;
    const assetTreeRef: Ref<OrAssetTree> = createRef();

    const setParent = () => {
      const assetTree = assetTreeRef.value!;
      this.asset.parentId = assetTree.selectedIds!.length === 1 ? assetTree.selectedIds![0] : undefined;
      // Need to update the assets path as well
      const path = [this.asset.id!];
      let parentNode = assetTree.selectedNodes[0];
      while (parentNode !== undefined) {
        path.unshift(parentNode.asset!.id!);
        parentNode = parentNode.parent;
      }
      this.asset.path = path;
      this._onModified();
    };

    const clearParent = () => {
      this.asset.parentId = undefined;
      this.asset.path = [this.asset.id!];
      this._onModified();
      dialog?.close();
    };

    const blockEvent = (ev: Event) => ev.stopPropagation();

    const parentSelector = (node: UiAssetTreeNode) => node.asset!.id !== this.asset.id;

    const onOk = () => {
      setParent();
      dialog?.close();
    };

    const openDialog = () => {
      dialog = showDialog(
        this.shadowRoot!,
        html`
          <or-vaadin-dialog width="384px">
            <h2 slot="header-content">
              <or-translate value="setParent"></or-translate>
            </h2>
            <or-asset-tree
              ${ref(assetTreeRef)}
              disableSubscribe
              readonly
              .selectedIds="${this.asset!.parentId ? [this.asset.parentId] : []}"
              style="width: 100%; aspect-ratio: 1/1.75;"
              @or-asset-tree-request-selection="${blockEvent}"
              @or-asset-tree-selection="${blockEvent}"
              .selector="${parentSelector}"
            ></or-asset-tree>
            <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
              <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
                <or-translate value="cancel"></or-translate>
              </or-vaadin-button>
              <div>
                <or-vaadin-button @click=${clearParent}>
                  <or-translate value="clear"></or-translate>
                </or-vaadin-button>
                <or-vaadin-button theme="primary" @click=${onOk}>
                  <or-translate value="ok"></or-translate>
                </or-vaadin-button>
              </div>
            </div>
          </or-vaadin-dialog>
        `
      );
    };

    return html`
      <div id="parent-edit-wrapper">
        ${getPropertyTemplate(this.asset, "parentId", this, undefined, undefined, { readonly: true, label: i18next.t("parent") })}
        <or-vaadin-button id="change-parent-btn" @click=${openDialog}>
          <or-translate value="edit"></or-translate>
        </or-vaadin-button>
      </div>
    `;
  }
}
