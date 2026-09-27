package com.hermes.agent.data.tools.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hermes.agent.util.net.PublicNetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * A hidden WebView the agent drives: real JavaScript, so pages that render
 * client-side (most shops, dashboards and news sites) have text to read.
 *
 * Locked down for an agent that may be steered by the pages it reads:
 *  - every navigation and every sub-request must be http(s) to a host whose
 *    addresses are all public (the same rule as [PublicNetworkGuard]);
 *  - no file or content access, no downloads, no pop-up windows, and every
 *    camera, microphone or location request is denied.
 * WebView cookies and storage are app-wide (the Home Assistant dashboard keeps
 * its login there), so [close] clears them only for the sites this session
 * visited: a later session starts signed out, and nothing else is touched.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewBrowserEngine(
    private val context: Context,
    private val isAllowedAddress: (InetAddress) -> Boolean = PublicNetworkGuard::isPublicAddress,
) : BrowserEngine {

    private var webView: WebView? = null
    private val lastActivity = AtomicLong(0)
    private val blocked = AtomicReference<String?>(null)
    @Volatile private var loading = false
    private val visitedOrigins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun view(): WebView = withContext(Dispatchers.Main) {
        webView ?: WebView(context).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                setGeolocationEnabled(false)
                userAgentString = userAgentString.replace("; wv", "")
            }
            wv.webViewClient = client
            wv.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
            wv.setDownloadListener { _, _, _, _, _ -> }
            // A phone-sized viewport, so pages lay out and lazy-load as they would on screen.
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, VIEW_WIDTH, VIEW_HEIGHT)
            webView = wv
        }
    }

    /** http(s) to a host whose every address is allowed; resolved here, off the main thread. */
    private fun allowed(uri: Uri?): Boolean {
        val scheme = uri?.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all(isAllowedAddress)
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                blocked.set(request.url.toString())
                return true
            }
            return false // host checked in shouldInterceptRequest, off the main thread
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            lastActivity.set(System.currentTimeMillis())
            if (allowed(request.url)) {
                request.url.let { visitedOrigins += "${it.scheme}://${it.authority}" }
                return null
            }
            if (request.isForMainFrame) blocked.set(request.url.toString())
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loading = true
            lastActivity.set(System.currentTimeMillis())
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            lastActivity.set(System.currentTimeMillis())
        }
    }

    override suspend fun load(url: String, timeoutMs: Long) {
        val wv = view()
        withContext(Dispatchers.Main) {
            loading = true
            lastActivity.set(System.currentTimeMillis())
            wv.loadUrl(url)
        }
        settle(timeoutMs)
    }

    override suspend fun evaluate(script: String): String {
        val wv = view()
        val raw = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value) }
            }
        }
        // evaluateJavascript hands back the result JSON-encoded: our scripts return a
        // JSON string, which arrives as a JSON string literal holding that JSON.
        return runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw ?: "null")
                .let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.toString() }
        }.getOrDefault(raw.orEmpty())
    }

    /** Settled: the page reports complete and nothing was requested for [QUIET_MS]. */
    override suspend fun settle(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            delay(POLL_MS)
            while (true) {
                val quiet = System.currentTimeMillis() - lastActivity.get() >= QUIET_MS
                if (!loading && quiet && evaluate(BrowserScripts.READY_STATE) == "complete") break
                delay(POLL_MS)
            }
        }
    }

    override suspend fun back(timeoutMs: Long): Boolean {
        val wv = view()
        val went = withContext(Dispatchers.Main) {
            if (wv.canGoBack()) {
                loading = true
                wv.goBack()
                true
            } else {
                false
            }
        }
        if (went) settle(timeoutMs)
        return went
    }

    override fun takeBlockedNavigation(): String? = blocked.getAndSet(null)

    override fun close() {
        val wv = webView ?: return
        webView = null
        val origins = visitedOrigins.toList()
        visitedOrigins.clear()
        // Not wv.post: a view that was never attached to a window only runs posted work
        // once attached, so the WebView was never destroyed and nothing was forgotten.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            wv.stopLoading()
            wv.clearHistory()
            wv.destroy()
            forget(origins)
        }
    }

    /** Expires every cookie and drops web storage for [origins], and only those. */
    private fun forget(origins: List<String>) {
        val cookies = CookieManager.getInstance()
        for (origin in origins) {
            cookies.getCookie(origin)?.split(';')?.forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
            }
            android.webkit.WebStorage.getInstance().deleteOrigin(origin)
        }
        cookies.flush()
    }

    private companion object {
        const val VIEW_WIDTH = 1080
        const val VIEW_HEIGHT = 1920
        const val POLL_MS = 250L
        const val QUIET_MS = 800L
    }
}
