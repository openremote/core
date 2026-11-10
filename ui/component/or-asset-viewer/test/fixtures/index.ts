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
import {
  ct as base,
  expect,
  withPage,
  type SharedComponentTestFixtures,
  type Locator,
  type Page,
} from "@openremote/test";
import type { WellknownMetaItems } from "@openremote/model";
import * as Util from "@openremote/core/lib/util";

export class AssetViewer {
  constructor(private readonly page: Page) {}

  /**
   * Switch between modify mode and view mode.
   * @param targetMode The mode to switch to
   */
  async switchMode(targetMode: "modify" | "view") {
    const selector = this.page.getByRole("button", { name: targetMode });
    await selector.waitFor({ state: "visible" });
    await selector.click();
  }

  /**
   * Get a locator to the asset header.
   * @param assetName The asset name
   */
  getHeaderLocator(assetName: string) {
    return this.page.locator(`#asset-header`, { hasText: assetName });
  }

  /**
   * Get a locator to an attribute.
   * @param attribute The attribute name
   */
  getAttributeLocator(attribute: string): Locator {
    return this.page.getByRole("row", { name: new RegExp(`\\b${attribute}\\b`, "i") });
  }

  /**
   * Get a locator to the specified attribute value input.
   * @param attribute The attribute name
   */
  getAttributeValueLocator(attribute: string | Locator) {
    return (typeof attribute === "string" ? this.getAttributeLocator(attribute) : attribute).locator("input");
  }

  /**
   * Add configuration items to an attribute by its name.
   * @param attribute The attribute's name
   * @param items The configuration items to add
   */
  async addConfigurationItems(attribute: string, ...items: `${WellknownMetaItems}`[]) {
    const configItemLocator = await this.expandAttribute(attribute);
    await configItemLocator.getByRole("button", { name: "Add configuration items" }).click();
    for (const item of items) {
      await this.page
        .getByRole("dialog")
        .getByRole("checkbox", { name: Util.camelCaseToSentenceCase(item), exact: true })
        .check();
    }
    await this.page.getByRole("dialog").getByRole("button", { name: "Add", exact: true }).click();
  }

  /**
   * Remove configuration items to an attribute by its name.
   * @param attribute The attribute's name
   * @param items The configuration items to remove
   */
  async removeConfigurationItems(attribute: string, ...items: `${WellknownMetaItems}`[]) {
    const configItemLocator = await this.expandAttribute(attribute);
    for (const item of items) {
      const byText = this.page.getByText(Util.camelCaseToSentenceCase(item), { exact: true });
      await expect(configItemLocator.filter({ has: byText })).toBeVisible();
      const removeButton = configItemLocator
        .locator("[class=meta-item-wrapper]", { has: byText })
        .locator("button:last-child");
      await removeButton.hover({ force: true });
      await removeButton.click({ force: true });
      await expect(configItemLocator.filter({ has: byText })).not.toBeVisible();
    }
  }

  /**
   * Gets the locator of the attribute configuration item row.
   *
   * If an item is supplied the locator will be narrowed down to the item.
   *
   * @param attribute The attribute name
   * @param item The configuration item to narrow down to
   */
  getConfigurationItemLocator(attribute: string, item?: string): Locator {
    return item
      ? // match the sibling row i.e. the configuration items row of the attribute
        this.getAttributeLocator(attribute).locator("+ tr", { hasText: new RegExp(`\\b${item}\\b`, "i") })
      : this.getAttributeLocator(attribute).locator("+ tr");
  }

  /**
   * Ensure that the attribute is expanded
   * @param attribute The attribute name to ensure to expand.
   * @returns The config item locator that belongs to the attribute.
   */
  async expandAttribute(attribute: string): Promise<Locator> {
    const attributeLocator = this.getAttributeLocator(attribute);
    const configItemLocator = attributeLocator.locator("+ tr");
    if (!(await configItemLocator.getAttribute("class"))?.includes("expanded")) {
      await attributeLocator.getByRole("cell", { name: new RegExp(`\\b${attribute}\\b`, "i") }).click();
      await expect(configItemLocator).toHaveClass(/expanded/);
    }
    return configItemLocator;
  }
}

interface ComponentFixtures extends SharedComponentTestFixtures {
  assetViewer: AssetViewer;
}

export const ct = base.extend<ComponentFixtures>({
  assetViewer: withPage(AssetViewer),
});
