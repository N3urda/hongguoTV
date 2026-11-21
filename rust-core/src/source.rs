// SPDX-License-Identifier: GPL-3.0-only
use std::{
    collections::{BTreeMap, BTreeSet, HashSet},
    fs::File,
    io::Read,
    path::Path,
    time::Duration,
};

use anyhow::{Context, Result, ensure};
use reqwest::{Client, ClientBuilder, Response, StatusCode, Url, header, redirect};
use serde::{Deserialize, Serialize};

use crate::model::{PlaybackModel, PlaybackVariant};
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
    variants: Vec<PlaybackVariant>,
    referer: Option<Url>,
    scope: PlaybackScope,
}

pub struct SelectedSource {
    pub url: Url,
    pub backup_url: Option<Url>,
    pub referer: Option<Url>,
    pub quality: u16,
    pub qualities: Vec<u16>,
    pub scope: PlaybackScope,
    pub key: Option<[u8; 16]>,
}

impl Ladder {
    #[cfg(feature = "registered")]
    pub(crate) fn registered(model: PlaybackModel) -> Self {
        Self {
            variants: model.into_variants(),
            referer: None,
            scope: PlaybackScope::Full,
        }
    }

    pub(crate) fn preview(urls: BTreeMap<u16, Url>, page: Url) -> Self {
        Self {
            variants: urls
                .into_iter()
                .map(|(quality, url)| PlaybackVariant {
                    quality,
                    url,
                    backup_url: None,
                    codec: String::new(),
                    width: None,
                    height: None,
                    key: None,
                })
                .collect(),
            referer: Some(page),
            scope: PlaybackScope::Preview,
        }
    }

