// SPDX-License-Identifier: GPL-3.0-only
//! Anonymous registered playback, ported from main's DeviceRegister,
//! PlaybackDeviceSession, MobilePlayback/v5 and singleVideoModel/PlayerSignature.
//! Keep one instance per application. Account cookies never enter this client.
use crate::{
    crypto,
    model::{ModelError, PlaybackModel},
    source::{Ladder, ResponseReadError, read_response_limited, valid_id},
};
use anyhow::Context;
use flate2::{Compression, write::GzEncoder};
use rand::RngCore;
use reqwest::{
    Client, Url,
    header::{HeaderMap, HeaderValue, SET_COOKIE},
    redirect,
};
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fmt,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{sync::Mutex, time::Instant};
use uuid::Uuid;

mod profile;
pub use profile::DeviceProfileStore;
use profile::ProfileStorage;

const REGISTER: &str = "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";
const TAB: &str =
    "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/";
const MODEL: &str = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/";
const UA: &str = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
const LIMIT: usize = 2 * 1024 * 1024;

/// Failures while obtaining a registered playback model, before opening the media.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[non_exhaustive]
pub enum PlaybackError {
    InvalidEpisode,
    Timeout,
    Network,
    HttpStatus(u16),
    RetryLater,
    EmptyResponse,
    ResponseTooLarge,
    DeviceUnavailable,
    InvalidDeviceResponse,
    Preparation,
    Model(ModelError),
}

impl fmt::Display for PlaybackError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidEpisode => f.write_str("分集编号无效"),
            Self::Timeout => f.write_str("注册源取流超时，请稍后重试"),
            Self::Network => f.write_str("连接注册源失败，请检查网络后重试"),
            Self::HttpStatus(status) => write!(f, "注册源请求失败（HTTP {status}）"),
            Self::RetryLater => f.write_str("注册源要求稍后重试"),
            Self::EmptyResponse => f.write_str("注册源返回空响应，请稍后重试"),
            Self::ResponseTooLarge => f.write_str("注册源响应超过大小限制"),
            Self::DeviceUnavailable => f.write_str("播放设备注册暂不可用，请稍后重试"),
            Self::InvalidDeviceResponse => f.write_str("播放设备注册响应无效"),
            Self::Preparation => f.write_str("无法准备注册源请求"),
            Self::Model(error) => error.fmt(f),
        }
    }
}

impl std::error::Error for PlaybackError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Model(error) => Some(error),
            _ => None,
        }
    }
}

impl From<ModelError> for PlaybackError {
    fn from(error: ModelError) -> Self {
        Self::Model(error)
    }
}

impl PlaybackError {
    fn from_request(error: reqwest::Error) -> Self {
        if error.is_timeout() {
            Self::Timeout
        } else {
            Self::Network
        }
    }
}

#[derive(Clone, PartialEq, Eq)]
pub(crate) struct Device {
    pub(crate) device_id: String,
    pub(crate) install_id: String,
    pub(crate) cdid: String,
    pub(crate) cookie: Option<HeaderValue>,
}
#[derive(Default)]
struct Session {
    device: Option<Arc<Device>>,
    empty_rounds: u8,
    retry_after: Option<Instant>,
    storage: ProfileStorage,
}

