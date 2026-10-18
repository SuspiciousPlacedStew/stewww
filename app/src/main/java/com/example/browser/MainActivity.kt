package com.example.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {

    companion object {
        private var runtime: GeckoRuntime? = null
    }

    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var topBar: View
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerOpen: Button
    private lateinit var bannerStay: Button

    private var backEnabled = false
    private var forwardEnabled = false
    private var inFullscreen = false
    private var lastUrl = ""

    private val home = "https://www.google.com"
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun desktopOn() = prefs.getBoolean("desktop", false)
    private fun askApps() = prefs.getBoolean("askApps", true)
    private fun askNew() = prefs.getBoolean("askNew", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.geckoview)
        topBar = findViewById(R.id.topBar)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progress)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.bannerText)
        bannerOpen = findViewById(R.id.bannerOpen)
        bannerStay = findViewById(R.id.bannerStay)

        createSession()

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (backEnabled) session.goBack()
        }
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (forwardEnabled) session.goForward()
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            session.reload()
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { v ->
            showMenu(v)
        }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                go()
                true
            } else {
                false
            }
        }

        load(home)
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    // ---------- session setup + crash recovery ----------

    // One GeckoRuntime per app process
    private fun runtimeInstance(): GeckoRuntime =
        runtime ?: GeckoRuntime.create(applicationContext).also { runtime = it }

    private fun createSession() {
        session = GeckoSession()
        applyDesktopMode()
        setupDelegates()
        session.open(runtimeInstance())
        geckoView.setSession(session)
    }

    // When a page crashes, its session is dead: throw it away and start a new one
    private fun recoverSession(message: String) {
        if (inFullscreen) setFullscreen(false)
        backEnabled = false
        forwardEnabled = false
        lastUrl = ""
        hideBanner()
        progressBar.visibility = View.GONE
        geckoView.releaseSession()
        session.close()
        createSession()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        load(home)
    }

    // ---------- GeckoView callbacks ----------

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
                    if (!urlBar.hasFocus()) urlBar.setText(url)
                }
            }

            override fun onCanGoBack(s: GeckoSession, canGoBack: Boolean) {
                backEnabled = canGoBack
            }

            override fun onCanGoForward(s: GeckoSession, canGoForward: Boolean) {
                forwardEnabled = canGoForward
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
                            geckoView.post { handleNewPage(uriStr) }
                            deny()
                        } else {
                            val fixed = desktopUrl(uriStr)
                            if (fixed != uriStr) {
                                geckoView.post { session.loadUri(fixed) }
                                deny()
                            } else {
                                allow()
                            }
                        }
                    }
                    isInternal(scheme) -> allow()
                    else -> {
                        geckoView.post { handleAppLink(Uri.parse(uriStr)) }
                        deny()
                    }
                }
            }

            override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                if (uri.startsWith("http")) {
                    geckoView.post { handleNewPage(uri) }
                }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(s: GeckoSession, url: String) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageStop(s: GeckoSession, success: Boolean) {
                progressBar.visibility = View.GONE
            }

            override fun onProgressChange(s: GeckoSession, value: Int) {
                progressBar.progress = value
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(s: GeckoSession, fullScreen: Boolean) {
                setFullscreen(fullScreen)
            }

            override fun onCrash(s: GeckoSession) {
                geckoView.post { recoverSession("That page crashed, so the browser restarted") }
            }

            override fun onKill(s: GeckoSession) {
                geckoView.post { recoverSession("The page ran out of memory and was closed") }
            }
        }
    }

    // ---------- fullscreen video ----------

    private fun setFullscreen(enabled: Boolean) {
        inFullscreen = enabled
        topBar.visibility = if (enabled) View.GONE else View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (enabled) {
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---------- link helpers ----------

    private fun isInternal(scheme: String): Boolean =
        scheme in listOf("about", "data", "blob", "javascript", "resource", "view-source")

    // In desktop mode, turn mobile addresses (m.example.com) into www.
    private fun desktopUrl(url: String): String {
        if (!desktopOn()) return url
        val host = Uri.parse(url).host ?: return url
        return if (host.startsWith("m.")) url.replaceFirst("//m.", "//www.") else url
    }

    private fun load(url: String) {
        session.loadUri(desktopUrl(url))
    }

    // ---------- top banner (does not pause or dim the page) ----------

    private fun showBanner(message: String, onOpen: () -> Unit) {
        if (banner.visibility == View.VISIBLE) return
        bannerText.text = message
        bannerOpen.setOnClickListener {
            hideBanner()
            onOpen()
        }
        bannerStay.setOnClickListener { hideBanner() }
        banner.visibility = View.VISIBLE
    }

    private fun hideBanner() {
        banner.visibility = View.GONE
    }

    // ---------- popups ----------

    private fun handleAppLink(uri: Uri) {
        if (!askApps()) return // popup off = app links are blocked
        showBanner("This site wants to open an app.") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: Exception) {
                Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleNewPage(url: String) {
        if (!askNew()) {
            load(url) // popup off = just open it here
            return
        }
        showBanner("This site wants to open a new page:\n$url") {
            load(url)
        }
    }

    // ---------- desktop site + settings ----------

    private fun applyDesktopMode() {
        val desktop = desktopOn()
        session.settings.setUserAgentMode(
            if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        session.settings.setViewportMode(
            if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
    }

    private fun setDesktop(enabled: Boolean) {
        prefs.edit().putBoolean("desktop", enabled).apply()
        applyDesktopMode()
        val fixed = desktopUrl(lastUrl)
        if (lastUrl.isNotEmpty() && fixed != lastUrl) {
            session.loadUri(fixed)
        } else {
            session.reload()
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val desktopItem = popup.menu.add(Menu.NONE, 1, 0, "Desktop site")
        desktopItem.isCheckable = true
        desktopItem.isChecked = desktopOn()
        popup.menu.add(Menu.NONE, 2, 1, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setDesktop(!desktopOn())
                    true
                }
                2 -> {
                    showSettings()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSettings() {
        val items = arrayOf(
            "Desktop site",
            "Ask before opening apps",
            "Ask before opening new sites"
        )
        val keys = arrayOf("desktop", "askApps", "askNew")
        val defaults = booleanArrayOf(false, true, true)
        val checked = BooleanArray(3) { prefs.getBoolean(keys[it], defaults[it]) }

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                if (which == 0) {
                    setDesktop(isChecked)
                } else {
                    prefs.edit().putBoolean(keys[which], isChecked).apply()
                }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    // ---------- address bar ----------

    private fun go() {
        val q = urlBar.text.toString().trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains(".") && !q.contains(" ") -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        hideBanner()
        load(url)
        urlBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inFullscreen -> session.exitFullScreen()
            backEnabled -> session.goBack()
            else -> super.onBackPressed()
        }
    }
}
