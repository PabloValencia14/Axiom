package org.readera.openreadera.translation

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class GoogleTranslateServiceTest {
    @Test fun hugeParagraphIsBoundedWithoutTruncationEmptyRequestsOrBrokenSurrogates() {
        val text = "a".repeat(2499) + "\uD83D\uDE00" + "b".repeat(7000) + "\n\n" + "end"
        val chunks = translationRequestChunks(text).toList()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 2500 })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        assertTrue(translationRequestChunks("").toList().isEmpty())
    }

    @Test fun paragraphBoundariesStayIntactAndNoEmptyLeadingRequestIsGenerated() {
        val text = "first\n\n" + "long paragraph ".repeat(500) + "\n\nlast"
        val chunks = translationRequestChunks(text).toList()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 2500 })
        assertEquals(listOf("ordinary paragraph"), translationRequestChunks("ordinary paragraph").toList())
    }

    @Test fun cancellationCancelsActualEnqueuedCallWithoutNetwork() = runTest {
        val call = PendingCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { GoogleTranslateService.awaitTranslation(call) }
        assertTrue(call.enqueued)
        result.cancel()
        result.join()
        assertTrue(call.cancelled)
        assertTrue(result.isCancelled)
        // A late transport failure must not turn cancelled work into a successful export.
        call.callback!!.onFailure(call, IOException("Socket closed"))
        assertTrue(result.isCancelled)
    }

    private class PendingCall : Call {
        var enqueued = false
        var cancelled = false
        var callback: Callback? = null
        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
        override fun execute(): Response = error("Tests must not perform synchronous HTTP")
        override fun enqueue(responseCallback: Callback) { enqueued = true; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = enqueued
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = PendingCall()
    }
}
