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
import { html, type TemplateResult } from "lit";
import { createRef, ref, type Ref } from "lit/directives/ref.js";
import { live } from "lit/directives/live.js";
import manager, { Util } from "@openremote/core";
import {
  AssetModelUtil,
  WellknownMetaItems,
  type Asset,
  type Attribute,
  type ConfigurationTemplateImportPreview,
  type ValueConstraint,
  type ValueFormat,
} from "@openremote/model";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-icon";
import "@openremote/or-translate";
import type { OrIcon } from "@openremote/or-icon";
import "@openremote/or-vaadin-components/or-vaadin-badge";
import "@openremote/or-vaadin-components/or-vaadin-button";
import "@openremote/or-vaadin-components/or-vaadin-checkbox";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import "@openremote/or-vaadin-components/or-vaadin-text-field";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import type { OrVaadinCheckbox } from "@openremote/or-vaadin-components/or-vaadin-checkbox";
import {
  dialogFooterRenderer,
  dialogHeaderRenderer,
  dialogRenderer,
  showDialog,
} from "@openremote/or-vaadin-components/or-vaadin-dialog";
import { showErrorDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";

export type AssetAttributes = { [index: string]: Attribute<any> };

/** A configuration item of an attribute as the rows show it. */
interface ConfigurationItem {
  label: string;
  /** The value in short, if it has one. */
  value?: string;
  /** The value as formatted JSON, for a value that is an object. */
  json?: string;
}

const DIALOG_WIDTH = "550px";

// language=CSS
const dialogStyle = html`
  <style>
    .ac-body {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-s);
    }
    .ac-card {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-m);
      background-color: var(--lumo-base-color);
      border-radius: var(--lumo-border-radius-m);
      padding: var(--lumo-space-m);
    }
    .ac-card-title {
      font-size: var(--lumo-font-size-l);
      font-weight: 600;
      color: var(--lumo-body-text-color);
    }
    .ac-select-all {
      padding-inline-start: var(--lumo-space-m);
    }
    .ac-rows {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-s);
    }
    .ac-row {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-xs);
      background-color: var(--lumo-contrast-10pct);
      border-radius: var(--lumo-border-radius-m);
      padding: var(--lumo-space-s) var(--lumo-space-m);
    }
    .ac-row-header {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-s);
      cursor: pointer;
    }
    .ac-row-header .ac-expander {
      flex: none;
      margin-left: auto;
    }
    .ac-row-header > or-vaadin-checkbox,
    .ac-row-header > or-vaadin-checkbox::part(label) {
      min-width: 0;
    }
    .ac-name {
      display: flex;
      gap: 0.25em;
      min-width: 0;
    }
    .ac-name-text {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .ac-name-type {
      flex: none;
      white-space: nowrap;
    }
    .ac-row-header > or-icon {
      flex: none;
      --or-icon-width: 20px;
      --or-icon-height: 20px;
      --or-icon-fill: var(--lumo-secondary-text-color);
    }
    .ac-row-details {
      display: none;
      flex-direction: column;
      gap: var(--lumo-space-xs);
      padding-bottom: var(--lumo-space-s);
    }
    .ac-expanded > .ac-row-details {
      display: flex;
    }
    .ac-item {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-xs);
      min-width: 0;
      background-color: var(--lumo-base-color);
      border-radius: var(--lumo-border-radius-s);
      padding: var(--lumo-space-s) var(--lumo-space-m);
    }
    .ac-item-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--lumo-space-s);
    }
    .ac-item-label {
      color: var(--lumo-tertiary-text-color);
    }
    .ac-item-json {
      display: none;
      max-height: 240px;
      margin: 0;
      overflow: auto;
      padding: var(--lumo-space-s);
      background-color: var(--lumo-contrast-5pct);
      border-radius: var(--lumo-border-radius-s);
      font-size: var(--lumo-font-size-s);
    }
    .ac-json-shown > .ac-item-json {
      display: block;
    }
    .ac-item-value {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      color: var(--lumo-body-text-color);
    }
    .ac-file-name {
      color: var(--lumo-tertiary-text-color);
    }
    .ac-placeholder {
      color: var(--lumo-disabled-text-color);
    }
    .ac-file-row {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-s);
    }
    .ac-alert {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-m);
      background-color: var(--lumo-error-color-10pct);
      border-radius: var(--lumo-border-radius-m);
      padding: var(--lumo-space-m);
      color: var(--lumo-body-text-color);
    }
    .ac-alert or-icon {
      flex: 0 0 auto;
    }
    .ac-dialog-header {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-s);
      width: 100%;
    }
    .ac-dialog-header h2 {
      flex: 1;
      margin: 0;
      font-size: var(--lumo-font-size-xl);
      font-weight: 600;
      color: var(--lumo-header-text-color);
    }
    .ac-dialog-actions {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
    }
  </style>
`;

/** Configuration items that stay with an asset rather than travel in a template, as they grant access or link attributes. */
const ASSET_BOUND_META_ITEMS: string[] = [
  WellknownMetaItems.ACCESSPUBLICREAD,
  WellknownMetaItems.ACCESSPUBLICWRITE,
  WellknownMetaItems.ACCESSRESTRICTEDREAD,
  WellknownMetaItems.ACCESSRESTRICTEDWRITE,
  WellknownMetaItems.ATTRIBUTELINKS,
];

/** The configuration items of an attribute that a template holds. */
function getTemplateMeta(attribute: Attribute<any> | undefined): { [name: string]: unknown } {
  return Object.fromEntries(
    Object.entries(attribute?.meta || {}).filter(([name]) => !ASSET_BOUND_META_ITEMS.includes(name))
  );
}

/** The attributes holding configuration items for a template, which are the attributes that can be exported. */
export function getConfigurableAttributes(asset: Asset): Attribute<any>[] {
  return Object.entries(asset.attributes || {})
    .filter(([, attribute]) => Object.keys(getTemplateMeta(attribute)).length > 0)
    .map(([name, attribute]) => ({ ...attribute, name }))
    .sort(Util.sortByString((attribute) => attribute.name!.toUpperCase()));
}

/** Lets the user pick the attributes to export, then downloads the configuration template. */
export function showExportConfigurationTemplateDialog(host: Node, asset: Asset) {
  const attributes = getConfigurableAttributes(asset);

  if (attributes.length === 0) {
    showSnackbar(undefined, "configurationTemplate.nothingToExport");
    return;
  }

  const selected = new Set<string>(attributes.map((attribute) => attribute.name!));
  let templateName = `${asset.name}-configuration-template.json`;
  const exportBtn: Ref<OrVaadinButton> = createRef();

  const updateExportBtn = () => {
    if (exportBtn.value) {
      exportBtn.value.disabled = selected.size === 0 || templateName.trim() === "";
    }
  };

  const onToggle = (names: string[], checked: boolean) => {
    names.forEach((name) => (checked ? selected.add(name) : selected.delete(name)));
    updateExportBtn();
    dialog?.requestContentUpdate();
  };

  const onTemplateNameChange = (name: string) => {
    templateName = name;
    updateExportBtn();
  };

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width=${DIALOG_WIDTH}
        ${dialogHeaderRenderer(() => getHeaderTemplate("configurationTemplate.exportHeading", () => dialog?.close()))}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <div class="ac-body">
              <or-translate value="configurationTemplate.exportDescription"></or-translate>
              <div class="ac-card">
                <or-vaadin-text-field
                  label=${i18next.t("configurationTemplate.templateName")}
                  .value=${templateName}
                  @value-changed=${(ev: CustomEvent<{ value: string }>) => onTemplateNameChange(ev.detail.value)}
                ></or-vaadin-text-field>
              </div>
              <div class="ac-card">
                <or-translate class="ac-card-title" value="configurationTemplate.selectAttributes"></or-translate>
                <or-vaadin-checkbox
                  class="ac-select-all"
                  label=${i18next.t("configurationTemplate.selectAll")}
                  .checked=${live(selected.size === attributes.length)}
                  .indeterminate=${live(selected.size > 0 && selected.size < attributes.length)}
                  @change=${(ev: Event) =>
                    onToggle(
                      attributes.map((attribute) => attribute.name!),
                      (ev.currentTarget as OrVaadinCheckbox).checked
                    )}
                ></or-vaadin-checkbox>
                <div class="ac-rows">
                  ${attributes.map((attribute) =>
                    getAttributeRowTemplate(
                      asset,
                      attribute,
                      html`
                        <or-vaadin-checkbox
                          .checked=${live(selected.has(attribute.name!))}
                          @change=${(ev: Event) =>
                            onToggle([attribute.name!], (ev.currentTarget as OrVaadinCheckbox).checked)}
                        >
                          <label slot="label" class="ac-name">${getAttributeNameTemplate(asset, attribute)}</label>
                        </or-vaadin-checkbox>
                      `
                    )
                  )}
                </div>
              </div>
            </div>
          `
        )}
        ${dialogFooterRenderer(
          () => html`
            <div class="ac-dialog-actions">
              <or-vaadin-button theme="tertiary contrast" @click=${() => dialog?.close()}>
                <or-translate value="cancel"></or-translate>
              </or-vaadin-button>
              <or-vaadin-button
                theme="primary"
                ${ref(exportBtn)}
                @click=${() => {
                  dialog?.close();
                  downloadConfigurationTemplate(asset, Array.from(selected), templateName);
                }}
              >
                <or-translate value="configurationTemplate.export"></or-translate>
              </or-vaadin-button>
            </div>
          `
        )}
      ></or-vaadin-dialog>
    `
  );
}

