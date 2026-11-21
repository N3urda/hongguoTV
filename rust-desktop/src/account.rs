// SPDX-License-Identifier: GPL-3.0-only
// The website Passport flow follows the existing TV WebLogin client.
use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use reqwest::cookie::{CookieStore, Jar};
use reqwest::header::{self, HeaderValue};
use reqwest::{Client, Url, redirect};
use serde::Serialize;
use serde_json::Value;
use tokio::sync::Mutex;
use uuid::Uuid;

use crate::catalog::{USER_AGENT, WEBSITE, read_limited};
use crate::vault::Vault;

const ACCOUNT_LIMIT: usize = 2 * 1024 * 1024;
const COOKIE_LIMIT: usize = 32 * 1024;
const IMAGE_LIMIT: usize = 512 * 1024;

#[derive(Clone, Serialize)]
pub(crate) struct Profile {
    nickname: String,
}

struct Session {
    cookie: HeaderValue,
    profile: Profile,
    remembered: bool,
}

struct Pending {
    id: Uuid,
    web: WebSession,
    token: String,
    image: Arc<[u8]>,
    content_type: &'static str,
    expires: Instant,
    remember: bool,
    last_poll: Mutex<Option<Instant>>,
}

#[derive(Serialize)]
struct Challenge {
    id: Uuid,
    remaining: u64,
    scanned: bool,
}

#[derive(Serialize)]
pub(crate) struct AccountStatus {
    restoring: bool,
    profile: Option<Profile>,
    remembered: bool,
    challenge: Option<Challenge>,
    notice: Option<String>,
}

struct State {
    restoring: bool,
    generation: Uuid,
    revision: Uuid,
    pending: Option<Arc<Pending>>,
    session: Option<Session>,
    scanned: bool,
    notice: Option<String>,
}

impl State {
    fn status(&self) -> AccountStatus {
        AccountStatus {
            restoring: self.restoring,
            profile: self.session.as_ref().map(|row| row.profile.clone()),
            remembered: self.session.as_ref().is_some_and(|row| row.remembered),
            challenge: self.pending.as_ref().map(|pending| Challenge {
                id: pending.id,
                remaining: pending
                    .expires
                    .saturating_duration_since(Instant::now())
                    .as_secs()
                    .saturating_add(1),
                scanned: self.scanned,
            }),
            notice: self.notice.clone(),
        }
    }

    fn clear_challenge(&mut self) {
        self.generation = Uuid::new_v4();
        self.pending = None;
        self.scanned = false;
    }
}

pub(crate) struct Account {
    base: Url,
    client: Client,
    vault: Vault,
    state: Mutex<State>,
}

impl Account {
    pub(crate) fn new(directory: &Path) -> Result<Self> {
        Self::with_origin(directory, Url::parse(WEBSITE)?)
    }

    fn with_origin(directory: &Path, base: Url) -> Result<Self> {
        Ok(Self {
            base,
            client: client_builder().build()?,
            vault: Vault::new(directory),
            state: Mutex::new(State {
                restoring: true,
                generation: Uuid::new_v4(),
                revision: Uuid::new_v4(),
                pending: None,
                session: None,
                scanned: false,
                notice: None,
            }),
        })
    }

    pub(crate) async fn restore(&self) {
        let vault = self.vault.clone();
        let outcome = async {
            let Some(bytes) = tokio::task::spawn_blocking(move || vault.load()).await?? else {
                return Ok::<_, anyhow::Error>(None);
            };
            let cookie = session_cookie(&bytes)?;
            let profile = self.profile(cookie.clone()).await?;
            Ok(Some(Session {
                cookie,
                profile,
                remembered: true,
            }))
        }
        .await;
        let mut state = self.state.lock().await;
        if !state.restoring {
            return;
        }
        match outcome {
            Ok(session) => {
                if session.is_some() {
                    state.revision = Uuid::new_v4();
                }
                state.session = session;
            }
            Err(_) => state.notice = Some("已保存的登录态暂不可用，可重新扫码".to_owned()),
        }
        state.restoring = false;
    }

