package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import org.junit.Assert.assertEquals
import org.junit.Test

/** spacingFor token mapping + the avatar-column invariant. */
class SpacingTest {

    @Test
    fun compact_tokens() {
        val s = spacingFor(LayoutDensity.COMPACT)
        assertEquals(12.dp, s.messageOuterHPad)
        assertEquals(0.dp, s.bubbleRowVPad)
        assertEquals(4.dp, s.bubbleInnerVPad)
        assertEquals(8.dp, s.bubbleInnerHPad)
        // COMPACT keeps today's tight rows: no inter-bubble gap.
        assertEquals(0.dp, s.bubbleBurstGap)
        assertEquals(0.dp, s.bubbleBreakGap)
        assertEquals(12.dp, s.bubbleCorner)
        assertEquals(26.dp, s.bubbleAvatar)
        assertEquals(34.dp, s.bubbleAvatarColumn)
        assertEquals(2.dp, s.actionVPad)
        assertEquals(2.dp, s.systemPillVPad)
        assertEquals(6.dp, s.chatListVPad)
        assertEquals(40.dp, s.chatListAvatar)
        assertEquals(32.dp, s.memberAvatar)
        // COMPACT is the only mode that switches to the classic single-line IRC renderer.
        assertEquals(true, s.compact)
        assertEquals(1.dp, s.compactRowVPad)
    }

    @Test
    fun comfortable_tokens_follow_expressive_scale() {
        val s = spacingFor(LayoutDensity.COMFORTABLE)
        assertEquals(12.dp, s.messageOuterHPad)
        assertEquals(1.dp, s.bubbleRowVPad)
        assertEquals(6.dp, s.bubbleInnerVPad)
        assertEquals(10.dp, s.bubbleInnerHPad)
        // COMFORTABLE opens Telegram-style gaps: tight within a burst, larger across a break.
        assertEquals(2.dp, s.bubbleBurstGap)
        assertEquals(8.dp, s.bubbleBreakGap)
        assertEquals(20.dp, s.bubbleCorner)
        assertEquals(32.dp, s.bubbleAvatar)
        assertEquals(40.dp, s.bubbleAvatarColumn)
        assertEquals(3.dp, s.actionVPad)
        assertEquals(4.dp, s.systemPillVPad)
        assertEquals(10.dp, s.chatListVPad)
        assertEquals(48.dp, s.chatListAvatar)
        assertEquals(36.dp, s.memberAvatar)
        // COMFORTABLE keeps the bubble renderer.
        assertEquals(false, s.compact)
    }

    @Test
    fun two_line_tokens() {
        val s = spacingFor(LayoutDensity.TWO_LINE)
        assertEquals(12.dp, s.messageOuterHPad)
        assertEquals(2.dp, s.bubbleRowVPad)
        assertEquals(4.dp, s.bubbleInnerVPad)
        assertEquals(12.dp, s.bubbleInnerHPad)
        // TWO_LINE keeps its compact rows: no inter-bubble gap.
        assertEquals(0.dp, s.bubbleBurstGap)
        assertEquals(0.dp, s.bubbleBreakGap)
        assertEquals(20.dp, s.bubbleCorner)
        assertEquals(20.dp, s.bubbleAvatar)
        assertEquals(28.dp, s.bubbleAvatarColumn)
        assertEquals(3.dp, s.actionVPad)
        assertEquals(4.dp, s.systemPillVPad)
        assertEquals(10.dp, s.chatListVPad)
        assertEquals(48.dp, s.chatListAvatar)
        assertEquals(36.dp, s.memberAvatar)
        // TWO_LINE is the compact two-line renderer: not the single-line IRC row, not a bubble.
        assertEquals(false, s.compact)
        assertEquals(true, s.twoLine)
    }

    @Test
    fun compact_is_the_only_single_line_renderer() {
        assertEquals(true, spacingFor(LayoutDensity.COMPACT).compact)
        assertEquals(false, spacingFor(LayoutDensity.COMFORTABLE).compact)
        assertEquals(false, spacingFor(LayoutDensity.TWO_LINE).compact)
    }

