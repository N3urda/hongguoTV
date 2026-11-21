// SPDX-License-Identifier: GPL-3.0-only
use jni::{
    JNIEnv,
    objects::{JIntArray, JObject, JString},
    sys::{jboolean, jint, jlong, jlongArray},
};

use crate::range::{ByteRange, RangeError, validate_response};
use crate::{Rendition, resume_target, select_rendition};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeResume(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    count: jint,
    matched: jint,
    position: jlong,
    completed: jboolean,
) -> jlongArray {
    let Some(target) = resume_target(
        count.max(0) as usize,
        usize::try_from(matched).ok(),
        position,
        completed != 0,
    ) else {
        return std::ptr::null_mut();
    };
    let Ok(result) = env.new_long_array(2) else {
        return std::ptr::null_mut();
    };
    if env
        .set_long_array_region(&result, 0, &[target.index as jlong, target.position])
        .is_err()
    {
        return std::ptr::null_mut();
    }
    result.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeSelect(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    packed: JIntArray<'_>,
    maximum: jint,
) -> jint {
    let Ok(length) = env.get_array_length(&packed) else {
        return -1;
    };
    if length % 2 != 0 || !(0..=4096).contains(&length) {
        return -1;
    }
    let mut values = vec![0; length as usize];
    if env.get_int_array_region(&packed, 0, &mut values).is_err() {
        return -1;
    }
    let renditions: Vec<_> = values
        .chunks_exact(2)
        .map(|entry| Rendition {
            quality: entry[0],
            avc: entry[1] & 1 != 0,
            supported: entry[1] & 2 != 0,
        })
        .collect();
    select_rendition(&renditions, maximum).map_or(-1, |index| index as jint)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_hongguotv_core_RustPlaybackCore_nativeRangeTotal(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    header: JString<'_>,
    start: jlong,
    end: jlong,
    expected: jlong,
) -> jlong {
    if start < 0 || end < start || expected < 0 {
        return -1;
    }
    let Ok(header) = env.get_string(&header) else {
        return -1;
    };
    let header: String = header.into();
    let range = ByteRange {
        start: start as u64,
        end: end as u64,
    };
    match validate_response(&header, range, (expected > 0).then_some(expected as u64)) {
        Ok((_, size)) => jlong::try_from(size).unwrap_or(-1),
        Err(RangeError::Invalid) => -1,
        Err(RangeError::Mismatch) => -2,
        Err(RangeError::Changed) => -3,
    }
}