    pub(crate) async fn status(&self) -> AccountStatus {
        let mut state = self.state.lock().await;
        if state
            .pending
            .as_ref()
            .is_some_and(|row| row.expires <= Instant::now())
        {
            state.clear_challenge();
            state.notice = Some("二维码已过期，请重新获取".to_owned());
        }
        state.status()
    }

    pub(crate) async fn authorization(&self) -> (Uuid, Option<HeaderValue>) {
        let state = self.state.lock().await;
        (
            state.revision,
            state.session.as_ref().map(|session| session.cookie.clone()),
        )
    }

    pub(crate) async fn start_qr(&self, remember: bool) -> Result<AccountStatus> {
        let id = {
            let mut state = self.state.lock().await;
            ensure!(!state.restoring, "正在检查本机登录态，请稍后");
            ensure!(state.session.is_none(), "请先退出当前账号");
            state.clear_challenge();
            state.notice = None;
            state.generation
        };
        let web = WebSession::new(self.base.clone())?;
        let reply = web
            .call("/passport/web/get_qrcode/", &[("next", self.base.as_str())])
            .await?;
        let token = reply["token"]
            .as_str()
            .filter(|value| !value.is_empty() && value.len() <= 2048)
            .context("扫码会话返回异常")?
            .to_owned();
        let encoded = reply["qrcode"].as_str().context("官网没有返回登录二维码")?;
        let encoded = encoded
            .split_once("base64,")
            .map_or(encoded, |(_, body)| body);
        ensure!(encoded.len() <= IMAGE_LIMIT * 2, "登录二维码超过大小限制");
        let image = STANDARD
            .decode(encoded)
            .map_err(|_| anyhow::anyhow!("登录二维码格式异常"))?;
        let content_type = qr_content_type(&image)?;
        let remaining = qr_expiry(&reply["expire_time"])?;
        let mut state = self.state.lock().await;
        ensure!(state.generation == id, "登录请求已取消");
        state.pending = Some(Arc::new(Pending {
            id,
            web,
            token,
            image: image.into(),
            content_type,
            expires: Instant::now() + remaining,
            remember,
            last_poll: Mutex::new(None),
        }));
        Ok(state.status())
    }

    pub(crate) async fn image(&self, id: Uuid) -> Option<(&'static str, Arc<[u8]>)> {
        let state = self.state.lock().await;
        let pending = state
            .pending
            .as_ref()
            .filter(|row| row.id == id && row.expires > Instant::now())?;
        Some((pending.content_type, pending.image.clone()))
    }

