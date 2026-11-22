// SPDX-License-Identifier: GPL-3.0-only
// Opt-in live probe; prints no device IDs, keys or access-bearing media URLs.
#[cfg(feature = "registered")]
#[tokio::main]
async fn main() -> anyhow::Result<()> {
    use std::time::Instant;

    use hongguo_playback::{
        range::ByteRange,
        registered::RegisteredSource,
        source::{RemoteMp4, authorized_media_client, read_limited},
    };
    let episode = std::env::args()
        .nth(1)
        .ok_or_else(|| anyhow::anyhow!("用法: registered_probe <分集编号>"))?;
    let begin = Instant::now();
    let ladder = RegisteredSource::new()?.ladder(&episode).await?;
    let source = ladder
        .select(1080, None)
        .ok_or_else(|| anyhow::anyhow!("没有清晰度"))?;
    println!(
        "model: {} ms, quality={}, tiers={:?}, encrypted={}",
        begin.elapsed().as_millis(),
        source.quality,
        source.qualities,
        source.key.is_some()
    );
    let opening = Instant::now();
    let media = RemoteMp4::open_source(authorized_media_client()?, &source).await?;
    println!("media open: {} ms", opening.elapsed().as_millis());
    // Optional diagnostic sample for ffmpeg/ffprobe, capped at 2 MiB.
    let output = std::env::args().nth(2);
    let limit = if output.is_some() {
        2 * 1024 * 1024
    } else {
        65536
    };
    let part = ByteRange {
        start: 0,
        end: (limit as u64 - 1).min(media.size() - 1),
    };
    let reading = Instant::now();
    let bytes = read_limited(media.read_range(part).await?, limit).await?;
    let clear = media.decode_chunk(0, &bytes)?;
    anyhow::ensure!(clear.get(4..8) == Some(b"ftyp"), "MP4 header missing");
    println!(
        "media range: read and decoded {} bytes in {} ms",
        clear.len(),
        reading.elapsed().as_millis()
    );
    if let Some(output) = output {
        use std::io::Write;
        std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output)?
            .write_all(&clear)?;
    }
    Ok(())
}
#[cfg(not(feature = "registered"))]
fn main() {
    eprintln!("需要 --features registered");
}
