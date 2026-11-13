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
import manager from "./index";
import { arrayRemove, Deferred, withTimeout } from "./util";
import {
  type Asset,
  type AssetEvent,
  AssetEventCause,
  type AssetFilter,
  type AssetsEvent,
  type AttributeEvent,
  type AttributeRef,
  type CancelEventSubscription,
  type EventSubscription,
  type ReadAssetsEvent,
  type SharedEvent,
  type TriggeredEventSubscription,
} from "@openremote/model";

export enum EventProviderStatus {
  DISCONNECTED = "DISCONNECTED",
  CONNECTED = "CONNECTED",
  CONNECTING = "CONNECTING",
}

/**
 * Subscriptions survive a reconnect, so subscribing again without unsubscribing first delivers every event twice.
 */
export interface EventProvider {
  status: EventProviderStatus;

  connect(): Promise<boolean>;

  disconnect(): void;

  subscribeStatusChange(callback: (status: EventProviderStatus) => void): void;

  unsubscribeStatusChange(callback: (status: EventProviderStatus) => void): void;

  subscribe<T extends SharedEvent>(
    eventSubscription: EventSubscription<T>,
    callback: (event: T) => void
  ): Promise<string>;

  unsubscribe<T extends SharedEvent>(subscriptionId: string): void;

  /**
   * Subscribe to {@link AssetEvent}s for the specified {@link Asset}s for all {@link Asset}s
   */
  subscribeAssetEvents(
    ids: string[] | AttributeRef[] | undefined,
    requestCurrentValues: boolean,
    callback: (event: AssetEvent) => void
  ): Promise<string>;

  /**
   * Subscribe to {@link AttributeEvent}s for the specified {@link Asset}s and if {@link AttributeRef}s are provided
   * then the attribute names from the refs will be used for each asset ID.
   */
  subscribeAttributeEvents(
    ids: string[] | AttributeRef[],
    requestCurrentValues: boolean,
    callback: (event: AttributeEvent) => void
  ): Promise<string>;

  sendEvent<T extends SharedEvent>(event: T): void;

  sendEventWithReply<T extends SharedEvent, U extends SharedEvent>(event: T): Promise<U>;
}

// Interface to provide a singleton implementation of EventProvider
export interface EventProviderFactory {
  getEventProvider(): EventProvider | undefined;
}

interface Subscription {
  eventSubscription: EventSubscription<SharedEvent>;
  callback: (event: SharedEvent) => void;
  // Whether the server has confirmed it on the current connection
  confirmed: boolean;
  // Settles the promise from subscribe() on the first confirmation
  settle?: { resolve: (subscriptionId: string) => void; reject: (reason: string) => void };
}

// One server subscription shared by all callbacks, each with its own filter
interface SharedSubscription<T extends SharedEvent> {
  eventType: "asset" | "attribute";
  idPrefix: string;
  promise?: Promise<string>;
  callbacks: Map<string, (event: T) => void>;
}

const SUBSCRIBE_MESSAGE_PREFIX = "SUBSCRIBE:";
const SUBSCRIBED_MESSAGE_PREFIX = "SUBSCRIBED:";
const UNSUBSCRIBE_MESSAGE_PREFIX = "UNSUBSCRIBE:";
const UNAUTHORIZED_MESSAGE_PREFIX = "UNAUTHORIZED:";
const TRIGGERED_MESSAGE_PREFIX = "TRIGGERED:";
const EVENT_MESSAGE_PREFIX = "EVENT:";

const assetIdsOf = (ids: string[] | AttributeRef[] | undefined) =>
  ids && typeof ids[0] !== "string" ? (ids as AttributeRef[]).map((ref) => ref.id!) : (ids as string[] | undefined);

export class WebSocketEventProvider implements EventProvider {
  protected static MIN_RECONNECT_DELAY: number = 0;
  protected static MAX_RECONNECT_DELAY: number = 30000;
  protected static _subscriptionCounter: number = 1;
  // How long sendEventWithReply waits for the reply
  public static REPLY_TIMEOUT_MILLIS = 30000;
  // How long a connection may receive nothing before it is checked
  public static HEARTBEAT_INTERVAL_MILLIS = 30000;
  // How long a checked connection may take to answer before it counts as lost
  public static HEARTBEAT_TIMEOUT_MILLIS = 10000;
  // How long getting the token for a connection may take before connecting is tried again
  public static TOKEN_TIMEOUT_MILLIS = 10000;

