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
import { expect, type Page } from "@openremote/test";
import type { AssetViewer } from "../../../component/or-asset-viewer/test/fixtures";
import { test, userStatePath } from "./fixtures/manager.js";
import { agent } from "./fixtures/data/assets.js";
import {
  configuredLight,
  lightTemplate,
  partlyImportableTemplate,
  pumpTemplate,
  targetLight,
} from "./fixtures/data/configuration-template.js";

test.use({ storageState: userStatePath });

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});

/**
 * Select a configuration template file for the asset open in modify mode and continue to what importing it would do.
 * @param page The page showing the asset
 * @param assetViewer The asset viewer fixture
 * @param name The file name
 * @param content The file content
 */
async function selectTemplateFile(page: Page, assetViewer: AssetViewer, name: string, content: string) {
  await page.getByRole("button", { name: "Import", exact: true }).click();
  await assetViewer
    .getConfigurationTemplateFileInput()
    .setInputFiles({ name, mimeType: "application/json", buffer: Buffer.from(content) });
  await page.getByRole("dialog").getByRole("button", { name: "Next" }).click();
}

/**
 * @given A configured light is set up in the "smartcity" realm
 * @when Opening the export dialog of the light in modify mode
 * @then The configured attributes that the asset type defines are listed and selected
 * @and The attribute of the light alone is not listed
 * @and The template is named after the light
 */
