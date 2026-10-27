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
package org.openremote.model.datapoint.query;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import org.openremote.model.attribute.AttributeRef;

@Schema(
    description =
        "Downsamples numeric or boolean datapoints from the inclusive range with the "
            + "Largest-Triangle-Three-Buckets (LTTB) algorithm while preserving the series' visual shape. Use this "
            + "to reduce a large series for charts. Results are chronological; boolean values are processed as "
            + "1 for true and 0 for false. Null-valued observations are omitted before downsampling, so their "
            + "timestamps are not returned as gap markers. A range with no available values returns no points. "
            + "Other attribute value types are rejected.")
public final class AssetDatapointLTTBQuery extends AssetDatapointQuery {

  @Schema(
      description =
          "Requested maximum number of representative datapoints after downsampling. Fewer "
              + "points are returned when the range contains fewer available datapoints.",
      example = "500",
      requiredMode = Schema.RequiredMode.REQUIRED)
  public int amountOfPoints;

  public AssetDatapointLTTBQuery() {}

  public AssetDatapointLTTBQuery(long fromTimestamp, long toTimestamp, int amountOfPoints) {
    this.fromTimestamp = fromTimestamp;
    this.toTimestamp = toTimestamp;
    this.amountOfPoints = amountOfPoints;
  }

  public AssetDatapointLTTBQuery(LocalDateTime fromTime, LocalDateTime toTime, int amountOfPoints) {
    this.fromTime = fromTime;
    this.toTime = toTime;
    this.amountOfPoints = amountOfPoints;
  }

  @Override
  public String getSQLQuery(String tableName, Class<?> attributeType) throws IllegalStateException {
    boolean isNumber = Number.class.isAssignableFrom(attributeType);
    boolean isBoolean = Boolean.class.isAssignableFrom(attributeType);
    if (isNumber) {
      return "select * from public.unnest((select public.lttb(cast(timestamp as timestamptz), cast("
          + NULLABLE_VALUE_SQL
          + " as double precision), ?) from "
          + tableName
          + " where ENTITY_ID = ? and ATTRIBUTE_NAME = ? and TIMESTAMP >= ? and TIMESTAMP <= ?))";
    } else if (isBoolean) {
      return "select * from public.unnest((select public.lttb(cast(timestamp as timestamptz), ("
          + BOOLEAN_AS_NUMBER_SQL
          + "), ?) from "
          + tableName
          + " where ENTITY_ID = ? and ATTRIBUTE_NAME = ? and TIMESTAMP >= ? and TIMESTAMP <= ?))";
    } else {
      throw new IllegalStateException(
          "Query of type LTTB requires either a number or a boolean attribute.");
    }
  }

  @Override
  public HashMap<Integer, Object> getSQLParameters(AttributeRef attributeRef) {
    LocalDateTime fromTimestamp =
        (this.fromTime != null)
            ? this.fromTime
            : Instant.ofEpochMilli(this.fromTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    LocalDateTime toTimestamp =
        (this.toTime != null)
            ? this.toTime
            : Instant.ofEpochMilli(this.toTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    HashMap<Integer, Object> parameters = new HashMap<>();
    parameters.put(1, this.amountOfPoints);
    parameters.put(2, attributeRef.getId());
    parameters.put(3, attributeRef.getName());
    parameters.put(4, fromTimestamp);
    parameters.put(5, toTimestamp);
    return parameters;
  }
}
