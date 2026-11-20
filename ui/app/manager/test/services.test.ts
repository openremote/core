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
import { adminStatePath, test } from "./fixtures/manager.js";
import { getRealmService } from "./fixtures/data/services.js";

test.use({ storageState: adminStatePath });

/**
 * @given a service is registered in the "master" realm and another in the "smartcity" realm
 * @when Logging into OpenRemote "master" realm as "admin"
 * @and Navigating to the "Services" page
 * @and Switching to the "smartcity" realm and back to the "master" realm
 * @then the page should show the service of the "master" realm again
 */
test("should show the services of a realm again when switching back to it", async ({ page, manager }) => {
  await page.route("**/service/global", (route) => route.fulfill({ json: [] }));
  await page.route(
    (url) => url.pathname.endsWith("/service") && url.searchParams.has("realm"),
    (route) => route.fulfill({ json: [getRealmService(new URL(route.request().url()).searchParams.get("realm")!)] })
  );
  const masterService = page.getByText(getRealmService("master").label!);
  const smartCityService = page.getByText(getRealmService("smartcity").label!);

  await manager.goToRealmStartPage("master");
  await manager.navigateToTab("Services");
  await expect(masterService).toBeVisible();

  await manager.switchToRealmByRealmPicker("smartcity");
  await expect(smartCityService).toBeVisible();
  await expect(masterService).not.toBeVisible();

  await manager.switchToRealmByRealmPicker("master");
  // Used to keep the "smartcity" service, as the page only reacted to the first switch to each realm
  await expect(masterService).toBeVisible();
  await expect(smartCityService).not.toBeVisible();
});
