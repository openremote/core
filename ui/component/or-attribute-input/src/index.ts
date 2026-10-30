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
import { customElement, property } from "lit/decorators.js";
import { ifDefined } from "lit/directives/if-defined.js";
import { until } from "lit/directives/until.js";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import { when } from "lit/directives/when.js";
import { i18next, translate } from "@openremote/or-translate";
import {
  type Attribute,
  type AttributeDescriptor,
  type AttributeEvent,
  type AttributeRef,
  type SharedEvent,
  type ValueDescriptor,
  WellknownMetaItems,
  WellknownValueTypes,
  AssetModelUtil,
  ClientRole,
  type ValueConstraintAllowedValues,
} from "@openremote/model";
import manager, { subscribe, Util } from "@openremote/core";
import "@openremote/or-icon";
import "@openremote/or-mwc-components/or-mwc-input";
import "@openremote/or-vaadin-components/or-vaadin-button";
import "@openremote/or-vaadin-components/or-vaadin-input";
import { progressCircular } from "@openremote/or-mwc-components/style";
import "@openremote/or-components/or-loading-wrapper";
import type { OrLoadingWrapper } from "@openremote/or-components/or-loading-wrapper";
import {
  getValueHolderInputTemplateProvider,
  type ValueInputProvider,
  type ValueInputProviderGenerator,
  type ValueInputProviderOptions,
  type ValueInputTemplateFunction,
} from "@openremote/or-vaadin-components/value-input-provider";
import {
  type InputType,
  SUPPORTED_WELLKNOWN_VALUE_TYPES,
  type SupportedWellknownValueTypes,
} from "@openremote/or-vaadin-components/util";
import "@openremote/or-map";
import { geoJsonPointInputTemplateProvider } from "@openremote/or-map";
import "@openremote/or-json-forms";
import { type ErrorObject, type OrJSONForms, StandardRenderers } from "@openremote/or-json-forms";
import type { OrInputChangedEventDetail } from "@openremote/or-mwc-components/or-mwc-input";
import { agentIdRendererRegistryEntry } from "./renderers/agent-link";
import { schedulerRendererRegistryEntry } from "./renderers/scheduler";
import { replayDataRendererRegistryEntry } from "./renderers/replay-data";

export { getAgentOptions, loadAgents } from "./renderers/agent-link";

export class OrAttributeInputChangedEvent extends CustomEvent<OrAttributeInputChangedEventDetail> {
  public static readonly NAME = "or-attribute-input-changed";

  constructor(value?: any, previousValue?: any) {
    super(OrAttributeInputChangedEvent.NAME, {
      detail: {
        value,
        previousValue,
      },
      bubbles: true,
      composed: true,
    });
  }
}

export interface OrAttributeInputChangedEventDetail {
  value?: any;
  previousValue?: any;
}

declare global {
  export interface HTMLElementEventMap {
    [OrAttributeInputChangedEvent.NAME]: OrAttributeInputChangedEvent;
  }
}

export function getAttributeInputWrapper(
  content: TemplateResult,
  value: any,
  loading: boolean,
  disabled: boolean,
  helperText: string | undefined,
  label: string | undefined,
  buttonIcon?: string,
  sendValue?: () => void,
  fullWidth?: boolean
): TemplateResult {
  if (helperText) {
    content = html`
      <div id="wrapper-helper">
        ${label ? html`<div id="wrapper-label">${label}</div>` : ``}
        <div id="wrapper-input">${content}</div>
        <div id="helper-text">${helperText}</div>
      </div>
    `;
  }

  if (buttonIcon) {
    content = html`
      ${content}
      <or-vaadin-button
        id="send-btn"
        theme="icon"
        ?disabled=${disabled || loading}
        @click=${(ev: Event) => {
          ev.stopPropagation();
          sendValue?.();
        }}
      >
        <or-icon icon=${buttonIcon}></or-icon>
      </or-vaadin-button>
    `;
  }

  return html`
    <div id="wrapper" style="width: 100%" class="${buttonIcon || fullWidth ? "no-padding" : "right-padding"}">
      ${content}
      <div id="scrim" class="${ifDefined(loading ? undefined : "hidden")}">
        <progress class="pure-material-progress-circular"></progress>
      </div>
    </div>
  `;
}

