// SPDX-License-Identifier: GPL-3.0-only
use super::*;
use hongguo_playback::social::{CloudFavorite, CloudProgress};

const CACHE_TTL: Duration = Duration::from_secs(30);

type Snapshot<T> = Option<(Uuid, Instant, Vec<T>)>;

#[derive(Default)]
pub(super) struct CloudLibrary {
    history: Mutex<Snapshot<CloudProgress>>,
    history_read: Mutex<()>,
    favorites: Mutex<Snapshot<CloudFavorite>>,
    mutations: Mutex<(Uuid, HashMap<String, bool>)>,
    uploads: Mutex<Uploads>,
}

#[derive(Default)]
struct Uploads {
    running: bool,
    pending: VecDeque<(Uuid, CloudProgress)>,
    failure: Option<(Uuid, String)>,
}

#[derive(Deserialize)]
pub(super) struct CloudQuery {
    #[serde(default)]
    fresh: bool,
}

#[derive(Serialize)]
pub(super) struct CloudRows<T> {
    revision: Uuid,
    rows: Vec<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    notice: Option<String>,
}

#[derive(Deserialize)]
pub(super) struct FavoriteSelection {
    id: String,
    saved: bool,
    revision: Uuid,
}

#[derive(Serialize)]
pub(super) struct FavoriteResult {
    confirmed: bool,
}

fn matching<T>(value: &Snapshot<T>, revision: Uuid, fresh: bool) -> Option<&Vec<T>> {
    let (scope, at, rows) = value.as_ref()?;
    (*scope == revision && (!fresh || at.elapsed() < CACHE_TTL)).then_some(rows)
}

async fn ensure_current(app: &App, revision: Uuid) -> ApiResult<()> {
    if app.account.authorization().await.0 != revision {
        return Err(ApiError(
            StatusCode::CONFLICT,
            "账户已切换，请重新读取片库".to_owned(),
        ));
    }
    Ok(())
}

pub(super) async fn cloud_history(
    State(app): State<App>,
    Query(query): Query<CloudQuery>,
) -> ApiResult<Json<CloudRows<CloudProgress>>> {
    let (revision, cookie) = app.account.authorization().await;
    let cookie = cookie.ok_or_else(sign_in_required)?;
    let _one_request = app.cloud.history_read.lock().await;
    let cached = matching(&*app.cloud.history.lock().await, revision, true).cloned();
    let rows = if !query.fresh
        && let Some(rows) = cached
    {
        rows
    } else {
        ensure_current(&app, revision).await?;
        let rows = app
            .social
            .cloud_history(&cookie)
            .await
            .map_err(ApiError::upstream)?;
        ensure_current(&app, revision).await?;
        *app.cloud.history.lock().await = Some((revision, Instant::now(), rows.clone()));
        rows
    };
    ensure_current(&app, revision).await?;
    let notice = app
        .cloud
        .uploads
        .lock()
        .await
        .failure
        .as_ref()
        .filter(|(key, _)| *key == revision)
        .map(|(_, notice)| notice.clone());
    Ok(Json(CloudRows {
        revision,
        rows,
        notice,
    }))
}

pub(super) async fn cloud_favorites(
    State(app): State<App>,
    Query(query): Query<CloudQuery>,
) -> ApiResult<Json<CloudRows<CloudFavorite>>> {
    let (revision, cookie) = app.account.authorization().await;
    let cookie = cookie.ok_or_else(sign_in_required)?;
    let mut cached = app.cloud.favorites.lock().await;
    let rows = if !query.fresh
        && let Some(rows) = matching(&cached, revision, true)
    {
        rows.clone()
    } else {
        ensure_current(&app, revision).await?;
        let rows = app
            .social
            .cloud_favorites(&cookie)
            .await
            .map_err(ApiError::upstream)?;
        ensure_current(&app, revision).await?;
        *cached = Some((revision, Instant::now(), rows.clone()));
        rows
    };
    ensure_current(&app, revision).await?;
    Ok(Json(CloudRows {
        revision,
        rows,
        notice: None,
    }))
}

