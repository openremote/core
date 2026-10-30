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
import { css, html, type PropertyValues, type TemplateResult, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, state, query } from "lit/decorators.js";
import { i18next } from "@openremote/or-translate";
import {
  type Asset,
  type AssetDatapointIntervalQuery,
  AssetDatapointIntervalQueryFormula,
  type AssetDatapointQueryUnion,
  AssetModelUtil,
  type Attribute,
  type AttributeRef,
  DatapointInterval,
  type ValueDatapoint,
} from "@openremote/model";
import manager, { DefaultColor3, DefaultColor4, Util } from "@openremote/core";
import * as echarts from "echarts/core";
import {
  type DatasetComponentOption,
  DataZoomComponent,
  type DataZoomComponentOption,
  GridComponent,
  type GridComponentOption,
  TooltipComponent,
  type TooltipComponentOption,
} from "echarts/components";
import { BarChart, type BarSeriesOption } from "echarts/charts";
import { CanvasRenderer } from "echarts/renderers";
import { UniversalTransition } from "echarts/features";
import moment from "moment";
import { getAssetDescriptorIconTemplate } from "@openremote/or-icon";
import { isAxiosError } from "@openremote/rest";
import { OrMwcDialog, showDialog } from "@openremote/or-mwc-components/or-mwc-dialog";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import { cache } from "lit/directives/cache.js";
import { createMenuBarItem, type MenuBarItem } from "@openremote/or-vaadin-components/or-vaadin-menu-bar";
import { OrVaadinDateTimePicker } from "@openremote/or-vaadin-components/or-vaadin-date-time-picker";
import { when } from "lit/directives/when.js";
import { createRef, type Ref, ref } from "lit/directives/ref.js";
import { getChartAxisBounds, getNavigationDuration, shiftTimeframe, type TimeframeDirection } from "./timeframe";
import "@openremote/or-translate";

echarts.use([GridComponent, TooltipComponent, DataZoomComponent, BarChart, CanvasRenderer, UniversalTransition]);

/**
 * Bar chart configuration with options for individual attributes.
 * For example, a list of attributes aligned to the axis on the right side.
 */
export interface BarChartAttributeConfig {
  rightAxisAttributes?: AttributeRef[];
  faintAttributes?: AttributeRef[];
  methodMaxAttributes?: AttributeRef[];
  methodMinAttributes?: AttributeRef[];
  methodAvgAttributes?: AttributeRef[];
  methodDifferenceAttributes?: AttributeRef[];
  methodMedianAttributes?: AttributeRef[];
  methodModeAttributes?: AttributeRef[];
  methodSumAttributes?: AttributeRef[];
  methodCountAttributes?: AttributeRef[];
}

/**
 * Interval configuration for a user to select from.
 * It contains a {@link displayName}, together with a step+interval like "6 hours".
 */
export interface IntervalConfig {
  displayName: string;
  steps: number;
  orFormat: DatapointInterval;
  momentFormat: moment.unitOfTime.DurationConstructor;
  millis: number;
}

/**
 * List of interval options
 */
export enum BarChartInterval {
  AUTO = "auto",
  NONE = "none",
  ONE_MINUTE = "1Minute",
  FIVE_MINUTES = "5Minutes",
  FIFTEEN_MINUTES = "15Minutes",
  THIRTY_MINUTES = "30Minutes",
  ONE_HOUR = "hour",
  ONE_DAY = "day",
  ONE_WEEK = "week",
  ONE_MONTH = "month",
  ONE_YEAR = "year",
}

/**
 * ECharts dataset object with additional optional fields for visualization purposes.
 * For example, {@link assetId} and {@link attrName} can be specified, so their information can be shown alongside the data itself.
 */
export interface BarChartData extends BarSeriesOption {
  index?: number;
  assetId?: string;
  attrName?: string;
  unit?: string;
  yAxisIndex?: number;
  color?: string;
}

export type ECChartOption = echarts.ComposeOption<
  BarSeriesOption | TooltipComponentOption | GridComponentOption | DatasetComponentOption | DataZoomComponentOption
>;

// language=CSS
const style = css`
  :host {
    --internal-or-chart-text-color: var(--or-chart-text-color, var(--or-app-color3, ${unsafeCSS(DefaultColor3)}));
    --internal-or-chart-graph-fill-color: var(
      --or-chart-graph-fill-color,
      var(--or-app-color4, ${unsafeCSS(DefaultColor4)})
    );
    --internal-or-chart-graph-point-hover-border-color: var(
      --or-chart-graph-point-hover-border-color,
      var(--or-app-color3, ${unsafeCSS(DefaultColor3)})
    );
    width: 100%;
    display: block;
  }

  .button-icon {
    align-self: center;
    padding: 10px;
    cursor: pointer;
  }

  .mdc-dialog .mdc-dialog__surface {
    min-width: 600px;
    height: calc(100vh - 50%);
  }

  :host([hidden]) {
    display: none;
  }

  #container {
    display: flex;
    min-width: 0;
    flex-direction: row;
    height: 100%;
  }

  #period-controls {
    display: flex;
    flex-direction: column;
    align-items: center;
  }

  #period-dropdown-controls {
    flex: 0;
    display: flex;
  }

  #period-dropdown-controls > *:first-child {
    margin-right: -2px;
  }

  #period-dropdown-controls > *:last-child {
    margin-left: -2px;
  }

  .navigate {
    flex-direction: column;
  }

  #controls {
    display: flex;
    flex-wrap: wrap;
    width: 100%;
    flex-direction: column;
    justify-content: center;
  }

  #attribute-list {
    overflow: hidden auto;
    min-height: 50px;
    flex: 1 1 0;
    width: 100%;
    display: flex;
    flex-direction: column;
  }

  .attribute-list-dense {
    flex-wrap: wrap;
  }

  .attribute-list-item {
    cursor: pointer;
    display: flex;
    flex-direction: row;
    align-items: center;
    padding: 0;
    min-height: 50px;
  }

  .attribute-list-item-dense {
    min-height: 28px;
  }

  .attribute-list-item-label {
    display: flex;
    flex: 1 1 0;
    line-height: 16px;
    flex-direction: column;
  }

  .attribute-list-item-bullet {
    width: 12px;
    height: 12px;
    border-radius: 7px;
    margin-right: 10px;
  }

  .attribute-list-item .button.delete {
    display: none;
  }

  .attribute-list-item:hover .button.delete {
    display: block;
  }

  #controls > * {
    margin-top: 5px;
    margin-bottom: 5px;
  }

  #chart-container {
    flex: 1 1 0;
    position: relative;
    overflow: hidden;
    /*min-height: 400px;
        max-height: 550px;*/
  }

  #chart-controls {
    display: flex;
    flex-direction: column;
    margin-top: 10px;
    margin-right: -4px;
  }

  #chart {
    width: 100%;
    height: 100%;
  }

  @media screen and (max-width: 1280px) {
    #chart-container {
    }
  }

  @media screen and (max-width: 769px) {
    .mdc-dialog .mdc-dialog__surface {
      min-width: auto;

      max-width: calc(100vw - 32px);
      max-height: calc(100% - 32px);
    }

    #container {
      flex-direction: column;
    }

    #controls {
      min-width: 100%;
      padding-left: 0;
    }

    .interval-controls,
    #period-controls {
      flex-direction: row;
      justify-content: left;
      align-items: center;
      gap: 8px;
    }
  }
`;

