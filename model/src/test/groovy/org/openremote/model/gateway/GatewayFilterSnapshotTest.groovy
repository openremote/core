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
package org.openremote.model.gateway

import org.openremote.model.query.AssetQuery
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class GatewayFilterSnapshotTest extends Specification {
  def "a JSON snapshot compares equal after the live filter caches its duration"() {
    given:
    def original = new GatewayAttributeFilter().setMatcher(new AssetQuery().names("Microphone 2")).setDuration("PT1M")
    def snapshot = ValueUtil.JSON.readValue(ValueUtil.JSON.writeValueAsBytes(original), GatewayAttributeFilter)
    original.getDurationParsed()

    expect:
    [original] == [snapshot]
    original.hashCode() == snapshot.hashCode()
  }

  def "a filter with a changed setting compares unequal to its previous snapshot"() {
    given:
    def original = new GatewayAttributeFilter()
    def changed = new GatewayAttributeFilter()
    changed."$property" = value

    expect:
    original != changed

    where:
    property      | value
    "matcher"     | new AssetQuery().names("changed")
    "duration"    | "PT3S"
    "delta"       | 1d
    "valueChange" | true
    "skipAlways"  | true
  }

  def "changing the query inside the matcher is detected"() {
    given:
    def original = new GatewayAttributeFilter().setMatcher(new AssetQuery().names("before"))
    def changed = ValueUtil.JSON.readValue(ValueUtil.JSON.writeValueAsBytes(original), GatewayAttributeFilter)
    changed.matcher.names("after")

    expect:
    original != changed
  }
}
