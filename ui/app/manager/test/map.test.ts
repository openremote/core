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
import { adminStatePath, test, userStatePath } from "./fixtures/manager.js";
import {
  preparedAssetsWithLocation as assets,
  assignLocation,
  assignRandomLocationInArea,
  commonAttrs,
  getAssetTypeColour,
  getAssetTypes,
  getAssetAt,
  rgbToHex,
  getAssetsForAllTypes,
  getRGBColor,
} from "./fixtures/data/assets.js";
import { markers } from "./fixtures/data/manager.js";
import type { OrMap, OrClusterMarker, MapFilter } from "@openremote/or-map";
import { type Asset, WellknownMetaItems } from "@openremote/model";

test.use({ storageState: userStatePath });

test.describe("Map markers", () => {
  /**
   * @given Assets with location are set up in the "smartcity" realm
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then 2 asset markers are displayed on the map
   * @when Clicking on the asset "Battery" marker
   * @then The asset card is displayed
   * @when Clicking "View"
   * @then Navigates to the asset detail page from the asset card
   * @and Shows the asset name "Battery" on the page header
   * @and Displays a default marker on the map preview
   * @when Clicking "Modify"
   * @and the location attribute input icon
   * @Then Displays a default marker on the map preview
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should show asset and navigate", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");

    const asset = assets[0].name;

    await expect(page.locator(".or-map-marker")).toHaveCount(2);

    await page.click(".marker-container");
    await expect(page.locator("#card-container", { hasText: asset })).toBeVisible();

    await page.getByRole("button", { name: "View" }).click();
    await expect(page.locator(`#asset-header`, { hasText: asset })).toBeVisible();
    await expect(page.locator("or-map").locator(".marker-container")).toBeVisible();

    await page.getByRole("button", { name: "Modify" }).click();
    await page.locator("or-icon[icon=crosshairs-gps]").click();
    await expect(page.locator("or-map").locator(".marker-container")).toBeVisible();
  });

  /**
   * @given 1 asset is setup with "show on dashboard" set to true in the "smartcity" realm
   * @and 1 asset is setup with "show on dashboard" set to false
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then Only shows 1 marker
   * @when Clicking the marker
   * @then Shows the asset name "ShownOnMap" on the card title
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test('should not show when "show on dashboard" is disabled', async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const assets = [
      ["ShownOnMap", { [WellknownMetaItems.SHOWONDASHBOARD]: true }, 4.482259693115793, 51.91756799273],
      ["NotShownOnMap", { [WellknownMetaItems.SHOWONDASHBOARD]: false }, 4.4845127486877345, 51.917435642781214],
    ].map(([name, meta, ...coordinates]) =>
      Object.assign(
        { name, type: "ThingAsset", realm: "smartcity" },
        {
          attributes: {
            ...commonAttrs,
            location: { meta, value: { type: "Point", coordinates } },
          },
        }
      )
    ) as Asset[];
    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator(".or-map-marker")).toHaveCount(1);

    await page.click(".marker-container");
    await expect(page.locator("#card-container", { hasText: assets[0].name })).toBeVisible();
  });

  /**
   * @given Assets with location and different directions are set up in the "smartcity" realm
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then Shows 3 asset markers
   * @when Clicking each individually
   * @then Shows their name and direction value
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should show marker direction", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const assets = [
      ["Thing1", 30, 4.482259693115793, 51.91756799273],
      ["Thing2", 60, 4.4845127486877345, 51.917435642781214],
      ["Thing3", 180, 4.486293735477517, 51.91605818019178],
    ].map(([name, direction, ...coordinates]) =>
      Object.assign(
        { name, type: "ThingAsset", realm: "smartcity" },
        {
          attributes: {
            ...commonAttrs,
            location: { value: { type: "Point", coordinates } },
            direction: { type: "direction", value: direction },
          },
        }
      )
    ) as Asset[];
    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator(".or-map-marker")).toHaveCount(assets.length);

    // const getDirection = (el: Element) => window.getComputedStyle(el).transform;

    for (const [i, asset] of assets.entries()) {
      await page.locator(".marker-container").nth(i).click();
      await expect(page.locator("#card-container", { hasText: asset.name })).toBeVisible();
      await expect(page.locator("#attribute-list", { hasText: asset.attributes!.direction.value })).toBeVisible();
    }
  });
});

test.describe("Marker config", () => {
  /**
   * @given Asset with location is set up in the "smartcity" realm
   * @and a marker config is configured
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then 1 asset marker is displayed on the map
   * @and It is colored black
   * @when the marker is clicked
   * @then shows the correct marker name
   * @and The attribute value is shows "-"
   * @when An update is sent to toggle the attribute value
   * @then The marker changes to yellow
   * @and The attribute value is shows "true"
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should toggle asset marker colour based on attribute value", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await page.addInitScript(manager.hijackWebSocket());

    const assets = [
      assignRandomLocationInArea(
        {
          name: "Light",
          type: "ThingAsset",
          realm: "smartcity",
          attributes: { ...commonAttrs, onOff: { name: "onOff", type: "boolean" } },
        },
        { west: 4.4857, south: 51.9162, east: 4.4865, north: 51.9167 }
      ),
    ];
    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({
      pages: { map: { clustering: { cluster: false }, markers: { ThingAsset: markers[0] } } },
    });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator(".or-map-marker")).toHaveCount(1);
    await page.click(".marker-container");
    await expect(page.locator("#card-container", { hasText: assets[0].name })).toBeVisible();
    await expect(page.locator("#attribute-list", { hasText: "-" })).toBeVisible();

    const off = await page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor);
    expect(rgbToHex(off)).toBe(markers[0].colours.false);

    await manager.sendWebSocketEvent({
      eventType: "attribute",
      ref: { id: manager.assets[0].id, name: "onOff" },
      value: true,
    });
    await expect(page.locator("#attribute-list", { hasText: "true" })).toBeVisible();

    const on = await page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor);
    expect(rgbToHex(on)).toBe(markers[0].colours.true);
  });

  /**
   * @given Asset with location is set up in the "smartcity" realm
   * @and a marker config is configured
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then 1 asset marker is displayed on the map
   * @when the marker is clicked
   * @then shows the correct marker name
   * @and it has the default marker color
   * @and The attribute value is shows "-"
   * @when An update is sent with a value of 0
   * @then It is colored green
   * @and The attribute value is shows "0"
   * @when An update is sent with a value of 30
   * @then The marker changes to orange
   * @and The attribute value is shows "30"
   * @when An update is sent with a value of 40
   * @then The marker changes to red
   * @and The attribute value is shows "40"
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should display marker with label and change marker colour based on attribute range", async ({
    page,
    manager,
    browserName,
  }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await page.addInitScript(manager.hijackWebSocket());

    const assets = [
      assignRandomLocationInArea(
        {
          name: "Thermometer",
          type: "ThingAsset",
          realm: "smartcity",
          attributes: { ...commonAttrs, temperature: { name: "temperature", type: "number" } },
        },
        { west: 4.4857, south: 51.9162, east: 4.4865, north: 51.9167 }
      ),
    ];
    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { markers: { ThingAsset: markers[1] } } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator(".or-map-marker")).toHaveCount(1);
    await page.click(".marker-container");
    await expect(page.locator("#card-container", { hasText: assets[0].name })).toBeVisible();
    await expect(page.locator("#attribute-list", { hasText: "-" })).toBeVisible();
    await expect(page.locator(".marker-container .label", { hasText: "-" })).toBeVisible();

    const _default = await page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor);
    expect(rgbToHex(_default)).toBe("4c4c4c");

    await manager.sendWebSocketEvent({
      eventType: "attribute",
      ref: { id: manager.assets[0].id, name: "temperature" },
      value: 0,
    });
    await expect(page.locator("#attribute-list", { hasText: "0" })).toBeVisible();
    await expect(page.locator(".marker-container .label", { hasText: "0" })).toBeVisible();

    const green = await page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor);
    expect(rgbToHex(green)).toBe(markers[1].colours.ranges[0].colour);

    await manager.sendWebSocketEvent({
      eventType: "attribute",
      ref: { id: manager.assets[0].id, name: "temperature" },
      value: 30,
    });
    await expect(page.locator("#attribute-list", { hasText: "30" })).toBeVisible();
    await expect(page.locator(".marker-container .label", { hasText: "30" })).toBeVisible();

    const orange = await page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor);
    expect(rgbToHex(orange)).toBe(markers[1].colours.ranges[1].colour);

    await manager.sendWebSocketEvent({
      eventType: "attribute",
      ref: { id: manager.assets[0].id, name: "temperature" },
      value: 40,
    });
    await expect(page.locator("#attribute-list", { hasText: "40" })).toBeVisible();
    await expect(page.locator(".marker-container .label", { hasText: "40" })).toBeVisible();

    const red = await page.locator('or-icon[icon="or:marker"]').evaluate(getRGBColor);
    expect(rgbToHex(red)).toBe(markers[1].colours.ranges[2].colour);
  });
});

test.describe("Marker clustering", () => {
  /**
   * @given Assets with location are set up in the "smartcity" realm
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then Shows cluster marker with the expected asset type colours
   * @when Zooming in
   * @then Shows cluster marker reduce to 10
   * @and Shows a normal marker
   * @when When clicking the cluster marker
   * @then Cluster marker disappears
   * @and All 10 asset markers part of the cluster are shown
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should display clustered markers", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const bbox = { west: 4.4859, south: 51.9163, east: 4.4864, north: 51.9166 };
    const assetInfos = (await manager.api.AssetModelResource.getAssetInfos()).data;
    const assets = getAssetsForAllTypes(assetInfos, { bbox });
    const outlier = assignLocation(getAssetAt(assetInfos), [4.48106646473, 51.9163295344]);
    assets.push({ ...outlier, name: "outlier", realm: "smartcity" });

    await manager.setup("smartcity", { assets });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator("or-map")).toBeVisible();
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    const clusterMarker = page.locator("or-cluster-marker");
    await expect(clusterMarker).toHaveCount(1);
    const coords = await clusterMarker.evaluate((marker: OrClusterMarker) => [marker.lng!, marker.lat!]);
    await expect(clusterMarker.locator("text")).toContainText(`${assets.length}`);
    const assetTypes = getAssetTypes(assets);
    expect(
      await clusterMarker.locator("path[fill]").evaluateAll((el) => el.map((e) => e.getAttribute("fill")!))
    ).toStrictEqual(assetTypes.map((t) => "#" + getAssetTypeColour(t, assetInfos)));

    await page.locator("or-map").evaluate((map: OrMap, [lat, lng]) => map.flyTo([lat, lng], 16), coords);
    await expect(clusterMarker.locator("text")).toContainText(`${assets.length}`);
    await expect(page.locator(".or-map-marker")).toHaveCount(1);
    await expect(clusterMarker).toHaveCount(1);

    await clusterMarker.click();
    await expect(clusterMarker).not.toBeVisible();
    await expect(page.locator(".or-map-marker")).toHaveCount(assets.length - 1);
  });

  /**
   * @given Assets with location are set up in the "smartcity" realm
   * @and clustering is disabled
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then Shows asset markers
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should not display clustered markers", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const assetInfos = (await manager.api.AssetModelResource.getAssetInfos()).data;
    const bbox = { west: 4.4859, south: 51.9163, east: 4.4864, north: 51.9166 };
    const assets = getAssetsForAllTypes(assetInfos, { bbox });

    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator("or-map")).toBeVisible();
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    await expect(page.locator("or-cluster-marker")).not.toBeVisible();
    await expect(page.locator(".or-map-marker")).toHaveCount(assets.length);
  });

  test.describe(() => {
    test.use({ storageState: adminStatePath });

    /**
     * @given Assets with location are set up in the "smartcity" realm
     * @when Logging in to OpenRemote "master" realm as "admin"
     * @and Navigating to the "map" page
     * @then Shows no markers
     * @when Switching to the "smartcity" realm
     * @then Shows cluster markers
     * @when Switching back to the "master" realm
     * @then Shows no markers
     *
     * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
     */
    test("should update markers when switching realm", async ({ page, manager, browserName }) => {
      test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

      const assetInfos = (await manager.api.AssetModelResource.getAssetInfos()).data;
      const assets = getAssetsForAllTypes(assetInfos);
      await manager.setup("smartcity", { assets });

      await manager.goToRealmStartPage("master");
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));
      await expect(page.locator("or-cluster-marker")).not.toBeVisible();
      await expect(page.locator(".or-map-marker")).not.toBeVisible();

      await manager.switchToRealmByRealmPicker("smartcity");
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));
      await expect(page.locator(".or-map-marker").or(page.locator("or-cluster-marker")).first()).toBeVisible();

      await manager.switchToRealmByRealmPicker("master");
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));
      await expect(page.locator("or-cluster-marker")).not.toBeVisible();
      await expect(page.locator(".or-map-marker")).not.toBeVisible();
    });
  });
});

