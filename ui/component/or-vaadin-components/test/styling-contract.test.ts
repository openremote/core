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
import { ct, expect } from "./fixtures";
import { StylingContractHarness } from "./fixtures/styling-contract-harness.js";

ct("should apply the styles of every exported component under the theme", async ({ mount }) => {
  const component = await mount(StylingContractHarness);

  expect(await component.evaluate((el: StylingContractHarness) => el.violations)).toEqual([]);
});
