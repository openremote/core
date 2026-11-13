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
import type { ConsoleProvider, ConsoleRegistration } from "@openremote/model";
import manager from "./index";
import type { AxiosResponse } from "axios";
import { Deferred } from "./util";

// No ES6 module support in platform lib
const platform = require("platform");

export interface ProviderAction {
  provider: string;
  action: string;
}

export interface ProviderMessage extends ProviderAction {
  data?: any;
  [x: string]: any;
}

interface ProviderInitialiseResponse extends ProviderMessage {
  version: string;
  requiresPermission: boolean;
  hasPermission: boolean;
  success: boolean;
  enabled: boolean;
  disabled: boolean;
}

export interface ProviderEnableRequest extends ProviderMessage {
  consoleId: string;
}

export interface ProviderEnableResponse extends ProviderMessage {
  hasPermission: boolean;
  success: boolean;
}

/**
 * Storage provider is a special case that must always be available and doesn't require init/enable logic
 */
export class Console {
  protected _realm: string;
  protected _registration: ConsoleRegistration;
  protected _autoEnable: boolean = false;
  protected _initialised: boolean = false;
  protected _initialiseInProgress: boolean = false;
  protected _pendingProviderPromises: { [name: string]: [Deferred<any>, number] } = {};
  protected _providerMessageListeners: { [name: string]: (msg: ProviderMessage) => void } = {};
  protected _pendingProviderEnables: string[] = [];
  protected _enableCompleteCallback: (() => void) | null;
  protected _registrationTimer: number | null = null;

  constructor(realm: string, autoEnable: boolean, enableComplete: () => void) {
    this._realm = realm;
    this._autoEnable = autoEnable;
    this._enableCompleteCallback = enableComplete;

    // Export this to the window to make it accessible from mobile webview code
    // @ts-ignore
    window.OpenRemoteConsole = this;

    // Check for query parameters to override values
    const queryParams = new URLSearchParams(window.location.search);
    const consoleName = queryParams.get("consoleName");
    const consoleVersion = queryParams.get("consoleVersion");
    const consolePlatform = queryParams.get("consolePlatform");
    const consoleProviders = queryParams.get("consoleProviders");
    const autoEnableStr = queryParams.get("consoleAutoEnable");

    const requestedProviders = consoleProviders && consoleProviders.length > 0 ? consoleProviders.split(" ") : ["push"];
    this._pendingProviderEnables = requestedProviders;

    // Look for existing console registration in local storage or just create a new one
    const consoleReg: ConsoleRegistration = Console._createConsoleRegistration();

    const consoleRegStr = window.localStorage.getItem(this._storageKey);
    if (consoleRegStr) {
      try {
        const storedRegObj = JSON.parse(consoleRegStr);
        const storedReg = storedRegObj as ConsoleRegistration;
        if (storedReg.id) {
          consoleReg.id = storedReg.id;
        }
        if (storedReg.name) {
          consoleReg.name = storedReg.name;
        }
        if (storedReg.providers) {
          consoleReg.providers = storedReg.providers;
        }
        if (storedReg.apps) {
          consoleReg.apps = storedReg.apps;
        }
      } catch (e) {
        console.error("Failed to deserialize console registration");
      }
    }

    const oldProviders = consoleReg.providers;
    consoleReg.providers = {};

    for (const providerName of requestedProviders) {
      const provider =
        oldProviders && oldProviders.hasOwnProperty(providerName)
          ? oldProviders[providerName]
          : {
              enabled: false,
              disabled: false,
            };
      consoleReg.providers[providerName] = provider;
    }

    const appName = manager.getAppName();
    if (appName.length > 0 && consoleReg.apps!.indexOf(appName) < 0) {
      consoleReg.apps!.push(appName);
    }

    this._registration = consoleReg;

    if (consoleName) {
      consoleReg.name = consoleName;
    }
    if (consoleVersion) {
      consoleReg.version = consoleVersion;
    }
    if (consolePlatform) {
      consoleReg.platform = consolePlatform;
    }

    if (autoEnableStr) {
      this._autoEnable = autoEnableStr === "TRUE" || autoEnableStr === "true";
    }
  }

