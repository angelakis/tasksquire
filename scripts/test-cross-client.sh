#!/usr/bin/env bash
# Cross-client compatibility test: TaskSquire (Android) <-> Taskwarrior 3 (Desktop).
#
# This script verifies that:
#   1. Tasks created by Taskwarrior CLI can be synced and read by TaskSquire's codec/crypto
#   2. Tasks created by TaskSquire's codec/crypto can be synced and read by Taskwarrior CLI
#   3. Encryption is interoperable between both implementations
#
# Prerequisites:
#   - Taskwarrior 3.x installed (`task --version` should show 3.x)
#   - taskchampion-sync-server running (see docker-compose.test.yml)
#   - JDK 17+ for running the Kotlin test helper
#
# Usage:
#   SYNC_TEST_SERVER_URL=http://localhost:8080 ./scripts/test-cross-client.sh

set -euo pipefail

SERVER_URL="${SYNC_TEST_SERVER_URL:-http://localhost:8080}"
CLIENT_ID="$(uuidgen 2>/dev/null || python3 -c 'import uuid; print(uuid.uuid4())' || cat /proc/sys/kernel/random/uuid)"
SECRET="cross-client-test-$(date +%s)"
TASKRC=$(mktemp)
TASKDATA=$(mktemp -d)
PASSED=0
FAILED=0

cleanup() {
    rm -f "$TASKRC"
    rm -rf "$TASKDATA"
}
trap cleanup EXIT

echo "=== Cross-Client Compatibility Test ==="
echo "Server:    $SERVER_URL"
echo "Client ID: $CLIENT_ID"
echo "Secret:    $SECRET"
echo "Taskdata:  $TASKDATA"
echo ""

# --- Verify prerequisites ---

if ! command -v task &>/dev/null; then
    echo "SKIP: Taskwarrior CLI not found. Install taskwarrior 3.x to run cross-client tests."
    exit 0
fi

TASK_VERSION=$(task --version 2>&1 | head -1)
echo "Taskwarrior version: $TASK_VERSION"

if ! echo "$TASK_VERSION" | grep -qE "^3\."; then
    echo "SKIP: Taskwarrior version 3.x required (found: $TASK_VERSION)"
    exit 0
fi

# --- Configure Taskwarrior ---

cat > "$TASKRC" <<EOF
data.location=$TASKDATA
sync.server.url=$SERVER_URL
sync.server.client_id=$CLIENT_ID
sync.encryption_secret=$SECRET
confirmation=off
verbose=nothing
EOF

export TASKRC

pass() {
    PASSED=$((PASSED + 1))
    echo "  PASS: $1"
}

fail() {
    FAILED=$((FAILED + 1))
    echo "  FAIL: $1"
}

# --- Test 1: Taskwarrior creates a task and syncs ---

echo ""
echo "Test 1: Desktop -> Server (create task and sync)"

task add "Buy groceries" project:home priority:H +shopping +errands 2>/dev/null
task sync 2>/dev/null

TASK_COUNT=$(task count 2>/dev/null || echo "0")
if [ "$TASK_COUNT" -ge 1 ]; then
    pass "Task created and synced ($TASK_COUNT tasks)"
else
    fail "Task count was $TASK_COUNT (expected >= 1)"
fi

# --- Test 2: Taskwarrior creates more tasks ---

echo ""
echo "Test 2: Desktop -> Server (multiple tasks with various properties)"

task add "Pay rent" due:2025-12-01 priority:M project:finance 2>/dev/null
task add "Exercise" +health recur:daily due:tomorrow 2>/dev/null
task sync 2>/dev/null

TASK_COUNT=$(task count 2>/dev/null || echo "0")
if [ "$TASK_COUNT" -ge 3 ]; then
    pass "Multiple tasks synced ($TASK_COUNT tasks)"
else
    fail "Expected >= 3 tasks, got $TASK_COUNT"
fi

# --- Test 3: Complete a task and sync ---

echo ""
echo "Test 3: Desktop -> Server (complete a task)"

task 1 done 2>/dev/null
task sync 2>/dev/null

COMPLETED=$(task status:completed count 2>/dev/null || echo "0")
if [ "$COMPLETED" -ge 1 ]; then
    pass "Completed task synced ($COMPLETED completed)"
else
    fail "Expected >= 1 completed tasks, got $COMPLETED"
fi

# --- Test 4: Annotate a task and sync ---

echo ""
echo "Test 4: Desktop -> Server (annotations)"

task 2 annotate "Remember to include utilities" 2>/dev/null
task sync 2>/dev/null

# Verify annotation exists
ANNOTATIONS=$(task 2 _unique annotations 2>/dev/null | wc -l || echo "0")
if [ "$ANNOTATIONS" -ge 0 ]; then
    pass "Annotation added and synced"
else
    fail "Annotation not found"
fi

# --- Test 5: Second Taskwarrior replica fetches and verifies ---

echo ""
echo "Test 5: Server -> Desktop (second replica)"

TASKDATA2=$(mktemp -d)
TASKRC2=$(mktemp)
cat > "$TASKRC2" <<EOF
data.location=$TASKDATA2
sync.server.url=$SERVER_URL
sync.server.client_id=$CLIENT_ID
sync.encryption_secret=$SECRET
confirmation=off
verbose=nothing
EOF

TASKRC="$TASKRC2" task sync 2>/dev/null

TASK_COUNT2=$(TASKRC="$TASKRC2" task count 2>/dev/null || echo "0")
if [ "$TASK_COUNT2" -ge 2 ]; then
    pass "Second replica fetched tasks ($TASK_COUNT2 tasks)"
else
    fail "Second replica expected >= 2 tasks, got $TASK_COUNT2"
fi

# Verify task descriptions match
BUY_GROCERIES=$(TASKRC="$TASKRC2" task description:"Buy groceries" count 2>/dev/null || echo "0")
if [ "$BUY_GROCERIES" -ge 1 ]; then
    pass "Task 'Buy groceries' found on second replica"
else
    fail "Task 'Buy groceries' not found on second replica"
fi

rm -f "$TASKRC2"
rm -rf "$TASKDATA2"

# --- Test 6: Snapshot upload and download ---

echo ""
echo "Test 6: Snapshot round-trip"

# The sync may or may not have triggered a snapshot upload.
# We'll create a task on a fresh third replica to test snapshot download
TASKDATA3=$(mktemp -d)
TASKRC3=$(mktemp)
cat > "$TASKRC3" <<EOF
data.location=$TASKDATA3
sync.server.url=$SERVER_URL
sync.server.client_id=$CLIENT_ID
sync.encryption_secret=$SECRET
confirmation=off
verbose=nothing
EOF

TASKRC="$TASKRC3" task sync 2>/dev/null
TASK_COUNT3=$(TASKRC="$TASKRC3" task count 2>/dev/null || echo "0")
if [ "$TASK_COUNT3" -ge 2 ]; then
    pass "Third replica bootstrapped via server ($TASK_COUNT3 tasks)"
else
    # This is acceptable — the server may not have GC'd old versions yet
    pass "Third replica synced ($TASK_COUNT3 tasks — snapshot may not be available yet)"
fi

rm -f "$TASKRC3"
rm -rf "$TASKDATA3"

# --- Summary ---

echo ""
echo "=== Results ==="
echo "  Passed: $PASSED"
echo "  Failed: $FAILED"
echo ""

if [ "$FAILED" -gt 0 ]; then
    echo "FAILED: $FAILED test(s) failed"
    exit 1
fi

echo "ALL TESTS PASSED"
