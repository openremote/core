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
import { ConfirmDialog } from "@vaadin/confirm-dialog";
import type { OrVaadinComponent } from "./util";
import { css, type TemplateResult, type LitElement, render, html } from "lit";
import { customElement } from "lit/decorators.js";
import { when } from "lit/directives/when.js";
import { Util } from "@openremote/core";

type WithLit<T> = T & typeof LitElement;

/**
 * Helper function for rendering the `<or-vaadin-confirm-dialog>` dynamically, to "show a dialog on command".
 * This saves initial render time for components, and prevents state handling on the consumer side.
 * `showConfirmDialog()` appends (and updates) dialogs to the host element when called,
 * and automatically removes the element upon closing the dialog.
 * It also conveniently adds HTML attributes like `cancel-button-visible` when a slotted element is present.
 * @param host - Host element (often shadow root) to append the dialog container as a child.
 * @param dialog - A {@link TemplateResult} to render. It is required to contain a `<or-vaadin-confirm-dialog>` tag.
 */
export function showConfirmDialog(host: Node, dialog: TemplateResult) {
  const container = document.createElement("div");
  container.id = `dialog-${Util.generateUniqueUUID()}`;
  render(dialog, container); // Render Lit template inside the container

  const dialogElem = container.querySelector("or-vaadin-confirm-dialog") as OrVaadinConfirmDialog | null;
  if (dialogElem) {
    dialogElem.opened = true;
    dialogElem.addEventListener("closed", () => container.remove());
    if (dialogElem.querySelector("[slot='cancel-button']")) {
      dialogElem.cancelButtonVisible = true;
    }
    if (dialogElem.querySelector("[slot='reject-button']")) {
      dialogElem.rejectButtonVisible = true;
    }
  } else {
    // As no or-vaadin-confirm-dialog is present, we can remove the HTMLElement
    container.remove();
    return;
  }
  host.appendChild(container);
  return dialogElem;
}

/**
 * Helper function for simplifying the generation of `<or-vaadin-confirm-dialog>` content.
 * Instead of using declarative HTML, this function provides an easy alternative with automatically translated keys.
 * Example: `getConfirmDialogContent("areYouSure", "deleteWarning", "remove", "cancel")`
 *
 * @param theme - Optional theme to use for the dialog and its buttons (for example 'error').
 * @param header - Dialog header that is either a translation key, or a {@link TemplateResult}
 * @param content - Dialog content that is either a translation key, or a {@link TemplateResult}
 * @param confirm - {@link TemplateResult} or translation key to display inside the "confirm" button. If `undefined` the button will not be visible.
 * @param cancel - {@link TemplateResult} or translation key to display inside the "cancel" button. If `undefined` the button will not be visible.
 * @param reject - {@link TemplateResult} or translation key to display inside the "reject" button. If `undefined` the button will not be visible.
 */
export function getConfirmDialogContent(
  theme: string | undefined,
  header: TemplateResult | string,
  content: TemplateResult | string,
  confirm?: TemplateResult | string,
  cancel?: TemplateResult | string,
  reject?: TemplateResult | string
): TemplateResult {
  return html`
    ${
      typeof header === "string"
        ? html`<or-translate slot="header" value=${header}></or-translate>`
        : html`<div slot="header">${header}</div>`
    }
    ${typeof content === "string" ? html`<or-translate value=${content}></or-translate>` : content}
    ${when(
      typeof confirm === "string",
      () => html`
        <or-vaadin-button theme=${theme && theme.includes(" ") ? theme : theme + " primary"} slot="confirm-button">
          <or-translate value=${confirm}></or-translate>
        </or-vaadin-button>
      `,
      confirm ? () => confirm : undefined
    )}
    ${when(
      typeof cancel === "string",
      () => html`
        <or-vaadin-button theme=${theme ? `tertiary ${theme}` : "tertiary"} slot="cancel-button">
          <or-translate value=${cancel}></or-translate>
        </or-vaadin-button>
      `,
      cancel ? () => cancel : undefined
    )}
    ${when(
      typeof reject === "string",
      () => html`
        <or-vaadin-button theme=${theme ? `tertiary ${theme}` : "tertiary"} slot="reject-button">
          <or-translate value=${reject}></or-translate>
        </or-vaadin-button>
      `,
      reject ? () => reject : undefined
    )}
  `;
}

/**
 * Helper function for rendering the `<or-vaadin-confirm-dialog>` dynamically, to "show an error dialog on command".
 * This saves initial render time for components, and prevents state handling on the consumer side.
 * It utilizes {@link showConfirmDialog}, to render the given message and title.
 * @param host - Host element (often shadow root) to append the dialog container as a child.
 * @param message - The translation key or {@link TemplateResult} to render as dialog message.
 * @param title - Optional translation key or {@link TemplateResult} to render as dialog title.
 */
export function showErrorDialog(
  host: Node,
  message: string | TemplateResult,
  title: string | TemplateResult = "errorOccurred"
) {
  return showConfirmDialog(
    host,
    html`
      <or-vaadin-confirm-dialog>
        ${getConfirmDialogContent("error tertiary", title, message, "close")}
      </or-vaadin-confirm-dialog>
    `
  );
}

@customElement("or-vaadin-confirm-dialog")
export class OrVaadinConfirmDialog
  extends (ConfirmDialog as new () => ConfirmDialog & LitElement)
  implements OrVaadinComponent
{
  static get styles() {
    return [
      (ConfirmDialog as WithLit<typeof ConfirmDialog>).styles,
      css`
        ::part(header),
        ::part(content) {
          background-color: var(--lumo-contrast-5pct);
          margin-inline-start: 0;
        }
        ::part(header),
        ::part(footer) {
          padding: var(--lumo-space-l);
        }
        ::part(content) {
          padding: 0 var(--lumo-space-l);
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
