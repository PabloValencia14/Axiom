package org.readera.openreadera.engine

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineTypographyTest {
    @Test
    fun boldAndBionicOptionsChangeTxtBodyRendering() {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("reader-typography", ".txt", cacheDir)
        try {
            file.writeText(("Typography changes the weight and emphasis of readable body text. ").repeat(20))
            val engine = TxtEngine()
            try {
                assertTrue(engine.open(file.absolutePath))
                val size = engine.getPageSize(0)
                fun render(options: RenderOptions): Bitmap = Bitmap.createBitmap(
                    size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888
                ).also { assertTrue(engine.renderPage(0, it, options)) }
                val normal = render(RenderOptions())
                val bold = render(RenderOptions(fontBold = true))
                val bionic = render(RenderOptions(bionicReading = true))
                try {
                    assertFalse(normal.sameAs(bold))
                    assertFalse(normal.sameAs(bionic))
                } finally {
                    normal.recycle()
                    bold.recycle()
                    bionic.recycle()
                }
            } finally {
                engine.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun boldOptionChangesEpubBodyRenderingAfterRepagination() {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File.createTempFile("reader-typography", ".epub", cacheDir)
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
                entry("mimetype", "application/epub+zip")
                entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""")
                entry("OEBPS/book.opf", """<package><metadata><dc:title xmlns:dc="x">Typography</dc:title></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="chapter"/></spine></package>""")
                entry("OEBPS/chapter.xhtml", "<html><body><h1>Typography</h1><p>${"Wide typography changes reader pagination and rendered body output. ".repeat(600)}</p></body></html>")
            }
            val engine = EpubEngine()
            try {
                assertTrue(engine.open(file.absolutePath))
                val normalOptions = RenderOptions(fontSizeSp = 32)
                engine.applyOptions(normalOptions)
                val size = engine.getPageSize(0)
                val normal = Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888)
                try {
                    assertTrue(engine.renderPage(0, normal, normalOptions))
                    val boldOptions = normalOptions.copy(fontBold = true)
                    engine.applyOptions(boldOptions)
                    val boldSize = engine.getPageSize(0)
                    val bold = Bitmap.createBitmap(boldSize.width.toInt(), boldSize.height.toInt(), Bitmap.Config.ARGB_8888)
                    try {
                        assertTrue(engine.renderPage(0, bold, boldOptions))
                        assertFalse(normal.sameAs(bold))
                    } finally {
                        bold.recycle()
                    }
                } finally {
                    normal.recycle()
                }
            } finally {
                engine.close()
            }
        } finally {
            file.delete()
        }
    }
}
