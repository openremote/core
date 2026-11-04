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
import path from "node:path";

import type { Locator } from "@playwright/experimental-ct-core";
import { Shared } from "./shared";

import type { i18n, Resource } from "i18next";
import type { Asset } from "@openremote/model";

declare global {
  interface Window {
    _i18next: i18n;
    _addTranslationFormats: (i18n: i18n) => void;
    _assets: Asset[];
  }
}

type ComponentProps<Component extends HTMLElement> = Partial<Component>;
type ComponentSlot = number | string | ComponentSlot[];
type ComponentSlots = Record<string, ComponentSlot> & { default?: ComponentSlot };

type ComponentEvents = Record<string, (detail: any) => void>;

interface ComponentHooksConfig {
  /**
   * Components used by the mounted markup as slotted/appended children rather than as the test
   * root. Playwright CT only imports the `mount()`ed component, so components listed here are
   * imported (and thereby registered with `customElements.define`) by the `beforeMount` hook in
   * `playwright/index.js` before the test mounts.
   */
  components?: (new (...args: any[]) => HTMLElement)[];
}

export interface MountOptions<HooksConfig, Component extends HTMLElement> {
  props?: ComponentProps<Component>;
  slots?: ComponentSlots;
  on?: ComponentEvents;
  hooksConfig?: HooksConfig & ComponentHooksConfig;
}

export interface MountResult<Component extends HTMLElement> extends Locator {
  unmount(): Promise<void>;
  update(options: {
    props?: Partial<ComponentProps<Component>>;
    slots?: Partial<ComponentSlots>;
    on?: Partial<ComponentEvents>;
  }): Promise<void>;
}

export class CtShared extends Shared {
  /**
   * Allows the test to wait for an event to be fired
   * @returns A promise that waits for the handler to be called
   */
  promiseEventDispatch<T extends CustomEvent>(): [
    promise: Promise<T["detail"]>,
    handler: (detail: T["detail"]) => T["detail"],
  ] {
    let resolver: (value: T | PromiseLike<T>) => void;
    const promise = new Promise<T>((resolve) => (resolver = resolve));
    return [promise, (detail: T["detail"]) => resolver(detail)];
  }

  /**
   * Init shared fonts to be served for material design icons.
   */
  async fonts() {
    await this.page.route("**/shared/fonts/**", async (route, request) => {
      await route.fulfill({ path: this.urlPathToFsPath(request.url()) });
    });
  }

  /**
   * Init shared translations to be served for i18next.
   * @param resources The custom translations to add
   */
  async locales(resources?: Resource) {
    await this.page.route("**/shared/locales/**", async (route, request) => {
      await route.fulfill({ path: this.urlPathToFsPath(request.url()) });
    });
    await this.page.evaluate(async (resources) => {
      await window._i18next.init({
        lng: "en",
        fallbackLng: "en",
        defaultNS: "test",
        fallbackNS: "or",
        ns: ["or"],
        backend: {
          loadPath: "/shared/locales/{{lng}}/{{ns}}.json",
        },
      });
      window._addTranslationFormats(window._i18next);
      if (resources) {
        Object.entries(resources).forEach(([locale, r]) =>
          Object.entries(r).forEach(([ns, translations]) => {
            window._i18next.addResourceBundle(locale, ns, translations);
          })
        );
      }
    }, resources);
  }

  async translate(message: string) {
    return this.page.evaluate(async (m) => window._i18next.t(m), message);
  }

  /**
   * Register assets to the window object to be resolved by components
   * subscribed to the `manager` instance from `@openremote/core`.
   * @param assets The assets to register for component tests.
   */
  async registerAssets(assets: Asset[]) {
    await this.page.evaluate(async (assets) => {
      window._assets = assets;
    }, assets);
  }

  /**
   * Resolves a request URL to a local filesystem path.
   * @param url The incoming request URL to resolve
   */
  private urlPathToFsPath(url: string) {
    return path.resolve(__dirname, decodeURI(`../../app${new URL(url).pathname}`));
  }
}
