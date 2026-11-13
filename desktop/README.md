# GamePilot Desktop

Tauri 2 + React + TypeScript desktop control center.

## Development

```bash
cd desktop
npm install
npm run dev
```

Run the Tauri desktop shell:

```bash
cd desktop
npm run tauri dev
```

## ADB device discovery

The desktop backend searches for `adb` in this order:

1. `ANDROID_SDK_ROOT/platform-tools`
2. `ANDROID_HOME/platform-tools`
3. Directories in `PATH`

Install Android SDK Platform-Tools and ensure the Android device has USB debugging enabled. For a first USB connection, confirm the RSA debugging authorization prompt on the phone.

The current backend exposes one typed Tauri command, `get_adb_snapshot`. It runs only `adb version` and `adb devices -l`, with an 8-second process timeout. It does not expose arbitrary shell execution, install, launch, or device mutation commands.

Wireless pairing/connect, device actions, Android companion pairing, task execution, accessibility automation, and screen streaming are not implemented yet.

## CI

GitHub Actions validates the React/TypeScript production build and runs `cargo check` for the Tauri Rust backend on Ubuntu.
