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
import "url-search-params-polyfill";
import { Console } from "./console";
import rest from "@openremote/rest";
import type { InternalAxiosRequestConfig } from "axios";
import { type EventProvider, type EventProviderFactory, EventProviderStatus, WebSocketEventProvider } from "./event";
import i18next, { type i18n, type InitOptions } from "i18next";
import i18nextBackend from "i18next-http-backend";
import moment from "moment";
import { AssetModelUtil, Auth, type ConsoleAppConfig, EventProviderType, type ManagerConfig } from "@openremote/model";
import * as Util from "./util";
import { createMdiIconSet, createSvgIconSet, IconSets, OrIconSet } from "@openremote/or-icon";
import Keycloak from "keycloak-js";

// Re-exports
export { Util };
export * from "./asset-mixin";
export * from "./console";
export * from "./event";
export * from "./defaults";

export const DEFAULT_ICONSET = "mdi";
export const OPENREMOTE_CLIENT_ID = "openremote";
export const RESTRICTED_USER_REALM_ROLE = "restricted_user";

export declare type KeycloakPromise<T> = {
  success<TResult1 = T, TResult2 = never>(
    onfulfilled?: ((value: T) => TResult1 | KeycloakPromise<TResult1>) | undefined | null,
    onrejected?: ((reason: any) => TResult2 | KeycloakPromise<TResult2>) | undefined | null
  ): KeycloakPromise<TResult1 | TResult2>;
  error<TResult = never>(
    onrejected?: ((reason: any) => TResult | KeycloakPromise<TResult>) | undefined | null
  ): Promise<T | TResult>;
};

export enum ORError {
  MANAGER_FAILED_TO_LOAD = "MANAGER_FAILED_TO_LOAD",
  AUTH_FAILED = "AUTH_FAILED",
  AUTH_TYPE_UNSUPPORTED = "AUTH_TYPE_UNSUPPORTED",
  CONSOLE_ERROR = "CONSOLE_INIT_ERROR",
  EVENTS_CONNECTION_ERROR = "EVENTS_CONNECTION_ERROR",
  TRANSLATION_ERROR = "TRANSLATION_ERROR",
}

export enum OREvent {
  ERROR = "ERROR",
  READY = "READY",
  ONLINE = "ONLINE",
  OFFLINE = "OFFLINE",
  CONNECTING = "CONNECTING",
  CONSOLE_INIT = "CONSOLE_INIT",
  CONSOLE_READY = "CONSOLE_READY",
  TRANSLATE_INIT = "TRANSLATE_INIT",
  TRANSLATE_LANGUAGE_CHANGED = "TRANSLATE_LANGUAGE_CHANGED",
  DISPLAY_REALM_CHANGED = "DISPLAY_REALM_CHANGED",
  LOGIN = "LOGIN",
}

export interface LoginOptions {
  redirectUrl?: string;
  action?: string;
}

export interface Languages {
  [langKey: string]: string;
}

export const DEFAULT_LANGUAGES: Languages = {
  en: "english",
  cn: "chinese",
  cs: "czech",
  nl: "dutch",
  fr: "french",
  de: "german",
  it: "italian",
  pl: "polish",
  pt: "portuguese",
  ro: "romanian",
  es: "spanish",
  uk: "ukrainian",
};

// Maps i18next language codes to Moment locale codes where they differ.
// Add a new entry here when adding a language whose i18next code != Moment locale code.
export const I18NEXT_TO_MOMENT_LOCALE: Record<string, string> = {
  cn: "zh-cn",
};

/**
 * Registers the formats the translations interpolate with on an initialised i18next instance.
 * i18next lowercases a format name, so the Moment formats that only differ in case take an option.
 */
export function addTranslationFormats(i18n: i18n): void {
  i18n.services.formatter!.add("uppercase", (value: unknown) =>
    typeof value === "string" ? value.toUpperCase() : String(value)
  );
  i18n.services.formatter!.add("lll", (value, lng: string | undefined) =>
    moment(value)
      .locale(I18NEXT_TO_MOMENT_LOCALE[lng!] ?? lng!)
      .format("lll")
  );
  i18n.services.formatter!.add("llll", (value, lng: string | undefined, options: { weekday?: string }) =>
    moment(value)
      .locale(I18NEXT_TO_MOMENT_LOCALE[lng!] ?? lng!)
      .format(options?.weekday === "long" ? "LLLL" : "llll")
  );
}