/**
 * Lets the user pick a configuration file, reports what importing it would do and, once confirmed,
 * hands the patched attributes to the caller to apply to the asset draft.
 */
export function showImportConfigurationTemplateDialog(
  host: Node,
  asset: Asset,
  onImported: (attributes: AssetAttributes) => void,
  selectedFile?: File
) {
  let file = selectedFile;
  const fileName: Ref<HTMLSpanElement> = createRef();
  const fileInput: Ref<HTMLInputElement> = createRef();
  const nextBtn: Ref<OrVaadinButton> = createRef();

  const onFileSelected = () => {
    file = fileInput.value?.files?.[0];
    if (fileName.value) {
      fileName.value.textContent = file ? file.name : i18next.t("configurationTemplate.noFileSelected");
      fileName.value.classList.toggle("ac-placeholder", !file);
    }
    if (nextBtn.value) {
      nextBtn.value.disabled = !file;
    }
  };

  const onNext = async () => {
    if (!file) {
      return;
    }

    const selected = file;
    const configuration = await selected.text();
    dialog?.close();

    previewImport(host, asset, selected.name, configuration, onImported, () =>
      showImportConfigurationTemplateDialog(host, asset, onImported, selected)
    );
  };

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width=${DIALOG_WIDTH}
        ${dialogHeaderRenderer(() => getHeaderTemplate("configurationTemplate.importHeading", () => dialog?.close()))}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <div class="ac-body">
              <or-translate value="configurationTemplate.selectFileDescription"></or-translate>
              <div class="ac-card">
                <div class="ac-file-row">
                  <input
                    ${ref(fileInput)}
                    type="file"
                    accept="application/json,.json"
                    style="display: none"
                    @change=${() => onFileSelected()}
                  />
                  <or-vaadin-button @click=${() => fileInput.value?.click()}>
                    <or-translate value="selectFile"></or-translate>
                  </or-vaadin-button>
                  <span ${ref(fileName)} class=${file ? "" : "ac-placeholder"}>
                    ${file ? file.name : i18next.t("configurationTemplate.noFileSelected")}
                  </span>
                </div>
              </div>
            </div>
          `
        )}
        ${dialogFooterRenderer(
          () => html`
            <div class="ac-dialog-actions">
              <or-vaadin-button theme="tertiary contrast" @click=${() => dialog?.close()}>
                <or-translate value="cancel"></or-translate>
              </or-vaadin-button>
              <or-vaadin-button theme="primary" ?disabled=${!file} ${ref(nextBtn)} @click=${() => onNext()}>
                <or-translate value="configurationTemplate.next"></or-translate>
              </or-vaadin-button>
            </div>
          `
        )}
      ></or-vaadin-dialog>
    `
  );
}

