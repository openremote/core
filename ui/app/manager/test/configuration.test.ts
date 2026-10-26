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

test.use({ storageState: adminStatePath });

/**
 * @given A superuser has selected a new realm logo
 * @when The server rejects the image upload
 * @then The validation reason is shown, configuration is not saved, and the user can retry
 */
test("should keep the configuration editable after an image upload fails", async ({ page, manager }) => {
  let uploadAttempts = 0;
  let configUpdates = 0;
  await page.route("**/api/master/configuration/manager/image?**", (route) => {
    uploadAttempts++;
    return route.fulfill({
      status: 400,
      contentType: "text/plain",
      body: "No image decoder recognizes the uploaded data",
    });
  });
  await page.route("**/api/master/configuration/manager", (route) => {
    if (route.request().method() === "PUT") {
      configUpdates++;
      return route.fulfill({ status: 200, json: {} });
    }
    return route.continue();
  });

  await manager.goToRealmStartPage("master");
  await manager.navigateToMenuItem("Appearance");

  const configuration = page.locator("page-configuration");
  const saveButton = configuration.locator("#save-btn");
  const defaultRealm = configuration.locator("or-conf-realm-card").filter({ hasText: "default" }).first();
  await defaultRealm
    .locator("or-file-uploader input[type=file]")
    .first()
    .setInputFiles({
      name: "invalid.png",
      mimeType: "image/png",
      buffer: Buffer.from("not an image"),
    });
  await expect(saveButton).toBeEnabled();

  await saveButton.click();

  // A rejected upload previously left the page loading indefinitely and hid the validation failure.
  await expect(page.locator("or-mwc-snackbar").last()).toContainText(
    "Failed to upload logo: No image decoder recognizes the uploaded data"
  );
  await expect(saveButton).toBeEnabled();
  expect(configUpdates).toBe(0);

  await saveButton.click();
  await expect.poll(() => uploadAttempts).toBe(2);
  expect(configUpdates).toBe(0);
});

/**
 * @given A superuser has changed map settings and selected new tiles
 * @when Both changes are saved together
 * @then Pending settings are kept and the uploaded tiles determine the center and bounds
 */
test("should retain tile metadata when saving map settings with an upload", async ({ page, manager }) => {
  const calls: string[] = [];
  const newCenter = [10, 20];
  const newBounds = [9, 19, 11, 21];
  const override = "https://example.invalid/style.json";
  let storedConfig = {
    version: 8,
    options: { default: { center: [0, 0], bounds: [-1, -1, 1, 1], zoom: 10 } },
    sources: { vector_tiles: { type: "vector", tiles: [] } },
    layers: [],
    override: undefined as string | undefined,
  };
  await page.route("**/api/master/map", (route) => {
    if (route.request().method() === "PUT") {
      calls.push("settings");
      storedConfig = route.request().postDataJSON();
    }
    return route.fulfill({ json: storedConfig });
  });
  await page.route("**/api/master/map/upload?**", (route) => {
    calls.push("tiles");
    storedConfig.options.default.center = newCenter;
    storedConfig.options.default.bounds = newBounds;
    return route.fulfill({ json: storedConfig });
  });

  await manager.goToRealmStartPage("master");
  await manager.navigateToMenuItem("Appearance");
  const configuration = page.locator("page-configuration");
  const map = configuration.locator("or-conf-map-global");
  await map.locator(".map-style-server-group or-vaadin-text-field input").fill(override);
  await map.locator(".map-style-server-group or-vaadin-text-field input").press("Tab");
  await map.locator("#fileupload-elem").setInputFiles({
    name: "new.mbtiles",
    mimeType: "application/octet-stream",
    buffer: Buffer.from("mock tiles"),
  });
  await configuration.locator("#save-btn").click();

  await expect.poll(() => calls.length).toBe(2);
  // Saving the original settings after the upload used to restore stale center/bounds.
  expect(calls).toEqual(["settings", "tiles"]);
  expect(storedConfig.options.default.center).toEqual(newCenter);
  expect(storedConfig.options.default.bounds).toEqual(newBounds);
  expect(storedConfig.override).toBe(override);
});