@customElement("or-attribute-barchart")
export class OrAttributeBarChart extends OrElement {
  public static readonly DEFAULT_COLORS = [
    "#3869B1",
    "#DA7E30",
    "#3F9852",
    "#CC2428",
    "#6B4C9A",
    "#922427",
    "#958C3D",
    "#535055",
  ];

  /**
   * Asset data to display in the bar chart. This array needs to be populated.
   * Used for the chart legend, information about units, and retrieval of data points.
   */
  @property({ type: Object })
  public assets: Asset[] = [];

  /**
   * List of attributes to display in the bar chart. This array needs to be populated.
   * Used for the chart legend, and the retrieval of data points.
   * Formatted as a JSON array of;
   *
   * ```[<index of {@link assets}>, {@link Attribute}]```
   */
  @property({ type: Object })
  public assetAttributes: [number, Attribute<any>][] = [];

  /**
   * Realm name to associate all the chart data with.
   */
  @property({ type: String })
  public realm: string | undefined = manager?.displayRealm;

  /**
   * The list of HEX colors representing the line color for each {@link AttributeRef}.
   * Acts as an override, and will fall back to the {@link colors} attribute if not specified.
   * The {@link AttributeRef} object with Asset ID and Attribute name need to be present in the Chart to work.
   * The HEX color should be put in without '#' prefix. For example, you'd use '4d9d2a' instead of '#4d9d2a'.
   */
  @property({ type: Array })
  public readonly attributeColors: [AttributeRef, string][] = [];

  /**
   * The attribute configuration object with, for example, a list of right-axis attributes.
   * See {@link BarChartAttributeConfig} for full documentation on the options.
   */
  @property({ type: Object })
  public readonly attributeConfig?: BarChartAttributeConfig;

  /**
   * List of colors to display attributes values with inside the bar chart.
   * The array corresponds with {@link assetAttributes}, so the 1st color will be used for the 1st attribute.
   */
  @property({ type: Array })
  public readonly colors: string[] = OrAttributeBarChart.DEFAULT_COLORS;

  /**
   * The query object to request attribute data / data points with.
   * Normally, it's of type {@link AssetDatapointIntervalQuery}, including a start- and end time.
   */
  @property({ type: Object })
  public readonly datapointQuery!: AssetDatapointQueryUnion;

  /**
   * Selected timeframe using a [start date, end date] array format.
   */
  @property({ type: Array })
  public timeframe?: [Date, Date];

  /**
   * Custom ECharts options object to configure the chart appearance.
   * See their documentation for more info: https://echarts.apache.org/en/option.html
   */
  @property({ type: Object })
  public chartOptions?: ECChartOption;

  /**
   * Shows a right-side legend with the list of visible attributes.
   */
  @property({ type: Boolean })
  public showLegend = false;

  /**
   * Shows the right-side legend (toggled with {@link showLegend}) in a compact / dense format.
   */
  @property({ type: Boolean })
  public denseLegend = false;

  /**
   * Displays the bar chart in a vertically "stacked" configuration.
   * It will display a cumulative effect of all data series on top of each other.
   */
  @property({ type: Boolean })
  public stacked = false;

  /**
   * Enables time control for the user, allowing them to customize the {@link timeframe} shown.
   * Leaving this disabled/unset will show them in a "read-only" state.
   */
  @property({ type: Boolean })
  public timestampControls = true;

  /**
   * Number of decimals to display for attributes.
   */
  @property({ type: Number })
  public decimals = 2;

  /**
   * A JSON array list of strings that represent the prefix options in the UI, such as "current" or "last".
   * In combination with {@link timeWindowOptions}, it provides a timeframe like "current 1 hour" to display data of.
   */
  @property({ type: Array })
  public timePrefixOptions: string[] = OrAttributeBarChart.getDefaultTimePrefixOptions();

  /**
   * The selected option from {@link timePrefixOptions}, such as "current" or "last".
   * In combination with {@link timeWindowOptions}, it provides a timeframe like "current 1 hour" to display data of.
   */
  @property({ type: String })
  public timePrefixKey?: string;

  /**
   * A JSON object containing a list of time window options, like "1 day", "6 hours" or "52 weeks".
   * In combination with {@link timePrefixOptions}, it provides a timeframe like "last 6 hours" to display data of.
   * The input should contain a JavaScript {@link Map}, containing the following:
   * - A {@link string} with a unique key like "6hours",
   * - A combination of any {@link moment.unitOfTime.DurationConstructor} and {@link number}, like ["hours", 6]
   *
   * Example input: `{"6hours": ["hours", 6], "4weeks": ["weeks", 4]}`
   */
  @property({ type: Object })
  public timeWindowOptions: Map<string, [moment.unitOfTime.DurationConstructor, number]> =
    OrAttributeBarChart.getDefaultTimeWindowOptions();

  /**
   * The key value of the selected option from {@link timeWindowOptions}, such as "1day", "6hours" or "52weeks".
   * In combination with {@link timePrefixOptions}, it provides a timeframe like "last 6 hours" to display data of.
   */
  @property({ type: String })
  public timeWindowKey?: string;

  /**
   * A JSON object containing a list of interval options, like "30minutes", "hour" or "day".
   * It determines the interval to display data in sections. For example, split the data every 5 minutes of the past hour.
   * In combination with the active formula in {@link datapointQuery}, such as AVG and MIN/MAX, it will show a '5 minute' average of the past hour.
   * The input should contain a JavaScript {@link Map}, containing the following:
   * - A {@link BarChartInterval} string with a unique key like "hour" or "day",
   * - A {@link IntervalConfig} object with details on the interval, such as the display name and the unit of time.
   *
   * Example input:
   * ```
   * {"30minutes": {
   *   displayName: "30Minutes", // translated automatically
   *   steps: 30,
   *   orFormat: "minute",
   *   momentFormat: "minutes", // using moment.js format
   *   millis: 1800000
   * }}
   * ```
   */
  @property({ type: Object })
  public intervalOptions: Map<BarChartInterval, IntervalConfig> = OrAttributeBarChart.getDefaultIntervalOptions();

  /**
   * The key value of the selected option from {@link intervalOptions}, such as "30minute", "hour" or "week".
   * In combination with the active formula in {@link datapointQuery}, such as AVG and MIN/MAX, it will show a '5 minute' average of the past hour.
   */
  @property({ type: String })
  public interval?: BarChartInterval;

  /**
   * Whether a custom {@link timeframe} has been configured by the user or not.
   * If it's a default value provided by us, it will be `false`.
   * If a user customized the timeframe, it will be `true`.
   * @protected
   */
  @state()
  protected _isCustomWindow = false;

