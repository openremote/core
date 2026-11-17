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
package org.openremote.manager.datapoint;

import static org.openremote.model.value.MetaItemType.APPLY_PREDICTED_DATA_POINTS;
import static org.openremote.model.value.MetaItemType.HAS_PREDICTED_DATA_POINTS;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.MetaMap;
import org.openremote.model.datapoint.AssetPredictedDatapointEvent;
import org.openremote.model.datapoint.ValueDatapoint;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.query.LogicGroup;
import org.openremote.model.query.filter.AttributePredicate;
import org.openremote.model.query.filter.BooleanPredicate;
import org.openremote.model.query.filter.NameValuePredicate;

/**
 * Applies predicted datapoints as attribute values when timestamps match for attributes tagged with
 * {@link org.openremote.model.value.MetaItemType#HAS_PREDICTED_DATA_POINTS} and {@link
 * org.openremote.model.value.MetaItemType#APPLY_PREDICTED_DATA_POINTS}.
 */
public class ApplyPredictedDataPointsService implements ContainerService {

  protected TimerService timerService;
  protected AssetStorageService assetStorageService;
  protected AssetPredictedDatapointService assetPredictedDatapointService;
  protected AssetProcessingService assetProcessingService;
  protected ClientEventService clientEventService;
  protected ScheduledExecutorService scheduledExecutorService;

  protected final Object scheduleLock = new Object();
  protected final PriorityQueue<ScheduledEntry> scheduleQueue =
      new PriorityQueue<>(Comparator.comparingLong(e -> e.timestamp));
  protected final Map<AttributeRef, ScheduledEntry> scheduledEntries = new HashMap<>();
  protected ScheduledFuture<?> scheduledFuture;

