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
package org.openremote.test.gateway

import org.openremote.manager.asset.AssetProcessingException
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.gateway.GatewayConnector
import org.openremote.manager.gateway.GatewayService
import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.system.ApplicationVersionService
import org.openremote.manager.setup.SetupService
import org.openremote.manager.system.VersionInfo
import org.openremote.model.asset.impl.GatewayAsset
import org.openremote.model.asset.impl.RoomAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeEvent
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.ManagerTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import static org.openremote.manager.gateway.GatewayService.ASSET_ID_MAPPING_V2_VERSION

class GatewayOwnershipTest extends Specification implements ManagerContainerTrait {
  def setupSpec() {
    startContainer(defaultConfig(), defaultServices())
  }

  def "new registrations use V2 across updates, qualified restarts and rollback"() {
    given:
    def storage = container.getService(AssetStorageService)
    def versions = container.getService(ApplicationVersionService)
    def gateways = container.getService(GatewayService)
    def realm = container.getService(SetupService).getTaskOfType(ManagerTestSetup).realmBuildingName
    def gateway = storage.merge(new GatewayAsset("V2 registration").setRealm(realm))
    def cutoff = versions.getFirstActivationAtOrAfter(ASSET_ID_MAPPING_V2_VERSION).orElseThrow()
    def createdOn = gateway.createdOn
    def mapId = {
      -> gateways.gatewayConnectorMap[gateway.id.toLowerCase(Locale.ROOT)].mapAssetId("b" * 22, false)
    }
    new PollingConditions(timeout: 15).eventually {
      assert gateways.gatewayConnectorMap[gateway.id.toLowerCase(Locale.ROOT)] != null
    }

    expect:
    GatewayConnector.getAssetIdMappingVersion(createdOn, cutoff) == 2
    mapId() == GatewayConnector.mapAssetId(gateway.id, "b" * 22, false, 2)

    when: "ordinary updates try to change the creation timestamp"
    gateway = storage.find(gateway.id, true) as GatewayAsset
    gateway = storage.merge(gateway.setCreatedOn(java.time.Instant.EPOCH).setName("Updated"))
    gateway = storage.find(gateway.id, true) as GatewayAsset

    then:
    gateway.createdOn == createdOn

    when: "qualified builds reactivate and the application rolls back and upgrades"
    [
      VersionInfo.managerVersion,
      VersionInfo.managerVersion,
      "0.9.0-SNAPSHOT",
      VersionInfo.managerVersion
    ].each { version ->
      versions.recordApplicationVersion(version)
      assert versions.getFirstActivationAtOrAfter(ASSET_ID_MAPPING_V2_VERSION).orElseThrow() == cutoff
    }
    gateways.stop(container)
    gateways.start(container)

    then:
    mapId() == GatewayConnector.mapAssetId(gateway.id, "b" * 22, false, 2)
    !storage.find(gateway.id, true).attributes.has("assetIdMappingVersion")
  }

  def "gateways created before the upgrade retain V1 across updates and service restarts"() {
    given:
    def storage = container.getService(AssetStorageService)
    def persistence = container.getService(PersistenceService)
    def versions = container.getService(ApplicationVersionService)
    def realm = container.getService(SetupService).getTaskOfType(ManagerTestSetup).realmBuildingName
    def gateway = storage.merge(new GatewayAsset("Legacy fixture").setRealm(realm))
    new PollingConditions(timeout: 15).eventually {
      assert container.getService(GatewayService).gatewayConnectorMap[gateway.id.toLowerCase(Locale.ROOT)] != null
    }
    def cutoff = versions.getFirstActivationAtOrAfter(ASSET_ID_MAPPING_V2_VERSION).orElseThrow()
    // Simulate a registration that already existed before the migration.
    persistence.doTransaction { em ->
      em.createNativeQuery("UPDATE asset SET created_on = :createdOn WHERE id = :id")
              .setParameter("createdOn", cutoff.minusSeconds(1))
              .setParameter("id", gateway.id).executeUpdate()
    }
    gateway = storage.find(gateway.id, true) as GatewayAsset

    when:
    gateway = storage.merge(gateway.setName("Updated legacy gateway"))
    def version = GatewayConnector.getAssetIdMappingVersion(gateway.createdOn, cutoff)
    def centralId = GatewayConnector.mapAssetId(gateway.id, "b" * 22, false, version)
    def child = storage.merge(new ThingAsset("Legacy child").setId(centralId).setParentId(gateway.id).setRealm(realm), true, gateway, null)
    def gateways = container.getService(GatewayService)
    gateways.stop(container)
    gateways.start(container)

    then:
    version == 1
    child.id == GatewayConnector.mapAssetId(gateway.id, "b" * 22, false, 1)
    gateways.gatewayConnectorMap[gateway.id.toLowerCase(Locale.ROOT)].mapAssetId("b" * 22, false) == child.id
    !gateway.attributes.has("assetIdMappingVersion")
  }

