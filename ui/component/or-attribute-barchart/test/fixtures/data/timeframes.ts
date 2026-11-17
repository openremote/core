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
import type { TimeDirection } from "@openremote/core";

export interface TimeframeStep {
  /** The chevron to click first. A step without one is what the chart shows after mounting. */
  click?: TimeDirection;
  start: string;
  end: string;
  bars: number;
}

export interface Timeframe {
  name: string;
  /** The current time of the browser. */
  now: string;
  timePrefixKey: string;
  timeWindowKey: string;
  steps: TimeframeStep[];
}

/**
 * Preset windows, each with what the chart shows after mounting and after every click. Times are in
 * Europe/Stockholm, and bars follow the buckets of a server in UTC.
 */
export const timeframes: Timeframe[] = [
  {
    name: "should still accept this as the former name of current",
    now: "2026-01-15T10:20:00+01:00",
    timePrefixKey: "this",
    timeWindowKey: "Day",
    steps: [{ start: "2026-01-15T00:00:00+01:00", end: "2026-01-15T23:59:59.999+01:00", bars: 24 }],
  },
  {
    name: "should end a last window of hours with the current hour",
    now: "2026-01-15T13:40:00+01:00",
    timePrefixKey: "last",
    timeWindowKey: "24Hours",
    steps: [
      // The window used to end at the current minute, so after half past the hour it rounded up to 25 hours and
      // switched to daily bars
      { start: "2026-01-14T14:00:00+01:00", end: "2026-01-15T14:00:00+01:00", bars: 24 },
      { click: "next", start: "2026-01-15T14:00:00+01:00", end: "2026-01-16T14:00:00+01:00", bars: 24 },
    ],
  },
  {
    name: "should center a current window of hours on the current hour",
    now: "2026-01-15T13:27:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "24Hours",
    steps: [
      // The window used to follow the current minute, so its bounds differed from the bars it showed
      { start: "2026-01-15T01:00:00+01:00", end: "2026-01-16T01:00:00+01:00", bars: 24 },
      { click: "next", start: "2026-01-16T01:00:00+01:00", end: "2026-01-17T01:00:00+01:00", bars: 24 },
    ],
  },
  {
    name: "should anchor a window of minutes to its 5 minute bars",
    now: "2026-01-15T13:27:00+01:00",
    timePrefixKey: "last",
    timeWindowKey: "60Minutes",
    steps: [
      // The window used to follow the current minute, so its bounds differed from the bars it showed
      { start: "2026-01-15T12:30:00+01:00", end: "2026-01-15T13:30:00+01:00", bars: 12 },
      { click: "next", start: "2026-01-15T13:30:00+01:00", end: "2026-01-15T14:30:00+01:00", bars: 12 },
    ],
  },
  {
    name: "should anchor a current window of days to the start of the day",
    now: "2026-01-15T13:40:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "7Days",
    steps: [
      // Half of the 7 days used to round up on both sides, so the window spanned 8 days
      { start: "2026-01-12T00:00:00+01:00", end: "2026-01-19T00:00:00+01:00", bars: 7 },
      { click: "next", start: "2026-01-19T00:00:00+01:00", end: "2026-01-26T00:00:00+01:00", bars: 7 },
    ],
  },
  {
    name: "should end a last window of days with the current day",
    now: "2026-01-15T13:40:00+01:00",
    timePrefixKey: "last",
    timeWindowKey: "7Days",
    steps: [
      // The window used to start on a whole day and end at the current minute, so it lasted more than 7 days
      { start: "2026-01-09T00:00:00+01:00", end: "2026-01-16T00:00:00+01:00", bars: 7 },
      { click: "next", start: "2026-01-16T00:00:00+01:00", end: "2026-01-23T00:00:00+01:00", bars: 7 },
    ],
  },
  {
    name: "should show the previous whole hour for the last hour",
    now: "2026-01-15T13:40:00+01:00",
    timePrefixKey: "last",
    timeWindowKey: "Hour",
    steps: [
      { start: "2026-01-15T12:00:00+01:00", end: "2026-01-15T13:00:00+01:00", bars: 12 },
      { click: "next", start: "2026-01-15T13:00:00+01:00", end: "2026-01-15T14:00:00+01:00", bars: 12 },
    ],
  },
  {
    name: "should show the previous whole day for the last day",
    now: "2026-01-15T13:40:00+01:00",
    timePrefixKey: "last",
    timeWindowKey: "Day",
    steps: [
      { start: "2026-01-14T00:00:00+01:00", end: "2026-01-15T00:00:00+01:00", bars: 24 },
      { click: "next", start: "2026-01-15T00:00:00+01:00", end: "2026-01-16T00:00:00+01:00", bars: 24 },
    ],
  },
  {
    name: "should keep hourly bars on a calendar day of 25 hours",
    now: "2026-10-24T12:00:00+02:00",
    timePrefixKey: "current",
    timeWindowKey: "Day",
    steps: [
      { start: "2026-10-24T00:00:00+02:00", end: "2026-10-24T23:59:59.999+02:00", bars: 24 },
      // The 25 hours used to exceed the limit for hourly bars, so this day switched to daily bars
      { click: "next", start: "2026-10-25T00:00:00+02:00", end: "2026-10-25T23:59:59.999+01:00", bars: 25 },
      { click: "next", start: "2026-10-26T00:00:00+01:00", end: "2026-10-26T23:59:59.999+01:00", bars: 24 },
    ],
  },
  {
    name: "should keep daily bars on a month of 745 hours",
    now: "2026-09-15T12:00:00+02:00",
    timePrefixKey: "current",
    timeWindowKey: "Month",
    steps: [
      { start: "2026-09-01T00:00:00+02:00", end: "2026-09-30T23:59:59.999+02:00", bars: 30 },
      // The 745 hours used to exceed the limit for daily bars, so this month switched to weekly bars
      { click: "next", start: "2026-10-01T00:00:00+02:00", end: "2026-10-31T23:59:59.999+01:00", bars: 31 },
    ],
  },
  {
    name: "should keep weekly bars on a leap year",
    now: "2027-06-15T12:00:00+02:00",
    timePrefixKey: "current",
    timeWindowKey: "Year",
    steps: [
      { start: "2027-01-01T00:00:00+01:00", end: "2027-12-31T23:59:59.999+01:00", bars: 52 },
      // The 8784 hours used to exceed the limit for weekly bars, so this year switched to monthly bars
      { click: "next", start: "2028-01-01T00:00:00+01:00", end: "2028-12-31T23:59:59.999+01:00", bars: 52 },
    ],
  },
  {
    name: "should keep a preset window across repeated navigation",
    now: "2026-01-15T10:20:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "Hour",
    steps: [
      { start: "2026-01-15T10:00:00+01:00", end: "2026-01-15T10:59:59.999+01:00", bars: 12 },
      { click: "next", start: "2026-01-15T11:00:00+01:00", end: "2026-01-15T11:59:59.999+01:00", bars: 12 },
      { click: "next", start: "2026-01-15T12:00:00+01:00", end: "2026-01-15T12:59:59.999+01:00", bars: 12 },
      { click: "last", start: "2026-01-15T11:00:00+01:00", end: "2026-01-15T11:59:59.999+01:00", bars: 12 },
    ],
  },
  {
    name: "should keep a fixed window at its elapsed duration across DST",
    now: "2026-03-28T12:00:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "24Hours",
    steps: [
      { start: "2026-03-28T00:00:00+01:00", end: "2026-03-29T00:00:00+01:00", bars: 24 },
      { click: "next", start: "2026-03-29T00:00:00+01:00", end: "2026-03-30T01:00:00+02:00", bars: 24 },
    ],
  },
  {
    name: "should keep a calendar day whole across DST",
    now: "2026-03-28T12:00:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "Day",
    steps: [
      { start: "2026-03-28T00:00:00+01:00", end: "2026-03-28T23:59:59.999+01:00", bars: 24 },
      // A calendar day crossing the spring transition lasts 23 hours
      { click: "next", start: "2026-03-29T00:00:00+01:00", end: "2026-03-29T23:59:59.999+02:00", bars: 23 },
    ],
  },
  {
    name: "should keep calendar months whole across months of different lengths",
    now: "2026-01-15T12:00:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "Month",
    steps: [
      { start: "2026-01-01T00:00:00+01:00", end: "2026-01-31T23:59:59.999+01:00", bars: 31 },
      { click: "next", start: "2026-02-01T00:00:00+01:00", end: "2026-02-28T23:59:59.999+01:00", bars: 28 },
      // Adding months clamps the day, which used to leave every later month ending on the 28th
      { click: "next", start: "2026-03-01T00:00:00+01:00", end: "2026-03-31T23:59:59.999+02:00", bars: 31 },
      { click: "last", start: "2026-02-01T00:00:00+01:00", end: "2026-02-28T23:59:59.999+01:00", bars: 28 },
      { click: "last", start: "2026-01-01T00:00:00+01:00", end: "2026-01-31T23:59:59.999+01:00", bars: 31 },
    ],
  },
  {
    name: "should move a current window of months by whole months",
    now: "2026-01-15T12:00:00+01:00",
    timePrefixKey: "current",
    timeWindowKey: "6Months",
    steps: [
      // The window used to follow the current day and time, so its bounds differed from the bars it showed
      { start: "2025-10-01T00:00:00+02:00", end: "2026-04-01T00:00:00+02:00", bars: 26 },
      { click: "next", start: "2026-04-01T00:00:00+02:00", end: "2026-10-01T00:00:00+02:00", bars: 26 },
    ],
  },
  {
    name: "should keep a last window of months whole at the end of a month",
    now: "2026-10-31T12:00:00+01:00",
    timePrefixKey: "last",
    timeWindowKey: "6Months",
    steps: [
      // The window used to run from the start of the month six months back to the current time, spanning seven months
      { start: "2026-05-01T00:00:00+02:00", end: "2026-11-01T00:00:00+01:00", bars: 26 },
      { click: "next", start: "2026-11-01T00:00:00+01:00", end: "2027-05-01T00:00:00+02:00", bars: 26 },
    ],
  },
];
