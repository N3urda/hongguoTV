// SPDX-License-Identifier: GPL-3.0-only
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ByteRange {
    pub start: u64,
    pub end: u64,
}

impl ByteRange {
    pub fn from_header(value: &str, total: u64) -> Option<Self> {
        let last = total.checked_sub(1)?;
        let (left, right) = value.trim().strip_prefix("bytes=")?.split_once('-')?;
        if left.is_empty() {
            let suffix = digits(right)?;
            return (suffix > 0).then(|| Self {
                start: total.saturating_sub(suffix),
                end: last,
            });
        }
        let start = digits(left)?;
        let end = if right.is_empty() {
            last
        } else {
            digits(right)?.min(last)
        };
        (start <= end && start < total).then_some(Self { start, end })
    }

    pub fn length(self) -> u64 {
        self.end - self.start + 1
    }

    pub fn header(self) -> String {
        format!("bytes={}-{}", self.start, self.end)
    }

    pub fn content_range(self, total: u64) -> String {
        format!("bytes {}-{}/{total}", self.start, self.end)
    }
}

fn digits(value: &str) -> Option<u64> {
    if value.bytes().all(|byte| byte.is_ascii_digit()) {
        value.parse().ok()
    } else {
        None
    }
}

#[derive(Debug, Eq, PartialEq)]
pub enum RangeError {
    Invalid,
    Mismatch,
    Changed,
}

/// Validate the returned bytes, allowing the server to clamp the upper end at EOF.
pub fn validate_response(
    header: &str,
    requested: ByteRange,
    expected_total: Option<u64>,
) -> Result<(ByteRange, u64), RangeError> {
    fn parse(value: &str) -> Option<(ByteRange, u64)> {
        let (span, total) = value.strip_prefix("bytes ")?.split_once('/')?;
        let (start, end) = span.split_once('-')?;
        Some((
            ByteRange {
                start: digits(start)?,
                end: digits(end)?,
            },
            digits(total)?,
        ))
    }
    let (span, total) = parse(header).ok_or(RangeError::Invalid)?;
    if total <= requested.start
        || span.start != requested.start
        || span.end != requested.end.min(total - 1)
        || span.end < span.start
    {
        return Err(RangeError::Mismatch);
    }
    if expected_total.is_some_and(|expected| expected != total) {
        return Err(RangeError::Changed);
    }
    Ok((span, total))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_clamped_length_and_distinguishes_source_change() {
        let request = ByteRange { start: 20, end: 90 };
        assert_eq!(
            validate_response("bytes 20-49/50", request, None),
            Ok((ByteRange { start: 20, end: 49 }, 50))
        );
        assert_eq!(
            validate_response("bytes 20-49/50", request, Some(80)),
            Err(RangeError::Changed)
        );
        assert_eq!(
            validate_response("bytes 20-19/50", request, None),
            Err(RangeError::Mismatch)
        );
        assert_eq!(
            validate_response("bytes 20-49/*", request, None),
            Err(RangeError::Invalid)
        );
    }

    #[test]
    fn parses_single_client_ranges_for_player_seek() {
        assert_eq!(
            ByteRange::from_header("bytes=4-", 10),
            Some(ByteRange { start: 4, end: 9 })
        );
        assert_eq!(
            ByteRange::from_header("bytes=-10", 60),
            Some(ByteRange { start: 50, end: 59 })
        );
        assert_eq!(
            ByteRange::from_header("bytes=0-59", 10),
            Some(ByteRange { start: 0, end: 9 })
        );
        assert!(ByteRange::from_header("bytes=0-", 0).is_none());
        for invalid in [
            "bytes=-0",
            "bytes=9-0",
            "bytes=10-",
            "bytes=+1-",
            "bytes=0-4,5-",
        ] {
            assert!(ByteRange::from_header(invalid, 10).is_none(), "{invalid}");
        }
    }
}
