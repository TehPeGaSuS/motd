package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.ui.theme.MotdLightScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListRowVisualTest {
    @Test fun rowStates_haveStablePriorityAndDistinctContainers() {
        assertEquals(
            ChatListRowVisualState.SELECTED,
            chatListRowVisualState(selected = true, active = true, unread = true),
        )
        assertEquals(
            ChatListRowVisualState.ACTIVE,
            chatListRowVisualState(selected = false, active = true, unread = true),
        )
        assertEquals(
            ChatListRowVisualState.UNREAD,
            chatListRowVisualState(selected = false, active = false, unread = true),
        )

        val containers = ChatListRowVisualState.entries
            .map { chatListRowContainer(it, MotdLightScheme) }
        assertEquals(containers.size, containers.distinct().size)
    }
}
