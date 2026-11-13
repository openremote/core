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
import {
  type Asset,
  AssetModelUtil,
  type AssetDescriptor,
  type AssetQuery,
  type AssetQueryAccess,
  type AssetQueryOrderBy,
  type AssetQuerySelect,
  type Attribute,
  type AttributeDescriptor,
  type AttributeEvent,
  type AttributePredicate,
  type GeofencePredicate,
  type JsonRulesetDefinition,
  type LogicGroup,
  type MetaHolder,
  type NameHolder,
  type NameValueHolder,
  type ParentPredicate,
  type PathPredicate,
  type PushNotificationMessage,
  type RealmPredicate,
  type RuleActionUnion,
  type RuleCondition,
  type StringPredicate,
  type ValueDescriptor,
  type ValueDescriptorHolder,
  type ValueFormat,
  type ValuePredicateUnion,
  WellknownMetaItems,
  type ValueConstraint,
  type AbstractNameValueDescriptorHolder,
  type MetaItemDescriptor,
  ValueFormatStyleRepresentation,
} from "@openremote/model";
import i18next from "i18next";
import Qs from "qs";
import moment from "moment";
import DateTimeFormatOptions = Intl.DateTimeFormatOptions;
import transform from "lodash.transform";

export class Deferred<T> {
  protected _resolve!: (value: T | PromiseLike<T>) => void;
  protected _reject!: (reason?: any) => void;
  protected _promise: Promise<T>;

  get resolve() {
    return this._resolve;
  }

  get reject() {
    return this._reject;
  }

  get promise() {
    return this._promise;
  }

  constructor() {
    this._promise = new Promise<T>((resolve1, reject1) => {
      this._resolve = resolve1;
      this._reject = reject1;
    });
    Object.freeze(this);
  }
}

export interface GeoNotification {
  predicate: GeofencePredicate;
  notification?: PushNotificationMessage;
}

export function getBrowserLanguage(): string {
  return navigator.language.split("-")[0] || "en";
}

export function getQueryParameters(queryStr: string): any {
  return Qs.parse(queryStr, { ignoreQueryPrefix: true });
}

export function getQueryParameter(parameter: string): any | undefined {
  let parsed;

  if (location.search && location.search !== "") {
    parsed = getQueryParameters(location.search);
  }

  if (location.hash) {
    const index = location.hash.indexOf("?");
    if (index > -1) {
      parsed = getQueryParameters(location.hash.substring(index + 1));
    }
  }
  return parsed ? parsed[parameter] : undefined;
}

export function getGeoNotificationsFromRulesSet(rulesetDefinition: JsonRulesetDefinition): GeoNotification[] {
  const geoNotifications: GeoNotification[] = [];

  rulesetDefinition.rules!.forEach((rule) => {
    if (rule.when && rule.then && rule.then.length > 0) {
      const geoNotificationMap = new Map<string, GeoNotification[]>();
      addGeofencePredicatesFromRuleCondition(rule.when, 0, geoNotificationMap);

      if (geoNotificationMap.size > 0) {
        rule.then.forEach((ruleAction) => addPushNotificationsFromRuleAction(ruleAction, geoNotificationMap));
      }
      for (const geoNotificationsArr of geoNotificationMap.values()) {
        geoNotificationsArr.forEach((geoNotification) => {
          if (geoNotification.notification) {
            geoNotifications.push(geoNotification);
          }
        });
      }
    }
  });

  return geoNotifications;
}

function addGeofencePredicatesFromRuleCondition(
  ruleCondition: LogicGroup<RuleCondition> | undefined,
  index: number,
  geoNotificationMap: Map<string, GeoNotification[]>
) {
  if (!ruleCondition) {
    return;
  }
  const items: any = [];
  if (ruleCondition.groups) {
    ruleCondition.groups.forEach((ruleGroup) => {
      if (ruleGroup.items) {
        ruleGroup.items.forEach((ruleTrigger) => {
          items.push(ruleTrigger);
        });
      }
    });
  }

  if (ruleCondition.items) {
    ruleCondition.items.forEach((ruleTrigger) => {
      items.push(ruleTrigger);
    });
  }
  if (items) {
    items.forEach((ruleTrigger: any) => {
      if (ruleTrigger.assets && ruleTrigger.assets.attributes) {
        const geoNotifications: GeoNotification[] = [];
        addGeoNotificationsFromAttributePredicateCondition(ruleTrigger.assets.attributes, geoNotifications);
        if (geoNotifications.length > 0) {
          const tagName = ruleTrigger.tag || index.toString();
          geoNotificationMap.set(tagName, geoNotifications);
        }
      }
    });
  }
}

function addGeoNotificationsFromAttributePredicateCondition(
  attributeCondition: LogicGroup<AttributePredicate> | undefined,
  geoNotifications: GeoNotification[]
) {
  if (!attributeCondition) {
    return;
  }

  attributeCondition.items!.forEach((predicate) => {
    if (predicate.value && (predicate.value.predicateType === "radial" || predicate.value!.predicateType === "rect")) {
      geoNotifications.push({
        predicate: predicate.value as GeofencePredicate,
      });
    }
  });

  if (attributeCondition.groups) {
    attributeCondition.groups.forEach((condition) =>
      addGeoNotificationsFromAttributePredicateCondition(condition, geoNotifications)
    );
  }
}

function addPushNotificationsFromRuleAction(
  ruleAction: RuleActionUnion,
  geoPredicateMap: Map<string, GeoNotification[]>
) {
  if (ruleAction && ruleAction.action === "notification") {
    if (ruleAction.notification && ruleAction.notification.message && ruleAction.notification.message.type === "push") {
      // Find applicable targets
      const target = ruleAction.target;
      if (target && target.conditionAssets) {
        const geoNotifications = geoPredicateMap.get(target.conditionAssets);
        if (geoNotifications) {
          geoNotifications.forEach((geoNotification) => {
            geoNotification.notification = ruleAction.notification!.message as PushNotificationMessage;
          });
        }
      } else {
        // Applies to all LHS rule triggers
        for (const geoNotifications of geoPredicateMap.values()) {
          geoNotifications.forEach((geoNotification) => {
            geoNotification.notification = ruleAction.notification!.message as PushNotificationMessage;
          });
        }
      }
    }
  }
}

