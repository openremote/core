/*
 * Copyright 2025, OpenRemote Inc.
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
import { css, html, type TemplateResult, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query, state } from "lit/decorators.js";
import manager, {
  DefaultBoxShadow,
  DefaultColor1,
  DefaultColor2,
  DefaultColor3,
  DefaultColor4,
  DefaultColor5,
  DefaultColor6,
  Util,
} from "@openremote/core";
import { i18next, translate } from "@openremote/or-translate";
import "@openremote/or-icon";
import {
  AssetModelUtil,
  type AssetDescriptor,
  type AssetQuery,
  type AssetTypeInfo,
  type AttributeDescriptor,
  ClientRole,
  type JsonRule,
  type LogicGroup,
  type ManagerAppRealmNotificationConfig,
  type NotificationTargetType,
  type RuleActionUnion,
  type RuleCondition,
  RulesetLang,
  type RulesetUnion,
  WellknownAssets,
  type Asset,
  AssetQueryOrderBy$Property,
  AssetQueryMatch,
} from "@openremote/model";
import "@openremote/or-translate";
import "@openremote/or-mwc-components/or-mwc-drawer";
import "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import "./or-rule-viewer";
import "./or-rule-group-viewer";
import "./or-rule-tree";
import "./flow-viewer/flow-viewer";
import type { OrRuleViewer } from "./or-rule-viewer";
import type { RecurrenceOption } from "./json-viewer/or-rule-then-otherwise";
import type { ValueInputProviderGenerator } from "@openremote/or-mwc-components/or-mwc-input";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import type { OrRuleTree, RuleTreeNode } from "./or-rule-tree";
import { OrTreeDragEvent } from "@openremote/or-tree-menu";
import { when } from "lit/directives/when.js";

export { buttonStyle } from "./style";

export const enum ConditionType {
  AGENT_QUERY = "agentQuery",
  ASSET_QUERY = "assetQuery",
  TIME = "time",
}

export const enum ActionType {
  WAIT = "wait",
  ALARM = "alarm",
  EMAIL = "email",
  EMAIL_LOCALIZED = "email_localized",
  PUSH_NOTIFICATION = "push",
  PUSH_LOCALIZED = "push_localized",
  ATTRIBUTE = "attribute",
  WEBHOOK = "webhook",
}
export enum TimeTriggerType {
  TIME_OF_DAY = "TIME_OF_DAY",
}
export enum AssetQueryOperator {
  VALUE_EMPTY = "empty",
  VALUE_NOT_EMPTY = "notEmpty",
  EQUALS = "equals",
  NOT_EQUALS = "notEquals",
  GREATER_THAN = "greaterThan",
  GREATER_EQUALS = "greaterEquals",
  LESS_THAN = "lessThan",
  LESS_EQUALS = "lessEquals",
  BETWEEN = "between",
  NOT_BETWEEN = "notBetween",
  CONTAINS = "contains",
  NOT_CONTAINS = "notContains",
  STARTS_WITH = "startsWith",
  NOT_STARTS_WITH = "notStartsWith",
  ENDS_WITH = "endsWith",
  NOT_ENDS_WITH = "notEndsWith",
  CONTAINS_KEY = "containsKey",
  NOT_CONTAINS_KEY = "notContainsKey",
  INDEX_CONTAINS = "indexContains",
  NOT_INDEX_CONTAINS = "notIndexContains",
  LENGTH_EQUALS = "lengthEquals",
  NOT_LENGTH_EQUALS = "notLengthEquals",
  LENGTH_GREATER_THAN = "lengthGreaterThan",
  LENGTH_LESS_THAN = "lengthLessThan",
  IS_TRUE = "true",
  IS_FALSE = "false",
  WITHIN_RADIUS = "withinRadius",
  OUTSIDE_RADIUS = "outsideRadius",
  WITHIN_RECTANGLE = "withinRectangle",
  OUTSIDE_RECTANGLE = "outsideRectangle",
  NOT_UPDATED_FOR = "notUpdatedFor",
  INSIDE_AREA = "insideArea",
  OUTSIDE_AREA = "outsideArea",
}

export interface AllowedActionTargetTypes {
  default?: NotificationTargetType[];
  actions?: { [actionType in ActionType]?: NotificationTargetType[] };
}

export interface RulesConfig {
  controls?: {
    allowedLanguages?: RulesetLang[];
    allowedConditionTypes?: ConditionType[];
    allowedActionTypes?: ActionType[];
    allowedAssetQueryOperators?: { [name: string]: AssetQueryOperator[] }; // name can be value descriptor name or value descriptor jsonType
    allowedRecurrenceOptions?: RecurrenceOption[];
    allowedActionTargetTypes?: AllowedActionTargetTypes;
    hideActionTypeOptions?: boolean;
    hideActionTargetOptions?: boolean;
    hideActionUpdateOptions?: boolean;
    hideConditionTypeOptions?: boolean;
    hideThenAddAction?: boolean;
    hideWhenAddCondition?: boolean;
    hideWhenAddAttribute?: boolean;
    hideWhenAddGroup?: boolean;
    multiSelect?: boolean;
  };
  inputProvider?: ValueInputProviderGenerator;
  descriptors?: {
    all?: RulesDescriptorSection;
    when?: RulesDescriptorSection;
    action?: RulesDescriptorSection;
  };
  rulesetTemplates?: { [key in RulesetLang]?: string };
  rulesetAddHandler?: (ruleset: RulesetUnion) => boolean;
  rulesetDeleteHandler?: (ruleset: RulesetUnion) => boolean;
  rulesetCopyHandler?: (ruleset: RulesetUnion) => boolean;
  rulesetSaveHandler?: (ruleset: RulesetUnion) => boolean;
  json?: {
    rule?: JsonRule;
    whenGroup?: LogicGroup<RuleCondition>;
    whenCondition?: RuleCondition;
    whenAssetQuery?: AssetQuery;
    then?: RuleActionUnion;
    otherwise?: RuleActionUnion;
  };
  notifications?: { [realm: string]: ManagerAppRealmNotificationConfig };
}

export interface RulesDescriptorSection {
  includeAssets?: string[];
  excludeAssets?: string[];
  attributeDescriptors?: { [attributeName: string]: RulesConfigAttribute };
  /**
   * Asset type specific config; '*' key will be used as a default fallback if no asset type specific entry exists
   */
  assets?: { [assetType: string]: RulesConfigAsset };
}

