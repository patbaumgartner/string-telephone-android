import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createServer } from "node:http";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const exec = promisify(execFile);
const command = fileURLToPath(new URL("../server/api/build/install/api/bin/stringtelephone-provision", import.meta.url));
const passphrase = "fixture-robin-maple-meadow-lantern-pebble-willow-scarlet-1234";

test("installed CLI authenticates and provisions through the running API, never an independent database writer", async t => {
  const users = [];
  const requests = [];
  let pending = false;
  const server = createServer(async (request, response) => {
    let body = "";
    for await (const chunk of request) body += chunk;
    requests.push({ url: request.url, body, headers: request.headers });
    if (request.url === "/admin/login" && request.method === "POST") {
      const form = new URLSearchParams(body);
      assert.equal(form.get("username"), "operator");
      assert.equal(form.get("password"), passphrase);
      response.writeHead(302, { Location: "/admin/", "Set-Cookie": "JSESSIONID=fixture; Path=/" });
      response.end();
    } else if (request.url === "/admin/login" || request.url === "/admin/") {
      response.end('<form><input name="_csrf" type="hidden" value="fixture-csrf"></form>');
    } else {
      assert.equal(request.headers["x-csrf-token"], request.method === "POST" ? "fixture-csrf" : undefined);
      assert.match(request.headers.cookie ?? "", /JSESSIONID/);
      response.setHeader("Content-Type", "application/json");
      const account = body ? JSON.parse(body) : null;
      if (request.url === "/v1/admin/list-users") response.end(JSON.stringify({ users }));
      else if (request.url === "/v1/admin/create-user") {
        users.push({ ...account, active: true });
        response.end(JSON.stringify({ ...account, apiToken: "st_fixture", sipSecret: "fixture-sip", ready: !pending }));
      } else if (request.url === "/v1/admin/revoke-user") {
        users.find(user => user.username === account.username).active = false;
        response.end(JSON.stringify({ username: account.username, revoked: true }));
      } else {
        response.writeHead(404);
        response.end("{}");
      }
    }
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const env = { ...process.env, API_URL: `http://127.0.0.1:${server.address().port}`, ADMIN_USERNAME: "operator", ADMIN_PASSWORD: passphrase };
  const run = (...args) => exec(command, args, { env, timeout: 30000 });
  const created = await run("create-user", "alice", "Alice");
  assert.match(created.stdout, /api_token=st_fixture/);
  assert.match(created.stdout, /PBX configured and verified/);
  assert.match((await run("list-users")).stdout, /alice\tAlice\tactive=true/);
  assert.match((await run("revoke-user", "alice")).stdout, /revoked alice/);
  assert.match((await run("list-users")).stdout, /active=false/);
  pending = true;
  await assert.rejects(run("create-user", "bob", "Bob"), error => {
    assert.equal(error.code, 1);
    assert.match(error.stdout, /sip_secret=fixture-sip/);
    assert.match(error.stderr, /provisioning is pending/);
    return true;
  });
  assert.ok(requests.some(request => request.url === "/v1/admin/create-user"));
});

test("installed CLI rejects missing arguments and missing admin credentials explicitly", async () => {
  for (const args of [[], ["create-user"], ["revoke-user"]]) {
    await assert.rejects(exec(command, args), error => {
      assert.equal(error.code, 2);
      assert.match(error.stdout, /Usage:/);
      return true;
    });
  }
  await assert.rejects(exec(command, ["list-users"], { env: { ...process.env, ADMIN_USERNAME: "", ADMIN_PASSWORD: "" } }), error => {
    assert.equal(error.code, 1);
    assert.match(error.stderr, /ADMIN_USERNAME and ADMIN_PASSWORD are required/);
    return true;
  });
});
