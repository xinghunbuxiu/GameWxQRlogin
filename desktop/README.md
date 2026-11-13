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


### scrcpy 投屏与控制

设备管理页在每台已授权设备旁提供「投屏控制」。GamePilot 会按设备 serial 启动独立 scrcpy 窗口；再次点击可停止由当前 GamePilot 实例启动的该设备投屏。多个设备可分别启动，不会依赖 ADB 当前默认设备。

安装方式：
- 官方项目与下载： https://github.com/Genymobile/scrcpy
- macOS（Homebrew）：`brew install scrcpy`
- Windows：从官方 Releases 下载并解压，将 scrcpy.exe 所在目录加入 PATH。
- Linux：使用发行版软件包管理器安装，或按官方说明安装。

桌面应用会从 `SCRCPY_PATH`、PATH，以及 macOS/Linux 常见安装目录查找 scrcpy。若使用自定义位置，可将 `SCRCPY_PATH` 设置为 scrcpy 可执行文件完整路径。GamePilot 不会自动下载或静默安装第三方二进制文件。

scrcpy 使用现有 ADB 连接（USB 或 TCP/IP），不要求 Root，也不需要在手机安装常驻 App。当前版本采用独立窗口，不将视频流嵌入 Tauri 页面。关闭 GamePilot 时会尝试结束由本实例启动的 scrcpy 子进程；如果用户自行启动 scrcpy，GamePilot 不会管理或结束它。