export function getHelperText(sending: boolean, error: boolean, timestamp: number | undefined): string | undefined {
  if (sending) {
    return i18next.t("sending");
  }

  if (error) {
    return i18next.t("sendFailed");
  }

  if (!timestamp) {
    return;
  }

  return i18next.t("updatedWithDate", { date: new Date(timestamp) });
}

export const jsonFormsAttributeRenderers = [
  ...StandardRenderers,
  agentIdRendererRegistryEntry,
  schedulerRendererRegistryEntry,
  replayDataRendererRegistryEntry,
];

const schemas = new Map<string, unknown>();
const inflightRequests = new Map<string, Promise<unknown>>();

async function getSchema(valueDescriptor: ValueDescriptor) {
  const descriptor = valueDescriptor.name + "[]".repeat(valueDescriptor.arrayDimensions ?? 0);

  if (schemas.has(descriptor)) {
    return schemas.get(descriptor);
  }

  // Coalesce inflight requests
  // Firefox won't guarantee subsequent requests provide the
  // "If-None-Match" request header.
  if (inflightRequests.has(descriptor)) {
    return inflightRequests.get(descriptor);
  }

  const promise = manager.rest.api.AssetModelResource.getValueDescriptorSchema({ name: descriptor })
    .then((response) => {
      const schema = response.data;
      schemas.set(descriptor, schema);
      return schema;
    })
    .finally(() => inflightRequests.delete(descriptor));

  inflightRequests.set(descriptor, promise);
  return promise;
}

export const jsonFormsInputTemplateProvider: (
  fallback: ValueInputProvider,
  clear?: boolean
) => ValueInputProviderGenerator =
  (fallback, clear) =>
  (assetDescriptor, valueHolder, valueHolderDescriptor, valueDescriptor, valueChangeNotifier, options) => {
    if (
      Util.isComplexValueDescriptor(valueDescriptor) &&
      !(
        SUPPORTED_WELLKNOWN_VALUE_TYPES.includes(valueDescriptor.name as SupportedWellknownValueTypes) &&
        !valueDescriptor?.arrayDimensions
      )
    ) {
      const disabled = !!(options && options.disabled);
      const readonly = !!(options && options.readonly);
      const label = options.label;

      // Apply a custom UI schema to remove the outer VerticalLayout
      const uiSchema: any = { type: "Control", scope: "#" };
      let schema: any;
      const jsonForms: Ref<OrJSONForms> = createRef();
      const loadingWrapper: Ref<OrLoadingWrapper> = createRef();

      let initialised = false;
      let prevValue: any;

      const onChanged = (dataAndErrors: { errors: ErrorObject[] | undefined; data: any }) => {
        if (!initialised) {
          return;
        }

        if (dataAndErrors.data !== prevValue) {
          // Reference check first; the deep compare only runs when json-forms hands
          // out a new object, so unchanged content does not notify a change.
          const changed = !Util.objectsEqual(dataAndErrors.data, prevValue);
          prevValue = dataAndErrors.data;
          if (changed) {
            const errors = !!dataAndErrors.errors?.length;
            valueChangeNotifier({ value: dataAndErrors.data, errors });
          }
        }
      };

      const doLoad = async (data: any) => {
        if (!initialised) {
          prevValue = data;
        }
        initialised = true;

        schema = await getSchema(valueDescriptor);

        if (jsonForms.value && loadingWrapper.value) {
          const forms = jsonForms.value;
          forms.schema = schema;
          forms.data = data;
          loadingWrapper.value.loading = false;
        }
      };

      const templateFunction: ValueInputTemplateFunction = (
        value,
        _focused,
        _loading,
        _sending,
        _error,
        _helperText
      ) => {
        // Schedule loading
        window.setTimeout(() => doLoad(value), 0);

        return html`
          <style>
            .disabled {
              opacity: 0.5;
              pointer-events: none;
            }
            or-loading-wrapper {
              width: 100%;
            }
            or-json-forms {
              max-width: 100%;
              min-width: 0; /* Allows the element to shrink */
            }
            #clear {
              width: unset !important;
            }
          </style>
          <or-loading-wrapper ${ref(loadingWrapper)} .loading="${true}">
            <div style="display: flex; width: 100%">
              <or-json-forms
                .renderers="${jsonFormsAttributeRenderers}"
                ${ref(jsonForms)}
                .disabled="${disabled}"
                .readonly="${readonly}"
                .label="${label}"
                .schema="${schema}"
                .uischema="${uiSchema}"
                .onChange="${onChanged}"
              >
              </or-json-forms>
              ${when(
                clear,
                () => html`
                  <or-vaadin-button id="clear" theme="icon" @click="${() => valueChangeNotifier({ value: null })}">
                    <or-icon icon="backspace"></or-icon>
                  </or-vaadin-button>
                `
              )}
            </div>
          </or-loading-wrapper>
        `;
      };

      return {
        templateFunction,
        supportsHelperText: false,
        supportsLabel: false,
        supportsSendButton: false,
        validator: () => {
          if (!jsonForms.value) {
            return false;
          }
          return jsonForms.value.checkValidity();
        },
      };
    }

    return fallback;
  };

