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
import moment from "moment";

/** The unit and the number of units of a time window, such as `["hours", 24]`. */
export type TimeWindow = [moment.unitOfTime.DurationConstructor, number];

let warnedAboutFormerPrefix = false;

/**
 * Returns a time prefix under its current name, as stored widgets and projects can still use `this` for `current`.
 */
export function normalizeTimePrefix(prefix: string): string {
  if (prefix !== "this") {
    return prefix;
  }
  if (!warnedAboutFormerPrefix) {
    warnedAboutFormerPrefix = true;
    console.warn('The time prefix "this" is deprecated, use "current" instead.');
  }
  return "current";
}

/**
 * Returns a key that combines a time prefix and a time window, like `thisDay`, with the prefix under its current name.
 */
export function normalizeTimePresetKey(key: string): string {
  return key.replace(/^this(?=[A-Z0-9])/u, "current");
}

/**
 * Returns the start and end of a time window at the current time, in the time zone of the browser.
 *
 * A window of one unit, like `Day`, is a calendar unit: `current` is the one that holds now, `last` the one before it
 * and `next` the one after it.
 *
 * A window of several units, like `24Hours`, is put around now: `current` has now in the middle, `last` ends at now and
 * `next` starts at now.
 *
 * @param wholeUnits Puts a window of several units on whole units instead of on now, or on whole 5 minute bars for
 * minutes, for a chart that has to fit whole bars in it. A `last` window then ends with the current unit.
 */
export function getTimeWindowDates(prefix: string, [unit, value]: TimeWindow, wholeUnits = false): [Date, Date] {
  const startOfUnit = moment().startOf(unit);
  const timePrefix = normalizeTimePrefix(prefix);

  if (value === 1) {
    switch (timePrefix) {
      case "current":
        return [startOfUnit.toDate(), moment().endOf(unit).toDate()];
      case "last":
        return [startOfUnit.clone().subtract(1, unit).toDate(), startOfUnit.toDate()];
      case "next":
        return [startOfUnit.clone().add(1, unit).toDate(), startOfUnit.clone().add(1, unit).endOf(unit).toDate()];
    }
  } else {
    const step = unit === "minutes" ? 5 : 1;
    const anchor = wholeUnits ? startOfUnit.clone().subtract(startOfUnit.get(unit) % step, unit) : moment();

    switch (timePrefix) {
      case "current": {
        const start = anchor.clone().subtract(Math.floor(value / 2), unit);
        return [start.toDate(), start.clone().add(value, unit).toDate()];
      }
      case "last": {
        if (wholeUnits) {
          const end = anchor.clone().add(step, unit);
          return [end.clone().subtract(value, unit).toDate(), end.toDate()];
        }
        return [moment().subtract(value, unit).startOf(unit).toDate(), anchor.toDate()];
      }
      case "next":
        return [anchor.toDate(), anchor.clone().add(value, unit).toDate()];
    }
  }

  console.error(`Could not get the time window dates, as the time prefix "${prefix}" is not supported.`);
  return [moment().toDate(), moment().toDate()];
}

/**
 * Returns the start and end of a time window moved back or forward by its own length. Leave out the time window for a
 * custom timeframe, which moves by the time between its start and end.
 */
export function shiftTimeWindowDates(
  start: Date,
  end: Date,
  timeWindow: TimeWindow | undefined,
  direction: "previous" | "next"
): [Date, Date] {
  const [unit, value]: TimeWindow = timeWindow ?? ["milliseconds", end.getTime() - start.getTime()];
  const amount = direction === "previous" ? -value : value;
  const newEnd = moment(end);
  const endsWithUnit = newEnd.isSame(newEnd.clone().endOf(unit));
  newEnd.add(amount, unit);
  // Adding months clamps the day, so a window that ends with its unit has to be extended again.
  if (endsWithUnit) {
    newEnd.endOf(unit);
  }
  return [moment(start).add(amount, unit).toDate(), newEnd.toDate()];
}
