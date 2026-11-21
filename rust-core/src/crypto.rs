// SPDX-License-Identifier: GPL-3.0-only
// Port of kotlin-tv/core DeviceRegister, PlayerSignature and MediaCrypto.
// Argus/Simon adapted from tr4cex/TikTok-Encryption (MIT; see kotlin-tv/NOTICE.md).
use aes::cipher::{BlockDecryptMut, BlockEncryptMut, KeyIvInit, block_padding::Pkcs7};
use base64::{
    Engine,
    engine::general_purpose::{STANDARD, STANDARD_NO_PAD},
};
use md5::{Digest, Md5};
use sha2::Sha512;
use sm3::Sm3;
use std::collections::{BTreeMap, VecDeque};

const SALT: &str = "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58";
pub(crate) fn hex(raw: &str) -> Vec<u8> {
    (0..raw.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&raw[i..i + 2], 16).unwrap())
        .collect()
}
pub(crate) fn hex_string(raw: &[u8]) -> String {
    raw.iter().map(|b| format!("{b:02x}")).collect()
}
pub(crate) fn md5(raw: &[u8]) -> Vec<u8> {
    Md5::digest(raw).to_vec()
}
pub(crate) fn sm3(raw: &[u8]) -> Vec<u8> {
    Sm3::digest(raw).to_vec()
}
pub(crate) fn decode64(raw: &str) -> Option<Vec<u8>> {
    let raw: String = raw
        .chars()
        .filter(|c| !c.is_ascii_whitespace())
        .collect::<String>()
        .replace('-', "+")
        .replace('_', "/");
    STANDARD
        .decode(&raw)
        .or_else(|_| STANDARD_NO_PAD.decode(raw.trim_end_matches('=')))
        .ok()
}
fn pad(mut raw: Vec<u8>) -> Vec<u8> {
    let n = 16 - raw.len() % 16;
    raw.extend(vec![n as u8; n]);
    raw
}
fn encrypt(raw: &[u8], key: &[u8], iv: &[u8]) -> Vec<u8> {
    cbc::Encryptor::<aes::Aes128>::new_from_slices(key, iv)
        .unwrap()
        .encrypt_padded_vec_mut::<Pkcs7>(raw)
}
fn derived(seed: &[u8]) -> Vec<u8> {
    Sha512::digest([Sha512::digest(seed).as_slice(), &hex(SALT)].concat()).to_vec()
}
pub(crate) fn tt_encrypt(data: &[u8], random: &[u8; 32]) -> Vec<u8> {
    let h = derived(random);
    [
        hex("746305100000"),
        random.to_vec(),
        encrypt(
            &[Sha512::digest(data).as_slice(), data].concat(),
            &h[..16],
            &h[16..32],
        ),
    ]
    .concat()
}
pub(crate) fn decrypt_url(raw: &str, seed: &[u8]) -> Option<String> {
    let raw = decode64(raw)?;
    if raw.len() < 20 || raw[0] != 0xa8 || raw[2..4] != [1, 0] {
        return None;
    }
    let h = derived(seed);
    let end = 4 + (raw.len() - 4) / 16 * 16;
    let data = cbc::Decryptor::<aes::Aes128>::new_from_slices(&h[..16], &h[16..32])
        .ok()?
        .decrypt_padded_vec_mut::<Pkcs7>(&raw[4..end])
        .ok()?;
    Some(
        String::from_utf8(data)
            .ok()?
            .trim_end_matches('\0')
            .to_owned(),
    )
}
pub(crate) fn derive_key(raw: &str) -> Option<[u8; 16]> {
    let raw = decode64(raw)?;
    if raw.len() < 3 {
        return None;
    }
    let length =
        (raw.len() as isize - (raw[0] ^ raw[1] ^ raw[2]) as isize + 47).min(raw.len() as isize - 1);
    if length < 33 {
        return None;
    }
    let mut work = raw[1..1 + length as usize].to_vec();
    let (mut a, mut b) = (85u8, 246u8);
    for (i, v) in work.iter_mut().enumerate() {
        let old = *v;
        let previous = if i % 2 != 0 { a } else { b };
        if i % 2 != 0 {
            a = old
        } else {
            b = old
        }
        *v = (previous ^ old)
            .wrapping_sub(21)
            .wrapping_sub(i.count_ones() as u8);
    }
    let text = std::str::from_utf8(&work[1..33]).ok()?;
    if !text.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    hex(text).try_into().ok()
}
fn word(raw: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(raw[at..at + 8].try_into().unwrap())
}
fn varint(out: &mut Vec<u8>, mut n: u64) {
    while n >= 128 {
        out.push((n as u8 & 127) | 128);
        n >>= 7;
    }
    out.push(n as u8);
}
#[derive(Default)]
pub(crate) struct Proto(pub(crate) Vec<u8>);
impl Proto {
    pub(crate) fn n(&mut self, tag: u64, n: u64) {
        varint(&mut self.0, tag << 3);
        varint(&mut self.0, n);
    }
    pub(crate) fn b(&mut self, tag: u64, b: &[u8]) {
        varint(&mut self.0, (tag << 3) | 2);
        varint(&mut self.0, b.len() as u64);
        self.0.extend(b);
    }
    pub(crate) fn s(&mut self, tag: u64, s: &str) {
        self.b(tag, s.as_bytes());
    }
}
pub(crate) fn ladon_for(ts: u64, salt: &[u8], license: &str) -> String {
    let ascii = hex_string(&md5(&[salt, b"8662"].concat())).into_bytes();
    let mut queue = VecDeque::from([word(&ascii, 16), word(&ascii, 24)]);
    let (mut left, mut right) = (word(&ascii, 0), word(&ascii, 8));
    let mut keys = [0u64; 34];
    for (i, k) in keys.iter_mut().enumerate() {
        *k = left;
        let next = right.rotate_right(8).wrapping_add(left) ^ i as u64;
        queue.push_back(next);
        left = next ^ left.rotate_left(3);
        right = queue.pop_front().unwrap();
    }
    let mut out = salt.to_vec();
    for block in pad(format!("{ts}-{license}-8662").into_bytes()).chunks_exact(16) {
        let (mut a, mut b) = (word(block, 0), word(block, 8));
        for k in keys {
            b = k ^ a.wrapping_add(b.rotate_right(8));
            a = b ^ a.rotate_left(3);
        }
        out.extend(a.to_le_bytes());
        out.extend(b.to_le_bytes());
    }
    STANDARD.encode(out)
}
fn argus(query: &str, body: Option<&[u8]>, device: &str, ts: u64, nonce: &[u8; 11]) -> String {
    let random = u32::from_le_bytes(nonce[6..10].try_into().unwrap()) & 0x7fffffff;
    let mut nested = Proto::default();
    for n in 1..=3 {
        nested.n(n, 1);
    }
    nested.n(7, 3348294860);
    let mut env = Proto::default();
    env.s(1, "25053RT47C");
    env.s(2, "16");
    env.s(3, "update_64");
    env.n(4, 2147483648);
    let mut p = Proto::default();
    p.n(1, 0x40401252);
    p.n(2, 2);
    p.n(3, random as u64);
    p.s(4, "8662");
    p.s(5, device);
    p.s(6, "1611921764");
    p.s(7, "7.3.5.32");
    p.s(8, "v04.07.01-ml-android");
    p.n(9, 135135744);
    p.b(10, &[0; 8]);
    p.n(11, 0);
    p.n(12, ts * 2);
    p.b(13, &sm3(&body.map(md5).unwrap_or_else(|| vec![0; 16]))[..6]);
    p.b(14, &sm3(query.as_bytes())[..6]);
    p.b(15, &nested.0);
    p.b(16, &[]);
    p.s(20, "none");
    p.n(21, 738);
    p.b(23, &env.0);
    p.n(25, 2);
    let secret = hex("ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");
    let derived = sm3(&[secret.as_slice(), &hex("3ccc567b"), secret.as_slice()].concat());
    let mut keys = [0u64; 72];
    for (i, k) in keys[..4].iter_mut().enumerate() {
        *k = word(&derived, i * 8);
    }
    for i in 4..72 {
        let x = keys[i - 1].rotate_right(3) ^ keys[i - 3];
        keys[i] = !keys[i - 4]
            ^ x
            ^ x.rotate_right(1)
            ^ ((0x3dc94c3a046d678bu64 >> ((i - 4) % 62)) & 1)
            ^ 3;
    }
    let mut encrypted = Vec::new();
    for block in pad(p.0).chunks_exact(16) {
        let (mut a, mut b) = (word(block, 0), word(block, 8));
        for k in keys {
            let next = a ^ (b.rotate_left(1) & b.rotate_left(8)) ^ b.rotate_left(2) ^ k;
            a = b;
            b = next;
        }
        encrypted.extend(a.to_le_bytes());
        encrypted.extend(b.to_le_bytes());
    }
    let mask = hex("d04ffdffd04ffdff");
    for (i, b) in encrypted.iter_mut().enumerate() {
        *b ^= mask[i % 8];
    }
    let mut scrambled = [mask, encrypted].concat();
    scrambled.reverse();
    let container = [hex("a6e783ee7001100918"), scrambled, hex("567b")].concat();
    STANDARD.encode(
        [
            hex("3ccc"),
            encrypt(&container, &md5(&secret[..16]), &md5(&secret[16..])),
        ]
        .concat(),
    )
}
pub(crate) fn headers(
    query: &str,
    body: Option<&[u8]>,
    device: &str,
    now: u64,
    nonce: &[u8; 11],
) -> BTreeMap<String, String> {
    let ts = now / 1000;
    let mut input = [
        md5(query.as_bytes())[..4].to_vec(),
        body.map(|b| md5(b)[..4].to_vec()).unwrap_or(vec![0; 4]),
        vec![0; 4],
        hex("00060b1c"),
        (ts as u32).to_be_bytes().to_vec(),
    ]
    .concat();
    let key = hex("44b9b9d9a4aef9fca493aa757ca3c2c4a496938f");
    for (i, b) in input.iter_mut().enumerate() {
        *b ^= key[i];
    }
    for i in 0..input.len() {
        input[i] = (input[i].rotate_left(4) ^ input[(i + 1) % input.len()]).reverse_bits() ^ 0xeb;
    }
    let mut result = BTreeMap::from([
        (
            "x-gorgon".into(),
            hex_string(&[hex("8404401c0000"), input].concat()),
        ),
        ("x-khronos".into(), ts.to_string()),
        ("x-ss-req-ticket".into(), now.to_string()),
        ("x-argus".into(), argus(query, body, device, ts, nonce)),
        ("x-ladon".into(), ladon_for(ts, &nonce[2..6], "1611921764")),
    ]);
    if let Some(body) = body {
        result.insert("x-ss-stub".into(), hex_string(&md5(body)).to_uppercase());
    }
    result
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn matches_main_go_signature_vectors() {
        let cases: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/player-signing.json")).unwrap();
        for case in cases.as_array().unwrap() {
            let nonce = hex(case["nonce"].as_str().unwrap()).try_into().unwrap();
            let result = headers(
                case["query"].as_str().unwrap(),
                case["body"].as_str().map(str::as_bytes),
                case["deviceId"].as_str().unwrap(),
                case["now"].as_u64().unwrap(),
                &nonce,
            );
            for (key, value) in case["headers"].as_object().unwrap() {
                assert_eq!(
                    result[&key.to_lowercase()],
                    value.as_str().unwrap(),
                    "{key}"
                );
            }
        }
    }
    #[test]
    fn tt_encrypt_matches_main_go_vector() {
        let result = tt_encrypt(
            b"hello-tt-encrypt-payload",
            &std::array::from_fn(|i| i as u8),
        );
        assert_eq!(
            hex_string(&result),
            "746305100000000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f9c2938fa4a012faf0ecd9d31020e00f9748841c73838f76c10a9f9084bcfc717bc3cb6085a81a491f6e9b3d47a574b96bd818707ca911fc89bfad9a68edc174aa2f2dbc4ea60415c1e06b7525320a6451dbebd0a552028b5e70653d538c39842"
        );
    }
}
