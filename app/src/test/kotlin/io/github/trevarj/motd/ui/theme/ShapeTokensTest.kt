package io.github.trevarj.motd.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ShapeTokensTest {
    @Test fun sharedShapes_followTheExpressiveScale() {
        assertEquals(RoundedCornerShape(8.dp), MotdShapes.tag)
        assertEquals(RoundedCornerShape(12.dp), MotdShapes.compact)
        assertEquals(RoundedCornerShape(16.dp), MotdShapes.card)
        assertEquals(RoundedCornerShape(20.dp), MotdShapes.bubble)
        assertEquals(RoundedCornerShape(6.dp), MotdShapes.groupedBubble)
        assertEquals(RoundedCornerShape(28.dp), MotdShapes.composer)
        assertEquals(RoundedCornerShape(percent = 50), MotdShapes.pill)
    }

    @Test fun componentSizes_shareAConsistentHierarchy() {
        assertEquals(32.dp, MotdSizes.messageAvatar)
        assertEquals(40.dp, MotdSizes.headerAvatar)
        assertEquals(48.dp, MotdSizes.chatListAvatar)
        assertEquals(24.dp, MotdSizes.icon)
        assertEquals(48.dp, MotdSizes.touchTarget)
        assertEquals(56.dp, MotdSizes.floatingActionButton)
    }
}