    @Test
    fun avatarColumn_is_avatar_plus_8dp_for_all_densities() {
        for (d in LayoutDensity.entries) {
            val s = spacingFor(d)
            assertEquals("$d: column == avatar + 8.dp", s.bubbleAvatar + 8.dp, s.bubbleAvatarColumn)
        }
    }

    @Test
    fun comfortable_compact_spacing_scales_inter_message_gaps_by_half() {
        val s = spacingFor(LayoutDensity.COMFORTABLE, MessageSpacing.COMPACT, BubbleCornerStyle.ROUNDED)
        assertEquals(1.dp, s.bubbleBurstGap)
        assertEquals(4.dp, s.bubbleBreakGap)
        assertEquals(0.5.dp, s.bubbleRowVPad)
        assertEquals(0.5.dp, s.compactRowVPad)
        assertEquals(1.5.dp, s.actionVPad)
        assertEquals(2.dp, s.systemPillVPad)
        // Inner-bubble padding and horizontal tokens are never scaled.
        assertEquals(6.dp, s.bubbleInnerVPad)
        assertEquals(10.dp, s.bubbleInnerHPad)
        assertEquals(12.dp, s.messageOuterHPad)
    }

    @Test
    fun comfortable_relaxed_spacing_scales_inter_message_gaps_by_1_75x() {
        val s = spacingFor(LayoutDensity.COMFORTABLE, MessageSpacing.RELAXED, BubbleCornerStyle.ROUNDED)
        assertEquals(3.5.dp, s.bubbleBurstGap)
        assertEquals(14.dp, s.bubbleBreakGap)
        assertEquals(1.75.dp, s.bubbleRowVPad)
        assertEquals(1.75.dp, s.compactRowVPad)
        assertEquals(5.25.dp, s.actionVPad)
        assertEquals(7.dp, s.systemPillVPad)
        // Inner-bubble padding and horizontal tokens are never scaled.
        assertEquals(6.dp, s.bubbleInnerVPad)
        assertEquals(10.dp, s.bubbleInnerHPad)
        assertEquals(12.dp, s.messageOuterHPad)
    }

    @Test
    fun comfortable_subtle_corners() {
        val s = spacingFor(LayoutDensity.COMFORTABLE, MessageSpacing.DEFAULT, BubbleCornerStyle.SUBTLE)
        assertEquals(12.dp, s.bubbleCorner)
        assertEquals(4.dp, s.bubbleGroupedCorner)
    }

    @Test
    fun comfortable_square_corners() {
        val s = spacingFor(LayoutDensity.COMFORTABLE, MessageSpacing.DEFAULT, BubbleCornerStyle.SQUARE)
        assertEquals(2.dp, s.bubbleCorner)
        assertEquals(2.dp, s.bubbleGroupedCorner)
    }

    @Test
    fun compact_density_keeps_its_own_corner_regardless_of_corner_style() {
        val subtle = spacingFor(LayoutDensity.COMPACT, MessageSpacing.DEFAULT, BubbleCornerStyle.SUBTLE)
        val square = spacingFor(LayoutDensity.COMPACT, MessageSpacing.DEFAULT, BubbleCornerStyle.SQUARE)
        assertEquals(12.dp, subtle.bubbleCorner)
        assertEquals(6.dp, subtle.bubbleGroupedCorner)
        assertEquals(12.dp, square.bubbleCorner)
        assertEquals(6.dp, square.bubbleGroupedCorner)
    }

    @Test
    fun compact_density_relaxed_spacing_scales_compactRowVPad() {
        val s = spacingFor(LayoutDensity.COMPACT, MessageSpacing.RELAXED, BubbleCornerStyle.ROUNDED)
        assertEquals(1.75.dp, s.compactRowVPad)
        // COMPACT's own bubbleRowVPad/gaps are 0.dp regardless of the multiplier.
        assertEquals(0.dp, s.bubbleRowVPad)
        assertEquals(0.dp, s.bubbleBurstGap)
        assertEquals(0.dp, s.bubbleBreakGap)
    }
}
