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

function fixture(
  t,
  modules,
  writers,
  { expectDeclaration = false, requireBrowserInstall = false, environment = {} } = {}
) {
  const root = mkdtempSync(join(tmpdir(), "openremote-ui-locks-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const projects = { model: {}, rest: {}, "or-icon": { script: "tasks.register('prepareFonts')" }, ...modules };
  write(
    root,
    "settings.gradle",
    `rootProject.name = 'ui-concurrency-tests'\ninclude ':ui', ':manager', ':deployment', ':ui:test', ':ui:app:manager', ':ui:app:shared'\n${Object.keys(
      projects
    )
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
project(':ui:test') {
  afterEvaluate {
    [installBrowsers: 'install-browser.mjs', verifyBrowsersInstalled: 'check-browser.mjs'].each { name, script ->
      tasks.named(name, Exec) {
        workingDir rootProject.projectDir
        commandLine ${JSON.stringify(process.execPath)}, rootProject.file(script).absolutePath
      }
    }
  }
}
`
  );
  mkdirSync(join(root, "ui/test"), { recursive: true });
  for (const path of ["ui/build.gradle", "ui/test/build.gradle"]) {
    copyFileSync(join(repository, path), join(root, path));
  }
  write(root, "deployment/build.gradle", "tasks.register('installDist') { dependsOn ':ui:app:manager:installDist' }\n");
  write(
    root,
    "install-browser.mjs",
    "import { writeFileSync } from 'node:fs';\nwriteFileSync('browser-installed', 'installed');\n"
  );
  write(
    root,
    "check-browser.mjs",
    `import assert from 'node:assert/strict';
import { existsSync, writeFileSync } from 'node:fs';
if (${requireBrowserInstall}) assert.ok(existsSync('browser-installed'), 'Browser installation must run before verification');
writeFileSync('browser-checked', 'checked');
`
  );
  for (const path of ["manager", "ui/app/shared"]) {
    write(root, `${path}/build.gradle`, "tasks.register('installDist')\n");
  }
  write(
    root,
    "ui/app/manager/build.gradle",
    "tasks.register('npmBuild')\ntasks.register('installDist') { dependsOn 'npmBuild' }\ntasks.register('npmTest')\n"
  );
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
        {
          cwd: repository,
          encoding: "utf8",
          timeout: 120_000,
          env: { ...process.env, GITHUB_ACTIONS: "false", ...environment },
        }
      );
      assert.ifError(result.error);
      assert.equal(result.status, 0, result.stdout + result.stderr);
      return result.stdout;
    },
  };
}

test("should install browsers before verification and build preparation when explicitly requested", (t) => {
  const task = ":ui:component:element:npmTest";
  const project = fixture(t, { element: { script: "tasks.register('npmTest')" } }, [], { requireBrowserInstall: true });
  const output = project.run(":deployment:installDist", ":ui:test:installBrowsers", task);
  const install = output.indexOf("> Task :ui:test:installBrowsers");
  const verify = output.indexOf("> Task :ui:test:verifyBrowsersInstalled");
  // The preflight order previously prevented the requested installer from preparing the browser.
  assert.ok(install !== -1 && verify > install, output);
  assert.ok(output.indexOf("> Task :deployment:installDist") > verify, output);
  assert.ok(output.indexOf(`> Task ${task}`) > verify, output);
  assert.equal(existsSync(join(project.root, "browser-checked")), true);
});

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

test("should serialize test compilers with shared transitive references while reusing artifacts", (t) => {
  const project = fixture(
    t,
    {
      shared: { references: ["model"] },
      alpha: { references: ["shared"], script: compiler("npmTest") },
      beta: { references: ["shared"], script: compiler("npmTest") },
    },
    [":ui:component:alpha:npmTest", ":ui:component:beta:npmTest"]
  );
  const output = project.run("writers", "-PreuseUiTestArtifacts=true");
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
    { expectDeclaration: true }
  );
  project.run("writers");
  assert.equal(existsSync(join(project.root, "knip-ran")), true);
});

test("should prepare component test sources and fonts without building Manager distributions", (t) => {
  const task = ":ui:component:element:npmTest";
  const project = fixture(t, { element: { script: "tasks.register('npmTest')" } }, [task]);
  const output = project.run("writers");
  // Component tests previously pulled in backend packaging and the Manager UI bundle.
  assert.doesNotMatch(output, /:(installDist|npmBuild|npmAnalyze)\b/);
  for (const prerequisite of [
    ":ui:component:model:generateTypeScript",
    ":ui:component:rest:generateTypeScript",
    ":ui:component:or-icon:prepareFonts",
  ]) {
    const preparation = output.indexOf(`> Task ${prerequisite}`);
    assert.notEqual(preparation, -1, output);
    assert.ok(output.indexOf(`> Task ${task}`) > preparation, output);
  }
});

