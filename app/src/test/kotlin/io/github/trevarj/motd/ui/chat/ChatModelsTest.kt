package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.repo.MESSAGE_PAGING_CONFIG
import io.github.trevarj.motd.data.sync.GapFillProgress
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.ui.components.HistoryGapState
import io.github.trevarj.motd.ui.theme.spacingFor
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {

    @Test fun `conversation layout inherits global when no override exists`() {
        assertEquals(
            LayoutDensity.COMPACT,
            ConversationLayoutState(global = LayoutDensity.COMPACT).effective,
        )
    }

    @Test fun `conversation layout override wins over global`() {
        assertEquals(
            LayoutDensity.TWO_LINE,
            ConversationLayoutState(
                global = LayoutDensity.COMPACT,
                override = LayoutDensity.TWO_LINE,
            ).effective,
        )
    }

    private fun react(
        msgid: String,
        sender: String,
        emoji: String,
        actorKey: String = IrcIdentityRules().actorKey(sender, null),
    ) =
        ReactionEntity(
            bufferId = 1L,
            targetMsgid = msgid,
            actorKey = actorKey,
            sender = sender,
            emoji = emoji,
            serverTime = 0L,
        )

    private fun message(
        kind: MessageKind = MessageKind.PRIVMSG,
        self: Boolean = false,
        failed: Boolean = false,
        id: Long = 1L,
        sender: String = "nick",
        normalizedActor: String = IrcIdentityRules().normalize(sender),
        senderAccount: String? = null,
        serverTime: Long = 1L,
    ) =
        MessageEntity(
            id = id,
            bufferId = 1L,
            serverTime = serverTime,
            sender = sender,
            normalizedActor = normalizedActor,
            senderAccount = senderAccount,
            kind = kind,
            text = "text",
            isSelf = self,
            failed = failed,
            dedupKey = "1",
        )

    @Test fun `mine matches own nick case-insensitively`() {
        val chips = aggregateReactions(listOf(react("m1", "Alice", "👍")), myNick = "alice")
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `mine uses rfc1459 casefolding for bracket chars`() {
        // nick[] and nick{} are the same nick under rfc1459 ( [ == { , ] == } ).
        val chips = aggregateReactions(listOf(react("m1", "nick[]", "🎉")), myNick = "nick{}")
        assertTrue("rfc1459 folding should treat []{} as equivalent", chips.getValue("m1").single().mine)
    }

    @Test fun `mine is false for a different reactor`() {
        val chips = aggregateReactions(listOf(react("m1", "bob", "👍")), myNick = "alice")
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `mine is false when disconnected (null nick)`() {
        val chips = aggregateReactions(listOf(react("m1", "alice", "👍")), myNick = null)
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `strict casemap does not merge tilde and caret reaction actors`() {
        val strict = IrcIdentityRules(IrcCaseMapping.Rfc1459Strict)
        val chips = aggregateReactions(
            listOf(react("m1", "nick~", "👍", strict.actorKey("nick~", null))),
            myNick = "nick^",
            identityRules = strict,
        )
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `reaction ownership prefers exact account actor`() {
        val chips = aggregateReactions(
            listOf(react("m1", "oldNick", "👍", actorKey = "account:alice")),
            myNick = "newNick",
            myAccount = "alice",
        )
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `persisted account owns reaction without a live nick`() {
        val chips = aggregateReactions(
            listOf(react("m1", "oldNick", "👍", actorKey = "account:alice")),
            myNick = null,
            myAccount = "alice",
        )
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `counts aggregate per emoji preserving first-appearance order`() {
        val chips = aggregateReactions(
            listOf(
                react("m1", "a", "👍"),
                react("m1", "b", "👍"),
                react("m1", "c", "🎉"),
            ),
            myNick = "z",
        ).getValue("m1")
        assertEquals(listOf("👍", "🎉"), chips.map { it.emoji })
        assertEquals(2, chips[0].count)
        assertEquals(1, chips[1].count)
    }

    // Auto-stick-to-bottom decision (autoscroll-to-newest bug). Pin the reverse list to the newest
    // row only when the user was already at the bottom AND a new row actually arrived.
    @Test fun `autoscroll when at bottom and count grew`() {
        assertTrue(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 11))
    }

    @Test fun `initial paging page does not animate an already-bottom reverse list`() {
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 0, newCount = 50))
    }

    @Test fun `no autoscroll when scrolled up even if count grew`() {
        assertFalse(shouldAutoscrollToNewest(atBottom = false, oldCount = 10, newCount = 11))
    }

    @Test fun `no autoscroll when count did not grow`() {
        // Same count (e.g. an echo-confirm msgid swap) or a shrink must not yank the viewport.
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 10))
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 9))
    }

    @Test fun `FAB tap with a pending mention follows the mention walk`() {
        val target = ChatPositionTarget(index = 7, expectedEventId = 70)
        assertEquals(
            ScrollToBottomFabJump.Mention(target),
            scrollToBottomFabJump(longPress = false, mentionTarget = target),
        )
    }

    @Test fun `FAB tap with no pending mention falls through to newest`() {
        assertEquals(ScrollToBottomFabJump.Newest, scrollToBottomFabJump(longPress = false, mentionTarget = null))
    }

    @Test fun `FAB long-press skips the mention walk and goes to newest`() {
        assertEquals(
            ScrollToBottomFabJump.Newest,
            scrollToBottomFabJump(longPress = true, mentionTarget = ChatPositionTarget(index = 7)),
        )
    }

    @Test fun `FAB long-press with no pending mention also goes to newest`() {
        assertEquals(ScrollToBottomFabJump.Newest, scrollToBottomFabJump(longPress = true, mentionTarget = null))
    }

    @Test fun `FAB hold animation compresses the icon continuously`() {
        assertEquals(1f, scrollToBottomFabIconScale(0f), 0.0001f)
        assertEquals(0.96f, scrollToBottomFabIconScale(0.5f), 0.0001f)
        assertEquals(0.92f, scrollToBottomFabIconScale(1f), 0.0001f)
    }

    @Test fun `FAB hold animation clamps transient progress`() {
        assertEquals(1f, scrollToBottomFabIconScale(-0.5f), 0.0001f)
        assertEquals(0.92f, scrollToBottomFabIconScale(1.5f), 0.0001f)
    }

    @Test fun `burst arrivals keep following across programmatic scroll motion`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)

        assertTrue(tracker.onItemCountChanged(11))
        tracker.onScrollStateChanged(scrolling = true, programmatic = true, atBottom = false)

        // A second insert while the first pin is active must request another pin rather than
        // interpreting the programmatic scroll as the user leaving the bottom.
        assertTrue(tracker.onItemCountChanged(12))
        assertTrue(tracker.following)
    }

    @Test fun `user scroll disables following until settling at bottom`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)

        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = true)
        assertFalse(tracker.onItemCountChanged(11))
        assertFalse(tracker.following)

        tracker.onScrollStateChanged(scrolling = false, programmatic = false, atBottom = true)
        assertTrue(tracker.onItemCountChanged(12))
    }

    @Test fun `initial paging reset is not treated as a live arrival`() {
        val tracker = AutoFollowTracker(initialItemCount = 0)

        tracker.reset(itemCount = 50, atBottom = true)
        assertFalse(tracker.onItemCountChanged(50))
        assertTrue(tracker.onItemCountChanged(51))
    }

    @Test fun `explicit newest request restores following`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = false)

        tracker.requestFollow()

        assertTrue(tracker.onItemCountChanged(11))
    }

    @Test fun `513 recovered history rows do not follow or animate a live entry`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 7)

        val recoveredHistory = tracker.onTimelineChangedWithEntry(
            newItemCount = 523,
            newNewestEffectiveId = 7,
        )

        assertFalse(recoveredHistory.shouldFollow)
        assertNull(recoveredHistory.liveEntryId)
        assertTrue(tracker.following)
        assertTrue(tracker.onTimelineChanged(newItemCount = 524, newNewestEffectiveId = 8))
    }

    @Test fun `live entry animation is emitted only for a followed newer identity`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 7)

        assertNull(tracker.onTimelineChangedWithEntry(11, 7).liveEntryId)

        val live = tracker.onTimelineChangedWithEntry(12, 8)
        assertTrue(live.shouldFollow)
        assertEquals(8L, live.liveEntryId)

        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = false)
        assertNull(tracker.onTimelineChangedWithEntry(13, 9).liveEntryId)
    }

    @Test fun `burst arrivals retain every in-flight live entry`() {
        val first = appendLiveEntryId(emptySet(), 8L)
        val burst = appendLiveEntryId(first, 9L)

        assertEquals(setOf(8L, 9L), appendLiveEntryId(burst, null))
    }

    @Test fun `live entry disposal consumes only its own identity`() {
        val burst = setOf(8L, 9L)

        assertEquals(setOf(9L), consumeLiveEntryId(current = burst, consumed = 8L))
        assertEquals(emptySet<Long>(), consumeLiveEntryId(current = setOf(9L), consumed = 9L))
    }

    @Test fun `system run extension updates its existing pill without entry motion`() {
        val rows = listOf(
            message(id = 9L, kind = MessageKind.JOIN),
            message(id = 8L, kind = MessageKind.PART),
            message(id = 7L),
        )

        assertTrue(extendsSystemRun(9L, rows.size, rows::getOrNull))
        assertFalse(extendsSystemRun(7L, rows.size, rows::getOrNull))
        assertFalse(extendsSystemRun(9L, 1) { rows.getOrNull(it) })
        assertFalse(extendsSystemRun(null, rows.size, rows::getOrNull))
    }

    @Test fun `paging invalidation cannot break following live arrivals`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 10)

        // Room invalidation can temporarily replace a populated Paging snapshot with an empty one.
        assertFalse(tracker.onTimelineChanged(newItemCount = 0, newNewestEffectiveId = null))

        assertTrue(tracker.onTimelineChanged(newItemCount = 11, newNewestEffectiveId = 11))
        assertTrue(tracker.following)
    }

    @Test fun `page replacement follows a newer identity even when loaded count stays constant`() {
        val tracker = AutoFollowTracker(initialItemCount = 50)
        tracker.reset(itemCount = 50, atBottom = true, newestEffectiveId = 50)

        assertTrue(tracker.onTimelineChanged(newItemCount = 50, newNewestEffectiveId = 51))
    }

    @Test fun `paging invalidation never overrides explicit user scroll intent`() {
        val tracker = AutoFollowTracker(initialItemCount = 10)
        tracker.reset(itemCount = 10, atBottom = true, newestEffectiveId = 10)
        tracker.onScrollStateChanged(scrolling = true, programmatic = false, atBottom = false)

        assertFalse(tracker.onTimelineChanged(newItemCount = 0, newNewestEffectiveId = null))
        assertFalse(tracker.onTimelineChanged(newItemCount = 11, newNewestEffectiveId = 11))
        assertFalse(tracker.following)
    }

    @Test fun `random paging and scroll interleavings preserve follow intent for every layout`() {
        LayoutDensity.entries.forEachIndexed { layoutIndex, _ ->
            val random = Random(0xA170 + layoutIndex)
            val tracker = AutoFollowTracker(initialItemCount = 50)
            var newestId = 100L
            var count = 50
            var expectedFollowing = true
            tracker.reset(count, atBottom = true, newestEffectiveId = newestId)

            repeat(1_000) {
                when (random.nextInt(6)) {
                    0 -> assertFalse(tracker.onTimelineChanged(0, null))
                    1 -> {
                        newestId++
                        count = (count + random.nextInt(0, 2)).coerceAtLeast(1)
                        assertEquals(
                            expectedFollowing,
                            tracker.onTimelineChanged(count, newestId),
                        )
                    }
                    2 -> tracker.onScrollStateChanged(
                        scrolling = random.nextBoolean(),
                        programmatic = true,
                        atBottom = random.nextBoolean(),
                    )
                    3 -> {
                        tracker.onScrollStateChanged(
                            scrolling = true,
                            programmatic = false,
                            atBottom = false,
                        )
                        expectedFollowing = false
                    }
                    4 -> {
                        tracker.onScrollStateChanged(
                            scrolling = false,
                            programmatic = false,
                            atBottom = true,
                        )
                        expectedFollowing = true
                    }
                    else -> assertFalse(
                        tracker.onTimelineChanged(count, newestId - 1),
                    )
                }
                assertEquals(expectedFollowing, tracker.following)
            }
        }
    }

    @Test fun `collapsed fool tail counts as effective bottom and cannot become saved anchor`() {
        val rows = listOf(
            message(id = 3, sender = "fool"),
            message(id = 2, sender = "alice"),
            message(id = 1, sender = "bob"),
        )
        val policy = MessageVisibilityPolicy(
            MessageVisibilitySpec(fools = setOf("fool"), foolsMode = FoolsMode.COLLAPSE),
        )

        assertTrue(isAtEffectiveBottom(1, 0, rows.size, rows::getOrNull, policy))
        assertEquals(2L, newestEffectiveMessageId(rows.size, rows::getOrNull, policy))
        assertEquals(2L, nearestAnchorRow(0, rows.size, rows::getOrNull, policy)?.second?.id)
    }

    @Test fun `meaningful row below viewport means it is not effective bottom`() {
        val rows = listOf(message(id = 2), message(id = 1))
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())

        assertFalse(isAtEffectiveBottom(1, 0, rows.size, rows::getOrNull, policy))
    }

    @Test fun `an unloaded row below the viewport blocks the effective bottom`() {
        // Unknown is not "already read". This is the whole safety property: the consumer of this
        // predicate acknowledges the ROOM's newest anchor, so a placeholder below the viewport —
        // what a deep jump leaves behind once maxSize drops the newest pages — must not be skipped
        // the way a materialized-but-ignored row is.
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())
        val parked: (Int) -> MessageEntity? = { index -> message(id = 300L - index).takeIf { index >= 200 } }

        assertFalse(isAtEffectiveBottom(200, 0, 400, parked, policy))
        // One materialized ignored row, then nothing: the ignorable tail must not paper over the
        // placeholders under it either.
        val foolPolicy = MessageVisibilityPolicy(
            MessageVisibilitySpec(fools = setOf("fool"), foolsMode = FoolsMode.COLLAPSE),
        )
        val foolThenPlaceholders: (Int) -> MessageEntity? = { index ->
            message(id = 3, sender = "fool").takeIf { index == 0 }
        }
        assertFalse(isAtEffectiveBottom(3, 0, 10, foolThenPlaceholders, foolPolicy))
    }

    @Test fun `a viewport genuinely at the bottom probes nothing and still follows`() {
        // Auto-follow is unaffected by the stricter rule: index 0 is the bottom, so there is no row
        // below the viewport to be unknown about. A `peek` that would throw proves nothing is read.
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())
        val never: (Int) -> MessageEntity? = { error("a bottom viewport must not probe below itself") }

        assertTrue(isAtEffectiveBottom(0, 0, 400, never, policy))
        // Still bottom within the autoscroll slack, and still not probing.
        assertTrue(isAtEffectiveBottom(0, AUTOSCROLL_BOTTOM_TOLERANCE_PX, 400, never, policy))
        assertFalse(isAtEffectiveBottom(0, AUTOSCROLL_BOTTOM_TOLERANCE_PX + 1, 400, never, policy))
    }

    @Test fun `placeholder-aware helpers never scan a 50k unloaded timeline`() {
        var probes = 0
        val peek: (Int) -> MessageEntity? = {
            probes++
            null
        }
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())

        assertNull(newestEffectiveMessageId(50_000, peek, policy))
        assertTrue(probes <= MAX_PLACEHOLDER_PROBES)
        probes = 0
        assertFalse(isAtEffectiveBottom(49_999, 0, 50_000, peek, policy))
        assertEquals(0, probes)
        assertNull(nearestAnchorRow(25_000, 50_000, peek, policy))
        assertTrue(probes <= MAX_PLACEHOLDER_PROBES * 2)
    }

    @Test fun `normal entry scrolls newest only when retained state is off bottom`() {
        assertFalse(shouldScrollToInitialTarget(ChatPositionTarget(index = 0), atBottom = true))
        assertTrue(shouldScrollToInitialTarget(ChatPositionTarget(index = 0), atBottom = false))
    }

    @Test fun `unsaved older target cannot displace a bottom aligned entry`() {
        assertFalse(shouldScrollToInitialTarget(ChatPositionTarget(index = 513), atBottom = true))
        assertTrue(shouldScrollToInitialTarget(ChatPositionTarget(index = 513), atBottom = false))
    }

    @Test fun `last read marker entry target displaces even a bottom aligned conversation`() {
        assertTrue(
            shouldScrollToInitialTarget(ChatPositionTarget(index = 513, forceScrollOnEntry = true), atBottom = true),
        )
        assertTrue(
            shouldScrollToInitialTarget(ChatPositionTarget(index = 0, forceScrollOnEntry = true), atBottom = true),
        )
        // placeAtTop is realized in ChatScreen, not the gate; forceScrollOnEntry still fires it.
        assertTrue(
            shouldScrollToInitialTarget(
                ChatPositionTarget(index = 515, forceScrollOnEntry = true, placeAtTop = true),
                atBottom = true,
            ),
        )
    }

    @Test fun `a frozen entry boundary restores an absence as an absence`() {
        // Computed-with-a-marker round-trips verbatim.
        assertEquals(
            UnreadEntrySnapshot(TimelineAnchor(101, 7, 9), loadedCount = 3, lowerBound = true),
            restoredUnreadEntrySnapshot(
                computed = true,
                markerServerTime = 101,
                markerEventId = 7,
                markerTimelineOrder = 9,
                loadedCount = 3,
                lowerBound = true,
            ),
        )
        // A visit that froze the ABSENCE of a boundary keeps it: recomputing would raise a divider
        // for messages that arrived after entry, which is what freezing on entry prevents.
        assertNull(restoredUnreadEntrySnapshot(true, 0, 0, 0, 0, false))
        // Never frozen (a genuinely new visit) is not the same state and must recompute instead.
        assertNull(restoredUnreadEntrySnapshot(false, 101, 7, 9, 3, true))
    }

    @Test fun `saved scroll position always restores`() {
        assertTrue(
            shouldScrollToInitialTarget(
                ChatPositionTarget(index = 0, offset = 20, fromSavedPosition = true),
                atBottom = true,
            ),
        )
    }

    @Test fun `first unread top anchor lands the target at the top of a reversed viewport`() {
        // Only one row fits: the first unread is both top and bottom.
        assertEquals(515, firstUnreadTopAnchorIndex(firstUnreadIndex = 515, rowsFit = 1))
        // Ten rows fit: first unread 9 rows above the bottom = at the top.
        assertEquals(506, firstUnreadTopAnchorIndex(firstUnreadIndex = 515, rowsFit = 10))
        // Fewer unread than rows fit: clamp to 0 (cannot scroll past newest to top a low index).
        assertEquals(0, firstUnreadTopAnchorIndex(firstUnreadIndex = 3, rowsFit = 10))
        // Single unread at newest: no movement.
        assertEquals(0, firstUnreadTopAnchorIndex(firstUnreadIndex = 0, rowsFit = 10))
        assertEquals(0, firstUnreadTopAnchorIndex(firstUnreadIndex = 0, rowsFit = 1))
        // Two rows fit, one unread below newest: clamp to 0.
        assertEquals(0, firstUnreadTopAnchorIndex(firstUnreadIndex = 1, rowsFit = 2))
    }

    @Test fun `measured row correction aligns variable-height unread target to the visual top`() {
        // reverseLayout offsets grow from the viewport start (visual bottom); top alignment is
        // offset + size == viewportEndOffset. scrollBy(delta) moves the item to offset - delta.
        // Entry row just past the top edge (real reopen trace): a small positive nudge up.
        assertEquals(
            75,
            reverseItemTopAlignmentCorrection(
                itemOffset = 1_651,
                itemSize = 222,
                viewportEndOffset = 1_798,
            ),
        )
        // Entry row resting at the max-scroll clamp (real first-open trace): a negative correction
        // eases it down into exact top alignment instead of poking past the viewport end.
        assertEquals(
            -138,
            reverseItemTopAlignmentCorrection(
                itemOffset = 1_284,
                itemSize = 376,
                viewportEndOffset = 1_798,
            ),
        )
        // Already top-aligned: no correction.
        assertEquals(
            0,
            reverseItemTopAlignmentCorrection(
                itemOffset = 1_576,
                itemSize = 222,
                viewportEndOffset = 1_798,
            ),
        )
    }

    @Test fun `composer does not need member nicks for blank text or command hints`() {
        assertFalse(composerNeedsMemberNicks(TextFieldValue("")))
        assertFalse(composerNeedsMemberNicks(TextFieldValue("/jo", TextRange(3))))
    }

    @Test fun `composer needs member nicks only for qualifying nick tokens`() {
        assertFalse(composerNeedsMemberNicks(TextFieldValue("a", TextRange(1))))
        assertTrue(composerNeedsMemberNicks(TextFieldValue("al", TextRange(2))))
        assertTrue(composerNeedsMemberNicks(TextFieldValue("@a", TextRange(2))))
    }

    @Test fun `lazy row content types separate structurally different messages`() {
        assertEquals(MessageContentType.OTHER, messageContentType(message()))
        assertEquals(MessageContentType.SELF, messageContentType(message(self = true)))
        assertEquals(MessageContentType.SELF_FAILED, messageContentType(message(self = true, failed = true)))
        assertEquals(MessageContentType.ACTION, messageContentType(message(kind = MessageKind.ACTION)))
        assertEquals(MessageContentType.SYSTEM, messageContentType(message(kind = MessageKind.JOIN)))
        assertEquals(MessageContentType.NETWORK_BATCH, messageContentType(message(kind = MessageKind.NETSPLIT)))
        assertEquals(MessageContentType.NETWORK_BATCH, messageContentType(message(kind = MessageKind.NETJOIN)))
    }

    @Test fun `grouping uses account then casemapped actor and always separates direction`() {
        val accountOlder = message(
            id = 1,
            sender = "OldNick",
            senderAccount = "alice",
            serverTime = 100,
        )
        val accountCurrent = message(
            id = 2,
            sender = "NewNick",
            senderAccount = "alice",
            serverTime = 200,
        )
        assertFalse(showsSender(accountCurrent, accountOlder))
        assertTrue(showsSender(accountCurrent.copy(senderAccount = "other"), accountOlder))

        val mappedOlder = message(id = 3, sender = "nick[]", normalizedActor = "nick{}", serverTime = 300)
        val mappedCurrent = message(id = 4, sender = "nick{}", normalizedActor = "nick{}", serverTime = 400)
        assertFalse(showsSender(mappedCurrent, mappedOlder))
        assertTrue(showsSender(mappedCurrent.copy(isSelf = true), mappedOlder))

        val partiallyEnriched = mappedCurrent.copy(senderAccount = "late-account")
        assertFalse(showsSender(partiallyEnriched, mappedOlder))
    }

    @Test fun `action breaks the consecutive-sender group on either side`() {
        // Same actor, within the 3-min window: a plain continuation hides its nick.
        val older = message(id = 1, sender = "nick", serverTime = 100)
        val current = message(id = 2, sender = "nick", serverTime = 200)
        assertFalse(showsSender(current, older))

        // Regular message after an ACTION shows its nick again (the reported bug).
        val actionOlder = message(id = 3, sender = "nick", kind = MessageKind.ACTION, serverTime = 300)
        val afterAction = message(id = 4, sender = "nick", serverTime = 400)
        assertTrue(showsSender(afterAction, actionOlder))

        // An ACTION after a regular message also opens a new group.
        val beforeAction = message(id = 5, sender = "nick", serverTime = 500)
        val actionCurrent = message(id = 6, sender = "nick", kind = MessageKind.ACTION, serverTime = 600)
        assertTrue(showsSender(actionCurrent, beforeAction))

        // Two consecutive ACTIONs each open their own group.
        val actionA = message(id = 7, sender = "nick", kind = MessageKind.ACTION, serverTime = 700)
        val actionB = message(id = 8, sender = "nick", kind = MessageKind.ACTION, serverTime = 800)
        assertTrue(showsSender(actionB, actionA))

        // Regression: the plain grouping window still collapses same-sender PRIVMSGs.
        val spaced = message(id = 9, sender = "nick", serverTime = 100)
        val spacedLater = message(id = 10, sender = "nick", serverTime = 100 + GROUP_WINDOW_MS + 1)
        assertTrue(showsSender(spacedLater, spaced))
    }

    @Test fun `bubble gap tracks grouping and density`() {
        val comfortable = spacingFor(LayoutDensity.COMFORTABLE)
        val compact = spacingFor(LayoutDensity.COMPACT)

        // No older neighbor => no gap (oldest row, nothing above to space from).
        assertEquals(0.dp, bubbleGap(showSender = true, hasOlder = false, comfortable))
        assertEquals(0.dp, bubbleGap(showSender = false, hasOlder = false, comfortable))

        // Continuing a same-sender group (showsSender false) => burst; new group (showsSender true) => break.
        assertEquals(2.dp, bubbleGap(showSender = false, hasOlder = true, comfortable))
        assertEquals(8.dp, bubbleGap(showSender = true, hasOlder = true, comfortable))

        // COMPACT tokens are 0 => no gap regardless of grouping.
        assertEquals(0.dp, bubbleGap(showSender = false, hasOlder = true, compact))
        assertEquals(0.dp, bubbleGap(showSender = true, hasOlder = true, compact))
    }

    @Test fun `typed UI queue replays in order and acknowledges by stable id`() {
        val queue = ChatUiEventQueue()
        val first = queue.enqueue(ChatUiEvent.InvalidCommand)
        val second = queue.enqueue(ChatUiEvent.SendRejected)

        assertEquals(listOf(first, second), queue.pending.value)
        queue.acknowledge(first.id)
        assertEquals(listOf(second), queue.pending.value)
        queue.acknowledge(first.id)
        assertEquals(listOf(second), queue.pending.value)
    }

    @Test fun `typed snackbar handles retry before acknowledging and preserves exact reply request`() {
        val order = mutableListOf<String>()
        val request = ReplyJumpRequest("MiXeD/opaque=Reply")
        var retried: ReplyJumpRequest? = null
        handleChatUiEventResult(
            event = QueuedChatUiEvent(8, ChatUiEvent.ReplyJumpUnavailable(request)),
            actionPerformed = true,
            retryReplyJump = { retried = it; order += "retry" },
            acknowledge = { order += "ack:$it" },
        )
        assertEquals(request, retried)
        assertEquals(listOf("retry", "ack:8"), order)
    }

    @Test fun `history footer derives the six states from append and availability`() {
        val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.MSGID), pageLimit = 50)
        val idle = LoadState.NotLoading(endOfPaginationReached = false)
        val ended = LoadState.NotLoading(endOfPaginationReached = true)
        val failed = LoadState.Error(IllegalStateException("boom"))
        val connected = IrcClientState.Ready("me", emptySet(), emptyMap())

        fun state(
            bufferType: BufferType?,
            connection: IrcClientState?,
            availability: HistoryAvailability,
            append: LoadState,
            historyComplete: Boolean = false,
        ) = chatHistoryUiState(bufferType, connection, availability, append, historyComplete)

        // Hidden: no/server buffer, or a Ready timeline with nothing terminal to show. A Ready
        // end-of-pagination without persisted completion (e.g. an unrecoverable gap) is silent
        // because scroll-driven APPEND owns any further fetch.
        assertEquals(ChatHistoryUiState.Hidden, state(null, connected, ready, idle))
        assertEquals(ChatHistoryUiState.Hidden, state(BufferType.SERVER, connected, ready, idle))
        assertEquals(ChatHistoryUiState.Hidden, state(BufferType.CHANNEL, connected, ready, idle))
        assertEquals(ChatHistoryUiState.Hidden, state(BufferType.CHANNEL, connected, ready, ended))

        // Loading: an APPEND page is in flight.
        assertEquals(ChatHistoryUiState.Loading, state(BufferType.CHANNEL, connected, ready, LoadState.Loading))

        // Retry: a recoverable append error while history is advertised.
        assertEquals(ChatHistoryUiState.Retry, state(BufferType.CHANNEL, connected, ready, failed))

        // Unavailable(offline): disconnected/fatal. Unavailable(negotiating): mid-registration,
        // whether the append is an error or merely idle.
        assertEquals(
            ChatHistoryUiState.Unavailable(offline = true),
            state(BufferType.CHANNEL, IrcClientState.Disconnected, HistoryAvailability.NegotiatingOrOffline, failed),
        )
        assertEquals(
            ChatHistoryUiState.Unavailable(offline = false),
            state(BufferType.CHANNEL, IrcClientState.Registering, HistoryAvailability.NegotiatingOrOffline, failed),
        )
        assertEquals(
            ChatHistoryUiState.Unavailable(offline = false),
            state(BufferType.CHANNEL, IrcClientState.Registering, HistoryAvailability.NegotiatingOrOffline, idle),
        )

        // Unsupported: the capability decision supersedes any append state.
        assertEquals(
            ChatHistoryUiState.Unsupported,
            state(BufferType.CHANNEL, connected, HistoryAvailability.Unsupported, idle),
        )
        assertEquals(
            ChatHistoryUiState.Unsupported,
            state(BufferType.CHANNEL, connected, HistoryAvailability.Unsupported, failed),
        )

        // ConfirmedStart: persisted completion at end-of-pagination.
        assertEquals(
            ChatHistoryUiState.ConfirmedStart,
            state(BufferType.CHANNEL, connected, ready, ended, historyComplete = true),
        )
    }

    @Test fun `offline failure retries once when Ready is first observed with the error`() {
        val gate = HistoryReadyRetryGate()
        val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.MSGID), pageLimit = 50)
        val disconnected = IrcDisconnectedException("CHATHISTORY", "offline")
        val offline = LoadState.Error(disconnected)

        assertTrue(gate.update(ready, offline))
        assertFalse(gate.update(ready, offline))
        assertFalse(gate.update(HistoryAvailability.Unsupported, offline))
        assertFalse(gate.update(ready, LoadState.Error(IllegalStateException("not offline"))))

        val nextGeneration = LoadState.Error(IrcDisconnectedException("CHATHISTORY", "offline again"))
        assertFalse(gate.update(HistoryAvailability.NegotiatingOrOffline, nextGeneration))
        assertTrue(gate.update(ready, nextGeneration))
        assertFalse(gate.update(ready, nextGeneration))
    }

    @Test
    fun `identity-free insertion point at snapshot end settles on the last row`() {
        assertEquals(0, materializableTargetIndex(1, itemCount = 1, hasExactIdentity = false))
        assertEquals(4, materializableTargetIndex(5, itemCount = 5, hasExactIdentity = false))
        assertEquals(null, materializableTargetIndex(1, itemCount = 1, hasExactIdentity = true))
        assertEquals(null, materializableTargetIndex(0, itemCount = 0, hasExactIdentity = false))
    }

    @Test
    fun `materialized target follows its stable key when an insertion shifts the index`() {
        val row = MessageEntity(
            id = 7,
            bufferId = 1,
            serverTime = 100,
            sender = "alice",
            kind = MessageKind.PRIVMSG,
            text = "row",
            dedupKey = "row",
        )
        val materialized = MaterializedChatTarget(row, index = 4)
        val shiftedVisibleItems = listOf(
            99L to 4,
            row.id to 5,
        )

        assertEquals(4, materialized.index)
        assertEquals(7L, materialized.row.id)
        assertEquals(5, materializedTargetVisibleIndex(shiftedVisibleItems, row.id))
    }

    @Test
    fun `the newest escape is shown exactly when the viewport is not at the bottom`() {
        assertTrue(shouldShowNewestFab(atBottom = false, autoScrolling = false))
        assertFalse(shouldShowNewestFab(atBottom = true, autoScrolling = false))
        // A programmatic scroll in flight is already heading there; the FAB would flicker.
        assertFalse(shouldShowNewestFab(atBottom = false, autoScrolling = true))
    }

    @Test
    fun `viewport acknowledgement rests entirely on at-bottom`() {
        // There is no second gate any more, which is exactly why isAtEffectiveBottom has to mean
        // "nothing unseen below me" rather than "nothing seen-and-meaningful below me". See
        // BoundedIslandMarkReadTest for the predicate side of this contract.
        assertTrue(
            shouldMarkReadFromViewport(
                atBottom = true,
                initialPositionSettled = true,
                viewportReadEnabled = true,
            ),
        )
        // Every pre-existing precondition still gates on its own.
        assertFalse(shouldMarkReadFromViewport(false, true, true))
        assertFalse(shouldMarkReadFromViewport(true, false, true))
        assertFalse(shouldMarkReadFromViewport(true, true, false))
    }

    @Test
    fun `a resumed viewport acknowledges display, not arrival`() {
        val rendered = TimelineAnchor(1_000, 10, 10)
        val raw = TimelineAnchor(5_000, 40, 40)
        // Steady state is untouched: the room's newest row still retires the ignored raw tail.
        assertEquals(raw, viewportMarkReadAnchor(raw, rendered, resumed = false))
        // The first run after a pause may confirm only what the timeline actually put on screen.
        assertEquals(rendered, viewportMarkReadAnchor(raw, rendered, resumed = true))
        // Nothing arrived while paused, so the resume acknowledges exactly what it always did.
        assertEquals(raw, viewportMarkReadAnchor(raw, raw, resumed = true))
        // A rendered anchor can never overtake the room; the clamp only ever moves older.
        assertEquals(rendered, viewportMarkReadAnchor(rendered, raw, resumed = true))
        // Nothing was rendered yet: acknowledge nothing and let the next measure re-run the effect.
        assertNull(viewportMarkReadAnchor(raw, null, resumed = true))
        assertNull(viewportMarkReadAnchor(null, rendered, resumed = false))
    }

    @Test
    fun `rendered bottom anchor refuses a layout that predates the paging snapshot`() {
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec())
        val rows = listOf(
            message(id = 40, serverTime = 5_000),
            message(id = 30, serverTime = 4_000),
            message(id = 10, serverTime = 1_000),
        )
        val peek = { index: Int -> rows.getOrNull(index) }

        // The measure pass saw this snapshot: the key still identifies the row at that index.
        assertEquals(
            TimelineAnchor(5_000, 40, 40),
            renderedBottomAnchor(0, 40L, rows.size, peek, policy),
        )
        // A prepend while the screen was paused shifted every index without a measure, so the
        // laid-out key no longer matches: the index now names a row that was never on screen.
        assertNull(renderedBottomAnchor(0, 10L, rows.size, peek, policy))
        // Nothing laid out at all.
        assertNull(renderedBottomAnchor(-1, null, rows.size, peek, policy))
        assertNull(renderedBottomAnchor(3, 99L, rows.size, peek, policy))
    }

    @Test
    fun `rendered bottom anchor skips an ignored row the way the effective bottom does`() {
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec(fools = setOf("troll")))
        val rows = listOf(
            message(id = 40, sender = "troll", serverTime = 5_000),
            message(id = 30, serverTime = 4_000),
        )
        assertEquals(
            TimelineAnchor(4_000, 30, 30),
            renderedBottomAnchor(0, 40L, rows.size, { rows.getOrNull(it) }, policy),
        )
    }

    // --- what the viewport asks for ---------------------------------------------------------------

    private fun row(id: Long, serverTime: Long) = message(id = id, serverTime = serverTime)

    /** Newest-first, the way the reversed timeline presents them. */
    private fun rows(vararg times: Long): List<MessageEntity> =
        times.mapIndexed { index, time -> row(id = times.size - index.toLong(), serverTime = time) }

    private fun withinPrefetch(
        rows: List<MessageEntity?>,
        first: Int,
        last: Int,
        seams: List<io.github.trevarj.motd.data.history.TimelineSeam>,
        prefetchDistance: Int = 0,
    ) = seamsWithinPrefetch(
        firstVisibleIndex = first,
        lastVisibleIndex = last,
        itemCount = rows.size,
        peek = { index -> rows.getOrNull(index) },
        seams = seams,
        prefetchDistance = prefetchDistance,
    )

    @Test
    fun `a seam between two visible rows is within reach`() {
        val timeline = rows(900, 500, 100)
        // The seam falls in the slot above the middle row, which is on screen.
        val seams = listOf(seam(gapId = 2, serverTime = 500))

        assertEquals(setOf(2L), withinPrefetch(timeline, first = 0, last = 2, seams))
    }

    @Test
    fun `a seam beyond the older edge comes within reach at the prefetch distance`() {
        val timeline = rows(900, 700, 500, 300, 100)
        val seams = listOf(seam(gapId = 2, serverTime = 300))

        // Two rows on screen and the seam three slots below them: out of reach, and reaching for it
        // would be fetching history nobody has scrolled toward.
        assertEquals(emptySet<Long>(), withinPrefetch(timeline, first = 0, last = 1, seams))
        // The same viewport with the list end's own rule applied to the seam: within the prefetch
        // window, so it loads before the reader gets there, exactly as an APPEND would.
        assertEquals(
            setOf(2L),
            withinPrefetch(timeline, first = 0, last = 1, seams, prefetchDistance = 2),
        )
    }

    @Test
    fun `a seam the reader has scrolled past stops being asked for`() {
        val timeline = rows(900, 700, 500, 300, 100)
        val seams = listOf(seam(gapId = 2, serverTime = 700))

        // The seam sits above row 700, which is now BELOW the viewport: the reader went past it and
        // is reading the older side. The window only ever extends toward history, so it drops out —
        // the same way Paging stops appending once you scroll back up.
        assertEquals(
            emptySet<Long>(),
            withinPrefetch(timeline, first = 3, last = 4, seams, prefetchDistance = 25),
        )
    }

    @Test
    fun `two seams within reach are both asked for`() {
        val timeline = rows(900, 500, 100)
        val seams = listOf(seam(gapId = 1, serverTime = 300), seam(gapId = 2, serverTime = 500))

        // The signal says what is in reach. Picking ONE to load is the rule's job, so loads stay
        // serialized and cannot contend for the same interval.
        assertEquals(setOf(1L, 2L), withinPrefetch(timeline, first = 0, last = 2, seams))
    }

    @Test
    fun `the reach abstains when the row past its end is not materialized`() {
        // Same rule seamAbove follows: a null neighbor leaves the slot's lower end unknown, so the
        // seam is not drawn there and must not be loaded there either. It is asked for once that
        // placeholder loads.
        val seams = listOf(seam(gapId = 2, serverTime = 500))
        val withPlaceholder = listOf(row(3, 900), row(2, 500), null)

        assertEquals(emptySet<Long>(), withinPrefetch(withPlaceholder, first = 1, last = 1, seams))

        val materialized = listOf(row(3, 900), row(2, 500), row(1, 100))
        assertEquals(setOf(2L), withinPrefetch(materialized, first = 1, last = 1, seams))
    }

    @Test
    fun `a viewport past the end of the list asks for nothing rather than throwing`() {
        val timeline = rows(900, 500)

        assertEquals(
            emptySet<Long>(),
            withinPrefetch(timeline, first = 5, last = 9, listOf(seam(gapId = 2, serverTime = 500))),
        )
        assertEquals(emptySet<Long>(), withinPrefetch(emptyList(), first = 0, last = 0, emptyList()))
    }

    @Test
    fun `the seam prefetch distance matches the one paging appends at`() {
        // Not a coincidence to be tidied away: a page inserts 50 rows against this distance, so one
        // load always pushes its own seam back out of reach and a stationary viewport stops. Raising
        // this past the page size would make an idle timeline fetch until the gap closed.
        assertEquals(MESSAGE_PAGING_CONFIG.prefetchDistance, SEAM_PREFETCH_DISTANCE)
        assertTrue(
            "a page must insert more rows than the trigger distance",
            MESSAGE_PAGING_CONFIG.pageSize > SEAM_PREFETCH_DISTANCE,
        )
    }

    // --- what one seam's divider says -------------------------------------------------------------

    @Test
    fun `a recoverable seam the reader has reached is loading, not waiting for a tap`() {
        val target = seam(gapId = 2, serverTime = 900)
        val state = TimelineSeamState(seams = listOf(target), historyUnavailable = false)

        // The divider exists only where its seam is composed, i.e. where the reader has scrolled to,
        // and scrolling to it is what loads it. A resting "tap to load" would be a second affordance
        // for something already happening — the incoherence this rule exists to remove.
        assertEquals(HistoryGapState.Loading, state.stateFor(target))
    }

    @Test
    fun `only a failed attempt offers a tap`() {
        val failed = seam(gapId = 2, serverTime = 900)
        val fine = seam(gapId = 3, serverTime = 100)
        val state = TimelineSeamState(
            seams = listOf(fine, failed),
            historyUnavailable = false,
            failed = setOf(2L),
        )

        assertEquals(HistoryGapState.Failed, state.stateFor(failed))
        // Per gap, not per room: the seam that has not broken is still loading.
        assertEquals(HistoryGapState.Loading, state.stateFor(fine))
    }

    @Test
    fun `a seam with no transport behind it offers a tap rather than an endless spinner`() {
        val target = seam(gapId = 2, serverTime = 900)

        assertEquals(
            HistoryGapState.Failed,
            TimelineSeamState(seams = listOf(target), historyUnavailable = true).stateFor(target),
        )
    }

    @Test
    fun `an in-flight retry shows its progress, and an unrecoverable seam beats everything`() {
        val target = seam(gapId = 2, serverTime = 900)
        val gone = seam(gapId = 4, serverTime = 900, recoverable = false)
        val state = TimelineSeamState(
            seams = listOf(target, gone),
            filling = setOf(2L, 4L),
            historyUnavailable = true,
            failed = setOf(2L, 4L),
        )

        assertEquals(HistoryGapState.Loading, state.stateFor(target))
        // A stale in-flight id must never paint a spinner on a seam that will never move.
        assertEquals(HistoryGapState.Unrecoverable, state.stateFor(gone))
    }

    @Test
    fun `negotiating is not the same as unreachable`() {
        // The distinction the seam's error state hangs on. A fresh connection spends a moment with
        // no history availability yet; painting "couldn't load" across every seam for that moment
        // would be an error the reader never had.
        assertFalse(
            historyUnreachable(HistoryAvailability.NegotiatingOrOffline, IrcClientState.Connecting),
        )
        assertTrue(
            historyUnreachable(HistoryAvailability.NegotiatingOrOffline, IrcClientState.Disconnected),
        )
        assertTrue(
            historyUnreachable(HistoryAvailability.NegotiatingOrOffline, IrcClientState.Failed("x", fatal = true)),
        )
        assertTrue(historyUnreachable(HistoryAvailability.Unsupported, IrcClientState.Ready("nick", emptySet(), emptyMap())))
        assertFalse(historyUnreachable(ready, IrcClientState.Ready("nick", emptySet(), emptyMap())))
    }

    // --- the history rule -------------------------------------------------------------------------

    private fun seam(gapId: Long, serverTime: Long, recoverable: Boolean = true) =
        io.github.trevarj.motd.data.history.TimelineSeam(
            gapId = gapId,
            position = TimelineAnchor(serverTime, gapId, gapId),
            recoverable = recoverable,
        )

    private val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100)

    private val gateOpen = SeamLoadingGate(onScreen = true, historyReady = true, entrySettled = true)

    private fun SeamLoadingRule.scrolledTo(
        depth: Int,
        seams: List<io.github.trevarj.motd.data.history.TimelineSeam>,
        inReach: Set<Long> = seams.mapTo(mutableSetOf()) { it.gapId },
        gate: SeamLoadingGate = gateOpen,
    ) = next(
        roomId = 7,
        gate = gate,
        seams = seams,
        prefetch = SeamPrefetch(lastVisibleIndex = depth, gapIds = inReach),
        tap = null,
    )

    @Test
    fun `scrolling to a seam loads across it`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))

        assertEquals(
            GapFillRequest(7, 2, fromTap = false),
            rule.scrolledTo(depth = 60, seams = seams),
        )
    }

    @Test
    fun `a seam out of reach is left alone`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))

        // Nothing has scrolled toward it. Before the divider existed nothing ever fetched a deep gap
        // on its own, and that is the property being kept.
        assertNull(rule.scrolledTo(depth = 60, seams = seams, inReach = emptySet()))
    }

    @Test
    fun `a stationary viewport loads once, even when the page did not push the seam out of reach`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        val loaded = checkNotNull(rule.scrolledTo(depth = 60, seams = seams))
        rule.settle(loaded, GapFillProgress.MOVED)

        // THE runaway case. Normally a 50-row page moves the seam past the 25-row reach on its own,
        // but a page that lands fewer rows than that leaves the seam sitting in its own trigger zone
        // with an identical signal behind it. Nothing about the reader changed, so nothing more is
        // fetched — no counter, no budget, just "you have not scrolled since".
        assertNull(rule.scrolledTo(depth = 60, seams = listOf(seam(gapId = 2, serverTime = 600))))
        repeat(20) {
            assertNull(rule.scrolledTo(depth = 60, seams = listOf(seam(gapId = 2, serverTime = 600))))
        }
    }

    @Test
    fun `scrolling further toward a seam keeps loading across it`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        rule.settle(checkNotNull(rule.scrolledTo(depth = 60, seams = seams)), GapFillProgress.MOVED)

        // The bound is the reader's attention, not an allowance: keep scrolling into history and it
        // keeps loading, exactly as the end of the list does.
        assertEquals(2L, rule.scrolledTo(depth = 61, seams = seams)?.gapId)
        rule.settle(checkNotNull(rule.scrolledTo(depth = 90, seams = seams)), GapFillProgress.MOVED)
        assertEquals(2L, rule.scrolledTo(depth = 140, seams = seams)?.gapId)
    }

    @Test
    fun `scrolling back toward the present does not re-load a seam`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        rule.settle(checkNotNull(rule.scrolledTo(depth = 60, seams = seams)), GapFillProgress.MOVED)

        // Drifting back up is not asking for more history.
        assertNull(rule.scrolledTo(depth = 59, seams = seams))
        assertNull(rule.scrolledTo(depth = 60, seams = seams))
    }

    @Test
    fun `a seam that left reach and came back loads again`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        rule.settle(checkNotNull(rule.scrolledTo(depth = 60, seams = seams)), GapFillProgress.MOVED)

        // Scroll away: the seam drops out of reach and the depth it was loaded at is forgotten.
        assertNull(rule.scrolledTo(depth = 10, seams = seams, inReach = emptySet()))
        // Coming back to it is a fresh approach, and the reader plainly wants what is behind it.
        assertEquals(2L, rule.scrolledTo(depth = 60, seams = seams)?.gapId)
    }

    @Test
    fun `each seam is remembered at its own depth`() {
        val rule = SeamLoadingRule()
        val newer = seam(gapId = 2, serverTime = 900)
        val older = seam(gapId = 1, serverTime = 100)
        val both = listOf(older, newer)

        // Two seams in reach at once is the case that would otherwise put two fetches on the wire for
        // adjacent intervals. Exactly one is offered per decision, newest first — the reader is
        // scrolling into history, so the nearer hole is the one they are reading toward.
        val first = checkNotNull(rule.scrolledTo(depth = 60, seams = both))
        assertEquals(2L, first.gapId)
        rule.settle(first, GapFillProgress.MOVED)
        assertEquals(1L, rule.scrolledTo(depth = 60, seams = both)?.gapId)
    }

    @Test
    fun `an unrecoverable seam is never loaded`() {
        val rule = SeamLoadingRule()

        // Nothing left to fetch: a load would cost a classification and change nothing.
        assertNull(rule.scrolledTo(60, listOf(seam(gapId = 4, serverTime = 900, recoverable = false))))
        // ...and refusing it is not the same as consuming it: the real seam still loads.
        assertEquals(2L, rule.scrolledTo(60, listOf(seam(gapId = 2, serverTime = 900)))?.gapId)
    }

    @Test
    fun `loading is gated on visibility, a ready transport, and a settled entry`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))

        assertNull("not on screen", rule.scrolledTo(60, seams, gate = gateOpen.copy(onScreen = false)))
        assertNull(
            "no transport to page against",
            rule.scrolledTo(60, seams, gate = gateOpen.copy(historyReady = false)),
        )
        // Entry freezes the unread boundary from the store, so a load that lands first would move
        // the divider onto rows this class had just fetched.
        assertNull(
            "entry has not resolved yet",
            rule.scrolledTo(60, seams, gate = gateOpen.copy(entrySettled = false)),
        )
        // None of the three consumed the seam: the gate is not a latch, so a room opened before its
        // connection settles — or before its entry positions — still catches up afterwards.
        assertEquals(2L, rule.scrolledTo(60, seams)?.gapId)
    }

    @Test
    fun `a failed attempt is the only thing that stops a seam loading`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        rule.settle(checkNotNull(rule.scrolledTo(60, seams)), GapFillProgress.FAILED)

        assertEquals(setOf(2L), rule.failedGapIds)
        // Scrolling further would otherwise retry a broken transport on every gesture. The divider
        // says what happened and the reader decides.
        assertNull(rule.scrolledTo(120, seams))
    }

    @Test
    fun `the retry tap resumes a failed seam and clears its error`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        rule.settle(checkNotNull(rule.scrolledTo(60, seams)), GapFillProgress.FAILED)

        val tapped = rule.next(7, gateOpen, seams, SeamPrefetch(60, setOf(2L)), GapTapRequest(2, 1))

        assertEquals(GapFillRequest(7, 2, fromTap = true), tapped)
        assertEquals(emptySet<Long>(), rule.failedGapIds)
        // One tap is one attempt: the same request replayed by the next combine emission is refused,
        // and the retry does not also hand the stationary viewport a scroll-driven load.
        assertNull(rule.next(7, gateOpen, seams, SeamPrefetch(60, setOf(2L)), GapTapRequest(2, 1)))
        rule.settle(checkNotNull(tapped), GapFillProgress.MOVED)
        assertNull(rule.scrolledTo(60, seams))
    }

    @Test
    fun `a retry tap is honored while the screen is still settling`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        val closed = SeamLoadingGate(onScreen = false, historyReady = false, entrySettled = false)

        // The user is looking at a failed divider. Nothing about a pending entry or a stale
        // availability snapshot makes their retry wrong.
        assertEquals(
            2L,
            rule.next(7, closed, seams, SeamPrefetch(60, emptySet()), GapTapRequest(2, 4))?.gapId,
        )
    }

    @Test
    fun `an empty-handed attempt is contention, not an error`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))

        // The anti-livelock stop with zero inserts, and a fill the room's single flight dropped:
        // some other fetch was working the same interval. The seam is still open, still recoverable,
        // and still exactly where it was, so advertising an error would be a lie about the history.
        rule.settle(checkNotNull(rule.scrolledTo(60, seams)), GapFillProgress.STALLED)
        assertEquals(emptySet<Long>(), rule.failedGapIds)
        assertEquals(2L, rule.scrolledTo(61, seams)?.gapId)

        rule.settle(checkNotNull(rule.scrolledTo(80, seams)), GapFillProgress.DROPPED)
        assertEquals(emptySet<Long>(), rule.failedGapIds)
        // ...and it is still bounded by the reader: no scroll, no retry.
        assertNull(rule.scrolledTo(80, seams))
    }

    @Test
    fun `a page that lands clears an earlier failure`() {
        val rule = SeamLoadingRule()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        rule.settle(checkNotNull(rule.scrolledTo(60, seams)), GapFillProgress.FAILED)
        val tapped = checkNotNull(
            rule.next(7, gateOpen, seams, SeamPrefetch(60, setOf(2L)), GapTapRequest(2, 1)),
        )

        rule.settle(tapped, GapFillProgress.MOVED)

        assertEquals(emptySet<Long>(), rule.failedGapIds)
        // The retry worked, so scrolling on resumes loading without another tap.
        assertEquals(2L, rule.scrolledTo(90, seams)?.gapId)
    }

    @Test
    fun `a reader working forward through unread keeps their place`() {
        // 300 unread. The reader ENTERED at the divider (index 300, so the watermark is 300), read
        // forward to row 100, and left. The read marker cannot advance mid-history — marking read
        // needs the effective bottom — so the first unread row is STILL the divider. Depth alone
        // cannot see the difference between this and a backfill; the watermark can, because the
        // reader has already had row 300 on screen.
        assertFalse(firstUnreadWinsEntry(savedIndex = 100, firstUnreadIndex = 300, 300))
        // Messages arriving while they were away shift both indices by the same amount.
        assertFalse(firstUnreadWinsEntry(savedIndex = 110, firstUnreadIndex = 310, 310))
        // They kept going on a later visit: still their place, never back to the divider.
        assertFalse(firstUnreadWinsEntry(savedIndex = 20, firstUnreadIndex = 300, 300))
    }

    @Test
    fun `unread older than anything displayed still wins entry`() {
        // The required E2E reopen: the reader saw down to row 48, then a backfill landed 150 rows of
        // unread history at 198. Nothing above the park has ever been on screen, so restoring the
        // park would strand the whole run above the viewport.
        assertTrue(firstUnreadWinsEntry(savedIndex = 40, firstUnreadIndex = 198, 48))
        // One row deeper than the watermark is already unseen; equality is not.
        assertTrue(firstUnreadWinsEntry(savedIndex = 40, firstUnreadIndex = 49, 48))
        assertFalse(firstUnreadWinsEntry(savedIndex = 40, firstUnreadIndex = 48, 48))
    }

    @Test
    fun `no watermark falls back to the deeper anchor`() {
        // Nothing displayed yet this process: the previous depth-only rule, unchanged, including
        // its tie going to the unread target and its top placement.
        assertTrue(firstUnreadWinsEntry(savedIndex = 1, firstUnreadIndex = 4, null))
        assertTrue(firstUnreadWinsEntry(savedIndex = 4, firstUnreadIndex = 4, null))
        assertFalse(firstUnreadWinsEntry(savedIndex = 4, firstUnreadIndex = 1, null))
        // A watermark shallower than the park cannot happen (a park was displayed), but if it ever
        // did, the depth rule still holds and a park is never skipped past.
        assertFalse(firstUnreadWinsEntry(savedIndex = 400, firstUnreadIndex = 300, 10))
    }

    @Test
    fun `entry target and pager key resolve the same anchor`() {
        val saved = ChatPositionTarget(index = 100, fromSavedPosition = true)
        val unread = ChatPositionTarget(index = 300, placeAtTop = true)

        // Forward reader: the target is the park, so the Pager must be keyed at the park. Keying at
        // the deeper anchor instead would push the chosen target out of the initial window.
        assertEquals(saved, preferredEntryTarget(saved, unread, furthestDisplayedIndex = 300))
        assertEquals(100, preferredEntryIndex(100, 300, 300))
        // Backfilled unread: both switch together.
        assertEquals(unread, preferredEntryTarget(saved, unread, furthestDisplayedIndex = 48))
        assertEquals(300, preferredEntryIndex(100, 300, 48))
        // One candidate only: the watermark cannot veto the sole anchor either way.
        assertEquals(unread, preferredEntryTarget(null, unread, furthestDisplayedIndex = 300))
        assertEquals(saved, preferredEntryTarget(saved, null, furthestDisplayedIndex = 300))
        assertNull(preferredEntryTarget(null, null, furthestDisplayedIndex = 300))
        assertNull(preferredEntryIndex(null, null, 300))
    }

    @Test
    fun `the displayed watermark never claims a row the reader could not read`() {
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec(fools = setOf("troll")))
        val rows = listOf(
            message(id = 40, serverTime = 5_000),
            message(id = 30, serverTime = 4_000),
            message(id = 20, sender = "troll", serverTime = 3_000),
            message(id = 10, serverTime = 1_000),
        )
        val peek = { index: Int -> rows.getOrNull(index) }

        // Reverse layout: the deepest visible index is the row at the TOP of the window.
        assertEquals(TimelineAnchor(1_000, 10, 10), displayedDepthAnchor(3, rows.size, peek, policy))
        // A hidden fool at the top edge was never presented, so the watermark stops at the newest
        // row that was — walking older instead would claim rows below the window as seen.
        assertEquals(TimelineAnchor(4_000, 30, 30), displayedDepthAnchor(2, rows.size, peek, policy))
        // A placeholder is a blank skeleton, not a row: fall back to the newest real row under it.
        assertEquals(
            TimelineAnchor(5_000, 40, 40),
            displayedDepthAnchor(2, rows.size, { index -> rows.getOrNull(index).takeIf { index == 0 } }, policy),
        )
        // Nothing laid out, and an index past the snapshot, report nothing rather than guessing.
        assertNull(displayedDepthAnchor(-1, rows.size, peek, policy))
        assertNull(displayedDepthAnchor(0, 0, peek, policy))
        assertEquals(TimelineAnchor(1_000, 10, 10), displayedDepthAnchor(99, rows.size, peek, policy))
    }

    @Test
    fun `lag tone thresholds bucket latency`() {
        assertEquals(LagTone.GOOD, lagTone(0))
        assertEquals(LagTone.GOOD, lagTone(299))
        assertEquals(LagTone.DEGRADED, lagTone(300))
        assertEquals(LagTone.DEGRADED, lagTone(1_499))
        assertEquals(LagTone.BAD, lagTone(1_500))
        assertEquals(LagTone.BAD, lagTone(60_000))
    }
}