const TIME_DURATION_REGEXP =
  /([+-])?((\d+)[Dd])?\s*((\d+)[Hh])?\s*((\d+)[Mm]$)?\s*((\d+)[Ss])?\s*((\d+)([Mm][Ss]$))?\s*((\d+)[Ww])?\s*((\d+)[Mm][Nn])?\s*((\d+)[Yy])?/;

export function isTimeDuration(time?: string): boolean {
  if (!time) {
    return false;
  }

  time = time.trim();

  return (
    time.length > 0 &&
    (TIME_DURATION_REGEXP.test(time) || isTimeDurationPositiveInfinity(time) || isTimeDurationNegativeInfinity(time))
  );
}

export function isTimeDurationPositiveInfinity(time?: string): boolean {
  time = time != null ? time.trim() : undefined;
  return time === "*" || time === "+*";
}

export function isTimeDurationNegativeInfinity(time?: string): boolean {
  time = time != null ? time.trim() : undefined;
  return time === "-*";
}

export function isObject(object: any): boolean {
  if (object) {
    return typeof object === "object";
  }
  return false;
}

export function objectsEqual(obj1?: any, obj2?: any, deep = true): boolean {
  if (obj1 === null || obj1 === undefined || obj2 === null || obj2 === undefined) {
    return obj1 === obj2;
  }
  // after this just checking type of one would be enough
  if (obj1.constructor !== obj2.constructor) {
    return false;
  }
  // if they are functions, they should exactly refer to same one (because of closures)
  if (obj1 instanceof Function) {
    return obj1 === obj2;
  }
  // if they are regexps, they should exactly refer to same one (it is hard to better equality check on current ES)
  if (obj1 instanceof RegExp) {
    return obj1 === obj2;
  }
  if (obj1 === obj2 || obj1.valueOf() === obj2.valueOf()) {
    return true;
  }
  if (Array.isArray(obj1) && obj1.length !== obj2.length) {
    return false;
  }

  // if they are dates, they must had equal valueOf
  if (obj1 instanceof Date) {
    return false;
  }

  // if they are strictly equal, they both need to be object at least
  if (!(obj1 instanceof Object)) {
    return false;
  }
  if (!(obj2 instanceof Object)) {
    return false;
  }

  if (deep) {
    // index-based comparison for arrays; the key-based check below is quadratic there
    if (Array.isArray(obj1)) {
      return obj1.every((item, i) => objectsEqual(item, obj2[i]));
    }
    // recursive object equality check
    const p = Object.keys(obj1);
    return (
      Object.keys(obj2).every((i) => {
        return p.indexOf(i) !== -1;
      }) &&
      p.every((i) => {
        return objectsEqual(obj1[i], obj2[i]);
      })
    );
  }

  return false;
}
/**
 * Deep diff between two object, using lodash
 * @param  {Object} object Object compared
 * @param  {Object} base   Object to compare with
 * @return {Object}        Return a new object who represent the diff
 */
export function difference(object?: any, base?: any): any {
  const changes = (object: any, base: any) =>
    transform(object, function (result: any, value, key: string | number | symbol) {
      if (!objectsEqual(value, base?.[key])) {
        result[key] = isObject(value) && isObject(base?.[key]) ? changes(value, base?.[key]) : value;
      }
    });
  return changes(object, base);
}

export function arrayRemove<T>(arr: T[], item: T) {
  if (arr.length === 0) {
    return;
  }
  const index = arr.indexOf(item);
  if (index >= 0) {
    arr.splice(index, 1);
  }
}

export function sentenceCaseToCamelCase(str: string | undefined): string {
  if (str === undefined || str === null) {
    return "";
  }

  return str
    .split(" ")
    .map((value: string, index: number): string => {
      if (index === 0) {
        return value[0].toLowerCase() + value.substring(1);
      }
      return value[0].toUpperCase() + value.substring(1);
    })
    .join("");
}

export function camelCaseToSentenceCase(str: string | undefined): string {
  if (str === undefined || str === null) {
    return "";
  }
  let startDone = false;
  return str
    .split(/([A-Z]|\d)/)
    .map((v, i, arr) => {
      // Skip empty blocks
      if (!v) return v;

      // If first block then capitalize 1st letter regardless
      if (!startDone) {
        startDone = true;
        return v.charAt(0).toUpperCase() + v.slice(1);
      }

      // Underscore substitution
      if (v === "_") return " ";
      // We have a capital or number
      if (v.length === 1 && v === v.toUpperCase()) {
        const previousCapital = !arr[i - 1] || arr[i - 1] === "_";
        const nextWord = i + 1 < arr.length && arr[i + 1] && arr[i + 1] !== "_";
        const nextTwoCapitalsOrEndOfString = i + 3 > arr.length || (!arr[i + 1] && !arr[i + 3]);
        // Insert space
        if (!previousCapital || nextWord) v = " " + v;
        // Start of word or single letter word
        if (nextWord || (!previousCapital && !nextTwoCapitalsOrEndOfString)) v = v.toLowerCase();
      }
      return v;
    })
    .join("")
    .trim();
}

export function stringMatch(needle: string, haystack: string): boolean {
  if (haystack === needle) {
    return true;
  }

  const startsWith = needle.endsWith("*");
  const endsWith = !startsWith && needle.startsWith("*");
  const regExp = !startsWith && !endsWith && needle.startsWith("^") && needle.endsWith("$");

  if (startsWith && haystack.startsWith(needle.substr(0, needle.length - 1))) {
    return true;
  }

  if (endsWith && haystack.endsWith(needle.substr(1))) {
    return true;
  }

  if (regExp) {
    try {
      const regexp = new RegExp(needle);
      return regexp.test(haystack);
    } catch (e) {
      console.error("Failed to compile needle as a RegExp: " + e);
    }
  }

  return false;
}

