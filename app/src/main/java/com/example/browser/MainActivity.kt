package com.example.browser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    private class Tab(var url: String, var title: String)

    private lateinit var engineHost: FrameLayout
    private lateinit var topBar: View
    private lateinit var urlBar: EditText
    private lateinit var btnTabs: Button
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

    private val tabs = ArrayList<Tab>()
    private var currentTab = 0

    private val home = "https://www.google.com"
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun engineName() = prefs.getString("engine", "webview") ?: "webview"
    private fun desktopOn() = prefs.getBoolean("desktop", false)
    private fun askApps() = prefs.getBoolean("askApps", true)
    private fun askNew() = prefs.getBoolean("askNew", true)
    private fun imagesOff() = prefs.getBoolean("noImages", false)
    private fun shareOn() = prefs.getBoolean("shareLogins", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engineHost = findViewById(R.id.engineHost)
        topBar = findViewById(R.id.topBar)
        urlBar = findViewById(R.id.urlBar)
        btnTabs = findViewById(R.id.btnTabs)
        progressBar = findViewById(R.id.progress)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.bannerText)
        bannerOpen = findViewById(R.id.bannerOpen)
        bannerStay = findViewById(R.id.bannerStay)

        // Older phones (Android 9 and below) need this permission for downloads
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        loadTabs()
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
        btnTabs.setOnClickListener {
            showTabs()
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

        // In WebView with sharing on, bring in logins saved by GeckoView
        // (done before the first page loads, so the cookies are already there)
        var imported = 0
        if (engineName() == "webview" && shareOn()) {
            imported = CookieSync.geckoToWebView(this)
        }

        // After an engine switch, reopen the page that was open
        val resume = prefs.getString("resumeUrl", null)
        prefs.edit().remove("resumeUrl").apply()
        if (!resume.isNullOrEmpty()) {
            tabs[currentTab].url = resume
            if (imported > 0) {
                Toast.makeText(this, "Copied $imported cookies from GeckoView", Toast.LENGTH_SHORT).show()
            }
        }

        updateTabButton()
        val first = tabs[currentTab].url
        urlBar.setText(first)
        engine?.load(first)
    }

    override fun onStart() {
        super.onStart()
        engine?.setActive(true)
    }

    override fun onStop() {
        saveTabs()
        engine?.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        engine?.destroy()
        engine = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        engine?.onActivityResult(requestCode, resultCode, data)
    }

    // ---------- tabs ----------

    private fun saveTabs() {
        val data = tabs.joinToString("\u0001") {
            it.url.replace("\u0001", "").replace("\u0002", "") + "\u0002" +
                    it.title.replace("\u0001", "").replace("\u0002", "")
        }
        prefs.edit().putString("tabsData", data).putInt("tabsCurrent", currentTab).apply()
    }

    private fun loadTabs() {
        tabs.clear()
        val data = prefs.getString("tabsData", "") ?: ""
        if (data.isNotEmpty()) {
            for (part in data.split("\u0001")) {
                val i = part.indexOf("\u0002")
                if (i >= 0) tabs.add(Tab(part.substring(0, i), part.substring(i + 1)))
            }
        }
        if (tabs.isEmpty()) tabs.add(Tab(home, ""))
        currentTab = prefs.getInt("tabsCurrent", 0).coerceIn(0, tabs.size - 1)
    }

    private fun updateTabButton() {
        btnTabs.text = tabs.size.toString()
    }

    // Only the current tab is kept loaded (saves memory); others reload when selected
    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        if (inFullscreen) onFullscreen(false, null)
        hideBanner()
        engine?.destroy()
        engine = null
        currentTab = index
        backEnabled = false
        forwardEnabled = false
        progressBar.visibility = View.GONE
        startEngine()
        updateTabButton()
        saveTabs()
        val url = tabs[currentTab].url
        urlBar.setText(url)
        engine?.load(url)
    }

    private fun openNewTab(url: String) {
        tabs.add(Tab(url, ""))
        switchToTab(tabs.size - 1)
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            tabs[0] = Tab(home, "")
            switchToTab(0)
            return
        }
        tabs.removeAt(index)
        if (index == currentTab) {
            switchToTab(minOf(index, tabs.size - 1))
        } else {
            if (index < currentTab) currentTab -= 1
            updateTabButton()
            saveTabs()
        }
    }

    private fun showTabs() {
        val labels = tabs.mapIndexed { i, t ->
            val name = if (t.title.isNotEmpty()) t.title else t.url
            (if (i == currentTab) "● " else "    ") + name.take(60)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size}) - hold a tab to close it")
            .setItems(labels) { _, which ->
                if (which != currentTab) switchToTab(which)
            }
            .setPositiveButton("New tab") { _, _ -> openNewTab(home) }
            .setNeutralButton("Close this tab") { _, _ -> closeTab(currentTab) }
            .setNegativeButton("Back", null)
            .create()
        dialog.show()
        dialog.listView.setOnItemLongClickListener { _, _, pos, _ ->
            dialog.dismiss()
            closeTab(pos)
            true
        }
    }

    // ---------- engine setup / switching ----------

    private fun startEngine() {
        val e: BrowserEngine = if (engineName() == "gecko") {
            GeckoEngine(this, this, desktopOn())
        } else {
            WebViewEngine(this, this, desktopOn(), imagesOff(), shareOn())
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
        tabs[currentTab].url = home
        tabs[currentTab].title = ""
        startEngine()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        urlBar.setText(home)
        engine?.load(home)
    }

    private fun switchEngine(url: String) {
        val leavingGecko = engineName() == "gecko"
        val next = if (leavingGecko) "webview" else "gecko"
        saveTabs()
        prefs.edit()
            .putString("engine", next)
            .putString("resumeUrl", url)
            .apply()
        if (leavingGecko && shareOn()) {
            // give Firefox a moment to save any new login to disk first
            Toast.makeText(this, "Saving logins...", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ recreate() }, 1500)
        } else {
            recreate()
        }
    }

    // ---------- callbacks from the engine ----------

    override fun onUrlChanged(url: String) {
        tabs.getOrNull(currentTab)?.url = url
        if (!urlBar.hasFocus()) urlBar.setText(url)
    }

    override fun onTitleChanged(title: String) {
        tabs.getOrNull(currentTab)?.title = title
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
            openNewTab(url) // popup off = just open it in a new tab
            return
        }
        showBanner("This site wants to open a new page:\n$url") {
            openNewTab(url)
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

    // Changes the browser ID WebView uses, so WebView restarts on the current page
    private fun setShareLogins(enabled: Boolean) {
        prefs.edit().putBoolean("shareLogins", enabled).apply()
        if (engineName() == "webview") {
            saveTabs()
            prefs.edit().putString("resumeUrl", engine?.currentUrl() ?: "").apply()
            recreate()
        }
    }

    private fun syncNow() {
        val n = CookieSync.geckoToWebView(this)
        Toast.makeText(
            this,
            if (n > 0) "Copied $n cookies from GeckoView" else "No GeckoView logins found yet",
            Toast.LENGTH_SHORT
        ).show()
        engine?.reload()
    }

    private fun removeCopiedLogins() {
        val n = CookieSync.removeCopied(this)
        Toast.makeText(
            this,
            if (n > 0) "Removed $n copied cookies" else "No copied logins to remove",
            Toast.LENGTH_SHORT
        ).show()
        engine?.reload()
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val desktopItem = popup.menu.add(Menu.NONE, 1, 0, "Desktop site")
        desktopItem.isCheckable = true
        desktopItem.isChecked = desktopOn()
        popup.menu.add(Menu.NONE, 6, 1, "New tab")
        popup.menu.add(
            Menu.NONE, 2, 2,
            if (engineName() == "gecko") "Switch to WebView (light, fast)"
            else "Switch to GeckoView (Google sign-in)"
        )
        if (engineName() == "webview") {
            popup.menu.add(Menu.NONE, 4, 3, "Sync logins from GeckoView now")
            popup.menu.add(Menu.NONE, 5, 4, "Remove copied logins")
        }
        popup.menu.add(Menu.NONE, 3, 5, "Settings")
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
                4 -> {
                    syncNow()
                    true
                }
                5 -> {
                    removeCopiedLogins()
                    true
                }
                6 -> {
                    openNewTab(home)
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
            "Block images (WebView only)",
            "Share logins between engines (experimental)"
        )
        val keys = arrayOf("desktop", "askApps", "askNew", "noImages", "shareLogins")
        val defaults = booleanArrayOf(false, true, true, false, false)
        val checked = BooleanArray(5) { prefs.getBoolean(keys[it], defaults[it]) }

        AlertDialog.Builder(this)
            .setTitle("Settings (engine: " + (if (engineName() == "gecko") "GeckoView" else "WebView") + ")")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                when (which) {
                    0 -> setDesktop(isChecked)
                    3 -> setImagesOff(isChecked)
                    4 -> setShareLogins(isChecked)
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
