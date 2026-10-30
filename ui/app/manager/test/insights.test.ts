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
import { test, userStatePath } from "./fixtures/manager";
import type { Dashboard } from "@openremote/model";
import {
  preparedAssetsForInsights as assets,
  assignLocation,
  commonAttrs,
  getRGBColor,
  rgbToHex,
  assetWithPredictedAndStoredDatapoints,
} from "./fixtures/data/assets.js";
import { users } from "./fixtures/data/users";
import { expect } from "@openremote/test";

test.use({ storageState: userStatePath });

test("Create a Line Chart widget", async ({ manager, shared, page, insightsPage }) => {
  await shared.interceptResponse<Dashboard>("**/dashboard", (dashboard) => {
    if (dashboard) manager.dashboards.push(dashboard.id!);
  });

  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Insights");

  // Drag and drop Line Chart widget
  await expect(insightsPage.getDashboardListItems()).toHaveCount(0);
  await page.click(".mdi-plus >> nth=0");
  await expect(insightsPage.getDashboardListItems()).toHaveCount(1);
  await expect(insightsPage.getWidgets()).toHaveCount(0);
  await insightsPage.dragAndDropWidget("Line Chart", [1, 1]);
  await expect(insightsPage.getWidgets()).toHaveCount(1);

  // Select widget and make widget larger by resizing it using the handle
  const chartWidget = insightsPage.getWidgets({ hasText: "Line Chart" }).first();
  await chartWidget.click();
  await expect(insightsPage.getBrowser()).toBeHidden();
  await insightsPage.resizeWidgetTo(chartWidget);
  await expect(insightsPage.getWidgets()).toHaveCount(1);

  // Add attribute of Asset #2 and Asset #1
  const attrName = "Energy level";
  await expect(insightsPage.getWidgetSettings({ hasText: "Line Chart" })).toBeVisible();
  await insightsPage.addAttributes(assets[1].name!, [attrName]);
  await expect(insightsPage.getWidgetAttributes().getByText(assets[1].name!).first()).toBeVisible();
  await expect(insightsPage.getWidgetAttributes().getByText(attrName)).toHaveCount(1);
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(1);
  await insightsPage.addAttributes(assets[0].name!, ["Power", attrName]);
  await expect(insightsPage.getWidgetAttributes().getByText(assets[0].name!).first()).toBeVisible();
  await expect(insightsPage.getWidgetAttributes().getByText(attrName)).toHaveCount(2);
  await expect(insightsPage.getWidgetAttributes().getByText("Power")).toHaveCount(1);
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(3);

  // Save the dashboard
  await shared.interceptResponse<Dashboard>("**/dashboard", async (_dashboard, _request, response) => {
    expect(response?.status()).toBe(200);
    expect(await response?.json()).toBeDefined();
  });
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();

  // Select the widget and do final checks
  await chartWidget.click();
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(3);
  await expect(insightsPage.getWidgetAttributes().first()).toContainText(assets[1].name!);
  await expect(insightsPage.getWidgetAttributes().first()).toContainText(attrName);
  await expect(insightsPage.getWidgetAttributes().last()).toContainText(assets[0].name!);
  await expect(insightsPage.getWidgetAttributes().last()).toContainText("Power");
});

