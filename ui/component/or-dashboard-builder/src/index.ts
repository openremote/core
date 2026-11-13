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
import { css, html, type PropertyValues, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query, state } from "lit/decorators.js";
import { when } from "lit/directives/when.js";
import { styleMap } from "lit/directives/style-map.js";
import "./or-dashboard-tree";
import "./or-dashboard-browser";
import "./or-dashboard-preview";
import "./or-dashboard-widgetsettings";
import "./or-dashboard-boardsettings";
import "./controls/dashboard-refresh-controls";
import "@openremote/or-icon";
import { style } from "./style";
import {
  ClientRole,
  type Dashboard,
  DashboardAccess,
  DashboardRefreshInterval,
  type DashboardScalingPreset,
  type DashboardScreenPreset,
  type DashboardTemplate,
  type DashboardWidget,
} from "@openremote/model";
import manager, { DefaultColor1, DefaultColor3, DefaultColor5, Util } from "@openremote/core";
import type { ListItem } from "@openremote/or-mwc-components/or-mwc-list";
import type { OrMwcTabItem } from "@openremote/or-mwc-components/or-mwc-tabs";
import "@openremote/or-mwc-components/or-mwc-tabs";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import { i18next, translate } from "@openremote/or-translate";
import { DashboardKeyEmitter } from "./or-dashboard-keyhandler";
import type { OrDashboardPreview } from "./or-dashboard-preview";
import type { WidgetManifest } from "./util/or-widget";
import { ChartWidget } from "./widgets/chart-widget";
import { GaugeWidget } from "./widgets/gauge-widget";
import { type IntervalSelectEvent, intervalToMillis } from "./controls/dashboard-refresh-controls";
import { ImageWidget } from "./widgets/image-widget";
import { KpiWidget } from "./widgets/kpi-widget";
import { MapWidget } from "./widgets/map-widget";
import { AttributeInputWidget } from "./widgets/attribute-input-widget";
import { TableWidget } from "./widgets/table-widget";
import { GatewayWidget } from "./widgets/gateway-widget";
import { BarChartWidget } from "./widgets/barchart-widget";
import type { OrVaadinTextField } from "@openremote/or-vaadin-components/or-vaadin-text-field";
import { getConfirmDialogContent, showConfirmDialog } from "@openremote/or-vaadin-components/or-vaadin-confirm-dialog";
export { intervalToMillis } from "./controls/dashboard-refresh-controls";
export { OrDashboardPreview } from "./or-dashboard-preview";

// language=CSS
const styling = css`
  @media only screen and (min-width: 641px) {
    #tree {
      min-width: 300px !important;
    }
  }
  @media only screen and (max-width: 641px) {
    #tree {
      flex: 1 !important;
    }
    #builder {
      max-height: inherit !important;
    }
  }

  #tree {
    flex: 0;
    align-items: stretch;
    z-index: 1;
    box-shadow: rgb(0 0 0 / 21%) 0px 1px 3px 0px;
  }

  /* Header related styling */
  #header {
    background: var(--or-app-color1, ${unsafeCSS(DefaultColor1)});
  }
  #header-wrapper {
    padding: 14px 30px;
    display: flex;
    flex-direction: row;
    align-items: center;
    border-bottom: 1px solid ${unsafeCSS(DefaultColor5)};
  }
  #header-title {
    display: flex;
    align-items: baseline;
    font-size: 18px;
  }
  #header-title > or-icon {
    margin-right: 10px;
  }
  #header-actions {
    flex: 1 1 auto;
    text-align: right;
  }
  #header-actions-content {
    display: flex;
    flex-direction: row;
    align-items: center;
    float: right;
  }

  /* Header related styling */
  @media screen and (max-width: 700px) {
    #fullscreen-header-wrapper {
      padding: 11px !important;
    }
  }
  #fullscreen-header-wrapper {
    min-height: 36px;
    padding: 20px 30px 15px;
    display: flex;
    flex-direction: row;
    align-items: center;
  }
  #fullscreen-header-title {
    font-size: 18px;
    font-weight: bold;
    color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
  }
  #fullscreen-header-actions {
    flex: 1 1 auto;
    text-align: right;
  }
  #fullscreen-header-actions-content {
    display: flex;
    flex-direction: row;
    align-items: center;
    float: right;
  }

  /* ----------------------------- */
  /* Editor/builder related styling */
  #builder {
    flex: 1 0 auto;
    height: 100%;
  }

  /* ----------------------------- */
  /* Sidebar related styling (drag and drop widgets / configuration) */
  #sidebar {
    vertical-align: top;
    position: relative;
    width: 300px;
    background: white;
    border-left: 1px solid ${unsafeCSS(DefaultColor5)};
  }
  #sidebar-widget-headeractions {
    flex: 0;
    display: flex;
    flex-direction: row;
    padding-right: 5px;
  }
  .settings-container {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
  #browser {
    flex-grow: 1;
    align-items: stretch;
    z-index: 1;
    max-width: 300px;
  }

  #save-btn {
    margin-left: 15px;
  }
  #view-btn {
    margin-left: 18px;
  }

  .small-btn {
    height: 36px;
    margin-top: -12px;
  }

  .hidescroll {
    -ms-overflow-style: none; /* for Internet Explorer, Edge */
    scrollbar-width: none; /* for Firefox */
  }
  .hidescroll::-webkit-scrollbar {
    display: none; /* for Chrome, Safari, and Opera */
  }
`;

