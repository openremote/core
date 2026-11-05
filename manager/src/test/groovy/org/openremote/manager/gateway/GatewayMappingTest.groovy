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
package org.openremote.manager.gateway

import org.openremote.container.timer.TimerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.model.asset.Asset
import org.openremote.model.asset.AssetEvent
import org.openremote.model.asset.AssetsEvent
import org.openremote.model.asset.impl.GatewayAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.util.UniqueIdentifierGenerator
import spock.lang.Specification

import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture

class GatewayMappingTest extends Specification {
  def setupSpec() {
    org.openremote.model.util.ValueUtil.initialise(null)
  }

  static final String GATEWAY = "4H1sjYjbYMnvQRsJnkmcrs"
  static final String OTHER = "4HQRYPyyW46j5pWoaKz0B3"
  static final String EDGE = "0123456789ABCDEFGHIJKL"

  def "V#version is reversible across Base62 IDs"() {
    expect:
    200.times {
      def id = UniqueIdentifierGenerator.generateId()
      def mapped = GatewayConnector.mapAssetId(GATEWAY, id, false, version)
      assert mapped ==~ /[a-zA-Z0-9]{22}/
      assert mapped == GatewayConnector.mapAssetId(GATEWAY, id, false, version)
      assert GatewayConnector.mapAssetId(GATEWAY, mapped, true, version) == id
    }
    true

    where:
    version << [1, 2]
  }

  def "legacy V1 output remains unchanged and V2 separates same-prefix gateways"() {
    expect:
    GatewayConnector.mapAssetId(GATEWAY, EDGE, false, 1) == "Qb23456789ABCDEFGHIJKL"
    GatewayConnector.mapAssetId(GATEWAY, EDGE, false, 1) == GatewayConnector.mapAssetId(OTHER, EDGE, false, 1)
    GatewayConnector.mapAssetId(GATEWAY, EDGE, false, 2) != GatewayConnector.mapAssetId(OTHER, EDGE, false, 2)
  }

  def "mapping rejects malformed input"() {
    when:
    GatewayConnector.mapAssetId(gateway, edge, false, version)

    then:
    thrown(IllegalArgumentException)

    where:
    gateway | edge | version
    null | EDGE | 1
    GATEWAY | null | 2
    "short" | EDGE | 2
    GATEWAY | "!" * 22 | 1
    GATEWAY | EDGE | 3
  }

  def "gateway mapping uses the fixed upgrade timestamp (#createdOn)"() {
    expect:
    GatewayConnector.getAssetIdMappingVersion(Instant.parse(createdOn), Instant.parse("2026-11-05T12:00:00Z")) == version

    where:
    createdOn | version
    "2026-11-05T11:59:59.999999Z" | 1
    "2026-11-05T12:00:00Z" | 2
    "2026-11-05T12:00:00.000001Z" | 2
  }

  def "missing mapping timestamps are rejected"() {
    when:
    GatewayConnector.getAssetIdMappingVersion(createdOn, cutoff)

    then:
    thrown(NullPointerException)

    where:
    createdOn | cutoff
    null | Instant.EPOCH
    Instant.EPOCH | null
  }

  def "mapping is frozen for a connector and reconstructed from creation time"() {
    given:
    def gateway = new GatewayAsset("New").setId(GATEWAY).setRealm("test").setCreatedOn(Instant.parse("2026-11-06T00:00:00Z"))
    def first = connector(gateway, Stub(AssetStorageService))
    def reconnected = connector(gateway, Stub(AssetStorageService))
    gateway.setCreatedOn(Instant.EPOCH)

    expect:
    first.mapAssetId(EDGE, false) == GatewayConnector.mapAssetId(GATEWAY, EDGE, false, 2)
    reconnected.mapAssetId(EDGE, false) == first.mapAssetId(EDGE, false)
  }

  def "invalid inventory never triggers cleanup"() {
    given:
    def storage = Mock(AssetStorageService)
    def gateway = new GatewayAsset("Test").setId(GATEWAY).setRealm("test")
    def connector = connector(gateway, storage)
    connector.@sessionId.set("session")
    connector.initialSyncInProgress = true
    connector.syncProcessorFuture = Stub(ScheduledFuture)
    connector.expectedSyncResponseName = "INITIAL"
    def response = new AssetsEvent(inventory)
    response.setMessageID("INITIAL")

    when:
    connector.onGatewayEvent(response)

    then:
    !connector.connected
    0 * storage._

    where:
    inventory << [
      null,
      [new ThingAsset("Invalid").setId("bad")],
      [new ThingAsset("Duplicate").setId(EDGE), new ThingAsset("Duplicate").setId(EDGE)],
      [new ThingAsset("Cycle").setId(EDGE).setParentId(EDGE)],
      [new ThingAsset("Missing parent").setId(EDGE).setParentId("a" * 22)]
    ]
  }

  def "sync failure stops before cleanup including cached creates"() {
    given:
    def storage = Mock(AssetStorageService)
    def gateway = new GatewayAsset("Test").setId(GATEWAY).setRealm("test")
    def connector = connector(gateway, storage)
    connector.@sessionId.set("session")
    connector.initialSyncInProgress = true
    connector.syncProcessorFuture = Stub(ScheduledFuture)
    connector.expectedSyncResponseName = "BATCH0"
    connector.syncAssetIds = [EDGE]
    connector.syncIndex = 0
    connector.cachedAttributeEvents = []
    connector.cachedAssetEvents = cachedFailure ? [new AssetEvent(AssetEvent.Cause.CREATE,
      new ThingAsset("Cached").setId("a" * 22))] : []
    def response = new AssetsEvent([new ThingAsset("Edge").setId(EDGE)])
    response.setMessageID("BATCH0")

    when:
    connector.onGatewayEvent(response)

    then:
    (cachedFailure ? 2 : 1) * storage.merge(_ as Asset, true, gateway, null) >> {
      if (!cachedFailure || it[0].name == "Cached") {
        throw new IllegalStateException("ownership conflict")
      }
      it[0]
    }
    0 * storage.findAll(_)
    0 * storage.deleteGatewayAssets(_, _)
    !connector.connected

    where:
    cachedFailure << [false, true]
  }

  private GatewayConnector connector(GatewayAsset gateway, AssetStorageService storage) {
    if (gateway.createdOn == null) {
      gateway.setCreatedOn(Instant.EPOCH)
    }
    new GatewayConnector(storage, Stub(AssetProcessingService), Stub(ExecutorService),
            Stub(ScheduledExecutorService), Stub(GatewayService), Stub(TimerService) {
              getNow() >> Instant.now()
            }, gateway, Instant.parse("2026-11-05T12:00:00Z"))
  }
}
