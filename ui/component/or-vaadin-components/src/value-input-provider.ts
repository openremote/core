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
import { Util } from "@openremote/core";
import { WellknownMetaItems, WellknownValueTypes } from "@openremote/model";
import type {
  AssetDescriptor,
  Attribute,
  AttributeDescriptor,
  MetaHolder,
  NameHolder,
  NameValueHolder,
  ValueConstraint,
  ValueConstraintAllowedValues,
  ValueConstraintFuture,
  ValueConstraintFutureOrPresent,
  ValueConstraintMax,
  ValueConstraintMin,
  ValueConstraintNotBlank,
  ValueConstraintNotEmpty,
  ValueConstraintNotNull,
  ValueConstraintPast,
  ValueConstraintPastOrPresent,
  ValueConstraintPattern,
  ValueConstraintSize,
  ValueDescriptor,
  ValueDescriptorHolder,
  ValueFormat,
  ValueHolder,
} from "@openremote/model";
import { html, type TemplateResult } from "lit";
import { type Ref, ref, createRef } from "lit/directives/ref.js";
import { ifDefined } from "lit/directives/if-defined.js";
import { styleMap } from "lit/directives/style-map.js";
import {
  InputType,
  inputTypeIsJson,
  inputTypeSupportsHelperText,
  inputTypeSupportsLabel,
  inputTypeSupportsSendButton,
  stringifyJson,
  type SupportedWellknownValueTypes,
} from "./util";
import { OrVaadinInput } from "./or-vaadin-input";
import { OrVaadinDateTimePicker } from "./or-vaadin-date-time-picker";

export interface ValueInputProviderOptions {
  label?: string;
  required?: boolean;
  readonly?: boolean;
  disabled?: boolean;
  compact?: boolean;
  rounded?: boolean;
  outlined?: boolean;
  comfortable?: boolean;
  resizeVertical?: boolean;
  minRows?: number;
  manualresize?: boolean;
  inputType?: InputType;
}

export interface ValueInputProvider {
  templateFunction: ValueInputTemplateFunction;
  supportsHelperText: boolean;
  supportsLabel: boolean;
  supportsSendButton: boolean;
  validator?: () => boolean;
}

export type ValueInputTemplateFunction =
  | ((
      value: any,
      focused: boolean,
      loading: boolean,
      sending: boolean,
      error: boolean,
      helperText: string | undefined
    ) => TemplateResult | PromiseLike<TemplateResult>)
  | undefined;

export type ValueInputProviderGenerator = (
  assetDescriptor: AssetDescriptor | string,
  valueHolder: (NameHolder & ValueHolder<any>) | undefined,
  valueHolderDescriptor: ValueDescriptorHolder | undefined,
  valueDescriptor: ValueDescriptor,
  valueChangeNotifier: (value: any, updateImmediately?: boolean) => void,
  options: ValueInputProviderOptions
) => ValueInputProvider;

/**
 * Internal function to retrieve HTML template based on the Asset-, attribute-, and value descriptors
 * It generates a template using `or-vaadin-input`, together with respective HTML attributes like min/max, pattern, and of course 'input type'.
 * For example, a 'positive number' attribute will render a `<or-vaadin-input type="number" min="0">`.
 * If the Vaadin input doesn't support the 'input type', it will automatically fall back to the older `or-mwc-input`.
 *
 * This is mostly copied over from `or-mwc-input`, so this will need optimizing once that has been deprecated.
 */
