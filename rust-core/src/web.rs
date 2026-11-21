// SPDX-License-Identifier: GPL-3.0-only
use std::{
    collections::{BTreeMap, HashSet},
    time::Duration,
};

use anyhow::{Context, Result, ensure};
use reqwest::{Client, Url, header, redirect};
use serde::{Deserialize, Serialize};
use serde_json::Value;

pub use crate::source::{read_limited, valid_id};

pub const WEBSITE: &str = "https://hongguoduanju.com/";
const WEBSITE_LIMIT: usize = 6 * 1024 * 1024;
// Use an ordinary browser profile for the site's desktop HTML and JSON routes.
pub const USER_AGENT: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

#[derive(Debug)]
pub struct PreviewUnavailable;

impl std::fmt::Display for PreviewUnavailable {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("本集暂无公开试看，可在官网播放")
    }
}

impl std::error::Error for PreviewUnavailable {}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Series {
    pub id: String,
    pub title: String,
    pub cover: String,
    pub description: String,
    pub badge: String,
    pub tags: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct CatalogPage {
    pub items: Vec<Series>,
    pub page: u32,
    pub has_more: bool,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub topics: Vec<DramaTopic>,
}

#[derive(Debug, Serialize)]
pub struct DramaTopic {
    id: String,
    label: String,
}

#[derive(Debug, Serialize)]
pub struct Episode {
    pub id: String,
    pub number: usize,
    pub available: bool,
    pub full: bool,
}

#[derive(Debug, Serialize)]
pub struct Detail {
    pub series: Series,
    pub episodes: Vec<Episode>,
    pub related: Vec<Series>,
    pub reviews: Vec<Review>,
}

#[derive(Debug, Serialize)]
pub struct Review {
    pub author: String,
    pub content: String,
}

#[derive(Debug, Serialize)]
pub struct EpisodeComments {
    pub items: Vec<Review>,
    pub total: u64,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RankBoard {
    All,
    Real,
    Comic,
    Ai,
    New,
    Searched,
    Favorites,
}

impl RankBoard {
    fn slug(self) -> &'static str {
        match self {
            Self::All => "hot-drama",
            Self::Real => "hot-real-drama",
            Self::Comic => "hot-comic-drama",
            Self::Ai => "hot-ai-drama",
            Self::New => "new-drama",
            Self::Searched => "hot-search-drama",
            Self::Favorites => "followed-drama",
        }
    }
}

#[derive(Serialize)]
pub struct RankRow {
    rank: u64,
    series: Series,
    metric: String,
}

#[derive(Serialize)]
pub struct RankPage {
    items: Vec<RankRow>,
    updated: String,
    has_more: bool,
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Category {
    Real,
    Comic,
    Ai,
}

impl Category {
    fn path(self) -> &'static str {
        match self {
            Self::Real => "category/real-drama",
            Self::Comic => "category/comic-drama",
            Self::Ai => "category/ai-drama",
        }
    }
}

#[derive(Clone)]
pub struct Site {
    client: Client,
    account_client: Client,
    cookie: Option<header::HeaderValue>,
    pub media_client: Client,
    base: Url,
}

impl Site {
    pub fn new() -> Result<Self> {
        let base = Url::parse(WEBSITE)?;
        let client = Client::builder()
            .user_agent(USER_AGENT)
            .cookie_store(true)
            .connect_timeout(Duration::from_secs(8))
            .timeout(Duration::from_secs(18))
            .redirect(redirect::Policy::custom(|attempt| {
                if attempt.previous().len() < 5 && official_origin(attempt.url()) {
                    attempt.follow()
                } else {
                    attempt.stop()
                }
            }))
            .build()?;
        let account_client = Client::builder()
            .user_agent(USER_AGENT)
            .connect_timeout(Duration::from_secs(8))
            .timeout(Duration::from_secs(18))
            .redirect(redirect::Policy::custom(|attempt| {
                if attempt.previous().len() < 5
                    && attempt
                        .previous()
                        .first()
                        .is_some_and(|url| url.origin() == attempt.url().origin())
                    && official_origin(attempt.url())
                {
                    attempt.follow()
                } else {
                    attempt.stop()
                }
            }))
            .build()?;
        // A different client keeps web session cookies out of playback requests.
        let media_client = crate::source::media_client_builder()
            .user_agent(USER_AGENT)
            .build()?;
        Ok(Self {
            client,
            account_client,
            cookie: None,
            media_client,
            base,
        })
    }

