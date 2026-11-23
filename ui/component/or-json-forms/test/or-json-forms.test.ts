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
import { ct } from "./fixtures/index";

import { expect } from "@openremote/test";
import { OrJSONForms, StandardRenderers } from "@openremote/or-json-forms";
import { schemas } from "./fixtures/schemas";

const typeValueMap = new Map()
  .set("array", [])
  .set("boolean", false)
  .set("object", {})
  .set("number", 0)
  .set("integer", 0)
  .set("string", "");

ct.beforeEach(async ({ shared }) => {
  await shared.fonts();
  await shared.locales();
});

for (const schema of schemas) {
  ct(`Should render form for: ${schema.title}`, async ({ mount, jsonForms }) => {
    const component = await mount(OrJSONForms, {
      props: {
        uischema: { type: "Control", scope: "#" } as any,
        schema,
        data: typeValueMap.get(schema.type),
        renderers: StandardRenderers,
        readonly: false,
        label: schema.title,
        required: false,
      },
      on: {},
    });
    await jsonForms.trackData(component);
    await jsonForms.walkForm(component, schema);

    await expect(jsonForms.getData(component)).resolves.toEqual(schema["or:test:expected"]);
    // Only an array or an object carries the header that opens the JSON editor
    if (schema.type === "array" || schema.type === "object") {
      await expect(jsonForms.getEditorJson(component)).resolves.toEqual(schema["or:test:expected"]);
    }
    await expect(jsonForms.getValidity(component)).resolves.toBeTruthy();
  });
}
