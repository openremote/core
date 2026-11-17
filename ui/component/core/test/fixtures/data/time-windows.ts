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
import type { TimeDirection, TimeWindow } from "../../../src/time-window";

/** A Tuesday, in Europe/Stockholm. */
export const NOW = "2026-11-03T17:15:00+01:00";

export interface TimeWindowCase {
  prefix: string;
  key: string;
  timeWindow: TimeWindow;
  wholeUnits?: boolean;
  start: string;
  end: string;
}

export interface TimeWindowShift {
  name: string;
  /** Left out for a custom timeframe. */
  timeWindow?: TimeWindow;
  direction: TimeDirection;
  from: [string, string];
  to: [string, string];
}

const HOUR: TimeWindow = ["hours", 1];
const DAY: TimeWindow = ["days", 1];
const WEEK: TimeWindow = ["weeks", 1];
const MONTH: TimeWindow = ["months", 1];
const MINUTES_60: TimeWindow = ["minutes", 60];
const HOURS_6: TimeWindow = ["hours", 6];
const HOURS_24: TimeWindow = ["hours", 24];
const DAYS_7: TimeWindow = ["days", 7];

export const timeWindows: TimeWindowCase[] = [
  // A window of one unit is the same whether or not it has to fit whole bars
  {
    prefix: "current",
    key: "Hour",
    timeWindow: HOUR,
    start: "2026-11-03T17:00:00+01:00",
    end: "2026-11-03T17:59:59.999+01:00",
  },
  {
    prefix: "last",
    key: "Hour",
    timeWindow: HOUR,
    start: "2026-11-03T16:00:00+01:00",
    end: "2026-11-03T17:00:00+01:00",
  },
  {
    prefix: "next",
    key: "Hour",
    timeWindow: HOUR,
    start: "2026-11-03T18:00:00+01:00",
    end: "2026-11-03T18:59:59.999+01:00",
  },
  {
    prefix: "current",
    key: "Day",
    timeWindow: DAY,
    start: "2026-11-03T00:00:00+01:00",
    end: "2026-11-03T23:59:59.999+01:00",
  },
  {
    prefix: "current",
    key: "Day",
    timeWindow: DAY,
    wholeUnits: true,
    start: "2026-11-03T00:00:00+01:00",
    end: "2026-11-03T23:59:59.999+01:00",
  },
  {
    prefix: "last",
    key: "Day",
    timeWindow: DAY,
    start: "2026-11-02T00:00:00+01:00",
    end: "2026-11-03T00:00:00+01:00",
  },
  {
    prefix: "next",
    key: "Day",
    timeWindow: DAY,
    start: "2026-11-04T00:00:00+01:00",
    end: "2026-11-04T23:59:59.999+01:00",
  },
  // The first day of the week follows the locale of moment, which is English here
  {
    prefix: "last",
    key: "Week",
    timeWindow: WEEK,
    start: "2026-10-25T00:00:00+02:00",
    end: "2026-11-01T00:00:00+01:00",
  },
  {
    prefix: "current",
    key: "Month",
    timeWindow: MONTH,
    start: "2026-11-01T00:00:00+01:00",
    end: "2026-11-30T23:59:59.999+01:00",
  },

  // A window of several units around now
  {
    prefix: "current",
    key: "24Hours",
    timeWindow: HOURS_24,
    start: "2026-11-03T05:15:00+01:00",
    end: "2026-11-04T05:15:00+01:00",
  },
  {
    prefix: "last",
    key: "24Hours",
    timeWindow: HOURS_24,
    start: "2026-11-02T17:00:00+01:00",
    end: "2026-11-03T17:15:00+01:00",
  },
  {
    prefix: "next",
    key: "24Hours",
    timeWindow: HOURS_24,
    start: "2026-11-03T17:15:00+01:00",
    end: "2026-11-04T17:15:00+01:00",
  },
  {
    prefix: "current",
    key: "7Days",
    timeWindow: DAYS_7,
    // Half of the 7 days used to round up on both sides, so the window spanned 8 days
    start: "2026-10-31T17:15:00+01:00",
    end: "2026-11-07T17:15:00+01:00",
  },

  // A window of several units on whole units
  {
    prefix: "current",
    key: "24Hours",
    timeWindow: HOURS_24,
    wholeUnits: true,
    start: "2026-11-03T05:00:00+01:00",
    end: "2026-11-04T05:00:00+01:00",
  },
  {
    prefix: "last",
    key: "24Hours",
    timeWindow: HOURS_24,
    wholeUnits: true,
    start: "2026-11-02T18:00:00+01:00",
    end: "2026-11-03T18:00:00+01:00",
  },
  {
    prefix: "next",
    key: "6Hours",
    timeWindow: HOURS_6,
    wholeUnits: true,
    start: "2026-11-03T17:00:00+01:00",
    end: "2026-11-03T23:00:00+01:00",
  },
  {
    prefix: "current",
    key: "7Days",
    timeWindow: DAYS_7,
    wholeUnits: true,
    start: "2026-10-31T00:00:00+01:00",
    end: "2026-11-07T00:00:00+01:00",
  },
  {
    prefix: "last",
    key: "7Days",
    timeWindow: DAYS_7,
    wholeUnits: true,
    start: "2026-10-28T00:00:00+01:00",
    end: "2026-11-04T00:00:00+01:00",
  },
  {
    prefix: "current",
    key: "60Minutes",
    timeWindow: MINUTES_60,
    wholeUnits: true,
    start: "2026-11-03T16:45:00+01:00",
    end: "2026-11-03T17:45:00+01:00",
  },
  {
    prefix: "last",
    key: "60Minutes",
    timeWindow: MINUTES_60,
    wholeUnits: true,
    start: "2026-11-03T16:20:00+01:00",
    end: "2026-11-03T17:20:00+01:00",
  },
];