  get registration(): ConsoleRegistration {
    return this._registration;
  }

  public get autoEnable(): boolean {
    return this._autoEnable;
  }

  get pendingProviderEnables(): string[] {
    return this._pendingProviderEnables.slice(0);
  }

  public get shellApple(): boolean {
    // prettier-ignore
    // @ts-ignore
    const platform = navigator.userAgentData && navigator.userAgentData.platform ? navigator.userAgentData.platform : navigator.platform;
    // prettier-ignore
    // @ts-ignore
    return platform && (platform.substring(0, 2) === 'iP' || platform.substring(0, 3) === 'Mac') && window.webkit && window.webkit.messageHandlers;
  }

  public get shellAndroid(): boolean {
    // @ts-ignore
    return !!window.MobileInterface;
  }

  public get isMobile(): boolean {
    return this.shellApple || this.shellAndroid;
  }

  public async initialise(): Promise<void> {
    if (this._initialised || this._initialiseInProgress) {
      return;
    }

    console.debug("Console: initializing");
    this._initialiseInProgress = true;

    try {
      if (this._registration.providers) {
        for (const providerName of Object.keys(this._registration.providers)) {
          await this._initialiseProvider(providerName);
        }
      }

      // Get an ID for this console if it doesn't have one
      if (!this._registration.id) {
        await this.sendRegistration();
      }

      this._initialised = true;
      this._initialiseInProgress = false;

      if (this._pendingProviderEnables.length === 0) {
        await this.sendRegistration();
        this._callCompletedCallback();
      } else if (this._autoEnable) {
        await this.enableProviders();
      }
    } catch (e) {
      console.error(e);
    } finally {
      this._initialiseInProgress = false;
    }
  }

  // This mechanism doesn't support sending extra data to the providers being enabled and it doesn't allow data
  // to be retrieved from the replies; if that is required then providers should be manually enabled from the calling app
  public async enableProviders(): Promise<void> {
    if (!this._initialised) {
      throw new Error("Console must be initialized before enabling providers");
    }

    for (let index = this._pendingProviderEnables.length - 1; index > -1; index--) {
      const providerName = this._pendingProviderEnables[index];
      await this.enableProvider(providerName);
    }
  }

  public async enableProvider(providerName: string, data?: any): Promise<ProviderEnableResponse> {
    if (!this._initialised) {
      console.debug("Console must be initialized before disabling providers");
      throw new Error("Console must be initialized before enabling providers");
    }

    if (!this._registration.providers!.hasOwnProperty(providerName)) {
      console.debug("Invalid console provider '" + providerName + "'");
      throw new Error("Invalid console provider '" + providerName + "'");
    }

    console.debug("Console: enabling provider '" + providerName + "'");
    const msg: ProviderEnableRequest = {
      provider: providerName,
      action: "PROVIDER_ENABLE",
      consoleId: this._registration.id!,
      data,
    };
    const response = await this.sendProviderMessage(msg, true);

    this._registration.providers![providerName].hasPermission = response.hasPermission;
    this._registration.providers![providerName].success = response.success;
    this._registration.providers![providerName].enabled = response.success;
    this._registration.providers![providerName].data = response.data;

    const index = this._pendingProviderEnables.indexOf(providerName);

    if (index >= 0) {
      this._pendingProviderEnables.splice(index, 1);
      if (this._pendingProviderEnables.length === 0) {
        this.sendRegistration().catch(() => undefined);
        this._callCompletedCallback();
      }
    }

    return response;
  }

