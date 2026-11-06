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
package org.openremote.model.asset

import org.openremote.model.event.shared.SharedEvent
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

class AssetsEventTest extends Specification {
  def "empty inventory remains distinct from missing inventory on the wire"() {
    when:
    def json = ValueUtil.asJSON(new AssetsEvent(inventory)).orElseThrow()
    def restored = ValueUtil.JSON.readValue(json, SharedEvent) as AssetsEvent

    then:
    restored.assets == inventory

    where:
    inventory << [[], null]
  }
}
