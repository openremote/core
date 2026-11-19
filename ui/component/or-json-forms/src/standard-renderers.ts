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
  and,
  type ControlElement,
  type ControlProps,
  createCombinatorRenderInfos,
  type DispatchPropsOfControl,
  findUISchema,
  hasType,
  isAllOfControl,
  isAnyOfControl,
  isBooleanControl,
  isDateControl,
  isDateTimeControl,
  isEnumControl,
  isIntegerControl,
  isNumberControl,
  isObjectControl,
  isOneOfControl,
  isOneOfEnumControl,
  isStringControl,
  isTimeControl,
  type JsonFormsRendererRegistryEntry,
  type JsonSchema,
  mapDispatchToControlProps,
  mapStateToControlProps,
  mapStateToControlWithDetailProps,
  mapStateToLayoutProps,
  or,
  type OwnPropsOfJsonFormsRenderer,
  type OwnPropsOfRenderer,
  type RankedTester,
  rankWith,
  type RendererProps,
  resolveSchema,
  schemaMatches,
  schemaSubPathMatches,
  type StatePropsOfControlWithDetail,
  type Tester,
  uiTypeIs,
} from "@jsonforms/core";
import { html, type TemplateResult } from "lit";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-vaadin-components/or-vaadin-input";
import type { JsonFormsStateContext } from "./index";
import {
  getCombinatorInfos,
  getLabel,
  getSchemaConst,
  getSchemaPicker,
  getTemplateFromProps,
  mapStateToCombinatorRendererProps,
} from "./util";
import "./layouts/layout-vertical-element";
import "./controls/control-input-element";
import "./controls/control-array-element";
import type { AdditionalProps } from "./base-element";
import { Util } from "@openremote/core";

const hasOneOfItems = (schema: JsonSchema): boolean =>
  schema.oneOf !== undefined &&
  schema.oneOf.length > 0 &&
  (schema.oneOf as JsonSchema[]).every((entry: JsonSchema) => {
    return getSchemaConst(entry) !== undefined;
  });

const hasEnumItems = (schema: JsonSchema): boolean => Array.isArray(schema.enum);

export const isEnumArray: Tester = and(
  uiTypeIs("Control"),
  and(
    schemaMatches((schema) => hasType(schema, "array") && !Array.isArray(schema.items) && schema.uniqueItems === true),
    schemaSubPathMatches("items", (schema) => {
      return hasOneOfItems(schema) || hasEnumItems(schema);
    })
  )
);

export const verticalOrGroupLayoutTester: RankedTester = rankWith(1, or(uiTypeIs("VerticalLayout"), uiTypeIs("Group")));

export const verticalLayoutRenderer = (
  state: JsonFormsStateContext,
  props: OwnPropsOfJsonFormsRenderer & AdditionalProps
) => {
  const contentProps: RendererProps & DispatchPropsOfControl & AdditionalProps = {
    ...mapStateToLayoutProps({ jsonforms: { ...state } }, props),
    ...mapDispatchToControlProps(state.dispatch),
    label: props.label,
    required: props.required,
    errors: props.errors,
    minimal: props.minimal,
    type: props.type,
  };

  const template = html`<or-json-forms-vertical-layout
    style="max-width: 100%"
    .state="${state}"
    .props="${contentProps}"
  ></or-json-forms-vertical-layout>`;
  let deleteHandler: undefined | (() => void);
  if (!contentProps.required && contentProps.path) {
    deleteHandler = () => {
      contentProps.handleChange(contentProps.path || "", undefined);
    };
  }
  return getTemplateWrapper(template, deleteHandler);
};

export const constTester: RankedTester = rankWith(
  6,
  schemaMatches((schema) => getSchemaConst(schema) !== undefined)
);
export const constRenderer = (state: JsonFormsStateContext, props: OwnPropsOfJsonFormsRenderer) => {
  // Don't render const
  return undefined;
};

