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
import { css, html } from "lit";
import { customElement, property, query } from "lit/decorators.js";
import "@openremote/or-rules";
import type { OrRules, RulesConfig } from "@openremote/or-rules";
import { NotificationTargetType, RulesetLang, WellknownAssets } from "@openremote/model";
import type { Store } from "@reduxjs/toolkit";
import { Page, type PageProvider } from "@openremote/or-app";
import type { AppStateKeyed } from "@openremote/or-app";
import manager from "@openremote/core";

export interface PageRulesConfig {
  rules: RulesConfig;
}

export function pageRulesProvider(
  store: Store<AppStateKeyed>,
  config: PageRulesConfig = PAGE_RULES_CONFIG_DEFAULT
): PageProvider<AppStateKeyed> {
  return {
    name: "rules",
    routes: ["rules", "rules/:id"],
    pageCreator: () => {
      const page = new PageRules(store);
      page.config = {
        rules: { ...PAGE_RULES_CONFIG_DEFAULT.rules, ...config.rules },
      };
      return page;
    },
  };
}

export const PAGE_RULES_CONFIG_DEFAULT: PageRulesConfig = {
  rules: {
    controls: {
      allowedLanguages: [RulesetLang.JSON, RulesetLang.FLOW, RulesetLang.GROOVY],
      allowedActionTargetTypes: {
        actions: {
          wait: [],
          attribute: [],
          webhook: [],
          email: [NotificationTargetType.USER, NotificationTargetType.CUSTOM],
          email_localized: [NotificationTargetType.USER, NotificationTargetType.CUSTOM],
          push: [NotificationTargetType.USER, NotificationTargetType.ASSET],
          push_localized: [NotificationTargetType.USER, NotificationTargetType.ASSET],
        },
      },
    },
    descriptors: {
      all: {
        excludeAssets: [
          WellknownAssets.TRADFRILIGHTASSET,
          WellknownAssets.TRADFRIPLUGASSET,
          WellknownAssets.ARTNETLIGHTASSET,
        ],
      },
    },
    json: {},
  },
};

@customElement("page-rules")
export class PageRules extends Page<AppStateKeyed> {
  static get styles() {
    // language=CSS
    return css`
      :host {
      }

      or-rules {
        width: 100%;
        height: 100%;
      }
    `;
  }

  @property()
  public config?: PageRulesConfig;

  @query("#rules")
  protected _orRules!: OrRules;

  protected _realmSelector = (state: AppStateKeyed) => state.app.realm || manager.displayRealm;

  protected _realm?: string;

  get name(): string {
    return "rules";
  }

  constructor(store: Store<AppStateKeyed>) {
    super(store);
  }

  protected render() {
    return html`
      <or-rules
        id="rules"
        .config="${this.config && this.config.rules ? this.config.rules : PAGE_RULES_CONFIG_DEFAULT.rules}"
      ></or-rules>
    `;
  }

  public stateChanged(state: AppStateKeyed) {
    const realm = this._realmSelector(state);
    if (realm !== this._realm) {
      this._realm = realm;
      this._orRules?.refresh();
    }
  }
}
