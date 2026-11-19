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
import assets, { commonAttrs, thing, weather } from "./fixtures/data/assets.js";
import { AssetModelUtil, type WellknownMetaItems } from "@openremote/model";
// `@openremote/core` depends on or-icon and isn't supported in nodejs
import * as Util from "@openremote/core/lib/util";

test.use({ storageState: userStatePath });

/**
 * @given Logged in to OpenRemote "smartcity" realm as "smartcity"
 * @when Navigating to the "asset" tab
 * @and Creating a weather asset with the given name
 * @and Opening the asset's detail page
 * @and Switching to modify mode
 * @and Setting values for specified attributes
 * @and Saving the changes
 * @then The asset with the given name should be visible in the UI
 */
test("Add new Weather asset and configure its attributes", async ({ page, manager, assetsPage, assetViewer }) => {
  await manager.goToRealmStartPage("smartcity");
  await assetsPage.goto();
  await assetsPage.addAsset("Weather Asset", "Weather");
  await page.click(`#list-container >> text=Weather`);
  await assetViewer.switchMode("modify");
  await assetViewer.getAttributeValueLocator("temperature").fill("30");
  await assetViewer.getAttributeValueLocator("humidity").fill("50");
  const saveBtn = page.getByRole("button", { name: "Save" });
  await saveBtn.click();
  await expect(saveBtn).toBeDisabled();
  await expect(page.locator(`text=Weather`)).toHaveCount(1);
});

/**
 * @given A weather asset is set up in the "smartcity" realm
 * @when Logging in to the OpenRemote "smartcity" realm
 * @and Navigating to the "asset" tab
 * @and Selecting the asset by name
 * @and Updating a specific attribute with a new value
 * @and Switching to modify mode
 * @and Updating the asset's location via map click
 * @and Saving the changes
 * @then The updated asset is saved and changes are persisted
 */
test("Update a Weather asset's attributes and location", async ({ page, manager, assetViewer }) => {
  await manager.setup("smartcity", { assets: [weather] });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("asset");
  await page.click(`text=Weather`);

  const item = page.locator(`#field-rainfall input[type="number"]`);
  if (await item.isEditable()) {
    await item.fill("70");
    await page.click(`#field-rainfall #send-btn`);
  }

  await assetViewer.switchMode("modify");
  await assetViewer.getAttributeLocator("location").getByRole("button").click();
  await page.mouse.click(705, 210, { delay: 1000 });
  await page.getByRole("button", { name: "OK" }).click();

  const saveBtn = page.getByRole("button", { name: "Save" });
  await saveBtn.click();
  await expect(saveBtn).toBeDisabled();
});

/**
 * @given A weather asset is set up in the "smartcity" realm
 * @when Logging in to the OpenRemote "smartcity" realm
 * @and Navigating to the "asset" tab
 * @and Selecting the asset by name
 * @and Switching to modify mode
 * @and Toggling read-only status for two attributes
 * @and Saving the changes
 * @and Navigating to the asset's view panel
 * @then The correct read-only indicators should be present for the attributes
 */
test("Toggle read-only for two attributes on a Weather asset", async ({ page, manager, assetViewer }) => {
  await manager.setup("smartcity", { assets: [weather] });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("asset");
  await page.click(`text=Weather`);

  await assetViewer.switchMode("modify");
  await page.getByRole("button", { name: "Expand all" }).click();

  await assetViewer.getAttributeLocator("temperature").click();
  await assetViewer.getConfigurationItemLocator("temperature", "Read only").locator("label").click();

  await assetViewer.getAttributeLocator("humidity").click();
  await assetViewer.getConfigurationItemLocator("humidity", "Read only").locator("label").click();

  const saveBtn = page.getByRole("button", { name: "Save" });
  await saveBtn.click();
  await expect(saveBtn).toBeDisabled();

  await page.getByRole("button", { name: "View" }).click();
  await expect(page.getByRole("button", { name: "Modify" })).toBeVisible();

  await expect(page.locator(`#field-temperature #send-btn`)).toBeVisible();
  await expect(page.locator(`#field-humidity #send-btn`)).not.toBeVisible();
});

