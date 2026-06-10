#!/usr/bin/env bash
# One-shot bootstrapper for the end-to-end demo stand.
# Idempotent: re-running does not duplicate datasets, mappings or flows.
#
# Usage:
#   ./gradlew :nifi-rml-nar:nar
#   docker compose -f docker-compose.demo.yml up -d
#   bash demo/bootstrap.sh
#
# Prereqs on the host: bash, docker, python3, curl, jq.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

NIFI_URL="${NIFI_URL:-http://localhost:8080/nifi-api}"
NIFI_USER="${NIFI_USER:-admin}"
NIFI_PASS="${NIFI_PASS:-Secrets4ChangeMeNow}"
FUSEKI_URL="${FUSEKI_URL:-http://localhost:3030}"
FUSEKI_USER="${FUSEKI_USER:-admin}"
FUSEKI_PASS="${FUSEKI_PASS:-admin}"
NEO4J_URL="${NEO4J_URL:-http://localhost:7474}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASS="${NEO4J_PASS:-Secrets4ChangeMeNow}"
GP_HOST="${GP_HOST:-localhost}"
GP_USER="${GP_USER:-gpadmin}"
GP_PASS="${GP_PASS:-Secrets4ChangeMeNow}"
GP_DB="${GP_DB:-ecommerce}"

log() { printf '[bootstrap] %s\n' "$*" >&2; }
require() { command -v "$1" >/dev/null 2>&1 || { log "missing required tool: $1"; exit 1; }; }

require docker
require python3
require curl
require jq

# ---------------------------------------------------------------
# 1. Wait for services to come up.
# ---------------------------------------------------------------
wait_http() {
    local url="$1" name="$2" tries=60
    while ((tries-- > 0)); do
        if curl -fsS -o /dev/null --max-time 2 "${url}"; then
            log "${name} is up"
            return 0
        fi
        sleep 2
    done
    log "${name} did not become ready in time"
    return 1
}

wait_http "${FUSEKI_URL}/$/ping" "Fuseki"
wait_http "${NEO4J_URL}/" "Neo4j"
wait_http "${NIFI_URL}/system-diagnostics" "NiFi"

# ---------------------------------------------------------------
# 2. Greenplum: load the full benchmark dataset (idempotent).
# ---------------------------------------------------------------
log "loading benchmark dataset into Greenplum (~1k customers / 10k orders)"
python3 evaluation/scripts/generate_datasets.py --customers 1000 --orders 10000 \
    --out "${REPO_ROOT}/evaluation/datasets" >/dev/null

PGPASSWORD="${GP_PASS}" psql -h "${GP_HOST}" -U "${GP_USER}" -d "${GP_DB}" \
    -v ON_ERROR_STOP=1 \
    -c "TRUNCATE ops.order_items, ops.orders, ops.customers, ops.products RESTART IDENTITY CASCADE;" \
    >/dev/null

PGPASSWORD="${GP_PASS}" psql -h "${GP_HOST}" -U "${GP_USER}" -d "${GP_DB}" \
    -c "\copy ops.customers FROM '${REPO_ROOT}/evaluation/datasets/customers.csv' WITH (FORMAT csv, HEADER true)"
PGPASSWORD="${GP_PASS}" psql -h "${GP_HOST}" -U "${GP_USER}" -d "${GP_DB}" \
    -c "\copy ops.products  FROM '${REPO_ROOT}/evaluation/datasets/products.csv'  WITH (FORMAT csv, HEADER true)"
PGPASSWORD="${GP_PASS}" psql -h "${GP_HOST}" -U "${GP_USER}" -d "${GP_DB}" \
    -c "\copy ops.orders    FROM '${REPO_ROOT}/evaluation/datasets/orders.csv'    WITH (FORMAT csv, HEADER true)"
PGPASSWORD="${GP_PASS}" psql -h "${GP_HOST}" -U "${GP_USER}" -d "${GP_DB}" \
    -c "\copy ops.order_items FROM '${REPO_ROOT}/evaluation/datasets/order_items.csv' WITH (FORMAT csv, HEADER true)"

# ---------------------------------------------------------------
# 3. Mapping repository: ensure per-tenant trees exist.
# ---------------------------------------------------------------
log "publishing RML mappings to mapping-repo"
for tenant in acme globex; do
    cp "docs/examples/mappings/customers.parameterized.rml.ttl" \
        "demo/mappings/${tenant}/customers.rml.ttl"
    cp "docs/examples/mappings/orders.rml.ttl" \
        "demo/mappings/${tenant}/orders.rml.ttl" 2>/dev/null \
        || log "  (orders.rml.ttl absent in docs/examples — re-uses customers mapping)"
done

# ---------------------------------------------------------------
# 4. Fuseki: create per-tenant TDB datasets.
# ---------------------------------------------------------------
log "creating Fuseki datasets"
for tenant in acme globex; do
    curl -fsS -u "${FUSEKI_USER}:${FUSEKI_PASS}" -X POST \
        "${FUSEKI_URL}/$/datasets" \
        --data "dbName=tenant_${tenant}&dbType=tdb2" || log "  (tenant_${tenant} may already exist — ok)"
done

# ---------------------------------------------------------------
# 5. Neo4j: install n10s indexes and uniqueness constraints.
# ---------------------------------------------------------------
log "configuring Neo4j (n10s + indexes)"
cypher() {
    curl -fsS -u "${NEO4J_USER}:${NEO4J_PASS}" -H 'Content-Type: application/json' \
        -X POST "${NEO4J_URL}/db/neo4j/tx/commit" \
        -d "$(jq -n --arg q "$1" '{statements:[{statement:$q}]}')" >/dev/null
}
cypher "CREATE CONSTRAINT customer_id IF NOT EXISTS FOR (c:Customer) REQUIRE c.id IS UNIQUE"
cypher "CREATE CONSTRAINT order_id    IF NOT EXISTS FOR (o:Order)    REQUIRE o.id IS UNIQUE"
cypher "CREATE CONSTRAINT product_sku IF NOT EXISTS FOR (p:Product)  REQUIRE p.sku IS UNIQUE"
cypher "CALL n10s.graphconfig.init({handleVocabUris:'IGNORE',handleMultival:'OVERWRITE'})" || true

# ---------------------------------------------------------------
# 6. NiFi: import the e2e demo flow.
# ---------------------------------------------------------------
if [[ -f "flows/flow_e2e_demo.json" ]]; then
    log "importing flows/flow_e2e_demo.json into NiFi"
    TOKEN=$(curl -fsS -X POST "${NIFI_URL}/access/token" \
        -d "username=${NIFI_USER}&password=${NIFI_PASS}")
    python3 flows/scripts/import_blueprint.py \
        --base-url "${NIFI_URL}" \
        --token "${TOKEN}" \
        flows/flow_e2e_demo.json
else
    log "flows/flow_e2e_demo.json not found — skip flow import (build the blueprint first)"
fi

log "done. UIs:"
log "  NiFi      ${NIFI_URL%/nifi-api}/nifi"
log "  Fuseki    ${FUSEKI_URL}"
log "  Neo4j     ${NEO4J_URL}"
log "  Superset  http://localhost:8089"
