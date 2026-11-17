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
import { normalizeTimePresetKey } from "@openremote/core";
import { css, html, type TemplateResult } from "lit";
import { customElement, query } from "lit/decorators.js";
import { WidgetSettings } from "../util/widget-settings";
import "../panels/attributes-chart-panel";
import "../util/settings-panel";
import { i18next } from "@openremote/or-translate";
import debounce from "lodash.debounce";
import type { AttributesChartPanel } from "../panels/attributes-chart-panel";
import type {
  AttributeAction,
  AttributeActionEvent,
  AttributeReplaceEvent,
  AttributesSelectEvent,
} from "../panels/attributes-panel";
import {
  type Asset,
  type AssetDatapointIntervalQuery,
  AssetDatapointIntervalQueryFormula,
  type AssetDescriptor,
  type Attribute,
  type AttributeRef,
} from "@openremote/model";
import type { ChartWidgetConfig } from "../widgets/chart-widget";
import type { OrVaadinToggle } from "@openremote/or-vaadin-components/or-vaadin-toggle";
import { when } from "lit/directives/when.js";
import type moment from "moment/moment";
import { type ChartAttributeConfig, OrChart } from "@openremote/or-chart";
import { getAssetDescriptorIconTemplate } from "@openremote/or-icon";
import type { OrVaadinSelect, SelectItem } from "@openremote/or-vaadin-components/or-vaadin-select";
import type { OrVaadinNumberField } from "@openremote/or-vaadin-components/or-vaadin-number-field";

const styling = css`
  .switch-container {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
`;

@customElement("chart-settings")
export class ChartSettings extends WidgetSettings {
  @query("attributes-chart-panel")
  protected _attributesPanelElem?: AttributesChartPanel;

  protected readonly widgetConfig!: ChartWidgetConfig;
  protected timeWindowOptions: Map<string, [moment.unitOfTime.DurationConstructor, number]> = new Map<
    string,
    [moment.unitOfTime.DurationConstructor, number]
  >();

  protected timePrefixOptions: string[] = [];
  protected samplingOptions: Map<string, string> = new Map<string, string>();

  public setTimeWindowOptions(options: Map<string, [moment.unitOfTime.DurationConstructor, number]>) {
    this.timeWindowOptions = options;
  }

  public setTimePrefixOptions(options: string[]) {
    this.timePrefixOptions = options;
  }

  public setSamplingOptions(options: Map<string, string>) {
    this.samplingOptions = options;
  }

  static get styles() {
    return [...super.styles, styling];
  }

