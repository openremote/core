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
import org.openremote.model.value.AttributeDescriptor;
import org.openremote.model.value.ValueConstraint;
import org.openremote.setup.configtemplate.model.AgentLinksAsset;
import org.openremote.setup.configtemplate.model.MixerAsset;
import org.openremote.setup.configtemplate.model.PumpAsset;

/**
 * Seeds one configured pump and three targets to copy its configuration onto, so exporting and
 * importing a configuration template can be tried end to end. Every target shows a different
 * outcome: a full import, an import that skips missing attributes, and an import into another asset
 * type that also skips an attribute of another value type and a custom attribute. Custom
 * attributes, added to an asset rather than defined by its asset type, take no part in a template,
 * so the pump's maintenance note is not exported. The pump's agent links follow the template
 * parameter concept: three Modbus links share an agent and two of them a unit id, and two simulator
 * links use different agents. A separate asset holds one attribute per agent link type, and another
 * holds every agent.
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
        new Attribute<>(PumpAsset.ERROR_CODE)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Error code"),
                new MetaItem<>(RULE_STATE, true),
                new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(simulator1AgentId))),
        new Attribute<>(PumpAsset.SERIAL_NUMBER)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Serial number"),
                new MetaItem<>(READ_ONLY, true),
                new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(simulator2AgentId))),
        // A custom attribute, so it takes no part in a template
        new Attribute<>("maintenanceNote", TEXT)
            .addOrReplaceMeta(new MetaItem<>(LABEL, "Maintenance note")));
    assetStorageService.merge(configured);

    // Holds every attribute of the configured pump, so the whole configuration can be imported
    PumpAsset bare = new PumpAsset("Pump 2 (import target)");
    bare.setRealm(MASTER_REALM);
    bare.addOrReplaceAttributes(
        new Attribute<>(PumpAsset.ERROR_CODE), new Attribute<>(PumpAsset.SERIAL_NUMBER));
    assetStorageService.merge(bare);

    // Misses the optional errorCode and serialNumber, so the import reports both as skipped
    PumpAsset partial = new PumpAsset("Pump 3 (partially compatible)");
    partial.setRealm(MASTER_REALM);
    assetStorageService.merge(partial);

    // Another asset type, sharing flowRate and pressure with the pump and holding serialNumber as a
    // number; running is a custom attribute of the mixer, so the import skips both
    MixerAsset otherType = new MixerAsset("Mixer 1 (other asset type)");
    otherType.setRealm(MASTER_REALM);
    otherType.addOrReplaceAttributes(new Attribute<>("running", BOOLEAN));
    assetStorageService.merge(otherType);

    // One attribute per agent link type; "(requirements)" marks links with fields that must be set
    AgentLinksAsset agentLinks = new AgentLinksAsset("Agent links (all types)");
    agentLinks.setRealm(MASTER_REALM);
    agentLinks.addOrReplaceAttributes(
        linked(
            AgentLinksAsset.BLUETOOTH_MESH,
            "Bluetooth mesh (requirements)",
            new BluetoothMeshAgentLink(bluetoothMeshAgentId, 0, "0002", "GenericOnOffServer")),
        linked(AgentLinksAsset.DEFAULT, "Default", new DefaultAgentLink(tcpAgentId)),
        linked(AgentLinksAsset.HTTP, "HTTP (requirements)", new HTTPAgentLink(httpAgentId)),
        linked(
            AgentLinksAsset.KNX,
            "KNX (requirements)",
            new KNXAgentLink(knxAgentId, "1.001", "1/0/1", "1/0/2")),
        linked(AgentLinksAsset.MAIL, "Mail", new MailAgentLink(mailAgentId)),
        linked(AgentLinksAsset.MODBUS, "Modbus", new ModbusAgentLink(modbusAgentId)),
        linked(AgentLinksAsset.MQTT, "MQTT", new MQTTAgentLink(mqttAgentId)),
        linked(
            AgentLinksAsset.OPEN_WEATHER_MAP,
            "OpenWeatherMap (requirements)",
            new OpenWeatherMapAgentLink(openWeatherMapAgentId)
                .setWeatherProperty(OpenWeatherMapProperty.TEMPERATURE)),
        linked(AgentLinksAsset.SIMULATOR, "Simulator", new SimulatorAgentLink(simulator1AgentId)),
        linked(
            AgentLinksAsset.SNMP,
            "SNMP (requirements)",
            new SNMPAgentLink(snmpAgentId, "1.3.6.1.2.1.1.3.0")),
        linked(
            AgentLinksAsset.VELBUS,
            "Velbus (requirements)",
            new VelbusAgentLink(velbusAgentId, 48, "CH1")),
        linked(AgentLinksAsset.WEBSOCKET, "Websocket", new WebsocketAgentLink(websocketAgentId)),
        linked(AgentLinksAsset.Z_WAVE, "Z-Wave", new ZWaveAgentLink(zWaveAgentId, 2, 0, "Basic")));
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
      AttributeDescriptor<?> descriptor, String label, AgentLink<?> agentLink) {
    return new Attribute<>(descriptor)
        .addOrReplaceMeta(new MetaItem<>(LABEL, label), new MetaItem<>(AGENT_LINK, agentLink));
  }
}