  protected readonly _endpointUrl: string;
  protected _status: EventProviderStatus = EventProviderStatus.DISCONNECTED;
  protected _statusCallbacks: Array<(status: EventProviderStatus) => void> = [];
  protected _webSocket?: WebSocket;
  protected _connecting?: Promise<boolean>;
  protected _disconnectRequested = false;
  protected _unloading = false;
  protected _reconnectDelayMillis = WebSocketEventProvider.MIN_RECONNECT_DELAY;
  protected _reconnectTimer?: number;
  protected _heartbeatTimer?: number;
  protected _lastMessageAt = 0;
  protected _subscriptions = new Map<string, Subscription>();
  protected _replies = new Map<string, Deferred<SharedEvent>>();
  protected _assetEvents: SharedSubscription<AssetEvent> = {
    eventType: "asset",
    idPrefix: "AssetEvent",
    callbacks: new Map(),
  };

  protected _attributeEvents: SharedSubscription<AttributeEvent> = {
    eventType: "attribute",
    idPrefix: "AttributeEvent",
    callbacks: new Map(),
  };

  constructor(managerUrl: string) {
    this._endpointUrl = managerUrl.replace(/^http/, "ws") + "/websocket/events";

    // Close socket on unload/refresh of page
    window.addEventListener("beforeunload", () => {
      this._unloading = true;
      this.disconnect();
    });

    // A connection often dies without closing while the device sleeps or changes networks
    document.addEventListener(
      "visibilitychange",
      () => document.visibilityState === "visible" && this._checkConnection(true)
    );
    window.addEventListener("online", () => this._checkConnection(true));
  }

  get endpointUrl(): string {
    return this._endpointUrl;
  }

  public get status(): EventProviderStatus {
    return this._status;
  }

  public subscribeStatusChange(callback: (status: EventProviderStatus) => void): void {
    this._statusCallbacks.push(callback);
  }

  public unsubscribeStatusChange(callback: (status: EventProviderStatus) => void): void {
    arrayRemove(this._statusCallbacks, callback);
  }

  public connect(): Promise<boolean> {
    console.debug("Attempting to connect to events:", this._endpointUrl);
    window.clearTimeout(this._reconnectTimer);
    this._reconnectTimer = undefined;
    if (this._status === EventProviderStatus.CONNECTED) {
      return Promise.resolve(true);
    }

    this._disconnectRequested = false;
    if (!this._connecting) {
      this._setStatus(EventProviderStatus.CONNECTING);
      this._connecting = this._open().finally(() => (this._connecting = undefined));
    }
    return this._connecting;
  }

  public disconnect(): void {
    console.debug("Disconnecting from event service: " + this._endpointUrl);
    this._disconnectRequested = true;
    window.clearTimeout(this._reconnectTimer);
    this._reconnectTimer = undefined;
    this._discardWebSocket();
    this._setStatus(EventProviderStatus.DISCONNECTED);
  }

  public subscribe<T extends SharedEvent>(
    eventSubscription: EventSubscription<T>,
    callback: (event: T) => void
  ): Promise<string> {
    const subscriptionId = (eventSubscription.subscriptionId ??= WebSocketEventProvider._subscriptionCounter++ + "");

    return new Promise((resolve, reject) => {
      const subscription: Subscription = {
        eventSubscription,
        callback: callback as (event: SharedEvent) => void,
        confirmed: false,
        settle: { resolve, reject },
      };
      this._subscriptions.set(subscriptionId, subscription);

      // Otherwise it is sent once connected
      if (this._status === EventProviderStatus.CONNECTED) {
        this._send(SUBSCRIBE_MESSAGE_PREFIX, eventSubscription);
      }
    });
  }

