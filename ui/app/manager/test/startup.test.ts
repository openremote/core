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

/**
 * @given The manager app config can't be fetched
 * @when The manager is opened
 * @then The login page is shown
 */
test("should show the login page when the app config can't be fetched", async ({ page, manager }) => {
  await page.route("**/api/master/configuration/manager", (route) => route.abort("connectionreset"));
  await manager.goToRealmStartPage("master");
  // The app was never added to the page, which stayed blank
  await expect(page.getByRole("textbox", { name: "Username or email" })).toBeVisible();
});

/**
 * @given The manager info endpoint answers with an HTML error page
 * @when The manager is opened
 * @then The login page is shown
 */
test("should show the login page when the manager info is not JSON", async ({ page, manager }) => {
  await page.route("**/api/master/info", (route) => route.fulfill(serviceUnavailable));
  await manager.goToRealmStartPage("master");
  // Initialization waited forever for the info, which left the page blank
  await expect(page.getByRole("textbox", { name: "Username or email" })).toBeVisible();
});
