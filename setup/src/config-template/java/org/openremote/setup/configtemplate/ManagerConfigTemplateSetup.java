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
package org.openremote.setup.configtemplate;

import static org.openremote.model.Constants.*;
import static org.openremote.model.value.MetaItemType.*;
import static org.openremote.model.value.ValueType.*;

import org.openremote.agent.protocol.bluetooth.mesh.BluetoothMeshAgent;
import org.openremote.agent.protocol.bluetooth.mesh.BluetoothMeshAgentLink;
import org.openremote.agent.protocol.http.HTTPAgent;
import org.openremote.agent.protocol.http.HTTPAgentLink;
import org.openremote.agent.protocol.knx.KNXAgent;
import org.openremote.agent.protocol.knx.KNXAgentLink;
import org.openremote.agent.protocol.mail.MailAgent;
import org.openremote.agent.protocol.mail.MailAgentLink;
import org.openremote.agent.protocol.modbus.ModbusAgentLink;
import org.openremote.agent.protocol.modbus.ModbusAgentLink.ModbusDataType;
import org.openremote.agent.protocol.modbus.ModbusAgentLink.ReadMemoryArea;
import org.openremote.agent.protocol.modbus.ModbusTcpAgent;
import org.openremote.agent.protocol.mqtt.MQTTAgent;
import org.openremote.agent.protocol.mqtt.MQTTAgentLink;
import org.openremote.agent.protocol.openweathermap.OpenWeatherMapAgent;
import org.openremote.agent.protocol.openweathermap.OpenWeatherMapAgentLink;
import org.openremote.agent.protocol.openweathermap.OpenWeatherMapProperty;
import org.openremote.agent.protocol.simulator.SimulatorAgent;
import org.openremote.agent.protocol.simulator.SimulatorAgentLink;
import org.openremote.agent.protocol.snmp.SNMPAgent;
import org.openremote.agent.protocol.snmp.SNMPAgentLink;
import org.openremote.agent.protocol.tcp.TCPAgent;
import org.openremote.agent.protocol.velbus.VelbusAgentLink;
import org.openremote.agent.protocol.velbus.VelbusTCPAgent;
import org.openremote.agent.protocol.websocket.WebsocketAgent;
import org.openremote.agent.protocol.websocket.WebsocketAgentLink;
import org.openremote.agent.protocol.zwave.ZWaveAgent;
import org.openremote.agent.protocol.zwave.ZWaveAgentLink;
import org.openremote.manager.setup.ManagerSetup;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.asset.agent.Agent;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.asset.agent.DefaultAgentLink;
import org.openremote.model.asset.impl.ThingAsset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.ValueConstraint;
import org.openremote.model.value.ValueDescriptor;
import org.openremote.setup.configtemplate.model.PumpAsset;

/**
 * Seeds one configured pump and three targets to copy its configuration onto, so exporting and
 * importing a configuration template can be tried end to end. Every target shows a different
 * outcome: a full import, an import that skips attributes, and an import from another asset type.
 * The pump's agent links follow the template parameter concept: three Modbus links share an agent
 * and two of them a unit id, and two simulator links use different agents. A separate asset holds
 * one attribute per agent link type, and another holds every agent.
 */
public class ManagerConfigTemplateSetup extends ManagerSetup {

  protected ThingAsset agentsParent;

  public ManagerConfigTemplateSetup(Container container) {
    super(container);
  }

