package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.ui.theme.MotdDarkScheme
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

    // The row container is animated (MotdMotion.colorFade), and Compose interpolates a Color's
    // channels independently of its alpha — so the invisible DEFAULT endpoint must carry the
    // surface hue. Color.Transparent is transparent BLACK: fading the unread tint from it blended
    // every arrival through a semi-opaque dark color, which read as the row flashing dark.
    @Test fun animatedContainerEndpoints_neverPassThroughTransparentBlack() {
        listOf(MotdLightScheme, MotdDarkScheme).forEach { scheme ->
            val default = chatListRowContainer(ChatListRowVisualState.DEFAULT, scheme)
            assertEquals(scheme.surface.copy(alpha = 0f), default)
            // Every visible state is fully opaque, so no other transition composites mid-fade.
            ChatListRowVisualState.entries
                .filterNot { it == ChatListRowVisualState.DEFAULT }
                .forEach { state ->
                    assertEquals(1f, chatListRowContainer(state, scheme).alpha, 0f)
                }
        }
    }
}