export function normaliseConfig(config: ManagerConfig): ManagerConfig {
  const normalisedConfig: ManagerConfig = config ? Object.assign({}, config) : {};

  if (!normalisedConfig.managerUrl || normalisedConfig.managerUrl === "") {
    // Assume manager is running on same host as this code
    normalisedConfig.managerUrl =
      window.location.protocol +
      "//" +
      window.location.hostname +
      (window.location.port ? ":" + window.location.port : "") +
      window.location.pathname.replace(/\/[^/]+\/?$/, "");
  } else {
    // Normalise by stripping any trailing slashes
    normalisedConfig.managerUrl = normalisedConfig.managerUrl.replace(/\/+$/, "");
  }

  if (!normalisedConfig.realm || normalisedConfig.realm === "") {
    // Assume master realm
    normalisedConfig.realm = "master";
  }

  if (!normalisedConfig.auth) {
    normalisedConfig.auth = Auth.KEYCLOAK;
  }

  if (normalisedConfig.consoleAutoEnable === undefined) {
    normalisedConfig.consoleAutoEnable = true;
  }

  if (normalisedConfig.applyConfigToAdmin === undefined) {
    normalisedConfig.applyConfigToAdmin = true;
  }

  if (!normalisedConfig.eventProviderType) {
    normalisedConfig.eventProviderType = EventProviderType.WEBSOCKET;
  }

  if (!normalisedConfig.pollingIntervalMillis || normalisedConfig.pollingIntervalMillis < 5000) {
    normalisedConfig.pollingIntervalMillis = 10000;
  }

  if (normalisedConfig.loadIcons === undefined) {
    normalisedConfig.loadIcons = true;
  }

  if (normalisedConfig.loadTranslations === undefined) {
    normalisedConfig.loadTranslations = ["or"];
  }

  if (normalisedConfig.translationsLoadPath === undefined) {
    normalisedConfig.translationsLoadPath = "locales/{{lng}}/{{ns}}.json";
  }

  if (normalisedConfig.loadDescriptors === undefined) {
    normalisedConfig.loadDescriptors = true;
  }

  if (normalisedConfig.clientId === undefined) {
    normalisedConfig.clientId = OPENREMOTE_CLIENT_ID;
  }

  return normalisedConfig;
}

export interface OrManagerEventDetail {
  event: OREvent;
  error?: ORError;
}

export type EventCallback = (event: OREvent) => void;

export class Manager implements EventProviderFactory {
  get username() {
    return this._username;
  }

  get error() {
    return this._error;
  }

  get authenticated() {
    return this._authenticated;
  }

  get ready() {
    return this._ready;
  }

  get config() {
    return this._config;
  }

  get roles(): Map<string, string[]> {
    const roleMap = new Map<string, string[]>();

    if (this._keycloak) {
      if (this._keycloak.resourceAccess) {
        if (this._config.clientId && this._keycloak!.resourceAccess) {
          Object.entries(this._keycloak!.resourceAccess).forEach(([client, resourceObj]) => {
            const roles = (resourceObj as any).roles as string[];
            roleMap.set(client, roles);
          });
        }
      }
    }

    return roleMap;
  }

  get managerVersion() {
    return this._managerVersion;
  }

  get isManagerAvailable() {
    return this._managerVersion && this._managerVersion !== "";
  }

  get managerUrl() {
    return this._config?.managerUrl;
  }

  get keycloakUrl() {
    return this._config?.keycloakUrl;
  }

  get isError() {
    return !!this._error;
  }

  get connectionStatus() {
    return this._events && this._events.status;
  }

  get console() {
    return this._console;
  }

  get consoleAppConfig() {
    return this._consoleAppConfig;
  }

  get events() {
    return this._events;
  }

  get rest() {
    return rest;
  }

  get language() {
    return i18next.language;
  }

  set language(lang: string) {
    console.debug(`Changing language to ${lang}.`);
    if (lang) {
      i18next.changeLanguage(lang);
      this.console.storeData("LANGUAGE", lang);
      if (this.authenticated) {
        this.updateKeycloakUserLanguage(lang).catch((e) => console.error(e));
      }
    }
  }

