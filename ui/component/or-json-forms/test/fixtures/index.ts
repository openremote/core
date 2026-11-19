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
import {
  withPage,
  ct as base,
  expect,
  type Page,
  type Locator,
  type SharedComponentTestFixtures,
} from "@openremote/test";
import type { JsonSchema7, OrJSONForms } from "@openremote/or-json-forms";
import type { OrAceEditor } from "@openremote/or-components/or-ace-editor";
import * as Util from "@openremote/core/lib/util";

interface WalkFormOptions {
  /**
   * Whether all possible object properties should be added.
   * If falsy, then only the specified `or:test:props` are added.
   * @example
   * ```ts
   * "or:test:props": ["prop1", "prop2"]
   * ```
   */
  selectAllProps?: boolean;
}

export interface JsonSchema extends JsonSchema7 {
  /**
   * Allow schemas to specify a custom discriminator property to resolve subtypes
   */
  discriminator?: { propertyName?: string };
  /**
   * Describes what properties should be manually selected by {@link JsonForms#walkForm}
   */
  "or:test:props"?: string[];
  /**
   * Describes what value to set in {@link JsonForms#walkForm}
   */
  "or:test:value"?: any;
  /**
   * Describes how many items to add to an array control {@link JsonForms#walkForm}
   */
  "or:test:item:count"?: number;
  /**
   * Describes whether {@link JsonForms#walkForm} should delete the property again once it has been filled
   */
  "or:test:remove"?: boolean;
  /**
   * The whole JSON document the form should hold once {@link JsonForms#walkForm} has been through it
   */
  "or:test:expected"?: unknown;
  /**
   * The following overwrites the {@link JsonSchema7} types with {@link JsonSchema}
   */
  /***/
  additionalItems?: boolean | JsonSchema;
  items?: JsonSchema | JsonSchema[];
  additionalProperties?: boolean | JsonSchema;
  /**
   * Holds simple JSON Schema definitions for
   * referencing from elsewhere.
   */
  definitions?: {
    [key: string]: JsonSchema;
  };
  /**
   * The keys that can exist on the object with the
   * json schema that should validate their value
   */
  properties?: {
    [property: string]: JsonSchema;
  };
  /**
   * The key of this object is a regex for which
   * properties the schema applies to
   */
  patternProperties?: {
    [pattern: string]: JsonSchema;
  };
  /**
   * If the key is present as a property then the
   * string of properties must also be present.
   * If the value is a JSON Schema then it must
   * also be valid for the object if the key is
   * present.
   */
  dependencies?: {
    [key: string]: JsonSchema | string[];
  };
  allOf?: JsonSchema[];
  anyOf?: JsonSchema[];
  oneOf?: JsonSchema[];
  /**
   * The entity being validated must not match this schema
   */
  not?: JsonSchema;
  contains?: JsonSchema;
  propertyNames?: JsonSchema;
  if?: JsonSchema;
  then?: JsonSchema;
  else?: JsonSchema;
}

type Path = (string | number)[];
// An array control renders its items with `controlWithoutLabel`, so they carry no accessible name to match on
type Parent = "array" | "object";

export class JsonForms {
  constructor(
    private readonly page: Page,
    private dialog: Locator
  ) {
    // A closing dialog is only removed once its animation ends, so match on the open one to stay unambiguous
    this.dialog = this.page.locator("or-vaadin-dialog[opened]");
  }