    pub fn with_cookie(&self, cookie: Option<header::HeaderValue>) -> Self {
        Self {
            cookie,
            ..self.clone()
        }
    }

    pub async fn discover(&self, category: Category, page: u32) -> Result<CatalogPage> {
        self.discover_topic(category, "", page).await
    }

    pub async fn discover_topic(
        &self,
        category: Category,
        topic: &str,
        page: u32,
    ) -> Result<CatalogPage> {
        ensure!(topic.is_empty() || valid_topic(topic), "题材无效");
        let path = if topic.is_empty() {
            category.path().to_owned()
        } else {
            format!("{}/{topic}", category.path())
        };
        let mut url = self.base.join(&path)?;
        url.query_pairs_mut().append_pair("page", &page.to_string());
        let root = router_data(&self.text(url).await?)?;
        let section = root["loaderData"]
            .get("category_$")
            .filter(|row| row.is_object())
            .or_else(|| root["loaderData"].get("category_page"))
            .context("分类内容暂不可用")?;
        parse_discovery(section, category, &path, topic, page)
    }

    pub async fn search(&self, query: &str, page: u32) -> Result<CatalogPage> {
        let mut url = self.base.join("api/search/result")?;
        url.query_pairs_mut()
            .append_pair("query", query)
            .append_pair("page", &page.to_string());
        let root: Value = serde_json::from_str(&self.text(url).await?)
            .map_err(|_| anyhow::anyhow!("搜索响应格式已变化"))?;
        if let Some(actual) = number(&root["pageNum"]) {
            ensure!(actual == u64::from(page), "搜索页码不匹配");
        }
        let rows = root["searchList"].as_array().context("搜索结果暂不可用")?;
        let mut items = cards(rows);
        let query = query.to_lowercase();
        items.sort_by_key(|item| {
            let title = item.title.to_lowercase();
            if title == query {
                0
            } else if title.contains(&query) {
                1
            } else {
                2
            }
        });
        let has_more = root["hasMore"]
            .as_bool()
            .unwrap_or_else(|| u64::from(page) < number(&root["totalPages"]).unwrap_or(100));
        Ok(CatalogPage {
            items,
            page,
            has_more: !rows.is_empty() && has_more && page < 100,
            topics: Vec::new(),
        })
    }

    pub async fn suggestions(&self, query: &str) -> Result<Vec<String>> {
        let query = query.trim();
        if query.is_empty() {
            return Ok(Vec::new());
        }
        ensure!(query.chars().count() <= 80, "搜索词最多 80 个字");
        let mut url = self.base.join("incent_resource/suggestion")?;
        url.query_pairs_mut()
            .append_pair("app_id", "8662")
            .append_pair("query", query)
            .append_pair("count", "8");
        let root: Value = serde_json::from_str(&self.with_cookie(None).text(url).await?)
            .map_err(|_| anyhow::anyhow!("搜索联想格式已变化"))?;
        let rows = root["suggest_list"]
            .as_array()
            .context("搜索联想暂不可用")?;
        let mut seen = HashSet::new();
        Ok(rows
            .iter()
            .filter_map(|row| {
                public_text(&row["name"]).or_else(|| public_text(&row["display_words"][0]))
            })
            .filter(|word| word.chars().count() <= 80 && seen.insert(word.clone()))
            .take(8)
            .collect())
    }

    pub async fn episode_comments(&self, series: &str, episode: &str) -> Result<EpisodeComments> {
        ensure!(valid_id(series) && valid_id(episode), "分集编号无效");
        let url = self.base.join(&format!("player/{series}/{episode}"))?;
        let root = router_data(&self.with_cookie(None).text(url).await?)?;
        parse_episode_comments(&root, series, episode)
    }

    pub async fn detail(&self, id: &str) -> Result<Detail> {
        ensure!(valid_id(id), "剧集编号无效");
        let mut url = self.base.join("detail")?;
        url.query_pairs_mut().append_pair("series_id", id);
        let root = router_data(&self.text(url).await?)?;
        let page = &root["loaderData"]["detail_page"];
        let mut detail = parse_detail(&page["seriesDetail"], id)?;
        if let Some(rows) = page["recommendations"]
            .as_array()
            .or_else(|| page["recommendations"]["recommendList"].as_array())
        {
            detail.related = cards(rows)
                .into_iter()
                .filter(|row| row.id != id)
                .take(12)
                .collect();
        }
        detail.reviews = reviews(&page["seriesSocialInfo"]["reviews"]["reviews"], 30);
        Ok(detail)
    }

