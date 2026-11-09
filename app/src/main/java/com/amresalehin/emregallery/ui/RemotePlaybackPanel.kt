package com.amresalehin.emregallery.ui

import android.content.Context
import android.view.View
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
import androidx.compose.runtime.MutableState
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
                    openOriginalSourcePage(
                        originalSourceUrl = originalSourceUrl,
                        dismissPlayback = { selectedPlayback = null },
                        onOpenBrowser = onOpenBrowser
                    )
                }
            } else {
                null
            }
        )
    }
}

/**
 * Owns one remote player for the lifetime of its dialog.
 *
 * The injectable factories keep Compose lifecycle behavior testable without starting network
 * playback; production defaults retain the dedicated remote transport and native Media3 views.
 */
@Composable
internal fun RemotePlaybackDialog(
    playback: ResolvedMediaAssetPlayback,
    onDismiss: () -> Unit,
    onOpenSourcePage: (() -> Unit)?,
    playerFactory: (Context) -> ExoPlayer = { appContext ->
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(RemotePlaybackHttpClient.dataSourceFactory())
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    },
    preparePlayer: (ExoPlayer) -> Unit = { it.prepare() },
    releasePlayer: (ExoPlayer) -> Unit = { it.release() },
    playerViewFactory: (Context) -> PlayerView = { PlayerView(it) }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val playerState = remember(playback.asset.id, playback.resolution) {
        mutableStateOf<ExoPlayer?>(null)
    }
    val playerViewState = remember(playback.asset.id, playback.resolution) {
        mutableStateOf<PlayerView?>(null)
    }
    var playbackFailed by remember(playback.asset.id, playback.resolution) {
        mutableStateOf(false)
    }
    val player = playerState.value

    DisposableEffect(playback.asset.id, playback.resolution, lifecycleOwner) {
        var activePlayer: ExoPlayer? = null
        var playerListener: Player.Listener? = null
        var lifecycleObserver: LifecycleEventObserver? = null
        playbackFailed = false

        try {
            val created = playerFactory(context.applicationContext)
            activePlayer = created
            created.setMediaItem(RemotePlaybackMediaItemFactory.create(playback))
            created.repeatMode = Player.REPEAT_MODE_OFF

            val lifecycleController = RemotePlaybackLifecycleController(
                pausePlayback = { created.pause() },
                resumePlayback = { created.play() },
                onPlaybackFailed = { playbackFailed = true }
            )
            val listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    lifecycleController.onPlaybackError()
                }
            }
            playerListener = listener
            created.addListener(listener)

            val observer = remotePlaybackLifecycleObserver(
                controller = lifecycleController,
                playWhenReady = { created.playWhenReady }
            )
            lifecycleObserver = observer
            lifecycleOwner.lifecycle.addObserver(observer)

            val shouldStartImmediately = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
            created.playWhenReady = shouldStartImmediately
            preparePlayer(created)
            if (!shouldStartImmediately) {
                lifecycleController.onPause(playWhenReady = true)
            }
            playerState.value = created
        } catch (_: Exception) {
            lifecycleObserver?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            activePlayer?.let { failedPlayer ->
                playerListener?.let { failedPlayer.removeListener(it) }
                detachRemotePlaybackPlayerView(playerViewState.value, failedPlayer)
                releasePlayer(failedPlayer)
            }
            activePlayer = null
            playerListener = null
            lifecycleObserver = null
            playerState.value = null
            playbackFailed = true
        }

        onDispose {
            lifecycleObserver?.let { lifecycleOwner.lifecycle.removeObserver(it) }
            activePlayer?.let { releasingPlayer ->
                playerListener?.let { releasingPlayer.removeListener(it) }
                detachRemotePlaybackPlayerView(playerViewState.value, releasingPlayer)
                if (playerState.value === releasingPlayer) {
                    playerState.value = null
                }
                releasePlayer(releasingPlayer)
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

                // A failed native player is no longer useful; removing AndroidView invokes onRelease,
                // which detaches the PlayerView before the dialog's player is disposed.
                if (shouldAttachRemotePlaybackView(player != null, playbackFailed)) {
                    val activePlayer = requireNotNull(player)
                    OwnedRemotePlaybackAndroidView(
                        owner = activePlayer,
                        viewState = playerViewState,
                        factory = { viewContext ->
                            playerViewFactory(viewContext).apply {
                                useController = true
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            }
                        },
                        currentOwner = { it.player },
                        bindOwner = { view, owner -> view.player = owner as? Player },
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

/**
 * Binds an AndroidView to one playback session and releases only that session's view.
 *
 * Keeping the ownership and release callbacks in one composable makes the real Compose
 * AndroidView lifecycle testable with a lightweight View, without inflating Media3 resources.
 */
@Composable
internal fun <V : View> OwnedRemotePlaybackAndroidView(
    owner: Any,
    viewState: MutableState<V?>,
    factory: (Context) -> V,
    currentOwner: (V) -> Any?,
    bindOwner: (V, Any?) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { viewContext ->
            factory(viewContext).also { view -> bindOwner(view, owner) }
        },
        update = { view ->
            if (currentOwner(view) !== owner) bindOwner(view, owner)
            if (viewState.value !== view) viewState.value = view
        },
        onRelease = { view ->
            detachOwnedRemotePlaybackView(
                view = view,
                owner = owner,
                currentOwner = currentOwner,
                detach = { releasedView -> bindOwner(releasedView, null) }
            )
            if (viewState.value === view) viewState.value = null
        },
        modifier = modifier
    )
}

/**
 * Dismisses remote playback before returning to the exact saved bookmark URL.
 *
 * Do not normalize, rebuild, or substitute the media resource URL here: the original
 * page may contain client-side route state or query parameters needed by the browser.
 */
internal fun openOriginalSourcePage(
    originalSourceUrl: String,
    dismissPlayback: () -> Unit,
    onOpenBrowser: (String) -> Unit
) {
    if (originalSourceUrl.isBlank()) return
    dismissPlayback()
    onOpenBrowser(originalSourceUrl)
}

/**
 * Keep the native PlayerView attached only while a live player has not entered a terminal error
 * state. Removing AndroidView lets its onRelease callback detach the view from ExoPlayer.
 */
internal fun shouldAttachRemotePlaybackView(
    hasPlayer: Boolean,
    playbackFailed: Boolean
): Boolean = hasPlayer && !playbackFailed


/**
 * Returns true only when the view still references the player owned by the disposing session.
 * Kept as a pure identity policy so it can be tested without inflating Media3 UI resources.
 */
internal fun shouldDetachRemotePlaybackPlayerView(
    currentPlayer: Any?,
    owningPlayer: Any?
): Boolean = currentPlayer != null && currentPlayer === owningPlayer

/**
 * Detaches a PlayerView only when it is still owned by the player being disposed.
 * Shared by AndroidView release and the dialog's disposal path so an old session
 * cannot detach a view that has already been rebound to a newer player.
 */
internal fun detachRemotePlaybackPlayerView(view: PlayerView?, owningPlayer: Player?) {
    detachOwnedRemotePlaybackView(
        view = view,
        owner = owningPlayer,
        currentOwner = { it.player },
        detach = { it.player = null }
    )
}

/**
 * Applies the playback-session identity check to a concrete AndroidView instance.
 * A stale session cannot detach a view that has since been rebound to another owner.
 */
internal fun <V> detachOwnedRemotePlaybackView(
    view: V?,
    owner: Any?,
    currentOwner: (V) -> Any?,
    detach: (V) -> Unit
) {
    if (view == null) return

    detachRemotePlaybackPlayerViewIfOwned(
        currentPlayer = currentOwner(view),
        owningPlayer = owner
    ) {
        detach(view)
    }
}

/**
 * Runs the actual detach only while the view is still bound to this session's player.
 * Both AndroidView.onRelease and DisposableEffect.onDispose use this path, so either
 * callback order is safe and the second callback becomes a no-op after the first detaches.
 */
internal fun detachRemotePlaybackPlayerViewIfOwned(
    currentPlayer: Any?,
    owningPlayer: Any?,
    detach: () -> Unit
) {
    if (shouldDetachRemotePlaybackPlayerView(currentPlayer, owningPlayer)) {
        detach()
    }
}
