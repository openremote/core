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
import { Checkbox } from "@vaadin/checkbox";
import { registerStyles, css } from "@vaadin/vaadin-themable-mixin/register-styles.js";
import type { OrVaadinComponent } from "./util";
import type { LitElement } from "lit";

/*
 * The toggle reuses the Vaadin `<vaadin-checkbox>` as its base, so it inherits the checkbox's
 * behavior, form participation, accessibility and `checked` / `readonly` / `disabled` states.
 * Only the visuals and the exposed role differ: the `checkbox` part is restyled into a pill-shaped
 * track with a sliding knob, and the input is exposed as a `switch` to assistive technology.
 *
 * The styles are applied through Vaadin's `registerStyles()` rather than a `static get styles()`
 * override, because `finalizeStyles()` injects `static` styles BEFORE the (Lumo) theme injector,
 * meaning a themed environment would override them. Styles registered via `registerStyles()` are
 * injected AFTER the theme, so they always take effect - both when the OpenRemote theme is applied
 * and when the component is used standalone (hence every value below has a hard-coded fallback
 * alongside its theme variable).
 *
 * `registerStyles()` targets a component by its `is` tag name and must run before the element is
 * finalized. It is therefore called at module load, and `is` is overridden to `or-vaadin-toggle`
 * (the base class would otherwise report `vaadin-checkbox`, causing these styles to leak onto every
 * checkbox, or not match at all).
 */
registerStyles(
  "or-vaadin-toggle",
  css`
    :host {
      /* Public variables, overridable by consumers; the values are defaults with standalone fallbacks */
      --or-toggle-track-width: 30px;
      --or-toggle-track-height: 20px;
      --or-toggle-knob-size: 14px;
      --or-toggle-knob-inset: 3px;
      --or-toggle-radius: var(--lumo-border-radius-l, 12px);
      --or-toggle-track-off-color: var(--lumo-contrast-30pct, #c4c8c4);
      --or-toggle-track-on-color: var(--lumo-primary-color, #47a942);
      --or-toggle-knob-color: var(--lumo-base-color, #ffffff);
      --or-toggle-gap: 6px;
      --or-toggle-transition-duration: 0.15s;

      align-items: center;
      --vaadin-checkbox-gap: var(--or-toggle-gap);
    }

    /*
     * The Vaadin base styles give the label a 500 font weight. Lumo resets that for the checkbox,
     * but that reset is keyed off 'is' (overridden here), so restore the default weight ourselves.
     */
    :host [part="label"],
    :host([has-label]) ::slotted([slot="label"]) {
      font-weight: 400;
    }

    /*
     * Turn the square checkbox part into a pill-shaped track. Selectors are deliberately prefixed
     * with :host(...) to out-specify the Vaadin/Lumo base rules (e.g. the base hides the ::after
     * marker with a high-specificity ':host(:not([checked])) [part=checkbox]::after { opacity: 0 }'
     * rule, which would otherwise hide the knob in the off state).
     */
    :host [part="checkbox"] {
      box-sizing: border-box;
      width: var(--or-toggle-track-width);
      min-width: var(--or-toggle-track-width);
      height: var(--or-toggle-track-height);
      border: none;
      border-radius: var(--or-toggle-radius);
      background: var(--or-toggle-track-off-color);
      transition: background-color var(--or-toggle-transition-duration, 0.15s) ease-in-out;
    }

    :host([checked]) [part="checkbox"] {
      background: var(--or-toggle-track-on-color);
    }

    :host([disabled]) {
      opacity: 0.5;
    }

    /* Replace the checkmark with a sliding knob */
    :host [part="checkbox"]::after {
      content: "";
      position: absolute;
      inset: auto;
      top: 50%;
      width: var(--or-toggle-knob-size);
      height: var(--or-toggle-knob-size);
      min-width: 0;
      border-radius: 50%;
      background: var(--or-toggle-knob-color);
      mask: none;
      -webkit-mask: none;
      filter: none;
      transform: translateY(-50%);
      transition: left var(--or-toggle-transition-duration, 0.15s) ease-in-out;
    }

    :host(:not([checked])) [part="checkbox"]::after {
      opacity: 1;
      left: var(--or-toggle-knob-inset);
    }

    :host([checked]) [part="checkbox"]::after {
      opacity: 1;
      left: calc(var(--or-toggle-track-width) - var(--or-toggle-knob-size) - var(--or-toggle-knob-inset));
    }

    :host([focus-ring]) [part="checkbox"] {
      outline: 2px solid var(--or-toggle-track-on-color);
      outline-offset: 2px;
    }
  `
);

/**
 * A toggle (switch) component representing a binary on/off choice.
 *
 * @customElement "or-vaadin-toggle"
 * @fires {Event} change - Fired when the toggle is switched on or off by the user.
 * @fires {CustomEvent} checked-changed - Fired when the `checked` property changes.
 * @cssprop --or-toggle-track-width - Width of the track.
 * @cssprop --or-toggle-track-height - Height of the track.
 * @cssprop --or-toggle-knob-size - Diameter of the sliding knob.
 * @cssprop --or-toggle-knob-inset - Distance between the knob and the track edge.
 * @cssprop --or-toggle-radius - Border radius of the track.
 * @cssprop --or-toggle-track-off-color - Track color in the off state.
 * @cssprop --or-toggle-track-on-color - Track color in the on state.
 * @cssprop --or-toggle-knob-color - Color of the knob.
 * @cssprop --or-toggle-gap - Gap between the track and the label.
 * @cssprop --or-toggle-transition-duration - Duration of the track color and knob slide transitions.
 */
@customElement("or-vaadin-toggle")
export class OrVaadinToggle extends (Checkbox as new () => Checkbox & LitElement) implements OrVaadinComponent {
  static get is() {
    return "or-vaadin-toggle";
  }

  /**
   * Exposes the control to assistive technology as a switch instead of a checkbox, matching its
   * on/off semantics (and the `role="switch"` of the or-mwc-input switch it replaces). The native
   * checkbox input keeps mapping its checked state to `aria-checked`.
   */
  protected override _inputElementChanged(input: HTMLElement, oldInput: HTMLElement) {
    super._inputElementChanged(input, oldInput);
    input?.setAttribute("role", "switch");
  }
}
