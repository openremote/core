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
package org.openremote.test.model


import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.ws.rs.WebApplicationException
import org.jboss.resteasy.api.validation.ViolationReport
import org.openremote.agent.protocol.http.HTTPAgentLink
import org.openremote.agent.protocol.simulator.SimulatorAgent
import org.openremote.agent.protocol.velbus.VelbusTCPAgent
import org.openremote.container.persistence.PersistenceService
import org.openremote.container.timer.TimerService
import org.openremote.model.attribute.AttributeLink
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.manager.asset.AssetModelService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.*
import org.openremote.model.asset.agent.AgentDescriptor
import org.openremote.model.asset.agent.AgentLink
import org.openremote.model.asset.agent.DefaultAgentLink
import org.openremote.model.asset.impl.GroupAsset
import org.openremote.model.asset.impl.LightAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.attribute.AttributeMap
import org.openremote.model.attribute.MetaItem
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.util.TimeUtil
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.*
import org.openremote.model.value.impl.ColourRGB
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.setup.integration.model.asset.ModelTestAsset
import org.openremote.setup.integration.protocol.http.HTTPServerTestAgent
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.time.Instant

import java.lang.reflect.Array
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.container.security.IdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.util.MapAccess.getString
import static org.openremote.model.Constants.*
import static org.openremote.model.value.ValueType.BIG_NUMBER

// TODO: Define new asset model tests (setValue - equality checking etc.)
class AssetModelTest extends Specification implements ManagerContainerTrait {

  @Shared
  static AssetModelResource assetModelResource
  @Shared
  static AssetStorageService assetStorageService

  static String CUSTOM_ASSET_TYPE = "CustomAsset"
  static ValueDescriptor[] dynamicValueDescriptors = [
    new ValueDescriptor("dynamicValue", null, ValueConstraint.constraints(new ValueConstraint.AllowedValues("value1", "value2")), null, null, null)
  ]

  def setupSpec() {
    // A dynamic asset type is added
    def storageDir = Paths.get(PersistenceService.OR_STORAGE_DIR_DEFAULT, AssetModelService.DIRECTORY_NAME)
    Files.createDirectories(storageDir)
    MetaItemDescriptor[] dynamicMetaDescriptors = [new MetaItemDescriptor("dynamicMeta", dynamicValueDescriptors[0])]
    AttributeDescriptor[] dynamicAttributeDescriptors = [
      new AttributeDescriptor<>("dynamic1", dynamicValueDescriptors[0]),
      new AttributeDescriptor<>("dynamic2", ValueType.POSITIVE_INTEGER).withOptional(true)
    ]
    def dynamicAssetDescriptor = new AssetDescriptor(CUSTOM_ASSET_TYPE, "", "")
    def assetTypeInfo = new AssetTypeInfo(dynamicAssetDescriptor, dynamicAttributeDescriptors, dynamicMetaDescriptors, dynamicValueDescriptors)
    def assetTypeInfoStr = AssetModelService.JSON.writeValueAsString(assetTypeInfo)
    Files.writeString(storageDir.resolve("CustomAsset"), assetTypeInfoStr, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    startContainer(defaultConfig(), defaultServices())
    // Ensure the asset model is re-initialized (just in case container is reused)
    container.getService(AssetModelService).initDynamicModel()
    ValueUtil.doInitialise()
    assetModelResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM).proxy(AssetModelResource.class)
    assetStorageService = container.getService(AssetStorageService.class)
  }

  def "Check AttributeMap equality checking"() {
    given: "required services"
    def managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)

    when: "we take the AttributeMap of an asset and serialize it then deserialize it"
    def apartment1 = assetStorageService.find(managerTestSetup.apartment1Id)
    def attributeMapJson = ValueUtil.asJSON(apartment1.attributes).orElseThrow()
    def attributes1 = ValueUtil.parse(attributeMapJson, AttributeMap).orElse(null)
    def attributes2 = ValueUtil.parse(attributeMapJson, AttributeMap).orElse(null)

