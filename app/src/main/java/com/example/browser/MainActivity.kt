package com.example.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private val home = "https://www.google.com"
    private var dialogShowing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

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
                val url = uri.toString()
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                askToOpenApp(uri)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
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

    private fun askToOpenApp(uri: Uri) {
        if (dialogShowing) return
        dialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("Open another app?")
            .setMessage("This site wants to open an app on your phone.")
            .setPositiveButton("Open") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Stay here", null)
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    private fun go() {
        val q = urlBar.text.toString().trim()
        if (q.isEmpty()) return
        val url = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains(".") && !q.contains(" ") -> "https://$q"
            else -> "https://www.google.com/search?q=" + Uri.encode(q)
        }
        web.loadUrl(url)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
