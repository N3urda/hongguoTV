// SPDX-License-Identifier: GPL-3.0-only
//! Pure playback-model parsing. Callers supply a response they are authorized to use.
//! No registration, request signing, URL decryption or media decryption is performed.

use std::{borrow::Cow, fmt};

use base64::{Engine, engine::general_purpose::STANDARD};
use serde_json::Value;
use url::Url;

const MAX_MODEL_BYTES: usize = 2 * 1024 * 1024;
const MAX_VARIANTS: usize = 128;

#[derive(Clone)]
pub struct PlaybackVariant {
    pub quality: u16,
    pub codec: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub url: Url,
    pub backup_url: Option<Url>,
}

impl PlaybackVariant {
    pub fn is_avc(&self) -> bool {
        matches!(self.codec.as_str(), "h264" | "h.264" | "avc" | "avc1")
            || self.codec.starts_with("avc1.")
    }
}

pub struct PlaybackModel {
    variants: Vec<PlaybackVariant>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ModelError {
    TooLarge,
    InvalidResponse,
    Rejected(i64),
    EpisodeMissing,
    NoPlayableSource,
}

impl fmt::Display for ModelError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::TooLarge => f.write_str("播放信息超过大小限制"),
            Self::InvalidResponse => f.write_str("播放信息格式异常"),
            Self::Rejected(code) => write!(f, "上游拒绝播放请求（错误码 {code}）"),
            Self::EpisodeMissing => f.write_str("响应中没有所请求的分集"),
            Self::NoPlayableSource => f.write_str("没有可直接播放的视频资源"),
        }
    }
}

impl std::error::Error for ModelError {}

impl PlaybackModel {
    /// Decode a single model whose episode association is established by the caller.
    pub fn decode(bytes: &[u8]) -> Result<Self, ModelError> {
        Self::from_value(&decode_json(bytes)?)
    }

    /// Decode a batch response, matching the requested episode exactly. Never select
    /// the first available episode when that episode is absent or rejected.
    pub fn decode_response(bytes: &[u8], episode: &str) -> Result<Self, ModelError> {
        if episode.is_empty() || episode.len() > 30 || !episode.bytes().all(|b| b.is_ascii_digit())
        {
            return Err(ModelError::InvalidResponse);
        }
        let root = decode_json(bytes)?;
        check_status(&root)?;
        let data = root.get("data").ok_or(ModelError::InvalidResponse)?;
        let entries = data.get("1004").unwrap_or(data);
        let entry = entries.get(episode).ok_or(ModelError::EpisodeMissing)?;
        check_status(entry)?;
        Self::from_value(
            entry
                .get("video_model")
                .ok_or(ModelError::NoPlayableSource)?,
        )
    }

    pub fn variants(&self) -> &[PlaybackVariant] {
        &self.variants
    }

    pub fn into_variants(self) -> Vec<PlaybackVariant> {
        self.variants
    }

    pub(crate) fn from_value(value: &Value) -> Result<Self, ModelError> {
        let model = match value.as_str() {
            Some(text) => Cow::Owned(decode_json(text.as_bytes())?),
            None => Cow::Borrowed(value),
        };
        check_status(&model)?;
        let rows: Vec<_> = match &model["video_list"] {
            Value::Object(rows) if rows.len() <= MAX_VARIANTS => rows
                .iter()
                .map(|(name, row)| (name.as_str(), row))
                .collect(),
            Value::Array(rows) if rows.len() <= MAX_VARIANTS => {
                rows.iter().map(|row| ("", row)).collect()
            }
            Value::Object(_) | Value::Array(_) => return Err(ModelError::TooLarge),
            _ => return Err(ModelError::InvalidResponse),
        };
        let variants: Vec<_> = rows
            .into_iter()
            .filter_map(|(name, row)| parse_variant(name, row))
            .collect();
        if variants.is_empty() {
            return Err(ModelError::NoPlayableSource);
        }
        Ok(Self { variants })
    }
}

fn decode_json(bytes: &[u8]) -> Result<Value, ModelError> {
    if bytes.len() > MAX_MODEL_BYTES {
        return Err(ModelError::TooLarge);
    }
    serde_json::from_slice(bytes).map_err(|_| ModelError::InvalidResponse)
}

