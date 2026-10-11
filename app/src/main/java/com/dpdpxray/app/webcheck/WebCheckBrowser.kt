package com.dpdpxray.app.webcheck

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dpdpxray.core.web.CookieReading
import com.dpdpxray.core.web.RawWebRequest
import com.dpdpxray.core.web.WebLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The website check's own browser: Android's browser component inside TraceVeil, used only while a website check the
 * person started is on screen. This is the only file in TraceVeil that uses that component (NetworkCodeScanTest keeps it
 * so, and the trust screen lists it as a network path).
 *
 * What it does: loads the address the person typed; records every request the page (or its service workers) asks it to
 * make, with the full URL, before letting it go out unchanged; runs TraceVeil's in-page probe in the top page when asked;
 * reads Android's cookie store for the hosts the page contacted; and clears its own cookies, storage and cache. What it
 * never does: change, block or answer a request itself, open another app, allow camera, microphone or location, show the
 * page's pop-up dialogs, or proceed past a certificate error. Google Safe Browsing and usage statistics are switched off
 * for it in the manifest, so the browser itself sends nothing beyond what the site asks for.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebCheckBrowser(context: Context) {
    private val app = context.applicationContext

    /** The browser view, on a wrapper of the application context so it can move between screens. */
    val view: WebView = WebView(MutableContextWrapper(app))

    private val lock = Any()
    private var recording = false
    private val requests = ArrayList<RawWebRequest>()
    private var dropped = 0
    private val errors = linkedSetOf<String>()

    private val _seen = MutableStateFlow<List<RawWebRequest>>(emptyList())

    /** Requests recorded so far in the current plan (refreshed as they arrive), for the live screen's counters. */
    val seen: StateFlow<List<RawWebRequest>> = _seen.asStateFlow()

    /** The main page finished loading at least once in this plan. */
    @Volatile var pageLoaded = false
        private set

    /** The page's process stopped (crashed or was killed); the plan cannot continue. */
    @Volatile var rendererGone = false
        private set

    init {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // A normal visit in most browsers: third-party cookies are allowed, so the check can see them being set.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        current = this
        runCatching { ServiceWorkerController.getInstance().setServiceWorkerClient(WorkerClient) }
    }

    /** Starts a new plan's request list. */
    fun startRecording() = synchronized(lock) {
        requests.clear(); dropped = 0; errors.clear(); recording = true
        pageLoaded = false; rendererGone = false
        _seen.value = emptyList()
    }

    /** Stops recording and returns the plan's requests, how many were not kept, and the load errors. */
    fun stopRecording(): Triple<List<RawWebRequest>, Int, List<String>> = synchronized(lock) {
        recording = false
        Triple(requests.toList(), dropped, errors.toList())
    }

    private fun record(r: WebResourceRequest, serviceWorker: Boolean) {
        val url = r.url?.toString() ?: return
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val headers = r.requestHeaders.orEmpty()
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val entry = RawWebRequest(
            tNanos = SystemClock.elapsedRealtimeNanos(), url = url.take(WebLimits.MAX_URL), method = r.method ?: "GET",
            mainFrame = !serviceWorker && r.isForMainFrame, accept = header("Accept"), fetchDest = header("Sec-Fetch-Dest"),
            serviceWorker = serviceWorker,
        )
        synchronized(lock) {
            if (!recording) return
            if (requests.size >= WebLimits.MAX_REQUESTS) { dropped++; return }
            requests += entry
            _seen.value = requests.toList()
        }
    }

    private fun error(text: String) = synchronized(lock) { if (recording && errors.size < 10) errors += text }

    /** Loads [url] (main thread). */
    suspend fun load(url: String) = withContext(Dispatchers.Main) { view.loadUrl(url) }

    /** Runs [script] in the top page and returns the browser's JSON text of its result; null when no page answered in time. */
    suspend fun evaluate(script: String, timeoutMs: Long = 4_000): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont -> view.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) } }
        }
    }

    /** What Android's cookie store holds for each host (name and value; values never leave the phone's memory as text). */
    suspend fun readCookies(hosts: Collection<String>): List<CookieReading> = withContext(Dispatchers.Main) {
        val cm = CookieManager.getInstance()
        hosts.distinct().flatMap { host ->
            val header = runCatching { cm.getCookie("https://$host/") }.getOrNull().orEmpty()
            parseCookieHeader(header).map { (name, value) -> CookieReading(host, name, value) }
        }
    }

    /**
     * Clears this browser's cookies, storage (local and session storage, databases, service workers) and cache, then
     * checks that nothing is left. Returns false when something could not be confirmed cleared.
     */
    suspend fun clearProfile(): Boolean = withContext(Dispatchers.Main) {
        view.stopLoading()
        view.loadUrl("about:blank")
        // The site's page keeps running (and writing cookies or storage) until the blank page replaces it: wait for that.
        withTimeoutOrNull(3_000) { while (view.url != "about:blank" || view.progress < 100) delay(50) }
        val cm = CookieManager.getInstance()
        // Storage is deleted in the background, and a frame or worker of the old page can still write while it winds
        // down, so each round clears again and then checks; up to about 8 seconds in all.
        repeat(16) {
            suspendCancellableCoroutine { cont -> cm.removeAllCookies(ValueCallback { if (cont.isActive) cont.resume(Unit) }) }
            cm.removeSessionCookies(null)
            cm.flush()
            WebStorage.getInstance().deleteAllData()
            view.clearCache(true)
            view.clearHistory()
            view.clearFormData()
            runCatching { WebViewDatabase.getInstance(app).clearHttpAuthUsernamePassword() }
            delay(500)
            val origins = suspendCancellableCoroutine<Map<*, *>?> { cont ->
                WebStorage.getInstance().getOrigins(ValueCallback { m -> if (cont.isActive) cont.resume(m as? Map<*, *>) })
            }
            // An origin can stay listed with nothing stored; only stored bytes count as left over.
            val stored = origins.orEmpty().values.count { ((it as? WebStorage.Origin)?.usage ?: 1L) > 0L }
            if (!cm.hasCookies() && stored == 0) return@withContext true
        }
        false
    }

    /** Stops the page and frees the browser. TraceVeil clears the profile before calling this. */
    fun destroy() {
        synchronized(lock) { recording = false }
        if (current === this) current = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            record(request, serviceWorker = false)
            return null // the request goes out exactly as the page made it
        }

        /** Only http and https load here; links to other apps (intent:, tel:, market:, ...) are never opened. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url?.scheme?.lowercase()
            return scheme != "http" && scheme != "https"
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {}

        override fun onPageFinished(view: WebView, url: String?) {
            if (url != null && url != "about:blank") pageLoaded = true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) error("The page did not load: ${error.description}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) error("The site answered HTTP ${errorResponse.statusCode}")
        }

        /** A certificate problem is never ignored: the page is not loaded. */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            error("Certificate error: the connection was not secure, so the page was not loaded")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            rendererGone = true
            error("The page stopped working in the browser")
            return true
        }
    }

    /** The page's dialogs and permission requests are refused; it can never reach the camera, microphone or location. */
    private class Chrome : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.confirm(); return true }
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) = callback.invoke(origin, false, false)
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
    }

    /** Requests made by the site's service workers, recorded like the page's own. */
    private object WorkerClient : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            current?.record(request, serviceWorker = true)
            return null
        }
    }

    companion object {
        @Volatile private var current: WebCheckBrowser? = null

        /** "a=1; b=2" (the cookie store's format) as name/value pairs; a part without "=" is a value with no name. */
        fun parseCookieHeader(header: String): List<Pair<String, String>> = header.split(';')
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { part -> part.indexOf('=').let { i -> if (i < 0) "" to part else part.substring(0, i).trim() to part.substring(i + 1).trim() } }
    }
}

/** Shows the website check's browser on screen; it must be laid out for the page to render and the banner to be read. */
@Composable
fun WebCheckBrowserView(browser: WebCheckBrowser, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { _ -> browser.view.also { v -> (v.parent as? ViewGroup)?.removeView(v) } },
        modifier = modifier,
        onRelease = { v -> (v.parent as? ViewGroup)?.removeView(v) },
    )
}
