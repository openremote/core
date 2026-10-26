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
import { customElement } from "lit/decorators.js";
import { html, type LitElement } from "lit";
import { styleMap } from "lit/directives/style-map.js";
import { Tabs } from "@vaadin/tabs/vaadin-tabs.js";
import { registerStyles, css } from "@vaadin/vaadin-themable-mixin/register-styles.js";
import type { OrVaadinComponent } from "./util";

/* Registers the tabs that go in the bar, and exports their class so projects can type them */
export { Tab } from "@vaadin/tabs/vaadin-tab.js";

/*
 * Without a variant the theme gives this tag the Lumo tabs look, or the Vaadin base styles apply without it. The
 * `sliding` variant replaces either look, and styles the tabs from here as the Lumo tabs variants do. Its tabs share the
 * bar equally, so the indicator is sized from the number of tabs and moved by the index of the selected one.
 */
registerStyles(
  "or-vaadin-tabs",
  css`
    :host([theme~="sliding"]) {
      --_gap: var(--or-tabs-gap, 10px);
      --_padding: var(--or-tabs-padding, var(--lumo-space-xs, 4px));
      /* At most a full pill, and the bar curves around it so both orientations round alike */
      --_tab-radius: min(
        var(--or-tabs-radius, var(--lumo-border-radius-m, 6px)),
        var(--or-tab-height, var(--lumo-size-l, 44px)) / 2
      );
      /* The indicator marks the selection instead of the Lumo underline */
      --_lumo-tab-marker-display: none;
      position: relative;
      display: flex;
      box-sizing: border-box;
      padding: var(--_padding);
      border-radius: calc(var(--_tab-radius) + var(--_padding));
      background: var(--or-tabs-background, var(--lumo-contrast-5pct, #f2f3f2));
      box-shadow: none;
    }

    :host([theme~="sliding"][orientation="vertical"]) {
      flex-direction: column;
    }

    :host([theme~="sliding"]) [part="tabs"] {
      display: flex;
      flex-direction: row;
      gap: var(--_gap);
      margin: 0;
    }

    :host([theme~="sliding"][orientation="vertical"]) [part="tabs"] {
      flex-direction: column;
    }

    :host([theme~="sliding"]) [part="tabs"] ::slotted(vaadin-tab) {
      flex: 1 1 0;
      justify-content: center;
      min-width: 0;
      min-height: var(--or-tab-height, var(--lumo-size-l, 44px));
      box-sizing: border-box;
      margin: 0;
      border-radius: var(--_tab-radius);
      background: transparent;
      text-align: center;
      overflow-wrap: anywhere;
      transition: color var(--or-tabs-transition-duration, 0.3s) ease-out;
    }

    :host(:not([theme~="sliding"])) #indicator {
      display: none;
    }

    #indicator {
      position: absolute;
      inset-block: var(--_padding);
      inset-inline-start: var(--_padding);
      width: calc((100% - 2 * var(--_padding) - (var(--_count) - 1) * var(--_gap)) / var(--_count));
      border-radius: var(--_tab-radius);
      background: var(--or-tabs-indicator-color, var(--lumo-base-color, #ffffff));
      translate: calc(var(--_direction, 1) * var(--_index) * (100% + var(--_gap))) 0;
      transition: translate var(--or-tabs-transition-duration, 0.3s) ease-out;
    }

    :host([dir="rtl"]) #indicator {
      --_direction: -1;
    }

    :host([orientation="vertical"]) #indicator {
      inset-inline: var(--_padding);
      inset-block-end: auto;
      width: auto;
      height: calc((100% - 2 * var(--_padding) - (var(--_count) - 1) * var(--_gap)) / var(--_count));
      translate: 0 calc(var(--_index) * (100% + var(--_gap)));
    }

    @media (prefers-reduced-motion: reduce) {
      #indicator,
      :host([theme~="sliding"]) [part="tabs"] ::slotted(vaadin-tab) {
        transition: none;
      }
    }
  `
);

/**
 * A row of `<vaadin-tab>` elements, with the Lumo look and variants of `<vaadin-tabs>`. Set `theme="sliding"` for a
 * more mobile-friendly bar with a contrasting background, where the tabs share the width equally and an indicator
 * slides behind the selected tab. The `--or-*` properties style that variant, and its tabs keep the Lumo text styles,
 * which plain CSS on `vaadin-tab` overrides. Hidden tabs are left out of its layout, but only when they are hidden
 * before they are added or the selection changes.
 *
 * @customElement "or-vaadin-tabs"
 * @slot - The `<vaadin-tab>` elements.
 * @attr {string} orientation - `horizontal` (default) or `vertical`.
 * @attr {string} theme - Set to `sliding` for the bar with an indicator that slides behind the selected tab, or to a
 * Lumo variant of `<vaadin-tabs>`. Sliding tabs always fit the bar and take precedence over the Lumo variants they
 * conflict with, so `hide-scroll-buttons` and `show-scroll-buttons` have no effect with `sliding`.
 * @fires {CustomEvent} selected-changed - Fired when the `selected` property changes.
 * @fires {CustomEvent} items-changed - Fired when the `items` property changes.
 * @cssprop --or-tabs-background - Background of the bar.
 * @cssprop --or-tabs-indicator-color - Color of the indicator behind the selected tab.
 * @cssprop --or-tabs-padding - Space between the bar edge and the tabs.
 * @cssprop --or-tabs-gap - Space between the tabs.
 * @cssprop --or-tabs-radius - Corner radius of the tabs and the indicator, at most a full pill. The bar's corners
 * follow it around the padding. Defaults to the Lumo medium radius.
 * @cssprop --or-tabs-transition-duration - Duration of the indicator slide and the label color change.
 * @cssprop --or-tab-height - Minimum height of the tabs, which also caps their rounding at a full pill.
 */
@customElement("or-vaadin-tabs")
export class OrVaadinTabs extends (Tabs as new () => Tabs & LitElement) implements OrVaadinComponent {
  static get is() {
    return "or-vaadin-tabs";
  }

  protected override render() {
    const items = this.items ?? [];
    const visible = items.filter((item) => !(item as HTMLElement).hidden);
    const selected = this.selected == null ? undefined : items[this.selected];
    const index = selected ? visible.indexOf(selected) : -1;

    return html`
      <div
        id="indicator"
        aria-hidden="true"
        ?hidden=${index < 0}
        style=${styleMap({ "--_index": `${index}`, "--_count": `${visible.length}` })}
      ></div>
      ${super.render()}
    `;
  }
}