export interface RulesConfigAsset {
  includeAttributes?: string[];
  excludeAttributes?: string[];
  name?: string;
  icon?: string;
  color?: string;
  attributeDescriptors?: { [attributeName: string]: RulesConfigAttribute };
}

export interface RulesConfigAttribute extends AttributeDescriptor {}

export interface RulesetBaseNode {
  type: "rule" | "group";
}

export interface RulesetNode {
  type: "rule";
  ruleset: RulesetUnion;
  selected: boolean;
}

export interface RulesetGroupNode extends RulesetBaseNode {
  type: "group";
  groupId: string;
}

export interface RequestEventDetail<T> {
  allow: boolean;
  detail: T;
}

export interface RuleView {
  validate: () => boolean;
  beforeSave: () => void;
  ruleset?: RulesetUnion;
  readonly?: boolean;
  config?: RulesConfig;
}

export interface RuleViewInfo {
  viewTemplateProvider: (ruleset: RulesetUnion, config: RulesConfig | undefined, readonly: boolean) => TemplateResult;
  viewRulesetTemplate?: string;
}

export const RuleViewInfoMap: { [key in RulesetLang]: RuleViewInfo } = {
  JSON: {
    viewTemplateProvider: (ruleset, config, readonly) =>
      html`<or-rule-json-viewer
        id="rule-view"
        .ruleset="${ruleset}"
        .config="${config}"
        .readonly="${readonly}"
      ></or-rule-json-viewer>`,
  },
  FLOW: {
    viewTemplateProvider: (ruleset, config, readonly) =>
      html`<flow-editor id="rule-view" .ruleset="${ruleset}" .readonly="${readonly}"></flow-editor>`,
  },
  GROOVY: {
    viewTemplateProvider: (ruleset, config, readonly) => html`
      <or-rule-text-viewer
        id="rule-view"
        .ruleset="${ruleset}"
        .config="${config}"
        .readonly="${readonly}"
      ></or-rule-text-viewer>
    `,
    viewRulesetTemplate:
      "package demo.rules\n" +
      "\n" +
      "import org.openremote.manager.rules.RulesBuilder\n" +
      "import org.openremote.model.notification.*\n" +
      "import org.openremote.model.attribute.AttributeInfo\n" +
      "import org.openremote.model.asset.Asset\n" +
      "import org.openremote.model.asset.impl.*\n" +
      "import org.openremote.model.query.*\n" +
      "import org.openremote.model.query.filter.*\n" +
      "import org.openremote.model.rules.Assets\n" +
      "import org.openremote.model.rules.Notifications\n" +
      "import org.openremote.model.rules.Users\n" +
      "\n" +
      "import java.util.logging.Logger\n" +
      "import java.util.stream.Collectors\n" +
      "\n" +
      "Logger LOG = binding.LOG\n" +
      "RulesBuilder rules = binding.rules\n" +
      "Notifications notifications = binding.notifications\n" +
      "Users users = binding.users\n" +
      "Assets assets = binding.assets\n" +
      "\n" +
      "/*\n" +
      "* A groovy rule is made up of a when closure (LHS) which must return a boolean indicating whether the then closure (RHS)\n" +
      "* should be executed. The rule engine will periodically evaluate the when closure and if it evaluates to true then the\n" +
      "* rule then closure will execute.\n" +
      "*\n" +
      "* NOTE: DO NOT MODIFY THE FACTS IN THE WHEN CLOSURE THIS SHOULD BE DONE IN THE THEN CLOSURE\n" +
      "*\n" +
      "* To avoid an infinite rule loop the when closure should not continually return true for subsequent executions\n" +
      "* so either the then closure should perform an action that prevents the when closure from matching on subsequent\n" +
      "* evaluations, or custom facts should be used, some ideas:\n" +
      "*\n" +
      "* - Change the value of an attribute being matched in the when closure (which will prevent it matching on subsequent evaluations)\n" +
      "* - Insert a custom fact on first match and test this fact in the when closure to determine when the rule should match again (for\n" +
      "*   example if a rule should match whenever the asset state changes the asset state timestamp can be used)\n" +
      "*/\n" +
      "\n" +
      "rules.add()\n" +
      '        .name("Example rule")\n' +
      "        .when({\n" +
      "    facts ->\n" +
      "\n" +
      "        // Find first matching asset state using an asset query\n" +
      "\n" +
      "        facts.matchFirstAssetState(\n" +
      "\n" +
      "                // Find asset state by asset type and attribute name\n" +
      '                new AssetQuery().types(ThingAsset).attributeNames("someAttribute")\n' +
      "\n" +
      "                // Find asset state by asset ID and attribute name\n" +
      '                //new AssetQuery().ids("7CaBoyiDhtdf2kn1Xso1w5").attributeNames("someAttribute")\n' +
      "\n" +
      "                // Find asset state by asset type, attribute name and value string predicate\n" +
      "                //new AssetQuery().types(ThingAsset).attributes(\n" +
      "                //        new AttributePredicate()\n" +
      '                //                .name("someAttribute")\n' +
      "                //                .value(new StringPredicate()\n" +
      '                //                            .value("someValue")\n' +
      "                //                            .match(AssetQuery.Match.EXACT)\n" +
      "                //                            .caseSensitive(true)))\n" +
      "\n" +
      "                // Find asset state by asset type and location attribute predicate\n" +
      "                //new AssetQuery().types(ThingAsset).attributes(\n" +
      "                //        new AttributePredicate()\n" +
      "                //                .name(Asset.LOCATION)\n" +
      "                //                .value(new RadialGeofencePredicate()\n" +
      "                //                            .radius(100)\n" +
      "                //                            .lat(50.0)\n" +
      "                //                            .lng(0.0)))\n" +
      "\n" +
      "        ).map { assetState ->\n" +
      "\n" +
      "            // Use logging to help with debugging if needed" +
      '            //LOG.info("ATTRIBUTE FOUND")\n' +
      "\n" +
      "            // Check if this rule really should fire this time\n" +
      '            Optional<Long> lastFireTimestamp = facts.getOptional("someAttribute")\n' +
      "            if (lastFireTimestamp.isPresent() && assetState.getTimestamp() <= lastFireTimestamp.get()) {\n" +
      "                return false\n" +
      "            }\n" +
      "\n" +
      "            // OK to fire if we reach here\n" +
      "\n" +
      "            // Compute and bind any facts required for the then closure\n" +
      '            facts.bind("assetState", assetState)\n' +
      "            true\n" +
      "        }.orElseGet {\n" +
      "            // Asset state didn't match so clear out any custom facts to allow the rule to fire next time the when closure matches\n" +
      '            facts.remove("someAttribute")\n' +
      "            false\n" +
      "        }\n" +
      "\n" +
      "})\n" +
      "        .then({\n" +
      "    facts ->\n" +
      "\n" +
      "        // Extract any binded facts\n" +
      '        AttributeInfo assetState = facts.bound("assetState")\n' +
      "\n" +
      "        // Insert the custom fact to prevent rule loop\n" +
      '        facts.put("someAttribute", assetState.getTimestamp())\n' +
      "\n" +
      "        // Write to attributes\n" +
      "        def otherAttributeValue = null\n" +
      '        if (assetState.getValue().orElse{null} == "Value 1") {\n' +
      '            otherAttributeValue = "Converted Value 1"\n' +
      '        } else if (assetState.getValue().orElse{null} == "Value 2") {\n' +
      '            otherAttributeValue = "Converted Value 2"\n' +
      "        } else {\n" +
      '            otherAttributeValue = "Unknown"\n' +
      "        }\n" +
      '        assets.dispatch(assetState.id, "otherAttribute", otherAttributeValue)\n' +
      "\n" +
      "        // Generate notifications (useful for rules that check if an attribute is out of range)\n" +
      "        //notifications.send(new Notification()\n" +
      '        //        .setName("Attribute alert")\n' +
      "        //        .setMessage(new EmailNotificationMessage()\n" +
      '        //                .setTo("no-reply@openremote.io")\n' +
      '        //                .setSubject("Attribute out of range: Attribute=${assetState.name} Asset ID=${assetState.id}")\n' +
      '        //                .setText("Some text body")\n' +
      '        //                .setHtml("<p>Or some HTML body</p>")\n' +
      "        //        )\n" +
      "        //)\n" +
      "})",
  },
  JAVASCRIPT: {
    viewTemplateProvider: (ruleset, config, readonly) => html`
      <or-rule-text-viewer
        id="rule-view"
        .ruleset="${ruleset}"
        .config="${config}"
        .readonly="${readonly}"
      ></or-rule-text-viewer>
    `,
    viewRulesetTemplate:
      "rules = [ // An array of rules, add more objects to add more rules\n" +
      "    {\n" +
      '        name: "Set bar to foo on someAttribute",\n' +
      "        description: \"An example rule that sets 'bar' on someAttribute when it is 'foo'\",\n" +
      "        when: function(facts) {\n" +
      "            return facts.matchAssetState(\n" +
      '                new AssetQuery().types(AssetType.THING).attributeValue("someAttribute", "foo")\n' +
      "            ).map(function(thing) {\n" +
      '                facts.bind("assetId", thing.id);\n' +
      "                return true;\n" +
      "            }).orElse(false);\n" +
      "        },\n" +
      "        then: function(facts) {\n" +
      "            facts.updateAssetState(\n" +
      '                facts.bound("assetId"), "someAttribute", "bar"\n' +
      "            );\n" +
      "        }\n" +
      "    }\n" +
      "]",
  },
};

