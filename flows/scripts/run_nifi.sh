#!/usr/bin/env bash
# Spin up Apache NiFi 2.8.0 in Docker for local testing of the RML processors.
#
# Usage:
#   flows/scripts/run_nifi.sh start     # start container
#   flows/scripts/run_nifi.sh stop      # stop and remove
#   flows/scripts/run_nifi.sh deploy    # copy NAR-bundle into the running container
#
# Mounts:
#   $REPO/nifi-rml-nar/build/libs       -> /opt/nifi/nifi-current/extensions
#   $REPO/docs/examples/mappings        -> /opt/nifi/mappings (read-only)
#   $REPO/docs/examples/data            -> /tmp/nifi-rml-in   (read-only)
#   /tmp/nifi-rml-out                    -> /tmp/nifi-rml-out  (read-write, host)

set -euo pipefail

REPO="$(cd "$(dirname "$0")"/../.. && pwd)"
CONTAINER="nifi-rml-dev"
IMAGE="apache/nifi:2.8.0"

case "${1:-start}" in
  start)
    mkdir -p /tmp/nifi-rml-out
    docker run -d --name "$CONTAINER" \
      -p 8080:8080 -p 8443:8443 \
      -e NIFI_WEB_HTTP_PORT=8080 \
      -e SINGLE_USER_CREDENTIALS_USERNAME=admin \
      -e SINGLE_USER_CREDENTIALS_PASSWORD=ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB \
      -v "$REPO/nifi-rml-nar/build/libs:/opt/nifi/nifi-current/nar_extensions" \
      -v "$REPO/docs/examples/mappings:/opt/nifi/mappings:ro" \
      -v "$REPO/docs/examples/data:/tmp/nifi-rml-in" \
      -v "/tmp/nifi-rml-out:/tmp/nifi-rml-out" \
      "$IMAGE"
    echo "NiFi starting at http://localhost:8080/nifi (admin / ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB)"
    ;;

  stop)
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    ;;

  deploy)
    NAR=$(ls -1t "$REPO"/nifi-rml-nar/build/libs/*.nar 2>/dev/null | head -1)
    if [[ -z "$NAR" ]]; then
      echo "No NAR found. Run ./gradlew :nifi-rml-nar:nar first." >&2
      exit 1
    fi
    docker cp "$NAR" "$CONTAINER":/opt/nifi/nifi-current/extensions/
    docker restart "$CONTAINER"
    echo "Deployed $(basename "$NAR")"
    ;;

  *)
    echo "Usage: $0 {start|stop|deploy}" >&2
    exit 1
    ;;
esac
