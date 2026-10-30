# OpenRemote UI AGENTS.md file

Applies to everything under `ui/`. See the root `AGENTS.md` for build, test stack and backend conventions.

### Build and test output

Every package writes its output to its own `build/` dir, so a single Gradle `clean` removes it and a single `**/build/**` pattern covers it in `.gitignore`, `ui/.prettierignore` and `ui/.eslintrc.json`. Do not introduce a new top-level dir for compiler output, bundles, generated manifests, reports, caches or auth state. Point the tool at `build/` instead: `outDir` and `tsBuildInfoFile` in the package `tsconfig.json`, `outdir` in the cem config, `outputDir` and the reporter's `outputFolder` in the Playwright configs, and `output.path` in `ui/util/rspack.util.js`. A package's `exports`, `module`, `types` and `files` must then point into `build/` too.

Packages with no Gradle plugin need `tasks.register('clean', Delete) { delete layout.buildDirectory }`, since only the `base` plugin supplies `clean`. Where a plugin is already applied, add nothing: a second `clean` is redundant, and configuring it through `doLast` breaks the configuration cache.

Anything a package references relative to its own emitted files must survive the extra `build/lib` nesting. Reach assets through a package self-reference resolved by an `exports` subpath, not a path relative to the output dir, so the same specifier works from `src` and from the compiled output.

Three outputs stay outside `build/` on purpose. `ui/app/shared/fonts` is packaged by a `generateSources` task that copies the project dir minus `build/**`. `ui/app/shared/cache` is a checksum-keyed download that `clean` would force to re-download. The generated `model.ts` and `restclient.ts` are sources rather than artifacts, so they stay under `rootDir`.

## Generated model

`ui/component/model/src/model.ts` is generated from the Java backend by typescript-generator and holds enums and constants as well as types. Do not edit it by hand. Anything mirroring a backend class or enum comes from `@openremote/model`, so import it rather than redeclaring an interface or hardcoding a member's value, e.g. `manager.hasRole(ClientRole.READ_ASSETS)` over the `"read:assets"` string.

A member you expect being absent means the file is stale, not that the value has to be written by hand. Regenerate with `./gradlew :ui:component:model:generateTypeScript`. The file is gitignored and the build regenerates it, so this never shows up in a diff.

## Base element

Own Lit components extend `OrElement` from `@openremote/or-element` instead of `LitElement` (also through mixins, e.g. `translate(i18next)(OrElement)`). It applies the shared shadow-DOM styling automatically. Vaadin wrappers are exempt; they extend their Vaadin base and are themed via Lumo.

## Fine-grained rendering

A state change should re-render only the part it affects. Re-rendering a whole page, or a large component such as a table, flickers and discards the state that component holds: sort order, pagination, selection, scroll position. Keep those children mounted and update their properties.

## Adding a new component package

New packages under `ui/component/` are picked up automatically by the yarn workspace and by Gradle (any dir with a `build.gradle`). The rsbuild apps are not automatic: add the package to the `@openremote/*` alias maps in `ui/app/manager/rsbuild.config.ts` and `ui/app/storybook/rsbuild.config.ts`, which resolve workspace packages to their `src` dirs. A missing entry fails the build with "Module not found" for any file importing the package.

## Adding a new Vaadin component

Vaadin-based components live in `ui/component/or-vaadin-components/src/` as thin wrappers that extend a Vaadin class and re-register it under an `or-vaadin-<name>` tag, e.g. `export class OrVaadinX extends (X as new () => X & LitElement)` with `@customElement("or-vaadin-x")`. Keep all `@vaadin/*` dependency versions aligned.

The date and time picker wrappers set their `i18n` themselves: the date order, clock and first day of the week follow the browser locale, so users keep their regional format, while month and weekday names and button labels follow the app language and update when it changes. Do not set `i18n` on them from outside.

### Styling the component's shadow-DOM internals

**How Vaadin applies styles.** A Vaadin element's shadow root has three layers, later ones winning at equal specificity:

