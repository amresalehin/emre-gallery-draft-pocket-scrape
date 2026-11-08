package com.amresalehin.emregallery.ui

import android.net.Uri
import com.amresalehin.emregallery.GalleryApplication
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.BrowserSettingsRepository
import com.amresalehin.emregallery.data.BrowserStartupBehavior
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.MediaCategory
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.MediaInboxItem
import com.amresalehin.emregallery.data.MediaInboxRepository
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.MediaNormalizer
import com.amresalehin.emregallery.data.OkHttpDownloader
import com.amresalehin.emregallery.data.ScrapedMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.sqrt

enum class DownloaderViewMode {
    SEARCH_PAGE,
    DOWNLOADER_PAGE,
    SAVED_LIBRARY,
    ACTIVITY_PAGE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInboxScreen(
    onBack: () -> Unit,
    initialViewMode: DownloaderViewMode = DownloaderViewMode.SEARCH_PAGE,
    initialStackForDetail: MediaInboxStack? = null,
    initialOpenBrowser: Boolean = false,
    onOpenMediaItem: ((String) -> Unit)? = null,
    onOpenMediaWithList: ((String, List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val inboxRepo = remember { MediaInboxRepository.getInstance(context) }
    val stacks by inboxRepo.stacks.collectAsState()
    val workInfos by remember(context) {
        DownloadQueueManager.getActiveDownloadsFlow(context)
    }.collectAsState(initial = emptyList())
    val isDownloading = remember(workInfos) {
        workInfos.any {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var selectedStackForDetail by remember(initialStackForDetail) { mutableStateOf(initialStackForDetail) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showFullScreenBrowser by remember(initialOpenBrowser) { mutableStateOf(initialOpenBrowser) }
    var browserInitialUrl by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf("Newest") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selectedStackIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedStackIds.isNotEmpty()
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }

    var currentViewMode by remember(initialViewMode) {
        mutableStateOf(if (initialViewMode == DownloaderViewMode.DOWNLOADER_PAGE) DownloaderViewMode.SEARCH_PAGE else initialViewMode)
    }

    LaunchedEffect(Unit) {
        GalleryApplication.warmupChromium(context)
    }

    BackHandler(enabled = !showFullScreenBrowser) {
        if (isSelectionMode) {
            selectedStackIds = emptySet()
        } else if (isSearchFocused || searchQuery.isNotEmpty()) {
            searchQuery = ""
            isSearchFocused = false
            focusManager.clearFocus()
            keyboardController?.hide()
        } else if (selectedStackForDetail != null) {
            selectedStackForDetail = null
        } else if (currentViewMode == DownloaderViewMode.DOWNLOADER_PAGE) {
            currentViewMode = if (initialViewMode == DownloaderViewMode.SAVED_LIBRARY) DownloaderViewMode.SAVED_LIBRARY else DownloaderViewMode.SEARCH_PAGE
        } else if (currentViewMode == DownloaderViewMode.ACTIVITY_PAGE) {
            if (initialViewMode == DownloaderViewMode.ACTIVITY_PAGE) {
                onBack()
            } else {
                currentViewMode = if (initialViewMode == DownloaderViewMode.SAVED_LIBRARY) DownloaderViewMode.SAVED_LIBRARY else DownloaderViewMode.SEARCH_PAGE
            }
        } else if (showFilterSheet) {
            showFilterSheet = false
        } else if (currentViewMode == DownloaderViewMode.SAVED_LIBRARY) {
            if (initialViewMode == DownloaderViewMode.SAVED_LIBRARY) {
                onBack()
            } else {
                currentViewMode = DownloaderViewMode.SEARCH_PAGE
            }
        } else {
            onBack()
        }
    }

    // Filter stacks based on search query
    val filteredStacks = remember(stacks, searchQuery, sortOrder) {
        val base = if (searchQuery.isBlank()) {
            stacks
        } else {
            stacks.filter { stack ->
                stack.title.contains(searchQuery, ignoreCase = true) ||
                        stack.sourceDomain.contains(searchQuery, ignoreCase = true) ||
                        stack.items.any { it.title.contains(searchQuery, ignoreCase = true) }
            }
        }
        if (sortOrder == "Newest") {
            base.sortedByDescending { it.timestampMillis }
        } else {
            base.sortedBy { it.timestampMillis }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("media_inbox_screen")
    ) {
        Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    if (currentViewMode == DownloaderViewMode.SAVED_LIBRARY) {
                        Surface(
                            color = MaterialTheme.colorScheme.background,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                if (isSelectionMode) {
                                    // Selection mode toolbar
                                    Surface(
                                        shape = RoundedCornerShape(26.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        tonalElevation = 6.dp,
                                        shadowElevation = 8.dp,
                                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("inbox_selection_toolbar")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = { selectedStackIds = emptySet() },
                                                modifier = Modifier.size(38.dp).testTag("selection_close_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Exit selection",
                                                    tint = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            Spacer(Modifier.width(4.dp))

                                            Text(
                                                text = "${selectedStackIds.size} selected",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f).testTag("selection_count_text")
                                            )

                                            val allSelected = filteredStacks.isNotEmpty() && filteredStacks.all { it.id in selectedStackIds }
                                            IconButton(
                                                onClick = {
                                                    selectedStackIds = if (allSelected) {
                                                        emptySet()
                                                    } else {
                                                        filteredStacks.map { it.id }.toSet()
                                                    }
                                                },
                                                modifier = Modifier.size(38.dp).testTag("selection_select_all_button")
                                            ) {
                                                Icon(
                                                    imageVector = if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                                    contentDescription = if (allSelected) "Deselect all" else "Select all",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            IconButton(
                                                onClick = { showDeleteConfirmationDialog = true },
                                                modifier = Modifier.size(38.dp).testTag("selection_delete_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete selected stacks",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    // One Unified Search Bar
                                    Surface(
                                        shape = RoundedCornerShape(26.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp)
                                            .testTag("inbox_search_pill")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Back Button inside search bar
                                            IconButton(
                                                onClick = {
                                                    if (isSearchFocused || searchQuery.isNotEmpty()) {
                                                        searchQuery = ""
                                                        isSearchFocused = false
                                                        focusManager.clearFocus()
                                                        keyboardController?.hide()
                                                    } else {
                                                        if (initialViewMode == DownloaderViewMode.SAVED_LIBRARY) {
                                                            onBack()
                                                        } else {
                                                            currentViewMode = DownloaderViewMode.SEARCH_PAGE
                                                        }
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

                                            // Switcher Pill: download icon only and folder icon only (Hidden when search field receives focus)
                                            if (!isSearchFocused) {
                                                Spacer(Modifier.width(2.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                                    modifier = Modifier.height(34.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // Fetch icon only
                                                        Surface(
                                                            onClick = { currentViewMode = DownloaderViewMode.SEARCH_PAGE },
                                                            shape = RoundedCornerShape(14.dp),
                                                            color = if (currentViewMode == DownloaderViewMode.SEARCH_PAGE) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                                            modifier = Modifier
                                                                .size(width = 34.dp, height = 30.dp)
                                                                .testTag("tab_search_fetch")
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Icon(
                                                                    imageVector = Icons.Default.ArrowDownward,
                                                                    contentDescription = "Fetch",
                                                                    tint = if (currentViewMode == DownloaderViewMode.SEARCH_PAGE) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }

                                                        // Saved icon only
                                                        Surface(
                                                            onClick = { currentViewMode = DownloaderViewMode.SAVED_LIBRARY },
                                                            shape = RoundedCornerShape(14.dp),
                                                            color = if (currentViewMode == DownloaderViewMode.SAVED_LIBRARY) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                                            modifier = Modifier
                                                                .size(width = 34.dp, height = 30.dp)
                                                                .testTag("tab_saved_library")
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Folder,
                                                                    contentDescription = "Saved",
                                                                    tint = if (currentViewMode == DownloaderViewMode.SAVED_LIBRARY) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                Spacer(Modifier.width(8.dp))

                                                // Divider between switcher and search text
                                                Box(
                                                    modifier = Modifier
                                                        .width(1.dp)
                                                        .height(18.dp)
                                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                )

                                                Spacer(Modifier.width(8.dp))
                                            }

                                            // Search text field (No search icon, expands to fill available space)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(horizontal = 4.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                if (searchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Find in Saved Library...",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                BasicTextField(
                                                    value = searchQuery,
                                                    onValueChange = { searchQuery = it },
                                                    singleLine = true,
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    ),
                                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                                    keyboardActions = KeyboardActions(onSearch = {
                                                        keyboardController?.hide()
                                                    }),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .onFocusChanged { focusState ->
                                                            isSearchFocused = focusState.isFocused
                                                        }
                                                        .testTag("find_in_inbox_input")
                                                )
                                            }

                                            // Clear (×) button on right side of search field
                                            if (isSearchFocused || searchQuery.isNotEmpty()) {
                                                IconButton(
                                                    onClick = {
                                                        searchQuery = ""
                                                        // Only clears query without exiting search mode or dismissing keyboard
                                                    },
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .testTag("inbox_search_clear_btn")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Clear search",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }

                                            // Filter / settings button (Tune) - hidden when isSearchFocused
                                            if (!isSearchFocused && searchQuery.isEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(1.dp)
                                                        .height(18.dp)
                                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                )

                                                IconButton(
                                                    onClick = { showFilterSheet = true },
                                                    modifier = Modifier
                                                        .size(38.dp)
                                                        .testTag("inbox_tune_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Tune,
                                                        contentDescription = "Sort and filter inbox",
                                                        tint = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (currentViewMode == DownloaderViewMode.SAVED_LIBRARY && !isSelectionMode) {
                        FloatingActionButton(
                            onClick = { currentViewMode = DownloaderViewMode.SEARCH_PAGE },
                            shape = RoundedCornerShape(20.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp),
                            modifier = Modifier
                                .padding(bottom = 16.dp, end = 8.dp)
                                .size(56.dp)
                                .testTag("media_inbox_fab_add")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Media Download",
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (currentViewMode == DownloaderViewMode.SEARCH_PAGE) {
                        DownloaderSearchMainPage(
                            onFetchUrl = { targetUrl ->
                                browserInitialUrl = targetUrl
                                showFullScreenBrowser = true
                            },
                            onOpenBrowser = { targetUrl ->
                                browserInitialUrl = targetUrl
                                showFullScreenBrowser = true
                            },
                            onViewSavedLibrary = {
                                currentViewMode = DownloaderViewMode.SAVED_LIBRARY
                            },
                            onOpenActivity = {
                                currentViewMode = DownloaderViewMode.ACTIVITY_PAGE
                            },
                            onBack = onBack,
                            savedStacks = stacks,
                            onSelectStack = { stack ->
                                selectedStackForDetail = stack
                            },
                            activeDownloadsCount = workInfos.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED },
                            savedStacksCount = stacks.size,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (currentViewMode == DownloaderViewMode.ACTIVITY_PAGE) {
                        DownloaderActivityPage(
                            onBack = {
                                if (initialViewMode == DownloaderViewMode.ACTIVITY_PAGE) {
                                    onBack()
                                } else {
                                    currentViewMode = DownloaderViewMode.SEARCH_PAGE
                                }
                            },
                            onAddNewDownload = { currentViewMode = DownloaderViewMode.SEARCH_PAGE },
                            onOpenMedia = { item ->
                                item.localUri?.let { uriStr ->
                                    onOpenMediaItem?.invoke(uriStr)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // SAVED LIBRARY VIEW
                        if (filteredStacks.isNotEmpty() || isDownloading) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 12.dp, end = 20.dp)
                                    .testTag("inbox_media_grid"),
                                contentPadding = PaddingValues(top = 10.dp, bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (isDownloading) {
                                    item(key = "downloading_skeleton_card_item") {
                                        DownloadingSkeletonCard()
                                    }
                                }
                                itemsIndexed(filteredStacks, key = { _, stack -> stack.id }) { index, stack ->
                                    val isSelected = stack.id in selectedStackIds
                                    ImageStackCard(
                                        stack = stack,
                                        accentIndex = index,
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
                                                selectedStackForDetail = stack
                                            }
                                        },
                                        onLongClick = {
                                            if (!isSelectionMode) {
                                                selectedStackIds = setOf(stack.id)
                                            } else {
                                                selectedStackIds = if (isSelected) {
                                                    selectedStackIds - stack.id
                                                } else {
                                                    selectedStackIds + stack.id
                                                }
                                            }
                                        },
                                        onDelete = {
                                            inboxRepo.deleteStack(stack.id)
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Stack removed from Inbox")
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            // In empty state keep completely blank as requested
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("inbox_empty_state")
                            )
                        }
                    }
                }
            }
        }

    // Delete Confirmation Dialog for selected stacks
    if (showDeleteConfirmationDialog) {
        val count = selectedStackIds.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirmationDialog = false },
            title = {
                Text(
                    text = if (count == 1) "Delete media stack?" else "Delete $count media stacks?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove ${if (count == 1) "this stack" else "these $count stacks"} from your Inbox?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val idsToDelete = selectedStackIds.toList()
                        showDeleteConfirmationDialog = false
                        selectedStackIds = emptySet()
                        idsToDelete.forEach { id ->
                            inboxRepo.deleteStack(id)
                        }
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (idsToDelete.size == 1) "1 stack deleted" else "${idsToDelete.size} stacks deleted"
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirmationDialog = false },
                    modifier = Modifier.testTag("cancel_delete_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Modal Sheet for Sorting / Filtering Inbox
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Inbox Options",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text("Sort Order", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Newest", "Oldest").forEach { order ->
                        FilterChip(
                            selected = sortOrder == order,
                            onClick = { sortOrder = order },
                            label = { Text(order) }
                        )
                    }
                }

                HorizontalDivider()

                if (stacks.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            inboxRepo.clearAll()
                            showFilterSheet = false
                            scope.launch { snackbarHostState.showSnackbar("All inbox stacks cleared") }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Clear All Inbox Stacks")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showFilterSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Done")
                }
            }
        }
    }

    // Stack Detail Tab with stacked card carousel and bottom drawer (replacing modal)
    AnimatedVisibility(
        visible = selectedStackForDetail != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize().zIndex(20f)
    ) {
        selectedStackForDetail?.let { stack ->
            MediaStackDetailTab(
                stack = stack,
                onClose = { selectedStackForDetail = null },
                onOpenMedia = { uri ->
                    val allUris = stack.items.map { it.localUri.ifBlank { it.sourceUrl } }
                    if (onOpenMediaWithList != null) {
                        onOpenMediaWithList(uri, allUris)
                    } else {
                        onOpenMediaItem?.invoke(uri)
                    }
                },
                onOpenBrowser = { url ->
                    browserInitialUrl = url
                    showFullScreenBrowser = true
                },
                onDeleteStack = {
                    inboxRepo.deleteStack(stack.id)
                    selectedStackForDetail = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Stack removed from Inbox")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }


    // Full Screen In-App Browser (opens when clicking browser button above plus FAB)
    AnimatedVisibility(
        visible = showFullScreenBrowser,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize().zIndex(25f)
    ) {
        FullScreenBrowserView(
            initialUrl = browserInitialUrl,
            onClose = {
                showFullScreenBrowser = false
                browserInitialUrl = ""
            },
            onDownloadComplete = { count ->
                scope.launch {
                    snackbarHostState.showSnackbar("Downloaded $count media items to Inbox!")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * True Mosaic Thumbnail component for media stacks.
 * Supports:
 * - 0 items: Empty placeholder
 * - 1 item: Full image
 * - 2 items: 2 equal vertical columns (50% / 50%) side-by-side
 * - 3 items: 1 tall on left (58%), 2 stacked on right (42%)
 * - 4+ items: 2x2 quadrant grid with +N counter on the 4th quadrant
 */
@Composable
fun TrueMediaMosaicThumbnail(
    items: List<MediaInboxItem>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        when {
            items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            items.size == 1 -> {
                AsyncImage(
                    model = items[0].localUri,
                    contentDescription = items[0].title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            items.size == 2 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    AsyncImage(
                        model = items[0].localUri,
                        contentDescription = items[0].title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                    AsyncImage(
                        model = items[1].localUri,
                        contentDescription = items[1].title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            items.size == 3 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    AsyncImage(
                        model = items[0].localUri,
                        contentDescription = items[0].title,
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        AsyncImage(
                            model = items[1].localUri,
                            contentDescription = items[1].title,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentScale = ContentScale.Crop
                        )
                        AsyncImage(
                            model = items[2].localUri,
                            contentDescription = items[2].title,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        AsyncImage(
                            model = items[0].localUri,
                            contentDescription = items[0].title,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentScale = ContentScale.Crop
                        )
                        AsyncImage(
                            model = items[1].localUri,
                            contentDescription = items[1].title,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        AsyncImage(
                            model = items[2].localUri,
                            contentDescription = items[2].title,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            AsyncImage(
                                model = items[3].localUri,
                                contentDescription = items[3].title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (items.size > 4) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.58f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "+${items.size - 3}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stack layer color palettes (matching image_9a85efa6.png):
 * Each collection gets a distinct soft accent color (purple, warm amber, mint/green, rose/pink, cyan, etc.)
 */
private val StackLayerPalettesLight = listOf(
    // 1. Purple / Lavender (as in Taj Mahal card)
    listOf(Color(0xFFEDE9FE), Color(0xFFDDD6FE)),
    // 2. Warm Amber / Peach (as in Ancient India card)
    listOf(Color(0xFFFEF3C7), Color(0xFFFFD1D8)),
    // 3. Mint / Emerald (as in Mughal Art card)
    listOf(Color(0xFFD1FAE5), Color(0xFFA7F3D0)),
    // 4. Rose / Pink (as in Indian Architecture card)
    listOf(Color(0xFFFCE7F3), Color(0xFFFBCFE8)),
    // 5. Cyan / Sky Blue
    listOf(Color(0xFFCFFAFE), Color(0xFFA5F3FC)),
    // 6. Soft Indigo / Periwinkle
    listOf(Color(0xFFE0E7FF), Color(0xFFC7D2FE))
)

private val StackLayerPalettesDark = listOf(
    // 1. Purple / Lavender
    listOf(Color(0xFF312E81).copy(alpha = 0.65f), Color(0xFF3730A3).copy(alpha = 0.45f)),
    // 2. Warm Amber / Peach
    listOf(Color(0xFF78350F).copy(alpha = 0.65f), Color(0xFF92400E).copy(alpha = 0.45f)),
    // 3. Mint / Emerald
    listOf(Color(0xFF064E3B).copy(alpha = 0.65f), Color(0xFF065F46).copy(alpha = 0.5f)),
    // 4. Rose / Pink
    listOf(Color(0xFF831843).copy(alpha = 0.65f), Color(0xFF9D174D).copy(alpha = 0.45f)),
    // 5. Cyan / Sky Blue
    listOf(Color(0xFF164E63).copy(alpha = 0.65f), Color(0xFF155E75).copy(alpha = 0.45f)),
    // 6. Soft Indigo
    listOf(Color(0xFF1E1B4B).copy(alpha = 0.65f), Color(0xFF312E81).copy(alpha = 0.45f))
)

/**
 * Image Stack Card displaying real downloaded media matching image_9a85efa6.png:
 * 1. Single hero thumbnail: One large cover image instead of 2x2 collage
 * 2. Portrait-like card composition: Cover occupies upper section
 * 3. Stacked card effect: Two offset layers behind card to create physical media-stack appearance
 * 4. Colored stack layers: Distinct soft accent colors
 * 5. Source favicon: Website favicon displayed beside collection title
 * 6. Information section: Title and date/item count beneath cover
 * 7. Clean cover: No playback overlays, no +13 badge, no media-count badge
 * 8. Theme-aware card background and text
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageStackCard(
    stack: MediaInboxStack,
    accentIndex: Int = 0,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val layerColors = remember(accentIndex, isDark) {
        val list = if (isDark) StackLayerPalettesDark else StackLayerPalettesLight
        list[accentIndex % list.size]
    }

    val dateStr = remember(stack.timestampMillis) {
        SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(stack.timestampMillis))
    }

    // Real single hero cover image from stack items (prefer image, fallback to first item)
    val coverItem = remember(stack.items) {
        stack.items.firstOrNull { !it.isVideo && it.localUri.isNotBlank() }
            ?: stack.items.firstOrNull { it.localUri.isNotBlank() }
            ?: stack.items.firstOrNull()
    }
    val coverImageModel = remember(coverItem) {
        coverItem?.localUri?.takeIf { it.isNotBlank() }
            ?: coverItem?.sourceUrl?.takeIf { it.isNotBlank() }
    }

    val cardShape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 8.dp, bottom = 8.dp)
            .testTag("inbox_image_stack_${stack.id}")
    ) {
        // Layer 2 (Backmost stacked card - offset 8dp bottom-right)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 8.dp, y = 8.dp)
                .background(layerColors[1], shape = cardShape)
                .border(1.dp, layerColors[1].copy(alpha = 0.85f), shape = cardShape)
        )

        // Layer 1 (Middle stacked card - offset 4dp bottom-right)
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 4.dp)
                .background(layerColors[0], shape = cardShape)
                .border(1.dp, layerColors[0].copy(alpha = 0.9f), shape = cardShape)
        )

        // Front Main Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isSelected) {
                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, shape = cardShape)
                    } else Modifier
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = if (isDark) 1.dp else 3.dp,
            border = BorderStroke(
                1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.45f)
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. Single Hero Thumbnail (Portrait composition, clean with no badges or overlays)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(165.dp)
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    if (coverImageModel != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(coverImageModel)
                                .crossfade(true)
                                .build(),
                            contentDescription = stack.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    // Selection indicator checkmark when in selection mode
                    if (isSelectionMode) {
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
                            border = BorderStroke(
                                2.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else Color.White
                            ),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .size(26.dp)
                                .testTag("stack_selection_check_${stack.id}")
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

                // 2. Information Section beneath the cover image
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    // Title row with source favicon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Website favicon avatar
                        val cleanDomain = remember(stack.sourceDomain, stack.sourceUrl) {
                            val raw = stack.sourceDomain.ifBlank {
                                try {
                                    Uri.parse(stack.sourceUrl).host.orEmpty()
                                } catch (_: Exception) { "" }
                            }
                            raw.removePrefix("www.").lowercase().trim()
                        }
                        val faviconUrl = remember(cleanDomain) {
                            if (cleanDomain.isNotBlank()) "https://www.google.com/s2/favicons?domain=$cleanDomain&sz=64" else null
                        }

                        var faviconFailed by remember(faviconUrl) { mutableStateOf(false) }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                            modifier = Modifier.size(17.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (!faviconFailed && faviconUrl != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(faviconUrl)
                                            .crossfade(true)
                                            .listener(onError = { _, _ -> faviconFailed = true })
                                            .build(),
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    val initial = cleanDomain.firstOrNull()?.uppercaseChar()?.toString()
                                        ?: stack.title.firstOrNull()?.uppercaseChar()?.toString()
                                        ?: "W"
                                    Text(
                                        text = initial,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Collection Title
                        Text(
                            text = stack.title.ifBlank { stack.sourceDomain.ifBlank { "Saved Collection" } },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Formatted Date and item count
                    Text(
                        text = "$dateStr • ${stack.items.size} ${if (stack.items.size == 1) "item" else "items"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Detail Tab with stacked card carousel and lower drawer (matches screenshots)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaStackDetailTab(
    stack: MediaInboxStack,
    onClose: () -> Unit,
    onOpenMedia: (String) -> Unit,
    onOpenBrowser: (String) -> Unit,
    onDeleteStack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDrawerExpanded by remember { mutableStateOf(true) }

    val items = stack.items
    var currentIndex by remember(stack.id) { mutableIntStateOf(0) }
    val activeIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val activeItem = items.getOrNull(activeIndex) ?: items.firstOrNull()

    val dragOffsetX = remember { Animatable(0f) }
    val dragOffsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density

    val formattedDateTime = remember(stack.timestampMillis) {
        SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()).format(Date(stack.timestampMillis))
    }

    val totalBytes = remember(items) { items.sumOf { it.sizeBytes } }
    val formattedTotalSize = remember(totalBytes) {
        when {
            totalBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", totalBytes / (1024f * 1024f))
            totalBytes >= 1024 -> String.format(Locale.US, "%.1f KB", totalBytes / 1024f)
            totalBytes > 0 -> "$totalBytes B"
            else -> "Ready"
        }
    }

    val domain = remember(stack.sourceDomain, stack.sourceUrl) {
        if (stack.sourceDomain.isNotBlank()) {
            stack.sourceDomain
        } else {
            runCatching { URL(stack.sourceUrl).host }.getOrDefault("Web")
        }
    }

    val faviconUrl = remember(domain) {
        "https://www.google.com/s2/favicons?domain=$domain&sz=64"
    }

    val extractedTitle = remember(stack.title, activeItem) {
        if (activeItem != null && activeItem.title.isNotBlank()) {
            activeItem.title
        } else if (stack.title.isNotBlank() && stack.title != "Untitled Stack" && stack.title != "Discovered Media") {
            stack.title
        } else {
            domain
        }
    }

    val extractedDescription = remember(stack.description, activeItem, items, domain, formattedTotalSize) {
        if (activeItem != null) {
            val formatStr = if (activeItem.format.isNotBlank()) "${activeItem.format} format" else "media file"
            val typeStr = if (activeItem.isVideo) "Video" else if (activeItem.isStream) "Live Stream" else "Image"
            val itemSizeStr = if (activeItem.sizeBytes > 0) {
                when {
                    activeItem.sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", activeItem.sizeBytes / (1024f * 1024f))
                    activeItem.sizeBytes >= 1024 -> String.format(Locale.US, "%.1f KB", activeItem.sizeBytes / 1024f)
                    else -> "${activeItem.sizeBytes} B"
                }
            } else formattedTotalSize
            "File ${activeIndex + 1} of ${items.size}: $typeStr in $formatStr ($itemSizeStr) extracted from $domain."
        } else if (stack.description.isNotBlank()) {
            stack.description
        } else {
            "Contains ${items.size} media files extracted from $domain. Total size: $formattedTotalSize."
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("media_stack_detail_tab")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 1. Top Bar with Circular Back Button and Circular More Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Back Button
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 3.dp,
                    tonalElevation = 2.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.testTag("stack_detail_back_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Circular More Button
                Box {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 3.dp,
                        tonalElevation = 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.size(44.dp)
                    ) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.testTag("stack_detail_more_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open in Browser") },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, null) },
                            onClick = {
                                menuExpanded = false
                                onOpenBrowser(stack.sourceUrl.ifBlank { "https://$domain" })
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("View in Gallery") },
                            leadingIcon = { Icon(Icons.Default.Visibility, null) },
                            onClick = {
                                menuExpanded = false
                                activeItem?.let { onOpenMedia(it.localUri) }
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete Stack", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                showDeleteConfirmDialog = true
                            }
                        )
                    }
                }
            }

            // 2. Functional Stacked Photo Deck Area (Real images physically coming forward from below stack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("functional_stacked_photo_deck"),
                    contentAlignment = Alignment.Center
                ) {
                    if (items.isEmpty()) {
                        RealDeckMediaCard(
                            item = null,
                            elevation = 8.dp,
                            modifier = Modifier,
                            onClick = {}
                        )
                    } else {
                        val maxDepth = 3
                        val maxVisibleIndex = (currentIndex + maxDepth).coerceAtMost(items.size - 1)
                        val dragDistance = sqrt(dragOffsetX.value * dragOffsetX.value + dragOffsetY.value * dragOffsetY.value)
                        val swipeProgress = (dragDistance / 350f).coerceIn(0f, 1f)

                        // Render cards in reverse order so the deepest card is at the back, top card on top
                        for (i in maxVisibleIndex downTo currentIndex) {
                            val depth = i - currentIndex
                            val item = items[i]

                            // Dynamic interpolation: as top card is swiped away, card directly below comes forward!
                            val cardScale = when (depth) {
                                0 -> 1.0f
                                1 -> 0.93f + (1.0f - 0.93f) * swipeProgress
                                2 -> 0.86f + (0.93f - 0.86f) * swipeProgress
                                else -> 0.80f + (0.86f - 0.80f) * swipeProgress
                            }

                            val cardRotZ = when (depth) {
                                0 -> dragOffsetX.value * 0.04f
                                1 -> 3.5f * (1f - swipeProgress)
                                2 -> -4.5f + (3.5f - -4.5f) * swipeProgress
                                else -> 5.5f + (-4.5f - 5.5f) * swipeProgress
                            }

                            val cardTransX = when (depth) {
                                0 -> dragOffsetX.value
                                1 -> (14f * density) * (1f - swipeProgress)
                                2 -> (-16f * density) + ((14f * density) - (-16f * density)) * swipeProgress
                                else -> (18f * density) + ((-16f * density) - (18f * density)) * swipeProgress
                            }

                            val cardTransY = when (depth) {
                                0 -> dragOffsetY.value
                                1 -> (8f * density) * (1f - swipeProgress)
                                2 -> (16f * density) + ((8f * density) - (16f * density)) * swipeProgress
                                else -> (24f * density) + ((16f * density) - (24f * density)) * swipeProgress
                            }

                            val cardAlpha = when (depth) {
                                0 -> (1f - swipeProgress * 0.25f).coerceIn(0.75f, 1f)
                                else -> (0.88f + 0.12f * swipeProgress).coerceIn(0.88f, 1f)
                            }

                            val cardElevation = when (depth) {
                                0 -> 16.dp
                                1 -> 10.dp
                                2 -> 6.dp
                                else -> 3.dp
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = cardScale
                                        scaleY = cardScale
                                        rotationZ = cardRotZ
                                        translationX = cardTransX
                                        translationY = cardTransY
                                        alpha = cardAlpha
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                RealDeckMediaCard(
                                    item = item,
                                    elevation = cardElevation,
                                    modifier = if (depth == 0) {
                                        Modifier.pointerInput(currentIndex, items.size) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                var hasMoved = false
                                                var dragDeltaX = 0f
                                                var dragDeltaY = 0f
                                                val touchSlop = viewConfiguration.touchSlop

                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break

                                                    if (change.changedToUp()) {
                                                        change.consume()
                                                        break
                                                    }

                                                    val posDelta = change.positionChange()
                                                    if (posDelta != Offset.Zero) {
                                                        dragDeltaX += posDelta.x
                                                        dragDeltaY += posDelta.y
                                                        val dist = sqrt(dragDeltaX * dragDeltaX + dragDeltaY * dragDeltaY)
                                                        if (dist > touchSlop) {
                                                            hasMoved = true
                                                        }
                                                        if (hasMoved) {
                                                            change.consume()
                                                            scope.launch {
                                                                dragOffsetX.snapTo(dragOffsetX.value + posDelta.x)
                                                                dragOffsetY.snapTo(dragOffsetY.value + posDelta.y)
                                                            }
                                                        }
                                                    }
                                                }

                                                val offsetX = dragOffsetX.value
                                                val offsetY = dragOffsetY.value
                                                val totalDrag = kotlin.math.sqrt(offsetX * offsetX + offsetY * offsetY)

                                                if (!hasMoved || totalDrag < 45f) {
                                                    // Direct tap without swipe threshold: directly land in viewer!
                                                    val targetUri = item.localUri.ifBlank { item.sourceUrl }
                                                    onOpenMedia(targetUri)
                                                } else if ((offsetX < -140f || offsetY < -150f) && currentIndex < items.size - 1) {
                                                    // Swiped left or up: top card exits and card below comes forward to the top!
                                                    scope.launch {
                                                        val exitX = if (abs(offsetX) > 20f) (if (offsetX < 0) -1300f else 1300f) else 0f
                                                        val exitY = if (abs(offsetY) > 20f) (if (offsetY < 0) -1300f else 1300f) else 0f
                                                        launch { dragOffsetX.animateTo(exitX, tween(160)) }
                                                        launch { dragOffsetY.animateTo(exitY, tween(160)) }
                                                        delay(160)
                                                        currentIndex = (currentIndex + 1).coerceAtMost(items.size - 1)
                                                        dragOffsetX.snapTo(0f)
                                                        dragOffsetY.snapTo(0f)
                                                    }
                                                } else if (offsetX > 140f && currentIndex > 0) {
                                                    // Swiped right: bring previous card back
                                                    scope.launch {
                                                        launch { dragOffsetX.animateTo(1300f, tween(160)) }
                                                        delay(160)
                                                        currentIndex = (currentIndex - 1).coerceAtLeast(0)
                                                        dragOffsetX.snapTo(0f)
                                                        dragOffsetY.snapTo(0f)
                                                    }
                                                } else {
                                                    // Snap back to resting position with bouncy spring
                                                    scope.launch {
                                                        launch { dragOffsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
                                                        launch { dragOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Tapping the card peeking below immediately promotes it to the top!
                                        Modifier.clickable {
                                            scope.launch {
                                                dragOffsetX.snapTo(0f)
                                                dragOffsetY.snapTo(0f)
                                                currentIndex = i
                                            }
                                        }
                                    },
                                    onClick = {
                                        val targetUri = item.localUri.ifBlank { item.sourceUrl }
                                        onOpenMedia(targetUri)
                                    }
                                )
                            }
                        }
                    }
                }

                // Numeric Page Indicator (replaces overflowing dot pagination)
                if (items.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 10.dp)
                            .testTag("numeric_pagination_indicator")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${activeIndex + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = " / ${items.size}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // 3. Lower Drawer Sheet (occupying lower portion of the screen)
            Surface(
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 20.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stack_detail_lower_drawer")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                        .animateContentSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Drag handle pill at the top (tapping toggles drawer expand/collapse)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp)
                            .clickable { isDrawerExpanded = !isDrawerExpanded },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(44.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                    }

                    // Row 1: Date & Time
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = "Date and Time",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = formattedDateTime,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Row 2: URL with Favicon and Redirect Button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Favicon
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.size(28.dp)
                            ) {
                                AsyncImage(
                                    model = faviconUrl,
                                    contentDescription = "Favicon",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stack.sourceUrl.ifBlank { "https://$domain" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        onOpenBrowser(stack.sourceUrl.ifBlank { "https://$domain" })
                                    }
                            )
                            IconButton(
                                onClick = {
                                    onOpenBrowser(stack.sourceUrl.ifBlank { "https://$domain" })
                                },
                                modifier = Modifier.size(32.dp).testTag("stack_detail_redirect_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Open in browser",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Rows 3, 4, 5 (visible when expanded)
                    if (isDrawerExpanded) {
                        // Row 3: Extracted Title
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = "Title",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = extractedTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Row 4: Extracted Description
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notes,
                                    contentDescription = "Description",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = extractedDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        // Row 5: Tags and Format Chips with + action button matching image_4867641c.png
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocalOffer,
                                    contentDescription = "Tags",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    item {
                                        DetailTagChip(label = activeItem?.format?.ifBlank { "MEDIA" } ?: "MEDIA")
                                    }
                                    item {
                                        DetailTagChip(label = if (activeItem?.isVideo == true) "Video" else "Image")
                                    }
                                    item {
                                        DetailTagChip(label = formattedTotalSize)
                                    }
                                    item {
                                        DetailTagChip(label = domain)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                // Quick Action (+) button matching image_4867641c.png
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                    modifier = Modifier
                                        .size(30.dp)
                                        .clickable {
                                            activeItem?.let { onOpenMedia(it.localUri) }
                                        }
                                        .testTag("detail_tags_add_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Actions",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Stack?") },
            text = { Text("This will remove this stack and its downloaded files from your device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteStack()
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
}

/**
 * Real Media Card for the functional stacked photo deck (renders actual downloaded media items)
 */
@Composable
fun RealDeckMediaCard(
    item: MediaInboxItem?,
    elevation: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 8.dp,
        shadowElevation = elevation,
        border = BorderStroke(2.dp, Color.White.copy(alpha = 0.95f)),
        modifier = modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight(0.94f)
            .clip(RoundedCornerShape(32.dp))
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (item != null) {
                val context = LocalContext.current
                val localFile = remember(item.localUri) {
                    if (item.localUri.isNotBlank()) File(item.localUri.removePrefix("file://")) else null
                }
                val imageSource: Any = if (localFile != null && localFile.exists() && localFile.length() > 0L) {
                    localFile
                } else if (item.sourceUrl.isNotBlank()) {
                    item.sourceUrl
                } else {
                    item.localUri
                }

                val imageRequest = remember(imageSource) {
                    val rawStr = imageSource.toString()
                    val resolvedUrl = if (rawStr.startsWith("//")) "https:$rawStr" else rawStr
                    val refererHeader = item.sourceUrl.takeIf { it.isNotBlank() }
                        ?: runCatching {
                            val u = java.net.URI(resolvedUrl)
                            "${u.scheme ?: "https"}://${u.host}/"
                        }.getOrNull()
                    val builder = ImageRequest.Builder(context)
                        .data(if (imageSource is File) imageSource else resolvedUrl)
                        .crossfade(true)
                        .setHeader("User-Agent", OkHttpDownloader.DEFAULT_USER_AGENT)
                        .setHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .setHeader("Sec-Fetch-Dest", "image")
                        .setHeader("Sec-Fetch-Mode", "no-cors")

                    if (!refererHeader.isNullOrBlank()) {
                        builder.setHeader("Referer", refererHeader)
                    }

                    val cookies = OkHttpDownloader.sharedCookieJar.getCookieString(resolvedUrl)
                        .ifBlank { refererHeader?.let { OkHttpDownloader.sharedCookieJar.getCookieString(it) } }
                    if (!cookies.isNullOrBlank()) {
                        builder.setHeader("Cookie", cookies)
                    }

                    builder.build()
                }

                AsyncImage(
                    model = imageRequest,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    contentScale = ContentScale.Fit
                )

                if (item.isVideo || item.isStream) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.6f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                    ) {
                        Text(
                            text = if (item.isStream) "STREAM" else item.format.ifBlank { "VIDEO" },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Landscape,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(96.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DetailTagChip(label: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1
        )
    }
}

/**
 * Single skeleton card displayed when media is actively downloading.
 */
@Composable
fun DownloadingSkeletonCard(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "download_skeleton_shimmer")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "download_skeleton_alpha"
    )

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("downloading_skeleton_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Upper thumbnail skeleton block with animated shimmer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = alphaAnim)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Downloading media",
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = alphaAnim * 0.7f),
                    modifier = Modifier.size(32.dp)
                )
            }

            // Lower info skeleton bars
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top longer horizontal bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alphaAnim * 0.5f))
                )
                // Bottom shorter horizontal bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = alphaAnim * 0.35f))
                )
            }
        }
    }
}

/**
 * Full-screen in-app Chromium browser launched from the Media Downloader page.
 * Allows web browsing and auto-detects downloadable media on any visited site.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenBrowserView(
    initialUrl: String = "",
    onClose: () -> Unit,
    onDownloadComplete: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val inboxRepo = remember { MediaInboxRepository.getInstance(context) }
    val scope = rememberCoroutineScope()
    val browserSettingsRepository = remember(context) {
        BrowserSettingsRepository(context.applicationContext)
    }
    val restoredUrl = remember(browserSettingsRepository) {
        browserSettingsRepository.getLastVisitedUrl()
    }
    val startupUrl = remember(initialUrl, restoredUrl) {
        if (initialUrl.isNotBlank()) {
            initialUrl
        } else if (browserSettingsRepository.settings.value.startupBehavior == BrowserStartupBehavior.RESTORE_PREVIOUS_SESSION) {
            restoredUrl
        } else {
            ""
        }
    }

    var currentUrl by remember(initialUrl, startupUrl) { mutableStateOf(startupUrl) }
    var showBrowserSettings by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var discoveredItems by remember { mutableStateOf<List<ScrapedMediaItem>>(emptyList()) }
    var showDiscoveredMediaSheet by remember { mutableStateOf(false) }

    var selectedItemIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // Intercept back button when modal sheet is open or to close browser
    BackHandler {
        when {
            showBrowserSettings -> showBrowserSettings = false
            showDiscoveredMediaSheet -> showDiscoveredMediaSheet = false
            else -> onClose()
        }
    }

    // Auto-select newly discovered items if none explicitly selected
    LaunchedEffect(discoveredItems.size) {
        if (discoveredItems.isNotEmpty() && selectedItemIds.isEmpty()) {
            selectedItemIds = discoveredItems.map { it.id }.toSet()
        }
    }

    val filterOptions = listOf("Images", "Videos", "Audio", "Streams")
    val filteredItems = remember(discoveredItems, selectedFilter) {
        when (selectedFilter) {
            "Images" -> discoveredItems.filter { it.category == MediaCategory.IMAGES }
            "Videos" -> discoveredItems.filter { it.category == MediaCategory.VIDEOS }
            "Audio" -> discoveredItems.filter { it.category == MediaCategory.AUDIO }
            "Streams" -> discoveredItems.filter { it.category == MediaCategory.STREAMS }
            else -> discoveredItems
        }
    }

    Surface(
        modifier = modifier.testTag("full_screen_browser_view"),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // DirectBrowserView taking full screen with its unified, interactive toolbar
            DirectBrowserView(
                initialUrl = currentUrl,
                onClose = onClose,
                onUrlChange = {
                    currentUrl = it
                    browserSettingsRepository.setLastVisitedUrl(it)
                },
                settingsRepository = browserSettingsRepository,
                onOpenSettings = { showBrowserSettings = true },
                onPageTitleChanged = { pageTitle = it },
                onMediaExtracted = { item ->
                    discoveredItems = MediaNormalizer.deduplicateList(discoveredItems + item)
                },
                onBatchExtracted = { batch, title, domain ->
                    title?.let { if (it.isNotBlank()) pageTitle = it }
                    discoveredItems = MediaNormalizer.deduplicateList(discoveredItems + batch)
                },
                onSwitchToMediaTab = {
                    showDiscoveredMediaSheet = true
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showBrowserSettings) {
            BrowserSettingsScreen(
                repository = browserSettingsRepository,
                onBack = { showBrowserSettings = false },
                modifier = Modifier.fillMaxSize().zIndex(40f)
            )
        }

        // Modal Sheet for Discovered Media in Full Screen Browser
        if (showDiscoveredMediaSheet) {
            ModalBottomSheet(
                onDismissRequest = { showDiscoveredMediaSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    val isAllSelected = filteredItems.isNotEmpty() &&
                            selectedItemIds.containsAll(filteredItems.map { it.id }.toSet())

                    // Pinned Filter & Action Row: Category Chips with Icons + Pinned Select All
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .testTag("discovered_media_filters_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Category filter chips using icons instead of texts to save space
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            items(filterOptions) { filter ->
                                val isSelected = selectedFilter == filter
                                val count = when (filter) {
                                    "Images" -> discoveredItems.count { it.category == MediaCategory.IMAGES }
                                    "Videos" -> discoveredItems.count { it.category == MediaCategory.VIDEOS }
                                    "Audio" -> discoveredItems.count { it.category == MediaCategory.AUDIO }
                                    "Streams" -> discoveredItems.count { it.category == MediaCategory.STREAMS }
                                    else -> 0
                                }
                                val icon = when (filter) {
                                    "Images" -> Icons.Default.Image
                                    "Videos" -> Icons.Default.Videocam
                                    "Audio" -> Icons.Default.Audiotrack
                                    "Streams" -> Icons.Default.LiveTv
                                    else -> Icons.Default.PermMedia
                                }

                                Surface(
                                    onClick = {
                                        selectedFilter = if (selectedFilter == filter) null else filter
                                    },
                                    shape = CircleShape,
                                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    ),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("discovered_filter_chip_$filter")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = "$filter ($count)",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "$count",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // Pinned Select All / Deselect Pill matching scraper drawer
                        Surface(
                            onClick = {
                                val currentFilteredIds = filteredItems.map { it.id }.toSet()
                                selectedItemIds = if (isAllSelected) {
                                    selectedItemIds - currentFilteredIds
                                } else {
                                    selectedItemIds + currentFilteredIds
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(34.dp)
                                .testTag("discovered_select_all_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(3.dp),
                                    color = if (isAllSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    border = BorderStroke(
                                        1.2.dp,
                                        if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.size(14.dp)
                                ) {
                                    if (isAllSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isAllSelected) "Deselect" else "Select All",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    // Media items list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredItems) { item ->
                            val isSelected = item.id in selectedItemIds
                            Surface(
                                onClick = {
                                    selectedItemIds = if (isSelected) selectedItemIds - item.id else selectedItemIds + item.id
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Thumbnail / Icon preview
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        if (item.category == MediaCategory.IMAGES) {
                                            AsyncImage(
                                                model = item.thumbnailUrl ?: item.url,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Icon(
                                                    imageVector = when {
                                                        item.isStream -> Icons.Default.LiveTv
                                                        item.isVideo -> Icons.Default.Videocam
                                                        item.isAudio -> Icons.Default.Audiotrack
                                                        else -> Icons.Default.Image
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${item.category.label} • ${item.format}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedItemIds = if (checked) selectedItemIds + item.id else selectedItemIds - item.id
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Download Button
                    Button(
                        onClick = {
                            if (selectedItemIds.isEmpty() || isDownloading) return@Button
                            isDownloading = true
                            downloadProgress = 0.2f
                            scope.launch {
                                val toDownload = filteredItems.filter { it.id in selectedItemIds }
                                val domain = runCatching { URL(currentUrl).host }.getOrDefault("Web")
                                val stackId = "stack_${System.currentTimeMillis()}"
                                val stackTitle = pageTitle.ifBlank { domain.ifBlank { "Browser Download" } }

                                // Persistently download using WorkManager
                                com.amresalehin.emregallery.data.DownloadQueueManager.enqueueBatch(
                                    context = context,
                                    items = toDownload,
                                    referer = currentUrl,
                                    stackId = stackId,
                                    stackTitle = stackTitle,
                                    sourceDomain = domain,
                                    sourceUrl = currentUrl
                                )

                                downloadProgress = 1.0f
                                kotlinx.coroutines.delay(300)
                                isDownloading = false
                                showDiscoveredMediaSheet = false
                                onDownloadComplete(toDownload.size)
                            }
                        },
                        enabled = selectedItemIds.isNotEmpty() && !isDownloading,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                progress = { downloadProgress },
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Downloading (${(downloadProgress * 100).toInt()}%)...")
                        } else {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download (${selectedItemIds.size}) to Inbox")
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
