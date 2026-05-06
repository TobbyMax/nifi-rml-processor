#!/usr/bin/env bash
# Run the NiFi RML processor benchmarks against a running NiFi instance.
#
# Pre-requisites:
#   1. NiFi 2.8.0 is running locally (see flows/scripts/run_nifi.sh).
#   2. NAR-bundle deployed to NiFi extensions directory.
#   3. Datasets are generated: python evaluation/scripts/generate_datasets.py
#   4. Five flow blueprints imported from `flows/`.
#
# Behaviour:
#   For every (configuration × dataset) combination the script copies the input
#   dataset into the watch directory used by the corresponding flow, polls the
#   NiFi REST API for the FlowFile to reach the success queue, and records the
#   provenance event duration into evaluation/results/results.csv.
#
# Defaults
#   - 5 iterations per combination (first one is discarded as warm-up)
#   - Output directory: evaluation/results/
#   - NiFi base URL: http://localhost:8080/nifi-api

set -euo pipefail

REPO="$(cd "$(dirname "$0")"/../.. && pwd)"
RESULTS_DIR="$REPO/evaluation/results"
DATASETS_DIR="$REPO/evaluation/datasets"
ITERATIONS=${ITERATIONS:-5}
BASE_URL=${BASE_URL:-"http://localhost:8080/nifi-api"}
TOKEN=${NIFI_TOKEN:-""}

CONFIGS=(
  "Baseline"
  "Native-RMLMAPPER"
  "Native-MORPH_KGC"
  "AUTO"
)

DATASETS=(
  "small_100.json"
  "medium_10k.json"
  "large_500k.json"
  "xlarge_2m.json"
  "invoices_50k.xml"
)

mkdir -p "$RESULTS_DIR"
RESULTS="$RESULTS_DIR/results.csv"
echo "config,dataset,iteration,duration_ms,triples,engine_selected" > "$RESULTS"

for cfg in "${CONFIGS[@]}"; do
  for ds in "${DATASETS[@]}"; do
    for i in $(seq 1 "$ITERATIONS"); do
      echo "[$cfg | $ds | iter=$i] copying input to NiFi watch directory"
      cp "$DATASETS_DIR/$ds" /tmp/nifi-rml-bench/

      echo "[$cfg | $ds | iter=$i] waiting for processing"
      python "$REPO/evaluation/scripts/parse_results.py" \
        --base-url "$BASE_URL" --token "$TOKEN" \
        --config "$cfg" --dataset "$ds" --iteration "$i" \
        >> "$RESULTS"
    done
  done
done

echo "Benchmark complete. Results in $RESULTS"