  get displayRealm() {
    return this._displayRealm || this._config.realm!;
  }

  set displayRealm(realm: string) {
    if (!this.isSuperUser() || this._displayRealm === realm) {
      return;
    }
    this._displayRealm = realm;
    this._emitEvent(OREvent.DISPLAY_REALM_CHANGED);
  }

  get clientId() {
    return this._config.clientId || OPENREMOTE_CLIENT_ID;
  }

  getEventProvider(): EventProvider | undefined {
    return this.events;
  }

  protected static MAX_RECONNECT_DELAY = 45000;
  private _error?: ORError;
  private _config!: ManagerConfig;
  private _authenticated: boolean = false;
  private _ready: boolean = false;
  private _name: string = "";
  private _username: string = "";
  private _keycloak?: Keycloak;
  private _keycloakUpdateTokenInterval?: number = undefined;
  private _managerVersion: string = "";
  public _authServerUrl: string = "";
  private _listeners: EventCallback[] = [];
  private _console!: Console;
  private _consoleAppConfig?: ConsoleAppConfig;
  private _events?: EventProvider;
  private _disconnected: boolean = false;
  private _reconnectTimer?: number;
  private _displayRealm?: string;

  public isManagerSameOrigin(): boolean {
    if (!this.ready) {
      return false;
    }

    const managerUrl = new URL(this._config.managerUrl!);
    const windowUrl = window.location;
    return (
      managerUrl.protocol === windowUrl.protocol &&
      managerUrl.hostname === windowUrl.hostname &&
      managerUrl.port === windowUrl.port
    );
  }

  public addListener(callback: EventCallback) {
    const index = this._listeners.indexOf(callback);
    if (index < 0) {
      this._listeners.push(callback);
    }
  }

  public removeListener(callback: EventCallback) {
    const index = this._listeners.indexOf(callback);
    if (index >= 0) {
      this._listeners.splice(index, 1);
    }
  }

  public async init(config: ManagerConfig): Promise<boolean> {
    if (this._config) {
      console.debug("Already initialised");
    }

    this._config = normaliseConfig(config);

    let success = await this.loadManagerInfo();

    // Create console as we need to access storage during auth
    const orConsole = new Console(this._config.realm!, this._config.consoleAutoEnable!, () => {
      this._emitEvent(OREvent.CONSOLE_READY);
    });
    this._console = orConsole;

    if (this._config.auth === Auth.KEYCLOAK) {
      // The info endpoint of the manager might return a relative URL (relative to the manager)
      if (!this._config.keycloakUrl && this._authServerUrl) {
        const managerURL = new URL(this._config.managerUrl!);
        let authServerURL: URL;

        if (this._authServerUrl.startsWith("//")) {
          this._authServerUrl = managerURL.protocol + this._authServerUrl;
          this._config.keycloakUrl = this._authServerUrl;
        } else if (this._authServerUrl.startsWith("http")) {
          this._config.keycloakUrl = this._authServerUrl;
        } else {
          // Assume a relative URL
          authServerURL = new URL(managerURL);
          authServerURL.pathname = this._authServerUrl;

          // Use manager URL info
          if (!authServerURL.protocol) {
            authServerURL.protocol = managerURL.protocol;
          }
          if (!authServerURL.hostname) {
            authServerURL.hostname = managerURL.hostname;
          }
          if (!authServerURL.port) {
            authServerURL.port = managerURL.port;
          }

          this._config.keycloakUrl = authServerURL.toString();
        }
      }

      // If we still don't know auth server URL then use manager URL
      if (!this._config.keycloakUrl) {
        this._config.keycloakUrl = this._config.managerUrl + "/auth";
      }

      // Normalise by stripping any trailing slashes
      this._config.keycloakUrl = this._config.keycloakUrl.replace(/\/+$/, "");

      success = await this.doAuthInit();
    }

    if (!success) {
      // Either loadManagerInfo() or doAuthInit() failed, aborting manager initialization
      return false;
    }

    if (success) {
      success = this.doRestApiInit();
    }

    // Don't let console registration error prevent loading
    const consoleSuccess = await this.doConsoleInit();
    if (consoleSuccess) {
      // Send the console a message to clear the web history, so no pages outside the app can be accessed.
      // For example, this prevents navigating back to an authentication screen.
      this._clearWebHistory();
    }

    success = (await this.doTranslateInit()) && success;

    if (success) {
      success = await this.doDescriptorsInit();
      success = await this.getConsoleAppConfig();
    }

    this.doIconInit();

    if (success) {
      success = await this.doEventsSubscriptionInit();

      this._ready = true;
      this._emitEvent(OREvent.READY);
    } else {
      (this._config as any) = undefined;
      console.warn("Failed to initialise the manager");
      if (!this.isError) {
        // If no specific error got set during initialization, fall back to MANAGER_FAILED_TO_LOAD
        this._setError(ORError.MANAGER_FAILED_TO_LOAD);
      }
    }

    this.displayRealm = config.realm || "master";

    return success;
  }