/// Registers lazily on foreground playback; concurrent requests share the identity.
/// Transient empty responses can replace an identity on a later request, at most
/// once per 30 seconds. HTTP/business rejection and Retry-After never rotate it.
pub struct RegisteredSource {
    client: Client,
    session: Mutex<Session>,
    register_url: Url,
    model_url: Url,
    tab_url: Url,
    mobile_url: Option<Url>,
    signer: crate::v5::Signer,
}
impl RegisteredSource {
    pub fn new() -> anyhow::Result<Self> {
        let client = Client::builder()
            .https_only(true)
            .redirect(redirect::Policy::none())
            .connect_timeout(Duration::from_secs(4))
            .read_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(8))
            .user_agent(UA)
            .build()
            .context("无法初始化注册源连接")?;
        Ok(Self {
            client,
            session: Mutex::new(Session::default()),
            register_url: Url::parse(REGISTER)?,
            model_url: Url::parse(MODEL)?,
            tab_url: Url::parse(TAB)?,
            mobile_url: Some(Url::parse(
                "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1",
            )?),
            signer: crate::v5::Signer::new(now_ms()),
        })
    }

    /// Enables lazy restore and best-effort persistence of the anonymous device.
    /// Build one source per application so the identity and writes are shared.
    pub fn with_profile_store(mut self, store: impl DeviceProfileStore + 'static) -> Self {
        self.session.get_mut().storage = ProfileStorage::Unread(Arc::new(store));
        self
    }

    pub async fn ladder(&self, episode: &str) -> Result<Ladder, PlaybackError> {
        if !valid_id(episode) {
            return Err(PlaybackError::InvalidEpisode);
        }
        tokio::time::timeout(Duration::from_secs(12), self.resolve(episode))
            .await
            .map_err(|_| PlaybackError::Timeout)?
    }
    pub(crate) async fn identity(&self) -> Result<Arc<Device>, PlaybackError> {
        let mut state = self.session.lock().await;
        if state.device.is_none()
            && let Some(device) = state.storage.restore().await
        {
            state.device = Some(device);
        }
        let cooling = state.retry_after.is_some_and(|at| Instant::now() < at);
        if let Some(device) = &state.device {
            if state.empty_rounds < 2 || cooling {
                return Ok(device.clone());
            }
        } else if cooling {
            return Err(PlaybackError::DeviceUnavailable);
        }
        // Set before await so cancellation cannot create an unbounded registration loop.
        state.retry_after = Some(Instant::now() + Duration::from_secs(30));
        let fresh = Arc::new(self.register().await?);
        state.device = Some(fresh.clone());
        state.empty_rounds = 0;
        state.storage.save(&fresh);
        Ok(fresh)
    }
    async fn register(&self) -> Result<Device, PlaybackError> {
        let now = now_ms();
        let json = registration_payload(now);
        let mut random = [0; 32];
        rand::rngs::OsRng.fill_bytes(&mut random);
        let body = crypto::tt_encrypt(&gzip_json(&json)?, &random);
        let response = self
            .client
            .post(self.register_url.clone())
            .header("Content-Type", "application/octet-stream;tt-data=a")
            .header("log-encode-type", "gzip")
            .header("x-ss-dp", "8662")
            .header("x-ss-req-ticket", now.to_string())
            .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
            .header("lc", "101")
            .header("sdk-version", "2")
            .header("passport-sdk-version", "5051452")
            .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
            .header(
                "x-ss-stub",
                crypto::hex_string(&crypto::md5(&body)).to_uppercase(),
            )
            .body(body)
            .send()
            .await
            .map_err(PlaybackError::from_request)?;
        let cookie = registration_cookie(response.headers());
        let bytes = response_bytes(response).await?;
        let value: Value =
            serde_json::from_slice(&bytes).map_err(|_| PlaybackError::InvalidDeviceResponse)?;
        let mut device = parse_device(&value)?;
        device.cookie = cookie;
        Ok(device)
    }
    async fn resolve(&self, episode: &str) -> Result<Ladder, PlaybackError> {
        let device = self.identity().await?;
        let result = if let Some(url) = &self.mobile_url {
            match self.fetch_mobile(url.clone(), episode, &device).await {
                Err(PlaybackError::EmptyResponse) => self.fetch_tab(episode, &device).await,
                Err(PlaybackError::Model(ModelError::NoPlayableSource)) => {
                    self.fetch(episode, &device).await
                }
                result => result,
            }
        } else {
            self.fetch(episode, &device).await
        };
        let mut state = self.session.lock().await;
        if state
            .device
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, &device))
        {
            match &result {
                Ok(_) => state.empty_rounds = 0,
                Err(PlaybackError::EmptyResponse) => {
                    state.empty_rounds = state.empty_rounds.saturating_add(1)
                }
                _ => state.empty_rounds = 0,
            }
        }
        result.map(Ladder::registered)
    }
    async fn fetch_mobile(
        &self,
        mut url: Url,
        episode: &str,
        device: &Device,
    ) -> Result<PlaybackModel, PlaybackError> {
        let values = mobile_query(device);
        let payload = json!({"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":[episode]},"dr_scene":"player"});
        let body = gzip_json(&payload)?;
        let (query, headers) = self
            .signer
            .sign(&values, &body, now_ms())
            .map_err(|_| PlaybackError::Preparation)?;
        url.set_query(Some(&query));
        let mut request=self.client.post(url)
            .header("User-Agent","com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)")
            .header("Accept","application/json").header("Content-Type","application/json; charset=utf-8")
            .header("Content-Encoding","gzip");
        for (key, value) in headers {
            request = request.header(key, value);
        }
        let response = request
            .body(body)
            .send()
            .await
            .map_err(PlaybackError::from_request)?;
        let bytes = response_bytes(response).await?;
        PlaybackModel::decode_response(&bytes, episode).map_err(Into::into)
    }

    async fn fetch_tab(
        &self,
        episode: &str,
        device: &Device,
    ) -> Result<PlaybackModel, PlaybackError> {
        let mut values = mobile_query(device);
        values.extend(
            [
                ("video_id", episode),
                ("video_id_type", "0"),
                ("device_level", "1"),
                ("need_all_video_definition", "1"),
                ("need_mp4_align", "0"),
                ("use_os_player", "0"),
                ("video_platform", "1024"),
                ("source", "0"),
                ("from_video_id", ""),
            ]
            .map(|(key, value)| (key.to_owned(), value.to_owned())),
        );
        // The empty body is used for the v5 digest only. No POST headers go on the wire.
        let (query, mut headers) = self
            .signer
            .sign(&values, &[], now_ms())
            .map_err(|_| PlaybackError::Preparation)?;
        headers.remove("x-ss-stub");
        let mut url = self.tab_url.clone();
        url.set_query(Some(&query));
        let mut request = self.client.get(url)
            .header("User-Agent", "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)")
            .header("Accept", "application/json; charset=utf-8")
            .header("Accept-Language", "zh-CN,zh;q=0.9");
        for (key, value) in headers {
            request = request.header(key, value);
        }
        let response = request.send().await.map_err(PlaybackError::from_request)?;
        let bytes = response_bytes(response).await?;
        PlaybackModel::decode_tab_response(&bytes, episode).map_err(Into::into)
    }

    async fn fetch(&self, episode: &str, device: &Device) -> Result<PlaybackModel, PlaybackError> {
        let now = now_ms();
        let query = playback_query(device, now);
        let body=format!("{{\"biz_param\":{{\"need_all_video_definition\":true,\"video_platform\":3}},\"content_type\":1,\"video_id\":\"{episode}\"}}").into_bytes();
        let mut nonce = [0; 11];
        rand::rngs::OsRng.fill_bytes(&mut nonce);
        let mut url = self.model_url.clone();
        url.set_query(Some(&query));
        let mut request = self
            .client
            .post(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("Sdk-Version", "2")
            .header("X-XS-From-Web", "0");
        for (key, value) in crypto::headers(&query, Some(&body), &device.device_id, now, &nonce) {
            request = request.header(key, value);
        }
        let response = request
            .body(body)
            .send()
            .await
            .map_err(PlaybackError::from_request)?;
        let bytes = response_bytes(response).await?;
        let model = PlaybackModel::single_model(&bytes, episode)?;
        match PlaybackModel::from_value(&model) {
            Ok(model) => Ok(model),
            Err(error @ (ModelError::NoPlayableSource | ModelError::InvalidResponse)) => {
                let url = fallback_url(&model).ok_or(error)?;
                let response = self
                    .client
                    .get(url)
                    .send()
                    .await
                    .map_err(PlaybackError::from_request)?;
                let bytes = response_bytes(response).await?;
                let root: Value =
                    serde_json::from_slice(&bytes).map_err(|_| ModelError::InvalidResponse)?;
                crate::model::check_status(&root)?;
                let info = root
                    .pointer("/video_info/data")
                    .ok_or(ModelError::InvalidResponse)?;
                PlaybackModel::from_value(info).map_err(Into::into)
            }
            Err(error) => Err(error.into()),
        }
    }
}
fn mobile_query(device: &Device) -> Vec<(String, String)> {
    [
        ("aid", "8662"),
        ("app_name", "novelread"),
        ("version_code", "74332"),
        ("version_name", "7.4.3.32"),
        ("channel", "vivo_8662_64"),
        ("device_platform", "android"),
        ("device_type", "PGT-AN10"),
        ("os_version", "12"),
        ("update_version_code", "74332"),
        ("iid", &device.install_id),
        ("cdid", &device.cdid),
        ("device_id", &device.device_id),
    ]
    .into_iter()
    .map(|(k, v)| (k.to_owned(), v.to_owned()))
    .collect()
}

