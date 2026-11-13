# GamePilot Remote Protocol v0.1

## Product decisions

- Supported Helpers: Android and iOS.
- Every connection requires a fresh, explicit device-owner approval.
- Approval expires after 90 seconds.
- The owner can reject or terminate a session at any time.
- The server is a control plane and signaling relay, not a remote command executor.

## Identity and device registration

A signed-in owner registers a Helper with platform, display name, app version, and a capability list. The server returns a random device credential exactly once. The Helper stores it in Android Keystore or iOS Keychain. Only a peppered hash is persisted by the server.

Capabilities are descriptive and platform-specific, for example:
- Android: `screen.capture`, `accessibility.observe`, `accessibility.actions.basic`
- iOS: `screen.broadcast.replaykit`, `app.interaction`

A capability is not a permission grant. The operating system's permission prompt and the Helper's own approval UI remain authoritative.

## Session state machine

```text
pending --owner approves--> approved --device starts WebRTC--> active --either ends--> ended
   |                              |
   +--owner rejects--> rejected   +--timeout/device offline--> expired
   +--90s timeout----> expired
```

The server must reject signaling unless the session is approved or active. Every state transition is auditable.

## WebRTC topology

- WebSocket is used only for authenticated signaling and lifecycle events.
- WebRTC carries screen video and (where platform policy permits) a DataChannel.
- STUN is attempted first. TURN/TLS is the fallback for restrictive NAT/firewalls.
- Use short-lived TURN credentials; never embed permanent TURN secrets in a Helper.
- Screen media uses WebRTC's DTLS-SRTP encryption.
- The viewer cannot inject platform actions unless the Helper advertises and locally enables the corresponding capability.

## Platform capability contract

### Android Helper
Use MediaProjection with the Android system consent flow for screen capture. Use a user-enabled AccessibilityService only for the explicitly disclosed basic accessibility actions. Do not expose arbitrary shell execution, arbitrary app-private data access, or silent background capture. The owner can stop sharing from the persistent foreground notification/system controls.

### iOS Helper
Use ReplayKit Broadcast Upload Extension and the system-mediated broadcast start flow. The Helper can support its own app UI and application-scoped actions. Third-party iOS apps cannot be remotely clicked or controlled arbitrarily by a normal App Store app. A session can therefore be view-only for other apps.

## DataChannel message envelope

```json
{
  "version": 1,
  "sessionId": "uuid",
  "requestId": "uuid",
  "kind": "input.tap | input.scroll | session.stop",
  "payload": {}
}
```

The Helper must validate session ID, schema, capability, bounds, rate, and current owner-approved session state before acting. Unknown kinds fail closed. Do not implement a generic command or shell message.

## Required production hardening

Cross-account device sharing ACLs, OIDC login UX, short-lived TURN credentials, rate limits, replay protection, reconnect/resume semantics, distributed WebSocket presence, privacy indicators, retention controls, abuse reporting, and security review remain required before public deployment.
