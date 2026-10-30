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
import { css, html, type TemplateResult } from "lit";
import { customElement, query } from "lit/decorators.js";
import { WidgetSettings } from "../util/widget-settings";
import "../panels/attributes-chart-panel";
import "../util/settings-panel";
import { i18next } from "@openremote/or-translate";
import type {
  AttributeAction,
  AttributeActionEvent,
  AttributeReplaceEvent,
  AttributesSelectEvent,
} from "../panels/attributes-panel";
import type { Asset, AssetDescriptor, Attribute, AttributeRef } from "@openremote/model";
import type { BarChartWidgetConfig } from "../widgets/barchart-widget";
import type { OrVaadinToggle } from "@openremote/or-vaadin-components/or-vaadin-toggle";
import { when } from "lit/directives/when.js";
import type moment from "moment/moment";
import type { OrVaadinSelect, SelectItem } from "@openremote/or-vaadin-components/or-vaadin-select";
import { type OrVaadinDialog, showDialog } from "@openremote/or-vaadin-components/or-vaadin-dialog";
import {
  type BarChartAttributeConfig,
  type BarChartInterval,
  type IntervalConfig,
  OrAttributeBarChart,
} from "@openremote/or-attribute-barchart";
import { OrChart } from "@openremote/or-chart";
import { getAssetDescriptorIconTemplate } from "@openremote/or-icon";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import type { AttributesChartPanel } from "../panels/attributes-chart-panel";
import debounce from "lodash.debounce";
import type { OrVaadinNumberField } from "@openremote/or-vaadin-components/or-vaadin-number-field";
import type { OrVaadinCheckboxGroup } from "@openremote/or-vaadin-components/or-vaadin-checkbox-group";

const styling = css`
  .switch-container {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
`;

@customElement("barchart-settings")
export class BarChartSettings extends WidgetSettings {
  @query("attributes-chart-panel")
  protected _attributesPanelElem?: AttributesChartPanel;

  protected readonly widgetConfig!: BarChartWidgetConfig;

  protected timeWindowOptions: Map<string, [moment.unitOfTime.DurationConstructor, number]> = new Map<
    string,
    [moment.unitOfTime.DurationConstructor, number]
  >();

  protected timePrefixOptions: string[] = [];
  protected intervalOptions: Map<BarChartInterval, IntervalConfig> = new Map<BarChartInterval, IntervalConfig>();

  public setTimeWindowOptions(options: Map<string, [moment.unitOfTime.DurationConstructor, number]>) {
    this.timeWindowOptions = options;
  }

  public setTimePrefixOptions(options: string[]) {
    this.timePrefixOptions = options;
  }

  public setIntervalOptions(options: Map<BarChartInterval, IntervalConfig>) {
    this.intervalOptions = options;
  }

  static get styles() {
    return [...super.styles, styling];
  }