  protected async loadManagerInfo(): Promise<boolean> {
    // Check manager exists by calling the info endpoint
    try {
      const json = await new Promise<any>((resolve, reject) => {
        const oReq = new XMLHttpRequest();
        oReq.addEventListener("load", () => {
          resolve(JSON.parse(oReq.responseText));
        });
        oReq.addEventListener("error", () => {
          reject(new Error("Failed to contact the manager"));
        });
        oReq.open("GET", this._config.managerUrl + "/api/master/info");
        oReq.send();
      });
      this._managerVersion = json && json.version ? json.version : "";
      this._authServerUrl = json && json.authServerUrl ? json.authServerUrl : "";

      return true;
    } catch (e) {
      // TODO: Implement auto retry?
      console.error("Failed to contact the manager", e);
      this._setError(ORError.MANAGER_FAILED_TO_LOAD);
      return false;
    }
  }

  protected async doTranslateInit(): Promise<boolean> {
    if (i18next.isInitialized) {
      return true;
    }

    i18next.on("initialized", (options) => {
      this._emitEvent(OREvent.TRANSLATE_INIT);
    });

    i18next.on("languageChanged", (lng) => {
      moment.locale(I18NEXT_TO_MOMENT_LOCALE[lng] ?? lng);
      this._emitEvent(OREvent.TRANSLATE_LANGUAGE_CHANGED);
    });

    // Look for language preference in local storage
    const initOptions: InitOptions = {
      lng:
        (await this.getConsolePreferredLanguage()) ||
        (await this.getUserPreferredLanguage()) ||
        this.config.defaultLanguage ||
        "en",
      fallbackLng: "en",
      defaultNS: "app",
      fallbackNS: "or",
      ns: this.config.loadTranslations,
      backend: {
        loadPath: (langs: string[], namespaces: string[]) => {
          if (namespaces.length === 1 && namespaces[0] === "or") {
            return this.config.managerUrl + "/shared/locales/{{lng}}/{{ns}}.json";
          }

          if (this.config.translationsLoadPath) {
            return this.config.translationsLoadPath;
          }

          return "locales/{{lng}}/{{ns}}.json";
        },
      },
    };

    if (this.config.configureTranslationsOptions) {
      this.config.configureTranslationsOptions(initOptions);
    }

    try {
      await i18next.use(i18nextBackend).init(initOptions);
      addTranslationFormats(i18next);
    } catch (e) {
      console.error(e);
      this._setError(ORError.TRANSLATION_ERROR);
      return false;
    }

    return true;
  }

  protected async doDescriptorsInit(): Promise<boolean> {
    if (!this.config.loadDescriptors) {
      return true;
    }

    try {
      const assetInfosResponse = await rest.api.AssetModelResource.getAssetInfos();
      const metaItemDescriptorResponse = await rest.api.AssetModelResource.getMetaItemDescriptors();
      const valueDescriptorResponse = await rest.api.AssetModelResource.getValueDescriptors();

      AssetModelUtil._assetTypeInfos = assetInfosResponse.data;
      AssetModelUtil._metaItemDescriptors = Object.values(metaItemDescriptorResponse.data);
      AssetModelUtil._valueDescriptors = Object.values(valueDescriptorResponse.data);
    } catch (e) {
      console.error(e);
      return false;
    }
    return true;
  }