  @Override
  public void onStart() throws Exception {
    super.onStart();

    // Not a group asset, as those only hold one asset type
    agentsParent = new ThingAsset("Agents");
    agentsParent.setRealm(MASTER_REALM);
    agentsParent = assetStorageService.merge(agentsParent);

    // Two simulator agents, so the import asks for the agent of each simulator link separately
    String simulator1AgentId = store(new SimulatorAgent("Pump Agent 1 (Simulator)"));
    String simulator2AgentId = store(new SimulatorAgent("Pump Agent 2 (Simulator)"));

    // An agent of every other agent link type, disabled so none of them connects
    String bluetoothMeshAgentId = storeDisabled(new BluetoothMeshAgent("Bluetooth Mesh Agent"));
    String httpAgentId = storeDisabled(new HTTPAgent("HTTP Agent"));
    String knxAgentId = storeDisabled(new KNXAgent("KNX Agent"));
    String mailAgentId = storeDisabled(new MailAgent("Mail Agent"));
    String modbusAgentId = storeDisabled(new ModbusTcpAgent("Modbus TCP Agent"));
    String mqttAgentId = storeDisabled(new MQTTAgent("MQTT Agent"));
    String openWeatherMapAgentId = storeDisabled(new OpenWeatherMapAgent("OpenWeatherMap Agent"));
    String snmpAgentId = storeDisabled(new SNMPAgent("SNMP Agent"));
    String tcpAgentId = storeDisabled(new TCPAgent("TCP Agent"));
    String velbusAgentId = storeDisabled(new VelbusTCPAgent("Velbus TCP Agent"));
    String websocketAgentId = storeDisabled(new WebsocketAgent("Websocket Agent"));
    String zWaveAgentId = storeDisabled(new ZWaveAgent("Z-Wave Agent"));

    // The pump to export from; every attribute holds configuration items worth copying
    PumpAsset configured = new PumpAsset("Pump 1 (configured)");
    configured.setRealm(MASTER_REALM);
    configured.addOrReplaceAttributes(
        new Attribute<>(PumpAsset.FLOW_RATE)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Flow rate"),
                new MetaItem<>(
                    UNITS, Constants.units(UNITS_METRE, UNITS_CUBED, UNITS_PER, UNITS_HOUR)),
                new MetaItem<>(READ_ONLY, true),
                new MetaItem<>(STORE_DATA_POINTS, true),
                new MetaItem<>(RULE_STATE, true),
                new MetaItem<>(
                    AGENT_LINK,
                    modbusLink(modbusAgentId, 1, ReadMemoryArea.HOLDING, ModbusDataType.REAL, 1))),
        new Attribute<>(PumpAsset.PRESSURE)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Pressure"),
                new MetaItem<>(UNITS, Constants.units(UNITS_BAR)),
                new MetaItem<>(
                    CONSTRAINTS,
                    ValueConstraint.constraints(
                        new ValueConstraint.Min(0), new ValueConstraint.Max(16))),
                new MetaItem<>(STORE_DATA_POINTS, true),
                new MetaItem<>(
                    AGENT_LINK,
                    modbusLink(modbusAgentId, 1, ReadMemoryArea.HOLDING, ModbusDataType.REAL, 3))),
        new Attribute<>(PumpAsset.RUNNING)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Running"),
                new MetaItem<>(ACCESS_PUBLIC_READ, true),
                new MetaItem<>(
                    AGENT_LINK,
                    modbusLink(modbusAgentId, 2, ReadMemoryArea.COIL, ModbusDataType.BOOL, 1))),
        // Not a descriptor attribute, so a target asset can be missing it
        new Attribute<>("errorCode", TEXT)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Error code"),
                new MetaItem<>(RULE_STATE, true),
                new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(simulator1AgentId))),
        // Not a descriptor attribute, so a target asset can hold it with another value type
        new Attribute<>("serialNumber", TEXT)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Serial number"),
                new MetaItem<>(READ_ONLY, true),
                new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(simulator2AgentId))));
    assetStorageService.merge(configured);

    // Holds every attribute of the configured pump, so the whole configuration can be imported
    PumpAsset bare = new PumpAsset("Pump 2 (import target)");
    bare.setRealm(MASTER_REALM);
    bare.addOrReplaceAttributes(
        new Attribute<>("errorCode", TEXT), new Attribute<>("serialNumber", TEXT));
    assetStorageService.merge(bare);

    // Misses errorCode and holds serialNumber as a number, so the import reports both as skipped
    PumpAsset partial = new PumpAsset("Pump 3 (partially compatible)");
    partial.setRealm(MASTER_REALM);
    partial.addOrReplaceAttributes(new Attribute<>("serialNumber", NUMBER));
    assetStorageService.merge(partial);

    // Another asset type, sharing only some attributes with the pump
    ThingAsset otherType = new ThingAsset("Mixer 1 (other asset type)");
    otherType.setRealm(MASTER_REALM);
    otherType.addOrReplaceAttributes(
        new Attribute<>("flowRate", NUMBER), new Attribute<>("pressure", NUMBER));
    assetStorageService.merge(otherType);

    // One attribute per agent link type; "(requirements)" marks links with fields that must be set
    ThingAsset agentLinks = new ThingAsset("Agent links (all types)");
    agentLinks.setRealm(MASTER_REALM);
    agentLinks.addOrReplaceAttributes(
        linked(
            "bluetoothMesh",
            BOOLEAN,
            "Bluetooth mesh (requirements)",
            new BluetoothMeshAgentLink(bluetoothMeshAgentId, 0, "0002", "GenericOnOffServer")),
        linked("default", TEXT, "Default", new DefaultAgentLink(tcpAgentId)),
        linked("http", TEXT, "HTTP (requirements)", new HTTPAgentLink(httpAgentId)),
        linked(
            "knx",
            BOOLEAN,
            "KNX (requirements)",
            new KNXAgentLink(knxAgentId, "1.001", "1/0/1", "1/0/2")),
        linked("mail", TEXT, "Mail", new MailAgentLink(mailAgentId)),
        linked("modbus", NUMBER, "Modbus", new ModbusAgentLink(modbusAgentId)),
        linked("mqtt", TEXT, "MQTT", new MQTTAgentLink(mqttAgentId)),
        linked(
            "openWeatherMap",
            NUMBER,
            "OpenWeatherMap (requirements)",
            new OpenWeatherMapAgentLink(openWeatherMapAgentId)
                .setWeatherProperty(OpenWeatherMapProperty.TEMPERATURE)),
        linked("simulator", NUMBER, "Simulator", new SimulatorAgentLink(simulator1AgentId)),
        linked(
            "snmp",
            TEXT,
            "SNMP (requirements)",
            new SNMPAgentLink(snmpAgentId, "1.3.6.1.2.1.1.3.0")),
        linked(
            "velbus",
            BOOLEAN,
            "Velbus (requirements)",
            new VelbusAgentLink(velbusAgentId, 48, "CH1")),
        linked("websocket", TEXT, "Websocket", new WebsocketAgentLink(websocketAgentId)),
        linked("zWave", BOOLEAN, "Z-Wave", new ZWaveAgentLink(zWaveAgentId, 2, 0, "Basic")));
    assetStorageService.merge(agentLinks);
  }

  protected String store(Agent<?, ?, ?> agent) {
    agent.setParent(agentsParent);
    return assetStorageService.merge(agent).getId();
  }

  protected String storeDisabled(Agent<?, ?, ?> agent) {
    agent.setDisabled(true);
    return store(agent);
  }

  protected static ModbusAgentLink modbusLink(
      String agentId, int unitId, ReadMemoryArea area, ModbusDataType type, int address) {
    ModbusAgentLink link = new ModbusAgentLink(agentId);
    link.setUnitId(unitId);
    link.setReadMemoryArea(area);
    link.setReadValueType(type);
    link.setReadAddress(address);
    return link;
  }

  protected static Attribute<?> linked(
      String name, ValueDescriptor<?> type, String label, AgentLink<?> agentLink) {
    return new Attribute<>(name, type)
        .addOrReplaceMeta(new MetaItem<>(LABEL, label), new MetaItem<>(AGENT_LINK, agentLink));
  }
}