test("Create a Map widget with text thresholds", async ({ manager, shared, page, insightsPage, mwcInput }) => {
  await shared.interceptResponse<Dashboard>("**/dashboard", (dashboard) => {
    if (dashboard) manager.dashboards.push(dashboard.id!);
  });

  await page.addInitScript(manager.hijackWebSocket());

  await manager.setup("smartcity", {
    assets: [
      assignLocation({
        name: "Thing",
        type: "ThingAsset",
        realm: "smartcity",
        attributes: { ...commonAttrs },
      }),
    ],
  });
  await manager.configureAppConfig({});
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Insights");

  // Drag and drop Map widget
  await expect(insightsPage.getDashboardListItems()).toHaveCount(0);
  await page.click(".mdi-plus >> nth=0");
  await expect(insightsPage.getDashboardListItems()).toHaveCount(1);
  await expect(insightsPage.getWidgets()).toHaveCount(0);
  await insightsPage.dragAndDropWidget("Map");
  await expect(insightsPage.getWidgets()).toHaveCount(1);

  // Select widget and make widget larger by resizing it using the handle
  const mapWidget = insightsPage.getWidgets({ hasText: "Map" }).first();
  await insightsPage.resizeWidgetTo(mapWidget);
  await expect(insightsPage.getWidgets()).toHaveCount(1);
  await expect(page.locator(".or-map-marker")).not.toBeVisible();

  // Select the widget and add "notes" as attribute
  const thresholdPanel = insightsPage.getWidgetSettings().locator("thresholds-panel");
  await mapWidget.click();
  await expect(insightsPage.getBrowser()).toBeHidden();
  await expect(insightsPage.getWidgetSettings({ hasText: "Map" })).toBeVisible();
  await page.getByRole("combobox", { name: "Asset type" }).fill("Thing");
  await page.getByRole("option", { name: "Thing asset" }).click();
  await expect(thresholdPanel).toBeHidden();
  await insightsPage.getWidgetSettings().getByRole("combobox", { name: "Attribute" }).click();
  await insightsPage.getWidgetSettings().getByRole("option", { name: "Notes" }).click();
  await expect(page.locator(".or-map-marker")).toBeVisible();
  await expect(thresholdPanel).toBeVisible();

  // Check default text configuration is correct
  const textThresholds = thresholdPanel.locator("or-vaadin-text-field").getByRole("textbox");
  const thresholdsColors = mwcInput.getInputByType("color", thresholdPanel);
  await expect(textThresholds).toHaveCount(2);
  await expect(textThresholds.first()).toHaveValue("example1");
  await expect(textThresholds.last()).toHaveValue("example2");
  await expect(thresholdsColors).toHaveCount(2);
  await expect(thresholdsColors.first()).toHaveValue("#4caf50");
  await expect(thresholdsColors.last()).toHaveValue("#ff9800");
  await expect
    .poll(() => page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor).then(rgbToHex))
    .toBe("4c4c4c");

  // Update attribute to match the first threshold
  await manager.sendWebSocketEvent({
    eventType: "attribute",
    ref: { id: manager.assets[0].id, name: "notes" },
    value: "example1",
  });
  await page.getByTitle("Refresh", { exact: true }).click();
  await expect(page.locator(".or-map-marker")).toBeVisible();
  await expect
    .poll(() => page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor).then(rgbToHex))
    .toBe("4caf50");

  await mapWidget.click();
  await thresholdsColors.last().fill("#000000");

  // Update attribute to match the second threshold
  await manager.sendWebSocketEvent({
    eventType: "attribute",
    ref: { id: manager.assets[0].id, name: "notes" },
    value: "example2",
  });
  await page.getByTitle("Refresh", { exact: true }).click();
  await expect(page.locator(".or-map-marker")).toBeVisible();
  await expect
    .poll(() => page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor).then(rgbToHex))
    .toBe("000000");
});