1. Base styles defined in `static get styles()` of the Vaadin component class or our `or-vaadin-*` classes. The Lumo theme drops these when it injects, unless the component sets `includeBaseStyles: true` (badge, slider). Components without `LumoInjectionMixin`, such as `vaadin-dialog`, always keep them.
2. Lumo modules defined through `@media lumo_<name>` blocks are injected per tag as listed in `--_lumo-<tag>-inject-modules`. Lumo defines its modules and lists them this way in `@vaadin/vaadin-lumo-styles/components/<name>.css`. The list in our `ui/component/theme/src/components/or-vaadin-<name>.css` overwrites Lumo's list for that tag, so we include Lumo's declared list and include our own `@media lumo_` blocks. `components/index.css` therefore imports our file after every other file that declares a list for the same tag, including through a Lumo import.
3. Styles registered with ``registerStyles("<tag>", css`…`)`` are added after Lumo to elements whose `is` matches the tag, and are inherited by subclasses. Register them before `customElements.define` runs for that tag, otherwise existing instances are restyled and a console warning is logged. For our own tag that means on module level above the class.

Lumo and `registerStyles` match the element's `static get is()`, while base styles come with the class. From outside, custom properties and `::part()` rules beat the shadow DOM's own rules, and slotted light-DOM children such as native inputs take normal selectors. `test/styling-contract.test.ts` checks the theme and every element the package index exports against these layers.

Base styles and registered styles ship with the component code. Our Lumo module lists, `@media lumo_` blocks and `::part()` rules live in `@openremote/theme`. An app without that theme therefore gets the base styles, or Lumo's default lists if it loads Lumo itself, plus our registered styles. The hard-coded fallbacks below keep our own components usable there.

**Public API.** Projects may rely on what the custom-elements manifest documents: tags, attributes, properties, events, slots, `--or-*` properties and theme variants, plus the documented Vaadin API of thin wrappers. Do not rename or remove those in one step; keep the old name working with a warning for a release. Shadow structure, internal classes, Lumo module names, `--_*` properties and the parts of our own components are internal, and code that depends on Vaadin internals stays in this repo.

**Choosing the approach.** Use the first that fits:

1. A documented custom property, for a value such as a color, size or spacing.
2. A `::part()` rule in `ui/component/theme/src/components/or-vaadin-<name>.css`, keyed on the real tag.
3. A Lumo module in that file, for rules that must live inside the shadow DOM, such as `:host(...)` states, `::slotted()` or internal elements.
4. A theme variant: a Lumo module scoped by `:host([theme~="<variant>"])`, for one small change to an existing component, such as order, direction or colors. Lumo support for a Vaadin base variant, like `reverse`, keeps the Vaadin name.
5. A new `or-vaadin-<name>` component when the change touches several of those, needs another slot or template, or changes the semantics, as `or-vaadin-toggle` and `or-vaadin-radio-card` do:
   - Override `static get is()` and call `registerStyles("or-vaadin-<name>", …)` at module top, before the class is defined.
   - Reuse the Lumo look of the parts it keeps by listing their modules for the new tag, and style them through the Vaadin properties those modules read. Without listed modules the Vaadin base styles apply and need out-specifying with `:host(...)`.
   - Keep the slots the Vaadin mixins fill and the part names the reused modules target.
   - Expose `--or-*` properties with hard-coded fallbacks, and slots, rather than parts.

A look specific to one project stays in that project, built on the above. Do not add `static get styles()` to a wrapper whose Vaadin class drops its base styles under Lumo, and do not call `registerStyles()` for a `vaadin-*` tag, as it reaches every instance and subclass.

### Registering an input type in the input pipeline

To make an input type usable through the input pipeline, register it in `or-vaadin-input.ts` (`TEMPLATES` map + a `getXTemplate`) and update `value-input-provider.ts`. Where the Vaadin component represents the value differently than the attribute does, convert at both ends: `nativeValue` and `checkValidity` read and validate what the component holds, while each consumer serializes the value and any min/max into the attributes and converts back through `valueConverter`. Booleans expose `checked` instead of `value`, JSON types carry their value as formatted text, and the date time picker takes a local ISO string instead of a timestamp.