  /**
   * Loading state when fetching data from a remote source.
   * Show a "loading indicator" in the UI when set to `true`.
   * @protected
   */
  @state()
  protected _loading = false;

  /**
   * Cached array of {@link BarChartData} objects to display in the chart.
   * It contains custom data, but data from the ECharts library as well (see {@link BarSeriesOption} for details)
   * @protected
   */
  @state()
  protected _data?: BarChartData[];

  @query("#chart")
  protected _chartElem!: HTMLDivElement;

  protected _chart?: echarts.ECharts;
  protected _style!: CSSStyleDeclaration;
  protected _startOfPeriod?: number; // Start timestamp of the visible period
  protected _endOfPeriod?: number; // End timestamp of the visible period
  protected _navigationDuration?: number;
  protected _latestError?: string;
  protected _dataAbortController?: AbortController;
  protected _containerResizeObserver?: ResizeObserver;

  protected _intervalConfig?: IntervalConfig;

  static get styles() {
    return [style];
  }

  connectedCallback() {
    super.connectedCallback();
    this._style = window.getComputedStyle(this);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanup();
  }

  willUpdate(changedProps: PropertyValues) {
    if (changedProps.has("realm") && changedProps.get("realm")) {
      this.assets = [];
      this.assetAttributes = [];
    }
    if (changedProps.has("timePrefixOptions") && this.timePrefixOptions) {
      this.timePrefixKey ??= this.timePrefixOptions[0];
    }
    if (changedProps.has("timeWindowOptions") && this.timeWindowOptions) {
      this.timeWindowKey ??= Array.from(this.timeWindowOptions.keys())[0];
    }
    if (changedProps.has("intervalOptions") && this.intervalOptions) {
      this.interval ??= Array.from(this.intervalOptions.keys())[0];
    }
    return super.willUpdate(changedProps);
  }

  updated(changedProps: PropertyValues) {
    super.updated(changedProps);

    // When certain state is changed, rerender the bar chart
    const reloadData =
      changedProps.has("attributeColors") ||
      changedProps.has("datapointQuery") ||
      changedProps.has("timeframe") ||
      changedProps.has("interval") ||
      changedProps.has("timePrefixKey") ||
      changedProps.has("timeWindowKey") ||
      changedProps.has("attributeConfig") ||
      changedProps.has("assetAttributes") ||
      changedProps.has("realm");
    if (reloadData) {
      this._data = undefined;
      if (this._chart) {
        this._cleanup(); // destroy chart if one exists
      }
      // Gather information to use for loading
      const dates: [Date, Date] = this._getTimeSelectionDates(this.timePrefixKey!, this.timeWindowKey!);
      this._startOfPeriod = this.timeframe ? this.timeframe[0].getTime() : dates[0].getTime();
      this._endOfPeriod = this.timeframe ? this.timeframe[1].getTime() : dates[1].getTime();
      const timeWindow = this.timeWindowOptions.get(this.timeWindowKey!);
      if (!timeWindow) {
        throw new Error(`Unsupported time window selected: ${this.timeWindowKey}`);
      }
      this._navigationDuration = getNavigationDuration(
        this._startOfPeriod,
        this._endOfPeriod,
        this._isCustomWindow,
        timeWindow[0],
        timeWindow[1]
      );
      this._intervalConfig = this._getInterval(this._startOfPeriod, this._endOfPeriod, this.interval!);
      this._loadData();
    }
    // Abort when no data is present
    if (!this._data) {
      return;
    }
    // Generate chart element if not done yet
    if (!this._chart) {
      console.debug("Initializing chart...");
      this.chartOptions = { ...this._getDefaultChartOptions(), ...this.chartOptions };
      this._chart = echarts.init(this._chartElem);
      this._chart.setOption(this.chartOptions);
      this._toggleChartEventListeners(true);
    }
    // If data has changed (and there is data present), update the chart element.
    if (changedProps.has("_data") && this._data?.[0]?.data?.length) {
      this._updateChartData();
    }
  }

  render() {
    const disabled = this._loading || !!this._latestError;
    return html`
      <div id="container">
        ${when(
          this._loading,
          () => html`
            <div style="position: absolute; height: 100%; width: 100%;">
              <or-loading-indicator ?overlay="false"></or-loading-indicator>
            </div>
          `
        )}
        ${when(
          this._latestError,
          () => html`
            <div
              style="position: absolute; height: 100%; width: 100%; display: flex; justify-content: center; align-items: center;"
            >
              <or-translate .value="${this._latestError ?? "errorOccurred"}"></or-translate>
            </div>
          `
        )}
        ${when(
          Object.keys(this.attributeConfig || {})
            .filter((key) => key.startsWith("method"))
            .every((key) => (this.attributeConfig as any)[key].length === 0),
          () => html`
            <div
              style="position: inherit; height: 100%; width: 100%; display: flex; justify-content: center; align-items: center; z-index: 1; pointer-events: none;"
            >
              <or-translate .value="${"dashboard.selectMethod"}"></or-translate>
            </div>
          `
        )}
        ${when(
          this._data?.every((entry) => !entry.data?.length),
          () => html`
            <div
              style="position: inherit; height: 100%; width: 100%; display: flex; justify-content: center; align-items: center; z-index: 1; pointer-events: none;"
            >
              <or-translate .value="${"dashboard.noData"}"></or-translate>
            </div>
          `
        )}
        <div id="chart-container">
          <div id="chart" style="visibility: ${disabled ? "hidden" : "visible"}"></div>
        </div>

        ${when(
          this.timestampControls || this.showLegend,
          () => html`
            <div id="chart-controls">
              <div id="controls">
                ${when(this.timestampControls && this.timePrefixKey && this.timeWindowKey, () => this._getTimeControlsTemplate(disabled || !!this.timeframe))}
                ${when(this.timeframe, () => this._getTimeframeStatusTemplate(this.timeframe))}
              </div>
              ${cache(when(this.showLegend, () => this._getLegendTemplate()))}
            </div>
          `
        )}
      </div>
    `;
  }

