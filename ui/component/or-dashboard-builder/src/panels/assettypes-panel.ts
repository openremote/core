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
import { css, html, type PropertyValues, type TemplateResult } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, state } from "lit/decorators.js";
import { type AssetDescriptor, AssetModelUtil, type AssetTypeInfo } from "@openremote/model";
import { i18next } from "@openremote/or-translate";
import type { OrVaadinToggle } from "@openremote/or-vaadin-components/or-vaadin-toggle";
import { Util } from "@openremote/core";
import { when } from "lit/directives/when.js";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import type { AssetTreeConfig, OrAssetTree } from "@openremote/or-asset-tree";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import {
  type ComboBoxLitRenderer,
  comboBoxRenderer,
  type OrVaadinComboBox,
} from "@openremote/or-vaadin-components/or-vaadin-combo-box";
import type { OrVaadinMultiSelectComboBox } from "@openremote/or-vaadin-components/or-vaadin-multi-select-combo-box";
import type { ListItem } from "@openremote/or-vaadin-components/or-vaadin-list-box";
import { styleMap } from "lit/directives/style-map.js";

export class AssetTypeSelectEvent extends CustomEvent<string> {
  public static readonly NAME = "assettype-select";

  constructor(assetTypeName: string) {
    super(AssetTypeSelectEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: assetTypeName,
    });
  }
}

export class AssetAllOfTypeSwitchEvent extends CustomEvent<boolean> {
  public static readonly NAME = "alloftype-switch";

  constructor(allOfType: boolean) {
    super(AssetAllOfTypeSwitchEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: allOfType,
    });
  }
}

export class AssetIdsSelectEvent extends CustomEvent<string | string[]> {
  public static readonly NAME = "assetids-select";

  constructor(assetIds: string | string[]) {
    super(AssetIdsSelectEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: assetIds,
    });
  }
}

export class AttributeNamesSelectEvent extends CustomEvent<string | string[]> {
  public static readonly NAME = "attributenames-select";

  constructor(attributeNames: string[]) {
    super(AttributeNamesSelectEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: attributeNames,
    });
  }
}

export interface AssetTypesFilterConfig {
  assets?: {
    enabled?: boolean;
    multi?: boolean;
    allOfTypeOption?: boolean;
  };
  attributes?: {
    enabled?: boolean;
    multi?: boolean;
    valueTypes?: string[];
  };
}

const styling = css`
  .switchMwcInputContainer {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
`;

@customElement("assettypes-panel")
export class AssettypesPanel extends OrElement {
  @property() // selected asset type
  protected assetType?: string;

  @property()
  protected config: AssetTypesFilterConfig = {
    attributes: {
      enabled: true,
    },
  };

  @property() // Whether to include all assets of this type or if the user can choose specific assets
  protected allOfType: boolean = false;

  @property() // IDs of assets; either undefined, a single entry, or multi select
  protected assetIds: undefined | string | string[];

  @property() // names of selected attributes; either undefined, a single entry, or multi select
  protected attributeNames: undefined | string[];

  /* ----------- */

  @state()
  protected _attributeSelectList: string[][] = [];

  @state()
  protected _loadedAssetTypes: AssetDescriptor[] = AssetModelUtil.getAssetDescriptors().filter(
    (t) => t.descriptorType === "asset"
  );

  static get styles() {
    return [styling];
  }

  protected willUpdate(changedProps: PropertyValues) {
    super.willUpdate(changedProps);
    if (changedProps.has("assetType") && this.assetType) {
      this._attributeSelectList = this.getAttributesByType(this.assetType)!;
      this.dispatchEvent(new AssetTypeSelectEvent(this.assetType));
    }
    if (changedProps.has("allOfType")) {
      this.dispatchEvent(new AssetAllOfTypeSwitchEvent(this.allOfType));
    }
    if (changedProps.has("assetIds") && this.assetIds) {
      this.dispatchEvent(new AssetIdsSelectEvent(this.assetIds));
    }
    if (changedProps.has("attributeNames") && this.attributeNames) {
      this.dispatchEvent(new AttributeNamesSelectEvent(this.attributeNames));
    }
  }

