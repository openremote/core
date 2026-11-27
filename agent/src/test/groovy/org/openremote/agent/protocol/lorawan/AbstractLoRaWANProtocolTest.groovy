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
package org.openremote.agent.protocol.lorawan

import org.openremote.agent.protocol.lorawan.chirpstack.ChirpStackAgent
import org.openremote.agent.protocol.lorawan.tts.TheThingsStackAgent
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.syslog.SyslogCategory
import org.openremote.model.util.ValueUtil
import spock.lang.Specification

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

class AbstractLoRaWANProtocolTest extends Specification {

  def setupSpec() {
    ValueUtil.initialise(null)
  }

  def "#agentType.simpleName warns when a device asset type lacks the mandatory devEUI attribute"() {
    given:
    def agent = agentType.getConstructor(String).newInstance("LoRaWAN agent")
    agent.setMqttHost("mqtt.example.test")
    agent.setClientId("lorawan-test")
    def protocol = agent.getProtocolInstance()
    def record = new DeviceRecord(
            assetTypeName: ThingAsset.simpleName, name: "Test device", devEUI: "0011223344556677")
    def asset = new ThingAsset(record.name)
    def logger = SyslogCategory.getLogger(SyslogCategory.PROTOCOL, AbstractLoRaWANProtocol)
    List<LogRecord> records = []
    def handler = new Handler() {
              @Override
              void publish(LogRecord logRecord) {
                records.add(logRecord)
              }

              @Override
              void flush() {}

              @Override
              void close() {}
            }
    logger.addHandler(handler)

    when:
    def result = protocol.configureAsset(asset, record)

    then:
    result.empty
    asset.getAttribute(LoRaWANConstants.ATTRIBUTE_NAME_DEV_EUI).empty
    records.size() == 1
    records[0].level == Level.WARNING
    records[0].message.contains("the mandatory 'devEUI' attribute is missing")
    records[0].message.contains("type 'ThingAsset'")
    records[0].message.contains("device 'Test device'")
    records[0].message.contains("devEUI '0011223344556677'")
    records[0].message.contains(protocol.protocolInstanceUri)

    cleanup:
    logger?.removeHandler(handler)

    where:
    agentType << [ChirpStackAgent, TheThingsStackAgent]
  }
}
