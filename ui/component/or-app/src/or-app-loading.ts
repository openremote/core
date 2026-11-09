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
import { css, html, nothing, unsafeCSS } from "lit";
import { customElement, property, state } from "lit/decorators.js";
import { OrElement } from "@openremote/or-element";
import "@openremote/or-components/or-loading-indicator";
import { i18next } from "@openremote/or-translate";
import { DefaultColor3 } from "@openremote/core";

// Translations come from the manager, so they are missing when it can't be reached
function translate(key: string, fallback: string): string {
  return i18next.isInitialized ? i18next.t(key, { defaultValue: fallback }) : fallback;
}

/**
 * Shows a spinner while the app starts, or a message when it could not be loaded. The spinner only appears once the
 * page has been loading for {@link OrAppLoading.DELAY_MILLIS}, counted from the start of the page load, so a fast start
 * shows nothing and a later instance continues where an earlier one left off.
 */
@customElement("or-app-loading")
export class OrAppLoading extends OrElement {
  public static DELAY_MILLIS = 2000;

  /** Shows that the app could not be loaded instead of the spinner */
  @property({ type: Boolean, reflect: true })
  public failed = false;

  @state()
  protected _spinnerVisible = false;

  protected _spinnerTimer?: number;

  static get styles() {
    return css`
      :host {
        position: fixed;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
        font-family: var(--lumo-font-family, sans-serif);
        font-size: 14px;
      }

      or-loading-indicator {
        position: relative;
        width: 60px;
        height: 60px;
      }

      p {
        margin: 16px;
        text-align: center;
      }
    `;
  }

  connectedCallback() {
    super.connectedCallback();
    const remainingMillis = OrAppLoading.DELAY_MILLIS - performance.now();
    if (remainingMillis <= 0) {
      this._spinnerVisible = true;
    } else {
      this._spinnerTimer = window.setTimeout(() => (this._spinnerVisible = true), remainingMillis);
    }
  }

  disconnectedCallback() {
    window.clearTimeout(this._spinnerTimer);
    super.disconnectedCallback();
  }

  render() {
    if (this.failed) {
      return html`<p role="alert">${translate("appError.loadFailed", "Could not load the app, try again later")}</p>`;
    }
    if (!this._spinnerVisible) {
      return nothing;
    }
    return html`<or-loading-indicator
      role="progressbar"
      aria-label=${translate("loading", "Loading...")}
    ></or-loading-indicator>`;
  }
}
