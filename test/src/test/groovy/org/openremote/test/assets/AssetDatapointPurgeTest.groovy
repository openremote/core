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

import org.apache.camel.builder.RouteBuilder
import org.hibernate.cfg.AvailableSettings
import org.openremote.container.message.MessageBrokerService
import org.openremote.container.persistence.PersistenceService
import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.setup.SetupService
import org.openremote.model.PersistenceEvent
import org.openremote.model.asset.Asset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.datapoint.ValueDatapoint
import org.openremote.model.query.AssetQuery
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

import static org.openremote.container.persistence.PersistenceService.PERSISTENCE_TOPIC
import static org.openremote.container.persistence.PersistenceService.isPersistenceEventForEntityType
import static org.openremote.manager.datapoint.AssetDatapointService.OR_DATA_POINTS_CHUNK_INTERVAL_DAYS
import static org.openremote.manager.datapoint.AssetDatapointService.OR_DATA_POINTS_MAX_AGE_WEEKS

class AssetDatapointPurgeTest extends Specification implements ManagerContainerTrait {

  def setupSpec() {
    // 4 week retention and 1 day chunks, so the purge, compression and delete batching below run across many chunks
    def config = defaultConfig()
    config.put(OR_DATA_POINTS_MAX_AGE_WEEKS, "4")
    config.put(OR_DATA_POINTS_CHUNK_INTERVAL_DAYS, "1")
    startContainer(config, defaultServices())
  }

  def "Data point chunk interval and columnstore policy are configured"() {

    given: "the persistence service"
    def persistenceService = container.getService(PersistenceService.class)

    expect: "the hypertable chunk interval to be 1 day"
    persistenceService.doReturningTransaction { em ->
      em.createNativeQuery("""
                SELECT EXTRACT(EPOCH FROM time_interval)::bigint
                FROM timescaledb_information.dimensions
                WHERE hypertable_schema = current_schema() AND hypertable_name = 'asset_datapoint'
            """).getSingleResult() as Long
    } == 86400L

    and: "the columnstore policy to convert chunks 1 day after they end"
    persistenceService.doReturningTransaction { em ->
      em.createNativeQuery("""
                SELECT config->>'compress_after'
                FROM timescaledb_information.jobs
                WHERE hypertable_schema = current_schema() AND hypertable_name = 'asset_datapoint'
                  AND proc_name = 'policy_compression'
            """).getSingleResult() as String
    } == "1 day"
  }

