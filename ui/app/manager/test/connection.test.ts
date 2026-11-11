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
import type { Request } from "@playwright/test";
import { expect, type Page } from "@openremote/test";
import { adminStatePath, type Manager, test } from "./fixtures/manager.js";

test.use({ storageState: adminStatePath });

const tokenEndpoint = "**/realms/master/protocol/openid-connect/token";

const isTokenRefresh = (request: Request) =>
  request.method() === "POST" && !!request.postData()?.includes("grant_type=refresh_token");

const isSignIn = (request: Request) =>
  request.isNavigationRequest() && request.url().includes("/protocol/openid-connect/auth");

/**
 * Opens the manager on a clock the test controls. The access token lasts a minute and is checked every 10 seconds,
 * so moving the clock ahead makes the next check refresh it.
 */
async function openWithExpiringToken(page: Page, manager: Manager) {
  await page.clock.install();
  await manager.goToRealmStartPage("master");
  await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
  await page.clock.setSystemTime(Date.now() + 2 * 60_000);
}

/** Runs the token check, which is due every 10 seconds */
const runTokenCheck = (page: Page) => page.clock.fastForward(10_000);

/**
 * @given The user is signed in
 * @when Keycloak rejects a token refresh because the session has ended
 * @then The user is sent to sign in again
 */
test("should sign in again when Keycloak ends the session", async ({ page, manager }) => {
  await openWithExpiringToken(page, manager);
  await page.route(tokenEndpoint, (route) =>
    isTokenRefresh(route.request())
      ? route.fulfill({
          status: 400,
          contentType: "application/json",
          // Keycloak runs on another origin in some setups, and a cross-origin error hides the status
          headers: {
            "Access-Control-Allow-Origin": new URL(page.url()).origin,
            "Access-Control-Allow-Credentials": "true",
          },
          body: JSON.stringify({ error: "invalid_grant", error_description: "Session not active" }),
        })
      : route.fallback()
  );

  const signIn = page.waitForRequest(isSignIn);
  await runTokenCheck(page);
  await signIn;
});

/**
 * @given The user is signed in
 * @when The token can't be refreshed for a while, for example while Keycloak restarts
 * @then The app stays open
 * @and The token is refreshed once Keycloak answers again
 */
test("should keep the app open while the token can't be refreshed", async ({ page, manager }) => {
  await openWithExpiringToken(page, manager);
  let failing = true;
  let refreshes = 0;
  await page.route(tokenEndpoint, (route) => {
    if (!isTokenRefresh(route.request())) {
      return route.fallback();
    }
    refreshes++;
    return failing ? route.abort("connectionrefused") : route.fallback();
  });
  const signIns: string[] = [];
  page.on("request", (request) => isSignIn(request) && signIns.push(request.url()));
  const refreshesAfterCheck = async () => {
    await runTokenCheck(page);
    return refreshes;
  };

  await expect.poll(refreshesAfterCheck).toBeGreaterThanOrEqual(2);
  failing = false;
  const failedRefreshes = refreshes;
  await expect.poll(refreshesAfterCheck).toBeGreaterThan(failedRefreshes);

  // A failed refresh used to send the user to sign in, which during a restart ended on a blank page
  expect(signIns).toEqual([]);
  await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
});

/**
 * @given The user is signed in
 * @when The connection for events drops
 * @then The app connects again
 */
test("should connect the events again after the connection drops", async ({ page, manager }) => {
  const connections: { close(): Promise<void> }[] = [];
  await page.routeWebSocket(/\/websocket\/events/, (ws) => {
    ws.connectToServer();
    connections.push(ws);
  });
  await manager.goToRealmStartPage("master");
  await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
  await expect.poll(() => connections.length).toBe(1);

  await connections[0].close();
  await expect.poll(() => connections.length).toBe(2);
});