fn gzip_json(value: &Value) -> Result<Vec<u8>, PlaybackError> {
    use std::io::Write;
    let bytes = serde_json::to_vec(value).map_err(|_| PlaybackError::Preparation)?;
    let mut gzip = GzEncoder::new(Vec::new(), Compression::default());
    gzip.write_all(&bytes)
        .map_err(|_| PlaybackError::Preparation)?;
    gzip.finish().map_err(|_| PlaybackError::Preparation)
}

async fn response_bytes(response: reqwest::Response) -> Result<Vec<u8>, PlaybackError> {
    if !response.status().is_success() {
        return Err(PlaybackError::HttpStatus(response.status().as_u16()));
    }
    if response.headers().contains_key("retry-after") {
        return Err(PlaybackError::RetryLater);
    }
    let bytes = read_response_limited(response, LIMIT)
        .await
        .map_err(|error| match error {
            ResponseReadError::TooLarge => PlaybackError::ResponseTooLarge,
            ResponseReadError::Timeout => PlaybackError::Timeout,
            ResponseReadError::Network => PlaybackError::Network,
        })?;
    if bytes.iter().all(u8::is_ascii_whitespace) {
        return Err(PlaybackError::EmptyResponse);
    }
    Ok(bytes)
}
pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn parse_device(value: &Value) -> Result<Device, PlaybackError> {
    crate::model::check_status(value).map_err(|error| match error {
        ModelError::InvalidResponse => PlaybackError::InvalidDeviceResponse,
        error => error.into(),
    })?;
    let id = |name: &str| -> Result<String, PlaybackError> {
        let v = value
            .get(format!("{name}_str"))
            .filter(|v| v.as_str().is_some_and(|s| !s.is_empty()))
            .or_else(|| value.get(name));
        let text = match v {
            Some(Value::String(s)) => s.clone(),
            Some(Value::Number(n)) => n.to_string(),
            _ => String::new(),
        };
        if !valid_device_id(&text) {
            return Err(PlaybackError::InvalidDeviceResponse);
        }
        Ok(text)
    };
    Ok(Device {
        device_id: id("device_id")?,
        install_id: id("install_id")?,
        cdid: Uuid::new_v4().to_string(),
        cookie: None,
    })
}

