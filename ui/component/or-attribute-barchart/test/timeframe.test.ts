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
import { ct, expect, type Locator } from "@openremote/test";

import { OrAttributeBarChartTest } from "./fixtures/or-attribute-barchart-test.js";

ct.use({ timezoneId: "Europe/Stockholm" });

ct.beforeEach(async ({ shared }) => {
  await shared.locales();
  await shared.fonts();
});

const NEXT = 'or-icon[icon="chevron-right"]';
const PREVIOUS = 'or-icon[icon="chevron-left"]';

const range = (start: string, end: string) => [new Date(start).toISOString(), new Date(end).toISOString()];

const getTimeframe = (component: Locator) =>
  component.evaluate((el: any) => el.timeframe.map((date: Date) => date.toISOString()));

/**
 * Waits for the chart to be on screen, and returns its axis and the bars left on it.
 */
const getChart = async (component: Locator) => {
  await expect(component.locator("#chart canvas").first()).toBeVisible();
  return component.evaluate((el: any) => {
    const { min, max } = el._chart.getOption().xAxis[0];
    return { min, max, bars: el._chart.getModel().getSeriesByIndex(0).getData().count() };
  });
};

const chart = (min: string, max: string, bars: number) => ({ min: Date.parse(min), max: Date.parse(max), bars });

ct("should keep the requested period when bars are centered in their buckets", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T10:20:00+01:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });

  expect(await getChart(component)).toEqual(chart("2026-01-15T00:00:00+01:00", "2026-01-15T23:59:59.999+01:00", 24));

  await component.locator(NEXT).click();
  // The window moves as a whole, not from the centers of its first and last bar
  expect(await getTimeframe(component)).toEqual(range("2026-01-16T00:00:00+01:00", "2026-01-16T23:59:59.999+01:00"));
  expect(await getChart(component)).toEqual(chart("2026-01-16T00:00:00+01:00", "2026-01-16T23:59:59.999+01:00", 24));
});

ct("should end a last window of hours with the current hour", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T13:40:00+01:00");
  const component = await mount(OrAttributeBarChartTest, {
    props: { timePrefixKey: "last", timeWindowKey: "24Hours" },
  });

  expect(await getChart(component)).toEqual(chart("2026-01-14T14:00:00+01:00", "2026-01-15T14:00:00+01:00", 24));

  await component.locator(NEXT).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T14:00:00+01:00", "2026-01-16T14:00:00+01:00"));
  expect(await getChart(component)).toEqual(chart("2026-01-15T14:00:00+01:00", "2026-01-16T14:00:00+01:00", 24));
});

ct("should center a this window of hours on the current hour", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T13:27:00+01:00");
  const component = await mount(OrAttributeBarChartTest, {
    props: { timePrefixKey: "this", timeWindowKey: "24Hours" },
  });

  expect(await getChart(component)).toEqual(chart("2026-01-15T01:00:00+01:00", "2026-01-16T01:00:00+01:00", 24));

  await component.locator(NEXT).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-16T01:00:00+01:00", "2026-01-17T01:00:00+01:00"));
  expect(await getChart(component)).toEqual(chart("2026-01-16T01:00:00+01:00", "2026-01-17T01:00:00+01:00", 24));
});

ct("should anchor a window of minutes to its 5 minute bars", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T13:27:00+01:00");
  const component = await mount(OrAttributeBarChartTest, {
    props: { timePrefixKey: "last", timeWindowKey: "60Minutes" },
  });

  expect(await getChart(component)).toEqual(chart("2026-01-15T12:30:00+01:00", "2026-01-15T13:30:00+01:00", 12));
});

ct("should anchor a window of days to the start of the day", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T13:40:00+01:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "7Days" } });

  // Daily bars are aligned to UTC, so the one overlapping the first hour of the window is centered before it
  expect(await getChart(component)).toEqual(chart("2026-01-12T00:00:00+01:00", "2026-01-19T00:00:00+01:00", 7));

  await component.locator(NEXT).click();
  expect(await getChart(component)).toEqual(chart("2026-01-19T00:00:00+01:00", "2026-01-26T00:00:00+01:00", 7));
});

