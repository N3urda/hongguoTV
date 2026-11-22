// SPDX-License-Identifier: GPL-3.0-only
//! Reading-plan works, account reservations, and anonymous timed comments.
use std::{
    collections::{BTreeMap, HashSet, VecDeque},
    io::Write,
    sync::Arc,
    time::{Duration, Instant},
};

use anyhow::{Context, Result, anyhow, bail, ensure};
use flate2::{Compression, write::GzEncoder};
use reqwest::{
    Client, RequestBuilder, Url,
    header::{COOKIE, HeaderValue},
};
use serde::Serialize;
use serde_json::{Value, json};
use tokio::sync::Mutex;

use crate::{
    registered::{Device, RegisteredSource, now_ms},
    source::{read_limited, valid_id},
    v5::Signer,
    web::Series,
};

const ACCOUNT_ORIGIN: &str = "https://api5-normal-sinfonlinea.fqnovel.com";
const COMMENTS_ORIGIN: &str = "https://api5-normal-sinfonlineb.fqnovel.com";
const MAX_DURATION: u64 = 86_400_000;
const RESPONSE_LIMIT: usize = 2 * 1024 * 1024;

#[derive(Clone, Serialize)]
pub struct RelatedWork {
    pub series: Series,
    pub upcoming: bool,
    pub season: String,
    pub season_index: u32,
}

#[derive(Clone, Serialize)]
pub struct Reservation {
    pub series: Series,
    pub scheduled_at: u64,
    pub expected: String,
}

impl Reservation {
    pub fn matches(&self, id: &str) -> bool {
        self.series.id == id || self.series.id.strip_prefix("reservation:") == Some(id)
    }
}

#[derive(Clone, Serialize)]
pub struct Danmaku {
    pub id: String,
    pub text: String,
    pub time_ms: u64,
}

#[derive(Clone, Serialize)]
pub struct DanmakuPage {
    pub next_ms: u64,
    pub next_cursor: Option<String>,
    pub items: Vec<Danmaku>,
}

#[derive(Clone, PartialEq, Eq)]
struct CommentKey {
    series: String,
    episode: String,
    start: u64,
    duration: u64,
    cursor: String,
}

type Cache<K, V> = Mutex<VecDeque<(K, Instant, V)>>;

pub struct Social {
    registered: Arc<RegisteredSource>,
    client: Client,
    signer: Signer,
    related: Cache<String, Vec<RelatedWork>>,
    comments: Cache<CommentKey, DanmakuPage>,
}

impl Social {
    pub fn new(registered: Arc<RegisteredSource>) -> Result<Self> {
        Ok(Self {
            registered,
            client: Client::builder()
                .https_only(true)
                .redirect(reqwest::redirect::Policy::none())
                .retry(reqwest::retry::never())
                .connect_timeout(Duration::from_secs(4))
                .timeout(Duration::from_secs(8))
                .build()
                .context("无法初始化预约与弹幕连接")?,
            signer: Signer::new(now_ms()),
            related: Mutex::default(),
            comments: Mutex::default(),
        })
    }

    pub async fn related(&self, series: &str) -> Result<Vec<RelatedWork>> {
        ensure!(valid_id(series), "剧集编号无效");
        let key = series.to_owned();
        if let Some(works) = cached(&self.related, &key, 300).await {
            return Ok(works);
        }
        let device = self.registered.identity().await?;
        let mut values = profile(&device, "72232", "7.2.2.32");
        add(
            &mut values,
            [
                ("scene", "10"),
                ("from", "detail_page_more_related"),
                ("book_id", series),
                ("new_search_middle_page", "false"),
                ("bookstore_tab_type", "0"),
                ("query_history_removed", "false"),
                ("user_is_login", "0"),
                ("bookstore_tab", "0"),
                ("search_middle_page_version", "0"),
                ("pad_column_detail", "0"),
                ("hot_word_exchange", "false"),
                ("device_level", "3"),
                ("limit", "0"),
                ("offset", "0"),
                ("is_horizontal_screen", "false"),
                ("reader_single_col_from_ip_strategy", "false"),
            ],
        );
        for host in [
            "api5-normal-sinfonlinec.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
        ] {
            let url = format!("https://{host}/reading/bookapi/plan/v:version/");
            let request = self.signed(&url, values.clone(), None, "72232", false)?;
            if let Ok(root) = response(request, "相关作品").await
                && let Ok(works) = parse_related(&root, series)
            {
                remember(&self.related, key, works.clone(), 24).await;
                return Ok(works);
            }
        }
        bail!("相关作品暂时无法加载，请重试")
    }

