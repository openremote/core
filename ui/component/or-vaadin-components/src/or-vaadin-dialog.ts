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
import { Dialog } from "@vaadin/dialog";
import type { OrVaadinComponent } from "./util";
import { customElement } from "lit/decorators.js";
import { type LitElement, type TemplateResult, css, render } from "lit";
import { Util } from "@openremote/core";

/**
 * Vaadin uses custom directives for rendering the dialog content.
 * https://lit.dev/docs/templates/custom-directives/
 * https://vaadin.com/docs/latest/components/dialog/
 */
export {
  dialogHeaderRenderer,
  DialogHeaderRendererDirective,
  dialogRenderer,
  DialogRendererDirective,
  dialogFooterRenderer,
  DialogFooterRendererDirective,
} from "@vaadin/dialog/lit";

type WithLit<T> = T & typeof LitElement;

/**
 * Helper function for rendering the `<or-vaadin-dialog>` dynamically, to "show a dialog on command".
 * This saves initial render time for components, and prevents state handling on the consumer side.
 * `showDialog()` appends (and updates) dialogs to the host element when called,
 * and automatically removes the element upon closing the dialog.
 * @param host - Host element (often shadow root) to append the dialog container as a child.
 * @param dialog - A {@link TemplateResult} to render. It is required to contain a `<or-vaadin-dialog>` tag.
 */
export function showDialog(host: Node, dialog: TemplateResult): OrVaadinDialog | undefined {
  const container = document.createElement("div");
  container.id = `dialog-${Util.generateUniqueUUID()}`;
  render(dialog, container); // Render Lit template inside the container

  const dialogElem = container.querySelector("or-vaadin-dialog") as OrVaadinDialog | null;
  if (dialogElem) {
    dialogElem.open();
    dialogElem.addEventListener("closed", () => container.remove());
  } else {
    // As no or-vaadin-dialog is present, we can remove the HTMLElement
    container.remove();
    return;
  }
  host.appendChild(container);
  return dialogElem;
}

@customElement("or-vaadin-dialog")
export class OrVaadinDialog extends (Dialog as new () => Dialog & LitElement) implements OrVaadinComponent {
  static get styles() {
    return [
      (Dialog as WithLit<typeof Dialog>).styles,
      css`
        ::part(header),
        ::part(content) {
          background-color: var(--lumo-contrast-5pct);
        }
        ::part(header) {
          /* Color header icons (e.g. the close cross) with the primary color */
          --internal-or-icon-fill: var(--lumo-primary-color);
        }
        ::part(header),
        ::part(footer) {
          padding: var(--lumo-space-m);
        }
        ::part(content) {
          padding: 0 var(--lumo-space-m);
        }
      `,
    ];
  }

  public open() {
    this.setAttribute("opened", "true");
  }

  public close() {
    this.toggleAttribute("opened", false);
  }
}