  protected render(): TemplateResult {
    const attributeFilter: (attr: Attribute<any>) => boolean = (attr): boolean => {
      return [
        "boolean",
        "positiveInteger",
        "positiveNumber",
        "number",
        "long",
        "integer",
        "bigInteger",
        "negativeInteger",
        "negativeNumber",
        "bigNumber",
        "integerByte",
        "direction",
      ].includes(attr.type!);
    };
    const attrConfig = this.widgetConfig.attributeConfig;
    const min = this.widgetConfig.chartOptions.options?.scales?.y?.min;
    const max = this.widgetConfig.chartOptions.options?.scales?.y?.max;
    const isMultiAxis = (attrConfig?.rightAxisAttributes?.length || 0) > 0;

    const attributeIconCallback = (asset: Asset, attribute: Attribute<any>, descriptor?: AssetDescriptor) => {
      let color = this.widgetConfig.attributeColors
        ?.find((a) => a[0].id === asset.id && a[0].name === attribute.name)?.[1]
        ?.replace("#", "");
      if (!color) {
        const index = this.widgetConfig.attributeRefs?.findIndex(
          (ref) => ref.id === asset.id && ref.name === attribute.name
        );
        if (index >= 0) color = OrChart.DEFAULT_COLORS?.[index % OrChart.DEFAULT_COLORS.length]?.replace("#", "");
      }
      return html`<span>${getAssetDescriptorIconTemplate(descriptor, undefined, undefined, color)}</span>`;
    };
    const attributeActionCallback = (attributeRef: AttributeRef): AttributeAction[] => {
      const isOnRightAxis =
        this.widgetConfig?.attributeConfig?.rightAxisAttributes?.find(
          (a) => a.id === attributeRef.id && a.name === attributeRef.name
        ) !== undefined;
      const isFaint =
        attrConfig?.faintAttributes?.find((ar) => ar.id === attributeRef.id && ar.name === attributeRef.name) !==
        undefined;
      const isSmooth =
        attrConfig?.smoothAttributes?.find((ar) => ar.id === attributeRef.id && ar.name === attributeRef.name) !==
        undefined;
      const isStepped =
        attrConfig?.steppedAttributes?.find((ar) => ar.id === attributeRef.id && ar.name === attributeRef.name) !==
        undefined;
      const isArea =
        attrConfig?.areaAttributes?.find((ar) => ar.id === attributeRef.id && ar.name === attributeRef.name) !==
        undefined;
      const isExtended =
        attrConfig?.extendedAttributes?.find((ar) => ar.id === attributeRef.id && ar.name === attributeRef.name) !==
        undefined;
      let color = this.widgetConfig?.attributeColors?.find(
        (a) => a[0].id === attributeRef.id && a[0].name === attributeRef.name
      )?.[1];
      let customColor = false;
      if (!color) {
        const index = this.widgetConfig?.attributeRefs?.findIndex(
          (ref) => ref.id === attributeRef.id && ref.name === attributeRef.name
        );
        if (index >= 0) color = OrChart.DEFAULT_COLORS?.[index % OrChart.DEFAULT_COLORS.length];
      } else {
        customColor = true;
      }
      return [
        {
          icon: "palette",
          tooltip: i18next.t("dashboard.lineColor"),
          active: customColor,
          color,
          disabled: false,
        },
        {
          icon: "chart-bell-curve-cumulative",
          tooltip: i18next.t("dashboard.smooth"),
          active: isSmooth,
          color,
          disabled: false,
        },
        {
          icon: "square-wave",
          tooltip: i18next.t("dashboard.stepped"),
          active: isStepped,
          color,
          disabled: false,
        },
        {
          icon: "chart-areaspline-variant",
          tooltip: i18next.t("dashboard.fill"),
          active: isArea,
          color,
          disabled: false,
        },
        {
          icon: "arrange-send-backward",
          tooltip: i18next.t("dashboard.faint"),
          active: isFaint,
          color,
          disabled: false,
        },
        {
          icon: "arrow-expand-right",
          tooltip: i18next.t("dashboard.extendData"),
          active: isExtended,
          color,
          disabled: false,
        },
        {
          icon: isOnRightAxis ? "arrow-right-bold" : "arrow-left-bold",
          tooltip: i18next.t("dashboard.toggleAxis"),
          active: isOnRightAxis,
          color,
          disabled: false,
        },
      ];
    };
    // Determine the selected timeframe
    const timePresetKey = normalizeTimePresetKey(this.widgetConfig?.defaultTimePresetKey);
    const timePrefix =
      this.timePrefixOptions.find((prefix) => timePresetKey.startsWith(prefix)) ?? this.timePrefixOptions[0];
    let [_, timeWindow] = timePresetKey.split(timePrefix);
    if (!timeWindow || !this.timeWindowOptions.has(timeWindow))
      timeWindow = Array.from(this.timeWindowOptions.keys())[0];

    // List the available timeframes to select from
    const prefixOptions: SelectItem[] = this.timePrefixOptions.map((s) => ({
      value: s,
      label: i18next.t(s.toLowerCase()),
    }));
    const windowOptions: SelectItem[] = Array.from(this.timeWindowOptions.keys()).map((s) => ({
      value: s,
      label: i18next.t(s.toLowerCase()),
    }));
    return html`
      <div>
        <!-- Attribute selection -->
        <settings-panel displayName="attributes" expanded>
          <attributes-chart-panel
            .attributeRefs="${this.widgetConfig.attributeRefs}"
            multi
            onlyDataAttrs
            includePredictedDataAttrs
            .attributeFilter="${attributeFilter}"
            style="padding-bottom: 12px;"
            .attributeIconCallback="${attributeIconCallback}"
            .attributeActionCallback="${attributeActionCallback}"
            @attribute-action="${(ev: AttributeActionEvent) => this.onAttributeAction(ev)}"
            @attribute-replace="${(ev: AttributeReplaceEvent) => this.onAttributeReplace(ev)}"
            @attribute-select="${(ev: AttributesSelectEvent) => this.onAttributesSelect(ev)}"
          ></attributes-chart-panel>
        </settings-panel>

        <!-- Time options -->
        <settings-panel displayName="time" expanded>
          <div style="padding-bottom: 12px; display: flex; flex-direction: column; gap: 6px;">
            <!-- This/last selection of timeframe -->
            <or-vaadin-select
              .items=${prefixOptions}
              value=${timePrefix}
              @change=${(ev: Event) => this.onTimePrefixSelect(ev)}
            >
              <or-translate slot="label" value="prefixDefault"></or-translate>
            </or-vaadin-select>
            <!-- Select time frame -->
            <or-vaadin-select
              .items=${windowOptions}
              .value=${timeWindow}
              @change=${(ev: Event) => this.onTimeWindowSelect(ev)}
            >
              <or-translate slot="label" value="timeframeDefault"></or-translate>
            </or-vaadin-select>
          </div>
        </settings-panel>
        <!-- Display options -->
        <settings-panel displayName="display" expanded>
          <div style="padding-bottom: 12px; display: flex; flex-direction: column; gap: 6px;">
            <div class="switch-container">
              <span><or-translate value="dashboard.toggleStackView"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${this.widgetConfig.stacked}"
                @change="${(ev: Event) => this.onStackViewToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
            <div class="switch-container">
              <span><or-translate value="dashboard.allowTimerangeSelect"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${!this.widgetConfig.showTimestampControls}"
                @change="${(ev: Event) => this.onTimestampControlsToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
            <div class="switch-container">
              <span><or-translate value="dashboard.showLegend"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${this.widgetConfig.showLegend}"
                @change="${(ev: Event) => this.onShowLegendToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
            <!-- Datazoombar -->
            <div class="switch-container">
              <span><or-translate value="dashboard.showZoomBar"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${this.widgetConfig.showZoomBar}"
                @change="${(ev: Event) => this.onShowZoomBarToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
          </div>
        </settings-panel>

        <!-- Axis configuration -->
        <settings-panel displayName="dashboard.axisConfig" expanded>
          <div style="padding-bottom: 12px; display: flex; flex-direction: column; gap: 16px;">
            <!-- Left axis configuration -->
            <div>
              ${when(
                isMultiAxis,
                () => html`
                  <div style="margin-bottom: 8px;">
                    <span><or-translate value="dashboard.leftAxis"></or-translate></span>
                  </div>
                `
              )}
              <div style="display: flex; align-items: baseline; gap: var(--lumo-space-s);">
                ${
                  max !== undefined
                    ? html`
                        <or-vaadin-number-field
                          value=${max}
                          @change=${(ev: Event) => this.onMinMaxValueChange("left", "max", ev)}
                        >
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                          ></span>
                        </or-vaadin-number-field>
                      `
                    : html`
                        <or-vaadin-text-field value="auto" readonly>
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                          ></span>
                        </or-vaadin-text-field>
                      `
                }
                <or-vaadin-toggle
                  ?checked="${max !== undefined}"
                  @change="${(ev: Event) => this.onMinMaxValueToggle("left", "max", ev)}"
                ></or-vaadin-toggle>
              </div>
              <div style="display: flex; margin-top: 12px; align-items: baseline; gap: var(--lumo-space-s);">
                ${
                  min !== undefined
                    ? html`
                        <or-vaadin-number-field
                          value=${min}
                          @change=${(ev: Event) => this.onMinMaxValueChange("left", "min", ev)}
                        >
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                          ></span>
                        </or-vaadin-number-field>
                      `
                    : html`
                        <or-vaadin-text-field value="auto" readonly>
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                          ></span>
                        </or-vaadin-text-field>
                      `
                }
                <or-vaadin-toggle
                  ?checked="${min !== undefined}"
                  @change="${(ev: Event) => this.onMinMaxValueToggle("left", "min", ev)}"
                ></or-vaadin-toggle>
              </div>
            </div>

            <!-- Right axis configuration -->
            ${when(isMultiAxis, () => {
              const rightMin = this.widgetConfig.chartOptions.options?.scales?.y1?.min;
              const rightMax = this.widgetConfig.chartOptions.options?.scales?.y1?.max;
              return html`
                <div>
                  <div style="margin-bottom: 8px;">
                    <span><or-translate value="dashboard.rightAxis"></or-translate></span>
                  </div>
                  <div style="display: flex;">
                    ${
                      rightMax !== undefined
                        ? html`
                            <or-vaadin-number-field
                              value=${rightMax}
                              @change=${(ev: Event) => this.onMinMaxValueChange("right", "max", ev)}
                            >
                              <span slot="label"
                                ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                              ></span>
                            </or-vaadin-number-field>
                          `
                        : html`
                            <or-vaadin-text-field value="auto" readonly>
                              <span slot="label"
                                ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                              ></span>
                            </or-vaadin-text-field>
                          `
                    }
                    <or-vaadin-toggle
                      ?checked="${rightMax !== undefined}"
                      @change="${(ev: Event) => this.onMinMaxValueToggle("right", "max", ev)}"
                    ></or-vaadin-toggle>
                  </div>
                  <div style="display: flex; margin-top: 12px; align-items: baseline;">
                    ${
                      rightMin !== undefined
                        ? html`
                            <or-vaadin-number-field
                              value=${rightMin}
                              @change=${(ev: Event) => this.onMinMaxValueChange("right", "min", ev)}
                            >
                              <span slot="label"
                                ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                              ></span>
                            </or-vaadin-number-field>
                          `
                        : html`
                            <or-vaadin-text-field value="auto" readonly>
                              <span slot="label"
                                ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                              ></span>
                            </or-vaadin-text-field>
                          `
                    }
                    <or-vaadin-toggle
                      ?checked="${rightMin !== undefined}"
                      @change="${(ev: Event) => this.onMinMaxValueToggle("right", "min", ev)}"
                    ></or-vaadin-toggle>
                  </div>
                </div>
              `;
            })}
          </div>
        </settings-panel>

        <!-- Data sampling options -->
        ${when(Array.from(this.samplingOptions?.keys()).length > 1, () => {
          const samplingOptions: SelectItem[] = Array.from(this.samplingOptions.entries()).map((o) => ({
            value: o[0],
            label: i18next.t(o[1]),
          }));
          const samplingValue = this.widgetConfig.datapointQuery.type;
          return html`
            <settings-panel displayName="dataSampling" expanded="${false}">
              <div style="padding-bottom: 12px; display: flex; flex-direction: column; gap: 12px;">
                <div>
                  <or-vaadin-select
                    .items=${samplingOptions}
                    value=${samplingValue}
                    @change=${(ev: Event) => this.onSamplingQueryChange(ev)}
                  >
                    <or-translate slot="label" value="algorithm"></or-translate>
                  </or-vaadin-select>
                </div>
                <div>${this.getSamplingOptionsTemplate(this.widgetConfig.datapointQuery.type)}</div>
              </div>
            </settings-panel>
          `;
        })}
      </div>
    `;
  }

