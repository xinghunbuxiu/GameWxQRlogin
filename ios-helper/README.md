# GamePilot iOS Helper — ReplayKit extension scaffold

This directory contains the first native iOS Helper source: Keychain credential storage, HTTPS API client, approval UI, WSS request listener, ReplayKit picker, and Broadcast Upload Extension lifecycle. It is not yet a complete Xcode project or a working WebRTC sender.

## Xcode target setup

1. Create an iOS app target named GamePilotHelper (deployment target iOS 15 or later).
2. Add a Broadcast Upload Extension target named GamePilotBroadcast.
3. Replace the generated extension SampleHandler.swift and Info.plist with the files in ReplayKitBroadcast/.
4. Add the same App Group capability to both targets and replace the placeholder App Group identifier in SampleHandler.swift and the extension bundle identifier in RemoteHelperView.swift.
5. Sign both targets with the same team. Do not embed signing credentials in the repository.

## Current behavior

- ReplayKit owns the system-mediated broadcast start/stop UI.
- The extension records only broadcast lifecycle state in the shared App Group defaults.
- It receives video sample buffers but deliberately does not persist or transmit them yet.
- No audio is forwarded.
- No third-party app control is provided.

## Next implementation

1. Replace the pasted owner JWT with OIDC Authorization Code + PKCE in the containing app; the device credential is already stored in Keychain.
2. Implement the same one-time WSS ticket protocol as Android.
3. Add a reviewed WebRTC iOS dependency and create an RTCVideoSource / RTCVideoTrack adapter for ReplayKit sample buffers.
4. Add owner approval UI before presenting the ReplayKit broadcast picker.
5. Test broadcast interruption, app/extension termination, lock screen behavior, and TURN fallback.

A normal third-party iOS app cannot inject arbitrary taps or keyboard events into other apps. iOS remote sessions must be advertised as screen viewing plus app-scoped interaction, not unrestricted remote control.