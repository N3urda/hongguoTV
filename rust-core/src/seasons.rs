// SPDX-License-Identifier: GPL-3.0-only
use super::{RelatedWork, Series};

/// Accept only a unique, released, consecutive season of the same work.
pub(super) fn following<'a>(current: &Series, rows: &'a [RelatedWork]) -> Option<&'a RelatedWork> {
    let (title, number) = title_season(&current.title)?;
    let next = number.unwrap_or(1).checked_add(1)?;
    let stem = title_stem(title);
    if stem.is_empty() {
        return None;
    }
    let mut candidates = rows.iter().filter(|row| {
        if row.upcoming || row.series.id == current.id {
            return false;
        }
        let Some((title, inferred)) = title_season(&row.series.title) else {
            return false;
        };
        let index = if row.season_index > 0 {
            Some(row.season_index)
        } else {
            inferred
        };
        index == Some(next)
            && inferred.is_none_or(|number| number == next)
            && title_stem(title) == stem
    });
    let candidate = candidates.next()?;
    candidates.next().is_none().then_some(candidate)
}

fn title_stem(value: &str) -> String {
    value
        .chars()
        .filter(|ch| {
            !ch.is_whitespace()
                && !"：:。，、,.！!？?；;—–-·《》〈〉「」『』【】（）()[]".contains(*ch)
        })
        .collect()
}

fn title_season(value: &str) -> Option<(&str, Option<u32>)> {
    let value = value.trim();
    let Some(prefix) = value.strip_suffix('季') else {
        return Some((value, None));
    };
    let start = prefix.trim_end_matches(|ch: char| {
        ch.is_ascii_digit() || "零〇一二两三四五六七八九十百".contains(ch)
    });
    if prefix.len() == start.len() {
        return Some((value, None));
    }
    let numeric = &prefix[start.len()..];
    let number = if numeric.bytes().all(|c| c.is_ascii_digit()) {
        numeric.parse().ok()?
    } else {
        let mut total = 0u32;
        let mut part = 0u32;
        for ch in numeric.chars() {
            match ch {
                '十' | '百' => {
                    total = total.checked_add(part.max(1) * if ch == '十' { 10 } else { 100 })?;
                    part = 0;
                }
                '两' => part = 2,
                '〇' => part = 0,
                _ => {
                    part = "零一二三四五六七八九"
                        .chars()
                        .position(|digit| digit == ch)? as u32
                }
            }
        }
        total.checked_add(part)?
    };
    (number > 0).then_some((start.strip_suffix('第').unwrap_or(start), Some(number)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn series(id: &str, title: &str) -> Series {
        Series {
            id: id.into(),
            title: title.into(),
            cover: String::new(),
            badge: String::new(),
            description: String::new(),
            tags: Vec::new(),
        }
    }

    #[test]
    fn opens_only_a_unique_published_next_season_with_matching_metadata() {
        let current = series("123", "全球死考：刚下刑场你让我去考试一季");
        let second = RelatedWork {
            series: series("234", "全球死考：刚下刑场你让我去考试二季"),
            upcoming: false,
            season: String::new(),
            season_index: 2,
        };
        assert_eq!(
            following(&current, std::slice::from_ref(&second))
                .unwrap()
                .series
                .id,
            "234"
        );
        let mut another = second.clone();
        another.series.id = "345".into();
        assert!(following(&current, &[second.clone(), another]).is_none());
        assert!(
            following(
                &current,
                &[RelatedWork {
                    season_index: 3,
                    ..second.clone()
                }]
            )
            .is_none()
        );
        assert!(
            following(
                &current,
                &[RelatedWork {
                    upcoming: true,
                    ..second.clone()
                }]
            )
            .is_none()
        );
        assert!(
            following(
                &current,
                &[RelatedWork {
                    series: series("234", "全球死考：罪名不朽二季"),
                    ..second
                }]
            )
            .is_none()
        );
        assert_eq!(title_season("短剧第四十季"), Some(("短剧", Some(40))));
    }
}
