// SPDX-License-Identifier: GPL-3.0-only
use std::{collections::VecDeque, net::Ipv4Addr, path::Path, sync::Arc, time::Instant};

use anyhow::{Context, Result};
use axum::Json;
use axum::Router;
use axum::body::{Body, Bytes};
use axum::extract::{Path as RoutePath, Query, Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use hongguo_playback::resume_target;
use hongguo_playback::source::{
    AuthorizedSources, PlaybackScope, RemoteMp4, authorized_media_client,
};
use serde::{Deserialize, Serialize};
use tokio::net::TcpListener;
use tokio::sync::{Mutex, Semaphore};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::account::{Account, AccountStatus};
use crate::backup;
use crate::catalog::{Category, Detail, PreviewUnavailable, RankBoard, Series, Site, valid_id};
use crate::library::{LibraryStore, Preferences, WatchPosition};
use crate::media;
use crate::updates;

const MAX_SESSIONS: usize = 4;

type ApiResult<T> = Result<T, ApiError>;

struct ApiError(StatusCode, String);

impl ApiError {
    fn bad_request(message: impl Into<String>) -> Self {
        Self(StatusCode::BAD_REQUEST, message.into())
    }

    fn gone() -> Self {
        Self(
            StatusCode::GONE,
            "播放会话已关闭，请重新打开该集".to_owned(),
        )
    }

    fn upstream(error: anyhow::Error) -> Self {
        let status = if error.is::<PreviewUnavailable>() {
            StatusCode::NOT_FOUND
        } else {
            StatusCode::BAD_GATEWAY
        };
        Self(status, error.to_string())
    }

    fn storage(error: anyhow::Error) -> Self {
        Self(StatusCode::INTERNAL_SERVER_ERROR, error.to_string())
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(serde_json::json!({ "error": self.1 }))).into_response()
    }
}

struct Session {
    token: Uuid,
    series: Series,
    episode: String,
    number: usize,
    media: Arc<RemoteMp4>,
    scope: PlaybackScope,
    cancel: CancellationToken,
    last_sequence: u64,
    last_used: Instant,
}

impl Drop for Session {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

#[derive(Clone)]
struct App {
    site: Site,
    account: Arc<Account>,
    store: Arc<LibraryStore>,
    sources: Arc<AuthorizedSources>,
    full_media: reqwest::Client,
    sessions: Arc<Mutex<VecDeque<Session>>>,
    readers: Arc<Semaphore>,
    prepares: Arc<Semaphore>,
}

impl App {
    async fn account_site(&self) -> Site {
        self.site.with_cookie(self.account.authorization().await.1)
    }

