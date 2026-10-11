#!/usr/bin/env bash
#
# Launches the prototype Docker stack (Asterisk 22 + companion API), runs the
# SIP prototype scripts against it, and tears everything down.
#
#   scripts/test-prototype.sh            # full prototype pass
#
# G2: provisioned registration with digest auth succeeds
# G3: two-party REGISTER-INVITE-200-ACK-BYE completes
# G4: wrong-password registration is rejected and creates no contact
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/server/compose.yaml"
PROJECT="st-proto"

cleanup() {
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ "${1:-}" == "--keep" ]]; then
    trap - EXIT
fi

cd "$ROOT_DIR"

echo "== starting prototype stack =="
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" up -d --build --wait

wait_for_registration_ready() {
    local tries="${1:-30}"
    for _ in $(seq 1 "$tries"); do
        if docker exec st-proto-asterisk asterisk -rx 'pjsip show endpoints' 2>/dev/null | grep -q "Endpoint:  alice"; then
            echo "asterisk ready"
            return 0
        fi
        sleep 2
    done
    echo "asterisk did not become ready" >&2
    return 1
}
wait_for_registration_ready "$@"

SIP_DIR="$ROOT_DIR/integration-tests/sip"

echo "== G2: alice registers with digest auth =="
node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 alice prototest-alice-pass

echo "== G3: two-party call REGISTER-INVITE-200-ACK-BYE =="
CALLEE_LOG="$(mktemp)"
node "$SIP_DIR/callee.cjs" 127.0.0.1 5060 bob prototest-bob-pass > "$CALLEE_LOG" 2>&1 &
CALLEE_PID=$!
sleep 3
set +e
node "$SIP_DIR/caller.cjs" 127.0.0.1 5060 alice prototest-alice-pass bob
CALLER_EXIT=$?
set -e
wait "$CALLEE_PID" || true
if [[ $CALLER_EXIT -ne 0 ]]; then
    echo "PROTOTYPE-FAIL: caller exited $CALLER_EXIT" >&2
    exit 1
fi
if ! grep -q "BYE-OK" "$CALLEE_LOG"; then
    echo "PROTOTYPE-FAIL: callee never confirmed BYE" >&2
    cat "$CALLEE_LOG" >&2
    exit 1
fi
rm -f "$CALLEE_LOG"
echo "G3-CALL-COMPLETE"

echo "== G4: unprovisioned user with wrong password cannot register =="
set +e
node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 mallory definitely-wrong
NEG_EXIT=$?
set -e
if [[ $NEG_EXIT -eq 0 ]]; then
    echo "SECURITY-FAIL: mallory registered with a wrong password" >&2
    exit 1
fi
if docker exec st-proto-asterisk asterisk -rx 'pjsip show contacts' 2>/dev/null | grep -q "mallory"; then
    echo "SECURITY-FAIL: mallory has a registered contact" >&2
    exit 1
fi
echo "unprovisioned registration rejected without a contact"

echo "PROTOTYPE-OK"
