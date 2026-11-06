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
import { ct, expect } from "@openremote/test";

import { EventProviderHarness } from "./fixtures/event-provider-harness.js";

ct("should send a subscribe again when the connection drops before the reply", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    el.subscribeReply = "none";
    const interrupted = el.subscribeTo("alarm", "interrupted");
    await el.settle();

    el.subscribeReply = "subscribed";
    await el.reconnect();
    const interruptedOutcome = await el.outcome(interrupted);
    const nextOutcome = await el.outcome(el.subscribeTo("alarm", "next"));

    return { interruptedOutcome, nextOutcome, frames: el.socket.frames };
  });

  // The slot for the reply used to stay taken after a drop, so nothing went out on the new socket and every later
  // subscribe was rejected with "There's already a pending subscription"
  expect(result.frames).toEqual(["SUBSCRIBE", "SUBSCRIBE"]);
  expect(result.interruptedOutcome).toBe("resolved");
  expect(result.nextOutcome).toBe("resolved");
});

for (const eventType of ["asset", "attribute"]) {
  ct(`should retry a shared ${eventType} subscription after it was rejected`, async ({ mount }) => {
    const component = await mount(EventProviderHarness);

    const result = await component.evaluate(async (el: EventProviderHarness, type: string) => {
      await el.connect();
      el.subscribeReply = "unauthorized";
      const rejectedOutcome = await el.outcome(el.subscribeTo(type, "rejected"));

      el.subscribeReply = "subscribed";
      const retriedOutcome = await el.outcome(el.subscribeTo(type, "retried"));
      el.trigger(type);

      return { rejectedOutcome, retriedOutcome, frames: el.socket.frames, received: el.received };
    }, eventType);

    expect(result.rejectedOutcome).toBe("rejected: Unauthorized");
    // The rejected promise used to be kept and shared, so a retry got the same rejection without sending anything
    expect(result.retriedOutcome).toBe("resolved");
    expect(result.frames).toEqual(["SUBSCRIBE", "SUBSCRIBE"]);
    // The callback of the failed call used to stay registered, and nothing could remove it
    expect(result.received).toEqual(["retried"]);
  });
}

ct("should ignore a replaced socket that closes during the reconnect", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    const replaced = el.socket;
    replaced.error();
    await el.until(() => el.socket !== replaced);
    const current = el.socket;

    replaced.closed();
    current.open();
    await el.settle();

    return { status: el.provider.status, closeRequested: replaced.closeRequested, socketCount: el.sockets.length };
  });

  // The late close used to fail the reconnect that was under way, which left the new socket open but unused
  expect(result.status).toBe("CONNECTED");
  expect(result.closeRequested).toBe(true);
  expect(result.socketCount).toBe(2);
});

ct("should keep the connection when a replaced socket closes during a subscribe", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    const replaced = el.socket;
    replaced.error();
    await el.until(() => el.socket !== replaced);
    const current = el.socket;
    current.open();
    await el.until(() => el.provider.status === "CONNECTED");

    el.subscribeReply = "none";
    const subscribed = el.subscribeTo("alarm", "alarm");
    await el.settle();
    replaced.closed();
    await el.settle();
    current.answer();

    return { outcome: await el.outcome(subscribed), status: el.provider.status, socketCount: el.sockets.length };
  });

  // The close used to count as a drop of the current socket, which started another reconnect and left that socket open
  expect(result.status).toBe("CONNECTED");
  expect(result.socketCount).toBe(2);
  expect(result.outcome).toBe("resolved");
});

for (const eventType of ["alarm", "asset", "attribute"]) {
  ct(`should keep delivering ${eventType} events after a reconnect`, async ({ mount }) => {
    const component = await mount(EventProviderHarness);

    const result = await component.evaluate(async (el: EventProviderHarness, type: string) => {
      await el.connect();
      await el.subscribeTo(type, "subscribed");
      await el.reconnect();
      el.trigger(type);

      return { frames: el.socket.frames, received: el.received };
    }, eventType);

    // Subscriptions used to be lost on a reconnect, so a consumer holding on to its ID silently stopped getting events
    expect(result.frames).toEqual(["SUBSCRIBE"]);
    expect(result.received).toEqual(["subscribed"]);
  });
}

ct("should end with one server subscription when resubscribing during a restore", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    const subscriptionId = await el.subscribeTo("attribute", "old");

    el.subscribeReply = "none";
    await el.reconnect();
    await el.provider.unsubscribe(subscriptionId);
    el.subscribeReply = "subscribed";
    const resubscribed = el.subscribeTo("attribute", "new");
    el.socket.answer();
    const outcome = await el.outcome(resubscribed);
    el.trigger("attribute");

    return { outcome, frames: el.socket.frames, subscriptions: el.socket.subscriptions.size, received: el.received };
  });

  expect(result.outcome).toBe("resolved");
  expect(result.frames).toEqual(["SUBSCRIBE", "UNSUBSCRIBE", "SUBSCRIBE"]);
  expect(result.subscriptions).toBe(1);
  expect(result.received).toEqual(["new"]);
});

ct("should not restore a subscription unsubscribed while it waits for its turn", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    const subscriptionIds = [await el.subscribeTo("alarm", "first"), await el.subscribeTo("alarm", "second")];

    el.subscribeReply = "none";
    await el.reconnect();
    const inFlight = el.socket.unanswered[0].subscriptionId;
    const waiting = subscriptionIds.find((subscriptionId) => subscriptionId !== inFlight);
    await el.provider.unsubscribe(waiting);
    el.socket.answer();
    await el.settle();

    return { inFlight, frames: el.socket.frames, subscriptions: [...el.socket.subscriptions.keys()] };
  });

  expect(result.frames).toEqual(["SUBSCRIBE"]);
  expect(result.subscriptions).toEqual([result.inFlight]);
});

ct("should not restore a subscription unsubscribed before another drop", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    const subscriptionId = await el.subscribeTo("alarm", "alarm");

    el.subscribeReply = "none";
    await el.reconnect();
    await el.provider.unsubscribe(subscriptionId);
    await el.reconnect();

    return { frames: el.socket.frames };
  });

  expect(result.frames).toEqual([]);
});

ct("should resolve a subscribe that was unsubscribed before it was sent", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    const subscribed = el.provider.subscribe({ eventType: "alarm", subscriptionId: "own" }, () => {});
    await el.provider.unsubscribe("own");
    const outcome = await el.outcome(subscribed);
    await el.connect();

    return { outcome, frames: el.socket.frames };
  });

  expect(result.outcome).toBe("resolved");
  expect(result.frames).toEqual([]);
});

ct("should resolve a subscribe unsubscribed before its reply when the connection drops", async ({ mount }) => {
  const component = await mount(EventProviderHarness);

  const result = await component.evaluate(async (el: EventProviderHarness) => {
    await el.connect();
    el.subscribeReply = "none";
    const subscribed = el.provider.subscribe({ eventType: "alarm", subscriptionId: "own" }, () => {});
    await el.settle();
    await el.provider.unsubscribe("own");

    el.subscribeReply = "subscribed";
    await el.reconnect();

    return { outcome: await el.outcome(subscribed), frames: el.socket.frames };
  });

  expect(result.outcome).toBe("resolved");
  expect(result.frames).toEqual([]);
});
