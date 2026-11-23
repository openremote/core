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
  type ControlElement,
  createDefaultValue,
  getSchema,
  type GroupLayout,
  isControl,
  type JsonSchema,
  mapStateToControlProps,
  type OwnPropsOfControl,
  type OwnPropsOfRenderer,
  Paths,
  type RendererProps,
  type StatePropsOfControl,
  type VerticalLayout,
} from "@jsonforms/core";
import { css, html, PropertyValues, render, type TemplateResult } from "lit";
import { customElement, property } from "lit/decorators.js";
import { LayoutBaseElement } from "./layout-base-element";
import {
  type CombinatorInfo,
  controlWithoutLabel,
  getLabel,
  getSchemaPicker,
  getTemplateFromProps,
  showJsonEditor,
} from "../util";
import type { OrVaadinTextField } from "@openremote/or-vaadin-components/or-vaadin-text-field";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import type { ListItem } from "@openremote/or-vaadin-components/or-vaadin-list-box";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-text-field";
import "@openremote/or-vaadin-components/or-vaadin-button";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-list-box";
import "@openremote/or-vaadin-components/or-vaadin-item";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-components/or-collapsible-panel";
import { addItemOrParameterDialogStyle, baseStyle, panelStyle } from "../styles";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import { ifDefined } from "lit/directives/if-defined.js";
import { when } from "lit/directives/when.js";
import type { AdditionalProps } from "../base-element";