export function capitaliseFirstLetter(str: string | undefined) {
  if (!str) {
    return;
  }
  if (str.length === 1) {
    return str.toUpperCase();
  }
  return str.charAt(0).toUpperCase() + str.slice(1);
}

export function getEnumKeyAsString(enm: object, val: string): string | undefined {
  return Object.keys(enm).find((k) => enm[k as keyof object] === val);
}

/* For a given date, get the ISO week number
 *
 * Based on information at:
 *
 *    http://www.merlyn.demon.co.uk/weekcalc.htm#WNR
 *
 * Algorithm is to find nearest thursday, it's year
 * is the year of the week number. Then get weeks
 * between that date and the first day of that year.
 *
 * Note that dates in one year can be weeks of previous
 * or next year, overlap is up to 3 days.
 *
 * e.g. 2014/12/29 is Monday in week  1 of 2015
 *      2012/1/1   is Sunday in week 52 of 2011
 */
export function getWeekNumber(date: Date): number {
  // Copy date so don't modify original
  date = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
  // Set to nearest Thursday: current date + 4 - current day number
  // Make Sunday's day number 7
  date.setUTCDate(date.getUTCDate() + 4 - (date.getUTCDay() || 7));
  // Get first day of year
  const yearStart = new Date(Date.UTC(date.getUTCFullYear(), 0, 1));
  // Calculate full weeks to nearest Thursday
  const weekNo = Math.ceil(((date.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
  // Return array of year and week number
  return weekNo;
}

const monthNames = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

/* Creates a cron string based on the input parameters.
 * You can for example trigger an expression once a month on friday, or on a specific date ass March 1st.
 *
 * Useful URLs for understanding cron:
 * - https://en.wikipedia.org/wiki/Cron
 * - https://www.freeformatter.com/cron-expression-generator-quartz.html
 *
 *  @param years What years it should trigger. For example ['2022','2023'] or a string to inject.
 *  @param months What months of the year it should trigger. For example ['JAN','FEB'] or [0,1,2,3,4,5] or a string to inject.
 *  @param days What days of the month it should trigger. For example [7,14,21,28] or a string to inject.
 *  @param hours What hours of the day it should trigger. For example [3,6,9,12,15,18,21] or a string to inject.
 *  @param minutes What minutes of the hour it should trigger. For example [0,15,30,45] or a string to inject.
 *  @param seconds What seconds of the minute it should trigger. For example [0,15,30,45] or a string to inject.
 *  @param daysOfTheWeek String to inject for specifying specific days such as '1st monday of the month'.
 * */
export function formatCronString(
  years?: string | number | string[],
  months?: string | string[] | number[],
  days?: string | number[],
  hours?: string | number[],
  minutes?: string | number[],
  seconds?: string | number[],
  daysOfTheWeek?: string
): string {
  let cron = "";
  if (seconds) {
    if (Array.isArray(seconds)) {
      cron += seconds.toString().replace(" ", "");
    } else {
      cron += seconds.toString();
    }
  } else {
    cron += "0";
  }
  cron += " ";
  if (minutes) {
    if (Array.isArray(minutes)) {
      cron += minutes.toString().replace(" ", "");
    } else {
      cron += minutes.toString();
    }
  } else {
    cron += "0";
  }
  cron += " ";
  if (hours) {
    if (Array.isArray(hours)) {
      cron += hours.toString().replace(" ", "");
    } else {
      cron += hours.toString();
    }
  } else {
    cron += "0";
  }
  cron += " ";
  if (days) {
    if (Array.isArray(days)) {
      cron += days.toString().replace(" ", "");
    } else {
      cron += days.toString();
    }
  } else {
    cron += "*";
  }
  cron += " ";
  if (months) {
    if (Array.isArray(months)) {
      if (typeof months[0] === "number") {
        const monthStrings: string[] = [];
        months.forEach((month) => {
          monthStrings.push(monthNames[month as number].toString());
        });
        cron += monthStrings.toString().replace(" ", "");
      } else {
        cron += months.toString().replace(" ", "");
      }
    } else {
      cron += months.toString();
    }
  } else {
    cron += "*";
  }
  cron += " ";
  if (daysOfTheWeek) {
    cron += daysOfTheWeek.toString();
  } else {
    cron += "?";
  }
  cron += " ";
  if (years) {
    if (Array.isArray(years)) {
      cron += years.toString().replace(" ", "");
    } else {
      cron += years;
    }
  } else {
    cron += "*";
  }
  return cron;
}
/* Transforms an JS date to a cron string, to trigger ONCE A YEAR on that specific date */
export function dateToCronString(date: Date): string {
  return formatCronString(
    date.getFullYear(),
    monthNames[date.getMonth()],
    [date.getDate()],
    [date.getHours()],
    [date.getMinutes()]
  );
}
/*
 * Transforms a cron expression string into an ISO String.
 * Input for example would be `0 00 11 * * ? *` for every day at 11am.
 * If the input is '*', it will be replaced by 1. (month parameter of '*' becomes January)
 */
export function cronStringToISOString(cronString: string, isUTC: boolean): string | undefined {
  const splStr = cronString.split(" ");
  if (
    !Number.isNaN(Number(splStr[0])) &&
    !Number.isNaN(Number(splStr[1])) &&
    !Number.isNaN(Number(splStr[2])) &&
    (!Number.isNaN(Number(splStr[3])) || splStr[3] == "*")
  ) {
    const year: string = (!Number.isNaN(Number(splStr[6])) ? splStr[6] : new Date().getFullYear()).toString();
    let month: string = "";
    if (splStr[4] != "*") {
      month = monthNames.indexOf(splStr[4]).toString();
    } else {
      month = new Date().getMonth().toString();
    }
    month = month.length == 1 ? "0" + month : month;
    const date: string = splStr[3].length == 1 && splStr[3] != "*" ? "0" + splStr[3] : splStr[3].replace("*", "01");
    const hour: string = splStr[2].length == 1 && splStr[2] != "*" ? "0" + splStr[2] : splStr[2].replace("*", "00");
    const minute: string = splStr[1].length == 1 && splStr[1] != "*" ? "0" + splStr[1] : splStr[1].replace("*", "00");
    const second: string = splStr[0].length == 1 && splStr[0] != "*" ? "0" + splStr[0] : splStr[0].replace("*", "00");
    if (year.length > 0 && month.length > 0) {
      if (isUTC) {
        return moment
          .utc({
            year: Number(year),
            month: Number(month),
            date: Number(date),
            hour: Number(hour),
            minute: Number(minute),
            second: Number(second),
          })
          .toISOString();
      } else {
        return moment({
          year: Number(year),
          month: Number(month),
          date: Number(date),
          hour: Number(hour),
          minute: Number(minute),
          second: Number(second),
        }).toISOString();
      }
    }
  }
  return undefined;
}

export function getMetaValue(
  name: string | NameHolder,
  attribute: Attribute<any> | undefined,
  descriptor?: ValueDescriptorHolder | ValueDescriptor | string
): any | undefined {
  const metaName = typeof name === "string" ? name : (name as NameHolder).name!;

  if (attribute && attribute.meta && attribute.meta.hasOwnProperty(metaName)) {
    return attribute.meta[metaName];
  }

  if (descriptor && (descriptor as MetaHolder).meta) {
    return (descriptor as MetaHolder).meta![metaName];
  }
}

export function hasMetaItem(
  name: string | NameHolder,
  attribute: Attribute<any> | undefined,
  descriptor?: AttributeDescriptor | string
): boolean {
  const metaName = typeof name === "string" ? name : (name as NameHolder).name!;

  if (attribute && attribute.meta && attribute.meta.hasOwnProperty(metaName)) {
    return true;
  }

  if (descriptor && (descriptor as MetaHolder).meta && (descriptor as MetaHolder).meta!.hasOwnProperty(metaName)) {
    return true;
  }

  return false;
}

export function isPrimitiveValueDescriptor(descriptor: ValueDescriptor | undefined): boolean {
  return !isComplexValueDescriptor(descriptor);
}

export function isComplexValueDescriptor(descriptor: ValueDescriptor | undefined): boolean {
  return Boolean(descriptor?.jsonType === "object" || (descriptor?.arrayDimensions && descriptor.arrayDimensions > 0));
}

export function getPrimitiveMetaItems(): MetaItemDescriptor[] {
  return AssetModelUtil.getMetaItemDescriptors().filter((m) =>
    isPrimitiveValueDescriptor(AssetModelUtil.getValueDescriptor(m.type))
  );
}

export function getComplexMetaItems(): MetaItemDescriptor[] {
  return AssetModelUtil.getMetaItemDescriptors().filter((m) =>
    isComplexValueDescriptor(AssetModelUtil.getValueDescriptor(m.type))
  );
}

export function getAssetTypeLabel(type: string | AssetDescriptor | undefined): string {
  if (typeof type === "string") {
    type = AssetModelUtil.getAssetDescriptor(type);
  }
  if (!type) {
    return "";
  }
  return i18next.t("label.asset." + type.name, { defaultValue: camelCaseToSentenceCase(type.name!) });
}

export function getValueDescriptorLabel(descriptor: ValueDescriptor | undefined | string): string {
  if (!descriptor) {
    return i18next.t("label.value.unknown", { defaultValue: "Unknown" });
  }
  const name = typeof descriptor === "string" ? descriptor : descriptor.name;
  return i18next.t("label.value." + name, { defaultValue: camelCaseToSentenceCase(name || "") });
}

export function getAllowedValueLabel(allowedValue: string, fallback?: string): string | undefined {
  if (!allowedValue) {
    return;
  }

  return i18next.t("label.allowedValue." + allowedValue, {
    defaultValue: fallback || camelCaseToSentenceCase(allowedValue || ""),
    nsSeparator: false,
  });
}

export function getMetaItemNameValueHolder(
  metaNameOrDescriptor: MetaItemDescriptor | string,
  value: any
): NameValueHolder<any> {
  const descriptor =
    typeof metaNameOrDescriptor === "string"
      ? AssetModelUtil.getMetaItemDescriptor(metaNameOrDescriptor)!
      : metaNameOrDescriptor;

  if (descriptor) {
    return {
      name: descriptor.name,
      type: descriptor.type,
      value,
    };
  }

  return {
    name: typeof metaNameOrDescriptor,
    type: AssetModelUtil.resolveValueTypeFromValue(value),
    value,
  };
}

export function getAttributeLabel(
  attribute: Attribute<any> | undefined,
  descriptor: AttributeDescriptor | undefined,
  assetType: string | undefined,
  showUnits: boolean,
  fallback?: string
): string {
  return getValueHolderLabel(attribute, descriptor, assetType, showUnits, true, fallback);
}

export function getMetaLabel(
  metaItem: NameValueHolder<any> | undefined,
  descriptor: MetaItemDescriptor | string,
  assetType: string | undefined,
  showUnits: boolean,
  fallback?: string
): string {
  const metaValueHolder = metaItem || getMetaItemNameValueHolder(descriptor, null);
  return getValueHolderLabel(metaValueHolder, descriptor, assetType, showUnits, false, fallback);
}

function getValueHolderLabel(
  nameValueHolder: NameValueHolder<any> | undefined,
  descriptor: AbstractNameValueDescriptorHolder | string | undefined,
  assetType: string | undefined,
  showUnits: boolean,
  isAttribute: boolean,
  fallback?: string
): string {
  let label = doStandardTranslationLookup(
    WellknownMetaItems.LABEL,
    nameValueHolder,
    descriptor,
    assetType,
    isAttribute
  );
  let unitsStr = "";

  if (!label) {
    // Look in meta if it exists
    label = getMetaValue(
      WellknownMetaItems.LABEL,
      nameValueHolder as Attribute<any>,
      descriptor && (descriptor as MetaHolder).meta ? (descriptor as AttributeDescriptor) : undefined
    ) as string;
  }

  if (showUnits) {
    const units: string[] | undefined = getValueFormatConstraintOrUnits(
      WellknownMetaItems.UNITS,
      nameValueHolder,
      descriptor,
      assetType,
      isAttribute
    );
    unitsStr = resolveUnits(units);
  }

  if (!label) {
    const name = nameValueHolder
      ? nameValueHolder.name!
      : descriptor
        ? typeof descriptor === "string"
          ? descriptor
          : descriptor.name
        : undefined;
    label = fallback || (name ? camelCaseToSentenceCase(name) : undefined);
  }

  return label ? label + (unitsStr ? " (" + unitsStr + ")" : "") : unitsStr;
}

export function getAttributeValueAsString(
  attribute: Attribute<any>,
  descriptor: AttributeDescriptor | string | undefined,
  assetType: string | undefined,
  showUnits: boolean,
  fallback?: string
): string {
  return getValueHolderValueAsString(attribute, descriptor, assetType, showUnits, true, fallback);
}

export function getMetaValueAsString(
  metaItem: NameValueHolder<any> | undefined,
  descriptor: MetaItemDescriptor | string,
  assetType: string | undefined,
  showUnits: boolean,
  fallback?: string
): string {
  const metaValueHolder = metaItem || getMetaItemNameValueHolder(descriptor, null);
  return getValueHolderValueAsString(metaValueHolder, descriptor, assetType, showUnits, false, fallback);
}

function getValueHolderValueAsString(
  nameValueHolder: NameValueHolder<any> | undefined,
  descriptor: AbstractNameValueDescriptorHolder | string | undefined,
  assetType: string | undefined,
  showUnits: boolean,
  isAttribute: boolean,
  fallback?: string
): string {
  let valueStr = getValueAsString(
    nameValueHolder ? nameValueHolder.value : undefined,
    () =>
      getValueFormatConstraintOrUnits(WellknownMetaItems.FORMAT, nameValueHolder, descriptor, assetType, isAttribute),
    undefined,
    fallback
  );
  const attrUnits = getAttributeUnits(nameValueHolder, descriptor, assetType);
  if (showUnits && attrUnits && !!attrUnits.length) {
    const units: string[] | undefined = getValueFormatConstraintOrUnits(
      WellknownMetaItems.UNITS,
      nameValueHolder,
      descriptor,
      assetType,
      isAttribute
    );
    valueStr = resolveUnits(units, valueStr);
  }

  return valueStr;
}

export function getValueAsString(
  value: any,
  formatProvider: () => ValueFormat | undefined,
  language?: string,
  fallback?: string
): string {
  let valueStr = "";

  if (value === null || typeof value === "undefined") {
    valueStr = fallback || "";
  } else {
    if (typeof value === "string") {
      valueStr = value;
    } else if (typeof value === "number" || typeof value === "boolean" || value instanceof Date) {
      const format = formatProvider && formatProvider();

      if (format && Object.keys(format).length !== 0) {
        if (typeof value === "number") {
          if (format.asBoolean) {
            value = !!value;
          } else if (format.asDate) {
            // Assume UNIX timestamp in ms
            value = new Date(value);
          }
        } else if (typeof value === "boolean" && format.asNumber) {
          value = value ? 1 : 0;
        }

        switch (typeof value) {
          case "number":
            valueStr = new Intl.NumberFormat(language || i18next.language, format).format(value);
            break;
          case "boolean":
            if (format.asOnOff) {
              valueStr = value ? i18next.t("on") : i18next.t("off");
            }
            if (format.asOpenClosed) {
              valueStr = value ? i18next.t("open") : i18next.t("closed");
            }
            if (format.asPressedReleased) {
              valueStr = value ? i18next.t("pressed") : i18next.t("released");
            }
            break;
          case "object": // Date instance
            // Special handling for some format options
            if (format.momentJsFormat) {
              valueStr = moment(value).format(format.momentJsFormat);
            } else if (format.iso8601) {
              valueStr = value.toISOString();
            } else if (format.week) {
              const weekNo = getWeekNumber(value);
              valueStr =
                format.week === ValueFormatStyleRepresentation.DIGIT_2
                  ? String(weekNo).padStart(2, "0")
                  : Number(weekNo).toString(10);
            } else {
              valueStr = new Intl.DateTimeFormat(language || i18next.language, format as DateTimeFormatOptions).format(
                value
              );
            }
            break;
        }
      } else {
        valueStr = Object(value).toString();
      }
    }
  }

  return valueStr;
}

/**
 * Resolve supplied units using current translation locale; and optionally apply the units to the supplied value (this
 * is useful for units containing currency which in some locales is prefixed to the value e.g. £0.00 kW/hr rather than
 * 0.00 £kW/hr)
 */
export function resolveUnits(units: string[] | undefined, valueStr?: string): string {
  if (!units) {
    return "";
  }
  if (!valueStr) {
    valueStr = "";
  }

  const unitsStr = units
    .map((unit, index) => {
      if (unit.length === 3 && unit.toUpperCase() === unit) {
        // This is a currency code - use Intl API to find the symbol
        const parts = new Intl.NumberFormat(i18next.language, { currency: unit, style: "currency" }).formatToParts();
        // Check whether it goes before or after the value
        if (index === 0 && parts[0].type === "currency") {
          if (valueStr) {
            valueStr = parts[0].value + valueStr;
          } else {
            return parts[0].value;
          }
        } else {
          return parts[0].type === "currency" ? parts[0].value : parts[parts.length - 1].value;
        }
      } else {
        return i18next.t(["units." + unit, "or:units." + unit]);
      }
    })
    .join("");

  return valueStr.length > 0 ? valueStr + " " + unitsStr : unitsStr;
}

/**
 * Looks for {@link ValueConstraint[]} for the specified {@link Attribute} (see {@link getValueFormatConstraintOrUnits})
 */
export function getAttributeValueConstraints(
  attribute: Attribute<any> | undefined,
  descriptor: AttributeDescriptor | string | undefined,
  assetType: string | undefined
): ValueConstraint[] | undefined {
  return getValueFormatConstraintOrUnits(WellknownMetaItems.CONSTRAINTS, attribute, descriptor, assetType, true);
}

/**
 * Looks for {@link ValueConstraint[]} for the specified {@link NameValueHolder} (see {@link getValueFormatConstraintOrUnits})
 */
export function getMetaValueConstraints(
  metaItem: NameValueHolder<any> | undefined,
  descriptor: MetaItemDescriptor | string,
  assetType: string | undefined
): ValueConstraint[] | undefined {
  const metaValueHolder = metaItem || getMetaItemNameValueHolder(descriptor, null);
  return getValueFormatConstraintOrUnits(WellknownMetaItems.CONSTRAINTS, metaValueHolder, descriptor, assetType, false);
}

/**
 * Looks for units string[] for the specified {@link Attribute} (see {@link getValueFormatConstraintOrUnits})
 */
export function getAttributeUnits(
  attribute: Attribute<any> | undefined,
  descriptor: AttributeDescriptor | ValueDescriptor | string | undefined,
  assetType: string | undefined
): string[] | undefined {
  return getValueFormatConstraintOrUnits(WellknownMetaItems.UNITS, attribute, descriptor, assetType, true);
}

/**
 * Looks for units string[] for the specified {@link MetaItem} (see {@link getValueFormatConstraintOrUnits})
 */
export function getMetaUnits(
  metaItem: NameValueHolder<any> | undefined,
  descriptor: MetaItemDescriptor | string,
  assetType: string | undefined
): string[] | undefined {
  const metaValueHolder = metaItem || getMetaItemNameValueHolder(descriptor, null);
  return getValueFormatConstraintOrUnits(WellknownMetaItems.UNITS, metaValueHolder, descriptor, assetType, false);
}

/**
 * Looks for a {@link ValueFormat} for the specified {@link Attribute} (see {@link getValueFormatConstraintOrUnits})
 */
export function getAttributeValueFormat(
  attribute: Attribute<any> | undefined,
  descriptor: AttributeDescriptor | string | undefined,
  assetType: string | undefined
): ValueFormat | undefined {
  return getValueFormatConstraintOrUnits(WellknownMetaItems.FORMAT, attribute, descriptor, assetType, true);
}

/**
 * Looks for a {@see ValueFormat} for the specified {@link MetaItem} (see {@link getValueFormatConstraintOrUnits})
 */
export function getMetaValueFormat(
  metaItem: NameValueHolder<any> | undefined,
  descriptor: MetaItemDescriptor | string,
  assetType: string | undefined
): ValueFormat | undefined {
  const metaValueHolder = metaItem || getMetaItemNameValueHolder(descriptor, null);
  return getValueFormatConstraintOrUnits(WellknownMetaItems.FORMAT, metaValueHolder, descriptor, assetType, false);
}

export function mergeObjects(a: object | undefined, b: object | undefined, mergeArrays: boolean): object {
  if (a && !b) {
    return { ...a };
  }
  if (b && !a) {
    return { ...b };
  }
  const merged = { ...a };
  const path: string[] = [];
  Object.entries(b!).forEach(([k, v]) => {
    mergeObjectKey(merged, path, k, v, mergeArrays);
  });
  return merged;
}

function mergeObjectKey(destination: object, path: string[], key: string, value: any, mergeArrays: boolean) {
  let dest: any = destination;

  path.forEach((p) => {
    if (!dest.hasOwnProperty(p)) {
      dest[p] = {};
    }
    dest = dest[p];
  });

  if (!dest) {
    return;
  }

  if (!dest.hasOwnProperty(key)) {
    if (value === null || value === undefined) {
      delete dest[key];
    } else if (Array.isArray(value)) {
      dest[key] = [...value];
    } else if (typeof value === "object") {
      dest[key] = { ...value };
    } else {
      dest[key] = value;
    }
  } else {
    if (value === undefined || value === null) {
      delete dest[key];
    } else if (Array.isArray(dest[key])) {
      if (mergeArrays) {
        dest[key] = [...dest[key], ...value];
      } else {
        dest[key] = [...value];
      }
    } else if (typeof value === "object") {
      dest[key] = mergeObjects({ ...dest[key] }, value, mergeArrays);
    } else {
      dest[key] = value;
    }
  }
}

/**
 * Looks for the requested {@link ValueFormat}, {@link ValueConstraint[]} or units string[] defined in the translation
 * file in several locations (see {@link doStandardTranslationLookup}).
 * <p>
 * If no value is found in translation files then the standard resolution is used by looking at the nameValueHolder and/or
 * the provided descriptor(s); the resolution order is:
 * {@link NameValueHolder}, {@link ValueDescriptorHolder}, {@link ValueDescriptor}, the first value encountered will be
 * returned; with the exception of {@link ValueFormat} which are merged in reverse priority order.
 */
function getValueFormatConstraintOrUnits<T>(
  lookup: WellknownMetaItems.FORMAT | WellknownMetaItems.UNITS | WellknownMetaItems.CONSTRAINTS,
  nameValueHolder: NameValueHolder<any> | string | undefined,
  descriptor: AbstractNameValueDescriptorHolder | string | undefined,
  assetType: string | undefined,
  isAttribute: boolean
): T | undefined {
  let matched: T | undefined;
  const formats: ValueFormat[] = [];

  const name =
    nameValueHolder && typeof nameValueHolder === "string"
      ? nameValueHolder
      : nameValueHolder
        ? (nameValueHolder as NameHolder).name
        : descriptor
          ? typeof descriptor === "string"
            ? descriptor
            : descriptor.name
          : undefined;
  const str = doStandardTranslationLookup(lookup, name, descriptor, assetType, isAttribute);
  if (str) {
    matched = JSON.parse(str) as T;
    if (matched) {
      if (lookup === WellknownMetaItems.FORMAT) {
        formats.push(matched as ValueFormat);
      } else {
        return matched;
      }
    }
  }

  // Look in meta
  if (nameValueHolder && (nameValueHolder as MetaHolder).meta) {
    matched = getMetaValue(lookup, nameValueHolder as Attribute<any>, descriptor) as T;

    if (matched) {
      if (lookup === WellknownMetaItems.FORMAT) {
        formats.push(matched as ValueFormat);
      } else {
        return matched;
      }
    }
  }

  if (descriptor && typeof descriptor !== "string" && (descriptor as any).hasOwnProperty(lookup)) {
    matched = (descriptor as any)[lookup] as T;
    if (lookup === WellknownMetaItems.FORMAT) {
      formats.push(matched as ValueFormat);
    } else {
      return matched;
    }
  }

  if (descriptor && (descriptor as AbstractNameValueDescriptorHolder).type) {
    const valueDescriptor = AssetModelUtil.getValueDescriptor((descriptor as AbstractNameValueDescriptorHolder).type);
    matched = (valueDescriptor as any)[lookup] as T;
    if (lookup === WellknownMetaItems.FORMAT) {
      formats.push(matched as ValueFormat);
    } else {
      return matched;
    }
  }

  if (lookup !== WellknownMetaItems.FORMAT || formats.length === 0) {
    return matched;
  }

  let mergedFormat: ValueFormat = {};
  formats.reverse().forEach((format) => {
    mergedFormat = { ...mergedFormat, ...format };
  });

  return mergedFormat as T;
}

/**
 * Looks up the requested lookup in several keys, for example lookup=label, name=custom, isAttribute=true, assetType=ThingAsset,
 * descriptorType=number:
 * <ol>
 *     <li>label.attribute.ThingAsset.custom</li>
 *     <li>label.attribute.number.custom</li>
 *     <li>label.attribute.number</li>
 */
function doStandardTranslationLookup(
  lookup:
    WellknownMetaItems.LABEL | WellknownMetaItems.UNITS | WellknownMetaItems.FORMAT | WellknownMetaItems.CONSTRAINTS,
  nameValueHolder: NameValueHolder<any> | string | undefined,
  valueHolderDescriptor: AbstractNameValueDescriptorHolder | string | undefined,
  assetType: string | undefined,
  isAttribute: boolean,
  fallback?: string
): string | undefined {
  // Look in translation files for an override in multiple keys e.g.
  //  format.ThingAsset.[attribute|meta].custom (look for valueFormat where custom is meta item or attribute name)
  //  format.Duration (where Duration is the ValueDescriptor type)
  //  units.BuildingAsset.attribute.temperature
  let name: string | undefined;

  if (nameValueHolder) {
    name = typeof nameValueHolder === "string" ? nameValueHolder : nameValueHolder.name;
  } else if (valueHolderDescriptor) {
    name = typeof valueHolderDescriptor === "string" ? valueHolderDescriptor : valueHolderDescriptor.name;
  }

  if (!name) {
    return;
  }

  const lookups = [];
  const prefix = lookup + "." + (isAttribute ? "attribute" : "meta") + ".";

  if (assetType) {
    lookups.push(prefix + assetType + "." + name);
  }

  if (valueHolderDescriptor && typeof valueHolderDescriptor !== "string") {
    lookups.push(prefix + valueHolderDescriptor.type + "." + name);
    lookups.push(prefix + valueHolderDescriptor.type);
  }

  lookups.push(prefix + name);

  if (lookups.length > 0) {
    return i18next.t(lookups, { defaultValue: fallback || "" });
  }
}

/**
 * Immutable update of an asset using the supplied attribute event
 */
export function updateAsset<T extends Asset>(asset: T, event: AttributeEvent): T {
  const attributeName = event.ref!.name!;

  if (asset.attributes) {
    if (event.deleted) {
      delete asset.attributes![attributeName];
    } else {
      const attribute = asset.attributes[attributeName];
      if (attribute) {
        attribute.value = event.value;
        attribute.timestamp = event.timestamp;
      }
    }
  }

  return Object.assign({}, asset);
}

export function loadJs(url: string) {
  return new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.type = "text/javascript";
    script.src = url;
    script.addEventListener("load", (e) => resolve(e), false);
    script.addEventListener("error", (e) => reject(e), false);
    document.body.appendChild(script);
  });
}

export function sortByNumber<T>(valueExtractor: (item: T) => number): (a: T, b: T) => number {
  return (a, b) => {
    const v1 = valueExtractor(a);
    const v2 = valueExtractor(b);

    if (!v1 && !v2) {
      return 0;
    }
    if (v1 && !v2) {
      return 1;
    }
    if (!v1 && v2) {
      return -1;
    }
    return v1 - v2;
  };
}

export function sortByString<T>(valueExtractor: (item: T) => string): (a: T, b: T) => number {
  return (a, b) => {
    const v1 = valueExtractor(a);
    const v2 = valueExtractor(b);

    if (!v1 && !v2) {
      return 0;
    }
    if (v1 && !v2) {
      return 1;
    }
    if (!v1 && v2) {
      return -1;
    }
    return v1.localeCompare(v2);
  };
}

export interface RequestEventDetail<T> {
  allow: boolean;
  detail: T;
}

export function dispatchCancellableEvent<T>(target: EventTarget, event: CustomEvent<RequestEventDetail<T>>) {
  const deferred = new Deferred<RequestEventDetail<T>>();
  target.dispatchEvent(event);
  window.setTimeout(() => {
    deferred.resolve(event.detail);
  });

  return deferred.promise;
}

export function blobToBase64(blob: Blob) {
  return new Promise((resolve, reject) => {
    const fileReader = new FileReader();
    fileReader.readAsDataURL(blob);

    fileReader.onload = () => {
      resolve(fileReader.result);
    };

    fileReader.onerror = (error) => {
      reject(error);
    };
  });
}

/**
 * Generates a 36 character UUID using the crypto API. https://developer.mozilla.org/en-US/docs/Web/API/Crypto/randomUUID
 * As a fallback, in case an insecure browser context is used, we use `getRandomValues` and change it into UUIDv4.
 */
export function generateUniqueUUID(): string {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  // Fallback for insecure contexts
  return "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) =>
    (+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16)
  );
}

