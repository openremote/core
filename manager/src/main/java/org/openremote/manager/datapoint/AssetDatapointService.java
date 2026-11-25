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
package org.openremote.manager.datapoint;

import static org.openremote.model.syslog.SyslogCategory.DATA;
import static org.openremote.model.util.MapAccess.getInteger;
import static org.openremote.model.value.MetaItemType.STORE_DATA_POINTS;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.openremote.agent.protocol.ProtocolDatapointService;
import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingException;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.asset.OutdatedAttributeEvent;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.security.ManagerIdentityService;
import org.openremote.manager.web.ManagerWebService;
import org.openremote.model.Container;
import org.openremote.model.asset.Asset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeWriteFailure;
import org.openremote.model.datapoint.AssetDatapoint;
import org.openremote.model.datapoint.DatapointExportFormat;
import org.openremote.model.datapoint.DatapointQueryTooLargeException;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.value.MetaHolder;
import org.openremote.model.value.MetaItemType;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

/**
 * Store and retrieve datapoints for asset attributes and periodically purge data points using
 * TimescaleDB's {@code drop_chunks()} with week-based retention. Retention defaults to infinite.
 *
 * <h2>Storage layout</h2>
 *
 * The {@code asset_datapoint} table is a TimescaleDB hypertable split into chunks by time. The
 * chunk currently being written is kept in the rowstore, where the rows of every series are
 * interleaved in arrival order. A columnstore policy converts each chunk 1 day after it ends; in
 * the columnstore, rows are segmented by {@code entity_id} and {@code attribute_name}, so reading a
 * single series only touches that series' batches. In the rowstore, reading a single series from a
 * chunk that is not cached reads most of that chunk from disk.
 *
 * <h2>Choosing a chunk interval</h2>
 *
 * The chunk interval defaults to 7 days and can be changed with {@link
 * #OR_DATA_POINTS_CHUNK_INTERVAL_DAYS}. The new interval only applies to chunks created after the
 * change; existing chunks keep their range. Guidelines:
 *
 * <ul>
 *   <li>The chunk being written, including its indexes, should fit in about 25% of the database
 *       host's memory (roughly {@code shared_buffers}), so writes and queries for recent data are
 *       served from memory. A chunk that is larger than this makes queries for recent data read
 *       from disk. As an example, a system writing 1.7 GB of datapoints a day should use 1 day
 *       chunks unless the database has well over 12 GB of memory for {@code shared_buffers}.
 *   <li>Every query that filters relative to the current time (as chart and export queries do) pays
 *       a planning cost for every chunk in the table, and a query over a long range takes a lock
 *       per chunk it reads (see {@code max_locks_per_transaction}). Smaller chunks mean more
 *       chunks, so only reduce the interval when the size guideline above requires it, and combine
 *       small chunks with a retention period ({@link #OR_DATA_POINTS_MAX_AGE_WEEKS}).
 *   <li>Small chunks on a low volume system compress less well, as each series only has a few rows
 *       per chunk to put into a columnstore batch.
 * </ul>
 *
 * The query-exporter config in {@code deployment/query-exporter} reports the projected size of the
 * current chunk relative to {@code shared_buffers} ({@code pg_datapoint_chunk_memory_ratio}, over 1
 * means the interval should be reduced), the estimated write rate per day and the chunk count.
 */
