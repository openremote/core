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
import { css, unsafeCSS } from "lit";
import {
  DefaultColor1,
  DefaultColor2,
  DefaultColor3,
  DefaultColor5,
  DefaultColor4,
  DefaultColor6,
  DefaultHeaderHeight,
} from "@openremote/core";

// language=CSS
export const panelStyles = css`
  .panelContainer {
    flex: 1 1 50%;
    box-sizing: border-box;
    min-width: 400px;
    padding: 0 5px;
  }

  .panel {
    background-color: var(--internal-or-asset-viewer-panel-color);
    border: 1px solid #e5e5e5;
    border-radius: 5px;
    position: relative;
    margin: 0 0 10px 0;
  }

  .panel-content-wrapper {
    padding: var(--internal-or-asset-viewer-panel-padding);
  }

  .panel-content > :first-child {
    margin-top: 0;
  }
  .panel-content > :last-child {
    margin-bottom: 0;
  }

  .panel-content {
    display: flex;
    flex-wrap: wrap;
  }

  .panel-title {
    display: flex;
    align-items: center;
    font-size: 18px;
    font-weight: bold;
    color: var(--internal-or-asset-viewer-title-text-color);
    margin-bottom: 20px;
    flex: 0 0 auto;
  }

  .panel-title-actions {
    display: flex;
    gap: 8px;
    margin-left: auto;
    font-size: initial;
    font-weight: initial;
  }

  .field {
    margin: 10px 0;
    width: 100%;
    flex: 0 0 auto;
  }

  .field > * {
    width: 100%;
    box-sizing: border-box;
  }

  @media screen and (max-width: 767px) {
    .panel {
      border-radius: 0;
      border-right: none;
      border-left: none;
      flex-basis: 100%;
      min-width: 360px;
    }
  }

  #linkedUsers-panel {
    min-height: 200px;
  }
`;

// language=CSS
export const style = css`
  :host {
    --internal-or-asset-viewer-background-color: var(
      --or-asset-viewer-background-color,
      var(--or-app-color2, ${unsafeCSS(DefaultColor2)})
    );
    --internal-or-asset-viewer-panel-margin: var(--or-asset-viewer-panel-margin, 20px);
    --internal-or-asset-viewer-panel-padding: var(--or-asset-viewer-panel-padding, 24px);
    --internal-or-asset-viewer-text-color: var(
      --or-asset-viewer-text-color,
      var(--or-app-color3, ${unsafeCSS(DefaultColor3)})
    );
    --internal-or-asset-viewer-title-text-color: var(
      --or-asset-viewer-title-text-color,
      var(--or-app-color3, ${unsafeCSS(DefaultColor3)})
    );
    --internal-or-asset-viewer-panel-color: var(
      --or-asset-viewer-panel-color,
      var(--or-app-color1, ${unsafeCSS(DefaultColor1)})
    );
    --internal-or-asset-viewer-line-color: var(
      --or-asset-viewer-line-color,
      var(--or-app-color5, ${unsafeCSS(DefaultColor5)})
    );
    --internal-or-asset-viewer-button-color: var(
      --or-asset-viewer-button-color,
      var(--or-app-color4, ${unsafeCSS(DefaultColor4)})
    );
    --internal-or-asset-viewer-error-color: var(
      --or-asset-viewer-error-color,
      var(--or-app-color6, ${unsafeCSS(DefaultColor6)})
    );
    --internal-or-header-height: var(--or-header-height, ${unsafeCSS(DefaultHeaderHeight)});

    height: 100%;
    width: 100%;
    background-color: var(--internal-or-asset-viewer-background-color);
  }

  *[hidden] {
    display: none;
  }

  #wrapper {
    height: 100%;
    width: 100%;
    display: flex;
    flex-direction: column;
  }

  #wrapper.saving {
    opacity: 0.5;
    pointer-events: none;
  }

  #view-container,
  #edit-container {
    flex: 0 1 auto;
    overflow: auto;
  }

  #view-container {
    flex: 1;
    margin-top: 0;
    box-sizing: border-box;
    display: flex;
    flex-direction: row;
    flex-wrap: wrap;
    width: 100%;
    padding: 0 15px 15px;

    -webkit-animation: fadein 0.3s; /* Safari, Chrome and Opera > 12.1 */
    -moz-animation: fadein 0.3s; /* Firefox < 16 */
    -ms-animation: fadein 0.3s; /* Internet Explorer */
    -o-animation: fadein 0.3s; /* Opera < 12.1 */
    animation: fadein 0.3s;
  }

  #edit-container {
    padding: 10px 20px 0;
  }

  #name-input {
    width: 300px;
  }

  @keyframes fadein {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  /* Firefox < 16 */
  @-moz-keyframes fadein {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  /* Safari, Chrome and Opera > 12.1 */
  @-webkit-keyframes fadein {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  /* Internet Explorer */
  @-ms-keyframes fadein {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  /* Opera < 12.1 */
  @-o-keyframes fadein {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }

  #asset-header {
    padding: 20px 30px 15px;
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: space-between;
    z-index: 1;
    transition: box-shadow 0.2s;
    box-shadow: none;
  }

  #asset-header.editmode {
    padding: 14px 30px;
    background-color: var(--internal-or-asset-viewer-panel-color);
    border-bottom: solid 1px #e5e5e5;
  }
  #asset-header.scrolled {
    box-shadow: rgb(0 0 0 / 15%) 0 0 5px 0;
  }

  #title {
    flex: 1 1 auto;
    font-size: 18px;
    font-weight: bold;
    display: flex;
    flex-direction: row;
    align-items: center;
    color: var(--internal-or-asset-viewer-title-text-color);
  }

  #title > or-icon {
    margin-right: 10px;
  }

  #error-wrapper {
    color: var(--internal-or-asset-viewer-error-color);
  }

  #error-wrapper > * {
    vertical-align: middle;
  }

  #error-wrapper or-translate {
    margin-left: 5px;
  }

  #created-time {
    font-size: 12px;
  }

  #right-wrapper {
    flex: 1 1 auto;
    text-align: right;
  }

  #edit-wrapper {
    flex: 0 0 auto;
  }

  #edit-wrapper > or-translate {
    margin-right: 10px;
  }

  #save-btn {
    margin-left: 20px;
  }

  #edit-btn {
    margin-left: 15px;
  }

  #location-panel .panel-content {
    height: 100%;
  }

  #history-panel .panel-content {
    position: relative;
  }

  .msg {
    display: flex;
    justify-content: center;
    align-items: center;
    text-align: center;
    height: 100%;
  }

  .back-navigation {
    display: none;
    cursor: pointer;
  }

  #fileupload,
  #discovery {
    display: flex;
    align-items: end;
    width: 100%;
    margin-bottom: 10px;
    gap: 10px;
  }

  .hidden {
    display: none;
  }

  @media screen and (max-width: 767px) {
    #wrapper {
      position: absolute;
      left: 0;
      right: 0;
    }

    .back-navigation {
      display: block;
    }

    .mobileHidden {
      display: none;
    }

    #asset-header {
      padding: 15px 15px 15px;
    }

    #name-input {
      width: auto;
    }

    #view-container {
      padding: 0;
    }

    #edit-container {
      padding: 10px 0;
    }

    .panelContainer {
      min-width: 360px;
      padding: 0;
    }
  }

  @media screen and (max-width: 1130px) {
    #name-input {
      width: 150px;
    }

    .tabletHidden {
      display: none;
    }

    #view-container {
      flex-direction: column;
      flex-wrap: nowrap;
    }

    .panelContainer {
      flex: 0 1 auto;
    }
  }
`;
