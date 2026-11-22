// SPDX-License-Identifier: GPL-3.0-only
//! Catalog request and response rules with no network or platform dependencies.
use serde::{Deserialize, Serialize};

#[cfg(any(feature = "registered", feature = "android-jni"))]
pub(crate) mod danmaku;
#[cfg(any(feature = "registered", feature = "android-jni"))]
pub(crate) mod discovery;
#[cfg(any(feature = "registered", feature = "android-jni"))]
mod response_fields;
#[cfg(any(feature = "registered", feature = "android-jni"))]
pub(crate) use response_fields::*;

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Series {
    pub id: String,
    pub title: String,
    pub cover: String,
    pub description: String,
    pub badge: String,
    pub tags: Vec<String>,
}