  protected render(): TemplateResult {
    const attributeFilter: (attr: Attribute<any>) => boolean = (attr): boolean =>
      [
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

    const attrSettings = this.widgetConfig.attributeSettings;
    const min = this.widgetConfig.chartOptions.options?.scales?.y?.min;
    const max = this.widgetConfig.chartOptions.options?.scales?.y?.max;
    const isMultiAxis = !!attrSettings.rightAxisAttributes?.length;

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
      const faint = !!this.widgetConfig?.attributeSettings?.faintAttributes?.find(
        (ref) => ref.id === attributeRef.id && ref.name === attributeRef.name
      );
      let color = this.widgetConfig?.attributeColors?.find(
        ([ref, _]) => ref.id === attributeRef.id && ref.name === attributeRef.name
      )?.[1];
      let customColor = false;
      if (!color) {
        const index = this.widgetConfig?.attributeRefs?.findIndex(
          (ref) => ref.id === attributeRef.id && ref.name === attributeRef.name
        );
        if (index >= 0) color = OrAttributeBarChart.DEFAULT_COLORS[index % OrAttributeBarChart.DEFAULT_COLORS.length];
      } else {
        customColor = true;
      }
      return [
        {
          icon: "arrow-up-circle",
          tooltip: "Order up",
          active: false,
          disabled: this.widgetConfig.attributeRefs.indexOf(attributeRef) === 0,
        },
        {
          icon: "arrow-down-circle",
          tooltip: "Order down",
          active: false,
          disabled:
            this.widgetConfig.attributeRefs.indexOf(attributeRef) === this.widgetConfig.attributeRefs.length - 1,
        },
        {
          icon: "calculator-variant-outline",
          tooltip: i18next.t("algorithmMethod"),
          active: true,
          color,
          disabled: false,
        },
        {
          icon: "palette",
          tooltip: i18next.t("dashboard.barColor"),
          active: customColor,
          color,
          disabled: false,
        },
        {
          icon: "arrange-send-backward",
          tooltip: i18next.t("dashboard.faint"),
          active: faint,
          color,
          disabled: false,
        },
        {
          icon: this.widgetConfig.attributeSettings.rightAxisAttributes?.includes(attributeRef)
            ? "arrow-right-bold"
            : "arrow-left-bold",
          tooltip: i18next.t("dashboard.toggleAxis"),
          active: false,
          color,
          disabled: false,
        },
      ];
    };
    const timePrefixOpts: SelectItem[] = this.timePrefixOptions.map((o) => ({ value: o, label: i18next.t(o) }));
    const timeWindowOpts: SelectItem[] = Array.from(this.timeWindowOptions.keys()).map((o) => ({
      value: o,
      label: i18next.t(o.toLowerCase()),
    }));
    const intervalOpts: SelectItem[] = Array.from(this.intervalOptions.keys()).map((o) => ({
      value: o,
      label: i18next.t(`intervalBy${o.toLowerCase()}`),
    }));
    return html`
      <div>
        <!-- Attribute selection -->
        <settings-panel displayName="attributes" expanded="${true}">
          <attributes-chart-panel
            .attributeRefs="${this.widgetConfig.attributeRefs}"
            multi
            onlyDataAttrs
            .attributeFilter="${attributeFilter}"
            style="padding-bottom: 12px;"
            .attributeIconCallback=${attributeIconCallback}
            .attributeActionCallback="${attributeActionCallback}"
            @attribute-action="${(ev: AttributeActionEvent) => this.onAttributeAction(ev)}"
            @attribute-replace="${(ev: AttributeReplaceEvent) => this.onAttributeReplace(ev)}"
            @attribute-select="${(ev: AttributesSelectEvent) => this.onAttributesSelect(ev)}"
          ></attributes-chart-panel>
        </settings-panel>

        <!-- Time options -->
        <settings-panel displayName="time" expanded="${true}">
          <div style="padding-bottom: 12px; display: flex; flex-direction: column; gap: 6px;">
            <!-- Timeframe & interval -->
            <or-vaadin-select
              .items=${timePrefixOpts}
              value=${this.widgetConfig.defaultTimePrefixKey}
              @change=${(ev: Event) => this.onTimePrefixSelect(ev)}
            >
              <or-translate slot="label" value="prefixDefault"></or-translate>
            </or-vaadin-select>
            <or-vaadin-select
              .items=${timeWindowOpts}
              value=${this.widgetConfig.defaultTimeWindowKey}
              @change=${(ev: Event) => this.onTimeWindowSelect(ev)}
            >
              <or-translate slot="label" value="timeframeDefault"></or-translate>
            </or-vaadin-select>
            <or-vaadin-select
              .items=${intervalOpts}
              value=${this.widgetConfig.defaultInterval}
              @change=${(ev: Event) => this.onIntervalSelect(ev)}
            >
              <or-translate slot="label" value="withInterval"></or-translate>
            </or-vaadin-select>
          </div>
        </settings-panel>

        <!-- Display options -->
        <settings-panel displayName="display" expanded="${false}">
          <div style="padding-bottom: 12px; display: flex; flex-direction: column; gap: 16px;">
            <!-- Stacked -->
            <div class="switch-container">
              <span><or-translate value="dashboard.toggleStackView"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${this.widgetConfig.stacked}"
                @change="${(ev: Event) => this.onDefaultStackedToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
            <!-- Time range selection -->
            <div class="switch-container">
              <span><or-translate value="dashboard.allowTimerangeSelect"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${!this.widgetConfig.showTimestampControls}"
                @change="${(ev: Event) => this.onTimestampControlsToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
            <!-- Show legend -->
            <div class="switch-container">
              <span><or-translate value="dashboard.showLegend"></or-translate></span>
              <or-vaadin-toggle
                ?checked="${this.widgetConfig.showLegend}"
                @change="${(ev: Event) => this.onShowLegendToggle(ev)}"
              ></or-vaadin-toggle>
            </div>
            <!-- Decimal places -->
            <div>
              <or-vaadin-number-field
                min="0"
                value=${this.widgetConfig.decimals}
                @change=${(ev: Event) => this.onDecimalsChange(ev)}
              >
                <or-translate slot="label" value="decimals"></or-translate>
              </or-vaadin-number-field>
            </div>
          </div>
        </settings-panel>

        <!-- Axis configuration -->
        <settings-panel displayName="dashboard.axisConfig" expanded="${false}">
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
                ${when(
                  max !== undefined,
                  () => html`
                    <or-vaadin-number-field
                      value=${max}
                      @change=${(ev: Event) => this.onMinMaxValueChange("left", "max", ev)}
                    >
                      <span slot="label"
                        ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                      ></span>
                    </or-vaadin-number-field>
                  `,
                  () => html`
                    <or-vaadin-text-field value="auto" readonly>
                      <span slot="label"
                        ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                      ></span>
                    </or-vaadin-text-field>
                  `
                )}
                <or-vaadin-toggle
                  ?checked="${max !== undefined}"
                  @change="${(ev: Event) => this.onMinMaxValueToggle("left", "max", ev)}"
                ></or-vaadin-toggle>
              </div>
              <div style="display: flex; align-items: baseline; margin-top: 12px; gap: var(--lumo-space-s);">
                ${when(
                  min !== undefined,
                  () => html`
                    <or-vaadin-number-field
                      value=${min}
                      @change=${(ev: Event) => this.onMinMaxValueChange("left", "min", ev)}
                    >
                      <span slot="label"
                        ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                      ></span>
                    </or-vaadin-number-field>
                  `,
                  () => html`
                    <or-vaadin-text-field value="auto" readonly>
                      <span slot="label"
                        ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                      ></span>
                    </or-vaadin-text-field>
                  `
                )}
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
                  <div style="display: flex; align-items: baseline;">
                    ${when(
                      rightMax !== undefined,
                      () => html`
                        <or-vaadin-number-field
                          value=${rightMax}
                          @change=${(ev: Event) => this.onMinMaxValueChange("right", "max", ev)}
                        >
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                          ></span>
                        </or-vaadin-number-field>
                      `,
                      () => html`
                        <or-vaadin-text-field value="auto" readonly>
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="max"></or-translate
                          ></span>
                        </or-vaadin-text-field>
                      `
                    )}
                    <or-vaadin-toggle
                      ?checked="${rightMax !== undefined}"
                      @change="${(ev: Event) => this.onMinMaxValueToggle("right", "max", ev)}"
                    ></or-vaadin-toggle>
                  </div>
                  <div style="display: flex; margin-top: 12px; align-items: baseline;">
                    ${when(
                      rightMin !== undefined,
                      () => html`
                        <or-vaadin-number-field
                          value=${rightMin}
                          @change=${(ev: Event) => this.onMinMaxValueChange("right", "min", ev)}
                        >
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                          ></span>
                        </or-vaadin-number-field>
                      `,
                      () => html`
                        <or-vaadin-text-field value="auto" readonly>
                          <span slot="label"
                            ><or-translate value="yAxis"></or-translate> <or-translate value="min"></or-translate
                          ></span>
                        </or-vaadin-text-field>
                      `
                    )}
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
      </div>
    `;
  }

