// SPDX-License-Identifier: GPL-3.0-only
//! Anonymous registered playback, ported from main's DeviceRegister,
//! PlaybackDeviceSession, MobilePlayback/v5 and singleVideoModel/PlayerSignature.
//! Keep one instance per application. Account cookies never enter this client.
use crate::{
    crypto,
    model::{ModelError, PlaybackModel},
    source::{Ladder, read_limited, valid_id},
};
use anyhow::{Context, Result, bail, ensure};
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
    io::Write,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{sync::Mutex, time::Instant};
use uuid::Uuid;

const REGISTER: &str = "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";
const MODEL: &str = "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/video_model/v1/";
const UA: &str = "com.phoenix.read/73532 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
const LIMIT: usize = 2 * 1024 * 1024;

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
}

/// Registers lazily on foreground playback; concurrent requests share the identity.
/// Transient empty responses can replace an identity on a later request, at most
/// once per 30 seconds. HTTP/business rejection and Retry-After never rotate it.
pub struct RegisteredSource {
    client: Client,
    session: Mutex<Session>,
    register_url: Url,
    model_url: Url,
    mobile_url: Option<Url>,
    signer: crate::v5::Signer,
}
impl RegisteredSource {
    pub fn new() -> Result<Self> {
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
            mobile_url: Some(Url::parse(
                "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1",
            )?),
            signer: crate::v5::Signer::new(now_ms()),
        })
    }
    pub async fn ladder(&self, episode: &str) -> Result<Ladder> {
        ensure!(valid_id(episode), "分集编号无效");
        tokio::time::timeout(Duration::from_secs(12), self.resolve(episode))
            .await
            .map_err(|_| anyhow::anyhow!("注册源取流超时，请稍后重试"))?
    }
    pub(crate) async fn identity(&self) -> Result<Arc<Device>> {
        let mut state = self.session.lock().await;
        let cooling = state.retry_after.is_some_and(|at| Instant::now() < at);
        if let Some(device) = &state.device {
            if state.empty_rounds < 2 || cooling {
                return Ok(device.clone());
            }
        } else if cooling {
            bail!("播放设备注册暂不可用，请稍后重试");
        }
        // Set before await so cancellation cannot create an unbounded registration loop.
        state.retry_after = Some(Instant::now() + Duration::from_secs(30));
        let fresh = Arc::new(self.register().await?);
        state.device = Some(fresh.clone());
        state.empty_rounds = 0;
        Ok(fresh)
    }
    async fn register(&self) -> Result<Device> {
        let now = now_ms();
        let json = registration_payload(now);
        let mut gzip = GzEncoder::new(Vec::new(), Compression::default());
        gzip.write_all(&serde_json::to_vec(&json)?)?;
        let mut random = [0; 32];
        rand::rngs::OsRng.fill_bytes(&mut random);
        let body = crypto::tt_encrypt(&gzip.finish()?, &random);
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
            .map_err(|_| anyhow::anyhow!("播放设备注册连接失败"))?;
        let cookie = registration_cookie(response.headers());
        let bytes = response_bytes(response)
            .await
            .context("播放设备注册失败，请稍后重试")?;
        let value: Value =
            serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("播放设备注册响应无效"))?;
        let mut device = parse_device(&value)?;
        device.cookie = cookie;
        Ok(device)
    }
    async fn resolve(&self, episode: &str) -> Result<Ladder> {
        let device = self.identity().await?;
        let result = if let Some(url) = &self.mobile_url {
            match self.fetch_mobile(url.clone(), episode, &device).await {
                Err(error)
                    if error.is::<EmptyResponse>()
                        || matches!(
                            error.downcast_ref::<ModelError>(),
                            Some(ModelError::NoPlayableSource)
                        ) =>
                {
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
                Err(error) if error.is::<EmptyResponse>() => {
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
    ) -> Result<PlaybackModel> {
        let values = [
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
        .collect::<Vec<_>>();
        let payload = json!({"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":[episode]},"dr_scene":"player"});
        let mut gzip = GzEncoder::new(Vec::new(), Compression::default());
        gzip.write_all(&serde_json::to_vec(&payload)?)?;
        let body = gzip.finish()?;
        let (query, headers) = self.signer.sign(&values, &body, now_ms())?;
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
            .map_err(|_| anyhow::anyhow!("移动播放模型连接失败"))?;
        let bytes = response_bytes(response)
            .await
            .context("播放信息读取失败，请稍后重试")?;
        PlaybackModel::decode_response(&bytes, episode).map_err(Into::into)
    }

    async fn fetch(&self, episode: &str, device: &Device) -> Result<PlaybackModel> {
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
            .map_err(|_| anyhow::anyhow!("注册源播放信息连接失败"))?;
        let bytes = response_bytes(response)
            .await
            .context("播放信息读取失败，请稍后重试")?;
        let model = PlaybackModel::single_model(&bytes, episode)?;
        match PlaybackModel::from_value(&model) {
            Ok(model) => Ok(model),
            Err(ModelError::NoPlayableSource | ModelError::InvalidResponse) => {
                let url = fallback_url(&model).context("没有可播放的视频资源")?;
                let response = self
                    .client
                    .get(url)
                    .send()
                    .await
                    .map_err(|_| anyhow::anyhow!("兼容播放信息连接失败"))?;
                let bytes = response_bytes(response).await?;
                let root: Value = serde_json::from_slice(&bytes)
                    .map_err(|_| anyhow::anyhow!("兼容播放信息格式异常"))?;
                crate::model::check_status(&root)?;
                let info = root
                    .pointer("/video_info/data")
                    .context("没有兼容播放模型")?;
                PlaybackModel::from_value(info).map_err(Into::into)
            }
            Err(error) => Err(error.into()),
        }
    }
}
#[derive(Debug)]
struct EmptyResponse;
impl std::fmt::Display for EmptyResponse {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("注册源返回空响应，请稍后重试")
    }
}
impl std::error::Error for EmptyResponse {}
async fn response_bytes(response: reqwest::Response) -> Result<Vec<u8>> {
    ensure!(
        response.status().is_success(),
        "注册源请求失败（HTTP {}）",
        response.status().as_u16()
    );
    ensure!(
        !response.headers().contains_key("retry-after"),
        "注册源要求稍后重试"
    );
    let bytes = read_limited(response, LIMIT).await?;
    if bytes.iter().all(u8::is_ascii_whitespace) {
        return Err(EmptyResponse.into());
    }
    Ok(bytes)
}
pub(crate) fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn parse_device(value: &Value) -> Result<Device> {
    crate::model::check_status(value)?;
    let id = |name: &str| -> Result<String> {
        let v = value
            .get(format!("{name}_str"))
            .filter(|v| v.as_str().is_some_and(|s| !s.is_empty()))
            .or_else(|| value.get(name));
        let text = match v {
            Some(Value::String(s)) => s.clone(),
            Some(Value::Number(n)) => n.to_string(),
            _ => String::new(),
        };
        ensure!(
            valid_id(&text) && text.bytes().any(|b| b != b'0'),
            "注册源没有返回有效设备身份"
        );
        Ok(text)
    };
    Ok(Device {
        device_id: id("device_id")?,
        install_id: id("install_id")?,
        cdid: Uuid::new_v4().to_string(),
        cookie: None,
    })
}
fn registration_cookie(headers: &HeaderMap) -> Option<HeaderValue> {
    let mut cookies = BTreeMap::new();
    for header in headers.get_all(SET_COOKIE) {
        if let Ok(value) = header.to_str()
            && let Some((name, value)) = value
                .split(';')
                .next()
                .and_then(|part| part.split_once('='))
            && matches!(
                name,
                "install_id" | "ttreq" | "store-region" | "store-region-src"
            )
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
                let response = format!(
                    "HTTP/1.1 {code} Fixture\r\nContent-Length: {}\r\n{retry}Connection: close\r\n\r\n{response}",
                    response.len()
                );
                socket.write_all(response.as_bytes()).await.unwrap();
            }
        });
        Fixture {
            source: Arc::new(RegisteredSource {
                client: Client::builder().no_proxy().build().unwrap(),
                session: Mutex::new(Session::default()),
                register_url: base.join("register").unwrap(),
                model_url: base.join("model").unwrap(),
                mobile_url: None,
                signer: crate::v5::Signer::new(now_ms()),
            }),
            registers,
            models,
            task,
        }
    }
    #[tokio::test]
    async fn mobile_primary_binds_gzip_and_rejection_does_not_fall_back() {
        for (status, body, backoff, calls, success) in [
            (
                200,
                r#"{"code":0,"data":{"1004":{"123":{"video_model":{"video_list":[{"main_url":"https://media.example/clip.mp4","codec_type":"h264"}]}}}}}"#,
                false,
                1,
                true,
            ),
            (403, "", false, 1, false),
            (429, "", false, 1, false),
            (200, r#"{"code":110001}"#, false, 1, false),
            (200, "", true, 1, false),
            (200, "", false, 2, false),
        ] {
            let mut f = fixture(
                r#"{"device_id":333,"install_id":444}"#,
                status,
                body,
                backoff,
            )
            .await;
            let source = Arc::get_mut(&mut f.source).unwrap();
            source.mobile_url = Some(source.model_url.join("mobile").unwrap());
            assert_eq!(f.source.ladder("123").await.is_ok(), success);
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
        let f = fixture(r#"{"device_id":"000","install_id":444}"#, 200, GOOD, false).await;
        for _ in 0..3 {
            assert!(f.source.ladder("123").await.is_err());
        }
        assert!(f.source.session.lock().await.device.is_none());
        assert_eq!(f.registers.load(Ordering::SeqCst), 1);
        assert_eq!(f.models.load(Ordering::SeqCst), 0);
    }
    #[tokio::test]
    async fn persistent_empty_rotates_only_on_later_foreground_call_after_cooldown() {
        let f = fixture(r#"{"device_id":333,"install_id":444}"#, 200, "", false).await;
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