export class AssetQueryHelper implements AssetQuery {
  recursive?: boolean;
  select?: AssetQuerySelect;
  access?: AssetQueryAccess;
  ids?: string[];
  names?: StringPredicate[];
  parents?: ParentPredicate[];
  paths?: PathPredicate[];
  realm?: RealmPredicate;
  userIds?: string[];
  types?: string[];
  attributes?: LogicGroup<AttributePredicate>;
  orderBy?: AssetQueryOrderBy;
  limit?: number;
  offset?: number;

  constructor(query?: AssetQuery) {
    if (query) Object.assign(this, query);
  }

  // Single source of truth for which AssetQuery fields matches() evaluates.
  // getUnsupportedFields derives from this — keep it in sync with matches().
  private static readonly _SUPPORTED_FIELDS = new Set<string>([
    "ids",
    "types",
    "names",
    "parents",
    "paths",
    "realm",
    "attributes",
  ]);

  matches(asset: Asset): boolean {
    if (this.ids?.length && (!asset.id || !this.ids.includes(asset.id))) return false;
    if (this.types?.length && (!asset.type || !this.types.includes(asset.type))) return false;
    if (this.realm?.name && asset.realm !== this.realm.name) return false;
    if (this.parents?.length && !this.parents.some((p) => !p.id || asset.parentId === p.id)) return false;
    if (this.paths?.length) {
      const assetPath = asset.path ?? [];
      if (!this.paths.some((p) => (p.path ?? []).every((id, i) => assetPath[i] === id))) return false;
    }
    if (
      this.names?.length &&
      !this.names.every((p) => AssetQueryHelper._evalValuePredicate(asset.name, p as ValuePredicateUnion))
    )
      return false;
    if (this.attributes) return AssetQueryHelper._evalGroup(asset, this.attributes as LogicGroup<AttributePredicate>);
    return true;
  }

