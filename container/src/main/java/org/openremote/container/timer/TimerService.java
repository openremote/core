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
package org.openremote.container.timer;

import static org.openremote.model.util.MapAccess.getString;

import java.time.*;
import java.time.temporal.Temporal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.rules.RulesClock;

/** Wall real clock timer or pseudo clock time (for testing). */
public class TimerService implements ContainerService, RulesClock {

  private static final Logger LOG = Logger.getLogger(TimerService.class.getName());
  public static final String TIMER_CLOCK_TYPE = "TIMER_CLOCK_TYPE";
  public static final String TIMER_CLOCK_TYPE_DEFAULT = Clock.REAL.toString();
  public static final int PRIORITY = ContainerService.HIGH_PRIORITY + 300;

  public enum Clock {
    REAL {
      @Override
      public void init() {
        // NOOP
      }

      @Override
      public long getCurrentTimeMillis() {
        return System.currentTimeMillis();
      }

      @Override
      public long advanceTime(long amount, TimeUnit unit) {
        throw new UnsupportedOperationException("Wall clock can not be advanced manually");
      }

      @Override
      public long setTime(LocalDate date, LocalTime time, ZoneId zoneId) {
        throw new UnsupportedOperationException("Wall clock can not be set manually");
      }

      @Override
      public long setTime(String iso8601Timestamp) {
        throw new UnsupportedOperationException("Wall clock can not be set manually");
      }

      @Override
      public void stop() {
        // NOOP
      }

      @Override
      public void start() {
        // NOOP
      }

      @Override
      public void reset() {
        // NOOP
      }
    },
    PSEUDO {
      protected AtomicLong offset = new AtomicLong();
      protected volatile Long stopTime;
      // Use UTC internally without changing the JVM's default time zone.
      protected java.time.Clock utcClock = java.time.Clock.systemUTC();

      @Override
      public void init() {
        long current = getCurrentTimeMillis();
        LOG.info("Initialized pseudo clock to: " + current + " / " + Instant.ofEpochMilli(current));
      }

      @Override
      public synchronized long getCurrentTimeMillis() {
        return (stopTime != null ? stopTime : utcClock.millis()) + offset.get();
      }

      @Override
      public synchronized long advanceTime(long amount, TimeUnit unit) {
        offset.addAndGet(unit.toMillis(amount));
        long currentMillis = getCurrentTimeMillis();
        LOG.info(
            "Clock advanced to: " + currentMillis + " / " + Instant.ofEpochMilli(currentMillis));
        return currentMillis;
      }

      @Override
      public long setTime(LocalDate date, LocalTime time, ZoneId zoneId) {
        ZonedDateTime current = Instant.ofEpochMilli(getCurrentTimeMillis()).atZone(zoneId);
        ZonedDateTime target = date.atTime(time).atZone(zoneId);
        return advanceTime(Duration.between(current, target).toMillis(), TimeUnit.MILLISECONDS);
      }

      @Override
      public long setTime(String iso8601Timestamp) {
        Temporal current = Instant.ofEpochMilli(getCurrentTimeMillis());
        Temporal target = Instant.parse(iso8601Timestamp);
        return advanceTime(Duration.between(current, target).toMillis(), TimeUnit.MILLISECONDS);
      }

      @Override
      public synchronized void stop() {
        if (stopTime == null) {
          stopTime = utcClock.millis();
          LOG.info("Clock stopped at: " + stopTime + " / " + Instant.ofEpochMilli(stopTime));
        }
      }

      @Override
      public synchronized void start() {
        stopTime = null;
        long now = utcClock.millis();
        LOG.info("Clock started at: " + now + " / " + Instant.ofEpochMilli(now));
      }

      @Override
      public synchronized void reset() {
        offset.set(0L);
        stopTime = null;
      }
    };

    public abstract void init();

    public abstract long getCurrentTimeMillis();

    public abstract void stop();

    public abstract void start();

    public abstract void reset();

    public abstract long advanceTime(long amount, TimeUnit unit);

    public abstract long setTime(LocalDate date, LocalTime time, ZoneId zoneId);

    public abstract long setTime(String iso8601Timestamp);
  }

  protected Clock clock;

  @Override
  public int getPriority() {
    return PRIORITY;
  }

  @Override
  public void init(Container container) throws Exception {
    this.clock =
        Clock.valueOf(getString(container.getConfig(), TIMER_CLOCK_TYPE, TIMER_CLOCK_TYPE_DEFAULT));
    this.clock.init();
  }

  @Override
  public void start(Container container) throws Exception {
    getClock().start();
  }

  @Override
  public void stop(Container container) throws Exception {
    getClock().stop();
  }

  public Clock getClock() {
    return clock;
  }

  public long getCurrentTimeMillis() {
    return getClock().getCurrentTimeMillis();
  }

  public Instant getNow() {
    return Instant.ofEpochMilli(getCurrentTimeMillis());
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" + "clock=" + clock + '}';
  }
}