test("should list the configured attributes of the asset type for export", async ({
  page,
  manager,
  assetsPage,
  assetViewer,
}) => {
  await manager.setup("smartcity", { assets: [configuredLight] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);
  await page.getByRole("button", { name: "Export", exact: true }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog.getByLabel("Template name")).toHaveValue("Light 1 (configured)-configuration-template.json");
  await expect(dialog.getByRole("checkbox", { name: "Select all" })).toBeChecked();
  for (const label of ["Dimmer", "Power", "White tone"]) {
    await expect(dialog.getByRole("checkbox", { name: new RegExp(`^${label}`) })).toBeChecked();
  }
  await expect(assetViewer.getConfigurationTemplateAttribute(dialog, "Maintenance note")).toHaveCount(0);
});

/**
 * @given A configured light is set up in the "smartcity" realm
 * @when Exporting all but one of its attributes under another template name
 * @then A file of that name downloads, holding the type and configuration items of the selected attributes
 * @and Configuration items that grant access are left out
 */
test("should download the selected attributes as a configuration template", async ({ page, manager, assetsPage }) => {
  await manager.setup("smartcity", { assets: [configuredLight] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);
  await page.getByRole("button", { name: "Export", exact: true }).click();

  const dialog = page.getByRole("dialog");
  await dialog.getByRole("checkbox", { name: /^Power/ }).uncheck();
  await expect(dialog.getByRole("checkbox", { name: "Select all" })).not.toBeChecked();
  await dialog.getByLabel("Template name").fill("lights");
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === "GET" && new URL(response.url()).pathname.endsWith("/configurationTemplate")
  );
  const downloadPromise = page.waitForEvent("download");
  await dialog.getByRole("button", { name: "Export", exact: true }).click();
  const template = await (await responsePromise).json();

  expect((await downloadPromise).suggestedFilename()).toBe("lights.json");
  expect(template).toMatchObject({ version: 1, assetType: "LightAsset" });
  expect(Object.keys(template.attributes).sort()).toEqual(["brightness", "colourTemperature"]);
  expect(template.attributes.brightness).toEqual(lightTemplate.attributes.brightness);
  expect(template.attributes.colourTemperature).toMatchObject(lightTemplate.attributes.colourTemperature);
});

/**
 * @given A light to import into is set up in the "smartcity" realm
 * @when Importing the configuration template of another light in modify mode
 * @then The matched attributes are listed with a warning that their configuration items are overwritten
 * @when Confirming the import
 * @then The light has unsaved changes, so export is disabled
 * @when Saving the light
 * @then The configuration items of the template are stored, apart from those that grant access, which the light keeps
 */
test("should apply an imported configuration template to the draft until it is saved", async ({
  page,
  manager,
  assetsPage,
  assetViewer,
}) => {
  await manager.setup("smartcity", { assets: [targetLight] });
  const id = manager.assets[0].id!;
  await assetsPage.gotoAssetId("smartcity", id, true);
  await selectTemplateFile(page, assetViewer, "lights.json", JSON.stringify(lightTemplate));

  const matched = assetViewer.getConfigurationTemplateSection("Matched attributes");
  // getByText cannot match text that or-translate renders straight into its shadow root, so locate the element
  await expect(
    page.getByRole("dialog").locator("or-translate", { hasText: "This will overwrite existing metadata" })
  ).toBeVisible();
  for (const label of ["Dimmer", "Power", "White tone"]) {
    await expect(assetViewer.getConfigurationTemplateAttribute(matched, label)).toBeVisible();
  }
  await page.getByRole("dialog").getByRole("button", { name: "Import", exact: true }).click();

  const saveButton = page.getByRole("button", { name: "Save" });
  const exportButton = page.getByRole("button", { name: "Export", exact: true });
  await expect(saveButton).toBeEnabled();
  await expect(exportButton).toBeDisabled();
  await expect(exportButton).toHaveAttribute("title", "Save or revert your changes before exporting");

  await saveButton.click();
  await expect(saveButton).toBeDisabled();
  await expect(exportButton).toBeEnabled();

  const { data: light } = await manager.api.AssetResource.get(id, await manager.adminConfig());
  expect(light.attributes!.brightness).toMatchObject({ value: 40, meta: lightTemplate.attributes.brightness.meta });
  expect(light.attributes!.onOff.meta).toEqual({ label: "Power", accessRestrictedRead: true });
});

/**
 * @given A light to import into is set up in the "smartcity" realm
 * @when Importing a template holding an attribute the light misses and one of another value type
 * @then Those attributes are listed as not imported, next to the matched attribute
 */
test("should list the attributes of a template that cannot be imported", async ({
  page,
  manager,
  assetsPage,
  assetViewer,
}) => {
  await manager.setup("smartcity", { assets: [targetLight] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);
  await selectTemplateFile(page, assetViewer, "edited.json", JSON.stringify(partlyImportableTemplate));

  const matched = assetViewer.getConfigurationTemplateSection("Matched attributes");
  const skipped = assetViewer.getConfigurationTemplateSection("Attribute name or type not found");
  await expect(assetViewer.getConfigurationTemplateAttribute(matched, "Dimmer")).toBeVisible();
  await expect(assetViewer.getConfigurationTemplateAttribute(skipped, "Dim level")).toBeVisible();
  await expect(assetViewer.getConfigurationTemplateAttribute(skipped, "White tone")).toBeVisible();
  await expect(assetViewer.getConfigurationTemplateAttribute(matched, "White tone")).toHaveCount(0);
});

/**
 * @given A light to import into is set up in the "smartcity" realm
 * @when Going back from what an import would do and cancelling it
 * @then The light has no unsaved changes
 */
test("should leave the draft unchanged when an import is cancelled", async ({
  page,
  manager,
  assetsPage,
  assetViewer,
}) => {
  await manager.setup("smartcity", { assets: [targetLight] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);
  await selectTemplateFile(page, assetViewer, "lights.json", JSON.stringify(lightTemplate));

  await page.getByRole("dialog").getByRole("button", { name: "Back" }).click();
  await expect(page.getByRole("dialog").getByRole("button", { name: "Select file" })).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();

  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
});

/**
 * @given A light to import into is set up in the "smartcity" realm
 * @when Importing a file that does not hold JSON
 * @then The import is rejected with the reason
 */
test("should reject a file that is not a configuration template", async ({
  page,
  manager,
  assetsPage,
  assetViewer,
}) => {
  await manager.setup("smartcity", { assets: [targetLight] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);
  await selectTemplateFile(page, assetViewer, "notes.json", "not a template");

  await expect(page.getByText("The selected file does not hold valid JSON.")).toBeVisible();
});

/**
 * @given A light to import into is set up in the "smartcity" realm
 * @when Importing a template that shares no attribute with the light
 * @then The import is rejected with the reason
 * @when Going back
 * @then Another file can be selected and previewed
 */
test("should go back to the file selection from a template that cannot be imported", async ({
  page,
  manager,
  assetsPage,
  assetViewer,
}) => {
  await manager.setup("smartcity", { assets: [targetLight] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);
  await selectTemplateFile(page, assetViewer, "pumps.json", JSON.stringify(pumpTemplate));
  await expect(
    page.getByText("None of the attributes in the selected file can be imported into this asset.")
  ).toBeVisible();

  // A rejected file used to offer only Close, so another file meant starting the import over
  await page.getByRole("button", { name: "Back" }).click();
  await assetViewer.getConfigurationTemplateFileInput().setInputFiles({
    name: "lights.json",
    mimeType: "application/json",
    buffer: Buffer.from(JSON.stringify(lightTemplate)),
  });
  await page.getByRole("dialog").getByRole("button", { name: "Next" }).click();

  const matched = assetViewer.getConfigurationTemplateSection("Matched attributes");
  await expect(assetViewer.getConfigurationTemplateAttribute(matched, "Dimmer")).toBeVisible();
});

/**
 * @given An agent is set up in the "smartcity" realm
 * @when Opening the agent in modify mode
 * @then Import and export are not offered
 */
test("should not offer import and export on agents", async ({ page, manager, assetsPage }) => {
  await manager.setup("smartcity", { assets: [agent] });
  await assetsPage.gotoAssetId("smartcity", manager.assets[0].id!, true);

  await expect(page.getByRole("button", { name: "Save" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Import", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Export", exact: true })).toHaveCount(0);
});

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