export const getValueHolderInputTemplateProvider: ValueInputProviderGenerator = (
  assetDescriptor,
  valueHolder,
  valueHolderDescriptor,
  valueDescriptor,
  valueChangeNotifier,
  options
) => {
  let inputType: InputType | undefined = options.inputType;
  let step: number | undefined;
  let pattern: string | undefined;
  let min: any;
  let max: any;
  let multiple: any;
  let required: boolean | undefined;
  let selectOptions: { label: string; value: string }[] | undefined;
  let valueConverter: (v: any) => any | undefined;
  const styles = {} as any;

  const assetType = typeof assetDescriptor === "string" ? assetDescriptor : assetDescriptor.name;
  const constraints: ValueConstraint[] =
    ((valueHolder && (valueHolder as MetaHolder).meta) || (valueDescriptor && (valueDescriptor as MetaHolder).meta)
      ? Util.getAttributeValueConstraints(
          valueHolder as Attribute<any>,
          valueHolderDescriptor as AttributeDescriptor,
          assetType
        )
      : Util.getMetaValueConstraints(
          valueHolder as NameValueHolder<any>,
          valueHolderDescriptor as AttributeDescriptor,
          assetType
        )) || [];
  const format: ValueFormat | undefined =
    (valueHolder && (valueHolder as MetaHolder).meta) || (valueDescriptor && (valueDescriptor as MetaHolder).meta)
      ? Util.getAttributeValueFormat(
          valueHolder as Attribute<any>,
          valueHolderDescriptor as AttributeDescriptor,
          assetType
        )
      : Util.getMetaValueFormat(valueHolder as Attribute<any>, valueHolderDescriptor as AttributeDescriptor, assetType);

  // No valueConverter below turns an attribute timestamp into what the date or time picker takes
  const typesWithoutValueConversion: InputType[] = [InputType.DATE, InputType.TIME];
  const supportsVaadinInput = (type: InputType) =>
    OrVaadinInput.TEMPLATES.has(type) && !typesWithoutValueConversion.includes(type);

  // Enforces which value types are supported making SUPPORTED_WELLKNOWN_VALUE_TYPES the single source of truth through type checking
  let _exhaustiveTypeCheck: never;
  const valueType = valueDescriptor.name as SupportedWellknownValueTypes;

  // Determine input type
  if (!inputType) {
    switch (valueType) {
      case WellknownValueTypes.TEXT:
      case WellknownValueTypes.EMAIL:
      case WellknownValueTypes.UUID:
      case WellknownValueTypes.ASSETID:
      case WellknownValueTypes.HOSTORIPADDRESS:
      case WellknownValueTypes.IPADDRESS:
        inputType =
          Util.getMetaValue(WellknownMetaItems.MULTILINE, valueHolder, valueHolderDescriptor) === true
            ? InputType.TEXTAREA
            : InputType.TEXT;
        break;
      case WellknownValueTypes.BOOLEAN:
        if (format && format.asNumber) {
          inputType = InputType.NUMBER;
          step = 1;
          min = 0;
          max = 1;
          valueConverter = (v) => !!v;
          break;
        }
        if (format && (format.asOnOff || format.asOpenClosed)) {
          inputType = InputType.SWITCH;
        } else {
          inputType = InputType.CHECKBOX;
        }

        if (
          (format && format.asMomentary) ||
          Util.getMetaValue(WellknownMetaItems.MOMENTARY, valueHolder, valueHolderDescriptor) === true
        ) {
          inputType = InputType.BUTTON_MOMENTARY;
        }
        break;
      case WellknownValueTypes.BIGNUMBER:
      case WellknownValueTypes.NUMBER:
      case WellknownValueTypes.POSITIVEINTEGER:
      case WellknownValueTypes.POSITIVENUMBER:
      case WellknownValueTypes.LONG:
      case WellknownValueTypes.INTEGER:
      case WellknownValueTypes.BYTE:
      case WellknownValueTypes.INTEGERBYTE:
      case WellknownValueTypes.DIRECTION:
      case WellknownValueTypes.TCPIPPORTNUMBER:
        if (
          valueDescriptor.name === WellknownValueTypes.BYTE ||
          valueDescriptor.name === WellknownValueTypes.INTEGERBYTE
        ) {
          min = 0;
          max = 255;
          step = 1;
        } else if (
          valueDescriptor.name === WellknownValueTypes.INTEGER ||
          valueDescriptor.name === WellknownValueTypes.LONG
        ) {
          step = 1;
        }
        if (format && format.asDate) {
          inputType = InputType.DATETIME;
        } else if (format && format.asBoolean) {
          inputType = InputType.CHECKBOX;
          valueConverter = (v) => (v ? 1 : 0);
        } else if (format && format.asSlider) {
          inputType = InputType.RANGE;
        } else {
          inputType = InputType.NUMBER;
        }
        break;
      case WellknownValueTypes.BIGINTEGER:
        inputType = InputType.BIG_INT;
        step = 1;
        break;
      case WellknownValueTypes.COLOURRGB:
        inputType = InputType.COLOUR;
        break;
      case WellknownValueTypes.DATEANDTIME:
      case WellknownValueTypes.TIMESTAMP:
      case WellknownValueTypes.TIMESTAMPISO8601:
        inputType = InputType.DATETIME;
        break;
      case WellknownValueTypes.CRONEXPRESSION:
        inputType = InputType.CRON;
        break;
      case WellknownValueTypes.TIMEDURATIONISO8601:
        inputType = InputType.DURATION_TIME;
        break;
      case WellknownValueTypes.PERIODDURATIONISO8601:
        inputType = InputType.DURATION_PERIOD;
        break;
      case WellknownValueTypes.TIMEANDPERIODDURATIONISO8601:
        inputType = InputType.DURATION;
        break;
      case WellknownValueTypes.JSONOBJECT:
        inputType = InputType.JSON_OBJECT;
        break;
      default:
        _exhaustiveTypeCheck = valueType;
    }

    if (valueDescriptor.arrayDimensions && valueDescriptor.arrayDimensions > 0) {
      inputType = InputType.JSON;
    }
  }

  if (!inputType) {
    switch (valueDescriptor.jsonType) {
      case "number":
      case "bigint":
        inputType = InputType.NUMBER;
        break;
      case "boolean":
        inputType = InputType.CHECKBOX;
        break;
      case "string":
        inputType = InputType.TEXT;
        break;
      case "date":
        inputType = InputType.DATETIME;
        break;
      default:
        break;
    }
  }

  if (!inputType) {
    inputType = InputType.JSON;
  }

  // Apply any constraints
  const sizeConstraint = constraints?.find((c) => c.type === "size") as ValueConstraintSize;
  const patternConstraint = constraints?.find((c) => c.type === "pattern") as ValueConstraintPattern;
  const minConstraint = constraints?.find((c) => c.type === "min") as ValueConstraintMin;
  const maxConstraint = constraints?.find((c) => c.type === "max") as ValueConstraintMax;
  const allowedValuesConstraint = constraints?.find((c) => c.type === "allowedValues") as ValueConstraintAllowedValues;
  const pastConstraint = constraints?.find((c) => c.type === "past") as ValueConstraintPast;
  const pastOrPresentConstraint = constraints?.find((c) => c.type === "pastOrPresent") as ValueConstraintPastOrPresent;
  const futureConstraint = constraints?.find((c) => c.type === "future") as ValueConstraintFuture;
  const futureOrPresentConstraint = constraints?.find(
    (c) => c.type === "futureOrPresent"
  ) as ValueConstraintFutureOrPresent;
  const notEmptyConstraint = constraints?.find((c) => c.type === "notEmpty") as ValueConstraintNotEmpty;
  const notBlankConstraint = constraints?.find((c) => c.type === "notBlank") as ValueConstraintNotBlank;
  const notNullConstraint = constraints?.find((c) => c.type === "notNull") as ValueConstraintNotNull;

  if (sizeConstraint) {
    min = sizeConstraint.min;
    max = sizeConstraint.max;
  }
  if (minConstraint) {
    min = minConstraint.min;
  }
  if (maxConstraint) {
    max = maxConstraint.max;
  }
  if (patternConstraint) {
    pattern = patternConstraint.regexp;
  }
  if (notNullConstraint) {
    required = true;
  }
  if (notBlankConstraint && !pattern) {
    pattern = String.raw`\S+`;
  } else if (notEmptyConstraint && !pattern) {
    pattern = ".+";
  }
  if (allowedValuesConstraint?.allowedValues) {
    const allowedLabels =
      allowedValuesConstraint.allowedValueNames &&
      allowedValuesConstraint.allowedValueNames.length === allowedValuesConstraint.allowedValues.length
        ? allowedValuesConstraint.allowedValueNames
        : undefined;
    selectOptions = allowedValuesConstraint.allowedValues.map((v, i) => {
      let label = allowedLabels ? allowedLabels[i] : "" + v;
      label = Util.getAllowedValueLabel(label)!;
      return {
        value: v,
        label: label || "" + v,
      };
    });
    inputType = InputType.SELECT;

    if (valueDescriptor.arrayDimensions && valueDescriptor.arrayDimensions > 0) {
      multiple = true;
    }
  }

  if (inputType === InputType.DATETIME) {
    if (pastConstraint || pastOrPresentConstraint) {
      min = undefined;
      max = new Date();
    } else if (futureConstraint || futureOrPresentConstraint) {
      min = new Date();
      max = undefined;
    }

    // A format showing only a date or only a time refines the input type, and that narrower type is left without the
    // conversion below, so it stays on the fallback input
    if (format?.timeStyle && !format.dateStyle) {
      inputType = InputType.TIME;
    } else if (format?.dateStyle && !format.timeStyle) {
      inputType = InputType.DATE;
    } else {
      // or-vaadin-date-time-picker works with local ISO strings, while the value is a timestamp or an ISO 8601 string
      step = OrVaadinDateTimePicker.getStep(format);
      // A minimum rounds up, so that the earliest value the picker allows still meets it
      min = min === undefined ? undefined : OrVaadinDateTimePicker.getLocalizedISOString(new Date(min), step, true);
      max = max === undefined ? undefined : OrVaadinDateTimePicker.getLocalizedISOString(new Date(max), step);
      valueConverter = (v) => {
        const timestamp = v ? Date.parse(v) : Number.NaN;
        if (Number.isNaN(timestamp)) {
          return null;
        }
        return valueDescriptor.jsonType === "string" ? new Date(timestamp).toISOString() : timestamp;
      };
    }
  }

  if (inputType === InputType.NUMBER && format?.resolution) {
    step = format.resolution;
  }

  if (inputType === InputType.COLOUR) {
    styles.marginLeft = "24px";
  }

  const supportsHelperText = inputTypeSupportsHelperText(inputType);
  const supportsLabel = inputTypeSupportsLabel(inputType);
  const supportsSendButton = inputTypeSupportsSendButton(inputType);
  const readonly = options.readonly;
  required = required || options.required;
  const comfortable = options.comfortable;
  const resizeVertical = options.resizeVertical;
  const inputRef: Ref<OrVaadinInput> = createRef();

  const templateFunction: ValueInputTemplateFunction = (value, focused, loading, sending, _error, helperText) => {
    const disabled = options.disabled || loading || sending;
    const label = supportsLabel ? options.label : undefined;
    const inputStyle = styles ? styleMap(styles) : undefined;

    const onValueChange = (ev: Event) => {
      ev.stopPropagation();
      const elem = ev.currentTarget as OrVaadinInput | undefined;
      if (elem?.checkValidity()) {
        const doRemoteUpdate =
          !OrVaadinInput.CHANGE_EVENTS.has(inputType) || OrVaadinInput.CHANGE_EVENTS.get(inputType) === ev.type;
        valueChangeNotifier(valueConverter ? valueConverter(elem.nativeValue) : elem.nativeValue, doRemoteUpdate);
      }
    };

    if (supportsVaadinInput(inputType)) {
      // or-vaadin-checkbox and or-vaadin-toggle represent a boolean state, so they use the 'checked' HTML attribute instead of 'value'.
      let checked = false;
      if (inputType === InputType.CHECKBOX || inputType === InputType.SWITCH) {
        checked = Boolean(value);
        value = undefined;
      } else if (inputType === InputType.DATETIME && value !== undefined && value !== null) {
        value = OrVaadinDateTimePicker.getLocalizedISOString(new Date(value), step);
      } else if (inputTypeIsJson(inputType)) {
        // The value attribute carries text, so a JSON value is shown as formatted JSON
        value = stringifyJson(value);
      }

      // The step is set before the value, so that a date time picker does not trim the value to its default precision.
      return html`
        <or-vaadin-input
          ${ref(inputRef)}
          id="input"
          style="${ifDefined(inputStyle)}"
          type=${ifDefined(inputType)}
          label=${ifDefined(label)}
          step=${ifDefined(step)}
          value=${ifDefined(value)}
          ?checked="${checked}"
          pattern=${ifDefined(pattern)}
          min=${ifDefined(min)}
          max=${ifDefined(max)}
          ?multiple=${multiple}
          .format=${ifDefined(format)}
          ?autofocus=${focused}
          ?required=${required}
          ?readonly=${readonly}
          ?disabled=${disabled}
          .items=${ifDefined(selectOptions)}
          helper-text="${ifDefined(helperText)}"
          ?resizeVertical="${resizeVertical}"
          min-rows="${ifDefined(inputType === InputType.TEXTAREA ? options.minRows : undefined)}"
          ?manualresize="${inputType === InputType.TEXTAREA && !!options.manualresize}"
          ?rounded="${options.rounded}"
          ?outlined="${options.outlined}"
          @change="${onValueChange}"
          @submit="${onValueChange}"
        ></or-vaadin-input>
      `;
    } else {
      // Fallback code to use deprecated or-mwc-input. This should be removed in the future, once all input types are supported and mapped.
      // Be aware: this function only creates the template, so it does not import or-mwc-input by default.
      return html`
        <or-mwc-input
          ${ref(inputRef)}
          id="input"
          style="${styleMap(styles)}"
          .type="${inputType}"
          .label="${label}"
          .value="${value}"
          .pattern="${pattern}"
          .min="${min}"
          .max="${max}"
          .format="${format}"
          .focused="${focused}"
          .required="${required}"
          .multiple="${multiple}"
          .options="${selectOptions}"
          .comfortable="${comfortable}"
          .readonly="${readonly}"
          .disabled="${disabled}"
          .step="${step}"
          .helperText="${helperText}"
          .helperPersistent="${true}"
          .resizeVertical="${resizeVertical}"
          .rounded="${options.rounded}"
          .outlined="${options.outlined}"
          @or-mwc-input-changed="${(e: CustomEvent) => {
            e.stopPropagation();
            valueChangeNotifier(valueConverter ? valueConverter(e.detail.value) : e.detail.value);
          }}"
        ></or-mwc-input>
      `;
    }
  };

  return {
    templateFunction,
    supportsHelperText,
    supportsSendButton,
    supportsLabel,
    validator: () => {
      if (!inputRef.value) {
        return false;
      }
      return inputRef.value.checkValidity();
    },
  };
};
