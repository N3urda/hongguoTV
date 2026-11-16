package com.hongguotv.desktop

import com.hongguotv.core.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class DesktopAccountTest {
    private val series = Series("123456", "测试剧")
    private class MemoryStore : CredentialStore {
        var value: AccountCredentials? = null
        override fun load() = value
        override fun save(value: AccountCredentials) { this.value = value }
        override fun clear() { value = null }
    }
    private open inner class Gateway : AccountGateway {
        var shelf = emptyList<CloudFavorite>()
        var writes = 0
        var ambiguous = false
        override fun profile(account: AccountCredentials) = CloudProfile("user", "测试账号")
        override fun favorites(account: AccountCredentials) = shelf
        override fun history(account: AccountCredentials) = emptyList<CloudProgress>()
        override fun reservations(account: AccountCredentials, online: Boolean) = emptyList<CloudReservation>()
        override fun setFavorite(account: AccountCredentials, id: String, add: Boolean) {
            writes++; shelf = if (add) listOf(CloudFavorite(series, 1)) else emptyList()
            if (ambiguous) error("connection lost after server applied write")
        }
        override fun reserve(account: AccountCredentials, work: RelatedWork) {}
        override fun upload(account: AccountCredentials, progress: CloudProgress, count: Int) {}
        override fun registerDevice(): DeviceRegister.Registered? = null
    }
    private suspend fun awaitIdle(account: DesktopAccount) { withTimeout(5000) { while (account.busy) delay(10) } }

    @Test fun ambiguousFavoriteWriteIsReadBackWithoutResubmitting() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = Gateway().apply { ambiguous = true }
            val account = DesktopAccount(scope, gateway, MemoryStore())
            account.accept(AccountCredentials("test-only"))
            account.toggle(series); awaitIdle(account)
            assertEquals(1, gateway.writes)
            assertEquals(series, account.favorites.single().series)
            assertEquals("云收藏已确认", account.status)
        } finally { scope.cancel() }
    }
    @Test fun lateProfileCannotRestoreLoggedOutAccount() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gateway = object : Gateway() {
                override fun profile(account: AccountCredentials): CloudProfile {
                    entered.countDown(); release.await(5, TimeUnit.SECONDS)
                    return super.profile(account)
                }
            }
            val account = DesktopAccount(scope, gateway, MemoryStore())
            val login = launch { account.accept(AccountCredentials("test-only")) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            account.logout(); release.countDown(); login.join(); awaitIdle(account)
            assertNull(account.profile); assertTrue(account.favorites.isEmpty())
        } finally { release.countDown(); scope.cancel() }
    }
    @Test fun rememberedSessionRestoresAndLogoutDeletesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = MemoryStore().apply { value = AccountCredentials("test-only") }
            val account = DesktopAccount(scope, Gateway(), store)
            account.restore(); awaitIdle(account)
            assertEquals("测试账号", account.profile?.nickname)
            account.logout(); awaitIdle(account)
            assertNull(store.value); assertNull(account.profile)
        } finally { scope.cancel() }
    }
    @Test fun closingApplicationPreservesRememberedSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = MemoryStore().apply { value = AccountCredentials("test-only") }
            val account = DesktopAccount(scope, Gateway(), store)
            account.restore(); awaitIdle(account)
            account.logout(clearStored = false)
            assertNotNull(store.value); assertNull(account.profile)
        } finally { scope.cancel() }
    }
}
