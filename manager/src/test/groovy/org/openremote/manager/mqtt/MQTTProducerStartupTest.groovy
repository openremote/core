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
package org.openremote.manager.mqtt

import org.openremote.model.Container
import spock.lang.Specification

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.Logger

class MQTTProducerStartupTest extends Specification {

  def "producer startup logs the failing handler and propagates its exception"() {
    given:
    def container = Stub(Container)
    def failure = new IllegalStateException("Attribute route unavailable")
    def failingHandler = Stub(MQTTHandler) {
      getName() >> "CustomConnectionMonitor"
      startProducers(container) >> { throw failure }
    }
    def nextHandler = Mock(MQTTHandler)
    def broker = new MQTTBrokerService(active: true, customHandlers: [failingHandler, nextHandler])
    def logger = Logger.getLogger(MQTTBrokerService.LOG.name)
    def logHandler = Mock(Handler)
    def originalLevel = logger.level
    logger.level = Level.WARNING
    logger.addHandler(logHandler)

    when:
    broker.startProducers(container)

    then:
    def propagated = thrown(IllegalStateException)
    propagated.is(failure)
    1 * logHandler.publish({
      it.level == Level.WARNING && it.thrown.is(failure) &&
              it.message.contains("producers") && it.message.contains("CustomConnectionMonitor")
    })
    0 * nextHandler.startProducers(_)

    cleanup:
    logger.removeHandler(logHandler)
    logger.level = originalLevel
  }
}
