#!/bin/sh
# Superset entrypoint replacement: initializes the metadata DB,
# creates the admin user, then starts the dev server.
# Idempotent — safe on container restart.

set -e

if [ ! -f /app/superset_home/.bootstrapped ]; then
    superset db upgrade
    superset fab create-admin \
        --username "${ADMIN_USERNAME:-admin}" \
        --firstname Demo --lastname Admin \
        --email admin@example.com \
        --password "${ADMIN_PASSWORD:-admin}"
    superset init
    touch /app/superset_home/.bootstrapped
fi

exec /usr/bin/run-server.sh
