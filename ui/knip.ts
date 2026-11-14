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
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import type { KnipConfig } from "knip";

const config: KnipConfig = {
  ignore: ["**/*.stories.ts"],
  ignoreDependencies: [
    "@types/react", // Required by mdx which is used in Storybook
    "@webcomponents/webcomponentsjs", // Imported through index.html files for apps
    "@types/offscreencanvas", // Referenced in the tsconfig file
    "inter-ui", // Only imports CSS files
    "@vaadin/vaadin-lumo-styles", // Only imports CSS files
    "@openremote/theme", // Only imports CSS files
    // --- HOISTED UTIL PACKAGE TOOLING ---
    // Ignoring these stops Knip from flagging them as "Unused" in ui/util & ui/test
    // AND stops Knip from flagging them as "Unlisted" when used by other workspaces.
    "@custom-elements-manifest/analyzer",
    "@rspack/cli",
    "@typescript-eslint/eslint-plugin",
    "@typescript-eslint/parser",
    "@wc-toolkit/cem-inheritance",
    "@wc-toolkit/jsx-types",
    "@wc-toolkit/storybook-helpers",
    "cross-env",
    "css-loader",
    "eslint",
    "eslint-config-prettier",
    "eslint-config-standard",
    "eslint-plugin-import",
    "eslint-plugin-lit",
    "eslint-plugin-n",
    "eslint-plugin-promise",
    "eslint-plugin-wc",
    "prettier",
    "shx",
    "source-map-loader",
    "ts-loader",
  ],
  ignoreUnresolved: [
    "./restclient", // Generated file
    "./model", // Generated file
  ],
  rules: {
    binaries: "off",
    duplicates: "off",
    files: "off",
  },
  workspaces: {
    "ui/{app,component,demo}/*": {
      paths: {
        "@openremote/util": ["../../util/rspack.util.js"],
      },
    },
    "ui/util": {},
    "ui/test": {},
  },
  // Explicitly activate the rspack plugin,
  // because we only indirectly reference @rspack/core through the @openremote/util package
  rspack: true,
  // Knip's Rsbuild plugin calls tools.rspack without addRules/appendPlugins helpers.
  // Analyze these configs statically until the plugin supports those callbacks.
  rsbuild: {
    config: [],
    entry: ["rsbuild*.config.{mjs,ts,js,cjs,mts,cts}"],
  },
  // Webpack loaders resolving doesn't work because they're referenced under the @openremote/util package
  // "webpack": true,
  // ESlint plugin resolving doesn't work because they're referenced under the @openremote/util package
  // "eslint": ["ui/.eslintrc.json"],
  // Storybook story dependency resolving doesn't work because those reference the @storybook/web-components dependency
  // installed indirectly throught the @openremote/storybook app
  // "storybook": true
};

// These dependencies are exposed by inferred public declaration types, not source imports.
// Before declarations exist, Knip cannot see that usage and reports them as unused.
// Once built, keep the declaration checks active so missing dependencies are still detected.
const declarationDependencies: Record<string, string[]> = {
  "ui/app/manager": ["i18next", "redux", "redux-thunk"],
  "ui/component/or-app": ["i18next", "redux"],
  "ui/component/or-attribute-history": ["i18next"],
  "ui/component/or-attribute-input": ["i18next"],
  "ui/component/or-chart": ["i18next"],
  "ui/component/or-dashboard-builder": ["i18next"],
  "ui/component/or-json-forms": ["i18next"],
  "ui/component/or-log-viewer": ["i18next"],
  "ui/component/or-rules": ["@types/lodash", "axios", "i18next"],
  "ui/component/or-scheduler": ["i18next"],
  "ui/component/rest": ["@openremote/model"],
};

const rootDir = fileURLToPath(new URL("../", import.meta.url));
const workspaces = config.workspaces!;
for (const [workspace, dependencies] of Object.entries(declarationDependencies)) {
  if (!existsSync(resolve(rootDir, workspace, "build/lib"))) {
    workspaces[workspace] = {
      ...workspaces["ui/{app,component,demo}/*"],
      ignoreDependencies: dependencies,
    };
  }
}

export default config;