export interface DashboardBuilderConfig {
  // no configuration built yet
}
export const MAX_BREAKPOINT = 1000000;

// Enum to Menu String method
export function scalingPresetToString(scalingPreset: DashboardScalingPreset | undefined): string {
  return scalingPreset != null ? i18next.t("dashboard.presets." + scalingPreset.toLowerCase()) : "undefined";
}
export function dashboardAccessToString(access: DashboardAccess): string {
  return i18next.t("dashboard.access." + access.toLowerCase());
}

export function sortScreenPresets(
  presets: DashboardScreenPreset[],
  largetosmall: boolean = false
): DashboardScreenPreset[] {
  return presets.sort((a, b) => {
    if (a.breakpoint != null && b.breakpoint != null) {
      if (a.breakpoint > b.breakpoint) {
        return largetosmall ? 1 : -1;
      }
      if (a.breakpoint < b.breakpoint) {
        return largetosmall ? 1 : -1;
      }
    }
    return 0;
  });
}

export function getActivePreset(
  gridWidth: number,
  presets: DashboardScreenPreset[]
): DashboardScreenPreset | undefined {
  let activePreset: DashboardScreenPreset | undefined;
  sortScreenPresets(presets, true).forEach((preset) => {
    if (preset.breakpoint != null && gridWidth <= preset.breakpoint) {
      activePreset = preset;
    }
  });
  return activePreset;
}

// A map containing a unique identifier as key, and a WidgetManifest (displayName, displayIcon and HTML tags) as value.
// We will use this for or-dashboard-browser and to initialize widgets, based on the html tags.
export const widgetTypes: Map<string, WidgetManifest> = new Map<string, WidgetManifest>();

export function registerWidgetTypes() {
  widgetTypes.set("attributeinput", AttributeInputWidget.getManifest());
  widgetTypes.set("barchart", BarChartWidget.getManifest());
  widgetTypes.set("gateway", GatewayWidget.getManifest());
  widgetTypes.set("gauge", GaugeWidget.getManifest());
  widgetTypes.set("image", ImageWidget.getManifest());
  widgetTypes.set("kpi", KpiWidget.getManifest());
  widgetTypes.set("linechart", ChartWidget.getManifest());
  widgetTypes.set("map", MapWidget.getManifest());
  widgetTypes.set("table", TableWidget.getManifest());
}

@customElement("or-dashboard-builder")
export class OrDashboardBuilder extends translate(i18next)(OrElement) {
  // Importing Styles; the unsafe GridStack css, and all custom css
  static get styles() {
    return [styling, style];
  }

  @property()
  protected readonly config: DashboardBuilderConfig | undefined;

  @property() // (originally from URL)
  protected readonly editMode: boolean = false;

  @property()
  protected readonly fullscreen: boolean = true;

  @property() // ID of the selected dashboard (originally from URL)
  protected readonly selectedId: string | undefined;

  @property()
  protected realm: string = manager.displayRealm;

  @property() // REQUIRED userId
  protected readonly userId!: string;

  @property()
  protected readonly readonly: boolean = true;

  /* ------------------- */