const DEFAULT_TIMEOUT = 5000;

// TODO: Add support for attribute not found and attribute deletion/addition
@customElement("or-attribute-input")
export class OrAttributeInput extends subscribe(manager)(translate(i18next)(OrElement)) {
  // language=CSS
  static get styles() {
    return [
      progressCircular,
      css`
        :host {
          display: inline-block;
        }

        :host(.force-btn-padding) #wrapper.no-padding {
          /*padding-right: 52px;*/
        }

        #wrapper or-mwc-input,
        #wrapper or-vaadin-input,
        #wrapper or-map {
          width: 100%;
        }

        #wrapper or-map {
          min-height: 250px;
        }

        #wrapper .long-press-msg {
          display: none;
        }

        #wrapper {
          display: flex;
          position: relative;
          align-items: center;
        }

        #wrapper.right-padding {
          padding-right: 52px;
        }

        #wrapper-helper {
          display: flex;
          flex: 1;
          flex-direction: column;
          max-width: 100%;
        }

        #wrapper-input {
          flex: 1;
          display: flex;
        }

        #wrapper-label,
        #helper-text {
          margin-left: 16px;
        }

        /* Copy of mdc text field helper text styles */
        #helper-text {
          min-width: 255px;
          color: rgba(0, 0, 0, 0.6);
          font-family: Roboto, sans-serif;
          -webkit-font-smoothing: antialiased;
          font-size: 0.75rem;
          font-weight: 400;
          letter-spacing: 0.0333333em;
        }

        #scrim {
          position: absolute;
          left: 0;
          top: 0;
          right: 0;
          bottom: 0;
          background: white;
          opacity: 0.2;
          display: flex;
          align-items: center;
          justify-content: center;
        }

        #scrim.hidden {
          display: none;
        }

        #send-btn {
          flex: 0;
          margin-left: 4px;
          --or-icon-width: 20px;
        }
      `,
    ];
  }

  @property({ type: Object, reflect: false })
  public attribute?: Attribute<any>;

  @property({ type: String })
  public assetId?: string;

  @property({ type: Object })
  public attributeDescriptor?: AttributeDescriptor;

  @property({ type: Object })
  public valueDescriptor?: ValueDescriptor;

  @property({ type: String })
  public assetType?: string;

  @property({ type: String })
  public label?: string;

  @property({ type: Boolean })
  public disabled?: boolean;

  @property({ type: Boolean })
  public readonly?: boolean;

  @property({ type: Boolean, attribute: true })
  public required?: boolean;

  @property()
  public value?: any;

  @property()
  public inputType?: InputType;

  @property({ type: Boolean })
  public hasHelperText?: boolean;

  @property({ type: Boolean })
  public disableButton?: boolean;

  @property({ type: Boolean, attribute: true })
  public disableSubscribe: boolean = false;

  @property({ type: Boolean })
  public disableWrite: boolean = false;

  @property({ type: Boolean })
  public compact: boolean = false;

  @property({ type: Boolean })
  public comfortable: boolean = false;

  @property({ type: Boolean })
  public resizeVertical: boolean = false;

  @property({ type: Boolean })
  public fullWidth?: boolean;

  @property({ type: Boolean })
  public rounded?: boolean;

  @property({ type: Boolean })
  public outlined?: boolean;

  @property()
  protected _attributeEvent?: AttributeEvent;

  @property()
  protected _writeTimeoutHandler?: number;

  public customProvider?: ValueInputProviderGenerator;
  public writeTimeout?: number = DEFAULT_TIMEOUT;
  protected _requestFocus = false;
  protected _newValue: any;
  protected _templateProvider?: ValueInputProvider;
  protected _sendError = false;
  protected _attributeDescriptor?: AttributeDescriptor;
  protected _valueDescriptor?: ValueDescriptor;

