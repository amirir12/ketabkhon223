package com.ketabkhoon.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var chooserCallback: ValueCallback<Array<Uri>>? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        chooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        chooserCallback = null
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exports = File(filesDir, "exports")
        exports.mkdirs()

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/exports/", WebViewAssetLoader.InternalStoragePathHandler(this, exports))
            .build()

        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.allowFileAccess = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return loader.shouldInterceptRequest(request.url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                chooserCallback?.onReceiveValue(null)
                chooserCallback = filePathCallback
                picker.launch(arrayOf("*/*"))
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.__onBack ? window.__onBack() : false)") { result ->
                    if (result != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        bridge = Bridge(this, web, exports)
        web.addJavascriptInterface(bridge, "Android")

        PlaybackService.listener = { cmd ->
            runOnUiThread {
                web.evaluateJavascript("window.__remote && window.__remote('$cmd')", null)
            }
        }

        web.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        PlaybackService.listener = null
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (t: Throwable) {
        }
        super.onDestroy()
    }

    /** Asks for the notification permission (Android 13+), used for the playback controls. */
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    /** Handles "Open with" and "Share to" from other apps (PDF, Word, EPUB, text). */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val action = i.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return
        val uri: Uri? = i.data ?: streamUri(i)
        val text = i.getStringExtra(Intent.EXTRA_TEXT)
        if (uri != null) {
            bridge.receiveUri(uri)
        } else if (!text.isNullOrBlank()) {
            bridge.receiveText(text)
        }
        i.action = null
    }
}