test("Replace broken chart references while preserving custom colors", async ({
  manager,
  shared,
  page,
  insightsPage,
}) => {
  await shared.interceptResponse<Dashboard>("**/dashboard", (dashboard) => {
    if (dashboard) manager.dashboards.push(dashboard.id!);
  });

  // Provision the asset that the charts will reference (and that we later delete).
  // Reuse an existing insights asset; it has a "power" attribute with stored datapoints.
  const referencedAsset = assets[0];
  await manager.setup("smartcity", { assets: [referencedAsset] });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Insights");

  // Build a Line Chart and a Bar Chart referencing "Power", colour them, and save
  await expect(insightsPage.getDashboardListItems()).toHaveCount(0);
  await page.click(".mdi-plus >> nth=0");
  await expect(insightsPage.getDashboardListItems()).toHaveCount(1);

  // Drop both widgets while the widget palette is available (no widget selected yet)
  await insightsPage.dragAndDropWidget("Line Chart", [0, 0]);
  await expect(insightsPage.getWidgets()).toHaveCount(1);
  await insightsPage.dragAndDropWidget("Bar Chart", [6, 0]);
  await expect(insightsPage.getWidgets()).toHaveCount(2);
  const lineWidget = insightsPage.getWidgets({ hasText: "Line Chart" }).first();
  const barWidget = insightsPage.getWidgets({ hasText: "Bar Chart" }).first();

  // Line chart: add the Power attribute and give it a custom red colour
  await insightsPage.selectWidget(lineWidget);
  await expect(insightsPage.getWidgetSettings({ hasText: "Line Chart" })).toBeVisible();
  await insightsPage.resizeWidgetTo(lineWidget, [6, 6]);
  await insightsPage.addAttributes(referencedAsset.name!, ["Power"]);
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(1);
  await insightsPage.setAttributeColor("#ff0000");
  await expect(insightsPage.getAttributeColorInput()).toHaveValue("#ff0000");
  // Change the custom colour a second time; to make sure that works.
  await insightsPage.setAttributeColor("#0000ff");
  await expect(insightsPage.getAttributeColorInput()).toHaveValue("#0000ff");

  // Bar chart: add the Power attribute and give it a custom green colour
  await insightsPage.selectWidget(barWidget);
  await expect(insightsPage.getWidgetSettings({ hasText: "Bar Chart" })).toBeVisible();
  await insightsPage.resizeWidgetTo(barWidget, [12, 6], { force: true });
  await insightsPage.addAttributes(referencedAsset.name!, ["Power"]);
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(1);
  await insightsPage.setAttributeColor("#00ff00");
  await expect(insightsPage.getAttributeColorInput()).toHaveValue("#00ff00");
  // Change the custom colour a second time; to make sure that works.
  await insightsPage.setAttributeColor("#ffc400");
  await expect(insightsPage.getAttributeColorInput()).toHaveValue("#ffc400");

  // Save the dashboard
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();

  // Delete the referenced asset, then reload to force the charts to re-resolve
  const adminToken = await manager.getAccessToken("master", "admin", users.admin.password!);
  await manager.deleteAssets({ headers: { Authorization: `Bearer ${adminToken}` } });
  await page.reload();
  await expect(insightsPage.getBuilder()).toBeVisible();
  await expect(insightsPage.getPreview()).toBeVisible();
  await expect(insightsPage.getPreview()).toHaveJSProperty("isLoading", false);
  await expect(insightsPage.getWidgets()).toHaveCount(2);

  // Both widget legends should show "Broken Reference" instead of dropping the attribute
  await expect(insightsPage.getBrokenReferences()).toHaveCount(2);

  // Both settings panels should show a broken attribute row with the replace action
  await insightsPage.selectWidget(lineWidget);
  await expect(insightsPage.getBrokenAttributeRows()).toHaveCount(1);
  await insightsPage.selectWidget(barWidget);
  await expect(insightsPage.getBrokenAttributeRows()).toHaveCount(1);

  // Create the replacement asset and replace the broken references
  await manager.createAsset(assetWithPredictedAndStoredDatapoints);

  // Line chart: the replace picker enables predicted attributes, so BOTH are offered.
  // Replace with the predicted-only "energyImportTotal".
  await insightsPage.selectWidget(lineWidget);
  const linePicker = await insightsPage.openReplacePicker(insightsPage.getBrokenAttributeRows().first());
  await linePicker.getByText("Import Asset").click();
  await expect(linePicker.getByText("Energy export total")).toBeVisible();
  await expect(linePicker.getByText("Energy import total")).toBeVisible();
  await linePicker.getByText("Energy import total").click();
  await linePicker.getByRole("button", { name: "Add" }).click();
  await expect(linePicker).toBeHidden();

  // Line chart broken row should now be replaced and the custom colour migrated to the new ref
  await expect(insightsPage.getBrokenAttributeRows()).toHaveCount(0);
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(1);
  await expect(insightsPage.getWidgetAttributes().first()).toContainText("Import Asset");
  await expect(insightsPage.getAttributeColorInput()).toHaveValue("#0000ff");
  const lineColors = await insightsPage.getAttributeColors();
  expect(lineColors).toHaveLength(1);
  expect(lineColors[0][0].name).toBe("energyImportTotal");
  expect(lineColors[0][1]).toBe("#0000ff");

  // Bar chart: the replace picker does NOT enable predicted attributes, so the
  // predicted-only "energyImportTotal" must be absent; only the stored
  // "energyExportTotal" is offered.
  await insightsPage.selectWidget(barWidget);
  const barPicker = await insightsPage.openReplacePicker(insightsPage.getBrokenAttributeRows().first());
  await barPicker.getByText("Import Asset").click();
  await expect(barPicker.getByText("Energy export total")).toBeVisible();
  await expect(barPicker.getByText("Energy import total")).toHaveCount(0);
  await barPicker.getByText("Energy export total").click();
  await barPicker.getByRole("button", { name: "Add" }).click();
  await expect(barPicker).toBeHidden();

  // Bar chart: broken row should be replaced and the custom colour migrated to the new ref
  await expect(insightsPage.getBrokenAttributeRows()).toHaveCount(0);
  await expect(insightsPage.getWidgetAttributes()).toHaveCount(1);
  await expect(insightsPage.getWidgetAttributes().first()).toContainText("Import Asset");
  await expect(insightsPage.getAttributeColorInput()).toHaveValue("#ffc400");
  const barColors = await insightsPage.getAttributeColors();
  expect(barColors).toHaveLength(1);
  expect(barColors[0][0].name).toBe("energyExportTotal");
  expect(barColors[0][1]).toBe("#ffc400");
});

