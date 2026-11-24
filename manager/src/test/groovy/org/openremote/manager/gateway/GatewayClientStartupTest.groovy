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

import org.openremote.container.persistence.PersistenceService
import org.openremote.model.PersistenceEvent
import org.openremote.model.gateway.GatewayConnection
import spock.lang.Specification

class GatewayClientStartupTest extends Specification {

  def "configured connections open in the producer phase and close during shutdown"() {
    given:
    def configured = Stub(GatewayConnection) { getLocalRealm() >> "realm" }
    def connector = Mock(GatewayClientConnector)
    def persistence = Stub(PersistenceService) { doReturningTransaction(_) >> [configured] }
    def service = Spy(GatewayClientService)
    service.persistenceService = persistence

    when: "a persisted connection is reported before producers start"
    service.start(null)
    service.processConnectionChange(configured, PersistenceEvent.Cause.CREATE)

    then:
    0 * service.createClientConnector(_)

    when: "startup loads the connection and a delayed creation event arrives"
    service.startProducers(null)
    service.processConnectionChange(configured, PersistenceEvent.Cause.CREATE)

    then:
    1 * service.createClientConnector(configured) >> connector
    service.connectionRealmMap["realm"].is(connector)

    when:
    service.stop(null)
    service.processConnectionChange(configured, PersistenceEvent.Cause.UPDATE)

    then:
    1 * connector.close()
    0 * service.createClientConnector(_)
    service.connectionRealmMap.isEmpty()
  }
}
