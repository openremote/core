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
import { GridStack } from "gridstack";
import { css, html, type PropertyValues, type TemplateResult, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query, state } from "lit/decorators.js";
import { style } from "./style";
import { widgetTypes } from "./index";
import type { WidgetManifest } from "./util/or-widget";
import { when } from "lit/directives/when.js";
import { repeat } from "lit/directives/repeat.js";
import { keyed } from "lit/directives/keyed.js";
import type { DashboardGridNode } from "./or-dashboard-preview";
import { keepDomOrder } from "./or-dashboard-engine";

// TODO: Add webpack/rollup to build so consumers aren't forced to use the same tooling
const gridcss = require("gridstack/dist/gridstack.min.css");

// language=css
const browserStyling = css`
  #sidebar {
    display: grid;
    padding: 16px;
  }
  #sidebarElement,
  #sidebarBgElement {
    grid-column: 1;
    grid-row: 1;
  }
  .grid-stack-item {
    cursor: grab;
  }
  .sidebarItem {
    background: white;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    border: 1px solid #e0e0e0;
    border-radius: 8px;
    box-sizing: border-box;
    flex-direction: column;
    font-size: 14px;
    --or-icon-width: 36px;
  }
  .itemText {
    margin-top: 10px;
  }
`;

@customElement("or-dashboard-browser")
export class OrDashboardBrowser extends OrElement {
  @property({ type: Number })
  public columns = 4;

  @property({ type: Number })
  public size = 2;

  @property({ type: Number })
  public itemSize = 134;

  @state()
  protected sidebarGrid?: GridStack | null;

  @state()
  protected backgroundGrid?: GridStack | null;

  @state()
  protected items: Map<string, WidgetManifest> = new Map(widgetTypes);

  @state()
  protected rebuildNonce = 0;

  @query("#sidebarElement")
  protected sidebarElem?: HTMLDivElement;

  @query("#sidebarBgElement")
  protected backgroundElem?: HTMLDivElement;

  protected sidebarHeight = this._getSidebarHeight(this.columns);

  static get styles() {
    return [unsafeCSS(gridcss), browserStyling, style];
  }

  updated(changedProps: PropertyValues) {
    // Apply widgetTypeId to identify what 'card' is linked to what widget type
    this.sidebarGrid?.getGridItems()?.forEach((i) => {
      if (i.gridstackNode) (i.gridstackNode as DashboardGridNode).widgetTypeId = i.getAttribute("gs-id") ?? i.id;
    });
    return super.updated(changedProps);
  }

  firstUpdated(changedProps: PropertyValues) {
    this.renderGrid();
    return super.firstUpdated(changedProps);
  }

  /* --------------------------------- */

  protected renderGrid() {
    this.sidebarGrid?.destroy(false);
    this.sidebarGrid = GridStack.init(
      {
        acceptWidgets: false,
        animate: false,
        column: this.columns,
        cellHeight: this.itemSize / (this.columns / this.size),
        disableResize: true,
        removable: false,
        draggable: {
          appendTo: "parent",
        },
        margin: 8,
        row: this.sidebarHeight,
      },
      this.sidebarElem
    );

    // If an item gets dropped on the main grid, the dragged card needs to be reset to the sidebar.
    // Therefore, we bump the nonce variable, and rebuild both grids from scratch using the keyed() in render().
    // A clean rebuild lets GridStack auto-position every card back in its original slot.
    this.sidebarGrid?.on("removed", () => {
      this.rebuildNonce++;
      this.updateComplete.then(() => this.renderGrid());
    });

    // Separate Static Background grid (to make it look like the items duplicate)
    this.backgroundGrid?.destroy(false);
    this.backgroundGrid = GridStack.init(
      {
        acceptWidgets: false,
        animate: false,
        staticGrid: true,
        column: this.columns,
        cellHeight: this.itemSize / (this.columns / this.size),
        margin: 8,
      },
      this.backgroundElem
    );
  }

  protected render() {
    return html`
      <div id="sidebar">
        ${keyed(
          this.rebuildNonce,
          html`
            <div id="sidebarElement" class="grid-stack" style="width: 100%; z-index: 3;">
              ${repeat(
                this.items,
                ([type]) => type,
                ([type, manifest]) => this._getSidebarItemTemplate(type, type, manifest)
              )}
              ${when(this.items.size % 2 !== 0, () => this._getEmptyItemTemplate())}
            </div>
            <div id="sidebarBgElement" class="grid-stack" style="width: 100%; z-index: 2">
              ${repeat(
                this.items,
                ([type]) => type,
                ([type, manifest]) => this._getSidebarItemTemplate(`bg-${type}`, type, manifest)
              )}
              ${when(this.items.size % 2 !== 0, () => this._getEmptyItemTemplate())}
            </div>
          `
        )}
      </div>
    `;
  }

  protected _getSidebarItemTemplate(id: string, type: string, manifest: WidgetManifest): TemplateResult {
    return html`
      <div class="grid-stack-item" id=${id} gs-id=${type} gs-w=${this.size} gs-h=${this.size} gs-locked="true">
        <div class="grid-stack-item-content sidebarItem">
          <or-icon icon="${manifest.displayIcon}"></or-icon>
          <span class="itemText">${manifest.displayName}</span>
        </div>
      </div>
    `;
  }

  protected _getEmptyItemTemplate() {
    return html`
      <div
        class="grid-stack-item"
        gs-id="empty"
        gs-w=${this.size}
        gs-h=${this.size}
        gs-locked="true"
        gs-no-move="true"
        style="cursor: default;"
      >
        <div class="grid-stack-item-content"></div>
      </div>
    `;
  }

  protected _getSidebarHeight(columns = this.columns, size = this.size) {
    let sidebarHeight = Math.ceil((widgetTypes.size / (columns / size)) * size);
    if (sidebarHeight % size !== 0) {
      // If sidebarHeight is based on nr of widgets in array & is not even add 1
      sidebarHeight++;
    }
    return sidebarHeight;
  }
}
