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
import { test, userStatePath } from "./fixtures/manager.js";

test.use({ storageState: userStatePath });

/**
 * @given The manager is opened in a browser, outside the console app
 * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
 * @then The manager loads without requesting the console app config
 */
test("should not request the console app config in a browser", async ({ page, manager }) => {
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/consoleappconfig/")) requests.push(request.url());
  });

  await manager.setup("smartcity");
  await manager.goToRealmStartPage("smartcity");
  await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();

  // Every startup requested the file, which only console app deployments provide, and got a 404
  expect(requests).toEqual([]);
});

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});
