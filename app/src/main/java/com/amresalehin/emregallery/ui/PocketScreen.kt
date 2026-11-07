package com.amresalehin.emregallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.MediaInboxStack
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Strict monochrome color palette for PocketScreen, conforming to Emre Gallery's design:
 * Built exclusively from pure neutrals: whites, grays, and near-blacks.
 * Perfectly calibrated and optimized for both Day (Light) and Night (Dark) mode.
 */
object PocketThemeColors {
    fun background(isDark: Boolean): Color =
        if (isDark) Color(0xFF101010) else Color(0xFFFFFFFF)

    fun barSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF1C1C1C) else Color(0xFFF7F7F7)

    fun cardSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF181818) else Color(0xFFFFFFFF)

    fun primaryText(isDark: Boolean): Color =
        if (isDark) Color(0xFFEDEDED) else Color(0xFF141414)

    fun secondaryText(isDark: Boolean): Color =
        if (isDark) Color(0xFFA0A0A0) else Color(0xFF666666)

    fun divider(isDark: Boolean): Color =
        if (isDark) Color(0xFF2C2C2C) else Color(0xFFE2E2E2)

    fun thumbnailBg(isDark: Boolean): Color =
        if (isDark) Color(0xFF222222) else Color(0xFFECECEC)

    fun placeholderIcon(isDark: Boolean): Color =
        if (isDark) Color(0xFF707070) else Color(0xFF9E9E9E)

    fun pillSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF242424) else Color(0xFFF0F0F0)

    fun badgeBg(isDark: Boolean): Color =
        if (isDark) Color(0xFF222222).copy(alpha = 0.9f) else Color(0xFF181818).copy(alpha = 0.8f)

    fun badgeText(isDark: Boolean): Color =
        if (isDark) Color(0xFFEDEDED) else Color(0xFFFFFFFF)

    fun stackLayer1(isDark: Boolean): Color =
        if (isDark) Color(0xFF141414) else Color(0xFFDFDFDF)

    fun stackLayer2(isDark: Boolean): Color =
        if (isDark) Color(0xFF1C1C1C) else Color(0xFFE8E8E8)

    fun stackFront(isDark: Boolean): Color =
        if (isDark) Color(0xFF242424) else Color(0xFFFFFFFF)

    fun webGradient(isDark: Boolean): List<Color> =
        if (isDark) listOf(Color(0xFF222222), Color(0xFF181818))
        else listOf(Color(0xFFF5F5F5), Color(0xFFE8E8E8))

    fun faviconSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF282828) else Color(0xFFFFFFFF)

    fun drawerSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF181818) else Color(0xFFFFFFFF)

    fun drawerHandle(isDark: Boolean): Color =
        if (isDark) Color(0xFF484848) else Color(0xFFCCCCCC)

    fun detailRowSurface(isDark: Boolean): Color =
        if (isDark) Color(0xFF202020) else Color(0xFFF7F7F7)

    fun tagChipBg(isDark: Boolean): Color =
        if (isDark) Color(0xFF282828) else Color(0xFFEEEEEE)
}

