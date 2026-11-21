// SPDX-License-Identifier: GPL-3.0-only
use std::{
    cmp::Reverse,
    collections::{HashMap, HashSet},
};

use anyhow::{Result, ensure};
use reqwest::Url;
use serde::{Deserialize, Serialize};

use crate::catalog::{Series, valid_id};
use crate::library::{FrameMode, History, Library, MAX_FAVORITES, MAX_HISTORY, MAX_LATER, now_ms};

const FORMAT: &str = "hongguotv.library";
pub(crate) const MAX_BYTES: usize = 2 * 1024 * 1024;

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Backup {
    format: String,
    version: u32,
    #[serde(default)]
    created_at: u64,
    favorites: Vec<BackupSeries>,
    history: Vec<BackupHistory>,
    #[serde(default)]
    later: Vec<BackupSeries>,
    #[serde(skip_deserializing)]
    watched: Vec<String>,
    #[serde(skip_deserializing)]
    searches: Vec<()>,
    #[serde(skip_deserializing)]
    settings: Option<BackupSettings>,
}

#[derive(Deserialize, Serialize)]
struct BackupSeries {
    id: String,
    title: String,
    cover: String,
    #[serde(default)]
    description: String,
    badge: String,
    tags: String,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct BackupHistory {
    series: BackupSeries,
    episode_id: String,
    episode_index: usize,
    position: u64,
    duration: u64,
    completed: bool,
    updated_at: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    desktop_preview_completed: Option<bool>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct BackupSettings {
    quality: u16,
    speed: f32,
    auto_next: bool,
    #[serde(rename = "type")]
    content_type: String,
    frame: String,
    #[serde(default)]
    rotation: u16,
    #[serde(default)]
    always_show_progress: bool,
}

pub(crate) struct Imported {
    favorites: Vec<Series>,
    history: Vec<History>,
    later: Vec<Series>,
}

#[derive(Serialize)]
pub(crate) struct ImportCounts {
    favorites: usize,
    history: usize,
    later: usize,
}

fn text_len(value: &str, max: usize) -> bool {
    value.encode_utf16().count() <= max && !value.chars().any(char::is_control)
}

fn clean_text(value: &str, max: usize) -> String {
    let mut text = String::new();
    let mut length = 0;
    for character in value.chars() {
        length += character.len_utf16();
        if length > max {
            break;
        }
        text.push(if character.is_control() {
            ' '
        } else {
            character
        });
    }
    text
}

fn clean_cover(value: String) -> String {
    if !text_len(&value, 2048) {
        return String::new();
    }
    let valid = Url::parse(&value).is_ok_and(|url| {
        url.scheme() == "https"
            && url.username().is_empty()
            && url.password().is_none()
            && url.domain().is_some_and(|domain| {
                let domain = domain.trim_end_matches('.');
                domain.contains('.')
                    && ![".localhost", ".local", ".lan", ".internal"]
                        .iter()
                        .any(|suffix| domain.ends_with(suffix))
            })
    });
    if valid { value } else { String::new() }
}

impl From<&Series> for BackupSeries {
    fn from(series: &Series) -> Self {
        Self {
            id: series.id.clone(),
            title: clean_text(&series.title, 200),
            cover: clean_cover(series.cover.clone()),
            description: String::new(),
            badge: clean_text(&series.badge, 100),
            tags: clean_text(&series.tags.join(" · "), 200),
        }
    }
}

impl TryFrom<BackupSeries> for Series {
    type Error = anyhow::Error;

    fn try_from(row: BackupSeries) -> Result<Self> {
        ensure!(valid_id(&row.id), "备份包含无效的剧集编号");
        ensure!(
            text_len(&row.title, 200)
                && !row.title.trim().is_empty()
                && text_len(&row.badge, 100)
                && text_len(&row.tags, 200),
            "备份中的剧集信息无效"
        );
        Ok(Self {
            id: row.id,
            title: row.title,
            cover: clean_cover(row.cover),
            description: String::new(),
            badge: row.badge,
            tags: row
                .tags
                .split('·')
                .map(str::trim)
                .filter(|tag| !tag.is_empty())
                .map(ToOwned::to_owned)
                .collect(),
        })
    }
}

pub(crate) fn encode(library: &Library) -> Result<Vec<u8>> {
    let preferences = library.preferences;
    let backup = Backup {
        format: FORMAT.to_owned(),
        version: 1,
        created_at: now_ms(),
        favorites: library.favorites.iter().map(Into::into).collect(),
        history: library
            .history
            .iter()
            .map(|row| BackupHistory {
                series: (&row.series).into(),
                episode_id: row.episode.clone(),
                episode_index: row.number.saturating_sub(1),
                position: row.position,
                duration: row.duration,
                completed: row.from_tv && row.completed,
                updated_at: row.updated_at,
                desktop_preview_completed: (!row.from_tv).then_some(row.completed),
            })
            .collect(),
        later: library.later.iter().map(Into::into).collect(),
        watched: Vec::new(),
        searches: Vec::new(),
        settings: Some(BackupSettings {
            quality: 1080,
            speed: f32::from(preferences.speed) / 100.0,
            auto_next: preferences.auto_next,
            content_type: "short".to_owned(),
            frame: match preferences.frame {
                FrameMode::Fit => "FIT",
                FrameMode::Crop => "ZOOM",
                FrameMode::Stretch => "FILL",
            }
            .to_owned(),
            rotation: preferences.rotation,
            always_show_progress: preferences.show_progress,
        }),
    };
    let bytes = serde_json::to_vec(&backup)?;
    ensure!(bytes.len() <= MAX_BYTES, "备份超过 2 MB 限制");
    Ok(bytes)
}

pub(crate) fn decode(bytes: &[u8]) -> Result<Imported> {
    ensure!(bytes.len() <= MAX_BYTES, "备份超过 2 MB 限制");
    let backup: Backup =
        serde_json::from_slice(bytes).map_err(|_| anyhow::anyhow!("备份文件格式无效"))?;
    ensure!(
        backup.format == FORMAT && backup.version == 1,
        "不支持的备份格式或版本"
    );
    ensure!(
        backup.favorites.len() <= MAX_FAVORITES
            && backup.history.len() <= MAX_HISTORY
            && backup.later.len() <= MAX_LATER,
        "备份剧集数量超过限制"
    );
    let favorites = decode_series(backup.favorites)?;
    let later = decode_series(backup.later)?;
    let mut ids = HashSet::new();
    let mut history = Vec::new();
    for row in backup.history {
        let series = Series::try_from(row.series)?;
        ensure!(ids.insert(series.id.clone()), "备份包含重复历史");
        ensure!(
            valid_id(&row.episode_id)
                && row.episode_index <= 100_000
                && row.duration <= 604_800_000
                && row.position <= 604_800_000
                && row.updated_at <= 4_102_444_800_000,
            "备份中的观看记录无效"
        );
        history.push(History {
            series,
            episode: row.episode_id,
            number: row.episode_index + 1,
            position: if row.duration == 0 {
                row.position
            } else {
                row.position.min(row.duration)
            },
            duration: row.duration,
            completed: row.desktop_preview_completed.unwrap_or(row.completed),
            updated_at: row.updated_at,
            from_tv: row.desktop_preview_completed.is_none(),
        });
    }
    Ok(Imported {
        favorites,
        history,
        later,
    })
}

fn decode_series(rows: Vec<BackupSeries>) -> Result<Vec<Series>> {
    let mut ids = HashSet::new();
    rows.into_iter()
        .map(|row| {
            ensure!(ids.insert(row.id.clone()), "备份包含重复剧集");
            row.try_into()
        })
        .collect()
}

impl Imported {
    pub(crate) fn counts(&self) -> ImportCounts {
        ImportCounts {
            favorites: self.favorites.len(),
            history: self.history.len(),
            later: self.later.len(),
        }
    }

    pub(crate) fn merge(self, library: &mut Library) {
        merge_series(&mut library.favorites, self.favorites, MAX_FAVORITES);
        merge_series(&mut library.later, self.later, MAX_LATER);
        let indices: HashMap<String, usize> = library
            .history
            .iter()
            .enumerate()
            .map(|(index, row)| (row.series.id.clone(), index))
            .collect();
        for row in self.history {
            if let Some(index) = indices.get(&row.series.id) {
                let prior = &mut library.history[*index];
                if (row.from_tv && !prior.from_tv)
                    || (row.from_tv == prior.from_tv && row.updated_at > prior.updated_at)
                {
                    *prior = row;
                }
            } else {
                library.history.push(row);
            }
        }
        library.history.sort_by_key(|row| Reverse(row.updated_at));
        library.history.truncate(MAX_HISTORY);
    }
}

fn merge_series(local: &mut Vec<Series>, imported: Vec<Series>, capacity: usize) {
    let mut existing: HashSet<String> = local.iter().map(|row| row.id.clone()).collect();
    let remaining = capacity.saturating_sub(local.len());
    local.extend(
        imported
            .into_iter()
            .filter(|row| existing.insert(row.id.clone()))
            .take(remaining),
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn tv_backup() -> serde_json::Value {
        let series = json!({
            "id": "21", "title": "测试短剧", "cover": "https://example.com/test.jpg",
            "description": "", "badge": "10 集", "tags": "短剧 · 都市"
        });
        json!({
            "format": FORMAT, "version": 1, "createdAt": 120_000,
            "favorites": [series.clone()], "later": [series.clone()],
            "history": [{
                "series": series, "episodeId": "22", "episodeIndex": 4,
                "position": 15000, "duration": 60_000, "completed": false, "updatedAt": 120_000
            }],
            "watched": [], "searches": [],
            "settings": {"quality": 1080, "speed": 1.25, "type": "comic", "frame": "ZOOM", "autoNext": true}
        })
    }

    #[test]
    fn transfers_tv_series_and_progress_without_losing_existing_progress() {
        let mut library = Library::default();
        let input = tv_backup();
        decode(&serde_json::to_vec(&input).unwrap())
            .unwrap()
            .merge(&mut library);
        assert_eq!(library.favorites[0].id, "21");
        assert_eq!(library.later[0].id, "21");
        assert_eq!(library.history[0].number, 5);
        assert_eq!(library.history[0].position, 15_000);
        let mut earlier = input.clone();
        earlier["history"][0]["updatedAt"] = json!(80_000);
        earlier["history"][0]["position"] = json!(5_000);
        decode(&serde_json::to_vec(&earlier).unwrap())
            .unwrap()
            .merge(&mut library);
        assert_eq!(library.history[0].position, 15_000);

        let serialized: serde_json::Value =
            serde_json::from_slice(&encode(&library).unwrap()).unwrap();
        assert_eq!(serialized["history"][0]["episodeIndex"], 4);
        assert_eq!(serialized["history"][0]["episodeId"], "22");
        assert_eq!(serialized["favorites"][0]["tags"], "短剧 · 都市");

        let mut preview = tv_backup();
        preview["history"][0]["desktopPreviewCompleted"] = json!(true);
        preview["history"][0]["updatedAt"] = json!(160_000);
        decode(&serde_json::to_vec(&preview).unwrap())
            .unwrap()
            .merge(&mut library);
        assert!(!library.history[0].completed);
        assert_eq!(library.history[0].updated_at, 120_000);
        let mut desktop = Library::default();
        decode(&serde_json::to_vec(&preview).unwrap())
            .unwrap()
            .merge(&mut desktop);
        let backup: serde_json::Value = serde_json::from_slice(&encode(&desktop).unwrap()).unwrap();
        assert_eq!(backup["history"][0]["completed"], false);
        assert_eq!(backup["history"][0]["desktopPreviewCompleted"], true);
    }

    #[test]
    fn rejects_a_duplicate_series_and_filters_local_image_addresses() {
        let mut fixture = tv_backup();
        fixture["later"][0]["cover"] = json!("https://127.0.0.1/example.jpg");
        let imported = decode(&serde_json::to_vec(&fixture).unwrap()).unwrap();
        assert!(imported.later[0].cover.is_empty());
        fixture["later"] = json!([fixture["later"][0].clone(), fixture["later"][0].clone()]);
        assert!(decode(&serde_json::to_vec(&fixture).unwrap()).is_err());
    }
}
