// SPDX-License-Identifier: GPL-3.0-only
//! Multi-condition browse protocol shared with DiscoveryFilters.kt on main.
use super::*;

const FILTERS: [(&str, &str); 8] = [
    ("genre", "类型"),
    ("category_dim_theme", "主题"),
    ("category_dim_role", "设定"),
    ("category_dim_epoch", "背景"),
    ("sort", "推荐"),
    ("gender", "受众"),
    ("online_time", "时间"),
    ("creation_status", "状态"),
];
const LIMIT: u32 = 24;
const MAX_PAGES: u32 = 100;

#[derive(Serialize)]
pub struct DiscoveryOption {
    id: String,
    label: String,
}

#[derive(Serialize)]
pub struct DiscoveryGroup {
    #[serde(rename = "type")]
    kind: String,
    label: &'static str,
    options: Vec<DiscoveryOption>,
}

#[derive(Clone)]
pub struct DiscoveryCursor {
    offset: u32,
    page: u32,
    session: String,
    device: Arc<Device>,
}

pub struct DiscoveryPage {
    pub items: Vec<Series>,
    pub groups: Vec<DiscoveryGroup>,
    pub next: Option<DiscoveryCursor>,
}

fn valid_filter_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 80
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
}

pub fn valid_discovery_filters(filters: &BTreeMap<String, String>) -> bool {
    filters.len() <= FILTERS.len()
        && filters
            .iter()
            .all(|(key, id)| FILTERS.iter().any(|(kind, _)| *kind == key) && valid_filter_id(id))
}

fn selection_body(filters: &BTreeMap<String, String>, cursor: Option<&DiscoveryCursor>) -> Value {
    let scene = match filters.get("genre").map(String::as_str) {
        Some(genre @ ("comic_series" | "ai_series")) => genre,
        _ => "default",
    };
    let selected: serde_json::Map<_, _> = FILTERS
        .into_iter()
        .map(|(kind, _)| {
            let values: Vec<_> = filters.get(kind).into_iter().collect();
            (kind.to_owned(), json!(values))
        })
        .collect();
    json!({
        "offset":cursor.map_or(0, |row| row.offset), "limit":LIMIT,
        "need_selector_panel":cursor.is_none(), "select_items":selected,
        "session_id":cursor.map_or("", |row| row.session.as_str()),
        "req_type":if cursor.is_none() {"default"} else {"only_content"},
        "client_req_type":3, "filter_ids":"", "req_scene":scene,
    })
}

impl Social {
    pub async fn discover(
        &self,
        filters: &BTreeMap<String, String>,
        cursor: Option<&DiscoveryCursor>,
    ) -> Result<DiscoveryPage> {
        ensure!(valid_discovery_filters(filters), "筛选条件无效");
        let device = match cursor {
            Some(cursor) => cursor.device.clone(),
            None => self.registered.identity().await?,
        };
        let body = serde_json::to_vec(&selection_body(filters, cursor))?;
        let request = self.signed(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",
            profile(&device, "73532", "7.3.5.32"),
            Some(body),
            "71332",
            false,
        )?;
        parse_discovery(&response(request, "分类找剧").await?, cursor, device)
    }
}