fn check_status(value: &Value) -> Result<(), ModelError> {
    if !value.is_object() {
        return Err(ModelError::InvalidResponse);
    }
    for key in ["Code", "code"] {
        if let Some(code) = value.get(key) {
            let code = code
                .as_i64()
                .or_else(|| code.as_str()?.parse().ok())
                .ok_or(ModelError::InvalidResponse)?;
            if code != 0 {
                return Err(ModelError::Rejected(code));
            }
        }
    }
    Ok(())
}

fn text(value: &Value) -> &str {
    value.as_str().unwrap_or_default().trim()
}

fn protected(row: &Value) -> bool {
    let encryption = &row["encrypt_info"];
    !text(&row["spade_a"]).is_empty()
        || !text(&encryption["spade_a"]).is_empty()
        || [row.get("encrypt"), encryption.get("encrypt")]
            .into_iter()
            .flatten()
            .any(|value| {
                !matches!(value, Value::Bool(false))
                    && value.as_i64() != Some(0)
                    && !matches!(value.as_str(), Some("0" | "false"))
            })
        || (row.get("encrypt_info").is_some() && encryption.get("encrypt").is_none())
}

fn dimension(value: &Value) -> Option<u32> {
    value
        .as_u64()
        .and_then(|n| u32::try_from(n).ok())
        .or_else(|| value.as_str()?.parse().ok())
        .filter(|n| *n > 0)
}

fn parse_variant(name: &str, row: &Value) -> Option<PlaybackVariant> {
    if !row.is_object() || protected(row) {
        return None;
    }
    let meta = &row["video_meta"];
    let codec = [text(&row["codec_type"]), text(&meta["codec_type"])]
        .into_iter()
        .find(|s| !s.is_empty())
        .unwrap_or_default()
        .to_lowercase();
    if [
        codec.as_str(),
        text(&meta["codec_type"]),
        text(&row["gear_des_key"]),
    ]
    .iter()
    .any(|s| {
        s.to_lowercase()
            .split(|c: char| !c.is_ascii_alphanumeric())
            .any(|part| matches!(part, "bytevc2" | "bvc2"))
    }) {
        return None;
    }
    let backup = playable_url(text(&row["backup_url_1"]));
    let url = playable_url(text(&row["main_url"]))
        .or_else(|| playable_url(text(&row["play_url"])))
        .or_else(|| backup.clone())?;
    let width = dimension(&row["vwidth"]).or_else(|| dimension(&meta["vwidth"]));
    let height = dimension(&row["vheight"]).or_else(|| dimension(&meta["vheight"]));
    let quality = [text(&row["quality_desc"]), text(&meta["definition"])]
        .into_iter()
        .map(quality_number)
        .find(|q| *q > 0)
        .or_else(|| {
            width
                .zip(height)
                .and_then(|(w, h)| u16::try_from(w.min(h)).ok())
        })
        .or_else(|| {
            [&row["height"], &row["vheight"], &row["quality"]]
                .into_iter()
                .map(|v| {
                    quality_number(
                        &v.as_str()
                            .map(str::to_owned)
                            .unwrap_or_else(|| v.to_string()),
                    )
                })
                .find(|q| *q > 0)
        })
        .unwrap_or_else(|| quality_number(name));
    Some(PlaybackVariant {
        quality,
        codec,
        width,
        height,
        backup_url: backup.filter(|backup| backup != &url),
        url,
    })
}

fn quality_number(value: &str) -> u16 {
    [2160, 1440, 1080, 720, 576, 540, 480, 360]
        .into_iter()
        .filter_map(|tier| value.find(&tier.to_string()).map(|at| (at, tier)))
        .min_by_key(|(at, _)| *at)
        .map(|(_, tier)| tier)
        .unwrap_or_else(|| match value {
            "1920" => 1080,
            "1280" => 720,
            "1024" => 576,
            "854" => 480,
            "640" => 360,
            _ => value.parse().unwrap_or(0),
        })
}

