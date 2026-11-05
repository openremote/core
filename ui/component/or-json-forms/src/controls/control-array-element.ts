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
import {
  computeLabel,
  createDefaultValue,
  type JsonSchema,
  mapDispatchToArrayControlProps,
  type OwnPropsOfRenderer,
  Paths,
  Resolve,
  update,
} from "@jsonforms/core";
import { css, html, type PropertyValues, type TemplateResult, unsafeCSS } from "lit";
import { customElement, property } from "lit/decorators.js";
import {
  type CombinatorInfo,
  controlWithoutLabel,
  getCombinatorInfos,
  getTemplateFromProps,
  showJsonEditor,
} from "../util";
import { i18next } from "@openremote/or-translate";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { addItemOrParameterDialogStyle, baseStyle, panelStyle } from "../styles";
import type { ListItem } from "@openremote/or-vaadin-components/or-vaadin-list-box";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-list-box";
import "@openremote/or-vaadin-components/or-vaadin-item";
import "@openremote/or-vaadin-components/or-vaadin-button";
import { DefaultColor4, DefaultColor5 } from "@openremote/core";
import { ControlBaseElement } from "./control-base-element";
import { getTemplateWrapper } from "../index";
import type { AdditionalProps } from "../base-element";

// language=CSS
const style = css`
  .item-border,
  .drag-handle {
    border-color: var(--or-app-color5, ${unsafeCSS(DefaultColor5)});
    border-radius: 4px;
    border-width: 1px;
    border-style: solid;
  }

  .item-wrapper {
    display: flex;
  }

  .item-wrapper > .item-container {
    flex: 1;
  }

  .item-wrapper + .item-wrapper {
    padding-top: 10px;
  }

  .item-wrapper > .item-container > .item-container {
    margin: 0;
    flex: 1;
  }

  .item-wrapper > .item-container > .item-container > .delete-container {
    display: none;
  }

  .item-wrapper > .item-container > .item-container :first-child {
    padding: 0;
    margin: 0;
    flex: 1;
  }

  .item-wrapper.dragging > .item-container {
    opacity: 0.5;
  }

  .item-wrapper.indicator-after {
    border-bottom-width: 3px;
    border-bottom-style: solid;
    border-bottom-color: var(--or-app-color4, ${unsafeCSS(DefaultColor4)});
  }

  .item-wrapper.indicator-before {
    border-top-width: 3px;
    border-top-style: solid;
    border-top-color: var(--or-app-color4, ${unsafeCSS(DefaultColor4)});
  }

  .drag-container > button {
    cursor: grab;
  }

  #content-wrapper {
    overflow: auto;
  }

  [slot="header"] {
    display: flex;
    min-width: 0; /* Allows slotted element to shrink */
    & span {
      align-content: center;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      word-break: break-all;
    }
  }
`;

@customElement("or-json-forms-array-control")
export class ControlArrayElement extends ControlBaseElement {
  @property()
  protected minimal?: boolean;

  protected resolvedSchema!: JsonSchema;
  protected itemInfos: CombinatorInfo[] | undefined;
  protected addItem!: (value: any) => void;
  protected removeItem!: (index: number) => void;
  protected moveItem!: (fromIndex: number, toIndex: number) => void;

  public static get styles() {
    return [baseStyle, panelStyle, addItemOrParameterDialogStyle, style];
  }

  shouldUpdate(_changedProperties: PropertyValues): boolean {
    if (_changedProperties.has("schema")) {
      this.itemInfos = undefined;
      this.resolvedSchema = Resolve.schema(this.schema, "items", this.rootSchema);

      if (Array.isArray(this.resolvedSchema.anyOf)) {
        this.itemInfos = getCombinatorInfos(this.resolvedSchema.anyOf, this.rootSchema);
      } else if (Array.isArray(this.resolvedSchema.oneOf)) {
        this.itemInfos = getCombinatorInfos(this.resolvedSchema.oneOf, this.rootSchema);
      }
    }

    if (_changedProperties.has("state")) {
      const dispatchMethods = mapDispatchToArrayControlProps(this.state.dispatch);
      this.addItem = (value) => dispatchMethods.addItem(this.path, value)();
      this.removeItem = (index) => {
        dispatchMethods.removeItems!(this.path, [index])();
      };
      this.moveItem = (fromIndex, toIndex) => {
        const move = (input: [], from: number, to: number) => {
          let numberOfDeletedElm = 1;
          const elm = input.splice(from, numberOfDeletedElm)[0];
          numberOfDeletedElm = 0;
          input.splice(to, numberOfDeletedElm, elm);
        };

        this.state.dispatch(
          update(this.path, (array) => {
            move(array, fromIndex, toIndex);
            return array;
          })
        );
      };
    }

    return super.shouldUpdate(_changedProperties);
  }

