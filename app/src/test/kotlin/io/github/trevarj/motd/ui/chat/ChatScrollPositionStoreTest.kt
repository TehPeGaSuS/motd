package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.TimelineAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollPositionStoreTest {
    @Test
    fun `the displayed watermark only ever moves deeper into history`() {
        val store = ChatScrollPositionStore()
        val deep = TimelineAnchor(100, 1, 1)
        val shallow = TimelineAnchor(500, 5, 5)

        store.recordFurthestDisplayed(10, shallow)
        store.recordFurthestDisplayed(10, deep)
        // Scrolling back forward does not retract what the reader already had on screen.
        store.recordFurthestDisplayed(10, shallow)

        assertEquals(deep, store.furthestDisplayed(10))
        assertNull(store.furthestDisplayed(11))
    }

    @Test
    fun `clearing a saved position keeps the watermark`() {
        // Reaching the bottom means "resume live next time", not "the reader never saw that
        // history". Dropping the watermark here would resurrect the forward-reader defect on the
        // next backlog: the park would be gone AND the depth it was earned at with it.
        val store = ChatScrollPositionStore()
        val anchor = TimelineAnchor(100, 1, 1)
        store.put(10, ChatScrollPosition(index = 4, offset = 0, msgid = "a", serverTime = 100, rowId = 1))
        store.recordFurthestDisplayed(10, anchor)

        store.remove(10)

        assertNull(store.get(10))
        assertEquals(anchor, store.furthestDisplayed(10))
    }

    @Test
    fun `stores last position per buffer`() {
        val store = ChatScrollPositionStore()
        val first = ChatScrollPosition(index = 4, offset = 12, msgid = "a", serverTime = 100, rowId = 1)
        val second = ChatScrollPosition(index = 0, offset = 0, msgid = "b", serverTime = 200, rowId = 2)

        store.put(10, first)
        store.put(11, second)

        assertEquals(first, store.get(10))
        assertEquals(second, store.get(11))
        assertNull(store.get(12))
    }
}