function getAssetDescriptorFromSection(
  assetType: string,
  config: RulesConfig | undefined,
  useActionConfig: boolean
): RulesConfigAsset | undefined {
  if (!config || !config.descriptors) {
    return;
  }

  const section = useActionConfig ? config.descriptors.action : config.descriptors.when;
  const allSection = config.descriptors.all;

  const descriptor =
    section && section.assets
      ? section.assets[assetType]
        ? section.assets[assetType]
        : section.assets["*"]
      : undefined;
  if (descriptor) {
    return descriptor;
  }

  return allSection && allSection.assets
    ? allSection.assets[assetType]
      ? allSection.assets[assetType]
      : allSection.assets["*"]
    : undefined;
}

export function getAssetTypeFromQuery(query?: AssetQuery): string | undefined {
  return query && query.types && query.types.length > 0 && query.types[0] ? query.types[0] : undefined;
}

export function getAssetIdsFromQuery(query?: AssetQuery) {
  return query && query.ids ? [...query.ids] : undefined;
}

export const getAssetTypes: () => Promise<string[]> = async () => {
  // RT: Change to just get all asset types for now as if an instance of a particular asset doesn't exist you
  // won't be able to create a rule for it (e.g. if no ConsoleAsset in a realm then cannot create a console rule)
  return AssetModelUtil.getAssetTypeInfos().map((ati) => ati.assetDescriptor!.name!);
  // const response = await manager.rest.api.AssetResource.queryAssets({
  //     select: {
  //         attributes: []
  //     },
  //     realm: {
  //         realm: manager.displayRealm
  //     },
  //     recursive: true
  // });
  //
  // if (response && response.data) {
  //     return response.data.map(asset => asset.type!);
  // }
};