for (const { githubActions, target } of [
  { githubActions: false, target: "npmTest" },
  { githubActions: true, target: "check" },
]) {
  test(`should prepare app distributions for ${target} with GITHUB_ACTIONS=${githubActions}`, (t) => {
    const project = fixture(t, {}, [], { environment: { GITHUB_ACTIONS: String(githubActions) } });
    const output = project.run(`:ui:app:manager:${target}`);
    for (const distribution of [":manager:installDist", ":ui:app:manager:installDist", ":ui:app:shared:installDist"]) {
      const preparation = output.indexOf(`> Task ${distribution}`);
      // Running on GitHub Actions alone previously disabled required app preparation.
      assert.notEqual(preparation, -1, output);
      assert.ok(output.indexOf("> Task :ui:app:manager:npmTest") > preparation, output);
    }
  });
}

test("should reuse artifacts for standalone tests and checks only when explicitly requested", (t) => {
  const project = fixture(t, { element: { script: "tasks.register('npmTest')" } }, [
    ":ui:component:element:npmTest",
    ":ui:app:manager:npmTest",
  ]);
  for (const targets of [["writers"], [":ui:component:element:check", ":ui:app:manager:check"]]) {
    const output = project.run(...targets, "-PreuseUiTestArtifacts=true");
    assert.match(output, /> Task :ui:component:element:npmTest/);
    assert.match(output, /> Task :ui:app:manager:npmTest/);
    assert.doesNotMatch(output, /:(installDist|npmBuild|generateTypeScript|prepareFonts)\b/);
  }
});

test("should track the artifact reuse property with the configuration cache enabled", (t) => {
  const project = fixture(t, { element: { script: "tasks.register('npmTest')" } }, [
    ":ui:component:element:npmTest",
    ":ui:app:manager:npmTest",
  ]);
  const localOutput = project.run("writers", "--configuration-cache", "-PreuseUiTestArtifacts=false");
  assert.match(localOutput, /> Task :manager:installDist/);
  assert.match(localOutput, /> Task :ui:component:or-icon:prepareFonts/);
  assert.match(
    project.run("writers", "--configuration-cache", "-PreuseUiTestArtifacts=false"),
    /Reusing configuration cache/
  );
  const reuseOutput = project.run("writers", "--configuration-cache", "-PreuseUiTestArtifacts=true");
  assert.match(reuseOutput, /configuration cache cannot be reused/i);
  assert.doesNotMatch(reuseOutput, /:(installDist|npmBuild|generateTypeScript|prepareFonts)\b/);
  const cachedOutput = project.run("writers", "--configuration-cache", "-PreuseUiTestArtifacts=true");
  assert.match(cachedOutput, /Reusing configuration cache/);
  assert.doesNotMatch(cachedOutput, /:(installDist|npmBuild|generateTypeScript|prepareFonts)\b/);
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

for (const [target, packaging] of [
  ["prepareUi", "npmPrepare"],
  ["publishUi", "npmPublish"],
]) {
  test(`should retain compiled modules when running ${target}`, (t) => {
    const project = fixture(
      t,
      {
        rest: {
          script: `
def compiledModule = layout.buildDirectory.file('lib/index.js')
tasks.register('npmBuild') {
  doLast {
    def output = compiledModule.get().asFile
    output.parentFile.mkdirs()
    output.text = 'complete'
  }
}
tasks.register('${packaging}') {
  doLast {
    // Clean previously ran after compilation and removed the module before packaging.
    assert compiledModule.get().asFile.text == 'complete'
  }
}
tasks.register('${target}') {
  dependsOn 'clean', 'assemble', '${packaging}'
}
`,
        },
      },
      []
    );
    project.run(`:ui:component:rest:${target}`);
    assert.ok(existsSync(join(project.root, "ui/component/rest/build/lib/index.js")));
  });
}

test("should coordinate compilers through commented TypeScript references with trailing commas", (t) => {
  const project = fixture(
    t,
    {
      model: { script: compiler("npmBuild") },
      rest: { references: ["model"] },
      core: { references: ["rest"], script: compiler("npmBuild") },
    },
    [":ui:component:model:npmBuild", ":ui:component:core:npmBuild"]
  );
  for (const [name, reference] of [
    ["core", "rest"],
    ["rest", "model"],
  ]) {
    write(
      project.root,
      `ui/component/${name}/tsconfig.json`,
      `{
  // References can include comments and trailing commas.
  "references": [
    /* Follow transitive references too. */
    { "path": "../${reference}" },
  ],
}`
    );
  }
  // Strict JSON parsing previously failed before the compiler locks could be configured.
  project.run("writers", "--configuration-cache");
  assert.match(project.run("writers", "--configuration-cache"), /Reusing configuration cache/);
});