pub(super) async fn cloud_favorite(
    State(app): State<App>,
    Json(choice): Json<FavoriteSelection>,
) -> ApiResult<Json<FavoriteResult>> {
    if !valid_id(&choice.id) {
        return Err(ApiError::bad_request("剧集编号无效"));
    }
    let mut writes = app.cloud.mutations.lock().await;
    let (revision, cookie) = app.account.authorization().await;
    let cookie = cookie.ok_or_else(sign_in_required)?;
    if revision != choice.revision {
        return Err(ApiError(
            StatusCode::CONFLICT,
            "账户已切换，请重新读取片库".to_owned(),
        ));
    }
    if writes.0 != revision {
        *writes = (revision, HashMap::new());
    }
    let mut shelf = app.cloud.favorites.lock().await;
    let rows = app
        .social
        .cloud_favorites(&cookie)
        .await
        .map_err(ApiError::upstream)?;
    ensure_current(&app, revision).await?;
    let observed = rows.iter().any(|row| row.series.id == choice.id);
    *shelf = Some((revision, Instant::now(), rows));
    if let Some(last) = writes.1.get(&choice.id) {
        // Never submit a reversal while the previous outcome is unclear.
        if observed != *last {
            return Ok(Json(FavoriteResult { confirmed: false }));
        }
        writes.1.remove(&choice.id);
    }
    if observed == choice.saved {
        return Ok(Json(FavoriteResult { confirmed: true }));
    }
    let request = app
        .social
        .prepare_cloud_favorite(&cookie, &choice.id, choice.saved)
        .await
        .map_err(ApiError::upstream)?;
    ensure_current(&app, revision).await?;
    writes.1.insert(choice.id.clone(), choice.saved);
    if let Err(error) = request.submit().await
        && error.is::<ProviderRejection>()
    {
        writes.1.remove(&choice.id);
        return Err(ApiError::upstream(error));
    }
    for attempt in 0..2 {
        if attempt == 1 {
            tokio::time::sleep(Duration::from_millis(750)).await;
        }
        ensure_current(&app, revision).await?;
        if let Ok(rows) = app.social.cloud_favorites(&cookie).await {
            ensure_current(&app, revision).await?;
            let confirmed = rows.iter().any(|row| row.series.id == choice.id) == choice.saved;
            *shelf = Some((revision, Instant::now(), rows));
            if confirmed {
                writes.1.remove(&choice.id);
                return Ok(Json(FavoriteResult { confirmed }));
            }
        }
    }
    ensure_current(&app, revision).await?;
    Ok(Json(FavoriteResult { confirmed: false }))
}

pub(super) async fn cached_resume(app: &App, id: &str) -> Option<CloudProgress> {
    let (revision, cookie) = app.account.authorization().await;
    cookie?;
    let cache = app.cloud.history.lock().await;
    matching(&cache, revision, false)?
        .iter()
        .find(|item| item.series.id == id)
        .cloned()
}

pub(super) async fn queue_progress(app: App, revision: Uuid, row: CloudProgress) {
    let (current, cookie) = app.account.authorization().await;
    if current != revision || cookie.is_none() {
        return;
    }
    let mut uploads = app.cloud.uploads.lock().await;
    uploads
        .pending
        .retain(|(key, previous)| *key != revision || previous.series.id != row.series.id);
    uploads.pending.push_back((revision, row));
    if uploads.pending.len() > 32 {
        uploads.pending.pop_front();
    }
    if uploads.running {
        return;
    }
    uploads.running = true;
    drop(uploads);
    tokio::spawn(drain_progress(app));
}

async fn drain_progress(app: App) {
    loop {
        let (revision, row) = {
            let mut uploads = app.cloud.uploads.lock().await;
            let Some(job) = uploads.pending.pop_front() else {
                uploads.running = false;
                return;
            };
            job
        };
        let (current, cookie) = app.account.authorization().await;
        let Some(cookie) = cookie.filter(|_| revision == current) else {
            continue;
        };
        if !app
            .store
            .preferences()
            .is_ok_and(|prefs| prefs.cloud_progress)
        {
            continue;
        }
        let result = async {
            let request = app.social.prepare_cloud_progress(&cookie, &row).await?;
            anyhow::ensure!(
                revision == app.account.authorization().await.0,
                "账户已切换"
            );
            anyhow::ensure!(
                app.store.preferences()?.cloud_progress,
                "观看进度同步已关闭"
            );
            request.submit().await
        }
        .await;
        if revision != app.account.authorization().await.0 {
            continue;
        }
        let mut uploads = app.cloud.uploads.lock().await;
        uploads.failure = result.err().map(|_| {
            (
                revision,
                "最近的进度未同步到红果账户，本机进度已保存".to_owned(),
            )
        });
        let success = uploads.failure.is_none();
        drop(uploads);
        if success {
            let mut cached = app.cloud.history.lock().await;
            if let Some((key, _, rows)) = cached.as_mut()
                && *key == revision
            {
                rows.retain(|item| item.series.id != row.series.id);
                rows.insert(0, row);
                rows.sort_by_key(|item| std::cmp::Reverse(item.updated_at));
                rows.truncate(50);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_snapshot_cannot_be_read_under_another_account_or_reused_as_fresh() {
        let current = Uuid::new_v4();
        let snapshot = Some((current, Instant::now() - CACHE_TTL, vec!["account record"]));
        assert!(matching(&snapshot, Uuid::new_v4(), false).is_none());
        assert!(matching(&snapshot, current, true).is_none());
        assert_eq!(
            matching(&snapshot, current, false).unwrap(),
            &["account record"]
        );
    }
}
