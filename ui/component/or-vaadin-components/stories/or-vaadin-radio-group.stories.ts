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
import { setCustomElementsManifest, type Meta, type StoryObj } from "@storybook/web-components";
import { getORStorybookHelpers } from "../../storybook-utils";
import customElements from "../build/custom-elements.json" with { type: "json" };
import packageJson from "../package.json" with { type: "json" };
import { html } from "lit";
import "../src/or-vaadin-radio-group";

const tagName = "or-vaadin-radio-group";
type Story = StoryObj;
setCustomElementsManifest(customElements);

const { events, args, argTypes, template } = getORStorybookHelpers(tagName);

const radioButtons = html`
  <or-vaadin-radio-button label="Email" value="email"></or-vaadin-radio-button>
  <or-vaadin-radio-button label="SMS" value="sms"></or-vaadin-radio-button>
  <or-vaadin-radio-button label="Push" value="push"></or-vaadin-radio-button>
`;

const meta: Meta = {
  title: "Playground/or-vaadin-components/radio-group",
  component: tagName,
  args,
  argTypes,
  render: (storyArgs) => template(storyArgs, radioButtons),
  excludeStories: /^[a-z].*/,
  parameters: {
    actions: {
      handles: events,
    },
    docs: {
      subtitle: `<${tagName}>`,
      description:
        "Radio group lets the user pick exactly one option from a set of `<or-vaadin-radio-button>` or `<or-vaadin-radio-card>` children. The group holds the selected `value` and provides the label, helper text and validation, and it supports the same states (disabled, readonly, required) and events (`change`, `value-changed`) as the other Vaadin fields.",
    },
  },
};

export const Primary: Story = {
  args: {
    label: "Notifications",
    value: "email",
  },
};

/** Disabled and readonly radio groups. */
export const DisabledReadonly: Story = {
  name: "Disabled & Readonly",
  parameters: {
    title: "Disabled & Readonly",
    summary:
      "Use `disabled` to block interaction entirely and `readonly` to show a value that cannot be edited. Both apply to all options in the group.",
    docs: {
      story: {
        height: "110px",
      },
    },
  },
  render: () => html`
    <div style="display: grid; grid-template-columns: repeat(2, max-content); gap: 24px 64px; padding: 8px;">
      <or-vaadin-radio-group label="Disabled" value="email" disabled>${radioButtons}</or-vaadin-radio-group>
      <or-vaadin-radio-group label="Readonly" value="email" readonly>${radioButtons}</or-vaadin-radio-group>
    </div>
  `,
};

/** Radio groups in both orientations. */
export const Orientation: Story = {
  name: "Orientation",
  parameters: {
    title: "Orientation",
    summary: 'Under the theme the group is horizontal by default; use `theme="vertical"` to stack the options.',
    docs: {
      story: {
        height: "280px",
      },
    },
  },
  render: () => html`
    <div style="display: flex; flex-direction: column; gap: 32px; padding: 8px;">
      <or-vaadin-radio-group label="Notifications (vertical)" theme="vertical" value="email">
        ${radioButtons}
      </or-vaadin-radio-group>
      <or-vaadin-radio-group label="Notifications (horizontal)" value="email">${radioButtons}</or-vaadin-radio-group>
    </div>
  `,
};

/** Helper text gives extra context below the options. */
export const HelperText: Story = {
  name: "Helper text",
  parameters: {
    title: "Helper text",
    summary:
      "Use `helper-text` to show additional context below the options, for example to explain why a choice is `required`.",
    docs: {
      story: {
        height: "130px",
      },
    },
  },
  render: () => html`
    <div style="display: grid; grid-template-columns: repeat(2, max-content); gap: 24px 64px; padding: 8px;">
      <or-vaadin-radio-group
        label="With helper text"
        value="email"
        helper-text="Additional context shown below the options"
      >
        ${radioButtons}
      </or-vaadin-radio-group>
      <or-vaadin-radio-group label="Required" required helper-text="This choice is required">
        ${radioButtons}
      </or-vaadin-radio-group>
    </div>
  `,
};

export const examples = [DisabledReadonly, Orientation, HelperText];

export { customElements, packageJson };

export default meta;
