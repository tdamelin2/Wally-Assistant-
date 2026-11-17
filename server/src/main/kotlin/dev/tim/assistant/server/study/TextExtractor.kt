package dev.tim.assistant.server.study

import java.io.File
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFGroupShape
import org.apache.poi.xslf.usermodel.XSLFShape
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape

const val MIN_TEXT_CHARS = 200
const val MAX_TEXT_CHARS = 400_000
const val CUT_LINE = "[Text cut at 400 000 characters]"

/** Result of reading a study file: usable text, or no (or too little) text. */
sealed interface Extraction {
    data class Text(val text: String) : Extraction

    /** Nothing readable: a scanned PDF, an empty deck or a text shorter than [MIN_TEXT_CHARS]. */
    data object NoText : Extraction
}

/** Text of a PDF (PDFBox 3, file-backed) or PPTX (POI, read-only package). */
object TextExtractor {
    /** Throws on a damaged or protected file; the caller turns any Throwable into FAILED. */
    fun extract(file: File, extension: String): Extraction {
        val raw = when (extension.lowercase()) {
            "pdf" -> pdfText(file)
            "pptx" -> pptxText(file)
            else -> throw IllegalArgumentException("unsupported type")
        }
        return limit(raw)
    }

    /** Applies the minimum and maximum length rules to [raw]. */
    fun limit(raw: String): Extraction {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.length < MIN_TEXT_CHARS) return Extraction.NoText
        if (text.length <= MAX_TEXT_CHARS) return Extraction.Text(text)
        var cut = text.lastIndexOf("\n\n", MAX_TEXT_CHARS)
        if (cut < 1) {
            cut = MAX_TEXT_CHARS
            if (Character.isHighSurrogate(text[cut - 1])) cut--
        }
        return Extraction.Text(text.substring(0, cut).trimEnd() + "\n\n" + CUT_LINE)
    }

    private fun pdfText(file: File): String =
        Loader.loadPDF(RandomAccessReadBufferedFile(file), "", null, null, IOUtils.createTempFileOnlyStreamCache())
            .use { doc -> PDFTextStripper().getText(doc) }

    private fun pptxText(file: File): String {
        val pkg = OPCPackage.open(file, PackageAccess.READ)
        XMLSlideShow(pkg).use { show ->
            val out = StringBuilder()
            for (slide in show.slides) {
                collect(slide.shapes, out)
                slide.notes?.let { collect(it.shapes, out) }
            }
            return out.toString()
        }
    }

    private fun collect(shapes: List<XSLFShape>, out: StringBuilder) {
        for (shape in shapes) {
            when (shape) {
                is XSLFGroupShape -> collect(shape.shapes, out)
                is XSLFTable -> for (row in shape.rows) for (cell in row.cells) append(cell.text, out)
                is XSLFTextShape -> append(shape.text, out)
                else -> {}
            }
        }
    }

    private fun append(text: String?, out: StringBuilder) {
        val t = text?.trim().orEmpty()
        if (t.isNotEmpty()) out.append(t).append("\n\n")
    }
}
