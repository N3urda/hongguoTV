// SPDX-License-Identifier: GPL-3.0-only
use anyhow::{Context, Result, ensure};
use hongguo_playback::web::{Category, RankBoard, Site};

#[tokio::main]
async fn main() -> Result<()> {
    let site = Site::new()?;
    let query = std::env::args()
        .nth(1)
        .filter(|value| !value.trim().is_empty());
    let category = site.discover(Category::Real, 1).await.context("找剧")?;
    let seed = category.items.first().context("找剧未返回可用剧集")?;
    let topic = category.topics.first().context("找剧未返回可用题材")?;
    println!("找剧和题材列表通过");

    let filtered = site
        .discover_topic(Category::Real, topic.id(), 1)
        .await
        .context("找剧题材筛选")?;
    ensure!(!filtered.items.is_empty(), "题材筛选未返回可用剧集");
    println!("题材筛选通过");

    let results = site
        .search(query.as_deref().unwrap_or(&seed.title), 1)
        .await
        .context("搜索")?;
    let first = results.items.first().context("搜索未返回可用剧集")?;
    println!("搜索通过");

    let detail = site.detail(&first.id).await.context("剧集详情")?;
    ensure!(!detail.episodes.is_empty(), "详情未返回可用分集");
    println!("剧集详情和分集列表通过");

    let prefix: String = seed.title.chars().take(8).collect();
    site.suggestions(&prefix).await.context("搜索联想")?;
    println!("搜索联想通过");

    let rank = site.rank(RankBoard::All, 1).await.context("热播榜")?;
    ensure!(!rank.items().is_empty(), "热播榜未返回有效条目");
    println!("热播榜通过");

    let episode = detail.episodes.first().context("详情缺少分集")?;
    site.episode_comments(&first.id, &episode.id)
        .await
        .context("公开分集评论")?;
    println!("公开分集评论通过");
    Ok(())
}