  protected getSamplingOptionsTemplate(type: string): TemplateResult {
    switch (type) {
      case "interval": {
        const intervalQuery = this.widgetConfig.datapointQuery as AssetDatapointIntervalQuery;
        const formulaOptions: SelectItem[] = [
          AssetDatapointIntervalQueryFormula.AVG,
          AssetDatapointIntervalQueryFormula.MIN,
          AssetDatapointIntervalQueryFormula.MAX,
        ].map((o) => ({ value: o, label: i18next.t(o) }));
        return html`
          <or-vaadin-select
            .items=${formulaOptions}
            value=${intervalQuery.formula}
            required
            @change=${(ev: Event) => {
              intervalQuery.formula = (ev.currentTarget as OrVaadinSelect).value as any;
              this.notifyConfigUpdate();
            }}
          >
            <or-translate slot="label" value="algorithmMethod"></or-translate>
          </or-vaadin-select>
        `;
      }
      default:
        return html``;
    }
  }

  // Check which icon was pressed and act accordingly.
  protected onAttributeAction(ev: AttributeActionEvent) {
    const { attributeRef, action } = ev.detail;
    switch (action.icon) {
      case "palette":
        this.openColorPickDialog(attributeRef);
        break;
      case "arrow-right-bold":
      case "arrow-left-bold":
        this._toggleAttributeSetting("rightAxisAttributes", attributeRef);
        break;
      case "chart-bell-curve-cumulative":
        if (!action.active) {
          this._removeAttributeSetting("steppedAttributes", attributeRef);
        }
        this._toggleAttributeSetting("smoothAttributes", attributeRef);
        break;
      case "square-wave":
        if (!action.active) {
          this._removeAttributeSetting("smoothAttributes", attributeRef);
        }
        this._toggleAttributeSetting("steppedAttributes", attributeRef);
        break;
      case "chart-areaspline-variant":
        this._toggleAttributeSetting("areaAttributes", attributeRef);
        break;
      case "arrange-send-backward":
        this._toggleAttributeSetting("faintAttributes", attributeRef);
        break;
      case "arrow-expand-right":
        this._toggleAttributeSetting("extendedAttributes", attributeRef);
        break;
      default:
        console.warn("Unknown attribute panel action:", action);
    }
  }

