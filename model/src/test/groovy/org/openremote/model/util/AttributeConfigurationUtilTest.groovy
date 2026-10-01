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
package org.openremote.model.util

import org.openremote.model.asset.AttributeConfigurationDocument
import org.openremote.model.asset.impl.RoomAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.MetaItem
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueDescriptor
import org.openremote.model.value.ValueType
import spock.lang.Specification

import static org.openremote.model.asset.AttributeConfigurationImportFailure.*

class AttributeConfigurationUtilTest extends Specification {

  def setupSpec() {
    ValueUtil.initialise(null)
  }

  static def thingAsset(Map<String, Attribute<?>> attributes = [:]) {
    def asset = new ThingAsset("Test Thing")
    attributes.each { name, attribute -> asset.getAttributes().put(name, attribute) }
    return asset
  }

  static def attribute(String name, ValueDescriptor type, Map<String, Object> meta = null) {
    def attribute = new Attribute<>(name, type)
    meta?.each { metaName, value -> attribute.addMeta(new MetaItem<>(metaName, null, value)) }
    return attribute
  }

  static String document(String assetType = ThingAsset.DESCRIPTOR.name, Map<String, String> entries = [:], Integer version = 1) {
    def attributes = entries.collect { name, entry -> "\"${name}\": ${entry}" }.join(",")
    def fields = []
    if (version != null) {
      fields.add("\"version\": ${version}")
    }
    if (assetType != null) {
      fields.add("\"assetType\": \"${assetType}\"")
    }
    fields.add("\"attributes\": {${attributes}}")
    return "{${fields.join(",")}}"
  }

  def "export holds the type and metadata of attributes that have metadata"() {
    given: "an asset with configured and unconfigured attributes"
    def asset = thingAsset([
      temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature", unknownMetaItem: [nested: 1]]),
      plain : attribute("plain", ValueType.TEXT)
    ])

    when: "the configuration is exported"
    def exported = AttributeConfigurationUtil.export(asset, null)

    then: "the document is of the supported version and holds the asset type"
    exported.version == AttributeConfigurationDocument.VERSION
    exported.assetType == ThingAsset.DESCRIPTOR.name

    and: "only the attribute with metadata is held, with its type and its metadata as stored"
    exported.attributes.keySet() == ["temperature"] as Set
    exported.attributes.temperature.type == ValueType.NUMBER.name
    exported.attributes.temperature.meta.get(MetaItemType.LABEL.name).asText() == "Temperature"
    exported.attributes.temperature.meta.get("unknownMetaItem").get("nested").asInt() == 1
  }

