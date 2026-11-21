// SPDX-License-Identifier: GPL-3.0-only
use std::cmp::Reverse;

pub mod range;

#[cfg(feature = "models")]
pub mod model;

#[cfg(feature = "web")]
pub mod source;
#[cfg(feature = "web")]
pub mod web;

#[cfg(feature = "android-jni")]
mod android_jni;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ResumeTarget {
    pub index: usize,
    pub position: i64,
}

/// Episode IDs are resolved by the caller against the current playable list.
pub fn resume_target(
    count: usize,
    matched: Option<usize>,
    position: i64,
    completed: bool,
) -> Option<ResumeTarget> {
    let last = count.checked_sub(1)?;
    let Some(index) = matched.filter(|index| *index <= last) else {
        return Some(ResumeTarget {
            index: 0,
            position: 0,
        });
    };
    Some(if completed {
        ResumeTarget {
            index: (index + 1).min(last),
            position: 0,
        }
    } else {
        ResumeTarget {
            index,
            position: position.max(0),
        }
    })
}

#[derive(Clone, Copy)]
pub struct Rendition {
    pub quality: i32,
    pub avc: bool,
    pub supported: bool,
}

/// Use compatible tiers when possible, keep the first input on ties, and use the lowest
/// positive tier when every reported quality exceeds the requested maximum.
pub fn select_rendition(rows: &[Rendition], maximum: i32) -> Option<usize> {
    let has_supported = rows.iter().any(|row| row.supported);
    rows.iter()
        .enumerate()
        .filter(|(_, row)| row.supported || !has_supported)
        .max_by_key(|(index, row)| {
            let quality = i64::from(row.quality);
            let priority = if row.quality <= 0 {
                (1, quality, row.avc)
            } else if row.quality <= maximum {
                (2, quality, row.avc)
            } else {
                (0, -quality, !row.avc)
            };
            (priority, Reverse(*index))
        })
        .map(|(index, _)| index)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resume_uses_saved_episode_and_advances_within_current_list() {
        assert_eq!(resume_target(0, Some(0), 100, true), None);
        let first = Some(ResumeTarget {
            index: 0,
            position: 0,
        });
        assert_eq!(resume_target(2, Some(2), 100, false), first);
        assert_eq!(resume_target(2, None, 100, false), first);
        assert_eq!(resume_target(2, Some(0), -1, false), first);
        assert_eq!(
            resume_target(2, Some(0), 150, false),
            Some(ResumeTarget {
                index: 0,
                position: 150
            })
        );
        let last = Some(ResumeTarget {
            index: 1,
            position: 0,
        });
        assert_eq!(resume_target(2, Some(0), 150, true), last);
        assert_eq!(resume_target(2, Some(1), 150, true), last);
    }

    #[test]
    fn quality_matches_android_compatibility_and_stable_tie_breaks() {
        let rows = [
            Rendition {
                quality: 1080,
                avc: false,
                supported: false,
            },
            Rendition {
                quality: 720,
                avc: false,
                supported: true,
            },
            Rendition {
                quality: 720,
                avc: true,
                supported: true,
            },
            Rendition {
                quality: 720,
                avc: true,
                supported: true,
            },
        ];
        assert_eq!(select_rendition(&rows, 1080), Some(2));
        assert_eq!(select_rendition(&rows, 480), Some(1));
        assert_eq!(select_rendition(&rows[..1], 480), Some(0));
        assert_eq!(select_rendition(&[], 480), None);
        let automatic = Rendition {
            quality: 0,
            avc: false,
            supported: true,
        };
        assert_eq!(select_rendition(&[rows[0], automatic], 1080), Some(1));
    }
}
