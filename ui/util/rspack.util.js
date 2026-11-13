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
const fs = require("fs");
const path = require("path");
const { rspack } = require("@rspack/core");
const HtmlWebpackPlugin = require("html-webpack-plugin");
const MomentLocalesPlugin = require("moment-locales-webpack-plugin");

function getStandardModuleRules() {
  return {
    rules: [
      {
        // moment resolves its locale data through a require it has assigned to a variable
        test: /moment[\\/]moment\.js$/,
        parser: { requireAlias: true },
      },
      {
        test: /(maplibre|@material|gridstack|@mdi).*\.css$/, // output css as strings
        type: "asset/source",
      },
      {
        test: /\.wasm$/,
        type: "asset/resource",
      },
      {
        // maplibre builds its worker URL at runtime, so its own `new URL()` calls are not build time assets
        test: /maplibre-gl[\\/]dist[\\/].+\.mjs$/,
        parser: { url: false },
      },
      {
        test: /\.css$/, //
        exclude: /(maplibre|@material|gridstack|@mdi).*\.css$/,
        use: [{ loader: "css-loader" }],
      },
      {
        test: /\.(png|jpg|ico|gif|svg|eot|ttf|woff|woff2|mp4)$/,
        type: "asset",
        generator: {
          filename: "images/[hash][ext][query]",
        },
      },
      {
        test: /\.tsx?$/,
        exclude: /node_modules/,
        use: {
          // TODO: Switch to builtin:swc-loader, and remove ts-loader / webpack dependency
          loader: "ts-loader",
          options: {
            projectReferences: true,
          },
        },
      },
    ],
  };
}

function resolveDir(request, dirname) {
  try {
    return path.dirname(require.resolve(request, { paths: [dirname] }));
  } catch {
    return undefined;
  }
}

