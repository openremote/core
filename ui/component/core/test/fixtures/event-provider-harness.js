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
// Plain JS on purpose: the component-test bundle compiles .ts files against ui/test's tsconfig
// (rootDir ui/test), so TS component sources outside that dir fail the build.
import manager, { WebSocketEventProvider } from "@openremote/core";

const SETTLE_MILLIS = 50;
const WAIT_MILLIS = 2000;

const ASSET_ID = "asset1";

const EVENTS = {
  alarm: { eventType: "alarm" },
  asset: { eventType: "asset", asset: { id: ASSET_ID } },
  attribute: { eventType: "attribute", ref: { id: ASSET_ID, name: "attribute1" } },
};

const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis));

const DEFAULT_TIMEOUTS = {
  reply: WebSocketEventProvider.REPLY_TIMEOUT_MILLIS,
  heartbeatInterval: WebSocketEventProvider.HEARTBEAT_INTERVAL_MILLIS,
  heartbeatTimeout: WebSocketEventProvider.HEARTBEAT_TIMEOUT_MILLIS,
  token: WebSocketEventProvider.TOKEN_TIMEOUT_MILLIS,
};

/**
 * Stands in for a browser socket and the server behind it, which holds the subscriptions of that connection. The
 * socket's own events only fire when a test calls `open`, `error` or `closed`, so a test decides their order.
 */
class FakeWebSocket {
  static harness;

  constructor(url) {
    this.url = url;
    this.frames = [];
    this.subscriptions = new Map();
    this.unanswered = [];
    this.closeRequested = false;
    FakeWebSocket.harness.sockets.push(this);
  }

  send(message) {
    const separator = message.indexOf(":");
    const frame = message.substring(0, separator);
    const body = JSON.parse(message.substring(separator + 1));
    this.frames.push(frame);

    if (FakeWebSocket.harness.silent) {
      return;
    }

    if (frame === "UNSUBSCRIBE") {
      this.subscriptions.delete(body.subscriptionId);
    } else if (frame === "SUBSCRIBE") {
      this._onSubscribe(body);
    }
  }

  _onSubscribe(subscription) {
    const reply = FakeWebSocket.harness.subscribeReply;

    if (reply === "none") {
      this.unanswered.push(subscription);
    } else if (reply === "unauthorized") {
      setTimeout(() => this.onmessage?.({ data: "UNAUTHORIZED:" + JSON.stringify(subscription) }));
    } else {
      this.subscriptions.set(subscription.subscriptionId, subscription);
      setTimeout(() => this.onmessage?.({ data: "SUBSCRIBED:" + JSON.stringify(subscription) }));
    }
  }

  /** Replies to the subscribes that got no reply so far. */
  answer() {
    const subscriptions = this.unanswered;
    this.unanswered = [];
    subscriptions.forEach((subscription) => {
      this.subscriptions.set(subscription.subscriptionId, subscription);
      this.onmessage?.({ data: "SUBSCRIBED:" + JSON.stringify(subscription) });
    });
  }

  close() {
    this.closeRequested = true;
  }

  open() {
    this.onopen?.({});
  }

  error() {
    this.onerror?.({});
  }

  closed() {
    this.onclose?.({});
  }
}

/**
 * Runs a real {@link WebSocketEventProvider} against {@link FakeWebSocket}. Tests drive it from `evaluate`, since the
 * provider and its callbacks can't cross into the Playwright test.
 */
export class EventProviderHarness extends HTMLElement {
  constructor() {
    super();
    this.sockets = [];
    /** How the server replies to a subscribe: `subscribed`, `unauthorized` or `none`. */
    this.subscribeReply = "subscribed";
    /** The labels of the subscribe calls whose callback got an event, in order. */
    this.received = [];
    /** Whether the server stops answering anything, like a connection that died without closing. */
    this.silent = false;
    this.setTimeouts(DEFAULT_TIMEOUTS);

    FakeWebSocket.harness = this;
    window.WebSocket = FakeWebSocket;
    this.provider = new WebSocketEventProvider("https://localhost");
  }

  /** Sets the provider's reply, heartbeat and token timeouts, in milliseconds, for the rest of the test. */
  setTimeouts({ reply, heartbeatInterval, heartbeatTimeout, token }) {
    WebSocketEventProvider.TOKEN_TIMEOUT_MILLIS = token ?? WebSocketEventProvider.TOKEN_TIMEOUT_MILLIS;
    WebSocketEventProvider.REPLY_TIMEOUT_MILLIS = reply ?? WebSocketEventProvider.REPLY_TIMEOUT_MILLIS;
    WebSocketEventProvider.HEARTBEAT_INTERVAL_MILLIS =
      heartbeatInterval ?? WebSocketEventProvider.HEARTBEAT_INTERVAL_MILLIS;
    WebSocketEventProvider.HEARTBEAT_TIMEOUT_MILLIS =
      heartbeatTimeout ?? WebSocketEventProvider.HEARTBEAT_TIMEOUT_MILLIS;
  }

  /**
   * Signs the manager in, so the provider gets a token for every connection. `token` answers each request for one,
   * like `manager.retrieveAuthorizationHeader`.
   */
  signIn(token) {
    Object.defineProperty(manager, "authenticated", { configurable: true, get: () => true });
    manager.retrieveAuthorizationHeader = token;
  }

  /** The socket the provider opened last. */
  get socket() {
    return this.sockets[this.sockets.length - 1];
  }

  /** Subscribes through the method a consumer of this event type uses, and records events under `label`. */
  subscribeTo(eventType, label) {
    const callback = () => this.received.push(label);

    switch (eventType) {
      case "asset":
        return this.provider.subscribeAssetEvents([ASSET_ID], false, callback);
      case "attribute":
        return this.provider.subscribeAttributeEvents([ASSET_ID], false, callback);
      default:
        return this.provider.subscribe({ eventType }, callback);
    }
  }

  /** Sends an event of this type to each subscription of that type on the current connection. */
  trigger(eventType) {
    this.socket.subscriptions.forEach((subscription, subscriptionId) => {
      if (subscription.eventType === eventType) {
        const triggered = { subscriptionId, events: [EVENTS[eventType]] };
        this.socket.onmessage?.({ data: "TRIGGERED:" + JSON.stringify(triggered) });
      }
    });
  }

  async connect() {
    this.provider.connect();
    await this.until(() => this.socket);
    this.socket.open();
    await this.until(() => this.provider.status === "CONNECTED");
    await this.settle();
  }

  /** Drops the current connection and completes the reconnect the provider starts by itself. */
  async reconnect() {
    const previous = this.socket;
    previous.error();
    previous.closed();
    await this.until(() => this.socket !== previous);
    this.socket.open();
    await this.until(() => this.provider.status === "CONNECTED");
    await this.settle();
  }

  async until(predicate) {
    const deadline = Date.now() + WAIT_MILLIS;
    while (!predicate()) {
      if (Date.now() > deadline) {
        throw new Error("Timed out waiting for: " + predicate);
      }
      await sleep(1);
    }
  }

  /** Gives the provider time to finish what it does on its own, to check that something didn't happen. */
  settle() {
    return sleep(SETTLE_MILLIS);
  }

  /** Describes how a promise ended, so a hanging one fails an assertion instead of the test's timeout. */
  outcome(promise) {
    return Promise.race([
      promise.then(
        () => "resolved",
        (reason) => "rejected: " + reason
      ),
      sleep(WAIT_MILLIS).then(() => "pending"),
    ]);
  }
}

customElements.define("event-provider-harness", EventProviderHarness);
