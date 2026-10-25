package com.example.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

// Must match the GeckoView version in app/build.gradle.kts (major.minor only)
private const val GECKO_VERSION = "150.0"

// request code used for the file picker (uploads)
private const val REQ_FILE = 4711

private fun toastOn(activity: Activity, message: String) {
    activity.runOnUiThread {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}

// ---------- what an engine tells the app ----------

interface EngineCallbacks {
    fun onUrlChanged(url: String)
    fun onTitleChanged(title: String)
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
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {}
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
//  Copies logins (cookies) saved by GeckoView into WebView,
//  and can remove exactly the cookies it copied
// =====================================================================

object CookieSync {

    private const val PREFS = "cookiesync"
    private const val KEY = "copied"
    private const val SEP = "\u0001"

    private class Row(
        val name: String,
        val value: String,
        val host: String,
        val path: String,
        val expirySec: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val sameSite: Int
    )

    // Looks for Firefox's cookie file anywhere inside the app's own storage
    private fun findCookieDb(dir: File, depth: Int): File? {
        if (depth > 6) return null
        val children = dir.listFiles() ?: return null
        for (f in children) {
            if (f.isFile && f.name == "cookies.sqlite") return f
        }
        for (f in children) {
            if (f.isDirectory) {
                val found = findCookieDb(f, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    // Returns how many cookies were copied (0 if none or if anything went wrong)
    fun geckoToWebView(context: Context): Int {
        try {
            val src = findCookieDb(context.dataDir, 0) ?: return 0

            // Work on a copy so Firefox's live file is never touched
            val tmpDir = File(context.cacheDir, "ckcopy")
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            for (suffix in listOf("", "-wal", "-shm")) {
                val f = File(src.path + suffix)
                if (f.exists()) f.copyTo(File(tmpDir, "cookies.sqlite$suffix"), overwrite = true)
            }

            val rows = ArrayList<Row>()
            val db = SQLiteDatabase.openDatabase(
                File(tmpDir, "cookies.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE
            )
            try {
                db.rawQuery(
                    "SELECT name, value, host, path, expiry, isSecure, isHttpOnly, sameSite, originAttributes FROM moz_cookies",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        // skip cookies from containers / third-party partitions
                        if ((c.getString(8) ?: "").isNotEmpty()) continue
                        var expiry = c.getLong(4)
                        if (expiry > 100_000_000_000L) expiry /= 1000 // milliseconds -> seconds
                        rows.add(
                            Row(
                                c.getString(0) ?: "",
                                c.getString(1) ?: "",
                                c.getString(2) ?: "",
                                c.getString(3) ?: "/",
                                expiry,
                                c.getInt(5) != 0,
                                c.getInt(6) != 0,
                                c.getInt(7)
                            )
                        )
                    }
                }
            } finally {
                db.close()
            }

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            val now = System.currentTimeMillis() / 1000
            val copied = HashSet<String>()
            var count = 0
            for (r in rows) {
                if (r.name.isEmpty() || r.host.isEmpty()) continue
                if (r.expirySec <= now) continue // expired
                val bareHost = r.host.trimStart('.')
                val sb = StringBuilder()
                sb.append(r.name).append('=').append(r.value)
                sb.append("; Path=").append(if (r.path.isEmpty()) "/" else r.path)
                if (r.host.startsWith(".")) sb.append("; Domain=").append(r.host)
                sb.append("; Max-Age=").append(r.expirySec - now)
                if (r.secure) sb.append("; Secure")
                if (r.httpOnly) sb.append("; HttpOnly")
                when (r.sameSite) {
                    1 -> sb.append("; SameSite=Lax")
                    2 -> sb.append("; SameSite=Strict")
                    0 -> if (r.secure) sb.append("; SameSite=None")
                }
                cm.setCookie("https://$bareHost/", sb.toString())
                copied.add(r.host + SEP + r.name + SEP + r.path)
                count++
            }
            cm.flush()
            tmpDir.deleteRecursively()

            // remember what we copied so it can be removed later
            if (count > 0) {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val all = HashSet<String>(sp.getStringSet(KEY, emptySet()) ?: emptySet())
                all.addAll(copied)
                sp.edit().putStringSet(KEY, all).apply()
            }
            return count
        } catch (e: Exception) {
            return 0
        }
    }

    // Removes only the cookies that were copied from GeckoView
    fun removeCopied(context: Context): Int {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = sp.getStringSet(KEY, emptySet()) ?: emptySet()
        val cm = CookieManager.getInstance()
        var n = 0
        for (entry in set) {
            val p = entry.split(SEP)
            if (p.size < 3) continue
            val host = p[0]
            val name = p[1]
            val path = p[2]
            val bare = host.trimStart('.')
            val sb = StringBuilder()
            sb.append(name).append("=; Path=").append(if (path.isEmpty()) "/" else path)
            if (host.startsWith(".")) sb.append("; Domain=").append(host)
            sb.append("; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            cm.setCookie("https://$bare/", sb.toString())
            n++
        }
        cm.flush()
        sp.edit().remove(KEY).apply()
        return n
    }
}

// =====================================================================
//  WebView engine (Chrome's engine, built into the phone)
// =====================================================================

class WebViewEngine(
    private val activity: Activity,
    private val cb: EngineCallbacks,
    private var desktop: Boolean,
    private var imagesBlocked: Boolean,
    private val shareUa: Boolean
) : BrowserEngine {

    private lateinit var web: WebView
    private lateinit var container: FrameLayout

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var desktopScript: ScriptHandler? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var mobileUA = ""
    private var desktopUA = ""
    private var chromeVersion = "124.0.0.0"
    private var destroyed = false

    // Runs before every page's own scripts when desktop mode is on:
    // forces a wide desktop layout and hides touch-screen signals
    private val desktopJsTemplate = """
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
            Object.defineProperty(navigator, 'platform', { get: function () { return '__PLATFORM__'; } });
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

        val base = s.userAgentString
        chromeVersion = Regex("Chrome/([0-9.]+)").find(base)?.groupValues?.get(1) ?: "124.0.0.0"
        if (shareUa) {
            // Same browser ID that GeckoView sends
            val androidVersion = Build.VERSION.RELEASE
            mobileUA = "Mozilla/5.0 (Android $androidVersion; Mobile; rv:$GECKO_VERSION) " +
                    "Gecko/$GECKO_VERSION Firefox/$GECKO_VERSION"
            desktopUA = "Mozilla/5.0 (X11; Linux x86_64; rv:$GECKO_VERSION) " +
                    "Gecko/20100101 Firefox/$GECKO_VERSION"
        } else {
            // Chrome-style IDs built from the phone's real WebView version
            mobileUA = base.replace("; wv", "").replace("Version/4.0 ", "")
            desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
        }

        applyDesktop(desktop)
        setImagesBlocked(imagesBlocked)

        // Downloads go through Android's download manager
        web.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startDownload(url, userAgent, contentDisposition, mimetype)
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (destroyed) return
                cb.onUrlChanged(url)
                cb.onLoading(true, 0)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (destroyed) return
                cb.onLoading(false, 100)
                CookieManager.getInstance().flush()
                cb.onNavState(view.canGoBack(), view.canGoForward())
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (destroyed) return
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
                if (destroyed) return true
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
                if (destroyed) return
                cb.onLoading(newProgress < 100, newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (destroyed) return
                if (title != null) cb.onTitleChanged(title)
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

            // Uploads: open Android's file picker
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    activity.startActivityForResult(params.createIntent(), REQ_FILE)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
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

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        val scheme = Uri.parse(url).scheme ?: ""
        if (scheme != "http" && scheme != "https") {
            toastOn(activity, "This kind of download isn't supported yet")
            return
        }
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
            if (!mimeType.isNullOrEmpty()) request.setMimeType(mimeType)
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) request.addRequestHeader("Cookie", cookie)
            if (!userAgent.isNullOrEmpty()) request.addRequestHeader("User-Agent", userAgent)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            toastOn(activity, "Downloading $name")
        } catch (e: Exception) {
            toastOn(activity, "Download failed")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) return
        val callback = filePathCallback ?: return
        filePathCallback = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
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

        // Client hints (only in Chrome-style mode; the Firefox ID doesn't use them)
        if (!shareUa && WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
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
                val js = desktopJsTemplate.replace("__PLATFORM__", if (shareUa) "Linux x86_64" else "Win32")
                desktopScript = WebViewCompat.addDocumentStartJavaScript(web, js, setOf("*"))
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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
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
    private var destroyed = false

    private var pendingPrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null

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
                if (destroyed) return
                if (url != null) {
                    lastUrl = url
                    cb.onUrlChanged(url)
                }
            }

            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) {
                if (destroyed) return
                backOk = canGoBack
                cb.onNavState(backOk, forwardOk)
            }

            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) {
                if (destroyed) return
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
                            geckoView.post { if (!destroyed) cb.onNewPage(uriStr) }
                            deny()
                        } else {
                            val fixed = Links.desktopUrl(uriStr, desktop)
                            if (fixed != uriStr) {
                                geckoView.post { if (!destroyed) session.loadUri(fixed) }
                                deny()
                            } else {
                                allow()
                            }
                        }
                    }
                    Links.isInternal(scheme) -> allow()
                    else -> {
                        geckoView.post { if (!destroyed) cb.onAppLink(Uri.parse(uriStr)) }
                        deny()
                    }
                }
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri.startsWith("http")) {
                    geckoView.post { if (!destroyed) cb.onNewPage(uri) }
                }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                if (destroyed) return
                cb.onLoading(true, 0)
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                if (destroyed) return
                cb.onLoading(false, 100)
            }

            override fun onProgressChange(s: GeckoSession, value: Int) {
                if (destroyed) return
                cb.onLoading(true, value)
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(s: GeckoSession, fullScreen: Boolean) {
                if (destroyed) return
                cb.onFullscreen(fullScreen, null)
            }

            override fun onTitleChange(s: GeckoSession, title: String?) {
                if (destroyed) return
                if (title != null) cb.onTitleChanged(title)
            }

            // Downloads: GeckoView hands us the file as a stream
            override fun onExternalResponse(s: GeckoSession, response: WebResponse) {
                saveDownload(response)
            }

            override fun onCrash(s: GeckoSession) {
                geckoView.post { if (!destroyed) cb.onEngineDied("That page crashed, so the browser restarted") }
            }

            override fun onKill(s: GeckoSession) {
                geckoView.post { if (!destroyed) cb.onEngineDied("The page ran out of memory and was closed") }
            }
        }

        // Uploads: open Android's file picker
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(
                s: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                // finish any earlier prompt first
                val oldPrompt = pendingPrompt
                val oldResult = pendingResult
                if (oldPrompt != null && oldResult != null) {
                    oldResult.complete(oldPrompt.dismiss())
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingPrompt = prompt
                pendingResult = result

                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                val types = prompt.mimeTypes
                if (types != null && types.isNotEmpty()) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, types)
                }
                try {
                    activity.startActivityForResult(intent, REQ_FILE)
                } catch (e: Exception) {
                    pendingPrompt = null
                    pendingResult = null
                    return GeckoResult.fromValue(prompt.dismiss())
                }
                return result
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_FILE) return
        val prompt = pendingPrompt ?: return
        val result = pendingResult ?: return
        pendingPrompt = null
        pendingResult = null
        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
        if (uri != null) {
            result.complete(prompt.confirm(activity.applicationContext, uri))
        } else {
            result.complete(prompt.dismiss())
        }
    }

    private fun headerValue(headers: Map<String, String>, key: String): String? =
        headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun saveDownload(response: WebResponse) {
        val body = response.body
        if (body == null) {
            toastOn(activity, "Download failed")
            return
        }
        val mime = headerValue(response.headers, "content-type")?.substringBefore(';')?.trim()
        val name = URLUtil.guessFileName(response.uri, headerValue(response.headers, "content-disposition"), mime)
        toastOn(activity, "Downloading $name")
        Thread {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = activity.contentResolver
                    val values = ContentValues()
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name)
                    if (!mime.isNullOrEmpty()) values.put(MediaStore.Downloads.MIME_TYPE, mime)
                    values.put(MediaStore.Downloads.IS_PENDING, 1)
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("no uri")
                    val out = resolver.openOutputStream(uri) ?: throw IllegalStateException("no stream")
                    out.use { body.copyTo(it) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, name).outputStream().use { body.copyTo(it) }
                }
                toastOn(activity, "Saved to Downloads: $name")
            } catch (e: Exception) {
                toastOn(activity, "Download failed")
            } finally {
                try {
                    body.close()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }.start()
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
        if (destroyed) return
        destroyed = true
        val p = pendingPrompt
        val r = pendingResult
        if (p != null && r != null) r.complete(p.dismiss())
        pendingPrompt = null
        pendingResult = null
        geckoView.releaseSession()
        session.close()
        container.removeView(geckoView)
    }
}
