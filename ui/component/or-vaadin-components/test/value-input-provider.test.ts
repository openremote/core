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

import { ValueInputProviderHarness } from "./fixtures/value-input-provider-harness.js";

// The picker shows local time in the format of the browser locale, so both are pinned for the expected values.
ct.use({ timezoneId: "Europe/Amsterdam", locale: "en-US" });

/** Returns the timestamp of a local time on 2026-01-02 in Europe/Amsterdam (UTC+1). */
function localTime(hours: number, minutes: number, seconds = 0, milliseconds = 0): number {
  return Date.UTC(2026, 0, 2, hours - 1, minutes, seconds, milliseconds);
}

// Every date and time value type (durations excluded), with the conversion of a timestamp to the JSON representation of
// that type. WellknownValueTypes names are inlined to keep @openremote/model out of the Node test body.
const DATE_TIME_VALUE_TYPES = [
  { valueType: "timestamp", toValue: (timestamp: number) => timestamp },
  { valueType: "timestampISO8601", toValue: (timestamp: number) => new Date(timestamp).toISOString() },
  { valueType: "dateAndTime", toValue: (timestamp: number) => timestamp },
];

for (const { valueType, toValue } of DATE_TIME_VALUE_TYPES) {
  ct.describe(`Date time input for ${valueType}`, () => {
    ct(
      "should show the value as local date and time and write back the edited value",
      async ({ mount, vaadinDateTimePicker }) => {
        const values: unknown[] = [];
        const component = await mount(ValueInputProviderHarness, {
          props: { valueType, value: toValue(localTime(10, 30)) },
          on: { "value-change": (value: unknown) => values.push(value) },
        });

        const timeInput = vaadinDateTimePicker.getTimeInput(component);
        await expect(vaadinDateTimePicker.getDateInput(component)).toHaveValue("01/02/2026");
        await expect(timeInput).toHaveValue("10:30 AM");
        await timeInput.fill("11:45 AM");
        await timeInput.press("Enter");

        // String types used to get a number written back, which fails their ISO 8601 pattern.
        await expect.poll(() => values).toEqual([toValue(localTime(11, 45))]);
      }
    );

    ct("should edit seconds when the value format shows seconds", async ({ mount, vaadinDateTimePicker }) => {
      const values: unknown[] = [];
      const component = await mount(ValueInputProviderHarness, {
        props: { valueType, value: toValue(localTime(10, 30, 45, 123)), format: { second: "numeric" } },
        on: { "value-change": (value: unknown) => values.push(value) },
      });

      const timeInput = vaadinDateTimePicker.getTimeInput(component);
      await expect(timeInput).toHaveValue("10:30:45 AM");
      await timeInput.fill("11:45:50 AM");
      await timeInput.press("Enter");

      await expect.poll(() => values).toEqual([toValue(localTime(11, 45, 50))]);
    });

    ct(
      "should edit milliseconds when the value format shows fractional seconds",
      async ({ mount, vaadinDateTimePicker }) => {
        const values: unknown[] = [];
        const component = await mount(ValueInputProviderHarness, {
          props: { valueType, value: toValue(localTime(10, 30, 45, 123)), format: { fractionalSecondDigits: 3 } },
          on: { "value-change": (value: unknown) => values.push(value) },
        });

        const timeInput = vaadinDateTimePicker.getTimeInput(component);
        await expect(timeInput).toHaveValue("10:30:45.123 AM");
        await timeInput.fill("11:45:50.456 AM");
        await timeInput.press("Enter");

        await expect.poll(() => values).toEqual([toValue(localTime(11, 45, 50, 456))]);
      }
    );

    ct("should write back null when the date and time are cleared", async ({ mount, vaadinDateTimePicker }) => {
      const values: unknown[] = [];
      const component = await mount(ValueInputProviderHarness, {
        props: { valueType, value: toValue(localTime(10, 30)) },
        on: { "value-change": (value: unknown) => values.push(value) },
      });

      const dateInput = vaadinDateTimePicker.getDateInput(component);
      const timeInput = vaadinDateTimePicker.getTimeInput(component);
      await dateInput.fill("");
      await dateInput.press("Enter");
      await timeInput.fill("");
      await timeInput.press("Enter");

      await expect.poll(() => values).toEqual([null]);
    });

    ct(
      "should render an empty picker for a value that is not a date and time",
      async ({ mount, vaadinDateTimePicker }) => {
        const component = await mount(ValueInputProviderHarness, {
          props: { valueType, value: "10:30:00" },
        });

        // Rendering used to throw on the invalid date, leaving no input at all.
        await expect(vaadinDateTimePicker.getDateInput(component)).toHaveValue("");
        await expect(vaadinDateTimePicker.getTimeInput(component)).toHaveValue("");
      }
    );
  });
}

