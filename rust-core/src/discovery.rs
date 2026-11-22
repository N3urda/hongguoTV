// SPDX-License-Identifier: GPL-3.0-only
//! Multi-condition browse protocol shared with DiscoveryFilters.kt on main.
use super::*;

use crate::catalog_rules::discovery::{DiscoveryPosition, parse_discovery, selection_body};

pub use crate::catalog_rules::discovery::{DiscoveryGroup, valid_discovery_filters};

#[derive(Clone)]
pub struct DiscoveryCursor {
    position: Arc<DiscoveryPosition>,
    device: Arc<Device>,
    filters: BTreeMap<String, String>,
}

impl DiscoveryCursor {
    pub fn matches_filters(&self, filters: &BTreeMap<String, String>) -> bool {
        self.filters == *filters
    }
}

pub struct DiscoveryPage {
    pub items: Vec<Series>,
    pub groups: Vec<DiscoveryGroup>,
    pub next: Option<DiscoveryCursor>,
}

impl Social {
    pub async fn discover(
        &self,
        filters: &BTreeMap<String, String>,
        cursor: Option<&DiscoveryCursor>,
    ) -> Result<DiscoveryPage> {
        ensure!(valid_discovery_filters(filters), "筛选条件无效");
        ensure!(
            cursor.is_none_or(|cursor| cursor.matches_filters(filters)),
            "筛选条件已变化，请从第一页开始"
        );
        let device = match cursor {
            Some(cursor) => cursor.device.clone(),
            None => self.registered.identity().await?,
        };
        let body = serde_json::to_vec(&selection_body(
            filters,
            cursor.map(|row| row.position.as_ref()),
        ))?;
        let request = self.signed(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/distribution/category/landpage/v/",
            profile(&device, "73532", "7.3.5.32"),
            Some(body),
            "71332",
            false,
        )?;
        let page = parse_discovery(
            &response(request, "分类找剧").await?,
            cursor.map(|row| row.position.as_ref()),
        )?;
        Ok(DiscoveryPage {
            items: page.items,
            groups: page.groups,
            next: page.next.map(|position| DiscoveryCursor {
                position: Arc::new(position),
                filters: filters.clone(),
                device,
            }),
        })
    }
}
