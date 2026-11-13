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
package org.openremote.test.assets

import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.ApplyPredictedDataPointsService
import org.openremote.manager.datapoint.AssetPredictedDatapointService
import org.openremote.manager.event.ClientEventService
import org.openremote.manager.setup.SetupService
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.model.attribute.MetaMap
import org.openremote.model.datapoint.ValueDatapoint
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import static org.openremote.model.value.MetaItemType.APPLY_PREDICTED_DATA_POINTS
import static org.openremote.model.value.MetaItemType.HAS_PREDICTED_DATA_POINTS

class ApplyPredictedDataPointsServiceTest extends Specification implements ManagerContainerTrait {

  // Delay and future of the last schedule as a single value, so a delay can never be paired with a stale future
  volatile Map<String, ?> scheduled
  ApplyPredictedDataPointsService mockedApplyService
  ScheduledExecutorService originalExecutor

  def cleanup() {
    if (mockedApplyService != null) {
      synchronized (mockedApplyService.scheduleLock) {
        mockedApplyService.scheduledFuture?.cancel(true)
        mockedApplyService.scheduledFuture = null
        mockedApplyService.scheduledEntries.clear()
        mockedApplyService.scheduleQueue.clear()
        mockedApplyService.scheduledExecutorService = originalExecutor
      }
      mockedApplyService = null
    }
  }

  def "Applies predicted datapoints when timestamps match"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "the attribute is created tagged for predicted datapoint application"
    def attributeRef = createTestAttributeWithApplyMeta(assetStorageService, managerTestSetup.thingId, "predictedValue1")

