#!/bin/bash
set -eo pipefail

# Retrieve largest uncompressed chunk size from query-exporter service
CHUNK_SIZE=$(curl -s http://localhost:8406/metrics | grep '^pg_datapoint_largest_uncompressed_chunk' | awk '{printf "%.0f\n", $NF}')
if [ -z "$CHUNK_SIZE" ]; then
  echo 'Could not retrieve chunk size from query-exporter service, using defaults from Docker Compose'
  exit 1
fi

# Calculate minimum RAM size and round up to the next whole GB (1.2 GB becomes 2 GB)
MINIMUM_RAM_SIZE="$((((4 * CHUNK_SIZE + 1024 - 1) / 1024) * 1024))MB"

# Set shared buffers to 25% of minimum RAM size.
export OR_DB_SHARED_BUFFERS="$((MINIMUM_RAM_SIZE / 4))MB"
# Calculate SHM size by adding 20% to the shared buffers and round up to the next whole GB (1.2 GB becomes 2 GB)
export OR_DB_SHM="$((((OR_DB_SHARED_BUFFERS * 120 / 100 + 1024 - 1) / 1024) * 1024))MB"
# Set maintenance work mem to 12.5% of minimum RAM size and round up to the next multiple of 128 MB.
export OR_DB_MAINTENANCE_WORK_MEM="$((((MINIMUM_RAM_SIZE / 8 + 128 - 1) / 128) * 128))MB"
# Set worker mem to 1% of minimum RAM size and round up to the next multiple of 16 MB.
export OR_DB_WORK_MEM="$((((MINIMUM_RAM_SIZE / 100 + 16 - 1) / 16) * 16))MB"
# Calculate effective cache size
export OR_DB_CACHE_SIZE="$((MINIMUM_RAM_SIZE - OR_DB_SHARED_BUFFERS))MB"

echo 'Chunk size:' "$CHUNK_SIZE MB"
echo 'Minimum RAM size:' "$MINIMUM_RAM_SIZE MB"
echo 'Shared buffers:' "$OR_DB_SHARED_BUFFERS MB"
echo 'SHM Size:' "$OR_DB_SHM MB"
echo 'Maintenance worker mem:' "$OR_DB_MAINTENANCE_WORK_MEM MB"
echo 'Worker mem:' "$OR_DB_WORK_MEM MB"
echo 'Effective cache size:' "$OR_DB_CACHE_SIZE MB"
