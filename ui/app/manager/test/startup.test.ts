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
import { expect } from "@openremote/test";
import { test } from "./fixtures/manager.js";

// What a reverse proxy answers while the manager restarts
const serviceUnavailable = {
  status: 503,
  contentType: "text/html",
  body: "<html><body><h1>503 Service Unavailable</h1></body></html>",
};

// Startup retries back off for up to a few seconds, so the next attempt can take a while
const afterRetry = { timeout: 15_000 };

/**
 * @given The manager app config can't be fetched while the manager restarts
 * @when The manager is opened
 * @then A loading spinner is shown
 * @and The login page is shown once the config can be fetched again
 */
test("should show the login page once the app config can be fetched again", async ({ page, manager }) => {
  let restarting = true;
  await page.route("**/api/master/configuration/manager", (route) =>
    restarting ? route.abort("connectionreset") : route.fallback()
  );
  await manager.goToRealmStartPage("master");
  // The app was never added to the page, which stayed blank
  await expect(page.getByRole("progressbar")).toBeVisible();

  restarting = false;
  await expect(page.getByRole("textbox", { name: "Username or email" })).toBeVisible(afterRetry);
});

/**
 * @given The manager info endpoint answers with the proxy's error page while the manager restarts
 * @when The manager is opened
 * @then A loading spinner is shown
 * @and The login page is shown once the manager answers again
 */
test("should show the login page once the manager info is available again", async ({ page, manager }) => {
  let restarting = true;
  await page.route("**/api/master/info", (route) =>
    restarting ? route.fulfill(serviceUnavailable) : route.fallback()
  );
  await manager.goToRealmStartPage("master");
  // Initialization waited forever for the info, which left the page blank
  await expect(page.getByRole("progressbar")).toBeVisible();

  restarting = false;
  await expect(page.getByRole("textbox", { name: "Username or email" })).toBeVisible(afterRetry);
});

/**
 * @given The manager stays unreachable
 * @when The manager is opened
 * @then A message says that the app could not be loaded
 */
test("should show that the app could not be loaded when the manager stays unreachable", async ({ page, manager }) => {
  await page.clock.install();
  await page.route("**/api/master/info", (route) => route.fulfill(serviceUnavailable));
  await manager.goToRealmStartPage("master");

  // The retries give up after 45 s, so skip ahead instead of waiting for them
  await expect
    .poll(
      async () => {
        await page.clock.fastForward(10_000);
        return page.getByRole("alert").isVisible();
      },
      { timeout: 20_000 }
    )
    .toBe(true);
  await expect(page.getByRole("alert")).toHaveText("Could not load the app, try again later");
});
