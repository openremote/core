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
} from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";

export type AssetAttributes = { [index: string]: Attribute<any> };

// language=CSS
const dialogStyle = html`
  <style>
    .dialog-text {
      margin: 0 0 16px 0;
    }
    .dialog-section {
      margin-bottom: 16px;
    }
    .dialog-section-title {
      font-size: 12px;
      font-weight: bold;
      text-transform: uppercase;
      margin-bottom: 8px;
    }
    .attribute-row {
      border: 1px solid var(--lumo-contrast-10pct);
      border-radius: 4px;
      padding: 8px;
      margin-bottom: 8px;
    }
    .attribute-row-header {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .meta-count {
      background-color: var(--lumo-contrast-10pct);
      border-radius: 10px;
      font-size: 12px;
      padding: 0 8px;
    }
    .meta-names {
      color: var(--lumo-secondary-text-color);
      font-size: 14px;
      margin: 4px 0 0 8px;
    }
    .warning {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--lumo-error-text-color);
      margin-bottom: 16px;
    }
    .file-row {
      display: flex;
      align-items: center;
      gap: 12px;
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
  const exportBtn: Ref<OrVaadinButton> = createRef();

  const onToggle = (name: string, checked: boolean) => {
    if (checked) {
      selected.add(name);
    } else {
      selected.delete(name);
    }
    if (exportBtn.value) {
      exportBtn.value.disabled = selected.size === 0;
    }
  };

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width="550px"
        ${dialogHeaderRenderer(
          () => html`<h2><or-translate value="attributeConfiguration.exportHeading"></or-translate></h2>`
        )}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <p class="dialog-text"><or-translate value="attributeConfiguration.exportDescription"></or-translate></p>
            <div class="dialog-section-title">
              <or-translate value="attributeConfiguration.selectAttributes"></or-translate>
            </div>
            ${attributes.map(
              (attribute) => html`
                <div class="attribute-row">
                  <div class="attribute-row-header">
                    <or-vaadin-checkbox
                      checked
                      label=${attributeLabel(asset, attribute)}
                      @change=${(ev: Event) => onToggle(attribute.name!, (ev.currentTarget as OrVaadinCheckbox).checked)}
                    ></or-vaadin-checkbox>
                    <span class="meta-count">${Object.keys(attribute.meta || {}).length}</span>
                  </div>
                  <div class="meta-names">${metaLabels(asset, attribute).join(", ")}</div>
                </div>
              `
            )}
          `
        )}
        ${dialogFooterRenderer(
          () => html`
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button
              theme="primary"
              ${ref(exportBtn)}
              @click=${() => {
                dialog?.close();
                downloadAttributeConfiguration(asset, Array.from(selected));
              }}
            >
              <or-icon slot="prefix" icon="download"></or-icon>
              <or-translate value="attributeConfiguration.export"></or-translate>
            </or-vaadin-button>
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
  onImported: (attributes: AssetAttributes) => void
) {
  let file: File | undefined;
  const fileName: Ref<HTMLSpanElement> = createRef();
  const fileInput: Ref<HTMLInputElement> = createRef();
  const continueBtn: Ref<OrVaadinButton> = createRef();

  const onFileSelected = () => {
    file = fileInput.value?.files?.[0];
    if (fileName.value) {
      fileName.value.textContent = file ? file.name : i18next.t("attributeConfiguration.noFileSelected");
    }
    if (continueBtn.value) {
      continueBtn.value.disabled = !file;
    }
  };

  const onContinue = async () => {
    if (!file) {
      return;
    }

    const configuration = await file.text();
    const selectedName = file.name;
    dialog?.close();

    try {
      const response = await manager.rest.api.AssetResource.previewAttributeConfigurationImport(asset.id!, {
        draft: asset,
        document: configuration,
      });
      showImportPreviewDialog(host, asset, selectedName, response.data, onImported);
    } catch (error) {
      showImportFailureDialog(host, error);
    }
  };

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width="550px"
        ${dialogHeaderRenderer(
          () => html`<h2><or-translate value="attributeConfiguration.importHeading"></or-translate></h2>`
        )}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <p class="dialog-text"><or-translate value="attributeConfiguration.selectFile"></or-translate></p>
            <div class="file-row">
              <input
                ${ref(fileInput)}
                type="file"
                accept="application/json,.json"
                style="display: none"
                @change=${() => onFileSelected()}
              />
              <or-vaadin-button @click=${() => fileInput.value?.click()}>
                <or-icon slot="prefix" icon="upload"></or-icon>
                <or-translate value="attributeConfiguration.chooseFile"></or-translate>
              </or-vaadin-button>
              <span ${ref(fileName)}>${i18next.t("attributeConfiguration.noFileSelected")}</span>
            </div>
          `
        )}
        ${dialogFooterRenderer(
          () => html`
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" disabled ${ref(continueBtn)} @click=${() => onContinue()}>
              <or-translate value="next"></or-translate>
            </or-vaadin-button>
          `
        )}
      ></or-vaadin-dialog>
    `
  );
}

/** Reports what the import would change and applies it once the user confirms. */
function showImportPreviewDialog(
  host: Node,
  asset: Asset,
  fileName: string,
  preview: AttributeConfigurationImportPreview,
  onImported: (attributes: AssetAttributes) => void
) {
  const patchedAttributes = (preview.patchedAttributes || {}) as AssetAttributes;
  const importable = preview.importableAttributes || [];

  const dialog = showDialog(
    host,
    html`
      <or-vaadin-dialog
        width="550px"
        ${dialogHeaderRenderer(
          () => html`<h2><or-translate value="attributeConfiguration.importHeading"></or-translate></h2>`
        )}
        ${dialogRenderer(
          () => html`
            ${dialogStyle}
            <p class="dialog-text">${fileName}</p>
            ${getAssetTypeMismatchTemplate(preview)}
            <p class="dialog-text">
              <or-translate value="attributeConfiguration.overwriteWarning"></or-translate>
            </p>
            <div class="dialog-section">
              <div class="dialog-section-title">
                <or-translate value="attributeConfiguration.willBeImported"></or-translate>
              </div>
              ${importable.map(
                (attribute) => html`
                  <div class="attribute-row">
                    <div class="attribute-row-header">
                      <span>${attributeLabel(asset, patchedAttributes[attribute.name!], attribute.name)}</span>
                      <span class="meta-count">
                        ${Object.keys(patchedAttributes[attribute.name!]?.meta || {}).length}
                      </span>
                    </div>
                    <div class="meta-names">${metaLabels(asset, patchedAttributes[attribute.name!]).join(", ")}</div>
                  </div>
                `
              )}
            </div>
            ${getSkippedTemplate(preview)}
          `
        )}
        ${dialogFooterRenderer(
          () => html`
            <or-vaadin-button theme="tertiary" @click=${() => dialog?.close()}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button
              theme="primary"
              @click=${() => {
                dialog?.close();
                onImported(patchedAttributes);
                showImportResultDialog(host, preview);
              }}
            >
              <or-icon slot="prefix" icon="upload"></or-icon>
              <or-translate value="attributeConfiguration.import"></or-translate>
            </or-vaadin-button>
          `
        )}
      ></or-vaadin-dialog>
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
          "tertiary",
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
      <div class="dialog-text">${message}</div>
      ${preview ? getSkippedTemplate(preview) : ``}`,
    "attributeConfiguration.importHeading"
  );
}

function getAssetTypeMismatchTemplate(preview: AttributeConfigurationImportPreview): TemplateResult | string {
  return when(
    preview.assetTypeMismatch,
    () => html`
      <div class="warning">
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
    ${when(
      missing.length > 0,
      () => html`
        <div class="dialog-section">
          <div class="dialog-section-title">
            <or-translate value="attributeConfiguration.skippedMissing"></or-translate>
          </div>
          <div>${missing.map((attribute) => `${attribute.name} (${attribute.type})`).join(", ")}</div>
        </div>
      `
    )}
    ${when(
      mismatches.length > 0,
      () => html`
        <div class="dialog-section">
          <div class="dialog-section-title">
            <or-translate value="attributeConfiguration.skippedTypeMismatch"></or-translate>
          </div>
          <div>
            ${mismatches
              .map((mismatch) => `${mismatch.name} (${mismatch.importedType} → ${mismatch.targetType})`)
              .join(", ")}
          </div>
        </div>
      `
    )}
  `;
}

async function downloadAttributeConfiguration(asset: Asset, attributeNames: string[]) {
  try {
    const response = await manager.rest.api.AssetResource.exportAttributeConfiguration(asset.id!, {
      attributeName: attributeNames,
    });
    const blob = new Blob([JSON.stringify(response.data, null, 2)], { type: "application/json" });
    const url = window.URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.setAttribute("download", `${asset.name}-attribute-config.json`);
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
