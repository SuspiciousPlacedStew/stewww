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
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), EngineCallbacks {

    private lateinit var engineHost: FrameLayout
    private lateinit var topBar: View
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerOpen: Button
    private lateinit var bannerStay: Button

    private var engine: BrowserEngine? = null
    private var backEnabled = false
    private var forwardEnabled = false
    private var inFullscreen = false
    private var fullscreenView: View? = null

    private val home = "https://www.google.com"
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun engineName() = prefs.getString("engine", "webview") ?: "webview"
    private fun desktopOn() = prefs.getBoolean("desktop", false)
    private fun askApps() = prefs.getBoolean("askApps", true)
    private fun askNew() = prefs.getBoolean("askNew", true)
    private fun imagesOff() = prefs.getBoolean("noImages", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engineHost = findViewById(R.id.engineHost)
        topBar = findViewById(R.id.topBar)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progress)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.bannerText)
        bannerOpen = findViewById(R.id.bannerOpen)
        bannerStay = findViewById(R.id.bannerStay)

        startEngine()

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (backEnabled) engine?.goBack()
        }
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (forwardEnabled) engine?.goForward()
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            engine?.reload()
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

        // After an engine switch, reopen the page that was open
        val resume = prefs.getString("resumeUrl", null)
        prefs.edit().remove("resumeUrl").apply()
        engine?.load(if (!resume.isNullOrEmpty()) resume else home)
    }

    override fun onStart() {
        super.onStart()
        engine?.setActive(true)
    }

    override fun onStop() {
        engine?.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        engine?.destroy()
        engine = null
        super.onDestroy()
    }

    // ---------- engine setup / switching ----------

    private fun startEngine() {
        val e: BrowserEngine = if (engineName() == "gecko") {
            GeckoEngine(this, this, desktopOn())
        } else {
            WebViewEngine(this, this, desktopOn(), imagesOff())
        }
        e.attach(engineHost)
        engine = e
    }

    // Start over with the same engine (used after a page crash)
    private fun rebuildEngine(message: String) {
        if (inFullscreen) onFullscreen(false, null)
        engine?.destroy()
        engine = null
        backEnabled = false
        forwardEnabled = false
        hideBanner()
        progressBar.visibility = View.GONE
        startEngine()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        engine?.load(home)
    }

    private fun switchEngine(url: String) {
        val next = if (engineName() == "gecko") "webview" else "gecko"
        prefs.edit()
            .putString("engine", next)
            .putString("resumeUrl", url)
            .apply()
        recreate()
    }

    // ---------- callbacks from the engine ----------

    override fun onUrlChanged(url: String) {
        if (!urlBar.hasFocus()) urlBar.setText(url)
    }

    override fun onLoading(loading: Boolean, percent: Int) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        progressBar.progress = percent
    }

    override fun onNavState(canGoBack: Boolean, canGoForward: Boolean) {
        backEnabled = canGoBack
        forwardEnabled = canGoForward
    }

    override fun onAppLink(uri: Uri) {
        handleAppLink(uri)
    }

    override fun onNewPage(url: String) {
        handleNewPage(url)
    }

    override fun onFullscreen(enabled: Boolean, customView: View?) {
        val decor = window.decorView as FrameLayout
        if (enabled) {
            if (customView != null) {
                fullscreenView = customView
                decor.addView(
                    customView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            inFullscreen = true
            topBar.visibility = View.GONE
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            fullscreenView?.let { decor.removeView(it) }
            fullscreenView = null
            inFullscreen = false
            topBar.visibility = View.VISIBLE
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onEngineDied(message: String) {
        rebuildEngine(message)
    }

    override fun onNeedsGecko(url: String) {
        showBanner("Google sign-in doesn't work in WebView. Tap Open to switch to GeckoView.") {
            switchEngine(url)
        }
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
            engine?.load(url) // popup off = just open it here
            return
        }
        showBanner("This site wants to open a new page:\n$url") {
            engine?.load(url)
        }
    }

    // ---------- desktop site + settings ----------

    private fun setDesktop(enabled: Boolean) {
        prefs.edit().putBoolean("desktop", enabled).apply()
        val e = engine ?: return
        e.applyDesktop(enabled)
        val current = e.currentUrl()
        val fixed = Links.desktopUrl(current, enabled)
        if (current.isNotEmpty() && fixed != current) e.load(fixed) else e.reload()
    }

    private fun setImagesOff(enabled: Boolean) {
        prefs.edit().putBoolean("noImages", enabled).apply()
        engine?.setImagesBlocked(enabled)
        engine?.reload()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val desktopItem = popup.menu.add(Menu.NONE, 1, 0, "Desktop site")
        desktopItem.isCheckable = true
        desktopItem.isChecked = desktopOn()
        popup.menu.add(
            Menu.NONE, 2, 1,
            if (engineName() == "gecko") "Switch to WebView (light, fast)"
            else "Switch to GeckoView (Google sign-in)"
        )
        popup.menu.add(Menu.NONE, 3, 2, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    setDesktop(!desktopOn())
                    true
                }
                2 -> {
                    switchEngine(engine?.currentUrl() ?: "")
                    true
                }
                3 -> {
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
            "Ask before opening new sites",
            "Block images (WebView only)"
        )
        val keys = arrayOf("desktop", "askApps", "askNew", "noImages")
        val defaults = booleanArrayOf(false, true, true, false)
        val checked = BooleanArray(4) { prefs.getBoolean(keys[it], defaults[it]) }

        AlertDialog.Builder(this)
            .setTitle("Settings (engine: " + (if (engineName() == "gecko") "GeckoView" else "WebView") + ")")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> setDesktop(isChecked)
                    3 -> setImagesOff(isChecked)
                    else -> prefs.edit().putBoolean(keys[which], isChecked).apply()
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
        engine?.load(url)
        urlBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inFullscreen -> engine?.exitFullscreen()
            backEnabled -> engine?.goBack()
            else -> super.onBackPressed()
        }
    }
}
