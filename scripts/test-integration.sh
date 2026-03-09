#!/usr/bin/env bash
# Run integration tests against a local taskchampion-sync-server.
#
# Usage:
#   ./scripts/test-integration.sh
#
# Prerequisites:
#   - Docker and Docker Compose
#   - JDK 17+ and Gradle
#
# This script:
#   1. Starts taskchampion-sync-server via Docker Compose
#   2. Waits for the server to be healthy
#   3. Runs integration tests
#   4. Tears down the server

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_URL="${SYNC_TEST_SERVER_URL:-http://localhost:8080}"

echo "=== TaskSquire Integration Tests ==="
echo "Project: $PROJECT_DIR"
echo "Server: $SERVER_URL"
echo ""

# Start the sync server
echo "Starting taskchampion-sync-server..."
docker compose -f "$PROJECT_DIR/docker-compose.test.yml" up -d

# Wait for health
echo "Waiting for server to be ready..."
for i in $(seq 1 30); do
    if curl -s -o /dev/null "$SERVER_URL" 2>/dev/null; then
        echo "Server is ready!"
        break
    fi
    if [ "$i" -eq 30 ]; then
        echo "ERROR: Server failed to start within 60 seconds"
        docker compose -f "$PROJECT_DIR/docker-compose.test.yml" logs
        docker compose -f "$PROJECT_DIR/docker-compose.test.yml" down
        exit 1
    fi
    echo "  Waiting... ($i/30)"
    sleep 2
done

# Run tests
echo ""
echo "Running integration tests..."
SYNC_TEST_SERVER_URL="$SERVER_URL" \
    "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" test \
        --tests "com.tasksquire.android.sync.integration.*" \
        --info || TEST_EXIT=$?

# Tear down
echo ""
echo "Tearing down server..."
docker compose -f "$PROJECT_DIR/docker-compose.test.yml" down

if [ "${TEST_EXIT:-0}" -ne 0 ]; then
    echo "FAILED: Integration tests failed"
    exit "$TEST_EXIT"
fi

echo "PASSED: All integration tests passed!"
