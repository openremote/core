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

import { timeframes } from "./fixtures/data/timeframes";
import { OrAttributeBarChartTest } from "./fixtures/or-attribute-barchart-test.js";

ct.use({ timezoneId: "Europe/Stockholm" });

ct.beforeEach(async ({ shared }) => {
  await shared.locales();
  await shared.fonts();
});

const CHEVRONS = {
  next: 'or-icon[icon="chevron-right"]',
  previous: 'or-icon[icon="chevron-left"]',
};

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

const chart = (start: string, end: string, bars: number) => ({ min: Date.parse(start), max: Date.parse(end), bars });

for (const { name, now, timePrefixKey, timeWindowKey, steps } of timeframes) {
  ct(name, async ({ mount, page }) => {
    await page.clock.setFixedTime(now);
    const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey, timeWindowKey } });

    for (const { click, start, end, bars } of steps) {
      if (click) {
        await component.locator(CHEVRONS[click]).click();
      }
      // The axis used to run between the centers of the first and last bar, so every window lost one interval
      expect(await getChart(component), `${click ?? "mount"} to ${start}`).toEqual(chart(start, end, bars));
    }
  });
}

ct("should shift a custom timeframe by its own length after a calendar preset", async ({ mount }) => {
  const component = await mount(OrAttributeBarChartTest, { props: { timePrefixKey: "this", timeWindowKey: "Day" } });
  await getChart(component);
  await component.evaluate(
    (el: any, { start, end }) => {
      el._isCustomWindow = true;
      el.timeframe = [new Date(start), new Date(end)];
    },
    { start: "2026-01-15T00:00:00+01:00", end: "2026-01-15T03:00:00+01:00" }
  );
  expect(await getChart(component)).toEqual(chart("2026-01-15T00:00:00+01:00", "2026-01-15T03:00:00+01:00", 6));

  await component.locator(CHEVRONS.next).click();
  // A custom timeframe used to move by the selected preset, which is a whole day here
  expect(await getChart(component)).toEqual(chart("2026-01-15T03:00:00+01:00", "2026-01-15T06:00:00+01:00", 6));

  await component.locator(CHEVRONS.previous).click();
  expect(await getChart(component)).toEqual(chart("2026-01-15T00:00:00+01:00", "2026-01-15T03:00:00+01:00", 6));
});