// language=CSS
const style = css`
  #dynamic-wrapper {
    display: table;
  }

  #dynamic-wrapper .row {
    display: table-row;
  }

  #dynamic-wrapper .row:hover .button-clear {
    visibility: visible;
  }

  #dynamic-wrapper .row > div {
    display: table-cell;
  }

  .value-container {
    padding: 0 0 20px 10px;
  }

  .key-container {
    padding: 0 10px 20px 0;
  }

  .value-container,
  .key-container {
    vertical-align: top;
  }

  .value-container > .item-container {
    margin: 0;
  }

  .value-container > .item-container > .delete-container {
    display: none;
  }

  .value-container > .item-container :first-child {
    border: 0;
    padding: 0;
    margin: 0;
    flex: 1;
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

function isDynamic(schema: JsonSchema): boolean {
  return (
    schema.allOf === undefined &&
    schema.anyOf === undefined &&
    (schema.properties === undefined || Object.keys(schema.properties).length === 0)
  );
}

@customElement("or-json-forms-vertical-layout")
export class LayoutVerticalElement extends LayoutBaseElement<VerticalLayout | GroupLayout> {
  @property()
  protected minimal?: boolean;

  @property()
  protected type?: string;

  public handleChange!: (path: string, data: any) => void;

  public static get styles() {
    return [baseStyle, panelStyle, addItemOrParameterDialogStyle, style];
  }

  render() {
    const optionalProps: StatePropsOfControl[] = [];
    const jsonFormsState = { jsonforms: { ...this.state } };
    const rootSchema = getSchema(jsonFormsState);
    const dynamic = isDynamic(this.schema);
    let dynamicPropertyRegex = ".+";
    let dynamicValueSchema: JsonSchema | undefined;

    if (dynamic) {
      if (typeof this.schema.patternProperties === "object") {
        const patternObjs = Object.entries(this.schema.patternProperties);
        if (patternObjs.length === 1) {
          dynamicPropertyRegex = patternObjs[0][0];
          dynamicValueSchema = patternObjs[0][1] as JsonSchema;
        }
      } else if (typeof this.schema.additionalProperties === "object") {
        dynamicValueSchema = this.schema.additionalProperties;
      }
    }

    const header = this.minimal
      ? ``
      : html`
          <div slot="header">
            <span>${this.label ? computeLabel(this.label, this.required, false) : ""}</span>
            ${this.type ? html`<span id="type-label" title="${this.type}">${this.type}</span>` : ``}
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

    let contentTemplate: TemplateResult | TemplateResult[] | undefined;

    if (dynamic && dynamicValueSchema) {
      contentTemplate = this._getDynamicContentTemplate(dynamicPropertyRegex, dynamicValueSchema);
    } else if (this.getChildProps().length > 0) {
      contentTemplate = this.getChildProps()
        .map((childProps: OwnPropsOfRenderer & AdditionalProps) => {
          if (isControl(childProps.uischema)) {
            const controlProps = childProps as OwnPropsOfControl;
            const stateControlProps = mapStateToControlProps(jsonFormsState, controlProps);
            stateControlProps.label =
              stateControlProps.label ||
              getLabel(this.schema, rootSchema, undefined, (childProps.uischema as ControlElement).scope) ||
              "";
            childProps.label = stateControlProps.label;
            childProps.required = !!stateControlProps.required;
            if (!stateControlProps.required && stateControlProps.data === undefined) {
              // Optional property with no data so show this in the add parameter dialog
              optionalProps.push(stateControlProps);
              return html``;
            }
          }

          return getTemplateFromProps(this.state, childProps);
        })
        .filter((t) => t !== undefined) as TemplateResult[];
    }

    const expandable =
      (!!contentTemplate && (!Array.isArray(contentTemplate) || contentTemplate.length > 0)) ||
      (!this.errors && optionalProps.length > 0);

    const content = html`
      ${header}
      <div id="content-wrapper" slot="content">
        <div id="content">${contentTemplate || ``}</div>

        ${
          this.errors || (optionalProps.length === 0 && !dynamic)
            ? ``
            : html`
                <div id="footer">
                  <or-vaadin-button
                    @click=${() => this._addParameter(rootSchema, optionalProps, dynamicPropertyRegex, dynamicValueSchema)}
                  >
                    <or-icon slot="prefix" icon="plus"></or-icon>
                    <or-translate value="addParameter"></or-translate>
                  </or-vaadin-button>
                </div>
              `
        }
      </div>
    `;

    return this.minimal
      ? html`<div>${content}</div>`
      : html`<or-collapsible-panel .expandable="${expandable}">${content}</or-collapsible-panel>`;
  }

  protected _getDynamicContentTemplate(
    dynamicPropertyRegex: string,
    dynamicValueSchema: JsonSchema
  ): TemplateResult | undefined {
    if (!this.data) {
      return undefined;
    }

    const deleteHandler = (key: string) => {
      const data = { ...this.data };
      delete data[key];
      this.handleChange(this.path, data);
    };

    const keyChangeHandler = (orInput: OrVaadinTextField, oldKey: string, newKey: string) => {
      if (!orInput.checkValidity()) {
        return;
      }

      if (this.data[newKey] !== undefined) {
        orInput.errorMessage = i18next.t("validation.keyAlreadyExists");
        return;
      } else {
        orInput.errorMessage = undefined;
      }
      const data = { ...this.data };
      const value = data[oldKey];
      delete data[oldKey];
      data[newKey] = value;
      this.handleChange(this.path, data);
    };

    const props: RendererProps & AdditionalProps = {
      renderers: this.renderers,
      uischema: controlWithoutLabel("#"),
      enabled: this.enabled,
      visible: this.visible,
      path: "",
      schema: dynamicValueSchema,
      minimal: true,
      required: false,
      label: "",
    };

    const getDynamicValueTemplate: (key: string, value: any) => TemplateResult = (key, value) => {
      props.path = Paths.compose(this.path, key);
      return getTemplateFromProps(this.state, props) || html``;
    };

    return html`
      <div id="dynamic-wrapper">
        ${Object.entries(this.data).map(([key, value]) => {
          return html`
                        <div class="row">
                            <div class="key-container">
                                <or-vaadin-text-field value=${key} required pattern="${dynamicPropertyRegex}" 
                                                      @change=${(ev: Event) => {
                                                        const elem = ev.currentTarget as OrVaadinTextField;
                                                        keyChangeHandler(elem, key, elem.value);
                                                      }}>
                                </or-vaadin-text-field>
                            </div>
                            <div class="value-container">
                                ${getDynamicValueTemplate(key, value)}
                            </div>
                            <div class="delete-container">
                                <button class="button-clear" @click="${() => deleteHandler(key)}"><or-icon icon="close-circle"></or-icon></input>
                            </div>
                        </div>
                    `;
        })}
      </div>
    `;
  }

  protected _showJson(ev: Event) {
    ev.stopPropagation();

    showJsonEditor(this.shadowRoot!, this.title || this.schema.title || "", this.data, (newValue) => {
      this.handleChange(this.path || "", newValue);
    });
  }

  protected _addParameter(
    rootSchema: JsonSchema,
    optionalProps: StatePropsOfControl[],
    dynamicPropertyRegex?: string,
    dynamicValueSchema?: JsonSchema
  ) {
    const dynamic = optionalProps.length === 0;
    let selectedParameter: StatePropsOfControl | undefined;
    let selectedOneOf: CombinatorInfo | undefined;
    let keyValue: string | undefined;
    let dialog: OrVaadinDialog | undefined;
    const descRef: Ref<HTMLDivElement> = createRef();
    const addBtnRef: Ref<OrVaadinButton> = createRef();

    const listItems: ListItem[] = optionalProps.map((props) => {
      const labelStr = computeLabel(props.label, !!props.required, false);
      return {
        text: labelStr,
        value: labelStr,
        data: props,
      };
    });

    // The description pane changes with the selection, so it is rendered on its own instead of re-opening the dialog
    const refreshDescription = () => render(getDescriptionTemplate(), descRef.value!);

    const onSchemaSelected = (selectedSchema: CombinatorInfo) => {
      selectedOneOf = selectedSchema;
      addBtnRef.value!.disabled = !selectedOneOf;
      refreshDescription();
    };

    const onParamChanged = (selected: StatePropsOfControl) => {
      selectedParameter = selected;
      selectedOneOf = undefined;
      addBtnRef.value!.disabled = !!selected.schema?.oneOf;
      refreshDescription();
    };

    const onKeyChanged = (ev: Event) => {
      const keyInput = ev.currentTarget as OrVaadinTextField;
      keyValue = keyInput.value;
      const duplicate = !!keyValue && this.data?.[keyValue] !== undefined;
      keyInput.errorMessage = duplicate ? i18next.t("validation.keyAlreadyExists") : undefined;
      const valid = keyInput.validate() && !duplicate;
      keyInput.invalid = !valid;
      addBtnRef.value!.disabled = !valid;
    };

    const onAdd = () => {
      const key = dynamic ? (keyValue as string) : selectedParameter!.path.split(".").pop()!;
      const data = { ...this.data };
      const schema = dynamic ? dynamicValueSchema! : selectedParameter!.schema;
      data[key] = Array.isArray(schema.type)
        ? null
        : (selectedOneOf ? selectedOneOf.defaultValueCreator() : undefined) || createDefaultValue(schema, rootSchema);
      this.handleChange(this.path || "", data);
      dialog?.close();
    };

    const getDescriptionTemplate: () => TemplateResult = () => {
      if (dynamic) {
        return html`
          <or-vaadin-text-field
            id="key-input"
            required
            pattern="${ifDefined(dynamicPropertyRegex)}"
            @input="${(ev: Event) => onKeyChanged(ev)}"
          >
            <or-translate slot="label" value="schema.keyInputLabel"></or-translate>
          </or-vaadin-text-field>
        `;
      }

      if (!selectedParameter) {
        return html``;
      }

      const oneOf = selectedParameter.schema?.oneOf as JsonSchema[] | undefined;

      return html`
        <or-translate id="parameter-title" value="${selectedParameter.label}"></or-translate>
        <p>${selectedParameter.description}</p>
        ${when(
          oneOf,
          () => html`
            <div id="schema-picker">
              ${getSchemaPicker(rootSchema, oneOf!, selectedParameter!.label, onSchemaSelected)}
            </div>
            <p id="schema-description">
              ${
                selectedOneOf
                  ? selectedOneOf.description || i18next.t("schema.noDescriptionAvailable")
                  : i18next.t("schema.selectTypeMessage")
              }
            </p>
          `
        )}
      `;
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="800px">
          <h2 slot="header-content">
            ${(this.label ? computeLabel(this.label, this.required, false) + " - " : "") + i18next.t("addParameter")}
          </h2>
          <div id="dialog-content" class="${dynamic ? "auto-height" : ""}">
            ${when(
              !dynamic,
              () => html`
                <or-vaadin-list-box
                  id="type-list"
                  @selected-changed="${(ev: CustomEvent) => {
                    const selected = listItems[ev.detail.value as number];
                    if (selected) onParamChanged(selected.data as StatePropsOfControl);
                  }}"
                >
                  ${listItems.map((item) => html`<or-vaadin-item>${item.text}</or-vaadin-item>`)}
                </or-vaadin-list-box>
              `
            )}
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

    refreshDescription();
  }
}