export function getAssetInfos(config: RulesConfig | undefined, useActionConfig: boolean): Promise<AssetTypeInfo[]> {
  const assetDescriptors: AssetDescriptor[] = AssetModelUtil.getAssetDescriptors();

  return getAssetTypes().then((availibleAssetTypes) => {
    let allowedAssetTypes: string[] = availibleAssetTypes || [];
    let excludedAssetTypes: string[] = [];
    if (!config || !config.descriptors) {
      return assetDescriptors.map((ad) => AssetModelUtil.getAssetTypeInfo(ad)!);
    }

    const section = useActionConfig ? config.descriptors.action : config.descriptors.when;

    if ((section && section.includeAssets) || (config.descriptors.all && config.descriptors.all.includeAssets)) {
      allowedAssetTypes = [];

      if (section && section.includeAssets) {
        allowedAssetTypes = [...section.includeAssets];
      }

      if (config.descriptors.all && config.descriptors.all.includeAssets) {
        allowedAssetTypes = [...config.descriptors.all.includeAssets];
      }
    }

    if (section && section.excludeAssets) {
      excludedAssetTypes = [...section.excludeAssets];
    }
    if (config.descriptors.all && config.descriptors.all.excludeAssets) {
      excludedAssetTypes = excludedAssetTypes.concat(config.descriptors.all.excludeAssets);
    }

    return assetDescriptors
      .filter((ad) => {
        if (allowedAssetTypes.length > 0 && allowedAssetTypes.indexOf(ad.name!) < 0) {
          return false;
        }
        return excludedAssetTypes.indexOf(ad.name!) < 0;
      })
      .map((ad) => {
        const typeInfo = AssetModelUtil.getAssetTypeInfo(ad)!;

        // Amalgamate matching descriptor from config if defined
        const configDescriptor = getAssetDescriptorFromSection(ad.name!, config, useActionConfig);
        if (!configDescriptor) {
          return typeInfo;
        }

        const modifiedTypeInfo: AssetTypeInfo = {
          assetDescriptor: typeInfo.assetDescriptor ? { ...typeInfo.assetDescriptor } : { descriptorType: "asset" },
          attributeDescriptors: typeInfo.attributeDescriptors ? [...typeInfo.attributeDescriptors] : [],
          metaItemDescriptors: typeInfo.metaItemDescriptors ? [...typeInfo.metaItemDescriptors] : [],
          valueDescriptors: typeInfo.valueDescriptors ? [...typeInfo.valueDescriptors] : [],
        };

        if (configDescriptor.icon) {
          modifiedTypeInfo.assetDescriptor!.icon = configDescriptor.icon;
        }
        if (configDescriptor.color) {
          modifiedTypeInfo.assetDescriptor!.colour = configDescriptor.color;
        }

        // Remove any excluded attributes
        if (modifiedTypeInfo.attributeDescriptors) {
          const includedAttributes =
            configDescriptor.includeAttributes !== undefined ? configDescriptor.includeAttributes : undefined;
          const excludedAttributes =
            configDescriptor.excludeAttributes !== undefined ? configDescriptor.excludeAttributes : undefined;

          if (includedAttributes || excludedAttributes) {
            modifiedTypeInfo.attributeDescriptors = modifiedTypeInfo.attributeDescriptors.filter(
              (mad) =>
                (!includedAttributes || includedAttributes.some((inc) => Util.stringMatch(inc, mad.name!))) &&
                (!excludedAttributes || !excludedAttributes.some((exc) => Util.stringMatch(exc, mad.name!)))
            );
          }

          // Override any attribute descriptors
          if (configDescriptor.attributeDescriptors) {
            modifiedTypeInfo.attributeDescriptors.map((attributeDescriptor) => {
              let configAttributeDescriptor: RulesConfigAttribute | undefined =
                configDescriptor.attributeDescriptors![attributeDescriptor.name!];
              if (!configAttributeDescriptor) {
                configAttributeDescriptor =
                  section && section.attributeDescriptors
                    ? section.attributeDescriptors[attributeDescriptor.name!]
                    : undefined;
              }
              if (!configAttributeDescriptor) {
                configAttributeDescriptor =
                  config.descriptors!.all && config.descriptors!.all.attributeDescriptors
                    ? config.descriptors!.all.attributeDescriptors[attributeDescriptor.name!]
                    : undefined;
              }
              if (configAttributeDescriptor) {
                if (configAttributeDescriptor.type) {
                  attributeDescriptor.type = configAttributeDescriptor.type;
                }
                if (configAttributeDescriptor.format) {
                  attributeDescriptor.format = configAttributeDescriptor.format;
                }
                if (configAttributeDescriptor.units) {
                  attributeDescriptor.units = configAttributeDescriptor.units;
                }
                if (configAttributeDescriptor.constraints) {
                  attributeDescriptor.constraints = attributeDescriptor.constraints
                    ? [...configAttributeDescriptor.constraints, ...attributeDescriptor.constraints]
                    : configAttributeDescriptor.constraints;
                }
              }
            });
          }
        }
        return modifiedTypeInfo;
      });
  });
}

// Function for getting assets by type
// loadedAssets is an object given as parameter that will be updated if new assets are fetched.
export async function getAssetsByType(
  type: string,
  customQuery?: AssetQuery,
  loadedAssets?: Map<string, Asset[]>
): Promise<{ assets?: Asset[]; loadedAssets?: Map<string, Asset[]> }> {
  if (loadedAssets?.has(type)) {
    return {
      assets: loadedAssets?.get(type),
      loadedAssets,
    };
  } else {
    loadedAssets ??= new Map<string, any[]>();
    const assetQuery: AssetQuery = {
      realm: {
        name: manager.displayRealm,
      },
      types: [type],
      orderBy: {
        property: AssetQueryOrderBy$Property.NAME,
      },
    };
    const response = await manager.rest.api.AssetResource.queryAssets({ ...assetQuery, ...customQuery });
    loadedAssets.set(type, response.data);
    return {
      assets: response.data,
      loadedAssets,
    };
  }
}

