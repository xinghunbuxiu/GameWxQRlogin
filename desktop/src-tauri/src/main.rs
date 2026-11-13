#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::{
    collections::HashMap,
    env,
    io::Read,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    time::Duration,
};
use tauri::State;
use wait_timeout::ChildExt;

const TARGET_GAME_PACKAGE: &str = "com.myretirementtime.wdylsg";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdbDevice {
    serial: String,
    state: String,
    model: Option<String>,
    product: Option<String>,
    device: Option<String>,
    transport_id: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdbDeviceDetails {
    serial: String,
    model: Option<String>,
    android_version: Option<String>,
    screen_resolution: Option<String>,
    foreground_activity: Option<String>,
    game_installed: bool,
    game_apk_path: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdbSnapshot {
    available: bool,
    adb_path: Option<String>,
    version: Option<String>,
    devices: Vec<AdbDevice>,
    error: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ScrcpyStatus {
    available: bool,
    path: Option<String>,
    running_serials: Vec<String>,
}

#[derive(Default)]
struct ScrcpyProcessManager {
    children: Mutex<HashMap<String, Child>>,
}

impl Drop for ScrcpyProcessManager {
    fn drop(&mut self) {
        if let Ok(children) = self.children.get_mut() {
            for child in children.values_mut() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

fn adb_name() -> &'static str {
    if cfg!(target_os = "windows") { "adb.exe" } else { "adb" }
}

fn is_executable_file(path: &Path) -> bool {
    path.is_file()
}

fn scrcpy_name() -> &'static str {
    if cfg!(target_os = "windows") { "scrcpy.exe" } else { "scrcpy" }
}

fn find_scrcpy() -> Option<PathBuf> {
    if let Some(configured) = env::var_os("SCRCPY_PATH") {
        let candidate = PathBuf::from(configured);
        if is_executable_file(&candidate) { return Some(candidate); }
    }
    let name = scrcpy_name();
    if let Some(path) = env::var_os("PATH") {
        for directory in env::split_paths(&path) {
            let candidate = directory.join(name);
            if is_executable_file(&candidate) { return Some(candidate); }
        }
    }
    #[cfg(target_os = "macos")]
    for candidate in ["/opt/homebrew/bin/scrcpy", "/usr/local/bin/scrcpy"] {
        let path = PathBuf::from(candidate);
        if is_executable_file(&path) { return Some(path); }
    }
    #[cfg(target_os = "linux")]
    for candidate in ["/usr/bin/scrcpy", "/usr/local/bin/scrcpy"] {
        let path = PathBuf::from(candidate);
        if is_executable_file(&path) { return Some(path); }
    }
    None
}

fn find_adb() -> Option<PathBuf> {
    let name = adb_name();
    for key in ["ANDROID_SDK_ROOT", "ANDROID_HOME"] {
        if let Some(root) = env::var_os(key) {
            let candidate = PathBuf::from(root).join("platform-tools").join(name);
            if is_executable_file(&candidate) { return Some(candidate); }
        }
    }
    if let Some(path) = env::var_os("PATH") {
        for directory in env::split_paths(&path) {
            let candidate = directory.join(name);
            if is_executable_file(&candidate) { return Some(candidate); }
        }
    }
    None
}

fn run_adb_with_timeout(adb: &Path, args: &[&str], timeout: Duration) -> Result<String, String> {
    let mut child = Command::new(adb)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("无法启动 ADB：{e}"))?;

    // Drain both pipes concurrently so verbose commands (for example dumpsys)
    // cannot block on a full OS pipe while the parent waits for process exit.
    let stdout_reader = child.stdout.take().map(|mut pipe| std::thread::spawn(move || {
        let mut value = String::new();
        let _ = pipe.read_to_string(&mut value);
        value
    }));
    let stderr_reader = child.stderr.take().map(|mut pipe| std::thread::spawn(move || {
        let mut value = String::new();
        let _ = pipe.read_to_string(&mut value);
        value
    }));

    match child.wait_timeout(timeout).map_err(|e| format!("等待 ADB 失败：{e}"))? {
        Some(status) => {
            let stdout = stdout_reader.and_then(|handle| handle.join().ok()).unwrap_or_default();
            let stderr = stderr_reader.and_then(|handle| handle.join().ok()).unwrap_or_default();
            if status.success() { Ok(stdout) }
            else {
                let detail = stderr.trim();
                Err(if detail.is_empty() { format!("ADB 命令退出：{status}") } else { detail.to_string() })
            }
        }
        None => {
            let _ = child.kill();
            let _ = child.wait();
            if let Some(handle) = stdout_reader { let _ = handle.join(); }
            if let Some(handle) = stderr_reader { let _ = handle.join(); }
            Err(format!("ADB 命令超时（{} 秒）", timeout.as_secs()))
        }
    }
}

fn run_adb(adb: &Path, args: &[&str]) -> Result<String, String> {
    run_adb_with_timeout(adb, args, Duration::from_secs(8))
}


#[tauri::command]
fn get_scrcpy_status(manager: State<'_, ScrcpyProcessManager>) -> ScrcpyStatus {
    let running_serials = manager.children.lock().map(|mut children| {
        children.retain(|_, child| child.try_wait().ok().flatten().is_none());
        children.keys().cloned().collect()
    }).unwrap_or_default();
    let path = find_scrcpy();
    ScrcpyStatus {
        available: path.is_some(),
        path: path.map(|value| value.display().to_string()),
        running_serials,
    }
}

#[tauri::command]
fn start_scrcpy(serial: String, manager: State<'_, ScrcpyProcessManager>) -> Result<String, String> {
    validate_device_serial(&serial)?;
    let adb = adb_executable()?;
    let devices_output = run_adb(&adb, &["devices", "-l"])?;
    let authorized = parse_devices(&devices_output).iter()
        .any(|device| device.serial == serial && device.state == "device");
    if !authorized {
        return Err("目标设备当前未处于 ADB 已授权状态，请刷新设备列表后重试。".to_string());
    }
    let scrcpy = find_scrcpy().ok_or_else(|| "未找到 scrcpy。请安装官方 scrcpy，并将其加入 PATH，或设置 SCRCPY_PATH。".to_string())?;

    let mut children = manager.children.lock().map_err(|_| "scrcpy 进程管理器不可用。")?;
    if let Some(child) = children.get_mut(&serial) {
        if child.try_wait().map_err(|error| format!("检查 scrcpy 进程失败：{error}"))?.is_none() {
            return Ok("该设备的 scrcpy 投屏窗口已经运行。".to_string());
        }
    }
    children.remove(&serial);

    let title = format!("GamePilot - {serial}");
    let mut child = Command::new(scrcpy)
        .args(["--serial", &serial, "--window-title", &title])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| format!("启动 scrcpy 失败：{error}"))?;
    std::thread::sleep(Duration::from_millis(700));
    if let Some(status) = child.try_wait().map_err(|error| format!("检查 scrcpy 启动状态失败：{error}"))? {
        return Err(format!("scrcpy 启动后立即退出（{status}）。请检查官方版本、系统图形环境和 ADB 连接。"));
    }
    children.insert(serial.clone(), child);
    Ok(format!("已为设备 {serial} 启动 scrcpy 投屏窗口。"))
}

#[tauri::command]
fn stop_scrcpy(serial: String, manager: State<'_, ScrcpyProcessManager>) -> Result<String, String> {
    validate_device_serial(&serial)?;
    let mut children = manager.children.lock().map_err(|_| "scrcpy 进程管理器不可用。")?;
    if let Some(mut child) = children.remove(&serial) {
        let _ = child.kill();
        let _ = child.wait();
        Ok(format!("已停止设备 {serial} 的 scrcpy 投屏。"))
    } else {
        Ok("该设备当前没有由 GamePilot 启动的 scrcpy 进程。".to_string())
    }
}

fn parse_devices(output: &str) -> Vec<AdbDevice> {
    output.lines().skip_while(|line| !line.starts_with("List of devices")).skip(1)
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            let serial = fields.next()?.to_string();
            let state = fields.next()?.to_string();
            if serial.is_empty() { return None; }
            let mut model = None;
            let mut product = None;
            let mut device = None;
            let mut transport_id = None;
            for field in fields {
                if let Some((key, value)) = field.split_once(':') {
                    match key {
                        "model" => model = Some(value.replace('_', " ")),
                        "product" => product = Some(value.to_string()),
                        "device" => device = Some(value.to_string()),
                        "transport_id" => transport_id = Some(value.to_string()),
                        _ => {}
                    }
                }
            }
            Some(AdbDevice { serial, state, model, product, device, transport_id })
        })
        .collect()
}