  public disconnectedCallback() {
    super.disconnectedCallback();
    this._clearWriteTimeout();
  }

  langChangedCallback = () => {
    this._updateTemplate();
    this.requestUpdate();
  };

  public shouldUpdate(_changedProperties: PropertyValues): boolean {
    const shouldUpdate = super.shouldUpdate(_changedProperties);

    let updateSubscribedRefs = false;
    let updateDescriptors = false;

    if (_changedProperties.has("disableSubscribe")) {
      updateSubscribedRefs = true;
    }

    if (
      _changedProperties.has("attributeDescriptor") ||
      _changedProperties.has("valueDescriptor") ||
      _changedProperties.has("assetType")
    ) {
      updateDescriptors = true;
    }

    if (_changedProperties.has("attribute")) {
      const oldAttr = _changedProperties.get("attribute") as Attribute<any>;
      const attr = this.attribute;

      if (oldAttr && attr) {
        const oldValue = oldAttr.value;
        const oldTimestamp = oldAttr.timestamp;

        // Structural check: name, type, and meta reference must all match.
        // Live value updates arrive as shallow copies of the attribute ({...attr}),
        // so meta keeps the same reference and this check is O(1).
        // A full asset reload produces a new meta reference, which is sufficient
        // to detect a structural change without deep-comparing large meta values.
        const structurallyEqual =
          oldAttr.name === attr.name && oldAttr.type === attr.type && oldAttr.meta === attr.meta;
        if (structurallyEqual) {
          // Compare value and timestamp
          if (oldValue !== attr.value || oldTimestamp !== attr.timestamp) {
            this._onAttributeValueChanged(oldValue, attr.value, attr.timestamp);
          } else if (_changedProperties.size === 1) {
            // Only the attribute has 'changed' and we've handled it so don't perform update
            return false;
          }
        } else {
          updateSubscribedRefs = true;
          updateDescriptors = true;
        }
      }
    }

    if (_changedProperties.has("assetId") && _changedProperties.get("assetId") !== this.assetId) {
      updateSubscribedRefs = true;
      updateDescriptors = true;
    }

    if (updateDescriptors) {
      this._updateDescriptors();
    }

    if (updateSubscribedRefs) {
      this._updateSubscribedRefs();
    }

    if (
      this._templateProvider &&
      (_changedProperties.has("disabled") ||
        _changedProperties.has("readonly") ||
        _changedProperties.has("required") ||
        _changedProperties.has("label"))
    ) {
      this._updateTemplate();
    }

    return shouldUpdate;
  }

  protected _updateSubscribedRefs(): void {
    this._attributeEvent = undefined;

    if (this.disableSubscribe) {
      this.attributeRefs = undefined;
    } else {
      const attributeRef = this._getAttributeRef();
      this.attributeRefs = attributeRef ? [attributeRef] : undefined;
    }
  }

  protected _getAttributeRef(): AttributeRef | undefined {
    if (this.assetId && this.attribute) {
      return { name: this.attribute.name, id: this.assetId };
    }
  }

  protected _updateDescriptors(): void {
    this._valueDescriptor = undefined;
    this._attributeDescriptor = undefined;

    if (this.attributeDescriptor && this.valueDescriptor) {
      this._attributeDescriptor = this.attributeDescriptor;
      this._valueDescriptor = this.valueDescriptor;
    } else {
      const attributeOrDescriptorOrName =
        this.attributeDescriptor || (this.attribute ? this.attribute.name : undefined);

      if (!attributeOrDescriptorOrName) {
        this._attributeDescriptor = this.attributeDescriptor;
        this._valueDescriptor = this.valueDescriptor;
      } else {
        const attributeAndValueDescriptors = AssetModelUtil.getAttributeAndValueDescriptors(
          this.assetType,
          attributeOrDescriptorOrName,
          this.attribute
        );
        this._attributeDescriptor = attributeAndValueDescriptors[0];
        this._valueDescriptor = this.valueDescriptor ? this._valueDescriptor : attributeAndValueDescriptors[1];
      }
    }

    // Sort asset type options in alphabetical order
    if (this._valueDescriptor && this._valueDescriptor.name == WellknownValueTypes.ASSETTYPE) {
      const allowedValuesConstraint = this._valueDescriptor.constraints?.find(
        (constraint): constraint is ValueConstraintAllowedValues =>
          (constraint as ValueConstraintAllowedValues).type === "allowedValues"
      );

      if (allowedValuesConstraint && allowedValuesConstraint.allowedValues) {
        allowedValuesConstraint.allowedValues.sort((a, b) => a.localeCompare(b));
      }
    }

    this._updateTemplate();
  }