export class OrRulesRuleChangedEvent extends CustomEvent<boolean> {
  public static readonly NAME = "or-rules-rule-changed";

  constructor(valid: boolean) {
    super(OrRulesRuleChangedEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: valid,
    });
  }
}

export class OrRulesRuleUnsupportedEvent extends CustomEvent<void> {
  public static readonly NAME = "or-rules-rule-unsupported";

  constructor() {
    super(OrRulesRuleUnsupportedEvent.NAME, {
      bubbles: true,
      composed: true,
    });
  }
}

export interface NodeSelectEventDetail {
  oldNodes: RulesetBaseNode[];
  newNodes: RulesetBaseNode[];
}

export class OrRulesRequestSelectionEvent extends CustomEvent<NodeSelectEventDetail> {
  public static readonly NAME = "or-rules-request-selection";

  constructor(request: NodeSelectEventDetail) {
    super(OrRulesRequestSelectionEvent.NAME, {
      bubbles: true,
      composed: true,
      cancelable: true,
      detail: request,
    });
  }
}

export class OrRulesSelectionEvent extends CustomEvent<NodeSelectEventDetail> {
  public static readonly NAME = "or-rules-selection";

  constructor(nodes: NodeSelectEventDetail) {
    super(OrRulesSelectionEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: nodes,
    });
  }
}

export type AddEventDetail = {
  ruleset: RulesetUnion;
  sourceRuleset?: RulesetUnion;
};

export class OrRulesRequestAddEvent extends CustomEvent<RequestEventDetail<AddEventDetail>> {
  public static readonly NAME = "or-rules-request-add";

  constructor(detail: AddEventDetail) {
    super(OrRulesRequestAddEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: {
        detail,
        allow: true,
      },
    });
  }
}

export class OrRulesRequestGroupEvent extends CustomEvent<{ value: string }> {
  public static readonly NAME = "or-rules-request-group";

  constructor(name: string) {
    super(OrRulesRequestGroupEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: {
        value: name,
      },
    });
  }
}

export class OrRulesAddEvent extends CustomEvent<AddEventDetail> {
  public static readonly NAME = "or-rules-add";

  constructor(detail: AddEventDetail) {
    super(OrRulesAddEvent.NAME, {
      bubbles: true,
      composed: true,
      detail,
    });
  }
}

export class OrRulesRequestDeleteEvent extends CustomEvent<RequestEventDetail<RulesetNode[]>> {
  public static readonly NAME = "or-rules-request-delete";

  constructor(request: RulesetNode[]) {
    super(OrRulesRequestDeleteEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: {
        detail: request,
        allow: true,
      },
    });
  }
}

export type SaveResult = {
  success: boolean;
  ruleset: RulesetUnion;
  isNew: boolean;
};

export class OrRulesRequestSaveEvent extends CustomEvent<RequestEventDetail<RulesetUnion>> {
  public static readonly NAME = "or-rules-request-save";

  constructor(ruleset: RulesetUnion) {
    super(OrRulesRequestSaveEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: {
        allow: true,
        detail: ruleset,
      },
    });
  }
}

export class OrRulesSaveEvent extends CustomEvent<SaveResult> {
  public static readonly NAME = "or-rules-save";

  constructor(result: SaveResult) {
    super(OrRulesSaveEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: result,
    });
  }
}

export class OrRulesDeleteEvent extends CustomEvent<RulesetUnion[]> {
  public static readonly NAME = "or-rules-delete";

  constructor(rulesets: RulesetUnion[]) {
    super(OrRulesDeleteEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: rulesets,
    });
  }
}

export type OrRulesGroupNameChangeReason = "exists" | "legacyJavascript";

export class OrRulesGroupNameChangeEvent extends CustomEvent<{ value: string; reason?: OrRulesGroupNameChangeReason }> {
  public static readonly NAME = "or-rules-group-name-change";

  constructor(name: string) {
    super(OrRulesGroupNameChangeEvent.NAME, {
      bubbles: true,
      composed: true,
      cancelable: true,
      detail: {
        value: name,
        reason: undefined,
      },
    });
  }
}

declare global {
  export interface HTMLElementEventMap {
    [OrRulesRuleUnsupportedEvent.NAME]: OrRulesRuleUnsupportedEvent;
    [OrRulesRuleChangedEvent.NAME]: OrRulesRuleChangedEvent;
    [OrRulesRequestSelectionEvent.NAME]: OrRulesRequestSelectionEvent;
    [OrRulesSelectionEvent.NAME]: OrRulesSelectionEvent;
    [OrRulesRequestAddEvent.NAME]: OrRulesRequestAddEvent;
    [OrRulesAddEvent.NAME]: OrRulesAddEvent;
    [OrRulesRequestDeleteEvent.NAME]: OrRulesRequestDeleteEvent;
    [OrRulesRequestSaveEvent.NAME]: OrRulesRequestSaveEvent;
    [OrRulesSaveEvent.NAME]: OrRulesSaveEvent;
    [OrRulesDeleteEvent.NAME]: OrRulesDeleteEvent;
    [OrTreeDragEvent.NAME]: OrTreeDragEvent;
    [OrRulesRequestGroupEvent.NAME]: OrRulesRequestGroupEvent;
    [OrRulesGroupNameChangeEvent.NAME]: OrRulesGroupNameChangeEvent;
  }
}

