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
import { css, html, nothing, type PropertyValues, type TemplateResult } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, state } from "lit/decorators.js";
import manager, { OREvent, Util } from "@openremote/core";
import { i18next } from "@openremote/or-translate";
import "@openremote/or-vaadin-components/or-vaadin-badge";
import "@openremote/or-vaadin-components/or-vaadin-select";
import "@openremote/or-vaadin-components/or-vaadin-item";
import "@openremote/or-vaadin-components/or-vaadin-list-box";
import { selectRenderer } from "@openremote/or-vaadin-components/or-vaadin-select";
import type { Map as MapGL } from "maplibre-gl";
import { OrMapBaseControl } from "./base";
import type { AssetWithLocation, MapFilter } from "../types";
import { formatCount } from "../util";

import type { AssetQuery } from "@openremote/model";

export class OrMapPresetFilterEvent extends CustomEvent<AssetQuery | null> {
  public static readonly NAME = "or-map-preset-filter-changed";

  constructor(filter: AssetQuery | null) {
    super(OrMapPresetFilterEvent.NAME, {
      bubbles: true,
      composed: true,
      detail: filter,
    });
  }
}

declare global {
  export interface HTMLElementEventMap {
    [OrMapPresetFilterEvent.NAME]: OrMapPresetFilterEvent;
  }
}

// Registered once at module load — survives SPA navigation so it fires on login
// regardless of which page is active.
manager.addListener((event) => {
  if (event !== OREvent.LOGIN) return;
  for (let i = localStorage.length - 1; i >= 0; i--) {
    const key = localStorage.key(i);
    if (key?.startsWith("or-map-filter:")) localStorage.removeItem(key);
  }
});

@customElement("or-map-preset-filter")
export class OrMapPresetFilter extends OrElement {
  static get styles() {
    return [
      css`
        :host {
          display: block;
        }
        .filter-container {
          position: relative;
          display: block;
        }
        or-vaadin-select {
          width: 320px;
          --vaadin-input-field-background: white;
        }
        vaadin-select-item::part(content) {
          overflow: hidden;
          text-overflow: ellipsis;
          max-width: calc(100% - 60px);
        }
        @media only screen and (max-width: 40em) {
          :host {
            width: 100%;
          }
          or-vaadin-select {
            width: 100%;
          }
        }
        .filter-field-count {
          position: absolute;
          right: 40px;
          top: 50%;
          transform: translateY(-50%);
          pointer-events: none;
        }
      `,
    ];
  }

  @property({ type: Array })
  public filters: MapFilter[] = [];

  @property({ type: Array })
  public assets: AssetWithLocation[] = [];

  @state()
  protected _activeIndex = 0;

  protected _getFilterLabel(filter: MapFilter): string {
    if (filter.label) return filter.label;
    const { query } = filter;
    const types = query.types;
    const typeLabel = types?.length
      ? types
          .map((t) =>
            Util.getAssetTypeLabel(t)
              .replace(/\s*asset\s*$/i, "")
              .trim()
          )
          .join(" + ")
      : i18next.t("mapPage.filterCustom");
    const attrValues = (query.attributes?.items ?? ([] as any[]))
      .map((item: any) => {
        const val = item.value?.value;
        if (val === undefined || val === null) return null;
        if (typeof val === "string") {
          return val
            .replace(/_/g, " ")
            .toLowerCase()
            .replace(/^\w/, (c) => c.toUpperCase());
        }
        return String(val);
      })
      .filter(Boolean);
    return attrValues.length ? `${typeLabel}: ${attrValues.join(", ")}` : typeLabel;
  }

  protected _getFilterCount(filterIndex: number): number {
    if (filterIndex === 0) return this.assets.length;
    const query = new Util.AssetQueryHelper(this.filters[filterIndex - 1].query);
    return this.assets.filter((a) => query.matches(a)).length;
  }

  protected _buildOptions() {
    const all = {
      value: "0",
      label: i18next.t("mapPage.filterAll"),
      count: this.assets.length,
      warning: undefined as string | undefined,
    };
    const filters = this.filters
      .map((filter, i) => {
        const unsupported = Util.AssetQueryHelper.getUnsupportedFields(filter.query);
        return {
          value: String(i + 1),
          label: this._getFilterLabel(filter),
          count: this._getFilterCount(i + 1),
          warning: unsupported.length ? `Filter ${i + 1}: ${unsupported.join(", ")} not supported` : undefined,
        };
      })
      .sort((a, b) => a.label.localeCompare(b.label));
    return [all, ...filters];
  }

