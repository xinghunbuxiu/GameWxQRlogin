#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::{
    env,
    io::Read,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::Duration,
};
use wait_timeout::ChildExt;

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
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct AdbDeviceDetails {
    serial: String,
    model: Option<String>,
    android_version: Option<String>,
    screen_resolution: Option<String>,
    foreground_activity: Option<String>,
}

struct AdbSnapshot {
    available: bool,
    adb_path: Option<String>,
    version: Option<String>,
    devices: Vec<AdbDevice>,
    error: Option<String>,
}

fn adb_name() -> &'static str {
    if cfg!(target_os = "windows") { "adb.exe" } else { "adb" }
}

fn is_executable_file(path: &Path) -> bool {
    path.is_file()
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

fn run_adb(adb: &Path, args: &[&str]) -> Result<String, String> {
    let mut child = Command::new(adb)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("无法启动 ADB：{e}"))?;

    match child.wait_timeout(Duration::from_secs(8)).map_err(|e| format!("等待 ADB 失败：{e}"))? {
        Some(status) => {
            let mut stdout = String::new();
            let mut stderr = String::new();
            if let Some(mut pipe) = child.stdout.take() { let _ = pipe.read_to_string(&mut stdout); }
            if let Some(mut pipe) = child.stderr.take() { let _ = pipe.read_to_string(&mut stderr); }
            if status.success() { Ok(stdout) }
            else {
                let detail = stderr.trim();
                Err(if detail.is_empty() { format!("ADB 命令退出：{status}") } else { detail.to_string() })
            }
        }
        None => {
            let _ = child.kill();
            let _ = child.wait();
            Err("ADB 命令超时（8 秒）".to_string())
        }
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

fn output_value(output: Result<String, String>) -> Option<String> {
    output.ok().map(|value| value.trim().to_string()).filter(|value| !value.is_empty())
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
    Ok(AdbDeviceDetails { serial, model, android_version, screen_resolution, foreground_activity })
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
    use super::validate_adb_endpoint;

    #[test]
    #[test]
    fn validates_device_serials_without_shell_metacharacters() {
        assert!(super::validate_device_serial("192.168.1.20:5555").is_ok());
        assert!(super::validate_device_serial("R58M123ABC").is_ok());
        assert!(super::validate_device_serial("-s").is_err());
        assert!(super::validate_device_serial("device;whoami").is_err());
        assert!(super::validate_device_serial("device name").is_err());
    }

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
        .invoke_handler(tauri::generate_handler![get_adb_snapshot, adb_pair, adb_connect, adb_disconnect, get_adb_device_details])
        .run(tauri::generate_context!())
        .expect("error while running GamePilot");
}
