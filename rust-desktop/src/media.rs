// SPDX-License-Identifier: GPL-3.0-only
use std::io;

use anyhow::{Context, Result, ensure};
use axum::body::Body;
use axum::http::{HeaderMap, Method, StatusCode, header};
use axum::response::Response;
use hongguo_playback::range::{ByteRange, validate_response};
use reqwest::{Client, Url};
use tokio::sync::OwnedSemaphorePermit;
use tokio_util::sync::CancellationToken;

use crate::catalog::read_limited;

const MAX_MEDIA_BYTES: u64 = 2 * 1024 * 1024 * 1024;

pub struct RemoteVideo {
    client: Client,
    url: Url,
    referer: Url,
    total: u64,
}

impl RemoteVideo {
    pub async fn open(client: Client, url: Url, referer: Url) -> Result<Self> {
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
            "官网未返回可播放的 MP4"
        );
        media.total = total;
        Ok(media)
    }

    async fn request(&self, part: ByteRange) -> Result<(reqwest::Response, u64)> {
        let response = self
            .client
            .get(self.url.clone())
            .header(header::REFERER, self.referer.as_str())
            .header(header::RANGE, part.header())
            .header(header::ACCEPT_ENCODING, "identity")
            .send()
            .await
            .map_err(|_| anyhow::anyhow!("获取视频失败，请检查网络后重试"))?;
        ensure!(
            response.status() == StatusCode::PARTIAL_CONTENT,
            "官网视频请求失败（HTTP {}）",
            response.status().as_u16()
        );
        let content_range = response
            .headers()
            .get(header::CONTENT_RANGE)
            .and_then(|value| value.to_str().ok())
            .unwrap_or_default();
        let expected_total = (self.total > 0).then_some(self.total);
        let (returned, total) = validate_response(content_range, part, expected_total)
            .map_err(|_| anyhow::anyhow!("官网视频分段信息异常"))?;
        let mime = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|value| value.to_str().ok());
        ensure!(
            returned == part
                && total <= MAX_MEDIA_BYTES
                && mime.is_some_and(|value| value.split(';').next() == Some("video/mp4"))
                && response
                    .content_length()
                    .is_none_or(|length| length == part.length()),
            "官网视频分段信息异常"
        );
        Ok((response, total))
    }

    pub async fn serve(
        &self,
        method: Method,
        headers: HeaderMap,
        cancellation: CancellationToken,
        permit: OwnedSemaphorePermit,
    ) -> Result<Response> {
        let range_header = headers.get(header::RANGE);
        let range = match range_header {
            None => ByteRange {
                start: 0,
                end: self.total - 1,
            },
            Some(value) => match value
                .to_str()
                .ok()
                .and_then(|value| ByteRange::from_header(value, self.total))
            {
                Some(part) => part,
                None => {
                    return Ok(Response::builder()
                        .status(StatusCode::RANGE_NOT_SATISFIABLE)
                        .header(header::CONTENT_RANGE, format!("bytes */{}", self.total))
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
            builder.header(header::CONTENT_RANGE, range.content_range(self.total))
        } else {
            builder
        };
        if method == Method::HEAD {
            return Ok(builder.body(Body::empty())?);
        }

        let (mut response, _) = cancellation
            .run_until_cancelled(self.request(range))
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
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let media = RemoteVideo::open(Client::new(), url.clone(), url)
            .await
            .unwrap();
        let readers = std::sync::Arc::new(tokio::sync::Semaphore::new(1));
        let mut headers = HeaderMap::new();
        headers.insert(header::RANGE, "bytes=-4".parse().unwrap());
        let response = media
            .serve(
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
        let response = media
            .serve(
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
}