  protected _getTimeControlsTemplate(disabled: boolean): TemplateResult {
    console.debug("Disabled?", disabled);
    const menuItems: MenuBarItem[] = [
      {
        component: createMenuBarItem(
          html`<or-translate value=${this.timeframe ? "dashboard.customTimeSpan" : this.timePrefixKey}></or-translate>`
        ),
        children: this.timePrefixOptions.map((o) => ({
          type: "prefix",
          value: o,
          component: createMenuBarItem(html`<or-translate value=${o}></or-translate>`),
        })),
      },
      {
        component: createMenuBarItem(
          html`<or-translate
            value=${this._isCustomWindow ? "timeframe" : i18next.t(this.timeWindowKey?.toLowerCase() ?? "")}
          ></or-translate>`
        ),
        children: Array.from(this.timeWindowOptions).map((o) => ({
          type: "window",
          value: o[0],
          component: createMenuBarItem(html`<or-translate value=${o[0]?.toLowerCase()}></or-translate>`),
        })),
      },
    ];
    const intervalMenuItems: MenuBarItem[] = [
      {
        component: createMenuBarItem(
          html`<or-translate value=${`intervalBy${this._intervalConfig?.displayName?.toLowerCase()}`}></or-translate>`
        ),
        children: Array.from(this.intervalOptions.keys()).map((o) => ({
          value: o,
          component: createMenuBarItem(html`<or-translate value=${`intervalBy${o.toLowerCase()}`}></or-translate>`),
        })),
      },
    ];
    return html`
      <div id="period-controls">
        <!-- Time prefix & window selection -->
        <div id="period-dropdown-controls">
          <or-vaadin-menu-bar
            .items=${menuItems}
            ?disabled=${disabled}
            @item-selected=${(ev: CustomEvent) => this._onTimeframeMenuSelect(ev)}
          ></or-vaadin-menu-bar>
        </div>
        <!-- Interval selection -->
        <or-vaadin-menu-bar
          .items=${intervalMenuItems}
          ?disabled=${disabled}
          @item-selected=${(ev: CustomEvent) => (this.interval = ev.detail.value.value)}
        ></or-vaadin-menu-bar>
      </div>
      <div class="navigate" style="text-align: right">
        <!-- Scroll left button -->
        <or-icon
          class="button button-icon"
          ?disabled="${disabled}"
          icon="chevron-left"
          @click="${() => this._shiftTimeframe(this.timeframe ? this.timeframe[0] : new Date(this._startOfPeriod!), this.timeframe ? this.timeframe[1] : new Date(this._endOfPeriod!), this.timeWindowKey!, "previous")}"
        ></or-icon>
        <!-- Scroll right button -->
        <or-icon
          class="button button-icon"
          ?disabled="${disabled}"
          icon="chevron-right"
          @click="${() => this._shiftTimeframe(this.timeframe ? this.timeframe[0] : new Date(this._startOfPeriod!), this.timeframe ? this.timeframe[1] : new Date(this._endOfPeriod!), this.timeWindowKey!, "next")}"
        ></or-icon>
        <!-- Button that opens custom time selection or restores to widget setting-->
        <or-icon
          class="button button-icon"
          ?disabled="${disabled}"
          icon="${this.timeframe ? "restore" : "calendar-clock"}"
          @click="${() => (this.timeframe ? ((this._isCustomWindow = false), (this.timeframe = undefined)) : this._openTimeDialog(this._startOfPeriod, this._endOfPeriod))}"
        ></or-icon>
      </div>
    `;
  }

  protected _onTimeframeMenuSelect(ev: CustomEvent) {
    const type = ev.detail.value.type as "prefix" | "window";
    const value = ev.detail.value.value;
    if (type === "prefix") {
      // Handle time prefix
      this.timeframe = undefined; // remove any custom start & end times
      this.timePrefixKey = value;
    } else {
      // Handle time window
      this.timeframe = undefined; // remove any custom start & end times
      this.timeWindowKey = value;
    }
  }

  protected _getTimeframeStatusTemplate(timeframe = this.timeframe): TemplateResult {
    return html`
      <div style="margin-left: 18px; font-size: 12px; display: flex; justify-content: flex-end;">
        <table style="text-align: right;">
          <thead>
            <tr>
              <th style="font-weight: normal; text-align: right;"><or-translate value="from"></or-translate></th>
              <th style="font-weight: normal; text-align: right;">${moment(timeframe![0]).format("lll")}</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td style="text-align: right;"><or-translate value="to"></or-translate></td>
              <td style="text-align: right;">${moment(timeframe![1]).format("lll")}</td>
            </tr>
          </tbody>
        </table>
      </div>
    `;
  }

  protected _getLegendTemplate(): TemplateResult {
    return html`
      <div id="attribute-list" class="${this.denseLegend ? "attribute-list-dense" : undefined}">
        ${when(
          this.assetAttributes == null || this.assetAttributes.length === 0,
          () => html`
            <div>
              <or-translate value="noAttributesConnected"></or-translate>
            </div>
          `
        )}
        ${
          this.assetAttributes &&
          this.assetAttributes.map(([assetIndex, attr], index) => {
            const asset: Asset | undefined = this.assets[assetIndex];
            if (!asset) {
              // Broken reference: kept in the legend to indicate the config is broken
              return html`
                <div class="attribute-list-item ${this.denseLegend ? "attribute-list-item-dense" : undefined}">
                  <span style="margin-right: 10px; --or-icon-width: 20px;"
                    ><or-icon icon="link-variant-off"></or-icon
                  ></span>
                  <div
                    class="attribute-list-item-label ${this.denseLegend ? "attribute-list-item-label-dense" : undefined}"
                  >
                    <or-translate
                      style="font-size:12px; color:grey; font-style: italic;"
                      value="brokenReference"
                    ></or-translate>
                    <span style="font-size:12px; color:grey;">${Util.camelCaseToSentenceCase(attr.name) ?? ""}</span>
                  </div>
                </div>
              `;
            }
            const colourIndex = index % this.colors.length;
            const color = this.attributeColors.find((x) => x[0].id === asset.id && x[0].name === attr.name)?.[1];
            const descriptors = AssetModelUtil.getAttributeAndValueDescriptors(asset.type, attr.name, attr);
            const label = Util.getAttributeLabel(attr, descriptors[0], asset.type, true);
            const axisNote = this.attributeConfig?.rightAxisAttributes?.find(
              (ar) => asset.id === ar.id && attr.name === ar.name
            )
              ? i18next.t("right")
              : undefined;
            const bgColor = (color ?? this.colors[colourIndex]) || "";
            // Find which aggregation methods are active

            const methodList: string[] = Object.entries(this.attributeConfig || {})
              .filter(([key]) => key.includes("method"))
              .sort(([keyA], [keyB]) => keyA.localeCompare(keyB))
              .reduce<string[]>((list, [key, attributeRefs]) => {
                const isActive = attributeRefs.some(
                  (attrRef: AttributeRef) => attrRef.id === asset.id && attrRef.name === attr.name
                );
                if (isActive) {
                  list.push(i18next.t(`${key}-short`));
                }
                return list;
              }, []);

            return html`
              <div
                class="attribute-list-item ${this.denseLegend ? "attribute-list-item-dense" : undefined}"
                @mouseenter="${() => this._addDatasetHighlight({ id: this.assets[assetIndex].id, name: attr.name })}"
                @mouseleave="${() => this._removeDatasetHighlights()}"
              >
                <span style="margin-right: 10px; --or-icon-width: 20px;"
                  >${getAssetDescriptorIconTemplate(AssetModelUtil.getAssetDescriptor(this.assets[assetIndex].type), undefined, undefined, bgColor.split("#")[1])}</span
                >
                <div
                  class="attribute-list-item-label ${this.denseLegend ? "attribute-list-item-label-dense" : undefined}"
                >
                  <div style="display: flex; justify-content: space-between;">
                    <span style="font-size:12px; ${this.denseLegend ? "margin-right: 8px" : undefined}"
                      >${this.assets[assetIndex].name}</span
                    >
                    ${when(axisNote, () => html`<span style="font-size:12px; color:grey">(${axisNote})</span>`)}
                  </div>
                  <span style="font-size:12px; color:grey; white-space:pre-line;"
                    >${label} <br />
                    (${methodList.join(", ")})</span
                  >
                </div>
              </div>
            `;
          })
        }
      </div>
    `;
  }

