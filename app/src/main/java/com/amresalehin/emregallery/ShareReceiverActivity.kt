package com.amresalehin.emregallery

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.MediaClassifier
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.ui.FullScreenBrowserView
import com.amresalehin.emregallery.ui.theme.EmreGalleryTheme

/**
 * Share-sheet receiver that opens the browser directly with the shared link,
 * removing any intermediate scraper steps.
 */
class ShareReceiverActivity : ComponentActivity() {

    private val sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        GalleryApplication.warmupChromium(this)

        val extracted = extractUrlFromIntent(intent)
        if (extracted == null) {
            Toast.makeText(this, "No valid link found in shared content", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sharedUrlState.value = extracted

        setContent {
            val preferences = remember { PreferencesRepository(applicationContext) }
            val theme by preferences.theme.collectAsStateWithLifecycle(initialValue = "system")
            val currentUrl by sharedUrlState

            EmreGalleryTheme(theme = theme, surfaceColor = Color.Transparent) {
                if (currentUrl != null) {
                    ShareReceiverScreen(
                        url = currentUrl!!,
                        onDismiss = {
                            finishWithFade()
                        },
                        onDownloadComplete = {
                            Toast.makeText(
                                applicationContext,
                                "Download queued in background",
                                Toast.LENGTH_SHORT
                            ).show()
                            finishWithFade()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val extracted = extractUrlFromIntent(intent)
        if (extracted != null) {
            sharedUrlState.value = extracted
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return null

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.dataString
            ?: intent.clipData?.let { cd ->
                if (cd.itemCount > 0) cd.getItemAt(0).text?.toString() ?: cd.getItemAt(0).uri?.toString() else null
            }

        return MediaClassifier.extractFirstUrl(text)
    }

    private fun finishWithFade() {
        finish()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }
}

@Composable
private fun ShareReceiverScreen(
    url: String,
    onDismiss: () -> Unit,
    onDownloadComplete: () -> Unit
) {
    BackHandler {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("share_receiver_root")
    ) {
        FullScreenBrowserView(
            initialUrl = url,
            onClose = onDismiss,
            onDownloadComplete = {
                onDownloadComplete()
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
