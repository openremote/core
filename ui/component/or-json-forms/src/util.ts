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
  type CombinatorKeyword,
  composeWithUi,
  type ControlElement,
  createDefaultValue,
  deriveTypes,
  getAjv,
  getData,
  getRenderers,
  getSchema,
  hasShowRule,
  isVisible,
  type JsonFormsRendererRegistryEntry,
  type JsonFormsState,
  type JsonFormsSubStates,
  type JsonSchema,
  type JsonSchema4,
  type OwnPropsOfControl,
  type OwnPropsOfRenderer,
  Resolve,
  type StatePropsOfCombinator,
} from "@jsonforms/core";
import { Util } from "@openremote/core";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-components/or-ace-editor";
import type { OrAceEditor, OrAceEditorChangedEvent } from "@openremote/or-components/or-ace-editor";
import { html, type TemplateResult } from "lit";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import { unknownTemplate } from "./standard-renderers";
import { jsonEditorStyle } from "./styles";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import type { OrVaadinSelect, SelectItem } from "@openremote/or-vaadin-components/or-vaadin-select";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-select";
import "@openremote/or-vaadin-components/or-vaadin-button";

export function getTemplateFromProps<T extends OwnPropsOfRenderer>(
  state: JsonFormsSubStates | undefined,
  props: T | undefined
): TemplateResult | undefined {
  if (!state || !props) {
    return html``;
  }

  const renderers = props.renderers || getRenderers({ jsonforms: { ...state } });
  const schema = props.schema;
  const uischema = props.uischema;

  let template: TemplateResult | undefined;

  if (renderers && schema && uischema && state.core) {
    const orderedRenderers: [JsonFormsRendererRegistryEntry, number][] = renderers
      .map(
        (r) =>
          [
            r,
            r.tester(uischema, schema, {
              rootSchema: resolveSubSchemasRecursive(schema, state.core!.schema),
              config: state.config,
            }),
          ] as [JsonFormsRendererRegistryEntry, number]
      )
      .sort((a, b) => b[1] - a[1]);
    const renderer = orderedRenderers && orderedRenderers.length > 0 ? orderedRenderers[0] : undefined;
    if (renderer && renderer[1] !== -1) {
      template = renderer[0].renderer(state, props) as TemplateResult;
    } else {
      template = unknownTemplate();
    }
  }

  return template;
}

export interface CombinatorInfo {
  title: string;
  description: string;
  constProperty?: string;
  constValue?: any;
  defaultValueCreator: () => any;
}

/**
 * For a given anyOf schema array this will try and extract a common const property which can be used as a discriminator
 * when creating instances
 */
export function getCombinatorInfos(schemas: JsonSchema[], rootSchema: JsonSchema): CombinatorInfo[] {
  return schemas.map((schema) => {
    let constProperty: string | undefined;
    let constValue: any | undefined;
    let creator: () => any;
    const titleAndDescription = findSchemaTitleAndDescription(schema, rootSchema);

    if (schema.$ref) {
      schema = Resolve.schema(schema, "", rootSchema);
    }

    if (Array.isArray(schema.allOf)) {
      schema = Resolve.schema(schema, "allOf", rootSchema);
    }

    if (deriveTypes(schema).every((type) => type === "object")) {
      const props = getSchemaObjectProperties(schema);
      const constProp = props.find(([propName, propSchema]) => getSchemaConst(propSchema) !== undefined);
      if (constProp) {
        constProperty = constProp[0];
        constValue = getSchemaConst(constProp[1]);

        creator = () => {
          const obj: any = {};
          obj[constProp[0]] = getSchemaConst(constProp[1]);
          return obj;
        };

        if (!titleAndDescription[0]) {
          titleAndDescription[0] = getSchemaConst(constProp[1]);
        }
      } else {
        creator = () => createDefaultValue(schema, rootSchema);
      }
    } else {
      // Assume a primitive type that can be instantiated with default value creator
      creator = () => createDefaultValue(schema, rootSchema);
    }

    return {
      title: titleAndDescription[0],
      description: titleAndDescription[1],
      defaultValueCreator: creator,
      constProperty,
      constValue,
    } as CombinatorInfo;
  });
}

export function getSchemaConst(schema: JsonSchema): any {
  if (!schema) {
    return;
  }

  if (schema.const !== undefined) {
    return schema.const;
  }

  if (Array.isArray(schema.enum) && schema.enum!.length === 1) {
    return schema.enum[0];
  }
}