  protected _openTimeDialog(startTimestamp?: number, endTimestamp?: number) {
    const startRef: Ref<OrVaadinDateTimePicker> = createRef();
    const endRef: Ref<OrVaadinDateTimePicker> = createRef();
    showDialog(
      new OrMwcDialog()
        .setHeading(i18next.t("timeframe"))
        .setContent(
          () => html`
            <div style="max-width: 480px; display: flex; flex-direction: column; gap: 8px;">
              <or-vaadin-date-time-picker
                ${ref(startRef)}
                required
                value=${startTimestamp ? OrVaadinDateTimePicker.getLocalizedISOString(new Date(startTimestamp)) : undefined}
              >
                <or-translate slot="label" value="start"></or-translate>
              </or-vaadin-date-time-picker>
              <or-vaadin-date-time-picker
                ${ref(endRef)}
                required
                value=${endTimestamp ? OrVaadinDateTimePicker.getLocalizedISOString(new Date(endTimestamp)) : undefined}
              >
                <or-translate slot="label" value="ending"></or-translate>
              </or-vaadin-date-time-picker>
            </div>
          `
        )
        .setActions([
          {
            actionName: "cancel",
            content: "cancel",
          },
          {
            actionName: "ok",
            content: "ok",
            action: () => {
              if (startRef.value?.value && endRef.value?.value && startRef.value.value < endRef.value.value) {
                this._isCustomWindow = true;
                this.timeframe = [new Date(startRef.value.value), new Date(endRef.value.value)];
              } else {
                showSnackbar(undefined, i18next.t("errorOccurred"));
              }
            },
          },
        ])
    );
  }

  /**
   * Removes all active Chart bars color highlights, by reducing/increasing opacity.
   * @protected
   */
  protected _removeDatasetHighlights(chart = this._chart) {
    if (chart) {
      const options = chart.getOption();
      (options.series as BarChartData[] | undefined)?.forEach((series) => {
        series.itemStyle ??= {};
        if (series.itemStyle.opacity === 0.2 || series.itemStyle.opacity === 0.99) {
          series.itemStyle.opacity = 0.31;
        } else {
          series.itemStyle.opacity = 1;
        }
      });
      chart.setOption(options);
    }
  }

  /**
   * Adds a Chart bar color highlight, by reducing/increasing opacity.
   * So the given bar (represented by {@link assetId} and {@link attrName} will be emphasized, while others are less visible.
   * @param attrRef - Asset ID and attribute name to be highlighted
   * @param chart - ECharts instance to add the highlight to.
   */
  protected _addDatasetHighlight(attrRef: AttributeRef, chart = this._chart) {
    if (chart) {
      const options = chart.getOption();
      (options.series as BarChartData[] | undefined)?.forEach((series) => {
        series.itemStyle ??= {};
        if (series.assetId !== attrRef.id || series.attrName !== attrRef.name) {
          if (series.itemStyle.opacity === 0.31) {
            // 0.31 is faint setting, 1 is normal
            series.itemStyle.opacity = 0.2;
          } else {
            series.itemStyle.opacity = 0.3;
          }
        } else if (series.itemStyle.opacity === 0.31) {
          // extra highlight if selected is faint
          series.itemStyle.opacity = 0.99;
        }
      });
      chart.setOption(options);
    }
  }

  // Not the best implementation, but it changes the legend & controls to wrap under the chart.
  // Also sorts the attribute lists horizontally when it is below the chart
  protected _applyChartResponsiveness(): void {
    if (this.shadowRoot) {
      const container = this.shadowRoot.getElementById("container");
      if (container) {
        const bottomLegend: boolean = container.clientWidth < 600;
        container.style.flexDirection = bottomLegend ? "column" : "row";
        const controls = this.shadowRoot.getElementById("controls");
        if (controls) {
          controls.style.flexDirection = bottomLegend ? "row" : "column";
        }
        const periodControls = this.shadowRoot.getElementById("period-controls");
        if (periodControls) {
          periodControls.style.flexDirection = bottomLegend ? "row" : "column";
        }
        const attributeList = this.shadowRoot.getElementById("attribute-list");
        if (attributeList) {
          attributeList.style.gap = bottomLegend ? "4px 12px" : "";
          attributeList.style.maxHeight = bottomLegend ? "90px" : "";
          attributeList.style.flexFlow = bottomLegend ? "row wrap" : "column nowrap";
          attributeList.style.padding = bottomLegend ? "0" : "5px 0";
        }
        this.shadowRoot.querySelectorAll(".attribute-list-item").forEach((item: Element) => {
          (item as HTMLElement).style.minHeight = bottomLegend ? "0px" : "44px";
          (item as HTMLElement).style.paddingLeft = bottomLegend ? "" : "16px";
          (item.children[1] as HTMLElement).style.flexDirection = bottomLegend ? "row" : "column";
          (item.children[1] as HTMLElement).style.gap = bottomLegend ? "4px" : "";
        });
        this._chart?.resize();
      }
    }
    // Update ticks / labels
    const xAxisTicks = Math.max(1, (this._endOfPeriod! - this._startOfPeriod!) / this._intervalConfig!.millis - 1);
    const recommendedTicks = this._chartElem?.clientWidth ? this._chartElem.clientWidth / 50 : Number.MAX_SAFE_INTEGER;
    const maxTicks = Math.floor(recommendedTicks * 1.5);
    const splitNumber = Math.max(1, Math.min(xAxisTicks, maxTicks));
    const [axisMin, axisMax] = getChartAxisBounds(this._startOfPeriod!, this._endOfPeriod!, this._data);
    this._chart?.setOption({
      xAxis: {
        show: splitNumber > 1,
        splitNumber,
        minInterval: this._intervalConfig!.millis,
        axisLabel: {
          rotate: splitNumber > recommendedTicks ? 45 : 0,
        },
      },
    });
  }

  protected _cleanup() {
    if (this._chart) {
      console.debug("Destroying bar chart...");
      this._toggleChartEventListeners(false);
      this._chart.dispose();
      this._chart = undefined;
      this.requestUpdate();
    }
  }