    pub fn select(self, maximum: u16, exact: Option<u16>) -> Option<SelectedSource> {
        let qualities: Vec<_> = self
            .variants
            .iter()
            .map(|row| row.quality)
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        let mut variants: Vec<_> = self
            .variants
            .into_iter()
            .filter(|row| exact.is_none_or(|quality| quality == row.quality))
            .collect();
        let profiles: Vec<_> = variants
            .iter()
            .map(|row| Rendition {
                quality: i32::from(row.quality),
                avc: row.is_avc(),
                supported: true,
            })
            .collect();
        let index = select_rendition(&profiles, i32::from(exact.unwrap_or(maximum)))?;
        let row = variants.swap_remove(index);
        Some(SelectedSource {
            url: row.url,
            backup_url: row.backup_url,
            referer: self.referer,
            quality: row.quality,
            qualities,
            scope: self.scope,
            key: row.key,
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
    sources: Option<Vec<SourceRow>>,
    video_model: Option<serde_json::Value>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct SourceRow {
    quality: u16,
    url: String,
    backup_url: Option<String>,
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
            let variants = match (row.sources, row.video_model) {
                (Some(sources), None) => {
                    ensure!(
                        !sources.is_empty() && sources.len() <= 16,
                        "正式源清晰度列表无效"
                    );
                    let mut qualities = HashSet::new();
                    sources
                        .into_iter()
                        .map(|source| {
                            ensure!(
                                source.quality > 0 && qualities.insert(source.quality),
                                "正式源清晰度无效或重复"
                            );
                            Ok(PlaybackVariant {
                                quality: source.quality,
                                url: https_url(&source.url)?,
                                backup_url: source
                                    .backup_url
                                    .as_deref()
                                    .map(https_url)
                                    .transpose()?,
                                codec: String::new(),
                                width: None,
                                height: None,
                                key: None,
                            })
                        })
                        .collect::<Result<Vec<_>>>()?
                }
                (None, Some(model)) => PlaybackModel::from_value(&model)?.into_variants(),
                _ => anyhow::bail!("分集须提供 sources 或 video_model 中的一种"),
            };
            for variant in &variants {
                ensure!(
                    std::iter::once(&variant.url)
                        .chain(variant.backup_url.iter())
                        .all(|url| origins.contains(&url.origin())),
                    "正式源地址超出已配置的域名"
                );
            }
            let ladder = Ladder {
                variants,
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

pub(crate) fn https_url(value: &str) -> Result<Url> {
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
    #[cfg(feature = "registered")]
    decryption: Option<crate::cenc::Prepared>,
}

impl RemoteMp4 {
    /// Try the same rendition's authorized backup only while opening the source.
    /// Once opened, all ranges stay on that file; bytes from different files are
    /// never spliced together after a mid-stream failure.
    pub async fn open_source(client: Client, source: &SelectedSource) -> Result<Self> {
        match Self::open_selected(client.clone(), source.url.clone(), source).await {
            Ok(media) => Ok(media),
            Err(primary) => match source.backup_url.as_ref().filter(|url| *url != &source.url) {
                Some(backup) => Self::open_selected(client, backup.clone(), source)
                    .await
                    .context("主源和备用源均无法播放"),
                None => Err(primary),
            },
        }
    }

    async fn open_selected(client: Client, url: Url, source: &SelectedSource) -> Result<Self> {
        #[allow(unused_mut)]
        let mut media = Self::open(client, url, source.referer.clone()).await?;
        if let Some(key) = source.key {
            #[cfg(feature = "registered")]
            {
                let part = ByteRange {
                    start: 0,
                    end: 65535.min(media.total - 1),
                };
                let prefix = read_limited(media.request(part).await?.0, 65536).await?;
                let end = crate::cenc::header_end(&prefix, media.total)?;
                let response = media
                    .request(ByteRange {
                        start: 0,
                        end: end as u64 - 1,
                    })
                    .await?
                    .0;
                let header = read_limited(response, end).await?;
                ensure!(header.len() == end, "视频索引不完整");
                media.decryption = Some(crate::cenc::Prepared::parse(header, key, media.total)?);
            }
            #[cfg(not(feature = "registered"))]
            {
                let _ = key;
                anyhow::bail!("当前构建不支持视频解密");
            }
        }
        Ok(media)
    }

    /// Transform each range chunk at its absolute file offset, including seeks
    /// beginning inside an encrypted subsample. All key/index logic stays in core.
    pub fn decode_chunk(&self, start: u64, data: &[u8]) -> Result<Vec<u8>> {
        ensure!(
            start <= self.total && data.len() as u64 <= self.total - start,
            "视频分段越界"
        );
        #[cfg(feature = "registered")]
        if let Some(prepared) = &self.decryption {
            return prepared.decrypt(start, data);
        }
        Ok(data.to_vec())
    }

    pub async fn open(client: Client, url: Url, referer: Option<Url>) -> Result<Self> {
        let mut media = Self {
            client,
            url,
            referer,
            total: 0,
            #[cfg(feature = "registered")]
            decryption: None,
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

    #[test]
    fn model_ladders_keep_real_tiers_and_prefer_avc_at_the_requested_quality() {
        let bytes = serde_json::to_vec(&serde_json::json!({
            "version": 1, "origins": ["https://media.example", "https://backup.example"],
            "episodes": [{"series": "100", "episode": "200", "video_model": {
                "video_list": [
                    {"quality_desc": "720p", "codec_type": "hevc", "main_url": "https://media.example/hevc.mp4"},
                    {"quality_desc": "720p", "codec_type": "avc1", "main_url": "https://media.example/avc.mp4", "backup_url_1": "https://backup.example/avc.mp4"},
                    {"quality_desc": "1080p", "codec_type": "h264", "main_url": "https://media.example/1080.mp4"}
                ]
            }}]
        })).unwrap();
        let sources = AuthorizedSources::decode(&bytes).unwrap();
        let ladder = sources.for_episode("100", "200").unwrap();
        let selected = ladder.clone().select(720, None).unwrap();
        assert_eq!(selected.qualities, [720, 1080]);
        assert_eq!(selected.url.path(), "/avc.mp4");
        assert_eq!(
            selected.backup_url.unwrap().host_str(),
            Some("backup.example")
        );
        assert_eq!(
            ladder.clone().select(480, Some(720)).unwrap().url.path(),
            "/avc.mp4"
        );
        assert_eq!(ladder.clone().select(480, None).unwrap().quality, 720);
        assert!(ladder.clone().select(1080, Some(480)).is_none());
    }

    #[test]
    fn validates_backup_origins_and_rejects_ambiguous_episode_configuration() {
        let mut manifest: serde_json::Value =
            serde_json::from_slice(&manifest("https://media.example/b.mp4")).unwrap();
        manifest["episodes"][0]["sources"][0]["backup_url"] =
            "https://unlisted.example/a.mp4".into();
        assert!(AuthorizedSources::decode(&serde_json::to_vec(&manifest).unwrap()).is_err());
        let episode = &mut manifest["episodes"][0];
        episode.as_object_mut().unwrap().remove("sources");
        episode["video_model"] = serde_json::json!({"video_list": [{
            "quality_desc": "720", "main_url": "https://media.example/a.mp4",
            "backup_url_1": "https://unlisted.example/a.mp4"
        }]});
        assert!(AuthorizedSources::decode(&serde_json::to_vec(&manifest).unwrap()).is_err());
        manifest["episodes"][0]["video_model"]["video_list"][0]["backup_url_1"] =
            "https://media.example/backup.mp4".into();
        assert!(AuthorizedSources::decode(&serde_json::to_vec(&manifest).unwrap()).is_ok());
        manifest["episodes"][0]["sources"] = serde_json::json!([]);
        assert!(AuthorizedSources::decode(&serde_json::to_vec(&manifest).unwrap()).is_err());
    }
}

#[cfg(all(test, feature = "registered"))]
mod encrypted_tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    #[tokio::test]
    async fn opens_encrypted_mp4_and_decodes_seeks_and_header_with_one_source() {
        let (data, plain, key, _) = crate::cenc::test_fixture();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = Url::parse(&format!("http://{}/clip", listener.local_addr().unwrap())).unwrap();
        let task = tokio::spawn(async move {
            loop {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut request = Vec::new();
                let mut b = [0];
                while !request.ends_with(b"\r\n\r\n") {
                    stream.read_exact(&mut b).await.unwrap();
                    request.push(b[0]);
                }
                let request = String::from_utf8(request).unwrap();
                let range = request
                    .lines()
                    .find_map(|s| s.strip_prefix("range: "))
                    .unwrap();
                let part = ByteRange::from_header(range, data.len() as u64).unwrap();
                stream.write_all(format!("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Range: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",part.content_range(data.len() as u64),part.length()).as_bytes()).await.unwrap();
                stream
                    .write_all(&data[part.start as usize..=part.end as usize])
                    .await
                    .unwrap();
            }
        });
        let selected = SelectedSource {
            url,
            backup_url: None,
            referer: None,
            quality: 720,
            qualities: vec![720],
            scope: PlaybackScope::Full,
            key: Some(key),
        };
        let media =
            RemoteMp4::open_source(Client::builder().no_proxy().build().unwrap(), &selected)
                .await
                .unwrap();
        let part = ByteRange {
            start: 4101,
            end: 4150,
        };
        let bytes = read_limited(media.read_range(part).await.unwrap(), 50)
            .await
            .unwrap();
        assert_eq!(
            media.decode_chunk(part.start, &bytes).unwrap(),
            plain[5..55]
        );
        let part = ByteRange {
            start: 0,
            end: 1000,
        };
        let bytes = read_limited(media.read_range(part).await.unwrap(), 1001)
            .await
            .unwrap();
        let decoded = media.decode_chunk(0, &bytes).unwrap();
        assert!(decoded.windows(4).any(|b| b == b"avc1"));
        assert!(!decoded.windows(4).any(|b| b == b"encv"));
        task.abort();
    }
}