    async fn detail(&self, site: &Site, id: &str) -> Result<Detail> {
        let mut detail = site.detail(id).await?;
        for episode in &mut detail.episodes {
            episode.full = self.sources.for_episode(id, &episode.id).is_some();
            episode.available |= episode.full;
        }
        Ok(detail)
    }
}

pub struct RunningServer {
    pub url: String,
    task: JoinHandle<()>,
}

impl Drop for RunningServer {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub async fn start(data_dir: &Path) -> Result<RunningServer> {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
        .await
        .context("无法启动桌面播放服务")?;
    let authority = listener.local_addr()?.to_string();
    let origin = format!("http://{authority}");
    let route = format!("/{}", Uuid::new_v4().simple());
    let scope = format!("{origin}{route}/");
    let url = format!("{scope}index.html");
    let sources = std::env::var_os("HONGGUO_PLAYBACK_MANIFEST")
        .map(|path| AuthorizedSources::load(Path::new(&path)))
        .transpose()?
        .unwrap_or_default();
    let app = App {
        site: Site::new().context("无法初始化官网连接")?,
        store: Arc::new(LibraryStore::open(data_dir)?),
        account: Arc::new(Account::new(data_dir)?),
        sources: Arc::new(sources),
        full_media: authorized_media_client()?,
        sessions: Arc::new(Mutex::new(VecDeque::new())),
        readers: Arc::new(Semaphore::new(6)),
        prepares: Arc::new(Semaphore::new(4)),
    };
    let account = app.account.clone();
    tokio::spawn(async move { account.restore().await });
    let routes = Router::new()
        .route(
            "/index.html",
            get(|| async {
                asset(
                    "text/html; charset=utf-8",
                    include_bytes!("../ui/index.html"),
                )
            }),
        )
        .route(
            "/app.js",
            get(|| async {
                asset(
                    "text/javascript; charset=utf-8",
                    include_bytes!("../ui/app.js"),
                )
            }),
        )
        .route(
            "/settings.js",
            get(|| async {
                asset(
                    "text/javascript; charset=utf-8",
                    include_bytes!("../ui/settings.js"),
                )
            }),
        )
        .route(
            "/style.css",
            get(|| async { asset("text/css; charset=utf-8", include_bytes!("../ui/style.css")) }),
        )
        .route(
            "/logo.png",
            get(|| async {
                asset(
                    "image/png",
                    include_bytes!("../../assets/brand/logo-pixel.png"),
                )
            }),
        )
        .route(
            "/brand.png",
            get(|| async {
                asset(
                    "image/png",
                    include_bytes!(
                        "../../kotlin-tv/app/src/main/res/drawable-nodpi/brand_mark.png"
                    ),
                )
            }),
        )
        .route("/api/catalog", get(catalog))
        .route("/api/rank", get(rank))
        .route("/api/preferences", get(preferences).post(save_preferences))
        .route(
            "/api/app",
            get(|| async {
                Json(serde_json::json!({
                    "version": env!("CARGO_PKG_VERSION"),
                    "project": updates::PROJECT,
                }))
            }),
        )
        .route("/api/update", get(check_update))
        .route("/api/account", get(account_status).delete(account_logout))
        .route(
            "/api/account/qr",
            post(account_start).delete(account_cancel),
        )
        .route("/api/account/qr/{id}", get(account_qr).post(account_poll))
        .route("/api/detail/{id}", get(detail))
        .route("/api/library", get(library))
        .route(
            "/api/library/backup",
            get(export_backup).post(import_backup),
        )
        .route("/api/favorite", post(favorite))
        .route("/api/later", post(later))
        .route("/api/play", post(play))
        .route("/api/play/{token}", delete(stop))
        .route("/api/play/{token}/progress", post(progress))
        .route("/media/{token}", get(video))
        .with_state(app);
    let router = Router::new()
        .nest(&route, routes)
        .layer(middleware::from_fn(move |request: Request, next: Next| {
            let authority = authority.clone();
            let origin = origin.clone();
            async move { guard(request, next, &authority, &origin).await }
        }));
    let task = tokio::spawn(async move {
        if axum::serve(listener, router).await.is_err() {
            eprintln!("桌面播放服务已停止");
        }
    });
    Ok(RunningServer { url, task })
}

async fn guard(request: Request, next: Next, authority: &str, origin: &str) -> Response {
    let headers = request.headers();
    if headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
        != Some(authority)
        || headers
            .get(header::ORIGIN)
            .is_some_and(|value| value.as_bytes() != origin.as_bytes())
    {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mut response = next.run(request).await;
    let headers = response.headers_mut();
    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    headers.insert(
        header::REFERRER_POLICY,
        HeaderValue::from_static("no-referrer"),
    );
    headers.insert(header::CONTENT_SECURITY_POLICY, HeaderValue::from_static(
        "default-src 'self'; img-src 'self' https:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
    ));
    response
}

fn asset(content_type: &'static str, bytes: &'static [u8]) -> Response {
    ([(header::CONTENT_TYPE, content_type)], Body::from(bytes)).into_response()
}

#[derive(Deserialize)]
struct CatalogQuery {
    category: Option<Category>,
    q: Option<String>,
    page: Option<u32>,
}

async fn catalog(
    State(app): State<App>,
    Query(query): Query<CatalogQuery>,
) -> ApiResult<impl IntoResponse> {
    let page = query.page.unwrap_or(1);
    if !(1..=100).contains(&page) {
        return Err(ApiError::bad_request("页码无效"));
    }
    let keyword = query.q.as_deref().unwrap_or_default().trim();
    if keyword.chars().count() > 80 {
        return Err(ApiError::bad_request("搜索词最多 80 个字"));
    }
    let site = app.account_site().await;
    let result = if keyword.is_empty() {
        site.discover(query.category.unwrap_or(Category::Real), page)
            .await
    } else {
        site.search(keyword, page).await
    };
    result.map(Json).map_err(ApiError::upstream)
}

#[derive(Deserialize)]
struct RankQuery {
    board: RankBoard,
    page: Option<u32>,
}

async fn rank(
    State(app): State<App>,
    Query(query): Query<RankQuery>,
) -> ApiResult<impl IntoResponse> {
    let page = query.page.unwrap_or(1);
    if !(1..=100).contains(&page) {
        return Err(ApiError::bad_request("页码无效"));
    }
    app.site
        .rank(query.board, page)
        .await
        .map(Json)
        .map_err(ApiError::upstream)
}

async fn preferences(State(app): State<App>) -> ApiResult<Json<Preferences>> {
    app.store.preferences().map(Json).map_err(ApiError::storage)
}

async fn check_update(State(app): State<App>) -> ApiResult<Json<Option<updates::Update>>> {
    updates::check(&app.site.media_client)
        .await
        .map(Json)
        .map_err(ApiError::upstream)
}

async fn save_preferences(
    State(app): State<App>,
    Json(preferences): Json<Preferences>,
) -> ApiResult<Json<Preferences>> {
    preferences
        .validate()
        .map_err(|error| ApiError::bad_request(error.to_string()))?;
    tokio::task::spawn_blocking(move || app.store.set_preferences(preferences))
        .await
        .context("无法保存设置")
        .map_err(ApiError::storage)?
        .map_err(ApiError::storage)?;
    Ok(Json(preferences))
}

async fn account_status(State(app): State<App>) -> Json<AccountStatus> {
    Json(app.account.status().await)
}

#[derive(Deserialize)]
struct AccountStart {
    remember: bool,
}

async fn account_start(
    State(app): State<App>,
    Json(request): Json<AccountStart>,
) -> ApiResult<Json<AccountStatus>> {
    app.account
        .start_qr(request.remember)
        .await
        .map(Json)
        .map_err(ApiError::upstream)
}

async fn account_cancel(State(app): State<App>) -> Json<AccountStatus> {
    Json(app.account.cancel().await)
}

async fn account_qr(State(app): State<App>, RoutePath(id): RoutePath<Uuid>) -> ApiResult<Response> {
    let (format, image) = app
        .account
        .image(id)
        .await
        .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "二维码已失效".to_owned()))?;
    Ok((
        [(header::CONTENT_TYPE, format)],
        Body::from(axum::body::Bytes::from_owner(image)),
    )
        .into_response())
}

async fn account_poll(
    State(app): State<App>,
    RoutePath(id): RoutePath<Uuid>,
) -> ApiResult<Json<AccountStatus>> {
    app.account
        .poll(id)
        .await
        .map(Json)
        .map_err(ApiError::upstream)
}

async fn account_logout(State(app): State<App>) -> ApiResult<Json<AccountStatus>> {
    let mut sessions = app.sessions.lock().await;
    let status = app.account.logout().await.map_err(ApiError::storage)?;
    sessions.clear();
    Ok(Json(status))
}

#[derive(Serialize)]
struct PlaybackResume {
    episode: String,
    position: i64,
}

#[derive(Serialize)]
struct DetailResponse {
    #[serde(flatten)]
    detail: Detail,
    resume: Option<PlaybackResume>,
}

async fn detail(
    State(app): State<App>,
    RoutePath(id): RoutePath<String>,
) -> ApiResult<impl IntoResponse> {
    if !valid_id(&id) {
        return Err(ApiError::bad_request("剧集编号无效"));
    }
    let site = app.account_site().await;
    let detail = app.detail(&site, &id).await.map_err(ApiError::upstream)?;
    let history = app.store.history_for(&id).map_err(ApiError::storage)?;
    let has_full = detail.episodes.iter().any(|episode| episode.full);
    let available: Vec<_> = detail
        .episodes
        .iter()
        .filter(|episode| episode.available && (!has_full || episode.full))
        .collect();
    let matched = history.as_ref().and_then(|entry| {
        let saved = detail
            .episodes
            .iter()
            .find(|episode| episode.id == entry.episode);
        let episode = match saved {
            Some(episode) => episode,
            None if entry.full_source => detail.episodes.get(entry.number.checked_sub(1)?)?,
            None => return None,
        };
        available
            .iter()
            .position(|candidate| candidate.id == episode.id && candidate.full == entry.full_source)
    });
    let position = history
        .as_ref()
        .map_or(0, |entry| i64::try_from(entry.position).unwrap_or(i64::MAX));
    let completed = history.as_ref().is_some_and(|entry| entry.completed);
    let resume =
        resume_target(available.len(), matched, position, completed).map(|target| PlaybackResume {
            episode: available[target.index].id.clone(),
            position: target.position,
        });
    Ok(Json(DetailResponse { detail, resume }))
}

async fn library(State(app): State<App>) -> ApiResult<impl IntoResponse> {
    app.store.read().map(Json).map_err(ApiError::storage)
}

async fn export_backup(State(app): State<App>) -> ApiResult<impl IntoResponse> {
    let library = app.store.read().map_err(ApiError::storage)?;
    let bytes = backup::encode(&library).map_err(ApiError::storage)?;
    Ok((
        [
            (header::CONTENT_TYPE, "application/json; charset=utf-8"),
            (
                header::CONTENT_DISPOSITION,
                "attachment; filename=\"hongguotv-library.json\"",
            ),
        ],
        bytes,
    ))
}

#[derive(Deserialize)]
struct ImportOptions {
    #[serde(default)]
    restore_settings: bool,
}

async fn import_backup(
    State(app): State<App>,
    Query(options): Query<ImportOptions>,
    bytes: Bytes,
) -> ApiResult<Json<backup::ImportCounts>> {
    let imported =
        backup::decode(&bytes).map_err(|error| ApiError::bad_request(error.to_string()))?;
    let mut counts = imported.counts();
    let playback = options
        .restore_settings
        .then(|| imported.playback_preferences())
        .transpose()
        .map_err(|error| ApiError::bad_request(error.to_string()))?;
    counts.preferences = tokio::task::spawn_blocking(move || {
        app.store.update(|library| {
            imported.merge(library);
            if let Some(settings) = playback {
                library.preferences.quality = settings.quality;
                library.preferences.speed = settings.speed;
                library.preferences.frame = settings.frame;
                library.preferences.rotation = settings.rotation;
                library.preferences.auto_next = settings.auto_next;
                library.preferences.show_progress = settings.show_progress;
            }
        })?;
        playback.map(|_| app.store.preferences()).transpose()
    })
    .await
    .context("无法导入备份")
    .map_err(ApiError::storage)?
    .map_err(ApiError::storage)?;
    Ok(Json(counts))
}

#[derive(Deserialize)]
struct SavedSeriesChange {
    id: String,
    saved: bool,
}

async fn favorite(
    State(app): State<App>,
    Json(change): Json<SavedSeriesChange>,
) -> ApiResult<StatusCode> {
    save_series(app, change, LibraryStore::set_favorite).await
}

async fn later(
    State(app): State<App>,
    Json(change): Json<SavedSeriesChange>,
) -> ApiResult<StatusCode> {
    save_series(app, change, LibraryStore::set_later).await
}

async fn save_series(
    app: App,
    change: SavedSeriesChange,
    update: fn(&LibraryStore, &str, Option<Series>) -> Result<()>,
) -> ApiResult<StatusCode> {
    if !valid_id(&change.id) {
        return Err(ApiError::bad_request("剧集编号无效"));
    }
    let series = if change.saved {
        Some(
            app.account_site()
                .await
                .detail(&change.id)
                .await
                .map_err(ApiError::upstream)?
                .series,
        )
    } else {
        None
    };
    tokio::task::spawn_blocking(move || update(&app.store, &change.id, series))
        .await
        .context("无法保存剧集")
        .map_err(ApiError::storage)?
        .map_err(ApiError::storage)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct Selection {
    series: String,
    episode: String,
    quality: Option<u16>,
    scope: Option<PlaybackScope>,
}

#[derive(Serialize)]
struct Playback {
    token: Uuid,
    quality: u16,
    qualities: Vec<u16>,
    scope: PlaybackScope,
}

async fn play(
    State(app): State<App>,
    Json(selection): Json<Selection>,
) -> ApiResult<impl IntoResponse> {
    if !valid_id(&selection.series) || !valid_id(&selection.episode) {
        return Err(ApiError::bad_request("剧集编号无效"));
    }
    let _permit = app.prepares.try_acquire().map_err(|_| {
        ApiError(
            StatusCode::TOO_MANY_REQUESTS,
            "正在打开其他分集，请稍后重试".to_owned(),
        )
    })?;
    let (authorization, cookie) = app.account.authorization().await;
    let site = app.site.with_cookie(cookie);
    let detail = app
        .detail(&site, &selection.series)
        .await
        .map_err(ApiError::upstream)?;
    let episode = detail
        .episodes
        .into_iter()
        .find(|row| row.id == selection.episode && row.available)
        .ok_or_else(|| ApiError::upstream(PreviewUnavailable.into()))?;
    let scope = selection.scope.unwrap_or(if episode.full {
        PlaybackScope::Full
    } else {
        PlaybackScope::Preview
    });
    let sources = match scope {
        PlaybackScope::Full => app
            .sources
            .for_episode(&selection.series, &episode.id)
            .cloned()
            .ok_or_else(|| ApiError(StatusCode::NOT_FOUND, "本集暂未接入正式播放源".to_owned()))?,
        PlaybackScope::Preview => site
            .public_media(&selection.series, &episode.id)
            .await
            .map_err(ApiError::upstream)?,
    };
    let maximum = app.store.preferences().map_err(ApiError::storage)?.quality;
    let source = sources
        .select(maximum, selection.quality)
        .ok_or_else(|| ApiError::bad_request("本集暂未提供所选清晰度"))?;
    let client = match source.scope {
        PlaybackScope::Full => app.full_media.clone(),
        PlaybackScope::Preview => app.site.media_client.clone(),
    };
    let media = RemoteMp4::open(client, source.url, source.referer)
        .await
        .map_err(ApiError::upstream)?;
    let token = Uuid::new_v4();
    let mut sessions = app.sessions.lock().await;
    if authorization != app.account.authorization().await.0 {
        return Err(ApiError::gone());
    }
    sessions.retain(|row| row.last_used.elapsed().as_secs() < 3600);
    if sessions.len() == MAX_SESSIONS {
        sessions.pop_front();
    }
    sessions.push_back(Session {
        token,
        series: detail.series,
        episode: episode.id,
        number: episode.number,
        media: Arc::new(media),
        scope: source.scope,
        cancel: CancellationToken::new(),
        last_sequence: 0,
        last_used: Instant::now(),
    });
    Ok(Json(Playback {
        token,
        quality: source.quality,
        qualities: source.qualities,
        scope: source.scope,
    }))
}

async fn stop(State(app): State<App>, RoutePath(token): RoutePath<Uuid>) -> StatusCode {
    app.sessions
        .lock()
        .await
        .retain(|session| session.token != token);
    StatusCode::NO_CONTENT
}

async fn progress(
    State(app): State<App>,
    RoutePath(token): RoutePath<Uuid>,
    Json(position): Json<WatchPosition>,
) -> ApiResult<StatusCode> {
    let mut sessions = app.sessions.lock().await;
    let session = sessions
        .iter_mut()
        .find(|session| session.token == token)
        .ok_or_else(ApiError::gone)?;
    if position.sequence <= session.last_sequence {
        return Ok(StatusCode::NO_CONTENT);
    }
    position
        .validate()
        .map_err(|error| ApiError::bad_request(error.to_string()))?;
    let sequence = position.sequence;
    let (series, episode, number, scope) = (
        session.series.clone(),
        session.episode.clone(),
        session.number,
        session.scope,
    );
    tokio::task::spawn_blocking(move || {
        app.store
            .record(&series, &episode, number, &position, scope)
    })
    .await
    .context("无法保存播放进度")
    .map_err(ApiError::storage)?
    .map_err(ApiError::storage)?;
    session.last_sequence = sequence;
    session.last_used = Instant::now();
    Ok(StatusCode::NO_CONTENT)
}

async fn video(
    State(app): State<App>,
    RoutePath(token): RoutePath<Uuid>,
    method: Method,
    headers: HeaderMap,
) -> ApiResult<Response> {
    let (media, cancel) = {
        let mut sessions = app.sessions.lock().await;
        let session = sessions
            .iter_mut()
            .find(|session| session.token == token)
            .ok_or_else(ApiError::gone)?;
        session.last_used = Instant::now();
        (session.media.clone(), session.cancel.clone())
    };
    let permit = app.readers.try_acquire_owned().map_err(|_| {
        ApiError(
            StatusCode::SERVICE_UNAVAILABLE,
            "视频缓冲繁忙，请重试".to_owned(),
        )
    })?;
    media::serve(&media, method, headers, cancel, permit)
        .await
        .map_err(ApiError::upstream)
}
