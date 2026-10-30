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
import { test } from "./fixtures/manager.js";

test.use({ storageState: { cookies: [], origins: [] } });

test("does not show an error dialog during the unauthenticated login redirect", async ({ page, manager }) => {
  await page.addInitScript(() => {
    const appearances: string[] = [];

    (window as any).__errorDialogAppearances = appearances;

    const observer = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        mutation.addedNodes.forEach((node) => {
          if (node instanceof Element) {
            if (node.matches("or-vaadin-confirm-dialog") || node.querySelector("or-vaadin-confirm-dialog")) {
              appearances.push("Error dialog appeared");
            }
          }
        });
      });
    });

    const observe = (root: Document | ShadowRoot) => {
      observer.observe(root, {
        childList: true,
        subtree: true,
      });
    };

    observe(document);

    const originalAttachShadow = Element.prototype.attachShadow;

    Element.prototype.attachShadow = function (init) {
      const shadowRoot = originalAttachShadow.call(this, init);
      observe(shadowRoot);
      return shadowRoot;
    };
  });

  await manager.goToRealmStartPage("master");

  await expect(page.getByRole("heading", { name: "Sign in to your account" })).toBeVisible();

  const appearances = await page.evaluate(() => (window as any).__errorDialogAppearances);

  expect(appearances).toEqual([]);
});
