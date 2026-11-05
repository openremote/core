/*
 * Copyright 2016, OpenRemote Inc.
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
package org.openremote.agent.protocol.simulator;

import static org.openremote.model.syslog.SyslogCategory.PROTOCOL;
import static org.openremote.model.value.MetaItemType.HAS_PREDICTED_DATA_POINTS;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.io.Serializable;
import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.fortuna.ical4j.model.Recur;
import org.openremote.agent.protocol.AbstractProtocol;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.calendar.CalendarEvent;
import org.openremote.model.datapoint.ValueDatapoint;
import org.openremote.model.simulator.SimulatorReplayDatapoint;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.JSONSchemaUtil.*;
import org.openremote.model.value.AbstractNameValueHolder;

public class SimulatorProtocol extends AbstractProtocol<SimulatorAgent, SimulatorAgentLink> {

  private static final Logger LOG = SyslogCategory.getLogger(PROTOCOL, SimulatorProtocol.class);
  public static final String PROTOCOL_DISPLAY_NAME = "Simulator";

  protected final Map<AttributeRef, ScheduledFuture<?>> replayMap = new ConcurrentHashMap<>();
  protected final Map<AttributeRef, PredictedDatapointWindow> predictedDatapointWindowMap =
      new ConcurrentHashMap<>();

  public SimulatorProtocol(SimulatorAgent agent) {
    super(agent);
  }

  @Override
  public String getProtocolName() {
    return PROTOCOL_DISPLAY_NAME;
  }

  @Override
  public String getProtocolInstanceUri() {
    return "simulator://" + agent.getId();
  }

  @Override
  protected void doStart(Container container) throws Exception {
    setConnectionStatus(ConnectionStatus.CONNECTED);
  }

  @Override
  protected void doStop(Container container) throws Exception {
    replayMap
        .values()
        .forEach(
            scheduledFuture -> {
              try {
                scheduledFuture.cancel(true);
              } catch (Exception ignored) {

              }
            });
  }

  @Override
  protected void doLinkAttribute(
      String assetId, Attribute<?> attribute, SimulatorAgentLink agentLink) {

    // Look for replay data
    agentLink
        .getReplayData()
        .ifPresent(
            simulatorReplayDatapoints -> {
              LOG.info("Simulator replay data found for linked attribute: " + attribute);
              AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
              predictedDatapointWindowMap.put(attributeRef, PredictedDatapointWindow.BOTH);

              Long duration =
                  this.agent.getAgentLink(attribute).getSchedule().calculateDuration().orElse(null);

              // Sorting so we can assume positioning
              SimulatorReplayDatapoint[] sorted =
                  Arrays.stream(simulatorReplayDatapoints)
                      .filter(d -> duration == null || d.timestamp <= duration)
                      .sorted(Comparator.comparingLong(SimulatorReplayDatapoint::getTimestamp))
                      .toArray(SimulatorReplayDatapoint[]::new);

              ScheduledFuture<?> updateValueFuture = scheduleReplay(attributeRef, sorted);
              if (updateValueFuture != null) {
                replayMap.put(attributeRef, updateValueFuture);
              } else {
                LOG.warning(
                    "Failed to schedule replay update value for simulator replay attribute: "
                        + attribute);
                replayMap.put(attributeRef, null);
              }
            });
  }

  @Override
  protected void doUnlinkAttribute(
      String assetId, Attribute<?> attribute, SimulatorAgentLink agentLink) {
    AttributeRef attributeRef = new AttributeRef(assetId, attribute.getName());
    ScheduledFuture<?> updateValueFuture = replayMap.remove(attributeRef);

    if (updateValueFuture != null) {
      updateValueFuture.cancel(true);
    }

    // Purge previously configured predicted datapoints
    predictedDatapointService.purgeValues(attributeRef.getId(), attribute.getName());
  }

  @Override
  protected void doLinkedAttributeWrite(
      SimulatorAgentLink agentLink, AttributeEvent event, Object processedValue) {
    if (replayMap.containsKey(event.getRef())) {
      LOG.info(
          "Attempt to write to linked attribute that is configured for value replay so ignoring: "
              + event.getRef());
      return;
    }

    // Assume write through and endpoint returned the value we sent
    LOG.finest(
        "Write to linked attribute so simulating that the endpoint returned the written value");
    updateSensor(event.getRef(), processedValue);
  }

  /** Call this to simulate a sensor update */
  public void updateSensor(AttributeEvent attributeEvent) {
    updateSensor(
        attributeEvent.getRef(),
        attributeEvent.getValue().orElse(null),
        attributeEvent.getTimestamp());
  }

  public void updateSensor(AttributeRef attributeRef, Object value) {
    updateSensor(attributeRef, value, timerService.getCurrentTimeMillis());
  }

  /** Call this to simulate a sensor update using the specified timestamp */
  public void updateSensor(AttributeRef attributeRef, Object value, long timestamp) {
    updateLinkedAttribute(attributeRef, value, timestamp);
  }

  public void updateLinkedAttributePredictedDataPoints(
      AttributeRef attributeRef, List<ValueDatapoint<?>> values) {
    Attribute<?> attribute = linkedAttributes.get(attributeRef);

    if (attribute == null) {
      LOG.log(
          Level.WARNING,
          () ->
              "Update linked attribute predicted data points called for un-linked attribute: "
                  + attributeRef);
      return;
    }

    predictedDatapointService.updateValues(attributeRef.getId(), attribute.getName(), values);
  }

  public Map<AttributeRef, ScheduledFuture<?>> getReplayMap() {
    return replayMap;
  }

  protected ScheduledFuture<?> scheduleReplay(
      AttributeRef attributeRef, SimulatorReplayDatapoint[] simulatorReplayDatapoints) {
    Attribute<?> attribute = linkedAttributes.get(attributeRef);
    SimulatorAgentLink agentLink = this.agent.getAgentLink(attribute);

    LOG.finest("Scheduling linked attribute replay update");

    Schedule schedule = agentLink.getSchedule();
    ZoneId timezone = agentLink.getTimezone().orElse(TimeZone.getTimeZone("UTC")).toZoneId();

    long nowUTC = timerService.getNow().toEpochMilli(); // UTC
    long now = toLocalMillis(nowUTC, timezone);

    long timeSinceOccurrenceStart = now - schedule.tryAdvanceActive(now, timezone);

    // Find datapoint with timestamp after the current occurrence
    SimulatorReplayDatapoint nextDatapoint =
        Arrays.stream(simulatorReplayDatapoints)
            .filter(
                replaySimulatorDatapoint ->
                    replaySimulatorDatapoint.timestamp * 1000 > timeSinceOccurrenceStart)
            .findFirst()
            .orElse(simulatorReplayDatapoints[0]);

    if (nextDatapoint == null) {
      LOG.warning("Next datapoint not found so replay cancelled: " + attributeRef);
      return null;
    }

    OptionalLong nextRun = schedule.getDelay(nextDatapoint.timestamp, timeSinceOccurrenceStart);
    if (nextRun.isEmpty() || nextRun.getAsLong() < 0 || schedule.isAfterScheduleEnd(now)) {
      LOG.warning("Replay loop has ended for: " + attributeRef);
      predictedDatapointService.purgeValuesBefore(
          attributeRef.getId(), attributeRef.getName(), Instant.ofEpochMilli(nowUTC));
      return null;
    }

    // The next run is a distance between local times, which is an hour short or long across a
    // daylight saving transition, so it is resolved to the instant it occurs on
    long nextRunUTC = toEpochMillis(now + nextRun.getAsLong(), timezone, nowUTC);

    // Datapoints resolving to the same instant take effect together, so the last of them holds
    SimulatorReplayDatapoint replayDatapoint =
        Arrays.stream(simulatorReplayDatapoints)
            .filter(datapoint -> datapoint.timestamp >= nextDatapoint.timestamp)
            .takeWhile(
                datapoint ->
                    schedule.getDelay(datapoint.timestamp, timeSinceOccurrenceStart).stream()
                        .anyMatch(
                            datapointDelay ->
                                toEpochMillis(now + datapointDelay, timezone, nowUTC)
                                    == nextRunUTC))
            .reduce((previous, datapoint) -> datapoint)
            .orElse(nextDatapoint);

    if (attribute
        .getMeta()
        .get(HAS_PREDICTED_DATA_POINTS)
        .flatMap(AbstractNameValueHolder::getValue)
        .orElse(false)) {
      PredictedDatapointWindow status = predictedDatapointWindowMap.get(attributeRef);
      List<ValueDatapoint<?>> predictedDatapoints =
          calculatePredictedDatapoints(
              simulatorReplayDatapoints,
              schedule,
              status,
              timeSinceOccurrenceStart,
              nowUTC,
              timezone);
      try {
        updateLinkedAttributePredictedDataPoints(attributeRef, predictedDatapoints);
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Exception thrown when updating value: %s", e);
      }
      // Determine what predicted datapoint windows to be calculated next
      // Initially calculate BOTH the current- and next occurrence window,
      // afterward only calculate the NEXT window after switching to the next window.
      if (status.equals(PredictedDatapointWindow.BOTH)
          || status.equals(PredictedDatapointWindow.NEXT)) {
        predictedDatapointWindowMap.put(attributeRef, PredictedDatapointWindow.NONE);
      } else if (simulatorReplayDatapoints[simulatorReplayDatapoints.length - 1]
          == replayDatapoint) {
        predictedDatapointWindowMap.put(attributeRef, PredictedDatapointWindow.NEXT);
      }
    }

    long delay = nextRunUTC - nowUTC;

    LOG.fine(
        "Next update for asset "
            + attributeRef.getId()
            + " for attribute "
            + attributeRef.getName()
            + " in "
            + delay
            + " millisecond(s)");
    return scheduledExecutorService.schedule(
        () -> {
          LOG.fine(
              "Updating asset "
                  + attributeRef.getId()
                  + " for attribute "
                  + attributeRef.getName()
                  + " with value "
                  + replayDatapoint.value.toString());
          try {
            updateLinkedAttribute(attributeRef, replayDatapoint.value);
            predictedDatapointService.purgeValuesBefore(
                attributeRef.getId(), attributeRef.getName(), Instant.ofEpochMilli(nextRunUTC));
          } catch (Exception e) {
            LOG.log(Level.SEVERE, "Exception thrown when updating value: %s", e);
          }

          ScheduledFuture<?> future = scheduleReplay(attributeRef, simulatorReplayDatapoints);
          if (future != null) {
            replayMap.put(attributeRef, future);
          } else {
            replayMap.remove(attributeRef);
          }
        },
        delay,
        TimeUnit.MILLISECONDS);
  }

  /** Reads an instant as the local time of the timezone, in milliseconds since the epoch. */
  private static long toLocalMillis(long millisSinceEpoch, ZoneId timezone) {
    return Instant.ofEpochMilli(millisSinceEpoch)
        .atZone(timezone)
        .toLocalDateTime()
        .toInstant(ZoneOffset.UTC)
        .toEpochMilli();
  }

  /**
   * Resolves a local time of the timezone to the instant it occurs on, so the offset of that
   * instant applies instead of the one that happens to be active now.
   *
   * <p>A local time skipped by a daylight saving transition resolves to the end of the gap, where
   * the clocks land, and a local time that happens twice takes the first of the two, unless that
   * one is before {@code notBeforeUTC}.
   */
  private static long toEpochMillis(long localMillis, ZoneId timezone, long notBeforeUTC) {
    LocalDateTime local =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(localMillis), ZoneOffset.UTC);
    ZoneOffsetTransition transition = timezone.getRules().getTransition(local);
    if (transition != null && transition.isGap()) {
      return transition.getInstant().toEpochMilli();
    }
    ZonedDateTime zoned = local.atZone(timezone);
    long millis = zoned.toInstant().toEpochMilli();
    if (millis < notBeforeUTC) {
      millis = zoned.withLaterOffsetAtOverlap().toInstant().toEpochMilli();
    }
    return millis;
  }

  public List<ValueDatapoint<?>> calculatePredictedDatapoints(
      SimulatorReplayDatapoint[] simulatorReplayDatapoints,
      Schedule schedule,
      PredictedDatapointWindow status,
      long timeSinceOccurrenceStart,
      long nowUTC,
      ZoneId timezone) {
    List<ValueDatapoint<?>> predictedDatapoints = new ArrayList<>();
    long now = toLocalMillis(nowUTC, timezone);

    if (status.equals(PredictedDatapointWindow.NONE) || !schedule.hasCurrent()) {
      return predictedDatapoints;
    }

    if (status.equals(PredictedDatapointWindow.BOTH)) {
      for (SimulatorReplayDatapoint datapoint : simulatorReplayDatapoints) {
        OptionalLong delay = schedule.getDelay(datapoint.timestamp, timeSinceOccurrenceStart);
        if (delay.isEmpty()) {
          return predictedDatapoints;
        }
        long timestamp = now + delay.getAsLong();
        if (timestamp > now) { // Delay can be negative
          // If the predicted datapoint rolls past the current occurrence skip it
          // This happens when the offset is less than the time since the occurrence started
          if (timeSinceOccurrenceStart >= 0 && schedule.isAfterUpcoming(timestamp)) {
            continue;
          }
          // Return remaining predictions if single occurrence or recurrence end has been surpassed
          if (schedule.isAfterScheduleEnd(timestamp)) {
            return predictedDatapoints;
          }
          long UTCTimestamp = toEpochMillis(timestamp, timezone, nowUTC);
          addPredictedDatapoint(predictedDatapoints, UTCTimestamp, datapoint.value);
        }
      }
    }

    // Single occurrence schedule can't have a next window
    // Or a recurrence is already ongoing and is meant to end immediately
    if (schedule.isSingleOccurrence() || !schedule.hasUpcoming()) {
      return predictedDatapoints;
    }

    if (status.equals(PredictedDatapointWindow.BOTH)
        || status.equals(PredictedDatapointWindow.NEXT)) {
      for (SimulatorReplayDatapoint datapoint : simulatorReplayDatapoints) {
        long timeSinceUpcoming = now - schedule.upcoming.toInstant(ZoneOffset.UTC).toEpochMilli();

        OptionalLong delay = schedule.getDelay(datapoint.timestamp, timeSinceUpcoming);
        if (delay.isEmpty()) {
          continue;
        }
        long timestamp = now + delay.getAsLong();
        if (schedule.isAfterScheduleEnd(timestamp)) {
          return predictedDatapoints;
        }
        long UTCTimestamp = toEpochMillis(timestamp, timezone, nowUTC);
        addPredictedDatapoint(predictedDatapoints, UTCTimestamp, datapoint.value);
      }
    }

    return predictedDatapoints;
  }

  /**
   * Adds a predicted datapoint, replacing the previous one when both resolve to the same instant,
   * as the last of them is the value that holds.
   */
  private static void addPredictedDatapoint(
      List<ValueDatapoint<?>> predictedDatapoints, long timestamp, Object value) {
    ValueDatapoint<?> predictedDatapoint =
        new SimulatorReplayDatapoint(timestamp, value).toValueDatapoint();
    if (!predictedDatapoints.isEmpty()
        && predictedDatapoints.getLast().getTimestamp() == timestamp) {
      predictedDatapoints.set(predictedDatapoints.size() - 1, predictedDatapoint);
    } else {
      predictedDatapoints.add(predictedDatapoint);
    }
  }

  /** Determines for what occurrence window to create predicted datapoints */
  public enum PredictedDatapointWindow {
    BOTH,
    NONE,
    NEXT
  }

  public static class Schedule implements Serializable {

    private static class EpochLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

      @Override
      public LocalDateTime deserialize(JsonParser parser, DeserializationContext context)
          throws IOException {
        if (parser != null && parser.getCurrentToken() != null) {
          long value = parser.getValueAsLong(-1);
          if (value > -1L) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC);
          }
        }
        return null;
      }
    }

    public static class EpochLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {

      @Override
      public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
          throws IOException {
        if (value == null) {
          gen.writeNull();
          return;
        }
        gen.writeNumber(value.toInstant(ZoneOffset.UTC).toEpochMilli());
      }
    }

    @JsonSerialize(using = EpochLocalDateTimeSerializer.class)
    @JsonDeserialize(using = EpochLocalDateTimeDeserializer.class)
    @JsonSchemaTypeRemap(type = Long.class)
    protected LocalDateTime start;

    @JsonSerialize(using = EpochLocalDateTimeSerializer.class)
    @JsonDeserialize(using = EpochLocalDateTimeDeserializer.class)
    @JsonSchemaTypeRemap(type = Long.class)
    protected LocalDateTime end;

    @JsonSchemaDescription("The recurrence schedule follows the RFC 5545 RRULE format.")
    @JsonSchemaTypeRemap(type = String.class)
    @JsonSerialize(converter = CalendarEvent.RecurStringConverter.class)
    protected Recur<LocalDateTime> recurrence;

    @JsonIgnore private LocalDateTime current;
    @JsonIgnore private LocalDateTime upcoming;

    @JsonCreator
    public Schedule(
        @JsonProperty("start") LocalDateTime start,
        @JsonProperty("end") LocalDateTime end,
        @JsonProperty("recurrence") String recurrence) {
      Recur<LocalDateTime> recur = null;

      try {
        recur = new Recur<>(recurrence);
      } catch (Exception ignored) {
      }

      this.start =
          Optional.ofNullable(start).orElse(LocalDate.now(ZoneId.of("UTC")).atStartOfDay());
      this.end = end;
      this.recurrence = recur;
      this.upcoming = this.start;
    }

    public LocalDateTime getStart() {
      return start;
    }

    public LocalDateTime getEnd() {
      return end;
    }

    public Recur<LocalDateTime> getRecurrence() {
      return recurrence;
    }

    public Optional<Long> calculateDuration() {
      if (start == null || end == null) {
        return Optional.empty();
      }
      return Optional.of(end.toEpochSecond(ZoneOffset.UTC) - start.toEpochSecond(ZoneOffset.UTC));
    }

    /**
     * Try to advance to the next occurrence once {@code millisSinceEpoch} surpasses previously
     * active.
     *
     * <p>An occurrence can be a single event or part of a recurring event.
     *
     * <p>If a recurrence rule has been configured, the start of the current occurrence is used.
     *
     * <p>Both the argument and the result are local times of the configured timezone.
     *
     * @param millisSinceEpoch Milliseconds since the epoch (1970-01-01T00:00:00Z)
     * @return The start time of the active occurrence in milliseconds. If not started, the start of
     *     the schedule is returned.
     */
    public long tryAdvanceActive(long millisSinceEpoch) {
      return tryAdvanceActive(millisSinceEpoch, ZoneOffset.UTC);
    }

    /**
     * Same as {@link #tryAdvanceActive(long)}, except that an occurrence starting at a local time
     * skipped by a daylight saving transition of the timezone starts later by the length of the
     * gap, so its datapoints keep their spacing.
     */
    public long tryAdvanceActive(long millisSinceEpoch, ZoneId timezone) {
      long startInMillis = start.toInstant(ZoneOffset.UTC).toEpochMilli();

      if (recurrence == null) {
        current = skipGap(start, timezone);
        return current.toInstant(ZoneOffset.UTC).toEpochMilli();
      }

      LocalDateTime now =
          LocalDateTime.ofInstant(Instant.ofEpochMilli(millisSinceEpoch), ZoneOffset.UTC);

      // Check if this is the first time
      if (current == null) {
        // Get the previous (active) occ to catch up with the current occurrence for the first time
        // this method is called
        List<LocalDateTime> dates = recurrence.getDates(start, start, now);
        if (!dates.isEmpty()) {
          LocalDateTime last = dates.getLast();
          current = skipGap(last, timezone);
          upcoming = skipGap(recurrence.getNextDate(start, last), timezone);
          return current.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        LocalDateTime epoch = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
        LocalDateTime firstOccurrence = recurrence.getNextDate(start, epoch);
        // If the first occurrence does not equal 'start' when using a BYxxx rule part
        if (firstOccurrence != null
            && startInMillis != firstOccurrence.toInstant(ZoneOffset.UTC).toEpochMilli()) {
          current = skipGap(firstOccurrence, timezone);
          upcoming = skipGap(recurrence.getNextDate(start, firstOccurrence), timezone);
          return current.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        // The first occurrence hasn't started
        current = skipGap(start, timezone);
        upcoming = current;
        return current.toInstant(ZoneOffset.UTC).toEpochMilli();
      }

      // Preemptively get next to determine whether to advance the occurrence
      LocalDateTime next = skipGap(recurrence.getNextDate(start, now), timezone);

      if (next == null) {
        current = upcoming; // Update current 1 final time
        return current.toInstant(ZoneOffset.UTC).toEpochMilli();
      }

      // Track active and upcoming occurrence
      // Switch occurrences once next has surpassed previously upcoming
      if (next.isAfter(upcoming)) {
        current = upcoming;
      }
      upcoming = next;

      return current.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * Moves a local time skipped by a daylight saving transition forward by the length of the gap.
     */
    private static LocalDateTime skipGap(LocalDateTime localDateTime, ZoneId timezone) {
      return localDateTime == null ? null : localDateTime.atZone(timezone).toLocalDateTime();
    }

    /**
     * Calculates the remaining datapoint offset delay in milliseconds relative to the current time.
     *
     * @param offset The datapoint offset in seconds.
     * @param timeSinceOccurrenceStart The time since the occurrence started in milliseconds.
     * @return The remaining datapoint offset delay in milliseconds relative to the current time.
     *     <p>If the recurrence rule has ended returns {@link OptionalLong#empty()} instead.
     */
    public OptionalLong getDelay(long offset, long timeSinceOccurrenceStart) {
      long offsetInMillis = offset * 1000;
      if (offsetInMillis <= timeSinceOccurrenceStart) {
        return this.getTimeUntilNextOccurrence(timeSinceOccurrenceStart).stream()
            .map(n -> offsetInMillis + n)
            .findFirst();
      }
      return OptionalLong.of(offsetInMillis - timeSinceOccurrenceStart);
    }

    /**
     * Calculates the time in milliseconds until the next occurrence starts.
     *
     * @param timeSinceOccurrenceStart The time since the occurrence started in milliseconds.
     * @return The delay in milliseconds until the next occurrence.
     *     <p>If the recurrence rule has ended returns {@link OptionalLong#empty()} instead.
     */
    public OptionalLong getTimeUntilNextOccurrence(long timeSinceOccurrenceStart) {
      if (recurrence != null) {
        if (current == null || upcoming == null) {
          return OptionalLong.empty();
        }
        long duration =
            upcoming.toEpochSecond(ZoneOffset.UTC) - current.toEpochSecond(ZoneOffset.UTC);
        return OptionalLong.of(duration * 1000 - timeSinceOccurrenceStart);
      }
      return OptionalLong.of(-timeSinceOccurrenceStart);
    }

    public boolean hasCurrent() {
      return current != null;
    }

    public boolean hasUpcoming() {
      return upcoming != null;
    }

    public boolean isSingleOccurrence() {
      return recurrence == null;
    }

    public boolean isAfterScheduleEnd(long millis) {
      if (recurrence != null) {
        if (recurrence.getUntil() != null) {
          return millis > recurrence.getUntil().toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        return false;
      }
      if (end != null) {
        return millis > end.toInstant(ZoneOffset.UTC).toEpochMilli();
      }
      return false;
    }

    public boolean isAfterUpcoming(long millis) {
      if (!isSingleOccurrence() && upcoming != null) {
        return millis > upcoming.toInstant(ZoneOffset.UTC).toEpochMilli();
      }
      return false;
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "{"
          + "start='"
          + start
          + '\''
          + ", end='"
          + end
          + '\''
          + ", recurrence='"
          + recurrence
          + '\''
          + "}";
    }
  }
}
