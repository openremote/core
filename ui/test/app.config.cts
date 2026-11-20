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
import { basename, resolve, join } from "node:path";
import { defineConfig as baseConfig, devices, type Project } from ".";

const { CI, DEV, managerUrl } = process.env;

/** The manager that `manager.cjs` starts, on a port of its own so a development manager is never reused */
const testManagerUrl = "http://127.0.0.1:18080";

const browsers: Project[] = [
  {
    name: "chromium",
    use: { ...devices["Desktop Chrome"] },
  },
  // {
  //   name: "firefox",
  //   use: { ...devices["Desktop Firefox"] },
  // },
  // {
  //   name: "webkit",
  //   use: { ...devices["Desktop Safari"] },
  // },
];

/**
 * Creates a setup and teardown test project for a given app.
 * These configurations are intended to run before and after all other tests,
 * typically for initializing and cleaning up test environments. The app
 * project should have a dependency on the setup project while the cleanup
 * project is referenced to the setup project.
 *
 * @param app - The name of the app these projects are meant to be used by.
 * @returns An array of two configuration objects: one for setup and one for cleanup
 */
function createAppSetupAndTeardown(app: string) {
  return [
    {
      name: `setup ${app}`,
      testMatch: "**/*.setup.ts",
      teardown: `cleanup ${app}`,
    },
    {
      name: `cleanup ${app}`,
      testMatch: "**/*.cleanup.ts",
    },
  ];
}

/**
 * See https://playwright.dev/docs/test-configuration.
 */
export const defineAppConfig = (path: string) => {
  const name = basename(path);
  return baseConfig({
    testMatch: "*.test.ts",
    /* Fail the build on CI if you accidentally left test.only in the source code. */
    forbidOnly: Boolean(CI),
    /* Retry failed tests twice on CI only to allow flaky behavior such as test timeouts to be retried */
    retries: CI ? 2 : 0,
    /* Reporter to use. See https://playwright.dev/docs/test-reporters */
    reporter: [["html", { outputFolder: resolve(path, "build/app-test-report") }]],
    /* Traces, videos and other per-test output. See https://playwright.dev/docs/test-use-options */
    outputDir: resolve(path, "build/test-results"),
    /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
    use: {
      // Defaults to the test manager as that significantly speeds up the tests compared to serving the frontend with Webpack
      baseURL: managerUrl || DEV ? "http://127.0.0.1:9000" : testManagerUrl,
      /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
      trace: "retain-on-failure",
      video: "on",
      locale: "en",
    },
    timeout: 60_000,
    webServer: {
      command: `node ${join(__dirname, "manager.cjs")}`,
      url: testManagerUrl,
      reuseExistingServer: !process.env.CI,
    },
    workers: 1,
    /* Configure projects */
    projects: [
      ...createAppSetupAndTeardown(name),
      ...browsers.flatMap((browser) => ({
        name: `${name} ${browser.name}`,
        testDir: resolve(path, "test"),
        fullyParallel: false,
        dependencies: [`setup ${name}`],
        ...browser,
      })),
    ],
  });
};
