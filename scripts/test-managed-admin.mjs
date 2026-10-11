import assert from "node:assert/strict";
import { readFileSync, writeFileSync } from "node:fs";
import { AdminTestClient } from "./lib/admin-test-client.mjs";

const [mode, base, stateFile] = process.argv.slice(2);
const admin = new AdminTestClient(base);
await admin.login();
const sipPassword = "prototest-robin-maple-meadow-lantern-pebble-willow-scarlet-1234";
if (mode === "setup") {
  const state = {};
  for (const username of ["alice", "bob"]) {
    state[username] = await admin.request("create-user", { username, displayName: username, sipPassword });
    assert.equal(state[username].ready, true, "PBX must be applied before success");
  }
  await admin.request("pbx/settings", { codecs: "ulaw,alaw", ringSeconds: 40, maxContacts: 1, symmetricMedia: true });
  assert.equal((await admin.request("pbx")).settings.ringSeconds, 40);
  writeFileSync(stateFile, JSON.stringify(state), { mode: 0o600 });
  console.log("MANAGED-PROVISIONING-OK");
} else if (mode === "revoke") {
  assert.ok((await admin.request("pbx")).status.activeChannels >= 2, "Positive control: a real two-leg call must be active before revocation");
  await admin.request("revoke-user", { username: "bob" });
  const state = JSON.parse(readFileSync(stateFile, "utf8"));
  assert.equal((await fetch(`${base}/v1/me`, { headers: { Authorization: `Bearer ${state.bob.apiToken}` } })).status, 401);
  const status = await admin.request("pbx");
  assert.ok(!status.status.endpoints.some(endpoint => endpoint.username === "bob"));
  assert.equal(status.status.activeChannels, 0, "Revocation must hang up both legs of the call");
  console.log("MANAGED-REVOCATION-OK");
} else if (mode === "reset") {
  const state = JSON.parse(readFileSync(stateFile, "utf8"));
  const replacement = await admin.request("reset-user", { username: "alice", sipPassword: `${sipPassword}-new` });
  assert.equal(replacement.ready, true, JSON.stringify((await admin.request("pbx")).sync));
  assert.equal((await fetch(`${base}/v1/me`, { headers: { Authorization: `Bearer ${state.alice.apiToken}` } })).status, 401);
  assert.equal((await fetch(`${base}/v1/me`, { headers: { Authorization: `Bearer ${replacement.apiToken}` } })).status, 200);
  console.log("MANAGED-RESET-OK");
} else throw new Error(`Unknown mode ${mode}`);
