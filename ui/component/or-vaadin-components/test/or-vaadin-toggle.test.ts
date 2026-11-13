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
import { ct, expect, type Locator } from "@openremote/test";

import { OrVaadinToggle } from "@openremote/or-vaadin-components/or-vaadin-toggle";
import { OrVaadinToggleGroup } from "@openremote/or-vaadin-components/or-vaadin-toggle-group";

/*
 * These tests mirror the scenarios in `stories/or-vaadin-toggle.stories.ts`:
 * the Primary, "Disabled & Readonly", "Helper text" and "Groups"
 * (vertical + horizontal) stories.
 *
 * Note: the component-test runner (@sand4rt/experimental-ct-web) forwards only
 * `event.detail` to `on` handlers, so the boolean state that comes through is
 * asserted via the `checked-changed` event (detail = `{ value: boolean }`); the
 * native `change` event carries no useful detail so it is only counted.
 */

ct.describe("Primary", () => {
  ct("renders label and checked state", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, {
      props: { label: "Toggle", checked: true },
    });

    // The toggle is exposed to assistive technology with the `switch` role, not `checkbox`.
    await expect(component.getByRole("switch", { name: "Toggle" })).toBeChecked();
    await expect(component).toContainText("Toggle");
  });
});

ct.describe("Events", () => {
  ct("change + checked-changed emit the new value on user toggle", async ({ mount }) => {
    const checkedChanges: boolean[] = [];
    let changeCount = 0;

    const component = await mount(OrVaadinToggle, {
      props: { label: "Toggle" },
      on: {
        change: () => {
          changeCount += 1;
        },
        "checked-changed": (detail: { value: boolean }) => {
          checkedChanges.push(detail.value);
        },
      },
    });

    const input = component.getByRole("switch", { name: "Toggle" });
    await expect(input).not.toBeChecked();

    // Turn on
    await input.click();
    await expect(input).toBeChecked();

    // Turn off
    await input.click();
    await expect(input).not.toBeChecked();

    // Vaadin emits an initial checked-changed(false) at mount, so assert on the user-driven
    // transitions (the last two values) and that `change` only fires on interaction.
    await expect.poll(() => changeCount).toBe(2);
    await expect.poll(() => checkedChanges.slice(-2)).toEqual([true, false]);
  });
});

ct.describe("State", () => {
  ct("off (default) is unchecked", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Off (default)" } });
    await expect(component.getByRole("switch", { name: "Off (default)" })).not.toBeChecked();
  });

  ct("on is checked", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "On", checked: true } });
    await expect(component.getByRole("switch", { name: "On" })).toBeChecked();
  });

  ct("disabled cannot be toggled and emits no change event", async ({ mount }) => {
    const checkedChanges: boolean[] = [];
    let changeCount = 0;
    const component = await mount(OrVaadinToggle, {
      props: { label: "Disabled", disabled: true },
      on: {
        change: () => {
          changeCount += 1;
        },
        "checked-changed": (detail: { value: boolean }) => checkedChanges.push(detail.value),
      },
    });

    const input = component.getByRole("switch", { name: "Disabled" });
    await expect(input).toBeDisabled();
    await input.click({ force: true });
    await expect(input).not.toBeChecked();
    // The click must fire no `change` event (which has no mount-time noise); and `checked-changed`
    // must never carry `true` (a single checked-changed(false) may be emitted once at mount).
    expect(changeCount).toBe(0);
    expect(checkedChanges).not.toContain(true);
  });

  ct("disabled + on stays checked", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Disabled on", checked: true, disabled: true } });
    const input = component.getByRole("switch", { name: "Disabled on" });
    await expect(input).toBeDisabled();
    await expect(input).toBeChecked();
  });

  ct("readonly does not change on click and emits no change event", async ({ mount }) => {
    const checkedChanges: boolean[] = [];
    let changeCount = 0;
    const component = await mount(OrVaadinToggle, {
      props: { label: "Readonly", readonly: true },
      on: {
        change: () => {
          changeCount += 1;
        },
        "checked-changed": (detail: { value: boolean }) => checkedChanges.push(detail.value),
      },
    });

    const input = component.getByRole("switch", { name: "Readonly" });
    await input.click({ force: true });
    await expect(input).not.toBeChecked();
    // The click must fire no `change` event (which has no mount-time noise); and `checked-changed`
    // must never carry `true` (a single checked-changed(false) may be emitted once at mount).
    expect(changeCount).toBe(0);
    expect(checkedChanges).not.toContain(true);
  });

  ct("readonly + on stays checked", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Readonly on", checked: true, readonly: true } });
    await expect(component.getByRole("switch", { name: "Readonly on" })).toBeChecked();
  });

  ct("helper text renders below the toggle", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, {
      props: { label: "Toggle", helperText: "Helper text shown below the toggle" },
    });

    await expect(component).toContainText("Helper text shown below the toggle");
    await expect(component).toHaveAttribute("has-helper");
  });

  ct("required reflects the required attribute", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Required", required: true } });
    await expect(component).toHaveAttribute("required");
  });

  ct("renders without a label", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { checked: true } });
    await expect(component.getByRole("switch")).toBeChecked();
    await expect(component).not.toHaveAttribute("has-label");
  });
});

