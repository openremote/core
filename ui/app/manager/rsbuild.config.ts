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
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { defineConfig } from "@rsbuild/core";

// The maplibre worker imports the shared module, so both must sit side by side
const maplibreDist = dirname(createRequire(import.meta.url).resolve("maplibre-gl/dist/maplibre-gl-worker.mjs"));

export default defineConfig({
  output: {
    copy: ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"].map((file) => ({
      from: join(maplibreDist, file),
      to: "maplibre",
      info: { minimized: true },
      // Their source maps aren't copied, so drop the comment pointing to them
      transform: (input: Buffer) => input.toString().replace(/\n\/\/# sourceMappingURL=\S+\s*$/, "\n"),
    })),
  },
  dev: {
    hmr: false, // HMR does not work for our Web Components atm
    liveReload: true,
  },
  server: {
    port: 9000, // TODO: Leave default in production
  },
  source: {
    decorators: {
      version: "legacy",
    },
  },
  resolve: {
    alias: {
      // Webpack fallbacks for Node.js modules
      vm: false,
      // Workspace aliases for proper resolution
      "@openremote/core": "../../component/core/src",
      "@openremote/model": "../../component/model/src",
      "@openremote/rest": "../../component/rest/src",
      "@openremote/or-app": "../../component/or-app/src",
      "@openremote/or-asset-tree": "../../component/or-asset-tree/src",
      "@openremote/or-asset-viewer": "../../component/or-asset-viewer/src",
      "@openremote/or-attribute-barchart": "../../component/or-attribute-barchart/src",
      "@openremote/or-attribute-card": "../../component/or-attribute-card/src",
      "@openremote/or-attribute-history": "../../component/or-attribute-history/src",
      "@openremote/or-attribute-input": "../../component/or-attribute-input/src",
      "@openremote/or-attribute-picker": "../../component/or-attribute-picker/src",
      "@openremote/or-components": "../../component/or-components/src",
      "@openremote/or-chart": "../../component/or-chart/src",
      "@openremote/or-dashboard-builder": "../../component/or-dashboard-builder/src",
      "@openremote/or-element": "../../component/or-element/src",
      "@openremote/or-gauge": "../../component/or-gauge/src",
      "@openremote/or-icon": "../../component/or-icon/src",
      "@openremote/or-json-forms": "../../component/or-json-forms/src",
      "@openremote/or-log-viewer": "../../component/or-log-viewer/src",
      "@openremote/or-map": "../../component/or-map/src",
      "@openremote/or-mwc-components": "../../component/or-mwc-components/src",
      "@openremote/or-rules": "../../component/or-rules/src",
      "@openremote/or-services": "../../component/or-services/src",
      "@openremote/or-translate": "../../component/or-translate/src",
      "@openremote/or-tree-menu": "../../component/or-tree-menu/src",
      "@openremote/or-vaadin-components": "../../component/or-vaadin-components/src",
      "@openremote/theme": "../../component/theme",
      "@openremote/util": "../../util",
    },
  },
  plugins: [],
  tools: {
    bundlerChain: (chain, { CHAIN_ID }) => {
      chain.module.rule(CHAIN_ID.RULE.CSS).exclude.add(/(@material|@mdi).*\.css$/); // Exclude the external CSS imports from default bundling.
    },
    rspack: (config, { addRules }) => {
      addRules([{ test: /(@material|@mdi).*\.css$/, type: "asset/source" }]); // Add rule to treat external CSS imports as raw strings.
      addRules([{ test: /maplibre-gl[\\/]dist[\\/].+\.mjs$/, parser: { url: false } }]); // maplibre builds its worker URL at runtime, so its own `new URL()` calls are not build time assets.
      return config;
    },
  },
});
