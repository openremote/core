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
import manager, { OREvent } from "@openremote/core";
import { type Agent, type AgentDescriptor, AssetModelUtil } from "@openremote/model";
import {
  type JsonFormsStateContext,
  getTemplateWrapper,
  type JsonFormsRendererRegistryEntry,
  type RankedTester,
  rankWith,
  and,
  type ControlProps,
  mapStateToControlProps,
  mapDispatchToControlProps,
  uiTypeIs,
  formatIs,
} from "@openremote/or-json-forms";
import type { SelectItem } from "@openremote/or-vaadin-components/or-vaadin-select";
import type { OrVaadinComboBox } from "@openremote/or-vaadin-components/or-vaadin-combo-box";
import { html } from "lit";
import "@openremote/or-vaadin-components/or-vaadin-combo-box";
import { i18next } from "@openremote/or-translate";
import { until } from "lit/directives/until.js";

/**
 * This function creates a short lived cache for loading the list of agents; this is useful when multiple instances
 * of this control are used in a single UI
 */
let agents: Agent[] | undefined;
let loadingPromise: Promise<Agent[]> | undefined;
let subscribed = false;
const timeout = 2000;

function loadAgents(): PromiseLike<Agent[]> {
  if (agents) {
    return Promise.resolve(agents);
  }

  if (loadingPromise) {
    return loadingPromise;
  }

  if (!subscribed) {
    manager.addListener((ev: OREvent) => {
      switch (ev) {
        case OREvent.DISPLAY_REALM_CHANGED:
          agents = undefined;
          loadingPromise = undefined;
          break;
      }
    });

    manager.events!.subscribeAssetEvents(undefined, false, (assetEvent) => {
      if (assetEvent.asset && assetEvent.asset.type!.endsWith("Agent")) {
        agents = undefined;
        loadingPromise = undefined;
      }
    });

    subscribed = true;
  }

  loadingPromise = manager.rest.api.AssetResource.queryAssets({
    realm: {
      name: manager.displayRealm,
    },
    types: ["Agent"],
    select: {
      attributes: [],
    },
  })
    .then((response) => response.data as Agent[])
    .then((agnts) => {
      agents = agnts;
      return agnts;
    });

  return loadingPromise;
}

const agentIdTester: RankedTester = rankWith(6, and(uiTypeIs("Control"), formatIs("or-agent-id")));
const agentIdRenderer = (state: JsonFormsStateContext, props: ControlProps) => {
  props = {
    ...props,
    ...mapStateToControlProps({ jsonforms: { ...state } }, props),
    ...mapDispatchToControlProps(state.dispatch),
  };

  const onAgentChanged = (agent: Agent | undefined) => {
    if (!agents) {
      return;
    }

    if (agent) {
      const newAgentDescriptor = AssetModelUtil.getAssetDescriptor(agent.type) as AgentDescriptor;
      if (newAgentDescriptor) {
        props.handleChange("", {
          id: agent.id,
          type: newAgentDescriptor.agentLinkType,
        });
      }
    }
  };

  const loadedTemplatePromise = loadAgents().then((agents) => {
    const options: SelectItem[] = agents.map((agent) => ({
      value: agent.id!,
      label: agent.name + " (" + agent.id + ")",
    }));

    // A combo box rather than a select, so the list can be filtered and the agent id copied out of the field
    return html`
      <or-vaadin-combo-box
        required
        class="agent-id-picker"
        @change="${(ev: Event) =>
          onAgentChanged(agents.find((agent) => agent.id === (ev.currentTarget as OrVaadinComboBox).value))}"
        placeholder="${i18next.t("selectAgent")}"
        .items="${options}"
        .value="${props.data ?? ""}"
      >
        <or-translate slot="label" value="agentId"></or-translate>
      </or-vaadin-combo-box>
    `;
  });

  const template = html`
    <style>
      .agent-id-picker {
        min-width: 300px;
        max-width: 600px;
        width: 100%;
      }
    </style>
    ${until(loadedTemplatePromise, html`<or-vaadin-combo-box class="agent-id-picker"></or-vaadin-combo-box>`)}
  `;

  return getTemplateWrapper(template, undefined);
};

export const agentIdRendererRegistryEntry: JsonFormsRendererRegistryEntry = {
  tester: agentIdTester,
  renderer: agentIdRenderer,
};