  public async unsubscribe<T extends SharedEvent>(subscriptionId: string): Promise<void> {
    const subscription = this._subscriptions.get(subscriptionId);
    if (subscription) {
      this._subscriptions.delete(subscriptionId);
      // One the server has yet to confirm is removed when the confirmation arrives
      if (subscription.confirmed) {
        this._unsubscribeFromServer(subscriptionId);
      }
      // Resolves rather than rejects, since the subscribe didn't fail but was unsubscribed
      subscription.settle?.resolve(subscriptionId);
      subscription.settle = undefined;
      return;
    }

    (await this._removeSharedCallback(this._assetEvents, subscriptionId)) ||
      (await this._removeSharedCallback(this._attributeEvents, subscriptionId));
  }

  public sendEvent<T extends SharedEvent>(event: T): void {
    if (this._status === EventProviderStatus.CONNECTED) {
      this._send(EVENT_MESSAGE_PREFIX, event);
    }
  }

  public sendEventWithReply<T extends SharedEvent, U extends SharedEvent>(event: T): Promise<U> {
    if (this._status !== EventProviderStatus.CONNECTED) {
      return Promise.reject("Not connected");
    }

    const messageID = (event.messageID ??= (new Date().getTime() + Math.random() * 10).toString(10));
    if (this._replies.has(messageID)) {
      return Promise.reject("There's already a pending send and reply with this ID");
    }

    const reply = new Deferred<SharedEvent>();
    this._replies.set(messageID, reply);
    window.setTimeout(() => {
      if (this._replies.get(messageID) === reply) {
        this._replies.delete(messageID);
        reply.reject("Timed out waiting for a reply");
      }
    }, WebSocketEventProvider.REPLY_TIMEOUT_MILLIS);
    this._send(EVENT_MESSAGE_PREFIX, event);
    return reply.promise as Promise<U>;
  }

  public subscribeAssetEvents(
    ids: string[] | AttributeRef[] | undefined,
    requestCurrentValues: boolean,
    callback: (event: AssetEvent) => void
  ): Promise<string> {
    const assetIds = assetIdsOf(ids);
    return this._addSharedCallback(
      this._assetEvents,
      ({ asset }) => (assetIds ? assetIds.includes(asset!.id!) : asset!.realm === manager.displayRealm),
      callback,
      requestCurrentValues ? assetIds : undefined,
      (asset) => [{ eventType: "asset", asset, cause: AssetEventCause.READ }]
    );
  }

  public subscribeAttributeEvents(
    ids: string[] | AttributeRef[] | undefined,
    requestCurrentValues: boolean,
    callback: (event: AttributeEvent) => void
  ): Promise<string> {
    const assetIds = assetIdsOf(ids);
    const refs = ids && typeof ids[0] !== "string" ? (ids as AttributeRef[]) : undefined;
    return this._addSharedCallback(
      this._attributeEvents,
      ({ ref, realm }) =>
        refs
          ? refs.some(({ id, name }) => id === ref!.id && name === ref!.name)
          : assetIds
            ? assetIds.includes(ref!.id!)
            : realm === manager.displayRealm,
      callback,
      requestCurrentValues ? assetIds : undefined,
      (asset) =>
        Object.entries(asset.attributes ?? {}).map(([name, attribute]) => ({
          eventType: "attribute",
          timestamp: attribute.timestamp,
          value: attribute.value,
          ref: { id: asset.id, name },
        }))
    );
  }

  protected async _addSharedCallback<T extends SharedEvent>(
    shared: SharedSubscription<T>,
    matches: (event: T) => boolean,
    callback: (event: T) => void,
    readAssetIds: string[] | undefined,
    toEvents: (asset: Asset) => T[]
  ): Promise<string> {
    const callbackId = shared.idPrefix + WebSocketEventProvider._subscriptionCounter++;

    if (!shared.promise) {
      // Anonymous users must filter by realm
      const filter: AssetFilter | undefined = manager.authenticated
        ? undefined
        : { filterType: "asset", realm: manager.displayRealm };
      const promise = this.subscribe<T>({ eventType: shared.eventType, filter } as EventSubscription<T>, (event) =>
        shared.callbacks.forEach((sharedCallback) => sharedCallback(event))
      );
      shared.promise = promise;
      // A failed subscription must not be reused
      promise.catch(() => shared.promise === promise && (shared.promise = undefined));
    }

    const filtered = (event: T) => matches(event) && callback(event);
    shared.callbacks.set(callbackId, filtered);

    try {
      await shared.promise;
    } catch (reason) {
      shared.callbacks.delete(callbackId);
      throw reason;
    }

    if (readAssetIds) {
      const read: ReadAssetsEvent = {
        messageID: "read-assets:" + readAssetIds.join(",") + ":" + callbackId,
        eventType: "read-assets",
        assetQuery: { ids: readAssetIds },
      };
      this.sendEventWithReply<ReadAssetsEvent, AssetsEvent>(read)
        .then(({ assets }) => shared.callbacks.has(callbackId) && assets?.flatMap(toEvents).forEach(filtered))
        .catch((reason) => console.warn("Failed to read the current values of assets: " + readAssetIds, reason));
    }

    return callbackId;
  }