  @state()
  protected dashboards: Dashboard[] | undefined;

  @state() // Separate local template object
  protected currentTemplate: DashboardTemplate | undefined;

  @state()
  protected selectedDashboard: Dashboard | undefined;

  @state()
  protected selectedWidgetId: string | undefined;

  @state() // Used to toggle the SAVE button depending on whether changes have been made.
  protected initialDashboardJSON: string | undefined;

  @state() // Used to toggle the SAVE button depending on whether changes have been made.
  protected initialTemplateJSON: string | undefined;

  @state()
  protected refreshInterval: DashboardRefreshInterval = DashboardRefreshInterval.OFF;

  @state()
  protected isInitializing: boolean;

  @state()
  protected isLoading: boolean;

  @state() // Whether changes have been made
  protected hasChanged: boolean;

  @query("or-dashboard-preview")
  protected dashboardPreview?: OrDashboardPreview;

  protected refreshTimer?: ReturnType<typeof setInterval>;
  private readonly keyEmitter: DashboardKeyEmitter = new DashboardKeyEmitter();

  /* ------------- */

  constructor() {
    super();
    this.isInitializing = true;
    this.isLoading = true;
    this.hasChanged = false;

    registerWidgetTypes();

    this.updateComplete.then(() => {
      this.loadAllDashboards(this.realm);
    });
  }

  connectedCallback() {
    super.connectedCallback();
    this.keyEmitter.addListener("delete", (_e: KeyboardEvent) => {
      if (this.selectedWidgetId) {
        const selectedWidget = this.selectedDashboard?.template?.widgets?.find((w) => w.id == this.selectedWidgetId);
        if (selectedWidget) {
          showConfirmDialog(
            this.shadowRoot!,
            html`
              <or-vaadin-confirm-dialog @confirm=${() => this.deleteWidget(selectedWidget)}>
                ${getConfirmDialogContent("error", "areYouSure", "dashboard.deleteWidgetWarning", "delete", "cancel")}
              </or-vaadin-confirm-dialog>
            `
          );
        }
      }
    });
    this.keyEmitter.addListener("deselect", (_e: KeyboardEvent) => {
      this.deselectWidget();
    });
    this.keyEmitter.addListener("save", (_e: KeyboardEvent) => {
      this.saveDashboard();
    });
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.keyEmitter.removeAllListeners();
  }

  willUpdate(changedProps: PropertyValues) {
    super.willUpdate(changedProps);

    this.isLoading = this.dashboards == undefined;
    this.isInitializing = this.dashboards == undefined;

    // On any update (except widget selection), check whether hasChanged should be updated.
    if (!(changedProps.size === 1 && changedProps.has("selectedWidget"))) {
      // Check for dashboard changes
      const initialDashboard = this.initialDashboardJSON ? JSON.parse(this.initialDashboardJSON) : undefined;
      const dashboardDiff = Util.difference(this.selectedDashboard, initialDashboard);
      if (Object.keys(dashboardDiff).length > 0) {
        this.hasChanged = true;
        console.debug("Dashboard has detected changes! Diff:", dashboardDiff);
      }

      // Check for template changes
      else {
        const initialTemplate = this.initialTemplateJSON ? JSON.parse(this.initialTemplateJSON) : undefined;
        const templateDiff = Util.difference(this.currentTemplate, initialTemplate);
        if (Object.keys(templateDiff).length > 0) {
          this.hasChanged = true;
          console.debug("Template has detected changes! Diff:", templateDiff);
        } else {
          // If no changes detected between dashboard nor template
          this.hasChanged = false;
        }
      }
    }

    // Support for realm switching
    if (changedProps.has("realm") && changedProps.get("realm") !== undefined && this.realm) {
      this.loadAllDashboards(this.realm);
    }

    // Any update on the dashboard
    if (changedProps.has("selectedDashboard")) {
      this.deselectWidget();
      this.currentTemplate = this.selectedDashboard?.template;
      if (!this.editMode) {
        this.refreshInterval = this.currentTemplate?.refreshInterval || DashboardRefreshInterval.OFF;
        changedProps.set("refreshInterval", this.refreshInterval);
      }
      this.dispatchEvent(new CustomEvent("selected", { detail: this.selectedDashboard }));
    }

    // When edit/view mode gets toggled
    if (changedProps.has("editMode")) {
      this.deselectWidget();
      this.refreshInterval = DashboardRefreshInterval.OFF;
      this.showDashboardTree = true;
      if (this.editMode) {
        this.refreshInterval = DashboardRefreshInterval.OFF;
      } else {
        this.refreshInterval = this.currentTemplate?.refreshInterval || DashboardRefreshInterval.OFF;
      }
      changedProps.set("refreshInterval", this.refreshInterval);
    }

    // When refresh interval has changed
    if (changedProps.has("refreshInterval") && this.refreshInterval) {
      this.setRefreshTimer(intervalToMillis(this.refreshInterval));
    }
  }

