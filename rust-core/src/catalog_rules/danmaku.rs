// SPDX-License-Identifier: GPL-3.0-only
use std::collections::HashSet;

use anyhow::{Context, Result, bail, ensure};
use serde::Serialize;
use serde_json::{Value, json};

use super::{boolean, number, string, unsigned};

pub(crate) const MAX_DURATION: u64 = 86_400_000;

pub(crate) fn valid_window(start: u64, duration: u64) -> bool {
    start < duration && duration <= MAX_DURATION
}

pub(crate) fn danmaku_body(
    series: &str,
    episode: &str,
    start: u64,
    duration: u64,
    cursor: &str,
) -> Value {
    json!({
        "comment_source": 601, "server_channel": 1000, "group_id": episode,
        "group_type": 30, "comment_type": 20, "sort": 1, "count": 90, "cursor": cursor,
        "aid": 8662, "compliance_status": 0,
        "business_param": {"book_id": series, "start_offset_time": start,
            "playlet_item_duration": duration, "need_danmaku_guide_type": [1, 3, 4, 2]},
    })
}

#[derive(Clone, Serialize)]
pub struct Danmaku {
    pub id: String,
    pub text: String,
    pub time_ms: u64,
}

pub(crate) struct DanmakuWindow {
    pub(crate) end_ms: u64,
    #[cfg(feature = "android-jni")]
    pub(crate) raw_count: usize,
    pub(crate) continuation: Option<String>,
    pub(crate) items: Vec<Danmaku>,
}

pub(crate) fn parse_danmaku(
    root: &Value,
    episode: &str,
    start: u64,
    duration: u64,
) -> Result<DanmakuWindow> {
    let data = &root["data"];
    let rows = data["data_list"].as_array().context("弹幕数据格式已变化")?;
    let list = &data["common_list_info"];
    let has_more = boolean(&list["has_more"]);
    let next = &data["extra"]["next_query_danmaku_list_time"];
    let end_ms = match unsigned(next) {
        Some(next) if next > start && next <= MAX_DURATION => next.min(duration),
        Some(0) if has_more == Some(false) => duration,
        None if next.is_null() && has_more == Some(false) => duration,
        _ => bail!("弹幕时间段无效"),
    };
    let continuation = if has_more == Some(true) && end_ms < duration {
        let raw = list["cursor"].as_str().unwrap_or_default();
        ensure!(raw.len() <= 16_384, "弹幕续页格式无效");
        Some(raw.to_owned())
    } else {
        None
    };
    let mut seen = HashSet::new();
    let mut items = Vec::new();
    for row in rows.iter().take(300) {
        let comment = &row["comment"];
        let common = &comment["common"];
        if string(&common["group_id"]) != episode || number(&common["status"]) != Some(1) {
            continue;
        }
        let Some(time_ms) = unsigned(&comment["expand"]["offset_time"])
            .filter(|time| (start..end_ms).contains(time))
        else {
            continue;
        };
        let id: String = string(&comment["comment_id"]).chars().take(120).collect();
        let clean: String = string(&common["content"]["text"])
            .chars()
            .map(|ch| {
                if ch.is_control() || matches!(ch, '\u{2028}' | '\u{2029}') {
                    ' '
                } else {
                    ch
                }
            })
            .collect();
        let text: String = clean.trim().chars().take(180).collect();
        if id.is_empty() || text.is_empty() || !seen.insert(id.clone()) {
            continue;
        }
        items.push(Danmaku { id, text, time_ms });
    }
    items.sort_by_key(|item| item.time_ms);
    items.truncate(90);
    Ok(DanmakuWindow {
        end_ms,
        #[cfg(feature = "android-jni")]
        raw_count: rows.len(),
        continuation,
        items,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timed_comments_are_bound_to_the_requested_episode_and_segment() {
        let row = |id, group, status, offset, text| {
            json!({"comment": {
                "comment_id": id, "common": {"group_id": group, "status": status, "content": {"text": text}},
                "expand": {"offset_time": offset}
            }})
        };
        let root = json!({"data": {
            "extra": {"next_query_danmaku_list_time": 30_000},
            "common_list_info": {"cursor": "next", "has_more": true},
            "data_list": [
                row("later", "123456", 1, 14_000, "第二条"),
                row("wrong", "222222", 1, 13_000, "其他集"),
                row("hidden", "123456", 0, 13_000, "不可显示"),
                row("first", "123456", 1, 11_000, "第一\n条"),
                row("first", "123456", 1, 12_000, "重复"),
                row("long", "123456", 1, 20_000, "已越过总时长"),
                row("before", "123456", 1, 9_999, "已越过起点")
            ]
        }});
        let page = parse_danmaku(&root, "123456", 10_000, 20_000).unwrap();
        assert_eq!(page.end_ms, 20_000);
        assert!(page.continuation.is_none());
        assert_eq!(
            page.items
                .iter()
                .map(|item| item.text.as_str())
                .collect::<Vec<_>>(),
            ["第一 条", "第二条"]
        );
        assert!(parse_danmaku(&root, "123456", 30_000, 35_000).is_err());
        assert_eq!(
            parse_danmaku(&root, "123456", 10_000, 35_000)
                .unwrap()
                .continuation
                .as_deref(),
            Some("next"),
        );
    }

    #[test]
    fn empty_timed_windows_can_continue_and_an_explicit_end_needs_no_next_time() {
        let root = |has_more: bool, extra: Value| {
            json!({"data": {
                "data_list": [], "extra": extra,
                "common_list_info": {"has_more": has_more, "cursor": "{\"session_id\":\"fixture\"}"}
            }})
        };
        let empty = parse_danmaku(
            &root(true, json!({"next_query_danmaku_list_time": 60_000})),
            "123",
            30_000,
            92_000,
        )
        .unwrap();
        assert!(empty.items.is_empty());
        assert_eq!(empty.end_ms, 60_000);
        assert_eq!(
            empty.continuation.as_deref(),
            Some(r#"{"session_id":"fixture"}"#)
        );

        let terminal = parse_danmaku(&root(false, json!({})), "123", 60_000, 92_000).unwrap();
        assert_eq!(terminal.end_ms, 92_000);
        assert!(terminal.continuation.is_none());
        assert!(parse_danmaku(&root(true, json!({})), "123", 60_000, 92_000).is_err());
    }
}
