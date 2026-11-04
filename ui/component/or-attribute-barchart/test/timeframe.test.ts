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

import { OrAttributeBarChart } from "@openremote/or-attribute-barchart";

ct.use({ timezoneId: "Europe/Stockholm" });

ct.beforeEach(async ({ shared }) => {
  await shared.locales();
  await shared.fonts();
});

const HOUR = 60 * 60 * 1000;
const NEXT = 'or-icon[icon="chevron-right"]';
const PREVIOUS = 'or-icon[icon="chevron-left"]';

const range = (start: string, end: string) => [new Date(start).toISOString(), new Date(end).toISOString()];

const getTimeframe = (component: Locator) =>
  component.evaluate((el: any) => el.timeframe.map((date: Date) => date.toISOString()));

/**
 * Renders hourly buckets as bars at the center of their bucket, and returns the resulting axis and bars left on it.
 */
const renderBars = (component: Locator, firstBucket: string, buckets: number) =>
  component.evaluate(
    async (el: any, { first, length, hour }) => {
      el._data = [{ type: "bar", data: Array.from({ length }, (_, i) => [first + (i + 0.5) * hour, 1]) }];
      await el.updateComplete;
      const { min, max } = el._chart.getOption().xAxis[0];
      return { min, max, bars: el._chart.getModel().getSeriesByIndex(0).getData().count() };
    },
    { first: Date.parse(firstBucket), length: buckets, hour: HOUR }
  );

ct("should keep the requested period when bars are centered in their buckets", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T10:20:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });

  expect(await renderBars(component, "2026-01-15T00:00:00+01:00", 24)).toEqual({
    min: Date.parse("2026-01-15T00:00:00+01:00"),
    max: Date.parse("2026-01-16T00:00:00+01:00"),
    bars: 24,
  });

  await component.locator(NEXT).click();
  // Navigating used to start from the first and last bar, which shrank the window by one interval
  expect(await getTimeframe(component)).toEqual(range("2026-01-16T00:00:00+01:00", "2026-01-16T23:59:59.999+01:00"));
});

ct("should keep the newest bar in view when its bucket extends past the requested end", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T13:27:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "last", timeWindowKey: "24Hours" } });

  // The bar of the current hour is centered past the requested end, where the axis would filter it out
  expect(await renderBars(component, "2026-01-14T13:00:00+01:00", 25)).toEqual({
    min: Date.parse("2026-01-14T13:00:00+01:00"),
    max: Date.parse("2026-01-15T14:00:00+01:00"),
    bars: 25,
  });

  await component.locator(NEXT).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T13:00:00+01:00", "2026-01-16T13:27:00+01:00"));
});

ct("should keep the oldest bar in view when its bucket starts before the requested start", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T13:40:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "24Hours" } });

  // The first bar is centered before the requested start, where the axis would filter it out
  expect(await renderBars(component, "2026-01-15T01:00:00+01:00", 25)).toEqual({
    min: Date.parse("2026-01-15T01:00:00+01:00"),
    max: Date.parse("2026-01-16T02:00:00+01:00"),
    bars: 25,
  });

  await component.locator(NEXT).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-16T01:40:00+01:00", "2026-01-17T01:40:00+01:00"));
});

ct("should keep a preset window across repeated navigation", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T10:20:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "Hour" } });

  await component.locator(NEXT).click();
  await component.locator(NEXT).click();
  // Deriving the step from the inclusive end of the window used to lose a millisecond on every step
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T12:00:00+01:00", "2026-01-15T12:59:59.999+01:00"));

  await component.locator(PREVIOUS).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T11:00:00+01:00", "2026-01-15T11:59:59.999+01:00"));
});

ct("should keep a fixed window at its elapsed duration across DST", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-03-28T12:00:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "24Hours" } });

  await component.locator(NEXT).click();

  expect(await getTimeframe(component)).toEqual(range("2026-03-29T00:00:00+01:00", "2026-03-30T01:00:00+02:00"));
});

ct("should keep a calendar day whole across DST", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-03-28T12:00:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });

  await component.locator(NEXT).click();

  // A calendar day crossing the spring transition lasts 23 hours
  expect(await getTimeframe(component)).toEqual(range("2026-03-29T00:00:00+01:00", "2026-03-29T23:59:59.999+02:00"));
});

ct("should keep calendar months whole across months of different lengths", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T12:00:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "Month" } });

  await component.locator(NEXT).click();
  expect(await getTimeframe(component)).toEqual(range("2026-02-01T00:00:00+01:00", "2026-02-28T23:59:59.999+01:00"));

  await component.locator(NEXT).click();
  // Adding months clamps the day, which used to leave every later month ending on the 28th
  expect(await getTimeframe(component)).toEqual(range("2026-03-01T00:00:00+01:00", "2026-03-31T23:59:59.999+02:00"));

  await component.locator(PREVIOUS).click();
  await component.locator(PREVIOUS).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-01T00:00:00+01:00", "2026-01-31T23:59:59.999+01:00"));
});

ct("should move a window of several months by whole months", async ({ mount, page }) => {
  await page.clock.setFixedTime("2026-01-15T12:00:00+01:00");
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "6Months" } });

  await component.locator(NEXT).click();

  expect(await getTimeframe(component)).toEqual(range("2026-04-15T12:00:00+02:00", "2026-10-15T12:00:00+02:00"));
});

ct("should shift a custom timeframe by its own length after a calendar preset", async ({ mount }) => {
  const component = await mount(OrAttributeBarChart, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });
  await component.evaluate(
    (el: any, { start, end }) => {
      el._isCustomWindow = true;
      el.timeframe = [new Date(start), new Date(end)];
    },
    { start: "2026-01-15T00:00:00+01:00", end: "2026-01-15T03:00:00+01:00" }
  );

  await component.locator(NEXT).click();
  // A custom timeframe used to move by the selected preset, which is a whole day here
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T03:00:00+01:00", "2026-01-15T06:00:00+01:00"));

  await component.locator(PREVIOUS).click();
  expect(await getTimeframe(component)).toEqual(range("2026-01-15T00:00:00+01:00", "2026-01-15T03:00:00+01:00"));
});