  @Override
  public int getPriority() {
    return ContainerService.DEFAULT_PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    timerService = container.getService(TimerService.class);
    assetStorageService = container.getService(AssetStorageService.class);
    assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class);
    assetProcessingService = container.getService(AssetProcessingService.class);
    clientEventService = container.getService(ClientEventService.class);
    scheduledExecutorService = container.getScheduledExecutor();
  }

  @Override
  public void start(Container container) throws Exception {
    clientEventService.addSubscription(
        AssetPredictedDatapointEvent.class, null, this::onPredictedDatapointsChanged);
    clientEventService.addSubscription(AttributeEvent.class, null, this::onAttributeEvent);

    loadInitialAttributes();
    scheduleNext();
  }

  @Override
  public void stop(Container container) throws Exception {
    synchronized (scheduleLock) {
      if (scheduledFuture != null) {
        scheduledFuture.cancel(true);
        scheduledFuture = null;
      }
      scheduleQueue.clear();
      scheduledEntries.clear();
    }
  }

  protected void onPredictedDatapointsChanged(AssetPredictedDatapointEvent event) {
    rescheduleAttribute(event.getRef());
  }

  protected void onAttributeEvent(AttributeEvent event) {
    if (event.isDeleted()) {
      removeScheduledEntry(event.getRef());
      return;
    }

    // Events are delivered concurrently so their meta can be stale; it is only trusted for
    // attributes that are not
    // scheduled, otherwise the stored attribute is checked
    MetaMap meta = event.getMeta();
    if (meta != null && !hasRequiredMeta(meta) && !isScheduled(event.getRef())) {
      return;
    }

    rescheduleAttribute(event.getRef());
  }

  protected void processDueEntries() {
    List<AttributeRef> dueRefs = new ArrayList<>();
    long now = timerService.getCurrentTimeMillis();

    synchronized (scheduleLock) {
      while (!scheduleQueue.isEmpty()) {
        ScheduledEntry entry = scheduleQueue.peek();
        if (entry.timestamp > now) {
          break;
        }
        scheduleQueue.poll();
        scheduledEntries.remove(entry.ref);
        dueRefs.add(entry.ref);
      }
    }

    dueRefs.forEach(this::rescheduleAttribute);

    scheduleNext();
  }

  /**
   * Based on the next predicted data point to be applied, schedule the next execution of our
   * processing.
   */
  protected void scheduleNext() {
    // Canceling and scheduling must be atomic with reading the queue head, otherwise a concurrent
    // caller can
    // cancel a live schedule and leave a queued entry without one
    synchronized (scheduleLock) {
      if (scheduledFuture != null) {
        scheduledFuture.cancel(false);
        scheduledFuture = null;
      }

      if (!scheduleQueue.isEmpty()) {
        long delayMillis =
            Math.max(0, scheduleQueue.peek().timestamp - timerService.getCurrentTimeMillis());
        scheduledFuture =
            scheduledExecutorService.schedule(
                this::processDueEntries, delayMillis, TimeUnit.MILLISECONDS);
      }
    }
  }

  protected void loadInitialAttributes() {
    LogicGroup<AttributePredicate> group =
        new LogicGroup<>(
            List.of(
                new AttributePredicate()
                    .meta(
                        new NameValuePredicate(
                            HAS_PREDICTED_DATA_POINTS, new BooleanPredicate(true))),
                new AttributePredicate()
                    .meta(
                        new NameValuePredicate(
                            APPLY_PREDICTED_DATA_POINTS, new BooleanPredicate(true)))));
    group.operator = LogicGroup.Operator.AND;

    List<Asset<?>> assets = assetStorageService.findAll(new AssetQuery().attributes(group));
    assets.forEach(
        asset ->
            asset.getAttributes().stream()
                .filter(
                    attr ->
                        hasRequiredMeta(
                            attr.getMeta())) // Required, need to ensure at attribute level that
                // both MetaItem are present
                .forEach(
                    attr ->
                        rescheduleValidatedAttribute(
                            new AttributeRef(asset.getId(), attr.getName()), attr)));
  }

  protected void rescheduleAttribute(AttributeRef ref) {
    Attribute<?> attribute = getAttribute(ref);
    if (attribute == null) {
      removeScheduledEntry(ref);
      return;
    }

    MetaMap meta = attribute.getMeta();
    if (meta == null || !hasRequiredMeta(meta)) {
      removeScheduledEntry(ref);
      return;
    }

    rescheduleValidatedAttribute(ref, attribute);
  }

  protected void rescheduleValidatedAttribute(AttributeRef ref, Attribute<?> attribute) {
    List<ValueDatapoint> datapoints = assetPredictedDatapointService.getDatapoints(ref);
    if (datapoints == null || datapoints.isEmpty()) {
      removeScheduledEntry(ref);
      return;
    }

    long now = timerService.getCurrentTimeMillis();
    ValueDatapoint<?> lastPast = null;
    ValueDatapoint<?> nextFuture = null;

    // We gather the most recent datapoint that is in the past as of now and the earliest one in the
    // future
    for (ValueDatapoint<?> datapoint : datapoints) {
      long ts = datapoint.getTimestamp();
      if (ts <= now) {
        if (lastPast == null || ts > lastPast.getTimestamp()) {
          lastPast = datapoint;
        }
      } else {
        if (nextFuture == null || ts < nextFuture.getTimestamp()) {
          nextFuture = datapoint;
        }
      }
    }

    long currentTimestamp = attribute.getTimestamp().orElse(0L);
    // We apply the most recent past prediction unless the attribute was updated more recently
    if (lastPast != null && currentTimestamp < lastPast.getTimestamp()) {
      assetProcessingService.sendAttributeEvent(
          new AttributeEvent(ref, lastPast.getValue(), lastPast.getTimestamp()),
          getClass().getSimpleName());
    }

    // And schedule the next prediction to apply
    if (nextFuture != null) {
      upsertScheduledEntry(ref, nextFuture.getTimestamp(), nextFuture.getValue());
    } else {
      removeScheduledEntry(ref);
    }
  }

  protected Attribute<?> getAttribute(AttributeRef ref) {
    Asset<?> asset = assetStorageService.find(ref.getId(), true);
    if (asset == null) {
      return null;
    }
    return asset.getAttribute(ref.getName()).orElse(null);
  }

  protected boolean hasRequiredMeta(MetaMap meta) {
    return meta != null
        && meta.has(HAS_PREDICTED_DATA_POINTS)
        && meta.has(APPLY_PREDICTED_DATA_POINTS);
  }

  protected boolean isScheduled(AttributeRef ref) {
    synchronized (scheduleLock) {
      return scheduledEntries.containsKey(ref);
    }
  }

  protected void upsertScheduledEntry(AttributeRef ref, long timestamp, Object value) {
    synchronized (scheduleLock) {
      ScheduledEntry existing = scheduledEntries.remove(ref);
      if (existing != null) {
        scheduleQueue.remove(existing);
      }
      ScheduledEntry entry = new ScheduledEntry(ref, timestamp, value);
      scheduledEntries.put(ref, entry);
      scheduleQueue.add(entry);
    }
    scheduleNext();
  }

  protected void removeScheduledEntry(AttributeRef ref) {
    synchronized (scheduleLock) {
      ScheduledEntry existing = scheduledEntries.remove(ref);
      if (existing != null) {
        scheduleQueue.remove(existing);
      }
    }
    scheduleNext();
  }

  protected record ScheduledEntry(AttributeRef ref, long timestamp, Object value) {}
}