  protected _getTimeSelectionDates(timePrefixSelected: string, timeWindowSelected: string): [Date, Date] {
    let startDate: moment.Moment = moment();
    let endDate: moment.Moment = moment();

    const timeWindow: [moment.unitOfTime.DurationConstructor, number] | undefined =
      this.timeWindowOptions?.get(timeWindowSelected);

    if (!timeWindow) {
      throw new Error(`Unsupported time window selected: ${timeWindowSelected}`);
    }

    const [unit, value]: [moment.unitOfTime.DurationConstructor, number] = timeWindow;

    switch (timePrefixSelected) {
      case "this": {
        if (value === 1) {
          // For singulars like this hour
          startDate = moment().startOf(unit);
          endDate = moment().endOf(unit);
        } else {
          // For multiples like this 5 min, put now in the middle
          startDate = moment().subtract(value * 0.5, unit);
          endDate = moment().add(value * 0.5, unit);
        }
        break;
      }
      case "last": {
        startDate = moment().subtract(value, unit).startOf(unit);
        if (value === 1) {
          // For singulars like last hour
          endDate = moment().startOf(unit);
        } else {
          // For multiples like last 5 min
          endDate = moment();
        }
        break;
      }
      default: {
        console.error("Could not get time selection dates. The time prefix was not set correctly.");
        break;
      }
    }
    return [startDate.toDate(), endDate.toDate()];
  }

  protected _shiftTimeframe(
    currentStart: Date,
    currentEnd: Date,
    timeWindowSelected: string,
    direction: TimeframeDirection
  ) {
    if (!this.timeWindowOptions.has(timeWindowSelected)) {
      throw new Error(`Unsupported time window selected: ${timeWindowSelected}`);
    }

    const [unit, value] = this.timeWindowOptions.get(timeWindowSelected)!;
    const duration =
      this._navigationDuration ??
      getNavigationDuration(currentStart.getTime(), currentEnd.getTime(), this._isCustomWindow, unit, value);
    this.timeframe = shiftTimeframe(currentStart, currentEnd, duration, unit, value, direction, this._isCustomWindow);
  }

  protected _getInterval(
    start: number,
    end: number,
    selectedInterval: BarChartInterval,
    exact = false
  ): IntervalConfig {
    const hoursDiff = (end - start) / 1000 / 60 / 60;
    const diffInHours = exact ? hoursDiff : Math.round(hoursDiff);
    if (selectedInterval === BarChartInterval.AUTO) {
      // Returns number of steps, interval size and moment.js time format
      if (diffInHours <= 1) {
        return {
          displayName: "5Minutes-auto",
          steps: 5,
          orFormat: DatapointInterval.MINUTE,
          momentFormat: "minutes",
          millis: 300000,
        };
      } else if (diffInHours <= 6) {
        return {
          displayName: "30Minutes-auto",
          steps: 30,
          orFormat: DatapointInterval.MINUTE,
          momentFormat: "minutes",
          millis: 1800000,
        };
      } else if (diffInHours <= 24) {
        // hour if up to one day
        return {
          displayName: "hour-auto",
          steps: 1,
          orFormat: DatapointInterval.HOUR,
          momentFormat: "hours",
          millis: 3600000,
        };
      } else if (diffInHours <= 744) {
        // one day if up to one month
        return {
          displayName: "day-auto",
          steps: 1,
          orFormat: DatapointInterval.DAY,
          momentFormat: "days",
          millis: 86400000,
        };
      } else if (diffInHours <= 8760) {
        // one week if up to 1 year
        return {
          displayName: "week-auto",
          steps: 1,
          orFormat: DatapointInterval.WEEK,
          momentFormat: "weeks",
          millis: 604800000,
        };
      } else {
        // one month if more than a year
        return {
          displayName: "month-auto",
          steps: 1,
          orFormat: DatapointInterval.MONTH,
          momentFormat: "months",
          millis: 2592000000,
        };
      }
    } else if (selectedInterval === BarChartInterval.NONE) {
      // Set interval to total time span
      const millis = this._endOfPeriod! - this._startOfPeriod!;
      const steps = Math.ceil(millis / 60000);
      return { displayName: "none", steps, orFormat: DatapointInterval.MINUTE, momentFormat: "minutes", millis };
    }
    // Otherwise, check if select interval is a valid combination with set time window
    const intervalProp: IntervalConfig = this.intervalOptions.get(selectedInterval)!;
    const selectedIntervalHours = moment.duration(intervalProp.steps, intervalProp.momentFormat).asHours();

    if (selectedIntervalHours <= diffInHours) {
      return intervalProp; // Already valid so quit
    }

    // If no selected interval is larger than timeframe, switch to the first next valid timeframe.
    console.warn("Selected interval is larger than the timeframe! Changing to a valid timeframe...");
    const intervalOptions = Array.from(this.intervalOptions.entries());

    for (let i = intervalOptions.length - 1; i >= 0; i--) {
      const [_, value] = intervalOptions[i];
      const intervalHours = moment.duration(value.steps, value.momentFormat).asHours();

      if (intervalHours <= diffInHours) {
        return value; // Found a valid option
      }
    }
    // If no valid option is found, return the smallest available option
    return intervalOptions[0][1];
  }

  protected async _loadData() {
    if (
      this._data ||
      !this.assetAttributes ||
      !this.assets ||
      this.assets.length === 0 ||
      this.assetAttributes.length === 0 ||
      !this.datapointQuery
    ) {
      return;
    }

    if (this._loading) {
      if (this._dataAbortController) {
        this._dataAbortController.abort("Data request overridden");
        delete this._dataAbortController;
      } else {
        return;
      }
    }

    this._loading = true;
    this._latestError = undefined;
    const data: BarChartData[] = [];
    let promises;

    try {
      this._dataAbortController = new AbortController();
      promises = this.assetAttributes.map(async ([assetIndex, attribute], index) => {
        const asset = this.assets[assetIndex];
        if (!asset) {
          return;
        }
        const shownOnRightAxis = !!this.attributeConfig?.rightAxisAttributes?.find(
          (ar) => ar.id === asset.id && ar.name === attribute.name
        );
        const color = this.attributeColors.find((x) => x[0].id === asset.id && x[0].name === attribute.name)?.[1];
        const descriptors = AssetModelUtil.getAttributeAndValueDescriptors(asset.type, attribute.name, attribute);
        const faint = !!this.attributeConfig?.faintAttributes?.find(
          (ar) => ar.id === asset.id && ar.name === attribute.name
        );
        const label = Util.getAttributeLabel(attribute, descriptors[0], asset.type, false);
        const unit = Util.resolveUnits(Util.getAttributeUnits(attribute, descriptors[0], asset.type));
        const colourIndex = index % this.colors.length;
        const options = { signal: this._dataAbortController?.signal };

        // Map calculation methods to their corresponding attribute arrays and formulas
        const methodMapping: { [key: string]: { active: boolean; formula: AssetDatapointIntervalQueryFormula } } = {
          AVG: {
            active: !!this.attributeConfig?.methodAvgAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.AVG,
          },
          COUNT: {
            active: !!this.attributeConfig?.methodCountAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.COUNT,
          },
          DIFFERENCE: {
            active: !!this.attributeConfig?.methodDifferenceAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.DIFFERENCE,
          },
          MAX: {
            active: !!this.attributeConfig?.methodMaxAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.MAX,
          },
          MEDIAN: {
            active: !!this.attributeConfig?.methodMedianAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.MEDIAN,
          },
          MIN: {
            active: !!this.attributeConfig?.methodMinAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.MIN,
          },
          MODE: {
            active: !!this.attributeConfig?.methodModeAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.MODE,
          },
          SUM: {
            active: !!this.attributeConfig?.methodSumAttributes?.find(
              (ar) => ar.id === asset.id && ar.name === attribute.name
            ),
            formula: AssetDatapointIntervalQueryFormula.SUM,
          },
        };
        // Iterate over the mapping, make a dataset for every active method
        for (const [key, value] of Object.entries(methodMapping).sort(([keyA], [keyB]) => keyA.localeCompare(keyB))) {
          if (value.active) {
            // Initiate query Attribute Data
            const dataset = await this._loadAttributeData(
              asset,
              attribute,
              color ?? this.colors[colourIndex],
              this._startOfPeriod!,
              this._endOfPeriod!,
              value.formula,
              faint,
              `${asset.name} | ${label} \n${i18next.t(value.formula)}`,
              options,
              unit
            );
            dataset.index = Object.keys(methodMapping).indexOf(key) * 1000 + (index + 1);
            dataset.assetId = asset.id;
            dataset.attrName = attribute.name;
            dataset.unit = unit;
            dataset.yAxisIndex = shownOnRightAxis ? 1 : 0;
            dataset.color = color ?? this.colors[colourIndex];
            data.push(dataset);
          }
        }
      });
      // Await HTTP requests
      if (promises) {
        await Promise.all(promises);
      }
      // Sort data in correct order, for inserting it into the chart.
      const sortedData = data.sort((a, b) => b.index! - a.index!).reverse();

      // If stacked, remove all labels for every fill except the top
      if (this.stacked) {
        const formulas = new Set(sortedData.map((d) => d.stack));
        if (formulas.size > 1) {
          formulas.forEach((formula) => {
            const lastEntry = [...sortedData].reverse().find((d) => d.stack === formula);
            if (lastEntry) lastEntry.label!.show = true;
          });
        }
      }

      // Sort data in correct order, and insert data in the chart
      this._data = sortedData;
      this._loading = false;
    } catch (ex) {
      console.error(ex);
      if ((ex as Error)?.message === "canceled") {
        return; // If request has been canceled (using AbortController); return, and prevent _loading is set to false.
      }
      this._loading = false;

      if (isAxiosError(ex)) {
        if (ex.message.includes("timeout")) {
          this._latestError = "noAttributeDataTimeout";
          return;
        } else if (ex.response?.status === 413) {
          this._latestError = "datapointRequestTooLarge";
          return;
        }
      }
      this._latestError = "errorOccurred";
    }
  }

