package com.amresalehin.emregallery.ui

import com.amresalehin.emregallery.face.FaceRecognitionSettings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.EngineComparisonResult
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

@Composable
fun IndexingStatusScreen(
    media: List<MediaItem>,
    ocrLanguages: Set<String>,
    ocrModel: String = "fast",
    ocrEngine: String = "tesseract",
    mlKitLanguages: Set<String> = setOf("latin"),
    onModelChange: ((String) -> Unit)? = null,
    onEngineChange: ((String) -> Unit)? = null,
    onToggleLanguage: ((String) -> Unit)? = null,
    onToggleMlKitLanguage: ((String) -> Unit)? = null,
    onOpenMap: (() -> Unit)? = null,
    onBack: () -> Unit,
    viewModel: IndexingStatusViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val faceStore = remember { FaceClusterStore(context.applicationContext) }

    var activeTargetEngineId by remember { mutableStateOf(BuffaloLFaceEngine.ENGINE_ID) }
    var showFaceScanOptions by remember { mutableStateOf(false) }
    var showModelRequiredDialog by remember { mutableStateOf(false) }
    var showComparisonDialog by remember { mutableStateOf(false) }
    var comparisonResult by remember { mutableStateOf<EngineComparisonResult?>(null) }

    val availableFolders = remember(media) {
        media.filterNot { it.isVideo }
            .groupBy { FaceRecognitionSettings.normalizeFolderPath(it.relativePath) }
            .map { (path, items) ->
                FaceFolderInfo(
                    path = path,
                    name = path.substringAfterLast('/').ifBlank { "Internal storage" },
                    photoCount = items.size,
                    coverUri = items.firstOrNull()?.uri
                )
            }
            .sortedByDescending { it.photoCount }
    }

    // Request notification permission on Android 13+ (POST_NOTIFICATIONS)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result is reflected by Android notification settings. */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(media, ocrLanguages, ocrModel, ocrEngine, mlKitLanguages) {
        viewModel.setMedia(media, ocrLanguages, ocrModel, ocrEngine, mlKitLanguages)
    }

    val lightAppearance = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val pageBackground = if (lightAppearance) Color(0xFFF7F7F7) else MaterialTheme.colorScheme.background
    val cardBackground = if (lightAppearance) Color.White else MaterialTheme.colorScheme.surfaceContainer
    val cardBorder = BorderStroke(
        1.dp,
        if (lightAppearance) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    )
    val numberFormat = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        // Main scrollable content with two simple cards
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.statusBarsPadding())
            Spacer(Modifier.height(56.dp))

            // 1. OCR Indexing & Configuration Card
            OcrIndexingCard(
                totalPhotos = uiState.totalPhotos,
                ocrState = uiState.ocr,
                ocrLanguages = ocrLanguages,
                countsReady = uiState.countsReady,
                cardBackground = cardBackground,
                cardBorder = cardBorder,
                numberFormat = numberFormat,
                onToggleOcr = viewModel::toggleOcr,
                onStopOcr = viewModel::stopOcr,
                onReindexOcr = viewModel::clearAndReindexOcr,
                onEngineChange = { newEngine ->
                    viewModel.setOcrEngine(newEngine)
                    onEngineChange?.invoke(newEngine)
                },
                onModelChange = { newModel ->
                    viewModel.setOcrModel(newModel)
                    onModelChange?.invoke(newModel)
                },
                onToggleLanguage = { code ->
                    onToggleLanguage?.invoke(code)
                },
                onToggleMlKitLanguage = { code ->
                    onToggleMlKitLanguage?.invoke(code)
                },
                onClearCache = {
                    scope.launch(Dispatchers.IO) {
                        TesseractOcr.clearCachedResults(context)
                        withContext(Dispatchers.Main) {
                            viewModel.refreshCounts()
                        }
                    }
                }
            )

            // 2. Face Recognition (Finding & Clustering) Card
            FaceRecognitionCard(
                totalPhotos = uiState.totalPhotos,
                faceFindingState = uiState.faceFinding,
                engineState = uiState.engine,
                modelDownloadState = uiState.modelDownload,
                clusteringState = uiState.clustering,
                enteFaceFindingState = uiState.enteFaceFinding,
                enteModelDownloadState = uiState.enteModelDownload,
                enteClusteringState = uiState.enteClustering,
                countsReady = uiState.countsReady,
                cardBackground = cardBackground,
                cardBorder = cardBorder,
                numberFormat = numberFormat,
                onConfidenceChange = viewModel::updateDetectionConfidence,
                onMinGroupFacesChange = viewModel::updateMinGroupFaces,
                onClusterDistanceChange = viewModel::updateClusterDistance,
                onToggleFaceFinding = { eng ->
                    activeTargetEngineId = eng
                    viewModel.toggleFaceFinding(
                        onNeedsModelDownload = { showModelRequiredDialog = true },
                        engineId = eng
                    )
                },
                onStopFaceFinding = { eng -> viewModel.stopFaceFinding(eng) },
                onRescanRequested = { eng ->
                    activeTargetEngineId = eng
                    showFaceScanOptions = true
                },
                onDownloadModel = { force, eng ->
                    activeTargetEngineId = eng
                    viewModel.startModelDownload(force, eng)
                },
                onCancelDownload = { eng -> viewModel.cancelModelDownload(eng) },
                onClusterFaces = { threshold, eng -> viewModel.runFaceClustering(threshold, eng) },
                onResetClusters = { eng -> viewModel.resetFaceClusters(eng) },
                onRetryFailedFaces = { eng -> viewModel.retryFailedFaces(eng) },
                availableFolders = availableFolders,
                onToggleFolder = { folderPath ->
                    val isAll = uiState.faceFinding.isAllFolders
                    val currentSelected = uiState.faceFinding.selectedFolders
                    if (isAll) {
                        viewModel.setFaceIndexingFolders(allFolders = false, folders = setOf(folderPath))
                    } else {
                        val newSelected = if (folderPath in currentSelected) {
                            currentSelected - folderPath
                        } else {
                            currentSelected + folderPath
                        }
                        if (newSelected.isEmpty() || newSelected.size >= availableFolders.size) {
                            viewModel.setFaceIndexingFolders(allFolders = true, folders = emptySet())
                        } else {
                            viewModel.setFaceIndexingFolders(allFolders = false, folders = newSelected)
                        }
                    }
                },
                onSelectAllFolders = {
                    viewModel.setFaceIndexingFolders(allFolders = true, folders = emptySet())
                },
                onComparePipelines = {
                    scope.launch(Dispatchers.IO) {
                        val commonUri = faceStore.findCommonIndexedMediaUri(BuffaloLFaceEngine.ENGINE_ID, com.amresalehin.emregallery.face.EnteFaceConstants.ENGINE_ID)
                            ?: media.firstOrNull { !it.isVideo }?.uri
                        if (commonUri != null) {
                            val result = faceStore.compareEnginesOnMedia(mediaUri = commonUri, context = context)
                            withContext(Dispatchers.Main) {
                                comparisonResult = result
                                showComparisonDialog = true
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(16.dp))
        }

        // Floating Top Bar: Back & Refresh Buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Surface(
                onClick = viewModel::refreshCounts,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh status",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    if (showFaceScanOptions) {
        FaceScanOptionsDialog(
            onDismiss = { showFaceScanOptions = false },
            onRescanAll = {
                showFaceScanOptions = false
                viewModel.clearAndRescanFaces(
                    onNeedsModelDownload = { showModelRequiredDialog = true },
                    engineId = activeTargetEngineId
                )
            },
            onRemainingOnly = {
                showFaceScanOptions = false
                viewModel.toggleFaceFinding(
                    onNeedsModelDownload = { showModelRequiredDialog = true },
                    engineId = activeTargetEngineId
                )
            }
        )
    }

    if (showModelRequiredDialog) {
        ModelDownloadRequiredDialog(
            onDismiss = { showModelRequiredDialog = false },
            onDownloadModel = {
                showModelRequiredDialog = false
                viewModel.startModelDownload(engineId = activeTargetEngineId)
            }
        )
    }

    val currentComparisonResult = comparisonResult
    if (showComparisonDialog && currentComparisonResult != null) {
        val comp = currentComparisonResult
        AlertDialog(
            onDismissRequest = { showComparisonDialog = false },
            title = {
                Text(
                    "Pipeline Comparison",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Sample: ${comp.mediaUri.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Buffalo_L (InsightFace SCRFD-10GF)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Detected faces: ${comp.facesA.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Ente (YOLO5Face-small)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Detected faces: ${comp.facesB.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "Spatial Overlap (IoU >= ${FaceRecognitionSettings.COMPARISON_IOU_THRESHOLD}): ${comp.matchingFaceCount} face(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showComparisonDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}