  public async disableProvider(provider: string): Promise<ProviderMessage> {
    if (!this._initialised) {
      console.debug("Console must be initialized before disabling providers");
      throw new Error("Console must be initialized before disabling providers");
    }

    if (!this._registration.providers!.hasOwnProperty(provider)) {
      console.debug("Invalid console provider '" + provider + "'");
      throw new Error("Invalid console provider '" + provider + "'");
    }

    console.debug("Console: disabling provider '" + provider + "'");

    const response = await this.sendProviderMessage(
      {
        provider,
        action: "PROVIDER_DISABLE",
      },
      true
    );
    this._registration.providers![provider].disabled = true;
    this._registration.providers![provider].enabled = false;
    return response;
  }

  public getProvider(name: string): ConsoleProvider | undefined {
    return this._registration && this._registration.providers ? this._registration.providers[name] : undefined;
  }

  public async sendProviderMessage(message: ProviderMessage, waitForResponse: boolean): Promise<any | null> {
    if (message.provider !== "storage" && !this._registration.providers!.hasOwnProperty(message.provider)) {
      console.debug("Invalid console provider '" + message.provider + "'");
      throw new Error("Invalid console provider '" + message.provider + "'");
    }

    if (!waitForResponse) {
      this._doSendProviderMessage(message);
      return;
    }

    const promiseName = message.provider + message.action;

    if (this._pendingProviderPromises[promiseName]) {
      throw new Error(
        "Message already pending for provider '" + message.provider + "' with action '" + message.action + "'"
      );
    }

    const deferred = new Deferred();
    const cancel = () => {
      delete this._pendingProviderPromises[promiseName];
      deferred.reject("No response from provider");
    };
    this._pendingProviderPromises[promiseName] = [deferred, window.setTimeout(cancel, 5000)];
    this._doSendProviderMessage(message);
    return deferred.promise;
  }

  public sendRegistration(): Promise<void> {
    if (this._registrationTimer) {
      window.clearTimeout(this._registrationTimer);
      this._registrationTimer = null;
    }

    return new Promise((resolve, reject) => {
      this._registrationTimer = window.setTimeout(async () => {
        this._registrationTimer = null;
        console.debug("Console: updating registration");

        try {
          await this._sendRegistration(true);
          resolve();
        } catch (e) {
          console.error("Failed to register console", e);
          reject(e);
        }
      });
    });
  }

  protected async _sendRegistration(retryOnStaleRegistration: boolean): Promise<void> {
    this._normaliseRegistration();

    const hadRegistrationId = !!this._registration.id;

    try {
      const response: AxiosResponse<ConsoleRegistration> = await manager.rest.api.ConsoleResource.register(
        this._registration
      );
      if (response.status !== 200) {
        throw new Error("Failed to register console");
      }

      this._registration = response.data;
      console.debug("Console: registration successful");
      console.debug("Console: updating locally stored registration");
      window.localStorage.setItem(this._storageKey, JSON.stringify(this._registration));
    } catch (e) {
      if (retryOnStaleRegistration && hadRegistrationId && Console._isStaleRegistrationResponse(e)) {
        console.warn("Console registration ID is stale or cannot be claimed; creating a new console registration");
        window.localStorage.removeItem(this._storageKey);
        delete this._registration.id;
        await this._sendRegistration(false);
        return;
      }
      throw e;
    }
  }

  protected _normaliseRegistration() {
    // Ensure console name, platform, version and providers are not null
    if (!this._registration.name) {
      this._registration.name = "Console";
    }
    if (!this._registration.platform) {
      this._registration.platform = "N/A";
    }
    if (!this._registration.version) {
      this._registration.version = "N/A";
    }
    if (!this._registration.providers) {
      this._registration.providers = {};
    }
  }

  protected get _storageKey(): string {
    return "OpenRemoteConsole:" + this._realm;
  }

  protected static _isStaleRegistrationResponse(e: any): boolean {
    const status = e && e.response ? e.response.status : undefined;
    return status === 403 || status === 409;
  }

  public storeData(key: string, value: any) {
    this.sendProviderMessage(
      {
        provider: "storage",
        action: "STORE",
        key,
        value,
      },
      false
    );
  }

