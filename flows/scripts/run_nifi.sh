#!/usr/bin/env bash
# Spin up Apache NiFi 2.8.0 in Docker for local testing of the RML processors.
#
# Usage:
#   flows/scripts/run_nifi.sh start             # start container (custom image with morph-kgc + Python processor)
#   flows/scripts/run_nifi.sh stop              # stop and remove
#   flows/scripts/run_nifi.sh deploy            # copy NAR-bundle into the running container
#   flows/scripts/run_nifi.sh deploy_py         # hot-deploy Python processor (no rebuild needed)
#   flows/scripts/run_nifi.sh rebuild-image     # rebuild custom image after Dockerfile changes
#
# Mounts:
#   $REPO/nifi-rml-nar/build/libs       -> /opt/nifi/nifi-current/nar_extensions
#   $REPO/evaluation/mappings           -> /opt/nifi/mappings (read-only)
#   $REPO/evaluation/datasets           -> /opt/nifi/datasets (read-only)
#   /tmp/nifi-rml-bench                  -> /tmp/nifi-rml-bench  (read-write)
#   /tmp/nifi-rml-out                    -> /tmp/nifi-rml-out    (read-write)

set -euo pipefail

REPO="$(cd "$(dirname "$0")"/../.. && pwd)"
CONTAINER="nifi-rml-dev"
CUSTOM_IMAGE="nifi-rml-dev-image"

case "${1:-start}" in
  start)
    if ! docker image inspect "$CUSTOM_IMAGE" >/dev/null 2>&1; then
      echo "Custom image not found; building..."
      docker build -t "$CUSTOM_IMAGE" -f "$REPO/Dockerfile.nifi" "$REPO"
    fi
    mkdir -p /tmp/nifi-rml-bench /tmp/nifi-rml-out
    docker run -d --name "$CONTAINER" \
      -p 8080:8080 -p 8443:8443 \
      -e NIFI_WEB_HTTP_PORT=8080 \
      -e SINGLE_USER_CREDENTIALS_USERNAME=admin \
      -e SINGLE_USER_CREDENTIALS_PASSWORD=ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB \
      -e NIFI_PYTHON_COMMAND=/opt/nifi/python-venv/bin/python \
      -v "$REPO/nifi-rml-nar/build/libs:/opt/nifi/nifi-current/nar_extensions" \
      -v "$REPO/docs/examples/mappings:/opt/nifi/mappings:ro" \
      -v "$REPO/docs/examples/data:/tmp/nifi-rml-in" \
      -v "/tmp/nifi-rml-bench:/tmp/nifi-rml-bench" \
      -v "/tmp/nifi-rml-out:/tmp/nifi-rml-out" \
      "$CUSTOM_IMAGE"
    echo "NiFi starting at http://localhost:8080/nifi (admin / ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB)"
    echo "Watch dir: /tmp/nifi-rml-bench"
    echo "Output:    /tmp/nifi-rml-out"
    ;;

  start_for_bench)
    if ! docker image inspect "$CUSTOM_IMAGE" >/dev/null 2>&1; then
      echo "Custom image not found; building..."
      docker build -t "$CUSTOM_IMAGE" -f "$REPO/Dockerfile.nifi" "$REPO"
    fi
    mkdir -p /tmp/nifi-rml-bench /tmp/nifi-rml-out
    docker run -d --name "$CONTAINER" \
      -p 8080:8080 -p 8443:8443 \
      -e NIFI_WEB_HTTP_PORT=8080 \
      -e SINGLE_USER_CREDENTIALS_USERNAME=admin \
      -e SINGLE_USER_CREDENTIALS_PASSWORD=ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB \
      -e NIFI_PYTHON_COMMAND=/opt/nifi/python-venv/bin/python \
      -v "$REPO/nifi-rml-nar/build/libs:/opt/nifi/nifi-current/nar_extensions" \
      -v "$REPO/evaluation/mappings:/opt/nifi/mappings:ro" \
      -v "$REPO/evaluation/datasets:/opt/nifi/datasets:ro" \
      -v "/tmp/nifi-rml-bench:/tmp/nifi-rml-bench" \
      -v "/tmp/nifi-rml-out:/tmp/nifi-rml-out" \
      "$CUSTOM_IMAGE"
    echo "NiFi starting at http://localhost:8080/nifi (admin / ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB)"
    echo "Mappings:  /opt/nifi/mappings (from evaluation/mappings)"
    echo "Datasets:  /opt/nifi/datasets (from evaluation/datasets)"
    echo "Watch dir: /tmp/nifi-rml-bench"
    echo "Output:    /tmp/nifi-rml-out"
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

  deploy_py)
    # Hot-deploy Python processor: the file is self-contained, no helper package needed
    docker cp "$REPO/nifi-rml-py/ExecuteRMLMappingPython.py" \
      "$CONTAINER":/opt/nifi/nifi-current/python_extensions/
    docker exec "$CONTAINER" chown nifi:nifi /opt/nifi/nifi-current/python_extensions/ExecuteRMLMappingPython.py
    docker restart "$CONTAINER"
    echo "Python processor deployed and container restarted"
    ;;

  rebuild-image)
    echo "Rebuilding custom NiFi image..."
    docker build -t "$CUSTOM_IMAGE" -f "$REPO/Dockerfile.nifi" "$REPO"
    echo "Done. Run: $0 stop && $0 start"
    ;;

  *)
    echo "Usage: $0 {start|start_for_bench|stop|deploy|deploy_py|rebuild-image}" >&2
    exit 1
    ;;
esac
