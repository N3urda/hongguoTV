// SPDX-License-Identifier: GPL-3.0-only
use super::*;

#[derive(Clone, Serialize)]
pub struct CloudFavorite {
    pub series: Series,
    pub collected_at: u64,
}

#[derive(Clone, Serialize)]
pub struct CloudProgress {
    pub series: Series,
    pub episode: String,
    pub number: usize,
    pub position: u64,
    pub duration: u64,
    pub updated_at: u64,
    pub episode_count: usize,
    pub completed: bool,
}

impl Social {
    async fn cloud_request(
        &self,
        cookie: &HeaderValue,
        path: &str,
        params: &[(&str, &str)],
        body: Option<Value>,
    ) -> Result<RequestBuilder> {
        let device = self.registered.identity().await?;
        let (code, version, ua) = if body.is_some() {
            ("73932", "7.3.9.32", "71332")
        } else {
            ("72232", "7.2.2.32", "72232")
        };
        let mut values = profile(&device, code, version);
        add(&mut values, params.iter().copied());
        let body = body.as_ref().map(serde_json::to_vec).transpose()?;
        Ok(self
            .signed(&format!("{ACCOUNT_ORIGIN}{path}"), values, body, ua, false)?
            .header(COOKIE, cookie.clone()))
    }

    pub async fn cloud_favorites(&self, cookie: &HeaderValue) -> Result<Vec<CloudFavorite>> {
        let request = self
            .cloud_request(
                cookie,
                "/reading/bookapi/bookshelf/video/info/v1/",
                &[],
                None,
            )
            .await?;
        parse_favorites(&response(request, "账户收藏").await?)
    }

    pub async fn cloud_history(&self, cookie: &HeaderValue) -> Result<Vec<CloudProgress>> {
        let request = self
            .cloud_request(
                cookie,
                "/reading/bookapi/read_progress/list/v1/",
                &[("book_types", "2"), ("limit", "50")],
                None,
            )
            .await?;
        let mut history = parse_history(&response(request, "云端历史").await?)?;
        if history.is_empty() {
            return Ok(history);
        }
        let ids = history
            .iter()
            .map(|item| item.series.id.as_str())
            .collect::<Vec<_>>()
            .join(",");
        let prepared = self
            .cloud_request(
                cookie,
                "/reading/bookapi/multi-detail/v1/",
                &[("book_id", &ids), ("book_type", "2")],
                None,
            )
            .await?;
        // Keep usable resume positions if optional poster/title metadata is unavailable.
        if let Ok(metadata) = response(prepared, "云端剧集资料").await {
            let rows: BTreeMap<_, _> = metadata["data"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|row| (string(&row["book_id"]), row))
                .collect();
            for item in &mut history {
                if let Some(row) = rows.get(&item.series.id) {
                    let title = string(&row["book_name"]);
                    if !title.is_empty() {
                        item.series.title = title;
                    }
                    item.series.cover = cover(&string(&row["thumb_url"]));
                    item.series.description = string(&row["abstract"]);
                    item.series.badge = episode_badge(row, row);
                }
            }
        }
        Ok(history)
    }

    pub async fn prepare_cloud_favorite(
        &self,
        cookie: &HeaderValue,
        id: &str,
        add_favorite: bool,
    ) -> Result<CloudWrite> {
        ensure!(valid_id(id), "剧集编号无效");
        let mut entry = json!({"book_id":id,"book_type":2,"modify_time":now_ms(),
            "video_shelf_operate_type":if add_favorite {0} else {1}});
        if !add_favorite {
            entry["group_name"] = json!("");
        }
        let mut body = json!({"is_cancelled":false,"update_bookshelf_video_list":[entry]});
        if add_favorite {
            body["shark_extra"] = json!({"enter_from":"MainFragmentActivity","previous_page":"",
                "page_list":"MainFragmentActivity","is_active_behavior":"true","inactive_type":"0"});
        }
        Ok(CloudWrite(
            self.cloud_request(
                cookie,
                "/reading/bookapi/bookshelf/video/update/v1/",
                &[],
                Some(body),
            )
            .await?,
            "账户收藏",
        ))
    }

    pub async fn prepare_cloud_progress(
        &self,
        cookie: &HeaderValue,
        row: &CloudProgress,
    ) -> Result<CloudWrite> {
        ensure!(
            valid_id(&row.series.id)
                && valid_id(&row.episode)
                && row.number > 0
                && row.number <= row.episode_count,
            "播放进度无效"
        );
        let rate = if row.duration > 0 {
            (row.position as f64 / row.duration as f64).clamp(0.0, 1.0)
        } else {
            0.0
        };
        let payload = json!({"books":[{
            "book_id":row.series.id,"item_id":row.episode,"vid_index":row.number,
            "current_play_time":row.position,"current_total_time":row.duration,
            "episode_count":row.episode_count,"read_timestamp_ms":row.updated_at,"book_type":2,
            "progress_rate":rate.to_string(),"player_cumulative_total_duration":row.position
        }],"write_mode":1});
        Ok(CloudWrite(
            self.cloud_request(
                cookie,
                "/reading/bookapi/read_progress/upload/v1/",
                &[],
                Some(payload),
            )
            .await?,
            "云端进度",
        ))
    }
}