/**
 * Pocket Screen
 * Displays saved bookmarks and web collections in a clean, modern staggered masonry layout.
 * Unified search & paste bar aligns with gallery search bar behavior, removing separate headers
 * and embedding navigation and filter controls within the capsule.
 * Clicking any bookmark card opens the Bookmark Viewer / Detail page with stacked card presentation
 * and structured metadata (Time, Source, Note, Tags, Title, Description).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketScreen(
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
    onMenuClick: () -> Unit = {},
    onOpenMediaItem: ((String) -> Unit)? = null,
    onOpenMediaWithList: ((String, List<String>) -> Unit)? = null,
    onDetailViewerStateChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val inboxRepo = remember { MediaInboxRepository.getInstance(context) }
    val stacks by inboxRepo.stacks.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedStackIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedStackIds.isNotEmpty()

    var sortOrder by remember { mutableStateOf("Newest") } // Newest, Oldest, Title, Domain
    var filterType by remember { mutableStateOf("All") } // All, Web Links, Media
    var showFilterSheet by remember { mutableStateOf(false) }

    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var editingStack by remember { mutableStateOf<MediaInboxStack?>(null) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var selectedStackForDetail by remember { mutableStateOf<MediaInboxStack?>(null) }

    var showFullScreenBrowser by remember { mutableStateOf(false) }
    var browserUrl by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    // Clipboard check for quick paste affordance
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var clipboardUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isSearchFocused) {
        if (isSearchFocused) {
            val clip = clipboardManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val clipText = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (MediaClassifier.isValidWebUrl(clipText) || clipText.startsWith("http://") || clipText.startsWith("https://")) {
                    clipboardUrl = clipText
                } else {
                    clipboardUrl = null
                }
            }
        }
    }

    // Filter and sort stacks
    val filteredStacks = remember(stacks, searchQuery, sortOrder, filterType) {
        var result = stacks.filter { stack ->
            val matchesQuery = if (searchQuery.isBlank()) {
                true
            } else {
                stack.title.contains(searchQuery, ignoreCase = true) ||
                        stack.sourceDomain.contains(searchQuery, ignoreCase = true) ||
                        stack.sourceUrl.contains(searchQuery, ignoreCase = true) ||
                        stack.description.contains(searchQuery, ignoreCase = true) ||
                        stack.items.any { it.title.contains(searchQuery, ignoreCase = true) }
            }

            val matchesFilter = when (filterType) {
                "Web Links" -> stack.items.isEmpty() || stack.sourceUrl.isNotBlank()
                "Media" -> stack.items.isNotEmpty()
                else -> true
            }

            matchesQuery && matchesFilter
        }

        result = when (sortOrder) {
            "Oldest" -> result.sortedBy { it.timestampMillis }
            "Title" -> result.sortedBy { it.title.lowercase() }
            "Domain" -> result.sortedBy { it.sourceDomain.lowercase() }
            else -> result.sortedByDescending { it.timestampMillis } // Newest
        }

        result
    }

    LaunchedEffect(selectedStackForDetail) {
        onDetailViewerStateChange?.invoke(selectedStackForDetail != null)
    }
    DisposableEffect(Unit) {
        onDispose {
            onDetailViewerStateChange?.invoke(false)
        }
    }

    // Handle back button
    BackHandler(enabled = !showFullScreenBrowser && (selectedStackForDetail != null || isSelectionMode || isSearchFocused || searchQuery.isNotEmpty() || showFilterSheet)) {
        when {
            showFullScreenBrowser -> showFullScreenBrowser = false
            selectedStackForDetail != null -> {
                selectedStackForDetail = null
                onDetailViewerStateChange?.invoke(false)
            }
            isSelectionMode -> selectedStackIds = emptySet()
            isSearchFocused || searchQuery.isNotEmpty() -> {
                searchQuery = ""
                isSearchFocused = false
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            showFilterSheet -> showFilterSheet = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark))
            .testTag("pocket_screen")
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = PocketThemeColors.background(isDark),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = PocketThemeColors.background(isDark),
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (isSelectionMode) {
                            // Selection mode toolbar
                            PocketSelectionToolbar(
                                selectedCount = selectedStackIds.size,
                                allSelected = filteredStacks.isNotEmpty() && filteredStacks.all { it.id in selectedStackIds },
                                onClearSelection = { selectedStackIds = emptySet() },
                                onToggleSelectAll = {
                                    val all = filteredStacks.isNotEmpty() && filteredStacks.all { it.id in selectedStackIds }
                                    selectedStackIds = if (all) emptySet() else filteredStacks.map { it.id }.toSet()
                                },
                                onShare = {
                                    val urlsToShare = filteredStacks
                                        .filter { it.id in selectedStackIds && it.sourceUrl.isNotBlank() }
                                        .map { "${it.title}: ${it.sourceUrl}" }
                                    if (urlsToShare.isNotEmpty()) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, urlsToShare.joinToString("\n\n"))
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share bookmarks"))
                                    }
                                },
                                onDelete = { showDeleteConfirmDialog = true },
                                isDark = isDark
                            )
                        } else {
                            // Unified Search & Paste Bar (aligning with other search bar behavior, no separate headers)
                            val isUrlInQuery = searchQuery.isNotBlank() && (
                                    MediaClassifier.isValidWebUrl(searchQuery.trim()) ||
                                            searchQuery.trim().startsWith("http://") ||
                                            searchQuery.trim().startsWith("https://")
                                    )
                            val hasActiveFilters = filterType != "All" || sortOrder != "Newest"

                            PocketSearchPasteCapsule(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                isFocused = isSearchFocused,
                                onFocusChange = { isSearchFocused = it },
                                onMenuClick = onMenuClick,
                                onOpenFilterSheet = { showFilterSheet = true },
                                hasActiveFilters = hasActiveFilters,
                                onBookmarkAction = {
                                    val trimmed = searchQuery.trim()
                                    if (trimmed.isNotBlank()) {
                                        val domain = MediaClassifier.cleanDomainFromUrl(trimmed)
                                        val saved = inboxRepo.addBookmark(
                                            title = domain.ifBlank { trimmed },
                                            url = trimmed
                                        )
                                        searchQuery = ""
                                        isSearchFocused = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        selectedStackForDetail = saved
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Bookmark saved to Pocket")
                                        }
                                    } else if (clipboardUrl != null) {
                                        val domain = MediaClassifier.cleanDomainFromUrl(clipboardUrl!!)
                                        val saved = inboxRepo.addBookmark(
                                            title = domain.ifBlank { clipboardUrl!! },
                                            url = clipboardUrl!!
                                        )
                                        clipboardUrl = null
                                        isSearchFocused = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                        selectedStackForDetail = saved
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Bookmark saved from clipboard")
                                        }
                                    } else {
                                        showAddBookmarkDialog = true
                                    }
                                },
                                onSearchSubmit = { target ->
                                    val trimmed = target.trim()
                                    if (trimmed.isNotBlank()) {
                                        if (MediaClassifier.isValidWebUrl(trimmed) || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                            val domain = MediaClassifier.cleanDomainFromUrl(trimmed)
                                            val saved = inboxRepo.addBookmark(
                                                title = domain.ifBlank { trimmed },
                                                url = trimmed
                                            )
                                            searchQuery = ""
                                            isSearchFocused = false
                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            selectedStackForDetail = saved
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Bookmark saved to Pocket")
                                            }
                                        } else {
                                            keyboardController?.hide()
                                        }
                                    }
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (filteredStacks.isNotEmpty()) {
                    // Staggered 2-column masonry grid matching image_15171895.png
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .testTag("pocket_bookmarks_grid"),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalItemSpacing = 14.dp
                    ) {
                        itemsIndexed(filteredStacks, key = { _, stack -> stack.id }) { index, stack ->
                            val isSelected = stack.id in selectedStackIds
                            PocketBookmarkStaggeredCard(
                                stack = stack,
                                index = index,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        selectedStackIds = if (isSelected) {
                                            selectedStackIds - stack.id
                                        } else {
                                            selectedStackIds + stack.id
                                        }
                                    } else {
                                        // Clicking link opens a bookmark detail page as requested
                                        selectedStackForDetail = stack
                                    }
                                },
                                onLongClick = {
                                    selectedStackIds = if (isSelectionMode) {
                                        if (isSelected) selectedStackIds - stack.id else selectedStackIds + stack.id
                                    } else {
                                        setOf(stack.id)
                                    }
                                },
                                isDark = isDark,
                                modifier = Modifier.testTag("pocket_bookmark_card_${stack.id}")
                            )
                        }
                    }
                } else {
                    // Empty state
                    PocketEmptyState(
                        searchQuery = searchQuery,
                        onAddBookmark = { showAddBookmarkDialog = true }
                    )
                }
            }
        }

        // Bookmark Viewer / Detail Page matching image_a086dfff.png
        AnimatedVisibility(
            visible = selectedStackForDetail != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(50f)
        ) {
            selectedStackForDetail?.let { stack ->
                PocketBookmarkViewerPage(
                    stack = stack,
                    onBack = {
                        selectedStackForDetail = null
                        onDetailViewerStateChange?.invoke(false)
                    },
                    onOpenBrowser = { url ->
                        browserUrl = url
                        showFullScreenBrowser = true
                    },
                    onDelete = {
                        inboxRepo.deleteStack(stack.id)
                        selectedStackForDetail = null
                        onDetailViewerStateChange?.invoke(false)
                        scope.launch {
                            snackbarHostState.showSnackbar("Bookmark deleted")
                        }
                    },
                    onEditNote = { editingStack = stack },
                    onOpenMediaItem = onOpenMediaItem,
                    onOpenMediaWithList = onOpenMediaWithList,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Fullscreen in-app web browser
        if (showFullScreenBrowser) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(100f)
            ) {
                DirectBrowserView(
                    initialUrl = browserUrl,
                    onClose = { showFullScreenBrowser = false },
                    onMediaExtracted = {},
                    onBatchExtracted = { _, _, _ -> },
                    onSwitchToMediaTab = { showFullScreenBrowser = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Add Bookmark Dialog
        if (showAddBookmarkDialog) {
            AddBookmarkDialog(
                onDismiss = { showAddBookmarkDialog = false },
                onSave = { title, url, description ->
                    val added = inboxRepo.addBookmark(
                        title = title,
                        url = url,
                        description = description
                    )
                    showAddBookmarkDialog = false
                    selectedStackForDetail = added
                    scope.launch {
                        snackbarHostState.showSnackbar("Bookmark saved to Pocket")
                    }
                }
            )
        }

        // Edit Bookmark Dialog
        editingStack?.let { stack ->
            EditBookmarkDialog(
                stack = stack,
                onDismiss = { editingStack = null },
                onSave = { updatedTitle, updatedDesc ->
                    val updated = stack.copy(
                        title = updatedTitle,
                        description = updatedDesc
                    )
                    inboxRepo.updateStack(updated)
                    if (selectedStackForDetail?.id == stack.id) {
                        selectedStackForDetail = updated
                    }
                    editingStack = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Bookmark updated")
                    }
                }
            )
        }

        // Delete confirmation dialog
        if (showDeleteConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { Text("Delete Bookmarks") },
                text = { Text("Are you sure you want to remove ${selectedStackIds.size} bookmarks from Pocket?") },
                confirmButton = {
                    Button(
                        onClick = {
                            selectedStackIds.forEach { id -> inboxRepo.deleteStack(id) }
                            selectedStackIds = emptySet()
                            showDeleteConfirmDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Bookmarks deleted")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Filter & Sort Bottom Sheet
        if (showFilterSheet) {
            PocketFilterBottomSheet(
                currentSort = sortOrder,
                currentFilter = filterType,
                onSelectSort = { sortOrder = it },
                onSelectFilter = { filterType = it },
                onDismiss = { showFilterSheet = false }
            )
        }
    }
}

/**
 * Single unified capsule search and paste bar.
 * Aligned with Maindownloader page behavior:
 * - When focused: leading icon becomes Back arrow, and trailing controls show Cross (clear) and Bookmark icon
 * - When not focused: leading icon is Hamburger menu, and trailing control shows Filter sliders
 */
