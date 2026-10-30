/*
 * Copyright 2025, OpenRemote Inc.
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
import { css, html, type LitElement, type PropertyValues, type TemplateResult } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query } from "lit/decorators.js";
import { InputType, inputTypeIsJson, parseJson } from "./util";
import "./or-vaadin-checkbox";
import "./or-vaadin-date-picker";
import "./or-vaadin-date-time-picker";
import "./or-vaadin-email-field";
import "./or-vaadin-number-field";
import "./or-vaadin-password-field";
import "./or-vaadin-select";
import "./or-vaadin-slider";
import "./or-vaadin-text-field";
import "./or-vaadin-text-area";
import "./or-vaadin-time-picker";
import "./or-vaadin-toggle";

/**
 * Function to register properties (get/setter) of a CustomElement to the child Vaadin element.
 * When the Vaadin element does not recognize the HTML attribute or class property, it is ignored.
 * @param constructors - List of CustomElements to scan for available properties
 */
function registerProperties(constructors: (CustomElementConstructor | undefined)[]) {
  constructors
    .filter((constr) => !!constr)
    .map((contr) => contr as typeof LitElement)
    .forEach((constr) =>
      constr.observedAttributes
        .filter((attr) => !OrVaadinInput.elementProperties.has(attr))
        .forEach((attr) => OrVaadinInput.createProperty(attr, constr.getPropertyOptions(attr)))
    );
}

/**
 * Custom element that wraps various Vaadin input components based on the specified `type`.
 * Provides a unified interface for interacting with different types of inputs.
 * @customElement "or-vaadin-input"
 */
@customElement("or-vaadin-input")
export class OrVaadinInput extends OrElement {
  /**
   * Static list of Vaadin component classes to scan through during attribute changes.
   * It's important that the complete list of CustomElements is defined here.
   * Be aware: all CustomElements defined here need to be imported during initialization; dynamic imports are not expected to work.
   */
  public static readonly VAADIN_CLASSES: (CustomElementConstructor | undefined)[] = [
    customElements.get("or-vaadin-date-picker"),
    customElements.get("or-vaadin-date-time-picker"),
    customElements.get("or-vaadin-number-field"),
    customElements.get("or-vaadin-password-field"),
    customElements.get("or-vaadin-select"),
    customElements.get("or-vaadin-slider"),
    customElements.get("or-vaadin-text-area"),
    customElements.get("or-vaadin-text-field"),
    customElements.get("or-vaadin-time-picker"),
  ];

  /**
   * Static map of template functions that are used to render the Vaadin components.
   * For example, {@link InputType.CHECKBOX} generates a `<or-vaadin-checkbox>` element.
   * The map value contains a function, with an {@link onChange} callback parameter for handling events.
   */
  public static readonly TEMPLATES = new Map<InputType, (onChange: (ev: Event) => void) => TemplateResult>([
    [InputType.BIG_INT, OrVaadinInput.getNumberFieldTemplate],
    [InputType.CHECKBOX, OrVaadinInput.getCheckboxTemplate],
    [InputType.DATE, OrVaadinInput.getDatePickerTemplate],
    [InputType.DATETIME, OrVaadinInput.getDateTimePickerTemplate],
    [InputType.EMAIL, OrVaadinInput.getEmailFieldTemplate],
    [InputType.JSON, OrVaadinInput.getJsonTemplate],
    [InputType.JSON_OBJECT, OrVaadinInput.getJsonTemplate],
    [InputType.NUMBER, OrVaadinInput.getNumberFieldTemplate],
    [InputType.PASSWORD, OrVaadinInput.getPasswordFieldTemplate],
    [InputType.RANGE, OrVaadinInput.getSliderTemplate],
    [InputType.SELECT, OrVaadinInput.getSelectTemplate],
    [InputType.SWITCH, OrVaadinInput.getSwitchTemplate],
    [InputType.TELEPHONE, OrVaadinInput.getTextFieldTemplate],
    [InputType.TEXT, OrVaadinInput.getTextFieldTemplate],
    [InputType.TEXTAREA, OrVaadinInput.getTextAreaTemplate],
    [InputType.TIME, OrVaadinInput.getTimePickerTemplate],
  ]);