function getAppConfig(mode, isDevServer, dirname, managerUrl, keycloakUrl, port) {
  const production = mode === "production";
  port = port || 9000;
  managerUrl = managerUrl || (production && !isDevServer ? undefined : "http://127.0.0.1:8080");
  const OUTPUT_PATH = isDevServer ? "src" : "dist";

  if (isDevServer) {
    console.log("");
    console.log("To customize the URL of the manager and/or keycloak use the managerUrl and/or keycloakUrl");
    console.log(" environment arguments e.g: ");
    console.log("");
    console.log("npm run serve -- --env managerUrl=https://127.0.0.1");
    console.log("npm run serve -- --env keycloakUrl=https://127.0.0.1/auth");
    console.log("");
    console.log("MANAGER URL: " + managerUrl || "");
    console.log("KEYCLOAK URL: " + keycloakUrl || managerUrl + "/auth");
    console.log("");
  }

  const config = {
    entry: {
      bundle: "./src/index.ts",
    },
    output: {
      path: dirname + "/build/dist",
      publicPath: isDevServer ? "/" + dirname.split(path.sep).slice(-1)[0] + "/" : "./",
      filename: production ? "[name].[contenthash].js" : "[name].js",
    },
    module: { ...getStandardModuleRules() },
    // optimization: {
    //     minimize: true,
    //     minimizer: [
    //         // For webpack@5 you can use the `...` syntax to extend existing minimizers (i.e. `terser-webpack-plugin`), uncomment the next line
    //         // `...`,
    //         new CssMinimizerPlugin(),
    //     ],
    // },
    resolve: {
      extensions: [".ts", ".tsx", "..."],
      fallback: {
        vm: false,
        querystring: require.resolve("querystring-es3"),
      },
    },
  };

  config.plugins = [
    // Conditional compilation variables
    new rspack.DefinePlugin({
      PRODUCTION: JSON.stringify(production),
      MANAGER_URL: JSON.stringify(managerUrl),
      KEYCLOAK_URL: JSON.stringify(keycloakUrl),
      "process.env": {
        BABEL_ENV: JSON.stringify(mode),
      },
    }),
    // Generate our index page
    new HtmlWebpackPlugin({
      chunksSortMode: "none",
      inject: false,
      template: "index.html",
    }),
    // Remove any unused locales
    new MomentLocalesPlugin({
      localesToKeep: ["ar", "zh-cn", "de", "en", "es", "fr", "it", "nl", "pl", "pt", "ro", "uk", "cs"],
    }),
  ];

  if (production) {
    config.module.rules.push({
      test: /\.js$/,
      include: function (modulePath) {
        return (
          /(@webcomponents[\/|\\]shadycss|lit-css|styled-lit-element|lit-element|lit-html|@polymer|@lit|pwa-helpers)/.test(
            modulePath
          ) || !/node_modules/.test(modulePath)
        );
      },
      loader: "builtin:swc-loader",
    });
  }

  if (isDevServer) {
    // Load source maps generated by typescript
    // config.devtool = 'inline-source-map';
    config.module.rules.push({
      test: /\.js$/,
      use: ["source-map-loader"],
      enforce: "pre",
      exclude: [/node_modules/],
    });
  }

  // Build list of resources to copy
  const patterns = [
    {
      from: "**/*.js",
      context: path.dirname(require.resolve("@webcomponents/webcomponentsjs")),
      to: "modules/@webcomponents/webcomponentsjs",
      toType: "dir",
    },
  ];
  // Check if images dir exists
  if (fs.existsSync(path.join(dirname, "images"))) {
    patterns.push({
      from: "./images",
      to: "images",
    });
  }
  // Check if locales dir exists
  if (fs.existsSync(path.join(dirname, "locales"))) {
    patterns.push({
      from: "./locales",
      to: "locales",
    });
  }
  // Check if htaccess file exists
  if (fs.existsSync(path.join(dirname, ".htaccess"))) {
    patterns.push({
      from: ".htaccess",
      to: ".htaccess",
      toType: "file",
    });
  }
  // Check if .appignore file exists
  if (fs.existsSync(path.join(dirname, ".appignore"))) {
    patterns.push({
      from: ".appignore",
      to: ".appignore",
      toType: "file",
    });
  }
  // Check if maplibre is installed, its worker imports the shared module so both must sit side by side
  const maplibreDist = resolveDir("maplibre-gl/dist/maplibre-gl-worker.mjs", dirname);
  if (maplibreDist) {
    for (const file of ["maplibre-gl-worker.mjs", "maplibre-gl-shared.mjs"]) {
      patterns.push({
        from: path.join(maplibreDist, file),
        to: "maplibre",
        toType: "dir",
        info: { minimized: true },
      });
    }
  }

  // Copy unprocessed files
  config.plugins.push(
    new rspack.CopyRspackPlugin({
      patterns,
    })
  );

  config.devtool = !isDevServer ? false : "inline-source-map";
  config.devServer = {
    client: {
      overlay: {
        runtimeErrors: (e) => {
          console.error(e);
          return false;
        },
      },
    },
    historyApiFallback: {
      index: "/" + dirname.split(path.sep).slice(-1)[0] + "/",
    },
    port,
    open: false,
    hot: false, // HMR doesn't work with webcomponents at present
    liveReload: true,
    static: OUTPUT_PATH,
  };
  config.watchOptions = {
    ignored: ["node_modules"],
  };

  return config;
}

function getLibName(componentName) {
  if (componentName.startsWith("or-")) {
    componentName = componentName.substr(3);
  }
  componentName = componentName.replace(/-([a-z])/g, function (g) {
    return g[1].toUpperCase();
  });
  return "OR" + componentName.charAt(0).toUpperCase() + componentName.substring(1);
}

function ORExternals(context, callback) {
  const match = context.request.match(/^@openremote\/([^\/]*)$/);
  if (match) {
    const component = getLibName(match[1]);
    console.log(context.request + " => " + component);
    return callback(null, "umd " + component);
  }
  callback();
}

function generateExternals(bundle) {
  if (!bundle) {
    return;
  }

  const externals = [];

  if (bundle.excludeOr) {
    externals.push(ORExternals);
  }
  if (bundle.vendor) {
    externals.push(bundle.vendor);
  }

  return externals;
}

function generateExports(dirname) {
  const libName = getLibName(dirname.split(path.sep).pop());

  return Object.entries(bundles).map(([name, bundle]) => {
    const entry = {};
    entry[name] = "./src/index.ts";

    const config = {
      entry,
      mode: "production",
      output: {
        filename: "[name].js",
        path: path.resolve(dirname, "build/dist/umd"),
        library: {
          name: libName,
          target: "umd",
        },
      },
      resolve: {
        extensions: [".ts", ".tsx", "..."],
        fallback: { vm: false },
      },
      module: { ...getStandardModuleRules() },
      externals: generateExternals(bundle),
    };

    return config;
  });
}

module.exports = {
  getLibName,
  generateExports,
  getStandardModuleRules,
  getAppConfig,
};
