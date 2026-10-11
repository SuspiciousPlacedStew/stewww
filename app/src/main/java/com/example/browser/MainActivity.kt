package com.example.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerOpen: Button
    private lateinit var bannerStay: Button
    private lateinit var mobileUA: String

    private val home = "https://www.google.com"
    private val desktopUA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun desktopOn() = prefs.getBoolean("desktop", false)
    private fun askApps() = prefs.getBoolean("askApps", true)
    private fun askNew() = prefs.getBoolean("askNew", true)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.bannerText)
        bannerOpen = findViewById(R.id.bannerOpen)
        bannerStay = findViewById(R.id.bannerStay)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)

        mobileUA = s.userAgentString
        applyUserAgent()

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                urlBar.setText(url)
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isHttp(uri) || isInternal(uri)) return false
                handleAppLink(uri)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val temp = WebView(this@MainActivity)
                temp.settings.javaScriptEnabled = true
                temp.settings.domStorageEnabled = true
                var done = false

                fun deliver(target: String) {
                    if (done || target.isEmpty() || target == "about:blank") return
                    done = true
                    temp.stopLoading()
                    web.post { temp.destroy() }
                    handleNewPage(Uri.parse(target))
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

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            if (web.canGoBack()) web.goBack()
        }
        findViewById<Button>(R.id.btnForward).setOnClickListener {
            if (web.canGoForward()) web.goForward()
        }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            web.reload()
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

        web.loadUrl(home)
    }

    // ---------- link type helpers ----------

    private fun isHttp(uri: Uri): Boolean = uri.scheme == "http" || uri.scheme == "https"

    private fun isInternal(uri: Uri): Boolean {
        val scheme = uri.scheme ?: ""
        return scheme in listOf("about", "data", "blob", "javascript")
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

    private fun handleNewPage(uri: Uri) {
        if (isHttp(uri)) {
            val url = uri.toString()
            if (!askNew()) {
                web.loadUrl(url) // popup off = just open it here
                return
            }
            showBanner("This site wants to open a new page:\n$url") {
                web.loadUrl(url)
            }
        } else if (!isInternal(uri)) {
            handleAppLink(uri)
        }
    }

    // ---------- desktop site + settings ----------

    private fun applyUserAgent() {
        web.settings.userAgentString = if (desktopOn()) desktopUA else mobileUA
    }

    private fun setDesktop(enabled: Boolean) {
        prefs.edit().putBoolean("desktop", enabled).apply()
        applyUserAgent()
        web.reload()
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
        web.loadUrl(url)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
