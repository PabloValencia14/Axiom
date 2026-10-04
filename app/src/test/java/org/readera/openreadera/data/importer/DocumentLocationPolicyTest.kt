package org.readera.openreadera.data.importer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DocumentLocationPolicyTest {
    @Test fun canonicalPrivatePathsCannotEnterOrUploadButExplicitDocumentDirectoriesWork() {
        val root = Files.createTempDirectory("document-policy-").toFile().canonicalFile
        try {
            val privateRoot = File(root, "app").apply { mkdirs() }
            val files = File(privateRoot, "files").apply { mkdirs() }
            val directories = listOf("imported", "scans", "pdf-tools").map { File(files, it).apply { mkdirs() } }
            val covers = File(files, "covers").apply { mkdirs() }
            val policy = DocumentLocationPolicy(listOf(privateRoot), directories, listOf(covers))
            val secret = File(privateRoot, "shared_prefs/google_sync_prefs.xml").apply { parentFile!!.mkdirs(); writeText("secret") }
            val publicBook = File(root, "book.pdf").apply { writeText("%PDF ordinary document") }
            assertNull(policy.externalDocument(secret))
            assertNull(policy.uploadDocument(secret))
            assertNull(policy.uploadCover(secret))
            assertEquals(publicBook, policy.externalDocument(publicBook))
            assertEquals(publicBook, policy.uploadDocument(publicBook))
            directories.forEach { directory ->
                val book = File(directory, "ordinary.pdf").apply { writeText("book") }
                assertNull(policy.externalDocument(book))
                assertEquals(book, policy.uploadDocument(book))
            }
            val cover = File(covers, "cover.jpg").apply { writeText("cover") }
            assertEquals(cover, policy.uploadCover(cover))
            assertNull(policy.uploadDocument(cover))
            val traversal = File(files, "../shared_prefs/${secret.name}")
            assertNull(policy.externalDocument(traversal))
            assertNull(policy.uploadDocument(traversal))
            val neighboringApp = File(root, "app-neighbor/book.pdf").apply { parentFile!!.mkdirs(); writeText("book") }
            assertEquals(neighboringApp, policy.externalDocument(neighboringApp))
        } finally { root.deleteRecursively() }
    }

    @Test fun replacingAnApprovedDirectoryWithASymlinkDoesNotApprovePrivateState() {
        val root = Files.createTempDirectory("document-root-policy-").toFile().canonicalFile
        try {
            val privateRoot = File(root, "app").apply { mkdirs() }
            val secretDirectory = File(privateRoot, "preferences").apply { mkdirs() }
            val secret = File(secretDirectory, "token.pdf").apply { writeText("secret") }
            val approved = File(privateRoot, "imported")
            createSymlinkOrSkip(approved, secretDirectory)
            val policy = DocumentLocationPolicy(listOf(privateRoot), listOf(approved), emptyList())
            assertNull(policy.uploadDocument(File(approved, secret.name)))
        } finally { root.deleteRecursively() }
    }

    @Test fun fileSymlinkAliasesCannotEnterOrUploadPrivateFiles() {
        val root = Files.createTempDirectory("document-symlink-policy-").toFile().canonicalFile
        try {
            val privateRoot = File(root, "app").apply { mkdirs() }
            val imported = File(privateRoot, "imported").apply { mkdirs() }
            val secret = File(privateRoot, "token.pdf").apply { writeText("secret") }
            val policy = DocumentLocationPolicy(listOf(privateRoot), listOf(imported), emptyList())
            val alias = File(root, "alias.pdf")
            createSymlinkOrSkip(alias, secret)
            assertNull(policy.externalDocument(alias))
            assertNull(policy.uploadDocument(alias))
            val disguisedImport = File(imported, "secret.pdf")
            createSymlinkOrSkip(disguisedImport, secret)
            assertNull(policy.uploadDocument(disguisedImport))
        } finally { root.deleteRecursively() }
    }
}

internal fun createSymlinkOrSkip(alias: File, target: File) {
    try {
        Files.createSymbolicLink(alias.toPath(), target.toPath())
    } catch (unsupported: UnsupportedOperationException) {
        org.junit.Assume.assumeNoException("Host does not support symbolic links", unsupported)
    } catch (denied: SecurityException) {
        org.junit.Assume.assumeNoException("Host denies symbolic links", denied)
    } catch (failure: java.nio.file.FileSystemException) {
        val reason = failure.reason.orEmpty().lowercase(java.util.Locale.ROOT)
        if (failure is java.nio.file.AccessDeniedException || reason.contains("privilege") ||
            reason.contains("not permitted") || reason.contains("not supported")
        ) {
            org.junit.Assume.assumeNoException("Host cannot create symbolic links", failure)
        } else {
            throw failure
        }
    }
}
