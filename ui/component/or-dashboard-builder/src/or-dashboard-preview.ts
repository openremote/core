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
import manager, { DefaultColor4, DefaultColor5 } from "@openremote/core";
import { css, html, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, state } from "lit/decorators.js";
import { style } from "./style";
import "./or-dashboard-widgetcontainer";
import debounce from "lodash.debounce";
import {
  type DashboardGridItem,
  DashboardScalingPreset,
  type DashboardScreenPreset,
  type DashboardTemplate,
  type DashboardWidget,
} from "@openremote/model";
import { getActivePreset } from "./index";
import "@openremote/or-components/or-loading-indicator";
import { repeat } from "lit/directives/repeat.js";
import { type GridItemHTMLElement, GridStack, type GridStackElement, type GridStackNode } from "gridstack";
import { showSnackbar } from "@openremote/or-mwc-components/or-mwc-snackbar";
import { when } from "lit/directives/when.js";
import { guard } from "lit/directives/guard.js";
import { OrDashboardEngine } from "./or-dashboard-engine";
import { WidgetService } from "./service/widget-service";
import { OrDashboardWidgetContainer } from "./or-dashboard-widgetcontainer";
import type { OrVaadinNumberField } from "@openremote/or-vaadin-components/or-vaadin-number-field";
import type { OrVaadinSelect, SelectItem } from "@openremote/or-vaadin-components/or-vaadin-select";
import { i18next, translate } from "@openremote/or-translate";

// TODO: Add webpack/rollup to build so consumers aren't forced to use the same tooling
const gridcss = require("gridstack/dist/gridstack.min.css");

// language=css
const editorStyling = css`
  #loadingContainer {
    position: absolute;
    width: 100%;
    height: 100%;
    display: flex;
    justify-content: center;
    align-items: center;
  }

  #view-options {
    padding: 24px;
    display: flex;
    justify-content: center;
    align-items: baseline;
  }
  /* Margins on view options */
  #fit-btn {
    margin-right: 10px;
  }
  #view-preset-select {
    margin-left: 20px;
  }
  #width-input {
    margin-left: 20px;
  }
  #height-input {
    margin-left: 10px;
  }
  #rotate-btn {
    margin-left: 10px;
  }

  .maingridContainer {
    position: absolute;
    padding-bottom: 32px;
  }
  .maingridContainer__fullscreen {
    width: 100%;
  }

  .maingrid {
    border: 3px solid #909090;
    background: #ffffff;
    border-radius: 8px;
    overflow-x: hidden;
    overflow-y: scroll;
    padding: 4px;
    z-index: 0;
  }
  .maingrid__fullscreen {
    border: none;
    background: transparent;
    border-radius: 0;
    overflow-x: hidden;
    overflow-y: scroll;
    height: 100% !important; /* To override .maingrid */
    width: 100% !important; /* To override .maingrid */
    padding: 0;
    /*pointer-events: none;*/
    position: relative;
    z-index: 0;
  }
  .maingrid__disabled {
    pointer-events: none;
    opacity: 40%;
  }
  .grid-stack-item-content {
    background: white;
    box-sizing: border-box;
    border: 1px solid var(--or-app-color5, ${unsafeCSS(DefaultColor5)});
    border-radius: 4px;
  }
  .grid-stack > .grid-stack-item > .grid-stack-item-content {
    overflow: visible;
  }
  .grid-stack-item-content__active {
    border: 2px solid var(--or-app-color4, ${unsafeCSS(DefaultColor4)});
    margin: -1px !important; /* to compromise with the extra pixel of border. */
  }
  .grid-stack-item > .ui-resizable-handle {
    opacity: 0.4;
  }

  /* Grid lines on the background of the grid */
  .grid-element {
    min-height: 100%;
    background-image:
      linear-gradient(90deg, #e0e0e0, transparent 1px), linear-gradient(90deg, transparent calc(100% - 1px), #e0e0e0),
      linear-gradient(#e0e0e0, transparent 1px), linear-gradient(transparent calc(100% - 1px), #e0e0e0 100%);
  }
`;

/* -------------------------------------------------- */

export interface DashboardGridNode extends GridStackNode {
  widgetTypeId: string;
}
export interface DashboardPreviewSize {
  displayName: string;
  width?: number;
  height?: number;
}

/* ------------------------------------------------------------ */

