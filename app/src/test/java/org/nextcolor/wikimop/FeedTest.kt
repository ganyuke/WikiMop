package org.nextcolor.wikimop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nextcolor.wikimop.data.Chunk

class FeedTest {
    /** A list of numbers, newest first, served three at a time like a wiki's list of changes. */
    private var newest = 10
    private fun feed() = Feed(CoroutineScope(Dispatchers.Unconfined), { it: Int -> it }) { from ->
        val start = from?.toInt() ?: newest
        Chunk((start downTo maxOf(1, start - 2)).toList(), (start - 3).takeIf { it > 0 }?.toString())
    }

    @Test fun catchingUpKeepsThePartsFurtherDown() {
        val feed = feed()
        feed.start()
        feed.more()
        feed.more()
        feed.catchUp(0)
        assertEquals((10 downTo 2).toList(), feed.items)
        // Two changes come in, one of them made from the app.
        newest = 12
        feed.catchUp(1)
        assertEquals((12 downTo 2).toList(), feed.items)
        // Further down is loaded from where it was before.
        feed.more()
        assertEquals((12 downTo 1).toList(), feed.items)
    }

    @Test fun tooManyNewOffersToStartOver() {
        val feed = feed()
        feed.start()
        feed.more()
        feed.catchUp(0)
        // More come in than the newest part holds, so it would not meet the list as shown.
        newest = 14
        feed.catchUp(1)
        assertEquals((10 downTo 5).toList(), feed.items)
        assertTrue(feed.behind)
        feed.refresh()
        assertEquals(listOf(14, 13, 12), feed.items)
        assertFalse(feed.behind)
    }

    @Test fun aRowDroppingOutOfTheTopStillMeets() {
        // Only odd numbers are shown, like only unpatrolled changes, and the top one drops out once it is dealt with.
        var top = 9
        val feed = Feed(CoroutineScope(Dispatchers.Unconfined), { it: Int -> it }) { from ->
            Chunk((top downTo 1 step 2).take(3), "more")
        }
        feed.start()
        feed.catchUp(0)
        top = 7
        feed.catchUp(1)
        assertEquals(listOf(7, 5, 3), feed.items)
        assertFalse(feed.behind)
    }

    @Test fun catchingUpOnlyLoadsAfterAChange() {
        var loads = 0
        val feed = Feed(CoroutineScope(Dispatchers.Unconfined), { it: Int -> it }) { loads++; Chunk(listOf(1), null) }
        feed.start()
        feed.catchUp(3)
        feed.catchUp(3)
        assertEquals(1, loads)
        feed.catchUp(4)
        assertEquals(2, loads)
    }

    @Test fun pullingDownStartsOver() {
        val feed = feed()
        feed.start()
        feed.more()
        newest = 12
        feed.refresh()
        assertEquals(listOf(12, 11, 10), feed.items)
    }
}