  protected setRefreshTimer(millis: number | undefined) {
    this.clearRefreshTimer();
    if (millis !== undefined) {
      this.refreshTimer = setInterval(() => {
        this.deselectWidget();
        this.dashboardPreview?.refreshWidgets();
      }, millis);
    }
  }

  protected clearRefreshTimer() {
    if (this.refreshTimer) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = undefined;
    }
  }

  async loadAllDashboards(realm: string) {
    // Getting dashboards
    await manager.rest.api.DashboardResource.getAllRealmDashboards(realm)
      .then((result) => {
        this.dashboards = result.data;
      })
      .catch((reason) => {
        showSnackbar(undefined, "errorOccurred");
        console.error(reason);
      });

    // Setting dashboard if selectedId is given by parent component
    if (this.selectedId !== undefined) {
      this.selectedDashboard = this.dashboards?.find((x) => {
        return x.id == this.selectedId;
      });
    }
  }

  /* ------------- */

  // On every property update
  updated(changedProperties: Map<string, any>) {
    super.updated(changedProperties);

    // Update on the Grid and its widget
    if (changedProperties.has("currentTemplate")) {
      if (this.selectedDashboard != null) {
        this.selectedDashboard.template = this.currentTemplate;
      }
    }
  }

  /* ----------------- */

  protected onWidgetCreation(widget: DashboardWidget): void {
    const tempTemplate = JSON.parse(JSON.stringify(this.currentTemplate)) as DashboardTemplate;
    if (!tempTemplate.widgets) {
      tempTemplate.widgets = [];
    }
    tempTemplate.widgets.push(widget);
    this.currentTemplate = tempTemplate;
  }

  deleteWidget(widget: DashboardWidget) {
    if (this.currentTemplate != null && this.currentTemplate.widgets != null) {
      const tempTemplate = this.currentTemplate;
      tempTemplate.widgets = tempTemplate.widgets?.filter((x: DashboardWidget) => {
        return x.id != widget.id;
      });
      this.currentTemplate = tempTemplate;
    }
    if (this.selectedWidgetId === widget.id) {
      this.deselectWidget();
    }
  }

  /* ------------------------------ */

  selectWidget(widget: DashboardWidget): void {
    const foundWidget = this.currentTemplate?.widgets?.find((x) => {
      return x.gridItem?.id == widget.gridItem?.id;
    });
    if (foundWidget != null) {
      this.selectedWidgetId = foundWidget.id;
    } else {
      console.error("The selected widget does not exist!");
    }
  }

  deselectWidget() {
    this.selectedWidgetId = undefined;
  }

  /* --------------------- */

  selectDashboard(dashboard: Dashboard | undefined) {
    if (this.dashboards != null) {
      // If switching between dashboards, check if there were changes, and reset to 'initialDashboardJSON' if needed.
      if (this.selectedDashboard && this.initialDashboardJSON) {
        const index = this.dashboards.indexOf(this.selectedDashboard);
        if (index && JSON.stringify(this.selectedDashboard) !== this.initialDashboardJSON) {
          this.dashboards[index] = JSON.parse(this.initialDashboardJSON) as Dashboard;
        }
      }

      // Update selected dashboard
      this.selectedDashboard = dashboard
        ? this.dashboards.find((x) => {
            return x.id == dashboard.id;
          })
        : undefined;
      this.initialDashboardJSON = JSON.stringify(this.selectedDashboard);
      this.initialTemplateJSON = JSON.stringify(this.selectedDashboard?.template);
    }
  }

  _onDashboardNameChanged(ev: Event) {
    const elem = ev.currentTarget as OrVaadinTextField;
    if (!elem?.checkValidity()) {
      return;
    }
    if (this.selectedDashboard != null) {
      this.selectedDashboard.displayName = elem.value;
      this.requestUpdate("selectedDashboard");
    }
  }

  openDashboardInInsights() {
    if (this.selectedDashboard != null) {
      const insightsUrl: string =
        window.location.origin +
        "/insights/?realm=" +
        manager.displayRealm +
        "#/view/" +
        this.selectedDashboard.id +
        "/true/"; // Just using relative URL to origin, as its enough for now.
      window.open(insightsUrl)?.focus();
    }
  }

  shareUrl(method: string) {
    const url = window.location.href.replace("true", "false");
    if (method == "copy") {
      navigator.clipboard.writeText(url);
    } else if (method == "tab") {
      window.open(url, "_blank")?.focus();
    }
  }

  /* ----------------------------------- */

  saveDashboard() {
    if (this.selectedDashboard != null && !this._isReadonly() && this._hasEditAccess()) {
      this.isLoading = true;

      // Saving object into the database
      manager.rest.api.DashboardResource.update(this.selectedDashboard)
        .then((response) => {
          // Need to update the selected dashboard with what was persisted but due to code structure we also need to update
          // the dashboards array otherwise the old version gets loaded
          this.selectedDashboard = response.data;
          this.dashboards = [
            this.selectedDashboard,
            ...this.dashboards?.filter((d) => d.id !== this.selectedDashboard!.id)!,
          ];
          this.initialDashboardJSON = JSON.stringify(this.selectedDashboard);
          this.initialTemplateJSON = JSON.stringify(this.selectedDashboard.template);
          this.dashboards[this.dashboards?.indexOf(this.selectedDashboard)] = this.selectedDashboard;
          this.currentTemplate = Object.assign({}, this.selectedDashboard.template);
          showSnackbar(undefined, "dashboard.saveSuccessful");
        })
        .catch((reason) => {
          console.error(reason);
          showSnackbar(undefined, "errorOccurred");
        })
        .finally(() => {
          this.isLoading = false;
        });
    } else {
      console.error("The selected dashboard could not be found..");
      showSnackbar(undefined, "errorOccurred");
    }
  }

  protected _isReadonly(): boolean {
    return this.readonly;
  }

  protected _hasEditAccess(): boolean {
    return manager.hasRole(ClientRole.WRITE_INSIGHTS);
  }

  protected _hasViewAccess(): boolean {
    return (
      manager.hasRole(ClientRole.READ_INSIGHTS) &&
      this.userId != null &&
      (this.selectedDashboard?.access == DashboardAccess.PRIVATE
        ? this.selectedDashboard?.ownerId == this.userId
        : true)
    );
  }

  /* ----------------- */

  @state()
  protected sidebarMenuIndex: number = 0;

  @state()
  protected showDashboardTree: boolean = true;

  private readonly menuItems: ListItem[] = [
    { icon: "content-copy", text: i18next.t("copy") + " URL", value: "copy" },
    { icon: "open-in-new", text: i18next.t("dashboard.openInNewTab"), value: "tab" },
  ];

  private readonly tabItems: OrMwcTabItem[] = [
    { name: i18next.t("dashboard.widgets") },
    { name: i18next.t("settings") },
  ];

  // Rendering the page
  render(): any {
    if (window.matchMedia("(max-width: 600px)").matches && this.editMode) {
      this.dispatchEvent(new CustomEvent("editToggle", { detail: false }));
      this.showDashboardTree = true;
    }
    const builderStyles = {
      display: this.editMode && (this._isReadonly() || !this._hasEditAccess()) ? "none" : undefined,
      maxHeight: this.editMode ? "calc(100vh - 77px - 50px)" : "inherit",
    };
    return !this.isInitializing || (this.dashboards != null && this.dashboards.length == 0)
      ? html`
          <div id="container">
            ${
              this.showDashboardTree
                ? html`
                    <or-dashboard-tree
                      id="tree"
                      class="${this.selectedDashboard ? "hideMobile" : undefined}"
                      .realm="${this.realm}"
                      .hasChanged="${this.hasChanged}"
                      .selected="${this.selectedDashboard}"
                      .dashboards="${this.dashboards}"
                      .showControls="${true}"
                      .userId="${this.userId}"
                      .readonly="${this._isReadonly()}"
                      @created="${(_event: CustomEvent) => {
                        this.dispatchEvent(new CustomEvent("editToggle", { detail: true }));
                      }}"
                      @updated="${(event: CustomEvent) => {
                        this.dashboards = event.detail;
                        this.selectedDashboard = undefined;
                      }}"
                      @select="${(event: CustomEvent) => {
                        this.selectDashboard(event.detail);
                      }}"
                    ></or-dashboard-tree>
                  `
                : undefined
            }
            <div
              class="${this.selectedDashboard == null ? "hideMobile" : undefined}"
              style="flex: 1; display: flex; flex-direction: column;"
            >
              ${
                this.editMode
                  ? html`
                      <div id="header" class="hideMobile">
                        <div id="header-wrapper">
                          <div id="header-title">
                            <or-icon icon="view-dashboard"></or-icon>
                            ${when(
                              this.selectedDashboard,
                              () => html`
                                <or-vaadin-text-field
                                  minlength="1"
                                  maxlength="1023"
                                  required
                                  ?readonly=${this._isReadonly() || !this._hasEditAccess()}
                                  value=${this.selectedDashboard?.displayName}
                                  ?disabled=${this.isLoading}
                                  style="width: 300px;"
                                  @change=${(ev: Event) => this._onDashboardNameChanged(ev)}
                                >
                                  <or-translate slot="label" value="name"></or-translate>
                                </or-vaadin-text-field>
                              `
                            )}
                          </div>
                          <div id="header-actions">
                            <div id="header-actions-content">
                              ${when(
                                this.selectedDashboard,
                                () => html`
                                  <or-vaadin-button
                                    id="refresh-btn"
                                    theme="icon tertiary"
                                    ?disabled=${this.isLoading}
                                    title=${i18next.t("dashboard.refresh")}
                                    @click=${() => {
                                      this.deselectWidget();
                                      this.dashboardPreview?.refreshPreview();
                                    }}
                                  >
                                    <or-icon slot="prefix" icon="refresh"></or-icon>
                                  </or-vaadin-button>
                                  <or-vaadin-button
                                    id="responsive-btn"
                                    theme="icon tertiary"
                                    ?disabled=${this.isLoading}
                                    title=${i18next.t("dashboard.previewScreenSizes")}
                                    @click=${() => {
                                      this.dispatchEvent(
                                        new CustomEvent("fullscreenToggle", {
                                          detail: !this.fullscreen,
                                        })
                                      );
                                    }}
                                  >
                                    <or-icon slot="prefix" icon="responsive"></or-icon>
                                  </or-vaadin-button>
                                  <or-vaadin-button
                                    id="share-btn"
                                    theme="icon tertiary"
                                    ?disabled=${this.isLoading}
                                    title=${i18next.t("dashboard.openInNewTab")}
                                    @click=${() => this.openDashboardInInsights()}
                                  >
                                    <or-icon slot="prefix" icon="open-in-new"></or-icon>
                                  </or-vaadin-button>
                                  <or-vaadin-button
                                    id="save-btn"
                                    theme="primary"
                                    ?disabled=${this.isLoading || !this.hasChanged}
                                    ?hidden=${this._isReadonly() || !this._hasEditAccess()}
                                    @click=${() => this.saveDashboard()}
                                  >
                                    <or-translate value="save"></or-translate>
                                  </or-vaadin-button>
                                  <or-vaadin-button
                                    id="view-btn"
                                    ?hidden=${this._isReadonly() || !this._hasViewAccess()}
                                    @click=${() => this.dispatchEvent(new CustomEvent("editToggle", { detail: false }))}
                                  >
                                    <or-icon slot="prefix" icon="eye"></or-icon>
                                    <or-translate value="viewAsset"></or-translate>
                                  </or-vaadin-button>
                                `
                              )}
                            </div>
                          </div>
                        </div>
                      </div>
                    `
                  : html`
                      <div id="fullscreen-header">
                        <div id="fullscreen-header-wrapper">
                          <div id="fullscreen-header-title" style="display: flex; align-items: center;">
                            <or-icon
                              class="showMobile"
                              style="margin-right: 10px; cursor: pointer;"
                              icon="chevron-left"
                              @click="${() => {
                                this.selectedDashboard = undefined;
                              }}"
                            ></or-icon>
                            <or-icon
                              class="hideMobile"
                              style="margin-right: 10px; cursor: pointer;"
                              icon="menu"
                              title="${i18next.t(this.showDashboardTree ? "closeList" : "openList")}"
                              @click="${() => {
                                this.showDashboardTree = !this.showDashboardTree;
                              }}"
                            ></or-icon>
                            <span>${this.selectedDashboard?.displayName}</span>
                          </div>
                          <div id="fullscreen-header-actions">
                            <div id="fullscreen-header-actions-content">
                              ${when(
                                this.selectedDashboard,
                                () => html`
                                  <or-vaadin-button
                                    id="refresh-btn"
                                    theme="icon"
                                    ?disabled=${this.selectedDashboard == null}
                                    title=${i18next.t("dashboard.refresh")}
                                    @click=${() => {
                                      this.deselectWidget();
                                      this.dashboardPreview?.refreshPreview();
                                    }}
                                  >
                                    <or-icon icon="refresh"></or-icon>
                                  </or-vaadin-button>
                                  <dashboard-refresh-controls
                                    .interval="${this.refreshInterval}"
                                    .readonly="${false}"
                                    @interval-select="${(ev: IntervalSelectEvent) => this.onIntervalSelect(ev)}"
                                  ></dashboard-refresh-controls>
                                  <or-vaadin-button
                                    id="share-btn"
                                    theme="icon"
                                    ?disabled=${this.selectedDashboard == null}
                                    title=${i18next.t("dashboard.openInNewTab")}
                                    @click=${() => this.openDashboardInInsights()}
                                  >
                                    <or-icon icon="open-in-new"></or-icon>
                                  </or-vaadin-button>
                                  <or-vaadin-button
                                    id="view-btn"
                                    class="hideMobile"
                                    ?hidden=${this.selectedDashboard == null || this._isReadonly() || !this._hasEditAccess()}
                                    @click=${() => this.dispatchEvent(new CustomEvent("editToggle", { detail: true }))}
                                  >
                                    <or-icon slot="prefix" icon="pencil"></or-icon>
                                    <or-translate value="editAsset"></or-translate>
                                  </or-vaadin-button>
                                `
                              )}
                            </div>
                          </div>
                        </div>
                      </div>
                    `
              }
              <div id="content" style="flex: 1; overflow: auto;">
                <div id="container">
                  ${
                    this.editMode && (this._isReadonly() || !this._hasEditAccess())
                      ? html`
                          <div style="display: flex; justify-content: center; align-items: center; height: 100%;">
                            <span
                              >${!this._hasEditAccess() ? i18next.t("noDashboardWriteAccess") : i18next.t("errorOccurred")}.</span
                            >
                          </div>
                        `
                      : undefined
                  }
                  <div id="builder" style="${styleMap(builderStyles)}">
                    ${
                      this.selectedDashboard != null
                        ? html`
                            <or-dashboard-preview
                              class="editor"
                              style="background: transparent;"
                              .realm="${this.realm}"
                              .template="${this.currentTemplate}"
                              .selectedWidget="${this.selectedDashboard?.template?.widgets?.find((w) => w.id == this.selectedWidgetId)}"
                              .editMode="${this.editMode}"
                              .fullscreen="${this.fullscreen}"
                              .readonly="${this._isReadonly() || !this._hasEditAccess()}"
                              @selected="${(event: CustomEvent) => {
                                this.selectWidget(event.detail);
                              }}"
                              @deselected="${() => {
                                this.deselectWidget();
                              }}"
                              @created="${(event: CustomEvent) => {
                                this.onWidgetCreation(event.detail);
                              }}"
                              @changed="${(event: CustomEvent) => {
                                this.currentTemplate = event.detail.template;
                              }}"
                            ></or-dashboard-preview>
                          `
                        : html`
                            <div style="display: flex; justify-content: center; align-items: center; height: 100%;">
                              <span><or-translate value="noDashboardSelected"></or-translate></span>
                            </div>
                          `
                    }
                  </div>
                  ${when(
                    this.selectedDashboard != null && this.editMode && !this._isReadonly() && this._hasEditAccess(),
                    () => {
                      const selectedWidget = this.selectedDashboard?.template?.widgets?.find(
                        (w) => w.id == this.selectedWidgetId
                      );
                      return html`
                        <div id="sidebar" class="hideMobile">
                          ${
                            this.selectedWidgetId != null
                              ? html`
                                  <div class="settings-container">
                                    <div id="menu-header">
                                      <div id="title-container">
                                        <span id="title" title="${selectedWidget?.displayName}"
                                          >${selectedWidget?.displayName}</span
                                        >
                                      </div>
                                      <div id="sidebar-widget-headeractions">
                                        <or-vaadin-button
                                          theme="icon"
                                          @click=${() => {
                                            showConfirmDialog(
                                              this.shadowRoot!,
                                              html`
                                                <or-vaadin-confirm-dialog
                                                  @confirm=${() => this.deleteWidget(selectedWidget!)}
                                                >
                                                  ${getConfirmDialogContent("error", "areYouSure", "dashboard.deleteWidgetWarning", "delete", "cancel")}
                                                </or-vaadin-confirm-dialog>
                                              `
                                            );
                                          }}
                                        >
                                          <or-icon icon="delete"></or-icon>
                                        </or-vaadin-button>
                                        <or-vaadin-button theme="icon" @click=${() => this.deselectWidget()}>
                                          <or-icon icon="close"></or-icon>
                                        </or-vaadin-button>
                                      </div>
                                    </div>
                                    <div id="content" class="hidescroll" style="flex: 1; overflow: hidden auto;">
                                      <div style="position: relative;">
                                        <or-dashboard-widgetsettings
                                          style="position: absolute;"
                                          .selectedWidget="${selectedWidget}"
                                          .realm="${this.realm}"
                                          @delete="${(event: CustomEvent) => {
                                            this.deleteWidget(event.detail);
                                          }}"
                                          @update="${(event: CustomEvent) => {
                                            this.currentTemplate = Object.assign({}, this.selectedDashboard?.template);
                                            if (event.detail.force) {
                                              this.deselectWidget();
                                              this.dashboardPreview?.refreshPreview();
                                            }
                                          }}"
                                        ></or-dashboard-widgetsettings>
                                      </div>
                                    </div>
                                  </div>
                                `
                              : undefined
                          }
                          <div
                            class="settings-container"
                            style="${
                              this.selectedWidgetId != null
                                ? css`
                                    display: none;
                                  `
                                : null
                            }"
                          >
                            <div style="border-bottom: 1px solid ${unsafeCSS(DefaultColor5)};">
                              <or-mwc-tabs
                                .items="${this.tabItems}"
                                noScroll
                                @activated="${(event: CustomEvent) => {
                                  this.sidebarMenuIndex = event.detail.index;
                                }}"
                                style="pointer-events: ${this.selectedDashboard ? undefined : "none"}"
                              ></or-mwc-tabs>
                            </div>
                            <div id="content" style="flex: 1; overflow: auto;">
                              <div style="position: relative;">
                                <or-dashboard-browser
                                  id="browser"
                                  style="position: absolute; ${
                                    this.sidebarMenuIndex != 0
                                      ? css`
                                          display: none;
                                        `
                                      : null
                                  }"
                                ></or-dashboard-browser>
                                <or-dashboard-boardsettings
                                  style="position: absolute; ${
                                    this.sidebarMenuIndex != 1
                                      ? css`
                                          display: none;
                                        `
                                      : null
                                  }"
                                  .dashboard="${this.selectedDashboard}"
                                  showPerms
                                  @update="${(event: CustomEvent) => {
                                    this.currentTemplate = Object.assign({}, this.selectedDashboard?.template);
                                    if (event.detail.force) {
                                      this.deselectWidget();
                                      this.dashboardPreview?.refreshPreview();
                                    }
                                  }}"
                                ></or-dashboard-boardsettings>
                              </div>
                            </div>
                          </div>
                        </div>
                      `;
                    }
                  )}
                </div>
              </div>
            </div>
          </div>
        `
      : html`
          <div id="container" style="justify-content: center; align-items: center;">
            ${
              this.isInitializing
                ? html` <span>${i18next.t("loading")}.</span> `
                : html` <span>${i18next.t("errorOccurred")}.</span> `
            }
          </div>
        `;
  }

  protected onIntervalSelect(ev: IntervalSelectEvent) {
    this.refreshInterval = ev.detail;
  }

  /* ======================== */
}
