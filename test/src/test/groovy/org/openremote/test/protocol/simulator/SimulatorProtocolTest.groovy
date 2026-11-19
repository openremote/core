/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.test.protocol.simulator

import org.openremote.agent.protocol.simulator.SimulatorAgent
import org.openremote.agent.protocol.simulator.SimulatorAgentLink
import org.openremote.agent.protocol.simulator.SimulatorProtocol
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.datapoint.AssetPredictedDatapointService
import org.openremote.model.Constants
import org.openremote.model.asset.agent.Agent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.model.datapoint.query.AssetDatapointAllQuery
import org.openremote.model.simulator.SimulatorReplayDatapoint
import org.openremote.model.value.ValueType
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import static java.util.concurrent.TimeUnit.DAYS
import static java.util.concurrent.TimeUnit.HOURS
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static org.openremote.model.value.MetaItemType.AGENT_LINK
import static org.openremote.model.value.MetaItemType.HAS_PREDICTED_DATA_POINTS

class SimulatorProtocolTest extends Specification implements ManagerContainerTrait {

  private final static long SECOND_IN_MILLIS = 1000
  private final static long MINUTE_IN_MILLIS = SECOND_IN_MILLIS * 60
  private final static long HOUR_IN_MILLIS = MINUTE_IN_MILLIS * 60
  private final static long DAY_IN_MILLIS = HOUR_IN_MILLIS * 24
  private final static long WEEK_IN_MILLIS = DAY_IN_MILLIS * 7

  private final static long HOUR_IN_SECONDS = 3600

  static final PollingConditions conditions = new PollingConditions(timeout: 10, delay: 0.2)

  @Shared
  AssetStorageService assetStorageService

  @Shared
  AssetDatapointService assetDatapointService

  @Shared
  AssetPredictedDatapointService assetPredictedDatapointService

  @Shared
  AgentService agentService

  @Shared
  SimulatorAgent agent

  ThingAsset asset

  SimulatorProtocol protocol

  ScheduledExecutorService originalExecutor

  // Delay of every schedule, so the schedule of an attribute can be resolved through the protocol's replay map
  final Map<ScheduledFuture<?>, Long> delayByFuture = new ConcurrentHashMap<>()

  def setupSpec() {
    given: "environment is setup"
    startContainer(defaultConfig(), defaultServices())

    // Setup services
    assetStorageService = container.getService(AssetStorageService.class)
    assetDatapointService = container.getService(AssetDatapointService.class)
    assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class)
    agentService = container.getService(AgentService.class)

    // Create and link asset to agent
    agent = new SimulatorAgent("Test agent").setRealm(Constants.MASTER_REALM)
    agent = assetStorageService.merge(agent)