export const timeWindowShifts: TimeWindowShift[] = [
  {
    name: "a current day back by one day",
    timeWindow: DAY,
    direction: "last",
    from: ["2026-11-03T00:00:00+01:00", "2026-11-03T23:59:59.999+01:00"],
    to: ["2026-11-02T00:00:00+01:00", "2026-11-02T23:59:59.999+01:00"],
  },
  {
    name: "a last day back by one day",
    timeWindow: DAY,
    direction: "last",
    from: ["2026-11-02T00:00:00+01:00", "2026-11-03T00:00:00+01:00"],
    to: ["2026-11-01T00:00:00+01:00", "2026-11-02T00:00:00+01:00"],
  },
  {
    name: "a calendar day onto the day of 25 hours",
    timeWindow: DAY,
    direction: "next",
    from: ["2026-10-24T00:00:00+02:00", "2026-10-24T23:59:59.999+02:00"],
    to: ["2026-10-25T00:00:00+02:00", "2026-10-25T23:59:59.999+01:00"],
  },
  {
    name: "a fixed window by its elapsed hours across DST",
    timeWindow: HOURS_24,
    direction: "next",
    from: ["2026-03-28T00:00:00+01:00", "2026-03-29T00:00:00+01:00"],
    to: ["2026-03-29T00:00:00+01:00", "2026-03-30T01:00:00+02:00"],
  },
  {
    name: "a whole month onto a longer month",
    timeWindow: MONTH,
    direction: "next",
    from: ["2026-02-01T00:00:00+01:00", "2026-02-28T23:59:59.999+01:00"],
    // Adding months clamps the day, which used to leave every later month on the line chart ending on the 28th
    to: ["2026-03-01T00:00:00+01:00", "2026-03-31T23:59:59.999+02:00"],
  },
  {
    name: "a custom timeframe by its own length",
    direction: "next",
    from: ["2026-01-15T00:00:00+01:00", "2026-01-15T03:00:00+01:00"],
    // A custom timeframe on the line chart used to move by the selected time window instead
    to: ["2026-01-15T03:00:00+01:00", "2026-01-15T06:00:00+01:00"],
  },
];