  // When the list of attributeRefs is changed by the asset selector,
  // we should remove the settings references for the attributes that got removed.
  // Also update the WidgetConfig attributeRefs field as usual
  protected onAttributesSelect(ev: AttributesSelectEvent) {
    const removedAttributeRefs = this.widgetConfig.attributeRefs.filter(
      (ref) => !ev.detail.attributeRefs.some((r) => r.id === ref.id && r.name === ref.name)
    );

    removedAttributeRefs.forEach((raf) => {
      this.removeFromAttributeConfig(raf);
      this.removeFromAttributeColors(raf);
    });

    this.widgetConfig.attributeRefs = ev.detail.attributeRefs;
    this.notifyConfigUpdate();
  }

  /**
   * Handles an in-place replacement of an {@link AttributeRef}. All per-attribute configuration
   * (axis assignment, smooth/stepped/area/faint/extended flags and custom color) is migrated from
   * the old reference to the new one, so the new attribute inherits the full configuration of the
   * one it replaces.
   */
  protected onAttributeReplace(ev: AttributeReplaceEvent) {
    const { oldRef, newRef } = ev.detail;
    const matches = (ar: AttributeRef) => ar.id === oldRef.id && ar.name === oldRef.name;

    // Migrate the attribute config categories (rightAxis, smooth, stepped, area, faint, extended, ...)
    const config = this.widgetConfig.attributeConfig;
    if (config) {
      (Object.keys(config) as (keyof typeof config)[]).forEach((key) => {
        config[key] = config[key]?.map((ar: AttributeRef) => (matches(ar) ? { id: newRef.id, name: newRef.name } : ar));
      });
    }

    // Migrate the custom color, if any
    this.widgetConfig.attributeColors = (this.widgetConfig.attributeColors ?? []).map((entry) =>
      matches(entry[0]) ? [{ id: newRef.id, name: newRef.name }, entry[1]] : entry
    );

    // Swap the reference in place, keeping its position in the list
    const index = this.widgetConfig.attributeRefs.findIndex(matches);
    if (index >= 0) {
      this.widgetConfig.attributeRefs[index] = { id: newRef.id, name: newRef.name };
    }
    this.notifyConfigUpdate();
  }