fn valid_device_id(value: &str) -> bool {
    valid_id(value) && value.bytes().any(|b| b != b'0')
}

fn is_anonymous_cookie(name: &str) -> bool {
    matches!(
        name,
        "install_id" | "ttreq" | "store-region" | "store-region-src"
    )
}

fn registration_cookie(headers: &HeaderMap) -> Option<HeaderValue> {
    let mut cookies = BTreeMap::new();
    for header in headers.get_all(SET_COOKIE) {
        if let Ok(value) = header.to_str()
            && let Some((name, value)) = value
                .split(';')
                .next()
                .and_then(|part| part.split_once('='))
            && is_anonymous_cookie(name)
        {
            cookies.insert(name, value);
        }
    }
    let value = cookies
        .into_iter()
        .map(|(name, value)| format!("{name}={value}"))
        .collect::<Vec<_>>()
        .join("; ");
    (!value.is_empty())
        .then(|| {
            let mut cookie = HeaderValue::from_str(&value).ok()?;
            cookie.set_sensitive(true);
            Some(cookie)
        })
        .flatten()
}

fn playback_query(device: &Device, now: u64) -> String {
    let mut values = BTreeMap::from([
        ("ac", "wifi"),
        ("channel", "update_64"),
        ("aid", "8662"),
        ("app_name", "novelread"),
        ("version_code", "73532"),
        ("version_name", "7.3.5.32"),
        ("device_platform", "android"),
        ("os", "android"),
        ("ssmix", "a"),
        ("device_type", "25053RT47C"),
        ("device_brand", "Redmi"),
        ("language", "zh"),
        ("os_api", "36"),
        ("os_version", "16"),
        ("manifest_version_code", "73532"),
        ("resolution", "1280*2772"),
        ("dpi", "520"),
        ("update_version_code", "73532"),
        ("device_id", device.device_id.as_str()),
        ("iid", device.install_id.as_str()),
    ]);
    let ticket = now.to_string();
    values.insert("_rticket", &ticket);
    url::form_urlencoded::Serializer::new(String::new())
        .extend_pairs(values)
        .finish()
}
fn fallback_url(model: &Value) -> Option<Url> {
    let mut raw = model.get("fallback_api")?.clone();
    if let Value::Array(rows) = raw {
        raw = rows.into_iter().next()?;
    }
    if let Some(text) = raw.as_str()
        && let Ok(decoded) = serde_json::from_str::<Value>(text)
    {
        raw = decoded;
    }
    let text = raw.as_str().or_else(|| raw.get("fallback_api")?.as_str())?;
    let mut url = crate::source::https_url(text).ok()?;
    let query: Vec<_> = url
        .query_pairs()
        .filter(|(k, v)| !(k == "stream_type" && v == "audio_encrypt"))
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    url.set_query(None);
    if !query.is_empty() {
        url.query_pairs_mut().extend_pairs(query);
    }
    Some(url)
}
fn registration_payload(now: u64) -> Value {
    json!({"magic_tag":"ss_app_log","_gen_time":now,"header":{
        "display_name":"红果免费短剧","update_version_code":73932,"manifest_version_code":73932,
        "app_version_minor":"7.3.9.32","aid":8662,"channel":"update_64","package":"com.phoenix.read",
        "app_version":"7.3.9.32","version_code":73932,"sdk_version":"3.7.0-rc.34-fanqie-xiaoshuo",
        "sdk_target_version":29,"git_hash":"3a88f4e","os":"Android","os_version":"16","os_api":36,
        "device_model":"25053RT47C","device_brand":"Redmi","device_manufacturer":"Redmi","cpu_abi":"arm64-v8a",
        "release_build":"b5e187f","density_dpi":520,"display_density":"mdpi","resolution":"1280x2772",
        "language":"zh","timezone":8,"access":"wifi","not_request_sender":0,"carrier":"CHINA MOBILE",
        "mcc_mnc":"46000","rom":"eng.root.20231116.160340","rom_version":"W528JS release-keys",
        "cdid":Uuid::new_v4().to_string(),"sig_hash":"56a962410c494bbaf0b58dba20cae56f",
        "openudid":Uuid::new_v4().simple().to_string()[..16],"clientudid":Uuid::new_v4().to_string(),
        "ipv6_list":[],"region":"CN","tz_name":"Asia/Shanghai","tz_offset":28800,"sim_region":"cn",
        "sim_serial_number":[],"oaid_may_support":false,"req_id":Uuid::new_v4().to_string(),"device_platform":"android",
        "custom":{"account_region_src":"fallback","host_bit":64,"account_region":"cn","dragon_device_type":"phone"},
        "pre_installed_channel":"","apk_first_install_time":now,"is_system_app":0,"sdk_flavor":"china","guest_mode":0
    }})
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    const GOOD: &str = r#"{"code":0,"data":{"video_model":{"video_list":[{"main_url":"https://media.example/clip.mp4","codec_type":"h264","vwidth":720,"vheight":1280}]}}}"#;

    #[derive(Clone, Default)]
    struct MemoryProfileStore {
        bytes: Arc<std::sync::Mutex<Option<Vec<u8>>>>,
        saved: Arc<tokio::sync::Notify>,
    }

    impl DeviceProfileStore for MemoryProfileStore {
        fn load(&self) -> anyhow::Result<Option<Vec<u8>>> {
            Ok(self.bytes.lock().unwrap().clone())
        }

        fn save(&self, profile: &[u8]) -> anyhow::Result<()> {
            *self.bytes.lock().unwrap() = Some(profile.to_vec());
            self.saved.notify_one();
            Ok(())
        }
    }

    struct Fixture {
        source: Arc<RegisteredSource>,
        registers: Arc<AtomicUsize>,
        models: Arc<AtomicUsize>,
        task: tokio::task::JoinHandle<()>,
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            self.task.abort();
        }
    }
    async fn fixture(
        register: &'static str,
        status: u16,
        body: &'static str,
        backoff: bool,
        tab_body: &'static str,
        store: Option<MemoryProfileStore>,
    ) -> Fixture {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = Url::parse(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
        let registers = Arc::new(AtomicUsize::new(0));
        let models = Arc::new(AtomicUsize::new(0));
        let (r, m) = (registers.clone(), models.clone());
        let task = tokio::spawn(async move {
            loop {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut request = Vec::new();
                let mut b = [0];
                while !request.ends_with(b"\r\n\r\n") {
                    socket.read_exact(&mut b).await.unwrap();
                    request.push(b[0]);
                    assert!(request.len() < 16000);
                }
                let raw_headers = String::from_utf8(request).unwrap();
                let headers = raw_headers.to_ascii_lowercase();
                let length: usize = headers
                    .lines()
                    .find_map(|s| s.strip_prefix("content-length: "))
                    .unwrap_or("0")
                    .parse()
                    .unwrap();
                let mut payload = vec![0; length];
                socket.read_exact(&mut payload).await.unwrap();
                assert!(
                    !headers.contains("\r\ncookie:") && !headers.contains("\r\nauthorization:")
                );
                let is_register = headers.starts_with("post /register ");
                let (code, response) = if is_register {
                    r.fetch_add(1, Ordering::SeqCst);
                    assert!(payload.starts_with(&crypto::hex("746305100000")));
                    assert!(!headers.contains("x-argus") && !headers.contains("x-gorgon"));
                    assert!(headers.contains(&format!(
                        "x-ss-stub: {}",
                        crypto::hex_string(&crypto::md5(&payload))
                    )));
                    (200, register)
                } else if headers.starts_with("post /mobile?") {
                    use std::io::Read;
                    m.fetch_add(1, Ordering::SeqCst);
                    assert!(
                        headers.contains("device_id=333")
                            && headers.contains("iid=444")
                            && headers.contains("version_code=74332")
                    );
                    assert!(
                        headers.contains("x-medusa:")
                            && headers.contains("x-helios:")
                            && !headers.contains("x-argus:")
                            && !headers.contains("x-gorgon:")
                    );
                    assert!(headers.contains(&format!(
                        "x-ss-stub: {}",
                        crypto::hex_string(&crypto::md5(&payload))
                    )));
                    let mut decoded = Vec::new();
                    flate2::read::GzDecoder::new(payload.as_slice())
                        .read_to_end(&mut decoded)
                        .unwrap();
                    let payload: Value = serde_json::from_slice(&decoded).unwrap();
                    assert_eq!(payload["biz_param"]["device_level"], 1);
                    assert_eq!(payload["mixed_video_id_map"]["1004"][0], "123");
                    (status, body)
                } else if headers.starts_with("get /tab?") {
                    m.fetch_add(1, Ordering::SeqCst);
                    assert!(payload.is_empty());
                    assert!(headers.contains("x-medusa:") && headers.contains("x-helios:"));
                    for header in ["x-ss-stub:", "content-type:", "content-encoding:"] {
                        assert!(!headers.contains(header));
                    }
                    let path = raw_headers
                        .lines()
                        .next()
                        .unwrap()
                        .split_whitespace()
                        .nth(1)
                        .unwrap();
                    let query: BTreeMap<_, _> = Url::parse("https://example.invalid")
                        .unwrap()
                        .join(path)
                        .unwrap()
                        .query_pairs()
                        .into_owned()
                        .collect();
                    for (key, value) in [
                        ("video_id", "123"),
                        ("video_id_type", "0"),
                        ("device_level", "1"),
                        ("need_all_video_definition", "1"),
                        ("video_platform", "1024"),
                        ("use_os_player", "0"),
                        ("need_mp4_align", "0"),
                        ("source", "0"),
                        ("from_video_id", ""),
                        ("version_code", "74332"),
                        ("device_id", "333"),
                        ("iid", "444"),
                    ] {
                        assert_eq!(query.get(key).map(String::as_str), Some(value));
                    }
                    assert!(Uuid::parse_str(&query["cdid"]).is_ok());
                    (200, tab_body)
                } else {
                    m.fetch_add(1, Ordering::SeqCst);
                    assert!(headers.starts_with("post /model?"));
                    assert!(
                        headers.contains("device_id=333")
                            && headers.contains("iid=444")
                            && headers.contains("version_code=73532")
                    );
                    let query = raw_headers
                        .lines()
                        .next()
                        .unwrap()
                        .split_whitespace()
                        .nth(1)
                        .unwrap()
                        .split_once('?')
                        .unwrap()
                        .1;
                    let ticket = headers
                        .lines()
                        .find_map(|s| s.strip_prefix("x-ss-req-ticket: "))
                        .unwrap()
                        .parse()
                        .unwrap();
                    // Query values here are ASCII; recompute the signature over exact request bytes.
                    let expected = crypto::headers(query, Some(&payload), "333", ticket, &[0; 11]);
                    assert!(headers.contains(&format!("x-gorgon: {}", expected["x-gorgon"])));
                    assert!(headers.contains("x-argus:") && headers.contains("x-ladon:"));
                    let payload: Value = serde_json::from_slice(&payload).unwrap();
                    assert_eq!(payload["video_id"], "123");
                    (status, body)
                };
                let retry = if !is_register && backoff {
                    "Retry-After: 60\r\n"
                } else {
                    ""
                };
                let cookies = if is_register {
                    "Set-Cookie: ttreq=guest-fixture; Path=/\r\nSet-Cookie: sessionid=private-fixture; HttpOnly\r\n"
                } else {
                    ""
                };
                let response = format!(
                    "HTTP/1.1 {code} Fixture\r\nContent-Length: {}\r\n{retry}{cookies}Connection: close\r\n\r\n{response}",
                    response.len()
                );
                socket.write_all(response.as_bytes()).await.unwrap();
            }
        });
        let source = RegisteredSource {
            client: Client::builder().no_proxy().build().unwrap(),
            session: Mutex::new(Session::default()),
            register_url: base.join("register").unwrap(),
            model_url: base.join("model").unwrap(),
            tab_url: base.join("tab").unwrap(),
            mobile_url: None,
            signer: crate::v5::Signer::new(now_ms()),
        };
        let source = match store {
            Some(store) => source.with_profile_store(store),
            None => source,
        };
        Fixture {
            source: Arc::new(source),
            registers,
            models,
            task,
        }
    }
    #[tokio::test]
    async fn mobile_primary_binds_gzip_and_empty_response_uses_v5_tab() {
        use PlaybackError as Error;

        for (status, body, backoff, calls, failure, tab_body) in [
            (
                200,
                r#"{"code":0,"data":{"1004":{"123":{"video_model":{"video_list":[{"main_url":"https://media.example/clip.mp4","codec_type":"h264"}]}}}}}"#,
                false,
                1,
                None,
                "",
            ),
            (403, "", false, 1, Some(Error::HttpStatus(403)), ""),
            (429, "", false, 1, Some(Error::HttpStatus(429)), ""),
            (
                200,
                r#"{"code":110001}"#,
                false,
                1,
                Some(ModelError::Rejected(110001).into()),
                "",
            ),
            (200, "", true, 1, Some(Error::RetryLater), ""),
            (200, " \n\t", false, 2, Some(Error::EmptyResponse), ""),
            (
                200,
                "{",
                false,
                1,
                Some(ModelError::InvalidResponse.into()),
                "",
            ),
            (
                200,
                r#"{"code":0,"data":{"1004":{"123":{"video_list":[]}}}}"#,
                false,
                2,
                Some(ModelError::NoPlayableSource.into()),
                "",
            ),
            (
                200,
                "",
                false,
                2,
                None,
                r#"{"code":0,"data":{"video_model_data":{"123":{"video_model":{"video_list":[{"main_url":"https://media.example/clip.mp4","codec_type":"h264"}]}}}}}"#,
            ),
            (
                200,
                "",
                false,
                2,
                Some(ModelError::EpisodeMissing.into()),
                r#"{"code":0,"data":{"video_model_data":{"999":{"video_model":{"video_list":[{"main_url":"https://media.example/clip.mp4"}]}}}}}"#,
            ),
        ] {
            let mut f = fixture(
                r#"{"device_id":333,"install_id":444}"#,
                status,
                body,
                backoff,
                tab_body,
                None,
            )
            .await;
            let source = Arc::get_mut(&mut f.source).unwrap();
            source.mobile_url = Some(source.model_url.join("mobile").unwrap());
            assert_eq!(f.source.ladder("123").await.err(), failure);
            assert_eq!(f.models.load(Ordering::SeqCst), calls);
            assert_eq!(f.registers.load(Ordering::SeqCst), 1);
        }
    }
    #[tokio::test]
    async fn concurrent_playback_registers_once_and_reuses_signed_identity() {
        let f = fixture(
            r#"{"device_id":333,"install_id_str":"444"}"#,
            200,
            GOOD,
            false,
            "",
            None,
        )
        .await;
        let (a, b, c) = tokio::join!(
            f.source.ladder("123"),
            f.source.ladder("123"),
            f.source.ladder("123")
        );
        for result in [a, b, c] {
            assert_eq!(result.unwrap().select(1080, None).unwrap().quality, 720);
        }
        assert_eq!(f.registers.load(Ordering::SeqCst), 1);
        assert_eq!(f.models.load(Ordering::SeqCst), 3);
    }
    #[tokio::test]
    async fn rejection_backoff_and_missing_episode_never_rotate_or_fall_through() {
        for (status, body, backoff) in [
            (403, "", false),
            (429, "", false),
            (200, r#"{"code":110001}"#, false),
            (200, "", true),
            (
                200,
                r#"{"code":0,"data":{"999":{"video_model":{}}}}"#,
                false,
            ),
        ] {
            let f = fixture(
                r#"{"device_id":"333","install_id":444}"#,
                status,
                body,
                backoff,
                "",
                None,
            )
            .await;
            for _ in 0..3 {
                assert!(f.source.ladder("123").await.is_err());
            }
            assert_eq!(f.registers.load(Ordering::SeqCst), 1);
            assert_eq!(f.models.load(Ordering::SeqCst), 3);
            assert_eq!(f.source.session.lock().await.empty_rounds, 0);
        }
    }
    #[tokio::test]
    async fn invalid_registration_is_not_saved_and_is_cooled_down() {
        let store = MemoryProfileStore::default();
        let f = fixture(
            r#"{"device_id":"000","install_id":444}"#,
            200,
            GOOD,
            false,
            "",
            Some(store.clone()),
        )
        .await;
        assert_eq!(
            f.source.ladder("bad").await.err(),
            Some(PlaybackError::InvalidEpisode)
        );
        assert_eq!(f.registers.load(Ordering::SeqCst), 0);
        assert_eq!(
            f.source.ladder("123").await.err(),
            Some(PlaybackError::InvalidDeviceResponse)
        );
        for _ in 0..2 {
            assert_eq!(
                f.source.ladder("123").await.err(),
                Some(PlaybackError::DeviceUnavailable)
            );
        }
        assert!(f.source.session.lock().await.device.is_none());
        assert!(store.load().unwrap().is_none());
        assert_eq!(f.registers.load(Ordering::SeqCst), 1);
        assert_eq!(f.models.load(Ordering::SeqCst), 0);
    }
    #[tokio::test]
    async fn distinguishes_an_oversized_response_from_a_stalled_body() {
        use tokio::io::{AsyncBufReadExt, BufReader};

        for (length, error) in [
            (LIMIT + 1, PlaybackError::ResponseTooLarge),
            (4, PlaybackError::Timeout),
        ] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            let server = tokio::spawn(async move {
                let (socket, _) = listener.accept().await.unwrap();
                let mut request = BufReader::new(socket);
                let mut line = String::new();
                loop {
                    assert_ne!(request.read_line(&mut line).await.unwrap(), 0);
                    if line == "\r\n" {
                        break;
                    }
                    line.clear();
                }
                let headers = format!("HTTP/1.1 200 OK\r\nContent-Length: {length}\r\n\r\n");
                request
                    .get_mut()
                    .write_all(headers.as_bytes())
                    .await
                    .unwrap();
                std::future::pending::<()>().await;
            });
            let client = Client::builder()
                .no_proxy()
                .read_timeout(Duration::from_millis(500))
                .build()
                .unwrap();
            let response = client.get(url).send().await.unwrap();
            let result = response_bytes(response).await;
            server.abort();
            assert_eq!(result, Err(error));
        }
    }
    #[tokio::test]
    async fn reopens_anonymous_profile_without_registering_again() {
        let store = MemoryProfileStore::default();
        let cold = fixture(
            r#"{"device_id":333,"install_id":444}"#,
            200,
            GOOD,
            false,
            "",
            Some(store.clone()),
        )
        .await;
        assert!(cold.source.ladder("123").await.is_ok());
        let original = cold.source.identity().await.unwrap();
        tokio::time::timeout(Duration::from_secs(2), store.saved.notified())
            .await
            .unwrap();
        let saved = String::from_utf8(store.load().unwrap().unwrap()).unwrap();
        assert!(saved.contains("ttreq=guest-fixture") && !saved.contains("private-fixture"));
        drop(cold);

        let reopened = fixture("", 200, GOOD, false, "", Some(store)).await;
        assert_eq!(reopened.registers.load(Ordering::SeqCst), 0);
        assert!(reopened.source.ladder("123").await.is_ok());
        let restored = reopened.source.identity().await.unwrap();
        assert!(restored.as_ref() == original.as_ref());
        assert!(restored.cookie.as_ref().unwrap().is_sensitive());
        assert_eq!(reopened.registers.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn leaves_invalid_stored_profile_intact_and_uses_memory() {
        for (version, cookie) in [(2, "ttreq=fixture"), (1, "sessionid=private-fixture")] {
            let store = MemoryProfileStore::default();
            let saved = serde_json::to_vec(&json!({
                "version": version, "device_id": "333", "install_id": "444",
                "cdid": Uuid::new_v4().to_string(), "cookie": cookie,
            }))
            .unwrap();
            store.save(&saved).unwrap();
            let f = fixture(
                r#"{"device_id":333,"install_id":444}"#,
                200,
                GOOD,
                false,
                "",
                Some(store.clone()),
            )
            .await;
            assert!(f.source.ladder("123").await.is_ok());
            assert_eq!(f.registers.load(Ordering::SeqCst), 1);
            assert!(matches!(
                f.source.session.lock().await.storage,
                ProfileStorage::Disabled
            ));
            assert_eq!(store.load().unwrap().unwrap(), saved);
        }
    }

    #[tokio::test]
    async fn persistent_empty_rotates_only_on_later_foreground_call_after_cooldown() {
        let f = fixture(
            r#"{"device_id":333,"install_id":444}"#,
            200,
            "",
            false,
            "",
            None,
        )
        .await;
        for _ in 0..3 {
            assert!(f.source.ladder("123").await.is_err());
        }
        assert_eq!(f.registers.load(Ordering::SeqCst), 1);
        f.source.session.lock().await.retry_after = Some(Instant::now() - Duration::from_secs(1));
        assert!(f.source.ladder("123").await.is_err());
        assert_eq!(f.registers.load(Ordering::SeqCst), 2);
        assert!(f.source.ladder("123").await.is_err());
        assert_eq!(f.registers.load(Ordering::SeqCst), 2);
    }
    #[test]
    fn registration_payload_preserves_protocol_types_and_has_no_fixed_identity() {
        let p = registration_payload(123);
        assert_eq!(p["header"]["os_version"], "16");
        assert_eq!(p["header"]["aid"], 8662);
        assert!(p["header"].get("device_id").is_none());
        assert!(p["header"].get("install_id").is_none());
        for device in [
            json!({}),
            json!({"device_id":"bad","install_id":444}),
            json!({"device_id":-1,"install_id":444}),
        ] {
            assert!(parse_device(&device).is_err());
        }
    }
}
