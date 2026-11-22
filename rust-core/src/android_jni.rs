// SPDX-License-Identifier: GPL-3.0-only
use std::collections::BTreeMap;

use anyhow::{Context, Result, ensure};
use jni::{
    JNIEnv,
    objects::{JIntArray, JObject, JString},
    sys::{jboolean, jint, jlong, jlongArray, jstring},
};
use serde::Deserialize;
use serde_json::{Value, json};

use crate::catalog_rules::{
    danmaku::{danmaku_body, parse_danmaku, valid_window},
    discovery::{DiscoveryPosition, parse_discovery, selection_body, valid_discovery_filters},
    number,
};
use crate::range::{ByteRange, RangeError, validate_response};
use crate::{Rendition, resume_target, select_rendition};

#[derive(Deserialize)]
#[serde(tag = "operation", rename_all = "snake_case")]
enum CatalogCall {
    DiscoveryRequest {
        filters: BTreeMap<String, String>,
        cursor: Option<DiscoveryPosition>,
    },
    DiscoveryPage {
        cursor: Option<DiscoveryPosition>,
    },
    DanmakuRequest {
        series: String,
        episode: String,
        start: u64,
        duration: u64,
        cursor: String,
    },
    DanmakuPage {
        series: String,
        episode: String,
        start: u64,
        duration: u64,
    },
}

fn catalog_value(arguments: &str, response: &str) -> Result<Value> {
    ensure!(arguments.len() <= 128 * 1024, "目录请求参数过大");
    ensure!(response.len() <= 2 * 1024 * 1024, "目录响应过大");
    let request =
        serde_json::from_str(arguments).map_err(|_| anyhow::anyhow!("目录请求参数无效"))?;
    let body = || -> Result<Value> {
        let root: Value =
            serde_json::from_str(response).map_err(|_| anyhow::anyhow!("目录响应格式异常"))?;
        ensure!(number(&root["code"]) == Some(0), "目录服务暂不可用");
        Ok(root)
    };
    Ok(match request {
        CatalogCall::DiscoveryRequest { filters, cursor } => {
            ensure!(valid_discovery_filters(&filters), "筛选条件无效");
            ensure!(
                cursor.as_ref().is_none_or(DiscoveryPosition::is_valid),
                "分类续页信息异常，请刷新重试"
            );
            selection_body(&filters, cursor.as_ref())
        }
        CatalogCall::DiscoveryPage { cursor } => {
            ensure!(
                cursor.as_ref().is_none_or(DiscoveryPosition::is_valid),
                "分类续页信息异常，请刷新重试"
            );
            serde_json::to_value(parse_discovery(&body()?, cursor.as_ref())?)
                .context("分类结果序列化失败")?
        }
        CatalogCall::DanmakuRequest {
            series,
            episode,
            start,
            duration,
            cursor,
        } => {
            ensure!(
                crate::valid_id(&series)
                    && crate::valid_id(&episode)
                    && valid_window(start, duration)
                    && cursor.len() <= 16_384,
                "弹幕请求范围无效"
            );
            danmaku_body(&series, &episode, start, duration, &cursor)
        }
        CatalogCall::DanmakuPage {
            series,
            episode,
            start,
            duration,
        } => {
            ensure!(
                crate::valid_id(&series)
                    && crate::valid_id(&episode)
                    && valid_window(start, duration),
                "弹幕请求范围无效"
            );
            let page = parse_danmaku(&body()?, &episode, start, duration)?;
            let items: Vec<_> = page
                .items
                .iter()
                .map(|item| {
                    json!({
                        "id": item.id, "text": item.text, "timeMS": item.time_ms,
                    })
                })
                .collect();
            json!({
                "rawCount":page.raw_count, "nextCursor":page.continuation.as_deref().unwrap_or_default(),
                "hasMore":page.continuation.is_some(), "seriesID":series, "episodeID":episode,
                "startMS":start, "nextMS":page.end_ms, "durationMS":duration, "items":items,
            })
        }
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeCatalog(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    arguments: JString<'_>,
    response: JString<'_>,
) -> jstring {
    let Ok(arguments) = env.get_string(&arguments) else {
        return std::ptr::null_mut();
    };
    let Ok(response) = env.get_string(&response) else {
        return std::ptr::null_mut();
    };
    let arguments: String = arguments.into();
    let response: String = response.into();
    let output = match catalog_value(&arguments, &response) {
        Ok(value) => json!({"value":value}),
        Err(error) => json!({"error":error.to_string()}),
    };
    env.new_string(output.to_string())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeResume(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    count: jint,
    matched: jint,
    position: jlong,
    completed: jboolean,
) -> jlongArray {
    let Some(target) = resume_target(
        count.max(0) as usize,
        usize::try_from(matched).ok(),
        position,
        completed != 0,
    ) else {
        return std::ptr::null_mut();
    };
    let Ok(result) = env.new_long_array(2) else {
        return std::ptr::null_mut();
    };
    if env
        .set_long_array_region(&result, 0, &[target.index as jlong, target.position])
        .is_err()
    {
        return std::ptr::null_mut();
    }
    result.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeSelect(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    packed: JIntArray<'_>,
    maximum: jint,
) -> jint {
    let Ok(length) = env.get_array_length(&packed) else {
        return -1;
    };
    if length % 2 != 0 || !(0..=4096).contains(&length) {
        return -1;
    }
    let mut values = vec![0; length as usize];
    if env.get_int_array_region(&packed, 0, &mut values).is_err() {
        return -1;
    }
    let renditions: Vec<_> = values
        .chunks_exact(2)
        .map(|entry| Rendition {
            quality: entry[0],
            avc: entry[1] & 1 != 0,
            supported: entry[1] & 2 != 0,
        })
        .collect();
    select_rendition(&renditions, maximum).map_or(-1, |index| index as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeRangeTotal(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    header: JString<'_>,
    start: jlong,
    end: jlong,
    expected: jlong,
) -> jlong {
    if start < 0 || end < start || expected < 0 {
        return -1;
    }
    let Ok(header) = env.get_string(&header) else {
        return -1;
    };
    let header: String = header.into();
    let range = ByteRange {
        start: start as u64,
        end: end as u64,
    };
    match validate_response(&header, range, (expected > 0).then_some(expected as u64)) {
        Ok((_, size)) => jlong::try_from(size).unwrap_or(-1),
        Err(RangeError::Invalid) => -1,
        Err(RangeError::Mismatch) => -2,
        Err(RangeError::Changed) => -3,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn checks_catalog_call_scope_and_rejects_invalid_replies() {
        let request = r#"{"operation":"discovery_request","filters":{"gender":"0"},
            "cursor":{"offset":24,"page":2,"session":"fixture"}}"#;
        let payload = catalog_value(request, "").unwrap();
        assert_eq!(payload["select_items"]["gender"], json!(["0"]));
        assert_eq!(payload["session_id"], "fixture");
        assert!(catalog_value(&request.replace("gender", "unknown"), "").is_err());
        assert!(catalog_value(&request.replace("\"page\":2", "\"page\":1"), "").is_err());

        let parse = r#"{"operation":"danmaku_page","series":"123456","episode":"234567","start":30000,"duration":90000}"#;
        let response =
            r#"{"code":0,"data":{"data_list":[],"common_list_info":{"has_more":false}}}"#;
        let output = catalog_value(parse, response).unwrap();
        assert_eq!(output["nextMS"], 90000);
        assert_eq!(output["durationMS"], 90000);
        assert_eq!(output["hasMore"], false);
        assert!(catalog_value(parse, &response.replace("\"code\":0", "\"code\":1")).is_err());
    }
}