  protected removeFromAttributeConfig(attributeRef: AttributeRef) {
    const config = this.widgetConfig.attributeConfig;
    if (config) {
      (Object.keys(config) as (keyof typeof config)[]).forEach((key) => {
        config[key] = config[key]?.filter(
          (ar: AttributeRef) => ar.id !== attributeRef.id || ar.name !== attributeRef.name
        );
      });
    }
  }

  /**
   * Internal function that removes/adds an {@link AttributeRef} to a category of the {@link AttributeConfig}.
   * For example, you can add an attribute to the array of 'rightAxisAttributes'.
   * If the attribute is already present, it will be removed automatically.
   *
   * @param setting The key / category to add the attribute to. (for example, 'rightAxisAttributes')
   * @param attributeRef The asset-attribute combination to add/remove.
   * @protected
   */
  protected _toggleAttributeSetting(setting: keyof ChartAttributeConfig, attributeRef: AttributeRef) {
    const attributes = this.widgetConfig.attributeConfig?.[setting];
    const index = attributes?.findIndex(
      (item: AttributeRef) => item.id === attributeRef.id && item.name === attributeRef.name
    );
    if (index == null || index < 0) {
      this._addAttributeSetting(setting, attributeRef);
    } else {
      this._removeAttributeSetting(setting, attributeRef);
    }
    this.notifyConfigUpdate();
  }

