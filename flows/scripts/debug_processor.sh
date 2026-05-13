#!/usr/bin/env bash
# Debug script to check if RML processor was loaded into NiFi

set -euo pipefail

CONTAINER="nifi-rml-dev"

echo "=== 1. Files in NAR extensions directory ==="
docker exec "$CONTAINER" ls -lh /opt/nifi/nifi-current/nar_extensions/ || echo "No extensions found"

echo ""
echo "=== 2. NiFi logs (last 50 lines) ==="
docker logs "$CONTAINER" 2>&1 | tail -50 | grep -i "rml\|error\|loaded\|extension" || echo "No relevant logs found"

echo ""
echo "=== 3. Available processor types containing 'rml' ==="
TOKEN=$(curl -k -s -X POST https://localhost:8443/nifi-api/access/token \
-d 'username=admin&password=ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB') \
RESPONSE=$(curl -s -k -H "Authorization: Bearer $TOKEN" \
  "https://localhost:8443/nifi-api/flow/processor-types" 2>&1)
if echo "$RESPONSE" | grep -qi "ExecuteRML"; then
  echo "$RESPONSE" | grep -i "ExecuteRML" | head -5
else
  echo "Processor not found in response. Full response:"
  echo "$RESPONSE" | head -200
fi

echo ""
echo "=== 4. NAR file contents (jar/processor classes) ==="
NAR=$(ls -1t nifi-rml-nar/build/libs/*.nar 2>/dev/null | head -1 || echo "")
if [ -n "$NAR" ]; then
  echo "Found NAR: $NAR"
  unzip -l "$NAR" | grep -i "processor\|manifest" | head -20
else
  echo "No NAR file found in nifi-rml-nar/build/libs/"
fi