  public async retrieveData<T>(key: string): Promise<T | undefined> {
    const responsePromise = this.sendProviderMessage(
      {
        provider: "storage",
        action: "RETRIEVE",
        key,
      },
      true
    );

    // This is here to deal with lack of response from storage provider
    // Storage provider should respond quickly
    try {
      const response = await Promise.race([
        responsePromise,
        new Promise<T>((resolve, reject) => setTimeout(reject, 2000)),
      ]);
      if (response && response.value) {
        const value = response.value as T;
        return value === "null" ? undefined : value;
      }
    } catch (e) {
      console.log("Failed to retrieve data from storage provider");
    }
    return undefined;
  }

  public addProviderMessageListener(providerAction: ProviderAction, listener: (msg: ProviderMessage) => void) {
    this._providerMessageListeners[providerAction.provider + providerAction.action] = listener;
  }

  public removeProviderMessageListener(providerAction: ProviderAction) {
    delete this._providerMessageListeners[providerAction.provider + providerAction.action];
  }

  protected _postNativeShellMessage(jsonMessage: any) {
    try {
      if (this.shellAndroid) {
        // @ts-ignore
        return window.MobileInterface.postMessage(JSON.stringify(jsonMessage));
      }
      if (this.shellApple) {
        // @ts-ignore
        return window.webkit.messageHandlers.int.postMessage(jsonMessage);
      }
    } catch (e) {
      console.error("Failed to send shell message towards console", e);
    }
  }

  /**
   * Function that allows sending of custom types and messages towards the console.
   * TODO: Will be improved in the future, see this GitHub issue; https://github.com/openremote/core/issues/1318
   */
  public _doSendGenericMessage(type: string, msg: any) {
    const payload = { type, data: msg };
    if (this.isMobile) {
      this._postNativeShellMessage(payload);
    } else {
      console.warn("Failed to send generic message to console.", payload);
    }
  }

  public _doSendProviderMessage(msg: any) {
    if (this.isMobile) {
      this._postNativeShellMessage({ type: "provider", data: msg });
    } else {
      if (!msg.provider || !msg.action) {
        return;
      }
      switch (msg.provider.trim().toUpperCase()) {
        // TODO: Implement web browser provider handling (web push etc.)
        case "PUSH":
          switch (msg.action.trim().toUpperCase()) {
            case "PROVIDER_INIT":
              const initResponse: ProviderInitialiseResponse = {
                action: "PROVIDER_INIT",
                provider: "push",
                version: "web",
                enabled: true,
                disabled: false,
                hasPermission: true,
                requiresPermission: false,
                success: true,
              };
              this._handleProviderResponse(JSON.stringify(initResponse));
              break;
            case "PROVIDER_ENABLE":
              const enableResponse: ProviderEnableResponse = {
                action: "PROVIDER_ENABLE",
                provider: "push",
                hasPermission: true,
                success: true,
              };
              this._handleProviderResponse(JSON.stringify(enableResponse));
              break;
            default:
              throw new Error("Unsupported provider '" + msg.provider + "' and action '" + msg.action + "'");
          }

          break;

        case "STORAGE":
          switch (msg.action) {
            case "PROVIDER_INIT":
              const initResponse: ProviderInitialiseResponse = {
                action: "PROVIDER_INIT",
                provider: "storage",
                version: "1.0.0",
                disabled: false,
                enabled: true,
                hasPermission: true,
                requiresPermission: false,
                success: true,
              };
              this._handleProviderResponse(JSON.stringify(initResponse));
              break;
            case "PROVIDER_ENABLE":
              const enableResponse: ProviderEnableResponse = {
                action: "PROVIDER_ENABLE",
                provider: "storage",
                hasPermission: true,
                success: true,
              };
              this._handleProviderResponse(JSON.stringify(enableResponse));
              break;
            case "STORE":
              {
                const keyValue = msg.key ? msg.key.trim() : null;

                if (!keyValue || keyValue.length === 0) {
                  throw new Error("Storage provider 'store' action requires a `key`");
                }

                if (msg.value === null) {
                  window.localStorage.removeItem(keyValue);
                } else {
                  window.localStorage.setItem(keyValue, JSON.stringify(msg.value));
                }
              }
              break;
            case "RETRIEVE":
              {
                const keyValue = msg.key ? msg.key.trim() : null;

                if (!keyValue || keyValue.length === 0) {
                  throw new Error("Storage provider 'retrieve' action requires a `key`");
                }

                let val = window.localStorage.getItem(keyValue);
                if (val !== null) {
                  try {
                    val = JSON.parse(val);
                  } catch (e) {
                    // Fallback to just returning the val as it might not be JSON
                  }
                }

                this._handleProviderResponse(
                  JSON.stringify({
                    action: "RETRIEVE",
                    provider: "storage",
                    key: keyValue,
                    value: val,
                  })
                );
              }
              break;
            default:
              throw new Error("Unsupported provider '" + msg.provider + "' and action '" + msg.action + "'");
          }
          break;
        default:
          // Just log that this provider is unsupported in a normal browser
          console.error("Unsupported provider: " + msg.provider);
      }
    }
  }

