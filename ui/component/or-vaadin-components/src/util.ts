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
import { WellknownValueTypes } from "@openremote/model";

export enum InputType {
  BUTTON = "button",
  BUTTON_TOGGLE = "button-toggle",
  BUTTON_MOMENTARY = "button-momentary",
  CHECKBOX = "checkbox",
  CHECKBOX_LIST = "checkbox-list",
  COLOUR = "color",
  DATE = "date",
  DATETIME = "datetime-local",
  EMAIL = "email",
  JSON = "json",
  JSON_OBJECT = "json-object",
  MONTH = "month",
  NUMBER = "number",
  BIG_INT = "big-int",
  PASSWORD = "password",
  RADIO = "radio",
  SWITCH = "switch",
  RANGE = "range",
  TELEPHONE = "tel",
  TEXT = "text",
  TEXTAREA = "textarea",
  TIME = "time",
  URL = "url",
  WEEK = "week",
  SELECT = "select",
  LIST = "list",
  CRON = "cron",
  DURATION = "duration",
  DURATION_TIME = "duration-time",
  DURATION_PERIOD = "duration-period",
}

export const SUPPORTED_WELLKNOWN_VALUE_TYPES = [
  WellknownValueTypes.TEXT,
  WellknownValueTypes.EMAIL,
  WellknownValueTypes.UUID,
  WellknownValueTypes.ASSETID,
  WellknownValueTypes.HOSTORIPADDRESS,
  WellknownValueTypes.IPADDRESS,
  WellknownValueTypes.BOOLEAN,
  WellknownValueTypes.BIGNUMBER,
  WellknownValueTypes.NUMBER,
  WellknownValueTypes.POSITIVEINTEGER,
  WellknownValueTypes.POSITIVENUMBER,
  WellknownValueTypes.LONG,
  WellknownValueTypes.INTEGER,
  WellknownValueTypes.BYTE,
  WellknownValueTypes.INTEGERBYTE,
  WellknownValueTypes.DIRECTION,
  WellknownValueTypes.TCPIPPORTNUMBER,
  WellknownValueTypes.BIGINTEGER,
  WellknownValueTypes.COLOURRGB,
  WellknownValueTypes.DATEANDTIME,
  WellknownValueTypes.TIMESTAMP,
  WellknownValueTypes.TIMESTAMPISO8601,
  WellknownValueTypes.CRONEXPRESSION,
  WellknownValueTypes.TIMEDURATIONISO8601,
  WellknownValueTypes.PERIODDURATIONISO8601,
  WellknownValueTypes.TIMEANDPERIODDURATIONISO8601,
  WellknownValueTypes.JSONOBJECT,
] as const;

export type SupportedWellknownValueTypes = (typeof SUPPORTED_WELLKNOWN_VALUE_TYPES)[number];

export interface OrVaadinComponent {
  getAttributeNames(): string[];
}

/**
 * Returns whether the {@link InputType} holds its value as JSON text, so it needs to be parsed on the way out
 * and serialized on the way in.
 */
export function inputTypeIsJson(inputType: InputType) {
  return inputType === InputType.JSON || inputType === InputType.JSON_OBJECT;
}

/**
 * Returns whether the {@link InputType} should show a "send" button within the attribute input UI.
 * Some input types have internal mechanics for updating attributes, which is why they should return `false`.
 * Generic input types, like a text field, "support a send button", so should return `true`
 */
export function inputTypeSupportsSendButton(inputType: InputType): boolean {
  return (
    inputType === InputType.NUMBER ||
    inputType === InputType.BIG_INT ||
    inputType === InputType.TELEPHONE ||
    inputType === InputType.TEXT ||
    inputType === InputType.PASSWORD ||
    inputType === InputType.DATE ||
    inputType === InputType.DATETIME ||
    inputType === InputType.EMAIL ||
    inputTypeIsJson(inputType) ||
    inputType === InputType.MONTH ||
    inputType === InputType.TEXTAREA ||
    inputType === InputType.TIME ||
    inputType === InputType.URL ||
    inputType === InputType.WEEK
  );
}

export function inputTypeSupportsHelperText(inputType: InputType) {
  return inputTypeSupportsSendButton(inputType) || inputType === InputType.SELECT || inputType === InputType.RANGE;
}

export function inputTypeSupportsLabel(inputType: InputType) {
  return (
    inputTypeSupportsHelperText(inputType) ||
    inputType === InputType.CHECKBOX ||
    inputType === InputType.BUTTON_MOMENTARY
  );
}

/**
 * Parses the text of a JSON input type. An empty field is a valid, absent value; text that does not parse,
 * or that is not an object for {@link InputType.JSON_OBJECT}, is invalid and has no value.
 * @param inputType - The input type being parsed
 * @param text - The text to parse
 */
export function parseJson(inputType: InputType, text?: string): { value?: any; valid: boolean } {
  if (!text?.trim()) {
    return { valid: true };
  }
  try {
    const value = JSON.parse(text);
    if (inputType === InputType.JSON_OBJECT && (typeof value !== "object" || value === null || Array.isArray(value))) {
      return { valid: false };
    }
    return { value, valid: true };
  } catch {
    return { valid: false };
  }
}

/**
 * Serializes a value for display in a JSON input type, matching what {@link parseJson} reads back.
 * @param value - The value to serialize
 */
export function stringifyJson(value: any): string | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return undefined;
  }
}
