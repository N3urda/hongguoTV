// SPDX-License-Identifier: GPL-3.0-only
use anyhow::{Result, ensure};
use reqwest::{Client, Url};
use semver::Version;
use serde::{Deserialize, Serialize};

use crate::catalog::read_limited;

pub const PROJECT: &str = "https://github.com/N3urda/hongguoTV";
const RELEASES: &str = "https://api.github.com/repos/N3urda/hongguoTV/releases?per_page=30";
const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    draft: bool,
    prerelease: bool,
    assets: Vec<Asset>,
}

#[derive(Deserialize)]
struct Asset {
    name: String,
    state: String,
}

#[derive(Serialize)]
pub(crate) struct Update {
    version: String,
    page: String,
}

pub(crate) async fn check(client: &Client) -> Result<Option<Update>> {
    let response = client
        .get(RELEASES)
        .header(reqwest::header::ACCEPT, "application/vnd.github+json")
        .send()
        .await
        .map_err(|_| anyhow::anyhow!("暂时无法访问更新服务器"))?;
    ensure!(
        response.status().is_success(),
        "暂时无法读取新版本，请稍后再试"
    );
    let bytes = read_limited(response, 3 * 1024 * 1024).await?;
    let releases: Vec<Release> = serde_json::from_slice(&bytes)?;
    Ok(newest(
        releases,
        &Version::parse(VERSION)?,
        platform_assets(),
    ))
}

fn platform_assets() -> &'static [&'static str] {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => &["windows-x64-setup.exe"],
        ("macos", "aarch64") => &["macos-arm64.zip", "macos-universal.zip"],
        ("macos", "x86_64") => &["macos-x64.zip", "macos-universal.zip"],
        _ => &[],
    }
}

fn newest(releases: Vec<Release>, current: &Version, suffixes: &[&str]) -> Option<Update> {
    let mut recent = None;
    for release in releases {
        if release.draft || (release.prerelease && current.pre.is_empty()) {
            continue;
        }
        let Some(version) = release
            .tag_name
            .strip_prefix("rust-v")
            .and_then(|tag| Version::parse(tag).ok())
        else {
            continue;
        };
        if &version <= current || recent.as_ref().is_some_and(|(prior, _)| &version <= prior) {
            continue;
        }
        if release.assets.iter().any(|asset| {
            asset.state == "uploaded"
                && suffixes
                    .iter()
                    .any(|suffix| asset.name == format!("Hongguo-Rust-{version}-{suffix}"))
        }) {
            recent = Some((version, release.tag_name));
        }
    }
    recent.map(|(version, tag)| Update {
        version: version.to_string(),
        page: format!("{PROJECT}/releases/tag/{tag}"),
    })
}

pub fn is_project_url(raw: &str) -> bool {
    if raw == PROJECT {
        return true;
    }
    let prefix = format!("{PROJECT}/releases/tag/rust-v");
    raw.strip_prefix(&prefix)
        .is_some_and(|tag| Version::parse(tag).is_ok())
        && Url::parse(raw).is_ok_and(|url| url.query().is_none() && url.fragment().is_none())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn release(tag: &str, asset: &str, prerelease: bool) -> Release {
        Release {
            tag_name: tag.to_owned(),
            draft: false,
            prerelease,
            assets: vec![Asset {
                name: asset.to_owned(),
                state: "uploaded".to_owned(),
            }],
        }
    }

    #[test]
    fn only_offers_newer_published_installers_on_the_same_channel_and_platform() {
        let current = Version::parse("0.4.0").unwrap();
        let releases = vec![
            release("kotlin-v0.16.12", "update.json", false),
            release(
                "rust-v0.6.0-beta.1",
                "Hongguo-Rust-0.6.0-beta.1-windows-x64-setup.exe",
                true,
            ),
            release("rust-v0.5.1", "Hongguo-Rust-0.5.1-macos-arm64.zip", false),
            release(
                "rust-v0.5.0",
                "Hongguo-Rust-0.5.0-windows-x64-setup.exe",
                false,
            ),
        ];
        let update = newest(releases, &current, &["windows-x64-setup.exe"]).unwrap();
        assert_eq!(update.version, "0.5.0");
        assert!(is_project_url(&update.page));
        assert!(!is_project_url(
            "https://github.com/N3urda/hongguoTV/releases/tag/rust-v0.5.0?x=1"
        ));
    }
}
