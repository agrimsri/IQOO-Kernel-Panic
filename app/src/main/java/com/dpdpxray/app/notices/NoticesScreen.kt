package com.dpdpxray.app.notices

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.dpdpxray.app.BuildConfig
import com.dpdpxray.app.ui.components.ChipFlow
import com.dpdpxray.app.ui.components.EmptyHint
import com.dpdpxray.app.ui.components.Hairline
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.SectionCard
import com.dpdpxray.app.ui.components.SkeletonBar
import com.dpdpxray.app.ui.components.Tag
import com.dpdpxray.app.ui.components.TopBar
import com.dpdpxray.app.ui.components.pressClickable
import com.dpdpxray.app.ui.components.reveal
import com.dpdpxray.app.ui.components.shimmer
import com.dpdpxray.app.ui.guide.Glyph
import com.dpdpxray.app.ui.guide.GlyphIcon
import com.dpdpxray.app.ui.guide.LinkAction
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.Mono
import com.dpdpxray.app.ui.theme.Radii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** The list: one card per group; each row names a component, its version and licence, and what it is used for. */
@Composable
fun NoticesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val notices = remember { OpenSourceNotices.all(BuildConfig.VERSION_NAME) }
    Column(Modifier.fillMaxSize()) {
        TopBar(OpenSourceNotices.TITLE, onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "intro") {
                Column(Modifier.reveal(0), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(OpenSourceNotices.INTRO, style = MaterialTheme.typography.bodyLarge, color = Film.bone)
                    Note(OpenSourceNotices.NOT_ADVICE)
                }
            }
            NoticeGroup.entries.forEachIndexed { gi, group ->
                val rows = notices.filter { it.group == group }
                if (rows.isNotEmpty()) item(key = group.name) {
                    SectionCard(Modifier.reveal(1 + gi), title = "${group.title} (${rows.size})", glyph = Glyph.BOOK) {
                        rows.forEachIndexed { i, c ->
                            if (i > 0) Hairline()
                            ComponentRow(c) { onOpen(c.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(c: NoticeComponent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .pressClickable(shape = RoundedCornerShape(Radii.small), onClickLabel = "Read the licence", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleMedium, color = Film.bone)
            Text("${c.version} · ${c.licence}", style = MaterialTheme.typography.bodyMedium, color = Film.scan, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(c.usedFor, style = MaterialTheme.typography.bodyMedium, color = Film.boneDim)
        }
        Spacer(Modifier.width(8.dp))
        GlyphIcon(Glyph.CHEVRON_RIGHT, Film.boneDim, size = 18.dp)
    }
}

/**
 * One component: version, licence and use, then the full text of the chosen licence or notice file, read from the
 * APK's assets. Text files show as-is, in chunks of lines that the list lays out lazily (so the 2 MB LiteRT-LM notice
 * scrolls smoothly); PDF files are drawn page by page by Android's own PDF renderer from an unchanged copy, and can
 * also be opened in the phone's PDF viewer.
 */
@Composable
fun NoticeDetailScreen(id: String, onBack: () -> Unit) {
    val c = remember(id) { OpenSourceNotices.byId(id, BuildConfig.VERSION_NAME) }
    Column(Modifier.fillMaxSize()) {
        TopBar(c?.name ?: OpenSourceNotices.TITLE, onBack)
        if (c == null) {
            EmptyHint("This notice is not in this build.", Modifier.padding(20.dp), glyph = Glyph.WARN)
            return@Column
        }
        var selected by rememberSaveable(id) { mutableIntStateOf(0) }
        val text = c.texts[selected.coerceIn(c.texts.indices)]
        val ctx = LocalContext.current
        val content by produceState<NoticeContent>(NoticeContent.Loading, text.asset) {
            value = NoticeContent.Loading
            value = withContext(Dispatchers.IO) { load(ctx, text) }
        }
        val pdf = (content as? NoticeContent.Pdf)?.doc
        DisposableEffect(pdf) { onDispose { pdf?.close() } }
        var zoom by rememberSaveable(text.asset) { mutableStateOf(false) }
        val screen = LocalConfiguration.current.screenWidthDp.dp - 40.dp
        val pageWidth = if (zoom) screen * 2 else screen
        val widthPx = with(LocalDensity.current) { pageWidth.roundToPx() }.coerceIn(320, 2400)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "facts") {
                SectionCard(Modifier.reveal(0)) {
                    Fact("Version", c.version)
                    Hairline(Modifier.padding(vertical = 4.dp))
                    Fact("Licence", c.licence)
                    Hairline(Modifier.padding(vertical = 4.dp))
                    Fact("Used for", c.usedFor)
                }
            }
            if (c.texts.size > 1) item(key = "choose") {
                ChipFlow(Modifier.reveal(1)) {
                    c.texts.forEachIndexed { i, t ->
                        Tag(
                            t.title, if (i == selected) Film.scan else Film.boneDim,
                            Modifier.clip(RoundedCornerShape(10.dp)).pressClickable(onClickLabel = "Show ${t.title}") { selected = i },
                        )
                    }
                }
            }
            item(key = "heading-${text.asset}") {
                Column(Modifier.padding(top = 4.dp)) {
                    Text(text.title, style = MaterialTheme.typography.titleMedium, color = Film.bone, modifier = Modifier.semantics { heading() })
                    Text("Text from: ${text.source}", style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
                }
            }
            when (val shown = content) {
                NoticeContent.Loading -> item(key = "loading") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { repeat(6) { SkeletonBar(Modifier.fillMaxWidth(0.9f).shimmer()) } }
                }
                is NoticeContent.Failed -> item(key = "failed") {
                    Column {
                        EmptyHint("This text could not be shown here.", glyph = Glyph.WARN)
                        shown.file?.let { f -> LinkAction("Open in a PDF viewer", { openPdf(ctx, f) }, glyph = Glyph.BOOK) }
                    }
                }
                is NoticeContent.Text -> items(shown.chunks.size, key = { "chunk-${text.asset}-$it" }) { i ->
                    Text(
                        shown.chunks[i], fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, color = Film.bone,
                        modifier = Modifier.fillMaxWidth().background(Film.base.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                is NoticeContent.Pdf -> {
                    item(key = "pdf-actions") {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LinkAction(if (zoom) "Fit to width" else "Zoom in", { zoom = !zoom }, glyph = Glyph.SEARCH)
                                LinkAction("Open in a PDF viewer", { openPdf(ctx, shown.file) }, glyph = Glyph.BOOK)
                            }
                            Note("${shown.doc.count} pages, shown as published.")
                        }
                    }
                    items(shown.doc.count, key = { "page-${text.asset}-$it" }) { i ->
                        val bmp by produceState<Bitmap?>(null, shown.doc, i, widthPx) {
                            value = withContext(Dispatchers.IO) { shown.doc.render(i, widthPx) }
                        }
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            val b = bmp
                            if (b == null) {
                                SkeletonBar(Modifier.width(pageWidth).height(pageWidth * 1.3f).shimmer())
                            } else {
                                Image(
                                    b.asImageBitmap(), contentDescription = "Page ${i + 1} of ${shown.doc.count} of ${text.title}",
                                    modifier = Modifier.width(pageWidth).aspectRatio(b.width / b.height.toFloat()).clip(RoundedCornerShape(4.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Film.boneDim)
    Text(value, style = MaterialTheme.typography.bodyLarge, color = Film.bone)
}

/** What the detail screen shows for one file: still loading, its text in chunks, an open PDF, or a failure. */
private sealed interface NoticeContent {
    data object Loading : NoticeContent
    class Text(val chunks: List<String>) : NoticeContent
    class Pdf(val doc: PdfDoc, val file: File) : NoticeContent
    class Failed(val file: File?) : NoticeContent
}

/** Reads one bundled file. Blocking: call from Dispatchers.IO. */
private fun load(ctx: Context, text: NoticeText): NoticeContent {
    if (!text.isPdf) {
        return runCatching { NoticeContent.Text(NoticeChunks.split(ctx.assets.open(text.asset).bufferedReader().use { it.readText() })) }
            .getOrElse { NoticeContent.Failed(null) }
    }
    val file = runCatching { copyAsset(ctx, text.asset) }.getOrNull() ?: return NoticeContent.Failed(null)
    return runCatching { NoticeContent.Pdf(PdfDoc(file), file) }.getOrElse { NoticeContent.Failed(file) }
}

/** Copies an asset to the app's cache, unchanged, so PdfRenderer and the phone's PDF viewer can read it. */
private fun copyAsset(ctx: Context, asset: String): File {
    val out = File(File(ctx.cacheDir, "notices").apply { mkdirs() }, asset.substringAfterLast('/'))
    ctx.assets.open(asset).use { input -> out.outputStream().use { input.copyTo(it) } }
    return out
}

/** Opens the copied PDF in the phone's own viewer through TraceVeil's existing FileProvider (read-only grant). */
private fun openPdf(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No PDF viewer on this phone. The pages shown here are the same file.", Toast.LENGTH_LONG).show()
    }
}

/** One open PDF. PdfRenderer allows one open page at a time, so rendering and closing are serialised. */
private class PdfDoc(file: File) : Closeable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private var closed = false
    val count: Int = renderer.pageCount

    /** Page [index] drawn [widthPx] wide on white; null if it cannot be drawn. Blocking: call off the main thread. */
    fun render(index: Int, widthPx: Int): Bitmap? {
        synchronized(this) {
            if (closed) return null
            return runCatching {
                val page = renderer.openPage(index)
                try {
                    val height = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bmp ->
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                } finally {
                    page.close()
                }
            }.getOrNull()
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }
}
