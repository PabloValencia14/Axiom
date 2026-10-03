package org.readera.openreadera.catalog

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** URL, DNS, and redirect checks shared by catalog download requests. */
internal object CatalogUrlSecurity {
    private const val MAX_URL_LENGTH = 4096
    private val IPV4_LITERAL = Regex("""[0-9.]+""")

    fun checkedUrl(raw: String): HttpUrl {
        require(raw.length in 1..MAX_URL_LENGTH) { "Invalid catalog URL length" }
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid catalog URL")
        return checkedUrl(url)
    }

    fun checkedUrl(url: HttpUrl): HttpUrl {
        require(url.toString().length <= MAX_URL_LENGTH) { "Invalid catalog URL length" }
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
            "Only public HTTPS catalog URLs without userinfo or fragments are allowed"
        }

        val host = url.host.trimEnd('.').lowercase(java.util.Locale.ROOT)
        require(host != "localhost" && !host.endsWith(".localhost")) { "Non-public destination rejected" }
        if (host.contains(':') || IPV4_LITERAL.matches(host)) {
            val address = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid catalog IP address")
            }
            require(isPublicAddress(address)) { "Non-public destination rejected" }
        }
        return url
    }

    fun resolveRedirect(from: HttpUrl, location: String): HttpUrl {
        val target = from.resolve(location) ?: throw IOException("Invalid catalog redirect")
        return try {
            checkedUrl(target)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unsafe catalog redirect", e)
        }
    }

    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false

        val bytes = address.address
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if (address is Inet4Address) {
            return !(first == 0 || first == 10 || first == 127 || first >= 224 ||
                (first == 100 && second in 64..127) || (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) || (first == 192 && second == 168) ||
                (first == 192 && second == 0 && third == 0) || (first == 192 && second == 0 && third == 2) ||
                (first == 192 && second == 88 && third == 99) ||
                (first == 198 && second in 18..19) || (first == 198 && second == 51 && third == 100) ||
                (first == 203 && second == 0 && third == 113))
        }
        if (address is Inet6Address) {
            val inIetfSpecialUse = first == 0x20 && second == 0x01 &&
                (third <= 1 || (third == 0x0d && fourth == 0xb8))
            val isSixToFour = first == 0x20 && second == 0x02
            val isDocumentation = first == 0x3f && second == 0xff && third <= 0x0f
            return (first and 0xe0) == 0x20 && !inIetfSpecialUse && !isSixToFour && !isDocumentation
        }
        return false
    }
}

internal class PublicCatalogDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !CatalogUrlSecurity.isPublicAddress(it) }) {
            throw UnknownHostException("Non-public destination rejected")
        }
        return addresses
    }
}

/** Executes GET requests with destination checks on the initial URL and each redirect hop. */
internal class CatalogRequestExecutor(client: OkHttpClient) {
    private val client = client.newBuilder()
        .connectionPool(ConnectionPool())
        .dns(PublicCatalogDns(client.dns))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun execute(request: Request): Response {
        var currentRequest = request
        var redirects = 0
        while (true) {
            val currentUrl = CatalogUrlSecurity.checkedUrl(currentRequest.url)
            val checkedRequest = if (currentUrl == currentRequest.url) currentRequest
            else currentRequest.newBuilder().url(currentUrl).build()
            val response = client.newCall(checkedRequest).execute()
            val location = response.header("Location")
            if (response.code !in REDIRECT_CODES || location == null) return response
            if (redirects == MAX_REDIRECTS) {
                response.close()
                throw IOException("Too many catalog redirects")
            }

            val nextUrl = try {
                CatalogUrlSecurity.resolveRedirect(currentUrl, location)
            } catch (e: Exception) {
                response.close()
                throw e
            }
            val nextRequest = currentRequest.newBuilder().url(nextUrl)
            if (currentUrl.host != nextUrl.host || currentUrl.port != nextUrl.port) {
                nextRequest.removeHeader("Authorization")
                    .removeHeader("Cookie")
                    .removeHeader("Referer")
                    .removeHeader("Origin")
            }
            response.close()
            currentRequest = nextRequest.build()
            redirects++
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
