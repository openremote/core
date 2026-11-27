-- Convert asset_datapoint chunks to the columnstore 1 day after they end, rather than 7 days.
--
-- A chunk is only converted once its whole range is older than the policy's compress_after. With the 7 day chunk
-- interval and the previous compress_after of 7 days, two chunks (up to 14 days of data) were always left in the
-- rowstore. In the rowstore the rows of a single series are interleaved by arrival time with every other series, so
-- reading one series from a chunk that is not cached reads most of that chunk from disk. In the columnstore, rows are
-- segmented by entity_id and attribute_name and the same read touches only that series' batches.
--
-- With compress_after of 1 day only the chunk currently being written stays in the rowstore, and data that arrives
-- late still has 24 hours after its chunk ends before that chunk is converted. Data written to a converted chunk later
-- still works, it is just slower as the affected batches have to be decompressed.
--
-- alter_job keeps the existing job (and its job_id, which monitoring may reference) and its schedule. On an upgraded
-- system the next run of the policy converts the previous chunk, which can be several GB on a busy system.
SELECT public.alter_job(j.job_id, config => jsonb_set(j.config, '{compress_after}', '"1 day"'))
FROM timescaledb_information.jobs j
WHERE j.hypertable_schema = '${schemaName}'
  AND j.hypertable_name = 'asset_datapoint'
  AND j.proc_name = 'policy_compression';

-- Replace the query used by monitoring to give a typical historical datapoint query time.
--
-- The series is now chosen from the last hour rather than the last day: scanning a full day to pick a series costs more
-- than the query being timed and grows with the ingest rate, and the busiest series rarely changes between hours. Only
-- numeric values are used so the cast in the aggregation cannot fail.
CREATE OR REPLACE FUNCTION datapoint_metric_query()
RETURNS integer AS $$
DECLARE
    target_entity text;
    target_attr text;
    result_count integer;
BEGIN
    -- 1. Find the busiest numeric series in the last hour
    SELECT entity_id, attribute_name
    INTO target_entity, target_attr
    FROM asset_datapoint
    WHERE timestamp > now() - INTERVAL '1 hour'
      AND jsonb_typeof(value) = 'number'
    GROUP BY entity_id, attribute_name
    ORDER BY count(*) DESC
    LIMIT 1;

    IF target_entity IS NULL THEN
        RETURN 0;
    END IF;

    -- 2. Run an hourly aggregation of that series over a week that is in the columnstore. Dynamic SQL means the plan
    -- is built for the actual entity and attribute so the segment indexes are used.
    EXECUTE format('
        WITH aggregated_points AS (
            SELECT
                time_bucket(''1 hour'', timestamp) AS bucket,
                avg((value::text)::numeric) AS avg_value
            FROM asset_datapoint
            WHERE entity_id = %L
              AND attribute_name = %L
              AND timestamp BETWEEN now() - INTERVAL ''14 days'' AND now() - INTERVAL ''7 days''
              AND jsonb_typeof(value) = ''number''
            GROUP BY bucket
        )
        SELECT count(bucket) FROM aggregated_points;
    ', target_entity, target_attr) INTO result_count;

    -- 3. Return the row count to the exporter
    RETURN result_count;
END;
$$ LANGUAGE plpgsql;