  protected async doAuthInit(): Promise<boolean> {
    let success = true;
    switch (this._config.auth) {
      case Auth.KEYCLOAK:
        success = await this.loadAndInitialiseKeycloak();
        break;
      case Auth.NONE:
        // Nothing for us to do here
        return true;
      default:
        this._setError(ORError.AUTH_TYPE_UNSUPPORTED);
        return false;
    }

    if (!success) {
      return false;
    }

    // Add interceptor to inject authorization header on each request
    rest.addRequestInterceptor((config: InternalAxiosRequestConfig) => {
      if (!config!.headers!.Authorization) {
        const authHeader = this.getAuthorizationHeader();

        if (authHeader) {
          config!.headers!.Authorization = authHeader;
        }
      }

      return config;
    });
    return success;
  }

  protected doRestApiInit(): boolean {
    rest.setTimeout(20000);
    rest.initialise(this.getApiBaseUrl());
    return true;
  }

  protected async doEventsSubscriptionInit(): Promise<boolean> {
    let connected = false;

    switch (this._config.eventProviderType) {
      case EventProviderType.WEBSOCKET:
        this._events = new WebSocketEventProvider(this._config.managerUrl!);
        this._events.subscribeStatusChange((status: EventProviderStatus) => this._onEventProviderStatusChanged(status));
        connected = await this._events.connect();
        break;
      case EventProviderType.POLLING:
        break;
    }

    if (!connected) {
      this._setError(ORError.EVENTS_CONNECTION_ERROR);
    }

    return connected;
  }

  // Function that connects the EventProvider.
  protected _connectEvents() {
    if (this.events?.status === EventProviderStatus.DISCONNECTED) {
      this.events!.connect().catch((e) => {
        console.error(`Failed to connect EventProvider.`);
        console.error(e);
      });
    } else {
      console.warn("Tried to connect EventProvider, but it wasn't disconnected!");
    }
  }

  protected _onEventProviderStatusChanged(status: EventProviderStatus) {
    switch (status) {
      case EventProviderStatus.DISCONNECTED:
        this._onDisconnect();
        break;
      case EventProviderStatus.CONNECTED:
        break;
      case EventProviderStatus.CONNECTING:
        this._emitEvent(OREvent.CONNECTING);
        break;
    }
  }

  protected async doConsoleInit(): Promise<boolean> {
    try {
      await this.console.initialise();
      this._emitEvent(OREvent.CONSOLE_INIT);
      return true;
    } catch (e) {
      this._setError(ORError.CONSOLE_ERROR);
      return false;
    }
  }

  protected doIconInit() {
    // Load material design and OR icon sets if requested
    if (this._config.loadIcons) {
      IconSets.addIconSet("mdi", createMdiIconSet(manager.managerUrl!));
      IconSets.addIconSet("or", createSvgIconSet(OrIconSet.size, OrIconSet.icons));
    }
  }

  protected async getConsoleAppConfig(): Promise<boolean> {
    try {
      const response = await fetch((manager.managerUrl ?? "") + "/consoleappconfig/" + manager.displayRealm + ".json");
      this._consoleAppConfig = (await response.json()) as ConsoleAppConfig;
      return true;
    } catch (e) {
      return true;
    }
  }

  /**
   * Checks the native console to gather the preferred language of the device.
   */
  public async getConsolePreferredLanguage(orConsole = this.console): Promise<string | undefined> {
    return orConsole.retrieveData("LANGUAGE");
  }

  /**
   * Checks the keycloak access token to gather the preferred language of a user.
   */
  public async getUserPreferredLanguage(keycloak = this._keycloak): Promise<string | undefined> {
    if (keycloak && keycloak.authenticated) {
      const profile: Keycloak.KeycloakProfile | undefined = keycloak?.profile || (await keycloak?.loadUserProfile());
      if (profile?.attributes) {
        const attributes = new Map(Object.entries(profile.attributes));
        if (attributes.has("locale")) {
          const attr = attributes.get("locale") as any[];
          if (typeof attr[0] === "string") {
            return attr[0];
          }
        }
        console.warn("Could not get user language from keycloak: no user attributes were found.");
      } else {
        console.warn("Could not get user language from keycloak: no valid keycloak user profile was found.");
      }
    }
  }