    pub(crate) async fn poll(&self, id: Uuid) -> Result<AccountStatus> {
        let pending = {
            let state = self.state.lock().await;
            state.pending.as_ref().filter(|row| row.id == id).cloned()
        };
        let Some(pending) = pending else {
            return Ok(self.status().await);
        };
        let Ok(mut last) = pending.last_poll.try_lock() else {
            return Ok(self.status().await);
        };
        if pending.expires <= Instant::now()
            || last.is_some_and(|at| at.elapsed() < Duration::from_secs(1))
        {
            return Ok(self.status().await);
        }
        *last = Some(Instant::now());
        let reply = pending
            .web
            .call(
                "/passport/web/check_qrconnect/",
                &[("next", self.base.as_str()), ("token", &pending.token)],
            )
            .await?;
        let status = text(&reply["status"]).unwrap_or_default();
        if matches!(status.as_str(), "expired" | "5") {
            let mut state = self.state.lock().await;
            if state.generation == id && pending.expires > Instant::now() {
                state.clear_challenge();
                state.notice = Some("二维码已过期，请重新获取".to_owned());
            }
            return Ok(state.status());
        }
        if matches!(status.as_str(), "3" | "confirmed")
            && let Some(redirect) = reply["redirect_url"]
                .as_str()
                .filter(|value| !value.is_empty())
        {
            pending.web.confirm(redirect).await?;
        }
        if let Some(cookie) = pending
            .web
            .jar
            .cookies(&self.base)
            .and_then(|value| session_cookie(value.as_bytes()).ok())
        {
            let profile = self.profile(cookie.clone()).await?;
            let mut state = self.state.lock().await;
            if state.generation == id {
                state.clear_challenge();
                let vault = self.vault.clone();
                let bytes = cookie.as_bytes().to_vec();
                let remember = pending.remember;
                let saved = tokio::task::spawn_blocking(move || {
                    if remember {
                        vault.save(&bytes)
                    } else {
                        vault.clear()
                    }
                })
                .await
                .is_ok_and(|result| result.is_ok());
                if !saved {
                    state.notice = Some(
                        if remember {
                            "本次登录已生效，系统未能记住登录态"
                        } else {
                            "系统未能清除先前记住的登录态，可在账号页退出后重试"
                        }
                        .to_owned(),
                    );
                }
                state.session = Some(Session {
                    cookie,
                    profile,
                    remembered: remember && saved,
                });
                state.revision = Uuid::new_v4();
            }
            return Ok(state.status());
        }
        let mut state = self.state.lock().await;
        if state.generation == id {
            state.scanned |= matches!(
                status.as_str(),
                "2" | "scanned" | "scan" | "3" | "confirmed"
            );
        }
        Ok(state.status())
    }

    pub(crate) async fn cancel(&self) -> AccountStatus {
        let mut state = self.state.lock().await;
        if state.session.is_none() {
            state.clear_challenge();
            state.notice = None;
        }
        state.status()
    }

    pub(crate) async fn logout(&self) -> Result<AccountStatus> {
        let mut state = self.state.lock().await;
        state.restoring = false;
        state.clear_challenge();
        let vault = self.vault.clone();
        tokio::task::spawn_blocking(move || vault.clear())
            .await
            .context("无法清除本机登录态")??;
        state.session = None;
        state.revision = Uuid::new_v4();
        state.notice = None;
        Ok(state.status())
    }

    async fn profile(&self, cookie: HeaderValue) -> Result<Profile> {
        let data = passport_reply(
            passport_request(&self.client, &self.base, "/passport/account/info/v2/", &[])?
                .header(header::COOKIE, cookie),
        )
        .await?;
        let user = data
            .get("user")
            .filter(|value| value.is_object())
            .unwrap_or(&data);
        let nickname = ["nickname", "screen_name", "user_name", "name"]
            .iter()
            .filter_map(|key| user[*key].as_str())
            .find(|label| !label.trim().is_empty())
            .unwrap_or("红果账号")
            .trim()
            .chars()
            .take(80)
            .collect();
        Ok(Profile { nickname })
    }
}

struct WebSession {
    base: Url,
    client: Client,
    jar: Arc<Jar>,
}

impl WebSession {
    fn new(base: Url) -> Result<Self> {
        let jar = Arc::new(Jar::default());
        let client = client_builder().cookie_provider(jar.clone()).build()?;
        Ok(Self { base, client, jar })
    }

    async fn call(&self, path: &str, params: &[(&str, &str)]) -> Result<Value> {
        let request = passport_request(&self.client, &self.base, path, params)?;
        let csrf = self.jar.cookies(&self.base.join(path)?).and_then(|value| {
            let cookies = value.to_str().ok()?;
            cookies
                .split(';')
                .find_map(|part| part.trim().strip_prefix("passport_csrf_token="))
                .and_then(|value| HeaderValue::from_str(value).ok())
        });
        let request = match csrf {
            Some(value) => request.header("x-tt-passport-csrf-token", value),
            None => request,
        };
        passport_reply(request).await
    }

