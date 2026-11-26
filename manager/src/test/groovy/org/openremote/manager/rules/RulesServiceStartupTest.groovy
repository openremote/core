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
package org.openremote.manager.rules

import org.openremote.model.attribute.AttributeEvent
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

class RulesServiceStartupTest extends Specification {

  def "an event waiting to enter the pre-start buffer is processed when startup finishes"() {
    given:
    def processed = []
    def service = new RulesService() {
              @Override
              protected void doProcessAttributeUpdate(AttributeEvent event) {
                processed.add(event)
              }
            }
    def event = new AttributeEvent("asset", "count", 1)
    def conditions = new PollingConditions(timeout: 5)
    def delivery = Thread.ofPlatform().unstarted { service.onAttributeEvent(event) }

    when: "startup drains the buffer while delivery is waiting for its lock"
    synchronized (service.preInitAttributeEvents) {
      delivery.start()
      conditions.eventually {
        assert delivery.state == Thread.State.BLOCKED
      }
      service.startProducers(null)
    }
    delivery.join(5000)

    then:
    !delivery.alive
    processed == [event]
    service.preInitAttributeEvents.isEmpty()
  }
}