  def "export limited to a selection holds only the selected attributes"() {
    given: "an asset with two configured attributes"
    def asset = thingAsset([
      temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature"]),
      humidity : attribute("humidity", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Humidity"])
    ])

    expect: "the selection is exported"
    AttributeConfigurationUtil.export(asset, ["humidity"]).attributes.keySet() == ["humidity"] as Set
  }

  def "a document that cannot be read is rejected with #failure"() {
    given: "an asset with a configured attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature"])])

    expect: "the import is rejected"
    AttributeConfigurationUtil.preview(asset, documentText).failure == failure

    where:
    failure | documentText
    INVALID_JSON | "not json at all"
    INVALID_JSON | "[]"
    INVALID_JSON | null
    MISSING_REQUIRED_FIELD | document(ThingAsset.DESCRIPTOR.name, [temperature: '{"type": "number", "meta": {}}'], null)
    MISSING_REQUIRED_FIELD | document(null, [temperature: '{"type": "number", "meta": {}}'])
    MISSING_REQUIRED_FIELD | '{"version": 1, "assetType": "ThingAsset"}'
    UNSUPPORTED_VERSION | document(ThingAsset.DESCRIPTOR.name, [temperature: '{"type": "number", "meta": {}}'], 2)
    UNSUPPORTED_VERSION | document(ThingAsset.DESCRIPTOR.name, [temperature: '{"type": "number", "meta": {}}'], 0)
    MALFORMED_ATTRIBUTE_ENTRY | '{"version": 1, "assetType": "ThingAsset", "attributes": 5}'
    MALFORMED_ATTRIBUTE_ENTRY | document(ThingAsset.DESCRIPTOR.name, [temperature: '5'])
    MALFORMED_ATTRIBUTE_ENTRY | document(ThingAsset.DESCRIPTOR.name, [temperature: '{"meta": {}}'])
    MALFORMED_ATTRIBUTE_ENTRY | document(ThingAsset.DESCRIPTOR.name, [temperature: '{"type": "number"}'])
    MALFORMED_ATTRIBUTE_ENTRY | document(ThingAsset.DESCRIPTOR.name, [temperature: '{"type": "number", "meta": 5}'])
    NO_IMPORTABLE_ATTRIBUTES | document(ThingAsset.DESCRIPTOR.name, [:])
  }

  def "an attribute missing on the target asset is skipped and reported"() {
    given: "an asset without the imported attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature"])])

    when: "a document holding a known and an unknown attribute is previewed"
    def preview = AttributeConfigurationUtil.preview(asset, document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"type": "number", "meta": {"label": "Imported"}}',
      humidity : '{"type": "number", "meta": {"label": "Imported"}}'
    ]))

    then: "the unknown attribute is reported as missing and is not patched"
    preview.failure == null
    preview.missingAttributes*.name == ["humidity"]
    preview.importableAttributes*.name == ["temperature"]
    !preview.patchedAttributes.has("humidity")
  }

  def "an attribute whose type differs is skipped and reported"() {
    given: "an asset holding the imported attribute as text"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.TEXT, [(MetaItemType.LABEL.name): "Temperature"])])

    when: "a document holding the attribute as a number is previewed"
    def preview = AttributeConfigurationUtil.preview(asset, document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"type": "number", "meta": {"label": "Imported"}}'
    ]))

    then: "nothing can be imported, with the mismatch reported"
    preview.failure == NO_IMPORTABLE_ATTRIBUTES
    preview.typeMismatches*.name == ["temperature"]
    preview.typeMismatches*.importedType == [ValueType.NUMBER.name]
    preview.typeMismatches*.targetType == [ValueType.TEXT.name]
  }

  def "type comparison is case sensitive"() {
    given: "an asset holding a number attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature"])])

    when: "a document spelling the same type differently is previewed"
    def preview = AttributeConfigurationUtil.preview(asset, document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"type": "Number", "meta": {"label": "Imported"}}',
      humidity : '{"type": "number", "meta": {"label": "Imported"}}'
    ]))

    then: "the differently spelled type is a mismatch rather than a match"
    preview.typeMismatches*.name == ["temperature"]
    preview.typeMismatches*.importedType == ["Number"]
    preview.typeMismatches*.targetType == [ValueType.NUMBER.name]
  }

  def "a document exported from another asset type is reported but can still be imported"() {
    given: "a thing asset holding a configured attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature"])])

    when: "a document exported from a room asset is previewed"
    def preview = AttributeConfigurationUtil.preview(asset, document(RoomAsset.DESCRIPTOR.name, [
      temperature: '{"type": "number", "meta": {"label": "Imported"}}'
    ]))

    then: "the mismatch is reported without preventing the import"
    preview.failure == null
    preview.assetTypeMismatch
    preview.importedAssetType == RoomAsset.DESCRIPTOR.name
    preview.targetAssetType == ThingAsset.DESCRIPTOR.name
    preview.importableAttributes*.name == ["temperature"]
  }

  def "the metadata of an importable attribute is replaced as a whole"() {
    given: "an asset whose attribute holds metadata that the document does not"
    def asset = thingAsset([
      temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature", (MetaItemType.READ_ONLY.name): true]),
      plain : attribute("plain", ValueType.TEXT)
    ])
    asset.getAttributes().get("temperature").get().setValue(21.5d)

    when: "a document holding other metadata for that attribute is previewed"
    def preview = AttributeConfigurationUtil.preview(asset, document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"type": "number", "meta": {"unitType": ["celsius"]}}',
      plain : '{"type": "text", "meta": {"label": "Plain"}}'
    ]))

    then: "the imported metadata replaces the existing metadata entirely"
    def patchedTemperature = preview.patchedAttributes.get("temperature")
    patchedTemperature.get("meta").get("unitType").get(0).asText() == "celsius"
    !patchedTemperature.get("meta").has(MetaItemType.LABEL.name)
    !patchedTemperature.get("meta").has(MetaItemType.READ_ONLY.name)

    and: "an attribute without metadata gets the imported metadata added"
    preview.patchedAttributes.get("plain").get("meta").get("label").asText() == "Plain"

    and: "the type and the value of the attribute are untouched"
    patchedTemperature.get("type").asText() == ValueType.NUMBER.name
    patchedTemperature.get("value").asDouble() == 21.5d

    and: "the asset itself is not modified"
    asset.getAttributes().get("temperature").get().getMeta().has(MetaItemType.LABEL.name)
    !asset.getAttributes().get("plain").get().getMeta().has("label")
  }

  def "an internal name field within an entry is ignored"() {
    given: "an asset holding a configured attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER, [(MetaItemType.LABEL.name): "Temperature"])])

    when: "a document whose entry names another attribute is previewed"
    def preview = AttributeConfigurationUtil.preview(asset, document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"name": "somethingElse", "type": "number", "meta": {"label": "Imported"}}'
    ]))

    then: "the map key decides which attribute is imported"
    preview.importableAttributes*.name == ["temperature"]
    preview.patchedAttributes.get("temperature").get("meta").get("label").asText() == "Imported"
  }
}