    pub async fn reservations(
        &self,
        cookie: &HeaderValue,
        online: bool,
    ) -> Result<Vec<Reservation>> {
        let device = self.registered.identity().await?;
        let mut values = profile(&device, "72232", "7.2.2.32");
        add(
            &mut values,
            [
                ("offset", "0"),
                ("limit", "20"),
                ("is_online", if online { "true" } else { "false" }),
                ("tab_type", "13"),
                ("subscribe_order_type", "0"),
                ("swipe_type", "0"),
                ("need_calendar_schema", "false"),
                ("subscribe_offset", "0"),
            ],
        );
        let request = self
            .signed(
                &format!("{ACCOUNT_ORIGIN}/reading/user/subscribe/list/v0/"),
                values,
                None,
                "72232",
                false,
            )?
            .header(COOKIE, cookie.clone());
        parse_reservations(&response(request, "预约列表").await?, online)
    }

    pub async fn prepare_reservation(
        &self,
        cookie: &HeaderValue,
        work: &RelatedWork,
    ) -> Result<ReservationRequest> {
        let id = work
            .series
            .id
            .parse::<i64>()
            .ok()
            .filter(|id| *id > 0)
            .filter(|_| work.upcoming)
            .ok_or_else(|| anyhow!("预约编号无效"))?;
        let device = self.registered.identity().await?;
        let payload = json!({"item_id": id, "item_type": 3, "op_type": 1, "subscribe_source": 0});
        let mut gzip = GzEncoder::new(Vec::new(), Compression::default());
        gzip.write_all(&serde_json::to_vec(&payload)?)?;
        let request = self
            .signed(
                &format!("{ACCOUNT_ORIGIN}/reading/bookapi/search/uncover_subscribe/v1/"),
                profile(&device, "73932", "7.3.9.32"),
                Some(gzip.finish()?),
                "71332",
                false,
            )?
            .header(COOKIE, cookie.clone())
            .header("Content-Encoding", "gzip");
        Ok(ReservationRequest(request))
    }

    pub async fn danmaku(
        &self,
        series: &str,
        episode: &str,
        start: u64,
        duration: u64,
        cursor: &str,
    ) -> Result<DanmakuPage> {
        ensure!(
            valid_id(series)
                && valid_id(episode)
                && start < duration
                && duration <= MAX_DURATION
                && cursor.len() <= 16_384,
            "弹幕请求范围无效"
        );
        let key = CommentKey {
            series: series.into(),
            episode: episode.into(),
            start,
            duration,
            cursor: cursor.into(),
        };
        if let Some(page) = cached(&self.comments, &key, 60).await {
            return Ok(page);
        }
        let device = self.registered.identity().await?;
        let payload = json!({
            "comment_source": 601, "server_channel": 1000, "group_id": episode,
            "group_type": 30, "comment_type": 20, "sort": 1, "count": 90, "cursor": cursor,
            "aid": 8662, "compliance_status": 0,
            "business_param": {"book_id": series, "start_offset_time": start,
                "playlet_item_duration": duration, "need_danmaku_guide_type": [1, 3, 4, 2]},
        });
        let mut request = self
            .signed(
                &format!("{COMMENTS_ORIGIN}/novel/commentapi/comment/list/{episode}/v1/"),
                profile(&device, "74332", "7.4.3.32"),
                Some(serde_json::to_vec(&payload)?),
                "74332",
                true,
            )?
            .header("Comment-Source", "601")
            .header("Server-Channel", "1000");
        if let Some(cookie) = &device.cookie {
            request = request.header(COOKIE, cookie.clone());
        }
        let page = parse_danmaku(
            &response(request, "弹幕").await?,
            episode,
            start,
            duration,
            cursor,
        )?;
        remember(&self.comments, key, page.clone(), 64).await;
        Ok(page)
    }

    fn signed(
        &self,
        base: &str,
        values: BTreeMap<String, String>,
        body: Option<Vec<u8>>,
        version: &str,
        comment: bool,
    ) -> Result<RequestBuilder> {
        let (query, headers) =
            self.signer
                .sign_legacy(values, body.as_deref(), now_ms(), comment)?;
        let mut url = Url::parse(base)?;
        url.set_query(Some(&query));
        let mut request = if let Some(body) = body {
            self.client
                .post(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body)
        } else {
            self.client.get(url)
        };
        let mut agent = format!(
            "com.phoenix.read/{version} (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3"
        );
        if version == "71332" {
            agent.push_str(
                "; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08",
            );
        }
        agent.push(')');
        request = request
            .header("Accept", "application/json")
            .header("User-Agent", agent);
        for (name, value) in headers {
            request = request.header(name, value);
        }
        Ok(request)
    }
}

