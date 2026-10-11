import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

const resource = name => readFileSync(new URL(`../server/api/src/main/resources/${name}`, import.meta.url), "utf8");

test("administration uses server-rendered POST forms with accessible labels and no browser-side credential storage", () => {
  for (const name of ["login", "admin"]) {
    const template = resource(`templates/${name}.html`);
    const forms = [...template.matchAll(/<form\b([^>]*)>/g)];
    assert.ok(forms.length > 0);
    for (const [, attributes] of forms) {
      assert.match(attributes, /th:action=/);
      assert.match(attributes, /method="post"/);
    }
    assert.doesNotMatch(template, /localStorage|sessionStorage|innerHTML|<script/);
    assert.match(template, /name="viewport"/);
    assert.match(template, /<html lang="en"/);
  }
});

test("admin credentials allow long passphrases and managed account actions are exposed", () => {
  const login = resource("templates/login.html");
  const password = login.match(/<input[^>]*id="password"[^>]*>/)[0];
  assert.doesNotMatch(password, /maxlength|pattern/);
  assert.match(password, /autocomplete="current-password"/);
  const admin = resource("templates/admin.html");
  for (const action of ["reset", "enabled", "name", "pbx/reconcile", "pbx/settings"]) {
    assert.ok(admin.includes(action));
  }
  assert.match(admin, /name="sipPassword"[^>]*minlength="24" maxlength="1024"/);
  assert.match(admin, /th:text="\$\{credential\.sipSecret\}"/);
  assert.doesNotMatch(admin, /th:utext/);
});

test("admin styling supports narrow layouts, dark mode, visible keyboard focus and wrapping credentials", () => {
  const css = resource("static/admin/style.css");
  assert.match(css, /max-width: 48rem/);
  assert.match(css, /prefers-color-scheme: dark/);
  assert.match(css, /:focus-visible/);
  assert.match(css, /overflow-wrap: anywhere/);
});

test("login and administration display a responsive footer with the copyright credit", () => {
  const css = resource("static/admin/style.css");
  for (const name of ["login", "admin"]) {
    const template = resource(`templates/${name}.html`);
    assert.match(template, /<footer class="site-footer">/);
    assert.match(template, /© 2026 <a[^>]*>Patrick Baumgartner<\/a>/);
  }
  assert.match(css, /\.site-footer\s*\{[^}]*display:\s*flex/);
  assert.match(css, /\.site-footer\s*\{[^}]*flex-wrap:\s*wrap/);
  assert.match(css, /\.login \.site-footer/);
});

test("HTML input patterns compile under the browser Unicode sets flag and allow hyphenated usernames", () => {
  const admin = resource("templates/admin.html");
  for (const [, pattern] of admin.matchAll(/pattern="([^"]+)"/g)) {
    assert.doesNotThrow(() => new RegExp(pattern, "v"));
  }
  const username = admin.match(/id="new-username"[^>]*pattern="([^"]+)"/)[1];
  const pattern = new RegExp(`^(?:${username})$`, "v");
  assert.equal(pattern.test("alice-one"), true);
  assert.equal(pattern.test("../alice"), false);
});