    // Wait until agent is connected before resetting the clock so attribute events are processed correctly
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
    }
  }

  def setup() {
    stopPseudoClock()
    setPseudoClock("1970-01-01T00:00:00.000Z")

    delayByFuture.clear()
    // Mock executor and rely on the delay argument to determine schedule
    def executor = Mock(ScheduledExecutorService)

    asset = new ThingAsset("Test asset").setRealm(Constants.MASTER_REALM)
    asset = assetStorageService.merge(asset)

    // Must use boxed Long type in Spock closures, so avoiding parameter expansion so it doesn't silently fail.
    executor.schedule(_ as Runnable, _ as Long, _ as TimeUnit) >> { args ->
      // Create a fresh future *per invocation*
      def scheduledFuture = Mock(ScheduledFuture)
      scheduledFuture.get() >> {
        (args[0] as Runnable).run()
        return true
      }
      // Recorded before the future is returned, so the protocol cannot publish it before its delay is known
      delayByFuture.put(scheduledFuture, args[1] as Long)
      return scheduledFuture
    }

    protocol = (SimulatorProtocol) agentService.protocolInstanceMap.get(agent.getId())
    originalExecutor = protocol.scheduledExecutorService
    protocol.scheduledExecutorService = executor
  }

  def cleanup() {
    assetStorageService.delete(List.of(asset.getId()))
    conditions.eventually {
      protocol.linkedAttributes.size() == 0
    }
    // The mock only lives as long as the feature that created it, so the protocol must not keep it afterwards
    protocol.scheduledExecutorService = originalExecutor
  }

  private getDataPoints = {
    def now = Instant
            .ofEpochMilli(getClockTimeOf(container)).atZone(ZoneId.of("UTC")).toLocalDateTime()
    new AssetDatapointAllQuery(
            now.minus(1, ChronoUnit.HOURS),
            now.plus(1, ChronoUnit.HOURS),
    )
  }

  /**
   * The delay and future the protocol currently has scheduled for an attribute, or null while it has none. The
   * executor is not told which attribute it schedules for, so the future is matched through the protocol's replay map.
   */
  private Map<String, ?> scheduledFor(AttributeRef attributeRef) {
    def future = protocol.replayMap.get(attributeRef)
    def delay = future != null ? delayByFuture.get(future) : null
    return delay != null ? [delay: delay, future: future] : null
  }

  /**
   * Advances the clock by the delay the protocol scheduled for an attribute and runs its replay, so a test can step
   * from datapoint to datapoint without knowing the offset of the configured timezone.
   */
  private void fastForwardToScheduled(AttributeRef attributeRef) {
    def scheduled = scheduledFor(attributeRef)
    assert scheduled != null
    advancePseudoClock(scheduled.delay as long, MILLISECONDS, container)
    scheduled.future.get() // resolve future manually, because we surpassed the delay
  }

  private long getDatapointTimestamp(Attribute attribute) {
    def datapoints = assetDatapointService.queryDatapoints(
            asset.getId(),
            attribute.getName(),
            getDataPoints.call()
    )
    assert !datapoints.isEmpty()
    return datapoints.get(0).getTimestamp()
  }

  def "Check Simulator Agent protocol without replay"() {
    when: "nothing is configured"
    asset.addOrReplaceAttributes(new Attribute<>("test1", ValueType.TEXT)
            .addMeta(new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId())))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test1").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "has schedule"
    def schedule = agent.getAgentLink(attribute).schedule
    schedule != null

    and: "with a 1 day period"
    def start = schedule.getStart()
    schedule.getEnd() == start.plusDays(1)

    and: "nothing happens"
    assetDatapointService.getDatapoints(attributeRef).size() == 0
  }

  def "Check Simulator Agent protocol follows replay data end"() {
    when: "nothing is configured"
    def replayData = new SimulatorReplayDatapoint[] {
              new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
              new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 23, "test"),
              new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 12, "test")
            }
    asset.addOrReplaceAttributes(new Attribute<>("test1", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId(), replayData, null, null))
            ))
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test1").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "has schedule"
    def schedule = agent.getAgentLink(attribute).schedule
    schedule != null

    and: "with a 23 hour period"
    def start = schedule.getStart()
    schedule.getEnd() == start.plusHours(23)
  }

  def "Check Simulator Agent protocol with replay"() {
    when: "replayData is configured to add a datapoint in 1hr, 12hrs, and 24hrs every day"
    asset.addOrReplaceAttributes(new Attribute<>("test2", ValueType.TEXT)
            .addMeta(new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                    new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                    new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 12, "test"),
                    new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 24, "test")
                    ).setSchedule(new SimulatorProtocol.Schedule(
                    // We manually set the schedule so it starts when we want, as AgentLinks don't have access to the timer service
                    LocalDateTime.ofInstant(Instant.parse("1970-01-01T00:00:00.000Z"), ZoneOffset.UTC), null, "FREQ=DAILY"
                    ))))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test2").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    (0..1).each { i ->
      def days = i * HOUR_IN_MILLIS * 24 // Starts at 0 days, increments with 1 days

      then: "the delay is 1 hour"
      conditions.eventually {
        scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS
      }

      when: "fast forward 1 hour"
      advancePseudoClock(1, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoint is present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == days + HOUR_IN_MILLIS
      }

      and: "the delay is 11 hours"
      conditions.eventually {
        scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 11
      }

      when: "fast forward 11 hours"
      advancePseudoClock(11, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoint is present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == days + HOUR_IN_MILLIS * 12
      }

      and: "the delay is 12 hours"
      conditions.eventually {
        scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 12
      }

      when: "fast forward 12 hour"
      advancePseudoClock(12, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoints are present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == days + HOUR_IN_MILLIS * 24
      }
    }
  }

  def "Check Simulator Agent protocol replays multiple attributes independently"() {
    when: "one attribute replays daily from the epoch and another one once from the next day"
    asset.addOrReplaceAttributes(
            new Attribute<>("test8", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "test")
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-01-01T00:00:00.000Z"), ZoneOffset.UTC), null, "FREQ=DAILY"
                            )))
                    ),
            new Attribute<>("test9", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 25, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 49, "test")
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-01-02T00:00:00.000Z"), ZoneOffset.UTC), null, null
                            )))
                    )
    )
    asset = assetStorageService.merge(asset)
    def attribute1 = asset.getAttribute("test8").get()
    def attribute2 = asset.getAttribute("test9").get()
    def attributeRef1 = new AttributeRef(asset.getId(), attribute1.getName())
    def attributeRef2 = new AttributeRef(asset.getId(), attribute2.getName())

    then: "both attributes should be linked to the protocol"
    conditions.eventually {
      protocol.linkedAttributes.get(attributeRef1) == attribute1
      protocol.linkedAttributes.get(attributeRef2) == attribute2
    }

    and: "each attribute has its own delay"
    conditions.eventually {
      scheduledFor(attributeRef1)?.delay == HOUR_IN_MILLIS
      scheduledFor(attributeRef2)?.delay == DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    when: "fast forward 1 hour and only the first attribute is resolved"
    advancePseudoClock(1, HOURS, container)
    scheduledFor(attributeRef1).future.get()

    then: "only the first attribute has a datapoint"
    conditions.eventually {
      getDatapointTimestamp(attribute1) == HOUR_IN_MILLIS
    }

    and: "the second attribute is untouched"
    assetDatapointService.getDatapoints(attributeRef2).size() == 0

    and: "the first attribute is scheduled for its second datapoint and the second attribute is unchanged"
    conditions.eventually {
      scheduledFor(attributeRef1)?.delay == HOUR_IN_MILLIS
      scheduledFor(attributeRef2)?.delay == DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    when: "fast forward to the second attribute's first datapoint and only that attribute is resolved"
    advancePseudoClock(1, DAYS, container)
    scheduledFor(attributeRef2).future.get()

    then: "the second attribute has a datapoint"
    conditions.eventually {
      getDatapointTimestamp(attribute2) == DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    and: "the first attribute still has only its own datapoint"
    assetDatapointService.getDatapoints(attributeRef1).size() == 1
  }

  def "Check Simulator Agent protocol with replay startDate"() {
    when: "replayData is configured to replay in 1day and 1hr"
    asset.addOrReplaceAttributes(new Attribute<>("test3", ValueType.TEXT)
            .addMeta(new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                    new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                    new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 25, "test"),
                    new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 49, "test")
                    ).setSchedule(new SimulatorProtocol.Schedule(
                    LocalDateTime.ofInstant(Instant.parse("1970-01-02T00:00:00.000Z"), ZoneOffset.UTC), null, null
                    ))
    )))
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test3").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay is 25 hours"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    when: "fast forward 1 day and 1 hour"
    advancePseudoClock(1, DAYS, container)
    advancePseudoClock(1, HOURS, container)
    scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

    then: "datapoint is present"
    conditions.eventually {
      getDatapointTimestamp(attribute) == DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    // Starting at 2 days, because start date + first datapoint = 2 days since epoch
    (2..3).each { i ->
      def days = i * DAY_IN_MILLIS // Starts at 2 days, increments with 1 day

      and: "the delay is 24 hours"
      conditions.eventually {
        scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 24
      }

      when: "fast forward 24 hours"
      advancePseudoClock(24, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoint is present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == days + HOUR_IN_MILLIS
      }
    }

    and: "attribute is removed from replay map"
    conditions.eventually {
      protocol.replayMap.get(attributeRef) == null
    }
  }

  def "Check Simulator Agent protocol with custom recurrence schedule"() {
    when: "replayData is configured to add a datapoint in 1hr, and 2hrs weekly on Mondays"
    asset.addOrReplaceAttributes(new Attribute<>("test5", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "test")
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            // First day should be 1970-01-05 (in 4 days) a Monday
                            LocalDateTime.ofInstant(Instant.parse("1970-01-05T00:00:00.000Z"), ZoneOffset.UTC),
                            null,
                            // Recur every Monday until 1970-01-31
                            "FREQ=WEEKLY;UNTIL=19700131T000000;BYDAY=MO"
                            ))
            )))
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test5").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    then: "the delay is 4 days and 1 hour"
    conditions.eventually {
      Instant.ofEpochMilli(getClockTimeOf(container)) == Instant.parse("1970-01-01T00:00:00.000Z")
      scheduledFor(attributeRef)?.delay == 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    when: "fast forward 4 days and 1 hour"
    advancePseudoClock(4, DAYS, container)
    advancePseudoClock(1, HOURS, container)
    scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

    then: "datapoint is present"
    conditions.eventually {
      getDatapointTimestamp(attribute) == 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    (0..2).each { i ->
      def weeks = i * WEEK_IN_MILLIS // Starts at 0 weeks, increments with 1 week
      def firstWeekOffset = 4 * DAY_IN_MILLIS

      and: "the delay is 1 hour"
      conditions.eventually {
        Instant.ofEpochMilli(getClockTimeOf(container)) == Instant.parse("1970-01-05T01:00:00.000Z").plusMillis(weeks)
        scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS
      }

      when: "fast forward 1 hour"
      advancePseudoClock(1, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoint is present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == firstWeekOffset + weeks + 2 * HOUR_IN_MILLIS
      }

      and: "the delay is 1 week and -1 hour"
      conditions.eventually {
        Instant.ofEpochMilli(getClockTimeOf(container)) == Instant.parse("1970-01-05T02:00:00.000Z").plusMillis(weeks)
        scheduledFor(attributeRef)?.delay == WEEK_IN_MILLIS - HOUR_IN_MILLIS
      }

      when: "fast forward 1 week and -1 hour"
      advancePseudoClock(7, DAYS, container)
      advancePseudoClock(-1, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoint is present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == firstWeekOffset + WEEK_IN_MILLIS + weeks + HOUR_IN_MILLIS
      }
    }

    when: "fast forward past the end date"
    scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

    then: "the attributeRef should be present"
    conditions.eventually {
      Instant.ofEpochMilli(getClockTimeOf(container)) == Instant.parse("1970-01-26T01:00:00Z") // Last monday
      protocol.replayMap.get(attributeRef) != null
    }

    when: "fast forward 5 days and -1 hour"
    advancePseudoClock(5, DAYS, container)
    advancePseudoClock(-1, HOURS, container)
    scheduledFor(attributeRef).future.get() // resolve future manually

    then: "the attributeRef is removed from the replayMap"
    conditions.eventually {
      Instant.ofEpochMilli(getClockTimeOf(container)) == Instant.parse("1970-01-31T00:00:00Z") // Ends here
      protocol.replayMap.get(attributeRef) == null
    }
  }

  def "Check Simulator Agent protocol adds predicted datapoints for the current and next occurrence"() {
    when: "replayData is configured to add a datapoint in 1 and 2 hours every day"
    asset.addOrReplaceAttributes(new Attribute<>("test6", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            // We manually set the schedule so it starts when we want, as AgentLinks don't have access to the timer service
                            LocalDateTime.ofInstant(Instant.parse("1970-01-01T00:00:00.000Z"), ZoneOffset.UTC), null, "FREQ=DAILY"
                            ))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test6").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    (1..2).each { i ->
      and: "the delay is 1 hour"
      conditions.eventually {
        scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS
      }

      and: "the predicted datapoints are present"
      conditions.eventually {
        def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
        datapoints.size() == 4
        datapoints.get(3).getTimestamp() == HOUR_IN_MILLIS * 1
        datapoints.get(2).getTimestamp() == HOUR_IN_MILLIS * 2
        datapoints.get(1).getTimestamp() == HOUR_IN_MILLIS * 25
        datapoints.get(0).getTimestamp() == HOUR_IN_MILLIS * 26
      }

      when: "fast forward 1 hour"
      advancePseudoClock(1, HOURS, container)
      scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

      then: "datapoint is present"
      conditions.eventually {
        getDatapointTimestamp(attribute) == i * HOUR_IN_MILLIS
      }
    }

    and: "the predicted datapoints are present"
    def datapoints1 = assetPredictedDatapointService.getDatapoints(attributeRef)
    datapoints1.size() == 3
    datapoints1.get(2).getTimestamp() == HOUR_IN_MILLIS * 2 // Still present because condition is before (time is equal)
    datapoints1.get(1).getTimestamp() == HOUR_IN_MILLIS * 25
    datapoints1.get(0).getTimestamp() == HOUR_IN_MILLIS * 26

    and: "the delay is 1 day and -1 hour"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == DAY_IN_MILLIS - HOUR_IN_MILLIS
    }

    when: "fast forward 1 day and 1 hour"
    advancePseudoClock(1, DAYS, container)
    advancePseudoClock(-1, HOURS, container)
    scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

    then: "datapoint is present"
    conditions.eventually {
      getDatapointTimestamp(attribute) == DAY_IN_MILLIS + HOUR_IN_MILLIS
    }

    and: "the predicted datapoints are present"
    def datapoints2 = assetPredictedDatapointService.getDatapoints(attributeRef)
    datapoints2.size() == 2
    datapoints2.get(1).getTimestamp() == HOUR_IN_MILLIS * 25
    datapoints2.get(0).getTimestamp() == HOUR_IN_MILLIS * 26

    when: "reset agentLink"
    attribute.addOrReplaceMeta(
            new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId())),
    )
    assetStorageService.merge(asset)

    then: "purge datapoints"
    conditions.eventually {
      assetPredictedDatapointService.getDatapoints(attributeRef).size() == 0
    }
  }

  def "Check Simulator Agent protocol adds predicted datapoints for one-time occurrence"() {
    when: "replayData is configured to add a datapoint in 1 and 2 hours"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-01-01T00:00:00.000Z"), ZoneOffset.UTC), null, null
                            ))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay is 1 hour"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS
    }

    and: "the predicted datapoints are present"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.get(1).getTimestamp() == HOUR_IN_MILLIS * 1
      datapoints.get(0).getTimestamp() == HOUR_IN_MILLIS * 2
    }

    when: "fast forward 1 hour"
    advancePseudoClock(1, HOURS, container)
    scheduledFor(attributeRef).future.get() // resolve future manually, because we surpassed the delay

    then: "the predicted datapoints are present"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.get(1).getTimestamp() == HOUR_IN_MILLIS * 1
      datapoints.get(0).getTimestamp() == HOUR_IN_MILLIS * 2
    }
  }

  def "Check Simulator Agent protocol adds predicted datapoints for one-time occurrence in #timezone"() {
    when: "replayData is configured to add a datapoint at 01:00 and 02:00 on the 2nd, local time in #timezone"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-01-02T00:00:00.000Z"), ZoneOffset.UTC), null, null
                            )).setTimezone(TimeZone.getTimeZone(timezone))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay counts down from the epoch to the first datapoint"
    conditions.eventually {
      // The offset was applied to the start as well, delaying every one-time occurrence by twice the offset
      scheduledFor(attributeRef)?.delay == first
    }

    and: "the predicted datapoints are the UTC instants of 01:00 and 02:00 local time"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 2
      datapoints.get(1).getTimestamp() == first
      datapoints.get(0).getTimestamp() == second
    }

    when: "fast forward to the first datapoint"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 1 hour"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS
    }

    and: "the predicted datapoints are present"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 2
      datapoints.get(1).getTimestamp() == first
      datapoints.get(0).getTimestamp() == second
    }

    when: "fast forward to the second datapoint"
    fastForwardToScheduled(attributeRef)

    then: "nothing is scheduled anymore because the occurrence ended"
    conditions.eventually {
      scheduledFor(attributeRef) == null
    }

    and: "the last predicted datapoint is present"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      // Purging on the local time cut off an extra offset of datapoints, including this one
      datapoints.size() == 1
      datapoints.get(0).getTimestamp() == second
    }

    where: "the timezone shifts the UTC instants of 01:00 and 02:00 local time on the 2nd"
    // spotless:off
    timezone           || first                                                      | second
    "Europe/Amsterdam" || DAY_IN_MILLIS                                              | DAY_IN_MILLIS + HOUR_IN_MILLIS
    "America/New_York" || DAY_IN_MILLIS + HOUR_IN_MILLIS * 6                         | DAY_IN_MILLIS + HOUR_IN_MILLIS * 7
    "Asia/Kolkata"     || DAY_IN_MILLIS - HOUR_IN_MILLIS * 4 - MINUTE_IN_MILLIS * 30 | DAY_IN_MILLIS - HOUR_IN_MILLIS * 3 - MINUTE_IN_MILLIS * 30
    // spotless:on
  }

  def "Check Simulator Agent protocol adds predicted datapoints recurringly up to the UNTIL of the recurrence"() {
    when: "replayData is configured to add a datapoint in 1, 2, and 3 hours and cutoff at 4 hours"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 3, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 4, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-01-01T00:00:00.000Z"), ZoneOffset.UTC),
                            null,
                            "FREQ=DAILY;UNTIL=19700101T030000"
                            ))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay is 1 hour"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS
    }

    and: "the predicted datapoints are present"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 3
      datapoints.get(2).getTimestamp() == HOUR_IN_MILLIS * 1
      datapoints.get(1).getTimestamp() == HOUR_IN_MILLIS * 2
      datapoints.get(0).getTimestamp() == HOUR_IN_MILLIS * 3
    }
  }

  def "Check Simulator Agent protocol adds predicted datapoints recurringly in #timezone"() {
    when: "the clock is at noon UTC on the 3rd, halfway into the daily occurrence of every timezone"
    advancePseudoClock(DAY_IN_MILLIS * 2 + HOUR_IN_MILLIS * 12, MILLISECONDS, container)

    and: "replayData is configured to add a datapoint at 18:00 and 20:00 every day until the 4th, local time in #timezone"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 18, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 20, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-01-01T00:00:00.000Z"), ZoneOffset.UTC),
                            null,
                            "FREQ=DAILY;UNTIL=19700104T200000"
                            )).setTimezone(TimeZone.getTimeZone(timezone))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay counts down to 18:00 local time on the 3rd"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == firstDelay
    }

    and: "the predicted datapoints of the current and upcoming occurrence are the UTC instants of local time"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 4
      datapoints.get(3).getTimestamp() == first // 18:00 on the 3rd
      datapoints.get(2).getTimestamp() == second // 20:00 on the 3rd
      datapoints.get(1).getTimestamp() == first + DAY_IN_MILLIS // 18:00 on the 4th
      datapoints.get(0).getTimestamp() == second + DAY_IN_MILLIS // 20:00 on the 4th
    }

    when: "fast forward to 18:00 on the 3rd"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 2 hours"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 2
    }

    and: "the predicted datapoints are present"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 4
      datapoints.get(3).getTimestamp() == first
      datapoints.get(2).getTimestamp() == second
      datapoints.get(1).getTimestamp() == first + DAY_IN_MILLIS
      datapoints.get(0).getTimestamp() == second + DAY_IN_MILLIS
    }

    when: "fast forward to 20:00 on the 3rd"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 22 hours, until the occurrence of the 4th"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 22
    }

    and: "the predicted datapoint of 18:00 on the 3rd has been replayed and purged"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 3
      datapoints.get(2).getTimestamp() == second
      datapoints.get(1).getTimestamp() == first + DAY_IN_MILLIS
      datapoints.get(0).getTimestamp() == second + DAY_IN_MILLIS
    }

    when: "fast forward to 18:00 on the 4th"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 2 hours"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 2
    }

    and: "the predicted datapoints of the 3rd have been replayed and purged"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 2
      datapoints.get(1).getTimestamp() == first + DAY_IN_MILLIS
      datapoints.get(0).getTimestamp() == second + DAY_IN_MILLIS
    }

    when: "fast forward to 20:00 on the 4th"
    fastForwardToScheduled(attributeRef)

    then: "nothing is scheduled anymore because the recurrence ended"
    conditions.eventually {
      scheduledFor(attributeRef) == null
    }

    and: "only the predicted datapoint of the moment the recurrence ended is left"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      // Purging on the local time cut off an extra offset of datapoints, including this one
      datapoints.size() == 1
      datapoints.get(0).getTimestamp() == second + DAY_IN_MILLIS
    }

    where: "the timezone shifts the UTC instants of 18:00 and 20:00 local time on the 3rd"
    // spotless:off
    timezone           || firstDelay            | first                                                           | second
    "Europe/Amsterdam" || HOUR_IN_MILLIS * 5    | DAY_IN_MILLIS * 2 + HOUR_IN_MILLIS * 17                         | DAY_IN_MILLIS * 2 + HOUR_IN_MILLIS * 19
    "America/New_York" || HOUR_IN_MILLIS * 11   | DAY_IN_MILLIS * 2 + HOUR_IN_MILLIS * 23                         | DAY_IN_MILLIS * 3 + HOUR_IN_MILLIS
    "Asia/Kolkata"     || MINUTE_IN_MILLIS * 30 | DAY_IN_MILLIS * 2 + HOUR_IN_MILLIS * 12 + MINUTE_IN_MILLIS * 30 | DAY_IN_MILLIS * 2 + HOUR_IN_MILLIS * 14 + MINUTE_IN_MILLIS * 30
    // spotless:on
  }

  def "Check Simulator Agent protocol adds predicted datapoints recurringly over the 26th of April 1970 in #timezone"() {
    when: "the clock is on the 25th, the day before America/New_York moves from EST to EDT"
    setPseudoClock("1970-04-25T12:00:00.000Z")

    and: "replayData is configured to add a datapoint at 18:00 and 20:00 every day, local time in #timezone"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 18, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 20, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("1970-04-01T00:00:00.000Z"), ZoneOffset.UTC),
                            null,
                            "FREQ=DAILY"
                            )).setTimezone(TimeZone.getTimeZone(timezone))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay counts down to 18:00 local time on the 25th"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == firstDelay
    }

    and: "the predicted datapoints are the UTC instants of 18:00 and 20:00 local time on the 25th and 26th"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 4
      datapoints.get(3).getTimestamp() == instant(first)
      datapoints.get(2).getTimestamp() == instant(second)
      // The offset of the moment of scheduling was applied, dragging the day of a transition along
      datapoints.get(1).getTimestamp() == instant(third)
      datapoints.get(0).getTimestamp() == instant(fourth)
    }

    when: "fast forward to 18:00 on the 25th"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 2 hours"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 2
    }

    when: "fast forward to 20:00 on the 25th"
    fastForwardToScheduled(attributeRef)

    then: "the delay counts down to 18:00 local time on the 26th"
    conditions.eventually {
      // The local hours were delayed as if they were real hours, so a day losing one replayed late
      scheduledFor(attributeRef)?.delay == nextDayDelay
    }

    when: "fast forward to the 26th"
    fastForwardToScheduled(attributeRef)

    then: "the replay lands on 18:00 local time on the 26th"
    conditions.eventually {
      getClockTimeOf(container) == instant(third)
    }

    and: "the predicted datapoints of the 25th have been replayed and purged"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 2
      datapoints.get(1).getTimestamp() == instant(third)
      datapoints.get(0).getTimestamp() == instant(fourth)
    }

    where: "only New York changes its offset on the 26th, shortening its local day to 23 hours"
    // spotless:off
    timezone           || firstDelay            | nextDayDelay          | first                   | second                  | third                   | fourth
    "America/New_York" || HOUR_IN_MILLIS * 11   | HOUR_IN_MILLIS * 21   | "1970-04-25T23:00:00Z"  | "1970-04-26T01:00:00Z"  | "1970-04-26T22:00:00Z"  | "1970-04-27T00:00:00Z"
    // Amsterdam did not shift its clocks between 1946 and 1977
    "Europe/Amsterdam" || HOUR_IN_MILLIS * 5    | HOUR_IN_MILLIS * 22   | "1970-04-25T17:00:00Z"  | "1970-04-25T19:00:00Z"  | "1970-04-26T17:00:00Z"  | "1970-04-26T19:00:00Z"
    // Kolkata has not shifted its clocks since 1945
    "Asia/Kolkata"     || MINUTE_IN_MILLIS * 30 | HOUR_IN_MILLIS * 22   | "1970-04-25T12:30:00Z"  | "1970-04-25T14:30:00Z"  | "1970-04-26T12:30:00Z"  | "1970-04-26T14:30:00Z"
    // spotless:on
  }

  def "Check Simulator Agent protocol replays from the #pass pass of the hour Europe/Amsterdam repeats on the 25th of October 2026"() {
    when: "the clock is at 02:10 local time in the #pass pass of the hour repeated when Europe/Amsterdam moves from CEST to CET"
    setPseudoClock(clock)

    and: "replayData is configured to add a datapoint at 02:30 and 03:30 every day, local time in Europe/Amsterdam"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2 + 1800, "test"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 3 + 1800, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("2026-10-01T00:00:00.000Z"), ZoneOffset.UTC),
                            null,
                            "FREQ=DAILY"
                            )).setTimezone(TimeZone.getTimeZone("Europe/Amsterdam"))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay counts down to the 02:30 local time that is still ahead"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == MINUTE_IN_MILLIS * 20
    }

    and: "the predicted datapoints are the UTC instants of 02:30 and 03:30 local time on the 25th and 26th"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 4
      datapoints.get(3).getTimestamp() == instant(first)
      datapoints.get(2).getTimestamp() == instant("2026-10-25T02:30:00Z")
      datapoints.get(1).getTimestamp() == instant("2026-10-26T01:30:00Z")
      datapoints.get(0).getTimestamp() == instant("2026-10-26T02:30:00Z")
    }

    when: "fast forward to 02:30 on the 25th"
    fastForwardToScheduled(attributeRef)

    then: "the replay lands on the 02:30 local time that was ahead"
    conditions.eventually {
      getClockTimeOf(container) == instant(first)
    }

    and: "the delay counts down to 03:30 local time on the 25th"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == nextDelay
    }

    where: "02:30 local time happens at 00:30 UTC in CEST and again at 01:30 UTC in CET"
    // spotless:off
    pass     || clock                      | first                  | nextDelay
    // The repeat of 02:00 to 03:00 is skipped once its first pass has been replayed
    "first"  || "2026-10-25T00:10:00.000Z" | "2026-10-25T00:30:00Z" | HOUR_IN_MILLIS * 2
    "second" || "2026-10-25T01:10:00.000Z" | "2026-10-25T01:30:00Z" | HOUR_IN_MILLIS
    // spotless:on
  }

  def "Check Simulator Agent protocol replays an occurrence starting in the hour Europe/Amsterdam skips on the 29th of March 2026"() {
    when: "the clock is at 03:10 local time, after Europe/Amsterdam moved from 02:00 CET to 03:00 CEST"
    setPseudoClock("2026-03-29T01:10:00.000Z")

    and: "replayData is configured to add a datapoint 0, 10 and 40 minutes into an occurrence at 02:30 every day"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(0, "test"),
                            new SimulatorReplayDatapoint(600, "test"),
                            new SimulatorReplayDatapoint(2400, "test"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("2026-03-01T02:30:00.000Z"), ZoneOffset.UTC),
                            null,
                            "FREQ=DAILY"
                            )).setTimezone(TimeZone.getTimeZone("Europe/Amsterdam"))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay counts down to 03:30 local time, where the clocks landed on the skipped 02:30"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == MINUTE_IN_MILLIS * 20
    }

    and: "the predicted datapoints follow 03:30 local time on the 29th and 02:30 local time on the 30th"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 6
      datapoints.get(5).getTimestamp() == instant("2026-03-29T01:30:00Z")
      datapoints.get(4).getTimestamp() == instant("2026-03-29T01:40:00Z")
      datapoints.get(3).getTimestamp() == instant("2026-03-29T02:10:00Z")
      datapoints.get(2).getTimestamp() == instant("2026-03-30T00:30:00Z")
      datapoints.get(1).getTimestamp() == instant("2026-03-30T00:40:00Z")
      datapoints.get(0).getTimestamp() == instant("2026-03-30T01:10:00Z")
    }

    when: "fast forward to 03:30 on the 29th"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 10 minutes"
    conditions.eventually {
      getClockTimeOf(container) == instant("2026-03-29T01:30:00Z")
      scheduledFor(attributeRef)?.delay == MINUTE_IN_MILLIS * 10
    }

    when: "fast forward to 03:40 on the 29th"
    fastForwardToScheduled(attributeRef)

    then: "the delay is 30 minutes"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == MINUTE_IN_MILLIS * 30
    }

    when: "fast forward to 04:10 on the 29th"
    fastForwardToScheduled(attributeRef)

    then: "the delay counts down to 02:30 local time on the 30th"
    conditions.eventually {
      getClockTimeOf(container) == instant("2026-03-29T02:10:00Z")
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 22 + MINUTE_IN_MILLIS * 20
    }
  }

  def "Check Simulator Agent protocol replays datapoints in the hour Europe/Amsterdam skips on the 29th of March 2026"() {
    when: "the clock is at 01:30 local time, before Europe/Amsterdam moves from 02:00 CET to 03:00 CEST"
    setPseudoClock("2026-03-29T00:30:00.000Z")

    and: "replayData is configured to add a datapoint at 02:00, 02:30, 03:00 and 03:10 every day"
    asset.addOrReplaceAttributes(new Attribute<>("test7", ValueType.TEXT).addMeta(
                    new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agent.getId()).setReplayData(
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2, "02:00"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 2 + 1800, "02:30"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 3, "03:00"),
                            new SimulatorReplayDatapoint(HOUR_IN_SECONDS * 3 + 600, "03:10"),
                            ).setSchedule(new SimulatorProtocol.Schedule(
                            LocalDateTime.ofInstant(Instant.parse("2026-03-01T00:00:00.000Z"), ZoneOffset.UTC),
                            null,
                            "FREQ=DAILY"
                            )).setTimezone(TimeZone.getTimeZone("Europe/Amsterdam"))),
                    new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true))
    )
    asset = assetStorageService.merge(asset)
    def attribute = asset.getAttribute("test7").get()
    def attributeRef = new AttributeRef(asset.getId(), attribute.getName())

    then: "the agent status should become CONNECTED and the attribute linked to the protocol"
    conditions.eventually {
      assetStorageService.find(agent.getId(), Agent.class).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      protocol.linkedAttributes.get(attributeRef) == attribute
    }

    and: "the delay counts down to 03:00 local time, where the clocks land"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == MINUTE_IN_MILLIS * 30
    }

    and: "the datapoints of the skipped hour are predicted once at 03:00, with the value that holds there"
    conditions.eventually {
      def datapoints = assetPredictedDatapointService.getDatapoints(attributeRef)
      datapoints.size() == 6
      datapoints.get(5).getTimestamp() == instant("2026-03-29T01:00:00Z")
      datapoints.get(5).getValue() == "03:00"
      datapoints.get(4).getTimestamp() == instant("2026-03-29T01:10:00Z")
      datapoints.get(4).getValue() == "03:10"
      datapoints.get(3).getTimestamp() == instant("2026-03-30T00:00:00Z")
      datapoints.get(3).getValue() == "02:00"
      datapoints.get(2).getTimestamp() == instant("2026-03-30T00:30:00Z")
      datapoints.get(1).getTimestamp() == instant("2026-03-30T01:00:00Z")
      datapoints.get(0).getTimestamp() == instant("2026-03-30T01:10:00Z")
    }

    when: "fast forward to 03:00 on the 29th"
    fastForwardToScheduled(attributeRef)

    then: "the value of 03:00 is replayed"
    conditions.eventually {
      getClockTimeOf(container) == instant("2026-03-29T01:00:00Z")
      assetStorageService.find(asset.getId(), true).getAttribute("test7").flatMap {
        it.getValue()
      }.orElse(null) == "03:00"
    }

    and: "the delay is 10 minutes"
    conditions.eventually {
      scheduledFor(attributeRef)?.delay == MINUTE_IN_MILLIS * 10
    }

    when: "fast forward to 03:10 on the 29th"
    fastForwardToScheduled(attributeRef)

    then: "the delay counts down to 02:00 local time on the 30th"
    conditions.eventually {
      assetStorageService.find(asset.getId(), true).getAttribute("test7").flatMap {
        it.getValue()
      }.orElse(null) == "03:10"
      scheduledFor(attributeRef)?.delay == HOUR_IN_MILLIS * 22 + MINUTE_IN_MILLIS * 50
    }
  }

  private static long instant(String value) {
    Instant.parse(value).toEpochMilli()
  }
}