/** Asks the server what importing the configuration would do, and reports it. */
async function previewImport(
  host: Node,
  asset: Asset,
  fileName: string,
  configuration: string,
  onImported: (attributes: AssetAttributes) => void,
  onBack: () => void
) {
  try {
    const response = await manager.rest.api.AssetResource.previewConfigurationTemplateImport(asset.id!, {
      draft: asset,
      document: configuration,
    });
    showImportPreviewDialog(host, asset, fileName, configuration, response.data, onImported, onBack);
  } catch (error) {
    showImportFailureDialog(host, asset, configuration, error);
  }
}

/** Reports what the import would change and warns that it overwrites the metadata of matched attributes. */
function showImportPreviewDialog(
  host: Node,
  asset: Asset,
  fileName: string,
  configuration: string,
  preview: ConfigurationTemplateImportPreview,
  onImported: (attributes: AssetAttributes) => void,
  onBack: () => void
) {
  const patchedAttributes = (preview.patchedAttributes || {}) as AssetAttributes;
  const importable = preview.importableAttributes || [];

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width=${DIALOG_WIDTH}
        ${dialogHeaderRenderer(() => getHeaderTemplate("configurationTemplate.importHeading", () => dialog?.close()))}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <div class="ac-body">
              <span class="ac-file-name">${fileName}</span>
              <div class="ac-alert">
                <or-icon icon="alert-circle-outline"></or-icon>
                <or-translate value="configurationTemplate.overwriteWarning"></or-translate>
              </div>
              <div class="ac-card">
                <or-translate value="configurationTemplate.matchedAttributes"></or-translate>
                <div class="ac-rows">
                  ${importable.map((summary) => {
                    const attribute = { ...patchedAttributes[summary.name!], name: summary.name };
                    return getAttributeRowTemplate(
                      asset,
                      attribute,
                      html`
                        <or-icon icon="check"></or-icon>
                        <span class="ac-name">${getAttributeNameTemplate(asset, attribute)}</span>
                      `
                    );
                  })}
                </div>
              </div>
              ${getSkippedTemplate(asset, preview, configuration)}
            </div>
          `
        )}
        ${dialogFooterRenderer(
          () => html`
            <div class="ac-dialog-actions">
              <or-vaadin-button
                theme="tertiary contrast"
                @click=${() => {
                  dialog?.close();
                  onBack();
                }}
              >
                <or-translate value="configurationTemplate.back"></or-translate>
              </or-vaadin-button>
              <or-vaadin-button
                theme="primary"
                @click=${() => {
                  dialog?.close();
                  onImported(patchedAttributes);
                }}
              >
                <or-translate value="configurationTemplate.import"></or-translate>
              </or-vaadin-button>
            </div>
          `
        )}
      ></or-vaadin-dialog>
    `
  );
}

