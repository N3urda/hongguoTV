// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.desktop

import com.hongguotv.core.AccountCredentials
import java.nio.file.*
import java.util.concurrent.TimeUnit

/** OS-bound credential storage. Never falls back to plaintext. */
interface CredentialStore {
    fun load(): AccountCredentials?
    fun save(value: AccountCredentials)
    fun clear()
}
class DesktopCredentialStore(private val directory: Path = DesktopLibrary.defaultFile().parent) : CredentialStore {
    private val mac = System.getProperty("os.name").startsWith("Mac")
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val file = directory.resolve("account.dpapi")
    private fun execute(arguments: List<String>, input: String? = null): Pair<Int, String> {
        val process = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.bufferedWriter().use { if (input != null) it.write(input) }
        // Output is bounded by our payload (credentials < 32 KiB); consume concurrently with timeout.
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "credential-output").apply { isDaemon = true } }
        try {
            val result = reader.submit<String> { process.inputStream.bufferedReader().use { it.readText().also { value -> require(value.length < 65536) } } }
            if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); error("系统凭据存储超时") }
            return process.exitValue() to result.get(2, TimeUnit.SECONDS).trim()
        } finally { reader.shutdownNow(); if (process.isAlive) process.destroyForcibly() }
    }
    private fun powershell(operation: String, input: String): String {
        val script = "Add-Type -AssemblyName System.Security; \$ErrorActionPreference='Stop'; \$s=[Console]::In.ReadToEnd(); $operation"
        val (code, result) = execute(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), input)
        check(code == 0) { "系统凭据存储不可用" }
        return result
    }
    @Synchronized override fun load(): AccountCredentials? {
        val raw = when {
            mac -> {
                val (code, value) = execute(listOf("/usr/bin/security", "find-generic-password", "-a", "desktop", "-s", "com.hongguotv.desktop.account", "-w"))
                if (code == 44) return null
                check(code == 0) { "无法读取钥匙串" }; value
            }
            windows -> {
                if (!Files.exists(file)) return null
                require(Files.size(file) < 65536)
                powershell("[Text.Encoding]::UTF8.GetString([Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String(\$s),\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))", Files.readString(file))
            }
            else -> return null
        }
        return AccountCredentials.fromJson(raw)
    }
    @Synchronized override fun save(value: AccountCredentials) {
        val raw = value.toJson(); require(raw.length < 32768)
        when {
            mac -> {
                // security interactive mode keeps the credential out of process arguments.
                val quoted = raw.replace("\\", "\\\\").replace("\"", "\\\"")
                require(!quoted.contains('\n') && !quoted.contains('\r'))
                val (code, _) = execute(listOf("/usr/bin/security", "-i"), "add-generic-password -U -a desktop -s com.hongguotv.desktop.account -w \"$quoted\"\n")
                check(code == 0) { "无法保存到钥匙串" }
                check(load()?.toJson() == raw) { "钥匙串保存未确认" }
            }
            windows -> {
                val sealed = powershell("[Convert]::ToBase64String([Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes(\$s),\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))", raw)
                Files.createDirectories(directory)
                val temp = Files.createTempFile(directory, "account-", ".tmp")
                try { Files.writeString(temp, sealed); Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) } finally { Files.deleteIfExists(temp) }
            }
            else -> error("当前系统不支持安全保存登录态")
        }
    }
    @Synchronized override fun clear() {
        if (mac) {
            val (code, _) = execute(listOf("/usr/bin/security", "delete-generic-password", "-a", "desktop", "-s", "com.hongguotv.desktop.account"))
            check(code == 0 || code == 44) { "钥匙串登录态清理失败" }
        } else if (windows) Files.deleteIfExists(file)
    }
}
