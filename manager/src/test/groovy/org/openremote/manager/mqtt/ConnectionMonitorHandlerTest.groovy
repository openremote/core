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

import org.apache.camel.ServiceStatus
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.spi.RouteController
import org.openremote.container.message.MessageBrokerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.model.attribute.AttributeRef
import spock.lang.Specification

import java.util.logging.Handler
import java.util.logging.Level

class ConnectionMonitorHandlerTest extends Specification {

  def "a disconnect logs at #level and returns when #scenario"() {
    given:
    def routes = Stub(RouteController) {
      getRouteStatus("AttributeEvent-Processor") >> ServiceStatus.Stopped
    }
    def camel = Stub(DefaultCamelContext) { getRouteController() >> routes }
    def broker = Stub(MessageBrokerService) {
      isStarted() >>> brokerStates
      getContext() >> camel
    }
    def monitor = new ConnectionMonitorHandler()
    monitor.messageBrokerService = broker
    monitor.mqttBrokerService = Stub(MQTTBrokerService) {
      getUserConnections("user-id") >> Collections.emptySet()
    }
    monitor.assetProcessingService = new AssetProcessingService(messageBrokerService: broker)
    def logHandler = Mock(Handler)
    def logger = monitor.logger
    def originalLevel = logger.level
    logger.level = Level.FINE
    logger.addHandler(logHandler)

    when:
    monitor.updateUserConnectedStatus("user-id", [new AttributeRef("asset", "online")], false)

    then:
    1 * logHandler.publish({
      it.level == level && it.message.contains("user-id") && it.message.contains(reason)
    })

    cleanup:
    logger?.removeHandler(logHandler)
    logger?.setLevel(originalLevel)

    where:
    scenario                         | brokerStates  | level         | reason
    "Camel has stopped"              | [false]       | Level.FINE    | "Camel is stopped"
    "Camel stops during submission"  | [true, false] | Level.FINE    | "processor is unavailable"
    "only the attribute route stops" | [true]        | Level.WARNING | "processor is unavailable"
  }
}
