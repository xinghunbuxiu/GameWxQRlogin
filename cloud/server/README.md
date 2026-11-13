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
- `PORT` (default 8787)

Run `npm install`, `npm run typecheck`, then `npm start`. The service applies `schema.sql` at startup.

## Authentication

User API calls require a bearer JWT issued by the configured identity provider. The server validates issuer, audience, signature, and subject. Device registration is owner-authenticated and returns a one-time device bearer token. Only a peppered SHA-256 hash is stored. Treat the returned token like a password; store it in Android Keystore / iOS Keychain and never log it.

## Approval-first session lifecycle

`pending -> approved -> active -> ended`

Alternative terminal states: `rejected`, `expired`. A request expires after 90 seconds. There is no unattended or silent-connect mode.

1. Owner registers a Helper: `POST /v1/devices/register`.
2. Helper heartbeats: `POST /v1/devices/heartbeat`.
3. Remote user lists owner's devices: `GET /v1/devices` (cross-user discovery/sharing ACLs are intentionally not enabled in this first cut).
4. Authorized requester asks for a session: `POST /v1/sessions`.
5. Device receives `session.requested` over `/v1/realtime` and the owner explicitly approves or rejects via `POST /v1/sessions/:id/decision`.
6. Once approved, participants exchange WebRTC signaling messages over the authenticated WebSocket.
7. Either participant can end the session.

WebSocket messages use JSON envelopes:
- `{ type: "signal", sessionId, payload: { kind: "offer" | "answer" | "ice", data: ... } }`
- `{ type: "session.started", sessionId }`
- `{ type: "session.ended", sessionId }`

The signaling relay forwards opaque SDP/ICE payloads only between the two session participants and only after approval. It does not inspect or store media. WebRTC media encryption is provided by DTLS-SRTP; deploy TURN with authenticated, short-lived credentials before production.

## Important MVP boundaries

- User identity is delegated to OIDC; account creation, recovery, MFA, and organization/team sharing are not implemented here.
- The initial device list is owner-scoped. A production sharing/invitation ACL must be added before remote users can connect to devices belonging to other accounts.
- This initial implementation keeps live WebSocket connections in one process. Multi-instance deployments need Redis/NATS pub-sub and a shared session/connection registry.
- Rate limits, abuse reporting, retention policies, TURN credential issuance, and operational metrics are required before public launch.
- The service intentionally has no generic shell, file browser, arbitrary app-launch, or hidden-control API.