  def "Test TimescaleDB hypercore compression and purge"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 30, delay: 1)

    and: "the services"
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def originalAssetStorageService = container.getService(AssetStorageService.class)
    def assetStorageService = Spy(originalAssetStorageService)
    container.@services.put(AssetStorageService.class, assetStorageService)
    def assetDatapointService = container.getService(AssetDatapointService.class)
    def messageBrokerService = container.getService(MessageBrokerService.class)
    def persistenceService = container.getService(PersistenceService.class)
    def originalMaxTuplesDecompressedPerDmlTransaction = getMaxTuplesDecompressedPerDmlTransaction(persistenceService)
    def originalAssetDeleteDatapointBatchThreshold = assetStorageService.assetDeleteDatapointBatchThreshold
    def originalAssetDeleteDatapointBatchWeeks = assetStorageService.assetDeleteDatapointBatchWeeks
    assetStorageService.assetDeleteDatapointBatchThreshold = 100
    assetStorageService.assetDeleteDatapointBatchWeeks = 2

    def failedDeleteAssetIds = new CopyOnWriteArrayList<String>()
    assetStorageService.deletePendingAsset(_) >> { String assetId ->
      if (failedDeleteAssetIds.contains(assetId)) {
        assetStorageService.failedAssetDeleteIds.add(assetId)
        return
      }
      callRealMethod()
    }

    List<PersistenceEvent<Asset<?>>> assetPersistenceEvents = new CopyOnWriteArrayList<>()
    def assetPersistenceRouteId = "Test-AssetPersistenceEvents-${System.nanoTime()}"
    messageBrokerService.context.addRoutes(new RouteBuilder() {
              @Override
              void configure() {
                from(PERSISTENCE_TOPIC)
                        .routeId(assetPersistenceRouteId)
                        .filter(isPersistenceEventForEntityType(Asset.class))
                        .process { exchange ->
                          def event = exchange.in.getBody(PersistenceEvent.class) as PersistenceEvent<Asset<?>>
                          if (event.cause in [PersistenceEvent.Cause.DELETE, PersistenceEvent.Cause.DELETE_FINISHED]) {
                            assetPersistenceEvents.add(event)
                          }
                        }
              }
            })

    and: "the schema name is retrieved"
    def schemaName = persistenceService.persistenceUnitProperties.getProperty(AvailableSettings.DEFAULT_SCHEMA)
    getLOG().info("Using schema: ${schemaName}")

    and: "the clock is stopped and advanced to a known time"
    stopPseudoClock()
    advancePseudoClock(Instant.ofEpochMilli(getClockTimeOf(container)).truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS).toEpochMilli() - getClockTimeOf(container), TimeUnit.MILLISECONDS, container)

    and: "the datapoint attributes are defined"
    def attributeNames = (1..10).collect { "attribute${it}" }

    when: "a test asset is created with multiple attributes"
    def testAsset = new ThingAsset("Hypercore Test Asset")
            .setRealm(keycloakTestSetup.realmMaster.name)
    testAsset.addOrReplaceAttributes(numberAttributes(attributeNames))
    testAsset = assetStorageService.merge(testAsset)

    then: "the asset should be created"
    testAsset.id != null
    getLOG().info("Created test asset with ID: ${testAsset.id}")

    when: "ten assets for segment delete verification are created with the same attributes"
    def segmentDeleteAssets = (1..10).collect { index ->
      def asset = new ThingAsset("Hypercore Segment Delete Asset ${index}")
              .setRealm(keycloakTestSetup.realmMaster.name)
      asset.addOrReplaceAttributes(numberAttributes(attributeNames))
      assetStorageService.merge(asset)
    }
    def segmentDeleteAssetIds = segmentDeleteAssets.collect { it.id }

    then: "the segment delete assets should be created"
    segmentDeleteAssets.every { it.id != null }
    getLOG().info("Created ${segmentDeleteAssets.size()} segment delete assets with IDs: ${segmentDeleteAssetIds}")

    when: "25.000 datapoints are inserted across multiple attributes with some within the 4-week retention window"
    def totalDatapoints = 25_000
    def datapointsPerAttribute = (totalDatapoints / attributeNames.size()) as int
    def recentDatapointsPerAttribute = (datapointsPerAttribute * 0.2) as int
    def oldDatapointsPerAttribute = datapointsPerAttribute - recentDatapointsPerAttribute
    def expectedRecentDatapoints = recentDatapointsPerAttribute * attributeNames.size()
    def currentTime = getClockTimeOf(container)
    def oldBaseTimestamp = Instant.ofEpochMilli(currentTime).minus(365, ChronoUnit.DAYS)
    def recentBaseTimestamp = Instant.ofEpochMilli(currentTime).minus(7, ChronoUnit.DAYS)

    getLOG().info("Starting to insert ${totalDatapoints} datapoints...")
    def insertStartTime = System.currentTimeMillis()

    // Insert datapoints in batches for better performance
    def batchSize = 10000
    attributeNames.each { attributeName ->
      getLOG().info("Inserting ${datapointsPerAttribute} datapoints for attribute: ${attributeName}")

      for (int batchStart = 0; batchStart <datapointsPerAttribute; batchStart += batchSize) {
        def batchEnd = Math.min(batchStart + batchSize, datapointsPerAttribute)
        def datapoints = []
        for (int index = batchStart; index <batchEnd; index++) {
          def timestamp = index <oldDatapointsPerAttribute
                  ? oldBaseTimestamp.plus(index * 30, ChronoUnit.SECONDS).toEpochMilli()
                  : recentBaseTimestamp.plus((index - oldDatapointsPerAttribute) * 30, ChronoUnit.SECONDS).toEpochMilli()
          def value = 20.0 + (Math.sin(index / 100.0) * 10.0) + (Math.random() * 2.0)
          datapoints.add(new ValueDatapoint<>(timestamp, value))
        }
        assetDatapointService.upsertValues(testAsset.id, attributeName, datapoints)

        if (batchEnd % (batchSize * 10) == 0 || batchEnd == datapointsPerAttribute) {
          getLOG().info("  Progress: ${batchEnd} / ${datapointsPerAttribute} datapoints inserted for ${attributeName}")
        }
      }
    }

    def segmentDeleteDatapointsPerAttribute = 250
    segmentDeleteAssets.eachWithIndex { asset, assetIndex ->
      attributeNames.each { attributeName ->
        def datapoints = []
        for (int index = 0; index <segmentDeleteDatapointsPerAttribute; index++) {
          def timestamp = oldBaseTimestamp.plus(index * 30, ChronoUnit.SECONDS).toEpochMilli()
          datapoints.add(new ValueDatapoint<>(timestamp, 100.0 + assetIndex + index))
        }
        assetDatapointService.upsertValues(asset.id, attributeName, datapoints)
      }
    }

    def insertEndTime = System.currentTimeMillis()
    def insertDuration = (insertEndTime - insertStartTime) / 1000.0
    getLOG().info("Finished inserting ${totalDatapoints} datapoints in ${insertDuration} seconds")

    then: "datapoints should be stored"
    conditions.eventually {
      def count = 0
      attributeNames.each { attributeName ->
        def datapoints = assetDatapointService.getDatapoints(new AttributeRef(testAsset.id, attributeName))
        count += datapoints.size()
      }
      assert count >= totalDatapoints * 0.99 // Allow for 1% margin
      getLOG().info("Verified ${count} datapoints stored")
    }
    countDatapoints(persistenceService, segmentDeleteAssetIds, attributeNames) == segmentDeleteDatapointsPerAttribute * attributeNames.size() * segmentDeleteAssets.size()

    when: "storage usage is measured before enabling hypercore"
    def orDatabaseSizeBefore = persistenceService.doReturningTransaction { em ->
      def query = em.createNativeQuery("""
                SELECT pg_database_size(pg_database.datname)
                FROM pg_database
                WHERE pg_database.datname = 'openremote';
            """)
      return query.getSingleResult() as Long
    }

    getLOG().info("\n=== Storage BEFORE Hypercore ===")
    getLOG().info("\nDatabase size: ${String.format('%.2f MB', orDatabaseSizeBefore / (1024.0 * 1024.0))}")

    and: "compression job is called manually"
    def job_id = persistenceService.doReturningTransaction { em ->
      def query = em.createNativeQuery("""
                SELECT job_id
                FROM timescaledb_information.jobs
                WHERE proc_name = 'policy_compression' AND hypertable_name = 'asset_datapoint';
            """)
      return query.getSingleResult()
    }

    // run_job contains internal COMMIT statements, so it must be executed outside of a Hibernate transaction
    def dataSource = persistenceService.persistenceUnitProperties.get(AvailableSettings.DATASOURCE) as javax.sql.DataSource
    def connection = dataSource.getConnection()
    try {
      connection.setAutoCommit(true)
      def stmt = connection.prepareCall("CALL public.run_job(?)")
      stmt.setInt(1, job_id as int)
      stmt.execute()
      stmt.close()
    } finally {
      connection.close()
    }

    def orDatabaseSizeAfter = persistenceService.doReturningTransaction { em ->
      def query = em.createNativeQuery("""
                SELECT pg_database_size(pg_database.datname)
                FROM pg_database
                WHERE pg_database.datname = 'openremote';
            """)
      return query.getSingleResult() as Long
    }

    getLOG().info("\n=== Storage AFTER Hypercore ===")
    getLOG().info("\nDatabase size: ${String.format('%.2f MB', orDatabaseSizeAfter / (1024.0 * 1024.0))}")

    then: "storage should be smaller after hypercore"
    orDatabaseSizeBefore> orDatabaseSizeAfter

    when: "the segment-delete assets are deleted from compressed chunks with a low decompression limit"
    setMaxTuplesDecompressedPerDmlTransaction(persistenceService, 100)
    def failedSegmentDeleteAssetId = segmentDeleteAssetIds.first()
    failedDeleteAssetIds.add(failedSegmentDeleteAssetId)
    def segmentDeleteSucceeded = assetStorageService.delete(segmentDeleteAssetIds)

    then: "TimescaleDB should delete full segments without decompressing chunks"
    segmentDeleteSucceeded

    and: "asset DELETE events should be published for all assets and DELETE_FINISHED for all but the failed asset"
    conditions.eventually {
      assert countDatapoints(persistenceService, segmentDeleteAssetIds, attributeNames) == 0

      def deleteIds = assetPersistenceEvents
              .findAll { it.cause == PersistenceEvent.Cause.DELETE }
              .collect { it.entity.id }
      def deleteFinishedIds = assetPersistenceEvents
              .findAll { it.cause == PersistenceEvent.Cause.DELETE_FINISHED }
              .collect { it.entity.id }

      assert segmentDeleteAssetIds.every { deleteIds.contains(it) }
      assert (segmentDeleteAssetIds - failedSegmentDeleteAssetId).every {
        deleteFinishedIds.contains(it)
      }
      assert !deleteFinishedIds.contains(failedSegmentDeleteAssetId)
    }

    and: "the failed asset should be hidden from asset queries"
    assetStorageService.find(new AssetQuery().ids(failedSegmentDeleteAssetId)) == null

    when: "the failed asset deletion is retried"
    failedDeleteAssetIds.remove(failedSegmentDeleteAssetId)
    assetStorageService.retryFailedAssetDeletes()

    then: "the failed asset should be physically deleted"
    conditions.eventually {
      assert assetPersistenceEvents.any {
        it.cause == PersistenceEvent.Cause.DELETE_FINISHED && it.entity.id == failedSegmentDeleteAssetId
      }
      assert countAssets(persistenceService, failedSegmentDeleteAssetId) == 0L
    }

    when: "Purging will be called, count datapoints before purging"
    def countBeforePurge = 0
    attributeNames.each { attributeName ->
      def dataPoints = assetDatapointService.getDatapoints(new AttributeRef(testAsset.id, attributeName))
      countBeforePurge += dataPoints.size()
    }
    getLOG().info("Datapoints before purge: ${countBeforePurge}")

    and: "the purge routine is executed"
    def deleteStartTime = System.currentTimeMillis()
    assetDatapointService.purgeDataPoints()
    def deleteEndTime = System.currentTimeMillis()
    def deleteDuration = (deleteEndTime - deleteStartTime) / 1000.0

    getLOG().info("Purge completed in ${deleteDuration} seconds")

    then: "data points beyond the 4-week retention window should be purged via drop_chunks"
    conditions.eventually {
      def countAfterPurge = 0
      attributeNames.each { attributeName ->
        def dataPoints = assetDatapointService.getDatapoints(new AttributeRef(testAsset.id, attributeName))
        countAfterPurge += dataPoints.size()
      }
      def deletedCount = countBeforePurge - countAfterPurge

      getLOG().info("Datapoints after purge: ${countAfterPurge}")
      getLOG().info("Deleted ${deletedCount} datapoints")
      getLOG().info("Deletion rate: ${String.format('%.0f', deletedCount / deleteDuration)} datapoints/second")

      // With 365 days of data and 4-week (28-day) retention, most data should be purged
      assert countAfterPurge <countBeforePurge
      assert countAfterPurge >= expectedRecentDatapoints
      assert deletedCount> 0
    }

    when: "storage usage is measured after deletion"
    def orDatabaseSizeAfterDeletion = persistenceService.doReturningTransaction { em ->
      def query = em.createNativeQuery("""
                SELECT pg_database_size(pg_database.datname)
                FROM pg_database
                WHERE pg_database.datname = 'openremote';
            """)
      return query.getSingleResult() as Long
    }

    getLOG().info("\n=== Storage AFTER Deletion ===")
    getLOG().info("\nDatabase size: ${String.format('%.2f MB', orDatabaseSizeAfterDeletion / (1024.0 * 1024.0))}")

    then: "final storage should be measured"
    true

    when: "final verification of remaining datapoints"
    def finalCount = 0
    attributeNames.each { attributeName ->
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(testAsset.id, attributeName))
      finalCount += datapoints.size()
    }
    def actualDeleted = countBeforePurge - finalCount
    def compressionRatio = ((orDatabaseSizeBefore - orDatabaseSizeAfter) / (double) orDatabaseSizeBefore) * 100

    getLOG().info("\n=== Final Summary ===")
    getLOG().info("Total datapoints inserted: ${totalDatapoints}")
    getLOG().info("Datapoints before purge: ${countBeforePurge}")
    getLOG().info("Datapoints deleted by purge: ${actualDeleted}")
    getLOG().info("Datapoints remaining: ${finalCount}")
    getLOG().info("Deletion percentage: ${String.format('%.2f', (actualDeleted / totalDatapoints * 100.0))}%")
    getLOG().info("\nStorage Summary:")
    getLOG().info("  Before hypercore: ${String.format('%.2f MB', orDatabaseSizeBefore / (1024.0 * 1024.0))}")
    getLOG().info("  After hypercore:  ${String.format('%.2f MB', orDatabaseSizeAfter / (1024.0 * 1024.0))}")
    getLOG().info("  After deletion:   ${String.format('%.2f MB', orDatabaseSizeAfterDeletion / (1024.0 * 1024.0))}")
    getLOG().info("\nPerformance Summary:")
    getLOG().info("  Insert time: ${insertDuration} seconds")
    getLOG().info("  Purge time: ${deleteDuration} seconds")
    getLOG().info("  Compression: ${String.format('%.2f', compressionRatio)}%")

    then: "No exception should have occurred"
    true

    cleanup: "restore settings and remove the temporary route"
    if (messageBrokerService != null && assetPersistenceRouteId != null) {
      try {
        messageBrokerService.context.routeController.stopRoute(assetPersistenceRouteId)
      } catch (Exception ignored) {
        // Route may not have been added if setup failed early.
      }
      try {
        messageBrokerService.context.removeRoute(assetPersistenceRouteId)
      } catch (Exception ignored) {
        // Route may already be gone during container shutdown.
      }
    }
    if (assetStorageService != null && originalAssetDeleteDatapointBatchThreshold != null) {
      assetStorageService.assetDeleteDatapointBatchThreshold = originalAssetDeleteDatapointBatchThreshold
    }
    if (assetStorageService != null && originalAssetDeleteDatapointBatchWeeks != null) {
      assetStorageService.assetDeleteDatapointBatchWeeks = originalAssetDeleteDatapointBatchWeeks
    }
    if (container != null && originalAssetStorageService != null) {
      container.@services.put(AssetStorageService.class, originalAssetStorageService)
    }
    if (persistenceService != null && originalMaxTuplesDecompressedPerDmlTransaction != null) {
      setMaxTuplesDecompressedPerDmlTransaction(persistenceService, originalMaxTuplesDecompressedPerDmlTransaction)
    }
  }

  private static Attribute<?>[] numberAttributes(List<String> attributeNames) {
    return attributeNames.collect { new Attribute<>(it, ValueType.NUMBER) } as Attribute<?>[]
  }

  private static long countDatapoints(PersistenceService persistenceService, List<String> assetIds, List<String> attributeNames) {
    return persistenceService.doReturningTransaction { em ->
      def query = em.createNativeQuery("""
                SELECT count(*)
                FROM asset_datapoint
                WHERE entity_id = ANY(string_to_array(:assetIds, ','))
                  AND attribute_name = ANY(string_to_array(:attributeNames, ','));
            """)
      query.setParameter("assetIds", assetIds.join(","))
      query.setParameter("attributeNames", attributeNames.join(","))
      return (query.getSingleResult() as Number).longValue()
    }
  }

  private static long countAssets(PersistenceService persistenceService, String assetId) {
    return persistenceService.doReturningTransaction { em ->
      def query = em.createNativeQuery("SELECT count(*) FROM asset WHERE id = :assetId")
      query.setParameter("assetId", assetId)
      return (query.getSingleResult() as Number).longValue()
    }
  }

  private static String getMaxTuplesDecompressedPerDmlTransaction(PersistenceService persistenceService) {
    return persistenceService.doReturningTransaction { em ->
      em.createNativeQuery("SHOW timescaledb.max_tuples_decompressed_per_dml_transaction").getSingleResult() as String
    }
  }

  private static void setMaxTuplesDecompressedPerDmlTransaction(PersistenceService persistenceService, Object limit) {
    persistenceService.doTransaction { em ->
      def query = em.createNativeQuery("SELECT set_config('timescaledb.max_tuples_decompressed_per_dml_transaction', :limit, false)")
      query.setParameter("limit", limit.toString())
      query.getSingleResult()
    }
  }
}
