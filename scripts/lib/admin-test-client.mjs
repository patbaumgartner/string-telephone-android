import assert from "node:assert/strict";

export class AdminTestClient {
  constructor(base) { this.base = base; this.cookie = ""; this.csrf = ""; }
  async send(path, options = {}) {
    const response = await fetch(`${this.base}${path}`, {
      ...options, redirect: "manual",
      headers: { Cookie: this.cookie, ...(this.csrf ? { "X-CSRF-TOKEN": this.csrf } : {}), ...options.headers },
    });
    const cookie = response.headers.getSetCookie().find(value => value.startsWith("JSESSIONID="));
    if (cookie) this.cookie = cookie.split(";")[0];
    return response;
  }
  async login() {
    const page = await (await this.send("/admin/login")).text();
    this.csrf = page.match(/name="_csrf"[^>]*value="([^"]+)"/)[1];
    const response = await this.send("/admin/login", {
      method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ username: process.env.ADMIN_USERNAME, password: process.env.ADMIN_PASSWORD, _csrf: this.csrf }),
    });
    assert.equal(response.status, 302);
    assert.ok(!response.headers.get("location").includes("error"));
    const home = await this.send("/admin/");
    assert.equal(home.status, 200);
    this.csrf = (await home.text()).match(/name="_csrf"[^>]*value="([^"]+)"/)[1];
  }
  async request(action, body) {
    const response = await this.send(`/v1/admin/${action}`, body === undefined ? {} : {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    assert.ok(response.ok, `Admin ${action} failed: ${response.status} ${await response.clone().text()}`);
    return response.json();
  }
}
