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
import { basename, resolve } from "node:path";
import { defineCtConfig as baseConfig, type Project } from ".";
import type { PlaywrightTestConfig } from "@sand4rt/experimental-ct-web";

const { CI } = process.env;

/**
 * See https://playwright.dev/docs/test-configuration.
 */
export const defineCtConfig = (path: string, overrides: PlaywrightTestConfig = {}) => {
  const name = basename(path);
  const config = {
    testMatch: "*.test.ts",
    /* The general timeout setting which tests should pass within */
    timeout: 60_000,
    /* Fail the build on CI if you accidentally left test.only in the source code. */
    forbidOnly: Boolean(CI),
    /* Retry failed tests twice on CI only to allow flaky behavior such as test timeouts to be retried */
    retries: CI ? 2 : 0,
    /* Reporter to use. See https://playwright.dev/docs/test-reporters */
    reporter: [["html", { outputFolder: resolve(path, "build/component-test-report") }]],
    /* Traces, videos and other per-test output. See https://playwright.dev/docs/test-use-options */
    outputDir: resolve(path, "build/test-results"),
    /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
    /* Configure projects */
    projects: [{ name, testDir: resolve(path, "test"), fullyParallel: true, use: { ct: name } }] as Project[],
    ...overrides,
    use: {
      ...({
        trace: "retain-on-failure",
        video: "on",
        ctTemplateDir: resolve(__dirname, "playwright"),
        ctCacheDir: resolve(path, "build/.cache"),
      } as PlaywrightTestConfig["use"]),
      ...overrides.use,
    },
  } as PlaywrightTestConfig;
  return baseConfig(config);
};