    async fn confirm(&self, location: &str) -> Result<()> {
        let mut url = self
            .base
            .join(location)
            .map_err(|_| anyhow::anyhow!("登录确认地址无效"))?;
        for _ in 0..5 {
            ensure!(
                url.origin() == self.base.origin()
                    && url.username().is_empty()
                    && url.password().is_none(),
                "登录确认地址不属于官网"
            );
            let response = self
                .client
                .get(url.clone())
                .header(header::REFERER, self.base.as_str())
                .send()
                .await
                .map_err(|_| anyhow::anyhow!("无法连接官网登录服务"))?;
            if !response.status().is_redirection() {
                ensure!(response.status().is_success(), "官网尚未完成登录确认");
                return Ok(());
            }
            url = response
                .headers()
                .get(header::LOCATION)
                .and_then(|value| value.to_str().ok())
                .and_then(|value| url.join(value).ok())
                .context("登录确认跳转无效")?;
        }
        anyhow::bail!("登录确认跳转次数过多")
    }
}

fn client_builder() -> reqwest::ClientBuilder {
    Client::builder()
        .user_agent(USER_AGENT)
        .redirect(redirect::Policy::none())
        .connect_timeout(Duration::from_secs(7))
        .timeout(Duration::from_secs(15))
}

fn passport_request(
    client: &Client,
    base: &Url,
    path: &str,
    params: &[(&str, &str)],
) -> Result<reqwest::RequestBuilder> {
    let mut url = base.join(path)?;
    url.query_pairs_mut()
        .extend_pairs([
            ("aid", "513755"),
            ("passport_jssdk_version", "3.4.10"),
            ("passport_jssdk_type", "normal"),
            ("account_sdk_source", "web"),
            ("is_from_ttaccountsdk", "1"),
            ("language", "zh"),
        ])
        .extend_pairs(params.iter().copied());
    Ok(client
        .get(url)
        .header(header::REFERER, base.as_str())
        .header(header::ORIGIN, base.origin().ascii_serialization())
        .header(header::ACCEPT, "application/json, text/javascript"))
}

async fn passport_reply(request: reqwest::RequestBuilder) -> Result<Value> {
    let response = request
        .send()
        .await
        .map_err(|_| anyhow::anyhow!("无法连接官网登录服务，请检查网络"))?;
    ensure!(
        response.status().is_success(),
        "官网登录请求失败（HTTP {}）",
        response.status().as_u16()
    );
    let bytes = read_limited(response, ACCOUNT_LIMIT)
        .await
        .map_err(|_| anyhow::anyhow!("无法读取官网登录响应"))?;
    let mut reply: Value =
        serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("官网登录响应格式异常"))?;
    let data = reply["data"].take();
    ensure!(
        reply["message"].as_str() == Some("success"),
        "官网登录未完成（错误码 {}）",
        data["error_code"].as_i64().unwrap_or(-1)
    );
    ensure!(data.is_object(), "官网登录数据格式异常");
    Ok(data)
}

fn session_cookie(bytes: &[u8]) -> Result<HeaderValue> {
    ensure!(bytes.len() <= COOKIE_LIMIT, "网页登录态超过大小限制");
    let mut cookie = HeaderValue::from_bytes(bytes).context("网页登录态格式异常")?;
    let matched = cookie.to_str().ok().is_some_and(|text| {
        text.split(';').any(|part| {
            part.trim().split_once('=').is_some_and(|(name, value)| {
                matches!(name, "sessionid" | "sessionid_ss") && !value.is_empty()
            })
        })
    });
    ensure!(matched, "官网尚未确认登录");
    cookie.set_sensitive(true);
    Ok(cookie)
}

fn qr_content_type(image: &[u8]) -> Result<&'static str> {
    ensure!(
        !image.is_empty() && image.len() <= IMAGE_LIMIT,
        "登录二维码大小异常"
    );
    if image.starts_with(b"\x89PNG\r\n\x1a\n") {
        Ok("image/png")
    } else if image.starts_with(b"\xff\xd8\xff") {
        Ok("image/jpeg")
    } else {
        anyhow::bail!("登录二维码不是支持的图片格式")
    }
}

