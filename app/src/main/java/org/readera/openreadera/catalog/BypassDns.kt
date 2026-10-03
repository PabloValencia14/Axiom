package org.readera.openreadera.catalog

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
- * Intelligent anti-censorship DNS resolver for Axiom.
 *
 * Spanish and international ISPs often censor book catalogs and shadow libraries
 * (ebookelo.com, libgen.li, singlelogin.rs, etc.) by returning 127.0.0.1 or ::1 in DNS.
 *
 * This resolver:
 * 1. Checks known censored domains and routes them directly via Cloudflare DoH (1.1.1.1).
 * 2. For other domains, tries standard DNS first.
 * 3. If standard DNS returns loopback (127.0.0.1, ::1) or throws UnknownHostException,
 *    it automatically fails over to Cloudflare DoH and Google DoH (8.8.8.8).
 * 4. Maintains an in-memory cache and a static emergency fallback table for 100% uptime.
 */
class BypassDns private constructor() : Dns {

    companion object {
        val instance: BypassDns by lazy { BypassDns() }

        // Known static fallback IPs for critical domains in case both DoH and system DNS fail
        private val STATIC_FALLBACKS = mapOf(
            "ww2.ebookelo.com" to listOf("188.114.96.5", "188.114.97.5"),
            "ebookelo.com" to listOf("188.114.96.5", "188.114.97.5"),
            "libgen.li" to listOf("179.43.167.164"),
            "libgen.is" to listOf("193.218.118.42"),
            "libgen.st" to listOf("193.218.118.42"),
            "cdn2.booksdl.lc" to listOf("188.114.96.5", "188.114.97.5"),
            "booksdl.lc" to listOf("188.114.96.5", "188.114.97.5"),
            "singlelogin.rs" to listOf("79.124.59.190"),
            "singlelogin.re" to listOf("79.124.59.190"),
            "annas-archive.gl" to listOf("185.178.208.181"),
            "annas-archive.pm" to listOf("172.239.193.153", "172.239.194.103"),
            "annas-archive.pk" to listOf("186.2.163.174"),
            "annas-archive.gd" to listOf("185.178.208.181")
        )

        private val ALWAYS_DOH_DOMAINS = listOf(
            "ebookelo.com",
            "libgen.li",
            "libgen.is",
            "libgen.st",
            "libgen.rs",
            "booksdl.lc",
            "singlelogin.rs",
            "singlelogin.re",
            "annas-archive"
        )
    }

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // Dedicated minimal client for DoH queries targeting raw IP addresses directly
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .dns(Dns.SYSTEM) // Numeric IPs like 1.1.1.1 bypass DNS lookups in OkHttp
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase()

        // 1. Check in-memory cache
        val now = System.currentTimeMillis()
        cache[cleanHost]?.let { entry ->
            if (now < entry.expiresAt && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }

        // 2. If it is a known targeted domain, query DoH directly to avoid poisoned 127.0.0.1 delay
        val shouldBypassDirectly = ALWAYS_DOH_DOMAINS.any { cleanHost.contains(it) }

        if (!shouldBypassDirectly) {
            try {
                val systemResult = Dns.SYSTEM.lookup(cleanHost)
                // Verify not poisoned to loopback (127.0.0.1 or ::1) or zero address
                val isPoisoned = systemResult.all { addr ->
                    addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.hostAddress == "127.0.0.1" || addr.hostAddress == "::1" || addr.hostAddress == "0.0.0.0"
                }

                if (!isPoisoned && systemResult.isNotEmpty()) {
                    val filtered = systemResult.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
                    if (filtered.isNotEmpty()) {
                        cache[cleanHost] = CacheEntry(filtered, now + 300_000L) // Cache for 5 minutes
                        return filtered
                    }
                }
            } catch (_: Exception) {
                // System DNS failed, proceed to DoH
            }
        }

        // 3. Resolve via Cloudflare DNS-over-HTTPS (1.1.1.1)
        val cloudflareResult = resolveDoH("https://1.1.1.1/dns-query?name=$cleanHost&type=A", cleanHost)
        if (cloudflareResult.isNotEmpty()) {
            cache[cleanHost] = CacheEntry(cloudflareResult, now + 600_000L) // Cache for 10 minutes
            return cloudflareResult
        }

        // 4. Resolve via Google DNS-over-HTTPS (8.8.8.8)
        val googleResult = resolveDoH("https://8.8.8.8/resolve?name=$cleanHost&type=A", cleanHost)
        if (googleResult.isNotEmpty()) {
            cache[cleanHost] = CacheEntry(googleResult, now + 600_000L)
            return googleResult
        }

        // 5. Emergency static fallback table
        STATIC_FALLBACKS[cleanHost]?.let { ipList ->
            val staticAddrs = ipList.mapNotNull { ip ->
                try { InetAddress.getByName(ip) } catch (_: Exception) { null }
            }
            if (staticAddrs.isNotEmpty()) {
                cache[cleanHost] = CacheEntry(staticAddrs, now + 1_800_000L) // Cache 30 mins
                return staticAddrs
            }
        }

        // 6. Last resort: standard system lookup
        return try {
            Dns.SYSTEM.lookup(cleanHost).filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }.ifEmpty {
                throw UnknownHostException("No se pudo resolver el host tras eludir censura: $cleanHost")
            }
        } catch (e: Exception) {
            throw UnknownHostException("No se pudo resolver $cleanHost: ${e.message}")
        }
    }

    private fun resolveDoH(url: String, hostname: String): List<InetAddress> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .header("User-Agent", "Axiom-DoH/2.0")
                .build()

            val resp = dohClient.newCall(req).execute()
            if (!resp.isSuccessful) return emptyList()

            val body = resp.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return emptyList()

            val addresses = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val ans = answers.getJSONObject(i)
                val type = ans.optInt("type", 0)
                // Type 1 = A (IPv4), Type 28 = AAAA (IPv6)
                if (type == 1) {
                    val ip = ans.optString("data", "")
                    if (ip.isNotBlank() && ip != "127.0.0.1" && ip != "0.0.0.0") {
                        try {
                            addresses.add(InetAddress.getByName(ip))
                        } catch (_: Exception) {}
                    }
                }
            }
            addresses
        } catch (_: Exception) {
            emptyList()
        }
    }
}
