// SPDX-License-Identifier: GPL-3.0-only
use std::{collections::VecDeque, net::Ipv4Addr, path::Path, sync::Arc, time::Instant};

use anyhow::{Context, Result};
use axum::Json;
use axum::Router;
use axum::body::Body;
use axum::extract::{Path as RoutePath, Query, Request, State};
use axum::http::{HeaderMap, HeaderValue, Method, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{delete, get, post};
use serde::{Deserialize, Serialize};
use tokio::net::TcpListener;
use tokio::sync::{Mutex, Semaphore};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

use crate::catalog::{Category, PreviewUnavailable, Series, Site, valid_id};
use crate::library::{LibraryStore, WatchPosition};
use crate::media::RemoteVideo;

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
    media: Arc<RemoteVideo>,
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
    store: Arc<LibraryStore>,
    sessions: Arc<Mutex<VecDeque<Session>>>,
    readers: Arc<Semaphore>,
    prepares: Arc<Semaphore>,
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
    let app = App {
        site: Site::new().context("无法初始化官网连接")?,
        store: Arc::new(LibraryStore::open(data_dir)?),
        sessions: Arc::new(Mutex::new(VecDeque::new())),
        readers: Arc::new(Semaphore::new(6)),
        prepares: Arc::new(Semaphore::new(4)),
    };
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
        .route("/api/catalog", get(catalog))
        .route("/api/detail/{id}", get(detail))
        .route("/api/library", get(library))
        .route("/api/favorite", post(favorite))
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
    let result = if keyword.is_empty() {
        app.site
            .discover(query.category.unwrap_or(Category::Real), page)
            .await
    } else {
        app.site.search(keyword, page).await
    };
    result.map(Json).map_err(ApiError::upstream)
}

async fn detail(
    State(app): State<App>,
    RoutePath(id): RoutePath<String>,
) -> ApiResult<impl IntoResponse> {
    if !valid_id(&id) {
        return Err(ApiError::bad_request("剧集编号无效"));
    }
    app.site
        .detail(&id)
        .await
        .map(Json)
        .map_err(ApiError::upstream)
}

async fn library(State(app): State<App>) -> ApiResult<impl IntoResponse> {
    app.store.read().map(Json).map_err(ApiError::storage)
}

#[derive(Deserialize)]
struct FavoriteChange {
    id: String,
    saved: bool,
}

async fn favorite(
    State(app): State<App>,
    Json(change): Json<FavoriteChange>,
) -> ApiResult<StatusCode> {
    if !valid_id(&change.id) {
        return Err(ApiError::bad_request("剧集编号无效"));
    }
    let series = if change.saved {
        Some(
            app.site
                .detail(&change.id)
                .await
                .map_err(ApiError::upstream)?
                .series,
        )
    } else {
        None
    };
    tokio::task::spawn_blocking(move || app.store.set_favorite(&change.id, series))
        .await
        .context("无法保存收藏")
        .map_err(ApiError::storage)?
        .map_err(ApiError::storage)?;
    Ok(StatusCode::NO_CONTENT)
}

#[derive(Deserialize)]
struct Selection {
    series: String,
    episode: String,
}

#[derive(Serialize)]
struct Playback {
    token: Uuid,
    quality: String,
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
    let detail = app
        .site
        .detail(&selection.series)
        .await
        .map_err(ApiError::upstream)?;
    let episode = detail
        .episodes
        .into_iter()
        .find(|row| row.id == selection.episode && row.available)
        .ok_or_else(|| ApiError::upstream(PreviewUnavailable.into()))?;
    let (url, referer) = app
        .site
        .public_media(&selection.series, &episode.id)
        .await
        .map_err(ApiError::upstream)?;
    let quality = url
        .path()
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .trim_end_matches(".mp4")
        .to_owned();
    let media = RemoteVideo::open(app.site.media_client.clone(), url, referer)
        .await
        .map_err(ApiError::upstream)?;
    let token = Uuid::new_v4();
    let mut sessions = app.sessions.lock().await;
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
        cancel: CancellationToken::new(),
        last_sequence: 0,
        last_used: Instant::now(),
    });
    Ok(Json(Playback { token, quality }))
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
    let (series, episode, number) = (
        session.series.clone(),
        session.episode.clone(),
        session.number,
    );
    tokio::task::spawn_blocking(move || app.store.record(&series, &episode, number, &position))
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
    media
        .serve(method, headers, cancel, permit)
        .await
        .map_err(ApiError::upstream)
}
