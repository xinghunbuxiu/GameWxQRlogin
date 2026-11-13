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

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![get_adb_snapshot])
        .run(tauri::generate_context!())
        .expect("error while running GamePilot");
}
