package com.amresalehin.emregallery.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.*
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Generic media source classification for direct files vs web pages.
 */
enum class DetectedPlatform(
    val title: String,
    val icon: ImageVector,
    val badgeColor: Color,
    val onBadgeColor: Color,
    val description: String,
    val defaultUrl: String
) {
    DIRECT_MEDIA("Direct Media", Icons.Default.Download, Color(0xFF2E7D32), Color.White, "Direct Audio / Video / Image Stream", ""),
    GENERIC_WEB("Web Page", Icons.Default.Language, Color(0xFF455A64), Color.White, "Generic Web Media", "")
}

fun detectPlatform(url: String): DetectedPlatform {
    val lower = url.trim().lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m3u8") ||
                lower.endsWith(".mpd") || lower.endsWith(".jpg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".mp3") -> DetectedPlatform.DIRECT_MEDIA
        else -> DetectedPlatform.GENERIC_WEB
    }
}

/**
 * Minimal Emre-style Search & URL Fetch Page for the Media Downloader.
 * Clean monochromatic layout with dynamic capsule search input and compact
 * site favicon pills based on user history.
 */
data class DownloaderPill(
    val domain: String,
    val name: String,
    val url: String,
    val explicitFaviconUrl: String? = null
) {
    val faviconUrl: String
        get() = explicitFaviconUrl ?: "https://www.google.com/s2/favicons?domain=${domain.removePrefix("www.")}&sz=64"

    val initial: String
        get() = name.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
}

