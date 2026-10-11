package com.dpdpxray.app.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.dpdpxray.core.dpdp.CitationService
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.law.LegalProfile
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.report.HtmlCoverage
import com.dpdpxray.core.report.HtmlReport
import com.dpdpxray.core.trackers.TrackerCatalog
import java.io.File

/** Renders the Markdown report into a clean A4 PDF with Android's built-in PdfDocument (no network, no library). */
object PdfExporter {
    private const val W = 595
    private const val H = 842
    private const val M = 40

    internal enum class Kind { H1, H2, H3, BODY, QUOTE, CODE, BULLET, TABLE, RULE }

    /** One classified Markdown line. [keepWithNext] lines (headings, labels, metadata, table headers) never end a page. */
    internal data class Line(val kind: Kind, val text: String, val keepWithNext: Boolean = false)

    private class Laid(val line: Line, val layout: StaticLayout?, val indent: Int)

    fun render(markdown: String, out: File): File {
        val doc = PdfDocument()
        val laid = classify(markdown).map { line ->
            if (line.kind == Kind.RULE) Laid(line, null, 0)
            else {
                val indent = when (line.kind) { Kind.QUOTE, Kind.BULLET -> 12; Kind.CODE -> 8; else -> 0 }
                val layout = StaticLayout.Builder.obtain(line.text, 0, line.text.length, paintFor(line.kind), W - 2 * M - indent)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(1.5f, 1f).build()
                Laid(line, layout, indent)
            }
        }
        val blocks = laid.map { l ->
            val layout = l.layout
            if (layout == null) PdfPagination.Block(height = 0f, before = 6f, keepWithNext = false, gap = 0f)
            else PdfPagination.Block(
                height = layout.height.toFloat(),
                before = when (l.line.kind) { Kind.H1 -> 6f; Kind.H2 -> 12f; Kind.H3 -> 8f; else -> 2f },
                keepWithNext = l.line.keepWithNext,
                lineBottoms = (0 until layout.lineCount).map { layout.getLineBottom(it).toFloat() },
            )
        }

        var pageNo = 0
        var page: PdfDocument.Page? = null
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            val footer = TextPaint().apply { textSize = 7f; color = Color.GRAY; isAntiAlias = true }
            page!!.canvas.drawText("TraceVeil · generated on-device · page $pageNo", M.toFloat(), (H - 20).toFloat(), footer)
        }
        newPage()

        for (slice in PdfPagination.plan(blocks, M.toFloat(), (H - M).toFloat())) {
            val l = laid[slice.index]
            val layout = l.layout ?: continue
            while (pageNo < slice.page) newPage()
            val canvas = page!!.canvas
            val h = slice.to - slice.from
            if (l.line.kind == Kind.CODE) {
                val bg = android.graphics.Paint().apply { color = Color.rgb(242, 244, 247) }
                canvas.drawRect((M + l.indent - 4).toFloat(), slice.y - 2, (W - M).toFloat(), slice.y + h + 2, bg)
            }
            if (l.line.kind == Kind.QUOTE) {
                val bar = android.graphics.Paint().apply { color = Color.rgb(45, 212, 191); strokeWidth = 2f }
                canvas.drawLine((M + 4).toFloat(), slice.y, (M + 4).toFloat(), slice.y + h, bar)
            }
            canvas.save()
            // Only a block split across pages needs clipping to its own lines.
            if (slice.from > 0f || slice.to < layout.height) canvas.clipRect(0f, slice.y, W.toFloat(), slice.y + h)
            canvas.translate((M + l.indent).toFloat(), slice.y - slice.from)
            layout.draw(canvas)
            canvas.restore()
        }
        page?.let { doc.finishPage(it) }
        out.parentFile?.mkdirs()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun paintFor(kind: Kind) = TextPaint().apply {
        isAntiAlias = true
        color = Color.rgb(27, 35, 51)
        when (kind) {
            Kind.H1 -> { textSize = 17f; typeface = Typeface.DEFAULT_BOLD }
            Kind.H2 -> { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
            Kind.H3 -> { textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD }
            Kind.QUOTE -> { textSize = 8.5f; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); color = Color.rgb(70, 80, 95) }
            Kind.CODE -> { textSize = 7.5f; typeface = Typeface.MONOSPACE }
            Kind.TABLE -> { textSize = 8f; typeface = Typeface.MONOSPACE }
            else -> textSize = 9f
        }
    }

