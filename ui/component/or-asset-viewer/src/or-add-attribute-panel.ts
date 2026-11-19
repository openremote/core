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
import { html, css, type PropertyValues } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property } from "lit/decorators.js";
import type { OrVaadinTextField } from "@openremote/or-vaadin-components/or-vaadin-text-field";
import type { OrVaadinComboBox } from "@openremote/or-vaadin-components/or-vaadin-combo-box";
import type { OrVaadinCheckbox } from "@openremote/or-vaadin-components/or-vaadin-checkbox";
import type { OrVaadinNumberField } from "@openremote/or-vaadin-components/or-vaadin-number-field";
import { i18next } from "@openremote/or-translate";
import { type Asset, type Attribute, AssetModelUtil } from "@openremote/model";
import { Util } from "@openremote/core";

export class OrAddAttributePanelAttributeChangedEvent extends CustomEvent<Attribute<any>> {
  public static readonly NAME = "or-add-attribute-panel-attribute-changed";

  constructor(attribute: Attribute<any>) {
    super(OrAddAttributePanelAttributeChangedEvent.NAME, {
      bubbles: true,
      composed: false,
      detail: attribute,
    });
  }
}
declare global {
  export interface HTMLElementEventMap {
    [OrAddAttributePanelAttributeChangedEvent.NAME]: OrAddAttributePanelAttributeChangedEvent;
  }
}

@customElement("or-add-attribute-panel")
export class OrAddAttributePanel extends OrElement {
  @property({ attribute: false })
  protected asset!: Asset;

  @property({ attribute: false })
  protected attribute: Attribute<any> = {};

  @property()
  protected isCustom: boolean = false;

  @property()
  public isArray: boolean = false;

  @property()
  public arrayDimensions: number = 1;

  protected customAttribute: boolean = true;
  protected attributeTypes?: [string, string][];
  protected attributeValueTypes?: [string, string][];
  protected arrayRegex: RegExp = /\[\]/g;

  public static get styles() {
    return css`
      #attribute-creator {
        min-width: 300px;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
    `;
  }

  protected shouldUpdate(_changedProperties: PropertyValues) {
    if (_changedProperties.has("asset")) {
      this.attributeTypes = undefined;
      this.attributeValueTypes = undefined;

      this.attributeTypes = (AssetModelUtil.getAssetTypeInfo(this.asset.type!)?.attributeDescriptors || [])
        .filter((descriptor) => !this.asset.attributes![descriptor.name!])
        .sort(Util.sortByString((descriptor) => descriptor.name!))
        .map((descriptor) => {
          return [descriptor.name!, Util.getAttributeLabel(undefined, descriptor, this.asset.type, false)];
        });
      this.attributeTypes = [["@custom", i18next.t("custom")], ...this.attributeTypes];

      this.attributeValueTypes = (AssetModelUtil.getAssetTypeInfo(this.asset.type!)?.valueDescriptors || [])
        .sort(Util.sortByString((descriptor) => descriptor[0]))
        .filter((descriptorName) => {
          const valueDescriptor = AssetModelUtil.getValueDescriptor(descriptorName);
          return !valueDescriptor || !valueDescriptor.metaUseOnly;
        })
        .map((descriptor) => {
          return [descriptor, Util.getValueDescriptorLabel(descriptor)];
        });
    }

    return super.shouldUpdate(_changedProperties);
  }

  protected render() {
    if (!this.attributeTypes || !this.attributeValueTypes) {
      return;
    }
    const nameItems = this.attributeTypes.map((type) => ({ key: type[0], value: type[1] }));
    const nameSelected = nameItems.find((n) => n.key === this.attribute.name) ?? nameItems[0];
    const typeItems = this.attributeValueTypes.map((type) => ({ key: type[0], value: type[1] }));
    const typeSelected = typeItems.find((i) => i.key === this.attribute?.type?.replace(this.arrayRegex, ""));

    return html`
            <div id="attribute-creator">
                <or-vaadin-combo-box .items=${nameItems} .selectedItem=${nameSelected} item-value-path="key" item-label-path="value"
                                     @change=${(ev: CustomEvent) => this.onTypeChanged((ev.currentTarget as OrVaadinComboBox).value)}>
                    <or-translate slot="label" value="type"></or-translate>
                </or-vaadin-combo-box>
                <or-vaadin-text-field id="name-input" ?readonly=${!this.customAttribute} value=${this.attribute?.name} pattern="\\w+" required
                                      @input=${(ev: InputEvent) => this.onNameChanged((ev.currentTarget as OrVaadinTextField).value)}>
                    <or-translate slot="label" value="name"></or-translate>
                </or-vaadin-text-field>
                <or-vaadin-combo-box id="type-input" ?readonly=${!this.customAttribute} item-value-path="key" item-label-path="value"
                                     .items=${typeItems} .selectedItem=${typeSelected}
                                     @change=${(ev: CustomEvent) => this.onValueTypeChanged((ev.currentTarget as OrVaadinComboBox).value)}>
                    <or-translate slot="label" value="valueType"></or-translate>
                </or-vaadin-combo-box>
                <or-vaadin-checkbox id="array-checkbox" ?readonly=${!this.customAttribute} ?checked=${this.isArray}
                                    @change=${(ev: Event) => this.onArrayChanged((ev.currentTarget as OrVaadinCheckbox).checked, 1)}>
                    <label slot="label"><or-translate slot="label" value="array"></label></or-translate>
                </or-vaadin-checkbox>
                <or-vaadin-number-field id="array-input" ?disabled=${!this.isArray} ?readonly=${!this.customAttribute} value=${this.arrayDimensions} min="1" max="2"
                                        @change=${(ev: Event) => this.onArrayChanged(true, Number((ev.currentTarget as OrVaadinNumberField).value))}>
                    <or-translate slot="label" value="arrayDimensions"></or-translate>
                </or-vaadin-number-field>
            </div>
        `;
  }

  protected onTypeChanged(name: string) {
    if (name === "@custom") {
      this.customAttribute = true;
      this.attribute = {
        meta: {},
      };
    } else {
      this.customAttribute = false;
      const descriptor = AssetModelUtil.getAttributeDescriptor(name, this.asset.type!)!;
      this.attribute = {};
      this.attribute.name = descriptor.name;
      this.attribute.type = descriptor.type;
      if (descriptor.meta) {
        this.attribute.meta = JSON.parse(JSON.stringify(descriptor.meta));
      } else {
        this.attribute.meta = {};
      }
    }

    this.dispatchEvent(new OrAddAttributePanelAttributeChangedEvent(this.attribute));
  }

  protected onNameChanged(name: string) {
    this.attribute.name = name;
    this.dispatchEvent(new OrAddAttributePanelAttributeChangedEvent(this.attribute));
  }

  protected onValueTypeChanged(valueType: string) {
    this.attribute.type = valueType;
    this.updateAttributeType();
  }

  protected onArrayChanged(array: boolean, dimensions: number) {
    this.isArray = array;
    this.arrayDimensions = dimensions;
    this.updateAttributeType();
  }

  protected updateAttributeType() {
    if (!this.attribute || !this.attribute.type) {
      return;
    }

    let type = this.attribute.type.replace(this.arrayRegex, "");
    if (this.isArray) {
      for (let i = 1; i <= this.arrayDimensions; i++) {
        type += "[]";
      }
    }
    this.attribute.type = type;
    this.dispatchEvent(new OrAddAttributePanelAttributeChangedEvent(this.attribute));
  }
}
