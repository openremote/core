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
import { DefaultColor3, DefaultColor4, DefaultColor5 } from "@openremote/core";
import { css, unsafeCSS } from "lit";

// language=CSS
export const baseStyle = css`
  :host {
    flex: 1;
  }

  .item-container {
    display: flex;
  }

  .delete-container,
  .drag-container {
    width: 30px;
    display: flex;
    vertical-align: middle;
  }

  .item-container:hover .button-clear,
  .item-wrapper:hover .button-clear {
    visibility: visible;
  }

  .no-match-container {
    align-items: center;
  }

  .no-match-container > *:not(:last-child) {
    margin-right: 10px;
  }

  .button-clear {
    background: none;
    color: ${unsafeCSS(DefaultColor5)};
    --or-icon-fill: ${unsafeCSS(DefaultColor5)};
    visibility: hidden;
    display: inline-block;
    border: none;
    padding: 0 0 0 5px;
    cursor: pointer;
  }
  .button-clear:hover {
    --or-icon-fill: ${unsafeCSS(DefaultColor4)};
  }
  .button-clear:focus {
    outline: 0;
  }
  .button-clear.hidden {
    visibility: hidden;
  }

  .any-of-picker {
    width: 100%;
  }

  #errors {
    color: red;
    margin-right: 10px;
    flex: 1;
    display: flex;
    align-items: center;
  }

  #errors > or-icon {
    margin-right: 5px;
  }
`;

// language=CSS
export const panelStyle = css`
  #header-description {
    flex: 1;
    display: flex;
    flex-direction: row;
  }

  #type-label {
    border: 1px solid var(--or-app-color4, green);
    border-radius: 3px;
    padding: 3px;
    margin-left: 10px;
  }

  #header-buttons {
    flex: 0;
  }

  #content-wrapper {
    flex: 1;
    padding: 0 4px 14px 24px;
  }

  #content {
    display: flex;
    flex-direction: column;
  }

  #content > * {
    flex: 1;
  }

  .item-container + .item-container {
    padding-top: 10px;
  }

  #footer {
    margin-top: 10px;
  }
`;

/**
 * Layout for the "add item" and "add parameter" dialogs. Their content is rendered into the shadow root
 * of the element that opens them, so it is styled from that element's own styles.
 */
// language=CSS
export const addItemOrParameterDialogStyle = css`
  #dialog-content {
    display: flex;
    gap: var(--lumo-space-s);
    /* A short list still fills the dialog, so its height does not jump with the selection */
    min-height: 600px;
    max-height: 60vh;
  }

  /* A form holding nothing but a key field sizes to that field instead */
  #dialog-content.auto-height {
    min-height: unset;
  }

  /* The panes read as cards against the tinted dialog background, which is what separates them */
  #type-list,
  #parameter-desc {
    background-color: var(--lumo-base-color, #fff);
    border-radius: var(--lumo-border-radius-m);
  }

  /* The list scrolls through its own items part, so the host only has to clip the rounded corners */
  #type-list {
    min-width: 150px;
    max-width: 300px;
    flex: 0 0 40%;
    padding: var(--lumo-space-s) 0;
    overflow: hidden;
  }

  #parameter-title {
    text-transform: capitalize;
    color: var(--or-app-color3, ${unsafeCSS(DefaultColor3)});
    font-size: 18px;
    font-weight: bold;
  }

  #parameter-desc {
    padding: var(--lumo-space-m);
    flex: 1;
    display: flex;
    flex-direction: column;
    justify-content: flex-start;
    overflow: auto;
  }

  #dialog-footer {
    width: 100%;
    display: flex;
    justify-content: space-between;
  }
`;

/**
 * Inline style for the JSON editor, which opens on an arbitrary host and so cannot rely on that host's styles.
 * It matches the panes above: same height, and rounded corners against the tinted dialog background.
 */
export const jsonEditorStyle =
  "width: 100%; height: 60vh; min-height: 600px; border-radius: var(--lumo-border-radius-m); overflow: hidden;";