    then: "attributes should be equal"
    Objects.equals(attributes1, attributes2)
  }

  def "Serialize/Deserialize asset and attribute events and test validation"() {

    given: "an authenticated admin user"
    def accessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )
    stopPseudoClock()

    and: "the asset resource"
    def assetResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, accessToken).proxy(AssetResource.class)

    expect: "the custom asset type to be loaded"
    ValueUtil.getAssetDescriptor("CustomAsset").isPresent()

    when: "A well known asset is instantiated"
    def persistenceService = container.getService(PersistenceService.class)
    def timerService = container.getService(TimerService.class)
    def modelTestAsset = new ModelTestAsset("Test Asset")
    modelTestAsset.setRealm("master")

    and: "try to save the asset without meeting constraints on required attributes"
    assetResource.create(null, modelTestAsset)

    then: "a constraint violation exception should be thrown"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }
    def report = ex.response.readEntity(ViolationReport)
    report.propertyViolations.size() == 1
    report.propertyViolations.get(0).path == "attributes[positiveInt].value"
    report.propertyViolations.get(0).message == "must not be null"
    report.classViolations.size() == 1
    report.classViolations.get(0).path == ""
    report.classViolations.get(0).message == "Asset is not valid"

    when: "the issue is fixed and the asset is saved again"
    modelTestAsset.getAttribute(ModelTestAsset.REQUIRED_POSITIVE_INT_ATTRIBUTE_DESCRIPTOR).map(attr -> attr.setValue(1))
    modelTestAsset = assetResource.create(null, modelTestAsset)

    then: "the save should succeed"
    modelTestAsset != null
    modelTestAsset.getAttribute(ModelTestAsset.REQUIRED_POSITIVE_INT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == 1

    when: "other optional attributes are added with valid values"
    modelTestAsset.addAttributes(
            new Attribute<>(ModelTestAsset.NEGATIVE_INT_ATTRIBUTE_DESCRIPTOR, -1),
            new Attribute<>(ModelTestAsset.SIZE_STRING_ATTRIBUTE_DESCRIPTOR, "abcde"),
            new Attribute<>(ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR, [
              ("key1"): 1d,
              ("key2"): 2d,
              ("key3"): 3d
            ] as ValueType.DoubleMap
            ),
            new Attribute<>(ModelTestAsset.SIZE_ARRAY_ATTRIBUTE_DESCRIPTOR, ["arrayValue1", "arrayValue2"] as String[]),
            new Attribute<>(ModelTestAsset.ALLOWED_VALUES_ENUM_ATTRIBUTE_DESCRIPTOR, ModelTestAsset.TestValue.ENUM_2).addMeta(
                    new MetaItem<>(MetaItemType.CONSTRAINTS, ValueConstraint.constraints(new ValueConstraint.NotNull()))
                    ),
            new Attribute<>(ModelTestAsset.ALLOWED_VALUES_STRING_ATTRIBUTE_DESCRIPTOR, "Allowed1"),
            new Attribute<>(ModelTestAsset.ALLOWED_VALUES_NUMBER_ATTRIBUTE_DESCRIPTOR, 1.5d),
            new Attribute<>(ModelTestAsset.PAST_TIMESTAMP_ATTRIBUTE_DESCRIPTOR, timerService.getCurrentTimeMillis()-1000L),
            new Attribute<>(ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR, timerService.getNow().toDate()),
            new Attribute<>(ModelTestAsset.FUTURE_ISO8601_ATTRIBUTE_DESCRIPTOR, DateTimeFormatter.ISO_INSTANT.format(timerService.getNow().plusMillis(100000))),
            new Attribute<>(ModelTestAsset.FUTURE_OR_PRESENT_TIMESTAMP_ATTRIBUTE_DESCRIPTOR, timerService.getCurrentTimeMillis()+100000),
            new Attribute<>(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR, "abcde"),
            new Attribute<>(ModelTestAsset.NOT_EMPTY_ARRAY_ATTRIBUTE_DESCRIPTOR, [1] as Integer[]),
            new Attribute<>(ModelTestAsset.NOT_EMPTY_MAP_ATTRIBUTE_DESCRIPTOR, [
              ("key1"): true
            ] as ValueType.BooleanMap),
            new Attribute<>(ModelTestAsset.NOT_BLANK_STRING_ATTRIBUTE_DESCRIPTOR, "abcde"),
            new Attribute<>(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR, new ModelTestAsset.TestObject(110, true)),
            // Custom attributes
            new Attribute<>("custom1", null, 456), // Any value can go in here
            new Attribute<>("custom2", ValueType.GEO_JSON_POINT, new GeoJSONPoint(1.234, 5.678)).addMeta(
                    new MetaItem<>(MetaItemType.CONSTRAINTS, ValueConstraint.constraints(new ValueConstraint.NotNull()))
                    )
    )
    def updatedModelTestAsset = assetResource.update(null, modelTestAsset.id, modelTestAsset)

    then: "the save should succeed and correct values should be stored"
    modelTestAsset != null
    modelTestAsset.getAttribute(ModelTestAsset.REQUIRED_POSITIVE_INT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == 1
    modelTestAsset.getAttribute(ModelTestAsset.NEGATIVE_INT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == -1
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == "abcde"
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).size() == 3
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).get("key1") == 1d
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).get("key2") == 2d
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).get("key3") == 3d
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_ARRAY_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).size() == 2
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_ARRAY_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null)[0] == "arrayValue1"
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_ARRAY_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null)[1] == "arrayValue2"
    modelTestAsset.getAttribute(ModelTestAsset.ALLOWED_VALUES_ENUM_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == ModelTestAsset.TestValue.ENUM_2
    modelTestAsset.getAttribute(ModelTestAsset.ALLOWED_VALUES_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == "Allowed1"
    modelTestAsset.getAttribute(ModelTestAsset.ALLOWED_VALUES_NUMBER_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == 1.5d
    modelTestAsset.getAttribute(ModelTestAsset.PAST_TIMESTAMP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) <timerService.getCurrentTimeMillis()
    modelTestAsset.getAttribute(ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).getTime() <= timerService.getCurrentTimeMillis()
    modelTestAsset.getAttribute(ModelTestAsset.FUTURE_ISO8601_ATTRIBUTE_DESCRIPTOR).map {
      TimeUtil.parseTimeIso8601(it.value.orElse(null)).toEpochMilli()
    }.orElse(null) > timerService.getCurrentTimeMillis()
    modelTestAsset.getAttribute(ModelTestAsset.FUTURE_OR_PRESENT_TIMESTAMP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) >= timerService.getCurrentTimeMillis()
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == "abcde"
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_MAP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).size() == 1
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_MAP_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).get("key1") == true
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_ARRAY_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null).length == 1
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_ARRAY_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null)[0] == 1
    modelTestAsset.getAttribute(ModelTestAsset.NOT_BLANK_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == "abcde"
    modelTestAsset.getAttribute("custom1").flatMap {it.value}.orElse(null) == 456
    modelTestAsset.getAttribute("custom2").flatMap {it.value}.orElse(null).x == 1.234d
    modelTestAsset.getAttribute("custom2").flatMap {it.value}.orElse(null).y == 5.678d
    modelTestAsset.getAttribute(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    } != null
    modelTestAsset.getAttribute(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) != null
    modelTestAsset.getAttribute(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.get().range == 110
    modelTestAsset.getAttribute(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.get().active != null
    modelTestAsset.getAttribute(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.get().active.booleanValue()

    when: "The attribute values violate the constraints"
    modelTestAsset.getAttribute(ModelTestAsset.REQUIRED_POSITIVE_INT_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = -1
    }
    modelTestAsset.getAttribute(ModelTestAsset.NEGATIVE_INT_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = 1
    }
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_STRING_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = "abc"
    }
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = [
        ("key1"): 1d
      ] as ValueType.DoubleMap
    }
    modelTestAsset.getAttribute(ModelTestAsset.SIZE_ARRAY_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = ["arrayValue1"] as String[]
    }
    modelTestAsset.getAttribute(ModelTestAsset.ALLOWED_VALUES_ENUM_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = null
    }
    modelTestAsset.getAttribute(ModelTestAsset.ALLOWED_VALUES_STRING_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = "NotAllowed"
    }
    modelTestAsset.getAttribute(ModelTestAsset.ALLOWED_VALUES_NUMBER_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = 3.0d
    }
    modelTestAsset.getAttribute(ModelTestAsset.PAST_TIMESTAMP_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = timerService.getCurrentTimeMillis() + 100000
    }
    modelTestAsset.getAttribute(ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = timerService.getNow().plusMillis(100000).toDate()
    }
    modelTestAsset.getAttribute(ModelTestAsset.FUTURE_ISO8601_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = DateTimeFormatter.ISO_INSTANT.format(timerService.getNow().minus(1, ChronoUnit.DAYS))
    }
    modelTestAsset.getAttribute(ModelTestAsset.FUTURE_OR_PRESENT_TIMESTAMP_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = timerService.getNow().minus(1, ChronoUnit.DAYS).toEpochMilli()
    }
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = ""
    }
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_MAP_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = [] as ValueType.BooleanMap
    }
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_ARRAY_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = [] as Integer[]
    }
    modelTestAsset.getAttribute(ModelTestAsset.NOT_BLANK_STRING_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = "      "
    }
    modelTestAsset.getAttribute(ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = new ModelTestAsset.TestObject(90, null)
    }
    modelTestAsset.getAttribute("custom1").ifPresent {it.value = 123}
    modelTestAsset.getAttribute("custom2").ifPresent {it.value = null}
    modelTestAsset = assetResource.update(null, modelTestAsset.id, modelTestAsset)

    then: "a constraint violation exception should be thrown"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "the report is extracted"
    report = ex.response.readEntity(ViolationReport)

    then: "it should contain all the errors"
    report.propertyViolations.size() == 19
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.REQUIRED_POSITIVE_INT_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be greater than or equal to 0"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.NEGATIVE_INT_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be less than or equal to 0"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.SIZE_STRING_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "size must be between 5 and 10"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.SIZE_MAP_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "size must be between 2 and 3"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.SIZE_ARRAY_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "size must be between 2 and 3"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.ALLOWED_VALUES_ENUM_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must not be null"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.ALLOWED_VALUES_STRING_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be one of [Allowed1, Allowed2]"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.ALLOWED_VALUES_NUMBER_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be one of [1.5, 2.5]"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.PAST_TIMESTAMP_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be a past date"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be a date in the past or in the present"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.FUTURE_ISO8601_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be a future date"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.FUTURE_OR_PRESENT_TIMESTAMP_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must be a date in the present or in the future"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must not be empty"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.NOT_EMPTY_MAP_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must not be empty"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.NOT_EMPTY_ARRAY_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "must not be empty"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.NOT_BLANK_STRING_ATTRIBUTE_DESCRIPTOR.name}].value" && it.message == "Not blank custom message"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR.name}].value.range" && it.message == "must be greater than or equal to 100"
    }
    report.propertyViolations.any {
      it.path == "attributes[${ModelTestAsset.OBJECT_ATTRIBUTE_DESCRIPTOR.name}].value.active" && it.message == "must not be null"
    }
    report.classViolations.size() == 1
    report.classViolations.get(0).path == ""
    report.classViolations.get(0).message == "Asset is not valid"

    when: "we convert the asset to JSON object so we can manipulate it"
    modelTestAsset = updatedModelTestAsset // Reset attributes to valid values
    def assetObjectNode = ValueUtil.convert(modelTestAsset, ObjectNode) as ObjectNode

    and: "we simulate an attribute descriptor being removed or changed (by changing the attribute name)"
    def noDescriptorAttr = ((ObjectNode)assetObjectNode.get("attributes")).remove(ModelTestAsset.PAST_TIMESTAMP_ATTRIBUTE_DESCRIPTOR.name) as ObjectNode
    noDescriptorAttr.put("name", "missingAttr")
    ((ObjectNode)assetObjectNode.get("attributes")).set("missingAttr", noDescriptorAttr)

    and: "we simulate a meta item descriptor being removed or changed (by changing the meta item name)"
    def noDescriptorMeta = ((ObjectNode)((ObjectNode)((ObjectNode)assetObjectNode.get("attributes")).get(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR.name)).get("meta")).remove(MetaItemType.CONSTRAINTS.name)
    ((ObjectNode)((ObjectNode)((ObjectNode)assetObjectNode.get("attributes")).get(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR.name)).get("meta")).set("missingMeta", noDescriptorMeta)

    and: "we simulate a value descriptor being removed or changed (by changing the type name of an attribute)"
    ((ObjectNode)((ObjectNode)assetObjectNode.get("attributes")).get(ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR.name)).put("type", "missingType")

    and: "we simulate a value descriptor being removed on a custom attribute (by changing the type name of the attribute)"
    ((ObjectNode)((ObjectNode)assetObjectNode.get("attributes")).get("custom1")).put("type", "missingType2")

    and: "we deserialize the object back into an Asset"
    modelTestAsset = ValueUtil.parse(assetObjectNode.toString(), Asset).orElse(null) as ModelTestAsset

    then: "the attribute with the missing attribute descriptor should still have the correct value type and value should have deserialized correctly"
    modelTestAsset.getAttribute("missingAttr").map {it.type}.orElse(null) == ValueType.TIMESTAMP
    modelTestAsset.getAttribute("missingAttr").flatMap {it.value}.orElse(null) instanceof Long

    and: "the meta item with the missing meta item descriptor should now have no value type and the value should be an array of generic maps representing the value constraints"
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.getMeta().get("missingMeta")
    }.orElse(null) != null
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.getMeta().get("missingMeta")
    }.orElse(null).type == null
    modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.getMeta().get("missingMeta")
    }.flatMap {
      it.value
    }.orElse(null).getClass().isArray()
    Array.getLength(modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.getMeta().get("missingMeta")
    }.flatMap {
      it.value
    }.orElse(null)) == 1
    Array.get(modelTestAsset.getAttribute(ModelTestAsset.NOT_EMPTY_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.getMeta().get("missingMeta")
    }.flatMap {
      it.value
    }.orElse(null), 0) instanceof Map

    and: "the well known attribute with the missing value type should have the correct value type"
    modelTestAsset.getAttribute(ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR.name).map {
      it.type
    }.orElse(null) == ModelTestAsset.PAST_OR_PRESENT_DATE_ATTRIBUTE_DESCRIPTOR.type

    and: "the custom attribute with the missing value type should have no value type but the value should still be there"
    modelTestAsset.getAttribute("custom1").map {it.type}.isEmpty()
    modelTestAsset.getAttribute("custom1").flatMap {it.value}.orElse(null) == 456

    when: "the attribute with the missing attribute descriptor now violates its' previous constraint"
    modelTestAsset.getAttribute("missingAttr").ifPresent {
      it.value = timerService.getCurrentTimeMillis() + 100000
    }

    and: "this asset is now merged"
    modelTestAsset = assetResource.update(null, modelTestAsset.id, modelTestAsset)

    then: "it should succeed and attribute value should be correct"
    modelTestAsset != null
    modelTestAsset.getAttribute("missingAttr").flatMap {
      it.value
    }.orElse(null) == timerService.getCurrentTimeMillis() + 100000

    when: "we simulate a missing asset type"
    def updateResult = persistenceService.doReturningTransaction {em ->
      def query = em.createNativeQuery("update asset set type = 'MissingAssetType' where id = '" + modelTestAsset.id + "';")
      return query.executeUpdate()
    }

    then: "it should have succeeded"
    updateResult == 1

    when: "the model test asset is now fetched"
    def missingAssetTypeAsset = assetResource.get(null, modelTestAsset.id)

    then: "the missing type string should still be present"
    missingAssetTypeAsset != null
    missingAssetTypeAsset.type == "MissingAssetType"

    when: "an attribute value violates one of the original asset type attribute descriptor constraints"
    missingAssetTypeAsset.getAttribute(ModelTestAsset.NOT_BLANK_STRING_ATTRIBUTE_DESCRIPTOR).ifPresent {
      it.value = "    "
    }
    missingAssetTypeAsset = assetResource.update(null, missingAssetTypeAsset.id, missingAssetTypeAsset)

    then: "the update should succeed because the descriptor is no longer associated with the asset"
    missingAssetTypeAsset.getAttribute(ModelTestAsset.NOT_BLANK_STRING_ATTRIBUTE_DESCRIPTOR).flatMap {
      it.value
    }.orElse(null) == "    "

    when: "a custom asset type is persisted (using dynamic CustomAsset)"
    def customAsset = new ThingAsset("Custom asset").setRealm(MASTER_REALM)
            .addAttributes(
            new Attribute<>("attr1", ValueType.TIMESTAMP, getClockTimeOf(container)),
            new Attribute<>("attr2", ValueType.POSITIVE_INTEGER, 3)
    )
    customAsset.type = CUSTOM_ASSET_TYPE
    customAsset = assetResource.create(null, customAsset)

    then: "a constraint violation exception should be thrown"
    ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == 400
      return true
    }

    when: "the report is extracted"
    report = ex.response.readEntity(ViolationReport)

    then: "the missing required attribute should be included"
    report.propertyViolations.size() == 1
    report.propertyViolations.get(0).path == "attributes.dynamic1"
    report.propertyViolations.get(0).message == "required attribute is missing"
    report.classViolations.size() == 1
    report.classViolations.get(0).path == ""
    report.classViolations.get(0).message == "Asset is not valid"

    when: "the issue is resolved"
    customAsset.addAttributes(
            new Attribute<>("dynamic1", dynamicValueDescriptors[0], "value1")
    )
    customAsset = assetResource.create(null, customAsset)

    then: "it should have been persisted"
    customAsset != null
    customAsset.type == "CustomAsset"
    customAsset.getAttribute("attr1").get().type == ValueType.TIMESTAMP
    customAsset.getAttribute("attr2").get().type == ValueType.POSITIVE_INTEGER
    customAsset.getAttribute("attr2").get().value.orElse(0) == 3

    when: "we fetch the custom asset again directly from the DB"
    customAsset = assetResource.get(null, customAsset.id)

    then: "it should still have the correct custom type"
    customAsset != null
    customAsset.type == "CustomAsset"
    customAsset.getAttribute("attr1").get().type == ValueType.TIMESTAMP
    customAsset.getAttribute("attr2").get().type == ValueType.POSITIVE_INTEGER
    customAsset.getAttribute("attr2").get().value.orElse(0) == 3

    when: "we make a change to the asset and merge it directly into the asset storage service (bypassing any cloning logic in AssetResource)"
    customAsset.getAttribute("dynamic1").ifPresent {it.setValue("value2")}
    customAsset = assetStorageService.merge(customAsset, false, null, null)

    then: "it should succeed"
    customAsset.getAttribute("dynamic1").flatMap {it.value}.orElse(null) == "value2"
    customAsset.type == "CustomAsset"
  }

  def "Retrieving all asset model info"() {

    when: "the asset type value descriptor is retrieved"
    def assetValueType = ValueUtil.getValueDescriptor(ValueType.ASSET_TYPE.getName())

    then: "it should contain an allowed values constraint with all asset types listed"
    assetValueType.isPresent()
    assetValueType.get().constraints != null
    assetValueType.get().constraints.find {
      it instanceof ValueConstraint.AllowedValues
    } as ValueConstraint.AllowedValues != null
    (assetValueType.get().constraints.find {
      it instanceof ValueConstraint.AllowedValues
    } as ValueConstraint.AllowedValues).allowedValues.length == ValueUtil.getAssetInfos().length
    (assetValueType.get().constraints.find {
      it instanceof ValueConstraint.AllowedValues
    } as ValueConstraint.AllowedValues).allowedValues.any {
      (it == GroupAsset.class.getSimpleName())
    }

    when: "all asset model infos are retrieved"
    def assetInfos = assetModelResource.getAssetInfos(null, null, null)

    then: "the asset model infos should be available"
    assetInfos.size() > 0
    assetInfos.size() == ValueUtil.assetInfoMap.size()
    def velbusTcpAgent = assetInfos.find {it.assetDescriptor.type == VelbusTCPAgent.class}
    velbusTcpAgent != null
    !velbusTcpAgent.attributeDescriptors.get(VelbusTCPAgent.HOST.name).optional
    !velbusTcpAgent.attributeDescriptors.get(VelbusTCPAgent.PORT.name).optional

    when: "all value descriptors are retrieved"
    def valueDescriptors = assetModelResource.getValueDescriptors(null, null)

    then: "the value descriptors should be correctly serialized/deserialized"
    valueDescriptors.size() > 0
    valueDescriptors.every {
      it.value.name == it.key && it.value.type != null & it.value.jsonType != null
    }
    valueDescriptors.get(ValueType.ASSET_QUERY.name) == ValueType.ASSET_QUERY
    valueDescriptors.get(ValueType.ASSET_QUERY.name).jsonType == ValueType.ASSET_QUERY.jsonType

    when: "represented as JSON"
    def valueDescriptorsJSON = ValueUtil.parse(ValueUtil.asJSON(valueDescriptors).orElse(null), Object).get()

    then: "should be a map of objects"
    valueDescriptorsJSON instanceof Map
    ((Map)valueDescriptorsJSON).size() == valueDescriptors.size()
    ((Map)valueDescriptorsJSON).get(ValueType.TIMESTAMP.name) instanceof Map

    when: "all meta item descriptors are retrieved"
    def metaItemDescriptors = assetModelResource.getMetaItemDescriptors(null, null)

    then: "the meta item descriptors should be correctly serialized/deserialized"
    metaItemDescriptors.size() > 0

    when: "represented as JSON"
    def metaItemDescriptorsJSON = ValueUtil.parse(ValueUtil.asJSON(metaItemDescriptors).orElse(null), Object).get()

    then: "should be an array of string values"
    metaItemDescriptorsJSON instanceof Map
    ((Map)metaItemDescriptorsJSON).size() == metaItemDescriptors.size()
    ((Map)metaItemDescriptorsJSON).get(MetaItemType.ATTRIBUTE_LINKS.name) instanceof Map
    ((Map)((Map)metaItemDescriptorsJSON).get(MetaItemType.ATTRIBUTE_LINKS.name)).get("name") == MetaItemType.ATTRIBUTE_LINKS.name
    ((Map)((Map)metaItemDescriptorsJSON).get(MetaItemType.ATTRIBUTE_LINKS.name)).get("type") == "${ValueType.ATTRIBUTE_LINK.name}[]"
  }

  def "Retrieving a specific asset model info"() {
    when: "The Thing Asset model info is retrieved"
    def thingAssetInfo = assetModelResource.getAssetInfo(null, null, ThingAsset.DESCRIPTOR.name)

    then: "the asset model should be available"
    thingAssetInfo != null
    thingAssetInfo.assetDescriptor != null
    thingAssetInfo.attributeDescriptors != null
    thingAssetInfo.metaItemDescriptors != null
    thingAssetInfo.valueDescriptors != null
    thingAssetInfo.attributeDescriptors.get(Asset.LOCATION.name) == Asset.LOCATION
    thingAssetInfo.metaItemDescriptors.contains(MetaItemType.AGENT_LINK)
    ValueUtil.getAssetDescriptor(ThingAsset.class) != null
    ValueUtil.getAgentDescriptor(SimulatorAgent.class) != null

    when: "the http test server agent descriptor is retrieved (the test asset model provider should have registered test agents and assets)"
    def testAgentDescriptor = ValueUtil.getAgentDescriptor(HTTPServerTestAgent.DESCRIPTOR.name).orElse(null)

    then: "the descriptor should have been found"
    assert testAgentDescriptor != null

    and: "the descriptor should contain an agent link schema"
    def schema = ValueUtil.getSchema(AgentLink.class)
    def schema2 = ValueUtil.getSchema(ValueType.IntegerMap.class)
    assert schema != null
    assert schema.get("oneOf") != null
    assert schema.get("oneOf").size() == ValueUtil.getAssetDescriptors(null).findAll {
      it instanceof AgentDescriptor
    }.collect {
      (it as AgentDescriptor).getAgentLinkClass()
    }.unique {
      it.getSimpleName()
    }.size()
    assert schema2 != null
    assert schema2.get("type").asText() == "object"
    assert schema2.get("additionalProperties") != null
    assert schema2.get("additionalProperties").get("type").asText() == "integer"
  }

  def "Serialize/Deserialize asset model"() {
    given: "An asset"
    def parentId = UniqueIdentifierGenerator.generateId()
    def createdDate = Instant.now()
    def asset = new LightAsset("Test light")
            .setId(UniqueIdentifierGenerator.generateId())
            .setRealm(MASTER_REALM)
            .setParentId(parentId)
            .setTemperature(100I)
            .setColourRGB(new ColourRGB(50, 100, 200))
            .addAttributes(
            new Attribute<>("testAttribute", BIG_NUMBER, 100.5, System.currentTimeMillis())
            .addOrReplaceMeta(
                    new MetaItem<>(MetaItemType.AGENT_LINK, new HTTPAgentLink("http_agent_id")
                    .setPath("test_path")
                    .setPagingMode(true))
                    )
    )
    asset.getAttribute(LightAsset.COLOUR_RGB).ifPresent({
      it.addOrReplaceMeta(
                      new MetaItem<>(MetaItemType.AGENT_LINK, new DefaultAgentLink("agent_id")
                      .setValueFilters(
                              [new SubStringValueFilter(0,10)] as ValueFilter[]
                              )
                      )
              )
    })
    // Inject properties which are normally done by the backend on retrieval
    asset.path = [asset.id, parentId]
    asset.createdOn = createdDate
    asset.getAttributes().values().forEach {it.setTimestamp(asset.createdOn.toEpochMilli())}

    expect: "the attributes to match the set values"
    asset.getTemperature().orElse(null) == 100I
    asset.getColourRGB().map{it.getR()}.orElse(null) == 50I
    asset.getColourRGB().map{it.getG()}.orElse(null) == 100I
    asset.getColourRGB().map{it.getB()}.orElse(null) == 200I

    when: "the asset is serialized using default object mapper"
    def assetStr = ValueUtil.asJSON(asset).orElse(null)

    then: "the string should be valid JSON"
    def assetObjectNode = ValueUtil.parse(assetStr, ObjectNode.class).get()
    assetObjectNode.get("name").asText() == "Test light"
    assetObjectNode.get("path").get(0).asText() == asset.id
    assetObjectNode.get("path").get(1).asText() == parentId

    assetObjectNode.get("attributes").get("colourRGB").get("timestamp").asLong() == createdDate.toEpochMilli()
    assetObjectNode.get("attributes").get("colourRGB").get("meta").get(MetaItemType.AGENT_LINK.name).isObject()
    assetObjectNode.get("attributes").get("colourRGB").get("meta").get(MetaItemType.AGENT_LINK.name).get("id").asText() == "agent_id"
    assetObjectNode.get("attributes").get("colourRGB").get("meta").get(MetaItemType.AGENT_LINK.name).get("type").asText() == DefaultAgentLink.class.getSimpleName()
    assetObjectNode.get("attributes").get("testAttribute").get("meta").get(MetaItemType.AGENT_LINK.name).isObject()
    assetObjectNode.get("attributes").get("testAttribute").get("value").decimalValue() == 100.5
    assetObjectNode.get("attributes").get("testAttribute").get("meta").get(MetaItemType.AGENT_LINK.name).get("id").asText() == "http_agent_id"
    assetObjectNode.get("attributes").get("testAttribute").get("meta").get(MetaItemType.AGENT_LINK.name).get("type").asText() == HTTPAgentLink.class.getSimpleName()

    when: "the asset is deserialized"
    def asset2 = ValueUtil.parse(assetStr, LightAsset.class).orElse(null)

    then: "it should match the original"
    asset.getName() == asset2.getName()
    asset2.getType() == asset.getType()
    asset2.getCreatedOn().toEpochMilli() == asset.getCreatedOn().toEpochMilli()
    asset2.getParentId() == parentId
    asset2.getPath() == asset.getPath()
    asset2.getTemperature().orElse(null) == asset.getTemperature().orElse(null)
    asset2.getColourRGB().map{
      it.getR()
    }.orElse(null) == asset.getColourRGB().map{
      it.getR()
    }.orElse(null)
    asset2.getColourRGB().map{
      it.getG()
    }.orElse(null) == asset.getColourRGB().map{
      it.getG()
    }.orElse(null)
    asset2.getColourRGB().map{
      it.getB()
    }.orElse(null) == asset.getColourRGB().map{
      it.getB()
    }.orElse(null)
    asset2.getAttribute("testAttribute").flatMap{
      it.getMetaValue(MetaItemType.AGENT_LINK)
    }.orElse(null) instanceof HTTPAgentLink
    asset2.getAttribute("testAttribute").flatMap{
      it.getMetaValue(MetaItemType.AGENT_LINK)
    }.map{
      (HTTPAgentLink)it
    }.flatMap{
      it.path
    }.orElse("") == "test_path"
    asset2.getAttribute("testAttribute").flatMap{
      it.getMetaValue(MetaItemType.AGENT_LINK)
    }.map{
      (HTTPAgentLink)it
    }.flatMap{
      it.pagingMode
    }.orElse(false)

    when: "an attribute is cloned"
    def attribute = asset2.getAttribute(LightAsset.COLOUR_RGB).get()
    def clonedAttribute = ValueUtil.clone(attribute)

    then: "the cloned attribute should match the source"
    clonedAttribute.getName() == attribute.getName()
    clonedAttribute.getValue().orElse(null) == attribute.getValue().orElse(null)
    clonedAttribute.getMeta() == attribute.getMeta()

    when: "an attribute event is serialized"
    def attributeEvent = new AttributeEvent(asset2, attribute, "Test", attribute.getValue().orElse(null), attribute.getTimestamp().orElse(0L), null, 0L).setDeleted(true)
    def attributeEventStr = ValueUtil.asJSON(attributeEvent).orElse(null)

    then: "it should look as expected"
    def attributeEventObjectNode = ValueUtil.parse(attributeEventStr, ObjectNode.class).get()
    attributeEventObjectNode.get("ref").get("id").asText() == asset2.id
    attributeEventObjectNode.get("ref").get("name").asText() == LightAsset.COLOUR_RGB.name
    attributeEventObjectNode.get("timestamp").asLong() == createdDate.toEpochMilli()
    attributeEventObjectNode.has("realm")
    attributeEventObjectNode.get("value").isTextual()
    attributeEventObjectNode.get("value").asText() == "#3264C8"
    attributeEventObjectNode.get("deleted").asBoolean()
    !attributeEventObjectNode.has("source")
    !attributeEventObjectNode.has("meta")

    when: "the attribute event is serialized with the enhanced view"
    def attributeEventStr2 = ValueUtil.JSON.writerWithView(AttributeEvent.Enhanced.class).writeValueAsString(attributeEvent)

    then: "it should look as expected"
    def attributeEventObjectNode2 = ValueUtil.parse(attributeEventStr2, ObjectNode.class).get()
    attributeEventObjectNode2.get("ref").get("id").asText() == asset2.id
    attributeEventObjectNode2.get("ref").get("name").asText() == LightAsset.COLOUR_RGB.name
    attributeEventObjectNode2.get("timestamp").asLong() == createdDate.toEpochMilli()
    attributeEventObjectNode2.has("realm")
    attributeEventObjectNode2.get("value").isTextual()
    attributeEventObjectNode2.get("value").asText() == "#3264C8"
    attributeEventObjectNode2.get("deleted").asBoolean()
    !attributeEventObjectNode2.has("source")
    !attributeEventObjectNode2.has("meta")
  }

  @Unroll
  def "Get valueDescriptor schema and hash for #name"(String name, Class<?> clazz, String hash) {
    given: "required services are setup"
    def assetModelService = container.getService(AssetModelService.class)

    when: "we ask to generate a schema for #clazz"
    def result = assetModelService.getValueDescriptorSchema(name)
    def expected = ValueUtil.getSchema(clazz).toString()

    then: "the schema to be the same"
    Objects.equals(result.schema(), expected)

    and: "the hash to be the same"
    if (!hash.empty) {
      Objects.equals(result.hash(), hash)
    }

    where:
    name | clazz | hash
    "text" | String | "04818E8A57A9EBA38CA5457DA1907FEF"
    "text[]" | String[] | "A843B49121780A9A4DCA0FB5604CDA1B"
    "text[][]" | String[][] | "2FD46740DD360941363BDA6D7F2D9EF2"
    "agentLink" | AgentLink | "" // These are likely to change
    "attributeLink" | AttributeLink | "" // so we skip them.
    "valueConstraint[]" | ValueConstraint[] | ""
    "forecastConfiguration" | ForecastConfiguration | ""
    "valueFormat" | ValueFormat | ""
  }

  def "Get unknown valueDescriptor schema"() {
    given: "required services are setup"
    def assetModelService = container.getService(AssetModelService.class)

    when: "we ask to generate a schema for a nonexistent value descriptor"
    def schema = assetModelService.getValueDescriptorSchema("String")

    then: "to return null"
    Objects.equals(schema, null)
  }
}
