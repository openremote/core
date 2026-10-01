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
import { when } from "lit/directives/when.js";
import manager, { Util } from "@openremote/core";
import {
  AssetModelUtil,
  type Asset,
  type Attribute,
  type AttributeConfigurationImportPreview,
} from "@openremote/model";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-icon";
import "@openremote/or-translate";
import type { OrIcon } from "@openremote/or-icon";
import "@openremote/or-vaadin-components/or-vaadin-button";
import "@openremote/or-vaadin-components/or-vaadin-checkbox";
import "@openremote/or-vaadin-components/or-vaadin-dialog";
import type { OrVaadinButton } from "@openremote/or-vaadin-components/or-vaadin-button";
import type { OrVaadinCheckbox } from "@openremote/or-vaadin-components/or-vaadin-checkbox";
import {
  dialogFooterRenderer,
  dialogHeaderRenderer,
  dialogRenderer,
  showDialog,
} from "@openremote/or-vaadin-components/or-vaadin-dialog";
import {
  getConfirmDialogContent,
  showConfirmDialog,
  showErrorDialog,
  type OrVaadinConfirmDialog,
} from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";

export type AssetAttributes = { [index: string]: Attribute<any> };

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
    .ac-rows {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-s);
    }
    .ac-row {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-xs);
      background-color: var(--lumo-contrast-5pct);
      border-radius: var(--lumo-border-radius-m);
      padding: var(--lumo-space-s);
    }
    .ac-row-header {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-s);
    }
    .ac-row-header .ac-expander {
      margin-left: auto;
    }
    .ac-row-details {
      display: none;
      flex-direction: column;
      gap: var(--lumo-space-xs);
      background-color: var(--lumo-contrast-5pct);
      border-radius: var(--lumo-border-radius-m);
      padding: var(--lumo-space-m);
      color: var(--lumo-secondary-text-color);
    }
    .ac-row.ac-expanded .ac-row-details {
      display: flex;
    }
    .ac-badge {
      background-color: var(--lumo-contrast-10pct);
      border-radius: var(--lumo-border-radius-m);
      color: var(--lumo-secondary-text-color);
      font-size: var(--lumo-font-size-xs);
      line-height: 1;
      padding: 4px 10px;
    }
    .ac-block {
      display: flex;
      flex-direction: column;
      gap: var(--lumo-space-s);
      background-color: var(--lumo-contrast-5pct);
      border-radius: var(--lumo-border-radius-m);
      padding: var(--lumo-space-m);
    }
    .ac-block-title {
      font-size: var(--lumo-font-size-l);
      font-weight: 600;
      color: var(--lumo-body-text-color);
    }
    .ac-block ul {
      margin: 0;
      padding-inline-start: 24px;
    }
    .ac-file-name {
      color: var(--lumo-body-text-color);
    }
    .ac-placeholder {
      color: var(--lumo-disabled-text-color);
    }
    .ac-file-row {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-s);
    }
    .ac-warning {
      display: flex;
      align-items: center;
      gap: var(--lumo-space-s);
      color: var(--lumo-warning-text-color);
      --or-icon-fill: var(--lumo-warning-text-color);
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

/** The attributes that hold metadata, which are the attributes that can be exported. */
export function getConfigurableAttributes(asset: Asset): Attribute<any>[] {
  return Object.entries(asset.attributes || {})
    .filter(([, attribute]) => Object.keys(attribute.meta || {}).length > 0)
    .map(([name, attribute]) => ({ ...attribute, name }))
    .sort(Util.sortByString((attribute) => attribute.name!.toUpperCase()));
}

/** Lets the user pick the attributes to export, then downloads the configuration document. */
export function showExportAttributeConfigurationDialog(host: Node, asset: Asset) {
  const attributes = getConfigurableAttributes(asset);

  if (attributes.length === 0) {
    showSnackbar(undefined, "attributeConfiguration.nothingToExport");
    return;
  }

  const selected = new Set<string>(attributes.map((attribute) => attribute.name!));
  let templateName = `${asset.name}-attribute-config.json`;
  const exportBtn: Ref<OrVaadinButton> = createRef();

  const updateExportBtn = () => {
    if (exportBtn.value) {
      exportBtn.value.disabled = selected.size === 0 || templateName.trim() === "";
    }
  };

  const onToggle = (name: string, checked: boolean) => {
    if (checked) {
      selected.add(name);
    } else {
      selected.delete(name);
    }
    updateExportBtn();
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
        ${dialogHeaderRenderer(() => getHeaderTemplate("attributeConfiguration.exportHeading", () => dialog?.close()))}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <div class="ac-body">
              <or-translate value="attributeConfiguration.exportDescription"></or-translate>
              <div class="ac-card">
                <or-vaadin-text-field
                  label=${i18next.t("attributeConfiguration.templateName")}
                  .value=${templateName}
                  @value-changed=${(ev: CustomEvent<{ value: string }>) => onTemplateNameChange(ev.detail.value)}
                ></or-vaadin-text-field>
              </div>
              <div class="ac-card">
                <or-translate value="attributeConfiguration.selectAttributes"></or-translate>
                <div class="ac-rows">
                  ${attributes.map(
                    (attribute) => html`
                      <div class="ac-row">
                        <div class="ac-row-header">
                          <or-vaadin-checkbox
                            checked
                            label=${attributeLabel(asset, attribute)}
                            @change=${(ev: Event) =>
                              onToggle(attribute.name!, (ev.currentTarget as OrVaadinCheckbox).checked)}
                          ></or-vaadin-checkbox>
                          <span class="ac-badge">${Object.keys(attribute.meta || {}).length}</span>
                          <or-vaadin-button class="ac-expander" theme="tertiary icon" @click=${toggleRow}>
                            <or-icon icon="chevron-down"></or-icon>
                          </or-vaadin-button>
                        </div>
                        <div class="ac-row-details">
                          ${metaLabels(asset, attribute).map((label) => html`<span>${label}</span>`)}
                        </div>
                      </div>
                    `
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
                  downloadAttributeConfiguration(asset, Array.from(selected), templateName);
                }}
              >
                <or-translate value="attributeConfiguration.export"></or-translate>
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
export function showImportAttributeConfigurationDialog(
  host: Node,
  asset: Asset,
  onImported: (attributes: AssetAttributes) => void,
  selectedFile?: File
) {
  let file = selectedFile;
  const fileName: Ref<HTMLSpanElement> = createRef();
  const fileInput: Ref<HTMLInputElement> = createRef();
  const importBtn: Ref<OrVaadinButton> = createRef();

  const onFileSelected = () => {
    file = fileInput.value?.files?.[0];
    if (fileName.value) {
      fileName.value.textContent = file ? file.name : i18next.t("attributeConfiguration.noFileSelected");
      fileName.value.classList.toggle("ac-placeholder", !file);
    }
    if (importBtn.value) {
      importBtn.value.disabled = !file;
    }
  };

  const onImport = async () => {
    if (!file) {
      return;
    }

    const selected = file;
    const configuration = await selected.text();
    dialog?.close();

    try {
      const response = await manager.rest.api.AssetResource.previewAttributeConfigurationImport(asset.id!, {
        draft: asset,
        document: configuration,
      });
      showImportPreviewDialog(host, asset, selected.name, response.data, onImported, () =>
        showImportAttributeConfigurationDialog(host, asset, onImported, selected)
      );
    } catch (error) {
      showImportFailureDialog(host, error);
    }
  };

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width=${DIALOG_WIDTH}
        ${dialogHeaderRenderer(() => getHeaderTemplate("attributeConfiguration.importHeading", () => dialog?.close()))}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <div class="ac-body">
              <div class="ac-card">
                <or-translate value="attributeConfiguration.selectFileDescription"></or-translate>
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
                    ${file ? file.name : i18next.t("attributeConfiguration.noFileSelected")}
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
              <or-vaadin-button theme="primary" ?disabled=${!file} ${ref(importBtn)} @click=${() => onImport()}>
                <or-translate value="attributeConfiguration.import"></or-translate>
              </or-vaadin-button>
            </div>
          `
        )}
      ></or-vaadin-dialog>
    `
  );
}

/** Reports what the import would change, before the final overwrite warning. */
function showImportPreviewDialog(
  host: Node,
  asset: Asset,
  fileName: string,
  preview: AttributeConfigurationImportPreview,
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
        ${dialogHeaderRenderer(() => getHeaderTemplate("attributeConfiguration.importHeading", () => dialog?.close()))}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <div class="ac-body">
              <span class="ac-file-name">${fileName}</span>
              ${getAssetTypeMismatchTemplate(preview)}
              <div class="ac-card">
                <or-translate value="attributeConfiguration.overwriteWarning"></or-translate>
                ${importable.map(
                  (attribute) => html`
                    <div class="ac-block">
                      <span class="ac-block-title">
                        ${attributeLabel(asset, patchedAttributes[attribute.name!], attribute.name)}
                      </span>
                      <ul>
                        ${metaLabels(asset, patchedAttributes[attribute.name!]).map((label) => html`<li>${label}</li>`)}
                      </ul>
                    </div>
                  `
                )}
              </div>
              ${getSkippedTemplate(preview)}
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
                <or-translate value="attributeConfiguration.back"></or-translate>
              </or-vaadin-button>
              <or-vaadin-button
                theme="primary"
                @click=${() => {
                  dialog?.close();
                  showOverwriteWarningDialog(
                    host,
                    () => showImportPreviewDialog(host, asset, fileName, preview, onImported, onBack),
                    () => {
                      onImported(patchedAttributes);
                      showImportResultDialog(host, preview);
                    }
                  );
                }}
              >
                <or-translate value="attributeConfiguration.next"></or-translate>
              </or-vaadin-button>
            </div>
          `
        )}
      ></or-vaadin-dialog>
    `
  );
}

/**
 * The last step before the draft is changed. Back sits in the cancel slot, so the confirm dialog
 * closes itself on its click. Escape and the close icon only close it.
 */
function showOverwriteWarningDialog(host: Node, onBack: () => void, onConfirm: () => void) {
  const dialog: Ref<OrVaadinConfirmDialog> = createRef();

  showConfirmDialog(
    host,
    html`
      <or-vaadin-confirm-dialog ${ref(dialog)} @confirm=${() => onConfirm()}>
        ${getConfirmDialogContent(
          "",
          getHeaderTemplate("attributeConfiguration.warningHeading", () => dialog.value?.close()),
          "attributeConfiguration.overwriteConfirm",
          "attributeConfiguration.confirm"
        )}
        <or-vaadin-button theme="tertiary contrast" slot="cancel-button" @click=${() => onBack()}>
          <or-translate value="attributeConfiguration.back"></or-translate>
        </or-vaadin-button>
      </or-vaadin-confirm-dialog>
    `
  );
}

/** Reports the applied import, which the user still has to save or discard. */
function showImportResultDialog(host: Node, preview: AttributeConfigurationImportPreview) {
  const names = (preview.importableAttributes || []).map((attribute) => attribute.name).join(", ");

  showConfirmDialog(
    host,
    html`
      <or-vaadin-confirm-dialog>
        ${getConfirmDialogContent(
          "",
          "attributeConfiguration.importHeading",
          html`
            <div>${i18next.t("attributeConfiguration.imported", { attributes: names })}</div>
            <div><or-translate value="attributeConfiguration.saveToPersist"></or-translate></div>
          `,
          "close"
        )}
      </or-vaadin-confirm-dialog>
    `
  );
}

/** Reports a document that cannot be imported, with whatever the server could tell about it. */
function showImportFailureDialog(host: Node, error: unknown) {
  const preview = getFailurePreview(error);
  const forbidden = (error as { response?: { status?: number } })?.response?.status === 403;
  const message = preview
    ? i18next.t(`attributeConfiguration.failure.${preview.failure}`)
    : i18next.t(forbidden ? "attributeConfiguration.forbidden" : "errorOccurred");

  showErrorDialog(
    host,
    html`${dialogStyle}
      <div class="ac-body">
        <span>${message}</span>
        ${preview ? getSkippedTemplate(preview) : ``}
      </div>`,
    "attributeConfiguration.importHeading"
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

function getAssetTypeMismatchTemplate(preview: AttributeConfigurationImportPreview): TemplateResult | string {
  return when(
    preview.assetTypeMismatch,
    () => html`
      <div class="ac-warning">
        <or-icon icon="alert"></or-icon>
        <span>
          ${i18next.t("attributeConfiguration.assetTypeMismatch", {
            imported: Util.getAssetTypeLabel(preview.importedAssetType),
            target: Util.getAssetTypeLabel(preview.targetAssetType),
          })}
        </span>
      </div>
    `,
    () => ``
  );
}

function getSkippedTemplate(preview: AttributeConfigurationImportPreview): TemplateResult | string {
  const missing = preview.missingAttributes || [];
  const mismatches = preview.typeMismatches || [];

  if (missing.length === 0 && mismatches.length === 0) {
    return ``;
  }

  return html`
    <div class="ac-card">
      ${when(
        missing.length > 0,
        () => html`
          <div class="ac-block">
            <span class="ac-block-title">
              <or-translate value="attributeConfiguration.skippedMissing"></or-translate>
            </span>
            <ul>
              ${missing.map((attribute) => html`<li>${attribute.name} (${attribute.type})</li>`)}
            </ul>
          </div>
        `
      )}
      ${when(
        mismatches.length > 0,
        () => html`
          <div class="ac-block">
            <span class="ac-block-title">
              <or-translate value="attributeConfiguration.skippedTypeMismatch"></or-translate>
            </span>
            <ul>
              ${mismatches.map(
                (mismatch) => html`<li>${mismatch.name} (${mismatch.importedType} → ${mismatch.targetType})</li>`
              )}
            </ul>
          </div>
        `
      )}
    </div>
  `;
}

/** Expands or collapses the metadata of an attribute row. */
function toggleRow(ev: Event) {
  const button = ev.currentTarget as HTMLElement;
  const row = button.closest(".ac-row");
  const icon = button.querySelector("or-icon") as OrIcon | null;

  if (!row) {
    return;
  }

  row.classList.toggle("ac-expanded");

  if (icon) {
    icon.icon = row.classList.contains("ac-expanded") ? "chevron-up" : "chevron-down";
  }
}

async function downloadAttributeConfiguration(asset: Asset, attributeNames: string[], templateName: string) {
  try {
    const response = await manager.rest.api.AssetResource.exportAttributeConfiguration(asset.id!, {
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
    console.error("Failed to export attribute configuration", error);
    showSnackbar(undefined, "errorOccurred");
  }
}

/** The report the server sends along with a rejected import, if this is one. */
function getFailurePreview(error: unknown): AttributeConfigurationImportPreview | undefined {
  const data = (error as { response?: { data?: AttributeConfigurationImportPreview } })?.response?.data;
  return data?.failure ? data : undefined;
}

/** The attribute label with its value type, as the Figma dialogs show it, e.g. "Model (Text)". */
function attributeLabel(asset: Asset, attribute?: Attribute<any>, fallback?: string): string {
  if (!attribute) {
    return fallback || "";
  }
  const descriptor = AssetModelUtil.getAttributeDescriptor(attribute.name!, asset.type!);
  const label = Util.getAttributeLabel(attribute, descriptor, asset.type, false, fallback);
  return `${label} (${Util.getValueDescriptorLabel(attribute.type)})`;
}

function metaLabels(asset: Asset, attribute?: Attribute<any>): string[] {
  return Object.keys(attribute?.meta || {})
    .map((name) => Util.getMetaLabel(undefined, name, asset.type, false))
    .sort();
}