export const inputControlTester: RankedTester = rankWith(
  3,
  or(
    schemaMatches((schema) => Array.isArray(schema.type) && schema.type.length === 7),
    isStringControl,
    isBooleanControl,
    isNumberControl,
    isIntegerControl,
    isDateControl,
    isTimeControl,
    isDateTimeControl,
    isEnumControl,
    isOneOfEnumControl,
    isEnumArray
  )
);
export const inputControlRenderer = (state: JsonFormsStateContext, props: ControlProps) => {
  const contentProps: ControlProps = {
    ...mapStateToControlProps({ jsonforms: { ...state } }, props),
    ...mapDispatchToControlProps(state.dispatch),
  };

  contentProps.label = props.label || contentProps.label;
  contentProps.required = !!props.required || contentProps.required;

  const template = html`<or-json-forms-input-control
    .state="${state}"
    .props="${contentProps}"
  ></or-json-forms-input-control>`;
  let deleteHandler: undefined | (() => void);
  if (!contentProps.required && contentProps.path) {
    deleteHandler = () => {
      contentProps.handleChange(contentProps.path, undefined);
    };
  }
  return getTemplateWrapper(template, deleteHandler);
};

export const objectControlTester: RankedTester = rankWith(2, isObjectControl);
export const objectControlRenderer = (state: JsonFormsStateContext, props: ControlProps & AdditionalProps) => {
  const { required, renderers, cells, uischemas, schema, label, errors, path, visible, enabled, uischema, rootSchema } =
    mapStateToControlWithDetailProps({ jsonforms: { ...state } }, props);

  const detailUiSchema = findUISchema(uischemas!, schema, uischema.scope, path, "VerticalLayout", uischema, rootSchema);

  const contentProps: OwnPropsOfRenderer & AdditionalProps = {
    visible,
    enabled,
    schema,
    uischema: detailUiSchema,
    path,
    renderers,
    cells,
    label: props.label || getLabel(schema, rootSchema, label) || "",
    required: !!props.required || !!required,
    errors,
    minimal: props.minimal,
  };
  return getTemplateFromProps(state, contentProps);
};

export const anyOfOneOfControlTester: RankedTester = rankWith(4, or(isAnyOfControl, isOneOfControl));
export const anyOfOneOfControlRenderer = (state: JsonFormsStateContext, props: ControlProps & AdditionalProps) => {
  const jsonFormsContext = { jsonforms: { ...state } };

  const { required, renderers, cells, schema, label, path, errors, visible, enabled, uischema, rootSchema, data } =
    mapStateToControlWithDetailProps(jsonFormsContext, props);

  const keyword = schema.anyOf !== undefined ? "anyOf" : "oneOf";
  const resolvedSchema = resolveSchema(schema, keyword, rootSchema) as JsonSchema[];
  const resolvedProps = mapStateToCombinatorRendererProps(jsonFormsContext, props, keyword);

  const renderInfos = createCombinatorRenderInfos(
    resolvedSchema,
    rootSchema,
    keyword,
    resolvedProps.uischema || uischema,
    path,
    resolvedProps.uischemas
  );

  if (
    data !== undefined &&
    data !== null &&
    (resolvedProps.indexOfFittingSchema === undefined || resolvedProps.indexOfFittingSchema < 0)
  ) {
    // Try and match the data using our own combinator info objects
    const combinatorInfos = getCombinatorInfos(resolvedSchema, rootSchema);

    const constProp = combinatorInfos.length > 0 ? combinatorInfos[0].constProperty : undefined;
    if (constProp && typeof data === "object" && data[constProp]) {
      const dataType = data[constProp];
      resolvedProps.indexOfFittingSchema = combinatorInfos.findIndex(
        (combinatorInfo) => combinatorInfo.constValue === dataType
      );
    }
  }

  if (resolvedProps.indexOfFittingSchema === undefined || resolvedProps.indexOfFittingSchema < 0) {
    const { handleChange } = mapDispatchToControlProps(state.dispatch);

    // TODO: consider un-commenting when the AJV useDefaults option doesn't generate invalid schemas
    // if (data != null) {
    //     // We have data that doesn't match a schema so show invalid template
    //     console.warn("Cannot match " + keyword + " schema to instance data");

    //     const showJson = (ev: Event) => {
    //         ev.stopPropagation();

    //         showJsonEditor(label, data, ((newValue) => {
    //             handleChange(path || "", newValue);
    //         }));
    //     };

    //     return html`
    //         <div class="item-container no-match-container"><span>${label}:</span><b><or-translate value="validation.noSchemaMatchFound"></b><or-vaadin-button @click="${(ev: Event) => showJson(ev)}"><or-icon slot="prefix" icon="pencil"></or-icon>json</or-vaadin-button></div>
    //     `;
    // } else {
    // We have no data so show a schema picker
    return getSchemaPicker(rootSchema, resolvedSchema, props.label || label, (selectedSchema) =>
      handleChange(path, selectedSchema.defaultValueCreator())
    );
    // }
  }

  // Return template for the anyOf/oneOf schema that matches the data
  const matchedSchema = renderInfos[resolvedProps.indexOfFittingSchema].schema;
  let matchedUischema = renderInfos[resolvedProps.indexOfFittingSchema].uischema;

  if (matchedSchema.allOf) {
    // Force the uischema to be a simple control so it goes through the allOf renderer
    matchedUischema = {
      type: "Control",
      scope: "#",
      label: false,
    } as ControlElement;
  }

  const contentProps: OwnPropsOfRenderer & AdditionalProps = {
    schema: matchedSchema,
    uischema: matchedUischema,
    path,
    renderers,
    cells,
    label: props.label || getLabel(matchedSchema, rootSchema, label) || "",
    required: props.required || !!required,
    errors,
    minimal: props.minimal,
    type: matchedSchema.title,
  };

  return getTemplateFromProps(state, contentProps);
};

