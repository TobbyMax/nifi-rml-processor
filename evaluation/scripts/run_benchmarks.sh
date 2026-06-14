#!/usr/bin/env bash
# Run the NiFi RML processor benchmarks against a running NiFi instance.
#
# Pre-requisites:
#   1. NiFi 2.8.0 is running locally (see flows/scripts/run_nifi.sh).
#   2. NAR-bundle (java) or Python processor (py) deployed.
#   3. Datasets are generated: python evaluation/scripts/generate_datasets.py
#
# Behaviour:
#   - Imports the benchmark flow (rml-benchmark or rml-benchmark-py PG) if missing.
#   - Stops every other process group at root so they don't compete for
#     files dropped into /tmp/nifi-rml-bench.
#   - For each (case × dataset) combination:
#       * Patches processor properties to match the case.
#       * Copies the input dataset into the watch directory and waits for the
#         output file to appear in /tmp/nifi-rml-out (end-to-end timer).
#       * Looks up rml.triples.count / rml.engine.selected for that input via
#         the NiFi provenance REST API (filtered by Filename + startDate).
#   - Writes one CSV row per iteration to evaluation/results/results.csv.
#
# Tunable env vars:
#   ITERATIONS   default 5
#   BASE_URL     default https://localhost:8443/nifi-api
#   NIFI_TOKEN   bearer token, empty for unsecured NiFi
#   SKIP_XML     default 0 (set to 1 to skip XML tests)
#   SKIP_YARRRML default 0 (need customers_to_person.yarrrml.yml first)
#
# Flags:
#   --processor java  (default) use Java ExecuteRMLMappingProcessor
#   --processor py    use Python ExecuteRMLMappingPython
#
# Output CSV columns:
#   case,input_format,output_format,mapping_type,dataset,iteration,
#   duration_ms,triples,engine_selected

set -euo pipefail

REPO="$(cd "$(dirname "$0")"/../.. && pwd)"
RESULTS_DIR="$REPO/evaluation/results"
DATASETS_DIR="$REPO/evaluation/datasets"
MAPPINGS_DIR="$REPO/evaluation/mappings"
FLOWS_DIR="$REPO/flows"
SCRIPTS_DIR="$REPO/evaluation/scripts"

ITERATIONS=${ITERATIONS:-5}
BASE_URL=${BASE_URL:-"https://localhost:8443/nifi-api"}
TOKEN=${NIFI_TOKEN:-""}
SKIP_XML=${SKIP_XML:-0}
SKIP_YARRRML=${SKIP_YARRRML:-0}

# Parse --processor flag (default: java)
PROCESSOR_MODE="java"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --processor)
      PROCESSOR_MODE="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done

if [[ "$PROCESSOR_MODE" == "py" ]]; then
  BENCH_PG="rml-benchmark-py"
  BENCH_PROCESSOR="ExecuteRMLMappingPython (BENCHMARK)"
  BENCH_FLOW="$FLOWS_DIR/flow_benchmark_py.json"
else
  BENCH_PG="rml-benchmark"
  BENCH_PROCESSOR="ExecuteRMLMappingProcessor (BENCHMARK)"
  BENCH_FLOW="$FLOWS_DIR/flow_benchmark.json"
fi

BENCH_INPUT_DIR="/tmp/nifi-rml-bench"
BENCH_OUTPUT_DIR="/tmp/nifi-rml-out"

# HTTP headers for raw curl calls (used only for provenance/process-group health checks).
HEADERS=()
if [[ -n "$TOKEN" ]]; then
    HEADERS=(-H "Authorization: Bearer $TOKEN")
fi
export CURL_OPTS="-k -s"

# Get output file extension for format
get_out_ext() {
    case "$1" in
        TURTLE) echo "ttl" ;;
        NTRIPLES) echo "nt" ;;
        JSONLD) echo "jsonld" ;;
        RDFXML) echo "rdf" ;;
        *) echo "ttl" ;;
    esac
}

# Get timeout for dataset
get_timeout() {
    case "$1" in
        small_100*) echo "60" ;;
        medium_10k*) echo "120" ;;
        large_50k*) echo "180" ;;
        large_100k*) echo "300" ;;
        large_500k*) echo "900" ;;
        xlarge_2m*) echo "2400" ;;
        invoices_100*) echo "60" ;;
        invoices_1k*) echo "90" ;;
        invoices_5k*) echo "150" ;;
        invoices_10k*) echo "300" ;;
        *) echo "120" ;;
    esac
}

#------------------------------------------------------------------------------
# Helper functions
#------------------------------------------------------------------------------

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

