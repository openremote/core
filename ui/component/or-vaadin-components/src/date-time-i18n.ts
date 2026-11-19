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
import i18next from "i18next";
import { I18NEXT_TO_MOMENT_LOCALE } from "@openremote/core";
import { getAdjustedYear } from "@vaadin/date-picker/src/vaadin-date-picker-helper.js";
import type { DatePickerDate, DatePickerI18n } from "@vaadin/date-picker";
import type { TimePickerI18n } from "@vaadin/time-picker";
import type { TimePickerTime } from "@vaadin/time-picker/src/vaadin-time-picker-helper.js";

// Dates and times are formatted in the browser locale, so users keep their regional format whatever the app language
// is, while names and labels follow the app language.

/**
 * Calls `update` now and whenever the app language changes.
 * @returns A function that stops the updates
 */
export function syncWithLanguage(update: () => void): () => void {
  update();
  i18next.on("languageChanged", update);
  return () => i18next.off("languageChanged", update);
}

/**
 * Returns the Vaadin date picker i18n: the order of the date fields and the first day of the week of the browser
 * locale, and the month and weekday names and button labels of the app language.
 */
export function getDatePickerI18n(): DatePickerI18n {
  const dateFormat = getUTCFormat(undefined, { day: "2-digit", month: "2-digit", year: "numeric" });
  const fieldOrder = dateFormat
    .formatToParts(0)
    .map((part) => part.type)
    .filter((type) => type === "day" || type === "month" || type === "year");

  const language = getLanguageLocale();
  const monthFormat = getUTCFormat(language, { month: "long" });
  const weekdayFormat = getUTCFormat(language, { weekday: "long" });
  const shortWeekdayFormat = getUTCFormat(language, { weekday: "short" });
  const today = new Intl.RelativeTimeFormat(language, { numeric: "auto" }).format(0, "day");

  const i18n: DatePickerI18n = {
    monthNames: Array.from({ length: 12 }, (_, month) => monthFormat.format(Date.UTC(2000, month, 1))),
    // The weekday names start on Sunday, and 2 January 2000 is one
    weekdays: Array.from({ length: 7 }, (_, day) => weekdayFormat.format(Date.UTC(2000, 0, 2 + day))),
    weekdaysShort: Array.from({ length: 7 }, (_, day) => shortWeekdayFormat.format(Date.UTC(2000, 0, 2 + day))),
    today: today.charAt(0).toLocaleUpperCase(language) + today.slice(1),
    formatDate: (date) => formatDate(dateFormat, date),
    parseDate: (text) => parseDate(fieldOrder, text),
  };

  const firstDayOfWeek = getFirstDayOfWeek(dateFormat.resolvedOptions().locale);
  if (firstDayOfWeek !== undefined) {
    i18n.firstDayOfWeek = firstDayOfWeek;
  }
  if (i18next.exists("cancel")) {
    i18n.cancel = i18next.t("cancel");
  }
  return i18n;
}

/**
 * Returns the Vaadin time picker i18n: a 12-hour clock in the layout of the browser locale where it uses one, or the
 * default 24-hour format of the picker otherwise.
 */
export function getTimePickerI18n(): TimePickerI18n {
  const timeFormat = getUTCFormat(undefined, { hour: "numeric", minute: "2-digit" });
  if (!timeFormat.resolvedOptions().hour12) {
    return {};
  }

  const locale = timeFormat.resolvedOptions().locale;
  const [am, pm] = [0, 12].map(
    (hours) =>
      timeFormat.formatToParts(Date.UTC(2000, 0, 1, hours)).find((part) => part.type === "dayPeriod")?.value ??
      (hours < 12 ? "AM" : "PM")
  );
  // The parts of 1:02 give the order of the day period and the time, and the literals around them. Spaces are made
  // plain, as Intl may use a narrow no-break space that cannot be typed.
  const parts = timeFormat
    .formatToParts(Date.UTC(2000, 0, 1, 1, 2))
    .map((part) => (part.type === "literal" ? { ...part, value: part.value.replace(/\s/gu, " ") } : part));
  const separator =
    parts.find((part, index) => part.type === "literal" && parts[index - 1]?.type === "hour")?.value ?? ":";
  const timePattern = createTimePattern(separator);
  const normalize = (text: string) => text.toLocaleLowerCase(locale).replace(/[\s.]/gu, "");

  return {
    formatTime: (time) => formatTime(time, parts, separator, am, pm),
    parseTime: (text) => parseTime(text, timePattern, normalize(am), normalize(pm), normalize),
  };
}

/**
 * Returns the Intl locale of the app language, or undefined for the browser locale when Intl does not support it.
 */
function getLanguageLocale(): string | undefined {
  const language = i18next.language;
  if (!language) {
    return undefined;
  }
  try {
    return Intl.DateTimeFormat.supportedLocalesOf(I18NEXT_TO_MOMENT_LOCALE[language] ?? language)[0];
  } catch {
    return undefined;
  }
}

/**
 * Returns a UTC date format in the locale, where an undefined locale is the browser locale.
 */
