// SPDX-License-Identifier: GPL-3.0-only
// Account and timed-comment signatures from main's Signer.kt.
use super::*;

fn sint(proto: &mut Proto, tag: u64, value: i64) {
    proto.n(tag, ((value << 1) ^ (value >> 63)) as u64);
}

fn gorgon(query: &str, body: &[u8], ts: u32, salt: u16, post: bool) -> String {
    let salt = salt.to_le_bytes();
    let body_prefix = if post { &body[..4] } else { &[0; 4] };
    let mut data = [
        &md5(query.as_bytes())[..4],
        body_prefix,
        &[0; 4],
        &67503104u32.to_le_bytes(),
        &ts.to_be_bytes(),
    ]
    .concat();
    let key = [0x4a, 0x40, 0x16, salt[1], 0x47, 0x6c, 1, salt[0]];
    let mut s: [u8; 256] = std::array::from_fn(|i| i as u8);
    let mut j = 0u8;
    for i in 0..256 {
        j = j.wrapping_add(s[i]).wrapping_add(key[i % 8]);
        s[i] = s[j as usize];
    }
    j = 0;
    for (n, byte) in data.iter_mut().enumerate() {
        let i = n + 1;
        j = j.wrapping_add(s[i]);
        let y = s[j as usize];
        s[i] = y;
        *byte ^= s[y.wrapping_add(y) as usize];
    }
    for i in 0..data.len() {
        data[i] = !((data[i].rotate_left(4) ^ data[(i + 1) % data.len()]).reverse_bits() ^ 20);
    }
    hex_string(&[&[0x84, 4, salt[0], salt[1], 0x40, 1][..], &data].concat())
}

fn device_proto(signer: &Signer, device_id: &str) -> Proto {
    let mut device = Proto::default();
    sint(&mut device, 1, 1);
    sint(&mut device, 2, 2);
    device.s(3, "8662");
    device.s(4, device_id);
    device.s(5, &signer.nonce);
    device.s(6, "!noperm!");
    for (tag, num) in [(7, -888888), (8, -888888), (9, 3), (10, -888888)] {
        sint(&mut device, tag, num);
    }
    device.s(11, "!notset!");
    device.s(12, "Asia/Shanghai,8");
    device.s(13, "zh_CN");
    sint(&mut device, 14, 4);
    for tag in 16..=21 {
        device.0.push(((tag << 3) | 5) as u8 | 128);
        device.0.push(1);
        device.0.extend([0; 4]);
    }
    device.s(22, "16");
    sint(&mut device, 23, 41);
    sint(&mut device, 24, 36);
    for tag in 25..=28 {
        sint(&mut device, tag, signer.started as i64);
    }
    sint(&mut device, 29, -1);
    for (tag, value) in [
        (30, "25053RT47C"),
        (31, "Redmi"),
        (32, "25053RT47C"),
        (33, "25053RT47C"),
        (34, "Xiaomi"),
        (35, "Redmi"),
        (36, "Redmi"),
    ] {
        device.s(tag, value);
    }
    sint(&mut device, 38, 31);
    device
}

const LICENSE: &str = "1588093228";

struct Context<'a> {
    query: &'a str,
    digest: &'a [u8],
    ts: u32,
    device: &'a str,
    version: &'a str,
}