  protected async _loadAttributeData(
    asset: Asset,
    attribute: Attribute<any>,
    color: string,
    from: number,
    to: number,
    formula: AssetDatapointIntervalQueryFormula,
    faint = false,
    label?: string,
    options?: any,
    _unit?: any
  ): Promise<BarChartData> {
    const dataset = this._getDefaultDatasetOptions(label ?? "", formula, color, faint);

    if (asset.id && attribute.name && this.datapointQuery) {
      const datapointQuery = structuredClone(this.datapointQuery) as AssetDatapointIntervalQuery; // recreating object, since the changes shouldn't apply to parent components; only or-attribute-barchart itself.

      datapointQuery.fromTimestamp = from;
      datapointQuery.toTimestamp = to - 1; // subtract 1 millisecond
      datapointQuery.formula = formula;
      datapointQuery.gapFill = true;
      datapointQuery.interval = `${this._intervalConfig?.steps ?? "???"} ${this._intervalConfig?.orFormat ?? "???"}`; // for example: "5 minute"

      console.debug(
        `Requesting datapoints for '${attribute.name}' of ${asset.id}, between ${moment(from).format("lll")} and ${moment(to - 1).format("lll")}...`
      );
      const response = await manager.rest.api.AssetDatapointResource.getDatapoints(
        asset.id,
        attribute.name,
        datapointQuery,
        options
      );

      let data: ValueDatapoint<any>[] = [];

      if (response.status === 200) {
        data = response.data.map((point) => ({ x: point.x, y: point.y }) as ValueDatapoint<any>);

        // map to dataset and position to the middle of interval instead of start time
        dataset.data = data.map((point) => [
          (point.x ?? 0) + 0.5 * this._intervalConfig!.millis,
          point.y?.toFixed(this.decimals),
        ]);
      }
    }
    return dataset;
  }

  protected _updateChartData() {
    if (!this._chart) {
      console.error("Could not update bar chart data; the bar chart is not initialized yet.");
      return;
    }

    // Update ticks / labels
    const xAxisTicks = Math.max(1, (this._endOfPeriod! - this._startOfPeriod!) / this._intervalConfig!.millis - 1);
    const recommendedTicks = this._chartElem?.clientWidth ? this._chartElem.clientWidth / 50 : Number.MAX_SAFE_INTEGER;
    const maxTicks = Math.floor(recommendedTicks * 1.5);
    const splitNumber = Math.max(1, Math.min(xAxisTicks, maxTicks));
    const [axisMin, axisMax] = getChartAxisBounds(this._startOfPeriod!, this._endOfPeriod!, this._data);

    // Update chart
    this._chart.setOption({
      xAxis: {
        show: splitNumber > 1,
        splitNumber,
        minInterval: this._intervalConfig!.millis,
        min: axisMin,
        max: axisMax,
        axisLabel: {
          interval: this._intervalConfig?.millis,
          rotate: splitNumber > recommendedTicks ? 45 : 0,
        },
      },
      dataZoom: {
        minValueSpan: (this._intervalConfig?.millis ?? 0) * 4,
      },
      series: [
        ...(this._data ?? []).map((series) => ({
          ...series,
        })),
      ],
    } as ECChartOption);
  }

  protected _toggleChartEventListeners(connect: boolean) {
    if (connect) {
      this._containerResizeObserver = new ResizeObserver(() => this._applyChartResponsiveness());
      this._containerResizeObserver.observe(this.shadowRoot!.getElementById("container") as HTMLElement);
    } else {
      this._containerResizeObserver?.disconnect();
      this._containerResizeObserver = undefined;
    }
  }

