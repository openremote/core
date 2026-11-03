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
import type { AssetDatapointIntervalQuery, AssetDatapointQueryUnion, Attribute, AttributeRef } from "@openremote/model";
import { html, type PropertyValues, type TemplateResult } from "lit";
import { when } from "lit/directives/when.js";
import moment from "moment";
import { OrAssetWidget } from "../util/or-asset-widget";
import { customElement, state } from "lit/decorators.js";
import type { AssetWidgetConfig } from "../util/widget-config";
import type { OrWidget, WidgetManifest } from "../util/or-widget";
import { BarChartSettings } from "../settings/barchart-settings";
import type { WidgetSettings } from "../util/widget-settings";
import "@openremote/or-attribute-barchart";
import { type BarChartAttributeConfig, BarChartInterval, OrAttributeBarChart } from "@openremote/or-attribute-barchart";

export interface BarChartWidgetConfig extends AssetWidgetConfig {
  attributeColors: [AttributeRef, string][];
  attributeSettings: BarChartAttributeConfig;
  datapointQuery: AssetDatapointQueryUnion;
  chartOptions?: any;
  showTimestampControls: boolean;
  defaultTimeWindowKey: string;
  defaultTimePrefixKey: string;
  defaultInterval: BarChartInterval;
  showLegend: boolean;
  stacked: boolean;
  decimals: number;
}

function getDefaultWidgetConfig(): BarChartWidgetConfig {
  const preset = "Day"; // Default time preset, "current" prefix is hardcoded in startDate and endDate below.
  const dateFunc = OrAttributeBarChart.getDefaultTimeWindowOptions().get(preset);
  const startDate = moment().subtract(dateFunc![1], dateFunc![0]).startOf(dateFunc![0]);
  const endDate = dateFunc![1] === 1 ? moment().endOf(dateFunc![0]) : moment();
  return {
    attributeRefs: [],
    attributeColors: [],
    attributeSettings: {},
    datapointQuery: {
      type: "interval",
      fromTimestamp: startDate.toDate().getTime(),
      toTimestamp: endDate.toDate().getTime(),
    },
    chartOptions: {
      options: {
        scales: {
          y: {
            min: undefined,
            max: undefined,
          },
          y1: {
            min: undefined,
            max: undefined,
          },
        },
      },
    },
    showTimestampControls: false,
    defaultTimeWindowKey: preset,
    defaultTimePrefixKey: "current",
    defaultInterval: BarChartInterval.AUTO,
    showLegend: true,
    stacked: false,
    decimals: 2,
  };
}

/* --------------------------------------------------- */

@customElement("barchart-widget")
export class BarChartWidget extends OrAssetWidget {
  @state()
  protected datapointQuery!: AssetDatapointQueryUnion;

  @state()
  protected _loading = false;

  // Override of widgetConfig with extended type
  protected widgetConfig!: BarChartWidgetConfig;

  static getManifest(): WidgetManifest {
    return {
      displayName: "Bar Chart",
      displayIcon: "chart-bar",
      minColumnWidth: 2,
      minColumnHeight: 2,
      getContentHtml(config: BarChartWidgetConfig): OrWidget {
        return new BarChartWidget(config);
      },
      getSettingsHtml(config: BarChartWidgetConfig): WidgetSettings {
        const settings = new BarChartSettings(config);
        settings.setTimeWindowOptions(OrAttributeBarChart.getDefaultTimeWindowOptions());
        settings.setTimePrefixOptions(OrAttributeBarChart.getDefaultTimePrefixOptions());
        settings.setIntervalOptions(OrAttributeBarChart.getDefaultIntervalOptions());
        return settings;
      },
      getDefaultConfig(): BarChartWidgetConfig {
        return getDefaultWidgetConfig();
      },
    };
  }

