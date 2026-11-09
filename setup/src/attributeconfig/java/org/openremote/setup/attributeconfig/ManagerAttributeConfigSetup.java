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
package org.openremote.setup.attributeconfig;

import static org.openremote.model.Constants.*;
import static org.openremote.model.value.MetaItemType.*;
import static org.openremote.model.value.ValueType.*;

import org.openremote.agent.protocol.simulator.SimulatorAgent;
import org.openremote.agent.protocol.simulator.SimulatorAgentLink;
import org.openremote.manager.setup.ManagerSetup;
import org.openremote.model.Constants;
import org.openremote.model.Container;
import org.openremote.model.asset.impl.ThingAsset;
import org.openremote.model.attribute.Attribute;
import org.openremote.model.attribute.MetaItem;
import org.openremote.model.value.ValueConstraint;
import org.openremote.setup.attributeconfig.model.PumpAsset;

/**
 * Seeds one configured pump and three targets to copy its attribute configuration onto, so the
 * export and import of attribute configuration can be tried end to end. Every target shows a
 * different outcome: a full import, an import that skips attributes, and an import from another
 * asset type.
 */
public class ManagerAttributeConfigSetup extends ManagerSetup {

  public ManagerAttributeConfigSetup(Container container) {
    super(container);
  }

  @Override
  public void onStart() throws Exception {
    super.onStart();

    SimulatorAgent agent = new SimulatorAgent("Pump Agent (Simulator)");
    agent.setRealm(MASTER_REALM);
    agent = assetStorageService.merge(agent);
    String agentId = agent.getId();

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
                new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agentId))),
        new Attribute<>(PumpAsset.PRESSURE)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Pressure"),
                new MetaItem<>(UNITS, Constants.units(UNITS_BAR)),
                new MetaItem<>(
                    CONSTRAINTS,
                    ValueConstraint.constraints(
                        new ValueConstraint.Min(0), new ValueConstraint.Max(16))),
                new MetaItem<>(STORE_DATA_POINTS, true)),
        new Attribute<>(PumpAsset.RUNNING)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Running"),
                new MetaItem<>(ACCESS_PUBLIC_READ, true),
                new MetaItem<>(AGENT_LINK, new SimulatorAgentLink(agentId))),
        // Not a descriptor attribute, so a target asset can be missing it
        new Attribute<>("errorCode", TEXT)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Error code"), new MetaItem<>(RULE_STATE, true)),
        // Not a descriptor attribute, so a target asset can hold it with another value type
        new Attribute<>("serialNumber", TEXT)
            .addOrReplaceMeta(
                new MetaItem<>(LABEL, "Serial number"), new MetaItem<>(READ_ONLY, true)));
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

    // Another asset type, so the import warns about the mismatch before importing what it can
    ThingAsset otherType = new ThingAsset("Mixer 1 (other asset type)");
    otherType.setRealm(MASTER_REALM);
    otherType.addOrReplaceAttributes(
        new Attribute<>("flowRate", NUMBER), new Attribute<>("pressure", NUMBER));
    assetStorageService.merge(otherType);
  }
}