pub struct CloudWrite(RequestBuilder, &'static str);

impl CloudWrite {
    /// Read the remote state before attempting another write if submission is uncertain.
    pub async fn submit(self) -> Result<(), ProviderError> {
        response(self.0, self.1).await.map(|_| ())
    }
}

fn stamp(value: &Value) -> u64 {
    match unsigned(value).unwrap_or_default() {
        seconds @ 1..=9_999_999_999 => seconds * 1_000,
        millis => millis,
    }
}

fn episode_badge(video: &Value, row: &Value) -> String {
    [
        &video["episode_cnt"],
        &video["episode_count"],
        &row["drama_chapter_number"],
        &row["episode_cnt"],
    ]
    .into_iter()
    .filter_map(unsigned)
    .find(|n| *n > 0)
    .map_or_else(String::new, |count| format!("全 {count} 集"))
}

fn parse_favorites(root: &Value) -> Result<Vec<CloudFavorite>> {
    let rows = match root["data"].get("video_shelf_info") {
        Some(Value::Null) => return Ok(Vec::new()),
        Some(Value::Array(rows)) => rows,
        _ => bail!("账户收藏列表格式已变化"),
    };
    let mut parsed = Vec::new();
    for row in rows {
        let video = row["video_data"]
            .as_object()
            .map_or(row, |_| &row["video_data"]);
        let id = first(&[&video["series_id"], &row["series_id"], &row["book_id"]]);
        let title = first(&[&row["series_title"], &video["title"], &row["book_name"]]);
        if !valid_id(&id) || title.is_empty() {
            continue;
        }
        let thumbnail = first(&[
            &row["series_cover_url"],
            &video["cover"],
            &video["cover_url"],
            &video["thumb_url"],
            &row["thumb_url"],
        ]);
        let description = first(&[&row["series_intro"], &video["video_desc"], &row["abstract"]]);
        parsed.push(CloudFavorite {
            series: Series {
                id,
                title,
                cover: cover(&thumbnail),
                description,
                badge: episode_badge(video, row),
                tags: Vec::new(),
            },
            collected_at: stamp(&row["collect_time"]),
        });
    }
    ensure!(
        rows.is_empty() || !parsed.is_empty(),
        "账户收藏条目格式已变化"
    );
    parsed.sort_by_key(|row| std::cmp::Reverse(row.collected_at));
    let mut seen = HashSet::new();
    parsed.retain(|row| seen.insert(row.series.id.clone()));
    Ok(parsed)
}

fn parse_history(root: &Value) -> Result<Vec<CloudProgress>> {
    let rows = root["video_data"]
        .as_array()
        .or_else(|| root["data"]["video_data"].as_array())
        .context("云端历史格式已变化")?;
    let mut parsed = Vec::new();
    for row in rows {
        let id = first(&[&row["series_id"], &row["book_id"]]);
        let episode = first(&[&row["vid"], &row["item_id"]]);
        if !valid_id(&id) || !valid_id(&episode) {
            continue;
        }
        let count = unsigned(&row["episode_count"])
            .and_then(|n| usize::try_from(n).ok())
            .unwrap_or_default();
        let number = unsigned(&row["vid_index"])
            .and_then(|n| usize::try_from(n).ok())
            .unwrap_or_default();
        let duration = unsigned(&row["current_total_time"]).unwrap_or_default();
        let position = unsigned(&row["current_play_time"]).unwrap_or_default();
        let position = if duration > 0 {
            position.min(duration)
        } else {
            position
        };
        parsed.push(CloudProgress {
            series: Series {
                title: first(&[&row["series_title"], &row["book_name"]]),
                id: id.clone(),
                cover: String::new(),
                description: String::new(),
                badge: String::new(),
                tags: Vec::new(),
            },
            episode,
            number,
            position,
            duration,
            updated_at: stamp(&row["last_watch_time"]),
            episode_count: count,
            completed: duration > 0 && position > 0 && duration.saturating_sub(position) <= 2_000,
        });
        if let Some(item) = parsed.last_mut()
            && item.series.title.is_empty()
        {
            item.series.title = format!("剧集 {id}");
        }
    }
    ensure!(
        rows.is_empty() || !parsed.is_empty(),
        "云端历史条目格式已变化"
    );
    parsed.sort_by_key(|row| std::cmp::Reverse(row.updated_at));
    let mut seen = HashSet::new();
    parsed.retain(|row| seen.insert(row.series.id.clone()));
    parsed.truncate(50);
    Ok(parsed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keeps_latest_watch_even_if_episode_number_decreased() {
        let rows = parse_history(&json!({"data":{"video_data":[
            {"book_id":123,"vid":"789","vid_index":8,"current_play_time":25000,"last_watch_time":1700000000_u64},
            {"series_id":"123","vid":"456","vid_index":2,"current_play_time":97900,"current_total_time":98000,"last_watch_time":1700000010000_u64}
        ]}})).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(
            (&*rows[0].episode, rows[0].number, rows[0].position),
            ("456", 2, 97900)
        );
        assert!(rows[0].completed);
        assert!(parse_history(&json!({"data":{}})).is_err());
    }

    #[test]
    fn rejects_a_changed_shelf_format_instead_of_returning_an_empty_shelf() {
        assert!(parse_favorites(&json!({"data":{}})).is_err());
        assert!(
            parse_favorites(&json!({"data":{"video_shelf_info":null}}))
                .unwrap()
                .is_empty()
        );
        let rows = parse_favorites(&json!({"data":{"video_shelf_info":[
            {"book_id":"123","book_name":"旧标题","collect_time":1700000000_u64},
            {"video_data":{"series_id":123,"title":"新标题"},"collect_time":1700000010_u64}
        ]}}))
        .unwrap();
        assert_eq!(rows[0].series.title, "新标题");
        assert_eq!(rows.len(), 1);
    }
}