  static getUnsupportedFields(query: AssetQuery): string[] {
    return Object.keys(query).filter((k) => !AssetQueryHelper._SUPPORTED_FIELDS.has(k));
  }

  collectAttributeNames(): string[] {
    return this.attributes ? AssetQueryHelper._collectNames(this.attributes) : [];
  }

  private static _evalValuePredicate(val: unknown, predicate: ValuePredicateUnion): boolean {
    switch (predicate.predicateType) {
      case "string": {
        if (val === null || val === undefined) return false;
        const haystack = predicate.caseSensitive !== false ? String(val) : String(val).toLowerCase();
        const needle = predicate.caseSensitive !== false ? predicate.value : predicate.value?.toLowerCase();
        let m: boolean;
        switch (predicate.match) {
          case "BEGIN":
            m = haystack.startsWith(needle!);
            break;
          case "END":
            m = haystack.endsWith(needle!);
            break;
          case "CONTAINS":
            m = haystack.includes(needle!);
            break;
          default:
            m = haystack === needle;
        }
        return predicate.negate ? !m : m;
      }
      case "boolean":
        return val === predicate.value;
      case "number": {
        if (typeof val !== "number") return false;
        let m: boolean;
        switch (predicate.operator) {
          case "GREATER_THAN":
            m = val > predicate.value!;
            break;
          case "GREATER_EQUALS":
            m = val >= predicate.value!;
            break;
          case "LESS_THAN":
            m = val < predicate.value!;
            break;
          case "LESS_EQUALS":
            m = val <= predicate.value!;
            break;
          case "BETWEEN":
            m = val >= predicate.value! && val <= predicate.rangeValue!;
            break;
          default:
            m = val === predicate.value;
        }
        return predicate.negate ? !m : m;
      }
    }
    return false;
  }

  private static _evalPredicate(asset: Asset, predicate: AttributePredicate): boolean {
    const attrName = predicate.name?.value;
    if (!attrName) return true;
    const attribute = asset.attributes?.[attrName];
    if (!attribute) return !!predicate.negated;
    const matches = predicate.value
      ? AssetQueryHelper._evalValuePredicate(attribute.value, predicate.value as ValuePredicateUnion)
      : true;
    return predicate.negated ? !matches : matches;
  }

  private static _evalGroup(asset: Asset, group: LogicGroup<AttributePredicate>): boolean {
    const operator = group.operator ?? "AND";
    const results: boolean[] = [
      ...(group.items ?? []).map((item) => AssetQueryHelper._evalPredicate(asset, item)),
      ...(group.groups ?? []).map((g) => AssetQueryHelper._evalGroup(asset, g)),
    ];
    if (!results.length) return true;
    return operator === "OR" ? results.some(Boolean) : results.every(Boolean);
  }

  private static _collectNames(group: LogicGroup<AttributePredicate>): string[] {
    return [
      ...(group.items ?? []).map((item) => item.name?.value).filter((n): n is string => !!n),
      ...(group.groups ?? []).flatMap((g) => AssetQueryHelper._collectNames(g)),
    ];
  }
}
