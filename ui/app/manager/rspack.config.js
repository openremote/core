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
const util = require("@openremote/util");
const { rspack } = require("@rspack/core");
const { RsdoctorRspackPlugin } = require("@rsdoctor/rspack-plugin");
const packageJson = require("./package.json");

module.exports = (env, argv) => {
  const managerUrl = env.managerUrl;
  const keycloakUrl = env.keycloakUrl;
  const port = env.port;
  const IS_DEV_SERVER = !!process.argv.find((arg) => arg.includes("serve"));
  const config = util.getAppConfig(argv.mode, IS_DEV_SERVER, __dirname, managerUrl, keycloakUrl, port);

  config.entry = { bundle: "./src/bootstrap.ts" };
  config.output.chunkFilename = argv.mode === "production" ? "[name].[contenthash].js" : "[name].js";

  if (IS_DEV_SERVER) {
    config.performance = {
      hints: false,
    };
  }

  if (process.env.RSDOCTOR === "true") {
    config.plugins.push(new RsdoctorRspackPlugin());
  }

  // Add a custom base URL to resolve the config dir to the path of the dev server not root
  config.plugins.push(
    new rspack.DefinePlugin({
      APP_VERSION: JSON.stringify(packageJson.version),
    })
  );

  return config;
};