/**
 * @given A weather asset is set up in the "smartcity" realm
 * @when Logging in to the OpenRemote "smartcity" realm
 * @and Navigating to the "asset" tab
 * @and Selecting the asset by name
 * @and Switching to modify mode
 * @and Selecting configuration items like "ruleState" and "storeDataPoints" for two attributes
 * @and Saving the changes
 * @then The configuration items are persisted correctly
 */
test(`Set "ruleState" and "storeDataPoints" for Weather asset attributes`, async ({ page, manager, assetViewer }) => {
  await manager.setup("smartcity", { assets: [weather] });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("asset");
  await page.click(`text=Weather`);

  await assetViewer.switchMode("modify");
  await page.getByRole("button", { name: "Expand all" }).click();

  await assetViewer.addConfigurationItems("temperature", "ruleState", "storeDataPoints");
  await assetViewer.addConfigurationItems("humidity", "ruleState", "storeDataPoints");

  const saveBtn = page.getByRole("button", { name: "Save" });
  await saveBtn.click();
  await expect(saveBtn).toBeDisabled();
});

/**
 * @given Assets are set up in the "smartcity" realm
 * @when Logging in to OpenRemote "smartcity" realm as admin
 * @and Navigating to the "Assets" tab
 * @and Deleting the assets "Battery" and "Solar Panel"
 * @then The asset list should no longer show the deleted assets
 * @and The asset column should appear empty (showing "Console")
 */
test("Delete specified assets and verify they are removed", async ({ page, manager, assetsPage }) => {
  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Assets");
  await assetsPage.deleteSelectedAsset(manager, "Battery");
  await assetsPage.deleteSelectedAsset(manager, "Solar Panel");
  await expect(page.locator("text=Console")).toHaveCount(1);
  await expect(page.locator("text=Solar Panel")).toHaveCount(0);
  await expect(page.locator("text=Battery")).toHaveCount(0);
});

test.describe("Parent asset", () => {
  test.beforeEach(async ({ manager }) => {
    await manager.setup("smartcity", {
      assets: [
        {
          name: "Parent",
          type: "ThingAsset",
          realm: "smartcity",
          attributes: { ...commonAttrs },
        },
      ],
    });
    await manager.goToRealmStartPage("smartcity");
  });

  /**
   * @given A thing (and parent) asset is set up in the "smartcity" realm
   * @when Login to OpenRemote "smartcity" realm as "smartcity"
   * @and Go to the asset page
   * @and Modify the thing asset
   * @and Add a parent asset called "Parent"
   * @then The parent name should be set
   */
  test("can be added", async ({ manager, page, assetViewer }) => {
    await manager.createAsset(thing);
    await manager.navigateToTab("Assets");
    await page.getByText("Thing").click();
    await assetViewer.switchMode("modify");

    await page.getByText("Parent Edit").getByRole("button").click();
    await page.getByRole("dialog").getByText("Parent").click();
    await page.getByRole("dialog").getByRole("button", { name: "OK" }).click();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(page.getByRole("textbox", { name: "Parent" })).toHaveValue("Parent");
  });

  /**
   * @given A thing (with parent) asset is set up in the "smartcity" realm
   * @when Login to OpenRemote "smartcity" realm as "smartcity"
   * @and Go to the asset page
   * @and Modify the thing asset
   * @and Remove the "Parent" parent asset
   * @then The parent name should not be set
   */
  test("can be removed", async ({ manager, page, assetViewer, assetTree }) => {
    await manager.createAsset({ ...thing, parentId: manager.assets[0].id });
    await manager.navigateToTab("Assets");
    await assetTree.getFilterInput().fill("Thing");
    await assetViewer.switchMode("modify");

    await page.getByText("Parent Edit").getByRole("button").click();
    await page.getByRole("dialog").getByRole("button", { name: "Clear" }).click();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(page.getByRole("textbox", { name: "Parent" })).toBeEmpty();
  });
});