fn parse_discovery(
    root: &Value,
    cursor: Option<&DiscoveryCursor>,
    device: Arc<Device>,
) -> Result<DiscoveryPage> {
    let data = &root["data"];
    let rows = data["video_data"].as_array().context("分类剧集列表缺失")?;
    let mut seen = HashSet::new();
    let mut items = Vec::new();
    for row in rows {
        let id = string(&row["series_id"]);
        let title = string(&row["title"]);
        if !valid_id(&id) || title.is_empty() || !seen.insert(id.clone()) {
            continue;
        }
        let category = row["category_schema"]
            .as_str()
            .and_then(|raw| serde_json::from_str::<Value>(raw).ok());
        let tags = category
            .as_ref()
            .unwrap_or(&row["category_schema"])
            .as_array()
            .into_iter()
            .flatten()
            .map(|tag| string(&tag["name"]))
            .filter(|tag| !tag.is_empty())
            .take(8)
            .collect();
        let badge = unsigned(&row["episode_cnt"])
            .filter(|count| *count > 0)
            .map_or_else(String::new, |count| format!("共 {count} 集"));
        items.push(Series {
            id,
            title,
            cover: cover(&string(&row["cover"])),
            description: string(&row["video_desc"]),
            badge,
            tags,
        });
    }
    ensure!(rows.is_empty() || !items.is_empty(), "分类剧集格式异常");
    let mut seen_groups = HashSet::new();
    let groups: Vec<_> = data["selector_rows"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|row| {
            let kind = row["type"].as_str()?;
            let &(_, label) = FILTERS.iter().find(|(filter, _)| *filter == kind)?;
            if !seen_groups.insert(kind) {
                return None;
            }
            let mut ids = HashSet::new();
            let options = row["items"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|item| {
                    let id = string(&item["selector_item_id"]);
                    let label = string(&item["show_name"]);
                    (valid_filter_id(&id)
                        && !label.is_empty()
                        && label.chars().count() <= 40
                        && ids.insert(id.clone()))
                    .then_some(DiscoveryOption { id, label })
                })
                .take(128)
                .collect();
            Some(DiscoveryGroup {
                kind: kind.to_owned(),
                label,
                options,
            })
        })
        .collect();
    ensure!(
        cursor.is_some() || groups.iter().any(|group| group.kind == "genre"),
        "分类筛选条件缺失"
    );
    let page = cursor.map_or(1, |row| row.page);
    let next = if boolean(&data["has_more"]) == Some(true) && page < MAX_PAGES {
        let offset = unsigned(&data["next_offset"])
            .and_then(|offset| u32::try_from(offset).ok())
            .filter(|offset| *offset > cursor.map_or(0, |row| row.offset))
            .context("分类续页信息异常，请刷新重试")?;
        let mut session = string(&data["session_id"]);
        if session.is_empty()
            && let Some(row) = cursor
        {
            session.clone_from(&row.session);
        }
        ensure!(
            !session.is_empty() && session.len() <= 4096,
            "分类续页信息异常，请刷新重试"
        );
        Some(DiscoveryCursor {
            offset,
            page: page + 1,
            session,
            device,
        })
    } else {
        None
    };
    Ok(DiscoveryPage {
        items,
        groups,
        next,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device() -> Arc<Device> {
        Arc::new(Device {
            device_id: "123".into(),
            install_id: "456".into(),
            cdid: "fixture".into(),
            cookie: None,
        })
    }

    #[test]
    fn handles_combined_filters_and_the_exact_following_cursor() {
        let filters = BTreeMap::from([
            ("genre".into(), "comic_series".into()),
            ("gender".into(), "0".into()),
            ("category_dim_role".into(), "cate_36".into()),
        ]);
        assert!(valid_discovery_filters(&filters));
        let payload = selection_body(&filters, None);
        assert_eq!(payload["req_scene"], "comic_series");
        assert_eq!(payload["select_items"]["gender"], json!(["0"]));
        assert_eq!(
            payload["select_items"]["category_dim_role"],
            json!(["cate_36"])
        );
        assert_eq!(payload["select_items"]["sort"], json!([]));
        assert_eq!(payload["need_selector_panel"], true);
        let identity = device();
        let root = json!({"data":{
            "video_data":[{"series_id":123,"title":"作品","category_schema":"[{\"name\":\"作品标签\"}]"}],
            "selector_rows":[{"type":"genre","items":[]},{"type":"gender","items":[{"selector_item_id":0,"show_name":"女频"}]}],
            "has_more":true,"next_offset":24,"session_id":"fixture-only"
        }});
        let result = parse_discovery(&root, None, identity.clone()).unwrap();
        assert_eq!(result.items[0].tags, ["作品标签"]);
        assert_eq!(result.groups[1].options[0].id, "0");
        let cursor = result.next.unwrap();
        assert!(Arc::ptr_eq(&cursor.device, &identity));
        let next = selection_body(&filters, Some(&cursor));
        assert_eq!(next["req_type"], "only_content");
        assert_eq!(next["need_selector_panel"], false);
        assert_eq!(next["offset"], 24);
        assert_eq!(next["session_id"], "fixture-only");
        assert_eq!(next["select_items"], payload["select_items"]);
        assert!(parse_discovery(&root, Some(&cursor), identity).is_err());
        assert!(!valid_discovery_filters(&BTreeMap::from([(
            "unknown".into(),
            "cate_1".into()
        )])));
    }
}