export function getSchemaPicker(
  rootSchema: JsonSchema,
  resolvedSchemas: JsonSchema[],
  label: string,
  selectedCallback: (selectedSchema: CombinatorInfo) => void
): TemplateResult {
  const combinatorInfos = getCombinatorInfos(resolvedSchemas, rootSchema);
  const options: SelectItem[] = combinatorInfos.map((combinatorInfo, index) => ({
    value: index + "",
    label: combinatorInfo.title || i18next.t("schema.title.indexedItem", { index }),
  }));
  const pickerUpdater = (index: number) => {
    const matchedInfo = combinatorInfos[index];
    selectedCallback(matchedInfo);
  };
  const pickerLabel = label ? i18next.t("schema.anyOfPickerLabel", { label }) : i18next.t("type");
  return html`
    <or-vaadin-select
      class="any-of-picker"
      .items=${options}
      @change=${(ev: Event) => pickerUpdater(Number((ev.currentTarget as OrVaadinSelect).value))}
    >
      <span slot="label">${pickerLabel}</span>
    </or-vaadin-select>
  `;
}

// TODO: use i18n translations from jsonforms/core
export function findSchemaTitleAndDescription(
  schema: JsonSchema,
  rootSchema: JsonSchema
): [string | undefined, string | undefined] {
  let title: string | undefined;

  if (schema.$ref) {
    title = getLabelFromScopeOrRef(schema.$ref);
    schema = Resolve.schema(schema, "", rootSchema);
  }

  if (schema.title) {
    return [schema.title, schema.description];
  }

  if (schema.allOf) {
    const resolvedSchema = Resolve.schema(schema, "allOf", rootSchema);
    const titledSchema = (resolvedSchema.allOf! as JsonSchema[]).find((allOfSchema) => {
      return !!allOfSchema.title;
    });
    if (titledSchema) {
      return [titledSchema.title, titledSchema.description];
    }
  }

  return [i18next.t("schema.title." + title, { defaultValue: Util.camelCaseToSentenceCase(title) }), undefined];
}

function getLabelFromScopeOrRef(scopeOrRef: string) {
  return scopeOrRef.substr(scopeOrRef.lastIndexOf("/") + 1);
}

function getSchemaObjectProperties(schema: JsonSchema): [string, JsonSchema][] {
  let props: [string, JsonSchema][] = [];

  if (schema.allOf) {
    props = schema.allOf.map((schema) => (schema.properties ? Object.entries(schema.properties) : [])).flat();
  } else if (schema.properties) {
    props = Object.entries(schema.properties);
  }

  return props;
}

const COMBINATOR_IDENTIFICATION_PROPERTY = "type";

/**
 * TODO: when updating to jsonforms/core 4.0 consider using `getCombinatorIndexOfFittingSchema` from jsonforms/core instead
 */
export function getCombinatorIndexOfFittingSchema(
  data: any,
  keyword: CombinatorKeyword,
  schema: JsonSchema & { discriminator?: { propertyName?: string } },
  rootSchema: JsonSchema
): number {
  if (typeof data !== "object" || data === null) {
    return -1;
  }

  const resolvedCombinatorSchemas = [];
  for (let i = 0; i < schema[keyword]!.length; i++) {
    let resolvedSchema = schema[keyword]![i];
    if (resolvedSchema.$ref) {
      resolvedSchema = Resolve.schema(rootSchema, resolvedSchema.$ref, rootSchema);
    }
    resolvedCombinatorSchemas.push(resolvedSchema);
  }

  let indexOfFittingSchema = -1;

  // Check if the data matches the identification property of one of the resolved schemas
  for (let i = 0; i < resolvedCombinatorSchemas.length; i++) {
    const resolvedSchema = resolvedCombinatorSchemas[i];
    const combinatorIdentificationProperty = schema?.discriminator?.propertyName || COMBINATOR_IDENTIFICATION_PROPERTY;

    // Match the identification property against a constant value in resolvedSchema
    const maybeConstIdValue = resolvedSchema.properties?.[combinatorIdentificationProperty]?.const;

    if (maybeConstIdValue !== undefined && data[combinatorIdentificationProperty] === maybeConstIdValue) {
      indexOfFittingSchema = i;
      break;
    }
  }

  return indexOfFittingSchema;
}

/**
 * Copied from eclipse source code
 * TODO: when updating to jsonforms/core 4.0 we could consider `mapStateToCombinatorRendererProps` from jsonform/core instead
 */
