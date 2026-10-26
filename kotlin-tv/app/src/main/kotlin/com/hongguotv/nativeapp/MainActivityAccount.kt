// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.AccountCredentials
import com.hongguotv.core.AccountCredentialsParser

/**
 * 设置页「账号参数」的解析与文案（移植自 pan8664716/HGTV 1.0.8）。
 *
 * 解析规则（与 [AccountCredentialsParser.sessionIdOf] 的容错口径一致）：
 * - 文本里有 `<string name=` → 当 `cookieStore.xml` 整份解析；
 * - 有 `=` → 当整段 Cookie 串；
 * - 否则报「没认出」。真正取值全在 core 的 parser 里，这里只分发。
 */
internal fun MainActivity.parseAccountPayload(text: String): AccountCredentials? {
    val body=text.trim()
    if(body.isEmpty()) return null
    return when {
        // 先试 cookieStore.xml（可能整份文件，也可能只粘了几行 <string>）。
        body.contains("<string name=") && AccountCredentialsParser.parse(body).isNotEmpty() ->
            AccountCredentialsParser.parseCredentials(body)
        // 整段 Cookie 串；里面至少要有 sessionid 才算登录态。
        body.contains('=') && AccountCredentialsParser.parseHeader(body).configured &&
            AccountCredentialsParser.parseHeader(body).cookie.contains("sessionid=") ->
            AccountCredentialsParser.parseHeader(body)
        else -> null
    }?.takeIf { it.configured && it.cookie.contains("sessionid=") }
}

/** 设置行文案：未导入 / 已导入（剩余天数）。 */
internal fun MainActivity.accountLabel(): String {
    val cookie=library.accountCookie
    if(cookie.isBlank()) return "账号参数  ·  未导入（匿名搜索）"
    val days=if(library.accountExpiresAt<=0) null else (library.accountExpiresAt*1000-System.currentTimeMillis())/86_400_000.0
    return when {
        days==null -> "账号参数  ·  已导入（有效期未知）"
        days<0 -> "账号参数  ·  已过期（请重新导入）"
        else -> "账号参数  ·  已导入（约剩 %.0f 天）".format(days)
    }
}
