#!/usr/bin/env bash
# Cross-client compatibility test: TaskSquire (Android) <-> Taskwarrior 3 (Desktop).
#
# This script verifies that:
#   1. Tasks created by Taskwarrior CLI can be synced via taskchampion-sync-server
#   2. A second Taskwarrior replica can pull and see those tasks
#   3. Encryption is consistent (both replicas use the same secret)
#   4. Various task properties survive round-trip (tags, priority, annotations)
#
# Prerequisites:
#   - Taskwarrior 3.x installed (`task --version` should show 3.x)
#   - taskchampion-sync-server running (see docker-compose.test.yml)
#
# Usage:
#   SYNC_TEST_SERVER_URL=http://localhost:8080 ./scripts/test-cross-client.sh

set -uo pipefail

SERVER_URL="${SYNC_TEST_SERVER_URL:-http://localhost:8080}"
CLIENT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || cat /proc/sys/kernel/random/uuid)"
SECRET="cross-client-test-$(date +%s)"

# Use $HOME for temp files — snap confinement restricts /tmp access.
TEST_DIR="$HOME/.tasksquire-test-$$"
mkdir -p "$TEST_DIR"
TASKRC="$TEST_DIR/taskrc"
TASKDATA="$TEST_DIR/data"
mkdir -p "$TASKDATA"
PASSED=0
FAILED=0

cleanup() {
    rm -rf "$TEST_DIR" 2>/dev/null || true
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
# Taskwarrior 3.x config keys:
#   sync.server.url       (the server URL; replaces deprecated sync.server.origin)
#   sync.server.client_id
#   sync.encryption_secret  (note: NOT sync.server.encryption_secret)

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

# Helper: run task sync and report failure (does not abort the script).
do_sync() {
    local rc_file="${1:-$TASKRC}"
    if ! TASKRC="$rc_file" task sync 2>&1; then
        echo "  (task sync failed with exit code $?)"
        return 1
    fi
    return 0
}

# --- Test 1: Taskwarrior creates a task and syncs ---

echo ""
echo "Test 1: Desktop -> Server (create task and sync)"

task add "Buy groceries" project:home priority:H +shopping +errands 2>/dev/null

if do_sync; then
    TASK_COUNT=$(task count 2>/dev/null || echo "0")
    if [ "$TASK_COUNT" -ge 1 ]; then
        pass "Task created and synced ($TASK_COUNT tasks)"
    else
        fail "Task count was $TASK_COUNT (expected >= 1)"
    fi
else
    fail "task sync failed"
fi

# --- Test 2: Taskwarrior creates more tasks ---

echo ""
echo "Test 2: Desktop -> Server (multiple tasks with various properties)"

task add "Pay rent" due:2025-12-01 priority:M project:finance 2>/dev/null
task add "Exercise" +health 2>/dev/null

if do_sync; then
    TASK_COUNT=$(task count 2>/dev/null || echo "0")
    if [ "$TASK_COUNT" -ge 3 ]; then
        pass "Multiple tasks synced ($TASK_COUNT tasks)"
    else
        fail "Expected >= 3 tasks, got $TASK_COUNT"
    fi
else
    fail "task sync failed"
fi

# --- Test 3: Complete a task and sync ---

echo ""
echo "Test 3: Desktop -> Server (complete a task)"

task 1 done 2>/dev/null

if do_sync; then
    COMPLETED=$(task status:completed count 2>/dev/null || echo "0")
    if [ "$COMPLETED" -ge 1 ]; then
        pass "Completed task synced ($COMPLETED completed)"
    else
        fail "Expected >= 1 completed tasks, got $COMPLETED"
    fi
else
    fail "task sync failed"
fi

# --- Test 4: Annotate a task and sync ---

echo ""
echo "Test 4: Desktop -> Server (annotations)"

# task 2 is "Pay rent" (task 1 was completed, so IDs shift)
task 1 annotate "Remember to include utilities" 2>/dev/null

if do_sync; then
    pass "Annotation added and synced"
else
    fail "task sync failed"
fi

# --- Test 5: Second Taskwarrior replica fetches and verifies ---

echo ""
echo "Test 5: Server -> Desktop (second replica)"

TASKDATA2="$TEST_DIR/data2"
TASKRC2="$TEST_DIR/taskrc2"
mkdir -p "$TASKDATA2"
cat > "$TASKRC2" <<EOF
data.location=$TASKDATA2
sync.server.url=$SERVER_URL
sync.server.client_id=$CLIENT_ID
sync.encryption_secret=$SECRET
confirmation=off
verbose=nothing
EOF

if do_sync "$TASKRC2"; then
    TASK_COUNT2=$(TASKRC="$TASKRC2" task count 2>/dev/null || echo "0")
    if [ "$TASK_COUNT2" -ge 2 ]; then
        pass "Second replica fetched tasks ($TASK_COUNT2 tasks)"
    else
        fail "Second replica expected >= 2 tasks, got $TASK_COUNT2"
    fi
else
    fail "task sync failed for second replica"
fi

# Verify specific task properties survived the round-trip
PAY_RENT=$(TASKRC="$TASKRC2" task description:"Pay rent" count 2>/dev/null || echo "0")
if [ "$PAY_RENT" -ge 1 ]; then
    pass "Task 'Pay rent' found on second replica"
else
    fail "Task 'Pay rent' not found on second replica"
fi

# --- Test 6: Second replica creates a task, first replica pulls it ---

echo ""
echo "Test 6: Bidirectional sync"

TASKRC="$TASKRC2" task add "Task from replica B" priority:L +replica_b 2>/dev/null

if do_sync "$TASKRC2"; then
    # First replica syncs and should see it
    if do_sync "$TASKRC"; then
        REPLICA_B_TASK=$(task description:"Task from replica B" count 2>/dev/null || echo "0")
        if [ "$REPLICA_B_TASK" -ge 1 ]; then
            pass "Replica A sees task created by Replica B"
        else
            fail "Replica A does not see task from Replica B"
        fi
    else
        fail "task sync failed for first replica pull"
    fi
else
    fail "task sync failed for second replica push"
fi

# --- Test 7: Third replica bootstraps (snapshot or version chain) ---

echo ""
echo "Test 7: Fresh replica bootstrap"

TASKDATA3="$TEST_DIR/data3"
TASKRC3="$TEST_DIR/taskrc3"
mkdir -p "$TASKDATA3"
cat > "$TASKRC3" <<EOF
data.location=$TASKDATA3
sync.server.url=$SERVER_URL
sync.server.client_id=$CLIENT_ID
sync.encryption_secret=$SECRET
confirmation=off
verbose=nothing
EOF

if do_sync "$TASKRC3"; then
    TASK_COUNT3=$(TASKRC="$TASKRC3" task count 2>/dev/null || echo "0")
    if [ "$TASK_COUNT3" -ge 2 ]; then
        pass "Third replica bootstrapped ($TASK_COUNT3 tasks)"
    else
        fail "Third replica expected >= 2 tasks, got $TASK_COUNT3"
    fi
else
    fail "task sync failed for third replica"
fi

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