ct("should edit seconds when the moment format shows seconds", async ({ mount, vaadinDateTimePicker }) => {
  const component = await mount(ValueInputProviderHarness, {
    props: { valueType: "timestamp", value: localTime(10, 30, 45), format: { momentJsFormat: "DD-MM-YYYY HH:mm:ss" } },
  });

  await expect(vaadinDateTimePicker.getTimeInput(component)).toHaveValue("10:30:45 AM");
});

ct("should show a number formatted as a date in minutes", async ({ mount, vaadinDateTimePicker }) => {
  const component = await mount(ValueInputProviderHarness, {
    props: { valueType: "long", value: localTime(10, 30, 45), format: { asDate: true } },
  });

  // The whole-number step of the number input used to reach the picker, which then showed seconds.
  await expect(vaadinDateTimePicker.getTimeInput(component)).toHaveValue("10:30 AM");
});

ct("should keep a date only format on the fallback input", async ({ mount }) => {
  const component = await mount(ValueInputProviderHarness, {
    props: { valueType: "timestamp", value: localTime(10, 30), format: { dateStyle: "short" } },
  });

  // The pickers take a local ISO string, and the provider converts the timestamp for the combined picker only.
  await expect(component.locator("or-vaadin-date-picker")).toHaveCount(0);
  await expect(component.locator("or-mwc-input")).toHaveCount(1);
});

ct("should keep a time only format on the fallback input", async ({ mount }) => {
  const component = await mount(ValueInputProviderHarness, {
    props: { valueType: "timestamp", value: localTime(10, 30), format: { timeStyle: "short" } },
  });

  await expect(component.locator("or-vaadin-time-picker")).toHaveCount(0);
  await expect(component.locator("or-mwc-input")).toHaveCount(1);
});

ct("should show a json object as formatted text and write back the edited value", async ({ mount }) => {
  const values: unknown[] = [];
  const component = await mount(ValueInputProviderHarness, {
    props: { valueType: "JSONObject", value: { a: 1 } },
    on: { "value-change": (value: unknown) => values.push(value) },
  });

  const textArea = component.getByRole("textbox");
  // The text area carries the value as text, so the object reaches it as formatted JSON.
  await expect(textArea).toHaveValue('{\n  "a": 1\n}');

  await textArea.fill('{"b": 2}');
  await textArea.blur();

  await expect.poll(() => values).toEqual([{ b: 2 }]);
});

ct("should show an empty text area for a json object that is null", async ({ mount }) => {
  const component = await mount(ValueInputProviderHarness, {
    props: { valueType: "JSONObject", value: null },
  });

  await expect(component.getByRole("textbox")).toHaveValue("");
});

ct("should round up a minimum that falls between two picker values", async ({ mount, vaadinDateTimePicker }) => {
  const values: unknown[] = [];
  const component = await mount(ValueInputProviderHarness, {
    props: {
      valueType: "timestamp",
      value: localTime(11, 0),
      constraints: [{ type: "min", min: localTime(10, 30, 45) }],
    },
    on: { "value-change": (value: unknown) => values.push(value) },
  });

  const timeInput = vaadinDateTimePicker.getTimeInput(component);
  await timeInput.fill("10:30 AM");
  await timeInput.press("Enter");
  await timeInput.fill("10:31 AM");
  await timeInput.press("Enter");

  // The minimum used to round down to 10:30, which the picker then allowed although it is before the minimum.
  await expect.poll(() => values).toEqual([localTime(10, 31)]);
});

ct("should keep a manually resizable text area at its min-rows height", async ({ mount }) => {
  const component = await mount(ValueInputProviderHarness, {
    props: {
      valueType: "text",
      multiline: true,
      options: { minRows: 5, manualresize: true },
      value: Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join("\n"),
    },
  });

  // The template sets the value before manualresize, so a value that is already set reaches
  // autoresize first. The thirty lines have to scroll inside the five rows.
  const textArea = component.getByRole("textbox", { name: "Attribute" });
  await expect(textArea).toHaveAttribute("rows", "5");
  await expect.poll(() => textArea.evaluate((el: HTMLTextAreaElement) => el.scrollHeight > el.clientHeight)).toBe(true);
});
