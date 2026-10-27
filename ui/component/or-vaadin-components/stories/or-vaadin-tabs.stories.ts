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
import { styleMap } from "lit/directives/style-map.js";
import "../src/or-vaadin-tabs";
import type { OrVaadinTabs } from "../src/or-vaadin-tabs";

const tagName = "or-vaadin-tabs";
type Story = StoryObj;
setCustomElementsManifest(customElements);

const { events, args, argTypes, template } = getORStorybookHelpers(tagName);

const pages = [
  { name: "Status", content: "Everything is running normally. The last reading came in a minute ago." },
  { name: "Mode", content: "The asset runs automatically and follows its schedule." },
  { name: "Settings", content: "Change the name, location and schedule of the asset." },
];

/** Tabs for the pages, without content. */
const plainTabs = () => pages.map(({ name }) => html`<vaadin-tab>${name}</vaadin-tab>`);

/** Selects the tab in every bar of the `data-tab-group` and shows its page in the group's `<p>`. */
const showContent = (event: CustomEvent<{ value: number }>) => {
  const group = (event.target as Element).closest("[data-tab-group]");
  group?.querySelectorAll<OrVaadinTabs>("or-vaadin-tabs").forEach((tabs) => (tabs.selected = event.detail.value));
  const content = group?.querySelector("p");
  if (content) {
    content.textContent = pages[event.detail.value]?.content ?? "";
  }
};

/** An example with a caption naming what it sets. */
const captioned = (caption: string, example: unknown) => html`
  <figure style="margin: 0;">
    <figcaption
      style="margin-bottom: var(--lumo-space-s); color: var(--lumo-secondary-text-color); font-size: var(--lumo-font-size-s);"
    >
      ${caption}
    </figcaption>
    ${example}
  </figure>
`;

const meta: Meta = {
  title: "Playground/or-vaadin-components/tabs",
  component: tagName,
  args,
  argTypes,
  render: (storyArgs) => template(storyArgs, html`${plainTabs()}`),
  excludeStories: /^[a-z].*/,
  parameters: {
    actions: {
      handles: events,
    },
    docs: {
      subtitle: `<${tagName}>`,
      description:
        'Tabs switch between views or pages. They have the Lumo look of `<vaadin-tabs>` and work the same, so see the [Vaadin Tabs docs](https://vaadin.com/docs/latest/components/tabs) for their usage and [style variants](https://vaadin.com/docs/latest/components/tabs/styling). Set `theme="sliding"` for a more mobile-friendly bar with a contrasting background, where the `<vaadin-tab>` elements share the width equally and an indicator slides behind the selected one. Its colors, sizes, spacing, rounding and timing are set through `--or-tabs-*` and `--or-tab-*` properties.',
    },
  },
};

export const Primary: Story = {
  args: {
    selected: 0,
  },
};

/** The sliding variant, horizontally and vertically. */
export const Sliding: Story = {
  name: "Sliding",
  parameters: {
    title: "Sliding",
    summary:
      'The sliding variant is a more mobile-friendly alternative to the default tabs, for switching between a few views on a phone. Set `theme="sliding"` for a bar with a contrasting background, where the tabs share its width as large touch targets and an indicator slides behind the selected one. With `orientation="vertical"` the indicator slides vertically. The two bars here share one set of content, as selecting a tab in one sets `selected` on the other from its `selected-changed` event.',
    docs: {
      story: {
        height: "260px",
      },
    },
  },
  render: () => html`
    <div data-tab-group style="display: flex; flex-direction: column; gap: var(--lumo-space-m);">
      <or-vaadin-tabs theme="sliding" selected="0" @selected-changed=${showContent}>${plainTabs()}</or-vaadin-tabs>
      <div style="display: flex; align-items: start; gap: var(--lumo-space-l);">
        <or-vaadin-tabs theme="sliding" orientation="vertical" selected="0" @selected-changed=${showContent}>
          ${plainTabs()}
        </or-vaadin-tabs>
        <p>${pages[0].content}</p>
      </div>
    </div>
  `,
};

const roundings = [
  { caption: "0px", radius: "0px" },
  { caption: "3px", radius: "3px" },
  { caption: "Default, the Lumo medium radius", radius: undefined },
  { caption: "12px", radius: "12px" },
  { caption: "99px, a full pill", radius: "99px" },
];

/** The sliding variant with a range of `--or-tabs-radius` values. */
export const Rounding: Story = {
  name: "Rounding",
  parameters: {
    title: "Rounding",
    summary:
      "`--or-tabs-radius` rounds the tabs and the indicator, up to a full pill, and the corners of the bar follow it around the padding. The rounding is the same in either `orientation`.",
    docs: {
      story: {
        height: "760px",
      },
    },
  },
  render: () => html`
    <div style="display: flex; flex-direction: column; gap: var(--lumo-space-l);">
      ${roundings.map(({ caption, radius }) =>
        captioned(
          caption,
          html`<or-vaadin-tabs theme="sliding" selected="1" style=${styleMap({ "--or-tabs-radius": radius })}
            >${plainTabs()}</or-vaadin-tabs
          >`
        )
      )}
      <div style="display: flex; flex-wrap: wrap; gap: var(--lumo-space-l);">
        ${roundings.map(({ caption, radius }) =>
          captioned(
            caption,
            html`<or-vaadin-tabs
              theme="sliding"
              orientation="vertical"
              selected="1"
              style=${styleMap({ "--or-tabs-radius": radius })}
              >${plainTabs()}</or-vaadin-tabs
            >`
          )
        )}
      </div>
    </div>
  `,
};

export const examples = [Sliding, Rounding];

export { customElements, packageJson };

export default meta;
