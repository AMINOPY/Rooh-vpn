const MAX_PROFILE_BYTES = 64 * 1024;
const PROFILE_TTL_SECONDS = 15 * 60;

export function isSafeServer(row) {
  if (!row || typeof row.id !== "string" || !/^[A-Za-z0-9._-]{1,128}$/.test(row.id) || row.id.includes("..")) return false;
  if (typeof row.host !== "string" || row.host.length < 1 || row.host.length > 253 || /[\s\\/@?#%]/.test(row.host)) return false;
  if (!Number.isInteger(row.port) || row.port < 1 || row.port > 65535) return false;
  if (typeof row.region !== "string" || row.region.length < 1 || row.region.length > 80 || /[\x00-\x1f\x7f]/.test(row.region)) return false;
  if (typeof row.engine !== "string" || !/^[A-Za-z0-9_-]{1,40}$/.test(row.engine)) return false;
  if (!Number.isInteger(row.priority) || row.priority < -100000 || row.priority > 100000) return false;
  return true;
}

function json(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      "referrer-policy": "no-referrer",
      ...extraHeaders,
    },
  });
}

function decodeBase64Url(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4);
  return Uint8Array.from(atob(padded), (char) => char.charCodeAt(0));
}

async function verifyAccessToken(request, env) {
  const header = request.headers.get("authorization") || "";
  const match = /^Bearer ([A-Za-z0-9._~-]{20,4096})$/.exec(header);
  if (!match || !env.JWT_HS256_SECRET || env.JWT_HS256_SECRET.length < 32) return null;
  const parts = match[1].split(".");
  if (parts.length !== 3) return null;
  let tokenHeader, claims, signature;
  try {
    tokenHeader = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[0])));
    claims = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[1])));
    signature = decodeBase64Url(parts[2]);
  } catch {
    return null;
  }
  if (tokenHeader.alg !== "HS256" || tokenHeader.typ !== "JWT") return null;
  const now = Math.floor(Date.now() / 1000);
  if (typeof claims.sub !== "string" || claims.sub.length < 1 || claims.sub.length > 128) return null;
  if (!Number.isInteger(claims.exp) || claims.exp <= now || claims.exp > now + 3600) return null;
  if (claims.aud !== "roohvpn-profile") return null;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.JWT_HS256_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  return await crypto.subtle.verify("HMAC", key, signature, new TextEncoder().encode(parts[0] + "." + parts[1])) ? claims : null;
}

async function listServers(env) {
  const result = await env.DB.prepare("SELECT id, host, port, region, engine, priority FROM servers WHERE enabled = 1 ORDER BY priority DESC, id ASC LIMIT 500").all();
  // Project an explicit public schema. Never serialize database rows directly:
  // an accidental private-key/profile column must not leak through discovery.
  const servers = (result.results || []).filter(isSafeServer).map(row => ({
    id: row.id,
    host: row.host,
    port: row.port,
    region: row.region,
    engine: row.engine,
    priority: row.priority,
  }));
  return json({ version: 1, servers });
}

async function issueProfile(request, env, serverId) {
  const claims = await verifyAccessToken(request, env);
  if (!claims) return json({ error: "unauthorized" }, 401, { "www-authenticate": "Bearer" });
  if (!env.PROVISIONER_URL || !env.PROVISIONER_TOKEN) return json({ error: "profile_service_unavailable" }, 503);

  const row = await env.DB.prepare("SELECT id, host, port, region, engine, priority FROM servers WHERE id = ? AND enabled = 1 LIMIT 1").bind(serverId).first();
  if (!row || !isSafeServer(row)) return json({ error: "server_not_found" }, 404);

  let provisionerUrl;
  try {
    provisionerUrl = new URL(env.PROVISIONER_URL);
    if (provisionerUrl.protocol !== "https:" || provisionerUrl.username || provisionerUrl.password) throw new Error();
  } catch {
    return json({ error: "profile_service_unavailable" }, 503);
  }

  let upstream;
  try {
    upstream = await fetch(new URL("/v1/profile", provisionerUrl), {
      method: "POST",
      redirect: "error",
      signal: AbortSignal.timeout(8000),
      headers: { "content-type": "application/json", "authorization": "Bearer " + env.PROVISIONER_TOKEN, "accept": "application/json" },
      body: JSON.stringify({ serverId: row.id, engine: row.engine, subject: claims.sub }),
    });
  } catch {
    return json({ error: "profile_service_unavailable" }, 503);
  }
  if (!upstream.ok) return json({ error: "profile_service_unavailable" }, 503);
  let issued;
  try {
    const raw = await upstream.text();
    if (new TextEncoder().encode(raw).length > MAX_PROFILE_BYTES + 4096) throw new Error();
    issued = JSON.parse(raw);
  } catch {
    return json({ error: "invalid_profile_response" }, 502);
  }
  const norm = value => String(value).toLowerCase().replace(/[_ ]/g, "-");
  if (issued.serverId !== row.id || norm(issued.engine) !== norm(row.engine) || typeof issued.configuration !== "string") return json({ error: "invalid_profile_response" }, 502);
  const configBytes = new TextEncoder().encode(issued.configuration);
  if (configBytes.length < 1 || configBytes.length > MAX_PROFILE_BYTES) return json({ error: "invalid_profile_response" }, 502);
  const now = Math.floor(Date.now() / 1000);
  if (!Number.isInteger(issued.expiresAtEpochSeconds) || issued.expiresAtEpochSeconds <= now) {
    return json({ error: "invalid_profile_response" }, 502);
  }
  // Never extend the provisioner's own credential lifetime.
  const expiresAtEpochSeconds = Math.min(issued.expiresAtEpochSeconds, now + PROFILE_TTL_SECONDS);

  return json({
    version: 1,
    serverId: row.id,
    engine: row.engine,
    expiresAtEpochSeconds,
    configuration: issued.configuration,
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/healthz") return json({ status: "ok", service: "roohvpn-control-plane" });
    if (request.method === "GET" && url.pathname === "/v1/servers") {
      try {
        if (!env.DB) return json({ error: "directory_unavailable" }, 503);
        return await listServers(env);
      } catch {
        return json({ error: "directory_unavailable" }, 503);
      }
    }
    const match = /^\/v1\/servers\/([A-Za-z0-9._-]{1,128})\/profile$/.exec(url.pathname);
    if (request.method === "GET" && match) {
      if (match[1].includes("..")) return json({ error: "not_found" }, 404);
      try {
        if (!env.DB) return json({ error: "profile_service_unavailable" }, 503);
        return await issueProfile(request, env, match[1]);
      } catch {
        return json({ error: "profile_service_unavailable" }, 503);
      }
    }
    return json({ error: "not_found" }, 404);
  },
};
