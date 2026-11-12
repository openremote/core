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
import { ct, expect } from "@openremote/test";
import { OrIcon } from "@openremote/or-icon";

ct("should show Material Design icons without declaring the font in its shadow root", async ({ mount }) => {
  const component = await mount(OrIcon, { props: { icon: "mdi:home" } });

  const shadow = await component.evaluate((element) => {
    const root = element.shadowRoot!;
    const sheets = [...root.adoptedStyleSheets, ...[...root.querySelectorAll("style")].map((style) => style.sheet!)];
    const rules = sheets.flatMap((sheet) => [...sheet.cssRules]);
    return {
      fontFaces: rules.filter((rule) => rule instanceof CSSFontFaceRule).length,
      glyph: getComputedStyle(root.querySelector("span")!, "::before").content,
    };
  });

  expect(shadow.glyph).not.toBe("none");
  // The bundled @font-face resolved its font URL against the page, which WebKit requested and got a 404 for
  expect(shadow.fontFaces).toBe(0);
});
