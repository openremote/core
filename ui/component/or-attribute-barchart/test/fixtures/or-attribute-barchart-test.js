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
// Plain JS on purpose: the component-test bundle compiles .ts files against ui/test's tsconfig
// (rootDir ui/test), so TS component sources outside that dir fail the build.
import manager from "@openremote/core";
import { OrAttributeBarChart } from "@openremote/or-attribute-barchart";

const ASSET = { id: "asset", name: "Thing", type: "ThingAsset", attributes: {} };
const ATTRIBUTE = { name: "energy", type: "number" };

const INTERVAL_MILLIS = { MINUTE: 60000, HOUR: 3600000, DAY: 86400000, WEEK: 604800000 };
/** The Monday that interval queries align their buckets to, regardless of the requested period. */
const BUCKET_ORIGIN = Date.UTC(2000, 0, 3);

/** Returns a datapoint at the start of every bucket that overlaps the requested period. */
function getDatapoints(_assetId, _attributeName, { fromTimestamp, toTimestamp, interval }) {
  const [steps, unit] = interval.split(" ");
  const millis = Number(steps) * INTERVAL_MILLIS[unit];
  const data = [];
  let x = fromTimestamp - ((fromTimestamp - BUCKET_ORIGIN) % millis);
  while (x <= toTimestamp) {
    data.push({ x, y: (data.length % 7) + 1 });
    x += millis;
  }
  return Promise.resolve({ status: 200, data });
}

Object.defineProperty(manager.rest, "api", { value: { AssetDatapointResource: { getDatapoints } } });

/** Loads datapoints as if from the manager, so the chart renders without a REST connection. */
export class OrAttributeBarChartTest extends OrAttributeBarChart {
  constructor() {
    super();
    this.assets = [ASSET];
    this.assetAttributes = [[0, ATTRIBUTE]];
    this.attributeConfig = { methodAvgAttributes: [{ id: ASSET.id, name: ATTRIBUTE.name }] };
    this.datapointQuery = { type: "interval" };
    // Animations follow the system clock, so a fixed time would keep the bars at their initial height.
    this.chartOptions = { animation: false };
  }

  connectedCallback() {
    super.connectedCallback();
    this.style.height = "400px";
  }
}
customElements.define("or-attribute-barchart-test", OrAttributeBarChartTest);
