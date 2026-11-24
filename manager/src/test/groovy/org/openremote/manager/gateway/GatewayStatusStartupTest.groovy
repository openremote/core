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
package org.openremote.manager.gateway

import org.openremote.container.timer.TimerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.model.PersistenceEvent
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.GatewayAsset
import org.openremote.model.attribute.AttributeEvent
import spock.lang.Specification

import java.time.Instant

class GatewayStatusStartupTest extends Specification {

  def "stored gateways publish status in the producer phase and new gateways publish on registration"() {
    given:
    def stored = gateway("existing")
    def added = gateway("added")
    def processor = Mock(AssetProcessingService)
    def service = Spy(GatewayService)
    service.active = true
    service.assetStorageService = Stub(AssetStorageService) {
      findAll(_) >>> [[stored], []]
    }
    service.timerService = Stub(TimerService) { getNow() >> Instant.EPOCH }
    service.assetProcessingService = processor
    service.createUpdateGatewayServiceUser(_) >> {}
    def creation = Stub(PersistenceEvent) { getCause() >> PersistenceEvent.Cause.CREATE }

    when:
    service.start(null)

    then:
    service.isLocallyRegisteredGateway(stored.id)
    0 * processor._

    when:
    service.startProducers(null)

    then:
    1 * processor.sendAttributeEventAndWait({
      isDisconnectedStatus(it, stored.id)
    }, GatewayConnector.EVENT_SOURCE)

    when:
    service.processGatewayChange(added, creation)

    then:
    1 * processor.sendAttributeEvent({
      isDisconnectedStatus(it, added.id)
    }, GatewayConnector.EVENT_SOURCE)
  }

  def "shutdown disconnects every gateway even after attribute processing stops"() {
    given:
    def service = new GatewayService()
    service.timerService = Stub(TimerService) { getNow() >> Instant.EPOCH }
    service.assetProcessingService = new AssetProcessingService()
    def disconnected = []
    ["first", "second"].each { id ->
      def connector = new GatewayConnector(null, service.assetProcessingService, null, null,
              service, service.timerService, gateway(id))
      connector.@sessionId.set(id)
      connector.requestDisconnect = { disconnected.add(id) } as Runnable
      service.gatewayConnectorMap.put(id, connector)
    }

    when:
    service.stop(null)

    then:
    disconnected.toSet() == ["first", "second"] as Set
    service.gatewayConnectorMap.isEmpty()
  }

  private GatewayAsset gateway(String gatewayId) {
    Stub(GatewayAsset) {
      getId() >> gatewayId
      getRealm() >> "realm"
      getPath() >> ([gatewayId] as String[])
      getClientId() >> Optional.of("gateway-" + gatewayId)
      getClientSecret() >> Optional.of("secret")
    }
  }

  private static boolean isDisconnectedStatus(AttributeEvent event, String id) {
    event.id == id && event.name == GatewayAsset.STATUS.name && event.value.get() == ConnectionStatus.DISCONNECTED
  }
}
