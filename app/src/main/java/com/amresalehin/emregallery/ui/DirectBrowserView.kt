package com.amresalehin.emregallery.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import com.amresalehin.emregallery.data.BotDetectionMitigator
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amresalehin.emregallery.data.AdBlockEngine
import com.amresalehin.emregallery.data.BrowserAddressBarPosition
import com.amresalehin.emregallery.data.BrowserSettingsRepository
import com.amresalehin.emregallery.data.BrowserHistoryManager
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserSite
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.ExtractionSource
import com.amresalehin.emregallery.data.MediaCaptureEngine
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DirectBrowserView(
    initialUrl: String = "",
    onClose: (() -> Unit)? = null,
    onUrlChange: ((String) -> Unit)? = null,
    onPageTitleChanged: ((String) -> Unit)? = null,
    discoveredCount: Int = 0,
    onMediaExtracted: (ScrapedMediaItem) -> Unit,
    onBatchExtracted: (List<ScrapedMediaItem>, pageTitle: String?, domain: String?) -> Unit,
    onSwitchToMediaTab: () -> Unit,
    settingsRepository: BrowserSettingsRepository? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val actualSettingsRepository = remember(context, settingsRepository) {
        settingsRepository ?: BrowserSettingsRepository(context.applicationContext)
    }
    val browserSettings by actualSettingsRepository.settings.collectAsState()
    val latestBrowserSettings by rememberUpdatedState(browserSettings)
    val focusManager = LocalFocusManager.current

    val startUrl = remember(initialUrl, browserSettings.searchEngine) {
        val trimmed = initialUrl.trim()
        if (trimmed.isNotBlank()) {
            MediaClassifier.formatQueryOrUrl(trimmed, browserSettings.searchEngine, browserSettings.customSearchUrl)
        } else {
            "" // Empty home page by default
        }
    }

    var currentUrl by remember { mutableStateOf(startUrl) }
    var addressInput by remember { mutableStateOf(startUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.let { wv ->
                wv.stopLoading()
                wv.webViewClient = WebViewClient()
                wv.webChromeClient = WebChromeClient()
                wv.destroy()
                webViewInstance = null
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabDetectedCount by tabSession.detectedCount.collectAsState()
    val totalDetectedCount = maxOf(discoveredCount, tabDetectedCount)

    // Intercept back button when browser can navigate back
    BackHandler(enabled = canGoBack) {
        webViewInstance?.goBack()
    }

    fun navigateToUrl(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        val formatted = MediaClassifier.formatQueryOrUrl(trimmed, browserSettings.searchEngine)
        addressInput = formatted
        currentUrl = formatted
        onUrlChange?.invoke(formatted)
        tabSession.updateNavigation(formatted, pageTitle)
        coroutineScope.launch {
            historyManager.recordVisit(formatted)
        }
        webViewInstance?.loadUrl(formatted)
        focusManager.clearFocus()
    }

    fun scanCurrentPageDOM(wv: WebView?) {
        if (wv == null) return
        val current = wv.url ?: return
        if (current.isBlank() || current == "about:blank" || currentUrl.isBlank()) return

        wv.evaluateJavascript(MediaCaptureEngine.getJsDomScanScript()) { jsonRaw ->
            val discovered = MediaCaptureEngine.parseJsDomScanResult(jsonRaw, current)
            if (discovered.isNotEmpty()) {
                val domain = runCatching { java.net.URL(current).host }.getOrDefault("")
                tabSession.registerMediaBatch(discovered, pageTitle)
                onBatchExtracted(discovered, pageTitle, domain)
                DownloaderSession.addDiscoveredItems(discovered, pageTitle)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("direct_browser_view")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (browserSettings.addressBarPosition == BrowserAddressBarPosition.TOP) {
                BrowserNavigationBar(
                    onClose = onClose,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    onGoBack = { webViewInstance?.goBack() },
                    onGoForward = { webViewInstance?.goForward() },
                    addressInput = addressInput,
                    onAddressInputChange = { addressInput = it },
                    onNavigate = { navigateToUrl(addressInput) },
                    isLoading = isLoading,
                    onReloadOrStop = {
                        if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                    },
                    onHome = {
                        currentUrl = ""
                        addressInput = ""
                        pageTitle = ""
                        onUrlChange?.invoke("")
                        tabSession.updateNavigation("", "")
                        webViewInstance?.loadUrl("about:blank")
                    },
                    currentUrl = currentUrl,
                    detectedCount = totalDetectedCount,
                    onSwitchToMediaTab = onSwitchToMediaTab,
                    onOpenSettings = { onOpenSettings?.invoke() },
                    showSecurityIndicator = browserSettings.showSecurityIndicator,
                    isTop = true
                )
            }

            if (isLoading && browserSettings.showLoadingProgress) {
                LinearProgressIndicator(
                    progress = { loadingProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Anti-Bot Verification / Cloudflare Challenge Active Banner
            val isChallengeActive = remember(pageTitle, currentUrl) {
                BotDetectionMitigator.isBotChallenge(pageTitle, currentUrl)
            }
            AnimatedVisibility(
                visible = isChallengeActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .testTag("browser_bot_challenge_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Security Verification: Tap captcha checkbox if prompted to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 2. Interactive WebView and Home Page Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewInstance = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = browserSettings.javaScriptEnabled
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = true
                                setSupportMultipleWindows(false)
                                cacheMode = WebSettings.LOAD_DEFAULT
                                builtInZoomControls = true
                                displayZoomControls = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mediaPlaybackRequiresUserGesture = !browserSettings.allowAutoplay
                                loadsImagesAutomatically = browserSettings.loadImagesAutomatically
                                safeBrowsingEnabled = browserSettings.safeBrowsingEnabled
                                textZoom = browserSettings.websiteTextZoom
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                            CookieManager.getInstance().setAcceptThirdPartyCookies(
                                this,
                                !browserSettings.blockThirdPartyCookies
                            )
                            setInitialScale(browserSettings.websiteInitialScale)

                            addJavascriptInterface(
                                BrowserSession.EmreMediaBridge { rawUrl, assetRole ->
                                    if (latestBrowserSettings.mediaDiscoveryEnabled) {
                                        val item = MediaCaptureEngine.buildMediaItem(
                                        rawUrl = rawUrl,
                                        source = ExtractionSource.DOM_SCRAPE,
                                        baseUrl = currentUrl,
                                        assetRoleHint = assetRole
                                    )
                                        if (item != null) {
                                            tabSession.registerMedia(item)
                                            onMediaExtracted(item)
                                            DownloaderSession.addDiscoveredItem(item)
                                        }
                                    }
                                },
                                "EmreBridge"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }

                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                    onPageTitleChanged?.invoke(pageTitle)
                                    tabSession.updateNavigation(currentUrl, pageTitle)
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                private fun handleExternalNavigation(view: WebView?, urlString: String): Boolean {
                                    if (urlString.isBlank()) return false
                                    val lower = urlString.trim().lowercase()

                                    // Internal browser schemes should load within Chromium WebView
                                    if (lower.startsWith("http://") || lower.startsWith("https://") ||
                                        lower.startsWith("about:") || lower.startsWith("data:") ||
                                        lower.startsWith("javascript:") || lower.startsWith("blob:") ||
                                        lower.startsWith("file:///android_asset/")
                                    ) {
                                        return false
                                    }

                                    val context = view?.context ?: return false

                                    // 1. Handle intent:// URI scheme (e.g. intent://...#Intent;scheme=...;package=...;end)
                                    if (lower.startsWith("intent://") || lower.startsWith("intent:")) {
                                        try {
                                            val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                                            if (intent != null) {
                                                val pm = context.packageManager
                                                val resolveInfo = intent.resolveActivity(pm)
                                                if (resolveInfo != null) {
                                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    context.startActivity(intent)
                                                    return true
                                                }

                                                // If target app is not installed, check for browser_fallback_url
                                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                if (!fallbackUrl.isNullOrBlank() &&
                                                    (fallbackUrl.startsWith("http://", ignoreCase = true) || fallbackUrl.startsWith("https://", ignoreCase = true))
                                                ) {
                                                    view?.loadUrl(fallbackUrl)
                                                    return true
                                                }

                                                // If intent specifies target package, route to Google Play Store
                                                val packageName = intent.`package`
                                                if (!packageName.isNullOrBlank()) {
                                                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    if (marketIntent.resolveActivity(pm) != null) {
                                                        context.startActivity(marketIntent)
                                                        return true
                                                    } else {
                                                        view?.loadUrl("https://play.google.com/store/apps/details?id=$packageName")
                                                        return true
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.w("DirectBrowserView", "Failed to resolve intent scheme: $urlString", e)
                                        }
                                        return true // Prevent Chromium from displaying net::ERR_UNKNOWN_URL_SCHEME
                                    }

                                    // 2. Handle specific app/system schemes: market://, tel:, mailto:, sms:, geo:, whatsapp:, tg:, etc.
                                    try {
                                        val uri = Uri.parse(urlString)
                                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val pm = context.packageManager
                                        if (intent.resolveActivity(pm) != null) {
                                            context.startActivity(intent)
                                            return true
                                        } else if (lower.startsWith("market://details?id=")) {
                                            val pkg = uri.getQueryParameter("id")
                                            if (!pkg.isNullOrBlank()) {
                                                view?.loadUrl("https://play.google.com/store/apps/details?id=$pkg")
                                                return true
                                            }
                                        }
                                    } catch (e: ActivityNotFoundException) {
                                        Log.w("DirectBrowserView", "No app available to handle scheme: $urlString", e)
                                        return true
                                    } catch (e: Exception) {
                                        Log.w("DirectBrowserView", "Failed to handle external URI: $urlString", e)
                                        return true
                                    }

                                    return true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val reqUrl = request?.url?.toString() ?: return false
                                    return handleExternalNavigation(view, reqUrl)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    if (url.isNullOrBlank()) return false
                                    return handleExternalNavigation(view, url)
                                }
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        isLoading = false
                                        canGoBack = false
                                        canGoForward = false
                                        return
                                    }
                                    isLoading = true
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    tabSession.updateNavigation(url, pageTitle)
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (url.isNullOrBlank() || url == "about:blank") {
                                        canGoBack = false
                                        canGoForward = false
                                        return
                                    }
                                    BotDetectionMitigator.syncCookiesFromWebView(url)
                                    currentUrl = url
                                    addressInput = url
                                    onUrlChange?.invoke(url)
                                    tabSession.updateNavigation(url, view?.title)
                                    coroutineScope.launch {
                                        historyManager.recordVisit(url, view?.title)
                                    }
                                    if (BotDetectionMitigator.isBotChallenge(view?.title, url)) {
                                        // Keep the page visible for manual verification, but do not scrape challenge content.
                                        tabSession.clearMedia()
                                        return
                                    }
                                    if (latestBrowserSettings.mediaDiscoveryEnabled) {
                                        view?.evaluateJavascript(MediaCaptureEngine.getMutationObserverScript(), null)
                                        scanCurrentPageDOM(view)
                                    }
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null
                                    if (!latestBrowserSettings.mediaDiscoveryEnabled || reqUrl.startsWith("about:")) return null

                                    return MediaCaptureEngine.shouldInterceptRequest(
                                        request = request,
                                        baseUrl = currentUrl
                                    ) { item ->
                                        tabSession.registerMedia(item)
                                        DownloaderSession.addDiscoveredItem(item)
                                        onMediaExtracted(item)
                                    }
                                }
                            }

                            if (startUrl.isNotBlank()) {
                                loadUrl(startUrl)
                            }
                        }
                    },
                    update = { wv ->
                        webViewInstance = wv
                        wv.settings.apply {
                            javaScriptEnabled = browserSettings.javaScriptEnabled
                            loadsImagesAutomatically = browserSettings.loadImagesAutomatically
                            mediaPlaybackRequiresUserGesture = !browserSettings.allowAutoplay
                            safeBrowsingEnabled = browserSettings.safeBrowsingEnabled
                            textZoom = browserSettings.websiteTextZoom
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(
                            wv,
                            !browserSettings.blockThirdPartyCookies
                        )
                        wv.setInitialScale(browserSettings.websiteInitialScale)
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.webViewClient = WebViewClient()
                        wv.webChromeClient = WebChromeClient()
                        wv.destroy()
                        if (webViewInstance === wv) {
                            webViewInstance = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("browser_webview_canvas")
                )

                if (currentUrl.isBlank()) {
                    BrowserEmptyHomePage(
                        onNavigate = { target ->
                            navigateToUrl(target)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .testTag("browser_empty_home_page")
                    )
                }
            }

            if (browserSettings.addressBarPosition == BrowserAddressBarPosition.BOTTOM) {
                BrowserNavigationBar(
                    onClose = onClose,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    onGoBack = { webViewInstance?.goBack() },
                    onGoForward = { webViewInstance?.goForward() },
                    addressInput = addressInput,
                    onAddressInputChange = { addressInput = it },
                    onNavigate = { navigateToUrl(addressInput) },
                    isLoading = isLoading,
                    onReloadOrStop = {
                        if (isLoading) webViewInstance?.stopLoading() else webViewInstance?.reload()
                    },
                    onHome = {
                        currentUrl = ""
                        addressInput = ""
                        pageTitle = ""
                        onUrlChange?.invoke("")
                        tabSession.updateNavigation("", "")
                        webViewInstance?.loadUrl("about:blank")
                    },
                    currentUrl = currentUrl,
                    detectedCount = totalDetectedCount,
                    onSwitchToMediaTab = onSwitchToMediaTab,
                    onOpenSettings = { onOpenSettings?.invoke() },
                    showSecurityIndicator = browserSettings.showSecurityIndicator,
                    isTop = false
                )
            }

        }
    }
}


@Composable
private fun BrowserNavigationBar(
    onClose: (() -> Unit)?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    addressInput: String,
    onAddressInputChange: (String) -> Unit,
    onNavigate: () -> Unit,
    isLoading: Boolean,
    onReloadOrStop: () -> Unit,
    onHome: () -> Unit,
    currentUrl: String,
    detectedCount: Int,
    onSwitchToMediaTab: () -> Unit,
    onOpenSettings: () -> Unit,
    showSecurityIndicator: Boolean,
    isTop: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
            .testTag(if (isTop) "browser_toolbar_top" else "browser_toolbar_bottom")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp).testTag("browser_close_btn")) {
                    Icon(Icons.Default.Close, contentDescription = "Close browser", modifier = Modifier.size(19.dp))
                }
            }
            IconButton(onClick = onGoBack, enabled = canGoBack, modifier = Modifier.size(32.dp).testTag("browser_back_btn")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onGoForward, enabled = canGoForward, modifier = Modifier.size(32.dp).testTag("browser_forward_btn")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.weight(1f).height(38.dp).testTag("browser_address_bar")
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isSecure = currentUrl.startsWith("https://", ignoreCase = true)
                    Icon(
                        imageVector = if (showSecurityIndicator && isSecure) Icons.Default.Lock
                            else if (currentUrl.isNotBlank()) Icons.Default.Language else Icons.Default.Search,
                        contentDescription = if (showSecurityIndicator && isSecure) "Secure connection" else null,
                        tint = if (showSecurityIndicator && isSecure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (addressInput.isEmpty()) {
                            Text(
                                text = "Search or type URL",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = addressInput,
                            onValueChange = onAddressInputChange,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { onNavigate() }),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth().testTag("browser_address_input")
                        )
                    }
                    if (addressInput.isNotEmpty()) {
                        IconButton(onClick = { onAddressInputChange("") }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                        }
                    }
                    IconButton(onClick = onNavigate, modifier = Modifier.size(22.dp).testTag("browser_go_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    }
                }
            }
            IconButton(onClick = onReloadOrStop, modifier = Modifier.size(32.dp).testTag("browser_refresh_btn")) {
                Icon(
                    imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                    contentDescription = if (isLoading) "Stop" else "Reload",
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onHome, modifier = Modifier.size(32.dp).testTag("browser_home_btn")) {
                Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(32.dp).testTag("browser_settings_button")) {
                Icon(Icons.Default.Settings, contentDescription = "Browser settings", modifier = Modifier.size(18.dp))
            }
            if (detectedCount > 0) {
                Surface(
                    onClick = onSwitchToMediaTab,
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.height(32.dp).testTag("browser_media_badge_top")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Detected media ($detectedCount)", modifier = Modifier.size(15.dp))
                        Text(text = detectedCount.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (currentUrl.isNotBlank()) {
                IconButton(onClick = onSwitchToMediaTab, modifier = Modifier.size(32.dp).testTag("browser_media_badge_top")) {
                    Icon(Icons.Default.Download, contentDescription = "Detected media (0)", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


/**
 * Clean empty home page displayed when the browser is opened without a URL.
 * Offers a clean interface and quick shortcuts without loading 3rd-party search pages.
 */
@Composable
fun BrowserEmptyHomePage(
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val historyManager = remember { BrowserHistoryManager(context) }
    val allHistorySites by historyManager.allSites.collectAsState(initial = emptyList())

    val topVisitedSites = remember(allHistorySites) {
        if (allHistorySites.isNotEmpty()) {
            allHistorySites.sortedByDescending { it.visitCount }.take(5)
        } else {
            historyManager.defaultTopSites
        }
    }

    val recentSites = remember(allHistorySites) {
        if (allHistorySites.isNotEmpty()) {
            allHistorySites.sortedByDescending { it.lastVisitedMillis }.take(5)
        } else {
            historyManager.defaultTopSites.take(5)
        }
    }

    var homeSearchInput by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 1. Prominent Search / URL Input Bar
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
                shadowElevation = 3.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("home_page_search_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(Modifier.width(10.dp))

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (homeSearchInput.isEmpty()) {
                            Text(
                                text = "Search or type URL",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = homeSearchInput,
                            onValueChange = { homeSearchInput = it },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (homeSearchInput.isNotBlank()) {
                                        onNavigate(homeSearchInput)
                                    }
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_page_search_input")
                        )
                    }

                    if (homeSearchInput.isNotEmpty()) {
                        IconButton(
                            onClick = { homeSearchInput = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Surface(
                        onClick = {
                            if (homeSearchInput.isNotBlank()) {
                                onNavigate(homeSearchInput)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(34.dp).testTag("home_page_search_btn")
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Go",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // 2. Top 5 Visited Sites
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                topVisitedSites.take(5).forEachIndexed { idx, site ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(62.dp)
                            .clickable { onNavigate(site.url) }
                            .testTag("top_site_$idx")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            shadowElevation = 2.dp,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val initial = site.title.ifBlank { site.domain }.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
                                Text(
                                    text = initial,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = site.title.ifBlank { site.domain },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 3. Recent 5 Sites in Recommendation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                recentSites.take(5).forEachIndexed { idx, site ->
                    Surface(
                        onClick = { onNavigate(site.url) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("recent_site_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = site.title.ifBlank { site.domain },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
