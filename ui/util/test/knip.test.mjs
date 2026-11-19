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
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, copyFileSync, writeFileSync, symlinkSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const repository = fileURLToPath(new URL("../../../", import.meta.url));
const knip = resolve(repository, "node_modules/knip/bin/knip.js");

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), "openremote-knip-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  function write(path, contents) {
    const full = join(root, path);
    mkdirSync(resolve(full, ".."), { recursive: true });
    writeFileSync(full, contents);
  }
  write("package.json", JSON.stringify({ name: "knip-regression", private: true, workspaces: ["ui/app/*"] }));
  write(".gitignore", "node_modules/\n**/build/\n");
  write(
    "ui/app/manager/package.json",
    JSON.stringify({
      name: "@openremote/manager",
      version: "0.0.0",
      types: "build/lib/index.d.ts",
      dependencies: { i18next: "^26.3.6" },
      devDependencies: { "@rsbuild/core": "~1.7.5" },
    })
  );
  write("ui/app/manager/src/index.ts", "export const app = {};\n");
  write(
    "ui/app/manager/rsbuild.config.ts",
    `import { defineConfig } from '@rsbuild/core';
export default defineConfig({ tools: { rspack: (config, { addRules }) => { addRules([]); return config; } } });\n`
  );
  copyFileSync(join(repository, "ui/knip.ts"), join(root, "ui/knip.ts"));
  symlinkSync(join(repository, "node_modules"), join(root, "node_modules"), "junction");
  function run() {
    const result = spawnSync(
      process.execPath,
      [knip, "--config", "ui/knip.ts", "--directory", root, "--no-config-hints"],
      { cwd: root, encoding: "utf8" }
    );
    assert.ifError(result.error);
    return { status: result.status, output: result.stdout + result.stderr };
  }
  return { write, run };
}

test("should analyze Rsbuild configuration without executing bundler callbacks", (t) => {
  const { write, run } = fixture(t);
  let result = run();
  // Knip 6 used to execute this callback without addRules and abort configuration loading.
  assert.equal(result.status, 0, result.output);
  write(
    "ui/app/manager/rsbuild.config.ts",
    `import { defineConfig } from '@rsbuild/core';
import 'knip-regression-missing-package';
export default defineConfig({});\n`
  );
  result = run();
  assert.equal(result.status, 1, result.output);
  // Static config analysis must still report invalid imports.
  assert.match(result.output, /knip-regression-missing-package/);
});

test("should validate public type dependencies after declarations are built", (t) => {
  const { write, run } = fixture(t);
  write(
    "ui/app/manager/build/lib/index.d.ts",
    `import type { i18n } from 'i18next';\nexport declare const instance: i18n;\n`
  );
  let result = run();
  assert.equal(result.status, 0, result.output);
  write(
    "ui/app/manager/package.json",
    JSON.stringify({
      name: "@openremote/manager",
      version: "0.0.0",
      types: "build/lib/index.d.ts",
      devDependencies: { "@rsbuild/core": "~1.7.5" },
    })
  );
  result = run();
  assert.equal(result.status, 1, result.output);
  // Pre-build exceptions must not hide missing dependencies once declarations exist.
  assert.match(result.output, /Unlisted dependencies/);
  assert.match(result.output, /i18next/);
});
