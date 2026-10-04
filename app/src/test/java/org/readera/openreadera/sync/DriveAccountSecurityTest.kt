package org.readera.openreadera.sync

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DriveAccountSecurityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearIdentity() {
        DriveAccountGuard.invalidate()
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun accountChangeDropsTokenAndFolderAndFailureCannotReuseOldAuthorization() = runBlocking<Unit> {
        val auth = GoogleAuthManager(context) { email -> if (email == "a@example.com") "ya29.account-a" else error("Authorization denied") }
        auth.saveAccountByEmail("a@example.com")
        assertEquals("ya29.account-a", auth.fetchOAuthToken("a@example.com").getOrThrow())
        assertEquals("ya29.account-a", auth.saveAccountByEmail("a@example.com").accessToken)
        val destination = context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE)
        destination.edit().putString("target_folder_id", "folder-a").putString("target_folder_account", "a@example.com").apply()
        val session = DriveAccountGuard.capture()
        val pendingSync = Job()
        session.attach(pendingSync)
        auth.saveAccountByEmail(" B@example.com ")
        assertNull(auth.getUserProfile()!!.accessToken)
        assertNull(destination.getString("target_folder_id", null))
        assertTrue(pendingSync.isCancelled)
        assertTrue(auth.fetchOAuthToken("b@example.com").isFailure)
        assertNull(auth.getUserProfile()!!.accessToken)
        var committed = false
        assertThrows(CancellationException::class.java) { session.commit { committed = true } }
        assertFalse(committed)
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; error("Must not connect") }.build()
        assertThrows(CancellationException::class.java) { session.execute(client, Request.Builder().url("https://www.googleapis.com/drive/v3/files").build()) }
        assertEquals(0, requests)
        assertThrows(IllegalArgumentException::class.java) { auth.saveAccountByEmail("a@example.com", token = "ya29.account-a") }
        assertEquals("b@example.com", auth.getUserProfile()!!.email)
    }

    @Test fun authorizationCompletingAfterSelectionChangeCannotCommitItsToken() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val auth = GoogleAuthManager(context) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "ya29.old-account" }
        auth.saveAccountByEmail("a@example.com")
        val pending = async(Dispatchers.IO) { auth.fetchOAuthToken("a@example.com") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            auth.saveAccountByEmail("b@example.com")
        } finally { release.countDown() }
        try { pending.await(); fail("Old authorization must be cancelled") } catch (_: CancellationException) {}
        assertEquals("b@example.com", auth.getUserProfile()!!.email)
        assertNull(auth.getUserProfile()!!.accessToken)
    }

    @Test fun unboundLegacyTokenIsNotTrusted() {
        context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("is_signed_in", true).putString("google_email", "b@example.com")
            .putString("google_token", "ya29.legacy-a").apply()
        assertNull(GoogleAuthManager(context).getUserProfile()!!.accessToken)
    }

    @Test fun selectionWaitsForCancelledLocalTransactionToExitBeforeChangingIdentity() = runBlocking<Unit> {
        val auth = GoogleAuthManager(context) { "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val session = DriveAccountGuard.capture()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var finished = false
        val transaction = async(Dispatchers.IO) {
            session.transaction {
                entered.complete(Unit)
                try { kotlinx.coroutines.awaitCancellation() } finally { finished = true }
            }
        }
        session.attach(transaction)
        entered.await()
        val changed = async(Dispatchers.IO) { auth.saveAccountByEmail("b@example.com") }
        changed.await()
        assertTrue(finished)
        assertTrue(transaction.isCancelled)
        assertEquals("b@example.com", auth.getUserProfile()!!.email)
        assertThrows(CancellationException::class.java) { session.commit { fail("Old transaction cannot commit") } }
    }

    @Test fun aCancelledInFlightRequestCannotReturnAnOldAccountResponse() = runBlocking<Unit> {
        val auth = GoogleAuthManager(context) { "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val session = DriveAccountGuard.capture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(okhttp3.ResponseBody.create(null, "old account state")).build()
        }.build()
        val request = async(Dispatchers.IO) {
            runCatching {
                session.execute(client, Request.Builder().url("https://www.googleapis.com/drive/v3/files").build()).use { it.code }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            auth.saveAccountByEmail("b@example.com")
        } finally { release.countDown() }
        val failure = request.await().exceptionOrNull()
        assertTrue(failure is CancellationException || failure is java.io.IOException)
        assertEquals("b@example.com", auth.getUserProfile()!!.email)
    }
    @Test fun concurrentTransitionsRejectIntermediateCapturesAndCancelIntermediateWork() = runBlocking<Unit> {
        val auth = GoogleAuthManager(context) { "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val oldSession = DriveAccountGuard.capture()
        val transactionEntered = CompletableDeferred<Unit>()
        val releaseTransaction = CountDownLatch(1)
        val transaction = async(Dispatchers.IO) {
            oldSession.transaction {
                transactionEntered.complete(Unit)
                releaseTransaction.await()
            }
        }
        oldSession.attach(transaction)
        transactionEntered.await()
        val prefs = context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE)
        val failure = AtomicReference<Throwable?>()
        val intermediate = AtomicReference<DriveAccountGuard.Session?>()
        val intermediateJob = Job()
        val first = Thread {
            try {
                DriveAccountGuard.updateIdentity { session ->
                    prefs.edit().putString("google_email", "b@example.com").apply()
                    intermediate.set(session)
                    session.attach(intermediateJob)
                    assertThrows(CancellationException::class.java) { DriveAccountGuard.capture() }
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val second = Thread {
            try {
                DriveAccountGuard.updateIdentity {
                    prefs.edit().putString("google_email", "c@example.com").apply()
                }
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            first.start()
            awaitThreadState(first, Thread.State.WAITING)
            second.start()
            awaitThreadState(second, Thread.State.BLOCKED)
            assertThrows(CancellationException::class.java) { DriveAccountGuard.capture() }
        } finally {
            releaseTransaction.countDown()
            first.join(5000)
            if (second.state != Thread.State.NEW) second.join(5000)
        }
        try { transaction.await(); fail("Old transaction must be cancelled") } catch (_: CancellationException) {}
        assertTrue(transaction.isCancelled)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        failure.get()?.let { throw it }
        assertEquals("c@example.com", auth.getUserProfile()!!.email)
        assertTrue(intermediateJob.isCancelled)
        val stale = checkNotNull(intermediate.get())
        assertThrows(CancellationException::class.java) { stale.commit { fail("Intermediate identity cannot publish") } }
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; error("Must not connect") }.build()
        assertThrows(CancellationException::class.java) {
            stale.execute(client, Request.Builder().url("https://www.googleapis.com/drive/v3/files").build())
        }
        assertEquals(0, requests)
        DriveAccountGuard.capture().checkCurrent()
    }

    @Test fun cancelledAuthorizationCannotPersistTokenWithoutAnyIdentityChange() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val auth = GoogleAuthManager(context) {
            entered.countDown()
            release.await()
            "ya29.cancelled"
        }
        auth.saveAccountByEmail("a@example.com")
        val session = DriveAccountGuard.capture()
        val authorization = async(Dispatchers.IO) { auth.fetchOAuthToken("a@example.com") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            authorization.cancel()
        } finally { release.countDown() }
        try { authorization.await(); fail("Authorization must remain cancelled") } catch (_: CancellationException) {}
        authorization.join()
        session.checkCurrent()
        assertEquals("a@example.com", auth.getUserProfile()!!.email)
        assertNull(auth.getUserProfile()!!.accessToken)
        assertNull(context.getSharedPreferences("google_sync_prefs", Context.MODE_PRIVATE)
            .getString("google_token_account", null))
    }

    @Test fun lateSelectorResultCannotRestoreAccountAfterAnotherSelection() = runBlocking<Unit> {
        var authorizations = 0
        val auth = GoogleAuthManager(context) { authorizations++; "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val launchSession = auth.beginAccountSelection()
        auth.saveAccountByEmail("b@example.com")
        val account = GoogleSignInAccount.fromAccount(android.accounts.Account("a@example.com", "com.google"))
        try {
            auth.handleSignInAccount(account, launchSession)
            fail("Late Google selector result must be cancelled")
        } catch (_: CancellationException) {}
        assertThrows(CancellationException::class.java) {
            auth.selectAccountByEmail("a@example.com", launchSession = launchSession)
        }
        assertEquals("b@example.com", auth.getUserProfile()!!.email)
        assertEquals(0, authorizations)
    }

    @Test fun lateSelectorResultCannotRestoreAccountAfterSignOut() = runBlocking<Unit> {
        var authorizations = 0
        val auth = GoogleAuthManager(context, signOutRequest = { }) { authorizations++; "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val launchSession = auth.beginAccountSelection()
        auth.signOut()
        val account = GoogleSignInAccount.fromAccount(android.accounts.Account("a@example.com", "com.google"))
        try {
            auth.handleSignInAccount(account, launchSession)
            fail("Signed-out Google selector result must be cancelled")
        } catch (_: CancellationException) {}
        assertThrows(CancellationException::class.java) {
            auth.selectAccountByEmail("a@example.com", launchSession = launchSession)
        }
        assertNull(auth.getUserProfile())
        assertEquals(0, authorizations)
    }

    @Test fun identityChangeDoesNotWaitForBlockedProviderWriteOrClose() = runBlocking<Unit> {
        val auth = GoogleAuthManager(context) { "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val session = DriveAccountGuard.capture()
        val entered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) {
                writes++
                entered.countDown()
                release.await()
            }
            override fun close() {
                closeEntered.countDown()
                release.await()
            }
        }
        val writing = async(Dispatchers.IO) {
            runCatching {
                session.withOutput(output, currentCoroutineContext()[Job]) { out ->
                    out.write(1)
                    out.write(2)
                }
            }
        }
        val changed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val selection = Thread {
            try { auth.saveAccountByEmail("b@example.com") }
            catch (error: Throwable) { failure.set(error) }
            finally { changed.countDown() }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            selection.start()
            assertTrue("Selection must finish before the provider write is released", changed.await(5, TimeUnit.SECONDS))
            assertTrue("Identity change must request stream cancellation", closeEntered.await(5, TimeUnit.SECONDS))
            assertEquals("b@example.com", auth.getUserProfile()!!.email)
        } finally {
            release.countDown()
            if (selection.state != Thread.State.NEW) selection.join(5000)
        }
        failure.get()?.let { throw it }
        assertTrue(writing.await().exceptionOrNull() is CancellationException)
        assertEquals(1, writes)
    }

    @Test fun signOutUnlinksSafBeforeCallbackAndCallbackCannotInvalidateNewSelection() {
        val callback = AtomicReference<(() -> Unit)?>()
        val auth = GoogleAuthManager(context, signOutRequest = { callback.set(it) }) { "ya29.token" }
        auth.saveAccountByEmail("a@example.com")
        val destination = context.getSharedPreferences("drive_sync_data", Context.MODE_PRIVATE)
        destination.edit().putString("saf_folder_uri", "content://example/tree/old")
            .putString("saf_folder_name", "Old account")
            .putString("target_folder_id", "old-folder")
            .putString("target_folder_account", "a@example.com")
            .putLong("last_sync_timestamp", 123L).apply()
        val manager = GoogleDriveSyncManager(context)
        val oldSession = DriveAccountGuard.capture()
        var callbackCompleted = false
        auth.signOut { callbackCompleted = true }
        assertFalse(callbackCompleted)
        assertNotNull(callback.get())
        assertNull(auth.getUserProfile())
        assertNull(manager.getSafFolderUri())
        assertNull(manager.getSafFolderName())
        assertFalse(manager.isDriveConnected())
        assertNull(destination.getString("target_folder_id", null))
        assertNull(destination.getString("target_folder_account", null))
        assertEquals(0L, manager.getLastSyncTime())
        assertThrows(CancellationException::class.java) { oldSession.checkCurrent() }
        DriveAccountGuard.capture().commit { assertNull(destination.getString("saf_folder_uri", null)) }

        auth.saveAccountByEmail("b@example.com")
        val newSession = DriveAccountGuard.capture()
        val newSync = Job()
        newSession.attach(newSync)
        try {
            checkNotNull(callback.get()).invoke()
            assertTrue(callbackCompleted)
            assertEquals("b@example.com", auth.getUserProfile()!!.email)
            newSession.checkCurrent()
            assertFalse(newSync.isCancelled)
        } finally { newSession.detach(newSync); newSync.cancel() }
    }

    @Test fun validSelectorAuthorizesOnlyItsSelectedGeneration() = runBlocking<Unit> {
        val requested = mutableListOf<String>()
        val auth = GoogleAuthManager(context) { email -> requested.add(email); "ya29.selected" }
        auth.saveAccountByEmail("a@example.com")
        val launchSession = auth.beginAccountSelection()
        val account = GoogleSignInAccount.fromAccount(android.accounts.Account("b@example.com", "com.google"))
        val profile = auth.handleSignInAccount(account, launchSession).getOrThrow()
        assertEquals(listOf("b@example.com"), requested)
        assertEquals("b@example.com", profile.email)
        assertEquals("ya29.selected", profile.accessToken)
        assertEquals("ya29.selected", auth.getUserProfile()!!.accessToken)
        assertThrows(CancellationException::class.java) { launchSession.checkCurrent() }
    }

    @Test fun cancelledWriterCannotWriteAnotherBlockEvenWithoutIdentityChange() {
        val session = DriveAccountGuard.capture()
        val job = Job()
        var writes = 0
        val output = object : OutputStream() {
            override fun write(value: Int) { error("Use the block write") }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                assertEquals(64 * 1024, length)
                writes++
                job.cancel()
            }
        }
        assertThrows(CancellationException::class.java) {
            session.withOutput(output, job) { it.write(ByteArray(128 * 1024)) }
        }
        assertEquals(1, writes)
        session.checkCurrent()
    }

    private fun awaitThreadState(thread: Thread, state: Thread.State) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != state && thread.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Thread did not reach the expected synchronization barrier", state, thread.state)
    }
}