test.describe("Attributes", () => {
  /**
   * @given A thing asset is set up in the "smartcity" realm
   * @when Login to OpenRemote "smartcity" realm as "smartcity"
   * @and Go to the asset page
   * @and Modify the thing asset
   * @and Add an attribute called "test"
   * @then The attribute should be visible
   * @and be of type "Integer"
   */
  test("can be added", async ({ page, mwcInput, assetViewer, manager }) => {
    await manager.setup("smartcity", { assets: [thing] });
    await manager.goToRealmStartPage("smartcity");
    await manager.navigateToTab("Assets");
    await page.getByText("Thing").click();
    await assetViewer.switchMode("modify");

    await page.getByRole("button", { name: "Add attribute" }).click();

    const dialog = page.getByRole("dialog");
    await dialog.getByLabel("Name").fill("test");
    await dialog.getByRole("combobox", { name: "Value type", exact: true }).fill("Int");
    await dialog.getByRole("option", { name: "Integer", exact: true }).click();
    await dialog.getByRole("button", { name: "Add", exact: true }).click();
    await page.getByRole("button", { name: "Save", exact: true }).click();

    await expect(assetViewer.getAttributeLocator("test")).toBeVisible();
    await expect(assetViewer.getAttributeLocator("test")).toContainText(/Integer/);
    await expect(assetViewer.getAttributeLocator("test").getByRole("spinbutton")).toBeVisible();
  });

  /**
   * @given A thing asset is set up in the "smartcity" realm
   * @when Login to OpenRemote "smartcity" realm as "smartcity"
   * @and Go to the asset page
   * @and Modify the thing asset
   * @and Remove the attribute called "test"
   * @then The attribute should not be visible
   */
  test("can be removed", async ({ page, assetViewer, manager }) => {
    await manager.setup("smartcity");
    await manager.createAsset({
      name: "Thing",
      type: "ThingAsset",
      realm: "smartcity",
      attributes: { ...commonAttrs, test: { name: "test", type: "integer" } },
    });
    await manager.goToRealmStartPage("smartcity");
    await manager.navigateToTab("Assets");
    await page.getByText("Thing").click();
    await assetViewer.switchMode("modify");

    const attribute = assetViewer.getAttributeLocator("test");
    await expect(attribute).toBeVisible();
    await attribute.getByRole("button").last().click();
    await page.getByRole("button", { name: "Save" }).click();

    await expect(attribute).not.toBeVisible();
  });
});

