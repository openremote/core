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
package org.openremote.manager.datapoint

import org.openremote.container.timer.TimerService
import org.openremote.manager.asset.AssetProcessingService
import org.openremote.model.attribute.Attribute
import org.openremote.model.attribute.AttributeRef
import org.openremote.model.attribute.MetaItem
import org.openremote.model.datapoint.ValueDatapoint
import spock.lang.Specification

import java.util.concurrent.ScheduledExecutorService

import static org.openremote.model.value.MetaItemType.APPLY_PREDICTED_DATA_POINTS
import static org.openremote.model.value.MetaItemType.HAS_PREDICTED_DATA_POINTS
import static org.openremote.model.value.ValueType.INTEGER

class PredictionRetryTest extends Specification {

  def "an unavailable processor delays a prediction without losing the rest of the due batch"() {
    given:
    long now = 1000
    def first = new AttributeRef("asset", "first")
    def second = new AttributeRef("asset", "second")
    def attr = new Attribute<>("value", INTEGER, 0).setTimestamp(1L)
            .addOrReplaceMeta(new MetaItem<>(HAS_PREDICTED_DATA_POINTS, true),
            new MetaItem<>(APPLY_PREDICTED_DATA_POINTS, true))
    def predictions = [new ValueDatapoint<>(900L, 1)]
    def writes = []
    def service = Spy(ApplyPredictedDataPointsService)
    service.getAttribute(_) >> attr
    service.timerService = Stub(TimerService) { getCurrentTimeMillis() >> { now } }
    service.scheduledExecutorService = Stub(ScheduledExecutorService)
    service.assetPredictedDatapointService = Stub(AssetPredictedDatapointService) {
      getDatapoints(_) >> { predictions }
    }
    boolean unavailable = true
    service.assetProcessingService = Stub(AssetProcessingService) {
      sendAttributeEvent(_, _) >> { event, source ->
        if (unavailable && event.ref == first) {
          new AssetProcessingService().sendAttributeEvent(event, source)
        }
        writes << [event.ref, event.value.get(), event.timestamp]
      }
    }
    service.upsertScheduledEntry(first, 900L, 1)
    service.upsertScheduledEntry(second, 950L, 1)

    when:
    service.processDueEntries()

    then:
    writes == [[second, 1, 900L]]
    service.scheduledEntries.keySet() == [first] as Set
    service.scheduledEntries[first].timestamp() > now
    service.scheduledFuture != null

    when: "a newer prediction arrives before the retry"
    now = service.scheduledEntries[first].timestamp()
    predictions = [new ValueDatapoint<>(now - 1, 2)]
    unavailable = false
    service.processDueEntries()

    then:
    writes == [[second, 1, 900L], [first, 2, now - 1]]
    service.scheduledEntries.isEmpty()
    service.scheduledFuture == null
  }
}