@customElement("or-dashboard-preview")
export class OrDashboardPreview extends translate(i18next)(OrElement) {
  // Monitoring the changes in the template, save the changes to this.latestChanges,
  // so we can check afterwards which changes are made. Used for
  @property({
    hasChanged(oldValue, newValue) {
      return JSON.stringify(oldValue) != JSON.stringify(newValue);
    },
  })
  set template(newValue: DashboardTemplate) {
    const currentValue = this._template;
    if (currentValue != undefined) {
      const changes = {
        changedKeys: Object.keys(newValue).filter(
          (key) =>
            JSON.stringify(newValue[key as keyof DashboardTemplate]) !==
            JSON.stringify(currentValue[key as keyof DashboardTemplate])
        ),
        oldValue: currentValue,
        newValue,
      };
      this._template = JSON.parse(JSON.stringify(newValue));
      this.latestChanges = changes;
      this.requestUpdate("template", currentValue);

      // If there is no value yet, do initial setup:
    } else if (newValue != undefined) {
      console.debug("Creating a new Dashboard grid...");
      this._template = newValue;
      this.setupGrid(false, false);
    }
  }

  private _template?: DashboardTemplate;

  get template() {
    return this._template!;
  }

  /* ------------------------ */

  @property() // Optional alternative for template
  protected readonly dashboardId?: string;

  @property() // Normally manager.displayRealm
  protected realm?: string;

  @property({ type: Object })
  protected selectedWidget: DashboardWidget | undefined;

  @property()
  protected editMode: boolean = false;

  @property() // For example when no permission
  protected readonly: boolean = true;

  @property()
  protected previewWidth?: string;

  @property()
  protected previewHeight?: string;

  @property()
  protected previewZoom: number = 1;

  @property()
  protected previewSize?: DashboardPreviewSize;

  @property()
  protected availablePreviewSizes?: DashboardPreviewSize[];

  @property()
  protected fullscreen: boolean = true;

  /* -------------- */

  @state() // State where the changes of the template are saved temporarily (for comparison with incoming data)
  protected latestChanges?: {
    changedKeys: string[];
    oldValue: DashboardTemplate;
    newValue: DashboardTemplate;
  };

  @state()
  protected activePreset?: DashboardScreenPreset;

  @state()
  protected rerenderActive: boolean = false;

  @state()
  protected isLoading: boolean = false;

  protected grid?: GridStack | null;
  protected latestDragWidgetStart?: Date;

  /* ------------------------------------------- */

  // Using constructor to set initial values
  constructor() {
    super();
    if (!this.realm) {
      this.realm = manager.displayRealm;
    }
    if (!this.availablePreviewSizes) {
      this.availablePreviewSizes = [
        { displayName: "4k Television", width: 3840, height: 2160 },
        { displayName: "Desktop", width: 1920, height: 1080 },
        { displayName: "Small desktop", width: 1280, height: 720 },
        { displayName: "Phone", width: 360, height: 800 },
        { displayName: "Custom" },
      ];
    }
    // Defaulting to a Phone view
    if (!this.previewSize) {
      this.previewSize = this.availablePreviewSizes[3];
    }

    // Register custom override functions for GridStack
    GridStack.registerEngine(OrDashboardEngine);
  }

  static get styles() {
    return [unsafeCSS(gridcss), editorStyling, style];
  }

  /* ------------------------------ */

  // Checking whether actual changes have been made; if not, prevent updating.
  shouldUpdate(changedProperties: Map<PropertyKey, unknown>): boolean {
    const changed = changedProperties;

    if (
      changedProperties.has("latestChanges") &&
      this.latestChanges?.changedKeys.length == 0 &&
      JSON.stringify((changedProperties.get("latestChanges") as any)?.oldValue) ==
        JSON.stringify((changedProperties.get("latestChanges") as any)?.newValue)
    ) {
      changed.delete("latestChanges");
    }

    // Do not update UI if the preview size has changed while being fullscreen,
    // since it is only used when in "responsive mode".
    if (this.fullscreen && changedProperties.has("previewWidth")) {
      changed.delete("previewWidth");
    }
    if (this.fullscreen && changedProperties.has("previewHeight")) {
      changed.delete("previewHeight");
    }

    return changed.size === 0 ? false : super.shouldUpdate(changedProperties);
  }

