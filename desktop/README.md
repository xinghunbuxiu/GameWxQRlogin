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

The backend exposes typed Tauri commands for device discovery, wireless pairing/connect/disconnect, device details, APK installation, launching the configured game, and uninstalling that same configured game. Commands use fixed argument arrays and never invoke a shell command string. The backend does not expose arbitrary Shell execution.

Wireless ADB pairing and connect are available in the Devices page:
- Pair with the temporary pairing address and numeric pairing code shown by Android Wireless Debugging.
- Connect with the separate IP address and connection port shown on the Wireless Debugging screen.
- The pairing code is passed only to the one-time `adb pair` process, is not persisted, and is redacted from returned error text.
- Endpoint input is validated before being passed as individual process arguments; no shell is involved.

Device details include Android version, screen resolution, foreground Activity, and the install state/path of the configured game package. The game package is fixed to `com.myretirementtime.wdylsg`; the UI does not accept arbitrary package names. Uninstall is an explicit user action with a confirmation prompt.

## CI

GitHub Actions validates the React/TypeScript production build and runs Rust unit tests with `cargo test` for the Tauri backend on Ubuntu.


### APK 安装

在设备管理页，选择已授权设备旁的「安装 APK」，通过系统文件选择器选择本地 `.apk` 文件。安装命令只使用固定的 `adb -s <serial> install -r <path>` 参数，不经过 Shell；文件扩展名和文件存在性由 Rust 后端再次校验。安装操作最长等待 180 秒，页面会显示 ADB 返回的安装结果。

桌面端使用 Tauri Dialog 插件打开原生文件选择器。当前版本仅提供安装，不会自动卸载应用，也不会自动启动刚安装的 APK；启动按钮仍只针对已配置的《我的养老时光》包名。


### 游戏安装状态与卸载

在设备管理页点击已授权设备的「详情」，后端通过固定包名执行 `pm path com.myretirementtime.wdylsg` 检查游戏是否安装，并显示 APK 路径。只有检测到已安装时才启用「卸载游戏」；卸载前会再次弹出确认，后端只执行固定包名的 `pm uninstall`，最长等待 60 秒。检测命令失败会作为读取错误显示，不会直接伪装成「未安装」。
