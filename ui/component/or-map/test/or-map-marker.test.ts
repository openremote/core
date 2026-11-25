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
import { ct, expect, type SharedComponentTestFixtures } from "@openremote/test";
import { MarkerMarkupFixture } from "./fixtures/marker-content.js";

type MarkerMarkup = {
  elements: string[];
  directionStyle: string | null;
  labelText: string | null;
};

const MARKUP = '<img src=x onerror="window.__ormarkerxss=true">';
const ICONS = ["or-icon", "or-icon"];
const DIRECTION_INDICATOR = ["div", "svg", "circle", "or-icon"];

type Mount = SharedComponentTestFixtures["mount"];
async function inspectMarkerContent(mount: Mount, direction?: string, displayValue?: string): Promise<MarkerMarkup> {
  const fixture = await mount(MarkerMarkupFixture);
  return fixture.evaluate(
    (element, args) =>
      (
        element as HTMLElement & {
          inspectMarkerContent(direction?: string, displayValue?: string): MarkerMarkup;
        }
      ).inspectMarkerContent(args.direction, args.displayValue),
    { direction, displayValue }
  );
}

ct("should not render markup from a direction value", async ({ mount }) => {
  const result = await inspectMarkerContent(mount, `0");">${MARKUP}`);
  // Used to break out of the rotate() style attribute and add the <img> to the marker.
  expect(result.elements).toEqual(ICONS);
});

for (const direction of ["not-a-number", "Infinity"]) {
  ct(`should omit the direction indicator for "${direction}"`, async ({ mount }) => {
    const result = await inspectMarkerContent(mount, direction);
    expect(result.elements).toEqual(ICONS);
  });
}

for (const direction of ["0", "45", "-90", "12.5"]) {
  ct(`should rotate the direction indicator by ${direction} degrees`, async ({ mount }) => {
    const result = await inspectMarkerContent(mount, direction);
    expect(result.elements).toEqual([...DIRECTION_INDICATOR, ...ICONS]);
    expect(result.directionStyle).toContain(`rotate(${direction}deg)`);
  });
}

ct("should render the label value as text", async ({ mount }) => {
  const result = await inspectMarkerContent(mount, undefined, MARKUP);
  // Used to render the label value as markup.
  expect(result.elements).toEqual(["div", "span", ...ICONS]);
  expect(result.labelText).toBe(MARKUP);
});