#[tauri::command]
fn get_adb_snapshot() -> AdbSnapshot {
    let Some(adb) = find_adb() else {
        return AdbSnapshot {
            available: false,
            adb_path: None,
            version: None,
            devices: vec![],
            error: Some("未找到 ADB。请安装 Android SDK Platform-Tools，或将 adb 所在目录加入 PATH。".to_string()),
        };
    };

    let version = match run_adb(&adb, &["version"]) {
        Ok(value) => value.lines().next().map(str::to_string),
        Err(error) => return AdbSnapshot {
            available: false, adb_path: Some(adb.display().to_string()), version: None,
            devices: vec![], error: Some(error),
        },
    };

    match run_adb(&adb, &["devices", "-l"]) {
        Ok(output) => AdbSnapshot {
            available: true,
            adb_path: Some(adb.display().to_string()),
            version,
            devices: parse_devices(&output),
            error: None,
        },
        Err(error) => AdbSnapshot {
            available: false,
            adb_path: Some(adb.display().to_string()),
            version,
            devices: vec![],
            error: Some(error),
        },
    }
}


fn validate_adb_endpoint(value: &str) -> Result<(), String> {
    if value.is_empty() || value.len() > 255 || value.chars().any(char::is_whitespace) || value.contains("://") {
        return Err("地址格式无效，请填写主机名或 IP:端口。".to_string());
    }
    let (host, port, is_ipv6) = if let Some(rest) = value.strip_prefix('[') {
        let (ipv6, suffix) = rest.split_once(']').ok_or("IPv6 地址缺少右方括号。")?;
        let port = suffix.strip_prefix(':').ok_or("地址必须包含端口。")?;
        if ipv6.parse::<std::net::Ipv6Addr>().is_err() {
            return Err("IPv6 地址格式无效。".to_string());
        }
        (ipv6, port, true)
    } else {
        let (host, port) = value.split_once(':').ok_or("地址必须采用 主机:端口 格式。")?;
        (host, port, false)
    };
    if host.is_empty() || host.len() > 200 || (!is_ipv6 && !host.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')) {
        return Err("主机名格式无效。IPv6 请使用 [地址]:端口 格式。".to_string());
    }
    let port_number = port.parse::<u16>().map_err(|_| "端口必须是 1 到 65535 之间的数字。")?;
    if port_number == 0 { return Err("端口不能为 0。".to_string()); }
    Ok(())
}