    internal fun classify(md: String): List<Line> {
        val out = mutableListOf<Line>()
        var inCode = false
        val code = StringBuilder()
        for (raw in md.lines()) {
            if (raw.trim().startsWith("```")) {
                if (inCode) { out += Line(Kind.CODE, code.toString().trimEnd()); code.clear() }
                inCode = !inCode
                continue
            }
            if (inCode) { code.appendLine(raw); continue }
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {}
                line.startsWith("# ") -> out += Line(Kind.H1, clean(line.drop(2)), keepWithNext = true)
                line.startsWith("## ") -> out += Line(Kind.H2, clean(line.drop(3)), keepWithNext = true)
                line.startsWith("### ") -> out += Line(Kind.H3, clean(line.drop(4)), keepWithNext = true)
                line.startsWith("> ") || line == ">" -> out += Line(Kind.QUOTE, clean(line.removePrefix(">").trim()))
                line.startsWith("- ") -> out += Line(Kind.BULLET, "• " + clean(line.drop(2)))
                line.startsWith("|") -> if (!line.startsWith("|---")) {
                    // The first row of a table is its header: keep it with the first data row.
                    val header = out.lastOrNull()?.kind != Kind.TABLE
                    out += Line(Kind.TABLE, line.trim('|').split('|').joinToString("  ·  ") { clean(it.trim()) }, keepWithNext = header)
                }
                line.startsWith("---") -> out += Line(Kind.RULE, "")
                else -> out += Line(Kind.BODY, clean(line), keepWithNext = introducesNext(line))
            }
        }
        return out.filterNot { it.kind == Kind.QUOTE && it.text.isBlank() }
    }

    /** Bold labels ("**Evidence**", "**Plan A** — 6 lookups"), italic metadata lines and "label:" lines introduce what follows. */
    private fun introducesNext(line: String): Boolean {
        val t = line.trim()
        val italicOnly = t.length > 2 && t.startsWith("*") && !t.startsWith("**") && t.endsWith("*")
        return t.startsWith("**") || italicOnly || t.endsWith(":")
    }

    private fun clean(s: String) = s.replace("**", "").replace("`", "").replace(Regex("(?<!\\w)\\*(.+?)\\*(?!\\w)"), "$1")
}

/**
 * Pure page-break planning for the PDF, separate from Android drawing so it can be unit-tested. A block that ends a
 * page while it introduces the next one (heading, label, metadata, table header) moves to the next page together with
 * the first lines of what it introduces. Blocks taller than a page are split at line boundaries. Content is never
 * changed, dropped or reordered.
 */
internal object PdfPagination {
    /** [lineBottoms]: bottom of each text line inside the block, ascending; the last one equals [height]. */
    data class Block(
        val height: Float,
        val before: Float,
        val keepWithNext: Boolean,
        val lineBottoms: List<Float> = listOf(height),
        val gap: Float = 3f,
    )

    /** Draw block [index] from [from] to [to] (block coordinates) at [y] on 1-based [page]. */
    data class Slice(val index: Int, val page: Int, val y: Float, val from: Float, val to: Float)

    /** How many lines of the following block must share the page with the block that introduces it. */
    const val LEAD_LINES = 2