pub struct ReservationRequest(RequestBuilder);

impl ReservationRequest {
    /// An explicit upstream rejection is safe to retry. A transport or response error has an unknown outcome.
    pub async fn submit(self) -> Result<()> {
        response(self.0, "预约").await.map(|_| ())
    }
}

#[derive(Debug)]
pub struct ProviderRejection {
    operation: &'static str,
    code: i64,
}

impl std::fmt::Display for ProviderRejection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}接口暂不可用（错误码 {}）", self.operation, self.code)
    }
}

impl std::error::Error for ProviderRejection {}

async fn response(request: RequestBuilder, operation: &'static str) -> Result<Value> {
    let reply = request
        .send()
        .await
        .map_err(|_| anyhow!("{operation}连接失败"))?;
    ensure!(
        reply.status().is_success(),
        "{operation}读取失败（HTTP {}）",
        reply.status().as_u16()
    );
    let bytes = read_limited(reply, RESPONSE_LIMIT)
        .await
        .map_err(|_| anyhow!("{operation}响应读取失败"))?;
    let root: Value =
        serde_json::from_slice(&bytes).map_err(|_| anyhow!("{operation}响应格式无效"))?;
    let code = number(&root["code"]).ok_or_else(|| anyhow!("{operation}响应缺少状态"))?;
    if code != 0 {
        return Err(ProviderRejection { operation, code }.into());
    }
    Ok(root)
}

fn add<'a>(
    values: &mut BTreeMap<String, String>,
    items: impl IntoIterator<Item = (&'a str, &'a str)>,
) {
    values.extend(items.into_iter().map(|(k, v)| (k.to_owned(), v.to_owned())));
}

fn profile(device: &Device, code: &str, version: &str) -> BTreeMap<String, String> {
    let mut values = BTreeMap::new();
    add(
        &mut values,
        [
            ("iid", device.install_id.as_str()),
            ("device_id", device.device_id.as_str()),
            ("cdid", device.cdid.as_str()),
            ("ac", "wifi"),
            ("channel", "update_64"),
            ("aid", "8662"),
            ("app_name", "novelread"),
            ("version_code", code),
            ("version_name", version),
            ("device_platform", "android"),
            ("os", "android"),
            ("ssmix", "a"),
            ("device_type", "25053RT47C"),
            ("device_brand", "Redmi"),
            ("language", "zh"),
            ("os_api", "36"),
            ("os_version", "16"),
            ("manifest_version_code", code),
            ("resolution", "1280*2772"),
            ("dpi", "520"),
            ("update_version_code", code),
            ("host_abi", "arm64-v8a"),
            ("dragon_device_type", "phone"),
            ("pv_player", code),
            ("compliance_status", "0"),
            ("need_personal_recommend", "1"),
            ("player_so_load", "1"),
            ("is_android_pad_screen", "0"),
        ],
    );
    values
}

async fn cached<K: PartialEq, V: Clone>(cache: &Cache<K, V>, key: &K, seconds: u64) -> Option<V> {
    cache
        .lock()
        .await
        .iter()
        .rev()
        .find(|(entry, stamp, _)| entry == key && stamp.elapsed() < Duration::from_secs(seconds))
        .map(|(_, _, value)| value.clone())
}

async fn remember<K: PartialEq, V>(cache: &Cache<K, V>, key: K, value: V, limit: usize) {
    let mut rows = cache.lock().await;
    rows.retain(|(entry, _, _)| *entry != key);
    if rows.len() >= limit {
        rows.pop_front();
    }
    rows.push_back((key, Instant::now(), value));
}

fn number(value: &Value) -> Option<i64> {
    value.as_i64().or_else(|| value.as_str()?.parse().ok())
}

fn unsigned(value: &Value) -> Option<u64> {
    number(value)?.try_into().ok()
}

fn boolean(value: &Value) -> Option<bool> {
    value.as_bool().or_else(|| match value.as_str()? {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    })
}

fn string(value: &Value) -> String {
    match value {
        Value::String(s) => s.trim().to_owned(),
        Value::Number(n) => n.to_string(),
        _ => String::new(),
    }
}

