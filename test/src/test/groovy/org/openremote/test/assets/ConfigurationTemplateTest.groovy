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
package org.openremote.test.assets

import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.WebApplicationException
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.AssetResource
import org.openremote.model.asset.ConfigurationTemplateImportPreview
import org.openremote.model.asset.ConfigurationTemplateImportRequest
import org.openremote.model.asset.impl.RoomAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.MetaItem
import org.openremote.model.util.ValueUtil
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Shared
import spock.lang.Specification

import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST
import static jakarta.ws.rs.core.Response.Status.CONFLICT
import static jakarta.ws.rs.core.Response.Status.FORBIDDEN
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD
import static org.openremote.manager.security.ManagerIdentityProvider.OR_ADMIN_PASSWORD_DEFAULT
import static org.openremote.model.Constants.*
import static org.openremote.model.asset.ConfigurationTemplateImportFailure.*
import static org.openremote.model.util.MapAccess.getString

class ConfigurationTemplateTest extends Specification implements ManagerContainerTrait {

  @Shared
  static KeycloakTestSetup keycloakTestSetup

  @Shared
  static ManagerTestSetup managerTestSetup

  @Shared
  static AssetResource adminResource

  @Shared
  static AssetResource readOnlyUserResource

  @Shared
  static AssetResource masterUserResource

  @Shared
  static AssetResource restrictedUserResource

  /** Assets created by a feature, removed again in cleanup. */
  List<String> createdAssetIds = []

  def setupSpec() {
    def container = startContainer(defaultConfig(), defaultServices())
    keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    managerTestSetup = container.getService(SetupService.class).getTaskOfType(ManagerTestSetup.class)
    def buildingRealm = keycloakTestSetup.realmBuilding.name

    def adminAccessToken = authenticate(
            container,
            MASTER_REALM,
            KEYCLOAK_CLIENT_ID,
            MASTER_REALM_ADMIN_USER,
            getString(container.getConfig(), OR_ADMIN_PASSWORD, OR_ADMIN_PASSWORD_DEFAULT)
    )
    adminResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, adminAccessToken).proxy(AssetResource.class)

    // A building realm user that can read but not write assets
    def readOnlyUserToken = authenticate(container, buildingRealm, KEYCLOAK_CLIENT_ID, "testuser2", "testuser2")
    readOnlyUserResource = getClientApiTarget(serverUri(serverPort), buildingRealm, readOnlyUserToken).proxy(AssetResource.class)

    // A master realm user that can write assets
    def masterUserToken = authenticate(container, MASTER_REALM, KEYCLOAK_CLIENT_ID, "testuser1", "testuser1")
    masterUserResource = getClientApiTarget(serverUri(serverPort), MASTER_REALM, masterUserToken).proxy(AssetResource.class)