fn validate_device_serial(serial: &str) -> Result<(), String> {
    if serial.is_empty() || serial.len() > 255 || serial.starts_with('-')
        || !serial.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | ':' | '-' | '[' | ']')) {
        return Err("设备序列号格式无效。".to_string());
    }
    Ok(())
}

fn validate_apk_extension(path: &Path) -> Result<(), String> {
    if path.extension().and_then(|value| value.to_str()).map(|value| value.eq_ignore_ascii_case("apk")).unwrap_or(false) {
        Ok(())
    } else {
        Err("请选择 .apk 格式的安装文件。".to_string())
    }
}

fn output_value(output: Result<String, String>) -> Option<String> {
    output.ok().map(|value| value.trim().to_string()).filter(|value| !value.is_empty())
}

#[tauri::command]
fn install_target_game(serial: String, apk_path: String) -> Result<String, String> {
    validate_device_serial(&serial)?;
    let path = PathBuf::from(&apk_path);
    validate_apk_extension(&path)?;
    if !path.is_file() {
        return Err("APK 文件不存在或不是普通文件。".to_string());
    }
    let adb = adb_executable()?;
    let path_arg = path.to_str().ok_or("APK 路径不是有效的 UTF-8 路径。")?;
    let output = run_adb_with_timeout(&adb, &["-s", &serial, "install", "-r", path_arg], Duration::from_secs(180))?;
    let message = output.trim();
    if let Some(failure) = message.lines().find(|line| line.trim_start().starts_with("Failure")) {
        return Err(failure.trim().to_string());
    }
    if message.lines().any(|line| line.trim() == "Success") {
        Ok("APK 安装成功。".to_string())
    } else {
        Ok(if message.is_empty() { "ADB 安装命令已完成，请检查设备应用列表。".to_string() } else { message.to_string() })
    }
}

