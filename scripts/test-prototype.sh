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
# G5: SIP over TLS registration succeeds (skipped when openssl is unavailable)
# G6: the companion API answers HTTPS/WSS on 8443 (skipped without openssl)
# G7: read receipts are persisted, mutual and idempotent
# G8: revocation disconnects an established call without manual PBX edits
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/server/compose.yaml"
PROJECT="st-admin-test-$$"
CERTS_TMP=""
CALLEE_LOG=""
CALLER_LOG=""
STATE_FILE="$(mktemp)"
export ADMIN_USERNAME="prototype-operator"
export ADMIN_PASSWORD="prototest-admin-maple-robin-lantern-pebble-willow-1234"
export PBX_AMI_PASSWORD="prototest-manager-maple-robin-lantern-pebble-willow-1234"
export DB_PASSWORD="prototest-db-maple-robin-lantern-pebble-willow-1234"
SIP_PASSWORD="prototest-robin-maple-meadow-lantern-pebble-willow-scarlet-1234"

# Prints two host ports for the companion API (plain HTTP, then HTTPS),
# preferring 8080/8443 and falling back to ephemeral ports. 8080 commonly
# collides with other local services, so the prototype must not assume it.
pick_ports() {
    node -e '
const net = require("net");
const probe = (port) => new Promise((res) => {
  const s = net.createServer();
  s.once("error", () => res(false));
  s.listen({ port, host: "0.0.0.0" }, () => s.close(() => res(true)));
});
const ephemeral = () => new Promise((res) => {
  const s = net.createServer();
  s.listen(0, "0.0.0.0", () => { const p = s.address().port; s.close(() => res(p)); });
});
(async () => {
  const http = (await probe(8080)) ? 8080 : await ephemeral();
  let https = (await probe(8443)) ? 8443 : 0;
  if (!https || https === http) do { https = await ephemeral(); } while (https === http);
  process.stdout.write(http + " " + https);
})();
'
}

cleanup() {
    local result=$?
    if [[ "$result" -ne 0 ]]; then
        if [[ -n "$CALLER_LOG" && -f "$CALLER_LOG" ]]; then tail -30 "$CALLER_LOG" >&2; fi
        if [[ -n "$CALLEE_LOG" && -f "$CALLEE_LOG" ]]; then tail -30 "$CALLEE_LOG" >&2; fi
        docker compose -f "$COMPOSE_FILE" -p "$PROJECT" logs --tail=70 api asterisk db >&2 || true
    fi
    docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down -v --remove-orphans >/dev/null 2>&1 || true
    if [[ -n "$CERTS_TMP" ]]; then
        rm -f "$CERTS_TMP/privkey.pem" "$CERTS_TMP/fullchain.pem" "$CERTS_TMP/api-keystore.p12"
        rmdir "$CERTS_TMP"
    fi
    rm -f "$STATE_FILE"
    if [[ -n "$CALLEE_LOG" ]]; then rm -f "$CALLEE_LOG"; fi
    if [[ -n "$CALLER_LOG" ]]; then rm -f "$CALLER_LOG"; fi
}
trap cleanup EXIT

if [[ "${1:-}" == "--keep" ]]; then
    trap - EXIT
fi

cd "$ROOT_DIR"

# Throwaway certificates for the TLS checks. The stack still works unsecured
# when openssl is missing: ST_CERTS_DIR stays unset and the PBX/API come up
# UDP/HTTP only.
if command -v openssl >/dev/null 2>&1; then
    CERTS_TMP="$(mktemp -d)"
    openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
        -keyout "$CERTS_TMP/privkey.pem" \
        -out "$CERTS_TMP/fullchain.pem" \
        -subj "/CN=localhost" \
        -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1
    openssl pkcs12 -export \
        -in "$CERTS_TMP/fullchain.pem" \
        -inkey "$CERTS_TMP/privkey.pem" \
        -out "$CERTS_TMP/api-keystore.p12" \
        -name api -passout pass:changeit >/dev/null 2>&1
    export ST_CERTS_DIR="$CERTS_TMP"
    export API_TLS_KEYSTORE="/certs/api-keystore.p12"
    export API_TLS_KEYSTORE_PASSWORD="changeit"
    echo "generated throwaway TLS material in $CERTS_TMP"
