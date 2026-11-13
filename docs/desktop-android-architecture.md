# Desktop + Android architecture

## Decision

Desktop UI: Tauri 2 + React + TypeScript.
Android execution app: Kotlin.
Device transport: ADB (USB / wireless / emulator).
Screen mirroring/control: scrcpy as an optional desktop-side process, selected by ADB serial.
Optional privilege bridge: Shizuku.
UI automation: AccessibilityService first, screenshot/OCR/image matching for custom-rendered game scenes.
Desktop-to-device control: authenticated WebSocket protocol.

## Responsibilities

### Desktop
- Device inventory and connection status
- Launch/stop one scrcpy window per selected authorized device
- Task creation, scheduling and execution timeline
- Script and game-adapter management
- Live logs and diagnostic bundles
- App update and configuration management

### Android
- Pairing and device identity
- ADB-assisted bootstrap/diagnostics where available
- Accessibility permission status and actions
- Optional Shizuku capability checks
- Screen capture, OCR and visual matching
- Task state machine and action execution

## Safety and reliability boundaries

- The existing WeChat callback/Intent flow remains isolated and unchanged.
- Never expose arbitrary shell execution to JavaScript or remote task payloads.
- Commands are typed, allow-listed and validated on the Android side.
- Every action has a timeout, cancellation path and structured result.
- scrcpy is discovered from an explicit path or PATH; GamePilot never downloads or executes an untrusted binary.
- Destructive actions require explicit user confirmation.
- No automation to evade game account binding, anti-cheat or access controls.

## First milestone

1. Establish desktop app shell and navigation.
2. Connect ADB device inventory and optional scrcpy launch/stop controls.
2. Add Android companion pairing and authenticated heartbeat.
3. Discover ADB devices and show truthful online/offline states.
4. Add one harmless test task: launch the selected app, detect foreground package, record result.
5. Add accessibility and visual automation only after the transport and audit log are stable.
