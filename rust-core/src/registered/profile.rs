// SPDX-License-Identifier: GPL-3.0-only
use std::{sync::Arc, time::Duration};

use reqwest::header::HeaderValue;
use serde::{Deserialize, Serialize};
use tokio::{sync::watch, task::spawn_blocking, time::timeout};
use uuid::Uuid;

use super::{Device, is_anonymous_cookie, valid_device_id};

const PROFILE_VERSION: u8 = 1;
const PROFILE_LIMIT: usize = 16 * 1024;

/// Optional persistence supplied by the application. The opaque bytes include anonymous
/// cookies and must be stored privately, scoped to one installation. Save must replace
/// the previous profile atomically. Methods are called on Tokio's blocking pool.
pub trait DeviceProfileStore: Send + Sync {
    fn load(&self) -> anyhow::Result<Option<Vec<u8>>>;
    fn save(&self, profile: &[u8]) -> anyhow::Result<()>;
}

#[derive(Default)]
pub(super) enum ProfileStorage {
    #[default]
    Disabled,
    Unread(Arc<dyn DeviceProfileStore>),
    Ready(watch::Sender<Option<Arc<Device>>>),
}

impl ProfileStorage {
    pub(super) async fn restore(&mut self) -> Option<Arc<Device>> {
        let Self::Unread(store) = self else {
            return None;
        };
        let reader = store.clone();
        let result = timeout(
            Duration::from_secs(1),
            spawn_blocking(move || reader.load()?.map(decode).transpose()),
        )
        .await;
        // If a stored profile cannot be read, keep it intact and use this session in memory.
        let Ok(Ok(Ok(device))) = result else {
            *self = Self::Disabled;
            return None;
        };
        let store = store.clone();
        let (writer, mut updates) = watch::channel::<Option<Arc<Device>>>(None);
        tokio::spawn(async move {
            while updates.changed().await.is_ok() {
                let latest = updates.borrow_and_update().clone();
                if let Some(device) = latest {
                    let store = store.clone();
                    // Commit one snapshot at a time. After a slow write use the newest identity.
                    let _ = spawn_blocking(move || store.save(&encode(&device)?)).await;
                }
            }
        });
        *self = Self::Ready(writer);
        device.map(Arc::new)
    }

    pub(super) fn save(&self, device: &Arc<Device>) {
        if let Self::Ready(writer) = self {
            writer.send_replace(Some(device.clone()));
        }
    }
}

#[derive(Deserialize, Serialize)]
struct StoredProfile {
    version: u8,
    device_id: String,
    install_id: String,
    cdid: String,
    cookie: Option<String>,
}

fn encode(device: &Device) -> anyhow::Result<Vec<u8>> {
    let profile = StoredProfile {
        version: PROFILE_VERSION,
        device_id: device.device_id.clone(),
        install_id: device.install_id.clone(),
        cdid: device.cdid.clone(),
        cookie: device
            .cookie
            .as_ref()
            .and_then(|cookie| cookie.to_str().ok())
            .map(str::to_owned),
    };
    let bytes = serde_json::to_vec(&profile)?;
    anyhow::ensure!(bytes.len() <= PROFILE_LIMIT, "匿名设备档案超过大小限制");
    Ok(bytes)
}

fn decode(bytes: Vec<u8>) -> anyhow::Result<Device> {
    anyhow::ensure!(bytes.len() <= PROFILE_LIMIT, "匿名设备档案超过大小限制");
    let stored: StoredProfile =
        serde_json::from_slice(&bytes).map_err(|_| anyhow::anyhow!("匿名设备档案格式无效"))?;
    anyhow::ensure!(
        stored.version == PROFILE_VERSION
            && valid_device_id(&stored.device_id)
            && valid_device_id(&stored.install_id)
            && Uuid::parse_str(&stored.cdid).is_ok_and(|id| !id.is_nil()),
        "匿名设备档案格式无效"
    );
    let cookie = stored
        .cookie
        .map(|value| {
            anyhow::ensure!(
                value.split("; ").all(|pair| {
                    pair.split_once('=').is_some_and(|(name, value)| {
                        is_anonymous_cookie(name) && !value.contains(';')
                    })
                }),
                "匿名设备 Cookie 格式无效"
            );
            let mut header = HeaderValue::from_str(&value)?;
            header.set_sensitive(true);
            Ok::<_, anyhow::Error>(header)
        })
        .transpose()?;
    Ok(Device {
        device_id: stored.device_id,
        install_id: stored.install_id,
        cdid: stored.cdid,
        cookie,
    })
}