clean_dirs() {
    mkdir -p "$BENCH_INPUT_DIR" "$BENCH_OUTPUT_DIR"
    rm -rf "${BENCH_INPUT_DIR:?}"/* "${BENCH_OUTPUT_DIR:?}"/*
}

import_benchmark_flow_if_missing() {
    # The shared import_blueprint.py is not fully idempotent (it falls back on
    # processor 409s but raises on connection 409s). So we only call it when
    # the PG is absent — re-runs of the benchmark are safe.
    if python3 "$SCRIPTS_DIR/nifi_admin.py" \
        --base-url "$BASE_URL" --token "$TOKEN" \
        pg-exists "$BENCH_PG" >/dev/null 2>&1; then
        echo "PG $BENCH_PG already present, skipping import"
        return 0
    fi
    python3 "$REPO/flows/scripts/import_blueprint.py" \
        --base-url "$BASE_URL" --token "$TOKEN" \
        "$BENCH_FLOW"
}

solo_benchmark_pg() {
    python3 "$SCRIPTS_DIR/nifi_admin.py" \
        --base-url "$BASE_URL" --token "$TOKEN" \
        solo-pg "$BENCH_PG"
}

set_case_properties() {
    local mapping_file="$1"
    local mapping_format="$2"      # RML_TTL | YARRRML
    local input_data_format="$3"   # JSON | CSV | XML
    local output_rdf_format="$4"   # TURTLE | NTRIPLES | JSONLD | RDFXML
    # Path inside NiFi container (see run_nifi.sh volume mounts)
    local mapping_path="/opt/nifi/mappings/$mapping_file"
    local extra_props=()
    if [[ "$PROCESSOR_MODE" == "java" ]]; then
        extra_props=(--prop "engine-mode=RMLMAPPER")
    fi
    local cmd=(
        python3 "$SCRIPTS_DIR/nifi_admin.py"
        --base-url "$BASE_URL" --token "$TOKEN"
        set-properties "$BENCH_PG" "$BENCH_PROCESSOR"
        --prop "mapping-source=FILE"
        --prop "mapping-file=$mapping_path"
        --prop "mapping-format=$mapping_format"
        --prop "input-data-format=$input_data_format"
        --prop "output-rdf-format=$output_rdf_format"
    )
    if [[ ${#extra_props[@]} -gt 0 ]]; then
        cmd+=("${extra_props[@]}")
    fi
    "${cmd[@]}"
}

# size_token "small_100.json" -> "small_100"; falls back to the bare stem
# for datasets that don't follow the "<size>.<ext>" convention (e.g. invoices).
size_token() {
    local basename="$1"
    local stem="${basename%.*}"
    case "$stem" in
        small_100|medium_10k|large_500k|xlarge_2m) echo "$stem" ;;
        *) echo "$stem" ;;
    esac
}

# End-to-end wait: poll for the expected output basename. Echoes
# "<duration_ms>,<t0_ms>" to stdout. duration_ms == NA on timeout.
run_one_iteration() {
    local input_basename="$1"
    local output_format="$2"
    local timeout_seconds="$3"
    local ext
    ext=$(get_out_ext "$output_format")
    local out_basename="${input_basename%.*}.${ext}"
    local out_path="$BENCH_OUTPUT_DIR/$out_basename"

    rm -f "$out_path"

    local t0_ms
    t0_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
    cp "$DATASETS_DIR/$input_basename" "$BENCH_INPUT_DIR/"

    local deadline_ms=$(( t0_ms + timeout_seconds * 1000 ))
    while [[ ! -f "$out_path" ]]; do
        local now_ms
        now_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
        if (( now_ms >= deadline_ms )); then
            echo "NA,$t0_ms"
            return 0
        fi
        sleep 0.1
    done

    local t1_ms
    t1_ms=$(python3 -c 'import time; print(int(time.time()*1000))')
    echo "$(( t1_ms - t0_ms )),$t0_ms"
}

# Parse a CASES line: "name | input_pattern | mapping_file | output_format | sizes"
# Sets globals CASE_NAME, INPUT_PATTERN, MAPPING_FILE, OUTPUT_FORMAT, SIZES.
parse_case() {
    local line="$1"
    IFS='|' read -r CASE_NAME INPUT_PATTERN MAPPING_FILE OUTPUT_FORMAT SIZES <<<"$line"
    CASE_NAME="${CASE_NAME// /}"
    INPUT_PATTERN="${INPUT_PATTERN// /}"
    MAPPING_FILE="${MAPPING_FILE// /}"
    OUTPUT_FORMAT="${OUTPUT_FORMAT// /}"
    # SIZES intentionally keeps spaces — it's a space-separated list.
    SIZES="$(echo "$SIZES" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
}

# CASE_NAME is "<input_format>_<output_ext>_<mapping_type>".
# E.g. "json_ttl_rml" → input=json, output=ttl, mapping=rml.
derive_dimensions() {
    local name="$1"
    INPUT_FORMAT="${name%%_*}"
    local rest="${name#*_}"
    OUTPUT_EXT_LABEL="${rest%%_*}"
    MAPPING_TYPE="${rest#*_}"
}

#------------------------------------------------------------------------------
# Case matrix
#------------------------------------------------------------------------------

# name | input_pattern | mapping_file | output_rdf_format | sizes
# input_pattern uses %s for the dataset size token (small_100 | medium_10k | ...).
CASES=(
  "json_ttl_rml      | %s.json          | customers_to_person.rml.ttl       | TURTLE | small_100 medium_10k large_50k large_100k large_500k xlarge_2m"
  "json_jsonld_rml   | %s.json          | customers_to_person.rml.ttl       | JSONLD | small_100 medium_10k large_50k large_100k"
  "csv_ttl_rml       | %s.csv           | customers_to_person.rml.csv.ttl   | TURTLE | small_100 medium_10k large_50k large_100k large_500k xlarge_2m"
  "csv_jsonld_rml    | %s.csv           | customers_to_person.rml.csv.ttl   | JSONLD | small_100 medium_10k large_50k large_100k"
  "xml_ttl_rml       | invoices_%s.xml  | invoices.rml.ttl                  | TURTLE | 100 1k 5k 10k"
  "json_ttl_yarrrml  | %s.json          | customers_to_person.yarrrml.yml   | TURTLE | small_100 medium_10k large_50k large_100k large_500k xlarge_2m"
)

#------------------------------------------------------------------------------
# Main
#------------------------------------------------------------------------------

mkdir -p "$RESULTS_DIR" "$BENCH_INPUT_DIR" "$BENCH_OUTPUT_DIR"

echo "Processor mode: $PROCESSOR_MODE  (PG: $BENCH_PG)"
echo "Importing benchmark flow (idempotent)..."
import_benchmark_flow_if_missing

echo "Stopping all other PGs, ensuring rml-benchmark is RUNNING..."
solo_benchmark_pg

# Wipe any leftovers so the first iteration starts with empty dirs even when
# the previous run died mid-iteration.
clean_dirs

# Results file with timestamp and processor mode
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RESULTS="$RESULTS_DIR/results_${PROCESSOR_MODE}_${TIMESTAMP}.csv"
echo "case,input_format,output_format,mapping_type,dataset,iteration,duration_ms,triples,engine_selected" > "$RESULTS"

for case_line in "${CASES[@]}"; do
    parse_case "$case_line"
    derive_dimensions "$CASE_NAME"

    if [[ "$MAPPING_TYPE" == "yarrrml" && "$SKIP_YARRRML" == "1" ]]; then
        echo "[$CASE_NAME] skipped (SKIP_YARRRML=1)"
        continue
    fi
    if [[ "$INPUT_FORMAT" == "xml" && "$SKIP_XML" == "1" ]]; then
        echo "[$CASE_NAME] skipped (SKIP_XML=1)"
        continue
    fi

    mapping_format=$([[ "$MAPPING_TYPE" == "yarrrml" ]] && echo "YARRRML" || echo "RML_TTL")
    input_data_format=$(echo "$INPUT_FORMAT" | tr '[:lower:]' '[:upper:]')

    echo "[$CASE_NAME] configuring processor (mapping=$MAPPING_FILE, output=$OUTPUT_FORMAT)"
    set_case_properties "$MAPPING_FILE" "$mapping_format" "$input_data_format" "$OUTPUT_FORMAT"

    echo "Waiting 15 seconds for workflow to initialize"
    sleep 15

    for size in $SIZES; do
        # Skip datasets >100k for Python processor (memory limitations)
        if [[ "$PROCESSOR_MODE" == "py" ]]; then
            case "$size" in
                large_500k|xlarge_2m|2m|500k)
                    echo "[$CASE_NAME | $size] skipped for Python (size limit 100k)"
                    continue
                    ;;
            esac
        fi

        if [[ "$INPUT_PATTERN" == *"%s"* ]]; then
            input_basename=$(printf "$INPUT_PATTERN" "$size")
        else
            input_basename="$INPUT_PATTERN"
        fi
        if [[ ! -f "$DATASETS_DIR/$input_basename" ]]; then
            echo "[$CASE_NAME | $input_basename] dataset missing, skipping"
            continue
        fi
        token=$(size_token "$input_basename")
        timeout_s=$(get_timeout "$token")

        # Always 1 iteration for 2m and 500k datasets (time/memory constraints)
        iterations="$ITERATIONS"
        case "$token" in
            xlarge_2m|2m|large_500k|500k) iterations=1 ;;
        esac

        for i in $(seq 1 "$iterations"); do
            clean_dirs
            echo "[$CASE_NAME | $input_basename | iter=$i] waiting (timeout ${timeout_s}s)"
            read -r duration_ms t0_ms < <(run_one_iteration "$input_basename" "$OUTPUT_FORMAT" "$timeout_s" | tr ',' ' ')

            if [[ "$duration_ms" == "NA" ]]; then
                triples_engine="NA,NA"
            else
                # Compute output filename (same logic as in run_one_iteration)
                ext=$(get_out_ext "$OUTPUT_FORMAT")
                out_basename="${input_basename%.*}.${ext}"
                triples_engine=$(python3 "$SCRIPTS_DIR/parse_results.py" \
                    --base-url "$BASE_URL" --token "$TOKEN" \
                    --filename "$out_basename" \
                    --start-epoch-ms "$t0_ms" \
                    --timeout 30) || triples_engine="NA,NA"
            fi

            echo "$CASE_NAME,$INPUT_FORMAT,$OUTPUT_FORMAT,$MAPPING_TYPE,$input_basename,$i,$duration_ms,$triples_engine" >> "$RESULTS"
        done
    done
done

echo "Benchmark complete. Results in $RESULTS"