function getUTCFormat(locale: string | undefined, options: Intl.DateTimeFormatOptions) {
  return new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC", numberingSystem: "latn" });
}

/**
 * Returns the first day of the week of the locale, where 0 is Sunday, or undefined when the browser has no week info.
 */
function getFirstDayOfWeek(locale: string): number | undefined {
  const intlLocale = new Intl.Locale(locale) as Intl.Locale & {
    getWeekInfo?: () => { firstDay: number };
    weekInfo?: { firstDay: number };
  };
  const weekInfo = intlLocale.getWeekInfo?.() ?? intlLocale.weekInfo;
  return weekInfo && weekInfo.firstDay % 7;
}

function formatDate(format: Intl.DateTimeFormat, { day, month, year }: DatePickerDate): string {
  const date = new Date(0);
  date.setUTCFullYear(year, month, day);
  // A padded year keeps years below 1000 from parsing back as two-digit years
  return format
    .formatToParts(date)
    .map((part) => (part.type === "year" ? String(year).padStart(4, "0") : part.value))
    .join("");
}

/**
 * Parses the numbers of the text in the field order of the date format. Leaving out numbers drops the year and then the
 * month, which default to the current ones.
 */
function parseDate(fieldOrder: string[], text: string): DatePickerDate | undefined {
  const numbers = text.match(/\d+/gu) ?? [];
  if (numbers.length < 1 || numbers.length > 3) {
    return undefined;
  }

  let fields = ["day"];
  if (numbers.length === 3) {
    fields = fieldOrder;
  } else if (numbers.length === 2) {
    fields = fieldOrder.filter((field) => field !== "year");
  }
  const parts = Object.fromEntries(fields.map((field, index) => [field, numbers[index]]));

  const today = new Date();
  const day = parseInt(parts.day);
  const month = parts.month === undefined ? today.getMonth() : parseInt(parts.month) - 1;
  let year = today.getFullYear();
  if (parts.year !== undefined) {
    // getAdjustedYear takes at most two digits, so a longer year is already a full one
    year = parts.year.length < 3 ? getAdjustedYear(today, parseInt(parts.year), month, day) : parseInt(parts.year);
  }
  return { day, month, year };
}

/**
 * Formats a time on a 12-hour clock in the layout of the parts of the locale, with any seconds and milliseconds after
 * the minutes.
 */
function formatTime(
  time: TimePickerTime | undefined,
  parts: Intl.DateTimeFormatPart[],
  separator: string,
  am: string,
  pm: string
): string {
  if (!time) {
    return "";
  }
  const pad = (value: number | string, length = 2) => String(value).padStart(length, "0");
  const hours = Number(time.hours);
  let minutes = pad(time.minutes);
  if (time.seconds !== undefined) {
    minutes += `${separator}${pad(time.seconds)}`;
  }
  if (time.milliseconds !== undefined) {
    minutes += `.${pad(time.milliseconds, 3)}`;
  }

  return parts
    .map((part) => {
      switch (part.type) {
        case "hour":
          return part.value.length === 2 ? pad(hours % 12 || 12) : String(hours % 12 || 12);
        case "minute":
          return minutes;
        case "dayPeriod":
          return hours < 12 ? am : pm;
        default:
          return part.value;
      }
    })
    .join("");
}

/**
 * Returns the pattern of a time with the given separator, and a day period before or after it.
 */
function createTimePattern(separator: string): RegExp {
  const s = separator.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  return new RegExp(
    `^\\s*(\\D*?)\\s*(\\d{1,2})(?:${s}(\\d{1,2})(?:${s}(\\d{1,2})(?:\\.(\\d{1,3}))?)?)?\\s*(\\D*?)\\s*$`,
    "u"
  );
}

/**
 * Parses a time on a 12-hour clock with the day period before or after it, or on a 24-hour clock when the text has no
 * day period. A prefix of the day period is enough, such as "p" for "PM".
 */
function parseTime(
  text: string,
  pattern: RegExp,
  am: string,
  pm: string,
  normalize: (text: string) => string
): TimePickerTime | undefined {
  const match = pattern.exec(text);
  if (!match) {
    return undefined;
  }

  const [, leadingPeriod, hourText, minuteText, secondText, millisecondText, trailingPeriod] = match;
  const periods = [leadingPeriod, trailingPeriod].map(normalize).filter((period) => period);
  if (periods.length > 1) {
    return undefined;
  }
  const [period] = periods;
  let hours = parseInt(hourText);
  const minutes = minuteText === undefined ? 0 : parseInt(minuteText);
  const seconds = secondText === undefined ? undefined : parseInt(secondText);
  if (period) {
    const isAm = am.startsWith(period);
    const isPm = pm.startsWith(period);
    if (isAm === isPm || hours < 1 || hours > 12) {
      return undefined;
    }
    hours = (hours % 12) + (isPm ? 12 : 0);
  }
  if (hours > 23 || minutes > 59 || (seconds ?? 0) > 59) {
    return undefined;
  }

  return {
    hours,
    minutes,
    seconds,
    milliseconds: millisecondText === undefined ? undefined : parseInt(millisecondText.padEnd(3, "0")),
  };
}
