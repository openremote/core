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
import type { AssetQuery } from "@openremote/model";
import { getAssetAt } from "./fixtures/data/assets";
import { gatewayAsset, gatewayTunnels, unavailableGatewayId } from "./fixtures/data/gateway-tunnels";
import { adminStatePath, test } from "./fixtures/manager";

test.use({ storageState: adminStatePath });

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});

/**
 * @given Two tunnels point to an existing gateway and another gateway name is unavailable
 * @when The Gateway tunnels page opens
 * @then Both tunnels show the gateway name as an underlined link to the asset
 * @and The other tunnel shows the gateway ID
 * @and Sorting by Gateway uses the visible name or ID
 * @and Clicking the named gateway opens its asset details
 */
test("should link tunnels to their gateway assets by name", async ({ page, manager, assetViewer }) => {
  const { data: assetInfos } = await manager.api.AssetModelResource.getAssetInfos();
  const gatewayDefaults = getAssetAt(
    assetInfos.filter(({ assetDescriptor }) => assetDescriptor?.name === gatewayAsset.type)
  );
  await manager.createAsset({ ...gatewayDefaults, ...gatewayAsset });
  await page.route("**/api/master/gateway/tunnel/master", (route) => route.fulfill({ json: gatewayTunnels }));

  const gatewayQuery = page.waitForRequest(
    (request) =>
      request.method() === "POST" &&
      new URL(request.url()).pathname === "/api/master/asset/query" &&
      (request.postDataJSON() as AssetQuery).ids?.includes(gatewayAsset.id) === true
  );
  const [request] = await Promise.all([gatewayQuery, page.goto(`${manager.getAppUrl("master")}#/gateway-tunnel`)]);
  const query: AssetQuery = request.postDataJSON();
  expect(query.ids).toEqual([gatewayAsset.id, unavailableGatewayId]);
  expect(query.realm?.name).toBe(gatewayAsset.realm);

  const table = page.locator("page-gateway-tunnel").getByRole("table");
  const links = table.getByRole("link", { name: gatewayAsset.name });
  // The gateway cells previously displayed only the asset ID.
  await expect(links).toHaveCount(2);
  const link = table.getByRole("row").filter({ hasText: gatewayTunnels[0].id }).getByRole("link", {
    name: gatewayAsset.name,
  });
  await expect(link).toHaveCSS("text-decoration-line", "underline");
  await expect(table.getByRole("link", { name: unavailableGatewayId })).toBeVisible();

  const gatewayHeader = table.getByRole("columnheader", { name: "Gateway" });
  const gatewayLinks = table.getByRole("link");
  await gatewayHeader.click();
  await expect(gatewayLinks).toHaveText([gatewayAsset.name, gatewayAsset.name, unavailableGatewayId]);
  await gatewayHeader.click();
  // Sorting by the link's URL would put the named gateway's smaller ID first.
  await expect(gatewayLinks).toHaveText([unavailableGatewayId, gatewayAsset.name, gatewayAsset.name]);

  await link.click();
  await expect(page).toHaveURL(new RegExp(`#/assets/false/${gatewayAsset.id}$`));
  await expect(assetViewer.getHeaderLocator(gatewayAsset.name)).toBeVisible();
});
