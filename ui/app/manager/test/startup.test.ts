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
import type { Request, Route } from "@playwright/test";
import { expect } from "@openremote/test";
import { adminStatePath, test } from "./fixtures/manager.js";

// What a reverse proxy answers while the manager restarts
const serviceUnavailable = {
  status: 503,
  contentType: "text/html",
  body: "<html><body><h1>503 Service Unavailable</h1></body></html>",
};

// What Keycloak answers when the login code the page returns with has expired or was used
const invalidCode = {
  status: 400,
  contentType: "application/json",
  body: JSON.stringify({ error: "invalid_grant", error_description: "Code not valid" }),
};

// The code of the app, which loads after signing in
const isAppCode = (request: Request) => /\/app\.[0-9a-f]+\.js$/.test(new URL(request.url()).pathname);

const isSignIn = (request: Request) =>
  request.isNavigationRequest() && request.url().includes("/protocol/openid-connect/auth");

// Startup retries back off for up to a few seconds, so the next attempt can take a while
const afterRetry = { timeout: 15_000 };

/**
 * @given The manager app config can't be fetched while the manager restarts
 * @when The manager is opened
 * @then A loading spinner is shown
 * @and The login page is shown once the config can be fetched again
 */
test("should show the login page once the app config can be fetched again", async ({ page, manager }) => {
  let restarting = true;
  await page.route("**/api/master/configuration/manager", (route) =>
    restarting ? route.abort("connectionreset") : route.fallback()
  );
  await manager.goToRealmStartPage("master");
  // The app was never added to the page, which stayed blank
  await expect(page.getByRole("progressbar")).toBeVisible();

  restarting = false;
  await expect(page.getByRole("textbox", { name: "Username or email" })).toBeVisible(afterRetry);
});

/**
 * @given The manager info endpoint answers with the proxy's error page while the manager restarts
 * @when The manager is opened
 * @then A loading spinner is shown
 * @and The login page is shown once the manager answers again
 */
test("should show the login page once the manager info is available again", async ({ page, manager }) => {
  let restarting = true;
  await page.route("**/api/master/info", (route) =>
    restarting ? route.fulfill(serviceUnavailable) : route.fallback()
  );
  await manager.goToRealmStartPage("master");
  // Initialization waited forever for the info, which left the page blank
  await expect(page.getByRole("progressbar")).toBeVisible();

  restarting = false;
  await expect(page.getByRole("textbox", { name: "Username or email" })).toBeVisible(afterRetry);
});

/**
 * @given The manager stays unreachable
 * @when The manager is opened
 * @then A message says that the app could not be loaded
 */
test("should show that the app could not be loaded when the manager stays unreachable", async ({ page, manager }) => {
  await page.clock.install();
  await page.route("**/api/master/info", (route) => route.fulfill(serviceUnavailable));
  await manager.goToRealmStartPage("master");

  // The retries give up after 45 s, so skip ahead instead of waiting for them
  await expect
    .poll(
      async () => {
        await page.clock.fastForward(10_000);
        return page.getByRole("alert").isVisible();
      },
      { timeout: 20_000 }
    )
    .toBe(true);
  await expect(page.getByRole("alert")).toHaveText("Could not load the app, try again later");
});

