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
import { gatewayAsset, gatewayTunnels, unavailableGatewayId } from "./fixtures/data/gateway-tunnels";
import { adminStatePath, test } from "./fixtures/manager";

test.use({ storageState: adminStatePath });

/**
 * @given Two tunnels point to the same gateway and another gateway name is unavailable
 * @when The Gateway tunnels page opens
 * @then Both tunnels show the gateway name as an underlined link to the asset
 * @and The other tunnel shows the gateway ID
 * @and Sorting by Gateway uses the visible name or ID
 */
test("should link tunnels to their gateway assets by name", async ({ page, manager }) => {
  await page.route("**/api/master/gateway/tunnel/master", (route) => route.fulfill({ json: gatewayTunnels }));
  await page.route("**/api/master/asset/query", (route) => {
    const query: AssetQuery = route.request().postDataJSON();
    if (!query.ids?.includes(gatewayAsset.id)) return route.fallback();

    expect(query.ids).toEqual([gatewayAsset.id, unavailableGatewayId]);
    expect(query.realm?.name).toBe(gatewayAsset.realm);
    return route.fulfill({ json: [gatewayAsset] });
  });

  await page.goto(`${manager.getAppUrl("master")}#/gateway-tunnel`);
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
  await expect(page.locator("page-assets")).toBeVisible();
});
