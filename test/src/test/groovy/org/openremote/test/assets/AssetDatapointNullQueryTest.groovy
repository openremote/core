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

import org.openremote.manager.asset.AssetStorageService
import org.openremote.manager.datapoint.AssetDatapointService
import org.openremote.manager.datapoint.AssetPredictedDatapointService
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.datapoint.ValueDatapoint
import org.openremote.model.datapoint.query.AssetDatapointAllQuery
import org.openremote.model.datapoint.query.AssetDatapointIntervalQuery
import org.openremote.model.datapoint.query.AssetDatapointIntervalQuery.Formula
import org.openremote.model.datapoint.query.AssetDatapointLTTBQuery
import org.openremote.model.datapoint.query.AssetDatapointNearestQuery
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification

import java.time.Instant
import java.time.temporal.ChronoUnit

class AssetDatapointNullQueryTest extends Specification implements ManagerContainerTrait {

  AssetStorageService assetStorageService
  AssetDatapointService historicalService
  AssetPredictedDatapointService predictedService
  ThingAsset asset
  String realm
  long start

  def setup() {
    def container = startContainer(defaultConfig(), defaultServices())
    stopPseudoClock()
    assetStorageService = container.getService(AssetStorageService.class)
    historicalService = container.getService(AssetDatapointService.class)
    predictedService = container.getService(AssetPredictedDatapointService.class)
    realm = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class).realmBuilding.name
    start = getInstantTimeOf(container).truncatedTo(ChronoUnit.SECONDS).toEpochMilli()
  }

  def cleanup() {
    if (asset?.id) {
      assert assetStorageService.delete([asset.id])
    }
  }

  def "numeric All query preserves #description for #storage #valueType.name datapoints"() {
    given: "numeric datapoints containing explicitly stored JSON nulls"
    asset = assetStorageService.merge(new ThingAsset("Null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    def timestamps = (0..<values.size()).collect { start + it * 60_000L }
    def datapoints = values.withIndex().collect { value, index ->
      new ValueDatapoint<>(timestamps[index], value)
    }
    // The query must include both boundary timestamps and exclude their neighbours.
    datapoints.add(new ValueDatapoint<>(timestamps.first() - 60_000L, 111G))
    datapoints.add(new ValueDatapoint<>(timestamps.last() + 60_000L, 222G))
    service.upsertValues(asset.id, "value", datapoints)

    when: "the inclusive range is queried without aggregation"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointAllQuery(timestamps.first(), timestamps.last()))

    then: "every timestamp is returned newest first, retaining unavailable values"
    result.collect { it.timestamp } == timestamps.reverse()
    result.collect { it.value } == values.reverse()

    where:
    [storage, valueType, description, values] << ["historical", "predicted"].collectMany { storage ->
      [ValueType.NUMBER, ValueType.BIG_NUMBER].collectMany { type ->
        [
          [storage, type, "mixed values", [-5G, 0G, null, 90.125G]],
          [storage, type, "an entirely null range", [null, null, null]]
        ]
      }
    }
  }

  def "boolean All query preserves #description for #storage datapoints"() {
    given: "boolean datapoints containing explicitly stored JSON nulls"
    asset = assetStorageService.merge(new ThingAsset("Boolean null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", ValueType.BOOLEAN)))
    def service = storage == "historical" ? historicalService : predictedService
    def timestamps = (0..<values.size()).collect { start + it * 60_000L }
    def datapoints = values.withIndex().collect { value, index ->
      new ValueDatapoint<>(timestamps[index], value)
    }
    datapoints.add(new ValueDatapoint<>(timestamps.first() - 60_000L, true))
    datapoints.add(new ValueDatapoint<>(timestamps.last() + 60_000L, false))
    service.upsertValues(asset.id, "value", datapoints)

    when: "the inclusive range is queried without aggregation"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointAllQuery(timestamps.first(), timestamps.last()))

    then: "true, false, and unavailable remain distinct at their original timestamps"
    result.collect { it.timestamp } == timestamps.reverse()
    result.collect { it.value } == expectedValues.reverse()

    where:
    [storage, description, values, expectedValues] << ["historical", "predicted"].collectMany { storage ->
      [
        [storage, "mixed values", [true, false, null], [1D, 0D, null]],
        [storage, "an entirely null range", [null, null, null], [null, null, null]]
      ]
    }
  }

  def "numeric Nearest query preserves unavailable values for #storage #valueType.name datapoints"() {
    given: "an unavailable datapoint between older and newer available prices"
    asset = assetStorageService.merge(new ThingAsset("Nearest null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    service.upsertValues(asset.id, "value", [
      new ValueDatapoint<>(start, -5G),
      new ValueDatapoint<>(start + 60_000L, null),
      new ValueDatapoint<>(start + 120_000L, 0G),
      new ValueDatapoint<>(start + 180_000L, 90.125G)
    ])
    def queryAt = { long offset ->
      service.queryDatapoints(asset.id, "value", new AssetDatapointNearestQuery(start + offset)).collect {
        [it.timestamp, it.value]
      }
    }

    expect: "an exact match retains the null value and its timestamp"
    queryAt(60_000L) == [[start + 60_000L, null]]

    and: "a query between observations returns the null row, not an older or newer price"
    queryAt(90_000L) == [[start + 60_000L, null]]

    and: "a query before the first observation returns no datapoint"
    queryAt(-1_000L).isEmpty()

    and: "available observations remain selectable at and after their timestamps"
    queryAt(0) == [[start, -5G]]
    queryAt(120_000L) == [[start + 120_000L, 0G]]
    queryAt(240_000L) == [[start + 180_000L, 90.125G]]

    where:
    [storage, valueType] << [["historical", "predicted"], [ValueType.NUMBER, ValueType.BIG_NUMBER]].combinations()
  }

  def "numeric #formula handles unavailable values for #storage #valueType.name with gapFill=#gapFill"() {
    given: "mixed, entirely null, empty, and zero-valued minute buckets"
    asset = assetStorageService.merge(new ThingAsset("Interval null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    def bucketStart = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
    service.upsertValues(asset.id, "value", [
      [-1_000L, 999G],
      [0L, -6G],
      [10_000L, 0G],
      [20_000L, null],
      [30_000L, 6G],
      [40_000L, 12G],
      [70_000L, null],
      [100_000L, null],
      [190_000L, 0G],
      [240_000L, 999G]
    ].collect { new ValueDatapoint<>(bucketStart + it[0], it[1]) })

    when: "the four-minute range is aggregated"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart, bucketStart + 239_999L,
            "1 minute", formula, gapFill))

    then: "null-only buckets remain present and empty buckets follow the gap-fill setting"
    result.collect {
      it.timestamp
    } == (gapFill ? [0L, 60_000L, 120_000L, 180_000L] : [0L, 60_000L, 180_000L])
    .collect { bucketStart + it }
    result.collect { it.value } == (gapFill
    ? [mixedValue, nullBucketValue, null, zeroBucketValue]
    : [mixedValue, nullBucketValue, zeroBucketValue])

    where:
    [storage, valueType, gapFill, formula, mixedValue, nullBucketValue, zeroBucketValue] << [["historical", "predicted"], [ValueType.NUMBER, ValueType.BIG_NUMBER], [false, true]].combinations().collectMany { storage, type, gapFill ->
      [
        [Formula.MIN, -6G, null, 0G],
        [Formula.MAX, 12G, null, 0G],
        [Formula.AVG, 3G, null, 0G],
        [Formula.SUM, 12G, null, 0G],
        [Formula.MEDIAN, 3G, null, 0G],
        // COUNT retains row-count semantics, including stored nulls.
        [Formula.COUNT, 5G, 2G, 1G]
      ].collect { [storage, type, gapFill, *it] }
    }
  }

  def "boolean #formula handles unavailable values for #storage with gapFill=#gapFill"() {
    given: "mixed, entirely null, empty, false, and true minute buckets"
    asset = assetStorageService.merge(new ThingAsset("Boolean interval null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", ValueType.BOOLEAN)))
    def service = storage == "historical" ? historicalService : predictedService
    def bucketStart = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
    service.upsertValues(asset.id, "value", [
      [-1_000L, true],
      [0L, true],
      [10_000L, false],
      [20_000L, null],
      [30_000L, null],
      [40_000L, null],
      [70_000L, null],
      [100_000L, null],
      [190_000L, false],
      [200_000L, null],
      [250_000L, true],
      [260_000L, null],
      [300_000L, false]
    ].collect { new ValueDatapoint<>(bucketStart + it[0], it[1]) })

    when: "the five-minute range is aggregated"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart, bucketStart + 299_999L,
            "1 minute", formula, gapFill))

    then: "unavailable values are distinct from false and empty buckets follow the gap-fill setting"
    result.collect { it.timestamp } == (gapFill
    ? [0L, 60_000L, 120_000L, 180_000L, 240_000L]
    : [0L, 60_000L, 180_000L, 240_000L]).collect { bucketStart + it }
    result.collect { it.value } == (gapFill
    ? [mixedValue, nullBucketValue, null, falseBucketValue, trueBucketValue]
    : [mixedValue, nullBucketValue, falseBucketValue, trueBucketValue])

    where:
    [storage, gapFill, formula, mixedValue, nullBucketValue, falseBucketValue, trueBucketValue] << [["historical", "predicted"], [false, true]].combinations().collectMany { storage, gapFill ->
      [
        [Formula.MIN, 0D, null, 0D, 1D],
        [Formula.MAX, 1D, null, 0D, 1D],
        [Formula.AVG, 0.5D, null, 0D, 1D],
        [Formula.SUM, 1D, null, 0D, 1D],
        // MEDIAN retains the existing true result when available values are tied.
        [Formula.MEDIAN, 1D, null, 0D, 1D],
        // COUNT retains row-count semantics, including stored nulls.
        [Formula.COUNT, 5D, 2D, 2D, 2D]
      ].collect { [storage, gapFill, *it] }
    }
  }

  def "MODE ignores unavailable values for #storage #valueType.name with gapFill=#gapFill"() {
    given: "nulls outnumber available values, followed by null-only, empty, tied, zero, and high-value buckets"
    asset = assetStorageService.merge(new ThingAsset("Mode null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    def bucketStart = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
    def buckets = [
      [low, low, high, null, null, null],
      [null, null],
      [],
      [low, high, null],
      [zero, null],
      [high, high, low, null]
    ]
    def datapoints = buckets.withIndex().collectMany { values, bucket ->
      values.withIndex().collect { value, index ->
        new ValueDatapoint<>(bucketStart + bucket * 60_000L + index * 1_000L, value)
      }
    }
    datapoints.add(new ValueDatapoint<>(bucketStart - 1_000L, high))
    datapoints.add(new ValueDatapoint<>(bucketStart + 360_000L, low))
    service.upsertValues(asset.id, "value", datapoints)

    when: "the six-minute range is queried for its most frequent available values"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart, bucketStart + 359_999L,
            "1 minute", Formula.MODE, gapFill))

    then: "nulls cannot win and existing numeric and boolean tie-breaking is preserved"
    // Preserve existing MODE behavior: empty buckets are omitted for either gapFill setting.
    result.collect { it.timestamp } == [0L, 60_000L, 180_000L, 240_000L, 300_000L]
    .collect { bucketStart + it }
    result.collect { it.value } == [lowResult, null, tieResult, 0D, highResult]

    when: "only the entirely unavailable bucket is queried"
    def unavailable = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart + 60_000L, bucketStart + 119_999L,
            "1 minute", Formula.MODE, gapFill))

    then: "the bucket and its null result remain present"
    unavailable.collect { [it.timestamp, it.value] } == [[bucketStart + 60_000L, null]]

    where:
    [storage, gapFill, valueType, low, high, zero, lowResult, highResult, tieResult] << [["historical", "predicted"], [false, true]].combinations().collectMany { storage, gapFill ->
      [
        [ValueType.NUMBER, -5G, 7G, 0G, -5D, 7D, -5D],
        [ValueType.BIG_NUMBER, -5G, 7G, 0G, -5D, 7D, -5D],
        [ValueType.BOOLEAN, false, true, false, 0D, 1D, 1D]
      ].collect { [storage, gapFill, *it] }
    }
  }

  def "DIFFERENCE preserves unavailable values for #storage #valueType.name with gapFill=#gapFill"() {
    given: "last values, explicit unavailability, recovery, and empty minute buckets"
    asset = assetStorageService.merge(new ThingAsset("Difference null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    def bucketStart = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.MINUTES).toEpochMilli()
    service.upsertValues(asset.id, "value", [
      [-61_000L, 999G],
      [0L, 75G],
      [10_000L, 80G],
      [120_000L, 90G],
      [130_000L, null],
      [240_000L, null],
      [250_000L, 100G],
      [300_000L, 110G],
      [360_000L, 0G],
      [420_000L, -5G],
      [540_000L, -10G],
      [660_000L, 999G]
    ].collect { new ValueDatapoint<>(bucketStart + it[0], it[1]) })

    when: "the eleven-minute range is queried for changes between bucket values"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart, bucketStart + 659_999L,
            "1 minute", Formula.DIFFERENCE, gapFill))

    then: "explicit nulls interrupt differences while empty buckets carry forward when enabled"
    result.collect { it.timestamp } == (gapFill
    ? (0..10).toList() : [0, 2, 4, 5, 6, 7, 9]).collect { bucketStart + it * 60_000L }
    result.collect { it.value } == (gapFill
    ? [0D, 0D, null, null, null, 10D, -110D, -5D, 0D, -5D, 0D]
    : [0D, null, null, 10D, -110D, -5D, -5D])

    when: "the query starts with an empty bucket before the first available value"
    def leadingGap = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart - 60_000L, bucketStart + 119_999L,
            "1 minute", Formula.DIFFERENCE, gapFill))

    then: "an unavailable predecessor does not become an assumed zero baseline"
    leadingGap.collect { [it.timestamp, it.value] } == (gapFill
    ? [[bucketStart - 60_000L, null], [bucketStart, null], [bucketStart + 60_000L, 0D]]
    : [[bucketStart, 0D]])

    when: "only an empty bucket is requested"
    def empty = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart + 60_000L, bucketStart + 119_999L,
            "1 minute", Formula.DIFFERENCE, gapFill))

    then: "carry-forward does not read observations outside the query range"
    empty.collect { [it.timestamp, it.value] } == (gapFill ? [[bucketStart + 60_000L, null]] : [])

    when: "only explicitly unavailable observations are requested"
    def unavailable = service.queryDatapoints(asset.id, "value",
            new AssetDatapointIntervalQuery(bucketStart + 130_000L, bucketStart + 179_999L,
            "1 minute", Formula.DIFFERENCE, gapFill))

    then: "the initial difference stays unavailable"
    unavailable.collect { [it.timestamp, it.value] } == [[bucketStart + 120_000L, null]]

    where:
    [storage, valueType, gapFill] << [["historical", "predicted"], [ValueType.NUMBER, ValueType.BIG_NUMBER], [false, true]].combinations()
  }

  def "LTTB omits unavailable values in #description for #storage #valueType.name datapoints"() {
    given: "datapoints with unavailable observations and out-of-range neighbours"
    asset = assetStorageService.merge(new ThingAsset("LTTB null query test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    def timestamps = (0..<values.size()).collect { start + it * 60_000L }
    def datapoints = values.withIndex().collect { value, index ->
      new ValueDatapoint<>(timestamps[index], value)
    }
    def outsideValue = valueType == ValueType.BOOLEAN ? true : 999G
    datapoints.add(new ValueDatapoint<>(timestamps.first() - 60_000L, outsideValue))
    datapoints.add(new ValueDatapoint<>(timestamps.last() + 60_000L, outsideValue))
    service.upsertValues(asset.id, "value", datapoints)

    when: "the requested resolution exceeds the available observations"
    def result = service.queryDatapoints(asset.id, "value",
            new AssetDatapointLTTBQuery(timestamps.first(), timestamps.last(), 10))

    then: "only available observations are returned, chronologically and at their original timestamps"
    result.collect { [it.timestamp, it.value] } == expected.collect { index, value ->
      [timestamps[index], value]
    }

    where:
    [storage, valueType, description, values, expected] << [["historical", "predicted"], [ValueType.NUMBER, ValueType.BIG_NUMBER, ValueType.BOOLEAN]].combinations().collectMany { storage, type ->
      def isBoolean = type == ValueType.BOOLEAN
      [
        [
          storage,
          type,
          "a mixed range",
          isBoolean ? [null, true, null, false, true, null, false, null] : [null, -5G, null, 0G, 90G, null, 40G, null],
          isBoolean ? [[1, 1D], [3, 0D], [4, 1D], [6, 0D]] : [[1, -5D], [3, 0D], [4, 90D], [6, 40D]]
        ],
        [storage, type, "an entirely null range", [null, null, null], []],
        [storage, type, "a single available observation", [null, isBoolean ? false : 0G, null], [[1, 0D]]]
      ]
    }
  }

  def "LTTB downsampling ignores unavailable observations for #storage #valueType.name datapoints"() {
    given: "the same available series stored with and without null observations"
    asset = assetStorageService.merge(new ThingAsset("LTTB downsampling null test")
            .setRealm(realm).addOrReplaceAttributes(new Attribute<>("value", valueType), new Attribute<>("available", valueType)))
    def service = storage == "historical" ? historicalService : predictedService
    def values = valueType == ValueType.BOOLEAN
            ? [null, false, true, null, false, true, null, true, false, null, true, false, null]
            : [null, 0G, 12G, null, -4G, 50G, null, 3G, 0G, null, 9G, 2G, null]
    def datapoints = values.withIndex().collect { value, index ->
      new ValueDatapoint<>(start + index * 60_000L, value)
    }
    service.upsertValues(asset.id, "value", datapoints)
    service.upsertValues(asset.id, "available", datapoints.findAll { it.value != null })

    when: "both series are reduced from eight available points to four"
    def query = new AssetDatapointLTTBQuery(start, start + 720_000L, 4)
    def result = service.queryDatapoints(asset.id, "value", query)
    def reference = service.queryDatapoints(asset.id, "available", query)

    then: "null observations do not affect point selection or the first and last available points"
    result.size() == 4
    result.collect { [it.timestamp, it.value] } == reference.collect { [it.timestamp, it.value] }
    result.first().timestamp == start + 60_000L
    result.last().timestamp == start + 660_000L
    result.every { it.value != null }
    result.collect { it.timestamp } == result.collect { it.timestamp }.sort()

    where:
    [storage, valueType] << [["historical", "predicted"], [ValueType.NUMBER, ValueType.BIG_NUMBER, ValueType.BOOLEAN]].combinations()
  }
}
