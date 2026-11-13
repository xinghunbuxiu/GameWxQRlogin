# Android Helper integration contract

This directory documents the next native Android implementation step. It deliberately does not modify the already-working WeChat OAuth callback flow.

## Components

- `RemoteDeviceRepository`: register device, persist device credential in Android Keystore, heartbeat, unregister/revoke.
- `RemoteApprovalService`: foreground notification and in-app approval UI; default deny on timeout or process restart.
- `RemoteSignalingClient`: authenticated WSS connection and strict message parsing.
- `RemoteWebRtcSession`: WebRTC peer connection, screen video track, optional DataChannel.
- `ScreenCaptureController`: MediaProjection consent and lifecycle; never start capture without the OS consent result.
- `AccessibilityActionAdapter`: optional, separately disclosed, allow-listed basic actions only; no arbitrary shell bridge.

## Lifecycle

The Helper connects to the cloud only after owner sign-in and device registration. A request is shown with requester identity, reason, and an explicit Allow / Reject choice. Approval is single-session and expires. Screen capture then invokes the Android system consent flow if not already authorized for that capture session. The persistent foreground notification provides a stop action.

## Security rules

No auto-approval, no hidden capture, no arbitrary ADB/shell, no generic JavaScript bridge, no bypass of Android permission prompts. The existing game OAuth callback and Activity navigation must remain untouched.
