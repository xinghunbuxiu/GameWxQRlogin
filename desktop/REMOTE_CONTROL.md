# GamePilot Cloud Remote Viewer

The Tauri desktop control center includes a Cloud Remote workspace at the Cloud Remote navigation item.

## Owner flow

1. Enter the HTTPS cloud API URL and an OIDC user access token.
2. Load devices registered to the current account.
3. Select an online device and create a 7-day invite.
4. Share the returned invite token out-of-band with the intended requester. The raw token is shown only once; the invite ID is retained for revocation through the API.

## Requester flow

1. Sign in with an OIDC token and enter the device owner's invite token.
2. The desktop client obtains a one-time 30-second WebSocket ticket and opens WSS before creating the session request.
3. The device owner receives a request showing the opaque requester subject and reason.
4. After the owner approves and the device completes system screen-capture consent, the device emits session.ready.
5. The desktop viewer creates a receive-only RTCPeerConnection, sends an SDP offer, exchanges trickled ICE candidates, and renders the incoming video track.

## Network

- Signaling: HTTPS + WSS.
- Media: WebRTC DTLS-SRTP, P2P when possible.
- ICE servers: fetched from authenticated GET /v1/turn/credentials. The server returns STUN plus short-lived coturn REST credentials when configured.
- If TURN is not configured, the viewer warns that it is using STUN only; connectivity behind symmetric NAT or restrictive firewalls is not guaranteed.

## Limitations

- The user JWT is pasted into the UI and held only in React component state. Replace this with Authorization Code + PKCE before production.
- No TURN server is bundled; operators must deploy coturn and configure TURN_URIS / TURN_SHARED_SECRET.
- No remote input/DataChannel actions are enabled yet. The initial desktop view is screen-viewing only.
- The viewer and Android sender have not yet been verified together on physical devices.
