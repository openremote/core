/*
 * Copyright 2021, OpenRemote Inc.
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
package org.openremote.model.util;

import cz.habarta.typescript.generator.Extension;
import cz.habarta.typescript.generator.Settings;
import cz.habarta.typescript.generator.emitter.EmitterExtensionFeatures;
import cz.habarta.typescript.generator.emitter.TsModel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.openremote.agent.protocol.AgentModelProvider;
import org.openremote.model.Constants;
import org.openremote.model.rules.Ruleset;
import org.openremote.model.value.MetaItemDescriptor;

/**
 * Outputs enums for well known Asset types, attribute names and meta item names Outputs fields from
 * {@link org.openremote.model.Constants} that begin with "UNITS" as a UNITS enum
 */
public class AssetModelInfoExtension extends Extension {

  public AssetModelInfoExtension() {
    // Ensure the asset model is initialized
    ValueUtil.initialise(null);
    // Service loader doesn't seem to work so manually load the agent model provider
    ValueUtil.getModelProviders().add(new AgentModelProvider());
    ValueUtil.doInitialise();
  }

  @Override
  public EmitterExtensionFeatures getFeatures() {
    final EmitterExtensionFeatures features = new EmitterExtensionFeatures();
    features.generatesRuntimeCode = true;
    return features;
  }

  @SuppressWarnings("rawtypes")
  @Override
  public void emitElements(Writer writer, Settings settings, boolean exportKeyword, TsModel model) {

    Map<String, String> assetMap = new HashMap<>();
    Map<String, String> otherMap = new HashMap<>();

    Arrays.stream(ValueUtil.getAssetInfos(null))
        .forEach(
            assetModelInfo -> {
              String assetDescriptorName = assetModelInfo.getAssetDescriptor().getName();
              assetMap.put(assetDescriptorName.toUpperCase(Locale.ROOT), assetDescriptorName);

              // Store attributes
              assetModelInfo
                  .getAttributeDescriptors()
                  .values()
                  .forEach(
                      attributeDescriptor -> {
                        String attributeName = attributeDescriptor.getName();
                        otherMap.put(attributeName.toUpperCase(Locale.ROOT), attributeName);
                      });
            });

    emitEnum(writer, "WellknownAssets", assetMap);

    writer.writeIndentedLine("");
    emitEnum(writer, "WellknownAttributes", otherMap);

    otherMap.clear();
    ValueUtil.getMetaItemDescriptors()
        .values()
        .forEach(
            metaItemDescriptor -> {
              String metaName = metaItemDescriptor.getName();
              otherMap.put(metaName.toUpperCase(Locale.ROOT), metaName);
            });
    writer.writeIndentedLine("");
    emitEnum(writer, "WellknownMetaItems", otherMap);

    otherMap.clear();
    ValueUtil.getValueDescriptors()
        .values()
        .forEach(
            valueDescriptor -> {
              String valueTypeName = valueDescriptor.getName();
              otherMap.put(
                  valueTypeName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", ""),
                  valueTypeName);
            });
    writer.writeIndentedLine("");
    emitEnum(writer, "WellknownValueTypes", otherMap);

    otherMap.clear();
    Arrays.stream(Constants.class.getFields())
        .filter(f -> f.getName().startsWith("UNITS_"))
        .forEach(
            unitField -> {
              String unitName = unitField.getName().substring(6);
              try {
                otherMap.put(unitName.toUpperCase(Locale.ROOT), (String) unitField.get(null));
              } catch (IllegalAccessException e) {
                e.printStackTrace();
              }
            });
    writer.writeIndentedLine("");
    emitEnum(writer, "WellknownUnitTypes", otherMap);

    otherMap.clear();
    Arrays.stream(Ruleset.class.getFields())
        .filter(f -> f.getType() == MetaItemDescriptor.class)
        .forEach(
            rulesetMeta -> {
              try {
                MetaItemDescriptor metaItemDescriptor = (MetaItemDescriptor) rulesetMeta.get(null);
                otherMap.put(
                    metaItemDescriptor
                        .getName()
                        .toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9]", ""),
                    metaItemDescriptor.getName());
              } catch (IllegalAccessException e) {
                e.printStackTrace();
              }
            });
    writer.writeIndentedLine("");
    emitEnum(writer, "WellknownRulesetMetaItems", otherMap);
  }

  protected void emitEnum(Writer writer, String name, Map<String, String> values) {
    writer.writeIndentedLine("export const enum " + name + " {");
    int last = values.size();
    AtomicInteger counter = new AtomicInteger(1);
    values.forEach(
        (itemName, itemValue) -> {
          writer.writeIndentedLine(
              "    "
                  + itemName
                  + " = \""
                  + itemValue
                  + "\""
                  + (last != counter.getAndIncrement() ? "," : ""));
        });
    writer.writeIndentedLine("}");
  }
}
