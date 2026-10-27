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
import { RadioButton } from "@vaadin/radio-group/vaadin-radio-button.js";
import { registerStyles, css } from "@vaadin/vaadin-themable-mixin/register-styles.js";
import type { OrVaadinComponent } from "./util";

/*
 * The radio keeps the Vaadin look, as the theme injects the Lumo radio-button modules for this tag. These styles only
 * lay the radio button out as a card and map the `--or-radio-card-*` properties onto the Vaadin ones.
 */
registerStyles(
  "or-vaadin-radio-card",
  css`
    :host {
      --_checked-color: var(--or-radio-card-checked-color, var(--lumo-primary-text-color, #47a942));
      --vaadin-selection-color: var(--_checked-color);
      --vaadin-radio-button-background: var(--or-radio-card-radio-color, var(--lumo-contrast-20pct, #d9dcd9));
      --vaadin-radio-button-dot-color: var(--or-radio-card-dot-color, var(--lumo-primary-contrast-color, #ffffff));
      --vaadin-radio-button-size: var(--or-radio-card-radio-size, 24px);
      /* Lumo draws the dot as a border, so it takes the radius */
      --vaadin-radio-button-dot-size: calc(var(--or-radio-card-dot-size, 8px) / 2);
      --vaadin-radio-button-label-padding: 0;

      position: relative;
      display: flex;
      align-items: center;
      gap: var(--or-radio-card-gap, var(--lumo-space-s, 8px));
      box-sizing: border-box;
      padding: var(--or-radio-card-padding, var(--lumo-space-m, 16px));
      border-radius: var(--or-radio-card-radius, var(--lumo-border-radius-l, 12px));
      background: var(--or-radio-card-background, var(--lumo-contrast-5pct, #f2f3f2));
      color: var(--or-radio-card-color, var(--lumo-body-text-color, #1f211f));
      cursor: var(--lumo-clickable-cursor, pointer);
      -webkit-tap-highlight-color: transparent;
      transition:
        background-color 0.15s,
        color 0.15s;
    }

    :host([checked]) {
      background: var(--or-radio-card-checked-background, var(--lumo-primary-color-10pct, #e9f5e8));
      color: var(--_checked-color);
    }

    [part="radio"] {
      flex: none;
      margin: 0;
    }

    :host([theme~="reverse"]) [part="radio"] {
      order: 1;
    }

    ::slotted([slot="icon"]) {
      flex: none;
    }

    ::slotted([slot="label"]) {
      flex: 1;
      min-width: 0;
    }

    /* The native input covers the whole card, so a click anywhere on it selects the option */
    ::slotted(input) {
      position: absolute;
      inset: 0;
      z-index: 1;
      box-sizing: border-box;
      width: 100%;
      height: 100%;
      margin: 0;
      border: 0;
      border-radius: inherit;
      opacity: 0;
      appearance: none;
      cursor: inherit;
    }
  `
);

/**
 * A radio button shown as a card with an optional icon, for use in `<or-vaadin-radio-group>` next to or instead
 * of `<or-vaadin-radio-button>`. The whole card selects the option, and the checked card takes the checked colors.
 *
 * @customElement "or-vaadin-radio-card"
 * @slot icon - Icon shown before the label. It takes the card's text color through `currentColor`.
 * @slot label - Label content, laid out by the consumer. Use the `label` attribute instead for plain text.
 * @attr {string} theme - Set to `reverse` to show the radio at the end of the card instead of the start.
 * @fires {CustomEvent} checked-changed - Fired when the `checked` property changes.
 * @cssprop --or-radio-card-background - Card background when not checked.
 * @cssprop --or-radio-card-color - Text and icon color when not checked.
 * @cssprop --or-radio-card-checked-background - Card background when checked.
 * @cssprop --or-radio-card-checked-color - Text and icon color, and radio fill, when checked.
 * @cssprop --or-radio-card-radio-color - Radio fill when not checked.
 * @cssprop --or-radio-card-dot-color - Color of the dot in a checked radio.
 * @cssprop --or-radio-card-radio-size - Diameter of the radio.
 * @cssprop --or-radio-card-dot-size - Diameter of the dot in a checked radio.
 * @cssprop --or-radio-card-gap - Space between the radio, icon and label.
 * @cssprop --or-radio-card-padding - Padding of the card.
 * @cssprop --or-radio-card-radius - Corner radius of the card.
 * @cssprop --or-radio-card-spacing - Space between the cards of an `<or-vaadin-radio-group>`, applied by the theme.
 */
@customElement("or-vaadin-radio-card")
export class OrVaadinRadioCard
  extends (RadioButton as new () => RadioButton & LitElement)
  implements OrVaadinComponent
{
  static get is() {
    return "or-vaadin-radio-card";
  }

  protected override render() {
    return html`
      <div part="radio" aria-hidden="true"></div>
      <slot name="icon"></slot>
      <slot name="label"></slot>
      <slot name="input"></slot>
    `;
  }
}
