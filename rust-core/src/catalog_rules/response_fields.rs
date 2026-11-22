// SPDX-License-Identifier: GPL-3.0-only
use serde_json::Value;
use url::Url;

pub(crate) fn number(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_str()?.parse().ok())
}

pub(crate) fn unsigned(value: &Value) -> Option<u64> {
    number(value)?.try_into().ok()
}

pub(crate) fn boolean(value: &Value) -> Option<bool> {
    value.as_bool().or_else(|| match value.as_str()? {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    })
}

pub(crate) fn string(value: &Value) -> String {
    match value {
        Value::String(s) => s.trim().to_owned(),
        Value::Number(n) => n.to_string(),
        _ => String::new(),
    }
}

pub(crate) fn cover(value: &str) -> String {
    let upgraded = if value.starts_with("//") {
        format!("https:{value}")
    } else {
        value.replacen("http://", "https://", 1)
    };
    Url::parse(&upgraded)
        .ok()
        .filter(|url| {
            url.scheme() == "https"
                && url.host_str().is_some()
                && url.username().is_empty()
                && url.password().is_none()
        })
        .map_or_else(String::new, |url| url.to_string())
}