  protected async updateKeycloakUserLanguage(lang: string, rest = this.rest): Promise<void> {
    if (!this.authenticated) {
      console.warn("Tried updating user language, but the user is not authenticated.");
      return;
    }
    if (!rest) {
      console.warn("Tried updating user language, but the REST API is not initialized yet.");
      return;
    }
    await rest.api.UserResource.updateCurrentUserLocale(lang, { headers: { "Content-Type": "application/json" } });
  }

  public logout(redirectUrl?: string) {
    if (!this._authenticated) {
      return;
    }
    this._authenticated = true;

    if (this._keycloak) {
      if (this.isMobile()) {
        this.console.storeData("REFRESH_TOKEN", null);
      }
      if (this._keycloakUpdateTokenInterval) {
        window.clearTimeout(this._keycloakUpdateTokenInterval);
        this._keycloakUpdateTokenInterval = undefined;
      }
      this._keycloak.logout(redirectUrl && redirectUrl !== "" ? { redirectUri: redirectUrl } : undefined);
    }
  }

  public login(options?: LoginOptions) {
    switch (this._config.auth) {
      case Auth.KEYCLOAK:
        if (this._keycloak) {
          const keycloakOptions: any = {};
          if (options && options.redirectUrl && options.redirectUrl !== "") {
            keycloakOptions.redirectUri = options.redirectUrl;
          }
          if (options?.action && options.action !== "") {
            keycloakOptions.action = options.action;
          }
          if (this.isMobile()) {
            keycloakOptions.scope = "offline_access";
          }
          this._keycloak.login(keycloakOptions);
        }
        break;
      case Auth.NONE:
        break;
    }
  }

  public isSuperUser(): boolean {
    return !!(this.getRealm() && this.getRealm() === "master" && this.hasRealmRole("admin"));
  }

  public isRestrictedUser(): boolean {
    return !!this.hasRealmRole(RESTRICTED_USER_REALM_ROLE);
  }

  public getApiBaseUrl(): string {
    let baseUrl = this._config.managerUrl!;
    baseUrl += "/api/" + this._config.realm + "/";
    return baseUrl;
  }

  public getAppName(): string {
    const pathArr = location.pathname.split("/");
    return pathArr.length >= 1 ? pathArr[1] : "";
  }

  public hasRealmRole(role: string) {
    return this.isKeycloak() && this._keycloak!.hasRealmRole(role);
  }

  public hasRole(role: string, client: string = this._config.clientId!) {
    const roles = this.roles;
    return roles && roles.has(client) && roles.get(client)!.indexOf(role) >= 0;
  }

  public getAuthorizationHeader(): string | undefined {
    if (this.getKeycloakToken()) {
      return "Bearer " + this.getKeycloakToken();
    }
  }

  /**
   * This function revalidates the cache by checking if the token in the authorization header is still valid, and retrieves a new token if necessary.
   * It is useful if you want to guarantee the token inside the header is valid before making a request to the Manager APIs.
   */
  public async retrieveAuthorizationHeader(): Promise<string | undefined> {
    const keycloakToken = await this.retrieveKeycloakToken();
    if (keycloakToken) {
      return "Bearer " + keycloakToken;
    }
  }

  public getKeycloakToken(): string | undefined {
    if (this.isKeycloak()) {
      return this._keycloak!.token;
    }
    return undefined;
  }

  /**
   * This function revalidates the cached access token by checking if it is still valid, and retrieves a new token if necessary.
   * It is useful if you want to guarantee the token is valid before making a request to the Manager APIs.
   */
  public async retrieveKeycloakToken(): Promise<string | undefined> {
    if (this.isKeycloak()) {
      await this._updateKeycloakAccessToken();
      return this._keycloak!.token;
    }
    return undefined;
  }

  public getRealm(): string | undefined {
    if (this._config) {
      return this._config.realm;
    }
    return undefined;
  }

  protected isMobile(): boolean {
    return this.console && this.console.isMobile;
  }

  public isKeycloak(): boolean {
    return !!this._keycloak;
  }

  protected _onAuthenticated() {
    this._authenticated = true;
  }