  /**
   * Internal function that adds an {@link AttributeRef} to a category of the {@link AttributeConfig}.
   * For example, you can add an attribute to the array of 'rightAxisAttributes'.
   * @param setting The key / category to add the attribute to. (for example, 'rightAxisAttributes')
   * @param attributeRef The asset-attribute combination to add.
   * @protected
   */
  protected _addAttributeSetting(setting: keyof ChartAttributeConfig, attributeRef: AttributeRef) {
    this.widgetConfig.attributeConfig ??= {};
    this.widgetConfig.attributeConfig[setting] ??= [];
    const attributes = this.widgetConfig.attributeConfig[setting]!;
    const exists = !!attributes.find(
      (item: AttributeRef) => item.id === attributeRef.id && item.name === attributeRef.name
    );
    if (!exists) {
      console.debug(`Adding attribute ${attributeRef.name} to setting ${setting} in widget config.`);
      attributes.push(attributeRef);
    } else {
      console.warn(
        `Could not add attribute ${attributeRef.name}; attribute already exists in attribute setting ${setting}.`,
        attributes
      );
    }
  }

  /**
   * Internal function that removes an {@link AttributeRef} from a category of the {@link AttributeConfig}.
   * For example, you can remove an attribute from the array of 'rightAxisAttributes'.
   * @param setting The key / category to remove the attribute from. (for example, 'rightAxisAttributes')
   * @param attributeRef The asset-attribute combination to remove.
   * @protected
   */
  protected _removeAttributeSetting(setting: keyof ChartAttributeConfig, attributeRef: AttributeRef) {
    const attributes = this.widgetConfig.attributeConfig?.[setting];
    if (!attributes) {
      console.warn(`Could not remove attribute; attribute setting ${setting} not found in widget config.`, attributes);
      return;
    }
    // Check if attribute exists in the configuration for this setting
    const index = attributes?.findIndex(
      (item: AttributeRef) => item.id === attributeRef.id && item.name === attributeRef.name
    );
    if (index == null || index < 0) {
      console.warn(
        `Could not remove attribute; attribute ${attributeRef.name} not found in attribute setting ${setting}.`,
        attributes
      );
      return;
    }
    // Remove attribute
    console.debug(`Removing attribute ${attributeRef.name} from setting ${setting} in widget config.`);
    if (attributes.length === 1) {
      delete this.widgetConfig.attributeConfig![setting];
    } else {
      attributes.splice(index, 1);
    }
  }

  protected removeFromAttributeColors(attributeRef: AttributeRef) {
    this.widgetConfig.attributeColors = this.widgetConfig.attributeColors?.filter(
      ([ref, _]) => ref.id !== attributeRef.id || ref.name !== attributeRef.name
    );
  }

  protected openColorPickDialog(attributeRef: AttributeRef) {
    const inputElem = this._attributesPanelElem?.shadowRoot?.querySelector(
      `#chart-color-${attributeRef.id}-${attributeRef.name}`
    ) as HTMLInputElement | undefined;
    if (inputElem) {
      // Listen for changes
      inputElem.addEventListener(
        "input",
        debounce(() => {
          const color = inputElem.value;
          this.widgetConfig.attributeColors ??= [];
          const existingColor = this.widgetConfig.attributeColors.find(
            (x) => x[0].id === attributeRef.id && x[0].name === attributeRef.name
          );
          if (existingColor) {
            existingColor[1] = color;
          } else {
            this.widgetConfig.attributeColors.push([attributeRef, color]);
          }
          this.widgetConfig.attributeColors = [...this.widgetConfig.attributeColors];
          this.notifyConfigUpdate();
        }, 200)
      );

      // Open color picker
      inputElem.click();
    }
  }