## Storybook

Component docs are MDX under `ui/app/storybook/docs/components/or-vaadin-<name>.mdx`; the stories are under `ui/component/or-vaadin-components/stories/or-vaadin-<name>.stories.ts`. To add one, copy an existing pair (e.g. `or-vaadin-checkbox`), keeping the relative story import in the MDX and the `ComponentDocs` block.

Stories call `getORStorybookHelpers(tagName)` and `setCustomElementsManifest(customElements)` using the `../custom-elements.json` manifest. That manifest is generated by `npm run analyze`, so a newly added component will render but show no args/argTypes/description until the manifest is regenerated.

Storybook covers any component under `ui/component/` (e.g. `or-chart`, `or-map`, `or-mwc-components`, `or-tree-menu`, `or-vaadin-components`). For each component package the stories live in that package's own `stories/` dir (`ui/component/<pkg>/stories/<name>.stories.ts`), and the human-readable docs are MDX under `ui/app/storybook/docs/components/<name>.mdx`. To add a component, copy an existing pair from the same package, keeping the relative story import in the MDX and the `ComponentDocs` block.

Stories call `getORStorybookHelpers(tagName)` (from `ui/component/storybook-utils.js`) and `setCustomElementsManifest(customElements)` off the package's own `../custom-elements.json`. That manifest is generated per-package by `npm run analyze` (cem), so a newly added component will render but show no args/argTypes/description until you regenerate its package's manifest. `custom-elements.json` (and `custom-elements-jsx.d.ts`) are generated and untracked.

## Writing UI tests

- **Test Naming:** `test.describe` blocks describe a feature. Tests should be named starting with "should ...".
- **Test Structure:** Keep tests flat by default. Omit top-level `test.describe` blocks. If grouping is needed, target a specific feature (e.g., filtering notifications) rather than a parent concept like the whole page. Example: `test.describe("Filter Notifications", ...)` instead of `test.describe("Notifications", ...)`.
- **Regression Tests:** Every bug fix ships with a test that fails without it. Name it after the behaviour that should hold rather than the defect, and record what used to go wrong in a comment on the assertion that catches it.

### Fixtures

- **Avoid Redundant Actions:** Do not create methods in fixtures that simply wrap native Playwright actions (e.g., `click()`, `getByRole()`, `expect()`). Use native Playwright actions directly in the tests where possible.
- **Provide Locators:** Provide fixture methods for locators with non-standard or complex paths (e.g., reliant on specific DOM structures) so others can reuse the correct locators across tests.
- **Data Fixtures:** Keep test data, such as assets, schemas or complex structures, in `test/fixtures/data/` rather than in the test file.

### App tests

- **Compilation:** Run `./gradlew clean installDist` after making changes to the UI source code to ensure they are applied before testing.
- **Location:** Define tests in `ui/app/<app-name>/test/`. Define fixtures in `ui/app/<app-name>/test/fixtures/`.
- **Comments:** Add scenario comments above tests (`@given`, `@when`, `@then`, `@and`) based on the acceptance criteria.
- **Auth State:** Select correct `storageState` for the task to be tested. Use `adminStatePath` for master/admin tasks. Use `userStatePath` for regular realm user tasks.

### Component testing

- **Location:** Define tests in `ui/component/<component-name>/test/`. Define fixtures in `ui/component/<component-name>/test/fixtures/`.
- **One `ct`:** The package's `test/fixtures/index.ts` exports a single `ct` with all its fixtures registered and re-exports `expect`, and tests import both from `./fixtures`. Settings that differ per test go through that `ct` instead of additional exported test objects. Options that Playwright only takes per file or describe block, such as `locale`, can be set per test by overriding the fixture to read a test annotation.

