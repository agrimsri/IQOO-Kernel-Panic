package com.dpdpxray.core.web

import com.dpdpxray.core.model.Bounds
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** A key of the page's own storage, as the probe read it (value length only). */
data class WebStorageKey(val key: String, val valueLength: Int)

/** Metadata of a first-party cookie from the page's Cookie Store API. No value. */
data class WebCookieMeta(val name: String, val domain: String?, val path: String?, val expiresEpochMs: Long?)

/** A click the page saw. [trusted] = a real tap by the person; false = a script click (TraceVeil's own or the page's). */
data class WebClick(val epochMs: Long, val text: String, val trusted: Boolean)

/** One reading of the page by the in-page probe ("scan"). */
data class WebProbeResult(
    val url: String?,
    /** The banner read from the page, or a FRAME_ONLY banner for a consent tool's frame; null when none was found. */
    val banner: WebBanner?,
    val localStorage: List<WebStorageKey> = emptyList(),
    val sessionStorage: List<WebStorageKey> = emptyList(),
    val cookieMeta: List<WebCookieMeta> = emptyList(),
    /** Resource Timing entries new since the last scan: URL and the type the page reported. */
    val resourceTypes: List<Pair<String, WebResourceType>> = emptyList(),
    val clicks: List<WebClick> = emptyList(),
    val error: String? = null,
)

/** The result of asking the probe to click a control. */
data class WebClickResult(val ok: Boolean, val text: String?)

/**
 * The in-page probe (resource web/consent_probe.js) and the parser for what it returns. The script is a function
 * expression `(function (mode, arg) {...})`; [call] appends the arguments. The parser is tolerant: unknown fields are
 * ignored, missing ones are empty, oversized lists and text are cut to [WebLimits], and anything unreadable gives null.
 */
object WebProbe {
    const val RESOURCE = "/web/consent_probe.js"