  protected _updateTemplate(): void {
    this._templateProvider = undefined;

    if (!this.assetType) {
      return;
    }

    const valueDescriptor = AssetModelUtil.resolveValueDescriptor(
      this.attribute,
      this._valueDescriptor || this._attributeDescriptor
    );

    if (!valueDescriptor) {
      return;
    }

    const options: ValueInputProviderOptions = {
      readonly: this.isReadonly(),
      required: this.required,
      disabled: this.disabled,
      compact: this.compact,
      rounded: this.rounded,
      outlined: this.outlined,
      label: this.getLabel(),
      comfortable: this.comfortable,
      resizeVertical: this.resizeVertical,
      minRows: 5,
      manualresize: true,
      inputType: this.inputType,
    };

    // Use json forms with fallback to simple input provider
    const valueChangeHandler = (detail: (OrInputChangedEventDetail & { errors?: boolean }) | undefined) => {
      if (detail?.errors) return;
      const value = detail ? detail.value : undefined;
      const updateImmediately =
        (detail && detail.enterPressed) ||
        !this._templateProvider ||
        !this.showButton ||
        !this._templateProvider.supportsSendButton;
      this._onInputValueChanged(value, updateImmediately);
    };

    if (this.customProvider) {
      this._templateProvider = this.customProvider
        ? this.customProvider(
            this.assetType,
            this.attribute,
            this._attributeDescriptor,
            valueDescriptor,
            (detail) => valueChangeHandler(detail),
            options
          )
        : undefined;
      return;
    }

    // Handle special value types
    if (valueDescriptor.name === WellknownValueTypes.GEOJSONPOINT) {
      this._templateProvider = geoJsonPointInputTemplateProvider(
        this.assetType,
        this.attribute,
        this._attributeDescriptor,
        valueDescriptor,
        (detail) => valueChangeHandler(detail),
        options
      );
      return;
    }

    const standardInputProvider = getValueHolderInputTemplateProvider(
      this.assetType,
      this.attribute,
      this._attributeDescriptor,
      valueDescriptor,
      (value, updateImmediately) => valueChangeHandler({ value, enterPressed: !!updateImmediately }),
      options
    );
    this._templateProvider = jsonFormsInputTemplateProvider(standardInputProvider, true)(
      this.assetType,
      this.attribute,
      this._attributeDescriptor,
      valueDescriptor,
      (detail) => valueChangeHandler(detail),
      options
    );

    if (!this._templateProvider) {
      this._templateProvider = standardInputProvider;
    }
  }

  public getLabel(): string | undefined {
    let label;

    if (this.label) {
      label = this.label;
    } else if (this.label !== "" && this.label !== null) {
      const descriptors = AssetModelUtil.getAttributeAndValueDescriptors(
        this.assetType,
        this.attribute ? this.attribute!.name : undefined,
        this._attributeDescriptor
      );
      label = Util.getAttributeLabel(this.attribute, descriptors[0], this.assetType, true);
    }

    return label;
  }

  public isReadonly(): boolean {
    if (!manager.hasRole(ClientRole.WRITE_ATTRIBUTES)) {
      this.readonly = !manager.hasRole(ClientRole.WRITE_ATTRIBUTES);
      return this.readonly;
    }

    return this.readonly !== undefined
      ? this.readonly
      : Util.getMetaValue(WellknownMetaItems.READONLY, this.attribute, this._attributeDescriptor);
  }

