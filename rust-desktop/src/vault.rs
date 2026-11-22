// SPDX-License-Identifier: GPL-3.0-only
use std::path::Path;

use anyhow::Result;

#[derive(Clone)]
pub(crate) struct Vault {
    #[cfg(target_os = "macos")]
    profile: String,
    #[cfg(target_os = "macos")]
    service: &'static str,
    #[cfg(target_os = "windows")]
    file: std::path::PathBuf,
}

impl Vault {
    pub(crate) fn new(directory: &Path) -> Self {
        Self::in_namespace(
            directory,
            "com.hongguotv.rust.web-account",
            "web-account.dpapi",
        )
    }

    fn in_namespace(directory: &Path, _service: &'static str, _file: &str) -> Self {
        #[cfg(target_os = "macos")]
        let profile = {
            use sha2::{Digest, Sha256};
            let canonical = directory
                .canonicalize()
                .unwrap_or_else(|_| directory.to_owned());
            format!(
                "{:x}",
                Sha256::digest(canonical.as_os_str().as_encoded_bytes())
            )
        };
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        let _ = directory;
        Self {
            #[cfg(target_os = "macos")]
            profile,
            #[cfg(target_os = "macos")]
            service: _service,
            #[cfg(target_os = "windows")]
            file: directory.join(_file),
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
pub(crate) struct AnonymousProfileVault(Vault);

#[cfg(any(target_os = "macos", target_os = "windows"))]
impl AnonymousProfileVault {
    pub(crate) fn new(directory: &Path) -> Self {
        Self(Vault::in_namespace(
            directory,
            "com.hongguotv.rust.anonymous-device",
            "anonymous-device.dpapi",
        ))
    }
}

#[cfg(any(target_os = "macos", target_os = "windows"))]
impl hongguo_playback::registered::DeviceProfileStore for AnonymousProfileVault {
    fn load(&self) -> Result<Option<Vec<u8>>> {
        self.0.load()
    }

    fn save(&self, profile: &[u8]) -> Result<()> {
        self.0.save(profile)
    }
}

#[cfg(target_os = "macos")]
impl Vault {
    const ITEM_NOT_FOUND: i32 = -25300;

    pub(crate) fn load(&self) -> Result<Option<Vec<u8>>> {
        match security_framework::passwords::get_generic_password(self.service, &self.profile) {
            Ok(bytes) => Ok(Some(bytes)),
            Err(error) if error.code() == Self::ITEM_NOT_FOUND => Ok(None),
            Err(_) => anyhow::bail!("无法读取钥匙串中的登录态"),
        }
    }

    pub(crate) fn save(&self, bytes: &[u8]) -> Result<()> {
        security_framework::passwords::set_generic_password(self.service, &self.profile, bytes)
            .map_err(|_| anyhow::anyhow!("无法将登录态保存到钥匙串"))
    }

    pub(crate) fn clear(&self) -> Result<()> {
        match security_framework::passwords::delete_generic_password(self.service, &self.profile) {
            Ok(()) => Ok(()),
            Err(error) if error.code() == Self::ITEM_NOT_FOUND => Ok(()),
            Err(_) => anyhow::bail!("无法从钥匙串删除登录态"),
        }
    }
}

#[cfg(target_os = "windows")]
impl Vault {
    pub(crate) fn load(&self) -> Result<Option<Vec<u8>>> {
        use anyhow::{Context, ensure};
        use std::io::Read;

        let file = match std::fs::File::open(&self.file) {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error).context("无法读取加密登录态"),
        };
        let mut sealed = Vec::new();
        file.take(DPAPI_LIMIT as u64 + 1).read_to_end(&mut sealed)?;
        ensure!(sealed.len() <= DPAPI_LIMIT, "加密登录态超过大小限制");
        Ok(Some(dpapi(&sealed, true)?))
    }

    pub(crate) fn save(&self, bytes: &[u8]) -> Result<()> {
        use anyhow::Context;
        use std::io::Write;

        let sealed = dpapi(bytes, false)?;
        let directory = self.file.parent().context("凭据资料目录无效")?;
        let mut file = tempfile::NamedTempFile::new_in(directory)?;
        file.write_all(&sealed)?;
        file.as_file().sync_all()?;
        file.persist(&self.file).context("无法保存加密登录态")?;
        Ok(())
    }

    pub(crate) fn clear(&self) -> Result<()> {
        use anyhow::Context;
        match std::fs::remove_file(&self.file) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(error).context("无法清除加密登录态"),
        }
    }
}

#[cfg(target_os = "windows")]
const DPAPI_LIMIT: usize = 64 * 1024;

#[cfg(target_os = "windows")]
fn dpapi(bytes: &[u8], decrypt: bool) -> Result<Vec<u8>> {
    use anyhow::ensure;
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };

    ensure!(
        !bytes.is_empty() && bytes.len() <= DPAPI_LIMIT,
        "登录态格式无效"
    );
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr().cast_mut(),
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: null_mut(),
    };
    // DPAPI borrows `input` during the call and allocates a separate buffer on success.
    let success = unsafe {
        if decrypt {
            CryptUnprotectData(
                &input,
                null_mut(),
                null(),
                null(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptProtectData(
                &input,
                null(),
                null(),
                null(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    ensure!(
        success != 0 && !output.pbData.is_null(),
        "Windows 无法保护或解锁登录态"
    );
    let length = output.cbData as usize;
    let result = if length <= DPAPI_LIMIT {
        // The system supplied `length` initialized bytes at this address.
        Ok(unsafe { std::slice::from_raw_parts(output.pbData, length) }.to_vec())
    } else {
        Err(anyhow::anyhow!("登录态超过大小限制"))
    };
    // The Win32 contract requires releasing the DPAPI allocation with LocalFree.
    unsafe { LocalFree(output.pbData.cast()) };
    result
}

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
impl Vault {
    pub(crate) fn load(&self) -> Result<Option<Vec<u8>>> {
        Ok(None)
    }
    pub(crate) fn save(&self, _: &[u8]) -> Result<()> {
        anyhow::bail!("当前平台没有系统凭据存储")
    }
    pub(crate) fn clear(&self) -> Result<()> {
        Ok(())
    }
}
