# iOS Helper integration contract

## Components

- `RemoteDeviceRepository`: owner sign-in, registration, heartbeat, credential storage in Keychain.
- `RemoteApprovalView`: explicit per-request Allow / Reject decision; timeout defaults to reject.
- `RemoteSignalingClient`: authenticated WSS and strict signaling envelope parsing.
- `ReplayKitBroadcastExtension`: user-initiated screen broadcast using ReplayKit.
- `AppInteractionAdapter`: actions confined to GamePilot's own app UI.

## Platform boundary

A normal iOS third-party app cannot arbitrarily inject taps or keyboard input into other apps. ReplayKit can broadcast the screen after the user starts the system-mediated broadcast flow, but that does not grant general remote-control privileges. For third-party apps, ship view-only support and optional visual guidance; never advertise arbitrary iOS remote control.

## Security rules

Store device credentials in Keychain, require fresh owner approval for each session, show system broadcast UI/indicators, stop broadcast immediately when the owner ends the session, and fail closed for unsupported control messages.