  // Check which icon was pressed and act accordingly.
  protected onAttributeAction(ev: AttributeActionEvent) {
    const { attributeRef, action } = ev.detail;
    switch (action.icon) {
      case "palette": // Change color
        this.openColorPickDialog(attributeRef);
        break;
      case "arrow-right-bold":
      case "arrow-left-bold":
        this.toggleAttributeSetting("rightAxisAttributes", attributeRef);
        break;
      case "calculator-variant-outline":
        this.openAlgorithmMethodsDialog(attributeRef);
        break;
      case "arrange-send-backward":
        this.onFaintClick(attributeRef);
        break;
      case "arrow-up-circle":
        this.onArrowUpClick(attributeRef);
        break;
      case "arrow-down-circle":
        this.onArrowDownClick(attributeRef);
        break;
      default:
        console.warn("Unknown attribute panel action:", action);
    }
  }

  protected onFaintClick(attrRef: AttributeRef) {
    this.widgetConfig.attributeSettings.faintAttributes ??= [];
    const index = this.widgetConfig.attributeSettings.faintAttributes.findIndex(
      (attr) => attr.id === attrRef.id && attr.name === attrRef.name
    );
    if (index >= 0) {
      this.widgetConfig.attributeSettings.faintAttributes = this.widgetConfig.attributeSettings.faintAttributes.filter(
        (attr) => attr.id !== attrRef.id && attr.name !== attrRef.name
      );
    } else {
      this.widgetConfig.attributeSettings.faintAttributes.push(attrRef);
    }
    this.notifyConfigUpdate();
  }