test.describe("Asset type legend", () => {
  /**
   * @given Assets with location are set up in the "smartcity" realm
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then Show the asset type legend
   * @when clicking the open menu button
   * @then the asset type legend opens and shows the asset types
   * @and the asset types checkboxes are checked
   * @when Unchecking the asset type check boxes 1 by 1
   * @then Hides the markers
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should toggle asset types", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const assetInfos = (await manager.api.AssetModelResource.getAssetInfos()).data;
    const assets = getAssetsForAllTypes(assetInfos, { limit: 3 });

    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator("or-map")).toBeVisible();
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    await page.locator("or-map-legend #legend-title").click();
    await expect(page.locator("or-map-legend #legend-list")).toBeVisible();

    const assetTypes = getAssetTypes(assets);
    const options = await page.locator("or-map-legend or-vaadin-item").all();
    expect(options.length).toBe(assetTypes.length);

    // Each type has exactly one asset (getAssetsForAllTypes with limit creates 1 per type)
    let hidden = 0;
    for (const option of options) {
      await expect(option.getByRole("checkbox")).toBeChecked();
      await expect(page.locator(".or-map-marker")).toHaveCount(assets.length - hidden);
      await option.getByRole("checkbox").uncheck();
      hidden++;
      await expect(page.locator(".or-map-marker")).toHaveCount(assets.length - hidden);
    }
  });

  /**
   * @given Only 1 asset type is setup with a location in the "smartcity" realm
   * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
   * @and Navigating to the "map" page
   * @then Shows the marker
   * @and the legend is not visible
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should not not be shown with 1 asset type", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const assets = [
      assignRandomLocationInArea(
        {
          name: "Thing",
          type: "ThingAsset",
          realm: "smartcity",
          attributes: { ...commonAttrs },
        },
        { west: 4.4857, south: 51.9162, east: 4.4865, north: 51.9167 }
      ),
    ];

    await manager.setup("smartcity", { assets });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator("or-map")).toBeVisible();
    await expect(page.locator(".or-map-marker")).toBeVisible();
    await expect(page.locator("or-map-legend")).not.toBeVisible();
  });

  /**
   * @given assets of two types are set up, with 2 of the first type and 1 of the second
   * @when navigating to the map page and opening the legend
   * @then each legend item's count badge shows the number of assets of that type
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should display correct count badge for each asset type", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const assetInfos = (await manager.api.AssetModelResource.getAssetInfos()).data;
    // 2 of the first type, 1 of the second — gives varied badge values to test
    const baseAssets = getAssetsForAllTypes(assetInfos, { limit: 2 });
    const assets = [...baseAssets, { ...baseAssets[0], name: baseAssets[0].name + "_2" }];

    const expectedCounts: Record<string, number> = {};
    for (const a of assets) {
      expectedCounts[a.type!] = (expectedCounts[a.type!] ?? 0) + 1;
    }

    await manager.setup("smartcity", { assets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    await page.locator("or-map-legend #legend-title").click();
    await expect(page.locator("or-map-legend #legend-list")).toBeVisible();

    // Read the sorted type order directly from the component to correlate items to counts
    const sortedTypes: string[] = await page
      .locator("or-map-legend")
      .evaluate((el: any) =>
        [...el.assetTypes].sort((a: string, b: string) =>
          (el._assetTypesInfo[a]?.label ?? a).localeCompare(el._assetTypesInfo[b]?.label ?? b)
        )
      );

    const items = page.locator("or-map-legend or-vaadin-item");
    expect(await items.count()).toBe(sortedTypes.length);

    for (let i = 0; i < sortedTypes.length; i++) {
      const expected = String(expectedCounts[sortedTypes[i]]);
      await expect(items.nth(i).locator("or-vaadin-badge")).toHaveText(expected);
    }
  });

  test.describe(() => {
    test.use({ storageState: adminStatePath });

    /**
     * @given Assets with location are set up in the "smartcity" realm
     * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
     * @and Navigating to the "map" page
     * @then Show the asset type legend
     * @when clicking the open menu button
     * @then the asset type legend opens and shows the asset types
     * @and the asset types checkboxes are checked
     * @when Unchecking the asset type check boxes 1 by 1
     * @then Hides the markers
     * @when Switching to the "master" realm
     * @then Hides the legend
     * @and No markers are shown
     * @when Switching back to the "smartcity" realm
     * @then Shows the legend again
     * @and All asset types are checked
     * @and All markers are shown
     *
     * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
     */
    test("should reset when switching realm", async ({ page, manager, browserName }) => {
      test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

      const assetInfos = (await manager.api.AssetModelResource.getAssetInfos()).data;
      const assets = getAssetsForAllTypes(assetInfos, { limit: 3 });

      await manager.setup("smartcity", { assets });
      await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
      await manager.goToRealmStartPage("master");
      await manager.switchToRealmByRealmPicker("smartcity");

      await expect(page.locator("or-map")).toBeVisible();
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));
      await expect(page.locator(".or-map-marker")).toHaveCount(assets.length);

      await page.locator("or-map-legend #legend-title").click();
      await expect(page.locator("or-map-legend #legend-list")).toBeVisible();

      const assetTypes = getAssetTypes(assets);
      const options = await page.locator("or-map-legend or-vaadin-item").all();
      expect(options.length).toBe(assetTypes.length);

      for (const option of options) {
        await expect(option.getByRole("checkbox")).toBeChecked();
        await option.getByRole("checkbox").uncheck();
      }
      await expect(page.locator(".or-map-marker")).toHaveCount(0);

      await manager.switchToRealmByRealmPicker("master");
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));
      await expect(page.locator(".or-map-marker")).toHaveCount(0);
      await expect(page.locator("or-map-legend")).not.toBeVisible();

      await manager.switchToRealmByRealmPicker("smartcity");
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));
      await expect(page.locator(".or-map-marker")).toHaveCount(assets.length);
      // Re-query items — DOM was recreated after realm switch
      await page.locator("or-map-legend #legend-title").click();
      const optionsAfterSwitch = await page.locator("or-map-legend or-vaadin-item").all();
      for (const option of optionsAfterSwitch) {
        await expect(option.getByRole("checkbox")).toBeChecked();
      }
    });
  });
});

