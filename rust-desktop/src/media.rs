// SPDX-License-Identifier: GPL-3.0-only
use std::io;

use anyhow::{Context, Result};
use axum::body::Body;
use axum::http::{HeaderMap, Method, StatusCode, header};
use axum::response::Response;
use hongguo_playback::{range::ByteRange, source::RemoteMp4};
use tokio::sync::OwnedSemaphorePermit;
use tokio_util::sync::CancellationToken;

pub(crate) async fn serve(
    media: &RemoteMp4,
    method: Method,
    headers: HeaderMap,
    cancellation: CancellationToken,
    permit: OwnedSemaphorePermit,
) -> Result<Response> {
    let total = media.size();
    let range_header = headers.get(header::RANGE);
    let range = match range_header {
        None => ByteRange {
            start: 0,
            end: total - 1,
        },
        Some(value) => match value
            .to_str()
            .ok()
            .and_then(|value| ByteRange::from_header(value, total))
        {
            Some(part) => part,
            None => {
                return Ok(Response::builder()
                    .status(StatusCode::RANGE_NOT_SATISFIABLE)
                    .header(header::CONTENT_RANGE, format!("bytes */{}", total))
                    .header(header::CACHE_CONTROL, "no-store")
                    .body(Body::empty())?);
            }
        },
    };
    let builder = Response::builder()
        .status(if range_header.is_some() {
            StatusCode::PARTIAL_CONTENT
        } else {
            StatusCode::OK
        })
        .header(header::CONTENT_TYPE, "video/mp4")
        .header(header::CONTENT_LENGTH, range.length().to_string())
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CACHE_CONTROL, "no-store");
    let builder = if range_header.is_some() {
        builder.header(header::CONTENT_RANGE, range.content_range(total))
    } else {
        builder
    };
    if method == Method::HEAD {
        return Ok(builder.body(Body::empty())?);
    }

    let mut response = cancellation
        .run_until_cancelled(media.read_range(range))
        .await
        .context("播放会话已关闭")??;
    let body = Body::from_stream(async_stream::stream! {
        let _permit = permit;
        let mut remaining = range.length();
        while remaining > 0 {
            let result = tokio::select! {
                _ = cancellation.cancelled() => break,
                result = response.chunk() => result
            };
            match result {
                Ok(Some(chunk)) if chunk.len() as u64 <= remaining => {
                    remaining -= chunk.len() as u64;
                    yield Ok::<_, io::Error>(chunk);
                }
                _ => {
                    yield Err(io::Error::other("视频流读取中断"));
                    break;
                }
            }
        }
    });
    Ok(builder.body(body)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use hongguo_playback::source::{PlaybackScope, SelectedSource};
    use reqwest::{Client, Url};

    #[tokio::test]
    async fn relays_ranges_and_head_through_the_same_player_entry_point() {
        let mut fixture = [0; 64];
        fixture[4..8].copy_from_slice(b"ftyp");
        fixture[60..].copy_from_slice(b"tail");
        let source = axum::Router::new().route(
            "/clip",
            axum::routing::get(move |headers: HeaderMap| async move {
                let raw = headers[header::RANGE].to_str().unwrap();
                let part = ByteRange::from_header(raw, fixture.len() as u64).unwrap();
                let bytes = fixture[part.start as usize..=part.end as usize].to_vec();
                Response::builder()
                    .status(StatusCode::PARTIAL_CONTENT)
                    .header(header::CONTENT_TYPE, "video/mp4")
                    .header(
                        header::CONTENT_RANGE,
                        part.content_range(fixture.len() as u64),
                    )
                    .body(Body::from(bytes))
                    .unwrap()
            }),
        );
        let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
            .await
            .unwrap();
        let url = Url::parse(&format!("http://{}/clip", listener.local_addr().unwrap())).unwrap();
        let task = tokio::spawn(async move {
            axum::serve(listener, source).await.unwrap();
        });
        let media = RemoteMp4::open(Client::new(), url, None).await.unwrap();
        let readers = std::sync::Arc::new(tokio::sync::Semaphore::new(1));
        let mut headers = HeaderMap::new();
        headers.insert(header::RANGE, "bytes=-4".parse().unwrap());
        let response = serve(
            &media,
            Method::GET,
            headers,
            CancellationToken::new(),
            readers.clone().acquire_owned().await.unwrap(),
        )
        .await
        .unwrap();
        assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(response.headers()[header::CONTENT_RANGE], "bytes 60-63/64");
        assert_eq!(
            axum::body::to_bytes(response.into_body(), 64)
                .await
                .unwrap(),
            b"tail"[..]
        );
        let response = serve(
            &media,
            Method::HEAD,
            HeaderMap::new(),
            CancellationToken::new(),
            readers.acquire_owned().await.unwrap(),
        )
        .await
        .unwrap();
        assert_eq!(response.headers()[header::CONTENT_LENGTH], "64");
        assert!(
            axum::body::to_bytes(response.into_body(), 64)
                .await
                .unwrap()
                .is_empty()
        );
        task.abort();
    }

    #[tokio::test]
    async fn opens_authorized_backup_and_keeps_all_reads_on_that_file() {
        use std::sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        };
        let attempts = Arc::new(AtomicUsize::new(0));
        let observed = attempts.clone();
        let mut fixture = [0; 64];
        fixture[4..8].copy_from_slice(b"ftyp");
        fixture[60..].copy_from_slice(b"tail");
        let router = axum::Router::new()
            .route(
                "/primary",
                axum::routing::get(move || {
                    observed.fetch_add(1, Ordering::SeqCst);
                    async { StatusCode::SERVICE_UNAVAILABLE }
                }),
            )
            .route(
                "/backup",
                axum::routing::get(move |headers: HeaderMap| async move {
                    let part = ByteRange::from_header(headers[header::RANGE].to_str().unwrap(), 64)
                        .unwrap();
                    Response::builder()
                        .status(StatusCode::PARTIAL_CONTENT)
                        .header(header::CONTENT_TYPE, "video/mp4")
                        .header(header::CONTENT_RANGE, part.content_range(64))
                        .body(Body::from(
                            fixture[part.start as usize..=part.end as usize].to_vec(),
                        ))
                        .unwrap()
                }),
            );
        let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, 0))
            .await
            .unwrap();
        let base = Url::parse(&format!("http://{}", listener.local_addr().unwrap())).unwrap();
        let task = tokio::spawn(async move {
            axum::serve(listener, router).await.unwrap();
        });
        let mut source = SelectedSource {
            url: base.join("primary").unwrap(),
            backup_url: Some(base.join("backup").unwrap()),
            referer: None,
            quality: 720,
            qualities: vec![720],
            scope: PlaybackScope::Full,
        };
        let media = RemoteMp4::open_source(Client::new(), &source)
            .await
            .unwrap();
        assert_eq!(media.size(), 64);
        assert_eq!(
            media
                .read_range(ByteRange { start: 60, end: 63 })
                .await
                .unwrap()
                .bytes()
                .await
                .unwrap(),
            b"tail"[..]
        );
        assert_eq!(attempts.load(Ordering::SeqCst), 1);
        source.backup_url = Some(base.join("missing").unwrap());
        let error = RemoteMp4::open_source(Client::new(), &source)
            .await
            .err()
            .unwrap();
        assert_eq!(error.to_string(), "主源和备用源均无法播放");
        source.url = base.join("backup").unwrap();
        assert!(RemoteMp4::open_source(Client::new(), &source).await.is_ok());
        source.url = base.join("primary").unwrap();
        source.backup_url = None;
        assert!(
            RemoteMp4::open_source(Client::new(), &source)
                .await
                .is_err()
        );
        task.abort();
    }
}