  protected render(): TemplateResult {
    const assetTypeItems = this.mapDescriptors(this._loadedAssetTypes) as ListItem[];
    const selected = this.assetType ? assetTypeItems.find((i) => i.value === this.assetType) : undefined;
    const itemRenderer: ComboBoxLitRenderer<ListItem> = (listItem: ListItem) => html`
      <div style="display: flex; align-items: center; gap: 6px;">
        <or-icon icon=${listItem.icon} style=${listItem.styleMap ? styleMap(listItem.styleMap) : undefined}></or-icon>
        <span>${listItem.text}</span>
      </div>
    `;
    return html`
      <div style="display: flex; flex-direction: column; gap: 8px;">
        <!-- Select asset type -->
        <or-vaadin-combo-box
          .items=${assetTypeItems}
          .selectedItem=${selected}
          required
          item-value-path="value"
          item-label-path="text"
          ${comboBoxRenderer(itemRenderer, [])}
          @change=${(ev: CustomEvent) => {
            this.assetType = (ev.currentTarget as OrVaadinComboBox).value;
          }}
        >
          <or-icon
            slot="prefix"
            icon=${selected?.icon ?? "selection-ellipse"}
            style=${selected?.styleMap ? styleMap(selected.styleMap) : undefined}
          ></or-icon>
          <or-translate slot="label" value="filter.assetTypeLabel"></or-translate>
        </or-vaadin-combo-box>

        <!-- Switch to include all assets of this type  -->
        ${when(this.config.assets?.allOfTypeOption, () => {
          return html`
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <span><or-translate value="allAssetsofType"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${this.allOfType}"
                ?disabled="${!this.assetType}"
                @change="${(ev: Event) => this.onAssetAllOfTypeToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
          `;
        })}

        <!-- Select one or more assets -->
        ${when(this.config.assets?.enabled, () => {
          const assetIds = typeof this.assetIds === "string" ? [this.assetIds] : this.assetIds;
          const label = this.allOfType
            ? i18next.t("allAssets")
            : (this.assetIds?.length || 0) + " " + i18next.t("assets");
          return html`
            <or-vaadin-button
              ?disabled=${!this.assetType || this.allOfType}
              @click=${() => this._openAssetSelector(this.assetType!, assetIds, this.config.assets?.multi)}
            >
              <span>${label}</span>
            </or-vaadin-button>
          `;
        })}

        <!-- Select one or more attributes -->
        ${when(this.config.attributes?.enabled, () => {
          const options = this._attributeSelectList?.map((al) => ({ key: al[0], value: al[1] })) ?? [];
          const selectedItems = this.attributeNames?.map((n) => options.find((o) => o.key === n)) ?? [];
          if (this.config.attributes?.multi) {
            return html`
              <or-vaadin-multi-select-combo-box
                .items=${options}
                .selectedItems=${selectedItems}
                item-value-path="key"
                item-label-path="value"
                ?disabled=${!this.assetType}
                @change=${(ev: CustomEvent) => {
                  this.attributeNames = (ev.currentTarget as OrVaadinMultiSelectComboBox).selectedItems.map(
                    (i) => i.key
                  );
                }}
              >
                <or-translate slot="label" value="filter.attributeLabel"></or-translate>
              </or-vaadin-multi-select-combo-box>
            `;
          } else {
            return html`
              <or-vaadin-combo-box
                .items=${options}
                .selectedItem=${selectedItems[0]}
                item-value-path="key"
                item-label-path="value"
                ?disabled=${!this.assetType}
                @change=${(ev: CustomEvent) => {
                  this.attributeNames = [(ev.currentTarget as OrVaadinComboBox).value];
                }}
              >
                <or-translate slot="label" value="filter.attributeLabel"></or-translate>
              </or-vaadin-combo-box>
            `;
          }
        })}
      </div>
    `;
  }

  /* ----------- */

  protected mapDescriptors(descriptors: AssetDescriptor[], withNoneValue?: ListItem): ListItem[] {
    const items: ListItem[] = descriptors
      .map((descriptor) => {
        return {
          styleMap: {
            color: descriptor.colour ? "#" + descriptor.colour : "unset",
          },
          icon: descriptor.icon,
          text: Util.getAssetTypeLabel(descriptor),
          value: descriptor.name!,
          data: descriptor,
        };
      })
      .sort(Util.sortByString((listItem) => listItem.text));

    if (withNoneValue) {
      items.splice(0, 0, withNoneValue);
    }
    return items;
  }

  protected getAttributesByType(type: string) {
    const descriptor: AssetDescriptor = AssetModelUtil.getAssetDescriptor(type) as AssetDescriptor;
    if (descriptor) {
      const typeInfo: AssetTypeInfo = AssetModelUtil.getAssetTypeInfo(descriptor) as AssetTypeInfo;
      if (typeInfo?.attributeDescriptors) {
        const valueTypes = this.config.attributes?.valueTypes;
        const filtered = valueTypes
          ? typeInfo.attributeDescriptors.filter((ad) => valueTypes.indexOf(ad.type!) > -1)
          : typeInfo.attributeDescriptors;
        return filtered
          .map((ad) => {
            const label = Util.getAttributeLabel(ad, undefined, type, false);
            return [ad.name!, label];
          })
          .sort(Util.sortByString((attr) => attr[1]));
      }
    }
  }

  protected onAssetAllOfTypeToggle(ev: Event) {
    this.allOfType = (ev.currentTarget as OrVaadinToggle).checked;
  }

  protected _openAssetSelector(assetType: string, assetIds?: string[], multi = false) {
    const assetTreeRef: Ref<OrAssetTree> = createRef();
    const config = {
      select: {
        types: [assetType],
        multiSelect: multi,
      },
    } as AssetTreeConfig;

    let dialog: OrVaadinDialog | undefined;
    const onCancel = () => dialog?.close();
    const onOk = () => {
      const tree = assetTreeRef.value;
      if (tree?.selectedIds) {
        if (multi) {
          this.assetIds = tree.selectedIds;
        } else {
          this.assetIds = tree.selectedIds[0];
        }
      }
      dialog?.close();
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value="linkedAssets"></or-translate>
          </h2>
          <or-asset-tree
            ${ref(assetTreeRef)}
            readonly
            disableSubscribe
            .config="${config}"
            .selectedIds="${assetIds}"
            .showSortBtn="${false}"
            .showFilterIcon="${false}"
            .checkboxes="${multi}"
            style="width: 100%; aspect-ratio: 1/1.75;"
          ></or-asset-tree>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between">
            <or-vaadin-button theme="tertiary" @click=${onCancel}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" @click=${onOk}>
              <or-translate value="ok"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }
}