Component tests use Playwright component testing (`@sand4rt/experimental-ct-web`). They live in each package's `test/*.test.ts`, import `{ ct, expect }` from the package's `./fixtures` (or from `@openremote/test` when the package has no fixtures), and `mount(ComponentClass, { props, slots, on })`. Run them with `npm test` in the package (which does `tsc -b && playwright test`). CI runs `./gradlew -p ui/component npmTest`, which only executes packages that register an `npmTest` task, so when adding the first test to a package also register `npmTest` in its `build.gradle` file; copy the tasks from a sibling package. Prefer web-first, role-based assertions (`getByRole("checkbox", { name }).toBeChecked()`, `toHaveCount(...)`) over poking at JS properties (`toHaveJSProperty`) or internal locators. Some important quirks to know about:

- **Custom elements used as slotted/appended children must be eagerly registered.** Playwright CT turns each imported component into a _lazy_ dynamic import that only runs when that component is `mount()`ed, so a child element that is never mounted itself (e.g. `or-vaadin-toggle` slotted inside `or-vaadin-toggle-group`) never gets `customElements.define`d and stays an inert, unupgraded tag that appends to the DOM but does not render. Declare such components in the test itself via `mount(..., { hooksConfig: { components: [OrVaadinToggle] } })`; the `beforeMount` hook in `ui/test/playwright/index.js` resolves their import refs, which runs their modules and registers them before the mount.
- **Use only one `mount()` call per test.** Multiple mounts do not resolve to separate locator paths and can hang until the test times out on a strict mode violation.
- **Imports referenced outside a `mount()` call are evaluated in Node, where browser-only modules crash.** Any such usage (a props-building helper, a spread like `...StandardRenderers`) makes Node evaluate the imported module chain, which crashes on the `require("*.css")` calls in `or-mwc-components` (`SyntaxError: Unexpected token '.'`). Pass non-serializable values such as renderer arrays as bare identifiers inline in the `mount()` props. The inverse also holds: imported plain _data_ referenced inside `mount()` is mistaken for a component and replaced by an import-ref object, so extract the needed values to a local variable outside the call first (`const id = importedAsset.id`).
- **The `on` handler receives `event.detail`, not the event** (`listener(event.detail)`). This is inherent to `@sand4rt/experimental-ct-web`, which expects components to emit `CustomEvent`s and forwards only their `detail`, while components may also emit native events (like `change`) that carry no detail. So assert event values via events whose detail carries them; for Vaadin fields that is the `<prop>-changed` notify event (detail `{ value }`), while the native `change` event can only be counted. To await a single event's value, use the `shared.promiseEventDispatch()` fixture helper.
- **Vaadin fires an initial `<prop>-changed` at mount** (notify-on-first-commit), so a freshly mounted toggle emits `checked-changed(false)` before any interaction. Assert on the user-driven transitions (e.g. the last two values), not exact-array equality, and prove "emits nothing" with `not.toContain(true)` rather than `toEqual([])`.
- **Vaadin `<prop>-changed` events do not bubble or compose**, so a listener on a parent/group will not catch a child field's event; attach it to the field itself.
- **The default slot must be an array of single-element strings.** The CT runner builds each slot via `createContextualFragment(str).firstChild`, so a single string containing multiple elements silently keeps only the first; pass `slots: { default: ["<a>…</a>", "<b>…</b>"] }`.
- **Vaadin's `theme` is attribute-only.** `ThemePropertyMixin` derives a read-only `_theme` from the `theme` _attribute_; there is no writable reflecting `theme` property. So `mount(..., { props: { theme: "vertical" } })` sets an ignored JS property and the `:host([theme~='vertical'])` styles never apply (e.g. a "vertical" checkbox-group stays horizontal). Set it as an attribute instead: `await component.evaluate((el) => el.setAttribute("theme", "vertical"))`. (In Storybook/Lit templates `theme="vertical"` is already a real attribute, so it works there.)
- The `on` handler type is `Record<string, Function>`, so typed handler params are fine.
- **Mounted component sources must be JavaScript.** The CT bundle compiles `.ts` against `ui/test/tsconfig.json` (`rootDir` `ui/test`), so a TS component file anywhere else fails with TS6059. Mount components from the package's built lib, or write test-only fixture elements as plain `.js` (no decorators; register with `customElements.define`).
