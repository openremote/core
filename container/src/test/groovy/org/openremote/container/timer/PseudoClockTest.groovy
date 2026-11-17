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
package org.openremote.container.timer

import spock.lang.Specification

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class PseudoClockTest extends Specification {

  TimerService.Clock clock
  TimeZone originalTimeZone

  def setup() {
    originalTimeZone = TimeZone.getDefault()
    clock = TimerService.Clock.PSEUDO
    clock.reset()
    clock.init()
    clock.stop()
  }

  def cleanup() {
    try {
      clock.reset()
    } finally {
      TimeZone.setDefault(originalTimeZone)
    }
  }

  def "initializing the pseudo clock preserves the JVM time zone #zone"() {
    given:
    def timeZone = TimeZone.getTimeZone(zone)
    TimeZone.setDefault(timeZone)

    when:
    clock.init()

    then:
    TimeZone.getDefault() == timeZone

    where:
    zone << ["Europe/Brussels", "America/New_York", "Asia/Kathmandu"]
  }

  def "setTime respects the #hours hour day on #date in Europe/Brussels"() {
    given:
    def zone = ZoneId.of("Europe/Brussels")
    def startDate = LocalDate.parse(date)
    def start = clock.setTime(startDate, LocalTime.MIDNIGHT, zone)

    when:
    def end = clock.setTime(startDate.plusDays(1), LocalTime.MIDNIGHT, zone)

    then:
    end - start == TimeUnit.HOURS.toMillis(hours)

    where:
    date | hours
    "2026-03-29" | 23
    "2026-10-25" | 25
  }

  def "setTime sets #date at midnight in #zone to #expected milliseconds"() {
    expect:
    clock.setTime(LocalDate.parse(date), LocalTime.MIDNIGHT, ZoneId.of(zone)) == expected
    clock.currentTimeMillis == expected

    where:
    date | zone || expected
    "1970-01-01" | "UTC" || 0L
    "1970-01-02" | "UTC" || 24L * 3_600_000
    "1970-01-01" | "CET" || -3_600_000L
    "1970-01-02" | "CET" || 23L * 3_600_000
  }

  def "setTime sets #timestamp to #expected milliseconds"() {
    expect:
    clock.setTime(timestamp) == expected
    clock.currentTimeMillis == expected

    where:
    timestamp || expected
    "1970-01-01T00:00:00.000Z" || 0L
    "1970-01-02T00:00:00.000Z" || 24L * 3_600_000
    "1970-01-01T00:00:00.000+01:00" || -3_600_000L
    "1970-01-02T00:00:00.000+01:00" || 23L * 3_600_000
  }
}
