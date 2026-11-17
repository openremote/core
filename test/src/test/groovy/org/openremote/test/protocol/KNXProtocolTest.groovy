/*
 * Copyright 2017, OpenRemote Inc.
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
package org.openremote.test.protocol


import org.openremote.agent.protocol.knx.KNXAgent
import org.openremote.agent.protocol.knx.KNXAgentLink
import org.openremote.agent.protocol.knx.KNXProtocol
import org.openremote.manager.agent.AgentService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.model.Constants
import org.openremote.model.asset.agent.ConnectionStatus
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import tuwien.auto.calimero.server.Launcher

import java.nio.file.Path

import static org.openremote.model.value.MetaItemType.AGENT_LINK
import static org.openremote.model.value.MetaItemType.LABEL
import static org.openremote.model.value.ValueType.BOOLEAN

/**
 * This tests the KNX protocol and protocol implementation.
 */
class KNXProtocolTest extends Specification implements ManagerContainerTrait {

  def "Check KNX protocol"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the KNX emulation server is started"
    def configFile = "/org/openremote/test/protocol/knx/knx-server-config.xml"
    def configUri = getClass().getResource(configFile).toURI()
    // Calimero server uses NetworkInterface.getByName for interface lookup which is un-reliable so we find it at runtime
    def configStr = getClass().getResource(configFile).text
    def ni = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress())
    configStr = configStr.replaceAll("LOOPBACK_ADDRESS", ni.getName())
    def file = Path.of(configUri).toFile()
    if (file.exists()) {
      def w = file.newWriter()
      file.newWriter().withWriter {
        it << configStr
      }
    }
    def knxEmulationServer = new Launcher(configUri.toString())
    def knxServerThread = new Thread(knxEmulationServer)
    knxServerThread.start()
    KNXTestingNetworkLink knxTestingNetwork

    expect: "the testing network to become available"
    conditions.eventually {
      knxTestingNetwork = KNXTestingNetworkLink.getInstance()
      assert knxTestingNetwork != null
    }

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def assetStorageService = container.getService(AssetStorageService.class)
    def agentService = container.getService(AgentService.class)
    def assetProcessingService = container.getService(AssetProcessingService.class)


    when: "KNX agents are created"

    // Some machines have multiple IPs associated with the loopback interface e.g.
    // lo0: flags=8049<UP,LOOPBACK,RUNNING,MULTICAST> mtu 16384
    // options=1203<RXCSUM,TXCSUM,TXSTATUS,SW_TIMESTAMP>
    //         inet 127.0.0.1 netmask 0xff000000
    // inet6 ::1 prefixlen 128
    // inet6 fe80::1%lo0 prefixlen 64 scopeid 0x1
    // inet 127.51.68.120 netmask 0xff000000
    // nd6 options=201<PERFORMNUD,DAD>
    // Use the same code as Calimero uses to find the address to listen on
    def loopbackIP = ni.inetAddresses()
            .filter { it instanceof Inet4Address }
            .findFirst()
            .map { it.hostAddress }
            .orElse("127.0.0.1")
    def knxAgent1 = new KNXAgent("KNX Agent 1")
            .setHost(loopbackIP)
            .setBindHost(loopbackIP)
            .setRealm(Constants.MASTER_REALM)
    def knxAgent2 = new KNXAgent("KNX Agent 2")
            .setRealm(Constants.MASTER_REALM)

    knxAgent1 = assetStorageService.merge(knxAgent1)
    knxAgent2 = assetStorageService.merge(knxAgent2)

    then: "a protocol instance should be created for the valid agent but not the invalid one"
    conditions.eventually {
      assert agentService.getAgent(knxAgent1.id) != null
      assert agentService.getAgent(knxAgent2.id) != null
      assert agentService.getAgent(knxAgent1.id).getAgentStatus().orElse(null) == ConnectionStatus.CONNECTED
      assert agentService.getAgent(knxAgent2.id).getAgentStatus().orElse(null) == ConnectionStatus.ERROR
      assert agentService.getProtocolInstance(knxAgent1.id) != null
      assert agentService.getProtocolInstance(knxAgent2.id) == null
    }

    when: "a thing asset is created that links it's attributes to the valid knx agent"
    def knxThing = new ThingAsset("Living Room Asset")
            .setParent(knxAgent1)
            .addOrReplaceAttributes(
            new Attribute<>("light1ToggleOnOff", BOOLEAN)
            .addOrReplaceMeta(
                    new MetaItem<>(LABEL, "Light 1 Toggle On/Off"),
                    new MetaItem<>(AGENT_LINK, new KNXAgentLink(knxAgent1.id, "1.001", "1/0/17", "0/4/14"))
                    )
    )
    knxThing = assetStorageService.merge(knxThing)

    then: "the living room thing to be fully deployed"
    conditions.eventually {
      def protocol = (KNXProtocol) agentService.getProtocolInstance(knxAgent1.id)
      def attributeRef = new AttributeRef(knxThing.id, "light1ToggleOnOff")
      synchronized (protocol.attributeStatusMap) {
        assert protocol.attributeStatusMap.get(attributeRef) != null
      }
      // Status monitoring is registered before commands, so wait for both before writing.
      synchronized (protocol.attributeActionMap) {
        assert protocol.attributeActionMap.get(attributeRef) != null
      }
    }

    when: "change light1ToggleOnOff value to 'true'"
    def switchChange = new AttributeEvent(knxThing.getId(), "light1ToggleOnOff", true)
    assetProcessingService.sendAttributeEvent(switchChange)

    then: "the correct data should arrive on KNX bus"
    conditions.eventually {
      assert knxTestingNetwork.getLastDataReceived() == "0081"
    }

    when: "change light1ToggleOnOff value to 'false'"
    switchChange = new AttributeEvent(knxThing.getId(), "light1ToggleOnOff", false)
    assetProcessingService.sendAttributeEvent(switchChange)

    then: "the correct data should arrive on KNX bus"
    conditions.eventually {
      assert knxTestingNetwork.getLastDataReceived() == "0080"
    }

    cleanup: "the server should be stopped"
    if (knxThing != null) {
      assetStorageService.delete([knxThing.id])
    }
    if (knxAgent1 != null) {
      assetStorageService.delete([knxAgent1.id])
    }
    if (knxAgent2 != null) {
      assetStorageService.delete([knxAgent2.id])
    }
    if (knxEmulationServer != null) {
      knxEmulationServer.quit()
    }
    if (knxTestingNetwork != null) {
      knxTestingNetwork.close()
    }
  }
}