    val script: String by lazy {
        WebProbe::class.java.getResourceAsStream(RESOURCE)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().trim() }
            ?: error("consent_probe.js missing from resources")
    }

    fun scanCall(): String = "$script('scan', null)"

    fun clickCall(index: Int): String = "$script('click', ${index.coerceAtLeast(0)})"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Parses the value the browser returned for [scanCall]. The browser hands back the returned object as JSON text; a
     * JSON string holding that text is unwrapped once. Returns null for "null", empty or unreadable text.
     */
    fun parseScan(raw: String?): WebProbeResult? {
        val dto = decode<ScanDto>(raw) ?: return null
        if (dto.error != null && dto.banner == null && dto.url == null) return WebProbeResult(null, null, error = dto.error.take(200))
        val viewport = dto.vp
        val banner = dto.banner?.toBanner(viewport) ?: dto.frames.firstOrNull()?.let { f ->
            WebBanner(cmp = f.cmp.takeIf { it.isNotBlank() && it != GENERIC_FRAME }, detection = WebBannerDetection.FRAME_ONLY,
                frameHost = f.host.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT),
                viewportWidth = viewport?.w, viewportHeight = viewport?.h, devicePixelRatio = viewport?.dpr)
        }
        return WebProbeResult(
            url = dto.url?.take(WebLimits.MAX_URL),
            banner = banner,
            localStorage = dto.ls.take(WebLimits.MAX_STORAGE_KEYS).map { WebStorageKey(it.k.take(WebLimits.MAX_KEY), it.n.coerceAtLeast(0)) },
            sessionStorage = dto.ss.take(WebLimits.MAX_STORAGE_KEYS).map { WebStorageKey(it.k.take(WebLimits.MAX_KEY), it.n.coerceAtLeast(0)) },
            cookieMeta = dto.fpc.take(WebLimits.MAX_COOKIES).map { WebCookieMeta(it.name.take(WebLimits.MAX_KEY), it.domain, it.path, it.expires?.toLong()) },
            resourceTypes = dto.res.mapNotNull { pair ->
                val url = pair.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                url.take(WebLimits.MAX_URL) to initiatorType(pair.getOrNull(1))
            },
            clicks = dto.clicks.take(40).map { WebClick(it.t.toLong(), it.text.take(160), it.trusted) },
            error = dto.error?.take(200),
        )
    }

    fun parseClick(raw: String?): WebClickResult? = decode<ClickDto>(raw)?.let { WebClickResult(it.ok, it.text?.take(160)) }

    /** The Resource Timing initiatorType mapped to a request type ("img" and "image" are images, "beacon" a beacon...). */
    fun initiatorType(t: String?): WebResourceType = when (t?.lowercase(Locale.ROOT)) {
        "script" -> WebResourceType.SCRIPT
        "img", "image", "input" -> WebResourceType.IMAGE
        "css", "link" -> WebResourceType.STYLESHEET
        "xmlhttprequest", "fetch" -> WebResourceType.XHR_FETCH
        "beacon" -> WebResourceType.BEACON
        "iframe", "frame" -> WebResourceType.SUBFRAME
        "video", "audio", "track" -> WebResourceType.MEDIA
        "navigation" -> WebResourceType.DOCUMENT
        null, "" -> WebResourceType.UNKNOWN
        else -> WebResourceType.OTHER
    }

    private inline fun <reified T> decode(raw: String?): T? {
        var text = raw?.trim() ?: return null
        if (text.isEmpty() || text == "null" || text == "undefined") return null
        if (text.startsWith("\"")) text = runCatching { Json.parseToJsonElement(text).jsonPrimitive.content }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<T>(text) }.getOrNull()
    }

    private const val GENERIC_FRAME = "Consent frame"

    // --- What the script returns --------------------------------------------------------------------------------------

    @Serializable
    private data class ScanDto(
        val v: Int = 0,
        val url: String? = null,
        val error: String? = null,
        val banner: BannerDto? = null,
        val frames: List<FrameDto> = emptyList(),
        val vp: ViewportDto? = null,
        val ls: List<KeyDto> = emptyList(),
        val ss: List<KeyDto> = emptyList(),
        val fpc: List<CookieDto> = emptyList(),
        val res: List<List<String>> = emptyList(),
        val clicks: List<ClickEventDto> = emptyList(),
    )

    @Serializable
    private data class BannerDto(
        val cmp: String? = null,
        val how: String = "generic",
        val texts: List<String> = emptyList(),
        val controls: List<ControlDto> = emptyList(),
        val boxes: List<BoxDto> = emptyList(),
    ) {
        fun toBanner(vp: ViewportDto?) = WebBanner(
            cmp = cmp?.takeIf { it.isNotBlank() },
            detection = if (how == "cmp") WebBannerDetection.CMP_ELEMENT else WebBannerDetection.GENERIC_TEXT,
            texts = texts.map { it.trim().take(WebLimits.MAX_TEXT) }.filter { it.isNotEmpty() }.distinct().take(WebLimits.MAX_TEXTS),
            controls = controls.filter { it.text.isNotBlank() }.take(WebLimits.MAX_CONTROLS).map { c ->
                WebControl(
                    index = c.i, text = c.text.trim().take(160), cmpRole = c.role?.lowercase(Locale.ROOT)?.takeIf { it in ROLES },
                    isButton = c.btn, visible = c.vis, secondLayer = c.l2,
                    bounds = if (c.w > 0 && c.h > 0) Bounds(c.x, c.y, c.x + c.w, c.y + c.h) else null,
                )
            },
            checkboxes = boxes.take(WebLimits.MAX_CHECKBOXES).map { WebCheckbox(it.label.trim().take(120), it.checked, it.disabled, it.vis, it.l2) },
            viewportWidth = vp?.w, viewportHeight = vp?.h, devicePixelRatio = vp?.dpr,
        )
    }

    private val ROLES = setOf("accept", "reject", "settings", "save", "close")

    @Serializable
    private data class ControlDto(
        val i: Int = -1, val text: String = "", val role: String? = null, val btn: Boolean = false, val vis: Boolean = false,
        val l2: Boolean = false, val x: Int = 0, val y: Int = 0, val w: Int = 0, val h: Int = 0,
    )

    @Serializable
    private data class BoxDto(val label: String = "", val checked: Boolean = false, val disabled: Boolean = false, val vis: Boolean = false, val l2: Boolean = false)

    @Serializable
    private data class FrameDto(val cmp: String = "", val host: String = "", val id: String = "")

    @Serializable
    private data class ViewportDto(val w: Int? = null, val h: Int? = null, val dpr: Double? = null)

    @Serializable
    private data class KeyDto(val k: String = "", val n: Int = 0)

    @Serializable
    private data class CookieDto(val name: String = "", val domain: String? = null, val path: String? = null, val expires: Double? = null)

    @Serializable
    private data class ClickEventDto(val t: Double = 0.0, val text: String = "", val trusted: Boolean = false)

    @Serializable
    private data class ClickDto(val v: Int = 0, val ok: Boolean = false, @SerialName("text") val text: String? = null)
}