  // Main method for executing actions after property changes
  updated(changedProperties: Map<string, any>) {
    super.updated(changedProperties);

    if (this.realm == undefined) {
      this.realm = manager.displayRealm;
    }

    // Setup template (list of widgets and properties)
    if (!this.template && this.dashboardId) {
      manager.rest.api.DashboardResource.get(this.realm, this.dashboardId)
        .then((response) => {
          this.template = response.data.template!;
        })
        .catch((reason) => {
          console.error(reason);
          showSnackbar(undefined, "errorOccurred");
        });
    } else if (this.template == null && this.dashboardId == null) {
      console.warn("Neither the template nor dashboardId attributes have been specified!");
    }

    // If changes to the template have been made
    if (changedProperties.has("latestChanges")) {
      if (this.latestChanges) {
        this.processTemplateChanges(this.latestChanges);
        this.latestChanges = undefined;
      }
    }

    if (changedProperties.has("selectedWidget")) {
      if (this.selectedWidget) {
        if (changedProperties.get("selectedWidget") != undefined) {
          // if previous selected state was a different widget, dispatch event as well
          this.dispatchEvent(
            new CustomEvent("deselected", { detail: changedProperties.get("selectedWidget") as DashboardWidget })
          );
        }
        if (this.grid?.el != null) {
          const foundItem = this.grid
            ?.getGridItems()
            .find((item) => item.gridstackNode?.id == this.selectedWidget?.gridItem?.id);
          if (foundItem != null) {
            this.selectGridItem(foundItem);
          }
          this.dispatchEvent(new CustomEvent("selected", { detail: this.selectedWidget }));
        }
      } else {
        // Checking whether the mainGrid is not destroyed and there are Items to deselect...
        if (this.grid?.el != undefined && this.grid?.getGridItems() != null) {
          this.deselectGridItems(this.grid.getGridItems());
        }
        this.dispatchEvent(
          new CustomEvent("deselected", { detail: changedProperties.get("selectedWidget") as DashboardWidget })
        );
      }
    }

    // Switching edit/view mode needs recreation of Grid
    if (changedProperties.has("editMode")) {
      if (changedProperties.get("editMode") != undefined) {
        console.debug(`Edit mode is now ${this.editMode}. Force recreating grid...`);
        this.setupGrid(true, true);
      }
    }

    // Adjusting previewSize when manual pixels control changes
    if (changedProperties.has("previewWidth") || changedProperties.has("previewHeight")) {
      if (this.template?.screenPresets) {
        this.previewSize = this.availablePreviewSizes?.find(
          (s) => s.width + "px" == this.previewWidth && s.height + "px" == this.previewHeight
        );
      }
    }

    // Adjusting pixels control when previewSize changes.
    if (changedProperties.has("previewSize")) {
      if (this.previewSize) {
        this.previewWidth = this.previewSize.width + "px";
        this.previewHeight = this.previewSize.height + "px";
      }
    }

    // When parent component requests a forced rerender
    if (changedProperties.has("rerenderActive")) {
      if (this.rerenderActive) {
        this.rerenderActive = false;
      }
    }
  }

  /* ---------------------------------------- */