  protected onArrowUpClick(attrRef: AttributeRef) {
    const index = this.widgetConfig.attributeRefs.indexOf(attrRef);
    if (index > 0) {
      const temp = this.widgetConfig.attributeRefs[index - 1];
      this.widgetConfig.attributeRefs[index - 1] = attrRef;
      this.widgetConfig.attributeRefs[index] = temp;
    }
    this.notifyConfigUpdate();
  }

  protected onArrowDownClick(attrRef: AttributeRef) {
    const index = this.widgetConfig.attributeRefs.indexOf(attrRef);
    if (index < this.widgetConfig.attributeRefs.length - 1) {
      const temp = this.widgetConfig.attributeRefs[index + 1];
      this.widgetConfig.attributeRefs[index + 1] = attrRef;
      this.widgetConfig.attributeRefs[index] = temp;
    }
    this.notifyConfigUpdate();
  }

  // When the list of attributeRefs is changed by the asset selector,
  // we should remove the settings references for the attributes that got removed.
  // Also update the WidgetConfig attributeRefs field as usual
  protected onAttributesSelect(ev: AttributesSelectEvent) {
    const removedAttributeRefs = this.widgetConfig.attributeRefs.filter(
      (ar) => !ev.detail.attributeRefs.find((newRefs) => newRefs.id === ar.id && newRefs.name === ar.name)
    );
    removedAttributeRefs.forEach((ref) => {
      this.removeFromAttributeSettings(ref);
      this.removeFromAttributeColors(ref);
    });
    this.widgetConfig.attributeRefs = ev.detail.attributeRefs;

    // Add colors
    this.widgetConfig.attributeColors ??= [];
    ev.detail.attributeRefs.forEach((attrRef, index) => {
      const color = this.widgetConfig.attributeColors.find((x) => x[0].id === attrRef.id && x[0].name === attrRef.name);
      if (!color) {
        const newColor = OrAttributeBarChart.DEFAULT_COLORS[index] ?? "#000000";
        this.widgetConfig.attributeColors.push([attrRef, newColor]);
      }
    });

    // Loop through attribute methods (MIN, AVG, MAX etc), and place attributes in 'AVG' if not in any yet.
    const settings = this.widgetConfig.attributeSettings;
    const methodRefsList = [
      ...(settings.methodAvgAttributes ?? []),
      ...(settings.methodMinAttributes ?? []),
      ...(settings.methodMaxAttributes ?? []),
      ...(settings.methodDifferenceAttributes ?? []),
      ...(settings.methodMedianAttributes ?? []),
      ...(settings.methodModeAttributes ?? []),
      ...(settings.methodSumAttributes ?? []),
      ...(settings.methodCountAttributes ?? []),
    ];
    this.widgetConfig.attributeRefs.forEach((ref) => {
      if (!methodRefsList.find((methodRef) => methodRef.id === ref.id && methodRef.name === ref.name)) {
        this.widgetConfig.attributeSettings.methodAvgAttributes ??= [];
        this.widgetConfig.attributeSettings.methodAvgAttributes.push(ref);
      }
    });
    this.notifyConfigUpdate();
  }