  /**
   * Exhaust every `or-json-forms` option based on the provided JSONSchema. Adds items to arrays and parameters to
   * objects, fills every primitive and deletes the properties that ask for it, leaving the form holding the document
   * that {@link JsonForms#getData} returns and `or:test:expected` describes.
   *
   * Reordering items by dragging is not covered: the handle uses HTML5 drag and drop with a custom drag image, which
   * Playwright drives too unreliably to assert on.
   *
   * @param locator The root element of the `or-json-forms` instance to test.
   * @param schema The same JSONSchema used to generate the form.
   * @param options Options to fill out in the forms without explicitly defining the values.
   * @param path The path to the current node.
   * @param item The item index used to locate array items.
   * @param parent Whether the node sits in an array, whose items carry no accessible name.
   */
  async walkForm(
    locator: Locator,
    schema: JsonSchema,
    options?: WalkFormOptions,
    path: Path = [],
    item = 0,
    parent?: Parent
  ) {
    switch (schema.type) {
      case "array": {
        locator = await this.walkArray(locator, schema, path, item);
        break;
      }
      case "object": {
        locator = await this.walkObject(locator, schema, options, path, item);
        break;
      }
      case "string":
      case "number":
      case "integer":
      case "boolean": {
        await this.walkPrimitive(locator, schema, item, parent);
        break;
      }
    }

    if (schema.type === "array") {
      if (!Array.isArray(schema?.items) && schema.items?.oneOf) {
        let i = 0;
        for (const prop of Object.values(schema.items.oneOf)) {
          // A subtype is referenced, so it has to be resolved before the walk can descend into its properties
          const subType = prop.$ref ? this.resolveSchema(schema, prop.$ref) : (prop as JsonSchema);
          await this.walkForm(locator, subType, options, [...path], i, "array");
          i++;
        }
      } else {
        for (let i = 0; i < (schema?.["or:test:item:count"] ?? 0); i++) {
          await this.walkForm(locator, schema.items as JsonSchema, options, [...path], i, "array");
        }
      }
    } else if (schema.type === "object" && !schema.patternProperties) {
      let arrayControls = 0;
      let verticalLayouts = 0;
      for (const [key, prop] of Object.entries<any>(schema.properties ?? {})) {
        if (schema["or:test:props"] && !schema["or:test:props"].includes(key)) continue;
        if (["type", "id"].includes(key)) continue;
        if (prop.type === "array") {
          await this.walkForm(locator, prop, options, [...path], arrayControls);
          arrayControls++;
        } else if (prop.type === "object") {
          await this.walkForm(locator, prop, options, [...path], verticalLayouts);
          verticalLayouts++;
        } else {
          await this.walkForm(locator, prop, options, [...path]);
        }
        if (prop["or:test:remove"]) {
          await this.removeProperty(locator, prop.title ?? key);
        }
      }
    }
  }

  public async getValidity(form: Locator) {
    return form.evaluate((el: OrJSONForms) => el.checkValidity());
  }

  /**
   * Starts collecting the document the form reports, so that {@link JsonForms#getData} can return the latest. The form
   * only exposes its current document through `onChange`, so this has to be in place before the walk starts.
   */
  public async trackData(form: Locator) {
    await form.evaluate((el: OrJSONForms) => {
      const store = window as unknown as { orJsonFormsData?: unknown };
      store.orJsonFormsData = el.data;
      el.onChange = ({ data }) => (store.orJsonFormsData = data);
    });
  }

  /**
   * Returns the document the form last reported, once its pending update has settled so that the final edit has been
   * serialized into it.
   */
  public async getData(form: Locator) {
    return form.evaluate(async (el: OrJSONForms) => {
      await el.updateComplete;
      return (window as unknown as { orJsonFormsData?: unknown }).orJsonFormsData;
    });
  }

  /**
   * Opens the JSON editor of the outermost control and returns the document it shows, which is how the form presents
   * its value to the user rather than what it reports through `onChange`. Closes the dialog again through cancel, so
   * that reading the value cannot change it.
   */
  public async getEditorJson(form: Locator) {
    await form.getByRole("button", { name: "JSON", exact: true }).first().click();
    const editor = this.dialog.locator("or-ace-editor");
    // The editor only holds a value once Ace has rendered into it, which its content layer appearing marks
    await expect(editor.locator(".ace_content")).toBeVisible();
    const text = await editor.evaluate((el: OrAceEditor) => el.getValue());

    await this.dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(this.dialog).toHaveCount(0);
    return JSON.parse(text ?? "null");
  }

