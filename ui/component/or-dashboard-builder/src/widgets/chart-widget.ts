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
import type { AssetDatapointLTTBQuery, AssetDatapointQueryUnion, Attribute, AttributeRef } from "@openremote/model";
import { html, type PropertyValues, type TemplateResult } from "lit";
import { when } from "lit/directives/when.js";
import moment from "moment";
import type { ChartAttributeConfig } from "@openremote/or-chart";
import { OrAssetWidget } from "../util/or-asset-widget";
import { customElement, state } from "lit/decorators.js";
import type { AssetWidgetConfig } from "../util/widget-config";
import type { OrWidget, WidgetManifest } from "../util/or-widget";
import { ChartSettings } from "../settings/chart-settings";
import type { WidgetSettings } from "../util/widget-settings";
import "@openremote/or-chart";

export interface ChartWidgetConfig extends AssetWidgetConfig {
  attributeColors: [AttributeRef, string][];
  datapointQuery: AssetDatapointQueryUnion;
  attributeConfig?: ChartAttributeConfig;
  chartOptions?: any;
  showTimestampControls: boolean;
  defaultTimePresetKey: string;
  showLegend: boolean;
  showZoomBar: boolean;
  stacked: boolean;
}

function getDefaultTimeWindowOptions(): Map<string, [moment.unitOfTime.DurationConstructor, number]> {
  return new Map<string, [moment.unitOfTime.DurationConstructor, number]>([
    ["60Minutes", ["minutes", 60]],
    ["Hour", ["hours", 1]],
    ["6Hours", ["hours", 6]],
    ["24Hours", ["hours", 24]],
    ["Day", ["days", 1]],
    ["7Days", ["days", 7]],
    ["Week", ["weeks", 1]],
    ["30Days", ["days", 30]],
    ["Month", ["months", 1]],
    ["90Days", ["days", 90]],
    ["6Months", ["months", 6]],
    ["365Days", ["days", 365]],
    ["Year", ["years", 1]],
  ]);
}

function getDefaultTimePrefixOptions(): string[] {
  return ["current", "last", "next"];
}

function getDefaultSamplingOptions(): Map<string, string> {
  return new Map<string, string>([["lttb", "lttb"]]);
}

function getDefaultWidgetConfig(): ChartWidgetConfig {
  const defaultPrefix = getDefaultTimePrefixOptions()[0];
  const [defaultWindowKey, defaultWindow] = Array.from(getDefaultTimeWindowOptions().entries())[0];
  const startDate = moment().subtract(defaultWindow[1], defaultWindow[0]).startOf(defaultWindow[0]);
  const endDate = defaultWindow[1] === 1 ? moment().endOf(defaultWindow[0]) : moment();
  return {
    attributeRefs: [],
    attributeColors: [],
    datapointQuery: {
      type: "lttb",
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
    defaultTimePresetKey: defaultPrefix + defaultWindowKey,
    showLegend: true,
    showZoomBar: false,
    stacked: false,
  };
}

/* --------------------------------------------------- */

@customElement("chart-widget")
export class ChartWidget extends OrAssetWidget {
  @state()
  protected datapointQuery!: AssetDatapointQueryUnion;

  @state()
  protected _loading = false;

  // Override of widgetConfig with extended type
  protected widgetConfig!: ChartWidgetConfig;

  static getManifest(): WidgetManifest {
    return {
      displayName: "Line Chart",
      displayIcon: "chart-line",
      minColumnWidth: 2,
      minColumnHeight: 2,
      getContentHtml(config: ChartWidgetConfig): OrWidget {
        return new ChartWidget(config);
      },
      getSettingsHtml(config: ChartWidgetConfig): WidgetSettings {
        const settings = new ChartSettings(config);
        settings.setTimeWindowOptions(getDefaultTimeWindowOptions());
        settings.setTimePrefixOptions(getDefaultTimePrefixOptions());
        settings.setSamplingOptions(getDefaultSamplingOptions());
        return settings;
      },
      getDefaultConfig(): ChartWidgetConfig {
        return getDefaultWidgetConfig();
      },
    };
  }

  // Method called on every refresh/reload of the widget
  // We either refresh the datapointQuery or the full widgetConfig depending on the force parameter.
  // TODO: Improve this to a more efficient approach, instead of duplicating the object
  public refreshContent(force: boolean) {
    if (!force) {
      const datapointQuery = JSON.parse(JSON.stringify(this.widgetConfig.datapointQuery)) as AssetDatapointQueryUnion;
      datapointQuery.fromTimestamp = undefined;
      datapointQuery.toTimestamp = undefined;
      this.datapointQuery = datapointQuery;
    } else {
      this.widgetConfig = JSON.parse(JSON.stringify(this.widgetConfig)) as ChartWidgetConfig;
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
              const timePrefixList = getDefaultTimePrefixOptions();
              const timePresetKey = normalizeTimePresetKey(this.widgetConfig?.defaultTimePresetKey);
              const timePrefix = timePrefixList.find((prefix) => timePresetKey.startsWith(prefix)) ?? timePrefixList[0];
              const timeWindowList = getDefaultTimeWindowOptions();
              let [_, timeWindow] = timePresetKey.split(timePrefix);
              if (!timeWindow || !timeWindowList.has(timeWindow)) timeWindow = Array.from(timeWindowList.keys())[0];
              return html`
                <or-chart
                  .assets="${this.loadedAssets}"
                  .assetAttributes="${this.assetAttributes}"
                  .attributeColors="${this.widgetConfig?.attributeColors}"
                  ?stacked="${this.widgetConfig?.stacked}"
                  .attributeConfig="${this.widgetConfig?.attributeConfig != null ? this.widgetConfig.attributeConfig : {}}"
                  .showLegend="${this.widgetConfig?.showLegend != null ? this.widgetConfig?.showLegend : true}"
                  .showZoomBar="${this.widgetConfig?.showZoomBar != null ? this.widgetConfig?.showZoomBar : true}"
                  .attributeControls="${false}"
                  .timestampControls="${!this.widgetConfig?.showTimestampControls}"
                  .timePrefixOptions="${timePrefixList}"
                  .timeWindowOptions="${timeWindowList}"
                  .timePrefixKey="${timePrefix}"
                  .timeWindowKey="${timeWindow}"
                  .datapointQuery="${this.datapointQuery}"
                  .chartOptions="${this.widgetConfig?.chartOptions}"
                  style="height: 100%"
                ></or-chart>
              `;
            }
          )
      )}
    `;
  }

  protected getDefaultQuery(): AssetDatapointLTTBQuery {
    return {
      type: "lttb",
      fromTimestamp: moment().set("minute", -60).toDate().getTime(),
      toTimestamp: moment().set("minute", 60).toDate().getTime(),
    };
  }
}
