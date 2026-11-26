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
import { type BasePage, type Locator, type Page, type Shared, expect } from "@openremote/test";
import type { Manager } from "../manager";

export class UsersPage implements BasePage {
  private _allRoles?: string[];

  constructor(
    private readonly page: Page,
    private readonly shared: Shared,
    private readonly manager: Manager
  ) {}

  async goto() {
    this.manager.navigateToMenuItem("Users");
  }

  async gotoUserCreation(realm: string, type: "serviceuser" | "regular") {
    return this.page.goto(this.manager.getAppUrl(realm) + `#/users/new/${type}`);
  }

  /**
   * Get role checkbox locator by name.
   * @param role The role name
   */
  getRole(role: string): Locator {
    return this.page.getByRole("checkbox", { name: role });
  }

  /**
   * Toggle composite roles when configuring a user. These select the roles asserted by
   * {@link toHaveRoles}, they are not roles in their own right.
   * @param composites The composite roles to toggle
   */
  async toggleCompositeRoles(...composites: string[]) {
    const roleSelector = this.page.locator("or-vaadin-multi-select-combo-box", { hasText: "Manager roles" });
    await roleSelector.click();
    for (const composite of composites) {
      await this.page.getByRole("option", { name: composite }).click();
    }
    await roleSelector.locator("#toggleButton").click();
  }

  /**
   * Every role the current realm offers, read from the manager so that a role added to the backend
   * is asserted without this fixture being updated. Composite roles are excluded; they select
   * roles rather than being one.
   */
  private async getAllRoles(): Promise<string[]> {
    if (!this._allRoles) {
      const realm = await this.manager.getDisplayRealm();
      const roles = (await this.manager.getClientRoles(realm)) ?? [];
      expect(roles, `client roles of realm "${realm}"`).not.toHaveLength(0);
      this._allRoles = roles.filter((role) => !role.composite).map((role) => role.name!);
    }
    return this._allRoles;
  }

  /**
   * Assert selected roles, and that every other role is unselected.
   * @param roles The roles expected to be checked
   */
  async toHaveRoles(...roles: string[]) {
    for (const role of roles) {
      await expect(this.getRole(role)).toBeChecked();
      await expect(this.getRole(role)).toBeDisabled();
    }
    const all = await this.getAllRoles();
    for (const role of all.filter((r) => !roles.includes(r))) {
      await expect(this.getRole(role)).not.toBeChecked();
      await expect(this.getRole(role)).not.toBeDisabled();
    }
  }

  /** Assert that every role the realm offers is selected. */
  async toHaveAllRoles() {
    await this.toHaveRoles(...(await this.getAllRoles()));
  }

  /**
   * Create a user with read and write access for the current realm.
   *
   * Internally checks whether the roles are correctly set.
   *
   * @param username The users' username
   * @param password The users' password
   * @param tag Optional tag for the user
   */
  async addUser(username: string, password: string, tag?: string) {
    await this.page
      .locator("#content")
      .filter({ hasText: "Regular users" })
      .getByRole("button", { name: "Add User" })
      .click();
    await this.page.getByLabel("Username", { exact: true }).fill(username);
    await this.page.getByLabel("Password", { exact: true }).fill(password);
    await this.page.getByLabel("Repeat password", { exact: true }).fill(password);
    if (tag) {
      await this.page.getByLabel("Tag", { exact: true }).fill(tag);
    }
    await this.toggleCompositeRoles("Read", "Write");
    await this.toHaveAllRoles();
    await this.page.getByRole("button", { name: "Create" }).click();
  }

  /**
   * Create a service user for the current realm.
   *
   * @param username The service user's username
   * @param tag Optional tag for the service user
   */
  async addServiceUser(username: string, tag?: string): Promise<void> {
    await this.page
      .locator("#content")
      .filter({ hasText: "Service users" })
      .getByRole("button", { name: "Add User" })
      .click();
    await this.page.getByLabel("Username").fill(username);
    if (tag) {
      await this.page.getByLabel("Tag").fill(tag);
    }
    await this.toggleCompositeRoles("Read", "Write");
    await this.toHaveAllRoles();
    await this.page.getByRole("button", { name: "Create" }).click();
  }

  /**
   * Search for users in the regular user table.
   * @param searchTerm The term to search for
   */
  async searchRegularUsers(searchTerm: string) {
    const panel = this.page.locator("#content").filter({ hasText: "Regular users" }).first();
    const input = panel.locator('*[placeholder="Search"] input');
    await input.fill(searchTerm);
  }

  /**
   * Search for users in the service user table.
   * @param searchTerm The term to search for
   */
  async searchServiceUsers(searchTerm: string) {
    const panel = this.page.locator("#content").filter({ hasText: "Service users" }).first();
    const input = panel.locator('*[placeholder="Search"] input');
    await input.fill(searchTerm);
  }
}
