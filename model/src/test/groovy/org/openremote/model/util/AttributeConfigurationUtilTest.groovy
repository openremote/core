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

import com.fasterxml.jackson.databind.JsonNode
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

  static String document(String assetType = ThingAsset.DESCRIPTOR.name, Map<String, String> entries = [:], Integer version = 1, String genericParameters = null) {
    def attributes = entries.collect { name, entry -> "\"${name}\": ${entry}" }.join(",")
    def fields = []
    if (version != null) {
      fields.add("\"version\": ${version}")
    }
    if (assetType != null) {
      fields.add("\"assetType\": \"${assetType}\"")
    }
    if (genericParameters != null) {
      fields.add("\"genericParameters\": ${genericParameters}")
    }
    fields.add("\"attributes\": {${attributes}}")
    return "{${fields.join(",")}}"
  }

  static Map<String, JsonNode> values(Map<String, Object> entries) {
    return entries.collectEntries { name, value ->
      [(name): ValueUtil.JSON.valueToTree(value)]
    } as Map<String, JsonNode>
  }

  /** A pump whose attributes are linked to the same agent, each at another write value. */
  static def linkedPump() {
    return thingAsset([
      pumpOn : attribute("pumpOn", ValueType.BOOLEAN, [
        (MetaItemType.LABEL.name) : "Pump on",
        (MetaItemType.AGENT_LINK.name): [id: "agent1", type: "DefaultAgentLink", writeValue: "on", updateOnWrite: true]
      ]),
      error : attribute("error", ValueType.BOOLEAN, [
        (MetaItemType.LABEL.name) : "Error",
        (MetaItemType.READ_ONLY.name) : true,
        (MetaItemType.UNITS.name) : ["celsius"],
        (MetaItemType.AGENT_LINK.name): [id: "agent1", type: "DefaultAgentLink", writeValue: "error", updateOnWrite: true]
      ]),
      plain : attribute("plain", ValueType.TEXT)
    ])
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

  def "generic path candidates are the values held equally by every selected attribute holding them"() {
    given: "a pump whose attributes share an agent but differ in label and write value"
    def asset = linkedPump()

    when: "the candidates of all configured attributes are found"
    def candidates = AttributeConfigurationUtil.findGenericPathCandidates(AttributeConfigurationUtil.export(asset, null).attributes)

    then: "shared values and values held by a single attribute are candidates, differing values and arrays are not"
    candidates.keySet() as List == [
      "meta.agentLink.id",
      "meta.agentLink.type",
      "meta.agentLink.updateOnWrite",
      "meta.readOnly"
    ]
    candidates["meta.agentLink.id"] == ["error", "pumpOn"]
    candidates["meta.readOnly"] == ["error"]

    when: "the candidates of a single attribute are found"
    def selected = AttributeConfigurationUtil.findGenericPathCandidates(AttributeConfigurationUtil.export(asset, ["pumpOn"]).attributes)

    then: "the value that differs from the unselected attribute is a candidate"
    selected.containsKey("meta.agentLink.writeValue")
  }

  def "a generic parameter name is derived from its path as #name"() {
    expect:
    AttributeConfigurationUtil.deriveGenericParameterName(path) == name

    where:
    path | name
    "meta.agentLink.id" | "agentLinkId"
    "meta.agentLink.unitId" | "agentLinkUnitId"
    "meta.label" | "label"
  }

  def "export leaves a generic value out of every attribute holding it and records where it belongs"() {
    given: "a pump whose attributes share an agent"
    def asset = linkedPump()

    when: "the configuration is exported with the agent link id as a generic path"
    def exported = AttributeConfigurationUtil.export(asset, null, ["meta.agentLink.id"])

    then: "a single parameter named after the path records its type and both attribute paths"
    exported.version == AttributeConfigurationDocument.VERSION
    exported.genericParameters.keySet() == ["agentLinkId"] as Set
    exported.genericParameters.agentLinkId.type == ValueType.TEXT.name
    exported.genericParameters.agentLinkId.paths == ["attributes.error.meta.agentLink.id", "attributes.pumpOn.meta.agentLink.id"]

    and: "the value is removed from both attributes while the rest of their metadata is kept"
    !exported.attributes.error.meta.get("agentLink").has("id")
    !exported.attributes.pumpOn.meta.get("agentLink").has("id")
    exported.attributes.error.meta.get("agentLink").get("writeValue").asText() == "error"
    exported.attributes.pumpOn.meta.get(MetaItemType.LABEL.name).asText() == "Pump on"

    and: "the asset itself keeps the value, so exporting without generic paths still holds it"
    def plain = AttributeConfigurationUtil.export(asset, null, [])
    plain.genericParameters == null
    plain.attributes.error.meta.get("agentLink").get("id").asText() == "agent1"
  }

  def "export rejects the generic path #path"() {
    given: "a pump whose attributes share an agent but differ in write value"
    def asset = linkedPump()

    when: "the configuration is exported with a path that cannot be generic"
    AttributeConfigurationUtil.export(asset, null, [path])

    then: "the export is rejected"
    thrown(IllegalArgumentException)

    where:
    path << [
      "meta.agentLink.writeValue",
      "meta.units",
      "meta.agentLink",
      "meta.absent",
      "agentLink.id"
    ]
  }

  def "a generic parameter is asked for once and applied to every attribute holding it"() {
    given: "a document exported with the agent link id of two attributes as a generic parameter"
    def document = ValueUtil.asJSON(AttributeConfigurationUtil.export(linkedPump(), null, ["meta.agentLink.id"])).orElseThrow()

    and: "a pump that is not linked yet"
    def target = thingAsset([
      pumpOn: attribute("pumpOn", ValueType.BOOLEAN),
      error : attribute("error", ValueType.BOOLEAN)
    ])

    when: "the document is previewed without a value"
    def preview = AttributeConfigurationUtil.preview(target, document)

    then: "the single parameter is reported with both attribute paths"
    preview.failure == GENERIC_PARAMETER_VALUES_REQUIRED
    preview.importableAttributes*.name == ["error", "pumpOn"]
    preview.genericParameters.keySet() == ["agentLinkId"] as Set
    preview.genericParameters.agentLinkId.type == ValueType.TEXT.name
    preview.genericParameters.agentLinkId.paths == ["attributes.error.meta.agentLink.id", "attributes.pumpOn.meta.agentLink.id"]
    preview.patchedAttributes == null

    when: "the document is previewed with a value for the parameter"
    preview = AttributeConfigurationUtil.preview(target, document, values([agentLinkId: "agent2"]))

    then: "the value is applied to both attributes, next to the metadata that was exported"
    preview.failure == null
    preview.patchedAttributes.get("error").get("meta").get("agentLink").get("id").asText() == "agent2"
    preview.patchedAttributes.get("pumpOn").get("meta").get("agentLink").get("id").asText() == "agent2"
    preview.patchedAttributes.get("pumpOn").get("meta").get("agentLink").get("writeValue").asText() == "on"
  }

  def "a generic parameter held only by skipped attributes needs no value"() {
    given: "a document with a generic parameter held by the error attribute alone"
    def document = ValueUtil.asJSON(AttributeConfigurationUtil.export(linkedPump(), null, ["meta.readOnly"])).orElseThrow()

    and: "a pump without that attribute"
    def target = thingAsset([pumpOn: attribute("pumpOn", ValueType.BOOLEAN)])

    when: "the document is previewed without a value"
    def preview = AttributeConfigurationUtil.preview(target, document)

    then: "the remaining attribute is imported without asking for the parameter"
    preview.failure == null
    preview.missingAttributes*.name == ["error"]
    preview.genericParameters.isEmpty()
    preview.patchedAttributes.get("pumpOn").get("meta").get(MetaItemType.LABEL.name).asText() == "Pump on"
  }

  def "a generic parameter value is checked against the type #type and the metadata schema"() {
    given: "an asset holding a linked number attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER)])

    and: "a document with a generic parameter for one of its metadata values"
    def documentText = document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"type": "number", "meta": {"readOnly": true, "agentLink": {"id": "agent1", "type": "DefaultAgentLink"}}}'
    ], 1, "{\"parameter\": {\"type\": \"${type}\", \"paths\": [\"attributes.temperature.meta.${path}\"]}}")

    when: "the document is previewed with the value"
    def preview = AttributeConfigurationUtil.preview(asset, documentText, values([parameter: value]))

    then: "the value is accepted only when it fits"
    preview.failure == (valid ? null : INVALID_GENERIC_PARAMETER_VALUE)
    preview.invalidGenericParameters == (valid ? [] : ["parameter"])

    where:
    type | path | value | valid
    "text" | "agentLink.id" | "agent2" | true
    "text" | "agentLink.id" | 5 | false
    "boolean" | "readOnly" | false | true
    "boolean" | "readOnly" | "yes" | false
    "integer" | "custom" | 5 | true
    "integer" | "custom" | 1.5 | false
    "long" | "custom" | 5 | true
    "number" | "custom" | 5 | true
    "text" | "readOnly" | "notABoolean" | false
    "text" | "agentLink.updateOnWrite" | "sometimes" | false
  }

  def "a malformed generic parameter declaration is rejected"() {
    given: "an asset holding a number attribute"
    def asset = thingAsset([temperature: attribute("temperature", ValueType.NUMBER)])

    expect: "the import is rejected"
    AttributeConfigurationUtil.preview(asset, document(ThingAsset.DESCRIPTOR.name, [
      temperature: '{"type": "number", "meta": {"label": "Temperature", "agentLink": {"id": "agent1", "type": "DefaultAgentLink"}}}'
    ], 1, genericParameters), values([p: "value", q: "value"])).failure == MALFORMED_GENERIC_PARAMETER

    where:
    genericParameters << [
      '5',
      '{"p": 5}',
      '{"p": {"paths": ["attributes.temperature.meta.label"]}}',
      '{"p": {"type": "color", "paths": ["attributes.temperature.meta.label"]}}',
      '{"p": {"type": "text"}}',
      '{"p": {"type": "text", "paths": []}}',
      '{"p": {"type": "text", "paths": [5]}}',
      '{"p": {"type": "text", "paths": ["temperature.meta.label"]}}',
      '{"p": {"type": "text", "paths": ["attributes.temperature.label"]}}',
      '{"p": {"type": "text", "paths": ["attributes.temperature.meta.label."]}}',
      '{"p": {"type": "text", "paths": ["attributes.absent.meta.label"]}}',
      '{"p": {"type": "text", "paths": ["attributes.temperature.meta.missing.id"]}}',
      '{"p": {"type": "text", "paths": ["attributes.temperature.meta.agentLink"]}}',
      '{"p": {"type": "text", "paths": ["attributes.temperature.meta.label"]}, "q": {"type": "text", "paths": ["attributes.temperature.meta.label"]}}'
    ]
  }
}
