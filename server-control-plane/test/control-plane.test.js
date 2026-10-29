import test from "node:test";
import assert from "node:assert/strict";
import worker, { isSafeServer } from "../src/index.js";

test("accepts valid server metadata", () => {
  assert.equal(isSafeServer({ id: "eu-1", host: "vpn.example.org", port: 51820, region: "EU", engine: "wireguard", priority: 5 }), true);
});

test("rejects unsafe IDs and traversal", () => {
  for (const id of ["../secret", "a/b", "has space", ".."]) {
    assert.equal(isSafeServer({ id, host: "vpn.example.org", port: 51820, region: "EU", engine: "wireguard", priority: 0 }), false);
  }
});

test("rejects invalid ports, hosts, and engine identifiers", () => {
  const base = { id: "eu-1", host: "vpn.example.org", port: 443, region: "EU", engine: "wireguard", priority: 0 };
  assert.equal(isSafeServer({ ...base, port: 0 }), false);
  assert.equal(isSafeServer({ ...base, port: 65536 }), false);
  assert.equal(isSafeServer({ ...base, host: "vpn.example.org/path" }), false);
  assert.equal(isSafeServer({ ...base, engine: "wireguard;DROP" }), false);
});

test("health endpoint is available without secrets and is not cacheable", async () => {
  const response = await worker.fetch(new Request("https://unit.test/healthz"), {});
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, "ok");
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("profile provisioning requires a bearer token", async () => {
  const response = await worker.fetch(new Request("https://unit.test/v1/servers/eu-1/profile"), {
    DB: { prepare() { throw new Error("DB must not be queried before auth"); } },
  });
  assert.equal(response.status, 401);
  assert.equal(response.headers.get("www-authenticate"), "Bearer");
});

test("unknown routes fail closed", async () => {
  const response = await worker.fetch(new Request("https://unit.test/admin"), {});
  assert.equal(response.status, 404);
});

test("profile endpoint requires a valid bearer token", async () => {
  const db = {
    prepare() {
      return {
        bind() { return this; },
        async first() { return { id: "eu-1", host: "vpn.example.org", port: 51820, region: "EU", engine: "wireguard", priority: 0, enabled: 1 }; },
      };
    },
  };
  const response = await worker.fetch(
    new Request("https://unit.test/v1/servers/eu-1/profile"),
    { DB: db, JWT_HS256_SECRET: "a".repeat(48) },
  );
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "unauthorized");
  assert.equal(response.headers.get("cache-control"), "no-store");
});

test("server directory filters malformed rows and returns metadata only, never a profile", async () => {
  const db = {
    prepare() {
      return {
        async all() {
          return { results: [
            { id: "eu-1", host: "vpn.example.org", port: 51820, region: "EU", engine: "wireguard", priority: 1, configuration: "SHOULD_NOT_LEAK" },
            { id: "../bad", host: "evil.example", port: 443, region: "X", engine: "xray", priority: 1 },
          ] };
        },
      };
    },
  };
  const response = await worker.fetch(new Request("https://unit.test/v1/servers"), { DB: db });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const body = await response.json();
  assert.equal(body.version, 1);
  assert.deepEqual(body.servers.map((server) => server.id), ["eu-1"]);
  assert.equal("configuration" in body.servers[0], false);
});

test("profile endpoint requires a valid bearer token before provisioning", async () => {
  const db = {
    prepare() {
      return {
        bind() { return this; },
        async first() { return { id: "eu-1", host: "vpn.example.org", port: 51820, region: "EU", engine: "wireguard", priority: 0, enabled: 1 }; },
      };
    },
  };
  const response = await worker.fetch(
    new Request("https://unit.test/v1/servers/eu-1/profile"),
    { DB: db, JWT_HS256_SECRET: "a".repeat(48) },
  );
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "unauthorized");
  assert.equal(response.headers.get("cache-control"), "no-store");
});


test("bounded JSON reader accepts a response at the byte limit", async () => {
  const { readBoundedJson } = await import("../src/index.js");
  const body = JSON.stringify({ ok: true });
  const response = new Response(body);
  assert.deepEqual(await readBoundedJson(response, new TextEncoder().encode(body).length), { ok: true });
});

test("bounded JSON reader rejects oversized responses while streaming", async () => {
  const { readBoundedJson } = await import("../src/index.js");
  const response = new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('{"profile":"'));
      controller.enqueue(new Uint8Array(64).fill(65));
      controller.enqueue(new TextEncoder().encode('"}'));
      controller.close();
    },
  }));
  await assert.rejects(() => readBoundedJson(response, 32), /size limit/);
});

test("bounded JSON reader rejects invalid UTF-8 instead of replacement decoding", async () => {
  const { readBoundedJson } = await import("../src/index.js");
  const response = new Response(new Uint8Array([0x7b, 0x22, 0x78, 0x22, 0x3a, 0xff, 0x7d]));
  await assert.rejects(() => readBoundedJson(response, 64));
});