  protected async _removeSharedCallback<T extends SharedEvent>(
    shared: SharedSubscription<T>,
    callbackId: string
  ): Promise<boolean> {
    if (!shared.callbacks.delete(callbackId)) {
      return false;
    }

    const promise = shared.promise;
    if (promise && shared.callbacks.size === 0) {
      const subscriptionId = await promise.catch(() => undefined);
      // Another callback may have been added while waiting
      if (subscriptionId && shared.promise === promise && shared.callbacks.size === 0) {
        shared.promise = undefined;
        await this.unsubscribe(subscriptionId);
      }
    }
    return true;
  }

  protected async _open(): Promise<boolean> {
    let url = this._endpointUrl + "?Realm=" + manager.config.realm;
    if (manager.authenticated) {
      let authorization: string | undefined;
      try {
        authorization = await withTimeout(
          manager.retrieveAuthorizationHeader(),
          WebSocketEventProvider.TOKEN_TIMEOUT_MILLIS
        );
      } catch (e) {
        console.warn("Failed to retrieve the authorization header for events", e);
      }
      // The server would reject every subscription of a connection without one
      if (!authorization) {
        this._scheduleReconnect();
        return false;
      }
      url += "&Authorization=" + authorization;
    }
    if (this._disconnectRequested) {
      return false;
    }

    const socket = new WebSocket(url);
    this._webSocket = socket;
    const opened = await new Promise<boolean>((resolve) => {
      socket.onopen = () => resolve(true);
      socket.onerror = socket.onclose = () => resolve(false);
    });

    if (!opened || socket !== this._webSocket) {
      console.debug("Failed to connect to event service: " + this._endpointUrl);
      if (socket === this._webSocket) {
        this._discardWebSocket();
      }
      this._scheduleReconnect();
      return false;
    }

    // Only the current socket may change the state of the provider
    socket.onerror = socket.onclose = () => socket === this._webSocket && this._onConnectionLost();
    socket.onmessage = (e) => {
      if (socket === this._webSocket) {
        this._lastMessageAt = Date.now();
        this._onMessage(e.data);
      }
    };
    this._lastMessageAt = Date.now();
    this._heartbeatTimer = window.setInterval(
      () => this._checkConnection(false),
      WebSocketEventProvider.HEARTBEAT_INTERVAL_MILLIS
    );

    console.debug("Connected to event service: " + this._endpointUrl);
    this._reconnectDelayMillis = WebSocketEventProvider.MIN_RECONNECT_DELAY;
    this._setStatus(EventProviderStatus.CONNECTED);
    this._subscriptions.forEach(({ eventSubscription }) => this._send(SUBSCRIBE_MESSAGE_PREFIX, eventSubscription));
    return true;
  }

  // Browsers keep a dead connection open until the network stack gives up on it, which can take hours
  protected _checkConnection(force: boolean) {
    const socket = this._webSocket;
    const idleMillis = Date.now() - this._lastMessageAt;
    if (!socket || this._status !== EventProviderStatus.CONNECTED) {
      return;
    }
    if (!force && idleMillis < WebSocketEventProvider.HEARTBEAT_INTERVAL_MILLIS) {
      return;
    }

    // The server answers every subscribe, and one with an unknown ID is cancelled again on confirmation
    const sentAt = Date.now();
    const subscriptionId = "heartbeat" + WebSocketEventProvider._subscriptionCounter++;
    this._send(SUBSCRIBE_MESSAGE_PREFIX, { eventType: "alarm", subscriptionId });
    window.setTimeout(() => {
      if (socket === this._webSocket && this._lastMessageAt < sentAt) {
        console.warn("The event service stopped responding, reconnecting");
        this._onConnectionLost();
      }
    }, WebSocketEventProvider.HEARTBEAT_TIMEOUT_MILLIS);
  }