ct.describe("Group", () => {
  ct("vertical: stacks grouped toggles in a column layout", async ({ mount }) => {
    const component = await mount(OrVaadinToggleGroup, {
      props: { label: "Notifications" },
      hooksConfig: { components: [OrVaadinToggle] },
      slots: {
        default: [
          '<or-vaadin-toggle label="Email" value="email"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="SMS" value="sms"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="Push" value="push"></or-vaadin-toggle>',
        ],
      },
    });

    // `theme` is attribute-driven (there is no writable reflecting property), and mount `props` set JS
    // properties which Vaadin's theme handling ignores - so set the vertical theme as an attribute.
    await component.evaluate((el) => el.setAttribute("theme", "vertical"));

    await expect(component).toContainText("Notifications");
    await expect(component.getByRole("switch")).toHaveCount(3);

    // The vertical theme stacks the toggles: shared left edge, increasing top.
    const email = await component.getByRole("switch", { name: "Email" }).boundingBox();
    const sms = await component.getByRole("switch", { name: "SMS" }).boundingBox();
    expect(email).not.toBeNull();
    expect(sms).not.toBeNull();
    expect(Math.abs(email!.x - sms!.x)).toBeLessThan(4);
    expect(sms!.y).toBeGreaterThan(email!.y);
  });

  ct("horizontal: renders grouped toggles in a row layout", async ({ mount }) => {
    const component = await mount(OrVaadinToggleGroup, {
      props: { label: "Notifications" },
      hooksConfig: { components: [OrVaadinToggle] },
      slots: {
        default: [
          '<or-vaadin-toggle label="Email" value="email"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="SMS" value="sms"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="Push" value="push"></or-vaadin-toggle>',
        ],
      },
    });

    await expect(component.getByRole("switch")).toHaveCount(3);

    // Under the theme the group is horizontal by default: the toggles share a row (same top edge).
    const first = await component.getByRole("switch", { name: "Email" }).boundingBox();
    const second = await component.getByRole("switch", { name: "SMS" }).boundingBox();
    expect(first).not.toBeNull();
    expect(second).not.toBeNull();
    expect(Math.abs(first!.y - second!.y)).toBeLessThan(4);
    expect(second!.x).toBeGreaterThan(first!.x);
  });

  ct("registers toggles as group items and tracks the group value", async ({ mount }) => {
    // Guards the `__filterCheckboxes` override in `or-vaadin-checkbox-group`: without it, Vaadin only
    // registers literal `vaadin-checkbox` tags and the group `value` would stay empty.
    const component = await mount(OrVaadinToggleGroup, {
      props: { label: "Notifications" },
      hooksConfig: { components: [OrVaadinToggle] },
      slots: {
        default: [
          '<or-vaadin-toggle label="Email" value="email"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="SMS" value="sms"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="Push" value="push"></or-vaadin-toggle>',
        ],
      },
    });

    await expect.poll(() => component.evaluate((el: any) => el.value)).toEqual([]);

    await component.getByRole("switch", { name: "Email" }).click();
    await expect.poll(() => component.evaluate((el: any) => el.value)).toEqual(["email"]);

    await component.getByRole("switch", { name: "Push" }).click();
    await expect.poll(() => component.evaluate((el: any) => el.value)).toEqual(["email", "push"]);

    await component.getByRole("switch", { name: "SMS" }).click();
    await expect.poll(() => component.evaluate((el: any) => el.value)).toEqual(["email", "push", "sms"]);

    await component.getByRole("switch", { name: "Email" }).click();
    await expect.poll(() => component.evaluate((el: any) => el.value)).toEqual(["push", "sms"]);
  });

  ct("disabled and readonly propagate to the slotted toggles", async ({ mount }) => {
    const component = await mount(OrVaadinToggleGroup, {
      props: { label: "Notifications" },
      hooksConfig: { components: [OrVaadinToggle] },
      slots: {
        default: [
          '<or-vaadin-toggle label="Email" value="email"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="SMS" value="sms"></or-vaadin-toggle>',
        ],
      },
    });

    await component.evaluate((el: any) => {
      el.disabled = true;
    });
    await expect(component.getByRole("switch", { name: "Email" })).toBeDisabled();
    await expect(component.getByRole("switch", { name: "SMS" })).toBeDisabled();

    await component.evaluate((el: any) => {
      el.disabled = false;
      el.readonly = true;
    });
    const email = component.getByRole("switch", { name: "Email" });
    await email.click({ force: true });
    await expect(email).not.toBeChecked();
  });

  ct("a child toggle switches independently when clicked", async ({ mount }) => {
    // Note: Vaadin's `checked-changed` is dispatched without `bubbles`/`composed`, so it does not
    // reach a group-level listener - the per-toggle event flow is covered by the "Events" tests above.
    const component = await mount(OrVaadinToggleGroup, {
      props: { label: "Notifications" },
      hooksConfig: { components: [OrVaadinToggle] },
      slots: {
        default: [
          '<or-vaadin-toggle label="Email" value="email"></or-vaadin-toggle>',
          '<or-vaadin-toggle label="SMS" value="sms"></or-vaadin-toggle>',
        ],
      },
    });

    const email = component.getByRole("switch", { name: "Email" });
    const sms = component.getByRole("switch", { name: "SMS" });
    await email.click();
    await expect(email).toBeChecked();
    await expect(sms).not.toBeChecked();
  });
});