  protected async loadAndInitialiseKeycloak(): Promise<boolean> {
    try {
      // Initialise keycloak
      this._keycloak = new Keycloak({
        clientId: this._config.clientId!,
        realm: this._config.realm!,
        url: this._config.keycloakUrl,
      });

      // Try to use a stored offline refresh token if defined
      const offlineToken = await this._getNativeOfflineRefreshToken();

      // Cannot inject offlineToken here as this adapter is designed for interactive login and even check-sso
      // does a redirect to keycloak but we can inject the offline token as shown and then update the access token
      let authenticated = await this._keycloak!.init({
        checkLoginIframe: false, // Doesn't work well with offline tokens or periodic token updates
        onLoad: "check-sso",
      });

      if (!authenticated && offlineToken) {
        try {
          console.error("SETTING OFFLINE TOKEN");
          this._keycloak.refreshToken = offlineToken;
          authenticated = await this._updateKeycloakAccessToken();
        } catch (e) {
          console.error("Failed to authenticate using offline token");
        }
      }

      if (authenticated) {
        this._name = this._keycloak.tokenParsed?.name;
        this._username = this._keycloak.tokenParsed?.preferred_username;

        // If native shell is enabled store offline token
        if (this.isMobile() && this._keycloak?.refreshTokenParsed?.typ === "Offline") {
          console.debug("Storing offline refresh token");
          this.console.storeData("REFRESH_TOKEN", this._keycloak!.refreshToken);
        }
        this._onAuthenticated();
        this._createTokenUpdateInterval();

        const currentSession = this._keycloak.tokenParsed?.session_state;
        if (currentSession && sessionStorage.getItem("or-session") !== currentSession) {
          sessionStorage.setItem("or-session", currentSession);
          this._emitEvent(OREvent.LOGIN);
        }
      } else if (this.config.autoLogin) {
        this.login();
        return false;
      }
      return true;
    } catch (error) {
      this._authenticated = false;
      this._setError(ORError.AUTH_FAILED);
      console.error("Failed to initialise Keycloak: " + error);
      return false;
    }
  }

  protected _createTokenUpdateInterval() {
    if (!this._authenticated) {
      console.warn("User is not authenticated; skipping token update interval creation.");
      return;
    }
    if (!this._keycloakUpdateTokenInterval) {
      this._keycloakUpdateTokenInterval = window.setInterval(async () => {
        await this._updateKeycloakAccessToken().catch(() => {
          console.debug("Keycloak failed to refresh the access token");
          this._onDisconnect();
        });
      }, 10000);
    }
  }

  protected async _updateKeycloakAccessToken(): Promise<boolean> {
    const tokenRefreshed = await this._keycloak!.updateToken(20);
    console.debug("Access token update success, refreshed from server: " + tokenRefreshed);
    if (tokenRefreshed) {
      this._onAuthenticated();
    }
    return tokenRefreshed;
  }

  protected async _getNativeOfflineRefreshToken(): Promise<string | undefined> {
    if (this.isMobile()) {
      return await this.console.retrieveData("REFRESH_TOKEN");
    }
  }

  protected _emitEvent(event: OREvent) {
    window.setTimeout(() => {
      const listeners = this._listeners;
      for (const listener of listeners) {
        listener(event);
      }
    }, 0);
  }

  protected _setError(error: ORError) {
    this._error = error;
    this._emitEvent(OREvent.ERROR);
    console.warn("Error set: " + error);
  }

  /** Function that clears the `WebView` history of a console. It will not delete the history on regular browsers. */
  protected _clearWebHistory(): void {
    if (this.isMobile()) {
      this.console?._doSendGenericMessage("CLEAR_WEB_HISTORY", undefined);
    }
  }

  /**
   * Disconnect can occur when events status changes to offline and/or keycloak token refresh fails
   * we just try to reach keycloak and then get a new token as well as wait for the events status to change
   */
  protected async _onDisconnect() {
    if (this._disconnected) {
      return;
    }
    console.debug("Disconnected");
    this._disconnected = true;

    // Cancel token refresh timer
    if (this._keycloakUpdateTokenInterval) {
      window.clearTimeout(this._keycloakUpdateTokenInterval);
      this._keycloakUpdateTokenInterval = undefined;
    }

    this._emitEvent(OREvent.OFFLINE);
    this.reconnect();
  }