    when: "predicted datapoints are added in the future"
    def predictedDatapoints = [
      new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
      new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
    ]
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            predictedDatapoints
    )

    and: "time advances to the first datapoint"
    awaitScheduled(applyService, attributeRef, conditions)
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    scheduled.future.get()
    then: "the first predicted value should be applied"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 10d
      assert attribute.getTimestamp().orElse(0L) == now + TimeUnit.MINUTES.toMillis(1)
    }

    when: "time advances to the next datapoint"
    awaitScheduled(applyService, attributeRef, conditions)
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    scheduled.future.get()

    then: "the second predicted value should be applied"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 20d
      assert attribute.getTimestamp().orElse(0L) == now + TimeUnit.MINUTES.toMillis(2)
    }
  }

  def "Applies the last past datapoint when current value is older"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "the attribute has a value timestamp at the origin of test"
    def attributeRef = createTestAttribute(assetStorageService, managerTestSetup.thingId, "predictedValue2")
    assetProcessingService.sendAttributeEvent(
            new AttributeEvent(attributeRef, 1d, now)
    )
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 1d
      assert attribute.getTimestamp().orElse(0L) == now
    }

    and: "the attribute is tagged for predicted datapoint application"
    enablePredictedApplyMeta(assetStorageService, attributeRef.getId(), attributeRef.getName())
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      assert asset.getAttribute(attributeRef.getName()).get().getMeta().has(HAS_PREDICTED_DATA_POINTS)
      assert asset.getAttribute(attributeRef.getName()).get().getMeta().has(APPLY_PREDICTED_DATA_POINTS)
    }

    and: "the clock is moved forward"
    advancePseudoClock(3, TimeUnit.MINUTES)
    now = getClockTimeOf(container)

    when: "predicted datapoints are added in the past"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now - TimeUnit.MINUTES.toMillis(1), 5d),
              new ValueDatapoint<>(now - TimeUnit.SECONDS.toMillis(10), 7d)
            ]
    )

    then: "the last predicted value should be applied immediately"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 7d
      assert attribute.getTimestamp().orElse(0L) == now - TimeUnit.SECONDS.toMillis(10)
    }
  }

  def "Skips past datapoints when current value is newer"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "the attribute has a newer value timestamp"
    def attributeRef = createTestAttribute(assetStorageService, managerTestSetup.thingId, "predictedValue3")
    assetProcessingService.sendAttributeEvent(
            new AttributeEvent(attributeRef, 99d, now)
    )
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 99d
      assert attribute.getTimestamp().orElse(0L) == now
    }

    and: "the attribute is tagged for predicted datapoint application"
    enablePredictedApplyMeta(assetStorageService, attributeRef.getId(), attributeRef.getName())
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      assert asset.getAttribute(attributeRef.getName()).get().getMeta().has(HAS_PREDICTED_DATA_POINTS)
      assert asset.getAttribute(attributeRef.getName()).get().getMeta().has(APPLY_PREDICTED_DATA_POINTS)
    }

    when: "predicted datapoints are added in the past"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now - TimeUnit.MINUTES.toMillis(1), 5d),
              new ValueDatapoint<>(now - TimeUnit.SECONDS.toMillis(10), 7d)
            ]
    )

    then: "the current attribute value should remain unchanged"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 99d
      assert attribute.getTimestamp().orElse(0L) == now + 1 // +1 because of merge asset logic
    }
  }

  def "Does not apply values when required meta is missing"() {
    given: "a running container and target attribute without required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "a plain attribute exists without meta"
    def attributeRef = createTestAttribute(assetStorageService, managerTestSetup.thingId, "predictedValue4")

    when: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
            ]
    )

    and: "time advances beyond the datapoints"
    advancePseudoClock(3, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "the attribute value should remain unchanged"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 0d
    }
  }

  def "Does not apply values when only HAS_PREDICTED_DATA_POINTS is set"() {
    given: "a running container and target attribute with only HAS_PREDICTED_DATA_POINTS"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "a plain attribute exists with only HAS_PREDICTED_DATA_POINTS"
    def attributeRef = createTestAttribute(assetStorageService, managerTestSetup.thingId, "predictedValue5")
    addMeta(assetStorageService, attributeRef, new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))

    when: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
            ]
    )

    and: "time advances beyond the datapoints"
    advancePseudoClock(3, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "the attribute value should remain unchanged"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 0d
    }
  }

  def "Does not apply values when only APPLY_PREDICTED_DATA_POINTS is set"() {
    given: "a running container and target attribute with only APPLY_PREDICTED_DATA_POINTS"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "a plain attribute exists with only APPLY_PREDICTED_DATA_POINTS"
    def attributeRef = createTestAttribute(assetStorageService, managerTestSetup.thingId, "predictedValue6")
    addMeta(assetStorageService, attributeRef, new MetaItem<>(APPLY_PREDICTED_DATA_POINTS, true))

    when: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
            ]
    )

    and: "time advances beyond the datapoints"
    advancePseudoClock(3, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "the attribute value should remain unchanged"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 0d
    }
  }

  def "Applies values after adding required meta to existing attribute"() {
    given: "a running container and target attribute without required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def clientEventService = container.getService(ClientEventService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "we capture attribute events"
    List<AttributeEvent> events = new CopyOnWriteArrayList<>()
    Consumer<AttributeEvent> consumer = { event -> events.add(event) }
    clientEventService.addSubscription(AttributeEvent.class, null, consumer)

    and: "a plain attribute exists without meta"
    def attributeRef = createTestAttribute(assetStorageService, managerTestSetup.thingId, "predictedValue7")

    and: "the creation event without meta has been delivered, so it cannot arrive after the meta is added"
    conditions.eventually {
      assert events.any {
        it.ref == attributeRef && !it.getMeta()?.has(APPLY_PREDICTED_DATA_POINTS)
      }
    }

    and: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
            ]
    )

    when: "time advances to the first datapoint"
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    and: "required meta is added"
    enablePredictedApplyMeta(assetStorageService, attributeRef.getId(), attributeRef.getName())

    and: "time advances to the second datapoint"
    awaitScheduled(applyService, attributeRef, conditions)
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    scheduled.future.get()

    then: "the second predicted value should be applied"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 20d
      assert attribute.getTimestamp().orElse(0L) == now + TimeUnit.MINUTES.toMillis(2)
    }
  }

  def "Keeps the schedule when an attribute event carries stale meta"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "an attribute with the required meta is created in a single update"
    def attributeRef = createTestAttributeWithApplyMeta(assetStorageService, managerTestSetup.thingId, "predictedValue11")

    and: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
            ]
    )

    and: "the first datapoint is scheduled"
    awaitScheduled(applyService, attributeRef, conditions)

    when: "an event without the required meta is delivered, as can happen when events arrive out of order"
    applyService.onAttributeEvent(
            new AttributeEvent(attributeRef, 0d, now).setMeta(new MetaMap())
    )

    then: "the schedule should be kept because the stored attribute still has the required meta"
    assert isScheduled(applyService, attributeRef)

    when: "time advances to the first datapoint"
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    scheduled.future.get()

    then: "the first predicted value should still be applied"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 10d
      assert attribute.getTimestamp().orElse(0L) == now + TimeUnit.MINUTES.toMillis(1)
    }
  }

  def "Does not apply values after removing required meta from attribute"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "an attribute with the required meta is created in a single update"
    def attributeRef = createTestAttributeWithApplyMeta(assetStorageService, managerTestSetup.thingId, "predictedValue8")

    and: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d)
            ]
    )

    when: "time advances to the first datapoint"
    awaitScheduled(applyService, attributeRef, conditions)
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    scheduled.future.get()

    then: "the first predicted value should be applied"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 10d
      assert attribute.getTimestamp().orElse(0L) == now + TimeUnit.MINUTES.toMillis(1)
    }

    when: "required meta is removed"
    removeMeta(assetStorageService, attributeRef, HAS_PREDICTED_DATA_POINTS)
    removeMeta(assetStorageService, attributeRef, APPLY_PREDICTED_DATA_POINTS)

    and: "time advances beyond the datapoints"
    advancePseudoClock(2, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "the attribute value should remain unchanged"
    conditions.eventually {
      def asset = assetStorageService.find(attributeRef.getId(), true)
      def attribute = asset.getAttribute(attributeRef.getName()).get()
      assert attribute.getValue(Double.class).orElse(null) == 10d
    }
  }

  def "Does not emit attribute events after attribute deletion"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def clientEventService = container.getService(ClientEventService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "we capture attribute events"
    List<AttributeEvent> events = new CopyOnWriteArrayList<>()
    Consumer<AttributeEvent> consumer = { event -> events.add(event) }
    clientEventService.addSubscription(AttributeEvent.class, null, consumer)

    and: "an attribute with the required meta is created in a single update"
    def attributeRef = createTestAttributeWithApplyMeta(assetStorageService, managerTestSetup.thingId, "predictedValue9")

    and: "the creation event has been delivered to the service before it is observed here"
    conditions.eventually {
      assert events.any {
        it.ref == attributeRef && it.getMeta()?.has(APPLY_PREDICTED_DATA_POINTS)
      }
    }
    events.clear()

    and: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(3), 30d)
            ]
    )

    when: "time advances to the middle of the predicted datapoints"
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "an attribute event should have been emitted for the applied value"
    conditions.eventually {
      assert events.any { it.ref == attributeRef && it.value.orElse(null) == 10d }
    }

    when: "the attribute is deleted"
    def asset = assetStorageService.find(attributeRef.getId())
    asset.getAttributes().remove(attributeRef.getName())
    assetStorageService.merge(asset)

    then: "the deletion event is observed"
    conditions.eventually {
      assert events.any { it.ref == attributeRef && it.deleted }
    }

    when: "we clear events and advance time past all datapoints"
    events.clear()
    advancePseudoClock(3, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "the scheduled prediction for the attribute is canceled"
    conditions.eventually {
      assert !applyService.scheduledEntries.containsKey(attributeRef)
    }

    and: "no further non-deletion attribute events are emitted for the attribute"
    assert !events.any { it.ref == attributeRef && !it.deleted }
  }

  def "Does not emit attribute events after asset deletion"() {
    given: "a running container and target attribute with required meta"
    def container = startContainer(defaultConfig(), defaultServices())
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    def clientEventService = container.getService(ClientEventService.class)
    def applyService = container.getService(ApplyPredictedDataPointsService.class)

    and: "the clock is stopped for deterministic scheduling"
    stopPseudoClock()
    def now = getClockTimeOf(container)

    and: "the scheduled executor is mocked to allow manual triggering"
    setupScheduler(applyService)

    and: "we capture attribute events"
    List<AttributeEvent> events = new CopyOnWriteArrayList<>()
    Consumer<AttributeEvent> consumer = { event -> events.add(event) }
    clientEventService.addSubscription(AttributeEvent.class, null, consumer)

    and: "an attribute with the required meta is created in a single update"
    def attributeRef = createTestAttributeWithApplyMeta(assetStorageService, managerTestSetup.thingId, "predictedValue10")

    and: "the creation event has been delivered to the service before it is observed here"
    conditions.eventually {
      assert events.any {
        it.ref == attributeRef && it.getMeta()?.has(APPLY_PREDICTED_DATA_POINTS)
      }
    }
    events.clear()

    and: "predicted datapoints are added in the future"
    assetPredictedDatapointService.updateValues(
            attributeRef.getId(),
            attributeRef.getName(),
            [
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(1), 10d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(2), 20d),
              new ValueDatapoint<>(now + TimeUnit.MINUTES.toMillis(3), 30d)
            ]
    )

    when: "time advances to the middle of the predicted datapoints"
    advancePseudoClock(1, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "an attribute event should have been emitted for the applied value"
    conditions.eventually {
      assert events.any { it.ref == attributeRef && it.value.orElse(null) == 10d }
    }

    when: "the asset is deleted"
    assetStorageService.delete(List.of(attributeRef.getId()))

    then: "the deletion event is observed"
    conditions.eventually {
      assert events.any { it.ref == attributeRef && it.deleted }
    }

    when: "we clear events and advance time past all datapoints"
    events.clear()
    advancePseudoClock(3, TimeUnit.MINUTES, container)
    if (scheduled != null) {
      scheduled.future.get()
    }

    then: "the scheduled prediction for the attribute is canceled"
    conditions.eventually {
      assert !applyService.scheduledEntries.containsKey(attributeRef)
    }

    and: "no further non-deletion attribute events are emitted for the attribute"
    assert !events.any { it.ref == attributeRef && !it.deleted }
  }

  private static void enablePredictedApplyMeta(AssetStorageService assetStorageService, String assetId, String attributeName) {
    def asset = assetStorageService.find(assetId)
    def attribute = asset.getAttribute(attributeName).get()
    attribute.addOrReplaceMeta(
            new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true),
            new MetaItem<>(APPLY_PREDICTED_DATA_POINTS, true)
    )
    assetStorageService.merge(asset)
  }

  private static AttributeRef createTestAttribute(AssetStorageService assetStorageService, String assetId, String attributeName) {
    def asset = assetStorageService.find(assetId)
    asset.getAttributes().addOrReplace(
            new org.openremote.model.attribute.Attribute<>(attributeName, ValueType.NUMBER, 0d)
    )
    assetStorageService.merge(asset)
    return new AttributeRef(assetId, attributeName)
  }

  private static AttributeRef createTestAttributeWithApplyMeta(AssetStorageService assetStorageService, String assetId, String attributeName) {
    def asset = assetStorageService.find(assetId)
    def attribute = new Attribute<>(attributeName, ValueType.NUMBER, 0d)
    attribute.addOrReplaceMeta(
            new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true),
            new MetaItem<>(APPLY_PREDICTED_DATA_POINTS, true)
    )
    asset.getAttributes().addOrReplace(attribute)
    assetStorageService.merge(asset)
    return new AttributeRef(assetId, attributeName)
  }

  private static void addMeta(AssetStorageService assetStorageService, AttributeRef attributeRef, MetaItem<?> metaItem) {
    def asset = assetStorageService.find(attributeRef.getId())
    def attribute = asset.getAttribute(attributeRef.getName()).get()
    attribute.addOrReplaceMeta(metaItem)
    assetStorageService.merge(asset)
  }

  private static void removeMeta(AssetStorageService assetStorageService, AttributeRef attributeRef, def metaType) {
    def asset = assetStorageService.find(attributeRef.getId())
    def attribute = asset.getAttribute(attributeRef.getName()).get()
    attribute.getMeta().remove(metaType)
    assetStorageService.merge(asset)
  }

  private void setupScheduler(ApplyPredictedDataPointsService applyService) {
    mockedApplyService = applyService
    originalExecutor = applyService.scheduledExecutorService
    def executor = Mock(ScheduledExecutorService)
    scheduled = null

    executor.schedule(_ as Runnable, _ as Long, _ as TimeUnit) >> { args ->
      def scheduledFuture = Mock(ScheduledFuture)
      scheduledFuture.get() >> {
        (args[0] as Runnable).run()
        return true
      }
      // Published only once the future is stubbed, so awaiting a schedule also awaits its future
      scheduled = [delay: args[1] as Long, future: scheduledFuture]
      return scheduledFuture
    }

    synchronized (applyService.scheduleLock) {
      applyService.scheduledFuture?.cancel(true)
      applyService.scheduledFuture = null
      applyService.scheduledExecutorService = executor
    }
  }

  /**
   * The service schedules on a container thread in response to events, so the schedule must be awaited before the
   * clock is advanced; without this the test can resolve a future from an earlier schedule, or none at all.
   */
  private void awaitScheduled(ApplyPredictedDataPointsService applyService, AttributeRef attributeRef, PollingConditions conditions) {
    conditions.eventually {
      assert isScheduled(applyService, attributeRef)
      assert scheduled != null
    }
  }

  private static boolean isScheduled(ApplyPredictedDataPointsService applyService, AttributeRef attributeRef) {
    synchronized (applyService.scheduleLock) {
      return applyService.scheduledEntries.containsKey(attributeRef)
    }
  }
}
