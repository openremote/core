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
 * @given Logged in to the "smartcity" realm in modify mode
 * @when Adding an asset without saving it
 * @then Import is disabled until the asset is saved
 */
test("should disable import on an asset that is not saved yet", async ({ page, manager, assetsPage }) => {
  await page.goto(`${manager.getAppUrl("smartcity")}#/assets/true`);
  await assetsPage.addAsset("Weather Asset", "Unsaved weather");

  const importButton = page.getByRole("button", { name: "Import", exact: true });
  // Import used to be enabled here and fail with a generic error, as an import is checked against the stored asset
  await expect(importButton).toBeDisabled();
  await expect(importButton).toHaveAttribute("title", "Save the asset before importing");
});