  protected _onReconnected() {
    console.debug("Reconnected to the Manager.");
    this._disconnected = false;

    // Reinstate token update interval
    this._createTokenUpdateInterval();
    this._emitEvent(OREvent.ONLINE);
  }

  /**
   * Checks keycloak is available and token is valid otherwise will redirect to login; also checks if event bus is
   * online.
   */
  public async reconnect(reattemptDelayMillis: number = 3000) {
    console.debug("Checking connection to the Manager...");

    if (!this._disconnected) {
      console.debug("Manager is still connected. No action necessary.");
      return;
    }
    console.debug("Manager is disconnected!");

    if (this._reconnectTimer) {
      window.clearTimeout(this._reconnectTimer);
      this._reconnectTimer = undefined;
    }

    const tryReconnect = async () => {
      console.debug("Reconnecting to the Manager...");

      // If the user was never authenticated (public access), skip token refresh and just reconnect the event provider.
      if (!this._authenticated) {
        console.debug("User is not authenticated, skipping token refresh");
        const isEventsOnline = () => this.events?.status === EventProviderStatus.CONNECTED;
        if (!isEventsOnline()) {
          await this.events?.connect();
        }
        return isEventsOnline();
      }

      const keycloakOffline = !(await this.isKeycloakReachable());

      if (keycloakOffline) {
        console.error("Keycloak is not reachable. Reconnect attempt failed.");
        return false;
      }

      // Check if access token can be refreshed
      try {
        await this._updateKeycloakAccessToken();
      } catch (e) {
        console.error("Could not update Keycloak access token, attempting again using offline token...", e);
        // Try and use offline token if it is available
        const offlineToken = await this._getNativeOfflineRefreshToken();
        this._keycloak!.refreshToken = offlineToken;
        if (!offlineToken) {
          console.warn("No offline token was found on this device.");
        }

        try {
          await this._updateKeycloakAccessToken();
        } catch (e) {
          this.login();
          throw new Error("Cannot update access token so sending to login");
        }
        console.debug("Keycloak access token is valid");
      }

      // Check events
      const isEventsOnline = () => this.events?.status === EventProviderStatus.CONNECTED;
      if (!isEventsOnline()) {
        console.debug("Event provider offline, attempting to reconnect using latest auth token.");
        await this.events?.connect();
      }
      return isEventsOnline();
    };

    const connected = await tryReconnect();

    if (connected === undefined) {
      // Going back to keycloak login so nothing to do
      return;
    }

    // If failed to connect, schedule another reconnect attempt
    if (!connected) {
      reattemptDelayMillis = Math.min(Manager.MAX_RECONNECT_DELAY, reattemptDelayMillis + 3000);
      console.error(`Failed to reconnect to the Manager. Attempting again in ${reattemptDelayMillis}...`);
      this._reconnectTimer = window.setTimeout(() => this.reconnect(reattemptDelayMillis), reattemptDelayMillis);
      return;
    }

    this._onReconnected();
  }

  /**
   * Checks whether keycloak is reachable using a simple HTTP HEAD request since the keycloak JS adapter doesn't give
   * us details of the HTTP responses they get, we test it manually using this.
   * @param timeoutMillis - Custom timeout of the HTTP HEAD request in milliseconds
   * @protected
   */
  protected async isKeycloakReachable(timeoutMillis = 2000): Promise<boolean> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMillis);
    try {
      // Get the token URL from keycloak as this must already have CORS configured for keycloak to work
      // Make an OPTIONS request as GET/HEAD requests are not allowed on the token endpoint
      // Note if keycloak service is unavailable the proxy may respond with 503 and no CORS headers
      // but this is handled by the catch block
      // @ts-ignore
      const tokenUrl = this._keycloak.endpoints.token();
      const result = await fetch(tokenUrl, { method: "OPTIONS", signal: controller.signal });
      return result.status === 200;
    } catch (e) {
      return false;
    } finally {
      clearTimeout(timeout);
    }
  }
}

export const manager = new Manager(); // Needed for webpack bundling
export default manager;
