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
import org.openremote.manager.setup.SetupService
import org.openremote.model.asset.impl.LightAsset
import org.openremote.model.asset.impl.ThingAsset
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.model.datapoint.AssetDatapoint
import org.openremote.model.datapoint.DatapointQueryTooLargeException
import org.openremote.model.datapoint.ValueDatapoint
import org.openremote.model.datapoint.query.AssetDatapointAllQuery
import org.openremote.model.datapoint.query.AssetDatapointIntervalQuery
import org.openremote.model.datapoint.query.AssetDatapointLTTBQuery
import org.openremote.model.geo.GeoJSONPoint
import org.openremote.model.query.AssetQuery
import org.openremote.model.query.filter.RealmPredicate
import org.openremote.model.util.UniqueIdentifierGenerator
import org.openremote.model.value.MetaItemType
import org.openremote.model.value.ValueType
import org.openremote.setup.integration.KeycloakTestSetup
import org.openremote.test.ManagerContainerTrait
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class AssetDatapointQueryTest extends Specification implements ManagerContainerTrait {

  def "Test lttb datapoint query"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetDatapointService = container.getService(AssetDatapointService.class)
    assetDatapointService.maxAmountOfQueryPoints = 1000


    when: "requesting the first light asset in City realm"
    def asset = assetStorageService.find(
            new AssetQuery()
            .types(LightAsset.class)
            .realm(new RealmPredicate(keycloakTestSetup.realmCity.name))
            .names("Light 1")
    )

    then: "asset should exist and be correct"
    def assetName = "Light 1"
    def attributeName = "brightness"
    def dateTime = LocalDateTime.now()
    assert asset != null // light 1, 2 and 3
    assert asset.name == assetName
    assert asset.attributes.has(attributeName)

    and: "no datapoints should exist"
    conditions.eventually {
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(asset.getId(), attributeName))
      assert datapoints.isEmpty()
    }


    /* ------------------------- */

    when: "datapoints are added to the asset"
    assetDatapointService.upsertValues(asset.getId(), attributeName,
            [
              new ValueDatapoint<>(dateTime.minusMinutes(25).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 50d),
              new ValueDatapoint<>(dateTime.minusMinutes(20).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 40d),
              new ValueDatapoint<>(dateTime.minusMinutes(15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 30d),
              new ValueDatapoint<>(dateTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 20d),
              new ValueDatapoint<>(dateTime.minusMinutes(5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 10d),
            ]
    )

    then: "datapoints should exist"
    def allDatapoints = new ArrayList<ValueDatapoint>()
    conditions.eventually {
      allDatapoints = assetDatapointService.getDatapoints(new AttributeRef(asset.getId(), attributeName))
      assert allDatapoints.size() == 5
    }

    /* ------------------------- */

    and: "requesting 50 datapoints using the LTTB algorithm should return 5 values"
    def lttbDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointLTTBQuery(dateTime.minusMinutes(30), dateTime, 5)
    )
    assert lttbDatapoints1.size() == 5

    and: "the 5 values are equal to 5 values in 'allDatapoints'"
    def index = 0
    Collections.reverse(allDatapoints) // since they're returned in reverse other (from new to old)
    allDatapoints.stream().forEach {dp -> {
        assert dp.timestamp == lttbDatapoints1[index].timestamp
        assert dp.value == lttbDatapoints1[index].value
        index++
      }
    }

    and: "requesting 3 datapoints using LTTB should return 50, 40 and 10 to reflect the algorithm spec."
    def lttbDatapoints2 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointLTTBQuery(dateTime.minusMinutes(30), dateTime, 3)
    )
    assert lttbDatapoints2.size() == 3
    assert lttbDatapoints2[0].value == 50d
    assert lttbDatapoints2[1].value == 40d
    assert lttbDatapoints2[2].value == 10d


    /* ------------------------- */

    when: "the datapoints are cleared"
    assetDatapointService.purgeDataPoints()

    and: "datapoints are added that have a spike in value, it should be included in any downsample"
    assetDatapointService.upsertValues(asset.getId(), attributeName,
            [
              // placing them in a random order to verify order that is returned with the query
              new ValueDatapoint<>(dateTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 25d),
              new ValueDatapoint<>(dateTime.minusMinutes(5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 30d),
              new ValueDatapoint<>(dateTime.minusMinutes(30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 10d),
              new ValueDatapoint<>(dateTime.minusMinutes(20).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 90d),
              new ValueDatapoint<>(dateTime.minusMinutes(25).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 15d),
              new ValueDatapoint<>(dateTime.minusMinutes(15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 20d),
            ]
    )

    then: "the spike should be present as the 2nd value"
    def lttbDatapoints3 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointLTTBQuery(dateTime.minusMinutes(60), dateTime, 4)
    )
    assert lttbDatapoints3.size() == 4
    assert lttbDatapoints3[0].value == 10d
    assert lttbDatapoints3[1].value == 90d
    assert lttbDatapoints3[2].value == 20d
    assert lttbDatapoints3[3].value == 30d

    and: "returned datapoints should be in chronological order" // so from earliest to most recent
    def index2 = 0
    lttbDatapoints3.toList().stream().forEach { dp -> {
        if(index2> 0) {
          assert dp.timestamp> lttbDatapoints3[index2 - 1].timestamp
        }
        index2++
      }
    }
  }



  def "Lttb should not accept anything else than boolean and number"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetDatapointService = container.getService(AssetDatapointService.class)
    assetDatapointService.maxAmountOfQueryPoints = 1000

    when: "a thing asset is added to the building realm"
    def thingId = UniqueIdentifierGenerator.generateId("TestThing")
    def thingAsset = new ThingAsset("TestThing")
            .setId(thingId)
            .setRealm(keycloakTestSetup.realmBuilding.name)
            .setLocation(new GeoJSONPoint(0, 0))
            .addAttributes(
            new Attribute<>("status", ValueType.BOOLEAN, null).addMeta(new MetaItem<>(MetaItemType.STORE_DATA_POINTS)),
            new Attribute<>("deviceId", ValueType.TEXT, null).addMeta(new MetaItem<>(MetaItemType.STORE_DATA_POINTS)),
    )
    thingAsset = assetStorageService.merge(thingAsset)

    and: "add datapoints to the attributes"
    assetDatapointService.upsertValue(thingAsset.getId(), "status", true, LocalDateTime.now())
    assetDatapointService.upsertValue(thingAsset.getId(), "deviceId", "abcdefghijklmnopqrstuvwxyz", LocalDateTime.now())


    then: "datapoints should exist"
    conditions.eventually {
      def booleanDatapoints = assetDatapointService.getDatapoints(new AttributeRef(thingAsset.getId(), "status"))
      assert booleanDatapoints.size() == 1
      def textDatapoints = assetDatapointService.getDatapoints(new AttributeRef(thingAsset.getId(), "deviceId"))
      assert textDatapoints.size() == 1
    }

    and: "lttbQuery with boolean should not throw exception"
    conditions.eventually {
      def lttbDatapoints = assetDatapointService.queryDatapoints(
              thingAsset.getId(),
              thingAsset.getAttribute("status").orElseThrow({
                new RuntimeException("Missing attribute")
              }),
              new AssetDatapointLTTBQuery(LocalDateTime.now().minusMinutes(5), LocalDateTime.now(), 3)
      )
      assert lttbDatapoints.size() == 1
      assert lttbDatapoints[0].value == 1.0 // Aka true as boolean
    }

    when: "lttbQuery is created with text, it should throw exception"
    assetDatapointService.queryDatapoints(
            thingAsset.getId(),
            thingAsset.getAttribute("deviceId").orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointLTTBQuery(LocalDateTime.now().minusMinutes(5), LocalDateTime.now(), 3)
    )

    then: "exception to be thrown"
    IllegalStateException ex = thrown(IllegalStateException)
    assert ex.getMessage() == "Query of type LTTB requires either a number or a boolean attribute."
  }



  def "Interval query should be returned correctly and in chronological order"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetDatapointService = container.getService(AssetDatapointService.class)
    assetDatapointService.maxAmountOfQueryPoints = 1000


    when: "requesting the first light asset in City realm"
    def asset = assetStorageService.find(
            new AssetQuery()
            .types(LightAsset.class)
            .realm(new RealmPredicate(keycloakTestSetup.realmCity.name))
            .names("Light 1")
    )

    then: "asset should exist and be correct"
    def assetName = "Light 1"
    def attributeName = "brightness"
    def dateTime = LocalDateTime.of(2025, Month.AUGUST, 7, 9, 00)
    def queryTime30 = dateTime.minus(30, ChronoUnit.MINUTES)
    def queryTime60 = dateTime.minus(60, ChronoUnit.MINUTES)
    def queryTimeNow = dateTime
    def time30 = queryTime30.atZone(ZoneId.systemDefault()).toInstant()
    def time60 = queryTime60.atZone(ZoneId.systemDefault()).toInstant()
    def timeNow = queryTimeNow.atZone(ZoneId.systemDefault()).toInstant()
    assert asset != null // light 1, 2 and 3
    assert asset.name == assetName
    assert asset.attributes.has(attributeName)

    and: "no datapoints should exist"
    conditions.eventually {
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(asset.getId(), attributeName))
      assert datapoints.isEmpty()
    }


    /* ------------------------- */

    when: "the first datapoints are added to the asset in random order"
    assetDatapointService.upsertValue(asset.getId(), attributeName, 25d, dateTime.minus(10, ChronoUnit.MINUTES))
    assetDatapointService.upsertValue(asset.getId(), attributeName, 30d, dateTime.minus(5, ChronoUnit.MINUTES))
    assetDatapointService.upsertValue(asset.getId(), attributeName, 10d, dateTime.minus(30, ChronoUnit.MINUTES))
    assetDatapointService.upsertValue(asset.getId(), attributeName, 90d, dateTime.minus(20, ChronoUnit.MINUTES))
    assetDatapointService.upsertValue(asset.getId(), attributeName, 15d, dateTime.minus(25, ChronoUnit.MINUTES))
    assetDatapointService.upsertValue(asset.getId(), attributeName, 20d, dateTime.minus(15, ChronoUnit.MINUTES))

    then: "datapoints should exist"
    def allDatapoints = new ArrayList<AssetDatapoint>()
    conditions.eventually {
      allDatapoints = assetDatapointService.getDatapoints(new AttributeRef(asset.getId(), attributeName))
      assert allDatapoints.size() == 6
    }

    /* ------------------------- */

    and: "returned datapoints (without gapfill) should be in chronological order" // so from earliest to most recent
    def intervalDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime60, queryTimeNow, "1 minute", AssetDatapointIntervalQuery.Formula.AVG, false)
    )
    assert intervalDatapoints1.size() == 6
    def index = 0
    intervalDatapoints1.toList().stream().forEach { dp -> {
        if(index> 0) {
          assert dp.timestamp> intervalDatapoints1[index - 1].timestamp
        }
        index++
      }
    }

    and: "returned datapoints (with gapfill) should be in chronological order" // so from earliest to most recent
    def intervalDatapoints2 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime60, queryTimeNow, "1 minute", AssetDatapointIntervalQuery.Formula.AVG, true)
    )
    assert intervalDatapoints2.size() == 61 // because 60/1=60, plus an extra datapoint for the spare milliseconds of time
    def index2 = 0
    intervalDatapoints2.toList().stream().forEach { dp -> {
        if(index2> 0) {
          assert dp.timestamp> intervalDatapoints2[index2 - 1].timestamp
        }
        index2++
      }
    }

    when: "requesting interval datapoints using DIFFERENCE"
    def diffDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime30, queryTimeNow, "5 minutes", AssetDatapointIntervalQuery.Formula.DIFFERENCE, true)
    )

    then: "DIFFERENCE datapoints should be correct"
    assert diffDatapoints1.size() == 7 // Because 30/5=6, plus an extra datapoint for the spare milliseconds of time.
    assert diffDatapoints1[0].timestamp == time30.toEpochMilli()
    assert diffDatapoints1[1].timestamp == time30.plus(5, ChronoUnit.MINUTES).toEpochMilli()
    assert diffDatapoints1[2].timestamp == time30.plus(10, ChronoUnit.MINUTES).toEpochMilli()
    assert diffDatapoints1[3].timestamp == time30.plus(15, ChronoUnit.MINUTES).toEpochMilli()
    assert diffDatapoints1[4].timestamp == time30.plus(20, ChronoUnit.MINUTES).toEpochMilli()
    assert diffDatapoints1[5].timestamp == time30.plus(25, ChronoUnit.MINUTES).toEpochMilli()
    assert diffDatapoints1[6].timestamp == timeNow.toEpochMilli()
    assert diffDatapoints1[0].value == 0 // Initial value was 10, nothing happened since
    assert diffDatapoints1[1].value == 5 // First bump from 10 -> 15, so difference is 5.
    assert diffDatapoints1[2].value == 75 // 15 -> 90
    assert diffDatapoints1[3].value == -70 // 90 -> 20
    assert diffDatapoints1[4].value == 5 // 20 -> 25
    assert diffDatapoints1[5].value == 5 // 25 -> 30
    assert diffDatapoints1[6].value == 0 // Because nothing happened the spare milliseconds of time

    when: "requesting interval datapoints using COUNT"
    def countDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime60, queryTimeNow, "5 minutes", AssetDatapointIntervalQuery.Formula.COUNT, true)
    )

    then: "COUNT datapoints should be correct"
    assert countDatapoints1.size() == 13 // Because 60/5=12, plus an extra datapoint for the spare milliseconds of time.
    assert countDatapoints1[0].timestamp == time60.toEpochMilli()
    assert countDatapoints1[1].timestamp == time60.plus(5, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[2].timestamp == time60.plus(10, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[3].timestamp == time60.plus(15, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[4].timestamp == time60.plus(20, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[5].timestamp == time60.plus(25, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[6].timestamp == time60.plus(30, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[7].timestamp == time60.plus(35, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[8].timestamp == time60.plus(40, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[9].timestamp == time60.plus(45, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[10].timestamp == time60.plus(50, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[11].timestamp == time60.plus(55, ChronoUnit.MINUTES).toEpochMilli()
    assert countDatapoints1[12].timestamp == timeNow.toEpochMilli()
    assert countDatapoints1[0].value == null
    assert countDatapoints1[1].value == null
    assert countDatapoints1[2].value == null
    assert countDatapoints1[3].value == null
    assert countDatapoints1[4].value == null
    assert countDatapoints1[5].value == null
    assert countDatapoints1[6].value == 1
    assert countDatapoints1[7].value == 1
    assert countDatapoints1[8].value == 1
    assert countDatapoints1[9].value == 1
    assert countDatapoints1[10].value == 1
    assert countDatapoints1[11].value == 1
    assert countDatapoints1[12].value == null // Spare milliseconds of time

    when: "requesting interval datapoints using SUM"
    def sumDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime30, queryTimeNow, "10 minutes", AssetDatapointIntervalQuery.Formula.SUM, true)
    )

    then: "SUM datapoints should be correct"
    assert sumDatapoints1.size() == 4 // Because 30/10=3, plus an extra datapoint for the spare milliseconds of time.
    assert sumDatapoints1[0].timestamp == time30.toEpochMilli()
    assert sumDatapoints1[1].timestamp == time30.plus(10, ChronoUnit.MINUTES).toEpochMilli()
    assert sumDatapoints1[2].timestamp == time30.plus(20, ChronoUnit.MINUTES).toEpochMilli()
    assert sumDatapoints1[3].timestamp == timeNow.toEpochMilli()
    assert sumDatapoints1[0].value == (10 + 15)
    assert sumDatapoints1[1].value == (90 + 20)
    assert sumDatapoints1[2].value == (25 + 30)
    assert sumDatapoints1[3].value == null // Spare milliseconds of time

    when: "requesting interval datapoints using MODE"
    def modeDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime30, queryTimeNow, "5 minutes", AssetDatapointIntervalQuery.Formula.MODE, true)
    )

    then: "MODE datapoints should be correct"
    assert modeDatapoints1.size() == 7 // Include the empty bucket at the inclusive end timestamp.
    assert modeDatapoints1[0].timestamp == time30.toEpochMilli()
    assert modeDatapoints1[1].timestamp == time30.plus(5, ChronoUnit.MINUTES).toEpochMilli()
    assert modeDatapoints1[2].timestamp == time30.plus(10, ChronoUnit.MINUTES).toEpochMilli()
    assert modeDatapoints1[3].timestamp == time30.plus(15, ChronoUnit.MINUTES).toEpochMilli()
    assert modeDatapoints1[4].timestamp == time30.plus(20, ChronoUnit.MINUTES).toEpochMilli()
    assert modeDatapoints1[5].timestamp == time30.plus(25, ChronoUnit.MINUTES).toEpochMilli()
    assert modeDatapoints1[6].timestamp == timeNow.toEpochMilli()
    assert modeDatapoints1[0].value == 10
    assert modeDatapoints1[1].value == 15
    assert modeDatapoints1[2].value == 90
    assert modeDatapoints1[3].value == 20
    assert modeDatapoints1[4].value == 25
    assert modeDatapoints1[5].value == 30
    assert modeDatapoints1[6].value == null

    when: "requesting interval datapoints using MEDIAN"
    def medianDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime30, queryTimeNow, "15 minutes", AssetDatapointIntervalQuery.Formula.MEDIAN, true)
    )

    then: "MEDIAN datapoints should be correct"
    assert medianDatapoints1.size() == 3 // Because 30/15=2, plus an extra datapoint for the spare milliseconds of time.
    assert medianDatapoints1[0].timestamp == time30.toEpochMilli()
    assert medianDatapoints1[1].timestamp == time30.plus(15, ChronoUnit.MINUTES).toEpochMilli()
    assert medianDatapoints1[2].timestamp == timeNow.toEpochMilli()
    assert medianDatapoints1[0].value == 15
    assert medianDatapoints1[1].value == 25
    assert medianDatapoints1[2].value == null // Spare milliseconds of time

    when: "adding an additional datapoint of 90"
    assetDatapointService.upsertValue(asset.getId(), attributeName, 90d, dateTime.minus(1, ChronoUnit.MINUTES))

    and: "requesting interval datapoints using MODE with a full interval"
    def modeDatapoints2 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointIntervalQuery(queryTime30, queryTimeNow, "60 minutes", AssetDatapointIntervalQuery.Formula.MODE, true)
    )

    then: "MODE datapoints should return 90, because it is most common within the interval"
    assert modeDatapoints2.size() == 2 // Include the empty bucket at the inclusive end timestamp.
    assert modeDatapoints2[0].timestamp == time60.toEpochMilli()
    assert modeDatapoints2[0].value == 90
    assert modeDatapoints2[1].timestamp == timeNow.toEpochMilli()
    assert modeDatapoints2[1].value == null
  }

  def "All query should return the correct data when the maximum is respected"() {

    given: "expected conditions"
    def conditions = new PollingConditions(timeout: 10, delay: 0.2)

    and: "the container is started"
    def container = startContainer(defaultConfig(), defaultServices())
    def keycloakTestSetup = container.getService(SetupService.class).getTaskOfType(KeycloakTestSetup.class)
    def assetStorageService = container.getService(AssetStorageService.class)
    def assetDatapointService = container.getService(AssetDatapointService.class)
    assetDatapointService.maxAmountOfQueryPoints = 1000

    when: "requesting the first light asset in City realm"
    def asset = assetStorageService.find(
            new AssetQuery()
            .types(LightAsset.class)
            .realm(new RealmPredicate(keycloakTestSetup.realmCity.name))
            .names("Light 1")
    )

    then: "asset should exist and be correct"
    def assetName = "Light 1"
    def attributeName = "brightness"
    def dateTime = LocalDateTime.now()
    assert asset != null // light 1, 2 and 3
    assert asset.name == assetName
    assert asset.attributes.has(attributeName)

    and: "no datapoints should exist"
    conditions.eventually {
      def datapoints = assetDatapointService.getDatapoints(new AttributeRef(asset.getId(), attributeName))
      assert datapoints.isEmpty()
    }


    /* ------------------------- */

    when: "datapoints are added to the asset"
    assetDatapointService.upsertValues(asset.getId(), attributeName,
            [
              new ValueDatapoint<>(dateTime.minusMinutes(25).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 50d),
              new ValueDatapoint<>(dateTime.minusMinutes(20).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 40d),
              new ValueDatapoint<>(dateTime.minusMinutes(15).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 30d),
              new ValueDatapoint<>(dateTime.minusMinutes(10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 20d),
              new ValueDatapoint<>(dateTime.minusMinutes(5).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 10d),
            ]
    )

    then: "datapoints should exist"
    def allDatapoints = new ArrayList<ValueDatapoint>()
    conditions.eventually {
      allDatapoints = assetDatapointService.getDatapoints(new AttributeRef(asset.getId(), attributeName))
      assert allDatapoints.size() == 5
    }

    /* ------------------------- */

    and: "requesting 5 datapoints should return 5 values"
    def allDatapoints1 = assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointAllQuery(dateTime.minusMinutes(30), dateTime)
    )
    assert allDatapoints1.size() == 5

    /* ------------------------- */

    when: "the OR_DATA_POINTS_QUERY_LIMIT environment variable is updated to 2"
    assetDatapointService.maxAmountOfQueryPoints = 2

    and: "the same datapoints are being requested"
    assetDatapointService.queryDatapoints(
            asset.getId(),
            asset.getAttribute(attributeName).orElseThrow({
              new RuntimeException("Missing attribute")
            }),
            new AssetDatapointAllQuery(dateTime.minusMinutes(30), dateTime)
    )

    then: "no points should be returned"
    thrown(DatapointQueryTooLargeException)

    cleanup: "Remove the limit on datapoint querying"
    assetDatapointService.maxAmountOfQueryPoints = 0
  }
}
