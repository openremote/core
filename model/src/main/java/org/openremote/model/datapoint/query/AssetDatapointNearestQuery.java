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
import java.util.HashMap;
import org.openremote.model.attribute.AttributeRef;

/**
 * This `AssetDatapointQuery` retrieves the value of a datapoint associated with a specific
 * `AttributeRef` at a specified timestamp. It is designed to find the closest datapoint that
 * precedes the requested timestamp.
 */
@Schema(
    description =
        "Returns at most one numeric datapoint: the closest value at or before the requested "
            + "time. Use `fromTimestamp` as a Unix-seconds timestamp at the HTTP API boundary. `toTimestamp`, "
            + "`fromTime`, and `toTime` are ignored. The equality boundary is inclusive. "
            + "If the nearest datapoint has a null value, its timestamp and null value are returned.")
public class AssetDatapointNearestQuery extends AssetDatapointQuery {

  public AssetDatapointNearestQuery() {}

  public AssetDatapointNearestQuery(long timestamp) {
    // Convert to seconds from millis
    this.fromTimestamp = timestamp / 1000;
  }

  @Override
  public String getSQLQuery(String tableName, Class<?> attributeType) throws IllegalStateException {
    return "SELECT timestamp as X, cast("
        + NULLABLE_VALUE_SQL
        + " as numeric) as Y "
        + "    FROM "
        + tableName
        + " "
        + "    WHERE entity_id = ? AND attribute_name = ? "
        + "      AND timestamp <= to_timestamp(?) "
        + "    ORDER BY timestamp DESC "
        + "    LIMIT 1";
  }

  @Override
  public HashMap<Integer, Object> getSQLParameters(AttributeRef attributeRef) {
    HashMap<Integer, Object> parameters = new HashMap<>();
    // nearest_row parameters
    parameters.put(1, attributeRef.getId());
    parameters.put(2, attributeRef.getName());
    parameters.put(3, this.fromTimestamp);

    return parameters;
  }
}