fn first(values: &[&Value]) -> String {
    values
        .iter()
        .map(|value| string(value))
        .find(|value| !value.is_empty())
        .unwrap_or_default()
}

fn cover(value: &str) -> String {
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

fn parse_related(root: &Value, current: &str) -> Result<Vec<RelatedWork>> {
    let cells = root["data"].as_array().context("相关作品数据格式已变化")?;
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for row in cells
        .iter()
        .filter(|cell| number(&cell["show_type"]) == Some(547) || cell["cell_name"] == "相关作品")
        .flat_map(|cell| cell["video_data"].as_array().into_iter().flatten())
    {
        let id = string(&row["series_id"]);
        let title = string(&row["title"]);
        let kind = number(&row["content_type"]);
        let upcoming = kind == Some(1007);
        let episodes = unsigned(&row["episode_cnt"]).unwrap_or_default();
        if id == current
            || !valid_id(&id)
            || title.is_empty()
            || (!upcoming
                && (kind != Some(1004)
                    || episodes == 0
                    || boolean(&row["disable_play"]) == Some(true)))
            || !seen.insert(id.clone())
        {
            continue;
        }
        let season_index = unsigned(&row["season_index"])
            .and_then(|n| u32::try_from(n).ok())
            .unwrap_or_default();
        let mut season = string(&row["tag_info"]["text"]);
        if season.is_empty() && season_index > 0 {
            season = format!("第{season_index}季");
        }
        result.push(RelatedWork {
            upcoming,
            season,
            season_index,
            series: Series {
                id,
                title,
                cover: cover(&string(&row["cover"])),
                description: string(&row["video_desc"]),
                badge: if upcoming {
                    "待上线".into()
                } else {
                    format!("全 {episodes} 集")
                },
                tags: Vec::new(),
            },
        });
    }
    result.sort_by_key(|work| {
        if work.season_index == 0 {
            u32::MAX
        } else {
            work.season_index
        }
    });
    Ok(result)
}

fn parse_reservations(root: &Value, online: bool) -> Result<Vec<Reservation>> {
    let rows = match root["data"].get("subscribe_items") {
        Some(Value::Null) => return Ok(Vec::new()),
        Some(Value::Array(rows)) => rows,
        _ => bail!("预约列表格式已变化，请稍后重试"),
    };
    let mut seen = HashSet::new();
    let mut result = Vec::new();
    for row in rows {
        let nested = row
            .get("subscribe_data")
            .filter(|value| value.is_object())
            .or_else(|| row.get("video_data").filter(|value| value.is_object()));
        let video = nested.unwrap_or(row);
        let mut id = first(&[&video["series_id"], &row["series_id"], &row["book_id"]]);
        if !valid_id(&id) {
            let item = string(&row["item_id"]);
            if online || nested.is_some() || !valid_id(&item) {
                continue;
            }
            id = format!("reservation:{item}");
        }
        let title = first(&[
            &row["series_title"],
            &video["title"],
            &video["book_name"],
            &row["name"],
        ]);
        if title.is_empty()
            || boolean(&row["is_online"]).is_some_and(|value| value != online)
            || !seen.insert(id.clone())
        {
            continue;
        }
        let stamp = unsigned(&row["schedule_publish_time"]).unwrap_or_default();
        let scheduled_at = if (1..=9_999_999_999).contains(&stamp) {
            stamp * 1000
        } else {
            stamp
        };
        let episodes = [
            &video["episode_cnt"],
            &video["total_episode_count"],
            &row["episode_cnt"],
        ]
        .into_iter()
        .filter_map(unsigned)
        .find(|count| *count > 0);
        let badge = match (online, episodes) {
            (false, _) => "待上线".into(),
            (true, Some(count)) => format!("全 {count} 集"),
            (true, None) => "已上线".into(),
        };
        result.push(Reservation {
            scheduled_at,
            expected: string(&row["expected_rec_text"]),
            series: Series {
                id,
                title,
                badge,
                cover: cover(&first(&[
                    &row["series_cover_url"],
                    &video["cover"],
                    &video["cover_url"],
                    &video["thumb_url"],
                ])),
                description: first(&[&video["video_desc"], &row["abstract"], &row["item_desc"]]),
                tags: Vec::new(),
            },
        });
    }
    ensure!(
        rows.is_empty() || !result.is_empty(),
        "预约条目格式已变化，请稍后重试"
    );
    Ok(result)
}

fn parse_danmaku(
    root: &Value,
    episode: &str,
    start: u64,
    duration: u64,
    cursor: &str,
) -> Result<DanmakuPage> {
    let data = &root["data"];
    let next = unsigned(&data["extra"]["next_query_danmaku_list_time"])
        .filter(|next| *next > start && *next <= MAX_DURATION)
        .context("弹幕时间段无效")?;
    let next_ms = next.min(duration);
    let rows = data["data_list"].as_array().context("弹幕数据格式已变化")?;
    let mut seen = HashSet::new();
    let mut items = Vec::new();
    for row in rows.iter().take(300) {
        let comment = &row["comment"];
        let common = &comment["common"];
        if string(&common["group_id"]) != episode || number(&common["status"]) != Some(1) {
            continue;
        }
        let Some(time_ms) = unsigned(&comment["expand"]["offset_time"])
            .filter(|time| (start..next_ms).contains(time))
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
        if items.len() == 90 {
            break;
        }
    }
    items.sort_by_key(|item| item.time_ms);
    let list = &data["common_list_info"];
    let next_cursor = string(&list["cursor"]);
    let next_cursor = (boolean(&list["has_more"]) == Some(true)
        && !next_cursor.is_empty()
        && next_cursor != cursor
        && next_cursor.len() <= 16_384)
        .then_some(next_cursor);
    Ok(DanmakuPage {
        next_ms,
        next_cursor,
        items,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn related_work_only_exposes_actions_for_supported_types() {
        let root = json!({"code": 0, "data": [
            {"show_type": 123, "video_data": [{"series_id": "999", "title": "其他栏目", "content_type": 1007}]},
            {"show_type": 547, "video_data": [
                {"series_id": 111, "title": "当前", "content_type": 1004, "episode_cnt": 14},
                {"series_id": 333, "title": "第三季", "content_type": 1007, "season_index": 3},
                {"series_id": "222", "title": "第二季", "content_type": "1004", "episode_cnt": 20, "season_index": 2},
                {"series_id": "444", "title": "小说", "content_type": 1001, "episode_cnt": 20},
                {"series_id": "555", "title": "不可播放", "content_type": 1004, "episode_cnt": 20, "disable_play": true},
                {"series_id": 333, "title": "重复曲目", "content_type": 1007}
            ]}
        ]});
        let rows = parse_related(&root, "111").unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(
            (&*rows[0].series.id, &*rows[0].season, rows[0].upcoming),
            ("222", "第2季", false)
        );
        assert_eq!((&*rows[1].series.id, rows[1].upcoming), ("333", true));
    }

    #[test]
    fn pending_reservations_require_no_playable_id_and_empty_is_explicit() {
        let flat = json!({"data": {"subscribe_items": [
            {"item_id": 333, "name": "待播剧", "expected_rec_text": "预计下周", "schedule_publish_time": "1785000000"},
            {"item_id": 333, "name": "重复待播剧"}
        ]}});
        let rows = parse_reservations(&flat, false).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].series.id, "reservation:333");
        assert!(rows[0].matches("333"));
        assert_eq!(rows[0].scheduled_at, 1_785_000_000_000);
        assert!(parse_reservations(&flat, true).is_err());
        assert!(
            parse_reservations(&json!({"data":{"subscribe_items":null}}), true)
                .unwrap()
                .is_empty()
        );
        assert!(parse_reservations(&json!({"data":{}}), false).is_err());
        let nested = json!({"data": {"subscribe_items": [
            {"subscribe_data": {"series_id": "123456", "title": "已播剧", "episode_cnt": 5}, "is_online": true}
        ]}});
        let rows = parse_reservations(&nested, true).unwrap();
        assert_eq!(rows[0].series.id, "123456");
        assert_eq!(rows[0].series.badge, "全 5 集");
    }

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
        let page = parse_danmaku(&root, "123456", 10_000, 20_000, "").unwrap();
        assert_eq!(page.next_ms, 20_000);
        assert_eq!(page.next_cursor.as_deref(), Some("next"));
        assert_eq!(
            page.items
                .iter()
                .map(|item| item.text.as_str())
                .collect::<Vec<_>>(),
            ["第一 条", "第二条"]
        );
        assert!(parse_danmaku(&root, "123456", 30_000, 35_000, "").is_err());
        assert!(
            parse_danmaku(&root, "123456", 10_000, 20_000, "next")
                .unwrap()
                .next_cursor
                .is_none()
        );
    }
}
