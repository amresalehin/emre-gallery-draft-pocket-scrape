package com.amresalehin.emregallery.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemotePlaybackLifecycleControllerTest {
    @Test
    fun resumesWhenPlaybackWasRequestedBeforeLifecyclePause() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onResume()

        assertEquals(listOf("pause", "play"), actions)
    }

    @Test
    fun doesNotResumeWhenUserHadAlreadyPausedPlayback() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = false)
        controller.onResume()

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun duplicatePauseDoesNotEraseOriginalPlayIntent() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onPause(playWhenReady = false)
        controller.onResume()

        assertEquals(listOf("pause", "play"), actions)
    }

    @Test
    fun doesNotResumeAfterTerminalPlaybackError() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onPause(playWhenReady = true)
        controller.onPlaybackError()
        controller.onResume()

        assertEquals(listOf("pause"), actions)
    }

    @Test
    fun terminalFailureWhileResumedPausesImmediatelyAndIsIdempotent() {
        val actions = mutableListOf<String>()
        var failureNotifications = 0
        val controller = RemotePlaybackLifecycleController(
            pausePlayback = { actions += "pause" },
            resumePlayback = { actions += "play" },
            onPlaybackFailed = { failureNotifications += 1 }
        )

        controller.onPlaybackError()
        controller.onPlaybackError()
        controller.onPause(playWhenReady = false)
        controller.onResume()

        assertEquals(listOf("pause"), actions)
        assertEquals(1, failureNotifications)
    }

    @Test
    fun disposedPlaybackSessionIgnoresLateCallbacksAndCloseIsIdempotent() {
        val guard = RemotePlaybackSessionGuard()
        var callbackCount = 0

        guard.runIfActive { callbackCount += 1 }
        guard.close()
        guard.close()
        guard.runIfActive { callbackCount += 1 }

        assertEquals(1, callbackCount)
    }

    @Test
    fun resumeWithoutLifecyclePauseIsIgnored() {
        val actions = mutableListOf<String>()
        val controller = controller(actions)

        controller.onResume()

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun lifecycleObserverPreservesPlayIntentWhenPausedDuringBuffering() {
        val actions = mutableListOf<String>()
        var playWhenReady = true // Media3 keeps this true while STATE_BUFFERING.
        val observer = remotePlaybackLifecycleObserver(
            controller = RemotePlaybackLifecycleController(
                pausePlayback = {
                    actions += "pause"
                    playWhenReady = false
                },
                resumePlayback = {
                    actions += "play"
                    playWhenReady = true
                }
            ),
            playWhenReady = { playWhenReady }
        )
        val owner = resumedLifecycleOwner(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(listOf("pause", "play"), actions)
        assertTrue(playWhenReady)
    }

    @Test
    fun lifecycleObserverDoesNotResumeAfterFailureArrivesWhilePaused() {
        val actions = mutableListOf<String>()
        var playWhenReady = true
        var failureNotifications = 0
        val controller = RemotePlaybackLifecycleController(
            pausePlayback = {
                actions += "pause"
                playWhenReady = false
            },
            resumePlayback = { actions += "play" },
            onPlaybackFailed = { failureNotifications += 1 }
        )
        val observer = remotePlaybackLifecycleObserver(
            controller = controller,
            playWhenReady = { playWhenReady }
        )
        val owner = resumedLifecycleOwner(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        controller.onPlaybackError()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        controller.onPlaybackError()

        assertEquals(listOf("pause"), actions)
        assertEquals(1, failureNotifications)
        assertFalse(playWhenReady)
    }

    @Test
    fun disposedLifecycleObserverIgnoresLateEventsWithoutReadingReleasedPlayerState() {
        val actions = mutableListOf<String>()
        var playWhenReadyReads = 0
        val sessionGuard = RemotePlaybackSessionGuard()
        val observer = remotePlaybackLifecycleObserver(
            controller = controller(actions),
            playWhenReady = {
                playWhenReadyReads += 1
                true
            },
            sessionGuard = sessionGuard
        )
        val owner = TestLifecycleOwner()
        owner.lifecycle.addObserver(observer)

        sessionGuard.close()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertEquals(emptyList<String>(), actions)
        assertEquals(0, playWhenReadyReads)
    }

    @Test
    fun lifecycleObserverIgnoresUnrelatedEvents() {
        val actions = mutableListOf<String>()
        val observer = remotePlaybackLifecycleObserver(
            controller = controller(actions),
            playWhenReady = { true }
        )
        val owner = TestLifecycleOwner()
        owner.lifecycle.addObserver(observer)

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        assertEquals(emptyList<String>(), actions)
    }

    @Test
    fun closeWaitsForInFlightCallbackAndRejectsCallbacksAfterDisposal() {
        val guard = RemotePlaybackSessionGuard()
        val callbackEntered = CountDownLatch(1)
        val finishCallback = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val callbackCount = AtomicInteger()

        val callbackThread = Thread {
            guard.runIfActive {
                callbackEntered.countDown()
                check(finishCallback.await(5, TimeUnit.SECONDS))
                callbackCount.incrementAndGet()
            }
        }
        val closeThread = Thread {
            closeAttempted.countDown()
            guard.close()
            closeFinished.countDown()
        }

        try {
            callbackThread.start()
            assertTrue(
                "callback did not enter",
                callbackEntered.await(2, TimeUnit.SECONDS)
            )
            closeThread.start()
            assertTrue(
                "close thread did not start",
                closeAttempted.await(2, TimeUnit.SECONDS)
            )
            assertTrue(
                "close should block behind an active callback",
                awaitThreadState(closeThread, Thread.State.BLOCKED)
            )
            assertEquals(
                "close must not finish while the callback is active",
                1L,
                closeFinished.count
            )
        } finally {
            finishCallback.countDown()
            callbackThread.join(2_000)
            closeThread.join(2_000)
        }

        assertFalse("callback thread should terminate", callbackThread.isAlive)
        assertFalse("close thread should terminate", closeThread.isAlive)
        assertEquals("close should finish after the callback exits", 0L, closeFinished.count)
        assertEquals(1, callbackCount.get())

        guard.runIfActive { callbackCount.incrementAndGet() }

        assertEquals("callbacks after close must be ignored", 1, callbackCount.get())
    }

    private fun awaitThreadState(
        thread: Thread,
        expectedState: Thread.State
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.state == expectedState) return true
            if (!thread.isAlive) return false
            Thread.yield()
        }
        return thread.state == expectedState
    }

    private fun controller(actions: MutableList<String>) =
        RemotePlaybackLifecycleController(
            pausePlayback = { actions += "pause" },
            resumePlayback = { actions += "play" }
        )

    private fun resumedLifecycleOwner(
        observer: LifecycleEventObserver
    ): TestLifecycleOwner {
        val owner = TestLifecycleOwner()
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.lifecycle.addObserver(observer)
        return owner
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle
            get() = registry
    }
}
