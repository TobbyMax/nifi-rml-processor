#!/usr/bin/env bash
# Run the NiFi RML processor benchmarks against a running NiFi instance.
#
# Pre-requisites:
#   1. NiFi 2.8.0 is running locally (see flows/scripts/run_nifi.sh).
#   2. NAR-bundle deployed to NiFi extensions directory.
#   3. Datasets are generated: python evaluation/scripts/generate_datasets.py
#
# Behaviour:
#   - Imports all flow blueprints from flows/
#   - Starts all process groups
#   - For every (configuration × dataset) combination copies the input
#     dataset into the watch directory, polls NiFi REST API for completion,
#     and records the provenance event duration into results.csv.
#
# Defaults
#   - 5 iterations per combination (first one is discarded as warm-up)
#   - Output directory: evaluation/results/
#   - NiFi base URL: https://localhost:8443/nifi-api

set -euo pipefail

REPO="$(cd "$(dirname "$0")"/../.. && pwd)"
RESULTS_DIR="$REPO/evaluation/results"
DATASETS_DIR="$REPO/evaluation/datasets"
FLOWS_DIR="$REPO/flows"
ITERATIONS=${ITERATIONS:-5}
BASE_URL=${BASE_URL:-"https://localhost:8443/nifi-api"}
TOKEN=${NIFI_TOKEN:-""}

# HTTP headers for NiFi API
HEADERS=()
if [[ -n "$TOKEN" ]]; then
    HEADERS=(-H "Authorization: Bearer $TOKEN")
fi

# Disable SSL verification warnings for curl
export CURL_OPTS="-k -s"

# Output directories used by PutFile processors
OUTPUT_DIRS=(
  "/tmp/nifi-rml-out"
  "/tmp/nifi-rml-out/morph-kgc"
  "/tmp/nifi-rml-out/rmlmapper"
)

#------------------------------------------------------------------------------
# Helper functions
#------------------------------------------------------------------------------

clean_output_dirs() {
    for dir in "${OUTPUT_DIRS[@]}"; do
        if [[ -d "$dir" ]]; then
            rm -rf "${dir:?}"/*
        fi
    done
}

nifi_api() {
    local method="$1"
    local path="$2"
    local data="${3:-}"
    if [[ -n "$data" ]]; then
        curl $CURL_OPTS -X "$method" "$BASE_URL$path" "${HEADERS[@]}" \
            -H "Content-Type: application/json" -d "$data"
    else
        curl $CURL_OPTS -X "$method" "$BASE_URL$path" "${HEADERS[@]}"
    fi
}

get_root_pg_id() {
    nifi_api GET "/process-groups/root" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])"
}

import_flows() {
    echo "Importing flow blueprints..."
    for flow_file in "$FLOWS_DIR"/flow_*.json; do
        echo "  Importing: $flow_file"
        python3 "$REPO/flows/scripts/import_blueprint.py" \
            --base-url "$BASE_URL" \
            --token "$TOKEN" \
            "$flow_file"
    done
    echo "Flow import complete."
}

start_process_groups() {
    echo "Starting all process groups..."
    local root_id
    root_id=$(get_root_pg_id)
    
    # Get all process groups under root
    local pgs
    pgs=$(nifi_api GET "/process-groups/${root_id}/process-groups")
    
    # Extract and start each process group
    echo "$pgs" | python3 -c "
import sys, json
data = json.load(sys.stdin)
for pg in data.get('processGroups', []):
    pg_id = pg.get('id')
    name = pg.get('component', {}).get('name', 'unknown')
    print(f'  Starting: {name} ({pg_id})')
    print(pg_id)
" | while read -r pg_id; do
        if [[ -n "$pg_id" ]]; then
            # Start the process group (PUT with state=RUNNING)
            nifi_api PUT "/process-groups/${pg_id}/run-status" \
                '{"state":"RUNNING"}' > /dev/null
        fi
    done
    echo "All process groups started."
}

#------------------------------------------------------------------------------
# Main
#------------------------------------------------------------------------------

CONFIGS=(
  "Baseline"
  "Native-RMLMAPPER"
  "Native-MORPH_KGC"
)

DATASETS=(
  "small_100.json"
  "medium_10k.json"
  "large_500k.json"
  "xlarge_2m.json"
  "invoices_50k.xml"
)

mkdir -p "$RESULTS_DIR" /tmp/nifi-rml-bench

# Import and start flows
#import_flows
#start_process_groups

# Small delay to ensure flows are fully started
sleep 2

RESULTS="$RESULTS_DIR/results.csv"
echo "config,dataset,iteration,duration_ms,triples,engine_selected" > "$RESULTS"

for cfg in "${CONFIGS[@]}"; do
  for ds in "${DATASETS[@]}"; do
    for i in $(seq 1 "$ITERATIONS"); do
      # Clean output and input directories to prevent conflicts
      clean_output_dirs
      rm -rf /tmp/nifi-rml-bench/*
      
      echo "[$cfg | $ds | iter=$i] copying input to NiFi watch directory"
      cp "$DATASETS_DIR/$ds" /tmp/nifi-rml-bench/

      echo "[$cfg | $ds | iter=$i] waiting for processing"
      python3 "$REPO/evaluation/scripts/parse_results.py" \
        --base-url "$BASE_URL" --token "$TOKEN" \
        --config "$cfg" --dataset "$ds" --iteration "$i" \
        >> "$RESULTS"
    done
  done
done

echo "Benchmark complete. Results in $RESULTS"