  render() {
    const maxItems = this.schema.maxItems ?? Number.MAX_SAFE_INTEGER;
    const itemCount = Array.isArray(this.data) ? (this.data as []).length : 0;

    const header = this.minimal
      ? ``
      : html`
          <div slot="header">
            <span>${this.label ? computeLabel(this.label, this.required, false) : ""}</span>
          </div>
          <div id="header-description" slot="header-description">
            <div id="errors">
              ${!this.errors ? `` : html`<or-icon icon="alert"></or-icon><span>${this.errors}</span>`}
            </div>
            <div id="header-buttons">
              <or-vaadin-button @click=${(ev: Event) => this._showJson(ev)}>
                <or-icon slot="prefix" icon="pencil"></or-icon>
                <or-translate value="JSON"></or-translate>
              </or-vaadin-button>
            </div>
          </div>
        `;

    const content = html`
      ${header}
      <div id="content-wrapper" slot="content">
        <div id="content" @dragover="${(ev: DragEvent) => this._onDragOver(ev)}">
          ${
            !Array.isArray(this.data)
              ? ``
              : (this.data as any[]).map((item, index) => {
                  const childPath = Paths.compose(this.path, "" + index);

                  const props: OwnPropsOfRenderer | AdditionalProps = {
                    renderers: this.renderers,
                    uischema: controlWithoutLabel("#"),
                    schema: this.resolvedSchema,
                    path: childPath,
                  };

                  return this.getArrayItemWrapper(getTemplateFromProps(this.state, props) || html``, index);
                })
          }
        </div>
        ${
          this.errors
            ? ``
            : html`
                <div id="footer">
                  <or-vaadin-button ?disabled=${itemCount && itemCount >= maxItems} @click=${() => this.doAddItem()}>
                    <or-icon slot="prefix" icon="plus"></or-icon>
                    <or-translate value="addItem"></or-translate>
                  </or-vaadin-button>
                </div>
              `
        }
      </div>
    `;

    return this.minimal ? html`<div>${content}</div>` : html`<or-collapsible-panel>${content}</or-collapsible-panel>`;
  }

  protected getArrayItemWrapper(elementTemplate: TemplateResult, index: number) {
    return html`
            <div class="item-wrapper" data-index="${index}">
                <div class="drag-container">
                    <button draggable="true" @dragstart="${(ev: DragEvent) => this._onDragStart(ev)}" @dragend="${(ev: DragEvent) => this._onDragEnd(ev)}" class="draggable button-clear"><or-icon icon="menu"></or-icon></input>
                </div>
                ${getTemplateWrapper(elementTemplate, () => this.removeItem(index))}
            </div>
        `;
  }

  protected _onDragStart(ev: DragEvent) {
    const buttonElem = ev.currentTarget as HTMLButtonElement;
    const itemWrapperElem = buttonElem!.parentElement!.parentElement as HTMLDivElement;
    itemWrapperElem.classList.add("dragging");
    const itemContainerElem = itemWrapperElem!.lastElementChild!;
    ev.dataTransfer!.setDragImage(itemContainerElem, itemContainerElem.getBoundingClientRect().width / 2 - 50, 0);
  }