test.describe("Sign in", () => {
  test.use({ storageState: adminStatePath });

  /**
   * @given The user is signed in
   * @and The user profile can't be loaded from Keycloak
   * @when The manager is opened
   * @then The app loads
   */
  test("should load the app when the user profile can't be loaded", async ({ page, manager }) => {
    // A stored language is used instead of the one in the profile
    await page.addInitScript(() => window.localStorage.removeItem("LANGUAGE"));
    let profileRequests = 0;
    await page.route("**/realms/master/account", (route) => {
      profileRequests++;
      return route.abort("connectionreset");
    });
    await manager.goToRealmStartPage("master");
    // Initialization rejected, which left the loading spinner forever
    await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
    expect(profileRequests).toBeGreaterThan(0);
  });

  /**
   * @given The user is signed in
   * @and Keycloak rejects the login code the page returns with once
   * @when The manager is opened
   * @then A new login is started
   * @and The app loads without asking for the password
   */
  test("should sign in again when Keycloak rejects the returned login", async ({ page, manager }) => {
    let rejected = 0;
    await page.route("**/realms/master/protocol/openid-connect/token", (route) => {
      if (rejected === 0 && route.request().postData()?.includes("grant_type=authorization_code")) {
        rejected++;
        return route.fulfill(invalidCode);
      }
      return route.fallback();
    });
    await manager.goToRealmStartPage("master");
    // The rejected code showed "Could not sign in" until the page was reloaded twice
    await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
    expect(rejected).toBe(1);
  });

  /**
   * @given The user is signed in
   * @and Keycloak keeps rejecting the login code the page returns with
   * @when The manager is opened
   * @then A message says that signing in failed
   */
  test("should show that signing in failed when Keycloak keeps rejecting the returned login", async ({
    page,
    manager,
  }) => {
    let rejected = 0;
    await page.route("**/realms/master/protocol/openid-connect/token", (route) => {
      if (route.request().postData()?.includes("grant_type=authorization_code")) {
        rejected++;
        return route.fulfill(invalidCode);
      }
      return route.fallback();
    });
    await manager.goToRealmStartPage("master");
    const signInFailed = page.getByRole("alertdialog").filter({ hasText: "Could not sign in, try again later" });
    // Its content renders in an overlay, so the dialog itself has no box to check for visibility
    await expect(signInFailed).toHaveCount(1);
    // A single new login, not one after every rejection
    expect(rejected).toBe(2);
  });

  /**
   * @given The user is signed in
   * @when The manager is opened
   * @then The translations, the asset model and the console app config are requested together
   */
  test("should request the translations and the asset model together", async ({ page, manager }) => {
    const together = [
      /\/model\/assetInfos/,
      /\/model\/metaItemDescriptors/,
      /\/model\/valueDescriptors/,
      /\/consoleappconfig\//,
      /\/shared\/locales\/[^/]+\/or\.json/,
    ];
    // Each request waits until all of them were sent
    const requested = new Set<RegExp>();
    const held: Route[] = [];
    for (const pattern of together) {
      await page.route(pattern, (route) => {
        if (requested.size === together.length) {
          return route.fallback();
        }
        requested.add(pattern);
        held.push(route);
        if (requested.size === together.length) {
          held.forEach((heldRoute) => heldRoute.fallback());
        }
      });
    }

    await manager.goToRealmStartPage("master");
    // They used to be requested one after another, so the first one waited forever for the others
    await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
  });

  /**
   * @given The user is signed in
   * @when The manager is opened
   * @then The code of the app loads once, after the page returns from Keycloak
   */
  test("should load the app code once per page load", async ({ page, manager }) => {
    let returnedFromKeycloak = false;
    const appCodeLoads: boolean[] = [];
    page.on("request", (request) => {
      if (isSignIn(request)) {
        returnedFromKeycloak = true;
      } else if (isAppCode(request)) {
        appCodeLoads.push(returnedFromKeycloak);
      }
    });

    await manager.goToRealmStartPage("master");
    await expect(page.locator("#desktop-left").getByRole("link", { name: "Assets" })).toBeVisible();
    // The whole app used to load before the redirect to Keycloak, and again after it
    expect(appCodeLoads).toEqual([true]);
  });

  /**
   * @given The user is signed in
   * @and The code of the app can't be loaded
   * @when The manager is opened
   * @then The page reloads once
   * @and A message says that the app could not be loaded
   */
  test("should show that the app could not be loaded when its code can't be loaded", async ({ page, manager }) => {
    let failedLoads = 0;
    await page.route(
      (url) => /\/app\.[0-9a-f]+\.js$/.test(url.pathname),
      (route) => {
        failedLoads++;
        return route.abort("connectionreset");
      }
    );

    await manager.goToRealmStartPage("master");
    await expect(page.getByRole("alert")).toHaveText("Could not load the app, try again later");
    // A page cached from before an update gets new code by reloading once, but not over and over
    expect(failedLoads).toBe(2);
  });
});
