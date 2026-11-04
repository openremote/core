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
import { type BasePage, expect, type Locator, type Page, type Shared } from "@openremote/test";
import type { Manager } from "../manager";

export class InsightsPage implements BasePage {
  constructor(
    private readonly page: Page,
    private readonly shared: Shared,
    private readonly manager: Manager
  ) {}

  async goto() {
    return this.manager.navigateToTab("Insights");
  }

  getBuilder(options?: any) {
    return this.page.locator("or-dashboard-builder", options);
  }

  getDashboardListItems(options?: any) {
    return this.page.locator("or-dashboard-tree li", options);
  }

  getPreview(options?: any) {
    return this.page.locator("or-dashboard-preview", options);
  }

  getBrowser(options?: any) {
    return this.page.locator("or-dashboard-browser #sidebar", options);
  }

  getBrowserCards(options?: any) {
    return this.page.locator("or-dashboard-browser #sidebarElement .grid-stack-item", options);
  }

  getWidgetSettings(options?: any) {
    return this.page.locator(".settings-container:has(or-dashboard-widgetsettings)", options);
  }

  getWidgetAttributes(options?: any) {
    return this.getWidgetSettings().locator("settings-panel .attribute-list-item", options);
  }

  getDashboardGrid(options?: any) {
    return this.page.locator("or-dashboard-preview #gridElement", options);
  }

  getWidgets(options?: any) {
    return this.getDashboardGrid().locator("or-dashboard-widget-container", options);
  }

  getGridCellDimensions(): Promise<[number, number]> {
    return this.getDashboardGrid().evaluate((el: any) => [el.gridstack.cellWidth(), el.gridstack.getCellHeight()]);
  }

  /**
   * Gets the parent Gridstack item container for a given widget.
   * @param widget The `or-dashboard-widget-container` locator
   * @returns The locator for the Gridstack item container
   */
  getWidgetGSContainer(widget: Locator): Locator {
    return widget.locator("..").locator("..");
  }

  /**
   * Gets the x and y coordinates for the specified widget
   * @requires the locator to reference a `or-dashboard-widget-container` to be able to get the Gridstack node
   * @param widget The `or-dashboard-widget-container` locator
   * @returns The x and y grid cell coordinates
   */
  getWidgetLocation(widget: Locator): Promise<[number, number]> {
    return this.getWidgetGSContainer(widget).evaluate((el: any) => [el.gridstackNode.x, el.gridstackNode.y]);
  }

  /**
   * Drag an drop a widget on to the insights page grid
   * @param type The widget type to drag and drop
   * @param location The location on the grid where to drop the widget
   * @param cells The area in cells that this widget covers
   */
  async dragAndDropWidget(type: string, [gridX, gridY] = [0, 0], [cellsWidth, cellsHeight] = [2, 2]) {
    await expect(this.getBuilder()).toBeVisible();
    await expect(this.getPreview()).toBeVisible();
    await expect(this.getPreview()).toHaveJSProperty("isLoading", false);
    await expect(this.getDashboardGrid()).toBeVisible();

    // Verify the slot is empty
    const isEmpty = await this.getDashboardGrid().evaluate(
      (el: any, { x, y, width, height }) => el.gridstack.isAreaEmpty(x, y, width, height),
      { x: gridX, y: gridY, width: cellsWidth, height: cellsHeight }
    );
    expect(isEmpty).toBeTruthy();

    // Grab the X/Y browser coordinates
    const [cellWidth, cellHeight] = await this.getGridCellDimensions();
    const [x, y] = [cellWidth * gridX, cellHeight * gridY];

    // Manual drag and drop
    const count = await this.getWidgets().count();
    await this.page.dragAndDrop(`#sidebar .grid-stack-item :has-text("${type}")`, ".maingrid", {
      sourcePosition: { x: 10, y: 10 }, // To drop the widget at the intended cell, we grab it close to its top left corner
      targetPosition: { x, y },
      steps: 50,
    });
    await expect(this.getWidgets()).toHaveCount(count + 1);
  }

