package com.amresalehin.emregallery.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.amresalehin.emregallery.data.MediaAssetRepository
import com.amresalehin.emregallery.data.MediaAssetRole
import com.amresalehin.emregallery.data.RemotePlaybackHttpClient
import com.amresalehin.emregallery.data.RemotePlaybackMediaItemFactory
import com.amresalehin.emregallery.data.ResolvedMediaAssetPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shows only currently resolver-approved remote assets for an already-saved bookmark.
 * Discovery and playback reads are isolated from bookmark persistence and local copies.
 */
@Composable
fun RemotePlaybackPanel(
    bookmarkId: String,
    originalSourceUrl: String,
    onOpenBrowser: (String) -> Unit,
    isDark: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context.applicationContext) {
        MediaAssetRepository.getInstance(context.applicationContext)
    }
    var options by remember(bookmarkId) {
        mutableStateOf<List<ResolvedMediaAssetPlayback>>(emptyList())
    }
    var isLoading by remember(bookmarkId) { mutableStateOf(true) }
    var selectedPlayback by remember(bookmarkId) {
        mutableStateOf<ResolvedMediaAssetPlayback?>(null)
    }

    LaunchedEffect(bookmarkId, repository) {
        isLoading = true
        options = emptyList()
        try {
            options = repository.playbackOptionsForBookmark(bookmarkId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A playback read failure must not affect the saved bookmark or its detail page.
            options = emptyList()
        } finally {
            isLoading = false
        }
    }

    if (isLoading || options.isNotEmpty()) {
        val textColor = PocketThemeColors.primaryText(isDark)
        val secondaryText = PocketThemeColors.secondaryText(isDark)
        val barSurface = PocketThemeColors.barSurface(isDark)
        val dividerColor = PocketThemeColors.divider(isDark)

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = barSurface,
            border = BorderStroke(1.dp, dividerColor.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pocket_direct_playback_section")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = textColor
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Direct playback",
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                        Text(
                            text = "Only verified direct media is listed",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                options.forEachIndexed { index, option ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = PocketThemeColors.stackFront(isDark),
                        border = BorderStroke(1.dp, dividerColor.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    val refreshed = try {
                                        repository.playbackOptionsForBookmark(bookmarkId)
                                            .firstOrNull { it.asset.id == option.asset.id }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        null
                                    }

                                    if (refreshed != null) {
                                        selectedPlayback = refreshed
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Direct playback is no longer available. Opening the saved page.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        if (originalSourceUrl.isNotBlank()) {
                                            onOpenBrowser(originalSourceUrl)
                                        }
                                    }
                                }
                            }
                            .testTag("pocket_direct_playback_option_" + index)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = textColor
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (option.asset.role == MediaAssetRole.CONTENT_AUDIO) {
                                        "Audio stream"
                                    } else {
                                        "Video stream"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor
                                )
                                Text(
                                    text = option.resolution.mimeType,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryText
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = secondaryText
                            )
                        }
                    }
                }
            }
        }
    }

    selectedPlayback?.let { playback ->
        RemotePlaybackDialog(
            playback = playback,
            onDismiss = { selectedPlayback = null },
            onOpenSourcePage = if (originalSourceUrl.isNotBlank()) {
                {
                    selectedPlayback = null
                    onOpenBrowser(originalSourceUrl)
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun RemotePlaybackDialog(
    playback: ResolvedMediaAssetPlayback,
    onDismiss: () -> Unit,
    onOpenSourcePage: (() -> Unit)?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember(playback.asset.id, playback.resolution) {
        var createdPlayer: ExoPlayer? = null
        try {
            val mediaSourceFactory = DefaultMediaSourceFactory(context)
                .setDataSourceFactory(RemotePlaybackHttpClient.dataSourceFactory())
            val created = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            createdPlayer = created
            created.setMediaItem(RemotePlaybackMediaItemFactory.create(playback))
            created.repeatMode = Player.REPEAT_MODE_OFF
            created.prepare()
            created.playWhenReady = true
            created
        } catch (_: Exception) {
            createdPlayer?.release()
            null
        }
    }
    var playbackFailed by remember(playback.asset.id, player) {
        mutableStateOf(player == null)
    }

    DisposableEffect(player, lifecycleOwner) {
        val activePlayer = player
        if (activePlayer == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                    activePlayer.pause()
                }
            }
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    activePlayer.pause()
                }
            }
            activePlayer.addListener(listener)
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
                activePlayer.removeListener(listener)
                activePlayer.release()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 600.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remote playback",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = playback.resolution.mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close player"
                        )
                    }
                }

                if (player != null) {
                    val activePlayer = player
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                this.player = activePlayer
                                useController = true
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        update = { view ->
                            if (view.player !== activePlayer) view.player = activePlayer
                            view.useController = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .testTag("remote_playback_player_surface")
                    )
                }

                if (playbackFailed) {
                    Text(
                        text = "This direct resource could not be played. The bookmark and discovered media were left unchanged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (onOpenSourcePage != null) {
                        Button(onClick = onOpenSourcePage) {
                            Text("Open original page")
                        }
                    }
                } else {
                    Text(
                        text = "Remote playback uses a separate connection without browser cookies or Referer headers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
