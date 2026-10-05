package org.readera.openreadera.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readera.openreadera.translation.TranslationRejectedException
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfDocumentTranslatorTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val font get() = PDType1Font.HELVETICA

    @Test fun strokeAndFillStrokeModesRetainTheirInkAndFollowingDependentText() = runBlocking {
        withFiles { source,output,expected,graphics ->
            val prefix="q 1.5 w 1 J 2 j 8 M [3 2] 0 d 1 0 0 RG 0 0 1 rg 1.5 0 0 1 1 0 cm "
            val suffix="(9) Tj ET Q"
            val lastX=40f+font.getStringWidth("Fill stroke.")*12f/1000f
            simple(expected,prefix+"BT /F1 12 Tf 1 0 0 1 40 220 Tm 1 Tr (Texto.) Tj ET " +
                "BT /F1 12 Tf 1 0 0 1 40 150 Tm 2 Tr (Uno.) Tj 1 0 0 1 $lastX 150 Tm (9) Tj ET Q")
            simple(source,prefix+"BT /F1 12 Tf 1 0 0 1 40 220 Tm 1 Tr (Stroke prose.) Tj " +
                "1 0 0 1 40 150 Tm 2 Tr (Fill stroke.) Tj "+suffix)
            simple(graphics,prefix+"Q")
            val original=source.readBytes()
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es",{ text,_ ->
                calls+=text; Result.success(if (text=="Stroke prose.") "Texto." else "Uno.")
            },{ _,_ -> })
            assertEquals(listOf("Stroke prose.","Fill stroke."),calls)
            assertOutput(source,output,expected,graphics,listOf("Texto.","Uno.","9"),listOf("Stroke prose.","Fill stroke."),allowFontRasterVariance=true)
            assertArrayEquals(original,source.readBytes())
            PDDocument.load(output).use { document ->
                assertTrue(PDFTextStripper().getText(document).contains("Texto."))
                val operations=parsePdfOperations(com.tom_roush.pdfbox.pdfparser.PDFStreamParser(document.getPage(0)))
                assertTrue(operations.any { it.operator.name=="Tr" && (it.operands.single() as COSNumber).intValue()==1 })
                assertTrue(operations.any { it.operator.name=="Tr" && (it.operands.single() as COSNumber).intValue()==2 })
                assertTrue(operations.any { it.operator.name=="RG" } && operations.any { it.operator.name=="rg" })

            }
        }
    }
    @Test fun changedDashStateSeparatesAdjacentStrokedRunsAndKeepsFollowingAdvance() = runBlocking {
        withFiles { source,output,expected,graphics ->
            val prefix="q .5 w 1 J 0 j 2 M [2 1] 0 d 1 0 0 RG "
            val lastX=40f+font.getStringWidth("Source beta.")*12f/1000f
            simple(source,prefix+"BT /F1 12 Tf 1 0 0 1 40 220 Tm 1 Tr (Source alpha.) Tj [3 1] 0 d " +
                "1 0 0 1 40 200 Tm (Source beta.) Tj (9) Tj ET Q")
            simple(expected,prefix+"BT /F1 12 Tf 1 0 0 1 40 220 Tm 1 Tr (Uno.) Tj ET [3 1] 0 d " +
                "BT /F1 12 Tf 1 0 0 1 40 200 Tm 1 Tr (Dos.) Tj 1 0 0 1 $lastX 200 Tm (9) Tj ET Q")
            simple(graphics,"q Q")
            val original=source.readBytes()
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es",{ text,_ ->
                calls+=text; Result.success(if (text=="Source alpha.") "Uno." else "Dos.")
            },{ _,_ -> })
            assertEquals(listOf("Source alpha.","Source beta."),calls)
            assertOutput(source,output,expected,graphics,listOf("Uno.","Dos.","9"),listOf("Source alpha.","Source beta."),allowFontRasterVariance=true)
            assertArrayEquals(original,source.readBytes())
        }
    }

    @Test fun columnsHeadingBackgroundImageVectorAndOcclusionPreserveInkAndGeometry() = runBlocking {
        withFiles { source, output, expected, graphics ->
            columns(source,0); columns(expected,1); columns(graphics,2)
            val original = source.readBytes()
            val calls = mutableListOf<String>()
            val translations = mapOf(
                "Spanning heading." to "Titulo.",
                "Left paragraph first. Left paragraph next." to "Izquierda texto.",
                "Right paragraph first. Right paragraph next." to "Derecha texto.",
                "Occlusion sample." to "Tapado."
            )
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ ->
                calls += text; Result.success(translations.getValue(text))
            }, { _,_ -> })
            assertEquals(translations.keys.toList(),calls)
            assertOutput(source,output,expected,graphics,translations.values.toList(),translations.keys.toList())
            assertArrayEquals(original,source.readBytes())
        }
    }

    @Test fun tjAndBothQuoteOperatorsKeepExactAdvancesAndLaterDecorativeText() = runBlocking {
        withFiles { source,output,expected,graphics ->
            val firstAdvance = font.getStringWidth("Alpha beta")*12f/1000f-120f*12f/1000f
            val deltaAdvance = font.getStringWidth("Delta text")*12f/1000f + 10f*.3f + .7f
            val original = "BT /F1 12 Tf 16 TL 1 0 0 1 40 220 Tm (Alpha ) Tj [(beta) 120] TJ (7) Tj (Gamma text) ' .7 .3 (Delta text) \" (8) Tj T* (9) Tj ET"
            simple(source,original)
            simple(expected,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Uno.) Tj 1 0 0 1 40 204 Tm (Dos.) Tj ET " +
                "BT /F1 12 Tf 1 0 0 1 ${40f+firstAdvance} 220 Tm (7) Tj .3 Tc .7 Tw 1 0 0 1 ${40f+deltaAdvance} 188 Tm (8) Tj 1 0 0 1 40 172 Tm (9) Tj ET")
            simple(graphics,"BT /F1 12 Tf 1 0 0 1 ${40f+firstAdvance} 220 Tm (7) Tj .3 Tc .7 Tw 1 0 0 1 ${40f+deltaAdvance} 188 Tm (8) Tj 1 0 0 1 40 172 Tm (9) Tj ET")
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ ->
                calls+=text; Result.success(if (text=="Alpha beta") "Uno." else "Dos.")
            }, { _,_ -> })
            assertEquals(listOf("Alpha beta","Gamma text Delta text"),calls)
            assertOutput(source,output,expected,graphics,listOf("Uno.","Dos.","7","8","9"),listOf("Alpha","beta","Gamma","Delta"))
        }
    }

    @Test fun shiftedCropBoxesAllRotationsSharedFormsAndBlankPagesArePreserved() = runBlocking {
        withFiles { source,output,expected,graphics ->
            forms(source,0); forms(expected,1); forms(graphics,2)
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ -> calls+=text; Result.success("Texto.") }, { _,_ -> })
            assertEquals(List(8) { "Shared form prose." },calls)
            assertOutput(source,output,expected,graphics,listOf("Texto."),listOf("Shared form prose."))
            PDDocument.load(output).use { document ->
                for (index in 0..3) {
                    val names=document.getPage(index).resources.xObjectNames.toList()
                    val forms=names.mapNotNull { document.getPage(index).resources.getXObject(it) as? PDFormXObject }
                    assertTrue(forms.map { it.cosObject }.distinct().size >= 2)
                }
            }
        }
    }

    @Test fun bundledCjkAndCyrillicAreVisibleExtractableAndSubsetEmbedded() = runBlocking {
        for (translated in listOf("中文测试", "日本語", "Перевод")) withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Translation placeholder words.) Tj ET")
            val bytes=source.readBytes()
            PdfDocumentTranslator(context).translateCopy(source,output,"zh", { _,_ -> Result.success(translated) }, { _,_ -> })
            PDDocument.load(output).use { document ->
                assertTrue(PDFTextStripper().getText(document).contains(translated))
                val fonts=document.getPage(0).resources.fontNames.map { document.getPage(0).resources.getFont(it) }
                assertTrue(fonts.any { it.isEmbedded && it.name.contains('+') })
                val bitmap=PDFRenderer(document).renderImage(0)
                try { assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x,y)!=Color.WHITE } }) }
                finally { bitmap.recycle() }
            }
            assertArrayEquals(bytes,source.readBytes())
        }
    }

    @Test fun numericTjWordGapsReachProviderAndRetainDependentLaterText() = runBlocking {
        withFiles { source,output,expected,graphics ->
            val words=listOf("This","is","prose.")
            val laterX=40f+words.sumOf { font.getStringWidth(it).toDouble() }.toFloat()*12f/1000f+6f
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm [(This) -250 (is) -250 (prose.)] TJ (7) Tj ET")
            simple(expected,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Texto claro.) Tj 1 0 0 1 $laterX 220 Tm (7) Tj ET")
            simple(graphics,"BT /F1 12 Tf 1 0 0 1 $laterX 220 Tm (7) Tj ET")
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ -> calls+=text; Result.success("Texto claro.") }, { _,_ -> })
            assertEquals(listOf("This is prose."),calls)
            assertOutput(source,output,expected,graphics,listOf("Texto claro.","7"),words)
            PDDocument.load(output).use { document ->
                val arrays=parsePdfOperations(com.tom_roush.pdfbox.pdfparser.PDFStreamParser(document.getPage(0)))
                    .filter { it.operator.name=="TJ" }.map { it.operands.single() as COSArray }
                assertEquals(2,arrays.sumOf { array -> array.count { it is COSNumber && it.floatValue()==-250f } })
            }
        }
    }

    @Test fun safeMcidInlineAndResourceTagsKeepStructureAndSeparateAdjacentParagraphs() = runBlocking {
        withFiles { source,output,expected,graphics ->
            tagged(source,0); tagged(expected,1); tagged(graphics,2)
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ ->
                calls+=text; Result.success(if (text=="First tagged paragraph.") "Texto uno." else "Texto dos.")
            }, { _,_ -> })
            assertEquals(listOf("First tagged paragraph.","Second tagged paragraph."),calls)
            assertOutput(source,output,expected,graphics,listOf("Texto uno.","Texto dos."),calls)
            PDDocument.load(output).use { document ->
                val page=document.getPage(0)
                assertEquals(0,page.cosObject.getInt(COSName.STRUCT_PARENTS))
                val root=document.documentCatalog.cosObject.getDictionaryObject(COSName.STRUCT_TREE_ROOT) as COSDictionary
                val children=root.getDictionaryObject(COSName.K) as COSArray
                assertEquals(listOf(0,1),children.mapIndexed { index, _ ->
                    ((children.getObject(index) as COSDictionary).getDictionaryObject(COSName.K) as COSDictionary).getInt(COSName.MCID)
                })
                assertEquals(listOf("P","P"),children.mapIndexed { index, _ ->
                    (children.getObject(index) as COSDictionary).getNameAsString(COSName.S)
                })
                val operations=parsePdfOperations(com.tom_roush.pdfbox.pdfparser.PDFStreamParser(page))
                assertEquals(2,operations.count { it.operator.name=="BDC" })
                assertEquals(2,operations.count { it.operator.name=="EMC" })
                val mcids=operations.filter { it.operator.name=="BDC" }.map { operation ->
                    val property=operation.operands[1]
                    val dictionary=if (property is COSName) page.resources.getProperties(property).cosObject else property as COSDictionary
                    dictionary.getInt(COSName.MCID)
                }
                assertEquals(listOf(0,1),mcids)
            }
        }
        for (unsafe in listOf("inlineActualText","resourceActualText","structureAlt","structureActualText","optional")) {
            withFiles { source,output,_,_ ->
                tagged(source,0,unsafe)
                assertRejectedWithoutProvider(source,output,if (unsafe=="optional") "Optional" else "Alternate")
            }
        }
    }

    @Test fun rulesPaintedBeforeTextKeepSelectableTableRowsInTheirCells() = runBlocking {
        withFiles { source,output,expected,graphics ->
            table(source,0); table(expected,1); table(graphics,2)
            val calls=mutableListOf<String>()
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ ->
                calls+=text; Result.success(if (text=="First table row.") "Fila uno." else "Fila dos.")
            }, { _,_ -> })
            assertEquals(listOf("First table row.","Second table row."),calls)
            assertOutput(source,output,expected,graphics,listOf("Fila uno.","Fila dos."),calls)
        }
    }

    @Test fun adjacentMediaCannotBeInvadedButNonintersectingMultilineTextAndBackgroundImagesRenderExactly() = runBlocking {
        for (vector in listOf(false,true)) {
            withFiles { source,output,_,_ ->
                adjacentMedia(source,0,vector)
                val original=source.readBytes()
                val failure=try {
                    PdfDocumentTranslator(context).translateCopy(source,output,"es", { _,_ ->
                        Result.success("Texto de la linea superior.\nTexto extenso.")
                    }, { _,_ -> }); null
                } catch (exception: TranslationRejectedException) { exception }
                assertNotNull(failure); assertTrue(failure!!.message.orEmpty().contains("image or graphic"))
                assertFalse(output.exists()); assertArrayEquals(original,source.readBytes())
            }
            withFiles { source,output,expected,graphics ->
                adjacentMedia(source,0,vector); adjacentMedia(expected,1,vector); adjacentMedia(graphics,2,vector)
                PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ ->
                    assertEquals("Wide paragraph line with space. Short line.",text)
                    Result.success("Texto de la linea superior.\nTexto breve.")
                }, { _,_ -> })
                assertOutput(source,output,expected,graphics,listOf("Texto de la linea superior.","Texto breve."),listOf("Wide paragraph","Short line."))
            }
        }
    }
    @Test fun realisticMultilineRegularBoldItalicColumnsModeratelyFitBesideImagesAgainstIndependentOracle() = runBlocking {
        withFiles { source,output,expected,graphics ->
            styledColumns(source,0); val sizes=styledColumns(expected,1); styledColumns(graphics,2)
            assertTrue(sizes.any { it<12f && it>=10.2f })
            val calls=mutableListOf<String>()
            val failure=runCatching {
                PdfDocumentTranslator(context).translateCopy(source,output,"es", { text,_ ->
                    calls+=text
                    val outputLines=when {
                        text.startsWith("Left regular") -> listOf("Left translated regular paragraph.","Left translated regular paragraph.","Texto final.")
                        text.startsWith("Right regular") -> listOf("Right translated regular paragraph.","Right translated regular paragraph.","Texto final.")
                        text.startsWith("Bold source") -> List(2) { "Texto fuerte de la columna." }
                        else -> List(2) { "Texto cursivo de la columna." }
                    }
                    Result.success(outputLines.joinToString("\n"))
                }, { _,_ -> })
            }.exceptionOrNull()
            assertNull(failure?.let { "${it.message} @ ${(it as? TranslationRejectedException)?.location}" })
            assertEquals(6,calls.size)
            assertOutput(source,output,expected,graphics,
                listOf("Left translated regular paragraph.","Right translated regular paragraph.","Texto final.","Texto fuerte de la columna.","Texto cursivo de la columna."),
                listOf("regular paragraph source.","Bold source paragraph","Italic source paragraph","Source ending."),allowFontRasterVariance=true)
            PDDocument.load(output).use { actual -> PDDocument.load(expected).use { oracle ->
                fun normalized(document: PDDocument)=PDFTextStripper().getText(document).replace(Regex("\\s+")," ").trim()
                assertEquals(normalized(oracle),normalized(actual))
                val sizesWritten=parsePdfOperations(com.tom_roush.pdfbox.pdfparser.PDFStreamParser(actual.getPage(0)))
                    .filter { it.operator.name=="Tf" }.map { (it.operands[1] as COSNumber).floatValue() }
                assertTrue(sizesWritten.any { it<12f && it>=10.2f })
            } }
        }
    }

    @Test fun cjkUnicodeWrappingKeepsEveryCharacterAndSmallSourceFontsKeepTheirOriginalSize() = runBlocking {
        withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 16 TL 1 0 0 1 40 220 Tm (Original text.) Tj (Original text.) ' ET")
            val translated="中文译文测试页面"
            PdfDocumentTranslator(context).translateCopy(source,output,"zh", { _,_ -> Result.success(translated) }, { _,_ -> })
            PDDocument.load(output).use { document ->
                val extracted=PDFTextStripper().getText(document)
                assertEquals(translated,extracted.filterNot { it.isWhitespace() })
                assertFalse(extracted.contains("Original"))
                val operations=parsePdfOperations(com.tom_roush.pdfbox.pdfparser.PDFStreamParser(document.getPage(0)))
                assertTrue(operations.count { it.operator.name=="Tj" } >= 2)
            }
        }
        withFiles { source,output,expected,graphics ->
            simple(source,"BT /F1 6 Tf 1 0 0 1 40 220 Tm (Small prose.) Tj ET")
            simple(expected,"BT /F1 6 Tf 1 0 0 1 40 220 Tm (Texto.) Tj ET")
            simple(graphics,"")
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { _,_ -> Result.success("Texto.") }, { _,_ -> })
            assertOutput(source,output,expected,graphics,listOf("Texto."),listOf("Small prose."))
        }
    }

    @Test fun preflightRejectsScansInvisibleOcrClippingType3CyclesSignaturesAndRestrictionsWithoutProviderCalls() = runBlocking {
        val cases=listOf(
            "" to "No visible native prose",
            "BT /F1 12 Tf 3 Tr 1 0 0 1 40 220 Tm (Invisible OCR prose.) Tj ET" to "Invisible OCR",
            "q 40 220 4 4 re W n BT /F1 12 Tf 1 0 0 1 40 220 Tm (Clipped prose.) Tj ET Q" to "clipped",
            "BT /F1 12 Tf 4 Tr 1 0 0 1 40 220 Tm (Clipping text.) Tj ET" to "Text clipping",
            "BT /F1 12 Tf 1 .2 0 1 40 220 Tm (Oblique prose.) Tj ET" to "geometry",
            "BT /F1 12 Tf 1 0 0 1 40 220 Tm (Mixed prose = formula.) Tj ET" to "mathematical"
        )
        for ((content,reason) in cases) withFiles { source,output,_,_ ->
            simple(source,content,imageOnly=content.isEmpty())
            assertRejectedWithoutProvider(source,output,reason)
        }
        withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Signed prose.) Tj ET")
            PDDocument.load(source).use { document ->
                document.documentCatalog.cosObject.setItem(COSName.PERMS,PDSignature().cosObject)
                document.save(source)
            }
            assertRejectedWithoutProvider(source,output,"Signed")
        }
        withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Restricted prose.) Tj ET")
            PDDocument.load(source).use { document ->
                val permission=com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission().apply { setCanModify(false); setCanExtractContent(false) }
                document.protect(com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret","",permission))
                document.save(source)
            }
            assertRejectedWithoutProvider(source,output,"permissions")
        }
        withFiles { source,output,_,_ ->
            PDFBoxResourceLoader.init(context)
            PDDocument().use { document ->
                val page=PDPage(PDRectangle(300f,300f)); document.addPage(page); page.resources=PDResources()
                val form=PDFormXObject(document).apply { setBBox(PDRectangle(100f,100f)); resources=PDResources() }
                form.resources.put(COSName.getPDFName("Loop"),form)
                form.contentStream.createOutputStream().use { it.write("/Loop Do".toByteArray()) }
                page.resources.put(COSName.getPDFName("Loop"),form)
                page.setContents(PDStream(document).apply { createOutputStream().use { it.write("/Loop Do".toByteArray()) } })
                document.save(source)
            }
            assertRejectedWithoutProvider(source,output,"Cyclic")
        }
        withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Type3 prose.) Tj ET")
            PDDocument.load(source).use { document ->
                document.getPage(0).resources.getFont(COSName.getPDFName("F1")).cosObject.setItem(COSName.SUBTYPE,COSName.TYPE3)
                document.save(source)
            }
            assertRejectedWithoutProvider(source,output,"Type3")
        }
    }

    @Test fun overflowMissingGlyphUnsupportedShapingProviderFailureAndCancellationLeaveNoPart() = runBlocking {
        for (translation in listOf("expansion ".repeat(100), "\ud83e\udd84", "مرحبا", "e\u0301")) withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Short prose.) Tj ET")
            val bytes=source.readBytes()
            val failure=try {
                PdfDocumentTranslator(context).translateCopy(source,output,"xx", { _,_ -> Result.success(translation) }, { _,_ -> }); null
            } catch (exception: TranslationRejectedException) { exception }
            assertNotNull(failure); assertNotNull(failure!!.location)
            assertFalse(output.exists()); assertArrayEquals(bytes,source.readBytes())
        }
        for (cancel in listOf(false,true)) withFiles { source,output,_,_ ->
            simple(source,"BT /F1 12 Tf 1 0 0 1 40 220 Tm (Short prose.) Tj ET")
            val bytes=source.readBytes()
            val problem=if (cancel) CancellationException("cancel") else java.io.IOException("provider unavailable")
            try {
                PdfDocumentTranslator(context).translateCopy(source,output,"es", { _,_ -> Result.failure(problem) }, { _,_ -> }); fail("Expected failure")
            } catch (failure: Exception) {
                if (cancel) assertTrue(failure is CancellationException && failure.message == problem.message)
                else assertTrue(failure is java.io.IOException && failure.message == problem.message)
            }
            assertFalse(output.exists()); assertArrayEquals(bytes,source.readBytes())
        }
    }

    private suspend fun assertRejectedWithoutProvider(source: File,output: File,reason: String) {
        val original=source.readBytes(); var calls=0
        val failure=try {
            PdfDocumentTranslator(context).translateCopy(source,output,"es", { _,_ -> calls++; Result.success("Texto.") }, { _,_ -> }); null
        } catch (exception: TranslationRejectedException) { exception }
        assertNotNull(failure); assertTrue(failure!!.message.orEmpty().contains(reason,ignoreCase=true)); assertNotNull(failure.location)
        assertEquals(0,calls); assertFalse(output.exists()); assertArrayEquals(original,source.readBytes())
    }

    private fun assertOutput(source: File,output: File,expected: File,graphics: File,translated: List<String>,removed: List<String>,allowFontRasterVariance: Boolean=false) {
        val bytes=source.readBytes()
        PDDocument.load(source).use { old -> PDDocument.load(output).use { actual -> PDDocument.load(expected).use { oracle -> PDDocument.load(graphics).use { background ->
            assertEquals(old.numberOfPages,actual.numberOfPages)
            assertEquals(old.documentInformation.title,actual.documentInformation.title)
            val extracted=PDFTextStripper().getText(actual)
            translated.forEach { assertTrue("Missing translated text $it",extracted.contains(it)) }
            removed.forEach { assertFalse("Source text remains: $it",extracted.contains(it)) }
            var changed=0; var visible=0
            for (index in 0 until actual.numberOfPages) {
                val a=old.getPage(index); val b=actual.getPage(index)
                for (box in listOf(COSName.MEDIA_BOX,COSName.CROP_BOX,COSName.BLEED_BOX,COSName.TRIM_BOX,COSName.ART_BOX))
                    assertEquals(a.cosObject.getDictionaryObject(box)?.toString(),b.cosObject.getDictionaryObject(box)?.toString())
                assertEquals(a.rotation,b.rotation); assertEquals(a.userUnit,b.userUnit)
                assertEquals(a.annotations.size,b.annotations.size)
                a.annotations.zip(b.annotations).forEach { (left,right) ->
                    assertEquals(left.cosObject.toString(),right.cosObject.toString())
                }
                if (index==4) assertArrayEquals(a.contents.readBytes(),b.contents.readBytes())
                val before=PDFRenderer(old).renderImage(index,2f); val after=PDFRenderer(actual).renderImage(index,2f)
                val expectedImage=PDFRenderer(oracle).renderImage(index,2f); val graphicsImage=PDFRenderer(background).renderImage(index,2f)
                try {
                    assertEquals(before.width,after.width); assertEquals(before.height,after.height)
                    if (!allowFontRasterVariance) assertTrue("Rendered replacement differs from independently drawn expected page $index",after.sameAs(expectedImage))
                    var expectedInk=0; var actualInk=0; var sharedInk=0
                    for (y in 0 until after.height) for (x in 0 until after.width) {
                        val oldPixel=before.getPixel(x,y); val newPixel=after.getPixel(x,y); val backgroundPixel=graphicsImage.getPixel(x,y)
                        val expectedPixel=expectedImage.getPixel(x,y)
                        if (expectedPixel!=backgroundPixel) expectedInk++
                        if (newPixel!=backgroundPixel) actualInk++
                        if (expectedPixel!=backgroundPixel && newPixel!=backgroundPixel) sharedInk++
                        // Exact old/new ink masks from independently rendered graphics-only and expected fixtures.
                        val inkMask=oldPixel!=backgroundPixel || expectedPixel!=backgroundPixel ||
                            (allowFontRasterVariance && newPixel!=backgroundPixel)
                        if (!inkMask) assertEquals("Graphics changed at $index:$x,$y",oldPixel,newPixel)
                        if (oldPixel!=newPixel) changed++
                        if (newPixel!=backgroundPixel) visible++
                    }
                    if (allowFontRasterVariance) {
                        val union=expectedInk+actualInk-sharedInk
                        assertTrue("Actual and independent rendered text regions diverge on page $index",union>0 && sharedInk.toFloat()/union>=.65f)
                    }
                } finally { before.recycle(); after.recycle(); expectedImage.recycle(); graphicsImage.recycle() }
            }
            assertTrue("Translation is not visibly painted",visible>0); assertTrue("Source ink was not replaced",changed>0)
        } } } }
        assertArrayEquals(bytes,source.readBytes())
    }

    private fun columns(file: File,mode: Int) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(440f,420f)); document.addPage(page)
            PDPageContentStream(document,page).use { stream ->
                graphics(document,stream)
                fun text(value: String,x: Float,y: Float,size: Float) {
                    stream.beginText(); stream.setFont(font,size); stream.newLineAtOffset(x,y); stream.showText(value); stream.endText()
                }
                stream.setNonStrokingColor(.1f,.2f,.4f)
                if (mode==0) {
                    text("Spanning heading.",40f,370f,18f)
                    text("Left paragraph first.",40f,325f,12f); text("Left paragraph next.",40f,309f,12f)
                    text("Right paragraph first.",235f,325f,12f); text("Right paragraph next.",235f,309f,12f)
                    text("Occlusion sample.",40f,260f,12f)
                } else if (mode==1) {
                    text("Titulo.",40f,370f,18f); text("Izquierda texto.",40f,325f,12f)
                    text("Derecha texto.",235f,325f,12f); text("Tapado.",40f,260f,12f)
                }
                text("= 2",235f,260f,12f) // Nonlinguistic formula tokens must survive unchanged in every oracle.
                stream.setNonStrokingColor(.8f,.1f,.2f); stream.addRect(55f,259f,18f,10f); stream.fill()
            }
            page.annotations=listOf(PDAnnotationLink().apply { rectangle=PDRectangle(40f,40f,20f,20f) })
            document.documentInformation.title="Preserved catalog metadata"
            document.save(file)
        }
    }

    private fun forms(file: File,mode: Int) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val form=PDFormXObject(document).apply { setBBox(PDRectangle(140f,60f)); resources=PDResources() }
            form.resources.put(COSName.getPDFName("F1"),font)
            val text=when(mode) { 0 -> "BT /F1 12 Tf 1 0 0 1 5 20 Tm (Shared form prose.) Tj ET"; 1 -> "BT /F1 12 Tf 1 0 0 1 5 20 Tm (Texto.) Tj ET"; else -> "" }
            form.contentStream.createOutputStream().use { it.write(text.toByteArray(Charsets.US_ASCII)) }
            for (rotation in listOf(0,90,180,270)) {
                val page=PDPage(PDRectangle(440f,440f)); document.addPage(page)
                page.cropBox=PDRectangle(30f,40f,350f,360f); page.rotation=rotation
                PDPageContentStream(document,page).use { stream ->
                    stream.setNonStrokingColor(.85f,.9f,.95f); stream.addRect(30f,40f,350f,360f); stream.fill()
                    for ((x,y) in listOf(60f to 300f,210f to 150f)) {
                        stream.saveGraphicsState(); stream.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x,y)); stream.drawForm(form); stream.restoreGraphicsState()
                    }
                }
            }
            val blank=PDPage(PDRectangle(440f,440f)); document.addPage(blank)
            PDPageContentStream(document,blank).use { stream -> stream.setNonStrokingColor(.3f,.5f,.7f); stream.addRect(40f,40f,30f,30f); stream.fill() }
            document.save(file)
        }
    }

    private fun simple(file: File,content: String,imageOnly: Boolean=false) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(300f,300f)); document.addPage(page); page.resources=PDResources()
            page.resources.put(COSName.getPDFName("F1"),font)
            page.setContents(PDStream(document).apply { createOutputStream().use { it.write(content.toByteArray(Charsets.US_ASCII)) } })
            if (imageOnly) PDPageContentStream(document,page,PDPageContentStream.AppendMode.APPEND,true,true).use { graphics(document,it) }
            document.save(file)
        }
    }

    private fun graphics(document: PDDocument,stream: PDPageContentStream) {
        stream.setNonStrokingColor(.85f,.9f,.95f); stream.addRect(0f,0f,440f,420f); stream.fill()
        val bitmap=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888)
        try {
            for (y in 0..11) for (x in 0..11) bitmap.setPixel(x,y,if ((x+y)%2==0) Color.MAGENTA else Color.GREEN)
            stream.drawImage(LosslessFactory.createFromImage(document,bitmap),270f,70f,60f,60f)
        } finally { bitmap.recycle() }
        stream.setStrokingColor(.2f,.6f,.1f); stream.setLineWidth(2f)
        stream.moveTo(40f,80f); stream.lineTo(150f,180f); stream.stroke()
    }

    private fun tagged(file: File,mode: Int,unsafe: String?=null) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(300f,300f)); document.addPage(page); page.resources=PDResources()
            page.resources.put(COSName.getPDFName("F1"),font); page.cosObject.setInt(COSName.STRUCT_PARENTS,0)
            val properties=COSDictionary().apply {
                setItem(COSName.getPDFName("TagTwo"),COSDictionary().apply {
                    setInt(COSName.MCID,1)
                    if (unsafe=="resourceActualText") setString(COSName.ACTUAL_TEXT,"untranslated alternate prose")
                })
            }
            page.resources.cosObject.setItem(COSName.PROPERTIES,properties)
            val root=COSDictionary().apply { setItem(COSName.TYPE,COSName.STRUCT_TREE_ROOT) }
            val elements=(0..1).map { index -> COSDictionary().apply {
                setName(COSName.TYPE,"StructElem"); setName(COSName.S,"P"); setItem(COSName.P,root); setItem(COSName.PG,page.cosObject)
                val marked=COSDictionary().apply {
                    setName(COSName.TYPE,"MCR"); setItem(COSName.PG,page.cosObject); setInt(COSName.MCID,index)
                }
                setItem(COSName.K,marked)
                if (index==0 && unsafe=="structureAlt") setString(COSName.ALT,"untranslated alternative")
                if (index==0 && unsafe=="structureActualText") setString(COSName.ACTUAL_TEXT,"untranslated alternative")
            } }
            val kids=COSArray().apply { elements.forEach(::add) }
            root.setItem(COSName.K,kids)
            val parentArray=COSArray().apply { elements.forEach(::add) }
            root.setItem(COSName.PARENT_TREE,COSDictionary().apply {
                setItem(COSName.NUMS,COSArray().apply { add(COSInteger.ZERO); add(parentArray) })
            })
            root.setItem(COSName.getPDFName("ParentTreeNextKey"),COSInteger.get(2))
            document.documentCatalog.cosObject.setItem(COSName.STRUCT_TREE_ROOT,root)
            document.documentCatalog.cosObject.setItem(COSName.MARK_INFO,COSDictionary().apply { setBoolean(COSName.getPDFName("Marked"),true) })
            val content=when(mode) { 0 -> "First tagged paragraph." to "Second tagged paragraph."; 1 -> "Texto uno." to "Texto dos."; else -> "" to "" }
            val tag=if (unsafe=="optional") "OC" else "P"
            val alternate=if (unsafe=="inlineActualText") " /ActualText (untranslated alternate prose)" else ""
            val firstProperties="<< /MCID 0$alternate >>"
            val secondProperties=if (unsafe=="optional") "<< /MCID 1 /OC true >>" else "/TagTwo"
            val stream="/$tag $firstProperties BDC BT /F1 12 Tf 1 0 0 1 40 220 Tm (${content.first}) Tj ET EMC " +
                "/P $secondProperties BDC BT /F1 12 Tf 1 0 0 1 40 204 Tm (${content.second}) Tj ET EMC"
            page.setContents(PDStream(document).apply { createOutputStream().use { it.write(stream.toByteArray(Charsets.US_ASCII)) } })
            document.save(file)
        }
    }

    private fun table(file: File,mode: Int) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(300f,300f)); document.addPage(page)
            PDPageContentStream(document,page).use { stream ->
                stream.setStrokingColor(.2f,.3f,.4f); stream.setLineWidth(.8f)
                stream.addRect(35f,190f,180f,45f)
                stream.moveTo(35f,214f); stream.lineTo(215f,214f); stream.stroke()
                val lines=when(mode) { 0 -> listOf("First table row.","Second table row."); 1 -> listOf("Fila uno.","Fila dos."); else -> emptyList() }
                lines.forEachIndexed { index,line ->
                    stream.beginText(); stream.setFont(font,12f); stream.newLineAtOffset(40f,220f-index*20f)
                    stream.showText(line); stream.endText()
                }
            }
            document.save(file)
        }
    }

    private fun fixtureImage(document: PDDocument,stream: PDPageContentStream,x: Float,y: Float,width: Float,height: Float,background: Boolean=false) {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        try {
            for (row in 0..7) for (column in 0..7) bitmap.setPixel(column,row,
                if (background) Color.rgb(225+row,230+column,240) else if ((row+column)%2==0) Color.BLUE else Color.YELLOW)
            stream.drawImage(LosslessFactory.createFromImage(document,bitmap),x,y,width,height)
        } finally { bitmap.recycle() }
    }

    private fun adjacentMedia(file: File,mode: Int,vector: Boolean) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(300f,300f)); document.addPage(page)
            PDPageContentStream(document,page).use { stream ->
                fixtureImage(document,stream,0f,0f,300f,300f,background=true)
                if (vector) {
                    stream.setNonStrokingColor(.2f,.4f,.8f); stream.addRect(110f,201f,30f,12f); stream.fill()
                } else fixtureImage(document,stream,110f,201f,30f,12f)
                stream.setNonStrokingColor(0f)
                val lines=when(mode) {
                    0 -> listOf("Wide paragraph line with space.","Short line.")
                    1 -> listOf("Texto de la linea superior.","Texto breve.")
                    else -> emptyList()
                }
                lines.forEachIndexed { index,line ->
                    stream.beginText(); stream.setFont(font,12f); stream.newLineAtOffset(40f,220f-index*16f)
                    stream.showText(line); stream.endText()
                }
            }
            document.save(file)
        }
    }

    /** Independent standard-font metric oracle; does not call the production fitter or paragraph analyzer. */
    private fun fixtureInkWidth(selected: PDType1Font,text: String,size: Float): Float {
        val input=java.io.ByteArrayInputStream(selected.encode(text))
        var x=0f; var right=0f
        while (input.available()>0) {
            val code=selected.readCode(input)
            val bounds=android.graphics.RectF()
            selected.getPath(selected.codeToName(code)).apply {
                transform(pdfAndroidMatrix(selected.fontMatrix))
                computeBounds(bounds,true)
            }
            right=maxOf(right,x+bounds.right*size)
            x+=selected.getWidth(code)*size/1000f
        }
        return right
    }

    private fun styledColumns(file: File,mode: Int): List<Float> {
        PDFBoxResourceLoader.init(context)
        val expectedSizes=mutableListOf<Float>()
        PDDocument().use { document ->
            val page=PDPage(PDRectangle(560f,560f)); document.addPage(page)
            PDPageContentStream(document,page).use { stream ->
                stream.setNonStrokingColor(.85f,.9f,.95f); stream.addRect(0f,0f,560f,560f); stream.fill()
                stream.setStrokingColor(.2f,.6f,.1f); stream.setLineWidth(2f)
                stream.moveTo(40f,80f); stream.lineTo(150f,180f); stream.stroke()
                for ((label,x) in listOf("Left" to 40f,"Right" to 300f)) {
                    fixtureImage(document,stream,x+225f,450f,24f,12f)
                    val groups=listOf(
                        Triple(PDType1Font.HELVETICA,
                            listOf("$label regular paragraph source.","$label regular paragraph source.","Source ending."),
                            listOf("$label translated regular paragraph.","$label translated regular paragraph.","Texto final.")),
                        Triple(PDType1Font.HELVETICA_BOLD,List(2) { "Bold source paragraph line." },List(2) { "Texto fuerte de la columna." }),
                        Triple(PDType1Font.HELVETICA_OBLIQUE,List(2) { "Italic source paragraph line." },List(2) { "Texto cursivo de la columna." })
                    )
                    groups.forEachIndexed { groupIndex,(selected,original,translated) ->
                        val width=original.maxOf { fixtureInkWidth(selected,it,12f) }
                        val size=(0..30).map { 12f-1.8f*it/30f }.first { candidate ->
                            translated.all { line -> fixtureInkWidth(selected,line,candidate) <= width }
                        }
                        expectedSizes+=size
                        val y=listOf(500f,430f,375f)[groupIndex]
                        stream.setNonStrokingColor(.1f+groupIndex*.03f,.2f,.3f)
                        val lines=when(mode) { 0 -> original; 1 -> translated; else -> emptyList() }
                        lines.forEachIndexed { index,line ->
                            stream.beginText(); stream.setFont(selected,if (mode==0) 12f else size)
                            stream.newLineAtOffset(x,y-index*16f); stream.showText(line); stream.endText()
                        }
                    }
                }
            }
            document.save(file)
        }
        return expectedSizes
    }


    private suspend fun withFiles(block: suspend (File,File,File,File) -> Unit) {
        val token=System.nanoTime()
        val files=listOf("source.pdf","output.pdf.part","expected.pdf","graphics.pdf").map { File(context.cacheDir,"native-translation-$token-$it") }
        try { block(files[0],files[1],files[2],files[3]) } finally { files.forEach { it.delete() } }
    }
}
