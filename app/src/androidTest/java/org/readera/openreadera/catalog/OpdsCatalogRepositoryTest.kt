package org.readera.openreadera.catalog

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpdsCatalogRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun atomRelationsAndRelativeLinksAreResolvedAgainstResponseUrl() {
        val repo = OpdsCatalogRepository(context)
        val page = repo.parseFeed("""
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Root &amp; Stories</title>
              <link rel="next" href="page2"/>
              <link rel="search" type="application/opensearchdescription+xml" href="search.xml"/>
              <link rel="subsection" title="Unsafe" href="http://127.0.0.1/private"/>
              <link rel="subsection" title="Fiction" href="fiction/"/>
              <entry><id>urn:1</id><title>Book</title><author><name>A Writer</name></author><summary>Brief</summary>
                <link rel="subsection" title="More" href="more/"/>
                <link rel="http://opds-spec.org/acquisition/open-access" title="EPUB" type="application/epub+zip" href="../books/1.epub"/>
              </entry>
            </feed>
        """.trimIndent().toByteArray(), "https://catalog.example/path/feed.xml".toHttpUrl())
        assertEquals("Root & Stories", page.title)
        assertEquals("https://catalog.example/path/page2", page.nextUrl)
        assertEquals("https://catalog.example/path/search.xml", page.searchUrl)
        assertEquals("https://catalog.example/path/fiction/", page.navigationLinks.single().href)
        assertEquals("https://catalog.example/path/more/", page.entries.single().navigationUrl)
        assertEquals("A Writer", page.entries.single().author)
        assertEquals("https://catalog.example/books/1.epub", page.entries.single().acquisitions.single().href)
    }
    @Test fun openSearchTemplatesEncodeTermsAndRejectUnsupportedVariables() {
        val repo = OpdsCatalogRepository(context)
        val base = "https://catalog.example/path/open-search.xml".toHttpUrl()
        val target = repo.expandSearchTemplate(
            base,
            "/search?q={searchTerms}&count={count?}&language={language?}",
            "Dune & salt"
        )
        assertEquals("https://catalog.example/search?q=Dune%20%26%20salt&count=50&language=${java.util.Locale.getDefault().language}", target.toString())
        assertThrows(IllegalArgumentException::class.java) {
            repo.expandSearchTemplate(base, "/search?q={searchTerms}&x={unknown?}", "book")
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.expandSearchTemplate(base, "/search?q={searchTerms}", "x".repeat(257))
        }
    }

    @Test fun originAndAddressPoliciesRejectCredentialLeakAndPrivateNetworks() {
        val base = "https://catalog.example/opds".toHttpUrl()
        assertTrue(OpdsCatalogRepository.sameOrigin(base, "https://catalog.example/search".toHttpUrl()))
        assertFalse(OpdsCatalogRepository.sameOrigin(base, "https://other.example/search".toHttpUrl()))
        assertFalse(OpdsCatalogRepository.sameOrigin(base, "http://catalog.example/search".toHttpUrl()))
        assertFalse(OpdsCatalogRepository.sameOrigin(base, "https://catalog.example:444/search".toHttpUrl()))

        assertTrue(OpdsCatalogRepository.isPublicAddress(java.net.InetAddress.getByName("8.8.8.8")))
        assertTrue(OpdsCatalogRepository.isPublicAddress(java.net.InetAddress.getByName("2001:4860:4860::8888")))
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "169.254.1.1",
            "192.0.2.1",
            "::1",
            "2001:20::1",
            "2001:db8::1",
            "2002:0808:0808::1",
            "3fff::1"
        ).forEach { address ->
            assertFalse(address, OpdsCatalogRepository.isPublicAddress(java.net.InetAddress.getByName(address)))
        }
    }


    @Test fun hostileXmlAndUnsafeConfiguredUrlsAreRejected() {
        val repo = OpdsCatalogRepository(context)
        assertThrows(Exception::class.java) {
            repo.parseFeed("<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><feed><title>&x;</title></feed>".toByteArray(), "https://catalog.example/feed".toHttpUrl())
        }
        listOf("http://catalog.example/feed", "https://user:pass@catalog.example/feed", "https://127.0.0.1/feed", "https://catalog.example/feed#fragment").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { repo.addCatalog("invalid", url, null, null) }
        }
    }

    @Test fun credentialsAreEncryptedAtRestAndRemovedWithCatalog() {
        val prefs = context.getSharedPreferences("opds_catalog_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val repo = OpdsCatalogRepository(context)
        assertThrows(IllegalArgumentException::class.java) {
            repo.addCatalog("Incomplete", "https://catalog.example/opds", null, "password-only")
        }

        val catalog = repo.addCatalog("Private", "https://catalog.example/opds", "alice", "not-plaintext")
        val ciphertext = prefs.getString("secret_${catalog.id}", null)!!
        assertFalse(ciphertext.contains("alice"))
        assertFalse(ciphertext.contains("not-plaintext"))
        val reopened = OpdsCatalogRepository(context)
        assertTrue(reopened.getCatalogs().contains(catalog))
        repo.deleteCatalog(catalog.id)
        assertNull(prefs.getString("secret_${catalog.id}", null))
        assertTrue(repo.getCatalogs().isEmpty())
    }
}
