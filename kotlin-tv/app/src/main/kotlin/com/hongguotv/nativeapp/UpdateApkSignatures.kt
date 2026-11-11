// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException

/** 同时请求新旧签名字段，兼容旧系统解析 APK 文件时的证书收集行为。 */
@Suppress("DEPRECATION")
internal object UpdateApkSignatures {
    fun flags(): Int = PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private fun signers(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        // 部分电视系统只填充旧签名字段。新字段非空但签名不同时，
        // 不得用旧字段中的签名历史替代当前签名并放行。
        val certificates = modern?.takeIf { it.isNotEmpty() } ?: info.signatures
        return certificates?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    fun verify(installed: PackageInfo, candidate: PackageInfo) {
        val current = signers(installed)
        val downloaded = signers(candidate)
        if (current.isEmpty() || downloaded.isEmpty())
            throw IOException("无法读取安装包签名，已阻止安装；可从官方发布页下载并手动覆盖安装")
        if (current != downloaded) throw IOException("新版签名不匹配，已阻止安装")
    }
}
