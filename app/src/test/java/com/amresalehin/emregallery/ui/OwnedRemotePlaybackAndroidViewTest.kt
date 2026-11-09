package com.amresalehin.emregallery.ui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnedRemotePlaybackAndroidViewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun removingAndroidViewDetachesOnceAndLaterParentDisposalIsANoOp() {
        val owner = Any()
        val viewState = mutableStateOf<OwnerBoundView?>(null)
        val showView = mutableStateOf(true)
        val mountHost = mutableStateOf(true)
        var detachCount = 0

        composeRule.setContent {
            if (mountHost.value) {
                PlaybackViewHost(
                    owner = owner,
                    viewState = viewState,
                    showView = showView.value,
                    onDetach = { detachCount += 1 }
                )
            }
        }
        composeRule.waitForIdle()

        val attachedView = requireNotNull(viewState.value)
        assertSame(owner, attachedView.owner)

        composeRule.runOnIdle { showView.value = false }
        composeRule.waitForIdle()

        assertNull(attachedView.owner)
        assertNull(viewState.value)
        assertEquals(1, detachCount)

        composeRule.runOnIdle { mountHost.value = false }
        composeRule.waitForIdle()

        assertEquals(1, detachCount)
    }

    @Test
    fun disposingParentDetachesAttachedAndroidViewExactlyOnce() {
        val owner = Any()
        val viewState = mutableStateOf<OwnerBoundView?>(null)
        val mountHost = mutableStateOf(true)
        var detachCount = 0

        composeRule.setContent {
            if (mountHost.value) {
                PlaybackViewHost(
                    owner = owner,
                    viewState = viewState,
                    showView = true,
                    onDetach = { detachCount += 1 }
                )
            }
        }
        composeRule.waitForIdle()

        val attachedView = requireNotNull(viewState.value)
        assertSame(owner, attachedView.owner)

        composeRule.runOnIdle { mountHost.value = false }
        composeRule.waitForIdle()

        assertNull(attachedView.owner)
        assertNull(viewState.value)
        assertEquals(1, detachCount)
    }
}

@Composable
private fun PlaybackViewHost(
    owner: Any,
    viewState: MutableState<OwnerBoundView?>,
    showView: Boolean,
    onDetach: () -> Unit
) {
    DisposableEffect(owner) {
        onDispose {
            detachOwnedRemotePlaybackView(
                view = viewState.value,
                owner = owner,
                currentOwner = { it.owner },
                detach = { view ->
                    if (view.owner != null) onDetach()
                    view.owner = null
                }
            )
        }
    }

    if (showView) {
        OwnedRemotePlaybackAndroidView(
            owner = owner,
            viewState = viewState,
            factory = ::OwnerBoundView,
            currentOwner = { it.owner },
            bindOwner = { view, nextOwner ->
                if (view.owner != null && nextOwner == null) onDetach()
                view.owner = nextOwner
            },
            modifier = Modifier.size(120.dp)
        )
    }
}

private class OwnerBoundView(context: Context) : FrameLayout(context) {
    var owner: Any? = null
}
