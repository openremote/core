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
import { css, html, type TemplateResult } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property } from "lit/decorators.js";

/**
 * A simple loading wrapper around some other content that will hide the content while loading property is true
 */
@customElement("or-loading-wrapper")
export class OrLoadingWrapper extends OrElement {
  @property({ type: Number })
  public loadingHeight?: number;

  @property({ type: Boolean })
  public loadDom: boolean = true;

  @property({ type: Boolean })
  public fadeContent: boolean = false;

  @property({ type: Boolean })
  public loading: boolean = false;

  @property({ type: Object, attribute: false })
  public content?: TemplateResult;

  // language=CSS
  public static get styles() {
    return css`
      :host {
        display: block;
      }

      .hidden {
        display: none;
      }

      .faded {
        opacity: 0.5;
      }

      #wrapper {
        position: relative;
      }

      #loader {
        position: absolute;
        width: 100%;
        height: 100%;
      }

      #content-wrapper {
        display: flex;
      }
    `;
  }

  render() {
    return html`
      <div id="wrapper">
        ${this.loading ? html`<div id="loader">LOADING</div>` : ``}
        ${
          this.loadDom || !this.loading
            ? html` <div id="content-wrapper" class="${this.loading ? (this.fadeContent ? "faded" : "hidden") : ""}">
                <slot></slot>
                ${this.content || ``}
              </div>`
            : ``
        }
      </div>
    `;
  }
}
