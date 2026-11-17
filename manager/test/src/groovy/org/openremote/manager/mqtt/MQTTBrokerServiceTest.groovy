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

import com.google.common.cache.CacheBuilder
import org.apache.activemq.artemis.api.core.ActiveMQException
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType
import org.apache.activemq.artemis.core.remoting.FailureListener
import org.apache.activemq.artemis.core.remoting.server.RemotingService
import org.apache.activemq.artemis.core.server.ActiveMQServer
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection
import spock.lang.Specification

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

class MQTTBrokerServiceTest extends Specification {

  private MQTTBrokerService brokerService = new MQTTBrokerService()
  private FailureListener failureListener

  def setup() {
    def remotingService = Stub(RemotingService) {
      getConnections() >> Collections.emptySet()
    }
    def server = Stub(ActiveMQServer) {
      getRemotingService() >> remotingService
    }
    brokerService.@server = Stub(EmbeddedActiveMQ) {
      getActiveMQServer() >> server
    }
    brokerService.@disconnectedConnectionCache = CacheBuilder.newBuilder().build()
  }

  def "a last will can resolve its connection during disconnect cleanup"() {
    given: "a connection whose transport has already closed"
    def connection = connection("original")
    def connections = new ConnectionMap()
    connections.put("client", connection)
    brokerService.@clientIDConnectionMap = connections
    brokerService.afterCreateConnection(connection)

    and: "a publish lookup interleaved with removing the active connection"
    RemotingConnection resolvedConnection
    connections.afterRemove = {
      resolvedConnection = brokerService.getConnectionFromClientID("client")
    }

    when: "disconnect cleanup removes the connection from the active map"
    failureListener.connectionFailed(new ActiveMQException(ActiveMQExceptionType.DISCONNECTED), false)

    then: "the publish can still resolve the connection for the last will"
    resolvedConnection.is(connection)
    brokerService.getConnectionFromClientID("client").is(connection)
    connections.isEmpty()
  }

  def "a delayed disconnect callback preserves a reconnected client's connection"() {
    given: "a client that reconnected before its old disconnect callback runs"
    def originalConnection = connection("original")
    def replacementConnection = connection("replacement")
    brokerService.afterCreateConnection(originalConnection)
    brokerService.@clientIDConnectionMap.put("client", replacementConnection)

    when: "the original connection's disconnect callback runs"
    failureListener.connectionFailed(new ActiveMQException(ActiveMQExceptionType.DISCONNECTED), false)

    then: "the replacement remains active and is not recorded as disconnected"
    brokerService.@clientIDConnectionMap.get("client").is(replacementConnection)
    brokerService.getConnectionFromClientID("client").is(replacementConnection)
    brokerService.@disconnectedConnectionCache.getIfPresent("client") == null
  }

  def "a late old disconnect does not replace the newer disconnected connection"() {
    given: "a client that reconnected before its old disconnect callback runs"
    def originalConnection = connection("original")
    def replacementConnection = connection("replacement")
    brokerService.afterCreateConnection(originalConnection)
    def originalFailureListener = failureListener
    brokerService.afterCreateConnection(replacementConnection)
    def replacementFailureListener = failureListener
    brokerService.@clientIDConnectionMap.put("client", replacementConnection)

    when: "the replacement also disconnects"
    replacementFailureListener.connectionFailed(new ActiveMQException(ActiveMQExceptionType.DISCONNECTED), false)

    then: "its connection can be resolved for pending messages"
    brokerService.getConnectionFromClientID("client").is(replacementConnection)

    when: "the original connection's callback arrives late"
    originalFailureListener.connectionFailed(new ActiveMQException(ActiveMQExceptionType.DISCONNECTED), false)

    then: "pending messages still resolve the latest connection"
    brokerService.getConnectionFromClientID("client").is(replacementConnection)
  }

  private RemotingConnection connection(String id) {
    Stub(RemotingConnection) {
      getID() >> id
      getClientID() >> "client"
      addFailureListener(_) >> { FailureListener listener -> failureListener = listener }
    }
  }

  /** Runs a concurrent publish lookup at the point where the active entry has just been removed. */
  private static class ConnectionMap extends ConcurrentHashMap<String, RemotingConnection> {
    Runnable afterRemove

    @Override
    RemotingConnection remove(Object key) {
      def removed = super.remove(key)
      afterRemove.run()
      return removed
    }

    @Override
    boolean remove(Object key, Object value) {
      def removed = super.remove(key, value)
      afterRemove.run()
      return removed
    }

    @Override
    RemotingConnection computeIfPresent(String key, BiFunction<? super String, ? super RemotingConnection, ? extends RemotingConnection> remappingFunction) {
      def remaining = super.computeIfPresent(key, remappingFunction)
      afterRemove.run()
      return remaining
    }
  }
}