fn playable_url(raw: &str) -> Option<Url> {
    // Base64 is a transport encoding, not encrypted-source handling.
    let decoded;
    let value = if raw.starts_with("https://") {
        raw
    } else {
        decoded = String::from_utf8(STANDARD.decode(raw).ok()?).ok()?;
        decoded.trim()
    };
    let url = Url::parse(value).ok()?;
    (url.scheme() == "https"
        && url.host().is_some()
        && url.username().is_empty()
        && url.password().is_none()
        && url.fragment().is_none())
    .then_some(url)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn decode(value: Value) -> Result<PlaybackModel, ModelError> {
        PlaybackModel::decode(&serde_json::to_vec(&value).unwrap())
    }

    #[test]
    fn parses_object_and_string_models_and_matches_batch_episode() {
        let model = json!({"video_list": {"720": {
            "main_url": STANDARD.encode("https://media.example/a.mp4"),
            "backup_url_1": "https://backup.example/a.mp4", "codec_type": "H264",
            "vwidth": "720", "vheight": 1280
        }}});
        for value in [model.clone(), Value::String(model.to_string())] {
            for data in [
                json!({"200": {"video_model": value}}),
                json!({"1004": {"200": {"video_model": value}}}),
            ] {
                let bytes = serde_json::to_vec(&json!({"code": 0, "data": data})).unwrap();
                let parsed = PlaybackModel::decode_response(&bytes, "200").unwrap();
                let row = &parsed.variants()[0];
                assert_eq!(
                    (row.quality, row.width, row.height),
                    (720, Some(720), Some(1280))
                );
                assert!(row.is_avc());
                assert_eq!(
                    row.backup_url.as_ref().unwrap().host_str(),
                    Some("backup.example")
                );
                assert!(matches!(
                    PlaybackModel::decode_response(&bytes, "201"),
                    Err(ModelError::EpisodeMissing)
                ));
            }
        }
    }

    #[test]
    fn rejects_service_errors_before_using_any_sources() {
        let good = json!({"video_list": [{"main_url": "https://media.example/a.mp4"}]});
        for (root_code, entry_code, expected) in [(403, 0, 403), (0, 401, 401)] {
            let bytes = serde_json::to_vec(&json!({"code": root_code, "data": {
                "200": {"code": entry_code, "video_model": good}
            }}))
            .unwrap();
            assert!(
                matches!(PlaybackModel::decode_response(&bytes, "200"), Err(ModelError::Rejected(code)) if code == expected)
            );
        }
        assert!(matches!(
            decode(json!({"code": "bad", "video_list": []})),
            Err(ModelError::InvalidResponse)
        ));
    }

    #[test]
    fn retains_clear_variants_while_ignoring_protected_malformed_and_incompatible_rows() {
        let parsed = decode(json!({"video_list": [
            {"main_url": "https://media.example/encrypted.mp4", "encrypt_info": {"encrypt": true}},
            {"main_url": "https://media.example/unspecified.mp4", "encrypt_info": {}},
            {"main_url": "https://media.example/keyed.mp4", "spade_a": "test"},
            {"main_url": "https://media.example/unsupported.mp4", "codec_type": "bytevc2"},
            {"main_url": "https://media.example/conflict.mp4", "codec_type": "h264", "video_meta": {"codec_type": "bvc2"}},
            {"main_url": "https://user:secret@media.example/credential.mp4"},
            {"main_url": "http://media.example/insecure.mp4"},
            null,
            {"main_url": "bad", "backup_url_1": "https://media.example/good.mp4",
                "video_meta": {"definition": "1080p", "codec_type": "h264"},
                "encrypt_info": {"encrypt": "false"}}
        ]}))
        .unwrap();
        assert_eq!(parsed.variants().len(), 1);
        let row = &parsed.variants()[0];
        assert_eq!(row.quality, 1080);
        assert!(row.backup_url.is_none());
        assert_eq!(row.url.path(), "/good.mp4");
    }

    #[test]
    fn bounds_input_and_does_not_invent_renditions() {
        assert!(matches!(
            PlaybackModel::decode(&vec![b' '; MAX_MODEL_BYTES + 1]),
            Err(ModelError::TooLarge)
        ));
        assert!(matches!(
            decode(json!({"video_list": vec![Value::Null; MAX_VARIANTS + 1]})),
            Err(ModelError::TooLarge)
        ));
        assert!(matches!(
            decode(json!({"video_list": []})),
            Err(ModelError::NoPlayableSource)
        ));
        let parsed = decode(json!({"video_list": [
            {"main_url": "https://media.example/a.mp4", "vwidth": 360, "vheight": 640},
            {"main_url": "https://media.example/b.mp4"}
        ]}))
        .unwrap();
        assert_eq!(
            parsed
                .variants()
                .iter()
                .map(|v| v.quality)
                .collect::<Vec<_>>(),
            [360, 0]
        );
    }
}
