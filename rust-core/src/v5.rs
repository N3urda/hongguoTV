// SPDX-License-Identifier: GPL-3.0-only
// Port of main's Signer(mobileV5=true), AesV3 and V5Constants.
use crate::crypto::{Proto, hex, hex_string, ladon_for, md5, sm3};
use anyhow::{Result, bail};
use base64::{
    Engine,
    engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD},
};
use rand::RngCore;
use std::collections::BTreeMap;
#[path = "v5_table.rs"]
mod constants;
use constants::{HASH_TABLE, SBOX};
const MAGIC: &str = "2d4b4fca49750d433fb5ae2c226dcc56";
const KEY: &str = "b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d";
fn get_iv(mut v: u32, data: &[u8]) -> u32 {
    for (i, b) in data.iter().enumerate() {
        v = if i % 2 == 0 {
            (v >> 4) ^ v ^ (v << 6) ^ *b as u32
        } else {
            !((v >> 7) ^ v ^ (*b as u32 | (v << 12)))
        };
    }
    v
}
fn iv(query: &[u8], body: &[u8], ts: u32) -> u32 {
    get_iv(get_iv(get_iv(0x20230928, query), body), &ts.to_le_bytes())
}
fn f13(query: &[u8], body: &[u8], ts: u32) -> Vec<u8> {
    let initial_iv = 0x9d4a97c2u32;
    let count = initial_iv.wrapping_add(ts);
    let table = HASH_TABLE.map(|n| n.rotate_right(count.wrapping_add(1) & 255));
    let shift = count.wrapping_add(2) & 7;
    let padding = [0xfau32, 0x45, 0x61, 0xd7].map(|v| ((v | (v << 8)) >> shift) as u8);
    let data = [
        query,
        body,
        &ts.to_le_bytes(),
        &padding,
        &hex("00000000000001a0"),
    ]
    .concat();
    let mut di: Vec<u32> = data
        .chunks_exact(4)
        .map(|b| u32::from_be_bytes(b.try_into().unwrap()))
        .collect();
    for i in 0..112 {
        let x = di[i + 1];
        let y = di[i + 14];
        di.push(
            di[i]
                .wrapping_add(di[i + 9])
                .wrapping_add(x.rotate_left(14) ^ x.rotate_left(25) ^ (x >> 3))
                .wrapping_add(y.rotate_left(13) ^ y.rotate_left(15) ^ (y >> 10)),
        );
    }
    let initial = [
        0x7aba4fc8u32,
        0x67166507,
        0x6403fa00,
        0x340f512f,
        984304912,
        3005047866,
        2874125293,
        2152413264,
    ]
    .map(|n| n.rotate_right(count));
    let mut d = initial;
    for i in 0..96 {
        let base = initial_iv.wrapping_add(i);
        let n1 = ((d[4] ^ d[0]) & d[1]) ^ d[4];
        let n2 = d[1].rotate_left(26) ^ d[1].rotate_left(21) ^ d[1].rotate_left(7);
        let n4 = di[(base & 127) as usize]
            .wrapping_add(n1)
            .wrapping_add(n2)
            .wrapping_add(table[(base & 63) as usize])
            .wrapping_add(d[6]);
        let n5 = d[3].rotate_left(30) ^ d[3].rotate_left(19) ^ d[3].rotate_left(10);
        let n6 = (d[3] & d[5]) | ((d[3] | d[5]) & d[2]);
        let old = d[7];
        d.rotate_right(1);
        d[7] = n5.wrapping_add(n6).wrapping_add(n4);
        d[0] = old.wrapping_add(n4);
    }
    let bytes: Vec<u8> = d
        .iter()
        .zip(initial)
        .flat_map(|(a, b)| a.wrapping_add(b).to_be_bytes())
        .collect();
    let mut folded: Vec<u8> = bytes[..16]
        .iter()
        .zip(&bytes[16..])
        .map(|(a, b)| a ^ b)
        .collect();
    let mut check = 0x20220420u32;
    for (i, b) in folded[..12].iter().enumerate() {
        let temp = (check >> if i % 2 == 0 { 3 } else { 5 }) ^ check;
        check = if i % 2 == 0 {
            *b as u32 ^ (check << 7)
        } else {
            !(*b as u32 | (check << 11))
        };
        check ^= temp;
    }
    folded.extend((((check | 4) ^ 0x1000000) | 0x20).to_le_bytes());
    folded
}
fn xmxor(data: &[u8], key: &[u8]) -> Vec<u8> {
    let mut out = vec![0u8; data.len()];
    let last_index = data.len() - 1;
    for (i, b) in data.iter().enumerate() {
        let at = (i * 4) & 28;
        let (a, c) = (key[at], key[at + 1]);
        let v = (!(b.rotate_left(4).wrapping_add(a)) ^ c)
            .rotate_left(3)
            .wrapping_add(c)
            ^ a;
        out[last_index - i] = !v;
    }
    let last = out[last_index] ^ out[last_index - 1];
    let first = out[0];
    out[0] = (!last).wrapping_add(first);
    out[1] = (out[0] ^ out[last_index] ^ 254).wrapping_add(out[1]);
    out[2] = out[2].wrapping_add(last.wrapping_sub(first) ^ out[1].rotate_left(3) ^ 2);
    for i in 0..out.len() - 4 {
        let temp = out[i + 2].rotate_left(3) ^ out[i + 1] ^ ((i + 3) as u8);
        out[i + 3] = (!temp).wrapping_add(out[i + 3]);
    }
    out[last_index] ^= out[last_index - 1];
    out[0] = (out[0] ^ out[1]).wrapping_add(out[1..].iter().fold(0u8, |a, b| a.wrapping_add(*b)));
    out
}
fn carrier(source: &[u8], key: &[u8], iv: &[u8], ts: u32) -> Vec<u8> {
    let word = (ts & 3) as usize;
    let sbox = &SBOX[word * 256..(word + 1) * 256];
    let con = [[2, 0, 3, 1], [1, 3, 2, 0], [3, 2, 1, 0], [2, 3, 1, 0]][word];
    let order = [
        [0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11],
        [0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7],
        [0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7],
        [0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7],
    ][word];
    let initial = [0xbbb5de72u32, 0xd4638d5b, 0x92a5f73b, 0x89f8a1c9][word];
    let mut expanded = [0u8; 48];
    for i in 0..16 {
        expanded[i] = initial.to_le_bytes()[i % 4] ^ key[i];
    }
    let mut rounds = 8;
    for i in 4..12 {
        let at = 4 * (i - 1);
        let (mut a, mut b, mut c, mut d) = (
            expanded[at],
            expanded[at + 1],
            expanded[at + 2],
            expanded[at + 3],
        );
        if i & 3 == 0 {
            let t = ((initial >> (rounds & 24)) as u8) ^ sbox[b as usize];
            b = sbox[c as usize];
            c = sbox[d as usize];
            d = sbox[a as usize];
            a = t;
        }
        rounds += 2;
        expanded[at + 4] = a ^ expanded[at - 12];
        expanded[at + 5] = b ^ expanded[at - 11];
        expanded[at + 6] = c ^ expanded[at - 10];
        expanded[at + 7] = d ^ expanded[at - 9];
    }
    let block = |value: &[u8]| -> Vec<u8> {
        let mut rows = [[0u8; 4]; 4];
        for i in 0..16 {
            rows[i / 4][i % 4] = value[i];
        }
        let add = |rows: &mut [[u8; 4]; 4], offset: usize| {
            for i in 0..4 {
                for j in 0..4 {
                    rows[i][j] ^= expanded[(offset + i) * 4 + con[j]];
                }
            }
        };
        add(&mut rows, 0);
        for round in 1..=2 {
            for row in &mut rows {
                for b in row {
                    *b = sbox[*b as usize];
                }
            }
            let before = rows;
            for i in 0..4 {
                rows[i] = before[con[i]];
            }
            let flat: Vec<u8> = rows.into_iter().flatten().collect();
            for i in 0..16 {
                rows[i / 4][i % 4] = flat[order[i]];
            }
            if round == 1 {
                for row in &mut rows {
                    let old = *row;
                    *row = std::array::from_fn(|i| old[con[i]]);
                }
                let xt = |x: u8| (x << 1) ^ if x & 128 != 0 { 0x1b } else { 0 };
                let [r0, r1, r2, r3] = &mut rows;
                for (((a, b), c), d) in r0.iter_mut().zip(r1).zip(r2).zip(r3) {
                    let t = *a ^ *b ^ *c ^ *d;
                    let u = *a;
                    *a ^= t ^ xt(*a ^ *b);
                    *b ^= t ^ xt(*b ^ *c);
                    *c ^= t ^ xt(*c ^ *d);
                    *d ^= t ^ xt(*d ^ u);
                }
            }
            add(&mut rows, round * 4);
        }
        for i in 0..4 {
            for j in 0..4 {
                rows[i][j] ^= expanded[(4 + i) * 4 + j];
            }
        }
        rows.into_iter().flatten().collect()
    };
    let wire = [6, 5, 3, 4, 2, 7, 1, 0];
    let compact = [1, 7, 3, 6, 2, 0, 4, 5];
    let mut plaintext = [0u8; 32];
    for i in 0..31 {
        for j in 0..8 {
            plaintext[i] |= ((source[i * 8 + j] >> wire[j]) & 1) << compact[j];
        }
    }
    plaintext[31] = 1;
    let first = block(
        &plaintext[..16]
            .iter()
            .zip(iv)
            .map(|(a, b)| a ^ b)
            .collect::<Vec<_>>(),
    );
    let second = block(
        &plaintext[16..]
            .iter()
            .zip(&first)
            .map(|(a, b)| a ^ b)
            .collect::<Vec<_>>(),
    );
    let key = [first, second].concat();
    let mut out = source.to_vec();
    for (i, byte) in key.iter().enumerate().take(31) {
        for j in 0..8 {
            let at = i * 8 + j;
            out[at] = (out[at] & !(1 << wire[j])) | (((byte >> compact[j]) & 1) << wire[j]);
        }
    }
    [vec![key[31]], out].concat()
}
pub(crate) struct Signer {
    nonce: String,
    started: u64,
}
impl Signer {
    pub(crate) fn new(now: u64) -> Self {
        let mut random = [0u8; 18];
        rand::rngs::OsRng.fill_bytes(&mut random);
        Self {
            nonce: URL_SAFE_NO_PAD.encode(random),
            started: now,
        }
    }
    pub(crate) fn sign(
        &self,
        values: &[(String, String)],
        body: &[u8],
        now: u64,
    ) -> Result<(String, BTreeMap<String, String>)> {
        let ts = (now / 1000) as u32;
        for offset in 0..1024 {
            let ticket = now + offset;
            let mut pairs = values.to_vec();
            pairs.push(("_rticket".into(), ticket.to_string()));
            let query = url::form_urlencoded::Serializer::new(String::new())
                .extend_pairs(&pairs)
                .finish()
                .replace('+', "%20")
                .replace("%7E", "~");
            if iv(&sm3(query.as_bytes()), &md5(body), ts) & 15 != 12 {
                continue;
            }
            let who: BTreeMap<_, _> = pairs.into_iter().collect();
            let mut salt = [0; 4];
            rand::rngs::OsRng.fill_bytes(&mut salt);
            return Ok((
                query.clone(),
                BTreeMap::from([
                    ("x-khronos".into(), ts.to_string()),
                    ("x-helios".into(), ladon_for(ts as u64, &salt, "646637794")),
                    ("x-medusa".into(), self.medusa(&query, body, ts, &who)),
                    ("x-ss-stub".into(), hex_string(&md5(body)).to_uppercase()),
                ]),
            ));
        }
        bail!("无法生成播放签名")
    }
    fn medusa(&self, query: &str, body: &[u8], ts: u32, who: &BTreeMap<String, String>) -> String {
        let query = sm3(query.as_bytes());
        let digest = f13(&query, &md5(body), ts);
        let flags = [0u8; 8];
        let mut device = Proto::default();
        device.n(1, 2);
        device.n(2, 4);
        device.s(3, "8662");
        device.s(4, &who["device_id"]);
        device.s(5, &self.nonce);
        device.s(6, "!noperm!");
        device.s(12, "Asia/Shanghai,8");
        device.s(13, "zh_CN");
        device.s(22, &who["os_version"]);
        device.n(25, self.started * 2);
        device.s(30, &who["device_type"]);
        device.s(31, "HONOR");
        device.s(32, &who["device_type"]);
        device.s(34, "HONOR");
        let mut env = Proto::default();
        env.s(6, "v04.10.03.04-bugfix");
        env.s(8, &who["channel"]);
        env.b(12, &device.0);
        env.s(14, &who["version_name"]);
        let mut p = Proto::default();
        p.b(1, &hex(MAGIC));
        p.n(2, 10);
        p.n(3, rand::rngs::OsRng.next_u32() as u64 * 2);
        p.s(4, "8662");
        p.s(5, &who["device_id"]);
        p.s(6, "646637794");
        p.s(7, &who["version_name"]);
        p.s(8, "v04.10.03-ml-android");
        p.n(9, 0x040a0300 * 2);
        p.b(10, &flags);
        p.n(12, ts as u64 * 2);
        p.b(13, &digest);
        p.b(14, &query[..6]);
        let mut nested = Proto::default();
        for tag in [1, 2, 3, 5] {
            nested.n(tag, 2);
        }
        p.b(15, &nested.0);
        p.n(17, ts as u64 * 2);
        p.s(20, "none");
        p.n(21, 754);
        p.b(23, &env.0);
        p.s(24,r#"{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}"#);
        let nonce = rand::rngs::OsRng.next_u32();
        let key = hex(KEY);
        let hash = sm3(&[key.as_slice(), &nonce.to_le_bytes(), key.as_slice()].concat());
        let high = (nonce >> 16) & 255;
        let seed = (!(((high << 11) | (nonce >> 24)) ^ (high >> 5) ^ high)).to_le_bytes();
        let mut transformed = [flags.to_vec(), xmxor(&p.0, &hash)].concat();
        transformed.reverse();
        for (i, b) in transformed.iter_mut().enumerate() {
            *b ^= seed[!i & 3];
        }
        let check =
            0x18000001u32 | (((query[0] & 63) as u32) << 14) | (((digest[0] & 63) as u32) << 8);
        let packed = [
            vec![0xca],
            rand::rngs::OsRng.next_u32().to_le_bytes().to_vec(),
            check.to_le_bytes().to_vec(),
            transformed,
            vec![(nonce >> 16) as u8, (nonce >> 24) as u8],
        ]
        .concat();
        let encrypted = carrier(&packed, &md5(&key[..16]), &md5(&key[16..]), ts);
        let mut prefix = [5u32.to_le_bytes().to_vec(), hex(MAGIC)].concat();
        for (i, b) in prefix.iter_mut().enumerate() {
            *b ^= ts.to_le_bytes()[i & 3];
        }
        STANDARD.encode(
            [
                prefix,
                vec![nonce as u8, (nonce >> 8) as u8, 0, 1],
                encrypted,
            ]
            .concat(),
        )
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn matches_main_sdk_digest_and_carrier_vectors() {
        let rows: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/signing-v5-f13.json")).unwrap();
        for row in rows.as_array().unwrap() {
            let result = f13(
                &hex(row["query"].as_str().unwrap()),
                &hex(row["body"].as_str().unwrap()),
                row["ts"].as_u64().unwrap() as u32,
            );
            assert_eq!(hex_string(&result[..16]), row["digest"].as_str().unwrap());
        }
        let rows: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/signing-v5-carrier.json"))
                .unwrap();
        for row in rows.as_array().unwrap() {
            let result = carrier(
                &hex(row["source"].as_str().unwrap()),
                &hex(row["key"].as_str().unwrap()),
                &hex(row["iv"].as_str().unwrap()),
                row["ts"].as_u64().unwrap() as u32,
            );
            assert_eq!(hex_string(&result), row["encrypted"].as_str().unwrap());
        }
    }
}