test.describe("Preset filters", () => {
  const bbox = { west: 4.4859, south: 51.9163, east: 4.4864, north: 51.9166 };

  // 3 ThingAssets (no notes value) + 2 ThingAssets (notes="error") = 5 total
  const thingAssets = [0, 1, 2].map((i) =>
    assignRandomLocationInArea(
      { name: `Thing ${i}`, type: "ThingAsset", realm: "smartcity", attributes: { ...commonAttrs } } as Asset,
      bbox
    )
  );

  const thingAssetsWithError = [0, 1].map((i) =>
    assignRandomLocationInArea(
      {
        name: `Thing Error ${i}`,
        type: "ThingAsset",
        realm: "smartcity",
        attributes: { ...commonAttrs, notes: { name: "notes", type: "text", value: "error" } },
      } as Asset,
      bbox
    )
  );

  const allAssets = [...thingAssets, ...thingAssetsWithError];

  const filters: MapFilter[] = [
    // Filter 1: match all ThingAssets
    { query: { types: ["ThingAsset"] } },
    // Filter 2: match only ThingAssets with notes="error"
    {
      query: {
        types: ["ThingAsset"],
        attributes: {
          items: [
            {
              name: { predicateType: "string", value: "notes" },
              value: { predicateType: "string", value: "error" },
            },
          ],
        },
      },
    },
    // Filter 3: match a type that has no assets — label masks the unresolvable asset type name
    { label: "Water Meters", query: { types: ["WaterMeterAsset"] } },
  ];

  // Mirrors assetMatchesQuery client-side logic for deriving expected counts from test data
  function assetMatchesFilter(asset: Asset, filter: MapFilter): boolean {
    const query = filter.query;
    if (query.types?.length && !query.types.includes(asset.type!)) return false;
    const items = ((query.attributes as any)?.items ?? []) as any[];
    return items.every((item: any) => asset.attributes?.[item.name?.value]?.value === item.value?.value);
  }

  // Derive expected counts automatically from the test asset data
  const filterCases = [
    { filterIndex: 0, expectedCount: allAssets.length },
    ...filters.map((f, i) => ({
      filterIndex: i + 1,
      expectedCount: allAssets.filter((a) => assetMatchesFilter(a, f)).length,
    })),
  ];
  // Resolves to: [{ 0, 5 }, { 1, 5 }, { 2, 2 }, { 3, 0 }]

  /**
   * @given no filters are configured on the map page
   * @when navigating to the map page
   * @then the preset filter component is not rendered
   * @and the asset card CSS variable is set to top: 10px (no filter offset needed)
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should not render when no filters are configured", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", { assets: allAssets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false } } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator("or-map-preset-filter")).not.toBeVisible();

    const cardTopVar = await page
      .locator("page-map")
      .evaluate((el: HTMLElement) => el.style.getPropertyValue("--card-top"));
    expect(cardTopVar).toBe("10px");
  });

  /**
   * @given filters are configured on the map page
   * @when navigating to the map page
   * @then the preset filter component is rendered
   * @and the asset card CSS variable is set to top: 56px to clear the filter dropdown
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should render and push asset card down when filters are configured", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", { assets: allAssets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false }, filters } } });
    await manager.goToRealmStartPage("smartcity");

    await expect(page.locator("or-map-preset-filter")).toBeVisible();

    const cardTopVar = await page
      .locator("page-map")
      .evaluate((el: HTMLElement) => el.style.getPropertyValue("--card-top"));
    expect(cardTopVar).toBe("56px");
  });

  /**
   * Parameterised: for each filter option (including "All"), verify marker count and badge text.
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  for (const { filterIndex, expectedCount } of filterCases) {
    const filterDesc = filterIndex === 0 ? `"All" default selection` : `filter option ${filterIndex}`;
    const badgeText = String(expectedCount);

    test(`should show ${expectedCount} markers for ${filterDesc}`, async ({ page, manager, browserName }) => {
      /**
       * @given assets are set up in the "smartcity" realm
       * @and preset filters are configured on the map page
       * @when navigating to the map page
       * @and selecting filter option at index ${filterIndex}
       * @then ${expectedCount} asset markers are visible
       * @and the selected-state counter badge shows ${badgeText}
       */
      test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

      await manager.setup("smartcity", { assets: allAssets });
      await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false }, filters } } });
      await manager.goToRealmStartPage("smartcity");
      await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

      if (filterIndex > 0) {
        await page.locator("or-vaadin-select").click();
        // Wait for any option to appear, then select by value intersected with role="option"
        // to ensure we only click the visible overlay item, not Vaadin's hidden template copy
        await page.getByRole("option").first().waitFor({ state: "visible" });
        await page
          .getByRole("option")
          .and(page.locator(`[value="${filterIndex}"]`))
          .click();
      }

      await expect(page.locator(".or-map-marker")).toHaveCount(expectedCount);
      await expect(page.locator("or-map-preset-filter or-vaadin-select + or-vaadin-badge")).toHaveText(badgeText);
    });
  }

  /**
   * @given filters are configured, one of which has an explicit label and an unresolvable asset type
   * @when opening the preset filter dropdown
   * @then the labelled option displays the configured label instead of a derived type name
   * @when selecting that option
   * @then the select field shows the configured label as the selected value
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should use label property instead of derived type name", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", { assets: allAssets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false }, filters } } });
    await manager.goToRealmStartPage("smartcity");

    await page.locator("or-vaadin-select").click();
    await page.getByRole("option").first().waitFor({ state: "visible" });

    // "WaterMeterAsset" is not a registered type — without a label, getAssetTypeLabel returns "".
    // The label: "Water Meters" on filter 3 should appear as the option text instead.
    await expect(page.getByRole("option").and(page.locator('[value="3"]')).locator(".filter-item__label")).toHaveText(
      "Water Meters"
    );

    await page.getByRole("option").and(page.locator('[value="3"]')).click();
    await expect(page.locator("or-vaadin-select")).toContainText("Water Meters");
  });

  /**
   * @given assets are set up in the "smartcity" realm
   * @and preset filters are configured
   * @when opening the preset filter dropdown
   * @then each option's count badge matches the number of assets that satisfy that filter
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should display correct count badges for each option in the dropdown", async ({
    page,
    manager,
    browserName,
  }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", { assets: allAssets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false }, filters } } });
    await manager.goToRealmStartPage("smartcity");
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    await page.locator("or-vaadin-select").click();
    const overlayOptions = page.getByRole("option");
    await overlayOptions.first().waitFor({ state: "visible" });

    for (const { filterIndex, expectedCount } of filterCases) {
      const badgeText = String(expectedCount);
      // Select by value attribute (sort may reorder options relative to filterIndex)
      await expect(
        page
          .getByRole("option")
          .and(page.locator(`[value="${filterIndex}"]`))
          .locator("or-vaadin-badge")
      ).toHaveText(badgeText);
    }
  });

  /**
   * @given a filter is configured with default: true
   * @when navigating to the map page for the first time (no stored selection)
   * @then that filter is pre-selected without any user interaction
   * @and the marker count reflects the default filter
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should pre-select the filter marked as default", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const filtersWithDefault: MapFilter[] = [
      { query: { types: ["ThingAsset"] } },
      {
        label: "Errors",
        default: true,
        query: {
          types: ["ThingAsset"],
          attributes: {
            items: [
              {
                name: { predicateType: "string", value: "notes" },
                value: { predicateType: "string", value: "error" },
              },
            ],
          },
        },
      },
    ];

    await manager.setup("smartcity", { assets: allAssets });
    await manager.configureAppConfig({
      pages: { map: { clustering: { cluster: false }, filters: filtersWithDefault } },
    });
    await manager.goToRealmStartPage("smartcity");
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    // The "Errors" filter is default — it should activate without any user interaction
    const expectedCount = thingAssetsWithError.length;
    await expect(page.locator(".or-map-marker")).toHaveCount(expectedCount);
    await expect(page.locator("or-map-preset-filter or-vaadin-select + or-vaadin-badge")).toHaveText(
      String(expectedCount)
    );
  });

  test.describe(() => {
    test.use({ storageState: adminStatePath });

    /**
     * @given filters configured with different realm restrictions
     * @when in a realm not included in a filter's realms list
     * @then that filter is absent from the dropdown
     * @when switching to a realm that is included
     * @then the filter appears in the dropdown
     *
     * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
     */
    test("should show realm-scoped filters only in matching realms", async ({ page, manager, browserName }) => {
      test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

      const realmFilters: MapFilter[] = [
        { label: "Global", query: { types: ["ThingAsset"] } },
        { label: "Smartcity only", query: { types: ["ThingAsset"] }, realms: ["smartcity"] },
      ];

      await manager.setup("smartcity", { assets: allAssets });
      await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false }, filters: realmFilters } } });

      // In master realm: the smartcity-only filter is inactive → "All" + 1 global = 2 options
      await manager.goToRealmStartPage("master");
      await expect(page.locator("or-map-preset-filter")).toBeVisible();
      await page.locator("or-map-preset-filter or-vaadin-select").click();
      await page.getByRole("option").first().waitFor({ state: "visible" });
      await expect(page.getByRole("option")).toHaveCount(2);
      await page.keyboard.press("Escape");

      // Switch to smartcity: both filters are active → "All" + 2 = 3 options
      await manager.switchToRealmByRealmPicker("smartcity");
      await expect(page.locator("or-map-preset-filter")).toBeVisible();
      await page.locator("or-map-preset-filter or-vaadin-select").click();
      await page.getByRole("option").first().waitFor({ state: "visible" });
      await expect(page.getByRole("option")).toHaveCount(3);
    });
  });

  /**
   * @given filters are configured and a non-default filter was previously selected
   * @when the page is reloaded
   * @then the previously selected filter is restored from localStorage
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should restore last selected filter after page reload", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", { assets: allAssets });
    await manager.configureAppConfig({ pages: { map: { clustering: { cluster: false }, filters } } });
    await manager.goToRealmStartPage("smartcity");
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    // Select the error filter (value="2": filters[1], 2 matching assets)
    await page.locator("or-vaadin-select").click();
    await page.getByRole("option").first().waitFor({ state: "visible" });
    await page.getByRole("option").and(page.locator('[value="2"]')).click();
    await expect(page.locator(".or-map-marker")).toHaveCount(thingAssetsWithError.length);

    // Reload the page — localStorage persists (only cleared on OREvent.LOGIN / new session, not on reload)
    await page.reload();
    await expect(page.locator("or-map")).toBeVisible();
    await page.locator("or-map").evaluate((map: OrMap) => map.flyTo(undefined, 10));

    // The previously selected filter should be restored
    await expect(page.locator(".or-map-marker")).toHaveCount(thingAssetsWithError.length);
    await expect(page.locator("or-map-preset-filter or-vaadin-select + or-vaadin-badge")).toHaveText(
      String(thingAssetsWithError.length)
    );
  });
});

test.describe("Navigation control", () => {
  const bbox = { west: 4.4859, south: 51.9163, east: 4.4864, north: 51.9166 };

  /**
   * @given a map is loaded at the default zoom level
   * @when clicking the zoom in button
   * @then the map zoom level increases by one step
   * @when clicking the zoom out button
   * @then the map zoom level decreases back
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("zoom buttons should increase and decrease map zoom level", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const asset = assignRandomLocationInArea(
      { name: "Thing", type: "ThingAsset", realm: "smartcity", attributes: { ...commonAttrs } } as Asset,
      bbox
    );
    await manager.setup("smartcity", { assets: [asset] });
    await manager.goToRealmStartPage("smartcity");
    await expect(page.locator("or-map")).toBeVisible();

    const getZoom = () => page.locator("or-map").evaluate((el) => (el as any)._map?._map?.getZoom() as number);
    const waitForMapIdle = () =>
      expect.poll(() => page.locator("or-map").evaluate((el) => (el as any)._map?._map?.isMoving())).toBe(false);

    const zoomBefore = await getZoom();

    await page.getByTitle("Zoom in").click();
    await waitForMapIdle();
    expect(await getZoom()).toBeGreaterThan(zoomBefore);

    const zoomAfterIn = await getZoom();
    await page.getByTitle("Zoom out").click();
    await waitForMapIdle();
    expect(await getZoom()).toBeLessThan(zoomAfterIn);
  });

  /**
   * @given a map is loaded
   * @when the map bearing is set to 90 degrees programmatically
   * @then the compass icon rotates to reflect the inverted bearing (-90 deg)
   * @when the compass reset button is clicked
   * @then the map bearing resets to 0 and the compass icon rotation resets to 0 deg
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("compass button should reset bearing to north and update icon rotation", async ({
    page,
    manager,
    browserName,
  }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const asset = assignRandomLocationInArea(
      { name: "Thing", type: "ThingAsset", realm: "smartcity", attributes: { ...commonAttrs } } as Asset,
      bbox
    );
    await manager.setup("smartcity", { assets: [asset] });
    await manager.goToRealmStartPage("smartcity");
    await expect(page.locator("or-map")).toBeVisible();

    await page.locator("or-map").evaluate((el) => (el as any)._map?._map?.setBearing(90));

    // OrMapNavigation negates the bearing: _bearing = -getBearing()
    const compass = page.locator('or-map-navigation or-icon[icon="or:compass"]');
    await expect(compass).toHaveAttribute("style", "transform: rotate(-90deg)");

    await page.getByTitle("Reset bearing to north").click();
    await expect.poll(() => page.locator("or-map").evaluate((el) => (el as any)._map?._map?.isMoving())).toBe(false);

    const bearing = await page.locator("or-map").evaluate((el) => (el as any)._map?._map?.getBearing() as number);
    expect(bearing).toBeCloseTo(0, 1);
    await expect(compass).toHaveAttribute("style", "transform: rotate(0deg)");
  });
});

test.describe("Geocoder control", () => {
  const nominatimResponse = {
    type: "FeatureCollection",
    features: [
      {
        type: "Feature",
        bbox: [4.47, 51.9, 4.5, 51.93],
        properties: { display_name: "Rotterdam, Netherlands" },
        geometry: { type: "Point", coordinates: [4.485, 51.915] },
      },
    ],
  };

  /**
   * @given a map with a geocodeUrl configured in mapsettings.json
   * @when navigating to the map page
   * @then the geocoder shows a collapsed search button
   * @when clicking the search button
   * @then the search combo box becomes visible
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should start collapsed and expand to a combo box on click", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await manager.setup("smartcity", {});
    await manager.goToRealmStartPage("smartcity");
    await expect(page.locator("or-map")).toBeVisible();

    await expect(page.getByTitle("Search location")).toBeVisible();
    await expect(page.locator("or-vaadin-combo-box")).not.toBeVisible();

    await page.getByTitle("Search location").click();
    await expect(page.locator("or-vaadin-combo-box")).toBeVisible();
  });

  /**
   * @given a map with geocoding configured
   * @and the Nominatim API returns a mocked result
   * @when typing a search query
   * @then a suggestion appears in the dropdown
   * @when selecting the suggestion
   * @then a marker is placed on the map at the result location
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should show suggestions on typing and place a marker on selection", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    await page.route("https://nominatim.openstreetmap.org/search**", (route) =>
      route.fulfill({ contentType: "application/geo+json", json: nominatimResponse })
    );

    await manager.setup("smartcity", {});
    await manager.goToRealmStartPage("smartcity");
    await expect(page.locator("or-map")).toBeVisible();

    await page.getByTitle("Search location").click();
    await page.locator("or-vaadin-combo-box").getByRole("combobox").pressSequentially("Rotterdam");

    const suggestion = page.locator("vaadin-combo-box-item").filter({ hasText: "Rotterdam, Netherlands" });
    await expect(suggestion).toBeVisible();
    await suggestion.click();

    await expect(page.locator(".maplibregl-marker")).toBeVisible();
  });

  /**
   * @given a map with bounds configured in mapsettings.json
   * @when searching for a location outside the configured map bounds (Eindhoven)
   * @then the Nominatim request includes viewbox and bounded=1 params derived from the map bounds
   * @and a "No results found" item is shown because Eindhoven is outside the bounded area
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should restrict geocoder search to configured map bounds", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const emptyResponse = { type: "FeatureCollection", features: [] };
    await page.route("https://nominatim.openstreetmap.org/search**", (route) =>
      route.fulfill({ contentType: "application/geo+json", json: emptyResponse })
    );

    await manager.setup("smartcity", {});
    await manager.goToRealmStartPage("smartcity");
    await expect(page.locator("or-map")).toBeVisible();

    const requestPromise = page.waitForRequest("https://nominatim.openstreetmap.org/search**");
    await page.getByTitle("Search location").click();
    await page.locator("or-vaadin-combo-box").getByRole("combobox").pressSequentially("Eindhoven");

    const request = await requestPromise;
    expect(request.url()).toContain("viewbox=");
    expect(request.url()).toContain("bounded=1");
    await expect(page.locator("vaadin-combo-box-item.no-results-item")).toBeVisible();
    await expect(page.locator("vaadin-combo-box-item")).toHaveCount(1);
  });
});

test.describe("Geolocate control", () => {
  /**
   * @given navigator.geolocation is mocked to return a fixed position
   * @when navigating to the map and clicking the "Find my location" button
   * @then the map flies to the mocked coordinates
   *
   * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
   */
  test("should fly to user location when geolocation is granted", async ({ page, manager, browserName }) => {
    test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

    const mockLat = 51.9163;
    const mockLng = 4.4859;
    await page.addInitScript(
      ({ lat, lng }) => {
        Object.defineProperty(navigator, "geolocation", {
          value: {
            getCurrentPosition: (success: PositionCallback) => {
              success({
                coords: {
                  latitude: lat,
                  longitude: lng,
                  accuracy: 10,
                  altitude: null,
                  altitudeAccuracy: null,
                  heading: null,
                  speed: null,
                },
                timestamp: Date.now(),
              } as GeolocationPosition);
            },
          },
          configurable: true,
        });
      },
      { lat: mockLat, lng: mockLng }
    );

    await manager.setup("smartcity", {});
    await manager.goToRealmStartPage("smartcity");
    await expect(page.locator("or-map")).toBeVisible();

    await expect(page.getByTitle("Find my location")).toBeVisible();
    await page.getByTitle("Find my location").click();

    await expect.poll(() => page.locator("or-map").evaluate((el) => (el as any)._map?._map?.isMoving())).toBe(false);

    const center = await page.locator("or-map").evaluate((el) => {
      const c = (el as any)._map?._map?.getCenter();
      return { lng: c?.lng as number, lat: c?.lat as number };
    });
    expect(center.lng).toBeCloseTo(mockLng, 1);
    expect(center.lat).toBeCloseTo(mockLat, 1);
  });
});

/**
 * @given The "smartcity" map view zooms in past the highest zoom level of the map tiles
 * @when Logging in to OpenRemote "smartcity" realm as "smartcity"
 * @and The map has loaded
 * @then Each map tile is requested once
 *
 * @skip This test is skipped on Firefox because headless mode does not support WebGL required by maplibre
 */
test("should request each map tile once", async ({ page, manager, browserName }) => {
  test.skip(browserName === "firefox", "firefox headless mode does not support webgl required by maplibre");

  const tiles: string[] = [];
  page.on("request", (request) => {
    const { pathname } = new URL(request.url());
    if (pathname.includes("/map/tile/")) tiles.push(pathname);
  });

  await manager.setup("smartcity");
  await manager.goToRealmStartPage("smartcity");
  type MapElement = HTMLElement & { _map?: { _map?: { loaded(): boolean } } };
  await expect.poll(() => page.locator("or-map").evaluate((el: MapElement) => el._map?._map?.loaded())).toBe(true);

  expect(tiles.length).toBeGreaterThan(0);
  // Splitting the zoom levels past the tiles' highest one loaded each tile again for every split tile
  expect(tiles).toEqual([...new Set(tiles)]);
});

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});
