# GamePilot Remote Server (MVP)

This service is the control plane for Android and iOS Helpers. It does not execute device commands. Device owners approve every remote session.

## Runtime

- Node.js 22+
- PostgreSQL 15+
- An OIDC-compatible identity provider exposing a JWKS endpoint
- HTTPS/WSS at the public edge
- STUN/TURN for WebRTC connectivity (media is peer-to-peer where possible; TURN relays when required)

Configure:

- `DATABASE_URL`
- `AUTH_JWKS_URL`
- `AUTH_ISSUER`
- `AUTH_AUDIENCE`
- `DEVICE_TOKEN_PEPPER` (at least 32 characters; keep stable and secret)
- `TURN_URIS` (comma-separated `turn:` / `turns:` URLs)
- `TURN_SHARED_SECRET` (separate 32+ character coturn REST shared secret)
- `TURN_CREDENTIAL_TTL_SECONDS` (300–86400, default 3600)
- `PORT` (default 8787)

Local container startup:

```bash
cp .env.example .env
# Edit .env and set the real OIDC issuer, JWKS URL, audience, and a random DEVICE_TOKEN_PEPPER.
docker compose up --build
```

The API listens on port 8787. The service applies `schema.sql` at startup. For local development without Docker, run `npm install`, `npm run typecheck`, `npm run build`, then `npm start` with PostgreSQL and the required environment variables available.

## Authentication

User API calls require a bearer JWT issued by the configured identity provider. The server validates issuer, audience, signature, and subject. Device registration is owner-authenticated and returns a one-time device bearer token. Only a peppered SHA-256 hash is stored. Treat the returned token like a password; store it in Android Keystore / iOS Keychain and never log it.

## Approval-first session lifecycle

`pending -> approved -> active -> ended`

Alternative terminal states: `rejected`, `expired`. A pending request expires after 90 seconds. Once approved, the setup window lasts 5 minutes; an active session is capped at 4 hours. There is no unattended or silent-connect mode.

1. Owner registers a Helper: `POST /v1/devices/register`.
2. Helper heartbeats: `POST /v1/devices/heartbeat`.
3. Owner creates a share token: `POST /v1/devices/:deviceId/invites` (1–30 day expiry). The response contains `inviteId` and the raw token (returned once); only its hash is stored. Revoke it with `POST /v1/devices/:deviceId/invites/:inviteId/revoke`.
4. Remote user receives the token out-of-band and asks for a session: `POST /v1/sessions` with `{ inviteToken, reason }`. The device must also have a live signaling connection.
5. Each participant obtains a one-time 30-second WebSocket ticket from `POST /v1/realtime/ticket`, then connects to `/v1/realtime?ticket=...`. Device receives `session.requested` and the owner explicitly approves or rejects via `POST /v1/sessions/:id/decision`.
6. Once approved, participants exchange WebRTC signaling messages over the authenticated WebSocket.
7. Either participant can end the session.

WebSocket messages use JSON envelopes:
- `{ type: "signal", sessionId, payload: { kind: "offer" | "answer" | "ice", data: ... } }`
- `{ type: "session.started", sessionId }`
- `{ type: "session.ended", sessionId }`

The signaling relay forwards opaque SDP/ICE payloads only between the two session participants and only after approval. WebSocket tickets are single-use and expire after 30 seconds, so browser clients do not need to set a non-standard Authorization header. It does not inspect or store media. WebRTC media encryption is provided by DTLS-SRTP; deploy TURN with authenticated, short-lived credentials before production.

## Important MVP boundaries

- User identity is delegated to OIDC; account creation, recovery, MFA, and organization/team sharing are not implemented here.
- Device invitations are bearer links: anyone holding a valid invite can request a session, but cannot connect until the owner approves. Add revocation UI, usage limits, and abuse controls before public launch.
- This initial implementation keeps live WebSocket connections in one process. Multi-instance deployments need Redis/NATS pub-sub and a shared session/connection registry.
- Rate limits, abuse reporting, retention policies, TURN credential issuance, and operational metrics are required before public launch.
- The service intentionally has no generic shell, file browser, arbitrary app-launch, or hidden-control API.