@Composable
fun DownloaderSearchMainPage(
    onFetchUrl: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onViewSavedLibrary: () -> Unit,
    onOpenActivity: () -> Unit = {},
    onBack: () -> Unit = {},
    savedStacks: List<MediaInboxStack> = emptyList(),
    onSelectStack: (MediaInboxStack) -> Unit = {},
    activeDownloadsCount: Int = 0,
    savedStacksCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val historyManager = remember { BrowserHistoryManager(context) }
    val allHistorySites by historyManager.allSites.collectAsState(initial = emptyList())
    val inboxRepo = remember { MediaInboxRepository.getInstance(context) }
    val repoStacks by inboxRepo.stacks.collectAsState()
    val effectiveStacks = remember(savedStacks, repoStacks) {
        if (savedStacks.isNotEmpty()) savedStacks else repoStacks
    }

    val activities by DownloadActivityManager.activities.collectAsState()
    LaunchedEffect(Unit) {
        DownloadActivityManager.initialize(context)
        DownloadActivityManager.syncWithInboxRepository()
        com.amresalehin.emregallery.GalleryApplication.warmupChromium(context)
    }

    var urlInput by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    fun submitSearch(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        keyboardController?.hide()
        focusManager.clearFocus()
        val normalized = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            "https://$trimmed"
        } else {
            trimmed
        }
        scope.launch {
            historyManager.recordSearchQuery(trimmed)
            historyManager.recordVisit(normalized)
        }
        onFetchUrl(normalized)
    }

    fun openInBrowser(queryOrUrl: String) {
        val trimmed = queryOrUrl.trim()
        keyboardController?.hide()
        focusManager.clearFocus()
        if (trimmed.isBlank()) {
            onOpenBrowser("")
            return
        }
        val destinationUrl = if (trimmed.contains(".") && !trimmed.contains(" ") &&
            (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) ||
                    trimmed.matches(Regex("^[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}(/.*)?$")))) {
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
        scope.launch {
            historyManager.recordSearchQuery(trimmed)
            historyManager.recordVisit(destinationUrl)
        }
        onOpenBrowser(destinationUrl)
    }

    val recentSearches by historyManager.recentSearchQueries.collectAsState(initial = emptyList())

    // 5 most searched/visited site favicons
    val topFiveSites = remember(allHistorySites) {
        val seenDomains = mutableSetOf<String>()
        val result = mutableListOf<DownloaderPill>()
        val sorted = allHistorySites.sortedWith(
            compareByDescending<BrowserSite> { it.visitCount }
                .thenByDescending { it.lastVisitedMillis }
        )
        for (site in sorted) {
            val domain = site.domain.removePrefix("www.").lowercase().trim()
            if (domain.isNotBlank() && seenDomains.add(domain)) {
                val displayName = site.title.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("http") }
                    ?: domain.substringBefore('.').replaceFirstChar { it.uppercase() }
                result.add(
                    DownloaderPill(
                        domain = domain,
                        name = displayName,
                        url = site.url
                    )
                )
            }
        }
        result.take(5)
    }

    // Recent 3 searches
    val recentThreeSearches = remember(recentSearches, allHistorySites) {
        val list = mutableListOf<String>()
        for (q in recentSearches) {
            val trimmed = q.trim()
            if (trimmed.isNotBlank() && list.none { it.equals(trimmed, ignoreCase = true) }) {
                list.add(trimmed)
            }
        }
        // If recentSearches has fewer than 3, backfill from visited site titles/urls
        if (list.size < 3) {
            for (site in allHistorySites) {
                val candidate = site.title.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("http") }
                    ?: site.domain.removePrefix("www.")
                if (candidate.isNotBlank() && list.none { it.equals(candidate, ignoreCase = true) }) {
                    list.add(candidate)
                    if (list.size == 3) break
                }
            }
        }
        list.take(3)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("downloader_search_main_page"),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. One Unified Search Bar on Main Page (Back button & Switcher pills inside search bar, link icon removed)
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("downloader_main_search_card")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Button inside search bar (exits search edit if focused/query present, or navigates back)
                IconButton(
                    onClick = {
                        if (isSearchFocused || urlInput.isNotEmpty()) {
                            urlInput = ""
                            isSearchFocused = false
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        } else {
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("downloader_main_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Switcher Pill & Divider: hidden when search field is focused
                if (!isSearchFocused) {
                    Spacer(Modifier.width(2.dp))

                    // Switcher Pill: download icon only and folder icon only
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Fetch Pill (Active on main search page)
                            Surface(
                                onClick = { /* already on search page */ },
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(width = 34.dp, height = 30.dp)
                                    .testTag("tab_search_fetch")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDownward,
                                        contentDescription = "Fetch",
                                        tint = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Saved Pill (Inactive on main search page, switches to Saved Library)
                            Surface(
                                onClick = onViewSavedLibrary,
                                shape = RoundedCornerShape(14.dp),
                                color = Color.Transparent,
                                modifier = Modifier
                                    .size(width = 34.dp, height = 30.dp)
                                    .testTag("tab_saved_library")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = "Saved",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Divider between switcher and search field
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Search / URL input field (Link icon removed!)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (urlInput.isEmpty()) {
                        Text(
                            text = "Paste link or enter URL...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (urlInput.isNotBlank()) {
                                    submitSearch(urlInput)
                                }
                            }
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                isSearchFocused = focusState.isFocused
                            }
                            .testTag("downloader_main_url_input")
                    )
                }

                Spacer(Modifier.width(6.dp))

                // Action Controls: Always show Browser button (whether empty or query present, and whether focused or not).
                // Paste button removed.
                AnimatedContent(
                    targetState = urlInput.isNotBlank(),
                    transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                    label = "search_bar_actions"
                ) { hasQuery ->
                    if (hasQuery) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Clear input button
                            IconButton(
                                onClick = { urlInput = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("downloader_clear_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            // Browse button (opens in-app browser with query / url)
                            IconButton(
                                onClick = { openInBrowser(urlInput) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("downloader_action_browse_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TravelExplore,
                                    contentDescription = "Browse web with search query",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Download button (opens downloader page) - clean and transparent
                            IconButton(
                                onClick = { submitSearch(urlInput) },
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("downloader_action_download_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = "Download media from URL",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        // Always show browser button even when search bar is empty and not focused
                        IconButton(
                            onClick = { openInBrowser("") },
                            modifier = Modifier
                                .size(36.dp)
                                .testTag("downloader_action_browse_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.TravelExplore,
                                contentDescription = "Open browser homepage",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Recent 3 searches: one in a row. If empty show divider lines.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (i in 0 until 3) {
                val query = recentThreeSearches.getOrNull(i)
                if (query != null) {
                    Surface(
                        onClick = {
                            urlInput = query
                            submitSearch(query)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("recent_search_row_$i")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = query,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.NorthWest,
                                contentDescription = "Select query",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    // Empty state: clean subtle divider lines
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("recent_search_empty_divider_$i"),
                        contentAlignment = Alignment.Center
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 1.dp
                        )
                    }
                }
            }
        }

        // 3. One row with 5 most searched site favicons
        if (topFiveSites.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("top_favicons_row"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                topFiveSites.forEach { site ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clickable {
                                urlInput = site.url
                                submitSearch(site.url)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("top_favicon_${site.name}")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                var iconFailed by remember(site.faviconUrl) { mutableStateOf(false) }
                                if (!iconFailed) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(site.faviconUrl)
                                            .crossfade(true)
                                            .listener(
                                                onError = { _, _ -> iconFailed = true }
                                            )
                                            .build(),
                                        contentDescription = site.name,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = site.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            // Subtle horizontal placeholder divider when no sites have been searched yet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    thickness = 1.dp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        // 3. Activity Card matching image_4fd87912.png
        ActivityPreviewCard(
            activities = activities,
            onOpenActivity = onOpenActivity,
            modifier = Modifier.fillMaxWidth()
        )

        // 4. Saved Preview Section matching image_1d9653bc.png layout with real data
        SavedPreviewSection(
            stacks = effectiveStacks,
            onViewSavedLibrary = onViewSavedLibrary,
            onSelectStack = onSelectStack,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Saved Preview Section matching the layout of image_1d9653bc.png:
 * - Rounded card container with cloud download icon, "Saved" bold title, and "See All >" button
 * - Horizontal scroll of real stacked media cards with colored layer effect
 * - Item count badge on top-right of image, bold title, formatted timestamp
 * - Centered indicator dots below cards
 */
@Composable
fun SavedPreviewSection(
    stacks: List<MediaInboxStack>,
    onViewSavedLibrary: () -> Unit,
    onSelectStack: (MediaInboxStack) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val listState = rememberLazyListState()
    val containerShape = RoundedCornerShape(24.dp)

    Surface(
        shape = containerShape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.65f else 0.75f)
        ),
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = containerShape,
                ambientColor = if (isDark) Color.Black.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.12f),
                spotColor = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.Black.copy(alpha = 0.18f)
            )
            .testTag("downloader_saved_preview_section")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp, bottom = 20.dp, start = 18.dp, end = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: [Cloud Icon] Saved       [See All >]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Saved",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Saved",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    onClick = onViewSavedLibrary,
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.testTag("downloader_saved_see_all_btn")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            val displayStacks = remember(stacks) { stacks.take(5) }
            if (displayStacks.isNotEmpty()) {
                // Horizontal list of stacked preview cards (bounded to max 5 items to avoid unlimited horizontal scrolling)
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("saved_preview_cards_row"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp)
                ) {
                    itemsIndexed(displayStacks, key = { _, item -> item.id }) { index, stack ->
                        SavedStackPreviewCard(
                            stack = stack,
                            accentIndex = index,
                            onClick = { onSelectStack(stack) }
                        )
                    }
                }
            } else {
                // Clean empty state with no text or explanation matching image_6118052c.png
                val placeholderColor = if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                } else {
                    Color(0xFFE8E8EE)
                }
                val placeholderSubColor = if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                } else {
                    Color(0xFFEEEEF4)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp)
                        .clickable(onClick = onViewSavedLibrary)
                        .testTag("saved_empty_state_container"),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    repeat(2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Rounded thumbnail placeholder
                            Box(
                                modifier = Modifier
                                    .size(width = 86.dp, height = 52.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(placeholderColor)
                            )

                            // Title & subtitle line placeholders
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.72f)
                                        .height(11.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(placeholderColor)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.44f)
                                        .height(9.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(placeholderSubColor)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual Saved Stack Preview Card:
 * - Stacked layered cards effect behind the main card with pastel accents
 * - Rounded cover image with item count badge
 * - Stack title and formatted timestamp
 */
@Composable
fun SavedStackPreviewCard(
    stack: MediaInboxStack,
    accentIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val layerColors = remember(accentIndex, isDark) {
        val palettes = listOf(
            // Lavender / Indigo (as in Taj Mahal card)
            if (isDark) listOf(Color(0xFF312E81).copy(alpha = 0.6f), Color(0xFF3730A3).copy(alpha = 0.45f))
            else listOf(Color(0xFFEDE9FE), Color(0xFFDDD6FE)),
            // Warm Amber / Peach (as in Ancient India card)
            if (isDark) listOf(Color(0xFF78350F).copy(alpha = 0.6f), Color(0xFF92400E).copy(alpha = 0.45f))
            else listOf(Color(0xFFFEF3C7), Color(0xFFFDE68A)),
            // Rose / Pink (as in Indian Architecture card)
            if (isDark) listOf(Color(0xFF831843).copy(alpha = 0.6f), Color(0xFF9D174D).copy(alpha = 0.45f))
            else listOf(Color(0xFFFCE7F3), Color(0xFFFBCFE8)),
            // Soft Teal / Cyan
            if (isDark) listOf(Color(0xFF164E63).copy(alpha = 0.6f), Color(0xFF155E75).copy(alpha = 0.45f))
            else listOf(Color(0xFFCFFAFE), Color(0xFFA5F3FC)),
            // Soft Emerald
            if (isDark) listOf(Color(0xFF064E3B).copy(alpha = 0.6f), Color(0xFF065F46).copy(alpha = 0.45f))
            else listOf(Color(0xFFD1FAE5), Color(0xFFA7F3D0))
        )
        palettes[accentIndex % palettes.size]
    }

    val cardWidth = 176.dp
    val cardHeight = 210.dp
    val cardShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .width(cardWidth + 12.dp)
            .height(cardHeight + 12.dp)
            .clickable(onClick = onClick)
            .testTag("saved_preview_stack_${stack.id}")
    ) {
        // Layer 2 (Backmost stacked card)
        Box(
            modifier = Modifier
                .offset(x = 10.dp, y = 10.dp)
                .width(cardWidth)
                .height(cardHeight)
                .background(layerColors[1], shape = cardShape)
                .border(1.dp, layerColors[1].copy(alpha = 0.85f), shape = cardShape)
        )

        // Layer 1 (Middle stacked card)
        Box(
            modifier = Modifier
                .offset(x = 5.dp, y = 5.dp)
                .width(cardWidth)
                .height(cardHeight)
                .background(layerColors[0], shape = cardShape)
                .border(1.dp, layerColors[0].copy(alpha = 0.9f), shape = cardShape)
        )

        // Front Main Card
        Surface(
            modifier = Modifier
                .width(cardWidth)
                .height(cardHeight),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = if (isDark) 1.dp else 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Thumbnail Box with item count badge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    val coverItem = stack.items.firstOrNull { !it.isVideo && it.localUri.isNotBlank() }
                        ?: stack.items.firstOrNull { it.localUri.isNotBlank() }
                        ?: stack.items.firstOrNull()

                    val imageModel = coverItem?.localUri?.takeIf { it.isNotBlank() }
                        ?: coverItem?.sourceUrl?.takeIf { it.isNotBlank() }

                    if (imageModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(imageModel)
                                .crossfade(true)
                                .build(),
                            contentDescription = stack.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    // Count Badge in top-right (e.g. 16, 24, 8)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xCC1A1A1A),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .testTag("saved_preview_count_badge")
                    ) {
                        Text(
                            text = "${stack.items.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Title
                Text(
                    text = stack.title.ifBlank { "Saved Collection" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(Modifier.height(2.dp))

                // Formatted Date & Time (e.g. Nov 04, 2026 • 9:02 AM)
                val dateFormatted = remember(stack.timestampMillis) {
                    try {
                        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy • h:mm a", java.util.Locale.getDefault())
                        sdf.format(java.util.Date(stack.timestampMillis))
                    } catch (e: Exception) {
                        ""
                    }
                }
                Text(
                    text = dateFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

fun parseScrapedMediaDimensions(item: ScrapedMediaItem): Pair<Int?, Int?> {
    val hint = item.resolutionHint ?: return null to null
    val parts = hint.split("×", "x", "*").map { it.trim().filter { ch -> ch.isDigit() } }
    if (parts.size >= 2) {
        val w = parts[0].toIntOrNull()
        val h = parts[1].toIntOrNull()
        return w to h
    }
    return null to null
}

fun passesDimensionFilter(
    item: ScrapedMediaItem,
    minWidth: Int,
    minHeight: Int,
    dimensionsMap: Map<String, Pair<Int, Int>>
): Boolean {
    if (minWidth <= 0 && minHeight <= 0) return true
    if (item.isVideo || item.isAudio || item.isStream) return true
    val (w, h) = dimensionsMap[item.id] ?: parseScrapedMediaDimensions(item)
    if (w == null || h == null) return false
    val widthOk = if (minWidth > 0) w >= minWidth else true
    val heightOk = if (minHeight > 0) h >= minHeight else true
    return widthOk && heightOk
}