  protected _renderFilterOptions(): TemplateResult {
    const options = this._buildOptions();
    return html`
      <style>
        .filter-item {
          display: flex;
          align-items: center;
          gap: calc(var(--lumo-space-l) + var(--lumo-border-radius-m) / 4);
          padding-right: 4px;
        }
        .filter-item__label {
          flex: 1;
        }
        .filter-item__label--warning {
          color: var(--lumo-warning-text-color, #c46c0c);
        }
        .filter-item__warning {
          font-size: var(--lumo-font-size-xs);
          color: var(--lumo-warning-text-color, #c46c0c);
          white-space: nowrap;
        }
        or-vaadin-item.filter-option {
          padding: unset;
        }
      </style>
      <or-vaadin-list-box>
        ${options.map(
          (opt) => html`
            <or-vaadin-item class="filter-option" value="${opt.value}" label="${opt.label}">
              <div class="filter-item">
                <span class="filter-item__label ${opt.warning ? "filter-item__label--warning" : ""}"
                  >${opt.warning ? "⚠ " : ""}${opt.label}</span
                >
                ${opt.warning ? html`<span class="filter-item__warning">${opt.warning}</span>` : nothing}
                <or-vaadin-badge class="filter-item__count" theme="counter">${formatCount(opt.count)}</or-vaadin-badge>
              </div>
            </or-vaadin-item>
          `
        )}
      </or-vaadin-list-box>
    `;
  }

  protected render() {
    const currentCount = this._getFilterCount(this._activeIndex);
    return html`
      <div class="filter-container">
        <or-vaadin-select
          .value="${String(this._activeIndex)}"
          ${selectRenderer(this._renderFilterOptions, [this.filters, this.assets])}
          @change="${this._onChange}"
        ></or-vaadin-select>
        <or-vaadin-badge class="filter-field-count" theme="counter">${formatCount(currentCount)}</or-vaadin-badge>
      </div>
    `;
  }

  private _cachedStorageKey?: string;

  private _getStorageKey(): string {
    if (!this._cachedStorageKey) {
      this._cachedStorageKey = `or-map-filter:${manager.displayRealm}:${manager.username}`;
    }
    return this._cachedStorageKey;
  }

  protected updated(changedProperties: PropertyValues) {
    if (changedProperties.has("filters") && this.filters.length) {
      const stored = localStorage.getItem(this._getStorageKey());
      const storedIndex = stored !== null ? parseInt(stored, 10) : -1;
      const isValidStored = storedIndex >= 0 && storedIndex <= this.filters.length;

      if (isValidStored) {
        this._activeIndex = storedIndex;
      } else {
        const defaultIndex = this.filters.findIndex((f) => f.default);
        if (defaultIndex >= 0) this._activeIndex = defaultIndex + 1;
      }

      if (this._activeIndex > 0) {
        this.dispatchEvent(new OrMapPresetFilterEvent(this.filters[this._activeIndex - 1].query));
      }
    }
  }

  protected _onChange(e: Event) {
    this._activeIndex = parseInt((e.target as HTMLInputElement).value, 10) || 0;
    localStorage.setItem(this._getStorageKey(), String(this._activeIndex));
    const filter = this._activeIndex > 0 ? this.filters[this._activeIndex - 1].query : null;
    this.dispatchEvent(new OrMapPresetFilterEvent(filter));
  }
}

export class OrMapPresetFilterControl extends OrMapBaseControl {
  private _component?: OrMapPresetFilter;

  constructor(
    private _filters: MapFilter[],
    private _assets: AssetWithLocation[]
  ) {
    super();
  }

  onAdd(_map: MapGL): HTMLElement {
    this._createContainer();
    this._container!.classList.add("preset-filter-control");
    this._component = document.createElement("or-map-preset-filter") as OrMapPresetFilter;
    this._component.filters = this._filters;
    this._component.assets = this._assets;
    this._container!.appendChild(this._component);
    return this._container!;
  }

  onRemove(): void {
    super.onRemove();
    this._component = undefined;
  }

  set assets(assets: AssetWithLocation[]) {
    this._assets = assets;
    if (this._component) this._component.assets = assets;
  }

  clear() {
    this.assets = [];
  }

  /** Returns the initial filter synchronously by reading localStorage — call before flushing the asset buffer. */
  getInitialFilter(): AssetQuery | null {
    const key = `or-map-filter:${manager.displayRealm}:${manager.username}`;
    const stored = localStorage.getItem(key);
    const storedIndex = stored !== null ? parseInt(stored, 10) : -1;
    const isValid = storedIndex >= 0 && storedIndex <= this._filters.length;
    if (isValid && storedIndex > 0) return this._filters[storedIndex - 1].query;
    const defaultIndex = this._filters.findIndex((f) => f.default);
    if (defaultIndex >= 0) return this._filters[defaultIndex].query;
    return null;
  }

  get element(): OrMapPresetFilter | undefined {
    return this._component;
  }
}