  /**
   * Handles an in-place replacement of an {@link AttributeRef}. All per-attribute configuration
   * (aggregation method, alignment, custom color, ...) is migrated from the old reference to the
   * new one, so the new attribute inherits the full configuration of the one it replaces.
   */
  protected onAttributeReplace(ev: AttributeReplaceEvent) {
    const { oldRef, newRef } = ev.detail;
    const matches = (ar: AttributeRef) => ar.id === oldRef.id && ar.name === oldRef.name;

    // Migrate the attribute settings categories (aggregation methods, alignment, ...)
    const settings = this.widgetConfig.attributeSettings;
    (Object.keys(settings) as (keyof typeof settings)[]).forEach((key) => {
      settings[key] = settings[key]!.map((ar: AttributeRef) =>
        matches(ar) ? { id: newRef.id, name: newRef.name } : ar
      );
    });

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

  protected removeFromAttributeSettings(attributeRef: AttributeRef) {
    const settings = this.widgetConfig.attributeSettings;
    (Object.keys(settings) as (keyof typeof settings)[]).forEach((key) => {
      settings[key] = settings[key]!.filter(
        (ar: AttributeRef) => ar.id !== attributeRef.id && ar.name !== attributeRef.name
      );
    });
  }

  /**
   * Adds or removes an {@link AttributeRef} from a field in the {@link BarChartAttributeConfig}.
   * For example, you can modify the list of 'right aligned attributes', or attributes that are displaying their 'AVG value'.
   * If the {@link AttributeRef} is already present, it'll be removed. Otherwise, it will be added.
   * @param setting - Field of {@link BarChartWidgetConfig} to append/remove the attribute from.
   * @param attributeRef - The {@link AttributeRef} to add/remove.
   * @protected
   */
  protected toggleAttributeSetting(
    setting: keyof BarChartWidgetConfig["attributeSettings"],
    attributeRef: AttributeRef,
    notify = true
  ): void {
    this.widgetConfig.attributeSettings[setting] ??= [];
    const attributes = this.widgetConfig.attributeSettings[setting];
    const index = attributes.findIndex(
      (item: AttributeRef) => item.id === attributeRef.id && item.name === attributeRef.name
    );
    if (index < 0) {
      attributes.push(attributeRef);
    } else {
      attributes.splice(index, 1);
      if (attributes && attributes.length === 0) {
        delete this.widgetConfig.attributeSettings[setting];
      }
    }
    if (notify) {
      this.notifyConfigUpdate();
    }
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

  protected removeFromAttributeColors(attributeRef: AttributeRef) {
    this.widgetConfig.attributeColors = (this.widgetConfig.attributeColors ?? []).filter(
      ([ref, _]) => ref.id !== attributeRef.id || ref.name !== attributeRef.name
    );
  }

  protected openAlgorithmMethodsDialog(attributeRef: AttributeRef) {
    const methodKeys: (keyof BarChartAttributeConfig)[] = [
      "methodAvgAttributes",
      "methodCountAttributes",
      "methodDifferenceAttributes",
      "methodMaxAttributes",
      "methodMedianAttributes",
      "methodMinAttributes",
      "methodModeAttributes",
      "methodSumAttributes",
    ];
    const activeKeys = methodKeys.filter((key) =>
      this.widgetConfig?.attributeSettings?.[key]?.find(
        (ref) => ref.id === attributeRef.id && ref.name === attributeRef.name
      )
    );

    const groupRef: Ref<OrVaadinCheckboxGroup> = createRef();
    let dialog: OrVaadinDialog | undefined;

    const onCancel = () => {
      dialog?.close();
    };
    const onOk = () => {
      const groupElem = groupRef.value;
      if (groupElem) {
        // Find all "active" methods for this AttributeRef, and remove them all. (so they become empty arrays)
        activeKeys.forEach((key) => {
          this.toggleAttributeSetting(key, attributeRef, false);
        });
        // Add the methods that were checked in the checkbox list
        groupElem.value.forEach((key) => {
          this.toggleAttributeSetting(key as keyof BarChartAttributeConfig, attributeRef, false);
        });
        this.notifyConfigUpdate();
        dialog?.close();
      }
    };

    dialog = showDialog(
      this.shadowRoot!,
      html`
        <or-vaadin-dialog width="384px">
          <h2 slot="header-content">
            <or-translate value="algorithmMethod"></or-translate>
          </h2>
          <or-vaadin-checkbox-group ${ref(groupRef)} theme="vertical" .value="${activeKeys}">
            ${methodKeys.map(
              (method) => html`
                <vaadin-checkbox value=${method}>
                  <or-translate slot="label" value=${method}></or-translate>
                </vaadin-checkbox>
              `
            )}
          </or-vaadin-checkbox-group>
          <div slot="footer" style="width: 100%; display: flex; justify-content: space-between;">
            <or-vaadin-button theme="tertiary" @click=${onCancel}>
              <or-translate value="cancel"></or-translate>
            </or-vaadin-button>
            <or-vaadin-button theme="primary" @click=${onOk}>
              <or-translate value="ok"></or-translate>
            </or-vaadin-button>
          </div>
        </or-vaadin-dialog>
      `
    );
  }

  protected onTimePrefixSelect(ev: Event) {
    this.widgetConfig.defaultTimePrefixKey = (ev.currentTarget as OrVaadinSelect).value;
    this.notifyConfigUpdate();
  }

  protected onTimeWindowSelect(ev: Event) {
    this.widgetConfig.defaultTimeWindowKey = (ev.currentTarget as OrVaadinSelect).value;
    this.notifyConfigUpdate();
  }

  protected onIntervalSelect(ev: Event) {
    this.widgetConfig.defaultInterval = (ev.currentTarget as OrVaadinSelect).value as BarChartInterval;
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

  protected onDefaultStackedToggle(ev: Event) {
    this.widgetConfig.stacked = (ev.currentTarget as OrVaadinToggle).checked;
    this.notifyConfigUpdate();
  }

  protected setAxisMinMaxValue(axis: "left" | "right", type: "min" | "max", value?: number) {
    if (axis === "left") {
      this.widgetConfig.chartOptions.options.scales.y[type] = value;
    } else {
      this.widgetConfig.chartOptions.options.scales.y1[type] = value;
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

  protected onDecimalsChange(ev: Event) {
    const elem = ev.currentTarget as OrVaadinNumberField;
    if (elem.checkValidity()) {
      this.widgetConfig.decimals = Number(elem.value);
      this.notifyConfigUpdate();
    }
  }
}