    // A restricted building realm user that can write the assets linked to it
    def restrictedUserToken = authenticate(container, buildingRealm, KEYCLOAK_CLIENT_ID, "testuser3", "testuser3")
    restrictedUserResource = getClientApiTarget(serverUri(serverPort), buildingRealm, restrictedUserToken).proxy(AssetResource.class)
  }

  def cleanup() {
    if (!createdAssetIds.isEmpty()) {
      adminResource.delete(null, createdAssetIds)
    }
  }

  def "Export and import a configuration template as superuser"() {
    given: "a configured source asset and a target asset of the same type"
    def source = adminResource.create(null, new ThingAsset("Configuration source")
            .setRealm(keycloakTestSetup.realmMaster.name)
            .addOrReplaceAttributes(
                    new Attribute<>("temperature", ValueType.NUMBER)
                    .addMeta(new MetaItem<>(MetaItemType.LABEL, "Source temperature"))
                    .addMeta(new MetaItem<>(MetaItemType.READ_ONLY, true)),
                    new Attribute<>("customValue", ValueType.TEXT)
                    .addMeta(new MetaItem<>(MetaItemType.LABEL, "Source custom value")),
                    new Attribute<>("plain", ValueType.TEXT)
            ))
    def target = adminResource.create(null, new ThingAsset("Configuration target")
            .setRealm(keycloakTestSetup.realmMaster.name)
            .addOrReplaceAttributes(
                    new Attribute<>("temperature", ValueType.NUMBER, 21.5d)
                    .addMeta(new MetaItem<>(MetaItemType.LABEL, "Target temperature")),
                    new Attribute<>("customValue", ValueType.NUMBER),
                    new Attribute<>("plain", ValueType.TEXT)
            ))
    createdAssetIds.addAll([source.id, target.id])

    when: "a configuration template of the source asset is exported"
    def document = adminResource.exportConfigurationTemplate(null, source.id, [])

    then: "only the attributes holding metadata are exported, with their type and metadata"
    document.version == 1
    document.assetType == ThingAsset.DESCRIPTOR.name
    document.attributes.keySet() == ["temperature", "customValue"] as Set
    document.attributes.temperature.type == ValueType.NUMBER.name
    document.attributes.temperature.getMetaValue(MetaItemType.LABEL).orElse(null) == "Source temperature"
    document.attributes.temperature.getMetaValue(MetaItemType.READ_ONLY).orElse(false)

    when: "a subset of the attributes is exported"
    def subset = adminResource.exportConfigurationTemplate(null, source.id, ["temperature"])

    then: "only the requested attribute is exported"
    subset.attributes.keySet() == ["temperature"] as Set

    when: "only an attribute without metadata is selected"
    adminResource.exportConfigurationTemplate(null, source.id, ["plain"])

    then: "the export is rejected as there is nothing to export"
    WebApplicationException nothingToExport = thrown()
    nothingToExport.response.withCloseable { r ->
      assert r.status == BAD_REQUEST.statusCode
      return true
    }

    when: "the exported document is imported into the target asset"
    def preview = adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(target, ValueUtil.asJSON(document).orElseThrow()))

    then: "the attribute with a matching type is importable and the one with another type is reported"
    preview.failure == null
    preview.importableAttributes*.name == ["temperature"]
    preview.typeMismatches*.name == ["customValue"]
    preview.typeMismatches*.importedType == [ValueType.TEXT.name]
    preview.typeMismatches*.targetType == [ValueType.NUMBER.name]

    and: "the patched attributes hold the imported metadata, keeping the attribute value"
    def patched = preview.patchedAttributes.get("temperature").get()
    patched.getMetaValue(MetaItemType.LABEL).orElse(null) == "Source temperature"
    patched.getMetaValue(MetaItemType.READ_ONLY).orElse(false)
    patched.value.orElse(null) == 21.5d

    and: "the attributes that are not imported are returned untouched"
    preview.patchedAttributes.get("customValue").get().type == ValueType.NUMBER

    and: "the target asset is not stored by the preview"
    adminResource.get(null, target.id).getAttribute("temperature").get().getMeta()
            .getValue(MetaItemType.LABEL.name, String.class).orElse(null) == "Target temperature"

    when: "a document of an unsupported version is imported"
    adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(target, '{"version": 2, "assetType": "ThingAsset", "attributes": {}}'))

    then: "the import is rejected with the reason"
    WebApplicationException ex = thrown()
    ex.response.withCloseable { r ->
      assert r.status == BAD_REQUEST.statusCode
      assert r.readEntity(ConfigurationTemplateImportPreview.class).failure == UNSUPPORTED_VERSION
      return true
    }

    when: "a document holding a configuration item that breaks a constraint is imported"
    adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(target, '{"version": 1, "assetType": "ThingAsset", "attributes": {"temperature": {"type": "number", "meta": {"forecast": {"type": "wea", "pastPeriod": "P1D", "pastCount": 0, "forecastPeriod": "PT1H", "forecastCount": 1}}}}}'))

    then: "the import is rejected with the reason and where it fails"
    WebApplicationException invalidMetaItem = thrown()
    invalidMetaItem.response.withCloseable { r ->
      assert r.status == BAD_REQUEST.statusCode
      def rejected = r.readEntity(ConfigurationTemplateImportPreview.class)
      assert rejected.failure == INVALID_META_ITEM
      assert rejected.failureLocation.attribute == "temperature"
      assert rejected.failureLocation.metaItem == MetaItemType.FORECAST.name
      assert rejected.failureLocation.parameter == "pastCount"
      return true
    }

    when: "a document holding a minimum above the current value of the target attribute is imported"
    adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(target, '{"version": 1, "assetType": "ThingAsset", "attributes": {"temperature": {"type": "number", "meta": {"constraints": [{"type": "min", "min": 30}]}}}}'))

    then: "the import is rejected, as saving the asset would fail"
    WebApplicationException valueViolatesConstraints = thrown()
    valueViolatesConstraints.response.withCloseable { r ->
      assert r.status == BAD_REQUEST.statusCode
      def rejected = r.readEntity(ConfigurationTemplateImportPreview.class)
      assert rejected.failure == VALUE_VIOLATES_CONSTRAINTS
      assert rejected.failureLocation.attribute == "temperature"
      return true
    }

    when: "a document holding no attribute of the target asset is imported"
    adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(target, '{"version": 1, "assetType": "ThingAsset", "attributes": {"absent": {"type": "text", "meta": {"label": "Absent"}}}}'))

    then: "the import is rejected and the skipped attribute is reported"
    WebApplicationException noneImportable = thrown()
    noneImportable.response.withCloseable { r ->
      assert r.status == BAD_REQUEST.statusCode
      def rejected = r.readEntity(ConfigurationTemplateImportPreview.class)
      assert rejected.failure == NO_IMPORTABLE_ATTRIBUTES
      assert rejected.missingAttributes*.name == ["absent"]
      return true
    }

    when: "the draft of another asset is submitted"
    adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(source, ValueUtil.asJSON(document).orElseThrow()))

    then: "the import conflicts with the target asset"
    WebApplicationException draftMismatch = thrown()
    draftMismatch.response.withCloseable { r ->
      assert r.status == CONFLICT.statusCode
      assert r.readEntity(ConfigurationTemplateImportPreview.class).failure == DRAFT_MISMATCH
      return true
    }

    when: "no draft is submitted"
    adminResource.previewConfigurationTemplateImport(null, target.id,
            new ConfigurationTemplateImportRequest(null, ValueUtil.asJSON(document).orElseThrow()))

    then: "the request is rejected as invalid rather than as a conflict"
    WebApplicationException noDraft = thrown()
    noDraft.response.withCloseable { r ->
      assert r.status == BAD_REQUEST.statusCode
      return true
    }
  }

  def "Configuration template import requires asset write access"() {
    when: "a user that can read but not write assets exports a configuration template of an asset of its realm"
    def document = readOnlyUserResource.exportConfigurationTemplate(null, managerTestSetup.apartment1LivingroomId, [])

    then: "the configuration is returned"
    document.version == 1
    !document.attributes.isEmpty()

    when: "the same user previews an import"
    readOnlyUserResource.previewConfigurationTemplateImport(null, managerTestSetup.apartment1LivingroomId,
            new ConfigurationTemplateImportRequest(
                    readOnlyUserResource.get(null, managerTestSetup.apartment1LivingroomId),
                    ValueUtil.asJSON(document).orElseThrow()))

    then: "access is forbidden"
    ForbiddenException forbidden = thrown()
    forbidden.response.withCloseable { r ->
      assert r.status == FORBIDDEN.statusCode
      return true
    }
  }

  def "Configuration template import is rejected for assets the user cannot access"() {
    given: "a document, which is never read as access is checked first"
    def document = '{"version": 1, "assetType": "RoomAsset", "attributes": {}}'
    def buildingRealm = keycloakTestSetup.realmBuilding.name

    when: "a master realm user previews an import into an asset of the building realm"
    def otherRealmAssetId = managerTestSetup.apartment1LivingroomId
    masterUserResource.previewConfigurationTemplateImport(null, otherRealmAssetId,
            new ConfigurationTemplateImportRequest(draft(otherRealmAssetId, buildingRealm), document))

    then: "access is forbidden"
    WebApplicationException otherRealm = thrown()
    otherRealm.response.withCloseable { r ->
      assert r.status == FORBIDDEN.statusCode
      return true
    }

    when: "a restricted user previews an import into an asset that is not linked to it"
    def unlinkedAssetId = managerTestSetup.apartment2LivingroomId
    restrictedUserResource.previewConfigurationTemplateImport(null, unlinkedAssetId,
            new ConfigurationTemplateImportRequest(draft(unlinkedAssetId, buildingRealm), document))

    then: "access is forbidden"
    WebApplicationException unlinked = thrown()
    unlinked.response.withCloseable { r ->
      assert r.status == FORBIDDEN.statusCode
      return true
    }

    when: "an import into an asset that does not exist is previewed"
    def unknownAssetId = "unknownAssetId00000001"
    masterUserResource.previewConfigurationTemplateImport(null, unknownAssetId,
            new ConfigurationTemplateImportRequest(draft(unknownAssetId, MASTER_REALM), document))

    then: "the asset is not found"
    WebApplicationException unknown = thrown()
    unknown.response.withCloseable { r ->
      assert r.status == NOT_FOUND.statusCode
      return true
    }
  }

  /** A draft that identifies the asset, so only access decides the outcome. */
  protected static RoomAsset draft(String id, String realm) {
    return new RoomAsset("Living room").setId(id).setRealm(realm)
  }
}