  /**
   * Static map of what HTML event to listen for when a value changes. By default, or when undefined, the "change" event is used.
   * Sometimes you'd like to override this, such as a text field, where it should only be updated "on submit".
   */
  public static readonly CHANGE_EVENTS = new Map<InputType, string>([
    [InputType.BIG_INT, "submit"],
    [InputType.EMAIL, "submit"],
    [InputType.JSON, "submit"],
    [InputType.JSON_OBJECT, "submit"],
    [InputType.NUMBER, "submit"],
    [InputType.TELEPHONE, "submit"],
    [InputType.TEXTAREA, "submit"],
    [InputType.TEXT, "submit"],
    [InputType.PASSWORD, "submit"],
  ]);

  /**
   * List of forbidden attributes that are not processed nor "bubbled down" to the child Vaadin component.
   * So `<or-vaadin-input id="myId" type="text">`, wouldn't render `<or-vaadin-text-field id="myId">`, but `<or-vaadin-text-field>`.
   */
  public static readonly FORBIDDEN_ATTRIBUTES = ["id"];

  /**
   * The input type that determines which Vaadin component to render.
   * Refer to {@link InputType} and {@link OrVaadinInput.TEMPLATES} for the supported types.
   */
  @property({ type: String })
  public type: InputType = InputType.TEXT;

  @query("#elem")
  protected _elem?: HTMLInputElement;

  /**
   * Internal observer for tracking attribute updates on the root element.
   * @protected
   */
  protected _observer = new MutationObserver((mutations, observer) => this._onAttributeChange(mutations, observer));

  static get styles() {
    return css`
      #elem {
        width: 100%;
      }
      or-vaadin-textarea {
        max-height: 200px;
      }
    `;
  }

  connectedCallback() {
    this._observer.observe(this, { attributes: true });
    return super.connectedCallback();
  }

  disconnectedCallback() {
    this._observer.disconnect();
    return super.disconnectedCallback();
  }

  updated(changedProps: PropertyValues) {
    // A new type renders a different Vaadin element, which starts out without any of the attributes
    if (changedProps.has("type")) {
      this._applyAttributes();
    }
    changedProps.forEach((_, key) => this._onPropertyChange(String(key), this[String(key) as keyof OrVaadinInput]));
    return super.updated(changedProps);
  }

  firstUpdated(_changedProps: PropertyValues) {
    this._applyAttributes();
    return super.firstUpdated(_changedProps);
  }

  /**
   * Returns the native Vaadin input element.
   */
  public get native(): HTMLInputElement | undefined {
    return this._elem;
  }

  /**
   * Returns the value of the native Vaadin input element.
   */
  public get nativeValue(): any {
    switch (this.type) {
      case InputType.CHECKBOX:
      case InputType.SWITCH: {
        return (this._elem as HTMLInputElement | undefined)?.checked;
      }
      case InputType.JSON:
      case InputType.JSON_OBJECT: {
        return parseJson(this.type, this._elem?.value).value;
      }
      default: {
        return this._elem?.value;
      }
    }
  }

  /**
   * Function that returns a boolean which indicates if the element meets any constraint validation rules applied to it.
   * @see {@link https://developer.mozilla.org/en-US/docs/Web/API/HTMLInputElement/checkValidity|HTMLInputElement/checkValidity}
   */
  public checkValidity(): boolean {
    if (!(this._elem?.checkValidity() ?? false)) {
      return false;
    }
    // A text area accepts any text, so JSON types need their own parse check on top of the field constraints
    if (inputTypeIsJson(this.type)) {
      return parseJson(this.type, this._elem?.value).valid;
    }
    return true;
  }

  render() {
    return OrVaadinInput.TEMPLATES.get(this.type)?.(this._onValueChange) ?? html`Not supported yet.`;
  }

  /**
   * Internal {@link MutationCallback} function for handling attribute changes from an {@link MutationObserver}.
   * @param mutations - Array of mutations occurring in a single event
   * @param _observer - The observer causing the {@link MutationCallback}
   * @protected
   */
  protected _onAttributeChange(mutations: MutationRecord[], _observer: MutationObserver) {
    this.getUpdateComplete().then(() => {
      mutations
        .filter((mutation) => mutation.type === "attributes" && mutation.attributeName)
        .forEach((mutation) => {
          // console.debug(this._getLoggingPrefix() + `_onAttributeChange for ${mutation.attributeName} (${typeof this.getAttribute(mutation.attributeName!)})`, this.getAttribute(mutation.attributeName!));
          this._applyAttribute(mutation.attributeName!, this.getAttribute(mutation.attributeName!), this._elem);
        });
    });
  }