else
    echo "openssl not found: TLS checks (G5, G6) will be skipped"
fi

read -r ST_API_HTTP_PORT ST_API_HTTPS_PORT <<<"$(pick_ports)"
export ST_API_HTTP_PORT ST_API_HTTPS_PORT
echo "API host ports: http=$ST_API_HTTP_PORT https=$ST_API_HTTPS_PORT"

echo "== starting prototype stack =="
# Only the services the prototype exercises: the PBX, the database and the
# API. The prototype deliberately keeps ST_MEDIA_ENABLED at its in-memory
# default (no Garage): media behaviour is proven by the Garage Testcontainers
# test (G9) and the API integration tests' InMemoryMediaStore, while the
# production wiring of ST_MEDIA_* is in compose.yaml.
docker compose -f "$COMPOSE_FILE" -p "$PROJECT" up -d --build --wait asterisk db api

for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$ST_API_HTTP_PORT/health" >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
node scripts/test-managed-admin.mjs setup "http://127.0.0.1:$ST_API_HTTP_PORT" "$STATE_FILE"

wait_for_registration_ready() {
    local tries=30
    for _ in $(seq 1 "$tries"); do
        if docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T asterisk asterisk -rx 'pjsip show endpoints' 2>/dev/null | grep -q "Endpoint:  alice"; then
            echo "asterisk ready"
            return 0
        fi
        sleep 2
    done
    echo "asterisk did not become ready" >&2
    return 1
}
wait_for_registration_ready

SIP_DIR="$ROOT_DIR/integration-tests/sip"

echo "== G2: alice registers with digest auth =="
node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 alice "$SIP_PASSWORD"

echo "== G3: two-party call REGISTER-INVITE-200-ACK-BYE =="
CALLEE_LOG="$(mktemp)"
node "$SIP_DIR/callee.cjs" 127.0.0.1 5060 bob "$SIP_PASSWORD" > "$CALLEE_LOG" 2>&1 &
CALLEE_PID=$!
sleep 3
set +e
node "$SIP_DIR/caller.cjs" 127.0.0.1 5060 alice "$SIP_PASSWORD" bob
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

echo "== G3b: video offer negotiates VP8 and a disallowed codec is refused =="
run_video_call() {
    local label="$1" codec="$2" expected="$3" caller_log callee_log callee_pid
    caller_log="$(mktemp)"
    callee_log="$(mktemp)"
    node "$SIP_DIR/callee.cjs" 127.0.0.1 5060 bob "$SIP_PASSWORD" > "$callee_log" 2>&1 &
    callee_pid=$!
    sleep 3
    if ! CALL_VIDEO="$codec" node "$SIP_DIR/caller.cjs" 127.0.0.1 5060 alice "$SIP_PASSWORD" bob > "$caller_log" 2>&1; then
        echo "PROTOTYPE-FAIL: $label call did not complete" >&2
        cat "$caller_log" "$callee_log" >&2
        rm -f "$caller_log" "$callee_log"
        return 1
    fi
    wait "$callee_pid" || true
    if ! grep -q "$expected" "$caller_log"; then
        echo "PROTOTYPE-FAIL: $label expected $expected in the caller SDP answer" >&2
        cat "$caller_log" >&2
        rm -f "$caller_log" "$callee_log"
        return 1
    fi
    rm -f "$caller_log" "$callee_log"
    echo "G3b-${label}-OK"
}
run_video_call VP8 vp8 VIDEO-NEGOTIATED
run_video_call VP9 vp9 VIDEO-REJECTED
echo "G3b-VIDEO-NEGOTIATION-COMPLETE"

echo "== G4: unprovisioned user with wrong password cannot register =="
set +e
node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 mallory definitely-wrong
NEG_EXIT=$?
set -e
if [[ $NEG_EXIT -eq 0 ]]; then
    echo "SECURITY-FAIL: mallory registered with a wrong password" >&2
    exit 1
fi
if docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T asterisk asterisk -rx 'pjsip show contacts' 2>/dev/null | grep -q "mallory"; then
    echo "SECURITY-FAIL: mallory has a registered contact" >&2
    exit 1