// language=CSS
export const style = css`
  :host {
    display: flex;
    height: 100%;
    width: 100%;

    --internal-or-rules-background-color: var(
      --or-rules-background-color,
      var(--or-app-color2, ${unsafeCSS(DefaultColor2)})
    );
    --internal-or-rules-text-color: var(--or-rules-text-color, inherit);
    --internal-or-rules-button-color: var(--or-rules-button-color, var(--or-app-color4, ${unsafeCSS(DefaultColor4)}));
    --internal-or-rules-invalid-color: var(--or-rules-invalid-color, var(--or-app-color6, ${unsafeCSS(DefaultColor6)}));
    --internal-or-rules-panel-color: var(--or-rules-panel-color, var(--or-app-color1, ${unsafeCSS(DefaultColor1)}));
    --internal-or-rules-line-color: var(--or-rules-line-color, var(--or-app-color5, ${unsafeCSS(DefaultColor5)}));

    --internal-or-rules-list-selected-color: var(
      --or-rules-list-selected-color,
      var(--or-app-color2, ${unsafeCSS(DefaultColor2)})
    );
    --internal-or-rules-list-text-color: var(
      --or-rules-list-text-color,
      var(--or-app-color3, ${unsafeCSS(DefaultColor3)})
    );
    --internal-or-rules-list-text-size: var(--or-rules-list-text-size, 15px);
    --internal-or-rules-list-header-height: var(--or-rules-list-header-height, 48px);

    --internal-or-rules-list-icon-color-error: var(
      --or-rules-list-icon-color-error,
      var(--or-app-color6, ${unsafeCSS(DefaultColor6)})
    );
    --internal-or-rules-list-icon-color-ok: var(
      --or-rules-list-icon-color-ok,
      var(--or-app-color5, ${unsafeCSS(DefaultColor5)})
    );

    --internal-or-rules-list-button-size: var(--or-rules-list-button-size, 24px);

    --internal-or-rules-header-background-color: var(
      --or-rules-header-background-color,
      var(--or-app-color3, ${unsafeCSS(DefaultColor3)})
    );
    --internal-or-rules-header-height: var(--or-rules-header-height, unset);

    --or-panel-background-color: var(--internal-or-rules-panel-color);
  }

  or-rule-tree {
    min-width: 300px;
    width: 300px;
    z-index: 1;
    display: flex;
    flex-direction: column;
    background-color: var(--internal-or-rules-panel-color);
    color: var(--internal-or-rules-list-text-color);
    box-shadow: ${unsafeCSS(DefaultBoxShadow)};
  }

  or-rule-viewer,
  or-rule-group-viewer {
    z-index: 0;
  }
`;

@customElement("or-rules")
export class OrRules extends translate(i18next)(OrElement) {
  public static DEFAULT_RULESET_NAME = "";

  static get styles() {
    return [style];
  }

  @property({ type: Boolean })
  public readonly?: boolean;

  @property({ type: Object })
  public config?: RulesConfig;

  @property({ type: String })
  public realm?: string;

  @property({ type: String })
  public language?: RulesetLang;

  @property({ attribute: false })
  private _isValidRule?: boolean;

  /**
   * Represents the selected group name. (groupId in rules meta)
   * Will be undefined once something else is selected
   */
  @state()
  protected _selectedGroup?: string;

  @query("#rule-tree")
  private _rulesTree?: OrRuleTree;

  @query("#rule-viewer")
  private _viewer?: OrRuleViewer;

  constructor() {
    super();

    this.addEventListener(OrRulesRequestSelectionEvent.NAME, this._onRuleSelectionRequested);
    this.addEventListener(OrRulesSelectionEvent.NAME, this._onRuleSelectionChanged);
    this.addEventListener(OrRulesAddEvent.NAME, this._onRuleAdd);
    this.addEventListener(OrRulesSaveEvent.NAME, this._onRuleSave);
    this.addEventListener(OrTreeDragEvent.NAME, this._onRuleDrag);
    this.addEventListener(OrRulesRequestGroupEvent.NAME, this._onGroupAddRequest);
    this.addEventListener(OrRulesGroupNameChangeEvent.NAME, this._onGroupNameChange);
  }

  protected render() {
    return html`
      <or-rule-tree id="rule-tree" ?readonly=${this._isReadonly()} .config=${this.config}></or-rule-tree>
      ${when(
        this._selectedGroup,
        () => html`
          <or-rule-group-viewer
            .group="${this._selectedGroup}"
            .readonly="${this._isReadonly()}"
          ></or-rule-group-viewer>
        `,
        () => html`
          <or-rule-viewer id="rule-viewer" .config="${this.config}" .readonly="${this._isReadonly()}"></or-rule-viewer>
        `
      )}
    `;
  }

  public refresh() {
    console.debug("Refreshing the rules content...");
    if (this._viewer) {
      this._viewer.ruleset = undefined;
    }
    this._rulesTree?.refresh();
  }

  protected _isReadonly(): boolean {
    return this.readonly || !manager.hasRole(ClientRole.WRITE_RULES);
  }

  protected _isLegacyJavascriptRuleset(ruleset?: RulesetUnion) {
    return ruleset?.lang === RulesetLang.JAVASCRIPT;
  }

  protected _containsLegacyJavascriptRuleset(rulesets?: (RulesetUnion | undefined)[]) {
    return !!rulesets?.some((ruleset) => this._isLegacyJavascriptRuleset(ruleset));
  }

  protected _confirmContinue(action: (ok: boolean) => void) {
    if (this._viewer?.modified) {
      showConfirmDialog(
        this.shadowRoot!,
        html`
          <or-vaadin-confirm-dialog @cancel=${() => action(false)} @confirm=${() => action(true)}>
            ${getConfirmDialogContent("error", "loseChanges", "confirmContinueRulesetModified", "discard", "cancel")}
          </or-vaadin-confirm-dialog>
        `
      );
    } else {
      action(true);
    }
  }

