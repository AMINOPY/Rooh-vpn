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

test("unknown routes fail closed", async () => {
  const response = await worker.fetch(new Request("https://unit.test/admin"), {});
  assert.equal(response.status, 404);
});