  protected _onConnectionLost() {
    console.warn("The WebSocket connection of the event provider has been closed.");
    this._discardWebSocket();
    this._setStatus(EventProviderStatus.DISCONNECTED);
    this._setStatus(EventProviderStatus.CONNECTING);
    this._scheduleReconnect();
  }

  // The server forgets the subscriptions and pending replies of a closed connection
  protected _discardWebSocket() {
    this._webSocket?.close();
    this._webSocket = undefined;
    window.clearInterval(this._heartbeatTimer);
    this._heartbeatTimer = undefined;
    this._subscriptions.forEach((subscription) => (subscription.confirmed = false));
    this._replies.forEach((reply) => reply.reject("Not connected"));
    this._replies.clear();
  }

  protected _scheduleReconnect() {
    if (this._reconnectTimer || this._disconnectRequested) {
      return;
    }

    console.debug("Event provider scheduling reconnect in " + this._reconnectDelayMillis + "ms");
    this._reconnectTimer = window.setTimeout(() => {
      this._reconnectTimer = undefined;
      this.connect();
    }, this._reconnectDelayMillis);
    this._reconnectDelayMillis = Math.min(
      WebSocketEventProvider.MAX_RECONNECT_DELAY,
      this._reconnectDelayMillis + 3000
    );
  }

  protected _onMessage(message: string) {
    const separator = message.indexOf(":") + 1;
    const parse = () => JSON.parse(message.substring(separator));

    switch (message.substring(0, separator)) {
      case SUBSCRIBED_MESSAGE_PREFIX: {
        const { subscriptionId } = parse() as EventSubscription<SharedEvent>;
        const subscription = this._subscriptions.get(subscriptionId!);
        if (!subscription) {
          // Unsubscribed before the server confirmed it
          this._unsubscribeFromServer(subscriptionId!);
          return;
        }
        subscription.confirmed = true;
        subscription.settle?.resolve(subscriptionId!);
        subscription.settle = undefined;
        return;
      }
      case UNAUTHORIZED_MESSAGE_PREFIX: {
        const eventSubscription = parse() as EventSubscription<SharedEvent>;
        const subscription = this._subscriptions.get(eventSubscription.subscriptionId!);
        if (subscription) {
          console.warn("Unauthorized event subscription: " + JSON.stringify(eventSubscription, null, 2));
          this._subscriptions.delete(eventSubscription.subscriptionId!);
          subscription.settle?.reject("Unauthorized");
        }
        return;
      }
      case TRIGGERED_MESSAGE_PREFIX: {
        const { subscriptionId, events } = parse() as TriggeredEventSubscription<SharedEvent>;
        events?.forEach((event) => this._subscriptions.get(subscriptionId!)?.callback(event));
        return;
      }
      case EVENT_MESSAGE_PREFIX: {
        const event = parse() as SharedEvent;
        const reply = event.messageID ? this._replies.get(event.messageID) : undefined;
        this._replies.delete(event.messageID!);
        reply?.resolve(event);
      }
    }
  }

  protected _unsubscribeFromServer(subscriptionId: string) {
    const cancelSubscription: CancelEventSubscription = { subscriptionId };
    this._send(UNSUBSCRIBE_MESSAGE_PREFIX, cancelSubscription);
  }

  protected _send(prefix: string, payload: object) {
    this._webSocket?.send(prefix + JSON.stringify(payload));
  }

  protected _setStatus(status: EventProviderStatus) {
    if (status === this._status) {
      return;
    }

    console.debug("Event provider status changed: " + status);
    this._status = status;
    if (!this._unloading) {
      window.setTimeout(() => this._statusCallbacks.forEach((callback) => callback(status)), 0);
    }
  }
}
