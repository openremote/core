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
import { adminStatePath, test } from "./fixtures/manager.js";
import { custom } from "./fixtures/data/roles.js";
import { ClientRole, type Role } from "@openremote/model";

test.use({ storageState: adminStatePath });

/**
 * @given The realm "smartcity" is set up
 * @when Logging into OpenRemote "master" realm as "admin"
 * @and Switching to the "smartcity" realm
 * @and Navigating to the "Roles" page
 * @and Creating a new role named "Custom" with specific roles
 * @and Navigating to the "Users" page and selecting a user
 * @and Assigning only the "Custom" role to the user and verifying its roles
 * @and Switching back to the original roles and verifying all roles are selected
 * @then The new role is created and assigned correctly with the expected roles
 */
test("Create a new role, assign it to a user, and verify its roles", async ({ page, manager, shared, usersPage }) => {
  await manager.setup("smartcity");
  await manager.goToRealmStartPage("master");
  await manager.switchToRealmByRealmPicker("smartcity");
  await manager.navigateToMenuItem("Roles");
  await page.getByText("Add Role").click();

  const lastRow = page.locator("#table-roles tbody tr").last();
  await lastRow.getByRole("textbox", { name: "Role" }).fill(custom.name);
  await lastRow.getByRole("textbox", { name: "Description" }).fill(custom.description);
  await lastRow.getByRole("checkbox", { name: "assets: Read asset data" }).click();
  await lastRow.getByRole("checkbox", { name: "assets: Write asset data" }).click();

  await shared.interceptRequest<Role[]>("**/user/master/roles", (roles) => {
    const role = roles?.find(({ name }) => name === "Custom");
    if (role) manager.role = { realm: "smartcity", role };
  });
  await page.getByRole("button", { name: "create" }).click();
  await expect(page.getByText("Custom").first()).toBeVisible();

  await manager.navigateToMenuItem("Users");
  await page.getByRole("cell", { name: "smartcity" }).click();

  await usersPage.toggleCompositeRoles("Read", "Write", "Custom");
  await usersPage.toHaveRoles(ClientRole.READ_ASSETS, ClientRole.WRITE_ASSETS);

  await usersPage.toggleCompositeRoles("Read", "Write", "Custom");
  await usersPage.toHaveAllRoles();
});

/**
 * @given The realm "smartcity" with a role named "Custom" is set up
 * @when Logging into OpenRemote "master" realm as "admin"
 * @and Switching to the "smartcity" realm
 * @and Navigating to the "Roles" page
 * @and Deleting the "Custom" role
 * @then The "Custom" role should no longer be visible in the roles list
 */
test("Delete an existing role and verify it no longer appears", async ({ page, manager }) => {
  await manager.setup("smartcity", { role: custom });
  await manager.goToRealmStartPage("master");
  await manager.switchToRealmByRealmPicker("smartcity");
  await manager.navigateToMenuItem("Roles");

  const row = page.locator("#table-roles tbody tr", { hasText: "Custom" });
  await row.click();
  await row.locator("+ tr").getByRole("button", { name: "Delete" }).click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Delete" }).click();
  await expect(page.locator("text=Custom")).toHaveCount(0);
});

test.afterEach(async ({ manager }) => {
  await manager.cleanUp();
});