  def "gateway deletions accept absent and pending targets while checking persisted ownership"() {
    given:
    def storage = container.getService(AssetStorageService)
    def persistence = container.getService(PersistenceService)
    def gateways = container.getService(GatewayService)
    def realm = container.getService(SetupService).getTaskOfType(ManagerTestSetup).realmBuildingName
    def owner = storage.merge(new GatewayAsset("Delete owner").setRealm(realm))
    def other = storage.merge(new GatewayAsset("Other gateway").setRealm(realm))
    def conditions = new PollingConditions(timeout: 15)
    conditions.eventually {
      assert gateways.isLocallyRegisteredGateway(owner.id)
      assert gateways.isLocallyRegisteredGateway(other.id)
    }
    def parent = storage.merge(new ThingAsset("Parent").setId(UniqueIdentifierGenerator.generateId())
            .setParentId(owner.id).setRealm(realm), true, owner, null)
    def child = storage.merge(new ThingAsset("Child").setId(UniqueIdentifierGenerator.generateId())
            .setParentId(parent.id).setRealm(realm), true, owner, null)
    def setPending = { boolean pending ->
      persistence.doTransaction { em ->
        em.createNativeQuery("UPDATE asset SET delete_pending = :pending WHERE id = :id")
                .setParameter("pending", pending).setParameter("id", child.id).executeUpdate()
      }
    }
    setPending(true)

    expect:
    storage.deleteGatewayAssets([child.id], owner.id)
    !storage.deleteGatewayAssets([child.id], other.id)

    when:
    setPending(false)

    then:
    !storage.deleteGatewayAssets([parent.id], owner.id)
    storage.deleteGatewayAssets([parent.id, child.id], owner.id)
    conditions.eventually {
      assert persistence.doReturningTransaction { em ->
        em.find(ThingAsset, child.id) == null && em.find(ThingAsset, parent.id) == null
      }
    }
    storage.deleteGatewayAssets([parent.id, child.id], owner.id)

    cleanup:
    if (owner != null && gateways.isLocallyRegisteredGateway(owner.id)) {
      gateways.deleteGatewayUntilFinished(owner.id).completion().join()
    }
    if (other != null && gateways.isLocallyRegisteredGateway(other.id)) {
      gateways.deleteGatewayUntilFinished(other.id).completion().join()
    }
  }

  @Timeout(30)
  def "disabling a gateway repairs missing credentials within the attribute transaction"() {
    given:
    def storage = container.getService(AssetStorageService)
    def persistence = container.getService(PersistenceService)
    def gateways = container.getService(GatewayService)
    def realm = container.getService(SetupService).getTaskOfType(ManagerTestSetup).realmBuildingName
    def gateway = storage.merge(new GatewayAsset("Credential repair").setRealm(realm).setDisabled(false))
    new PollingConditions(timeout: 15).eventually {
      assert gateways.isLocallyRegisteredGateway(gateway.id)
      assert (storage.find(gateway.id, true) as GatewayAsset).clientSecret.present
    }
    persistence.doTransaction { em ->
      em.createNativeQuery("UPDATE asset SET attributes = attributes - :attribute WHERE id = :id")
              .setParameter("attribute", GatewayAsset.CLIENT_SECRET.name)
              .setParameter("id", gateway.id).executeUpdate()
    }

    when:
    container.getService(AssetProcessingService).sendAttributeEventAndWait(
            new AttributeEvent(gateway.id, GatewayAsset.DISABLED, true), null)

    then:
    with(storage.find(gateway.id, true) as GatewayAsset) {
      disabled.orElse(false)
      clientSecret.present
    }

    cleanup:
    if (gateway != null && gateways.isLocallyRegisteredGateway(gateway.id)) {
      gateways.deleteGatewayUntilFinished(gateway.id).completion().join()
    }
  }

