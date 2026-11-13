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

Wireless ADB pairing and connect are available in the Devices page:
- Pair with the temporary pairing address and numeric pairing code shown by Android Wireless Debugging.
- Connect with the separate IP address and connection port shown on the Wireless Debugging screen.
- The pairing code is passed only to the one-time `adb pair` process, is not persisted, and is redacted from returned error text.
- Endpoint input is validated before being passed as individual process arguments; no shell is involved.

Disconnect controls, device install/launch actions, Android companion pairing, task execution, accessibility automation, and screen streaming are not implemented yet.

## CI

GitHub Actions validates the React/TypeScript production build and runs `cargo check` for the Tauri Rust backend on Ubuntu.


### APK 安装

在设备管理页，选择已授权设备旁的「安装 APK」，通过系统文件选择器选择本地 `.apk` 文件。安装命令只使用固定的 `adb -s <serial> install -r <path>` 参数，不经过 Shell；文件扩展名和文件存在性由 Rust 后端再次校验。安装操作最长等待 180 秒，页面会显示 ADB 返回的安装结果。

桌面端使用 Tauri Dialog 插件打开原生文件选择器。当前版本仅提供安装，不会自动卸载应用，也不会自动启动刚安装的 APK；启动按钮仍只针对已配置的《我的养老时光》包名。
