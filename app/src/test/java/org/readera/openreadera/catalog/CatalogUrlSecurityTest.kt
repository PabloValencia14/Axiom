package org.readera.openreadera.catalog

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.ArrayDeque
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUrlSecurityTest {
    @Test
    fun publicHttpsUrlsAreAllowedAndUnsafeUrlFormsAreRejected() {
        assertEquals(
            "https://catalog.example/books/item.epub",
            CatalogUrlSecurity.checkedUrl("https://catalog.example/books/item.epub").toString()
        )

        listOf(
            "http://catalog.example/book.epub",
            "file:///etc/passwd",
            "https://user:password@catalog.example/book.epub",
            "https://catalog.example/book.epub#fragment",
            "https://localhost/book.epub",
            "https://reader.localhost/book.epub",
            "https://127.0.0.1/book.epub",
            "https://192.168.1.5/book.epub",
            "https://[::1]/book.epub"
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) {
                CatalogUrlSecurity.checkedUrl(url)
            }
        }
    }

    @Test
    fun publicAddressPolicyRejectsPrivateReservedAndNonUnicastAddresses() {
        listOf("8.8.8.8", "2001:4860:4860::8888").forEach { address ->
            assertTrue(address, CatalogUrlSecurity.isPublicAddress(InetAddress.getByName(address)))
        }
        listOf(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.2.1",
            "192.168.0.1",
            "198.19.0.1",
            "224.0.0.1",
            "240.0.0.1",
            "::",
            "::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
            "2001:db8::1",
            "2002:0808:0808::1"
        ).forEach { address ->
            assertFalse(address, CatalogUrlSecurity.isPublicAddress(InetAddress.getByName(address)))
        }
    }

    @Test
    fun dnsRejectsAResolutionContainingAnyNonPublicAddress() {
        val mixedDns = PublicCatalogDns(dnsOf("8.8.8.8", "10.0.0.1"))
        val privateDns = PublicCatalogDns(dnsOf("192.168.1.5"))
        val publicDns = PublicCatalogDns(dnsOf("8.8.8.8"))

        assertThrows(UnknownHostException::class.java) { mixedDns.lookup("catalog.example") }
        assertThrows(UnknownHostException::class.java) { privateDns.lookup("catalog.example") }
        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), publicDns.lookup("catalog.example"))
    }

    @Test
    fun requestExecutorChecksEveryRedirectAndDropsCredentialsAcrossHosts() {
        val responder = QueuedResponses(
            ResponseSpec(302, "../books/item.epub"),
            ResponseSpec(302, "https://cdn.example/item.epub"),
            ResponseSpec(200, null)
        )
        val executor = CatalogRequestExecutor(OkHttpClient.Builder().addInterceptor(responder).build())
        val request = Request.Builder()
            .url("https://catalog.example/path/search")
            .header("Authorization", "Bearer secret")
            .header("Cookie", "session=secret")
            .header("Referer", "https://catalog.example/path/search?token=secret")
            .header("Origin", "https://catalog.example")
            .build()

        executor.execute(request).use { assertEquals(200, it.code) }

        assertEquals(
            listOf(
                "https://catalog.example/path/search",
                "https://catalog.example/books/item.epub",
                "https://cdn.example/item.epub"
            ),
            responder.requests.map { it.url.toString() }
        )
        assertEquals("Bearer secret", responder.requests[1].header("Authorization"))
        assertEquals("session=secret", responder.requests[1].header("Cookie"))
        assertEquals("https://catalog.example/path/search?token=secret", responder.requests[1].header("Referer"))
        assertEquals("https://catalog.example", responder.requests[1].header("Origin"))
        assertNull(responder.requests[2].header("Authorization"))
        assertNull(responder.requests[2].header("Cookie"))
        assertNull(responder.requests[2].header("Referer"))
        assertNull(responder.requests[2].header("Origin"))
    }

    @Test
    fun privateAndDowngradeRedirectsAreRejectedBeforeTheNextRequest() {
        listOf("https://127.0.0.1/admin", "http://public.example/book.epub").forEach { target ->
            val responder = QueuedResponses(ResponseSpec(302, target))
            val executor = CatalogRequestExecutor(OkHttpClient.Builder().addInterceptor(responder).build())
            val request = Request.Builder().url("https://catalog.example/start").build()

            assertThrows(IOException::class.java) { executor.execute(request) }
            assertEquals(target, 1, responder.requests.size)
        }
    }

    @Test
    fun requestExecutorStopsAfterFiveRedirectHops() {
        val responder = QueuedResponses(*Array(6) { ResponseSpec(302, "/next") })
        val executor = CatalogRequestExecutor(OkHttpClient.Builder().addInterceptor(responder).build())
        val request = Request.Builder().url("https://catalog.example/start").build()

        assertThrows(IOException::class.java) { executor.execute(request) }
        assertEquals(6, responder.requests.size)
    }

    private fun dnsOf(vararg answer: String): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            answer.map { InetAddress.getByName(it) }
    }

    private data class ResponseSpec(val code: Int, val location: String?)

    private class QueuedResponses(vararg specs: ResponseSpec) : Interceptor {
        private val responses = ArrayDeque(specs.toList())
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val spec = responses.removeFirst()
            val headers = Headers.Builder().apply {
                spec.location?.let { add("Location", it) }
            }.build()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(spec.code)
                .message(if (spec.code in 300..399) "Redirect" else "OK")
                .headers(headers)
                .body(ByteArray(0).toResponseBody("text/plain".toMediaType()))
                .build()
        }
    }
}
