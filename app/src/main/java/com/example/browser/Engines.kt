package com.example.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

// ---------- what an engine tells the app ----------

interface EngineCallbacks {
    fun onUrlChanged(url: String)
    fun onLoading(loading: Boolean, percent: Int)
    fun onNavState(canGoBack: Boolean, canGoForward: Boolean)
    fun onAppLink(uri: Uri)
    fun onNewPage(url: String)
    fun onFullscreen(enabled: Boolean, customView: View?)
    fun onEngineDied(message: String)
    fun onNeedsGecko(url: String)
}

// ---------- what the app can ask an engine to do ----------

interface BrowserEngine {
    fun attach(container: FrameLayout)
    fun load(url: String)
    fun reload()
    fun goBack()
    fun goForward()
    fun applyDesktop(enabled: Boolean)
    fun setImagesBlocked(blocked: Boolean)
    fun currentUrl(): String
    fun setActive(active: Boolean)
    fun exitFullscreen()
    fun destroy()
}

// ---------- link helpers shared by both engines ----------

object Links {
    private val mobileToWww = setOf(
        "m.youtube.com", "m.facebook.com", "m.twitter.com", "mobile.twitter.com",
        "m.imdb.com", "m.tiktok.com", "m.twitch.tv", "m.soundcloud.com"
    )

    fun isInternal(scheme: String): Boolean =
        scheme in listOf("about", "data", "blob", "javascript", "resource", "view-source")

    // In desktop mode, send a few known mobile addresses to their desktop version
    fun desktopUrl(url: String, desktop: Boolean): String {
        if (!desktop) return url
        val host = Uri.parse(url).host ?: return url
        val newHost = when {
            host in mobileToWww -> "www." + host.substringAfter('.')
            host.endsWith(".m.wikipedia.org") || host.endsWith(".m.wikimedia.org") ||
                    host.endsWith(".m.wiktionary.org") -> host.replace(".m.", ".")
            else -> return url
        }
        return url.replaceFirst("//$host", "//$newHost")
    }
}

// =====================================================================
//  WebView engine (Chrome's engine, built into the phone)
// =====================================================================