/** Reports a document that cannot be imported, with whatever the server could tell about it. */
function showImportFailureDialog(host: Node, asset: Asset, configuration: string, error: unknown) {
  const preview = getFailurePreview(error);
  const forbidden = (error as { response?: { status?: number } })?.response?.status === 403;
  const message = preview
    ? i18next.t(`configurationTemplate.failure.${preview.failure}`)
    : i18next.t(forbidden ? "configurationTemplate.forbidden" : "errorOccurred");

  showErrorDialog(
    host,
    html`${dialogStyle}
      <div class="ac-body">
        <span>${message}</span>
        ${preview ? getSkippedTemplate(asset, preview, configuration) : ``}
      </div>`,
    "configurationTemplate.importHeading"
  );
}

function getHeaderTemplate(titleKey: string, onClose: () => void): TemplateResult {
  return html`
    ${dialogStyle}
    <div class="ac-dialog-header">
      <h2><or-translate value=${titleKey}></or-translate></h2>
      <or-vaadin-button theme="tertiary icon" @click=${() => onClose()}>
        <or-icon icon="close"></or-icon>
      </or-vaadin-button>
    </div>
  `;
}

/** The attributes of the file that are not imported, as the file holds them. */
function getSkippedTemplate(
  asset: Asset,
  preview: ConfigurationTemplateImportPreview,
  configuration: string
): TemplateResult | string {
  const skipped = [
    ...(preview.missingAttributes || []).map((attribute) => attribute.name!),
    ...(preview.typeMismatches || []).map((mismatch) => mismatch.name!),
  ];

  if (skipped.length === 0) {
    return ``;
  }

  const fileAttributes = getFileAttributes(configuration);

  return html`
    <div class="ac-card">
      <or-translate value="configurationTemplate.skippedAttributes"></or-translate>
      <div class="ac-rows">
        ${skipped.map((name) => {
          const attribute = { ...fileAttributes[name], name };
          return getAttributeRowTemplate(
            asset,
            attribute,
            html`
              <or-icon icon="close"></or-icon>
              <span class="ac-name">${getAttributeNameTemplate(asset, attribute)}</span>
            `
          );
        })}
      </div>
    </div>
  `;
}

