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
import { GridStackEngine, type GridStackNode, type GridStackMoveOpts, type GridStack } from "gridstack";

/**
 * Stops the grid from re-ordering its DOM children to match the visual layout.
 * Grid items are rendered through Lit and positioned with CSS, so the order does not affect what the user sees.
 * Re-ordering widgets detaches and re-attaches each HTML element, therefore this prevents widget content from being removed.
 */
export function keepDomOrder(grid?: GridStack | null) {
  if (!grid) {
    return;
  }
  const internals = grid as unknown as { _sortDom?: () => void };
  if (typeof internals._sortDom !== "function") {
    throw new Error(
      "GridStack._sortDom is gone, so the grid cannot be stopped from re-ordering its DOM children. " +
        "Check what the supported gridstack version does on change events before raising it."
    );
  }
  internals._sortDom = () => {};
}

export class OrDashboardEngine extends GridStackEngine {
  // Cancelling move when it collides with the same widget.
  // Apparently during some rerenders, the widget moved downwards after colliding with itself.
  // Now cancelling the movement when the IDs are the same.
  public moveNode(node: GridStackNode, o: GridStackMoveOpts): boolean {
    if (o.skip && o.skip.id == node.id) {
      o.x = o.skip.x;
      o.y = o.skip.y;
      o.w = o.skip.w;
      o.h = o.skip.h;
    }
    return super.moveNode(node, o);
  }
}