public class AssetDatapointService extends AbstractDatapointService<AssetDatapoint>
    implements ProtocolDatapointService {

  public static final String OR_DATA_POINTS_MAX_AGE_WEEKS = "OR_DATA_POINTS_MAX_AGE_WEEKS";

  /**
   * Chunk interval in days for the {@code asset_datapoint} hypertable; applied on startup when it
   * differs from the current interval and only affects chunks created afterwards. When not set the
   * interval in the database is left as is (7 days unless previously changed). See the class
   * documentation for how to choose a value.
   */
  public static final String OR_DATA_POINTS_CHUNK_INTERVAL_DAYS =
      "OR_DATA_POINTS_CHUNK_INTERVAL_DAYS";

  /**
   * @deprecated Use {@link #OR_DATA_POINTS_MAX_AGE_WEEKS} instead. If set, value is converted to
   *     weeks (rounded up).
   */
  @Deprecated
  public static final String OR_DATA_POINTS_MAX_AGE_DAYS = "OR_DATA_POINTS_MAX_AGE_DAYS";

  public static final String OR_DATA_POINTS_EXPORT_LIMIT = "OR_DATA_POINTS_EXPORT_LIMIT";
  public static final int OR_DATA_POINTS_EXPORT_LIMIT_DEFAULT = 1000000;
  private static final Logger LOG = Logger.getLogger(AssetDatapointService.class.getName());
  private static final Logger DATA_EXPORT_LOG =
      SyslogCategory.getLogger(DATA, AssetDatapointResourceImpl.class);
  protected int maxDatapointAgeWeeks = -1;
  protected int chunkIntervalDays = -1;
  protected int datapointExportLimit;

  @Override
  public void init(Container container) throws Exception {
    super.init(container);

    container
        .getService(ManagerWebService.class)
        .addApiSingleton(
            new AssetDatapointResourceImpl(
                container.getService(TimerService.class),
                container.getService(ManagerIdentityService.class),
                container.getService(AssetStorageService.class),
                this));

    if (container.getConfig().containsKey(OR_DATA_POINTS_MAX_AGE_WEEKS)) {
      maxDatapointAgeWeeks = getInteger(container.getConfig(), OR_DATA_POINTS_MAX_AGE_WEEKS, -1);
    } else if (container.getConfig().containsKey(OR_DATA_POINTS_MAX_AGE_DAYS)) {
      int days = getInteger(container.getConfig(), OR_DATA_POINTS_MAX_AGE_DAYS, -1);
      maxDatapointAgeWeeks = days > 0 ? (int) Math.ceil(days / 7.0) : days;
      LOG.warning(
          OR_DATA_POINTS_MAX_AGE_DAYS
              + " is deprecated, use "
              + OR_DATA_POINTS_MAX_AGE_WEEKS
              + " instead. Converted "
              + days
              + " days to "
              + maxDatapointAgeWeeks
              + " weeks.");
    } else {
      maxDatapointAgeWeeks = -1;
    }

    if (maxDatapointAgeWeeks <= 0) {
      LOG.info("Data point purge disabled");
    } else {
      LOG.log(Level.INFO, "Data point purge retention = " + maxDatapointAgeWeeks + " weeks");
    }

    chunkIntervalDays = getInteger(container.getConfig(), OR_DATA_POINTS_CHUNK_INTERVAL_DAYS, -1);
    if (container.getConfig().containsKey(OR_DATA_POINTS_CHUNK_INTERVAL_DAYS)
        && chunkIntervalDays < 1) {
      LOG.warning(
          OR_DATA_POINTS_CHUNK_INTERVAL_DAYS
              + " must be a whole number of days of at least 1, ignoring it");
      chunkIntervalDays = -1;
    }

    datapointExportLimit =
        getInteger(
            container.getConfig(),
            OR_DATA_POINTS_EXPORT_LIMIT,
            OR_DATA_POINTS_EXPORT_LIMIT_DEFAULT);

    if (datapointExportLimit <= 0) {
      LOG.warning(
          OR_DATA_POINTS_EXPORT_LIMIT
              + " value is not a valid value so the export data points won't be limited");
    } else {
      LOG.log(Level.INFO, "Data point export limit = " + datapointExportLimit);
    }
  }

  @Override
  public void start(Container container) throws Exception {
    applyChunkInterval();

    if (maxDatapointAgeWeeks > 0) {
      // Run daily so chunks are dropped soon after they pass the retention period, whatever the
      // chunk interval; drop_chunks only touches chunks that are entirely older than the cutoff
      dataPointsPurgeScheduledFuture =
          scheduledExecutorService.scheduleAtFixedRate(
              this::purgeDataPoints,
              getFirstPurgeMillis(timerService.getNow()),
              Duration.ofDays(1).toMillis(),
              TimeUnit.MILLISECONDS);
    }

    ClientEventService clientEventService = container.getService(ClientEventService.class);
    clientEventService.addSubscription(AttributeEvent.class, null, this::onAttributeEvent);
    clientEventService.addSubscription(
        OutdatedAttributeEvent.class, null, this::onOutdatedAttributeEvent);
  }

  public static boolean attributeIsStoreDatapoint(MetaHolder attributeInfo) {
    return attributeInfo
        .getMetaValue(STORE_DATA_POINTS)
        .orElse(attributeInfo.hasMeta(MetaItemType.AGENT_LINK));
  }

  public void onAttributeEvent(AttributeEvent attributeEvent) {
    if (attributeIsStoreDatapoint(attributeEvent)
        && attributeEvent.getValue().isPresent()) { // Don't store datapoints with null value
      try {
        upsertValue(
            attributeEvent.getId(),
            attributeEvent.getName(),
            attributeEvent.getValue().orElse(null),
            Instant.ofEpochMilli(attributeEvent.getTimestamp())
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime());
      } catch (Exception e) {
        throw new AssetProcessingException(
            AttributeWriteFailure.STATE_STORAGE_FAILED,
            "Failed to insert or update asset data point for attribute: " + attributeEvent,
            e);
      }
    }
  }

  public void onOutdatedAttributeEvent(OutdatedAttributeEvent outdatedAttributeEvent) {
    // Store the outdated event for historical analysis
    onAttributeEvent(outdatedAttributeEvent.getEvent());
  }

  @Override
  protected Class<AssetDatapoint> getDatapointClass() {
    return AssetDatapoint.class;
  }

  @Override
  protected String getDatapointTableName() {
    return AssetDatapoint.TABLE_NAME;
  }

  @Override
  protected Logger getLogger() {
    return LOG;
  }

  protected void purgeDataPoints() {
    LOG.info("Running data points purge task");

    try {
      persistenceService.doTransaction(
          em -> {
            // Get the schema-qualified hypertable name
            @SuppressWarnings("unchecked")
            List<String> hypertables =
                em.createNativeQuery(
                        "SELECT hypertable_schema || '.' || hypertable_name "
                            + "FROM timescaledb_information.hypertables "
                            + "WHERE hypertable_name = 'asset_datapoint'",
                        String.class)
                    .getResultList();

            if (hypertables.isEmpty()) {
              LOG.warning(
                  "asset_datapoint is not a TimescaleDB hypertable, skipping purge. "
                      + "TimescaleDB with hypercore is required for data point storage.");
              return;
            }

            String qualifiedTableName = hypertables.getFirst();

            // Find the maximum custom retention across all attributes with DATA_POINTS_MAX_AGE_DAYS
            // meta
            int effectiveRetentionWeeks = maxDatapointAgeWeeks;
            Number maxCustomDays =
                (Number)
                    em.createNativeQuery(
                            "SELECT MAX((attr_val->'meta'->>'dataPointsMaxAgeDays')::integer) "
                                + "FROM asset, jsonb_each(asset.attributes) AS a(attr_key, attr_val) "
                                + "WHERE jsonb_exists(attr_val->'meta', 'dataPointsMaxAgeDays')")
                        .getSingleResult();

            if (maxCustomDays != null) {
              int customRetentionWeeks = (int) Math.ceil(maxCustomDays.intValue() / 7.0);
              effectiveRetentionWeeks = Math.max(effectiveRetentionWeeks, customRetentionWeeks);
              if (customRetentionWeeks > maxDatapointAgeWeeks) {
                LOG.info(
                    "Custom attribute retention ("
                        + maxCustomDays.intValue()
                        + " days / "
                        + customRetentionWeeks
                        + " weeks) exceeds system default ("
                        + maxDatapointAgeWeeks
                        + " weeks), using "
                        + effectiveRetentionWeeks
                        + " weeks");
              }
            }

            // Compute cutoff from effective retention
            Instant cutoff =
                timerService.getNow().minus(Duration.ofDays((long) effectiveRetentionWeeks * 7));
            Timestamp cutoffTimestamp = Timestamp.from(cutoff);

            LOG.info(
                "Dropping chunks older than "
                    + cutoffTimestamp
                    + " ("
                    + effectiveRetentionWeeks
                    + " weeks retention)");

            Number dropped =
                (Number)
                    em.createNativeQuery(
                            "SELECT count(*) FROM public.drop_chunks(CAST(:hypertable AS regclass), older_than => CAST(:cutoff AS timestamp))")
                        .setParameter("hypertable", qualifiedTableName)
                        .setParameter("cutoff", cutoffTimestamp)
                        .getSingleResult();

            LOG.info("Successfully purged data points using drop_chunks, drop count = " + dropped);
          });
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to run data points purge", e);
    }
  }

  /**
   * Sets the chunk interval of the hypertable to {@link #OR_DATA_POINTS_CHUNK_INTERVAL_DAYS} when
   * configured and different from the current interval. Existing chunks, including the one
   * currently being written, keep their range; the first chunk created afterwards uses the new
   * interval.
   */
  protected void applyChunkInterval() {
    if (chunkIntervalDays <= 0) {
      return;
    }

    try {
      persistenceService.doTransaction(
          em ->
              em.unwrap(Session.class)
                  .doWork(
                      connection -> {
                        Long currentSeconds = null;
                        try (PreparedStatement st =
                            connection.prepareStatement(
                                "SELECT EXTRACT(EPOCH FROM time_interval)::bigint "
                                    + "FROM timescaledb_information.dimensions "
                                    + "WHERE hypertable_schema = current_schema() "
                                    + "AND hypertable_name = ? AND column_name = 'timestamp'")) {
                          st.setString(1, AssetDatapoint.TABLE_NAME);
                          try (var rs = st.executeQuery()) {
                            if (rs.next()) {
                              currentSeconds = rs.getLong(1);
                            }
                          }
                        }

                        if (currentSeconds == null) {
                          LOG.warning(
                              AssetDatapoint.TABLE_NAME
                                  + " is not a TimescaleDB hypertable, ignoring "
                                  + OR_DATA_POINTS_CHUNK_INTERVAL_DAYS);
                          return;
                        }

                        long requiredSeconds = Duration.ofDays(chunkIntervalDays).toSeconds();
                        if (currentSeconds == requiredSeconds) {
                          LOG.info("Data point chunk interval = " + formatDays(requiredSeconds));
                          return;
                        }

                        try (PreparedStatement st =
                            connection.prepareStatement(
                                "SELECT public.set_chunk_time_interval("
                                    + "format('%I.%I', current_schema(), ?)::regclass, "
                                    + "make_interval(days => ?))")) {
                          st.setString(1, AssetDatapoint.TABLE_NAME);
                          st.setInt(2, chunkIntervalDays);
                          st.execute();
                        }

                        LOG.info(
                            "Data point chunk interval changed from "
                                + formatDays(currentSeconds)
                                + " to "
                                + formatDays(requiredSeconds)
                                + "; this applies to chunks created from now on");
                      }));
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Failed to set data point chunk interval", e);
    }
  }

  protected static String formatDays(long seconds) {
    return String.format(Locale.ROOT, "%.2f days", seconds / 86400d);
  }

  /**
   * Streams datapoints as CSV using PostgreSQL COPY TO STDOUT. Backwards compatible overload with
   * default format.
   */
  public PipedInputStream exportDatapoints(
      AttributeRef[] attributeRefs, long fromTimestamp, long toTimestamp) throws IOException {
    return exportDatapoints(attributeRefs, fromTimestamp, toTimestamp, DatapointExportFormat.CSV);
  }

  /**
   * Streams datapoints as CSV using PostgreSQL COPY TO STDOUT through an in-memory pipe. No shared
   * filesystem or intermediate export file is required.
   */
  public PipedInputStream exportDatapoints(
      AttributeRef[] attributeRefs,
      long fromTimestamp,
      long toTimestamp,
      DatapointExportFormat format)
      throws IOException {
    try {
      ExportQuery exportQuery = getSelectExportQuery(attributeRefs, fromTimestamp, toTimestamp);

      // Verify the query is 'legal' and can be executed
      if (canQueryDatapoints(exportQuery.query, exportQuery.parameters, datapointExportLimit)) {
        return doExportDatapoints(attributeRefs, fromTimestamp, toTimestamp, format);
      }
      throw new RuntimeException("Could not export datapoints.");

    } catch (DatapointQueryTooLargeException dqex) {
      String msg =
          "Could not export data points. It exceeds the data limit of "
              + datapointExportLimit
              + " data points.";
      getLogger().log(Level.WARNING, msg, dqex);
      throw dqex;
    }
  }

  protected PipedInputStream doExportDatapoints(
      AttributeRef[] attributeRefs,
      long fromTimestamp,
      long toTimestamp,
      DatapointExportFormat format)
      throws IOException {
    // Increase buffer size (default is only 1 KB)
    PipedInputStream in = new PipedInputStream(1024 * 1024 * 4); // 4 MB
    PipedOutputStream out = new PipedOutputStream(in);

    // Execute query asynchronously
    scheduledExecutorService.schedule(
        () -> {
          boolean success = false;
          try {
            persistenceService.doTransaction(
                em ->
                    em.unwrap(Session.class)
                        .doWork(
                            connection -> {
                              String tempTableName = "tmp_export_attributes";
                              String tempBoundsTableName = "tmp_export_bounds";
                              List<ExportColumn> exportColumns = getExportColumns(attributeRefs);

                              // Create temp tables and insert parameters safely via
                              // PreparedStatement
                              // to prevent SQL injection (instead of string-interpolating IDs into
                              // the query).
                              try (Statement statement = connection.createStatement()) {
                                // We have 2 different tables because for attribute_name, we have
                                // one entry per exported attribute
                                statement.execute(
                                    "create temp table "
                                        + tempTableName
                                        + " (ordinal integer, entity_id text, attribute_name text, column_key text) on commit drop");
                                // but for timestamps, we always have only one row, as the export
                                // period is the same for all attributes
                                statement.execute(
                                    "create temp table "
                                        + tempBoundsTableName
                                        + " (from_timestamp timestamp, to_timestamp timestamp) on commit drop");
                              }

                              try (PreparedStatement insertStatement =
                                  connection.prepareStatement(
                                      "insert into "
                                          + tempTableName
                                          + " (ordinal, entity_id, attribute_name, column_key) values (?, ?, ?, ?)")) {
                                for (ExportColumn exportColumn : exportColumns) {
                                  AttributeRef attributeRef = exportColumn.attributeRef();
                                  insertStatement.setInt(1, exportColumn.ordinal());
                                  insertStatement.setString(
                                      2, validateAssetId(attributeRef.getId()));
                                  insertStatement.setString(3, attributeRef.getName());
                                  insertStatement.setString(4, exportColumn.columnKey());
                                  insertStatement.addBatch();
                                }
                                insertStatement.executeBatch();
                              }

                              try (PreparedStatement boundsStatement =
                                  connection.prepareStatement(
                                      "insert into "
                                          + tempBoundsTableName
                                          + " (from_timestamp, to_timestamp) values (?, ?)")) {
                                boundsStatement.setObject(
                                    1, toDatapointLocalDateTime(fromTimestamp));
                                boundsStatement.setObject(2, toDatapointLocalDateTime(toTimestamp));
                                boundsStatement.executeUpdate();
                              }

                              // Build the COPY ... TO STDOUT query based on format
                              String copyQuery =
                                  buildCopyToStdoutQuery(
                                      tempTableName, tempBoundsTableName, format, exportColumns);

                              try {
                                PGConnection pgConnection = connection.unwrap(PGConnection.class);
                                CopyManager copyManager = pgConnection.getCopyAPI();
                                if (isCrosstabFormat(format)) {
                                  out.write(
                                      buildCrosstabCsvHeader(exportColumns)
                                          .getBytes(StandardCharsets.UTF_8));
                                }
                                copyManager.copyOut(copyQuery, out);
                                out.flush();
                              } catch (IOException e) {
                                throw new RuntimeException("Failed to export datapoints", e);
                              }
                            }));
            success = true;
          } catch (Exception e) {
            DATA_EXPORT_LOG.log(Level.SEVERE, "Datapoint export failed", e);
          } finally {
            try {
              out.close();
            } catch (IOException e) {
              DATA_EXPORT_LOG.log(Level.WARNING, "Failed to close output stream", e);
            }
            if (!success) {
              try {
                in.close();
              } catch (IOException e) {
                DATA_EXPORT_LOG.log(Level.SEVERE, "Failed to close piped input stream", e);
              }
            }
          }
        },
        0,
        TimeUnit.MILLISECONDS);

    return in;
  }

  /**
   * Builds a COPY ... TO STDOUT query for streaming CSV data based on the export format. Uses a
   * temporary table for safe attribute filtering (SQL injection prevention).
   */
  private String buildCopyToStdoutQuery(
      String tempTableName,
      String tempBoundsTableName,
      DatapointExportFormat format,
      List<ExportColumn> exportColumns) {
    final String TO_STDOUT_WITH_CSV_HEADER_FORMAT =
        ") TO STDOUT WITH (FORMAT CSV, HEADER, DELIMITER ',');";
    final String TO_STDOUT_WITH_CSV_FORMAT = ") TO STDOUT WITH (FORMAT CSV, DELIMITER ',');";

    if (isCrosstabFormat(format)) {
      String categoryQuery = "select column_key from " + tempTableName + " order by ordinal";

      // Column definitions for crosstab result use generated identifiers only.
      String attributeColumns =
          exportColumns.stream()
              .map(exportColumn -> exportColumn.columnKey() + " text")
              .collect(Collectors.joining(", "));

      // Inner SELECT query for crosstab (uses escaped quotes since it's inside a string argument)
      String innerQuery;
      if (format == DatapointExportFormat.CSV_CROSSTAB_MINUTE) {
        innerQuery =
            String.format(
                "select public.time_bucket(''1 minute'', ad.timestamp) as bucket_timestamp, "
                    + "t.column_key as category, "
                    + "CASE "
                    + "  WHEN jsonb_typeof((array_agg(ad.value))[1]) = ''number'' THEN "
                    + "    round(avg((ad.value#>>''{}'')::numeric) FILTER (WHERE jsonb_typeof(ad.value) = ''number''), 3)::text "
                    + "  ELSE (array_agg(ad.value ORDER BY ad.timestamp DESC) FILTER (WHERE jsonb_typeof(ad.value) != ''number''))[1]#>>''{}''"
                    + "END as value "
                    + "from asset_datapoint ad "
                    + "join %s t on ad.entity_id = t.entity_id and ad.attribute_name = t.attribute_name "
                    + "cross join %s b "
                    + "where ad.timestamp >= b.from_timestamp and ad.timestamp <= b.to_timestamp "
                    + "group by bucket_timestamp, category "
                    + "order by bucket_timestamp, category",
                tempTableName, tempBoundsTableName);
      } else {
        innerQuery =
            String.format(
                "select ad.timestamp, t.column_key as category, ad.value "
                    + "from asset_datapoint ad "
                    + "join %s t on ad.entity_id = t.entity_id and ad.attribute_name = t.attribute_name "
                    + "cross join %s b "
                    + "where ad.timestamp >= b.from_timestamp and ad.timestamp <= b.to_timestamp "
                    + "order by ad.timestamp, category",
                tempTableName, tempBoundsTableName);
      }

      return String.format(
          "copy (select * from crosstab('%s', '%s') as ct(timestamp timestamp, %s)%s",
          innerQuery, categoryQuery, attributeColumns, TO_STDOUT_WITH_CSV_FORMAT);
    } else {
      // Plain CSV format using temp table join
      String innerQuery =
          String.format(
              "select ad.timestamp, a.name, ad.attribute_name, value "
                  + "from asset_datapoint ad "
                  + "join asset a on ad.entity_id = a.id "
                  + "join %s t on ad.entity_id = t.entity_id and ad.attribute_name = t.attribute_name "
                  + "cross join %s b "
                  + "where ad.timestamp >= b.from_timestamp and ad.timestamp <= b.to_timestamp "
                  + "order by ad.timestamp desc",
              tempTableName, tempBoundsTableName);
      return "copy (" + innerQuery + TO_STDOUT_WITH_CSV_HEADER_FORMAT;
    }
  }

  /**
   * Function for building crosstab columns when format will make a separate column per attribute.
   * Uses display-label ordering to preserve existing crosstab output ordering, but keeps those
   * labels out of SQL syntax by assigning generated column keys.
   */
  private List<ExportColumn> getExportColumns(AttributeRef[] attributeRefs) {
    List<ExportColumn> sortedColumns =
        Arrays.stream(attributeRefs)
            .map(attr -> new ExportColumn(-1, attr, getDisplayHeader(attr), null))
            .sorted(
                Comparator.comparing(ExportColumn::displayHeader)
                    .thenComparing(exportColumn -> exportColumn.attributeRef().getId())
                    .thenComparing(exportColumn -> exportColumn.attributeRef().getName()))
            .collect(Collectors.toList());

    List<ExportColumn> exportColumns = new ArrayList<>(sortedColumns.size());
    for (int i = 0; i < sortedColumns.size(); i++) {
      ExportColumn exportColumn = sortedColumns.get(i);
      exportColumns.add(
          new ExportColumn(
              i, exportColumn.attributeRef(), exportColumn.displayHeader(), "col_" + i));
    }
    return exportColumns;
  }

  private String getDisplayHeader(AttributeRef attributeRef) {
    List<String> assetNames = assetStorageService.findNames(attributeRef.getId());
    String assetName = assetNames.isEmpty() ? "" : assetNames.get(0);
    return assetName + " : " + attributeRef.getName();
  }

  private static String buildCrosstabCsvHeader(List<ExportColumn> exportColumns) {
    List<String> headers = new ArrayList<>(exportColumns.size() + 1);
    headers.add("timestamp");
    exportColumns.stream().map(ExportColumn::displayHeader).forEach(headers::add);
    return headers.stream()
            .map(AssetDatapointService::escapeCsvField)
            .collect(Collectors.joining(","))
        + "\n";
  }

  private static String escapeCsvField(String field) {
    if (field == null) {
      return "";
    }
    if (field.indexOf(',') < 0
        && field.indexOf('"') < 0
        && field.indexOf('\n') < 0
        && field.indexOf('\r') < 0) {
      return field;
    }
    return "\"" + field.replace("\"", "\"\"") + "\"";
  }

  private static boolean isCrosstabFormat(DatapointExportFormat format) {
    return format == DatapointExportFormat.CSV_CROSSTAB
        || format == DatapointExportFormat.CSV_CROSSTAB_MINUTE;
  }

  /**
   * Function for building an SQL query that SELECTs the content for the data export. Currently, it
   * includes timestamp, asset name, attribute name, and the data point value. Be aware: this SQL
   * query does NOT contain any CSV-related statements.
   *
   * <p>It returns a structure containing the parametrized query as a string and the parameter
   * values.
   */
  protected ExportQuery getSelectExportQuery(
      AttributeRef[] attributeRefs, long fromTimestamp, long toTimestamp) {
    StringBuilder sb =
        new StringBuilder(
            "select ad.timestamp, a.name, ad.attribute_name, value from asset_datapoint ad, asset a where ad.entity_id = a.id and ad.timestamp >= ? and ad.timestamp <= ? and ");
    Map<Integer, Object> parameters = new HashMap<>();
    int paramIndex = 1;
    parameters.put(paramIndex++, toDatapointLocalDateTime(fromTimestamp));
    parameters.put(paramIndex++, toDatapointLocalDateTime(toTimestamp));

    sb.append("(ad.entity_id, ad.attribute_name) in (");
    for (int i = 0; i < attributeRefs.length; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append("(?, ?)");
      parameters.put(paramIndex++, validateAssetId(attributeRefs[i].getId()));
      parameters.put(paramIndex++, attributeRefs[i].getName());
    }
    sb.append(") ");
    sb.append("order by ad.timestamp desc");

    return new ExportQuery(sb.toString(), parameters);
  }

  protected record ExportQuery(String query, Map<Integer, Object> parameters) {}

  private record ExportColumn(
      int ordinal, AttributeRef attributeRef, String displayHeader, String columnKey) {}

  private static String validateAssetId(String assetId) {
    if (assetId == null || !Asset.matchesAssetIdPattern(assetId)) {
      throw new IllegalArgumentException("Invalid asset id");
    }
    return assetId;
  }

  private static LocalDateTime toDatapointLocalDateTime(long timestamp) {
    // Datapoints are stored in a timestamp-without-time-zone column using this same JVM-local
    // conversion.
    // Keep export bounds in that representation so PostgreSQL's session timezone does not affect
    // filtering.
    return Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime();
  }
}