#[tauri::command]
fn launch_target_game(serial: String) -> Result<String, String> {
    validate_device_serial(&serial)?;
    let adb = adb_executable()?;
    let output = run_adb(&adb, &["-s", &serial, "shell", "monkey", "-p", TARGET_GAME_PACKAGE, "1"])?;
    let message = output.trim();
    Ok(if message.is_empty() { "已发送启动《我的养老时光》指令。".to_string() } else { message.to_string() })
}


#[tauri::command]
fn uninstall_target_game(serial: String) -> Result<String, String> {
    validate_device_serial(&serial)?;
    let adb = adb_executable()?;
    let output = run_adb_with_timeout(
        &adb,
        &["-s", &serial, "shell", "pm", "uninstall", TARGET_GAME_PACKAGE],
        Duration::from_secs(60),
    )?;
    let message = output.trim();
    if message.lines().any(|line| line.trim() == "Success") {
        Ok("《我的养老时光》已卸载。".to_string())
    } else {
        Err(if message.is_empty() {
            "卸载命令未返回成功状态，请检查设备。".to_string()
        } else {
            message.to_string()
        })
    }
}

fn is_target_game_installed(output: &str) -> bool {
    output.lines().any(|line| line.trim() == format!("package:{}", TARGET_GAME_PACKAGE))
}

#[tauri::command]
fn get_adb_device_details(serial: String) -> Result<AdbDeviceDetails, String> {
    validate_device_serial(&serial)?;
    let adb = adb_executable()?;
    let model = output_value(run_adb(&adb, &["-s", &serial, "shell", "getprop", "ro.product.model"]));
    let android_version = output_value(run_adb(&adb, &["-s", &serial, "shell", "getprop", "ro.build.version.release"]));
    let size_output = output_value(run_adb(&adb, &["-s", &serial, "shell", "wm", "size"]));
    let screen_resolution = size_output.and_then(|value| value.lines().last().map(|line| line.trim().replace("Physical size: ", "").replace("Override size: ", "")));
    let activity_output = output_value(run_adb(&adb, &["-s", &serial, "shell", "dumpsys", "activity", "activities"]));
    let foreground_activity = activity_output.and_then(|value| value.lines()
        .find(|line| line.contains("mResumedActivity") || line.contains("topResumedActivity"))
        .map(|line| line.trim().to_string()));
    let package_list = run_adb(&adb, &["-s", &serial, "shell", "pm", "list", "packages", TARGET_GAME_PACKAGE])?;
    let game_installed = is_target_game_installed(&package_list);
    let game_apk_path = if game_installed {
        let package_output = run_adb(&adb, &["-s", &serial, "shell", "pm", "path", TARGET_GAME_PACKAGE])?;
        package_output.lines()
            .find_map(|line| line.trim().strip_prefix("package:").map(str::to_string))
    } else {
        None
    };
    Ok(AdbDeviceDetails { serial, model, android_version, screen_resolution, foreground_activity, game_installed, game_apk_path })
}

fn adb_executable() -> Result<PathBuf, String> {
    find_adb().ok_or_else(|| "未找到 ADB。请安装 Android SDK Platform-Tools，并将 adb 加入 PATH。".to_string())
}

#[tauri::command]
fn adb_pair(address: String, pairing_code: String) -> Result<String, String> {
    validate_adb_endpoint(&address)?;
    if pairing_code.len() < 4 || pairing_code.len() > 12 || !pairing_code.chars().all(|c| c.is_ascii_digit()) {
        return Err("配对码应为 4 到 12 位数字。".to_string());
    }
    let adb = adb_executable()?;
    let result = run_adb(&adb, &["pair", &address, &pairing_code]);
    result.map(|output| {
        let text = output.trim();
        if text.is_empty() { "ADB 配对命令已完成，请检查手机上的配对状态。".to_string() } else { text.to_string() }
    }).map_err(|error| error.replace(&pairing_code, "[已隐藏]"))
}

