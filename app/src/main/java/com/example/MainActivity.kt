package com.example

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var adMobManager: AdMobRewardedManager
    private var webView: WebView? = null
    private var fullscreenContainer: FrameLayout? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var customView: View? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // File picker launcher for WebView <input type="file">
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data: Intent? = result.data
            var results: Array<Uri>? = null
            if (result.resultCode == RESULT_OK && data != null) {
                val dataString = data.dataString
                val clipData = data.clipData
                if (clipData != null) {
                    val count = clipData.itemCount
                    val uris = ArrayList<Uri>(count)
                    for (i in 0 until count) {
                        uris.add(clipData.getItemAt(i).uri)
                    }
                    results = uris.toTypedArray()
                } else if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }

        // 1. Initialize AdMob Manager and SDK
        adMobManager = AdMobRewardedManager(applicationContext)
        adMobManager.initialize {
            Log.d(TAG, "AdMob initialization callback completed.")
        }

        // 2. Set up back navigation
        setupBackNavigation()

        // 3. Compose content with WebView
        setContent {
            MyApplicationTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            createAppLayout(context)
                        }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createAppLayout(context: android.content.Context): View {
        val rootLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        fullscreenContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            }

            // JavaScript Bridge named "Android" for WebView ↔ Android interaction
            addJavascriptInterface(WebAppInterface(), "Android")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // Stay inside WebView for asset and application URLs
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView finished loading: $url")
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?
                ) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    fullscreenContainer?.addView(view)
                    fullscreenContainer?.visibility = View.VISIBLE
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }

                override fun onHideCustomView() {
                    if (customView == null) return
                    fullscreenContainer?.removeView(customView)
                    fullscreenContainer?.visibility = View.GONE
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    customViewCallback = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        val acceptTypes = fileChooserParams?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray()
                        if (!acceptTypes.isNullOrEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                        } else {
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                        }
                    }
                    val chooserIntent = Intent.createChooser(intent, "اختر صورة أو فيديو")

                    try {
                        fileChooserLauncher.launch(chooserIntent)
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching file chooser", e)
                        this@MainActivity.filePathCallback?.onReceiveValue(null)
                        this@MainActivity.filePathCallback = null
                        return false
                    }
                }
            }

            // Load the HTML/CSS/JavaScript app
            loadUrl("file:///android_asset/index.html")
        }

        webView = wv
        rootLayout.addView(wv)
        rootLayout.addView(fullscreenContainer)
        return rootLayout
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Check if full-screen video is showing
                    if (customView != null) {
                        fullscreenContainer?.removeView(customView)
                        fullscreenContainer?.visibility = View.GONE
                        customView = null
                        customViewCallback?.onCustomViewHidden()
                        customViewCallback = null
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        return
                    }

                    val wv = webView
                    if (wv != null) {
                        // Check if web app history can handle it
                        wv.evaluateJavascript(
                            "if (typeof window.goBack === 'function' && window.historyStack && window.historyStack.length > 0) { window.goBack(); 'handled'; } else { 'unhandled'; }"
                        ) { result ->
                            if (result != "\"handled\"") {
                                if (wv.canGoBack()) {
                                    wv.goBack()
                                } else {
                                    isEnabled = false
                                    onBackPressedDispatcher.onBackPressed()
                                }
                            }
                        }
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    /**
     * JavaScript Interface Bridge exposed as "window.Android" inside WebView.
     */
    inner class WebAppInterface {

        /**
         * Called by JavaScript when the user requests an episode:
         * window.Android.showRewarded(REWARDED_ID)
         */
        @JavascriptInterface
        fun showRewarded(adUnitId: String? = null) {
            Log.d(TAG, "JS Bridge showRewarded invoked")
            runOnUiThread {
                adMobManager.showRewardedAd(
                    activity = this@MainActivity,
                    onRewardedClosed = {
                        sendJsCallback("onRewardedAdClosed")
                    },
                    onAdFailed = {
                        sendJsCallback("onAdFailed")
                    }
                )
            }
        }

        /**
         * Allows JavaScript to preload a Rewarded Ad in advance.
         */
        @JavascriptInterface
        fun loadRewarded(adUnitId: String? = null) {
            Log.d(TAG, "JS Bridge loadRewarded invoked")
            runOnUiThread {
                adMobManager.loadRewardedAd()
            }
        }

        /**
         * Allows JavaScript to check if a Rewarded Ad is loaded and ready.
         */
        @JavascriptInterface
        fun isAdReady(): Boolean {
            return adMobManager.isAdLoaded()
        }

        private fun sendJsCallback(functionName: String) {
            runOnUiThread {
                val wv = webView ?: return@runOnUiThread
                Log.d(TAG, "Invoking JavaScript callback: window.$functionName()")
                val script = "if (typeof window.$functionName === 'function') { window.$functionName(); }"
                wv.evaluateJavascript(script, null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme { Greeting("Android") }
}