fi
echo "unprovisioned registration rejected without a contact"

if [[ -n "$CERTS_TMP" ]]; then
    echo "== G5: alice registers over SIP/TLS on 5061 =="
    if ! docker compose -f "$COMPOSE_FILE" -p "$PROJECT" exec -T asterisk asterisk -rx 'pjsip show transports' 2>/dev/null | grep -q "transport-tls"; then
        echo "TLS-FAIL: transport-tls is not configured in Asterisk" >&2
        exit 1
    fi
    node "$SIP_DIR/reg-tls.cjs" 127.0.0.1 5061 alice "$SIP_PASSWORD"
    echo "G5-TLS-REGISTER-COMPLETE"

    echo "== G6: companion API answers HTTPS on $ST_API_HTTPS_PORT =="
    if command -v curl >/dev/null 2>&1; then
        HEALTH="$(curl -fsS -k "https://127.0.0.1:$ST_API_HTTPS_PORT/health")"
        if [[ "$HEALTH" != *'"status":"ok"'* ]]; then
            echo "TLS-FAIL: unexpected HTTPS health body: $HEALTH" >&2
            exit 1
        fi
        echo "G6-HTTPS-HEALTH-OK"
    else
        echo "curl not found: HTTPS health check skipped"
    fi

    echo "== plain HTTP on $ST_API_HTTP_PORT still works alongside HTTPS =="
    if command -v curl >/dev/null 2>&1; then
        curl -fsS "http://127.0.0.1:$ST_API_HTTP_PORT/health" >/dev/null
        echo "G6-HTTP-HEALTH-OK"
    fi
else
    echo "== G5/G6 skipped: no openssl for throwaway certificates =="
fi

echo "== G7: read receipts are persisted, mutual and idempotent =="
node scripts/test-read-receipts.mjs "http://127.0.0.1:$ST_API_HTTP_PORT" "$STATE_FILE"

echo "== G8: revocation disconnects an established call without manual PBX edits =="
CALLEE_LOG="$(mktemp)"
CALLER_LOG="$(mktemp)"
node "$SIP_DIR/callee.cjs" 127.0.0.1 5060 bob "$SIP_PASSWORD" > "$CALLEE_LOG" 2>&1 &
CALLEE_PID=$!
sleep 2
CALL_HOLD_SECONDS=30 node "$SIP_DIR/caller.cjs" 127.0.0.1 5060 alice "$SIP_PASSWORD" bob > "$CALLER_LOG" 2>&1 &
CALLER_PID=$!
for _ in $(seq 1 80); do
    if grep -q ANSWERED "$CALLER_LOG"; then break; fi
    sleep 0.1
done
if ! grep -q ANSWERED "$CALLER_LOG"; then
    echo "PROTOTYPE-FAIL: no answered call for revocation test" >&2
    exit 1
fi
node scripts/test-managed-admin.mjs revoke "http://127.0.0.1:$ST_API_HTTP_PORT" "$STATE_FILE"
wait "$CALLER_PID"
wait "$CALLEE_PID"
grep -q REVOKED-BYE "$CALLER_LOG"
grep -q BYE-OK "$CALLEE_LOG"
rm -f "$CALLER_LOG" "$CALLEE_LOG"
if node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 bob "$SIP_PASSWORD"; then
    echo "SECURITY-FAIL: revoked account registered" >&2
    exit 1
fi
node scripts/test-managed-admin.mjs reset "http://127.0.0.1:$ST_API_HTTP_PORT" "$STATE_FILE"
if node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 alice "$SIP_PASSWORD"; then
    echo "SECURITY-FAIL: old SIP password registered after reset" >&2
    exit 1
fi
node "$SIP_DIR/reg-inline.cjs" 127.0.0.1 5060 alice "${SIP_PASSWORD}-new"
echo "PROTOTYPE-OK"
if [[ "${1:-}" == "--keep" ]]; then
    echo "Retained test project: $PROJECT"
    echo "Test API URL: http://127.0.0.1:$ST_API_HTTP_PORT"
    echo "Temporary certificate directory: $CERTS_TMP"
    echo "Temporary credential-state file: $STATE_FILE"
fi
