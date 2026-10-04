package org.readera.openreadera.catalog

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LibGenBoundaryTest {
    private fun libgen(client: OkHttpClient) = LibGenClient(CatalogDownloader(RuntimeEnvironment.getApplication()), client)

    @Test fun privateAndFragmentIntermediateUrlsAreRejectedBeforeAnyRequest() {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; error("Must not connect") }.build()
        val api = libgen(client)
        listOf("https://127.0.0.1/ads.php", "https://public.example/page#ads.php", "http://public.example/ads.php").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { api.intermediateDownloadUrl(url) }
        }
        assertEquals(0, requests)
    }

    @Test fun intermediateRedirectCannotReachPrivateDestinationAndPublicRelativeGetLinkWorks() {
        val urls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            urls.add(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "https://192.168.1.1/ads.php")
                .body("".toResponseBody("text/html".toMediaType())).build()
        }.build()
        assertThrows(java.io.IOException::class.java) { libgen(client).intermediateDownloadUrl("https://library.lol/ads.php") }
        assertEquals(listOf("https://library.lol/ads.php"), urls)
        val ordinary = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<a href='/books/ordinary.epub'>GET</a>".toResponseBody("text/html".toMediaType())).build()
        }.build()
        assertEquals("https://library.lol/books/ordinary.epub", libgen(ordinary).intermediateDownloadUrl("https://library.lol/ads.php"))
    }

    @Test fun searchAndIntermediateRequestsUseGuardedDnsWithoutNetwork() = runBlocking {
        var lookups = 0
        var connections = 0
        val client = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups++
                return listOf(InetAddress.getByName("10.0.0.1"))
            }
        }).eventListener(object : okhttp3.EventListener() {
            override fun connectStart(call: okhttp3.Call, address: java.net.InetSocketAddress, proxy: java.net.Proxy) {
                connections++
                error("No socket may be opened")
            }
        }).build()
        val api = libgen(client)
        assertThrows(UnknownHostException::class.java) { api.intermediateDownloadUrl("https://library.lol/ads.php") }
        assertTrue(api.search("ordinary book").isEmpty())
        assertEquals(4, lookups)
        assertEquals(0, connections)
    }

    @Test fun ordinarySearchReturnsBooksThroughTheGuardedExecutor() = runBlocking {
        var requests = 0
        val html = "<table class='c'><tbody><tr><td>1</td><td>Ordinary Author</td>" +
            "<td><a>Ordinary Title</a></td><td>Publisher</td><td>2026</td><td>10</td>" +
            "<td>English</td><td>1 MB</td><td>epub</td><td><a href='https://library.lol/ads.php'>Mirror</a></td></tr></tbody></table>"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val books = libgen(client).search("Ordinary Title")
        assertEquals(1, requests)
        assertEquals("Ordinary Title", books.single().title)
        assertEquals("https://library.lol/ads.php", books.single().downloadUrl)
    }

    @Test fun oversizedIntermediatePageIsRejectedBeforeParsing() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("x".repeat(4 * 1024 * 1024 + 1).toResponseBody("text/html".toMediaType())).build()
        }.build()
        assertThrows(IllegalArgumentException::class.java) { libgen(client).intermediateDownloadUrl("https://library.lol/ads.php") }
    }
}