  def "gateway cannot overwrite, move, delete or write another owner's asset (V#otherVersion, #replacementType.simpleName)"() {
    given:
    def storage = container.getService(AssetStorageService)
    def persistence = container.getService(PersistenceService)
    def versions = container.getService(ApplicationVersionService)
    def realm = container.getService(SetupService).getTaskOfType(ManagerTestSetup).realmBuildingName
    def owner = storage.merge(new GatewayAsset("Owner").setRealm(realm))
    def attacker = storage.merge(new GatewayAsset("Attacker").setRealm(realm))
    def gatewayService = container.getService(GatewayService)
    new PollingConditions(timeout: 15).eventually {
      assert gatewayService.gatewayConnectorMap[owner.id.toLowerCase(Locale.ROOT)] != null
      assert gatewayService.gatewayConnectorMap[attacker.id.toLowerCase(Locale.ROOT)] != null
    }
    if (otherVersion == 1) {
      def cutoff = versions.getFirstActivationAtOrAfter(ASSET_ID_MAPPING_V2_VERSION).orElseThrow()
      persistence.doTransaction { em ->
        em.createNativeQuery("UPDATE asset SET created_on = :createdOn WHERE id = :id")
                .setParameter("createdOn", cutoff.minusSeconds(1))
                .setParameter("id", owner.id).executeUpdate()
      }
      owner = storage.find(owner.id, true) as GatewayAsset
    }
    def targetId = GatewayConnector.mapAssetId(owner.id, "b" * 22, false, otherVersion)
    def target = storage.merge(new ThingAsset("Owned").setId(targetId).setParentId(owner.id).setRealm(realm)
            .addAttributes(new Attribute<>("test", ValueType.TEXT, "original")), true, owner, null)
    def maliciousEdgeId = GatewayConnector.mapAssetId(attacker.id, target.id, true, 2)
    assert GatewayConnector.mapAssetId(attacker.id, maliciousEdgeId, false, 2) == target.id

    when: "incoming create/update claims an occupied mapped ID"
    storage.merge(replacementType.newInstance("Replacement").setId(target.id).setParentId(attacker.id).setRealm(realm), true, attacker, null)

    then: "ownership is checked before type compatibility and the asset is untouched"
    IllegalStateException conflict = thrown()
    conflict.message.contains("Gateway asset ownership conflict")
    conflict.message.contains(attacker.id)
    conflict.message.contains(target.id)
    with(storage.find(target.id, true)) {
      id == target.id
      type == target.type
      parentId == target.parentId
      name == target.name
      getAttribute("test").orElseThrow().value.orElseThrow() == "original"
      !deletePending
    }

    when: "new asset names a parent outside its subtree"
    storage.merge(new ThingAsset("Attack parent").setId("z" * 22).setParentId(target.id).setRealm(realm), true, attacker, null)

    then:
    thrown(IllegalStateException)
    storage.find("z" * 22, true) == null

    when: "the gateway moves an owned asset within its own hierarchy"
    def ownParent = storage.merge(new ThingAsset("Own parent").setId(UniqueIdentifierGenerator.generateId())
            .setParentId(attacker.id).setRealm(realm), true, attacker, null)
    def ownChild = storage.merge(new ThingAsset("Own child").setId(UniqueIdentifierGenerator.generateId())
            .setParentId(attacker.id).setRealm(realm), true, attacker, null)
    ownChild = storage.merge(ownChild.setParentId(ownParent.id), true, attacker, null)

    then:
    storage.find(ownChild.id, true).parentId == ownParent.id

    when: "the gateway proposes a parent in a different hierarchy"
    storage.merge(ownChild.setParentId(target.id), true, attacker, null)

    then:
    thrown(IllegalStateException)
    storage.find(ownChild.id, true).parentId == ownParent.id

    when: "the gateway targets its own registration through a descendant merge"
    storage.merge(new GatewayAsset("Replacement").setId(attacker.id).setParentId(ownParent.id).setRealm(realm),
            true, attacker, null)

    then:
    thrown(IllegalStateException)
    storage.find(attacker.id, true).name == attacker.name
    storage.find(attacker.id, true).parentId == attacker.parentId

    when: "cleanup includes an owned asset and a conflicting asset"
    def deleted = storage.deleteGatewayAssets([ownChild.id, target.id], attacker.id)

    then: "the whole deletion batch is rejected"
    !deleted
    !storage.find(target.id, true).deletePending
    !storage.find(ownChild.id, true).deletePending

    when: "a queued incoming attribute write retains the authenticated gateway identity"
    container.getService(AssetProcessingService).processAttributeEvent(
            new AttributeEvent(target.id, "test", "attack").setSource(GatewayConnector.EVENT_SOURCE + ":" + attacker.id))

    then:
    thrown(AssetProcessingException)
    storage.find(target.id, true).getAttribute("test").get().value.get() == "original"

    when: "a foreign gateway attempts to clear the attribute value"
    container.getService(AssetProcessingService).processAttributeEvent(
            new AttributeEvent(target.id, "test", null).setSource(GatewayConnector.EVENT_SOURCE + ":" + attacker.id))

    then:
    thrown(AssetProcessingException)
    storage.find(target.id, true).getAttribute("test").get().value.get() == "original"

    when: "persisted ownership authorizes the actual owner"
    container.getService(AssetProcessingService).processAttributeEvent(
            new AttributeEvent(target.id, "test", "authorized", target.getAttribute("test").get().timestamp.get() + 1L).setSource(GatewayConnector.EVENT_SOURCE + ":" + owner.id))

    then:
    storage.find(target.id, true).getAttribute("test").get().value.get() == "authorized"

    when: "an incorrect routing cache tries to send the target to another gateway"
    def wrongConnector = gatewayService.gatewayConnectorMap[attacker.id.toLowerCase(Locale.ROOT)]
    def sent = []
    wrongConnector.@sessionId.set("test-session")
    wrongConnector.gatewayMessageConsumer = { sent.add(it) }
    gatewayService.assetIdGatewayIdMap.put(target.id, attacker.id)
    container.getService(AssetProcessingService).processAttributeEvent(
            new AttributeEvent(target.id, "test", "outbound"))

    then:
    thrown(AssetProcessingException)
    sent.empty

    cleanup:
    if (wrongConnector != null) {
      wrongConnector.@sessionId.set(null)
    }
    if (gatewayService != null && target != null && owner != null) {
      gatewayService.assetIdGatewayIdMap.put(target.id, owner.id)
    }

    where:
    otherVersion | replacementType
    1            | ThingAsset
    1            | RoomAsset
    2            | ThingAsset
  }
}
