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
import "../src/or-vaadin-radio-card";
import "../src/or-vaadin-radio-group";

const tagName = "or-vaadin-radio-card";
type Story = StoryObj;
setCustomElementsManifest(customElements);

const { events, args, argTypes, template } = getORStorybookHelpers(tagName);

const appearances = [
  { value: "light", title: "Light", description: "Dark text on a light background", swatch: "#ffffff" },
  { value: "dark", title: "Dark", description: "Light text on a dark background", swatch: "#1f211f" },
  {
    value: "system",
    title: "System",
    description: "Follows the setting of the device",
    swatch: "linear-gradient(135deg, #ffffff 50%, #1f211f 50%)",
  },
];

const swatch = (background: string) => html`
  <span
    slot="icon"
    aria-hidden="true"
    style="box-sizing: border-box; width: 40px; height: 40px; border: 1px solid var(--lumo-contrast-20pct); border-radius: var(--lumo-border-radius-m); background: ${background};"
  ></span>
`;

const meta: Meta = {
  title: "Playground/or-vaadin-components/radio-card",
  component: tagName,
  args,
  argTypes,
  render: (storyArgs) => template(storyArgs, swatch(appearances[2].swatch)),
  excludeStories: /^[a-z].*/,
  parameters: {
    actions: {
      handles: events,
    },
    docs: {
      subtitle: `<${tagName}>`,
      description:
        "Radio card is a more mobile-friendly alternative to `<or-vaadin-radio-button>`. It is a radio button shown as a card, with an optional icon before its label. It is meant for `<or-vaadin-radio-group>`, where it can be used next to or instead of `<or-vaadin-radio-button>`, and it supports the same states (checked, disabled) and events (`change`, `checked-changed`). The whole card selects the option, and the colors, sizes and spacing are set through `--or-radio-card-*` properties.",
    },
  },
};

export const Primary: Story = {
  args: {
    label: "System",
    checked: true,
  },
};

/** Cards with the radio at the end. */
export const Reverse: Story = {
  name: "Reverse",
  parameters: {
    title: "Reverse",
    summary: 'Set `theme="reverse"` on a card to show the radio at the end instead of the start.',
    docs: {
      story: {
        height: "260px",
      },
    },
  },
  render: () => html`
    <or-vaadin-radio-group theme="vertical" value="system">
      ${appearances.map(
        (appearance) => html`
          <or-vaadin-radio-card theme="reverse" value=${appearance.value} label=${appearance.title}>
            ${swatch(appearance.swatch)}
          </or-vaadin-radio-card>
        `
      )}
    </or-vaadin-radio-group>
  `,
};

/** Cards with a title and a description, styled once on the group. */
export const TitleAndDescription: Story = {
  name: "Title and description",
  parameters: {
    title: "Title and description",
    summary:
      "The `label` slot takes any content, such as a title and a description. The `--or-radio-card-*` properties inherit, so setting them on the group styles all of its cards.",
    docs: {
      story: {
        height: "280px",
      },
    },
  },
  render: () => html`
    <or-vaadin-radio-group
      label="Appearance"
      theme="vertical"
      value="system"
      style="--or-radio-card-padding: var(--lumo-space-s) var(--lumo-space-m); --or-radio-card-checked-color: var(--lumo-success-text-color); --or-radio-card-checked-background: var(--lumo-success-color-10pct);"
    >
      ${appearances.map(
        (appearance) => html`
          <or-vaadin-radio-card theme="reverse" value=${appearance.value}>
            ${swatch(appearance.swatch)}
            <div slot="label">
              <div style="font-weight: 600;">${appearance.title}</div>
              <div style="color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s);">
                ${appearance.description}
              </div>
            </div>
          </or-vaadin-radio-card>
        `
      )}
    </or-vaadin-radio-group>
  `,
};

export const examples = [Reverse, TitleAndDescription];

export { customElements, packageJson };

export default meta;
