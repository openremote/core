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
import { css, html, type PropertyValues, type TemplateResult, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { unsafeSVG } from "lit/directives/unsafe-svg.js";
import { customElement, property, state } from "lit/decorators.js";
import { type AssetDescriptor, AssetModelUtil, WellknownAssets } from "@openremote/model";

import mdiFontStyle from "@mdi/font/css/materialdesignicons.min.css";
import OrIconSet from "./or-icon-set";
export { OrIconSet };

// createMdiIconSet declares the font on the document, the bundled @font-face would load it relative to the page
const mdiIconStyle = mdiFontStyle.replace(/@font-face\{[^}]*\}/, "");

export class IconSetAddedEvent extends CustomEvent<void> {
  public static readonly NAME = "or-iconset-added";

  constructor() {
    super(IconSetAddedEvent.NAME, {
      bubbles: true,
      composed: true,
    });
  }
}

declare global {
  export interface HTMLElementEventMap {
    [IconSetAddedEvent.NAME]: IconSetAddedEvent;
  }
}

export interface IconSet {
  getIconTemplate: (icon: string) => TemplateResult;
  onAdd?: () => void;
}

export function createSvgIconSet(size: number, icons: { [name: string]: string }): IconSet {
  return {
    getIconTemplate: (name) => {
      const iconData = icons[name];
      if (!iconData) {
        return html``;
      }
      return html`
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 ${size} ${size}"
          preserveAspectRatio="xMidYMid meet"
          focusable="false"
        >
          ${unsafeSVG(iconData.startsWith("<") ? iconData : '<path xmlns="http://www.w3.org/2000/svg" d="' + iconData + '" />')}
        </svg>
      `;
    },
    onAdd: undefined,
  };
}

export function createMdiIconSet(managerUrl: string): IconSet {
  return {
    getIconTemplate(icon: string) {
      return html`<span style="font-family: 'Material Design Icons';" class="mdi-${icon}"></span>`;
    },
    onAdd(): void {
      // Load the material design font from the specified manager shared folder
      const style = document.createElement("style");
      style.id = "mdiFontStyle";
      style.textContent =
        "@font-face {\n" +
        '  font-family: "Material Design Icons";\n' +
        '  src: url("' +
        managerUrl +
        '/shared/fonts/Material Design Icons/fonts/materialdesignicons-webfont.woff2") format("woff2");\n' +
        "  font-weight: normal;\n" +
        "  font-style: normal;\n" +
        "}";
      document.head.appendChild(style);
    },
  };
}

class ORIconSets {
  private _icons: { [name: string]: IconSet } = {};
  private _defaultIconSet?: IconSet;

  addIconSet(name: string, iconSet: IconSet) {
    this._icons[name] = iconSet;
    if (!this._defaultIconSet) {
      this._defaultIconSet = iconSet;
    }
    if (iconSet.onAdd) {
      iconSet.onAdd();
    }
    window.dispatchEvent(new IconSetAddedEvent());
  }

  getIconSet(name: string): IconSet {
    return this._icons[name];
  }

  getIconTemplate(icon: string | undefined): TemplateResult {
    if (!icon) {
      return html``;
    }

    const parts = (icon || "").split(":");
    const iconName = parts.pop();
    const iconSetName = parts.pop();
    let iconSet = this._defaultIconSet;

    if (iconSetName) {
      iconSet = this.getIconSet(iconSetName);
    }

    if (!iconName || !iconSet) {
      return html``;
    }

    return iconSet.getIconTemplate(iconName);
  }
}

export const IconSets = new ORIconSets();

export function getAssetDescriptorIconTemplate(
  descriptor: AssetDescriptor | undefined,
  fallbackColor?: string | undefined,
  fallbackIcon?: string | undefined,
  overrideColor?: string | undefined,
  overrideIcon?: string | undefined
): TemplateResult {
  const color = overrideColor || AssetModelUtil.getAssetDescriptorColour(descriptor, fallbackColor);
  let icon = overrideIcon || AssetModelUtil.getAssetDescriptorIcon(descriptor, fallbackIcon);
  if (!icon) {
    icon = AssetModelUtil.getAssetDescriptorIcon(WellknownAssets.THINGASSET);
  }
  return html`<or-icon style="--or-icon-fill: ${color ? "#" + color : "unset"}" icon="${icon}"></or-icon>`;
}

@customElement("or-icon")
export class OrIcon extends OrElement {
  // language=CSS
  static get styles() {
    return [
      css`
        :host {
          --internal-or-icon-width: var(--or-icon-width, 24px);
          --internal-or-icon-height: var(--or-icon-height, 24px);
          --internal-or-icon-fill: var(--or-icon-fill, currentColor);
          --internal-or-icon-stroke: var(--or-icon-fill, none);
          --internal-or-icon-stroke-width: var(--or-icon-stroke-width, 0);

          display: inline-block;
          color: var(--internal-or-icon-fill);
          fill: var(--internal-or-icon-fill);
          stroke: var(--internal-or-icon-stroke);
          stroke-width: var(--internal-or-icon-stroke-width);
          vertical-align: text-bottom;
          font-weight: normal;
          font-style: normal;
          font-size: var(--internal-or-icon-width);
          line-height: 1;
          letter-spacing: normal;
          text-transform: none;
          white-space: nowrap;
          word-wrap: normal;
          direction: ltr;
          /* Support for all WebKit browsers. */
          -webkit-font-smoothing: antialiased;
          /* Support for Safari and Chrome. */
          text-rendering: optimizeLegibility;
          /* Support for Firefox. */
          -moz-osx-font-smoothing: grayscale;
          /* Support for IE. */
          font-feature-settings: "liga";
        }

        :host([hidden]) {
          display: none;
        }

        svg {
          pointer-events: none;
          display: block;
          width: var(--internal-or-icon-width);
          height: var(--internal-or-icon-height);
        }
      `,
      css`
        ${unsafeCSS(mdiIconStyle)}
      `,
    ];
  }

  @property({ type: String, reflect: true })
  icon?: string;

  @state()
  protected _iconTemplate?: TemplateResult;

  protected _handler = (evt: Event) => {
    this._updateIcon();
  };

  protected render() {
    return this._iconTemplate || html``;
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener(IconSetAddedEvent.NAME, this._handler);
  }

  protected shouldUpdate(changedProperties: PropertyValues): boolean {
    if (changedProperties.has("icon")) {
      this._updateIcon();
    }

    return true;
  }

  protected _updateIcon() {
    this._iconTemplate = undefined;
    window.removeEventListener(IconSetAddedEvent.NAME, this._handler);
    this._iconTemplate = IconSets.getIconTemplate(this.icon);

    if (this.icon && !this._iconTemplate) {
      window.addEventListener(IconSetAddedEvent.NAME, this._handler);
    }
  }
}