  private async walkArray(locator: Locator, schema: JsonSchema, path: Path, item: number) {
    locator = locator.locator("or-json-forms-array-control").nth(item);
    path.push("or-json-forms-array-control", item);
    await locator.locator("or-collapsible-panel").click();

    if (!Array.isArray(schema?.items) && schema.items?.oneOf) {
      for (const subType of Object.values(schema.items.oneOf)) {
        await locator.getByRole("button", { name: "Add Item" }).click();
        let resolvedSchema = structuredClone(subType);
        if (subType.$ref) {
          resolvedSchema = this.resolveSchema(schema, subType.$ref!);
        }
        await this.dialog.getByRole("option", { name: resolvedSchema.title, exact: true }).click();
        await this.dialog.getByRole("button", { name: "Add", exact: true }).click();
      }
    } else {
      for (let i = 0; i < (schema?.["or:test:item:count"] ?? 0); i++) {
        await locator.getByRole("button", { name: "Add Item" }).click();
      }
    }
    return locator;
  }

  private async walkObject(locator: Locator, schema: JsonSchema, options: WalkFormOptions, path: Path, item: number) {
    locator = locator.locator("or-json-forms-vertical-layout").nth(item);
    path.push("or-json-forms-vertical-layout", item);
    await locator.locator("or-collapsible-panel").click();

    if (schema.patternProperties) {
      await locator.getByRole("button", { name: "Add Parameter" }).click();
      await this.dialog.getByRole("textbox", { name: "Key" }).pressSequentially("test");
      await this.dialog.getByRole("button", { name: "Add", exact: true }).click();
    } else {
      const properties = options?.selectAllProps
        ? Object.keys(schema.properties ?? {})
        : (schema?.["or:test:props"] ?? []);

      for (const key of properties) {
        if (key === "type" || key === "id" || schema.required?.includes(key)) continue;
        await locator.getByRole("button", { name: "Add Parameter" }).click();

        const name = Util.camelCaseToSentenceCase(key);
        await this.dialog.getByRole("option", { name, exact: true }).click();
        const anyOfPicker = this.dialog.locator("#schema-picker or-vaadin-select");
        if (await anyOfPicker.isVisible()) {
          await anyOfPicker.click();
          await this.page.getByRole("listbox").getByRole("option").first().click();
        }
        await this.dialog.getByRole("button", { name: "Add", exact: true }).click();
      }
    }
    return locator;
  }

  private async walkPrimitive(locator: Locator, schema: JsonSchema, item: number, parent?: Parent) {
    const { type, role, fallback } = [
      { type: "boolean", role: "checkbox", fallback: false },
      { type: "integer", role: "spinbutton", fallback: 0 },
      { type: "number", role: "spinbutton", fallback: 0 },
      { type: "string", role: "textbox", fallback: "test" },
    ].find(({ type }) => type === schema.type)!;

    // TODO: remove first condition when or-json-forms-array-control always renders titles
    const options = parent !== "array" && schema.title ? { name: schema.title, exact: true } : {};
    locator = locator.getByRole(role as any, options).nth(item);
    await expect(locator).toBeVisible();

    if (type === "boolean") {
      await expect(locator).not.toBeChecked();
      if (schema["or:test:value"]) {
        await locator.check();
        await expect(locator).toBeChecked();
      }
    } else {
      const value = schema["or:test:value"] ?? fallback;
      await locator.fill(String(value));
      await expect(locator).toHaveValue(String(value));
      // A text or number field commits on blur, so filling it alone leaves the value out of the form data
      await locator.blur();
    }
  }

  /**
   * Deletes a property that has already been filled, through the delete button of the container it shares with its
   * control, so that the expected document can show it absent again.
   */
  private async removeProperty(locator: Locator, title: string) {
    // An exact label match, since a substring would also pick up a property whose title merely contains this one
    const container = locator.locator(".item-container").filter({ has: this.page.getByText(title, { exact: true }) });
    await container.getByRole("button", { name: "Delete", exact: true }).click();
    await expect(container).toHaveCount(0);
  }

  private resolveSchema(schema: JsonSchema, ref: string) {
    const segments = ref.split("/");
    return schema.definitions![segments[segments.length - 1]];
  }
}

interface ComponentFixtures extends SharedComponentTestFixtures {
  jsonForms: JsonForms;
}

export const ct = base.extend<ComponentFixtures>({
  jsonForms: withPage(JsonForms),
});