@Composable
fun PocketSearchPasteCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    isFocused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onOpenFilterSheet: () -> Unit,
    hasActiveFilters: Boolean,
    onBookmarkAction: () -> Unit,
    onSearchSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val barColor = PocketThemeColors.barSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = CircleShape,
        color = barColor,
        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading Button: Back button if focused or query is non-empty, otherwise Hamburger Menu
            IconButton(
                onClick = {
                    if (isFocused || query.isNotEmpty()) {
                        onQueryChange("")
                        onFocusChange(false)
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    } else {
                        onMenuClick()
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .testTag(if (isFocused || query.isNotEmpty()) "pocket_search_back_button" else "pocket_menu_button")
            ) {
                Icon(
                    imageVector = if (isFocused || query.isNotEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                    contentDescription = if (isFocused || query.isNotEmpty()) "Back" else "Open navigation menu",
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (!isFocused && query.isEmpty()) {
                // Divider |
                Spacer(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(dividerCol.copy(alpha = 0.8f))
                )
            }

            Spacer(Modifier.width(8.dp))

            // Search / URL Input with visible caret
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search library or paste URL",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            onFocusChange(focusState.isFocused)
                        }
                        .testTag("pocket_search_input"),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.isNotBlank()) {
                                onSearchSubmit(query.trim())
                            }
                        }
                    )
                )
            }

            // Trailing actions:
            // When focused (or query non-empty), shows cross (clear) and bookmark icon (matching Maindownloader behavior)
            if (isFocused || query.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Cross (clear) button
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("pocket_search_clear_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear search",
                            tint = secondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Bookmark icon button
                    IconButton(
                        onClick = onBookmarkAction,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("pocket_search_bookmark_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = "Save bookmark",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            } else {
                // Divider |
                Spacer(
                    modifier = Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(dividerCol.copy(alpha = 0.8f))
                )

                // Trailing Filter Sliders Action inside the search bar
                Box {
                    IconButton(
                        onClick = onOpenFilterSheet,
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("pocket_tune_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Filter and sort bookmarks",
                            tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else secondaryText,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (hasActiveFilters) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 10.dp, end = 10.dp)
                                .size(7.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pocket Selection Mode Toolbar
 */
@Composable
fun PocketSelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    onClearSelection: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    isDark: Boolean = false
) {
    val barColor = PocketThemeColors.barSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)

    Surface(
        shape = CircleShape,
        color = barColor,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("pocket_selection_toolbar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClearSelection,
                modifier = Modifier.size(38.dp).testTag("selection_close_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit selection",
                    tint = textColor
                )
            }

            Spacer(Modifier.width(4.dp))

            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.weight(1f).testTag("selection_count_text")
            )

            IconButton(
                onClick = onToggleSelectAll,
                modifier = Modifier.size(38.dp).testTag("selection_select_all_button")
            ) {
                Icon(
                    imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = if (allSelected) "Deselect all" else "Select all",
                    tint = textColor
                )
            }

            IconButton(
                onClick = onShare,
                modifier = Modifier.size(38.dp).testTag("selection_share_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share selected bookmarks",
                    tint = textColor
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(38.dp).testTag("pocket_delete_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete selected",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Pocket Bookmark Staggered Card conforming to image_15171895.png
 * Features:
 * - Rounded rectangle container (22.dp)
 * - Top preview image placeholder or real image/preview with varied aspect heights for masonry stagger
 * - Bottom white/surface pill section with Line 1 (Title) and Line 2 (Subtitle/Domain)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PocketBookmarkStaggeredCard(
    stack: MediaInboxStack,
    index: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardSurface = PocketThemeColors.cardSurface(isDark)
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    // Vary preview height to produce the natural staggered masonry flow from image_15171895.png
    val previewHeight = remember(index) {
        when (index % 4) {
            0 -> 180.dp
            1 -> 130.dp
            2 -> 140.dp
            else -> 195.dp
        }
    }

    val firstMedia = stack.items.firstOrNull()
    val mediaModel = remember(firstMedia) {
        firstMedia?.let {
            if (it.localUri.isNotBlank()) {
                val f = File(it.localUri.removePrefix("file://"))
                if (f.exists()) f else it.sourceUrl.ifBlank { it.localUri }
            } else {
                it.sourceUrl
            }
        }
    }

    val faviconUrl = remember(stack.sourceDomain) {
        if (stack.sourceDomain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=128"
        } else ""
    }

    val dateTimeFormatted = remember(stack.timestampMillis) {
        val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        sdf.format(Date(stack.timestampMillis))
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = cardSurface,
            tonalElevation = if (isSelected) 8.dp else 2.dp,
            shadowElevation = if (isSelected) 8.dp else 3.dp,
            border = BorderStroke(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else dividerCol.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Top Preview Image Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .background(PocketThemeColors.thumbnailBg(isDark)),
                    contentAlignment = Alignment.Center
                ) {
                    if (mediaModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(mediaModel)
                                .crossfade(true)
                                .build(),
                            contentDescription = stack.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (faviconUrl.isNotBlank()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = PocketThemeColors.faviconSurface(isDark),
                                shadowElevation = 2.dp,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(faviconUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = stack.sourceDomain,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stack.sourceDomain,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        // Skeleton image placeholder icon matching image_15171895.png
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = PocketThemeColors.placeholderIcon(isDark),
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    // Media badge overlay if stack has multiple items
                    if (stack.items.size > 1) {
                        Surface(
                            shape = CircleShape,
                            color = PocketThemeColors.badgeBg(isDark),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = null,
                                    tint = PocketThemeColors.badgeText(isDark),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "${stack.items.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PocketThemeColors.badgeText(isDark)
                                )
                            }
                        }
                    }
                }

                // Bottom Content Section: Title, Date & Time, Favicon & Site Address (matching image_026bd3b6.png)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // Line 1: Title (e.g. "Ancient India")
                    Text(
                        text = stack.title.ifBlank { stack.sourceDomain.ifBlank { "Untitled Bookmark" } },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(3.dp))

                    // Line 2: Date & Time (e.g. "Oct 29, 2026 · 4:06 AM")
                    Text(
                        text = dateTimeFormatted,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp
                        ),
                        color = secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(6.dp))

                    // Line 3: Favicon & Site Address Pill (e.g. [🏛️ | britannica.com])
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = PocketThemeColors.pillSurface(isDark),
                        border = BorderStroke(0.5.dp, dividerCol.copy(alpha = 0.4f)),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (faviconUrl.isNotBlank()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(faviconUrl)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = secondaryText,
                                    modifier = Modifier.size(13.dp)
                                )
                            }

                            Spacer(Modifier.width(5.dp))

                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(10.dp)
                                    .background(dividerCol.copy(alpha = 0.6f))
                            )

                            Spacer(Modifier.width(5.dp))

                            Text(
                                text = stack.sourceDomain.ifBlank { "bookmark" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Selection Checkbox indicator
        if (isSelectionMode) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Bookmark Viewer / Detail Page conforming strictly to image_a086dfff.png
 * Features:
 * - Top Bar: Circular Back button on left, 3 circular buttons on right (Reader/Browser, Copy, Delete)
 * - Center visual: Layered card stack deck with image/cover and `[🖼️ X / Y]` pagination badge
 * - Bottom sheet: Drag handle + rounded capsule cards for:
 *   1. 📅 Time
 *   2. 🔗 Source (clickable with open icon)
 *   3. 📄 Note (clickable to edit)
 *   4. 🏷️ Tags (chips of domain/type)
 *   5. ≡ Title
 *   6. ≡ Description
 */
@Composable
fun PocketBookmarkViewerPage(
    stack: MediaInboxStack,
    onBack: () -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDelete: () -> Unit,
    onEditNote: () -> Unit,
    onOpenMediaItem: ((String) -> Unit)? = null,
    onOpenMediaWithList: ((String, List<String>) -> Unit)? = null,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textColor = PocketThemeColors.primaryText(isDark)
    val secondaryText = PocketThemeColors.secondaryText(isDark)
    val cardSurface = PocketThemeColors.stackFront(isDark)
    val barSurface = PocketThemeColors.barSurface(isDark)
    val dividerCol = PocketThemeColors.divider(isDark)

    val dateFormatted = remember(stack.timestampMillis) {
        val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        sdf.format(Date(stack.timestampMillis))
    }

    val mediaItems = stack.items
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { maxOf(1, mediaItems.size) })

    val faviconUrl = remember(stack.sourceDomain) {
        if (stack.sourceDomain.isNotBlank()) {
            "https://www.google.com/s2/favicons?domain=${stack.sourceDomain}&sz=128"
        } else ""
    }

    // Derive tags without fabricating any information
    val derivedTags = remember(stack) {
        val tags = mutableListOf<String>()
        if (stack.sourceDomain.isNotBlank()) {
            tags.add(stack.sourceDomain.removePrefix("www."))
        }
        if (stack.items.isNotEmpty()) {
            tags.add("${stack.items.size} Media")
        } else if (stack.sourceUrl.isNotBlank()) {
            tags.add("Web Link")
        }
        if (tags.isEmpty()) {
            tags.add("Bookmark")
        }
        tags
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(PocketThemeColors.background(isDark)),
        color = PocketThemeColors.background(isDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top App Bar with Circular Buttons matching image_a086dfff.png
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Back Button (Circle)
                Surface(
                    shape = CircleShape,
                    color = barSurface,
                    shadowElevation = 2.dp,
                    border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onBack)
                        .testTag("bookmark_viewer_back_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Action Buttons on Right (Reader, Copy, Delete)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Open Reader / Browser
                    if (stack.sourceUrl.isNotBlank()) {
                        Surface(
                            shape = CircleShape,
                            color = barSurface,
                            shadowElevation = 2.dp,
                            border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .size(44.dp)
                                .clickable { onOpenBrowser(stack.sourceUrl) }
                                .testTag("bookmark_viewer_reader_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MenuBook,
                                    contentDescription = "Open in Reader",
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // 2. Copy Link
                    Surface(
                        shape = CircleShape,
                        color = barSurface,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                val textToCopy = stack.sourceUrl.ifBlank { stack.title }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("URL", textToCopy)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                            .testTag("bookmark_viewer_copy_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy link",
                                tint = textColor,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    // 3. Delete
                    Surface(
                        shape = CircleShape,
                        color = barSurface,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = onDelete)
                            .testTag("bookmark_viewer_delete_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete bookmark",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Upper Visual Area: Card Deck & Pagination
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Layered Card Deck matching image_a086dfff.png
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Layer 1 (Bottom stacked layer)
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = PocketThemeColors.stackLayer1(isDark),
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(180.dp)
                            .offset(y = (-10).dp)
                    ) {}

                    // Layer 2 (Middle stacked layer)
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = PocketThemeColors.stackLayer2(isDark),
                        modifier = Modifier
                            .fillMaxWidth(0.93f)
                            .height(185.dp)
                            .offset(y = (-5).dp)
                    ) {}

                    // Layer 3 (Top Front Card)
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = PocketThemeColors.stackFront(isDark),
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(195.dp)
                    ) {
                        if (mediaItems.isNotEmpty()) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize()
                            ) { page ->
                                val item = mediaItems[page]
                                val mediaPath = remember(item) {
                                    if (item.localUri.isNotBlank()) {
                                        val f = File(item.localUri.removePrefix("file://"))
                                        if (f.exists()) f else item.sourceUrl
                                    } else item.sourceUrl
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            val all = mediaItems.map { it.localUri.ifBlank { it.sourceUrl } }
                                            val target = item.localUri.ifBlank { item.sourceUrl }
                                            if (onOpenMediaWithList != null) {
                                                onOpenMediaWithList(target, all)
                                            } else {
                                                onOpenMediaItem?.invoke(target)
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(mediaPath)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        } else {
                            // Web link preview presentation with icon & artwork
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            colors = PocketThemeColors.webGradient(isDark)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    if (faviconUrl.isNotBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = PocketThemeColors.faviconSurface(isDark),
                                            shadowElevation = 3.dp,
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                AsyncImage(
                                                    model = ImageRequest.Builder(context)
                                                        .data(faviconUrl)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = stack.sourceDomain,
                                                    modifier = Modifier.size(34.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Bookmark,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(44.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = stack.sourceDomain.ifBlank { "Bookmark Preview" },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = textColor
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Pagination pill indicator [🖼️ 1 / 1] matching image_a086dfff.png
                Surface(
                    shape = CircleShape,
                    color = barSurface,
                    border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
                    modifier = Modifier.height(30.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = secondaryText,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        val currentPage = if (mediaItems.isNotEmpty()) pagerState.currentPage + 1 else 1
                        val totalPages = maxOf(1, mediaItems.size)
                        Text(
                            text = "$currentPage / $totalPages",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Bottom Drawer / Bottom Sheet: anchored to and starts from bottom, filling remaining height!
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = PocketThemeColors.drawerSurface(isDark),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.5f)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Drag Handle Pill (with swipe down to dismiss drawer gesture)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 2.dp)
                            .size(width = 80.dp, height = 20.dp)
                            .pointerInput(Unit) {
                                detectVerticalDragGestures { _, dragAmount ->
                                    if (dragAmount > 15f) {
                                        onBack()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(PocketThemeColors.drawerHandle(isDark))
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    // 1. Time Row
                    PocketDetailCardRow(
                        icon = Icons.Default.CalendarToday,
                        label = "Time",
                        value = dateFormatted,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        barSurface = barSurface,
                        dividerCol = dividerCol
                    )

                    // 2. Source Row (Clickable to open link)
                    PocketDetailCardRow(
                        icon = Icons.Default.Link,
                        label = "Source",
                        value = stack.sourceUrl.ifBlank { stack.sourceDomain.ifBlank { "No source URL" } },
                        trailingIcon = if (stack.sourceUrl.isNotBlank()) Icons.AutoMirrored.Filled.OpenInNew else null,
                        onClick = if (stack.sourceUrl.isNotBlank()) { { onOpenBrowser(stack.sourceUrl) } } else null,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        barSurface = barSurface,
                        dividerCol = dividerCol
                    )

                    // 3. Note Row (Clickable to edit note)
                    PocketDetailCardRow(
                        icon = Icons.Default.Description,
                        label = "Note",
                        value = stack.description.ifBlank { "Tap to add note" },
                        trailingIcon = Icons.Default.Edit,
                        onClick = onEditNote,
                        textColor = textColor,
                        secondaryText = secondaryText,
                        barSurface = barSurface,
                        dividerCol = dividerCol
                    )

                    // 4. Tags Row
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = barSurface,
                        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sell,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Tags",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = textColor
                            )
                            Spacer(Modifier.width(16.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                derivedTags.forEach { tag ->
                                    Surface(
                                        shape = CircleShape,
                                        color = PocketThemeColors.tagChipBg(isDark),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(horizontal = 10.dp)
                                        ) {
                                            Text(
                                                text = tag,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                color = textColor
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. Title Row
                    PocketDetailCardRow(
                        icon = Icons.Default.ShortText,
                        label = "Title",
                        value = stack.title.ifBlank { "Untitled Bookmark" },
                        textColor = textColor,
                        secondaryText = secondaryText,
                        barSurface = barSurface,
                        dividerCol = dividerCol
                    )

                    // 6. Description Row
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = barSurface,
                        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Subject,
                                    contentDescription = null,
                                    tint = textColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Description",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = textColor
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (stack.description.isNotBlank()) stack.description else "No additional description provided.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (stack.description.isNotBlank()) textColor else secondaryText
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

/**
 * Reusable detail row card conforming to image_a086dfff.png
 */
@Composable
fun PocketDetailCardRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    trailingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: (() -> Unit)? = null,
    textColor: Color,
    secondaryText: Color,
    barSurface: Color,
    dividerCol: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = barSurface,
        border = BorderStroke(1.dp, dividerCol.copy(alpha = 0.4f)),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailingIcon != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = null,
                    tint = secondaryText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Empty state for Pocket
 */
@Composable
fun PocketEmptyState(
    searchQuery: String,
    onAddBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .testTag("pocket_empty_state"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isDark = isSystemInDarkTheme()
            Surface(
                shape = CircleShape,
                color = if (isDark) Color(0xFF222222) else Color(0xFFF2F2F2),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isDark) Color(0xFFD4D4D4) else Color(0xFF383838),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (searchQuery.isNotBlank()) "No bookmarks found" else "Your Pocket is empty",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (searchQuery.isNotBlank()) {
                    "No bookmarks matching \"$searchQuery\". Try a different keyword."
                } else {
                    "Bookmark web links, articles, and saved pages to view and read anytime."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (searchQuery.isBlank()) {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onAddBookmark,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFFEEEEEE) else Color(0xFF202020),
                        contentColor = if (isDark) Color(0xFF161616) else Color(0xFFFFFFFF)
                    ),
                    modifier = Modifier.testTag("pocket_empty_add_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Bookmark")
                }
            }
        }
    }
}

/**
 * Add Bookmark Dialog
 */
@Composable
fun AddBookmarkDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, description: String) -> Unit
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf("") }
    var titleText by remember { mutableStateOf("") }
    var descText by remember { mutableStateOf("") }

    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val clipboardUrl = remember {
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
            if (MediaClassifier.isValidWebUrl(text) || text.startsWith("http://") || text.startsWith("https://")) text else null
        } else null
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BookmarkAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add Bookmark to Pocket",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Paste from clipboard suggestion
                if (clipboardUrl != null && urlText.isBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                urlText = clipboardUrl
                                if (titleText.isBlank()) {
                                    titleText = MediaClassifier.cleanDomainFromUrl(clipboardUrl)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Paste: $clipboardUrl",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        if (titleText.isBlank() && it.isNotBlank()) {
                            val domain = MediaClassifier.cleanDomainFromUrl(it)
                            if (domain.isNotBlank()) titleText = domain
                        }
                    },
                    label = { Text("Web URL / Link *") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Title (optional)") },
                    placeholder = { Text("Article or page title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    label = { Text("Notes / Description (optional)") },
                    placeholder = { Text("Add notes or details") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (urlText.isNotBlank()) {
                                onSave(titleText, urlText, descText)
                            }
                        },
                        enabled = urlText.isNotBlank()
                    ) {
                        Text("Save Bookmark")
                    }
                }
            }
        }
    }
}

/**
 * Edit Bookmark Dialog
 */
@Composable
fun EditBookmarkDialog(
    stack: MediaInboxStack,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String) -> Unit
) {
    var titleText by remember { mutableStateOf(stack.title) }
    var descText by remember { mutableStateOf(stack.description) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Edit Bookmark",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    label = { Text("Notes / Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(titleText, descText) },
                        enabled = titleText.isNotBlank()
                    ) {
                        Text("Update")
                    }
                }
            }
        }
    }
}

/**
 * Pocket Filter & Sort Bottom Sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketFilterBottomSheet(
    currentSort: String,
    currentFilter: String,
    onSelectSort: (String) -> Unit,
    onSelectFilter: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Sort & Filter Bookmarks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Sort By",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            val sortOptions = listOf("Newest", "Oldest", "Title", "Domain")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sortOptions.forEach { sort ->
                    val selected = currentSort == sort
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectSort(sort) },
                        label = { Text(sort) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "Show",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            val filterOptions = listOf("All", "Web Links", "Media")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filterOptions.forEach { filter ->
                    val selected = currentFilter == filter
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFilter(filter) },
                        label = { Text(filter) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