/**
 * @given A dashboard with the widget browser showing its stack of cards
 * @when A card is dragged from the browser onto the grid
 * @then The cards respawn in their original order and grid slots
 * @and The remaining cards can still be dragged onto the grid
 */
test("should keep widget cards in place and draggable after dragging one onto the grid", async ({
  manager,
  shared,
  page,
  insightsPage,
}) => {
  await shared.interceptResponse<Dashboard>("**/dashboard", (dashboard) => {
    if (dashboard) manager.dashboards.push(dashboard.id!);
  });

  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Insights");
  await page.click(".mdi-plus >> nth=0");
  await expect(insightsPage.getDashboardListItems()).toHaveCount(1);

  // Snapshot the browser layout: card ids in DOM order plus the grid slot each card occupies
  const getCardLayout = () =>
    insightsPage
      .getBrowserCards()
      .evaluateAll((cards) =>
        cards.map((card) => [card.getAttribute("gs-id"), card.getAttribute("gs-x"), card.getAttribute("gs-y")])
      );
  await expect(insightsPage.getBrowserCards().first()).toBeVisible();
  const layoutBefore = await getCardLayout();

  await insightsPage.dragAndDropWidget("Image", [0, 0]);
  await expect(insightsPage.getWidgets()).toHaveCount(1);
  await expect(insightsPage.getBrowserCards()).toHaveCount(layoutBefore.length);

  // Cards used to reshuffle around their own slots after one had been dragged away,
  // and some of them no longer responded to being picked up.
  await expect.poll(() => getCardLayout()).toEqual(layoutBefore);

  await insightsPage.dragAndDropWidget("Table", [6, 0]);
  await expect(insightsPage.getWidgets()).toHaveCount(2);
});

/**
 * @given An empty dashboard grid
 * @when Two widgets are dropped with the second visually before the first
 * @then The DOM order still follows insertion instead of the visual layout
 */
test("should keep grid widgets in DOM order when placed out of visual order", async ({
  manager,
  shared,
  page,
  insightsPage,
}) => {
  await shared.interceptResponse<Dashboard>("**/dashboard", (dashboard) => {
    if (dashboard) manager.dashboards.push(dashboard.id!);
  });

  await manager.setup("smartcity", { assets });
  await manager.goToRealmStartPage("smartcity");
  await manager.navigateToTab("Insights");
  await page.click(".mdi-plus >> nth=0");
  await expect(insightsPage.getDashboardListItems()).toHaveCount(1);

  // First widget on the grid in DOM order, identified by its gridstack id
  const getFirstWidgetId = () =>
    insightsPage.getWidgetGSContainer(insightsPage.getWidgets().first()).getAttribute("gs-id");

  // Widgets used to be re-ordered in the DOM to their visual layout on every grid change,
  // detaching and re-attaching each element, which destroyed the chart and map contents.
  await insightsPage.dragAndDropWidget("Image", [6, 0]);
  const firstWidgetId = await getFirstWidgetId();

  // Dropped visually before the first widget, so the visual order is the reverse of the DOM order
  await insightsPage.dragAndDropWidget("Table", [0, 0]);

  await expect.poll(() => getFirstWidgetId()).toBe(firstWidgetId);
});

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});
