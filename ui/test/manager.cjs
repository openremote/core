#!/usr/bin/env node
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
const { spawn } = require("node:child_process");
const path = require("node:path");
const os = require("node:os");

// Change working directory to repository root
process.chdir(path.resolve(__dirname, "../.."));

// --- Default Environment Variables ---
process.env.OR_STORAGE_DIR = process.env.OR_STORAGE_DIR || "tmp"; // Changed to relative local directory
process.env.OR_APP_DOCROOT = process.env.OR_APP_DOCROOT || "manager/build/install/manager/web";
// Ports apart from a development manager and its stack, which the tests would otherwise clean
process.env.OR_WEBSERVER_LISTEN_PORT = process.env.OR_WEBSERVER_LISTEN_PORT || "18080";
process.env.MQTT_SERVER_LISTEN_PORT = process.env.MQTT_SERVER_LISTEN_PORT || "11883";
process.env.OR_DB_PORT = process.env.OR_DB_PORT || "15432";
process.env.OR_KEYCLOAK_PORT = process.env.OR_KEYCLOAK_PORT || "18081";
// Variables without defaults
process.env.OR_LOGGING_CONFIG_FILE =
  process.env.OR_LOGGING_CONFIG_FILE || path.join("ui", "test", "logging.properties");

// --- Local Paths ---
// Ensure you have run `./gradlew :manager:installDist` so these folders exist.
const APP_LIB_DIR = path.join("manager", "build", "install", "manager", "lib", "*");
const EXTENSIONS_DIR = path.join("deployment", "manager", "extensions", "*");
// Handle Classpath Separator (':' on Unix, ';' on Windows)
const separator = os.platform() === "win32" ? ";" : ":";
const classPath = `${APP_LIB_DIR}${separator}${EXTENSIONS_DIR}`;

// --- Execution ---
console.log("Starting OpenRemote Manager...");
console.log(`Classpath: ${classPath}`);

// --- Prepare Java arguments ---
// We split JAVA_OPTS by space if it exists, or use the default JVM options defined in our Dockerfile
const javaOpts = process.env.JAVA_OPTS
  ? process.env.JAVA_OPTS.split(" ")
  : [
      "-XX:InitialRAMPercentage=75.0",
      "-XX:MaxRAMPercentage=75.0",
      "-XX:NativeMemoryTracking=summary",
      "-Xlog:all=warning:stdout:uptime,level,tags",
      "-XX:+HeapDumpOnOutOfMemoryError",
      "-XX:HeapDumpPath=./dump.hprof",
    ];
const javaOptsAppend = process.env.JAVA_OPTS_APPEND ? process.env.JAVA_OPTS_APPEND.split(" ") : [];
const args = [...javaOpts, ...javaOptsAppend, "-cp", classPath, "org.openremote.manager.Main"];

// --- Execute Java ---
const child = spawn("java", args, {
  stdio: "inherit", // Forwards all output/input to the terminal
  shell: false,
});
process.on("SIGINT", () => child.kill());
process.on("SIGTERM", () => child.kill());