export function mapStateToCombinatorRendererProps(
  state: JsonFormsState,
  ownProps: OwnPropsOfControl,
  keyword: CombinatorKeyword
): Omit<StatePropsOfCombinator, "label" | "errors" | "enabled"> {
  const { uischema } = ownProps;
  const path = composeWithUi(uischema!, ownProps.path!);
  const rootSchema = getSchema(state);
  const resolvedSchema = Resolve.schema(ownProps.schema || rootSchema, uischema!.scope, rootSchema);
  const visible: boolean =
    ownProps.visible === undefined || hasShowRule(uischema!)
      ? isVisible(uischema!, getData(state), ownProps.path!, getAjv(state))
      : ownProps.visible;
  const id = ownProps.id!;
  const data = Resolve.data(getData(state), path);
  const schema = resolvedSchema || rootSchema;

  const indexOfFittingSchema = getCombinatorIndexOfFittingSchema(data, keyword, schema, rootSchema);

  return {
    data,
    path,
    schema,
    rootSchema,
    visible,
    id,
    indexOfFittingSchema,
    uischemas: state.jsonforms.uischemas!,
    uischema: uischema!,
  };
}

export function getLabel(
  schema: JsonSchema,
  rootSchema: JsonSchema,
  uiElementLabel?: string,
  uiElementScope?: string
): string | undefined {
  if (uiElementLabel) {
    return uiElementLabel;
  }

  const titleAndDesc = findSchemaTitleAndDescription(schema, rootSchema);

  if (titleAndDesc[0]) {
    return titleAndDesc[0];
  }

  if (uiElementScope) {
    return Util.camelCaseToSentenceCase(getLabelFromScopeOrRef(uiElementScope));
  }

  return undefined;
}

export function resolveSubSchemasRecursive(
  schema: JsonSchema,
  rootSchema: JsonSchema,
  keyword?: CombinatorKeyword
): JsonSchema {
  const combinators: string[] = keyword ? [keyword] : ["allOf", "anyOf", "oneOf"];

  if (schema.$ref) {
    return resolveSubSchemasRecursive(Resolve.schema(rootSchema, schema.$ref, rootSchema), rootSchema);
  }

  combinators.forEach((combinator) => {
    const schemas = (schema as any)[combinator] as JsonSchema[];

    if (schemas) {
      (schema as any)[combinator] = schemas.map((subSchema) => resolveSubSchemasRecursive(subSchema, rootSchema));
    }
  });

  if (schema.items) {
    if (Array.isArray(schema.items)) {
      schema.items = (schema.items as JsonSchema4[]).map(
        (itemSchema) => resolveSubSchemasRecursive(itemSchema, rootSchema) as JsonSchema4
      );
    } else {
      schema.items = resolveSubSchemasRecursive(schema.items as JsonSchema, rootSchema);
    }
  }

  if (schema.properties) {
    Object.keys(schema.properties).forEach(
      (prop) => (schema.properties![prop] = resolveSubSchemasRecursive(schema.properties![prop], rootSchema))
    );
  }

  return schema;
}

export const controlWithoutLabel = (scope: string): ControlElement => ({
  type: "Control",
  scope,
  label: false,
});

export const showJsonEditor = (
  host: Node,
  title: string,
  value: any,
  updateCallback: (newValue: string) => void
): void => {
  const editorRef: Ref<OrAceEditor> = createRef();
  const updateBtnRef: Ref<OrVaadinButton> = createRef();
  let dialog: OrVaadinDialog | undefined;

  const onEditorEdit = () => {
    // Disable update button whilst edit in progress
    updateBtnRef.value!.disabled = true;
  };
  const onEditorChanged = (ev: OrAceEditorChangedEvent) => {
    const valid = ev.detail.valid;
    updateBtnRef.value!.disabled = !valid;
  };
  const onUpdate = () => {
    const editor = editorRef.value!;
    if (editor.validate()) {
      const data = editor.getValue() ? JSON.parse(editor.getValue()!) : undefined;
      updateCallback(data);
      dialog?.close();
    }
  };

  dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog width="1024px">
        <h2 slot="header-content">${title}</h2>
        <or-ace-editor
          ${ref(editorRef)}
          style="${jsonEditorStyle}"
          @or-ace-editor-edit="${() => onEditorEdit()}"
          @or-ace-editor-changed="${(ev: OrAceEditorChangedEvent) => onEditorChanged(ev)}"
          .value="${value}"
        ></or-ace-editor>
        <div slot="footer" style="width: 100%; display: flex; justify-content: space-between;">
          <or-vaadin-button theme="tertiary" @click="${() => dialog?.close()}">
            <or-translate value="cancel"></or-translate>
          </or-vaadin-button>
          <or-vaadin-button ${ref(updateBtnRef)} theme="primary" disabled @click="${() => onUpdate()}">
            <or-translate value="update"></or-translate>
          </or-vaadin-button>
        </div>
      </or-vaadin-dialog>
    `
  );
};