  // Method called on every refresh/reload of the widget
  // We either refresh the datapointQuery or the full widgetConfig depending on the force parameter.
  // TODO: Improve this to a more efficient approach, instead of duplicating the object
  public refreshContent(force: boolean) {
    if (!force) {
      const datapointQuery = structuredClone(this.widgetConfig.datapointQuery);
      datapointQuery.fromTimestamp = undefined;
      datapointQuery.toTimestamp = undefined;
      this.datapointQuery = datapointQuery;
    } else {
      this.widgetConfig = structuredClone(this.widgetConfig);
    }
  }

  /* ---------------------------------- */

  // WebComponent lifecycle method, that occurs DURING every state update
  protected willUpdate(changedProps: PropertyValues) {
    // Add datapointQuery if not set yet (due to migration)
    if (!this.widgetConfig.datapointQuery) {
      this.widgetConfig.datapointQuery = this.getDefaultQuery();
      if (!changedProps.has("widgetConfig")) {
        changedProps.set("widgetConfig", this.widgetConfig);
      }
    }

    if (changedProps.has("widgetConfig") && this.widgetConfig) {
      this.datapointQuery = this.widgetConfig.datapointQuery;

      const attributeRefs = this.widgetConfig.attributeRefs;
      if (attributeRefs.length === 0) {
        this._error = "noAttributesConnected";
      } else {
        const missingAssets = attributeRefs?.filter((attrRef: AttributeRef) => !this.isAttributeRefLoaded(attrRef));
        if (missingAssets.length > 0) {
          this.loadAssets(attributeRefs);
        }
      }
    }

    return super.willUpdate(changedProps);
  }

  protected loadAssets(attributeRefs: AttributeRef[]): void {
    if (attributeRefs.length === 0) {
      this._error = "noAttributesConnected";
      return;
    }
    this._loading = true;
    this._error = undefined;
    this.fetchAssets(attributeRefs)
      .then((assets) => {
        this.loadedAssets = assets;
        this.assetAttributes = attributeRefs?.map((attrRef: AttributeRef) => {
          const assetIndex = assets.findIndex((asset) => asset.id === attrRef.id);
          const foundAsset = assetIndex >= 0 ? assets[assetIndex] : undefined;
          const attribute = foundAsset?.attributes?.[attrRef.name!];
          return attribute ? [assetIndex, attribute] : [-1, { name: attrRef.name } as Attribute<any>];
        }) as [number, Attribute<any>][];
      })
      .catch((e) => {
        this._error = e.message;
      })
      .finally(() => {
        this._loading = false;
      });
  }

  protected render(): TemplateResult {
    return html`
      ${when(
        this._loading,
        () => html` <or-loading-indicator></or-loading-indicator> `,
        () =>
          when(
            this._error,
            () => html`
              <div
                style="height: 100%; display: flex; justify-content: center; align-items: center; text-align: center;"
              >
                <span><or-translate .value="${this._error}"></or-translate></span>
              </div>
            `,
            () => {
              return html`
                <or-attribute-barchart
                  .assets="${this.loadedAssets}"
                  .assetAttributes="${this.assetAttributes}"
                  .attributeColors="${this.widgetConfig?.attributeColors}"
                  .attributeConfig="${this.widgetConfig?.attributeSettings}"
                  ?showLegend=${this.widgetConfig?.showLegend}
                  ?stacked=${this.widgetConfig?.stacked}
                  .timestampControls="${!this.widgetConfig?.showTimestampControls}"
                  .interval="${this.widgetConfig?.defaultInterval}"
                  .timePrefixKey="${this.widgetConfig?.defaultTimePrefixKey}"
                  .timeWindowKey="${this.widgetConfig?.defaultTimeWindowKey}"
                  .datapointQuery="${this.datapointQuery}"
                  .chartOptions="${this.widgetConfig?.chartOptions}"
                  .decimals="${this.widgetConfig?.decimals}"
                  style="height: 100%"
                ></or-attribute-barchart>
              `;
            }
          )
      )}
    `;
  }

  protected getDefaultQuery(): AssetDatapointIntervalQuery {
    return {
      type: "interval",
      fromTimestamp: moment().subtract(30, "days").toDate().getTime(),
      toTimestamp: moment().toDate().getTime(),
    };
  }
}