    fun plan(blocks: List<Block>, top: Float, bottom: Float): List<Slice> {
        val out = mutableListOf<Slice>()
        val pageHeight = bottom - top
        var page = 1
        var y = top
        var follows = false
        for ((i, b) in blocks.withIndex()) {
            val atTop = y <= top
            if (!atTop && b.keepWithNext && y + chain(blocks, i) > bottom) { page++; y = top }
            // A block that does not fit moves to the next page whole, unless it follows the heading or label that
            // introduces it: then it flows across the break, so its first lines stay with that heading.
            else if (!atTop && !follows && b.height <= pageHeight && y + b.before + b.height > bottom) { page++; y = top }
            y += b.before
            follows = b.keepWithNext
            if (b.height <= 0f) { y += b.gap; continue }
            if (y + b.height <= bottom) { out += Slice(i, page, y, 0f, b.height); y += b.height + b.gap; continue }
            // Split at line boundaries: a block taller than a page, or one that follows its heading.
            var from = 0f
            while (from < b.height - 0.01f) {
                val room = bottom - y
                val fit = b.lineBottoms.lastOrNull { it > from && it - from <= room }
                val to = when {
                    fit != null -> fit
                    y > top -> { page++; y = top; continue }
                    else -> b.lineBottoms.first { it > from }  // a single line taller than a page: draw it anyway
                }
                out += Slice(i, page, y, from, to)
                y += to - from
                from = to
                if (from < b.height - 0.01f) { page++; y = top }
            }
            y += b.gap
        }
        return out
    }

    /** Space needed for block [i], every keep-with-next block after it, and the first lines of the block they introduce. */
    private fun chain(blocks: List<Block>, i: Int): Float {
        var need = 0f
        var j = i
        while (j < blocks.size) {
            val b = blocks[j]
            if (b.keepWithNext && j + 1 < blocks.size) { need += b.before + b.height + b.gap; j++ }
            else { need += b.before + (b.lineBottoms.getOrNull(LEAD_LINES - 1) ?: b.height); break }
        }
        return need
    }
}

/** Builds the offline interactive HTML report with the same coverage statements as the Markdown/PDF export. */
object HtmlExporter {
    const val MIME = "text/html"

    fun build(
        result: AuditResult, sessions: List<AuditSession>, timingProfile: String?, sample: Boolean,
        fixes: List<FixTemplate>, citations: CitationService, catalog: TrackerCatalog,
        /** The legal lens selected in Settings; it changes the quoted law, never the evidence. */
        profile: LegalProfile = LegalProfile.INDIA,
        /** "Play label vs what this check saw" as an HTML section (PlayLabelReport.html); empty when no label is attached. */
        playLabelSection: String = "",
    ): String = com.dpdpxray.core.datasafety.PlayLabelReport.insertHtml(HtmlReport.build(
        result, sessions, fixes, citations, catalog,
        HtmlCoverage(
            statements = EvidenceReport.coverageNotes(sessions, timingProfile, sample),
            scope = EvidenceReport.LOCAL_SCOPE,
            noGaps = EvidenceReport.NO_GAPS,
            sample = sample,
            sampleNotes = if (sample) sessions.flatMap { it.notes }.distinct() else emptyList(),
        ),
        profile = profile,
    ), playLabelSection)

    fun write(html: String, out: File): File {
        out.parentFile?.mkdirs()
        out.writeText(html, Charsets.UTF_8)
        return out
    }
}

object ExportManager {
    /** Copies to the shared Downloads folder, where any phone-to-laptop file transfer can pick it up. */
    fun saveToDownloads(context: Context, file: File, displayName: String, mime: String): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TraceVeil")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
        context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }.isSuccess

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares several files in one go (an export and its signature file); a single file is shared as above. */
    fun share(context: Context, files: List<File>, mime: String) {
        if (files.size <= 1) {
            files.firstOrNull()?.let { share(context, it, mime) }
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The read grant covers the URIs in ClipData, so every file is readable by the app the person picks.
        send.clipData = ClipData.newRawUri(files.first().name, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        context.startActivity(Intent.createChooser(send, "Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** With clipboard sync to a laptop, a copied fix can be pasted straight into Android Studio. */
    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
