// SPDX-License-Identifier: GPL-3.0-only
// Port of kotlin-tv/core/MediaCrypto.kt: CENC AES-CTR, including subsamples.
use aes::cipher::{KeyIvInit, StreamCipher, StreamCipherSeek};
use anyhow::{Context, Result, ensure};
const MAX_HEADER: usize = 8 * 1024 * 1024;
const MAX_SAMPLES: usize = 200_000;
#[derive(Clone)]
struct Sample {
    start: u64,
    end: u64,
    iv: [u8; 16],
    encrypted_offset: u64,
}
pub(crate) struct Prepared {
    header: Vec<u8>,
    samples: Vec<Sample>,
    key: [u8; 16],
}
#[derive(Clone, Copy)]
struct BoxInfo {
    start: usize,
    body: usize,
    end: usize,
    kind: [u8; 4],
}
fn u32_at(data: &[u8], at: usize) -> Result<u32> {
    Ok(u32::from_be_bytes(
        data.get(at..at + 4).context("视频索引截断")?.try_into()?,
    ))
}
fn u64_at(data: &[u8], at: usize) -> Result<u64> {
    Ok(u64::from_be_bytes(
        data.get(at..at + 8).context("视频索引截断")?.try_into()?,
    ))
}
fn box_at(data: &[u8], at: usize, limit: usize) -> Result<BoxInfo> {
    ensure!(
        limit <= data.len() && at <= limit && limit - at >= 8,
        "视频容器索引截断"
    );
    let size = u32_at(data, at)?;
    let header = if size == 1 { 16 } else { 8 };
    let size = match size {
        0 => (limit - at) as u64,
        1 => u64_at(data, at + 8)?,
        n => n as u64,
    };
    ensure!(
        size >= header as u64 && size <= (limit - at) as u64,
        "视频容器索引越界"
    );
    Ok(BoxInfo {
        start: at,
        body: at + header,
        end: at + size as usize,
        kind: data[at + 4..at + 8].try_into()?,
    })
}
fn children(data: &[u8], parent: BoxInfo) -> Result<Vec<BoxInfo>> {
    let mut result = Vec::new();
    let mut at = parent.body;
    while at < parent.end {
        let next = box_at(data, at, parent.end)?;
        at = next.end;
        result.push(next);
    }
    Ok(result)
}
fn child(data: &[u8], parent: BoxInfo, kind: &[u8; 4]) -> Result<BoxInfo> {
    children(data, parent)?
        .into_iter()
        .find(|b| &b.kind == kind)
        .context("视频样本索引缺失")
}
fn field(data: &[u8], b: BoxInfo, offset: usize) -> Result<u32> {
    ensure!(offset + 4 <= b.end - b.body, "视频索引字段截断");
    u32_at(data, b.body + offset)
}
fn count(data: &[u8], b: BoxInfo, offset: usize) -> Result<usize> {
    let n = field(data, b, offset)? as usize;
    ensure!(n <= MAX_SAMPLES, "视频索引数量过大");
    Ok(n)
}
pub(crate) fn header_end(prefix: &[u8], total: u64) -> Result<usize> {
    ensure!(
        prefix.len() >= 16 && &prefix[4..8] == b"ftyp",
        "不支持的视频容器"
    );
    let ftyp = u32_at(prefix, 0)? as usize;
    ensure!(
        ftyp >= 8 && ftyp + 8 <= prefix.len() && &prefix[ftyp + 4..ftyp + 8] == b"moov",
        "视频索引不在文件开头"
    );
    let size = u32_at(prefix, ftyp)? as usize;
    let end = ftyp + size;
    ensure!(
        size >= 8 && end <= MAX_HEADER && end as u64 <= total,
        "视频索引大小异常"
    );
    Ok(end)
}
impl Prepared {
    pub(crate) fn parse(mut data: Vec<u8>, key: [u8; 16], total: u64) -> Result<Self> {
        ensure!(data.len() <= MAX_HEADER, "视频索引过大");
        let ftyp = box_at(&data, 0, data.len())?;
        let moov = box_at(&data, ftyp.end, data.len())?;
        ensure!(
            &ftyp.kind == b"ftyp" && &moov.kind == b"moov",
            "视频索引格式异常"
        );
        let mut samples = Vec::new();
        for trak in children(&data, moov)?
            .into_iter()
            .filter(|b| &b.kind == b"trak")
        {
            let mdia = child(&data, trak, b"mdia")?;
            let minf = child(&data, mdia, b"minf")?;
            let stbl = child(&data, minf, b"stbl")?;
            let boxes = children(&data, stbl)?;
            let Some(saiz) = boxes.iter().find(|b| &b.kind == b"saiz").copied() else {
                continue;
            };
            let saio = child(&data, stbl, b"saio")?;
            let stsz = child(&data, stbl, b"stsz")?;
            let stsc = child(&data, stbl, b"stsc")?;
            let chunks = boxes
                .iter()
                .find(|b| matches!(&b.kind, b"stco" | b"co64"))
                .copied()
                .context("视频块索引缺失")?;
            let n = count(&data, stsz, 8)?;
            let default_size = field(&data, stsz, 4)?;
            let sizes = (0..n)
                .map(|i| {
                    if default_size > 0 {
                        Ok(default_size as u64)
                    } else {
                        field(&data, stsz, 12 + i * 4).map(u64::from)
                    }
                })
                .collect::<Result<Vec<_>>>()?;
            let chunk_count = count(&data, chunks, 4)?;
            let width = if &chunks.kind == b"co64" { 8 } else { 4 };
            ensure!(
                chunks.body + 8 + chunk_count * width <= chunks.end,
                "块偏移截断"
            );
            let offsets = (0..chunk_count)
                .map(|i| {
                    if width == 8 {
                        u64_at(&data, chunks.body + 8 + i * 8)
                    } else {
                        u32_at(&data, chunks.body + 8 + i * 4).map(u64::from)
                    }
                })
                .collect::<Result<Vec<_>>>()?;
            let entries_count = count(&data, stsc, 4)?;
            ensure!(entries_count > 0, "块索引为空");
            let entries = (0..entries_count)
                .map(|i| {
                    Ok((
                        field(&data, stsc, 8 + i * 12)? as usize,
                        field(&data, stsc, 12 + i * 12)? as usize,
                    ))
                })
                .collect::<Result<Vec<_>>>()?;
            ensure!(
                entries[0].0 == 1
                    && entries.windows(2).all(|v| v[0].0 < v[1].0)
                    && entries.iter().all(|v| v.0 <= chunk_count && v.1 > 0),
                "样本与块数量不一致"
            );
            let size_base = saiz.body
                + 4
                + if field(&data, saiz, 0)? & 1 != 0 {
                    8
                } else {
                    0
                };
            ensure!(size_base + 5 <= saiz.end, "辅助索引截断");
            let aux_default = data[size_base] as usize;
            let aux_count = u32_at(&data, size_base + 1)? as usize;
            ensure!(
                aux_count == n && (aux_default > 0 || size_base + 5 + n <= saiz.end),
                "辅助索引数量不一致"
            );
            let aux_sizes = (0..n)
                .map(|i| {
                    if aux_default > 0 {
                        aux_default
                    } else {
                        data[size_base + 5 + i] as usize
                    }
                })
                .collect::<Vec<_>>();
            let offset_base = saio.body
                + 4
                + if field(&data, saio, 0)? & 1 != 0 {
                    8
                } else {
                    0
                };
            let offset_width = if data[saio.body] == 1 { 8 } else { 4 };
            ensure!(
                offset_base + 4 + offset_width <= saio.end && u32_at(&data, offset_base)? == 1,
                "不支持的辅助索引布局"
            );
            let aux = if offset_width == 8 {
                u64_at(&data, offset_base + 4)?
            } else {
                u32_at(&data, offset_base + 4)? as u64
            };
            ensure!(
                aux <= data.len() as u64
                    && aux_sizes.iter().sum::<usize>() <= data.len() - aux as usize,
                "辅助数据超出索引范围"
            );
            let mut aux = aux as usize;
            let (mut index, mut entry) = (0, 0);
            for (chunk, offset) in offsets.into_iter().enumerate() {
                while entry + 1 < entries.len() && entries[entry + 1].0 <= chunk + 1 {
                    entry += 1;
                }
                let count = entries[entry].1;
                ensure!(count <= n - index, "样本与块数量不一致");
                let mut position = offset;
                for _ in 0..count {
                    let length = sizes[index];
                    let aux_size = aux_sizes[index];
                    ensure!(
                        position >= data.len() as u64
                            && position <= total
                            && length > 0
                            && length <= total - position
                            && aux_size >= 8,
                        "样本索引异常"
                    );
                    let mut iv = [0; 16];
                    iv[..8].copy_from_slice(&data[aux..aux + 8]);
                    if aux_size == 8 {
                        samples.push(Sample {
                            start: position,
                            end: position + length,
                            iv,
                            encrypted_offset: 0,
                        });
                    } else {
                        ensure!(aux_size >= 10, "加密子样本索引截断");
                        let subs = u16::from_be_bytes(data[aux + 8..aux + 10].try_into()?) as usize;
                        ensure!(aux_size == 10 + subs * 6, "加密子样本索引异常");
                        let mut start = position;
                        let mut encrypted = 0;
                        for sub in 0..subs {
                            let at = aux + 10 + sub * 6;
                            let clear = u16::from_be_bytes(data[at..at + 2].try_into()?) as u64;
                            let size = u32_at(&data, at + 2)? as u64;
                            ensure!(clear <= position + length - start, "加密子样本越界");
                            start += clear;
                            ensure!(size <= position + length - start, "加密子样本越界");
                            if size > 0 {
                                samples.push(Sample {
                                    start,
                                    end: start + size,
                                    iv,
                                    encrypted_offset: encrypted,
                                });
                            }
                            start += size;
                            encrypted += size;
                        }
                        ensure!(start == position + length, "加密子样本长度异常");
                    }
                    ensure!(samples.len() <= MAX_SAMPLES, "加密样本数量过大");
                    position += length;
                    aux += aux_size;
                    index += 1;
                }
            }
            ensure!(index == n, "视频样本不完整");
            // Sample entry layout differs for audio/video; identify sinf only inside
            // the bounded stsd entry, preserving frma's actual AVC/HEVC/audio codec.
            let stsd = child(&data, stbl, b"stsd")?;
            let entry_count = count(&data, stsd, 4)?;
            let mut at = stsd.body + 8;
            for _ in 0..entry_count {
                let sample = box_at(&data, at, stsd.end)?;
                at = sample.end;
                if !matches!(&sample.kind, b"encv" | b"enca") {
                    continue;
                }
                let sinf = (sample.body..sample.end.saturating_sub(7))
                    .find_map(|pos| {
                        (&data[pos + 4..pos + 8] == b"sinf")
                            .then(|| box_at(&data, pos, sample.end).ok())
                            .flatten()
                    })
                    .context("原始编码信息缺失")?;
                let frma = child(&data, sinf, b"frma")?;
                ensure!(frma.body + 4 <= frma.end, "原始编码格式无效");
                let codec: [u8; 4] = data[frma.body..frma.body + 4].try_into()?;
                data[sample.start + 4..sample.start + 8].copy_from_slice(&codec);
                data[sinf.start + 4..sinf.start + 8].copy_from_slice(b"free");
                data[sinf.body..sinf.end].fill(0);
            }
        }
        ensure!(!samples.is_empty(), "视频加密索引不可用");
        samples.sort_by_key(|s| s.start);
        ensure!(
            samples.windows(2).all(|s| s[0].end <= s[1].start),
            "视频样本重叠"
        );
        Ok(Self {
            header: data,
            samples,
            key,
        })
    }
    pub(crate) fn decrypt(&self, start: u64, data: &[u8]) -> Result<Vec<u8>> {
        let end = start
            .checked_add(data.len() as u64)
            .context("视频分段越界")?;
        let mut out = data.to_vec();
        if start < self.header.len() as u64 {
            let stop = end.min(self.header.len() as u64);
            out[..(stop - start) as usize]
                .copy_from_slice(&self.header[start as usize..stop as usize]);
        }
        let first = self.samples.partition_point(|s| s.end <= start);
        for sample in &self.samples[first..] {
            if sample.start >= end {
                break;
            }
            let begin = start.max(sample.start);
            let stop = end.min(sample.end);
            if begin >= stop {
                continue;
            }
            let offset = begin - sample.start + sample.encrypted_offset;
            let mut cipher =
                ctr::Ctr128BE::<aes::Aes128>::new((&self.key).into(), (&sample.iv).into());
            cipher
                .try_seek(offset)
                .map_err(|_| anyhow::anyhow!("解密偏移越界"))?;
            cipher
                .try_apply_keystream(&mut out[(begin - start) as usize..(stop - start) as usize])
                .map_err(|_| anyhow::anyhow!("解密分段越界"))?;
        }
        Ok(out)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::hex;
    #[test]
    fn nist_unaligned_ranges_and_subsamples() {
        let key = hex("2b7e151628aed2a6abf7158809cf4f3c").try_into().unwrap();
        let iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff").try_into().unwrap();
        let plain = hex(
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
        );
        let encrypted = hex(
            "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee",
        );
        let source = [
            vec![1, 2, 3],
            encrypted[..21].to_vec(),
            vec![4, 5],
            encrypted[21..].to_vec(),
        ]
        .concat();
        let expected = [
            vec![1, 2, 3],
            plain[..21].to_vec(),
            vec![4, 5],
            plain[21..].to_vec(),
        ]
        .concat();
        let p = Prepared {
            header: vec![],
            samples: vec![
                Sample {
                    start: 3,
                    end: 24,
                    iv,
                    encrypted_offset: 0,
                },
                Sample {
                    start: 26,
                    end: 69,
                    iv,
                    encrypted_offset: 21,
                },
            ],
            key,
        };
        for start in 0..source.len() {
            for length in 1..=29.min(source.len() - start) {
                assert_eq!(
                    p.decrypt(start as u64, &source[start..start + length])
                        .unwrap(),
                    expected[start..start + length]
                );
            }
        }
    }
}

#[cfg(test)]
pub(crate) fn test_fixture() -> (Vec<u8>, Vec<u8>, [u8; 16], usize) {
    fn bx(kind: &[u8; 4], body: Vec<u8>) -> Vec<u8> {
        [
            (body.len() as u32 + 8).to_be_bytes().to_vec(),
            kind.to_vec(),
            body,
        ]
        .concat()
    }
    fn ints(values: &[u32]) -> Vec<u8> {
        values.iter().flat_map(|v| v.to_be_bytes()).collect()
    }
    let key = [7u8; 16];
    let mut iv = [0; 16];
    iv[..8].copy_from_slice(&[1, 2, 3, 4, 5, 6, 7, 8]);
    let ftyp = bx(b"ftyp", vec![0; 24]);
    let moov = |aux: u32| {
        let entry = bx(
            b"encv",
            [vec![0; 78], bx(b"sinf", bx(b"frma", b"avc1".to_vec()))].concat(),
        );
        let stsd = bx(b"stsd", [ints(&[0, 1]), entry].concat());
        let stsz = bx(b"stsz", ints(&[0, 64, 1]));
        let stsc = bx(b"stsc", ints(&[0, 1, 1, 1, 1]));
        let stco = bx(b"stco", ints(&[0, 1, 4096]));
        let saiz = bx(b"saiz", [ints(&[0]), vec![8], ints(&[1])].concat());
        let saio = bx(b"saio", ints(&[0, 1, aux]));
        bx(
            b"moov",
            [
                bx(
                    b"trak",
                    bx(
                        b"mdia",
                        bx(
                            b"minf",
                            bx(b"stbl", [stsd, stsz, stsc, stco, saiz, saio].concat()),
                        ),
                    ),
                ),
                bx(b"free", iv[..8].to_vec()),
            ]
            .concat(),
        )
    };
    let header_end = ftyp.len() + moov(0).len();
    let header = [ftyp, moov((header_end - 8) as u32)].concat();
    let mut data = vec![0; 5000];
    data[..header.len()].copy_from_slice(&header);
    let plain: Vec<u8> = (0..64).map(|i| i as u8).collect();
    let mut encrypted = plain.clone();
    let mut cipher = ctr::Ctr128BE::<aes::Aes128>::new((&key).into(), (&iv).into());
    cipher.apply_keystream(&mut encrypted);
    data[4096..4160].copy_from_slice(&encrypted);
    (data, plain, key, header_end)
}
#[cfg(test)]
mod index_tests {
    use super::*;
    #[test]
    fn restores_codec_and_decrypts_real_index_ranges() {
        let (data, plain, key, end) = test_fixture();
        assert_eq!(header_end(&data[..64], data.len() as u64).unwrap(), end);
        let prepared = Prepared::parse(data[..end].to_vec(), key, data.len() as u64).unwrap();
        assert!(prepared.header.windows(4).any(|b| b == b"avc1"));
        assert!(
            !prepared
                .header
                .windows(4)
                .any(|b| b == b"encv" || b == b"sinf")
        );
        assert_eq!(
            prepared.decrypt(4101, &data[4101..4151]).unwrap(),
            plain[5..55]
        );
        assert!(Prepared::parse(data[..end - 1].to_vec(), key, data.len() as u64).is_err());
        assert!(Prepared::parse(data[..end].to_vec(), key, 4100).is_err());
    }
    #[test]
    fn bounds_moov_and_auxiliary_offsets() {
        let (mut data, _, key, end) = test_fixture();
        let saio = data.windows(4).position(|b| b == b"saio").unwrap();
        data[saio + 12..saio + 16].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(Prepared::parse(data[..end].to_vec(), key, data.len() as u64).is_err());
        data[32..36].copy_from_slice(&9_000_000u32.to_be_bytes());
        assert!(header_end(&data[..64], 10_000_000).is_err());
    }
}