    pub async fn rank(&self, board: RankBoard, page: u32) -> Result<RankPage> {
        let slug = board.slug();
        let mut url = self.base.join(&format!("rank/{slug}"))?;
        url.query_pairs_mut().append_pair("page", &page.to_string());
        let root = router_data(&self.text(url).await?)?;
        let data = &root["loaderData"][format!("rank_{slug}/page")];
        let list = data["content"]["rankList"]
            .as_array()
            .context("官网排行榜暂不可用")?;
        let mut seen = HashSet::new();
        let items = list
            .iter()
            .filter_map(|row| {
                let id = string(&row["seriesId"]).filter(|id| valid_id(id))?;
                if row["reservationOnly"].as_bool() == Some(true) || !seen.insert(id.clone()) {
                    return None;
                }
                let rank = number(&row["rank"]).filter(|rank| *rank > 0)?;
                let count = number(&row["episodeCount"]).unwrap_or(0);
                let series = Series {
                    id,
                    title: string(&row["title"])?,
                    cover: string(&row["cover"]).unwrap_or_default(),
                    description: string(&row["description"]).unwrap_or_default(),
                    badge: if count > 0 {
                        format!("全 {count} 集")
                    } else {
                        String::new()
                    },
                    tags: row["tags"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(string)
                        .take(5)
                        .collect(),
                };
                let metric = string(&row["primaryMetricText"])
                    .or_else(|| string(&row["heatText"]))
                    .unwrap_or_default();
                Some(RankRow {
                    rank,
                    series,
                    metric,
                })
            })
            .collect();
        let total_pages = number(&data["content"]["pagination"]["totalPages"])
            .unwrap_or(1)
            .min(100);
        Ok(RankPage {
            items,
            updated: string(&data["updatedText"]).unwrap_or_default(),
            has_more: u64::from(page) < total_pages,
        })
    }

    pub async fn public_media(&self, series: &str, episode: &str) -> Result<crate::source::Ladder> {
        ensure!(valid_id(series) && valid_id(episode), "分集编号无效");
        let page = self.base.join(&format!("player/{series}/{episode}"))?;
        let html = self.text(page.clone()).await?;
        let root = router_data(&html)?;
        let video = root["loaderData"]
            .as_object()
            .and_then(|routes| {
                routes
                    .values()
                    .find_map(|route| route.get("publicVideoSeo"))
            })
            .ok_or(PreviewUnavailable)?;
        if video["available"].as_bool() != Some(true)
            || string(&video["seriesId"]).as_deref() != Some(series)
            || string(&video["itemId"]).as_deref() != Some(episode)
        {
            return Err(PreviewUnavailable.into());
        }
        Ok(crate::source::Ladder::preview(
            preview_urls(&html, &self.base, episode)?,
            page,
        ))
    }

    async fn text(&self, url: Url) -> Result<String> {
        let request = match &self.cookie {
            Some(cookie) if url.origin() == self.base.origin() => {
                self.account_client.get(url).header(header::COOKIE, cookie)
            }
            _ => self.client.get(url),
        };
        let response = request
            .header(header::ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9")
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("连接官网失败，请检查网络后重试"))?;
        ensure!(
            response.status().is_success(),
            "官网请求失败（HTTP {}）",
            response.status().as_u16()
        );
        let bytes = read_limited(response, WEBSITE_LIMIT).await?;
        String::from_utf8(bytes).map_err(|_| anyhow::anyhow!("官网响应格式异常"))
    }
}

fn official_origin(url: &Url) -> bool {
    url.scheme() == "https"
        && matches!(
            url.host_str(),
            Some("hongguoduanju.com" | "www.hongguoduanju.com")
        )
        && url.port().is_none()
        && url.username().is_empty()
        && url.password().is_none()
}

pub fn is_official_player_url(raw: &str) -> bool {
    let Ok(url) = Url::parse(raw) else {
        return false;
    };
    official_origin(&url)
        && url.query().is_none()
        && url.fragment().is_none()
        && matches!(url.path().split('/').collect::<Vec<_>>().as_slice(),
            ["", "player", series, episode] if valid_id(series) && valid_id(episode))
}

fn preview_urls(html: &str, base: &Url, episode: &str) -> Result<BTreeMap<u16, Url>> {
    let prefix = format!("/video/public/{episode}/");
    let mut urls = BTreeMap::new();
    for element in html.split("<script") {
        let Some((tag, content)) = element.split_once('>') else {
            continue;
        };
        if !tag.contains("application/ld+json") {
            continue;
        }
        let Some((body, _)) = content.split_once("</script>") else {
            continue;
        };
        let Ok(data) = serde_json::from_str::<Value>(body.trim()) else {
            continue;
        };
        if data["@type"] != "VideoObject" {
            continue;
        }
        let content = &data["contentUrl"];
        let values = content
            .as_array()
            .map_or_else(|| std::slice::from_ref(content), Vec::as_slice);
        for raw in values.iter().filter_map(Value::as_str) {
            let Ok(url) = base.join(raw) else { continue };
            if !official_origin(&url) || url.fragment().is_some() {
                continue;
            }
            let Some(quality) = url
                .path()
                .strip_prefix(&prefix)
                .and_then(|filename| filename.strip_suffix("p.mp4"))
                .filter(|quality| quality.len() <= 4 && quality.bytes().all(|b| b.is_ascii_digit()))
                .and_then(|quality| quality.parse::<u16>().ok())
                .filter(|quality| *quality > 0)
            else {
                continue;
            };
            urls.entry(quality).or_insert(url);
        }
    }
    if urls.is_empty() {
        return Err(PreviewUnavailable.into());
    }
    Ok(urls)
}

fn router_data(html: &str) -> Result<Value> {
    for (offset, _) in html.match_indices("_ROUTER_DATA") {
        let Some(tail) = html
            .get(offset + "_ROUTER_DATA".len()..)
            .and_then(|value| value.trim_start().strip_prefix('='))
        else {
            continue;
        };
        let mut values = serde_json::Deserializer::from_str(tail).into_iter::<Value>();
        if let Some(Ok(root)) = values.next()
            && root["loaderData"].is_object()
        {
            return Ok(root);
        }
    }
    anyhow::bail!("官网页面未返回剧集数据")
}

fn number(value: &Value) -> Option<u64> {
    value.as_u64().or_else(|| value.as_str()?.parse().ok())
}

fn string(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => {
            let text = text.trim();
            (!text.is_empty()).then(|| text.to_owned())
        }
        Value::Number(number) => Some(number.to_string()),
        _ => None,
    }
}

fn public_text(value: &Value) -> Option<String> {
    let text = value.as_str()?.trim();
    (!text.is_empty()).then(|| text.to_owned())
}

fn valid_topic(topic: &str) -> bool {
    topic.len() <= 80
        && topic.split('-').all(|part| {
            !part.is_empty()
                && part
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        })
}

fn parse_discovery(
    section: &Value,
    category: Category,
    path: &str,
    topic: &str,
    page: u32,
) -> Result<CatalogPage> {
    let route = &section["categoryRoute"];
    ensure!(
        section["isSuccess"] == true
            && route["enabled"] == true
            && route["contentType"].as_str() == category.path().strip_prefix("category/")
            && route["canonicalPath"]
                .as_str()
                .and_then(|path| path.strip_prefix('/'))
                == Some(path),
        "官网未确认所选题材，请重新选择"
    );
    let pagination = &section["pagination"];
    ensure!(
        number(&pagination["pageNum"]) == Some(u64::from(page)),
        "分类页码不匹配"
    );
    let total_pages = number(&pagination["totalPages"]).context("分类分页信息缺失")?;
    let rows = section["recommendList"]
        .as_array()
        .context("分类列表格式已变化")?;
    let mut seen = HashSet::new();
    let topics: Vec<_> = section["selectorList"]
        .as_array()
        .into_iter()
        .flatten()
        .flat_map(|group| group["items"].as_array().into_iter().flatten())
        .filter_map(|item| {
            let id = public_text(&item["selector_item_id"]).filter(|id| valid_topic(id))?;
            let label =
                public_text(&item["show_name"]).filter(|label| label.chars().count() <= 40)?;
            seen.insert(id.clone()).then_some(DramaTopic { id, label })
        })
        .take(128)
        .collect();
    ensure!(
        topic.is_empty() || topics.iter().any(|item| item.id == topic),
        "该题材已变化，请重新选择"
    );
    Ok(CatalogPage {
        items: cards(rows),
        page,
        has_more: !rows.is_empty() && u64::from(page) < total_pages.min(100),
        topics,
    })
}

fn parse_episode_comments(root: &Value, series: &str, episode: &str) -> Result<EpisodeComments> {
    let row = root["loaderData"]
        .as_object()
        .and_then(|routes| {
            routes.values().find(|row| {
                row["isSuccess"] != false
                    && string(&row["seriesDetail"]["series_id"]).as_deref() == Some(series)
                    && string(&row["vid"]).as_deref() == Some(episode)
            })
        })
        .context("公开评论分集与请求不匹配")?;
    let review_data = &row["episodeSocialInfo"]["reviews"];
    let items = reviews(&review_data["reviews"], 50);
    let total = number(&review_data["total_count"])
        .unwrap_or(0)
        .max(items.len() as u64);
    Ok(EpisodeComments { items, total })
}

fn reviews(rows: &Value, limit: usize) -> Vec<Review> {
    rows.as_array()
        .into_iter()
        .flatten()
        .filter_map(|row| {
            Some(Review {
                content: public_text(&row["content"])?.chars().take(800).collect(),
                author: public_text(&row["user_name"])
                    .unwrap_or_else(|| "红果用户".to_owned())
                    .chars()
                    .take(100)
                    .collect(),
            })
        })
        .take(limit)
        .collect()
}

fn series_from(row: &Value) -> Option<Series> {
    let source = row["video_data"]
        .as_object()
        .map_or(row, |_| &row["video_data"]);
    let id = string(&source["series_id"])?;
    if !valid_id(&id) {
        return None;
    }
    let title = string(&source["series_name"])
        .or_else(|| string(&source["series_title"]))
        .or_else(|| string(&row["name"]))?;
    let raw_cover = string(&source["series_cover"])
        .unwrap_or_default()
        .replace("&amp;", "&");
    let cover = Url::parse(&raw_cover)
        .ok()
        .filter(|url| {
            url.scheme() == "https" && url.username().is_empty() && url.password().is_none()
        })
        .map(|url| url.to_string());
    let count = number(&source["episode_cnt"])
        .or_else(|| number(&source["series_episode_info"]["episode_cnt"]));
    let tags = source["tags"]
        .as_array()
        .or_else(|| source["category_list"].as_array())
        .into_iter()
        .flatten()
        .filter_map(|tag| string(&tag["name"]).or_else(|| string(tag)))
        .take(5)
        .collect();
    Some(Series {
        id,
        title,
        cover: cover.unwrap_or_default(),
        description: string(&source["series_intro"]).unwrap_or_default(),
        badge: string(&source["episode_right_text"])
            .or_else(|| {
                count
                    .filter(|count| *count > 0)
                    .map(|count| format!("全 {count} 集"))
            })
            .unwrap_or_default(),
        tags,
    })
}

fn cards(rows: &[Value]) -> Vec<Series> {
    let mut seen = HashSet::new();
    rows.iter()
        .filter_map(series_from)
        .filter(|row| seen.insert(row.id.clone()))
        .collect()
}

fn parse_detail(source: &Value, id: &str) -> Result<Detail> {
    let series = series_from(source).context("官网未返回剧集详情")?;
    ensure!(series.id == id, "剧集详情与请求不匹配");
    let rows = source["vid_list"]
        .as_array()
        .context("官网未返回分集列表")?;
    ensure!(!rows.is_empty() && rows.len() <= 2000, "分集列表格式异常");
    // The website marks a prefix of the published episodes as accessible in this web session.
    let accessible = number(&source["accessible_episode_cnt"]).unwrap_or(0);
    let mut seen = HashSet::new();
    let mut episodes = Vec::with_capacity(rows.len());
    for (index, row) in rows.iter().enumerate() {
        let id = string(row)
            .filter(|value| valid_id(value))
            .context("分集编号格式异常")?;
        ensure!(seen.insert(id.clone()), "官网分集编号重复");
        let number = index + 1;
        episodes.push(Episode {
            id,
            number,
            available: number as u64 <= accessible,
            full: false,
        });
    }
    Ok(Detail {
        series,
        episodes,
        related: Vec::new(),
        reviews: Vec::new(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn reads_router_data_without_running_scripts() {
        let html = r#"window._ROUTER_DATA ; window._ROUTER_DATA = {"loaderData":{"detail_page":{"a":"}"}}};"#;
        assert_eq!(
            router_data(html).unwrap()["loaderData"]["detail_page"]["a"],
            "}"
        );
    }

    #[test]
    fn keeps_large_ids_and_explicit_web_availability() {
        let raw = json!({
            "series_id": "7706512650816982040", "series_name": "测试剧集",
            "accessible_episode_cnt": 1, "vid_list": [7706514645208534041_u64, "02"]
        });
        let detail = parse_detail(&raw, "7706512650816982040").unwrap();
        assert_eq!(detail.episodes[0].id, "7706514645208534041");
        assert!(detail.episodes[0].available);
        assert!(!detail.episodes[1].available);
        assert!(parse_detail(&raw, "2").is_err());
    }

    #[test]
    fn rejects_category_fallbacks_and_mismatched_pages() {
        let fixture = json!({
            "isSuccess": true,
            "categoryRoute": {"contentType": "real-drama", "enabled": true, "canonicalPath": "/category/real-drama/suspense"},
            "pagination": {"pageNum": 2, "totalPages": 3},
            "selectorList": [{"items": [{"selector_item_id": "suspense", "show_name": "悬疑"}]}],
            "recommendList": [{"series_id": "21", "series_name": "测试剧集"}]
        });
        let path = "category/real-drama/suspense";
        let parsed = parse_discovery(&fixture, Category::Real, path, "suspense", 2).unwrap();
        assert_eq!(parsed.items[0].id, "21");
        assert!(parsed.has_more);
        assert!(parse_discovery(&fixture, Category::Real, path, "suspense", 1).is_err());
        assert!(parse_discovery(&fixture, Category::Real, "category/real-drama", "", 2).is_err());
        assert!(!valid_topic("a/../b"));
    }

    #[test]
    fn episode_comments_match_both_ids_and_exclude_series_reviews() {
        let fixture = json!({"loaderData": {"player": {
            "seriesDetail": {"series_id": "101"},
            "vid": 202,
            "episodeSocialInfo": {"reviews": {"total_count": 7, "reviews": [{"content": "分集评论"}]}},
            "seriesSocialInfo": {"reviews": {"reviews": [{"content": "全剧评论"}]}}
        }}});
        let comments = parse_episode_comments(&fixture, "101", "202").unwrap();
        assert_eq!(comments.total, 7);
        assert_eq!(comments.items.len(), 1);
        assert_eq!(comments.items[0].content, "分集评论");
        assert!(parse_episode_comments(&fixture, "102", "202").is_err());
        assert!(parse_episode_comments(&fixture, "101", "203").is_err());
    }

    #[test]
    fn accepts_only_public_media_for_the_requested_episode() {
        let base = Url::parse(WEBSITE).unwrap();
        let element = |src: &str| {
            format!(
                "<script type=\"application/ld+json\">{{\"@type\":\"VideoObject\",\"contentUrl\":\"{src}\"}}</script>"
            )
        };
        assert!(preview_urls(&element("/video/public/12/360p.mp4"), &base, "12").is_ok());
        assert!(preview_urls(&element("/video/public/13/360p.mp4"), &base, "12").is_err());
        assert!(
            preview_urls(
                &element("https://example.test/video/public/12/360p.mp4"),
                &base,
                "12"
            )
            .is_err()
        );
        let html = [
            element("/video/public/12/720p.mp4"),
            element("/video/public/13/1080p.mp4"),
            element("/video/public/12/480p.mp4"),
            element("/video/public/12/480p.mp4"),
        ]
        .join("");
        assert_eq!(
            preview_urls(&html, &base, "12")
                .unwrap()
                .into_keys()
                .collect::<Vec<_>>(),
            [480, 720]
        );
    }

    #[test]
    fn external_navigation_is_limited_to_official_episode_pages() {
        assert!(is_official_player_url(
            "https://hongguoduanju.com/player/42/100"
        ));
        assert!(!is_official_player_url(
            "https://hongguoduanju.com.evil.test/player/42/100"
        ));
        assert!(!is_official_player_url(
            "https://hongguoduanju.com/player/42/100?redirect=other"
        ));
        assert!(!is_official_player_url(
            "https://hongguoduanju.com/player/42/abc"
        ));
    }
}