ct("should keep hourly bars on a calendar day of 25 hours", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-10-24T12:00:00+02:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });

  expect(await getChart(component)).toEqual(chart("2026-10-24T00:00:00+02:00", "2026-10-24T23:59:59.999+02:00", 24));

  await component.locator(NEXT).click();
  expect(await getChart(component)).toEqual(chart("2026-10-25T00:00:00+02:00", "2026-10-25T23:59:59.999+01:00", 25));

  await component.locator(NEXT).click();
  expect(await getChart(component)).toEqual(chart("2026-10-26T00:00:00+01:00", "2026-10-26T23:59:59.999+01:00", 24));
});

ct("should keep daily bars on a month of 745 hours", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-09-15T12:00:00+02:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Month" } });

  expect(await getChart(component)).toEqual(chart("2026-09-01T00:00:00+02:00", "2026-09-30T23:59:59.999+02:00", 30));

  await component.locator(NEXT).click();

  expect(await getChart(component)).toEqual(chart("2026-10-01T00:00:00+02:00", "2026-10-31T23:59:59.999+01:00", 31));
});

ct("should keep weekly bars on a leap year", async ({ mount, page }) => {
  await page.clock.setFixedTime("2027-06-15T12:00:00+02:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Year" } });

  expect(await getChart(component)).toEqual(chart("2027-01-01T00:00:00+01:00", "2027-12-31T23:59:59.999+01:00", 52));

  await component.locator(NEXT).click();

  expect(await getChart(component)).toEqual(chart("2028-01-01T00:00:00+01:00", "2028-12-31T23:59:59.999+01:00", 52));
});

ct("should keep a preset window across repeated navigation", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T10:20:00+01:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Hour" } });

  await component.locator(NEXT).click();
  await component.locator(NEXT).click();
  // The step is the selected window, as its inclusive end is a millisecond short of it
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T12:00:00+01:00", "2026-01-15T12:59:59.999+01:00"));

  await component.locator(PREVIOUS).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T11:00:00+01:00", "2026-01-15T11:59:59.999+01:00"));
});

ct("should keep a fixed window at its elapsed duration across DST", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-03-28T12:00:00+01:00");
  const component = await mount(OrAttributeBarChartTest, {
    props: { timePrefixKey: "this", timeWindowKey: "24Hours" },
  });

  await component.locator(NEXT).click();

  expect(await getTimeframe(component)).toEqual(range("2026-03-29T00:00:00+01:00", "2026-03-30T01:00:00+02:00"));
});

ct("should keep a calendar day whole across DST", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-03-28T12:00:00+01:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });

  await component.locator(NEXT).click();

  // A calendar day crossing the spring transition lasts 23 hours
  expect(await getTimeframe(component)).toEqual(range("2026-03-29T00:00:00+01:00", "2026-03-29T23:59:59.999+02:00"));
});

ct("should keep calendar months whole across months of different lengths", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T12:00:00+01:00");
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Month" } });

  await component.locator(NEXT).click();
  expect(await getTimeframe(component)).toEqual(range("2026-02-01T00:00:00+01:00", "2026-02-28T23:59:59.999+01:00"));

  await component.locator(NEXT).click();
  // Adding months clamps the end to the 28th, so it has to be extended to the end of March again
  expect(await getTimeframe(component)).toEqual(range("2026-03-01T00:00:00+01:00", "2026-03-31T23:59:59.999+02:00"));

  await component.locator(PREVIOUS).click();
  await component.locator(PREVIOUS).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-01T00:00:00+01:00", "2026-01-31T23:59:59.999+01:00"));
});

ct("should move a window of several months by whole months", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T12:00:00+01:00");
  const component = await mount(OrAttributeBarChartTest, {
    props: { timePrefixKey: "this", timeWindowKey: "6Months" },
  });

  await component.locator(NEXT).click();

  expect(await getTimeframe(component)).toEqual(range("2026-04-01T00:00:00+02:00", "2026-10-01T00:00:00+02:00"));
});

ct("should shift a custom timeframe by its own length after a calendar preset", async ({ mount }) => {
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });
  await component.evaluate(
    (el: any, { start, end }) => {
      el._isCustomWindow = true;
      el.timeframe = [new Date(start), new Date(end)];
    },
    { start: "2026-01-15T00:00:00+01:00", end: "2026-01-15T03:00:00+01:00" }
  );

  await component.locator(NEXT).click();
  // A custom timeframe moves by its own length, not by the selected window of a whole day
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T03:00:00+01:00", "2026-01-15T06:00:00+01:00"));

  await component.locator(PREVIOUS).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T00:00:00+01:00", "2026-01-15T03:00:00+01:00"));
});
