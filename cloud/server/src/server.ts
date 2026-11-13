import Fastify, { type FastifyRequest } from "fastify";
import websocket from "@fastify/websocket";
import { createHash, randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import { Pool } from "pg";
import { createRemoteJWKSet, jwtVerify } from "jose";
import { z } from "zod";
import type { RawData } from "ws";

const env = z.object({
  PORT: z.coerce.number().int().positive().default(8787),
  DATABASE_URL: z.string().min(1),
  AUTH_JWKS_URL: z.string().url(),
  AUTH_ISSUER: z.string().url(),
  AUTH_AUDIENCE: z.string().min(1),
  DEVICE_TOKEN_PEPPER: z.string().min(32)
}).parse(process.env);

const db = new Pool({ connectionString: env.DATABASE_URL, max: 10, ssl: process.env.PGSSLMODE === "require" ? { rejectUnauthorized: true } : undefined });
const jwks = createRemoteJWKSet(new URL(env.AUTH_JWKS_URL));
const app = Fastify({ logger: true, bodyLimit: 64 * 1024 });
await app.register(websocket);

type Principal = { kind: "user" | "device"; id: string; ownerId?: string };
type Socket = { principal: Principal; send: (value: unknown) => void; close: () => void };
const sockets = new Set<Socket>();

const hashSecret = (value: string) => createHash("sha256").update(env.DEVICE_TOKEN_PEPPER).update(value).digest("hex");
const secureEqual = (a: string, b: string) => {
  const left = Buffer.from(a, "hex"); const right = Buffer.from(b, "hex");
  return left.length === right.length && timingSafeEqual(left, right);
};

async function authenticate(request: FastifyRequest): Promise<Principal> {
  const header = request.headers.authorization;
  if (!header?.startsWith("Bearer ")) throw Object.assign(new Error("Unauthorized"), { statusCode: 401 });
  const token = header.slice(7);
  if (token.startsWith("gp_dev_")) {
    const result = await db.query("SELECT id, owner_id, device_secret_hash FROM devices WHERE device_secret_hash = $1", [hashSecret(token)]);
    const row = result.rows[0];
    if (!row || !secureEqual(row.device_secret_hash, hashSecret(token))) throw Object.assign(new Error("Unauthorized"), { statusCode: 401 });
    return { kind: "device", id: row.id, ownerId: row.owner_id };
  }
  try {
    const verified = await jwtVerify(token, jwks, { issuer: env.AUTH_ISSUER, audience: env.AUTH_AUDIENCE });
    if (typeof verified.payload.sub !== "string" || !verified.payload.sub) throw new Error("Missing subject");
    return { kind: "user", id: verified.payload.sub };
  } catch {
    throw Object.assign(new Error("Unauthorized"), { statusCode: 401 });
  }
}

async function requirePrincipal(request: FastifyRequest, kind?: Principal["kind"]) {
  const principal = await authenticate(request);
  if (kind && principal.kind !== kind) throw Object.assign(new Error("Forbidden"), { statusCode: 403 });
  return principal;
}
function emit(target: (p: Principal) => boolean, event: unknown) {
  for (const socket of sockets) if (target(socket.principal)) socket.send(event);
}
async function record(sessionId: string, actorId: string, type: string, metadata: object = {}) {
  await db.query("INSERT INTO session_events(session_id, actor_id, event_type, metadata) VALUES($1,$2,$3,$4)", [sessionId, actorId, type, metadata]);
}

app.get("/health", async () => ({ ok: true, service: "gamepilot-remote", version: "0.1.0" }));

app.post("/v1/devices/register", async (request, reply) => {
  const user = await requirePrincipal(request, "user");
  const body = z.object({
    displayName: z.string().trim().min(1).max(80),
    platform: z.enum(["android", "ios"]),
    appVersion: z.string().max(40).optional(),
    capabilities: z.array(z.string().max(40)).max(30).default([])
  }).parse(request.body);
  const id = randomUUID();
  const token = "gp_dev_" + randomBytes(32).toString("base64url");
  await db.query(
    "INSERT INTO devices(id, owner_id, display_name, platform, app_version, device_secret_hash, capabilities, status, last_seen_at) VALUES($1,$2,$3,$4,$5,$6,$7,'online',now())",
    [id, user.id, body.displayName, body.platform, body.appVersion ?? null, hashSecret(token), JSON.stringify(body.capabilities)]
  );
  return reply.code(201).send({ deviceId: id, deviceToken: token, status: "online" });
});

app.get("/v1/devices", async (request) => {
  const user = await requirePrincipal(request, "user");
  const result = await db.query(
    "SELECT id, display_name, platform, app_version, status, capabilities, last_seen_at FROM devices WHERE owner_id=$1 ORDER BY created_at DESC",
    [user.id]
  );
  return { devices: result.rows };
});

app.post("/v1/devices/heartbeat", async (request) => {
  const device = await requirePrincipal(request, "device");
  const body = z.object({ status: z.enum(["online", "busy"]).default("online") }).parse(request.body ?? {});
  await db.query("UPDATE devices SET status=$1,last_seen_at=now(),updated_at=now() WHERE id=$2", [body.status, device.id]);
  return { ok: true };
});

app.post("/v1/devices/:deviceId/invites", async (request, reply) => {
  const user = await requirePrincipal(request, "user");
  const params = z.object({ deviceId: z.string().uuid() }).parse(request.params);
  const body = z.object({ expiresInDays: z.number().int().min(1).max(30).default(7) }).parse(request.body ?? {});
  const deviceResult = await db.query("SELECT id,owner_id FROM devices WHERE id=$1", [params.deviceId]);
  const device = deviceResult.rows[0];
  if (!device || device.owner_id !== user.id) throw Object.assign(new Error("Not found"), { statusCode: 404 });
  const id = randomUUID();
  const token = "gp_inv_" + randomBytes(32).toString("base64url");
  await db.query(
    "INSERT INTO device_invites(id,device_id,created_by,token_hash,expires_at) VALUES($1,$2,$3,$4,now()+($5 * interval '1 day'))",
    [id, params.deviceId, user.id, hashSecret(token), body.expiresInDays]
  );
  return reply.code(201).send({ inviteToken: token, expiresInDays: body.expiresInDays });
});

app.post("/v1/sessions", async (request, reply) => {
  const user = await requirePrincipal(request, "user");
  const body = z.object({ inviteToken: z.string().min(20).max(160), reason: z.string().trim().min(1).max(240) }).parse(request.body);
  const inviteResult = await db.query(
    "SELECT d.id,d.owner_id,d.status FROM device_invites i JOIN devices d ON d.id=i.device_id WHERE i.token_hash=$1 AND i.revoked_at IS NULL AND i.expires_at>now()",
    [hashSecret(body.inviteToken)]
  );
  const device = inviteResult.rows[0];
  if (!device || device.status !== "online") return reply.code(409).send({ error: "DEVICE_UNAVAILABLE" });
  if (device.owner_id === user.id) return reply.code(400).send({ error: "CANNOT_REMOTE_CONNECT_OWN_DEVICE" });
  if (![...sockets].some(p => p.principal.kind === "device" && p.principal.id === device.id)) return reply.code(409).send({ error: "DEVICE_NOT_CONNECTED_TO_SIGNALING" });
  const id = randomUUID();
  await db.query(
    "INSERT INTO remote_sessions(id,device_id,requester_id,status,reason,expires_at) VALUES($1,$2,$3,'pending',$4,now()+interval '90 seconds')",
    [id, body.deviceId, user.id, body.reason]
  );
  await record(id, user.id, "session.requested", { reason: body.reason });
  emit(p => p.kind === "device" && p.id === body.deviceId, { type: "session.requested", sessionId: id, reason: body.reason, expiresInSeconds: 90 });
  return reply.code(201).send({ sessionId: id, status: "pending", expiresInSeconds: 90 });
});

app.post("/v1/sessions/:sessionId/decision", async (request) => {
  const device = await requirePrincipal(request, "device");
  const params = z.object({ sessionId: z.string().uuid() }).parse(request.params);
  const body = z.object({ decision: z.enum(["approve", "reject"]) }).parse(request.body);
  const result = await db.query(
    "SELECT s.id,s.device_id,s.requester_id,s.status,s.expires_at,d.owner_id FROM remote_sessions s JOIN devices d ON d.id=s.device_id WHERE s.id=$1",
    [params.sessionId]
  );
  const session = result.rows[0];
  if (!session || session.device_id !== device.id || session.owner_id !== device.ownerId) throw Object.assign(new Error("Not found"), { statusCode: 404 });
  if (session.status !== "pending" || new Date(session.expires_at).getTime() <= Date.now()) {
    await db.query("UPDATE remote_sessions SET status='expired' WHERE id=$1 AND status='pending'", [params.sessionId]);
    return { status: "expired" };
  }
  const approved = body.decision === "approve";
  await db.query("UPDATE remote_sessions SET status=$1,approved_at=CASE WHEN $2 THEN now() ELSE NULL END WHERE id=$3", [approved ? "approved" : "rejected", approved, params.sessionId]);
  await record(params.sessionId, device.id, approved ? "session.approved" : "session.rejected");
  emit(p => p.kind === "user" && p.id === session.requester_id, { type: approved ? "session.approved" : "session.rejected", sessionId: params.sessionId });
  return { status: approved ? "approved" : "rejected" };
});

app.post("/v1/sessions/:sessionId/end", async (request) => {
  const principal = await requirePrincipal(request);
  const params = z.object({ sessionId: z.string().uuid() }).parse(request.params);
  const result = await db.query(
    "SELECT s.id,s.device_id,s.requester_id,d.owner_id FROM remote_sessions s JOIN devices d ON d.id=s.device_id WHERE s.id=$1",
    [params.sessionId]
  );
  const session = result.rows[0];
  if (!session || (principal.kind === "device" ? principal.id !== session.device_id : principal.id !== session.requester_id && principal.id !== session.owner_id)) {
    throw Object.assign(new Error("Not found"), { statusCode: 404 });
  }
  await db.query("UPDATE remote_sessions SET status='ended',ended_at=now() WHERE id=$1 AND status IN ('approved','active','pending')", [params.sessionId]);
  await record(params.sessionId, principal.id, "session.ended");
  emit(p => p.kind === "device" ? p.id === session.device_id : p.id === session.requester_id, { type: "session.ended", sessionId: params.sessionId });
  return { status: "ended" };
});

app.get("/v1/realtime", { websocket: true }, async (socket, request) => {
  let principal: Principal;
  try { principal = await authenticate(request); }
  catch { socket.close(1008, "unauthorized"); return; }
  const entry: Socket = { principal, send: value => socket.send(JSON.stringify(value)), close: () => socket.close() };
  sockets.add(entry);
  socket.on("close", () => sockets.delete(entry));
  socket.on("message", async (raw: RawData) => {
    try {
      const data = Buffer.isBuffer(raw) ? raw : Array.isArray(raw) ? Buffer.concat(raw) : Buffer.from(raw);
      if (data.length > 48 * 1024) { socket.close(1009, "message too large"); return; }
      const message = z.object({
        type: z.enum(["signal", "session.started", "session.ended"]),
        sessionId: z.string().uuid(),
        payload: z.unknown().optional()
      }).parse(JSON.parse(data.toString("utf8")));
      const result = await db.query(
        "SELECT s.id,s.device_id,s.requester_id,s.status,d.owner_id FROM remote_sessions s JOIN devices d ON d.id=s.device_id WHERE s.id=$1",
        [message.sessionId]
      );
      const session = result.rows[0];
      if (!session) throw new Error("Unknown session");
      const isDevice = principal.kind === "device" && principal.id === session.device_id;
      const isRequester = principal.kind === "user" && principal.id === session.requester_id;
      if (!isDevice && !isRequester) throw new Error("Not a participant");
      if (message.type === "signal") {
        if (!["approved", "active"].includes(session.status)) throw new Error("Session not approved");
        const target = (p: Principal) => isDevice ? p.kind === "user" && p.id === session.requester_id : p.kind === "device" && p.id === session.device_id;
        emit(target, { type: "signal", sessionId: message.sessionId, payload: message.payload });
      } else if (message.type === "session.started") {
        if (!isDevice || session.status !== "approved") throw new Error("Only the approved device can start");
        await db.query("UPDATE remote_sessions SET status='active',started_at=now() WHERE id=$1", [message.sessionId]);
        await record(message.sessionId, principal.id, "session.started");
        emit(p => p.kind === "user" && p.id === session.requester_id, { type: "session.started", sessionId: message.sessionId });
      } else {
        await db.query("UPDATE remote_sessions SET status='ended',ended_at=now() WHERE id=$1", [message.sessionId]);
        await record(message.sessionId, principal.id, "session.ended");
        emit(p => p.kind === "device" ? p.id === session.device_id : p.id === session.requester_id, { type: "session.ended", sessionId: message.sessionId });
      }
    } catch (error) {
      entry.send({ type: "error", code: "INVALID_OR_UNAUTHORIZED_MESSAGE", message: error instanceof Error ? error.message : "Invalid message" });
    }
  });
});

const schema = await readFile(new URL("../schema.sql", import.meta.url), "utf8");
await db.query(schema);
await app.listen({ port: env.PORT, host: "0.0.0.0" });
app.addHook("onClose", async () => { await db.end(); });
