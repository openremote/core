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
import type { Request } from "@playwright/test";
import { expect } from "@openremote/test";
import { adminStatePath, test } from "./fixtures/manager.js";

// Run with `BENCHMARK=1 npx playwright test benchmark --project chromium`
test.skip(!process.env.BENCHMARK, "Measures how long the manager takes to start, set BENCHMARK=1 to run it");
test.use({ storageState: adminStatePath });

const runs = Number(process.env.BENCHMARK_RUNS ?? 5);
// A slow mobile connection by default, BENCHMARK_THROTTLE=0 measures without
const throttled = process.env.BENCHMARK_THROTTLE !== "0";

type Measurement = { millis: number; pageLoads: number; requests: number };

const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];

/**
 * @given The user is signed in
 * @when The manager is opened several times, with an empty and with a filled browser cache
 * @then The time until the first page shows is reported
 */
test("should report how long the manager takes to start", async ({ page, manager }) => {
  test.setTimeout(runs * 2 * 60_000);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send("Network.enable");
  if (throttled) {
    await cdp.send("Network.emulateNetworkConditions", {
      offline: false,
      latency: 150,
      downloadThroughput: 4_000_000 / 8,
      uploadThroughput: 1_000_000 / 8,
    });
  }

  const measure = async (): Promise<Measurement> => {
    let pageLoads = 0;
    let requests = 0;
    const count = (request: Request) => {
      requests++;
      if (request.isNavigationRequest() && request.frame() === page.mainFrame()) {
        pageLoads++;
      }
    };
    page.on("request", count);
    const start = Date.now();
    await manager.goToRealmStartPage("master");
    await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible({ timeout: 60_000 });
    const millis = Date.now() - start;
    page.off("request", count);
    return { millis, pageLoads, requests };
  };

  const cold: Measurement[] = [];
  const warm: Measurement[] = [];
  for (let run = 0; run < runs; run++) {
    await cdp.send("Network.clearBrowserCache");
    cold.push(await measure());
    warm.push(await measure());
  }

  const summary = Object.entries({ cold, warm })
    .map(
      ([cache, measurements]) =>
        `${cache} cache: median ${median(measurements.map(({ millis }) => millis))} ms` +
        ` (${measurements.map(({ millis }) => millis).join(", ")}),` +
        ` ${median(measurements.map(({ pageLoads }) => pageLoads))} page loads,` +
        ` ${median(measurements.map(({ requests }) => requests))} requests`
    )
    .join("\n");
  const connection = throttled ? "4 Mbit/s down, 1 Mbit/s up, 150 ms latency" : "unthrottled";
  console.log(`Startup over ${runs} runs, ${connection}\n${summary}`);
  test.info().annotations.push({ type: "startup", description: `${connection}\n${summary}` });
});