  protected _onDragEnd(ev: DragEvent) {
    const draggables = [...(this.shadowRoot!.querySelectorAll(".item-wrapper") as any as HTMLDivElement[])];
    const buttonElem = ev.currentTarget as HTMLButtonElement;
    const itemWrapperElem = buttonElem!.parentElement!.parentElement as HTMLDivElement;
    itemWrapperElem.classList.remove("dragging");
    const index = Number(itemWrapperElem.getAttribute("data-index"));
    const afterIndex = Math.max(
      0,
      (itemWrapperElem.getAttribute("data-after-index") !== null
        ? Number(itemWrapperElem.getAttribute("data-after-index"))
        : draggables.length) - 1
    );

    draggables.forEach((draggable) => draggable.classList.remove("indicator-before", "indicator-after"));

    if (index === afterIndex) {
      return;
    }

    this.moveItem(index, afterIndex);
  }

  protected _onDragOver(ev: DragEvent) {
    const dragging = this.shadowRoot!.querySelector(".dragging") as HTMLDivElement;

    if (!dragging) {
      return;
    }

    ev.preventDefault();

    const draggables = [
      ...(this.shadowRoot!.querySelectorAll(".item-wrapper:not(.dragging)") as any as HTMLDivElement[]),
    ];
    const initial = { offset: Number.NEGATIVE_INFINITY, element: null } as {
      offset: number;
      element: HTMLDivElement | null;
    };

    const afterItem = draggables.reduce((closest, child) => {
      const box = child.getBoundingClientRect();
      const offset = ev.clientY - box.top - box.height / 2;
      if (offset < 0 && offset > closest.offset) {
        return { offset, element: child };
      } else {
        return closest;
      }
    }, initial).element;

    draggables.forEach((draggable) => draggable.classList.remove("indicator-before", "indicator-after"));

    if (afterItem === null) {
      draggables[draggables.length - 1].classList.add("indicator-after");
      dragging.removeAttribute("data-after-index");
    } else {
      afterItem.classList.add("indicator-before");
      const afterIndex = afterItem.getAttribute("data-index");
      dragging.setAttribute("data-after-index", afterIndex!);
    }
  }

  protected _showJson(ev: Event) {
    ev.stopPropagation();

    showJsonEditor(this.shadowRoot!, this.title || this.schema.title || "", this.data, (newValue) => {
      this.handleChange(this.path || "", newValue);
    });
  }

  protected doAddItem() {
    if (!this.resolvedSchema) {
      return;
    }

    if (this.itemInfos) {
      this.showAddDialog();
    } else {
      this.addItem(createDefaultValue(this.resolvedSchema, this.rootSchema));
    }
  }

  protected showAddDialog() {
    let selectedItemInfo: CombinatorInfo | undefined;
    let dialog: OrVaadinDialog | undefined;
    const descRef: Ref<HTMLDivElement> = createRef();
    const addBtnRef: Ref<OrVaadinButton> = createRef();

    const listItems: ListItem[] = this.itemInfos!.map((itemInfo) => {
      const labelStr = itemInfo.title ? computeLabel(itemInfo.title, false, true) : "";
      return {
        text: labelStr,
        value: labelStr,
        data: itemInfo,
      };
    }).sort((a, b) => a.text!.localeCompare(b.text!));

    const onParamChanged = (itemInfo: CombinatorInfo) => {
      selectedItemInfo = itemInfo;
      descRef.value!.innerHTML = itemInfo.description || "";
      addBtnRef.value!.disabled = false;
    };

    const onAdd = () => {
      if (selectedItemInfo) {
        this.addItem(selectedItemInfo.defaultValueCreator());
      }
      dialog?.close();
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="800px">
          <h2 slot="header-content">
            ${(this.label ? computeLabel(this.label, this.required, false) + " - " : "") + i18next.t("addItem")}
          </h2>
          <div id="dialog-content">
            <or-vaadin-list-box
              id="type-list"
              @selected-changed="${(ev: CustomEvent) => {
                const selected = listItems[ev.detail.value as number];
                if (selected) onParamChanged(selected.data as CombinatorInfo);
              }}"
            >
              ${listItems.map((item) => html`<or-vaadin-item>${item.text}</or-vaadin-item>`)}
            </or-vaadin-list-box>
            <div id="parameter-desc" ${ref(descRef)}></div>
          </div>
          <div id="dialog-footer" slot="footer">
            <or-vaadin-button theme="tertiary" @click="${() => dialog?.close()}">
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button ${ref(addBtnRef)} theme="primary" disabled @click="${() => onAdd()}">
              <or-translate value="add"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }
}
