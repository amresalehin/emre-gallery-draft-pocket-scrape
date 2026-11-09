package com.amresalehin.emregallery.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackOptionsRequestGateTest {
    @Test
    fun newerSelectionSupersedesAnEarlierRefreshForTheSameBookmark() {
        val gate = PlaybackOptionsRequestGate()
        gate.activate("bookmark-a")

        val firstRequest = gate.begin("bookmark-a")
        val secondRequest = gate.begin("bookmark-a")

        assertNotNull(firstRequest)
        assertNotNull(secondRequest)
        assertFalse(gate.isCurrent("bookmark-a", requireNotNull(firstRequest)))
        assertTrue(gate.isCurrent("bookmark-a", requireNotNull(secondRequest)))
    }

    @Test
    fun switchingBookmarksInvalidatesAnOutstandingRefresh() {
        val gate = PlaybackOptionsRequestGate()
        gate.activate("bookmark-a")
        val oldRequest = requireNotNull(gate.begin("bookmark-a"))

        gate.invalidate("bookmark-a")
        gate.activate("bookmark-b")

        assertFalse(gate.isCurrent("bookmark-a", oldRequest))
        assertNull(gate.begin("bookmark-a"))
        val newRequest = requireNotNull(gate.begin("bookmark-b"))
        assertTrue(gate.isCurrent("bookmark-b", newRequest))
    }

    @Test
    fun invalidatingAnOldBookmarkCannotInvalidateTheActiveBookmark() {
        val gate = PlaybackOptionsRequestGate()
        gate.activate("bookmark-b")
        val request = requireNotNull(gate.begin("bookmark-b"))

        gate.invalidate("bookmark-a")

        assertTrue(gate.isCurrent("bookmark-b", request))
    }

    @Test
    fun reactivatingTheSameBookmarkDoesNotReviveAnOldRequest() {
        val gate = PlaybackOptionsRequestGate()
        gate.activate("bookmark-a")
        val oldRequest = requireNotNull(gate.begin("bookmark-a"))

        gate.invalidate("bookmark-a")
        gate.activate("bookmark-a")

        assertFalse(gate.isCurrent("bookmark-a", oldRequest))
        val newRequest = requireNotNull(gate.begin("bookmark-a"))
        assertTrue(gate.isCurrent("bookmark-a", newRequest))
    }
}