export const allOfControlTester: RankedTester = rankWith(4, isAllOfControl);
export const allOfControlRenderer = (state: JsonFormsStateContext, props: ControlProps & AdditionalProps) => {
  const jsonFormsContext = { jsonforms: { ...state } };
  const contentProps: StatePropsOfControlWithDetail & AdditionalProps = {
    ...mapStateToControlWithDetailProps(jsonFormsContext, props),
  };

  // Merge the schemas
  const allOfSchema = resolveSchema(contentProps.schema, "allOf", contentProps.rootSchema);
  contentProps.schema = (allOfSchema.allOf! as JsonSchema[]).reduce((accumulator, value) =>
    Util.mergeObjects(accumulator, value, false)
  );
  // Reset the uischema scope
  contentProps.uischema.scope = "#";

  contentProps.label = props.label || contentProps.label;
  contentProps.required = !!props.required || contentProps.required;
  contentProps.minimal = props.minimal;

  return getTemplateFromProps(state, contentProps);
};

export const arrayControlTester: RankedTester = rankWith(
  2,
  schemaMatches(
    (schema) => hasType(schema, "array") && !Array.isArray(schema.items) // we don't care about tuples
  )
);
export const arrayControlRenderer = (state: JsonFormsStateContext, props: ControlProps & AdditionalProps) => {
  const contentProps: ControlProps & AdditionalProps = {
    ...mapStateToControlProps({ jsonforms: { ...state } }, props),
    ...mapDispatchToControlProps(state.dispatch),
  };

  contentProps.label = props.label || contentProps.label;
  contentProps.required = !!props.required || contentProps.required;
  contentProps.minimal = props.minimal;

  const template = html`<or-json-forms-array-control
    style="max-width: 100%"
    .state="${state}"
    .props="${contentProps}"
  ></or-json-forms-array-control>`;
  let deleteHandler: undefined | (() => void);
  if (!contentProps.required && contentProps.path) {
    deleteHandler = () => {
      contentProps.handleChange(contentProps.path, undefined);
    };
  }
  return getTemplateWrapper(template, deleteHandler);
};

export function getTemplateWrapper(elementTemplate: TemplateResult, deleteHandler?: () => void): TemplateResult {
  const deleteTemplate = !deleteHandler
    ? ``
    : html`
                <button class="button-clear" aria-label="${i18next.t("delete")}" @click="${deleteHandler}"><or-icon icon="close-circle"></or-icon></input>
            `;
  return html`
    <div class="item-container">
      ${elementTemplate}
      <div class="delete-container">${deleteTemplate}</div>
    </div>
  `;
}

export function unknownTemplate() {
  return html`<span>No applicable renderer found!</span>`;
}

export const StandardRenderers: JsonFormsRendererRegistryEntry[] = [
  { tester: verticalOrGroupLayoutTester, renderer: verticalLayoutRenderer },
  { tester: constTester, renderer: constRenderer },
  { tester: inputControlTester, renderer: inputControlRenderer },
  { tester: objectControlTester, renderer: objectControlRenderer },
  { tester: arrayControlTester, renderer: arrayControlRenderer },
  { tester: anyOfOneOfControlTester, renderer: anyOfOneOfControlRenderer },
  { tester: allOfControlTester, renderer: allOfControlRenderer },
];
