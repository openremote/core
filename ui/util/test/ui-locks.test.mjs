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
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const repository = fileURLToPath(new URL("../../../", import.meta.url));
const wrapper = join(repository, process.platform === "win32" ? "gradlew.bat" : "gradlew");

function write(root, name, contents) {
  const path = join(root, name);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, contents);
}

const compiler = (task) => `
def active = rootProject.file('compiler-active')
def declaration = rootProject.file('ui/component/model/build/lib/index.d.ts')
tasks.register('${task}') {
  doLast {
    // Separate compiler processes previously entered the same output tree together.
    if (!active.createNewFile()) throw new GradleException('Overlapping TypeScript outputs')
    try {
      declaration.parentFile.mkdirs()
      declaration.text = 'partial'
      Thread.sleep(600)
      declaration.text = 'complete'
    } finally {
      active.delete()
    }
  }
}
`;

const independentCompiler = `
def active = rootProject.file('compiler-active')
def overlap = rootProject.file('independent-overlap')
tasks.register('npmBuild') {
  doLast {
    def deadline = System.nanoTime() + 5_000_000_000L
    while (!active.exists() && System.nanoTime() < deadline) Thread.sleep(10)
    if (!active.exists()) throw new GradleException('Independent compilation was serialized')
    overlap.text = 'parallel'
  }
}
`;

function fixture(t, modules, writers, expectDeclaration = false) {
  const root = mkdtempSync(join(tmpdir(), "openremote-ui-locks-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const projects = { model: {}, rest: {}, ...modules };
  write(
    root,
    "settings.gradle",
    `rootProject.name = 'ui-concurrency-tests'\ninclude ':ui'\n${Object.keys(projects)
      .map((name) => `include ':ui:component:${name}'`)
      .join("\n")}\n`
  );
  write(
    root,
    "build.gradle",
    `allprojects {
  ext.getYarnInstallTask = { rootProject.tasks.named('yarnInstall') }
  ext.npmCommand = { String command -> command }
}
tasks.register('yarnInstall')
tasks.register('writers') { dependsOn(${JSON.stringify(writers)}) }
project(':ui') {
  afterEvaluate {
    tasks.named('knip', Exec) {
      commandLine ${JSON.stringify(process.execPath)}, rootProject.file('check-knip.mjs').absolutePath
    }
  }
}
`
  );
  mkdirSync(join(root, "ui"));
  copyFileSync(join(repository, "ui/build.gradle"), join(root, "ui/build.gradle"));
  for (const [name, { references = [], script = "" }] of Object.entries(projects)) {
    write(
      root,
      `ui/component/${name}/tsconfig.json`,
      JSON.stringify({ references: references.map((path) => ({ path: `../${path}` })) })
    );
    write(
      root,
      `ui/component/${name}/build.gradle`,
      `${["model", "rest"].includes(name) ? "tasks.register('generateTypeScript')\n" : ""}${script}`
    );
  }
  write(
    root,
    "check-knip.mjs",
    `import assert from 'node:assert/strict';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
if (${expectDeclaration}) {
  // Knip previously saw either missing declarations or an incomplete build/lib directory.
  assert.equal(existsSync('compiler-active'), false);
  assert.equal(readFileSync('ui/component/model/build/lib/index.d.ts', 'utf8'), 'complete');
}
writeFileSync('knip-ran', 'checked');
`
  );
  return {
    root,
    run(...tasks) {
      const args = [
        "--offline",
        "--no-configuration-cache",
        "--no-daemon",
        "--parallel",
        "--max-workers=4",
        "--console=plain",
        "--project-dir",
        root,
        ...tasks,
      ];
      const result = spawnSync(
        process.platform === "win32" ? "cmd.exe" : wrapper,
        process.platform === "win32" ? ["/c", wrapper, ...args] : args,
        { cwd: repository, encoding: "utf8", timeout: 120_000 }
      );
      assert.ifError(result.error);
      assert.equal(result.status, 0, result.stdout + result.stderr);
      return result.stdout;
    },
  };
}

test("should serialize overlapping compiler outputs while allowing independent builds in parallel", (t) => {
  const project = fixture(
    t,
    {
      model: { script: compiler("npmBuild") },
      rest: { references: ["model"] },
      core: { references: ["rest"], script: compiler("npmBuild") },
      independent: { script: independentCompiler },
    },
    [":ui:component:model:npmBuild", ":ui:component:core:npmBuild", ":ui:component:independent:npmBuild"]
  );
  project.run("writers");
  assert.equal(existsSync(join(project.root, "independent-overlap")), true);
});

test("should serialize test compilers with shared transitive references without scheduling builds", (t) => {
  const project = fixture(
    t,
    {
      shared: { references: ["model"] },
      alpha: { references: ["shared"], script: compiler("npmTest") },
      beta: { references: ["shared"], script: compiler("npmTest") },
    },
    [":ui:component:alpha:npmTest", ":ui:component:beta:npmTest"]
  );
  const output = project.run("writers");
  assert.doesNotMatch(output, /:npmBuild/);
});

test("should run Knip after scheduled declaration writers", (t) => {
  const project = fixture(
    t,
    {
      model: { script: compiler("npmBuild") },
      core: { references: ["model"], script: compiler("npmTest") },
    },
    [":ui:component:model:npmBuild", ":ui:component:core:npmTest", ":ui:knip"],
    true
  );
  project.run("writers");
  assert.equal(existsSync(join(project.root, "knip-ran")), true);
});

test("should run standalone Knip without generating sources or compiling projects", (t) => {
  const project = fixture(t, { model: { script: compiler("npmBuild") } }, []);
  const output = project.run(":ui:knip");
  assert.equal(existsSync(join(project.root, "knip-ran")), true);
  assert.doesNotMatch(output, /:(npmBuild|npmTest|generateTypeScript)/);
});

test("should update compiler locks when TypeScript references change with the configuration cache enabled", (t) => {
  const script = `def enabled = rootProject.file('compiler-enabled')\n${compiler("npmBuild").replace(
    "doLast {",
    "doLast {\n    if (!enabled.exists()) return"
  )}`;
  const project = fixture(t, { model: { script }, core: { script } }, [
    ":ui:component:model:npmBuild",
    ":ui:component:core:npmBuild",
  ]);
  project.run("writers", "--configuration-cache");
  write(project.root, "ui/component/core/tsconfig.json", JSON.stringify({ references: [{ path: "../model" }] }));
  write(project.root, "compiler-enabled", "compile");
  const output = project.run("writers", "--configuration-cache");
  assert.match(output, /configuration cache cannot be reused/i);
  const cachedOutput = project.run("writers", "--configuration-cache");
  assert.match(cachedOutput, /Reusing configuration cache/);
});
