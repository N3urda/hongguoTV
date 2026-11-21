// SPDX-License-Identifier: GPL-3.0-only
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, ensure};
use hongguo_playback::source::PlaybackScope;
use serde::{Deserialize, Serialize};

use crate::catalog::Series;

pub(crate) const MAX_HISTORY: usize = 200;
pub(crate) const MAX_FAVORITES: usize = 500;
pub(crate) const MAX_LATER: usize = 100;
pub(crate) const DEFAULT_QUALITIES: [u16; 3] = [480, 720, 1080];
const MAX_DATA_BYTES: u64 = 3 * 1024 * 1024;

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Theme {
    #[default]
    Dark,
    Light,
    Sand,
    Forest,
    Ocean,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum FrameMode {
    #[default]
    Fit,
    Crop,
    Stretch,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum StartPage {
    #[default]
    Home,
    Browse,
    Rank,
    Favorites,
    History,
    Settings,
    Search,
    Feed,
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum ClockMode {
    Off,
    #[default]
    Controls,
    Always,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(default)]
pub(crate) struct Preferences {
    pub(crate) theme: Theme,
    pub(crate) quality: u16,
    pub(crate) speed: u16,
    pub(crate) frame: FrameMode,
    pub(crate) rotation: u16,
    pub(crate) auto_next: bool,
    pub(crate) show_progress: bool,
    pub(crate) startup: StartPage,
    pub(crate) feed_autoplay: bool,
    pub(crate) clock: ClockMode,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            theme: Theme::Dark,
            quality: 1080,
            speed: 100,
            frame: FrameMode::Fit,
            rotation: 0,
            auto_next: true,
            show_progress: false,
            startup: StartPage::Home,
            feed_autoplay: true,
            clock: ClockMode::Controls,
        }
    }
}

impl Preferences {
    pub(crate) fn validate(&self) -> Result<()> {
        ensure!(DEFAULT_QUALITIES.contains(&self.quality), "默认清晰度无效");
        ensure!(
            [75, 100, 125, 150, 175, 200, 300].contains(&self.speed),
            "播放倍速无效"
        );
        ensure!(
            [0, 90, 180, 270].contains(&self.rotation),
            "画面旋转角度无效"
        );
        Ok(())
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub struct History {
    pub series: Series,
    pub episode: String,
    pub number: usize,
    pub position: u64,
    pub duration: u64,
    pub completed: bool,
    #[serde(default)]
    pub(crate) updated_at: u64,
    #[serde(default, rename = "from_tv")]
    pub(crate) full_source: bool,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Library {
    pub version: u32,
    pub favorites: Vec<Series>,
    pub history: Vec<History>,
    #[serde(default)]
    pub later: Vec<Series>,
    #[serde(default)]
    pub(crate) preferences: Preferences,
}

impl Default for Library {
    fn default() -> Self {
        Self {
            version: 1,
            favorites: Vec::new(),
            history: Vec::new(),
            later: Vec::new(),
            preferences: Preferences::default(),
        }
    }
}

#[derive(Deserialize)]
pub struct WatchPosition {
    pub position: u64,
    pub duration: u64,
    #[serde(default)]
    pub completed: bool,
    pub sequence: u64,
}

impl WatchPosition {
    pub(crate) fn validate(&self) -> Result<()> {
        ensure!(
            self.duration > 0 && self.duration <= 24 * 60 * 60 * 1000,
            "播放时长无效"
        );
        ensure!(self.position <= self.duration, "播放位置无效");
        Ok(())
    }
}

pub struct LibraryStore {
    file: PathBuf,
    data: Mutex<Library>,
    _lock: File,
}

impl LibraryStore {
    pub fn open(directory: &Path) -> Result<Self> {
        fs::create_dir_all(directory).context("无法创建本机资料目录")?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(directory.join("library.lock"))
            .context("无法打开本机资料目录")?;
        lock.try_lock()
            .map_err(|_| anyhow::anyhow!("资料库正在使用，请关闭已打开的红果窗口后重试"))?;
        let file = directory.join("library.json");
        let data = match File::open(&file) {
            Ok(existing) => {
                let mut raw = Vec::new();
                existing
                    .take(MAX_DATA_BYTES + 1)
                    .read_to_end(&mut raw)
                    .context("无法读取本机资料库")?;
                ensure!(raw.len() as u64 <= MAX_DATA_BYTES, "本机资料库超过大小限制");
                let library: Library = serde_json::from_slice(&raw)
                    .map_err(|_| anyhow::anyhow!("本机资料库损坏，原文件保留在资料目录"))?;
                ensure!(library.version == 1, "本机资料库版本不兼容");
                library.preferences.validate()?;
                library
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Library::default(),
            Err(error) => return Err(error).context("无法读取本机资料库"),
        };
        Ok(Self {
            file,
            data: Mutex::new(data),
            _lock: lock,
        })
    }

    fn data(&self) -> Result<MutexGuard<'_, Library>> {
        self.data
            .lock()
            .map_err(|_| anyhow::anyhow!("本机资料库暂不可用"))
    }

    pub fn read(&self) -> Result<Library> {
        Ok(self.data()?.clone())
    }

    pub(crate) fn preferences(&self) -> Result<Preferences> {
        Ok(self.data()?.preferences)
    }

    pub(crate) fn set_preferences(&self, preferences: Preferences) -> Result<()> {
        preferences.validate()?;
        self.update(|library| library.preferences = preferences)
    }

    pub fn history_for(&self, id: &str) -> Result<Option<History>> {
        Ok(self
            .data()?
            .history
            .iter()
            .find(|entry| entry.series.id == id)
            .cloned())
    }

    pub(crate) fn update(&self, change: impl FnOnce(&mut Library)) -> Result<()> {
        let mut current = self.data()?;
        let mut next = current.clone();
        change(&mut next);
        let encoded = serde_json::to_vec(&next).context("无法编码本机资料")?;
        ensure!(encoded.len() as u64 <= MAX_DATA_BYTES, "本机资料库已满");
        let mut temporary =
            tempfile::NamedTempFile::new_in(self.file.parent().expect("library directory"))
                .context("无法创建本机资料临时文件")?;
        temporary.write_all(&encoded).context("无法写入本机资料")?;
        temporary.as_file().sync_all().context("无法保存本机资料")?;
        temporary.persist(&self.file).context("无法替换本机资料")?;
        *current = next;
        Ok(())
    }

    pub fn set_favorite(&self, id: &str, series: Option<Series>) -> Result<()> {
        self.update(|library| {
            library.favorites.retain(|favorite| favorite.id != id);
            if let Some(series) = series {
                library.favorites.insert(0, series);
                library.favorites.truncate(MAX_FAVORITES);
            }
        })
    }

    pub(crate) fn set_later(&self, id: &str, series: Option<Series>) -> Result<()> {
        self.update(|library| {
            library.later.retain(|row| row.id != id);
            if let Some(series) = series {
                library.later.insert(0, series);
                library.later.truncate(MAX_LATER);
            }
        })
    }

    pub(crate) fn record(
        &self,
        series: &Series,
        episode: &str,
        number: usize,
        progress: &WatchPosition,
        scope: PlaybackScope,
    ) -> Result<()> {
        if progress.position == 0 {
            return Ok(());
        }
        let completed = progress.completed && progress.duration - progress.position <= 2000;
        let full_source = scope == PlaybackScope::Full;
        self.update(|library| {
            if !full_source
                && library
                    .history
                    .iter()
                    .any(|row| row.series.id == series.id && row.full_source)
            {
                return;
            }
            library.history.retain(|row| row.series.id != series.id);
            library.history.insert(
                0,
                History {
                    series: series.clone(),
                    episode: episode.to_owned(),
                    number,
                    position: progress.position,
                    duration: progress.duration,
                    completed,
                    updated_at: now_ms(),
                    full_source,
                },
            );
            library.history.truncate(MAX_HISTORY);
        })
    }
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as u64)
}

pub fn data_directory() -> Result<PathBuf> {
    if let Some(directory) = std::env::var_os("HONGGUO_RUST_DATA_DIR") {
        ensure!(!directory.is_empty(), "资料目录为空");
        return Ok(PathBuf::from(directory));
    }
    #[cfg(target_os = "windows")]
    {
        Ok(
            PathBuf::from(std::env::var_os("LOCALAPPDATA").context("找不到本机资料目录")?)
                .join("HongguoRust"),
        )
    }
    #[cfg(not(target_os = "windows"))]
    {
        let home = PathBuf::from(std::env::var_os("HOME").context("找不到本机资料目录")?);
        if cfg!(target_os = "macos") {
            Ok(home.join("Library/Application Support/HongguoRust"))
        } else {
            Ok(std::env::var_os("XDG_DATA_HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|| home.join(".local/share"))
                .join("hongguo-rust"))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn saves_in_one_transaction_and_recovers_on_reopen() {
        let directory = tempfile::tempdir().unwrap();
        let series = Series {
            id: "1".to_owned(),
            title: "测试".to_owned(),
            cover: String::new(),
            description: String::new(),
            badge: String::new(),
            tags: Vec::new(),
        };
        {
            let store = LibraryStore::open(directory.path()).unwrap();
            store.set_favorite("1", Some(series.clone())).unwrap();
            store
                .record(
                    &series,
                    "2",
                    1,
                    &WatchPosition {
                        position: 6000,
                        duration: 30000,
                        completed: false,
                        sequence: 1,
                    },
                    PlaybackScope::Full,
                )
                .unwrap();
            store
                .record(
                    &series,
                    "2",
                    1,
                    &WatchPosition {
                        position: 1000,
                        duration: 10000,
                        completed: false,
                        sequence: 1,
                    },
                    PlaybackScope::Preview,
                )
                .unwrap();
            assert!(LibraryStore::open(directory.path()).is_err());
        }
        let store = LibraryStore::open(directory.path()).unwrap();
        assert_eq!(store.read().unwrap().history[0].position, 6000);
        assert_eq!(store.read().unwrap().favorites[0].id, "1");
        store.set_favorite("1", None).unwrap();
        assert!(store.read().unwrap().favorites.is_empty());
    }
}