  // Main setup Grid method (often used)
  async setupGrid(recreate: boolean, force: boolean = false) {
    this.isLoading = true;
    await this.updateComplete;
    let gridElement = this.shadowRoot?.getElementById("gridElement");
    if (gridElement != null) {
      if (recreate && this.grid != null) {
        this.grid.destroy(false);

        if (force) {
          // Fully rerender the grid by switching rerenderActive on and off, and continue after that.
          this.rerenderActive = true;
          await this.updateComplete;
          await this.waitUntil((_: any) => !this.rerenderActive);
          gridElement = this.shadowRoot?.getElementById("gridElement");
          this.grid = undefined;
        }
      }
      const width: number = this.fullscreen ? this.clientWidth : +this.previewWidth?.replace(/\D/g, "")!;
      const newPreset = getActivePreset(width, this.template.screenPresets!);
      if (newPreset?.scalingPreset != this.activePreset?.scalingPreset) {
        if (!(recreate && force)) {
          // Fully rerender the grid by switching rerenderActive on and off, and continue after that.
          if (!recreate) {
            // If not destroyed yet, destroy first.
            this.grid?.destroy(false);
          }
          this.rerenderActive = true;
          await this.updateComplete;
          await this.waitUntil((_: any) => !this.rerenderActive);
          gridElement = this.shadowRoot?.getElementById("gridElement");
          this.grid = undefined;
        }
      }
      this.activePreset = newPreset;

      // If grid got reset, setup the ResizeObserver again.
      if (this.grid == null) {
        const gridHTML = this.shadowRoot?.querySelector(".maingrid");
        this.setupResizeObserver(gridHTML!);
      }

      gridElement!.style.maxWidth = this.template.maxScreenWidth + "px";

      this.grid = GridStack.init(
        {
          acceptWidgets: this.editMode,
          animate: true,
          cellHeight:
            this.activePreset?.scalingPreset === DashboardScalingPreset.WRAP_TO_SINGLE_COLUMN
              ? width / (this.template?.columns ? this.template.columns / 4 : 2)
              : "initial",
          column: this.template?.columns,
          columnOpts:
            this.activePreset?.scalingPreset === DashboardScalingPreset.WRAP_TO_SINGLE_COLUMN
              ? { breakpoints: [{ w: this.activePreset?.breakpoint ?? 768, c: 1 }], columnMax: this.template?.columns }
              : undefined,
          draggable: {
            appendTo: "parent", // Required to work, seems to be Shadow DOM related.
          },
          mode: "float",
          margin: 5,
          resizable: {
            handles: "all",
          },
          staticGrid:
            this.activePreset?.scalingPreset === DashboardScalingPreset.WRAP_TO_SINGLE_COLUMN ? true : !this.editMode,
          styleInHead: false,
        },
        gridElement!
      );

      if (this.grid) {
        gridElement!.style.backgroundSize = "" + this.grid.cellWidth() + "px " + this.grid.getCellHeight() + "px";
      }
      gridElement!.style.height = "100%";

      // When an item gets dropped ontop of the grid. GridStack docs say:
      // "called when an item has been dropped and accepted over a grid. If the item came from another grid, the previous widget node info will also be sent (but dom item long gone)."
      this.grid?.on("dropped", (ev: Event, prevWidget: any, newWidget: GridStackNode | undefined) =>
        this.onWidgetDrop(ev, prevWidget, newWidget as DashboardGridNode)
      );
      this.grid?.on("change", (_event: Event, items: any) => {
        if (this.template != null && this.template.widgets != null) {
          (items as GridStackNode[]).forEach((node) => {
            const foundWidget: DashboardWidget | undefined = this.template?.widgets?.find((widget) => {
              return widget.gridItem?.id == node.id;
            });
            foundWidget!.gridItem!.x = node.x;
            foundWidget!.gridItem!.y = node.y;
            foundWidget!.gridItem!.w = node.w;
            foundWidget!.gridItem!.h = node.h;
          });
          this.dispatchEvent(new CustomEvent("changed", { detail: { template: this.template } }));
        }
      });
      this.grid?.on("resizestart", (_event: Event) => {
        this.latestDragWidgetStart = new Date();
      });
      this.grid?.on("resizestop", (_event: Event) => {
        setTimeout(() => {
          this.latestDragWidgetStart = undefined;
        }, 200);
      });
    }
    this.isLoading = false;
  }

  /* ------------------------------- */

  public refreshPreview() {
    console.debug("Refreshing dashboard preview!");
    this.setupGrid(true, true);
  }

  public refreshWidgets() {
    this.grid?.getGridItems().forEach((gridItem) => {
      const widgetContainer = gridItem.querySelector(
        OrDashboardWidgetContainer.tagName
      ) as OrDashboardWidgetContainer | null;
      if (widgetContainer) {
        widgetContainer.refreshContent(false);
      }
    });
  }

  protected selectGridItem(gridItem: GridItemHTMLElement) {
    if (this.grid != null) {
      this.deselectGridItems(this.grid.getGridItems()); // deselecting all other items
      gridItem.querySelectorAll<HTMLElement>(".grid-stack-item-content").forEach((item: HTMLElement) => {
        item.classList.add("grid-stack-item-content__active"); // Apply active CSS class
      });
    }
  }

  protected deselectGridItem(gridItem: GridItemHTMLElement) {
    gridItem.querySelectorAll<HTMLElement>(".grid-stack-item-content").forEach((item: HTMLElement) => {
      item.classList.remove("grid-stack-item-content__active"); // Remove active CSS class
    });
  }