test.describe("Configuration items", () => {
  test.beforeEach(async ({ manager }) => {
    await manager.initAssetModel();
  });

  /**
   * @given A thing asset is set up in the "smartcity" realm
   * @when Login to OpenRemote "smartcity" realm as "smartcity"
   * @and Go to the asset page
   * @and Modify the thing asset
   * @and Adding configuration items
   * @then The configuration items to be visible
   */
  test("can be added", async ({ page, assetViewer, manager }) => {
    await manager.setup("smartcity", { assets: [thing] });
    await manager.goToRealmStartPage("smartcity");
    await manager.navigateToTab("Assets");
    await page.getByText("Thing").click();
    await assetViewer.switchMode("modify");

    const configurationItems = AssetModelUtil.getMetaItemDescriptors().map(
      ({ name }) => name as `${WellknownMetaItems}`
    );
    await assetViewer.addConfigurationItems("notes", ...configurationItems);
    for (const item of configurationItems) {
      await expect(assetViewer.getConfigurationItemLocator("notes", Util.camelCaseToSentenceCase(item))).toBeVisible();
    }
  });

  test.describe(() => {
    let primitiveItemsWithValues: [`${WellknownMetaItems}`, unknown][];
    // let complexItemsWithValues: [`${WellknownMetaItems}`, unknown][];

    function valueForType(
      type: unknown,
      dimensions = 0,
      { string, number, boolean, object } = { string: "", number: 0, boolean: false, object: {} }
    ): unknown {
      if (dimensions > 0) {
        return [valueForType(type, 0, { string, number, boolean, object })];
      }
      switch (type) {
        case "string":
          return string;
        case "bigint":
        case "number":
          return number;
        case "boolean":
          return boolean;
        case "object":
          return object;
      }
    }

    /**
     * @given A thing asset is set up in the "smartcity" realm with all meta items
     * @when Login to OpenRemote "smartcity" realm as "smartcity"
     * @and Go to the asset page
     * @and Modify the thing asset
     */
    test.beforeEach(async ({ assetViewer, manager, page }) => {
      // We can safely ascribe defaults inline with their json type as these descriptors are primitives without format
      primitiveItemsWithValues = Util.getPrimitiveMetaItems().map((m) => {
        const { jsonType } = AssetModelUtil.getValueDescriptor(m.type)!;
        return [m.name, valueForType(jsonType, 0, { boolean: true, number: 0, string: "", object: {} })];
      }) as [`${WellknownMetaItems}`, any][];
      // TODO: resolve complex meta item values through their JSON Schema
      // complexItemsWithValues = Util.getComplexMetaItems().map((m) => {
      //     const { jsonType, arrayDimensions } = AssetModelUtil.getValueDescriptor(m.type)!;
      //     return [m.name, valueForType(jsonType, arrayDimensions)];
      // }) as [`${WellknownMetaItems}`, any][];
      await manager.setup("smartcity");
      await manager.createAsset({
        name: "Thing",
        type: "ThingAsset",
        realm: "smartcity",
        attributes: {
          ...commonAttrs,
          notes: { meta: Object.fromEntries([...primitiveItemsWithValues /*, ...complexItemsWithValues */]) },
        },
      });
      await manager.goToRealmStartPage("smartcity");
      await manager.navigateToTab("Assets");
      await page.getByText("Thing").click();
      await assetViewer.switchMode("modify");
    });

    /**
     * @when Modifying configuration items
     * @then The configuration items should be visible
     */
    test("can be modified", async ({ page, assetViewer }) => {
      const updatedPrimitivesWithValues = Util.getPrimitiveMetaItems().map((m) => {
        const { jsonType } = AssetModelUtil.getValueDescriptor(m.type)!;
        return [m.name, valueForType(jsonType, 0, { boolean: false, number: 7, string: "test", object: {} })];
      }) as [`${WellknownMetaItems}`, any][];
      await assetViewer.expandAttribute("notes");

      // Modify primitive configuration items
      for (const [item, value] of updatedPrimitivesWithValues) {
        const itemLocator = assetViewer.getConfigurationItemLocator("notes");
        const options = { name: Util.camelCaseToSentenceCase(item) };
        if (typeof value === "string") {
          await itemLocator.getByRole("textbox", options).fill(value);
        } else if (typeof value === "number") {
          await itemLocator.getByRole("spinbutton", options).fill(`${value}`);
        } else if (value) {
          await itemLocator.getByRole("checkbox", options).uncheck();
        } else {
          await itemLocator.getByRole("checkbox", options).check();
        }
      }

      await page.getByRole("button", { name: "Save" }).click();

      for (const [item, value] of updatedPrimitivesWithValues) {
        const itemLocator = assetViewer.getConfigurationItemLocator("notes");
        const options = { name: Util.camelCaseToSentenceCase(item) };
        if (typeof value === "string") {
          await expect(itemLocator.getByRole("textbox", options)).toHaveValue(value);
        } else if (typeof value === "number") {
          await expect(itemLocator.getByRole("spinbutton", options)).toHaveValue(`${value}`);
        } else if (value) {
          await expect(itemLocator.getByRole("checkbox", options)).not.toBeChecked();
        } else {
          await expect(itemLocator.getByRole("checkbox", options)).toBeChecked();
        }
      }
      // Modify complex configuration items
      // jsonForms.walkForm();
    });

    /**
     * @when Removing configuration items
     * @then The configuration items should not be visible
     */
    test("can be removed", async ({ page, assetViewer }) => {
      // Remove primitive configuration items
      await assetViewer.removeConfigurationItems("notes", ...primitiveItemsWithValues.map(([item]) => item));
      // Remove complex configuration items
      // await assetViewer.removeConfigurationItems("notes", ...complexItemsWithValues.map(([item]) => item));

      await page.getByRole("button", { name: "Save" }).click();

      const configurationItems = AssetModelUtil.getMetaItemDescriptors().map(
        ({ name }) => name as `${WellknownMetaItems}`
      );
      for (const item of configurationItems) {
        await expect(
          assetViewer.getConfigurationItemLocator("notes", Util.camelCaseToSentenceCase(item))
        ).not.toBeVisible();
      }
    });

    test.afterEach(async ({ manager }) => {
      await manager.cleanUp();
    });
  });
});

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});