  public render() {
    if (!this.assetType || !this._templateProvider) {
      return html``;
    }

    // Check if attribute hasn't been loaded yet or pending write
    const loading = (this.attributeRefs && !this._attributeEvent) || !!this._writeTimeoutHandler;
    let content: TemplateResult;

    const value = this.getValue();
    const focus = this._requestFocus;
    this._requestFocus = false;
    const helperText = this.hasHelperText
      ? getHelperText(!!this._writeTimeoutHandler, this._sendError, this.getTimestamp())
      : undefined;
    const buttonIcon = !this.showButton
      ? this.disableButton
        ? undefined
        : ""
      : this._writeTimeoutHandler
        ? "send-clock"
        : "send";

    if (this._templateProvider && this._templateProvider.templateFunction) {
      content = html`${until(this._templateProvider.templateFunction(value, focus, loading, !!this._writeTimeoutHandler, this._sendError, this._templateProvider.supportsHelperText ? helperText : undefined), ``)}`;
    } else {
      content = html`<or-translate .value="attributeUnsupported"></or-translate>`;
    }

    content = getAttributeInputWrapper(
      content,
      value,
      loading,
      !!this.disabled,
      this._templateProvider.supportsHelperText ? undefined : helperText,
      this._templateProvider.supportsLabel ? undefined : this.getLabel(),
      this._templateProvider.supportsSendButton ? buttonIcon : undefined,
      () => this._updateValue(),
      this.fullWidth
    );
    return content;
  }

  protected updated(_changedProperties: PropertyValues): void {
    if (_changedProperties.has("_writeTimeoutHandler") && !this._writeTimeoutHandler) {
      this._requestFocus = true;
      this.requestUpdate();
    }
  }

  protected get showButton(): boolean {
    if (this.isReadonly() || this.disabled || this.disableButton || !this._getAttributeRef()) {
      return false;
    }

    return this._templateProvider ? this._templateProvider.supportsSendButton : false;
  }

  protected getValue(): any {
    return this._attributeEvent ? this._attributeEvent.value : this.attribute ? this.attribute.value : this.value;
  }

  protected getTimestamp(): number | undefined {
    return this._attributeEvent
      ? this._attributeEvent.timestamp
      : this.attribute
        ? this.attribute.timestamp
        : undefined;
  }

  /**
   * This is called by asset-mixin
   */
  public _onEvent(event: SharedEvent) {
    if (event.eventType !== "attribute") {
      return;
    }

    const oldValue = this.getValue();
    this._attributeEvent = event as AttributeEvent;
    this._onAttributeValueChanged(oldValue, this._attributeEvent.value, event.timestamp);
  }

  public checkValidity(): boolean {
    if (!this._templateProvider) {
      return false;
    }
    if (!this._templateProvider.validator) {
      return true;
    }
    return this._templateProvider.validator();
  }

  protected _onAttributeValueChanged(oldValue: any, newValue: any, timestamp?: number) {
    if (this.attribute) {
      this.attribute.value = newValue;
      this.attribute.timestamp = timestamp;
    }

    this._clearWriteTimeout();
    this.value = newValue;
    this._sendError = false;
    this.dispatchEvent(new OrAttributeInputChangedEvent(newValue, oldValue));
  }

  protected _onInputValueChanged(value: any, updateImmediately: boolean) {
    this._newValue = value;

    if (updateImmediately) {
      this._updateValue();
    }
  }

  protected _updateValue() {
    if (this.readonly || this.isReadonly()) {
      return;
    }

    if (this._writeTimeoutHandler) {
      return;
    }

    if (this._newValue === undefined) {
      this._newValue = this.getValue();
    }

    const oldValue = this.getValue();
    const newValue = this._newValue;
    this._newValue = undefined;

    // If we have an attributeRef then send an update and wait for the updated attribute event to come back through
    // the system or for the attribute property to be updated by a parent control or timeout and reset the value
    const attributeRef = this._getAttributeRef();

    if (attributeRef && !this.disableWrite) {
      super._sendEvent({
        eventType: "attribute",
        ref: attributeRef,
        value: newValue,
      } as AttributeEvent);

      this._writeTimeoutHandler = window.setTimeout(() => this._onWriteTimeout(), this.writeTimeout);
    } else {
      this.value = newValue;
      this.dispatchEvent(new OrAttributeInputChangedEvent(newValue, oldValue));
    }
  }

  protected _clearWriteTimeout() {
    if (this._writeTimeoutHandler) {
      window.clearTimeout(this._writeTimeoutHandler);
    }
    this._writeTimeoutHandler = undefined;
  }

  protected _onWriteTimeout() {
    this._sendError = true;
    if (!this.showButton || this.hasHelperText) {
      this.requestUpdate("value");
    }
    this._clearWriteTimeout();
  }
}