  /**
   * Resize a widget from its bottom right corner to the specified grid cell coordinates
   * @param widget The locator of the widget
   * @param to The grid cell coordinates to resize the widget to
   * @param options The resize options (e.g. force will skip dimension assertions)
   */
  async resizeWidgetTo(widget: Locator, [cellsWidth, cellsHeight] = [8, 8], { force } = { force: false }) {
    const [gridX, gridY] = await this.getWidgetLocation(widget);
    const [cellWidth, cellHeight] = await this.getGridCellDimensions();
    const [width, height] = [cellsWidth * cellWidth, cellsHeight * cellHeight];

    const grid = this.page.locator(".maingrid");
    await this.getWidgetGSContainer(widget)
      .locator(".ui-resizable-handle.ui-resizable-se")
      .dragTo(grid, {
        force,
        // We apply -cellWidth/Height / 2 as the handle causes the source position to be offset
        targetPosition: {
          x: gridX * cellWidth + width - cellWidth / 2,
          y: gridY * cellHeight + height - cellHeight / 2,
        },
        steps: 10,
      });

    if (force) return;

    const bbox = await widget.boundingBox();
    // The dashboard dimensions should be equal to the specified cells,
    // or between the specified cells and the specified cells - 1 cell,
    // due to margins.
    expect(bbox?.width).toBeGreaterThan(width - cellWidth);
    expect(bbox?.width).toBeLessThanOrEqual(width);
    expect(bbox?.height).toBeGreaterThan(cellHeight - cellHeight);
    expect(bbox?.height).toBeLessThanOrEqual(height);
  }

  async addAttributes(assetName: string, attributeNames: string[]) {
    await this.getWidgetSettings().getByRole("button", { name: "Attribute" }).click();
    const dialog = this.page.locator("#dialog");
    await expect(dialog.getByText(assetName)).toBeVisible();
    await dialog.getByText(assetName).click();
    for (const attributeName of attributeNames) {
      await expect(dialog.getByText(attributeName)).toBeVisible();
      await dialog.getByText(attributeName).click();
    }
    await dialog.getByRole("button", { name: "Add" }).click();
  }

  /**
   * Selects a widget (opens its settings).
   * @param widget The `or-dashboard-widget-container` locator
   */
  async selectWidget(widget: Locator) {
    await widget.scrollIntoViewIfNeeded();
    await widget.locator(".panel-title").click();
  }

  /**
   * "Broken Reference" entries, both in chart widget legends and in the widget
   * settings attribute list.
   * @param options Optional locator options (e.g. scope via `has`)
   */
  getBrokenReferences(options?: any) {
    return this.page.locator('or-translate[value="brokenReference"]', options);
  }

  /**
   * Broken attribute rows shown in the widget settings attribute list.
   */
  getBrokenAttributeRows(options?: any) {
    return this.getWidgetSettings().locator(".attribute-list-item.broken", options);
  }

  /**
   * Opens the attribute picker for the replace ("swap-horizontal") action on a
   * given attribute row and returns the picker dialog locator.
   * @param row The attribute row locator (broken or healthy)
   */
  async openReplacePicker(row: Locator): Promise<Locator> {
    const button = row.locator('button[title="Replace attribute"]');
    await expect(button).toBeVisible();
    await button.dispatchEvent("click");
    const dialog = this.page.locator("#dialog");
    await expect(dialog).toBeVisible();
    return dialog;
  }

  /**
   * Sets the custom line/bar colour of the (single) attribute in the open widget
   * settings.
   * @param hex The colour to set, e.g. `#ff0000`
   */
  async setAttributeColor(hex: string) {
    const settings = this.getWidgetSettings();
    // Action buttons on a healthy row are only visible while the row is hovered
    await settings.locator(".attribute-list-item").first().hover();
    await settings.locator('button:has(or-icon[icon="palette"])').click();
    const colorInput = settings.locator('input[id^="chart-color-"]');
    await colorInput.evaluate((el, value) => {
      (el as HTMLInputElement).value = value;
      el.dispatchEvent(new Event("input", { bubbles: true }));
    }, hex);
    // The colour change is written to the config behind a debounce; wait for it
    // to flush so the colour persists when the dashboard is saved.
    await expect
      .poll(async () => (await this.getAttributeColors()).some(([, c]) => c?.toLowerCase() === hex.toLowerCase()))
      .toBe(true);
  }

  /**
   * Reads the current value of the (single) custom colour input in the open
   * widget settings.
   */
  getAttributeColorInput(): Locator {
    return this.getWidgetSettings().locator('input[id^="chart-color-"]');
  }

  /**
   * Reads the `attributeColors` config (`[AttributeRef, hex]` entries) off the
   * open chart/barchart settings element.
   */
  getAttributeColors(): Promise<[{ id?: string; name?: string }, string][]> {
    return this.getWidgetSettings()
      .locator("chart-settings, barchart-settings")
      .evaluate((el: any) => el?.widgetConfig?.attributeColors ?? []);
  }
}