/*
 * Image snapshots are not used in this repo, so the toggle's key visual invariants are asserted
 * through computed styles instead. This guards the restyled shadow internals against Vaadin
 * upgrades: a change in the base checkbox styles could otherwise e.g. hide the knob in the off
 * state or restore the 500 label weight without any of the behavioral tests failing.
 * The expected values are the defaults of the public `--or-toggle-*` variables.
 */
ct.describe("Styling", () => {
  /** Reads the computed styles of the sliding knob (the `::after` pseudo-element of the track). */
  function getKnobStyles(track: Locator) {
    return track.evaluate((el) => {
      const style = getComputedStyle(el, "::after");
      return { width: style.width, height: style.height, opacity: style.opacity, left: style.left };
    });
  }

  ct("track is a 30x20 pill that changes color when toggled on", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Toggle" } });
    const track = component.locator("[part='checkbox']");

    await expect(track).toHaveCSS("width", "30px");
    await expect(track).toHaveCSS("height", "20px");

    const offColor = await track.evaluate((el) => getComputedStyle(el).backgroundColor);
    await component.getByRole("switch").click();
    await expect(track).not.toHaveCSS("background-color", offColor);
  });

  ct("knob is visible in both states and slides when toggled", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Toggle" } });
    const track = component.locator("[part='checkbox']");

    // Off state: the knob must be visible at the left inset. The Vaadin base styles hide the
    // checkmark marker when unchecked, which must not apply to the knob.
    await expect.poll(() => getKnobStyles(track)).toEqual({ width: "14px", height: "14px", opacity: "1", left: "3px" });

    await component.getByRole("switch").click();

    // On state: still visible, slid to the right (track width - knob size - inset).
    await expect
      .poll(() => getKnobStyles(track))
      .toEqual({ width: "14px", height: "14px", opacity: "1", left: "13px" });
  });

  ct("label uses the default 400 font weight", async ({ mount }) => {
    // The Vaadin base styles set a 500 label weight that Lumo normally resets; the toggle
    // restores the default weight itself because the Lumo reset is keyed off the overridden `is`.
    const component = await mount(OrVaadinToggle, { props: { label: "Toggle" } });
    await expect(component.locator("[part='label']")).toHaveCSS("font-weight", "400");
  });

  ct("disabled dims the whole toggle", async ({ mount }) => {
    const component = await mount(OrVaadinToggle, { props: { label: "Disabled", disabled: true } });
    await expect(component).toHaveCSS("opacity", "0.5");
  });
});