class WebViewEngine(
    private val activity: Activity,
    private val cb: EngineCallbacks,
    private var desktop: Boolean,
    private var imagesBlocked: Boolean
) : BrowserEngine {

    private lateinit var web: WebView
    private lateinit var container: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var desktopScript: ScriptHandler? = null
    private var mobileUA = ""
    private var desktopUA = ""
    private var chromeVersion = "124.0.0.0"
    private var destroyed = false

    // Runs before every page's own scripts when desktop mode is on:
    // forces a wide desktop layout and hides touch-screen signals
    private val desktopJs = """
        (function () {
          try {
            var W = 1280;
            function fixMeta() {
              var m = document.querySelector('meta[name="viewport"]');
              if (!m) {
                m = document.createElement('meta');
                m.name = 'viewport';
                (document.head || document.documentElement).appendChild(m);
              }
              if (m.getAttribute('content') !== 'width=' + W) {
                m.setAttribute('content', 'width=' + W);
              }
            }
            new MutationObserver(function () {
              var m = document.querySelector('meta[name="viewport"]');
              if (m && m.getAttribute('content') !== 'width=' + W) {
                m.setAttribute('content', 'width=' + W);
              }
            }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
            document.addEventListener('DOMContentLoaded', fixMeta);
            Object.defineProperty(navigator, 'maxTouchPoints', { get: function () { return 0; } });
            Object.defineProperty(navigator, 'platform', { get: function () { return 'Win32'; } });
          } catch (e) {}
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(container: FrameLayout) {
        this.container = container
        web = WebView(activity)
        container.addView(
            web, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)
        s.mediaPlaybackRequiresUserGesture = false

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }

        // Build the two browser IDs from the phone's real WebView version
        val base = s.userAgentString
        chromeVersion = Regex("Chrome/([0-9.]+)").find(base)?.groupValues?.get(1) ?: "124.0.0.0"
        mobileUA = base.replace("; wv", "").replace("Version/4.0 ", "")
        desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"

        applyDesktop(desktop)
        setImagesBlocked(imagesBlocked)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                cb.onUrlChanged(url)
                cb.onLoading(true, 0)
            }

            override fun onPageFinished(view: WebView, url: String) {
                cb.onLoading(false, 100)
                CookieManager.getInstance().flush()
                cb.onNavState(view.canGoBack(), view.canGoForward())
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                cb.onUrlChanged(url)
                cb.onNavState(view.canGoBack(), view.canGoForward())
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme ?: ""
                if (scheme == "http" || scheme == "https") {
                    // Google blocks sign-in inside WebView: offer GeckoView instead
                    if (uri.host == "accounts.google.com" && request.isForMainFrame) {
                        cb.onNeedsGecko(uri.toString())
                        return true
                    }
                    val target = uri.toString()
                    val fixed = Links.desktopUrl(target, desktop)
                    if (fixed != target && request.isForMainFrame) {
                        view.loadUrl(fixed)
                        return true
                    }
                    return false
                }
                if (Links.isInternal(scheme)) return false
                cb.onAppLink(uri)
                return true
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val message = if (detail.didCrash()) {
                    "That page crashed, so the browser restarted"
                } else {
                    "The page ran out of memory and was closed"
                }
                web.post { cb.onEngineDied(message) }
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                cb.onLoading(newProgress < 100, newProgress)
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                cb.onFullscreen(true, view)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val temp = WebView(activity)
                temp.settings.javaScriptEnabled = true
                temp.settings.domStorageEnabled = true
                var done = false

                fun deliver(target: String) {
                    if (done || target.isEmpty() || target == "about:blank") return
                    done = true
                    temp.stopLoading()
                    web.post { temp.destroy() }
                    val uri = Uri.parse(target)
                    val scheme = uri.scheme ?: ""
                    if (scheme == "http" || scheme == "https") {
                        cb.onNewPage(target)
                    } else if (!Links.isInternal(scheme)) {
                        cb.onAppLink(uri)
                    }
                }

                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        if (target == "about:blank") return false
                        deliver(target)
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        deliver(url)
                    }
                }

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun desktopMetadata(): UserAgentMetadata {
        val major = chromeVersion.substringBefore('.')
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion(major).setFullVersion(chromeVersion).build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome").setMajorVersion(major).setFullVersion(chromeVersion).build(),
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not-A.Brand").setMajorVersion("24").setFullVersion("24.0.0.0").build()
        )
        return UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion(chromeVersion)
            .setPlatform("Windows")
            .setPlatformVersion("10.0.0")
            .setArchitecture("x86")
            .setBitness(64)
            .setModel("")
            .setMobile(false)
            .build()
    }

    override fun applyDesktop(enabled: Boolean) {
        desktop = enabled
        web.settings.userAgentString = if (enabled) desktopUA else mobileUA

        // Client hints (what navigator.userAgentData and Sec-CH-UA-* headers say)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            try {
                WebSettingsCompat.setUserAgentMetadata(
                    web.settings,
                    if (enabled) desktopMetadata() else UserAgentMetadata.Builder().build()
                )
            } catch (e: Exception) {
                // keep going: the browser ID alone still works on most sites
            }
        }

        // Page script that forces the wide desktop layout
        desktopScript?.remove()
        desktopScript = null
        if (enabled && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                desktopScript = WebViewCompat.addDocumentStartJavaScript(web, desktopJs, setOf("*"))
            } catch (e: Exception) {
                // ignore: layout falls back to the site's own choice
            }
        }
    }

    override fun setImagesBlocked(blocked: Boolean) {
        imagesBlocked = blocked
        web.settings.blockNetworkImage = blocked
    }

    override fun load(url: String) {
        web.loadUrl(Links.desktopUrl(url, desktop))
    }

    override fun reload() = web.reload()
    override fun goBack() = web.goBack()
    override fun goForward() = web.goForward()
    override fun currentUrl(): String = web.url ?: ""

    // WebView keeps running in the background, which keeps audio playing
    override fun setActive(active: Boolean) {}

    override fun exitFullscreen() {
        if (customView == null) return
        customView = null
        cb.onFullscreen(false, null)
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        desktopScript?.remove()
        web.stopLoading()
        container.removeView(web)
        web.destroy()
    }
}

// =====================================================================
//  GeckoView engine (Firefox's engine, bundled in the app)
// =====================================================================

class GeckoEngine(
    private val activity: Activity,
    private val cb: EngineCallbacks,
    private var desktop: Boolean
) : BrowserEngine {

    companion object {
        private var runtime: GeckoRuntime? = null
    }

    private lateinit var geckoView: GeckoView
    private lateinit var container: FrameLayout
    private lateinit var session: GeckoSession

    private var lastUrl = ""
    private var backOk = false
    private var forwardOk = false

    override fun attach(container: FrameLayout) {
        this.container = container
        geckoView = GeckoView(activity)
        container.addView(
            geckoView, 0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // One GeckoRuntime per app process
        val rt = runtime ?: GeckoRuntime.create(activity.applicationContext).also { runtime = it }

        session = GeckoSession()
        applyDesktop(desktop)
        setupDelegates()
        session.open(rt)
        geckoView.setSession(session)
    }

    private fun allow(): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
    private fun deny(): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.DENY)

    private fun setupDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url != null) {
                    lastUrl = url
                    cb.onUrlChanged(url)
                }
            }

            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) {
                backOk = canGoBack
                cb.onNavState(backOk, forwardOk)
            }

            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) {
                forwardOk = canGoForward
                cb.onNavState(backOk, forwardOk)
            }

            override fun onLoadRequest(
                s: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val uriStr = request.uri
                val scheme = Uri.parse(uriStr).scheme ?: ""
                return when {
                    scheme == "http" || scheme == "https" -> {
                        if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                            geckoView.post { cb.onNewPage(uriStr) }
                            deny()
                        } else {
                            val fixed = Links.desktopUrl(uriStr, desktop)
                            if (fixed != uriStr) {
                                geckoView.post { session.loadUri(fixed) }
                                deny()
                            } else {
                                allow()
                            }
                        }
                    }
                    Links.isInternal(scheme) -> allow()
                    else -> {
                        geckoView.post { cb.onAppLink(Uri.parse(uriStr)) }
                        deny()
                    }
                }
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri.startsWith("http")) {
                    geckoView.post { cb.onNewPage(uri) }
                }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                cb.onLoading(true, 0)
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                cb.onLoading(false, 100)
            }

            override fun onProgressChange(s: GeckoSession, value: Int) {
                cb.onLoading(true, value)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(s: GeckoSession, fullScreen: Boolean) {
                cb.onFullscreen(fullScreen, null)
            }

            override fun onCrash(s: GeckoSession) {
                geckoView.post { cb.onEngineDied("That page crashed, so the browser restarted") }
            }

            override fun onKill(s: GeckoSession) {
                geckoView.post { cb.onEngineDied("The page ran out of memory and was closed") }
            }
        }
    }

    override fun applyDesktop(enabled: Boolean) {
        desktop = enabled
        session.settings.setUserAgentMode(
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        session.settings.setViewportMode(
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
    }

    // Not available in the GeckoView engine yet
    override fun setImagesBlocked(blocked: Boolean) {}

    override fun load(url: String) {
        session.loadUri(Links.desktopUrl(url, desktop))
    }

    override fun reload() = session.reload()
    override fun goBack() = session.goBack()
    override fun goForward() = session.goForward()
    override fun currentUrl(): String = lastUrl

    override fun setActive(active: Boolean) {
        session.setActive(active)
    }

    override fun exitFullscreen() {
        session.exitFullScreen()
    }

    override fun destroy() {
        geckoView.releaseSession()
        session.close()
        container.removeView(geckoView)
    }
}
