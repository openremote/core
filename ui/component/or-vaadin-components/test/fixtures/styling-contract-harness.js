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
// Plain JS on purpose: the component-test bundle compiles .ts files against ui/test's tsconfig
// (rootDir ui/test), so TS component sources outside that dir fail the build.
import { LitElement } from "lit";
import { parseStyleSheets } from "@vaadin/vaadin-themable-mixin/src/lumo-modules.js";
import * as components from "@openremote/or-vaadin-components";

/**
 * Reports the styling rules that the loaded theme and every element exported by the package index break. Elements are
 * found through the index, so a new component is checked as soon as it is exported.
 */
export class StylingContractHarness extends LitElement {
  get violations() {
    const violations = [];
    const { tags, modules } = parseStyleSheets([...document.styleSheets, ...document.adoptedStyleSheets]);

    if (!tags.size) {
      violations.push("The theme lists no Lumo modules");
    }
    for (const [tag, names] of tags) {
      for (const name of names.filter((name) => !modules.has(name))) {
        violations.push(`${tag}: Lumo module "${name}" does not exist`);
      }
    }

    for (const element of Object.values(components)) {
      const tag = typeof element === "function" ? customElements.getName(element) : null;
      // Lumo drops the base styles of these elements, and our static styles are part of those
      if (!tag || element.lumoInjector?.includeBaseStyles !== false) {
        continue;
      }
      for (let cls = element; customElements.getName(cls)?.startsWith("or-"); cls = Object.getPrototypeOf(cls)) {
        if (Object.hasOwn(cls, "styles")) {
          violations.push(`${tag}: static styles are dropped once Lumo styles are injected`);
        }
      }
    }

    return violations;
  }
}
customElements.define("test-styling-contract-harness", StylingContractHarness);
