# Android Helper implementation status

This Helper is being added as an isolated feature inside the existing Java application. The original WeChat OAuth WebView callback and WXEntryActivity intent handoff are not changed.

## Implemented in this branch

- RemoteHelperActivity: explicit enrollment screen, server URL, owner OIDC JWT input, device name, heartbeat, signaling connect/disconnect, and per-request Allow / Reject dialog.
- RemoteApiClient: HTTPS-only requests, no redirects, bounded request timeouts, device registration, heartbeat, one-time WSS ticket, and owner decision API.
- RemoteDeviceStore: device credential encrypted with AES-GCM using a non-exportable Android Keystore key; credentials are not logged and the owner JWT is cleared after registration.
- RemoteSignalingClient: authenticated WSS connection using a 30-second one-time ticket; handles session.requested events.
- MainActivity: separate GamePilot entry in the options menu.

## Current limitations

- Identity provider login is not integrated yet. The enrollment screen currently accepts a JWT from the configured OIDC provider; production should use Authorization Code + PKCE.
- Signaling is connected only while the Helper screen is open. A lifecycle-safe foreground service and reconnect/backoff policy are next.
- After owner approval, the app invokes the Android system MediaProjection consent screen. A non-exported foreground service starts WebRTC ScreenCapturerAndroid and exposes a persistent Stop Sharing notification. The service opens its own WSS ticket, initializes a PeerConnection, emits session.ready only after capture is initialized, answers the viewer SDP offer, and trickles ICE candidates. The viewer receives the screen track over DTLS-SRTP.
- Android 6.0+ is required for AES-GCM Android Keystore credential storage. Existing app features retain their older platform support.

## Security constraints

- Server URLs must use HTTPS; WebSocket URLs are derived as WSS.
- HTTP redirects are disabled to prevent bearer credentials being forwarded to another origin.
- Device credentials are encrypted at rest; the owner access token is held only in memory and cleared after registration.
- Every session request requires a fresh device-side decision. No auto-approval or silent screen capture.
- No arbitrary shell, file browser, or generic remote command API is exposed.

## Next Android milestones

1. Replace pasted owner JWT with OIDC Authorization Code + PKCE.
2. Move signaling into a foreground service with a persistent notification and owner-visible stop action.
3. Add TURN credential issuance and verify P2P plus TURN fallback on real devices and restrictive networks.
4. Add WebRTC video track and optional, allow-listed DataChannel actions.