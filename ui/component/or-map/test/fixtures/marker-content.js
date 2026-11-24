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
// Marker modules only initialise when loaded through the package index.
import "@openremote/or-map";

export class MarkerMarkupFixture extends HTMLElement {
  // Builds the default marker content and reports the elements it contains.
  inspectMarkerContent(direction, displayValue) {
    const marker = document.createElement("or-map-marker");
    marker.icon = "test";
    marker.direction = direction;
    marker.displayValue = displayValue;

    const content = marker._createDefaultMarkerContent();
    const wrapper = content.querySelector(".direction-icon-wrapper");
    const label = content.querySelector(".label span");

    return {
      elements: [...content.querySelectorAll("*")].map((element) => element.localName),
      directionStyle: wrapper ? wrapper.getAttribute("style") : null,
      labelText: label ? label.textContent : null,
    };
  }
}

customElements.define("marker-markup-fixture", MarkerMarkupFixture);
