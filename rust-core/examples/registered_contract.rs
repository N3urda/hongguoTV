// SPDX-License-Identifier: GPL-3.0-only
use std::{collections::BTreeMap, sync::Arc, time::Instant};

use anyhow::{Context, Result, ensure};
use hongguo_playback::{
    registered::RegisteredSource,
    social::Social,
    web::{Site, valid_id},
};

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = std::env::args().skip(1);
    let series = args.next().context("请指定剧集编号")?;
    let episode = args.next().context("请指定分集编号")?;
    let duration = args
        .next()
        .context("请指定真实分集时长（毫秒）")?
        .parse::<u64>()
        .ok()
        .filter(|duration| (1..=86_400_000).contains(duration))
        .context("分集时长无效")?;
    ensure!(args.next().is_none(), "只接受剧集、分集和时长三个参数");
    ensure!(
        valid_id(&series) && valid_id(&episode),
        "剧集或分集编号无效"
    );

    let detail = Site::new()?.detail(&series).await.context("核验剧集")?;
    ensure!(
        detail.episodes.iter().any(|entry| entry.id == episode),
        "分集与剧集不匹配"
    );
    let source = Arc::new(RegisteredSource::new()?);
    let social = Social::new(source.clone())?;
    let start = Instant::now();
    let model = source.ladder(&episode).await.context("注册源播放模型")?;
    ensure!(model.select(1080, None).is_some(), "播放模型缺少兼容清晰度");
    println!(
        "播放模型通过（含匿名身份初始化，{} ms）",
        start.elapsed().as_millis()
    );

    let filters = BTreeMap::new();
    let page = social
        .discover(&filters, None)
        .await
        .context("多条件找剧")?;
    ensure!(!page.items.is_empty(), "多条件找剧未返回有效剧集");
    println!("多条件找剧通过");
    if let Some(cursor) = &page.next {
        social
            .discover(&filters, Some(cursor))
            .await
            .context("多条件找剧续页")?;
        println!("多条件找剧续页通过");
    }

    social.related(&series).await.context("相关作品")?;
    println!("相关作品通过");

    let page = social
        .danmaku(&series, &episode, 0, duration, None)
        .await
        .context("弹幕首段")?;
    println!("弹幕首段通过");
    if let Some(cursor) = &page.next_cursor {
        social
            .danmaku(&series, &episode, page.next_ms, duration, Some(cursor))
            .await
            .context("弹幕下一段")?;
        println!("弹幕下一段通过");
    }
    Ok(())
}