  /**
   * Internal function for handling property changes during the Lit lifecycle.
   * @param name - Property key that was changed
   * @param newValue - (optional) the new value to apply
   * @protected
   */
  protected _onPropertyChange(name: string, newValue?: any) {
    // console.debug(this._getLoggingPrefix() + `_onPropertyChange for ${name} (${typeof newValue}) to`, newValue);
    newValue ??= (this as Record<string, unknown>)[String(name)];
    this._applyAttribute(name, JSON.stringify(newValue), this._elem);
  }

  /**
   * Internal callback function when a user changes the value inside the Vaadin element.
   * Normally this is caused by an `@change` event.
   * @param ev Event that occurred
   * @protected
   */
  protected _onValueChange(ev: Event) {
    ev.stopPropagation();
    if (ev.defaultPrevented) return;
    this.dispatchEvent(new CustomEvent("change", { bubbles: true }));
  }

  /**
   * Internal function to apply every attribute of the root element to the child Vaadin element.
   * @protected
   */
  protected _applyAttributes(elem = this._elem) {
    for (const name of this.getAttributeNames()) {
      // console.debug(this._getLoggingPrefix() + `applying ${name} (${typeof this.getAttribute(name)}) to`, this.getAttribute(name));
      this._applyAttribute(name, this.getAttribute(name), elem);
    }
  }

  /**
   * Internal function to apply an attribute to the child Vaadin element.
   * @param name - Attribute name
   * @param value - Value to apply
   * @param elem - Optional {@link HTMLInputElement} to apply the change to
   * @protected
   */
  protected _applyAttribute(name: string, value?: any, elem = this._elem) {
    if (elem && !OrVaadinInput.FORBIDDEN_ATTRIBUTES.includes(name)) {
      switch (typeof value) {
        case "boolean": {
          elem?.toggleAttribute(name, value);
          break;
        }
        default: {
          if (value !== null) {
            elem?.setAttribute(name, value);
          } else {
            elem?.removeAttribute(name);
          }
          break;
        }
      }
    }
  }

  public static getCheckboxTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-checkbox id="elem" @change=${onChange}></or-vaadin-checkbox>`;
  }

  public static getDatePickerTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-date-picker id="elem" @change=${onChange}></or-vaadin-date-picker>`;
  }

  public static getDateTimePickerTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-date-time-picker id="elem" @change=${onChange}></or-vaadin-date-time-picker>`;
  }

  public static getEmailFieldTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-email-field id="elem" @change=${onChange}></or-vaadin-email-field>`;
  }

  /**
   * Template for the JSON types, whose value a text area holds as text. No field constraint rejects malformed JSON,
   * so the parse result sets the invalid state before the change is passed on.
   */
  public static getJsonTemplate(onChange?: (e: Event) => void) {
    // Lit calls an event listener with the element that rendered the template as `this`
    function onJsonChange(this: OrVaadinInput, ev: Event) {
      const textArea = this.native as (HTMLInputElement & { invalid: boolean }) | undefined;
      if (textArea) {
        textArea.invalid = !this.checkValidity();
      }
      onChange?.call(this, ev);
    }

    return html`<or-vaadin-text-area id="elem" @change=${onJsonChange}></or-vaadin-text-area>`;
  }

  public static getNumberFieldTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-number-field id="elem" @change=${onChange}></or-vaadin-number-field>`;
  }

  public static getPasswordFieldTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-password-field id="elem" @change=${onChange}></or-vaadin-password-field>`;
  }

  public static getSelectTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-select id="elem" @change=${onChange}></or-vaadin-select>`;
  }

  public static getSliderTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-slider id="elem" @change=${onChange}></or-vaadin-slider>`;
  }

  public static getSwitchTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-toggle id="elem" @change=${onChange}></or-vaadin-toggle>`;
  }

  public static getTextAreaTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-text-area id="elem" @change=${onChange}></or-vaadin-text-area>`;
  }

  public static getTextFieldTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-text-field id="elem" @change=${onChange}></or-vaadin-text-field>`;
  }

  public static getTimePickerTemplate(onChange?: (e: Event) => void) {
    return html`<or-vaadin-time-picker id="elem" @change=${onChange}></or-vaadin-time-picker>`;
  }

  protected _getLoggingPrefix(): string {
    return `[${this.getAttribute("label")} ${this.type}] `;
  }
}

// Before the class is initialized, register the property fields of the underlying Vaadin classes
registerProperties(OrVaadinInput.VAADIN_CLASSES);