  /**
   * HTML event callback on when a "rule (or group) selection is requested".
   * It is a cancelable event that can prompt a user "to discard changes" if they have been made.
   * @param event - The cancelable selection event
   */
  protected _onRuleSelectionRequested(event: OrRulesRequestSelectionEvent) {
    const isModified = this._viewer?.modified;

    if (!isModified) {
      return;
    }

    // Prevent the initial request
    event.preventDefault();
    console.debug("Prevented new rule selection; prompting user changes have been made.");

    // Prompt user to "discard changes", and continue the selection afterwards
    this._confirmContinue((ok) => {
      if (ok) {
        this._onNodeSelectionChanged(event.detail);
        event.detail.newNodes
          .filter((n) => n.type === "rule")
          .forEach((n) => this._rulesTree?.selectRuleset((n as RulesetNode).ruleset, true));
      } else {
        event.detail.oldNodes
          .filter((n) => n.type === "rule")
          .forEach((n) => this._rulesTree?.selectRuleset((n as RulesetNode).ruleset, true));
      }
    });
  }

  /**
   * HTML event callback on when a new rule (or group) has been selected.
   * @param event - The selection event
   */
  protected _onRuleSelectionChanged(event: OrRulesSelectionEvent) {
    this._onNodeSelectionChanged(event.detail);
  }

  /**
   * Utility event callback function called by {@link _onRuleSelectionRequested} and {@link _onRuleSelectionChanged},
   * when a new node in the {@link OrRuleTree} element is selected. It processes the event, and updates the {@link OrRuleViewer} with the new rule.
   * Alternatively, if a group is selected, it updates {@link _selectedGroup} instead.
   * @param payload - Event payload of the {@link RulesetBaseNode} selection
   */
  protected _onNodeSelectionChanged(payload: NodeSelectEventDetail) {
    const selectedNodes = payload.newNodes;
    const groupNodes = selectedNodes.filter((n) => n.type === "group") as RulesetGroupNode[]; // list of selected group nodes

    // If any group has been selected
    if (groupNodes.length === 1) {
      if (this._viewer) this._viewer.ruleset = undefined; // clear viewer, since a group is selected instead
      this._selectedGroup = groupNodes[0].groupId;
    }

    // If the user has clicked the same node so let's force reload it
    else if (Util.objectsEqual(selectedNodes, payload.oldNodes)) {
      console.debug("Force reloading rule viewer...");
      const node = (selectedNodes[0] as any)?.ruleset as RulesetNode | undefined;
      this._viewer!.ruleset = node ? { ...node.ruleset } : undefined;

      // Otherwise, select the new rule or group.
    } else {
      const groupNodes = selectedNodes.filter((n) => n.type === "group") as RulesetGroupNode[]; // list of selected group nodes
      const rulesetNodes = selectedNodes.filter((n) => n.type === "rule") as RulesetNode[]; // list of selected rule nodes
      const selectedIds = rulesetNodes.map((node) => node.ruleset.id!);
      console.debug(`Selecting rule IDs ${selectedIds}`);

      // Deselect the group, and select either a new rule or group after that.
      this._deselectGroup().then(() => {
        if (rulesetNodes.length === 1) {
          this.getUpdateComplete().then(() => {
            console.debug("Ruleset viewer is now showing", rulesetNodes[0].ruleset.name);
            this._viewer!.ruleset = { ...rulesetNodes[0].ruleset };
          });
        } else {
          this._viewer!.ruleset = undefined; // clear viewer, since a group is selected instead
          if (groupNodes.length === 1) {
            this._selectedGroup = groupNodes[0].groupId;
          }
        }
      });
    }
  }

  /**
   * HTML callback event for when a new rule is added.
   * @param event - A {@link CustomEvent} with the new ruleset as payload.
   */
  protected _onRuleAdd(event: OrRulesAddEvent) {
    // Load the ruleset into the viewer
    this._deselectGroup().then(() => {
      this._viewer!.ruleset = event.detail.ruleset;
    });
  }

  /**
   * HTML callback event for when a rule has been saved by the user.
   * @param event - A {@link CustomEvent} with a {@link SaveResult} payload.
   */
  protected async _onRuleSave(event: OrRulesSaveEvent) {
    if (event.detail.success) {
      // Reset the modified state, which disables the "save" button
      if (this._viewer) this._viewer.modified = false;

      // Fetch the updated rules, and change the viewer to the latest version
      const newRulesets = await this._rulesTree?.refresh();
      this._checkForViewerUpdate(undefined, newRulesets);

      // After the tree has refreshed, check if the saved rule belongs to a group.
      const savedRuleset = event.detail.ruleset;
      const groupId = savedRuleset.meta?.groupId as string;

      // If it has a group ID, expand tree component to show that group and rules that are inside.
      if (groupId && this._rulesTree) {
        this._rulesTree.expandGroup(groupId);
      }

      // Select the ruleset if it's new
      if (event.detail.isNew) {
        const ruleset = newRulesets?.find((r) => r.id && r.id === event.detail.ruleset.id);
        if (ruleset) {
          this._rulesTree?.deselectAllNodes();
          this._rulesTree?.selectRuleset(ruleset);
        } else {
          console.warn("Could not select the new ruleset.");
        }
      }
    }
  }

  /**
   * Utility function that updates the viewer with the latest changes.
   * @param viewer - The or-rule-viewer HTML element
   * @param updatedRulesets - List of new rulesets that have been changed
   */
  protected _checkForViewerUpdate(viewer = this._viewer, updatedRulesets?: RulesetUnion[]) {
    const current = viewer?.ruleset;
    if (current) {
      const found = updatedRulesets?.find((r) => r.id === viewer?.ruleset?.id);
      if (found) viewer!.ruleset = found;
    }
  }

