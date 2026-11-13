# GamePilot Desktop

Tauri 2 + React + TypeScript desktop control center.

## Development

```bash
cd desktop
npm install
npm run tauri dev
```

## Build

```bash
npm run tauri build
```

## Architecture boundary

- React: presentation, device/task views, workflow editor and log viewer.
- Tauri/Rust: desktop host, local process lifecycle, secure command boundary.
- Android Kotlin companion: device-side execution, AccessibilityService, optional Shizuku integration, screen capture and OCR.
- ADB: device transport and diagnostics. Shizuku is an optional Android privilege bridge, not an ADB replacement.
- WebSocket protocol: authenticated local control channel between desktop and Android companion.

The initial UI is intentionally a disconnected-state prototype. It does not report fake devices or claim that ADB/automation is connected.