fn medusa(signer: &Signer, context: Context<'_>, mut random: impl FnMut() -> u32) -> String {
    let Context {
        query,
        digest,
        ts,
        device,
        version,
    } = context;
    let query_sm3 = sm3(query.as_bytes());
    let f13_digest = f13(&query_sm3, digest, ts, false);
    let mut nested = Proto::default();
    for (tag, val) in [(1, 111), (2, 10), (3, 694367), (5, 586952199)] {
        sint(&mut nested, tag, val);
    }
    let message_random = random();
    let launch = ((u64::from(random()) * 21) >> 32) + 100;
    let pid = ((u64::from(random()) * 2000) >> 32) + 10001;
    let mut env = Proto::default();
    for (tag, val) in [(1, launch as i64), (2, 146331399), (3, 146331396), (5, 7)] {
        sint(&mut env, tag, val);
    }
    env.s(6, "v04.06.04.03-bugfix");
    sint(&mut env, 7, pid as i64);
    env.b(12, &device_proto(signer, device).0);
    let mut telemetry = Proto::default();
    sint(&mut telemetry, 1, ts.into());
    sint(&mut telemetry, 2, -2);
    sint(&mut telemetry, 4, 200);
    env.b(13, &telemetry.0);
    env.s(14, version);
    let query_hash = sm3(&[query.as_bytes(), digest, b"none"].concat());
    let flags = hex("4001000000000000");
    let magic = hex("f7e85ffad7d7dc3bd62ac87057cf6118");
    let mut message = Proto::default();
    message.b(1, &magic);
    sint(&mut message, 2, 3);
    sint(&mut message, 3, message_random.into());
    message.s(4, "8662");
    message.s(5, device);
    message.s(6, LICENSE);
    message.s(7, version);
    message.s(8, "v04.06.04-ml-android");
    sint(&mut message, 9, 67503104);
    message.b(10, &flags);
    sint(&mut message, 12, ts.into());
    message.b(13, &f13_digest);
    message.b(14, &query_sm3[..6]);
    message.b(15, &nested.0);
    message.s(16, &signer.nonce);
    sint(&mut message, 17, ts.into());
    message.b(19, &query_hash);
    message.s(20, "none");
    sint(&mut message, 21, 312);
    message.b(23, &env.0);
    message.s(24, r#"{"cmr":16777216,"cmr2":16777216,"un_h":1879194040,"vpn":0,"kd":0,"fkd":3672518972,"pd":-1872573247,"dyn":"","do":0,"tk":true}"#);
    let nonce = random();
    let key = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
    let hash = sm3(&[key.as_slice(), &nonce.to_le_bytes(), key.as_slice()].concat());
    let high = (nonce >> 16) & 255;
    let seed = (!(((high << 11) | (nonce >> 24)) ^ (high >> 5) ^ high)).to_le_bytes();
    let mut transformed = [flags, xmxor(&message.0, &hash)].concat();
    transformed.reverse();
    for (i, byte) in transformed.iter_mut().enumerate() {
        *byte ^= seed[!i & 3];
    }
    let check =
        0x18000001u32 | (u32::from(query_sm3[0] & 63) << 14) | (u32::from(f13_digest[0] & 63) << 8);
    let nonce_bytes = nonce.to_le_bytes();
    let packed = [
        &[0x35][..],
        &random().to_le_bytes(),
        &check.to_le_bytes(),
        &transformed,
        &nonce_bytes[2..],
    ]
    .concat();
    let encrypted = carrier(
        &packed,
        &hex("f1593376766ea98d34f31b057a9d5be4"),
        &hex("1fe109a4125283f418de9e051a969e12"),
        ts,
        false,
    );
    let mut prefix = [3u32.to_le_bytes().to_vec(), magic].concat();
    for (i, byte) in prefix.iter_mut().enumerate() {
        *byte ^= ts.to_le_bytes()[i & 3];
    }
    STANDARD.encode(
        [
            &prefix[..],
            &[nonce_bytes[0], nonce_bytes[1], 0, 1],
            &encrypted,
        ]
        .concat(),
    )
}

impl Signer {
    /// The legacy app profile includes ts for account/content routes and omits it for playback comments.
    pub(crate) fn sign_legacy(
        &self,
        mut values: BTreeMap<String, String>,
        body: Option<&[u8]>,
        now: u64,
        comment: bool,
    ) -> Result<(String, BTreeMap<String, String>)> {
        let ts = (now / 1000) as u32;
        let digest = body.map(md5).unwrap_or_else(|| vec![0; 16]);
        if !comment {
            values.insert("ts".into(), ts.to_string());
        }
        for offset in 0..1024 {
            let ticket = now + offset;
            values.insert("_rticket".into(), ticket.to_string());
            let query = url::form_urlencoded::Serializer::new(String::new())
                .extend_pairs(&values)
                .finish()
                .replace('+', "%20")
                .replace("%7E", "~");
            if (iv(&sm3(query.as_bytes()), &digest, ts) & 15) % 3 != 0 {
                continue;
            }
            let mut rng = rand::rngs::OsRng;
            let mut headers = BTreeMap::from([
                ("x-khronos".into(), ts.to_string()),
                ("x-ss-req-ticket".into(), ticket.to_string()),
                ("x-ladon".into(), STANDARD.encode(ts.to_be_bytes())),
                ("x-argus".into(), STANDARD.encode(ts.to_le_bytes())),
                (
                    "x-gorgon".into(),
                    gorgon(&query, &digest, ts, rng.next_u32() as u16, body.is_some()),
                ),
                (
                    "x-helios".into(),
                    ladon_for(ts.into(), &rng.next_u32().to_le_bytes(), LICENSE),
                ),
                (
                    "x-medusa".into(),
                    medusa(
                        self,
                        Context {
                            query: &query,
                            digest: &digest,
                            ts,
                            device: &values["device_id"],
                            version: &values["version_name"],
                        },
                        || rng.next_u32(),
                    ),
                ),
                ("sdk-version".into(), "2".into()),
                ("x-xs-from-web".into(), "0".into()),
            ]);
            if !comment {
                headers.insert("x-tt-request-tag".into(), "t=0;n=0".into());
                headers.insert("passport-sdk-version".into(), "50561".into());
                headers.insert("x-tt-dt".into(), "".into());
            }
            if body.is_some() {
                headers.insert("x-ss-stub".into(), hex_string(&digest).to_uppercase());
                if !comment {
                    headers.insert("x-vc-bdturing-sdk-version".into(), "3.7.2.cn".into());
                }
            }
            return Ok((query, headers));
        }
        bail!("无法生成请求签名")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_main_legacy_signature_vectors() {
        let rows: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/signing-legacy.json")).unwrap();
        for row in rows.as_array().unwrap() {
            let ts = row["ts"].as_u64().unwrap() as u32;
            let query = row["url"].as_str().unwrap().split_once('?').unwrap().1;
            let body = row["body"].as_str();
            let digest = body
                .map(|s| md5(s.as_bytes()))
                .unwrap_or_else(|| vec![0; 16]);
            let signer = Signer {
                started: u64::from(ts) * 1000,
                nonce: row["nonce"].as_str().unwrap().to_owned(),
            };
            let mut seed = 17u32;
            let mut random = || {
                seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                seed
            };
            assert_eq!(
                hex_string(&f13(&sm3(query.as_bytes()), &digest, ts, false)),
                row["hash"]
            );
            assert_eq!(
                gorgon(query, &digest, ts, 42131, body.is_some()),
                row["gorgon"]
            );
            assert_eq!(
                ladon_for(ts.into(), &random().to_le_bytes(), LICENSE),
                row["helios"]
            );
            assert_eq!(
                medusa(
                    &signer,
                    Context {
                        query,
                        digest: &digest,
                        ts,
                        device: "1905892595378490",
                        version: "7.1.3.32"
                    },
                    random
                ),
                row["medusa"]
            );
        }
    }
}