  protected _getDefaultChartOptions(): ECChartOption {
    const xAxisTicks = Math.max(1, (this._endOfPeriod! - this._startOfPeriod!) / this._intervalConfig!.millis - 1);
    const recommendedTicks = this._chartElem?.clientWidth ? this._chartElem.clientWidth / 50 : Number.MAX_SAFE_INTEGER;
    const maxTicks = Math.floor(recommendedTicks * 1.5);
    const splitNumber = Math.max(1, Math.min(xAxisTicks, maxTicks));
    return {
      grid: {
        show: true,
        backgroundColor: this._style.getPropertyValue("--internal-or-asset-tree-background-color"),
        borderColor: this._style.getPropertyValue("--internal-or-chart-text-color"),
        left: 10,
        right: 10,
        top: 10,
        bottom: 10,
        containLabel: true,
      },
      backgroundColor: this._style.getPropertyValue("--internal-or-asset-tree-background-color"),
      tooltip: {
        trigger: "axis",
        appendTo: "body",
        confine: true,
        extraCssText: "white-space: normal; word-wrap: break-word;",
        axisPointer: {
          type: "cross",
          label: {
            show: true,
            formatter: (params) => {
              if (params.axisDimension === "x") {
                const time = moment(params.value as number);
                const startTime: moment.Moment = time
                  .clone()
                  .subtract((this._intervalConfig?.millis ?? 0) / 2, "milliseconds");
                const endTime: moment.Moment = time
                  .clone()
                  .add((this._intervalConfig?.millis ?? 0) / 2, "milliseconds");
                switch (this._intervalConfig?.orFormat) {
                  case DatapointInterval.MINUTE:
                  case DatapointInterval.HOUR: {
                    return `${startTime.format("lll")} - ${endTime.format("lll")}`;
                  }
                  case DatapointInterval.DAY: {
                    return `${time.format("dddd, LL")}`;
                  }
                  case DatapointInterval.WEEK: {
                    return `${i18next.t("week")} ${startTime.format("w, LL")} - ${endTime.format("LL")}`;
                  }
                  default: {
                    return `${startTime.format("LL")} - ${endTime.format("LL")}`;
                  }
                }
              } else {
                return Number(params.value).toFixed(this.decimals).toString();
              }
            },
          },
        },
      },
      xAxis: {
        type: "time",
        show: xAxisTicks > 1,
        axisLine: {
          lineStyle: { color: this._style.getPropertyValue("--internal-or-chart-text-color") },
        },
        splitNumber,
        minInterval: this._intervalConfig!.millis,
        min: this._startOfPeriod,
        max: this._endOfPeriod,
        boundaryGap: false as any,
        splitArea: {
          show: true,
          areaStyle: {
            color: ["transparent", "rgba(0, 0, 0, 0.05)"],
          },
        },
        axisLabel: {
          rotate: splitNumber > recommendedTicks ? 45 : 0,
          interval: this._intervalConfig?.millis,
          fontSize: 10,
          formatter: {
            year: "1-{MMM}-{yyyy}",
            month: "1-{MMM}-'{yy}",
            day: "{d}-{MMM}",
            hour: "{HH}:{mm}",
            minute: "{HH}:{mm}",
            second: "{HH}:{mm}:{ss}",
            millisecond: "{d}-{MMM} {HH}:{mm}",
            // @ts-ignore
            none: "{MMM}-{dd} {HH}:{mm}",
          },
        },
      },
      yAxis: [
        {
          type: "value",
          axisLine: { lineStyle: { color: this._style.getPropertyValue("--internal-or-chart-text-color") } },
          boundaryGap: ["10%", "10%"],
          scale: true,
          min: (this.chartOptions?.options as any)?.scales?.y?.min,
          max: (this.chartOptions?.options as any)?.scales?.y?.max,
        },
        {
          type: "value",
          show: (this.attributeConfig?.rightAxisAttributes?.length ?? 0) > 0,
          axisLine: { lineStyle: { color: this._style.getPropertyValue("--internal-or-chart-text-color") } },
          boundaryGap: ["10%", "10%"],
          scale: true,
          min: (this.chartOptions?.options as any)?.scales?.y1?.min,
          max: (this.chartOptions?.options as any)?.scales?.y1?.max,
        },
      ],
      dataZoom: [
        {
          type: "inside",
          minValueSpan: (this._intervalConfig?.millis ?? 0) * 4,
        },
      ],
      series: [],
    };
  }

  protected _getDefaultDatasetOptions(name: string, formula: string, color: string, faint = false): BarChartData {
    const hasMultipleMethods =
      Object.entries(this.attributeConfig || {}).filter(([key, value]) => key.startsWith("method") && value?.length > 0)
        .length > 1;
    return {
      name,
      type: "bar",
      data: [] as [number, number][],
      stack: this.stacked ? `${formula}` : undefined,
      itemStyle: {
        color,
        opacity: faint ? 0.31 : 1,
      },
      emphasis: {},
      label: {
        show: !this.stacked,
        align: "left",
        verticalAlign: "middle",
        position: "top",
        fontStyle: "italic",
        fontSize: 10,
        rotate: 90,
        distance: 10,
        formatter: (params): string => {
          if (hasMultipleMethods) {
            const data = this._data?.[params.seriesIndex ?? 0]?.data;
            const firstIndex = data?.findIndex((x) => (x as [number, number])[1] != null);
            if (firstIndex === params.dataIndex) {
              return formula;
            }
          }
          return "";
        },
      },
    } as BarChartData;
  }

  public static getDefaultTimePrefixOptions(): string[] {
    return ["this", "last"];
  }

  public static getDefaultTimeWindowOptions(): Map<string, [moment.unitOfTime.DurationConstructor, number]> {
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

  public static getDefaultIntervalOptions(): Map<BarChartInterval, IntervalConfig> {
    return new Map<BarChartInterval, IntervalConfig>([
      [
        BarChartInterval.AUTO,
        { displayName: "auto", steps: 1, orFormat: DatapointInterval.MINUTE, momentFormat: "minutes", millis: 60000 },
      ],
      [
        BarChartInterval.ONE_MINUTE,
        {
          displayName: "1Minute",
          steps: 1,
          orFormat: DatapointInterval.MINUTE,
          momentFormat: "minutes",
          millis: 60000,
        },
      ],
      [
        BarChartInterval.FIVE_MINUTES,
        {
          displayName: "5Minutes",
          steps: 5,
          orFormat: DatapointInterval.MINUTE,
          momentFormat: "minutes",
          millis: 300000,
        },
      ],
      [
        BarChartInterval.FIFTEEN_MINUTES,
        {
          displayName: "15Minutes",
          steps: 15,
          orFormat: DatapointInterval.MINUTE,
          momentFormat: "minutes",
          millis: 900000,
        },
      ],
      [
        BarChartInterval.THIRTY_MINUTES,
        {
          displayName: "30Minutes",
          steps: 30,
          orFormat: DatapointInterval.MINUTE,
          momentFormat: "minutes",
          millis: 1800000,
        },
      ],
      [
        BarChartInterval.ONE_HOUR,
        { displayName: "hour", steps: 1, orFormat: DatapointInterval.HOUR, momentFormat: "hours", millis: 3600000 },
      ],
      [
        BarChartInterval.ONE_DAY,
        { displayName: "day", steps: 1, orFormat: DatapointInterval.DAY, momentFormat: "days", millis: 86400000 },
      ],
      [
        BarChartInterval.ONE_WEEK,
        { displayName: "week", steps: 1, orFormat: DatapointInterval.WEEK, momentFormat: "weeks", millis: 604800000 },
      ],
      [
        BarChartInterval.ONE_MONTH,
        {
          displayName: "month",
          steps: 1,
          orFormat: DatapointInterval.MONTH,
          momentFormat: "months",
          millis: 2592000000,
        },
      ],
      [
        BarChartInterval.ONE_YEAR,
        { displayName: "year", steps: 1, orFormat: DatapointInterval.YEAR, momentFormat: "years", millis: 31536000000 },
      ],
    ]);
  }
}
