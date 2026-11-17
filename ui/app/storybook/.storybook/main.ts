/*
 * Copyright 2025, OpenRemote Inc.
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
import { StorybookConfig } from "storybook-web-components-rsbuild";

// @ts-ignore
const storybookConfig: StorybookConfig = {
  framework: "storybook-web-components-rsbuild",
  /*staticDirs: ["../images"],*/
  stories: ["../../../component/**/*.stories.@(js|jsx|mjs|ts|tsx)", "../docs/**/*.mdx"],
  addons: ["@storybook/addon-docs", "@storybook/addon-a11y"],
  core: {
    disableTelemetry: true,
  },
  rsbuildFinal: (config, { configType }) => {
    config.output ??= {};
    config.output.assetPrefix = "/storybook/";
    // Dev asset names use [path], which points outside the output directory for files in the root node_modules
    if (configType === "DEVELOPMENT") {
      const name = "static/media/[name].[contenthash:8][ext]";
      config.output.filename = { ...config.output.filename, image: name, font: name, media: name };
    }
    return config;
  },
};

export default storybookConfig;
