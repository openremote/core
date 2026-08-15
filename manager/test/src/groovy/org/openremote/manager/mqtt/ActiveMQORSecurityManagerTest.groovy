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

import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection
import org.apache.activemq.artemis.spi.core.security.jaas.RolePrincipal
import org.apache.activemq.artemis.spi.core.security.jaas.UserPrincipal
import org.openremote.container.security.IdentityService
import org.openremote.manager.security.ManagerIdentityProvider
import org.openremote.manager.security.RemotingConnectionPrincipal
import spock.lang.Specification

import javax.security.auth.Subject
import java.util.concurrent.ExecutorService

class ActiveMQORSecurityManagerTest extends Specification {

  def "authenticates a connection with an empty password as anonymous"() {
    given:
    def brokerService = Stub(MQTTBrokerService)
    def executorService = Stub(ExecutorService)
    def identityService = Mock(IdentityService)
    def identityProvider = Stub(ManagerIdentityProvider)
    def securityManager = new ActiveMQORSecurityManager(brokerService, executorService, identityService, identityProvider)
    Subject connectionSubject = null
    def connection = Stub(RemotingConnection) {
      getSubject() >> { connectionSubject }
      setSubject(_ as Subject) >> { Subject subject -> connectionSubject = subject }
      getRemoteAddress() >> "tcp://127.0.0.1:1883"
      getClientID() >> "862000000006159"
    }

    when:
    def subject = securityManager.authenticate("broker.example/862000000006159", "", connection, null)

    then:
    subject.is(connectionSubject)
    subject.principals.any {
      it instanceof UserPrincipal && it.name == ActiveMQORSecurityManager.ANONYMOUS_USERNAME
    }
    subject.principals.any {
      it instanceof RolePrincipal && it.name == ActiveMQORSecurityManager.ANONYMOUS_USERNAME
    }
    subject.principals.any {
      it instanceof RemotingConnectionPrincipal
    }
    0 * identityService.authenticate(_, _, _)
  }
}
