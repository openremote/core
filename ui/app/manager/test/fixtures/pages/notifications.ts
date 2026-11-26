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
import type { Manager } from "../manager.js";

/**
 * Page object for the notifications management page (page-notifications).
 *
 * The page is built on the Vaadin component wrappers: the create/details dialogs are `or-vaadin-dialog`s whose
 * content is teleported into a `vaadin` overlay, so dialog content (form, footer buttons) is located by its own
 * id/role rather than via the dialog host element. Selects open an overlay whose items expose role="option".
 */
export class NotificationsPage implements BasePage {
  constructor(
    private readonly page: Page,
    private readonly shared: Shared,
    private readonly manager: Manager
  ) {}

  async goto() {
    await this.manager.navigateToMenuItem("Notifications");
    await expect(this.getTable()).toBeVisible();
  }

  // --- Table -------------------------------------------------------------

  getTable(): Locator {
    return this.page.locator("or-notifications-table");
  }

  getRows(): Locator {
    return this.getTable().locator("tbody tr");
  }

  /** A table row containing the given text (e.g. a notification's subject/title). */
  getRowByText(text: string): Locator {
    return this.getRows().filter({ hasText: text });
  }

  /** The status badge (Sent/Delivered/Error) of the (first) row containing the given text. */
  getStatusBadge(rowText: string): Locator {
    return this.getRowByText(rowText).first().locator(".notification-status");
  }

  // --- Header / filters --------------------------------------------------

  getCreateButton(): Locator {
    return this.page.getByRole("button", { name: "Send new" });
  }

  getSourceSelect(): Locator {
    return this.page.locator(".controls or-vaadin-select").first();
  }

  getDatePickers(): Locator {
    return this.page.locator(".controls or-vaadin-date-time-picker");
  }

  /**
   * Set the from/to date-range filter. Values are local `YYYY-MM-DDTHH:mm` strings (as the picker uses).
   * The picker only commits on `change`, which isn't fired by a programmatic value assignment, so we set the
   * value and dispatch `change` ourselves to trigger the page's reload.
   */
  async setDateRange(fromValue: string, toValue: string) {
    const pickers = this.getDatePickers();
    await this.setPickerValue(pickers.nth(0), fromValue);
    await this.setPickerValue(pickers.nth(1), toValue);
  }

  private async setPickerValue(picker: Locator, value: string) {
    await picker.evaluate((el, val) => {
      (el as unknown as { value: string }).value = val;
      el.dispatchEvent(new Event("change", { bubbles: true }));
    }, value);
  }

  /**
   * Click the option with the given exact label in the currently-open Vaadin select overlay.
   *
   * Vaadin teleports the items into a `<vaadin-select-overlay>` and positions the currently-selected item
   * under the trigger; scoping the lookup to the open overlay (and waiting for it to open/close) avoids
   * clicking a stale or repositioning item, which could otherwise re-pick the previously-selected value.
   */
  private async pickOverlayOption(label: string) {
    // Vaadin slots the items into a <vaadin-select-list-box> in the select's light DOM (projected into the
    // overlay), so the options live under the list box, not the overlay element. Inert copies exist for the
    // responsive desktop/mobile selects; the open one is the only visible list box.
    const listBox = this.page.locator("vaadin-select-list-box:visible");
    await expect(listBox).toBeVisible();
    await listBox.getByRole("option", { name: label, exact: true }).click();
    await expect(listBox).not.toBeVisible();
  }

  /** Pick a source from the source filter via its overlay (e.g. "All sources", "Client"). */
  async setSourceFilter(label: string) {
    await this.getSourceSelect().click();
    await this.pickOverlayOption(label);
  }

  // --- Create dialog / form ---------------------------------------------

  /** The create form is teleported into an overlay; target it by its (unique) id. */
  getCreateForm(): Locator {
    return this.page.locator("#notificationForm");
  }

  getSubmitButton(): Locator {
    return this.page.getByRole("button", { name: "Create", exact: true });
  }

  async openCreateDialog() {
    await this.getCreateButton().click();
    await expect(this.getCreateForm()).toBeVisible();
  }

  /** Choose the message type (e.g. "Push", "Email"). */
  async selectMessageType(label: string) {
    await this.getCreateForm().locator("#messageType").click();
    await this.pickOverlayOption(label);
  }

  /** Choose the target type (e.g. "Users", "Users linked to assets", "All users in realms"). */
  async selectTargetType(label: string) {
    await this.getCreateForm().locator("#targetType").click();
    await this.pickOverlayOption(label);
  }

  async fillEmailMessage(subject: string, body: string) {
    // The fields commit on `change` (blur), so blur after filling to update the form model.
    const subjectInput = this.getCreateForm().locator("#notificationSubject input");
    await subjectInput.fill(subject);
    await subjectInput.blur();

    const bodyInput = this.getCreateForm().locator("#notificationEmailBody textarea");
    await bodyInput.fill(body);
    await bodyInput.blur();
  }

  async fillPushMessage(title: string, body: string) {
    // The fields commit on `change` (blur), so blur after filling to update the form model.
    const titleInput = this.getCreateForm().locator("#notificationTitle input");
    await titleInput.fill(title);
    await titleInput.blur();

    const bodyInput = this.getCreateForm().locator("#notificationBody textarea");
    await bodyInput.fill(body);
    await bodyInput.blur();
  }

  /** Tick a specific recipient (by visible label) in the (Users/Realms) checkbox list. */
  async checkTarget(label: string) {
    await this.getCreateForm().locator("#target").getByRole("checkbox", { name: label }).check();
  }

  /** Select an asset (by name) in the "Users linked to assets" target's asset tree. */
  async checkAssetTarget(name: string) {
    await this.getCreateForm().locator("#asset-selector .node-container", { hasText: name }).click();
  }

  // --- Details dialog ----------------------------------------------------

  /** The details dialog renders a read-only or-notification-form (reflected `readonly` attribute). */
  getDetailsForm(): Locator {
    return this.page.locator("or-notification-form[readonly]");
  }

  /**
   * The input/textarea of a read-only details field, located by its visible label (e.g. "Source").
   * Pass `exact` for labels that are substrings of another ("Recipient" vs "Recipient type").
   */
  getDetailsFieldByLabel(label: string, exact = false): Locator {
    return this.getDetailsForm()
      .locator("or-vaadin-text-field, or-vaadin-text-area")
      .filter({ hasText: exact ? new RegExp(`^\\s*${label}\\s*$`) : label })
      .locator("input, textarea");
  }

  /** Pick the language a localized notification's content is shown for (by visible label). */
  async selectMessageLanguage(label: string) {
    await this.getDetailsForm().locator("#messageLanguage").click();
    await this.pickOverlayOption(label);
  }

  /** Open the details dialog for the (first) row containing the given text. */
  async openDetailsByText(text: string) {
    // The row's center can fall on the target cell's link, so click the cell holding the text
    await this.getRowByText(text).first().locator("td", { hasText: text }).first().click();
    await expect(this.getDetailsForm()).toBeVisible();
  }

  /** The details dialog's top-right close cross (an or-vaadin-button wrapping the mdi:close icon). */
  getDetailsCloseButton(): Locator {
    return this.page.locator("or-vaadin-button").filter({ has: this.page.locator("or-icon[icon='mdi:close']") });
  }
}
