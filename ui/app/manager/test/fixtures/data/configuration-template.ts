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
import type { Asset } from "@openremote/model";
import { commonAttrs } from "./assets";

/**
 * A light whose attributes hold configuration items to copy, as the config template setup seeds its configured pump.
 * Maintenance note is an attribute of the asset alone, which the asset type does not define.
 */
export const configuredLight: Asset = {
  name: "Light 1 (configured)",
  realm: "smartcity",
  type: "LightAsset",
  attributes: {
    ...commonAttrs,
    onOff: { name: "onOff", type: "boolean", meta: { label: "Power", accessPublicRead: true } },
    brightness: {
      name: "brightness",
      type: "positiveInteger",
      meta: { label: "Dimmer", readOnly: true, storeDataPoints: true, ruleState: true },
    },
    colourRGB: { name: "colourRGB", type: "colourRGB" },
    colourTemperature: {
      name: "colourTemperature",
      type: "positiveInteger",
      meta: {
        label: "White tone",
        constraints: [
          { type: "min", min: 2000 },
          { type: "max", max: 6500 },
        ],
      },
    },
    maintenanceNote: { name: "maintenanceNote", type: "text", meta: { label: "Maintenance note" } },
  },
};

/** A light of the same type to import into, holding access metadata of its own and a label to replace. */
export const targetLight: Asset = {
  name: "Light 2 (import target)",
  realm: "smartcity",
  type: "LightAsset",
  attributes: {
    ...commonAttrs,
    onOff: { name: "onOff", type: "boolean", meta: { accessRestrictedRead: true } },
    brightness: { name: "brightness", type: "positiveInteger", value: 40, meta: { label: "Old label" } },
    colourRGB: { name: "colourRGB", type: "colourRGB" },
    colourTemperature: { name: "colourTemperature", type: "positiveInteger", value: 3000 },
  },
};

/** The configuration template of {@link configuredLight}. */
export const lightTemplate = {
  version: 1,
  assetType: "LightAsset",
  attributes: {
    brightness: {
      type: "positiveInteger",
      meta: { label: "Dimmer", readOnly: true, storeDataPoints: true, ruleState: true },
    },
    colourTemperature: {
      type: "positiveInteger",
      meta: {
        label: "White tone",
        constraints: [
          { type: "min", min: 2000 },
          { type: "max", max: 6500 },
        ],
      },
    },
    onOff: { type: "boolean", meta: { label: "Power" } },
  },
};

/** A hand edited template with an attribute that a light misses and one of another value type. */
export const partlyImportableTemplate = {
  version: 1,
  assetType: "LightAsset",
  attributes: {
    brightness: { type: "positiveInteger", meta: { label: "Dimmer" } },
    colourTemperature: { type: "text", meta: { label: "White tone" } },
    dimLevel: { type: "number", meta: { label: "Dim level" } },
  },
};
