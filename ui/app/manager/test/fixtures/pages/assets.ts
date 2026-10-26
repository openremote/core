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
import { type BasePage, type Page, type Shared, expect } from "@openremote/test";
import type { Manager } from "../manager";
import type { Asset } from "@openremote/model";

export class AssetsPage implements BasePage {
  constructor(
    private readonly page: Page,
    private readonly shared: Shared,
    private readonly manager: Manager
  ) {}

  async goto() {
    this.manager.navigateToTab("Assets");
  }

  /**
   * Navigate to an asset's page, as when opening its URL in a new tab.
   */
  async gotoAssetId(realm: string, id: string, editor = false) {
    await this.page.goto(this.manager.getAppUrl(realm) + `#/assets/${editor}/${id}`);
  }

  /**
   * Add asset of type and with name.
   *
   * Internally registers the asset for cleanup.
   *
   * @param type The asset type
   * @param name The name of the asset
   */
  async addAsset(type: string, name: string) {
    await this.page.click(".mdi-plus");
    await this.page.getByRole("option", { name: type }).click();
    await this.page.locator("#name-input").getByRole("textbox").fill(name);
    await this.shared.interceptResponse<Asset>("**/asset", (asset) => {
      if (asset) this.manager.assets.push(asset);
    });
    await this.page.click("#add-btn");
  }

  /**
   * Delete an asset by its name.
   * @param manager The manager instance
   * @param asset The asset name
   * @param page The page or locator to search from
   */
  async deleteSelectedAsset(manager: Manager, asset: string, locator?: any) {
    const assetLocator = locator ?? this.page.locator(`text="${asset}"`);
    await expect(assetLocator).toHaveCount(1);
    await assetLocator.click();
    await this.page.click(".mdi-delete");
    await this.page.getByRole("button", { name: "Delete" }).click();
    await expect(assetLocator).toHaveCount(0);
    manager.assets = manager.assets.filter((a) => a.name !== asset); // Remove asset from cache as well
  }
}
