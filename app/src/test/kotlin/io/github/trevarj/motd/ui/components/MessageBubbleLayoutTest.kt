package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.ui.theme.MotdLightScheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleLayoutTest {
    @Test fun bubbleWidth_usesItsPaneAndCapsWideLayouts() {
        assertEquals(344, bubbleMaxWidthPx(420, 560))
        assertEquals(560, bubbleMaxWidthPx(1_000, 560))
        assertEquals(0, bubbleMaxWidthPx(0, 560))
    }

    @Test fun bubbleRoles_areDistinctAndReadable() {
        val scheme = MotdLightScheme
        val roles = listOf(
            messageBubbleRoleColors(scheme, isSelf = false, mentionHighlighted = false, MessageKind.PRIVMSG),
            messageBubbleRoleColors(scheme, isSelf = true, mentionHighlighted = false, MessageKind.PRIVMSG),
            messageBubbleRoleColors(scheme, isSelf = false, mentionHighlighted = true, MessageKind.PRIVMSG),
            messageBubbleRoleColors(scheme, isSelf = false, mentionHighlighted = false, MessageKind.NOTICE),
        )

        assertEquals(4, roles.map { it.container }.distinct().size)
        roles.forEach { role ->
            assertTrue(contrastRatio(role.content, role.container) >= 4.5)
        }
    }
}