#[tauri::command]
fn adb_connect(address: String) -> Result<String, String> {
    validate_adb_endpoint(&address)?;
    let adb = adb_executable()?;
    run_adb(&adb, &["connect", &address]).map(|output| {
        let text = output.trim();
        if text.is_empty() { "ADB connect 命令已完成。".to_string() } else { text.to_string() }
    })
}

#[tauri::command]
fn adb_disconnect(address: String) -> Result<String, String> {
    validate_adb_endpoint(&address)?;
    let adb = adb_executable()?;
    run_adb(&adb, &["disconnect", &address]).map(|output| {
        let text = output.trim();
        if text.is_empty() { "ADB disconnect 命令已完成。".to_string() } else { text.to_string() }
    })
}


#[cfg(test)]
mod tests {
    use super::{is_target_game_installed, validate_adb_endpoint, TARGET_GAME_PACKAGE};

    #[test]
    fn validates_device_serials_without_shell_metacharacters() {
        assert!(super::validate_device_serial("192.168.1.20:5555").is_ok());
        assert!(super::validate_device_serial("R58M123ABC").is_ok());
        assert!(super::validate_device_serial("-s").is_err());
        assert!(super::validate_device_serial("device;whoami").is_err());
        assert!(super::validate_device_serial("device name").is_err());
    }

    #[test]
    fn target_game_package_is_fixed() {
        assert_eq!(TARGET_GAME_PACKAGE, "com.myretirementtime.wdylsg");
    }

    #[test]
    fn detects_only_exact_target_game_package() {
        assert!(is_target_game_installed("package:com.myretirementtime.wdylsg\n"));
        assert!(!is_target_game_installed(""));
        assert!(!is_target_game_installed("package:com.myretirementtime.wdylsg.debug\n"));
        assert!(!is_target_game_installed("package:com.other.game\n"));
    }

    #[test]
    fn accepts_only_apk_file_extensions() {
        assert!(super::validate_apk_extension(std::path::Path::new("/tmp/game.apk")).is_ok());
        assert!(super::validate_apk_extension(std::path::Path::new("/tmp/game.APK")).is_ok());
        assert!(super::validate_apk_extension(std::path::Path::new("/tmp/game.zip")).is_err());
        assert!(super::validate_apk_extension(std::path::Path::new("/tmp/game")).is_err());
    }

    #[test]
    fn accepts_ipv4_and_hostname_endpoints() {
        assert!(validate_adb_endpoint("192.168.1.20:37123").is_ok());
        assert!(validate_adb_endpoint("android-device.local:5555").is_ok());
    }

    #[test]
    fn accepts_bracketed_ipv6_endpoint() {
        assert!(validate_adb_endpoint("[2001:db8::1]:5555").is_ok());
    }

    #[test]
    fn rejects_missing_or_invalid_ports() {
        assert!(validate_adb_endpoint("192.168.1.20").is_err());
        assert!(validate_adb_endpoint("192.168.1.20:0").is_err());
        assert!(validate_adb_endpoint("192.168.1.20:abc").is_err());
    }

    #[test]
    fn rejects_shell_like_or_url_input() {
        assert!(validate_adb_endpoint("host:5555;whoami").is_err());
        assert!(validate_adb_endpoint("tcp://host:5555").is_err());
        assert!(validate_adb_endpoint("host name:5555").is_err());
    }

    #[test]
    fn rejects_unbracketed_ipv6() {
        assert!(validate_adb_endpoint("2001:db8::1:5555").is_err());
    }
}

fn main() {
    tauri::Builder::default()
        .manage(ScrcpyProcessManager::default())
        .plugin(tauri_plugin_dialog::init())
        .invoke_handler(tauri::generate_handler![get_adb_snapshot, get_scrcpy_status, start_scrcpy, stop_scrcpy, adb_pair, adb_connect, adb_disconnect, get_adb_device_details, launch_target_game, install_target_game, uninstall_target_game])
        .run(tauri::generate_context!())
        .expect("error while running GamePilot");
}
