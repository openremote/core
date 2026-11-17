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
import { ct, expect } from "@openremote/test";

import {
  getTimeWindowDates,
  normalizeTimePresetKey,
  normalizeTimePrefix,
  shiftTimeWindowDates,
} from "../src/time-window";
import { NOW, timeWindows, timeWindowShifts } from "./fixtures/data/time-windows";

// These run in the test process without a browser, so the time zone and the clock are set on the process
process.env.TZ = "Europe/Stockholm";

const realNow = Date.now;

ct.beforeEach(() => {
  Date.now = () => Date.parse(NOW);
});

ct.afterEach(() => {
  Date.now = realNow;
});

const range = (start: string, end: string) => [new Date(start).toISOString(), new Date(end).toISOString()];
const iso = (dates: [Date, Date]) => dates.map((date) => date.toISOString());

for (const { prefix, key, timeWindow, wholeUnits, start, end } of timeWindows) {
  ct(`should resolve ${prefix} ${key}${wholeUnits ? " on whole units" : ""}`, () => {
    expect(iso(getTimeWindowDates(prefix, timeWindow, wholeUnits))).toEqual(range(start, end));
  });
}

for (const { name, timeWindow, direction, from, to } of timeWindowShifts) {
  ct(`should move ${name}`, () => {
    const shifted = shiftTimeWindowDates(new Date(from[0]), new Date(from[1]), timeWindow, direction);
    expect(iso(shifted)).toEqual(range(to[0], to[1]));
  });
}

ct("should resolve the former prefix this as current", () => {
  expect(normalizeTimePrefix("this")).toEqual("current");
  expect(normalizeTimePrefix("last")).toEqual("last");
  expect(iso(getTimeWindowDates("this", ["days", 1]))).toEqual(iso(getTimeWindowDates("current", ["days", 1])));
});

ct("should rename the former prefix in a stored preset key", () => {
  expect(normalizeTimePresetKey("thisDay")).toEqual("currentDay");
  expect(normalizeTimePresetKey("this24Hours")).toEqual("current24Hours");
  expect(normalizeTimePresetKey("last24Hours")).toEqual("last24Hours");
  expect(normalizeTimePresetKey("currentDay")).toEqual("currentDay");
});