  protected onTimePrefixSelect(ev: Event) {
    let oldValue = normalizeTimePresetKey(this.widgetConfig.defaultTimePresetKey);
    const newPrefix = (ev.currentTarget as OrVaadinSelect).value;
    const prefixOptions = this.timePrefixOptions;
    if (!prefixOptions.find((o) => oldValue.includes(o))) {
      oldValue = Array.from(this.timeWindowOptions.keys())[0]; // Old value was invalid, so we replace it in full
    } else {
      prefixOptions.forEach((option) => (oldValue = oldValue.replace(option, "")));
    }
    this.widgetConfig.defaultTimePresetKey = newPrefix + oldValue;
    console.debug(`Updated default time preset to ${this.widgetConfig.defaultTimePresetKey}`);
    this.notifyConfigUpdate();
  }

  protected onTimeWindowSelect(ev: Event) {
    let oldValue = normalizeTimePresetKey(this.widgetConfig.defaultTimePresetKey);
    const newWindow = (ev.currentTarget as OrVaadinSelect).value;
    const windowOptions = Array.from(this.timeWindowOptions.keys()).sort((a, b) => b.length - a.length);
    if (!windowOptions.find((o) => oldValue.includes(o))) {
      oldValue = this.timePrefixOptions[0]; // Old value was invalid, so we replace it in full
    } else {
      windowOptions.forEach((window) => (oldValue = oldValue.replace(window, "")));
    }
    this.widgetConfig.defaultTimePresetKey = oldValue + newWindow;
    console.debug(`Updated default time preset to ${this.widgetConfig.defaultTimePresetKey}`);
    this.notifyConfigUpdate();
  }

  protected onStackViewToggle(ev: Event) {
    this.widgetConfig.stacked = (ev.currentTarget as OrVaadinToggle).checked;
    this.notifyConfigUpdate();
  }

  protected onTimestampControlsToggle(ev: Event) {
    this.widgetConfig.showTimestampControls = !(ev.currentTarget as OrVaadinToggle).checked;
    this.notifyConfigUpdate();
  }

  protected onShowLegendToggle(ev: Event) {
    this.widgetConfig.showLegend = (ev.currentTarget as OrVaadinToggle).checked;
    this.notifyConfigUpdate();
  }

  protected onShowZoomBarToggle(ev: Event) {
    this.widgetConfig.showZoomBar = (ev.currentTarget as OrVaadinToggle).checked;
    this.notifyConfigUpdate();
  }

  protected setAxisMinMaxValue(axis: "left" | "right", type: "min" | "max", value?: number) {
    if (axis === "left") {
      if (type === "min") {
        this.widgetConfig.chartOptions.options.scales.y.min = value;
      } else {
        this.widgetConfig.chartOptions.options.scales.y.max = value;
      }
    } else {
      if (type === "min") {
        this.widgetConfig.chartOptions.options.scales.y1.min = value;
      } else {
        this.widgetConfig.chartOptions.options.scales.y1.max = value;
      }
    }
    this.notifyConfigUpdate();
  }

  protected onMinMaxValueChange(axis: "left" | "right", type: "min" | "max", ev: Event) {
    const elem = ev.currentTarget as OrVaadinNumberField;
    if (elem.checkValidity()) {
      this.setAxisMinMaxValue(axis, type, Number(elem.value));
    }
  }

  protected onMinMaxValueToggle(axis: "left" | "right", type: "min" | "max", ev: Event) {
    this.setAxisMinMaxValue(
      axis,
      type,
      (ev.currentTarget as OrVaadinToggle).checked ? (type === "min" ? 0 : 100) : undefined
    );
  }

  protected onSamplingQueryChange(ev: Event) {
    const elem = ev.currentTarget as OrVaadinSelect;
    this.widgetConfig.datapointQuery.type = elem.value as any;
    this.notifyConfigUpdate();
  }
}
