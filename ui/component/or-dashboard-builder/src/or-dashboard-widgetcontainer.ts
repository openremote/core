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
import { html, type PropertyValues } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, query, state } from "lit/decorators.js";
import { when } from "lit/directives/when.js";
import throttle from "lodash.throttle";
import { style } from "./style";
import type { DashboardWidget } from "@openremote/model";
import type { OrWidget, WidgetManifest } from "./util/or-widget";
import { WidgetService } from "./service/widget-service";
import type { WidgetConfig } from "./util/widget-config";
import { Util } from "@openremote/core";

/* ------------------------------------ */

const elemTagName = "or-dashboard-widget-container";

@customElement(elemTagName)
export class OrDashboardWidgetContainer extends OrElement {
  static tagName = elemTagName;

  @property()
  protected readonly widget!: DashboardWidget;

  @property() // Optional property, that overrides widget.widgetConfig. Useful for view-only mode.
  protected widgetConfig?: WidgetConfig;

  @property()
  protected readonly editMode!: boolean;

  @property()
  protected loading: boolean = false;

  @state()
  protected orWidget?: OrWidget;

  @state()
  protected error?: string; // untranslated error messages

  @query("#widget-container")
  protected containerElem?: Element;

  protected resizeObserver?: ResizeObserver;
  protected manifest?: WidgetManifest;

  static get styles() {
    return [style];
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.resizeObserver?.disconnect();
  }

  shouldUpdate(changedProps: PropertyValues): boolean {
    const changed = changedProps;

    // Update config if some values in the spec are not set.
    // Useful for when migrations have taken place.
    if (this.widget) {
      const manifest = WidgetService.getManifest(this.widget.widgetTypeId!);
      if (manifest) {
        if (this.editMode) {
          this.widget.widgetConfig = WidgetService.correctToConfigSpec(manifest, this.widget.widgetConfig);
        } else {
          this.widgetConfig = WidgetService.correctToConfigSpec(manifest, this.widget.widgetConfig);
        }
      }
    }

    // Only update widget if certain properties of widget has changed.
    // For example, when the 'gridItem' field changes, no update is needed since it doesn't apply here.
    if (changedProps.has("widget") && this.widget) {
      const oldVal = changedProps.get("widget") as DashboardWidget | undefined;
      const idChanged = oldVal?.id !== this.widget?.id;
      const nameChanged = oldVal?.displayName !== this.widget?.displayName;
      const configChanged = !Util.objectsEqual(oldVal?.widgetConfig, this.getWidgetConfig());
      if (!(idChanged || nameChanged || configChanged)) {
        changed.delete("widget");
      }
    }

    return changed.size === 0 ? false : super.shouldUpdate(changedProps);
  }

  willUpdate(changedProps: PropertyValues) {
    super.willUpdate(changedProps);

    if (!this.manifest && this.widget) {
      this.manifest = WidgetService.getManifest(this.widget.widgetTypeId!);
    }

    // Create widget
    if (changedProps.has("widget") && this.widget) {
      this.initializeWidgetElem(this.manifest!, this.getWidgetConfig()!);
    }
  }

  firstUpdated(changedProps: PropertyValues) {
    super.firstUpdated(changedProps);

    if (this.orWidget) {
      const containerElem = this.containerElem;
      if (containerElem) {
        this.resizeObserver?.disconnect();
        this.resizeObserver = new ResizeObserver(
          throttle(() => {
            const minWidth = this.manifest!.minPixelWidth || 0;
            const minHeight = this.manifest!.minPixelHeight || 0;
            const isMinimumSize: boolean =
              minWidth < containerElem.clientWidth && minHeight < containerElem.clientHeight;
            this.error = isMinimumSize ? undefined : "dashboard.widgetTooSmall";
          }, 200)
        );
        this.resizeObserver.observe(containerElem);
      } else {
        console.error("gridItemElement could not be found!");
      }
    }
  }

  protected initializeWidgetElem(manifest: WidgetManifest, config: WidgetConfig) {
    console.debug(`Initializing ${manifest.displayName} widget..`);
    if (this.orWidget) {
      this.orWidget.remove();
    }
    this.orWidget = manifest.getContentHtml(config);
    this.orWidget.getDisplayName = () => this.widget.displayName;
    this.orWidget.getEditMode = () => this.editMode;
    this.orWidget.getWidgetLocation = () => ({
      x: this.widget.gridItem?.x,
      y: this.widget.gridItem?.y,
      w: this.widget.gridItem?.w,
      h: this.widget.gridItem?.h,
    });
  }

  protected render() {
    const showHeader = !!this.widget.displayName;
    return html`
      <div
        id="widget-container"
        style="height: calc(100% - 16px); padding: 8px 16px 8px 16px; display: flex; flex-direction: column;"
      >
        <!-- Container title -->
        ${when(
          showHeader,
          () => html`
            <div style="flex: 0 0 36px; display: flex; justify-content: space-between; align-items: center;">
              <span
                class="panel-title"
                style="width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
              >
                ${this.widget.displayName?.toUpperCase()}
              </span>
            </div>
          `
        )}

        <!-- Content -->
        <div style="flex: 1; max-height: ${showHeader ? "calc(100% - 36px)" : "100%"};">
          ${when(
            !this.error && !this.loading,
            () => html` ${this.orWidget} `,
            () => html`<or-translate value="${this.error ? this.error : "loading"}"></or-translate>`
          )}
        </div>
      </div>
    `;
  }

  public refreshContent(force: boolean) {
    this.orWidget?.refreshContent(force);
  }

  public getWidgetConfig(): WidgetConfig | undefined {
    return this.widgetConfig || this.widget?.widgetConfig;
  }
}