/** An attribute with its configuration items, which the row expands to show. */
function getAttributeRowTemplate(asset: Asset, attribute: Attribute<any>, lead: TemplateResult): TemplateResult {
  return html`
    <div class="ac-row">
      <div class="ac-row-header" @click=${toggleExpanded}>
        ${lead}
        <or-vaadin-badge theme="counter">${Object.keys(getTemplateMeta(attribute)).length}</or-vaadin-badge>
        <or-vaadin-button class="ac-expander" theme="tertiary icon">
          <or-icon icon="chevron-down"></or-icon>
        </or-vaadin-button>
      </div>
      <div class="ac-row-details">
        ${getConfigurationItems(asset, attribute).map(
          (item) => html`
            <div class="ac-item">
              <div class="ac-item-header">
                <span class="ac-item-label">${item.label}</span>
                ${
                  item.json
                    ? html`
                        <or-vaadin-button theme="tertiary small" @click=${toggleJson}>
                          <or-translate value="configurationTemplate.json"></or-translate>
                        </or-vaadin-button>
                      `
                    : ``
                }
              </div>
              ${
                item.value !== undefined
                  ? html`<span class="ac-item-value" title=${item.value}>${item.value}</span>`
                  : ``
              }
              ${item.json ? html`<pre class="ac-item-json">${item.json}</pre>` : ``}
            </div>
          `
        )}
      </div>
    </div>
  `;
}

/** Expands or collapses the section of the clicked header. */
function toggleExpanded(ev: Event) {
  const header = ev.currentTarget as HTMLElement;
  const section = header.parentElement;
  const icon = header.querySelector(".ac-expander or-icon") as OrIcon | null;

  // A checkbox in the header only toggles itself
  if (!section || (ev.target as Element).closest("or-vaadin-checkbox")) {
    return;
  }

  section.classList.toggle("ac-expanded");

  if (icon) {
    icon.icon = section.classList.contains("ac-expanded") ? "chevron-up" : "chevron-down";
  }
}