  /**
   * HTML callback for {@link OrTreeDragEvent}, for when a rule is dragged into (or outside) a group.
   * It handles updating of the 'meta groupId' in a rule, and persists the changes with the HTTP API.
   * @param ev - Tree drag event with a payload of the moved nodes
   */
  protected _onRuleDrag(ev: OrTreeDragEvent) {
    const isModified = this._viewer?.modified;
    const groupId = ev.detail.groupNode?.label;
    const draggedRulesets = (ev.detail.nodes as RuleTreeNode[]).flatMap((node) => (node.ruleset ? [node.ruleset] : []));

    if (this._containsLegacyJavascriptRuleset(draggedRulesets)) {
      ev.preventDefault();
      showSnackbar(undefined, "rulesLegacyJavaScriptUpdate");
      return;
    }

    const moveAndSave = (ruleset: RulesetUnion, groupId?: string) => {
      move(ruleset, groupId);
      return this._saveRuleset(ruleset);
    };
    const move = (ruleset: RulesetUnion, groupId?: string) => {
      if (!ruleset.meta) ruleset.meta = {};
      (ruleset.meta as any).groupId = groupId;
      if (Object.keys(ruleset.meta).length === 0) {
        delete ruleset.meta;
      }
    };

    // If the ruleset viewer has no changes, continue dragging the node, and apply changes.
    if (!isModified) {
      const promises = (ev.detail.nodes as RuleTreeNode[]).map((node) => {
        if (node.ruleset) {
          return moveAndSave(node.ruleset, groupId);
        }
      });
      Promise.all(promises)
        .then(() => showSnackbar(undefined, "ruleDragSuccess"))
        .catch(() => showSnackbar(undefined, "ruleDragFailed"))
        .finally(() => this._rulesTree?.refresh().then((rulesets) => this._checkForViewerUpdate(undefined, rulesets)));

      return;
    }

    console.debug("Prevented the default tree drag behavior.");
    ev.preventDefault(); // prevent the initial rule dragging

    // Prompt the user to "discard changes"
    this._confirmContinue((ok) => {
      if (ok) {
        (ev.detail.nodes as RuleTreeNode[]).forEach((node) => {
          if (node.ruleset) {
            moveAndSave(node.ruleset, groupId)
              ?.then(() => {
                this._rulesTree?.moveNodesToGroup([node], ev.detail.groupNode); // move the nodes again, as the initial event got canceled.
                showSnackbar(undefined, "ruleDragSuccess!");
              })
              .catch(() => {
                showSnackbar(undefined, "ruleDragFailed");
              });
          } else if (node.ruleset) {
            console.warn("Could not add rule to group; could not find ruleset.");
          } else if (groupId) {
            console.warn("Could not add rule to group; could not find group ID.");
          }
        });
      }
    });
  }

  /**
   * Utility function that deselects the group by clearing {@link _selectedGroup}.
   */
  protected _deselectGroup(): Promise<boolean> {
    this._selectedGroup = undefined;
    return this.getUpdateComplete();
  }

  /**
   * HTML callback for when a new group is added to the {@link OrRuleTree}.
   * @param ev - Callback event
   */
  protected _onGroupAddRequest(ev: OrRulesRequestGroupEvent) {
    this._selectedGroup = ev.detail.value;
  }

  /**
   * HTML callback for when the selected group name has changed.
   * This function handles renaming the groupId of the rules within that group,
   * together with persisting the changes using the HTTP API.
   * @param ev - Callback event
   */
  protected _onGroupNameChange(ev: OrRulesGroupNameChangeEvent) {
    const oldValue = this._selectedGroup;
    const newValue = ev.detail.value;
    const groupRules = this._rulesTree?.rules?.filter((r) => r.meta?.groupId === oldValue);

    // If the group name already exists, we should prevent the event from happening
    if (this._rulesTree?.nodes.find((n) => n.label === newValue)) {
      console.warn(`The group '${newValue}' already exists. Please try again.`);
      ev.detail.reason = "exists";
      ev.preventDefault();
      return;
    }

    if (this._containsLegacyJavascriptRuleset(groupRules)) {
      console.warn(`Prevented renaming group '${oldValue}' because it contains legacy JavaScript rules.`);
      ev.detail.reason = "legacyJavascript";
      ev.preventDefault();
      showSnackbar(undefined, "rulesLegacyJavaScriptRename");
      return;
    }

    console.debug(`Renaming group '${oldValue}' to '${newValue}'`);

    // Change the groupId for each child rule, and prepare an HTTP API update
    const promises: Promise<any>[] = [];
    groupRules?.forEach((r) => {
      r.meta!.groupId = newValue;
      promises.push(this._saveRuleset(r));
    });

    // If a rule has changed, execute the HTTP requests.
    // After that, we fetch the list of rules again using .refresh();
    if (promises.length > 0) {
      Promise.all(promises)
        .then(() => showSnackbar(undefined, "Saved!"))
        .catch(() => showSnackbar(undefined, "Failed!"))
        .finally(() => this._rulesTree?.refresh());

      // Otherwise, only update the group name in the tree.
    } else {
      if (this._rulesTree) {
        const nodes = [...this._rulesTree.nodes];
        const node = nodes.find((n) => n.label === oldValue);
        if (node) {
          node.label = newValue;
          this._rulesTree.nodes = nodes;
        } else {
          this._rulesTree.nodes = [...nodes, { id: newValue, label: newValue, children: [] }];
        }
      }
    }
    this._selectedGroup = newValue;
  }

  /**
   * Utility function that returns a promise for saving the ruleset, based on its type.
   * @param ruleset - The ruleset to be saved
   */
  protected async _saveRuleset(ruleset: RulesetUnion) {
    if (this._isLegacyJavascriptRuleset(ruleset)) {
      throw new Error(i18next.t("rulesLegacyJavaScriptUpdate"));
    }

    let promise;
    switch (ruleset.type) {
      case "asset": {
        promise = manager.rest.api.RulesResource.updateAssetRuleset(ruleset.id!, ruleset);
        break;
      }
      case "global": {
        promise = manager.rest.api.RulesResource.updateGlobalRuleset(ruleset.id!, ruleset);
        break;
      }
      case "realm": {
        promise = manager.rest.api.RulesResource.updateRealmRuleset(ruleset.id!, ruleset);
        break;
      }
      default: {
        break;
      }
    }
    return promise;
  }
}