  // This is called by native web view code
  protected _handleProviderResponse(msg: any) {
    if (!msg) {
      return;
    }

    const msgJson = JSON.parse(msg);
    const name = msgJson.provider;
    const action = msgJson.action;

    const deferredAndTimeout = this._pendingProviderPromises[name + action];

    if (deferredAndTimeout) {
      window.clearTimeout(deferredAndTimeout[1]);
      delete this._pendingProviderPromises[name + action];
      deferredAndTimeout[0].resolve(msgJson);
    }

    const listener = this._providerMessageListeners[name + action];
    if (listener) {
      listener(msgJson);
    }
  }

  protected _callCompletedCallback() {
    const callback = this._enableCompleteCallback;
    this._enableCompleteCallback = null;
    if (callback) {
      window.setTimeout(() => {
        callback!();
      }, 0);
    }
  }

  protected static _createConsoleRegistration(): ConsoleRegistration {
    const reg: ConsoleRegistration = {
      name: platform.name,
      version: platform.version,
      platform: platform.os.toString(),
      apps: [],
      model: (
        (platform.manufacturer ? platform.manufacturer + " " : "") + (platform.product ? platform.product : "")
      ).trim(),
    };

    return reg;
  }

  protected async _initialiseProvider(providerName: string): Promise<void> {
    console.debug("Console: initializing provider '" + providerName + "'");
    let initResponse: ProviderInitialiseResponse;

    try {
      initResponse = (await this.sendProviderMessage(
        {
          provider: providerName,
          action: "PROVIDER_INIT",
        },
        true
      )) as ProviderInitialiseResponse;

      this._registration.providers![providerName].version = initResponse.version;
      this._registration.providers![providerName].requiresPermission = initResponse.requiresPermission;
      this._registration.providers![providerName].hasPermission = initResponse.hasPermission;
      this._registration.providers![providerName].success = initResponse.success;
      this._registration.providers![providerName].enabled = initResponse.enabled;
      this._registration.providers![providerName].disabled = initResponse.disabled;
      this._registration.providers![providerName].data = initResponse.data;
    } catch (e) {
      console.error(e);
      initResponse = {
        action: "",
        disabled: false,
        enabled: false,
        hasPermission: false,
        provider: "",
        requiresPermission: false,
        version: "",
        success: false,
      };
    }

    if (!initResponse.success) {
      console.debug("Provider initialization failed: '" + providerName + "'");
      initResponse.disabled = true;
      this._registration.providers![providerName].disabled = true;
    }

    if (initResponse.disabled || initResponse.enabled) {
      const index = this._pendingProviderEnables.indexOf(providerName);
      if (index >= 0) {
        this._pendingProviderEnables.splice(index, 1);
      }
    }
  }
}
