use hbb_common::{
    anyhow::{anyhow, Context},
    config::Config,
    log,
    ResultType,
};

#[cfg(target_os = "android")]
use std::collections::HashMap;

#[cfg(target_os = "android")]
const ROBOT_BASE_PROPERTIES_PATH: &str = "/sdcard/robot/config/base.properties";

//#region Android base.properties 读取/写入
#[cfg(target_os = "android")]
fn parse_properties(content: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for raw in content.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            let k = k.trim();
            let v = v.trim();
            if !k.is_empty() {
                map.insert(k.to_owned(), v.to_owned());
            }
        }
    }
    map
}

#[cfg(target_os = "android")]
fn read_properties_file() -> ResultType<(String, HashMap<String, String>)> {
    let content = std::fs::read_to_string(ROBOT_BASE_PROPERTIES_PATH)
        .with_context(|| format!("read {}", ROBOT_BASE_PROPERTIES_PATH))?;
    let map = parse_properties(&content);
    Ok((content, map))
}

#[cfg(target_os = "android")]
fn set_or_insert_line(lines: &mut Vec<String>, key: &str, value: &str) {
    let mut updated = false;
    for line in lines.iter_mut() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        if let Some((k, _)) = trimmed.split_once('=') {
            if k.trim() == key {
                *line = format!("{}={}", key, value);
                updated = true;
                break;
            }
        }
    }
    if !updated {
        lines.push(format!("{}={}", key, value));
    }
}

#[cfg(target_os = "android")]
pub fn write_export_serial_number_manual(new_id: &str) -> ResultType<()> {
    let new_id = new_id.trim();
    if new_id.is_empty() {
        return Err(anyhow!("export_serial_number_manual is empty"));
    }

    let original = std::fs::read_to_string(ROBOT_BASE_PROPERTIES_PATH).unwrap_or_default();
    let mut lines: Vec<String> = if original.is_empty() {
        Vec::new()
    } else {
        original.lines().map(|s| s.to_owned()).collect()
    };

    set_or_insert_line(&mut lines, "export_serial_number_manual", new_id);

    let mut out = lines.join("\n");
    if !out.ends_with('\n') {
        out.push('\n');
    }

    std::fs::write(ROBOT_BASE_PROPERTIES_PATH, out)
        .with_context(|| format!("write {}", ROBOT_BASE_PROPERTIES_PATH))?;
    Ok(())
}
//#endregion

//#region Android 设备ID/UUID 统一入口
/// 获取 Android 的“有效设备ID”（优先 export_serial_number，其次 export_serial_number_manual）。
///
/// 返回值不会写入 Config，只负责读取。
#[cfg(target_os = "android")]
pub fn get_effective_device_id_from_robot_properties() -> Option<String> {
    let content = std::fs::read_to_string(ROBOT_BASE_PROPERTIES_PATH).ok()?;
    let map = parse_properties(&content);

    let get_non_empty = |k: &str| map.get(k).map(|s| s.trim()).filter(|s| !s.is_empty());

    if let Some(v) = get_non_empty("export_serial_number") {
        return Some(v.to_owned());
    }
    if let Some(v) = get_non_empty("export_serial_number_manual") {
        return Some(v.to_owned());
    }
    None
}

/// 尝试将 base.properties 中的有效设备ID 应用到运行时 Config。
///
/// - 若存在 export_serial_number => 使用它
/// - 否则若存在 export_serial_number_manual => 使用它
/// - 否则不修改 Config
#[cfg(target_os = "android")]
pub fn try_apply_effective_device_id_to_config() {
    if let Some(id) = get_effective_device_id_from_robot_properties() {
        if !id.is_empty() {
            log::info!("Apply device id from robot properties: {}", id);
            Config::set_id(&id);
            return;
        }
    }
}

/// Android 平台的 UUID 直接等同当前运行时 ID（bytes）。
#[cfg(target_os = "android")]
pub fn get_android_uuid_bytes_from_id() -> Vec<u8> {
    Config::get_id().into_bytes()
}

/// Android 平台的 UUID 直接等同当前运行时 ID（base64）。
#[cfg(target_os = "android")]
pub fn get_android_uuid_b64_from_id() -> String {
    crate::encode64(Config::get_id().into_bytes())
}
//#endregion