fn qr_expiry(value: &Value) -> Result<Duration> {
    let raw = text(value)
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(180);
    let seconds = if raw > 1_000_000_000 {
        raw.saturating_sub(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
        )
    } else {
        raw
    };
    ensure!(seconds > 0, "二维码已过期，请重新获取");
    Ok(Duration::from_secs(seconds.min(600)))
}

fn text(value: &Value) -> Option<String> {
    value
        .as_str()
        .map(ToOwned::to_owned)
        .or_else(|| value.as_u64().map(|value| value.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        Json, Router,
        extract::Query,
        http::{HeaderMap, StatusCode},
        routing::get,
    };
    use serde_json::json;
    use std::collections::HashMap;

    const PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAoMBgAnb98kAAAAASUVORK5CYII=";

    #[tokio::test]
    async fn login_confirms_and_invalidates_cancelled_challenges() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let origin = Url::parse(&format!("http://{}/", listener.local_addr().unwrap())).unwrap();
        let routes = Router::new()
            .route("/passport/web/get_qrcode/", get(|| async {
                ([("set-cookie", "passport_csrf_token=fixture; Path=/passport/")],
                    Json(json!({"message":"success", "data":{"token":"fixture-ticket", "qrcode":PNG, "expire_time":60}})))
            }))
            .route("/passport/web/check_qrconnect/", get(
                |Query(query): Query<HashMap<String, String>>, headers: HeaderMap| async move {
                    assert_eq!(query["token"], "fixture-ticket");
                    assert_eq!(headers["x-tt-passport-csrf-token"], "fixture");
                    Json(json!({"message":"success", "data":{"status":3, "redirect_url":"/confirm"}}))
                }
            ))
            .route("/confirm", get(|| async { (StatusCode::FOUND, [("location", "/done")]) }))
            .route("/done", get(|| async { [("set-cookie", "sessionid=fixture-session; Path=/; HttpOnly")] }))
            .route("/passport/account/info/v2/", get(|headers: HeaderMap| async move {
                assert_eq!(headers["cookie"], "sessionid=fixture-session");
                Json(json!({"message":"success", "data":{"nickname":"测试账号"}}))
            }));
        let server = tokio::spawn(async move { axum::serve(listener, routes).await.unwrap() });
        let directory = tempfile::tempdir().unwrap();
        let account = Account::with_origin(directory.path(), origin.clone()).unwrap();
        account.restore().await;
        let revision = account.authorization().await.0;
        let first = account.start_qr(false).await.unwrap().challenge.unwrap().id;
        assert_eq!(account.image(first).await.unwrap().0, "image/png");
        account.cancel().await;
        assert!(account.image(first).await.is_none());
        assert!(account.poll(first).await.unwrap().profile.is_none());
        assert_eq!(account.authorization().await.0, revision);

        let challenge = account.start_qr(false).await.unwrap().challenge.unwrap();
        assert_ne!(first, challenge.id);
        let signed_in = account.poll(challenge.id).await.unwrap();
        assert_eq!(signed_in.profile.as_ref().unwrap().nickname, "测试账号");
        assert!(!signed_in.remembered);
        assert!(signed_in.challenge.is_none());
        assert!(
            !serde_json::to_string(&signed_in)
                .unwrap()
                .contains("fixture-session")
        );
        let (next_revision, cookie) = account.authorization().await;
        assert_ne!(next_revision, revision);
        assert!(cookie.unwrap().is_sensitive());
        assert!(account.logout().await.unwrap().profile.is_none());
        assert!(account.authorization().await.1.is_none());
        assert!(
            WebSession::new(origin)
                .unwrap()
                .confirm("http://example.invalid/")
                .await
                .is_err()
        );
        server.abort();
    }
}
