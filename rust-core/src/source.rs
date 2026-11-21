// SPDX-License-Identifier: GPL-3.0-only
use std::{
    collections::{BTreeMap, HashSet},
    fs::File,
    io::Read,
    path::Path,
    time::Duration,
};

use anyhow::{Context, Result, ensure};
use reqwest::{Client, ClientBuilder, Response, StatusCode, Url, header, redirect};
use serde::{Deserialize, Serialize};

use crate::range::{ByteRange, validate_response};
use crate::{Rendition, select_rendition};

const MAX_MANIFEST_BYTES: u64 = 2 * 1024 * 1024;
const MAX_MEDIA_BYTES: u64 = 2 * 1024 * 1024 * 1024;

pub(crate) fn media_client_builder() -> ClientBuilder {
    Client::builder()
        .https_only(true)
        .no_gzip()
        .no_brotli()
        .connect_timeout(Duration::from_secs(8))
        .read_timeout(Duration::from_secs(15))
        .redirect(redirect::Policy::none())
}

pub fn authorized_media_client() -> Result<Client> {
    media_client_builder()
        .user_agent(concat!("hongguo-playback/", env!("CARGO_PKG_VERSION")))
        .build()
        .context("无法初始化正式源连接")
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PlaybackScope {
    Full,
    Preview,
}

#[derive(Clone)]
pub struct Ladder {
    urls: BTreeMap<u16, Url>,
    referer: Option<Url>,
    scope: PlaybackScope,
}

pub struct SelectedSource {
    pub url: Url,
    pub referer: Option<Url>,
    pub quality: u16,
    pub qualities: Vec<u16>,
    pub scope: PlaybackScope,
}

impl Ladder {
    pub(crate) fn preview(urls: BTreeMap<u16, Url>, page: Url) -> Self {
        Self {
            urls,
            referer: Some(page),
            scope: PlaybackScope::Preview,
        }
    }

    pub fn select(mut self, maximum: u16, exact: Option<u16>) -> Option<SelectedSource> {
        let qualities: Vec<_> = self.urls.keys().copied().collect();
        let quality = match exact {
            Some(quality) => quality,
            None => {
                let profiles: Vec<_> = qualities
                    .iter()
                    .map(|quality| Rendition {
                        quality: i32::from(*quality),
                        avc: false,
                        supported: true,
                    })
                    .collect();
                qualities[select_rendition(&profiles, i32::from(maximum))?]
            }
        };
        Some(SelectedSource {
            url: self.urls.remove(&quality)?,
            referer: self.referer,
            quality,
            qualities,
            scope: self.scope,
        })
    }
}

/// The operator supplies playable HTTPS URLs from a source authorized for this app.
/// Only the configured origins can receive video requests.
#[derive(Default)]
pub struct AuthorizedSources {
    by_series: BTreeMap<String, BTreeMap<String, Ladder>>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceManifest {
    version: u32,
    origins: Vec<String>,
    episodes: Vec<EpisodeSources>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct EpisodeSources {
    series: String,
    episode: String,
    sources: Vec<SourceRow>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceRow {
    quality: u16,
    url: String,
}

impl AuthorizedSources {
    pub fn load(path: &Path) -> Result<Self> {
        let mut bytes = Vec::new();
        File::open(path)
            .context("无法打开正式源清单")?
            .take(MAX_MANIFEST_BYTES + 1)
            .read_to_end(&mut bytes)
            .context("无法读取正式源清单")?;
        Self::decode(&bytes)
    }

    fn decode(bytes: &[u8]) -> Result<Self> {
        ensure!(
            bytes.len() as u64 <= MAX_MANIFEST_BYTES,
            "正式源清单超过 2 MB"
        );
        let manifest: SourceManifest =
            serde_json::from_slice(bytes).map_err(|_| anyhow::anyhow!("正式源清单格式无效"))?;
        ensure!(manifest.version == 1, "正式源清单版本不兼容");
        let mut origins = HashSet::new();
        for raw in &manifest.origins {
            let url = https_url(raw)?;
            ensure!(
                url.path() == "/" && url.query().is_none(),
                "正式源域名配置无效"
            );
            origins.insert(url.origin());
        }
        let mut result = Self::default();
        for row in manifest.episodes {
            ensure!(
                valid_id(&row.series) && valid_id(&row.episode),
                "正式源分集编号无效"
            );
            ensure!(
                !row.sources.is_empty() && row.sources.len() <= 16,
                "正式源清晰度列表无效"
            );
            let mut urls = BTreeMap::new();
            for source in row.sources {
                let url = https_url(&source.url)?;
                ensure!(
                    origins.contains(&url.origin()),
                    "正式源地址超出已配置的域名"
                );
                ensure!(
                    source.quality > 0 && urls.insert(source.quality, url).is_none(),
                    "正式源清晰度无效或重复"
                );
            }
            let ladder = Ladder {
                urls,
                referer: None,
                scope: PlaybackScope::Full,
            };
            let series = result.by_series.entry(row.series).or_default();
            ensure!(
                series.insert(row.episode, ladder).is_none(),
                "正式源清单包含重复分集"
            );
        }
        Ok(result)
    }

    pub fn for_episode(&self, series: &str, episode: &str) -> Option<&Ladder> {
        self.by_series.get(series)?.get(episode)
    }
}

fn https_url(value: &str) -> Result<Url> {
    let url = Url::parse(value).map_err(|_| anyhow::anyhow!("正式源地址格式无效"))?;
    ensure!(
        url.scheme() == "https"
            && url.host().is_some()
            && url.username().is_empty()
            && url.password().is_none()
            && url.fragment().is_none(),
        "正式源地址须使用 HTTPS"
    );
    Ok(url)
}

pub fn valid_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 30 && value.bytes().all(|b| b.is_ascii_digit())
}

pub async fn read_limited(mut response: Response, max: usize) -> Result<Vec<u8>> {
    ensure!(
        response
            .content_length()
            .is_none_or(|size| size <= max as u64),
        "响应超过大小限制"
    );
    let mut bytes = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| anyhow::anyhow!("读取响应失败，请重试"))?
    {
        ensure!(
            chunk.len() <= max.saturating_sub(bytes.len()),
            "响应超过大小限制"
        );
        bytes.extend_from_slice(&chunk);
    }
    Ok(bytes)
}

/// Ranged MP4 reads shared by native playback adapters.
pub struct RemoteMp4 {
    client: Client,
    url: Url,
    referer: Option<Url>,
    total: u64,
}

impl RemoteMp4 {
    pub async fn open(client: Client, url: Url, referer: Option<Url>) -> Result<Self> {
        let mut media = Self {
            client,
            url,
            referer,
            total: 0,
        };
        let probe = ByteRange { start: 0, end: 31 };
        let (response, total) = media.request(probe).await?;
        let sample = read_limited(response, 32).await?;
        ensure!(
            sample.len() == 32 && &sample[4..8] == b"ftyp",
            "取源未返回可播放的 MP4"
        );
        media.total = total;
        Ok(media)
    }

    pub fn size(&self) -> u64 {
        self.total
    }

    pub async fn read_range(&self, part: ByteRange) -> Result<Response> {
        self.request(part).await.map(|(response, _)| response)
    }

    async fn request(&self, part: ByteRange) -> Result<(Response, u64)> {
        let mut request = self
            .client
            .get(self.url.clone())
            .header(header::RANGE, part.header())
            .header(header::ACCEPT_ENCODING, "identity");
        if let Some(referer) = &self.referer {
            request = request.header(header::REFERER, referer.as_str());
        }
        let response = request
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("获取视频失败，请检查网络后重试"))?;
        ensure!(
            response.status() == StatusCode::PARTIAL_CONTENT,
            "视频请求失败（HTTP {}）",
            response.status().as_u16()
        );
        let content_range = response
            .headers()
            .get(header::CONTENT_RANGE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default();
        let expected_total = (self.total > 0).then_some(self.total);
        let (returned, total) = validate_response(content_range, part, expected_total)
            .map_err(|_| anyhow::anyhow!("视频分段信息异常"))?;
        let mime = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok());
        ensure!(
            returned == part
                && total <= MAX_MEDIA_BYTES
                && mime.is_some_and(|value| matches!(
                    value.split(';').next(),
                    Some("video/mp4" | "application/octet-stream")
                ))
                && response
                    .content_length()
                    .is_none_or(|length| length == part.length()),
            "视频分段信息异常"
        );
        Ok((response, total))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn manifest(url: &str) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({
            "version": 1,
            "origins": ["https://media.example"],
            "episodes": [{
                "series": "100", "episode": "200",
                "sources": [
                    {"quality": 480, "url": "https://media.example/episode-a.mp4"},
                    {"quality": 1080, "url": url}
                ]
            }]
        }))
        .unwrap()
    }

    #[test]
    fn selects_only_the_requested_episodes_authorized_renditions() {
        let sources =
            AuthorizedSources::decode(&manifest("https://media.example/episode-b.mp4")).unwrap();
        let ladder = sources.for_episode("100", "200").unwrap();
        assert!(sources.for_episode("101", "200").is_none());
        assert!(sources.for_episode("100", "201").is_none());
        assert!(ladder.clone().select(1080, Some(720)).is_none());
        let chosen = ladder.clone().select(720, None).unwrap();
        assert_eq!(chosen.quality, 480);
        assert_eq!(chosen.scope, PlaybackScope::Full);
        assert_eq!(chosen.qualities, [480, 1080]);
        assert!(chosen.referer.is_none());
        assert_eq!(
            ladder.clone().select(480, Some(1080)).unwrap().quality,
            1080
        );
    }

    #[test]
    fn rejects_unlisted_origins_and_insecure_or_credential_bearing_urls() {
        for address in [
            "https://media.example.invalid/episode.mp4",
            "https://media.example:8443/episode.mp4",
            "http://media.example/episode.mp4",
            "https://user:password@media.example/episode.mp4",
        ] {
            assert!(AuthorizedSources::decode(&manifest(address)).is_err());
        }
    }
}