  protected deselectGridItems(gridItems: GridItemHTMLElement[]) {
    gridItems.forEach((item) => {
      this.deselectGridItem(item);
    });
  }

  protected onGridItemClick(gridItem: DashboardGridItem | undefined) {
    if (!this.latestDragWidgetStart && !this.grid?.opts.staticGrid) {
      if (!gridItem) {
        this.selectedWidget = undefined;
      } else if (this.selectedWidget?.gridItem?.id != gridItem.id) {
        this.selectedWidget = this.template?.widgets?.find((widget) => {
          return widget.gridItem?.id == gridItem.id;
        });
      }
    }
  }

  protected onFitToScreenClick() {
    const container = this.shadowRoot?.querySelector("#container");
    if (container) {
      const zoomWidth = +((0.95 * container.clientWidth) / +this.previewWidth!.replace("px", "")).toFixed(2);
      this.previewZoom = zoomWidth > 1 ? 1 : zoomWidth;
    }
  }

  protected isPreviewVisible(): boolean {
    return !this.isLoading && this.activePreset?.scalingPreset != DashboardScalingPreset.BLOCK_DEVICE;
  }

  // Render
  protected render() {
    try {
      // to correct the list of gridItems each render (Hopefully temporarily since it's quite compute heavy)
      if (this.grid?.el && this.grid?.getGridItems()) {
        this.grid?.getGridItems().forEach((gridItem: GridItemHTMLElement) => {
          if (this.template?.widgets?.find((widget) => widget.id == gridItem.id) == undefined) {
            this.grid?.removeWidget(gridItem);
          }
        });
      }
    } catch (e) {
      console.error(e);
    }

    const customPreset = "Custom";
    const screenPresets = this.template?.screenPresets?.map((s) => s.displayName);
    screenPresets?.push(customPreset);
    return html`
      <div
        id="buildingArea"
        style="display: flex; flex-direction: column; height: 100%; position: relative;"
        @click="${(event: PointerEvent) => {
          if ((event.composedPath()[1] as HTMLElement).id === "buildingArea") {
            this.onGridItemClick(undefined);
          }
        }}"
      >
        ${
          this.editMode && !this.fullscreen
            ? html`
                <div id="view-options">
                  <!-- Fit to screen button-->
                  <or-vaadin-button id="fit-btn" theme="icon" @click=${() => this.onFitToScreenClick()}>
                    <or-icon icon="fit-to-screen"></or-icon>
                  </or-vaadin-button>
                  <!-- Zoom level -->
                  <or-vaadin-number-field
                    id="zoom-input"
                    min="25"
                    value=${this.previewZoom * 100}
                    style="width: 90px;"
                    @change=${(ev: Event) => {
                      const elem = ev.currentTarget as OrVaadinNumberField;
                      if (elem.checkValidity()) this.previewZoom = Number(elem.value) / 100;
                    }}
                  >
                    <or-translate slot="label" value="dashboard.zoomPercent"></or-translate>
                    <span slot="suffix">%</span>
                  </or-vaadin-number-field>
                  <!-- Select device size preset -->
                  <or-vaadin-select
                    id="view-preset-select"
                    .items=${this.availablePreviewSizes?.map((s) => ({ value: s.displayName, label: s.displayName }) as SelectItem)}
                    value=${this.previewSize == undefined ? customPreset : this.previewSize.displayName}
                    style="width: 220px;"
                    @change=${(ev: Event) => {
                      const name = (ev.currentTarget as OrVaadinSelect).value;
                      this.previewSize = this.availablePreviewSizes?.find((s) => s.displayName == name);
                    }}
                  >
                    <or-translate slot="label" value="dashboard.presetSize"></or-translate>
                  </or-vaadin-select>
                  <!-- Height width input fields -->
                  <or-vaadin-number-field
                    id="width-input"
                    value=${this.previewWidth?.replace("px", "")}
                    min="100"
                    style="width: 90px;"
                    @change=${(ev: Event) => {
                      const elem = ev.currentTarget as OrVaadinNumberField;
                      if (elem.checkValidity()) this.previewWidth = elem.value + "px";
                    }}
                  >
                    <or-translate slot="label" value="width"></or-translate>
                  </or-vaadin-number-field>
                  <or-vaadin-number-field
                    id="height-input"
                    value=${this.previewHeight?.replace("px", "")}
                    min="100"
                    style="width: 90px;"
                    @change=${(ev: Event) => {
                      const elem = ev.currentTarget as OrVaadinNumberField;
                      if (elem.checkValidity()) this.previewHeight = elem.value + "px";
                    }}
                  >
                    <or-translate slot="label" value="height"></or-translate>
                  </or-vaadin-number-field>
                  <!-- Rotate button -->
                  <or-vaadin-button
                    id="rotate-btn"
                    theme="icon"
                    @click=${() => {
                      const newWidth = this.previewHeight;
                      const newHeight = this.previewWidth;
                      this.previewWidth = newWidth;
                      this.previewHeight = newHeight;
                    }}
                  >
                    <or-icon icon="screen-rotation"></or-icon>
                  </or-vaadin-button>
                </div>
              `
            : undefined
        }
        ${
          this.rerenderActive
            ? html`
                <div id="container" style="justify-content: center; align-items: center;">
                  <span><or-translate value="dashboard.renderingGrid"></or-translate></span>
                </div>
              `
            : html`
                <div id="container" style="justify-content: center; position: relative;">
                  ${when(
                    this.isLoading,
                    () => html`
                      <div style="position: absolute; z-index: 3; height: 100%; display: flex; align-items: center;">
                        <or-loading-indicator></or-loading-indicator>
                      </div>
                    `,
                    () => html`
                      ${
                        this.activePreset?.scalingPreset == DashboardScalingPreset.BLOCK_DEVICE
                          ? html`
                              <div
                                style="position: absolute; z-index: 3; height: 100%; display: flex; align-items: center;"
                              >
                                <span><or-translate value="dashboard.deviceNotSupported"></or-translate></span>
                              </div>
                            `
                          : undefined
                      }
                    `
                  )}
                  <!-- The grid itself. Will also show during isLoading, but will be invisible through CSS -->
                  <div
                    class="${this.fullscreen ? "maingridContainer__fullscreen" : "maingridContainer"}"
                    style="${this.isLoading ? "visibility: hidden;" : ""}"
                  >
                    <div
                      class="maingrid ${this.fullscreen ? "maingrid__fullscreen" : undefined}"
                      style="width: ${this.previewWidth}; height: ${this.previewHeight}; visibility: ${this.isPreviewVisible() ? "visible" : "hidden"}; zoom: ${this.editMode && !this.fullscreen ? this.previewZoom : "normal"}; ${this.editMode && !this.fullscreen ? "-moz-transform: scale(" + this.previewZoom + ")" : undefined}; transform-origin: top;"
                      @click="${(ev: MouseEvent) => {
                        if ((ev.composedPath()[0] as HTMLElement).id == "gridElement") {
                          this.onGridItemClick(undefined);
                        }
                      }}"
                    >
                      ${guard(
                        [this.editMode, this.template],
                        () => html`
                          <!-- Gridstack element on which the Grid will be rendered -->
                          <div
                            id="gridElement"
                            class="grid-stack ${this.editMode ? "grid-element" : undefined}"
                            style="margin: auto;"
                          >
                            ${
                              this.template?.widgets
                                ? repeat(
                                    this.template.widgets,
                                    (item) => item.id,
                                    (widget) => {
                                      return html`
                                        <div
                                          class="grid-stack-item"
                                          id="${widget.id}"
                                          gs-id="${widget.gridItem?.id}"
                                          gs-x="${widget.gridItem?.x}"
                                          gs-y="${widget.gridItem?.y}"
                                          gs-w="${widget.gridItem?.w}"
                                          gs-h="${widget.gridItem?.h}"
                                          gs-min-w="${widget.gridItem?.minW}"
                                          gs-min-h="${widget.gridItem?.minH}"
                                          @click="${() => {
                                            this.onGridItemClick(widget.gridItem);
                                          }}"
                                        >
                                          <div class="grid-stack-item-content" style="display: flex;">
                                            <or-dashboard-widget-container
                                              .widget="${widget}"
                                              .editMode="${this.editMode}"
                                              style="width: 100%; height: auto; border-radius: 4px;"
                                            ></or-dashboard-widget-container>
                                          </div>
                                        </div>
                                      `;
                                    }
                                  )
                                : undefined
                            }
                          </div>
                        `
                      )}
                    </div>
                  </div>
                </div>
              `
        }
      </div>
    `;
  }

  /* ---------------------------------------------- */

  protected resizeObserver?: ResizeObserver;
  protected previousObserverEntry?: ResizeObserverEntry;

  disconnectedCallback() {
    super.disconnectedCallback();
    this.resizeObserver?.disconnect();
  }

  // Triggering a Grid rerender on every time the element resizes.
  // In fullscreen, debounce (only trigger after 550ms of no changes) to limit amount of rerenders.
  protected setupResizeObserver(element: Element): ResizeObserver {
    this.resizeObserver?.disconnect();
    if (this.fullscreen) {
      this.resizeObserver = new ResizeObserver(debounce(this.resizeObserverCallback, 200));
    } else {
      this.resizeObserver = new ResizeObserver(this.resizeObserverCallback);
    }
    this.resizeObserver.observe(element);
    return this.resizeObserver;
  }

  protected resizeObserverCallback: ResizeObserverCallback = (entries: ResizeObserverEntry[]) => {
    requestAnimationFrame(() => {
      if (this.previousObserverEntry?.contentRect.width + "px" !== entries[0].contentRect.width + "px") {
        this._onGridResize();
      }
      this.previousObserverEntry = entries[0];
    });
  };

  protected _onGridResize() {
    console.debug("Grid resize detected. Recreating the grid...");
    this.setupGrid(true, false);
  }

  /* --------------------------------------- */

  protected processTemplateChanges(changes: {
    changedKeys: string[];
    oldValue: DashboardTemplate;
    newValue: DashboardTemplate;
  }) {
    // If only columns property changed, change columns through the framework and then recreate grid.
    if (changes.changedKeys.length == 1 && changes.changedKeys.includes("columns") && this.grid) {
      this.grid.column(changes.newValue.columns!);
      const maingrid = this.shadowRoot?.querySelector(".maingrid");
      const gridElement = this.shadowRoot?.getElementById("gridElement");
      gridElement!.style.backgroundSize = "" + this.grid.cellWidth() + "px " + this.grid.getCellHeight() + "px";
      gridElement!.style.height = maingrid!.scrollHeight + "px";
      console.debug("Amount of columns has been changed! Recreating grid...");
      this.setupGrid(true, false);
    }

    // If multiple properties changed, just force rerender all of it.
    else if (changes.changedKeys.length > 1) {
      console.debug("Multiple changes detected. Force recreating grid...");
      this.setupGrid(true, true);
    }

    // On widgets change, check whether they are programmatically added to GridStack. If not, adding them.
    else if (changes.changedKeys.includes("widgets")) {
      if (this.grid?.el != null) {
        let amountAdded = 0;
        this.grid.getGridItems().forEach((gridElement) => {
          if (!gridElement.classList.contains("ui-draggable")) {
            this.grid?.makeWidget(gridElement);
            amountAdded++;
          }
        });
        // If a widget is added, update the grid. (for example to update dashboard height)
        if (amountAdded > 0) {
          console.debug(`Created ${amountAdded} widgets. Updating the grid...`);
          this.setupGrid(false, false);
        }
      }
    } else if (changes.changedKeys.includes("screenPresets")) {
      console.debug("New template has a different screen preset! Force recreating grid...");
      this.setupGrid(true, true);
    }
  }

  // Wait until function that waits until a boolean returns differently
  // TODO: Remove this, and replace 'waiting' functionality with observer pattern principles.
  protected waitUntil(conditionFunction: any) {
    const poll = (resolve: any) => {
      if (conditionFunction()) resolve();
      else setTimeout((_) => poll(resolve), 400);
    };
    return new Promise(poll);
  }

  // Callback method for GridStack Grid 'dropped' event. GridStack docs say:
  // called when an item has been dropped and accepted over a grid. If the item came from another grid, the previous widget node info will also be sent (but dom item long gone).
  protected onWidgetDrop(_ev: Event, _prevWidget: any, newWidget: DashboardGridNode | undefined) {
    // When a "Widget Card" gets dropped onto the grid, we create a new widget on those coordinates.
    if (this.grid && newWidget) {
      this.grid.removeWidget(newWidget.el as GridStackElement, true, false); // Removes dragged widget first
      WidgetService.placeNew(newWidget.widgetTypeId, newWidget.x!, newWidget.y!).then((widget) => {
        this.dispatchEvent(new CustomEvent("created", { detail: widget }));
      });
    }
  }
}