async function downloadConfigurationTemplate(asset: Asset, attributeNames: string[], templateName: string) {
  try {
    const response = await manager.rest.api.AssetResource.exportConfigurationTemplate(asset.id!, {
      attributeName: attributeNames,
    });
    const blob = new Blob([JSON.stringify(response.data, null, 2)], { type: "application/json" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    const fileName = templateName.trim();
    link.setAttribute("download", fileName.toLowerCase().endsWith(".json") ? fileName : `${fileName}.json`);
    document.body.appendChild(link);
    link.click();
    link.remove();
    window.URL.revokeObjectURL(url);
  } catch (error) {
    console.error("Failed to export the configuration template", error);
    showSnackbar(undefined, "errorOccurred");
  }
}

/** The report the server sends along with a rejected import, if this is one. */
function getFailurePreview(error: unknown): ConfigurationTemplateImportPreview | undefined {
  const data = (error as { response?: { data?: ConfigurationTemplateImportPreview } })?.response?.data;
  return data?.failure ? data : undefined;
}

/** The attributes of a configuration file keyed by name, or none if it cannot be read. */
function getFileAttributes(configuration: string): AssetAttributes {
  try {
    const attributes = JSON.parse(configuration)?.attributes;
    return attributes && typeof attributes === "object" ? (attributes as AssetAttributes) : {};
  } catch {
    return {};
  }
}

/** The attribute label, which truncates when it does not fit, followed by its value type, which stays visible. */
function getAttributeNameTemplate(asset: Asset, attribute: Attribute<any>): TemplateResult {
  return html`
    <span class="ac-name-text" title=${attributeLabel(asset, attribute)}>${attributeName(asset, attribute)}</span>
    <span class="ac-name-type">(${Util.getValueDescriptorLabel(attribute.type)})</span>
  `;
}

/** The attribute label with its value type, as the Figma dialogs show it, e.g. "Model (Text)". */
function attributeLabel(asset: Asset, attribute: Attribute<any>): string {
  return `${attributeName(asset, attribute)} (${Util.getValueDescriptorLabel(attribute.type)})`;
}

function attributeName(asset: Asset, attribute: Attribute<any>): string {
  const descriptor = AssetModelUtil.getAttributeDescriptor(attribute.name!, asset.type!);
  return Util.getAttributeLabel(attribute, descriptor, asset.type, false);
}

/** The configuration items of an attribute that a template holds, sorted by label. */
function getConfigurationItems(asset: Asset, attribute: Attribute<any>): ConfigurationItem[] {
  return Object.entries(getTemplateMeta(attribute))
    .map(([name, value]) => getConfigurationItem(asset, name, value))
    .sort((a, b) => a.label.localeCompare(b.label));
}

/**
 * A configuration item with its value in short: a plain value as it is, labeled with its value type, an item that has a
 * short form as that, and any other list of plain values comma separated. Any other value only shows as formatted JSON,
 * through its JSON button, which a short form has as well.
 */
function getConfigurationItem(asset: Asset, name: string, value: unknown): ConfigurationItem {
  const label = Util.getMetaLabel(undefined, name, asset.type, false);

  if (value === null || typeof value !== "object") {
    const type = AssetModelUtil.getMetaItemDescriptor(name)?.type;
    return { label: type ? `${label} (${Util.getValueDescriptorLabel(type)})` : label, value: String(value) };
  }
  const json = JSON.stringify(value, null, 2);
  const shortValue = SHORT_VALUES[name]?.(value);

  if (shortValue) {
    return { label, value: shortValue, json };
  }
  if (Array.isArray(value) && value.every((item) => item === null || typeof item !== "object")) {
    return { label, value: value.join(", ") };
  }
  return { label, json };
}

/** The short forms of configuration items, by item name. */
const SHORT_VALUES: { [name: string]: (value: any) => string | undefined } = {
  [WellknownMetaItems.AGENTLINK]: getAgentLinkShortValue,
  [WellknownMetaItems.CONSTRAINTS]: (constraints: ValueConstraint[]) => getNames(constraints.map(({ type }) => type)),
  [WellknownMetaItems.FORMAT]: (format: ValueFormat) => getNames(Object.keys(format)),
  [WellknownMetaItems.UNITS]: (units: string[]) => Util.resolveUnits(units),
};

/** An agent link as its type and agent id, e.g. "SimulatorAgentLink (5Bg0X…)". */
function getAgentLinkShortValue(link: { type?: unknown; id?: unknown }): string | undefined {
  if (typeof link.type !== "string") {
    return undefined;
  }
  return link.id ? `${link.type} (${String(link.id)})` : link.type;
}

/** Names in sentence case, comma separated, e.g. "Not blank, Size". */
function getNames(names: string[]): string {
  return names.map((name) => Util.camelCaseToSentenceCase(name)).join(", ");
}

/** Shows or hides the formatted JSON of a configuration item. */
function toggleJson(ev: Event) {
  (ev.currentTarget as HTMLElement).closest(".ac-item")?.classList.toggle("ac-json-shown");
}
