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
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
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
    fun `focused one-row island shows newest escape even at its local bottom`() {
        assertTrue(
            shouldShowNewestFab(
                atBottom = true,
                hasNewerHistoryIsland = true,
                autoScrolling = false,
            ),
        )
        assertFalse(shouldShowNewestFab(true, false, false))
        assertFalse(shouldShowNewestFab(true, true, true))
    }

    @Test
    fun `viewport acknowledgement requires the conversation bottom, not the island bottom`() {
        // The one behavior change: a bounded older island never acknowledges, even at its bottom.
        assertFalse(
            shouldMarkReadFromViewport(
                atBottom = true,
                hasNewerHistoryIsland = true,
                initialPositionSettled = true,
                viewportReadEnabled = true,
            ),
        )
        // Unbounded window at bottom still acknowledges exactly as before.
        assertTrue(
            shouldMarkReadFromViewport(
                atBottom = true,
                hasNewerHistoryIsland = false,
                initialPositionSettled = true,
                viewportReadEnabled = true,
            ),
        )
        // Every pre-existing precondition still gates on its own.
        assertFalse(shouldMarkReadFromViewport(false, false, true, true))
        assertFalse(shouldMarkReadFromViewport(true, false, false, true))
        assertFalse(shouldMarkReadFromViewport(true, false, true, false))
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

    // --- history gap autopilot --------------------------------------------------------------------

    private fun seam(gapId: Long, serverTime: Long, recoverable: Boolean = true) =
        io.github.trevarj.motd.data.history.TimelineSeam(
            gapId = gapId,
            position = TimelineAnchor(serverTime, gapId, gapId),
            recoverable = recoverable,
        )

    private val ready = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100)

    @Test
    fun `autopilot arms once for the newest recoverable seam`() {
        val autopilot = HistoryGapAutopilot()
        // Seams arrive oldest-first, so the newest recoverable one is the last: an older seam that
        // happens to be listed after it must not win.
        val seams = listOf(seam(gapId = 1, serverTime = 100), seam(gapId = 2, serverTime = 900))

        val armed = autopilot.arm(roomId = 7, visibleSession = 1, availability = ready, entrySettled = true, seams = seams)

        assertEquals(GapAutopilotArming(7, 2, TimelineAnchor(900, 2, 2)), armed)
        // The same seam list is republished on every fill-progress emission. Re-arming there would
        // turn the coordinator's bounded fill into an unbounded loop.
        assertNull(autopilot.arm(7, 1, ready, true, seams))
    }

    @Test
    fun `autopilot does not re-arm for a seam its own fill receded`() {
        val autopilot = HistoryGapAutopilot()
        autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 900)))

        // A budgeted fill leaves the gap open with its newer edge moved OLDER. The rest of that gap
        // is the user's to ask for, via the divider that is still on screen.
        assertNull(autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 600))))
    }

    @Test
    fun `autopilot does not chase an older seam promoted by closing the newest one`() {
        val autopilot = HistoryGapAutopilot()
        autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 1, serverTime = 100), seam(gapId = 2, serverTime = 900)))

        // Gap 2 closed, so gap 1 is now "newest recoverable". It is old history nobody asked for;
        // fetching it unprompted is the regression the budget and this rule exist to prevent.
        assertNull(autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 1, serverTime = 100))))
    }

    @Test
    fun `autopilot arms again for a genuinely newer seam`() {
        val autopilot = HistoryGapAutopilot()
        autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 900)))

        // A second reconnect always lands its catch-up gap at the newest end, which is exactly what
        // this rule lets through and nothing else.
        val armed = autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 900), seam(gapId = 3, serverTime = 5_000)))

        assertEquals(3L, armed?.gapId)
    }

    @Test
    fun `autopilot is gated on visibility, a ready transport, and a settled entry`() {
        val autopilot = HistoryGapAutopilot()
        val seams = listOf(seam(gapId = 2, serverTime = 900))

        assertNull("not on screen", autopilot.arm(7, null, ready, true, seams))
        assertNull(
            "no transport to page against",
            autopilot.arm(7, 1, HistoryAvailability.NegotiatingOrOffline, true, seams),
        )
        assertNull("history unsupported", autopilot.arm(7, 1, HistoryAvailability.Unsupported, true, seams))
        // Entry freezes the unread boundary from the store, so a fill that lands first would move
        // the divider onto rows the autopilot itself had just fetched.
        assertNull("entry has not resolved yet", autopilot.arm(7, 1, ready, false, seams))
        // None of the three consumed the seam: the gate is not a latch, so a room opened before its
        // connection settles — or before its entry positions — still catches up afterwards.
        assertEquals(2L, autopilot.arm(7, 1, ready, true, seams)?.gapId)
    }

    @Test
    fun `autopilot ignores an unrecoverable seam`() {
        val autopilot = HistoryGapAutopilot()

        // Nothing left to fetch: a fill would cost a classification and change nothing.
        assertNull(autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 4, serverTime = 900, recoverable = false))))
        // ...and refusing it is not the same as consuming it: the real seam still arms.
        assertEquals(2L, autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 900)))?.gapId)
    }

    @Test
    fun `autopilot does not re-arm across a pause and resume`() {
        val autopilot = HistoryGapAutopilot()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        assertEquals(2L, autopilot.arm(7, 1, ready, true, seams)?.gapId)

        // Backgrounding and resuming is not new information about history. Spending another budget
        // on the same seam because the screen came back would make the visible divider a lie: it
        // says the user decides how much more to fetch.
        assertNull(autopilot.arm(7, null, ready, true, seams))
        assertNull(autopilot.arm(7, 2, ready, true, seams))
        // A reconnect gap arriving while it was away still arms, because it is genuinely newer.
        assertEquals(
            3L,
            autopilot.arm(7, 2, ready, true, seams + seam(gapId = 3, serverTime = 5_000))?.gapId,
        )
    }

    @Test
    fun `a stalled fill does not spend the arming`() {
        val autopilot = HistoryGapAutopilot()
        val seams = listOf(seam(gapId = 2, serverTime = 900))
        val armed = checkNotNull(autopilot.arm(7, 1, ready, true, seams))

        // The fill inserted nothing and its boundary did not move: the seam is still open, still
        // recoverable, and still exactly where it was. Treating that as exhaustion retires
        // hands-free catch-up for the rest of the visit, because no later seam is ever newer.
        autopilot.releaseStalled(armed)

        assertEquals(2L, autopilot.arm(7, 1, ready, true, seams)?.gapId)
    }

    @Test
    fun `a released arming re-arms for the receded seam the contending fetch left`() {
        val autopilot = HistoryGapAutopilot()
        val armed = checkNotNull(autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 900))))
        autopilot.releaseStalled(armed)

        // The whole point: the other fetch's rows moved the seam OLDER, which the strictly-newer
        // rule rejects forever. Releasing rewinds the watermark to what it was before the wasted
        // arming, so the seam that actually needs filling is reachable again.
        assertEquals(2L, autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 600)))?.gapId)
    }

    @Test
    fun `releasing is bounded so contention cannot become a retry loop`() {
        val autopilot = HistoryGapAutopilot()
        val seams = listOf(seam(gapId = 2, serverTime = 900))

        repeat(HistoryGapAutopilot.RELEASE_BUDGET) {
            autopilot.releaseStalled(checkNotNull(autopilot.arm(7, 1, ready, true, seams)))
        }
        val last = checkNotNull(autopilot.arm(7, 1, ready, true, seams))
        autopilot.releaseStalled(last)

        // The budget is for the life of this instance — one room visit — and nothing resets it, so
        // the hands-free fills a visit can start is capped at 1 + RELEASE_BUDGET.
        assertNull(autopilot.arm(7, 1, ready, true, seams))
    }

    @Test
    fun `releasing a superseded arming leaves the newer one spent`() {
        val autopilot = HistoryGapAutopilot()
        val stale = checkNotNull(autopilot.arm(7, 1, ready, true, listOf(seam(gapId = 2, serverTime = 900))))
        val newer = listOf(seam(gapId = 2, serverTime = 900), seam(gapId = 3, serverTime = 5_000))
        assertEquals(3L, autopilot.arm(7, 1, ready, true, newer)?.gapId)

        // A second reconnect armed while the first fill was still running. Its arming is legitimately
        // spent, and the stale release must not resurrect the seam beneath it.
        autopilot.releaseStalled(stale)

        assertNull(autopilot.arm(7, 1, ready, true, newer))
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
