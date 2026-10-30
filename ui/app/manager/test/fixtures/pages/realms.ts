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
import type { BasePage, Page, Shared } from "@openremote/test";
import type { Manager } from "../manager";

export class RealmsPage implements BasePage {
  constructor(
    private readonly page: Page,
    private readonly shared: Shared,
    private readonly manager: Manager
  ) {}

  async goto() {
    this.manager.navigateToMenuItem("Realms");
  }

  /**
   * Create realm with name if not already present.
   * @param name The realm name
   */
  async addRealm(name: string) {
    const locator = this.page.getByRole("button", { name, exact: true });
    await this.page.getByRole("button", { name: "Master", exact: true }).waitFor();
    if (await locator.isVisible()) {
      console.warn(`Realm "${name}" already present`);
    } else {
      await this.page.click("text=Add Realm");
      const realmRow = this.page.locator("#realm-row-1");
      const realmNameInput = realmRow.getByLabel("Realm");
      const displayNameInput = realmRow.getByLabel("Friendly name");
      await realmNameInput.fill(name);
      await realmNameInput.dispatchEvent("change");
      await displayNameInput.fill(name);
      await displayNameInput.dispatchEvent("change");
      await this.page.getByRole("button", { name: "create" }).click();
    }
  }

  /**
   * Delete a certain realm by its name.
   * @param name The realm's name
   */
  async deleteRealm(realm: string) {
    await this.page.getByRole("cell", { name: realm }).first().click();
    await this.page.getByRole("button", { name: "Delete" }).click();
    await this.page.getByRole("alertdialog").getByRole("textbox", { name: "Realm" }).fill(realm);
    await this.page.getByRole("alertdialog").getByRole("button", { name: "Delete" }).click();
  }
}
