package io.github.trevarj.motd

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.VoiceSendRequest
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.e2e.BootstrappedNetwork
import io.github.trevarj.motd.e2e.BufferProbe
import io.github.trevarj.motd.e2e.ConnectionProbe
import io.github.trevarj.motd.e2e.E2eBootstrap
import io.github.trevarj.motd.e2e.E2eFailureArtifactRule
import io.github.trevarj.motd.e2e.E2eMilestoneRecorder
import io.github.trevarj.motd.e2e.FixtureIrcClient
import io.github.trevarj.motd.e2e.HistorySyncProbe
import io.github.trevarj.motd.e2e.MessageLifecycleProbe
import io.github.trevarj.motd.e2e.MessageRunProbe
import io.github.trevarj.motd.e2e.ScenarioHolder
import io.github.trevarj.motd.e2e.robots.BouncerRobot
import io.github.trevarj.motd.e2e.robots.ChatListRobot
import io.github.trevarj.motd.e2e.robots.ChatRobot
import io.github.trevarj.motd.e2e.robots.OnboardingRobot
import io.github.trevarj.motd.e2e.robots.SettingsRobot
import io.github.trevarj.motd.e2e.robots.ThemeSheetRobot
import io.github.trevarj.motd.e2e.robots.TimelineRobot
import io.github.trevarj.motd.e2e.robots.NetworksRobot
import io.github.trevarj.motd.service.MotdNotifications
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Marks the real-stack, isolated journeys required by the headless API34 gate. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FastHeadlessE2e

@RunWith(AndroidJUnit4::class)
@FastHeadlessE2e
class RequiredHeadlessE2eTest {
    private val milestones = E2eMilestoneRecorder()
    private val scenario = ScenarioHolder()
    private val artifacts = E2eFailureArtifactRule(scenario, milestones)
    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS))
        .around(artifacts)
        .around(compose)

    private fun launchBootstrapped(requiredCaps: Set<String> = emptySet()): Pair<E2eBootstrap, BootstrappedNetwork> {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val network = runBlocking { bootstrap.connectedSojuNetwork() }
        val probe = ConnectionProbe(bootstrap.seams.connections(), milestones)
        runBlocking {
            probe.awaitReady(network.rootId, emptySet())
            probe.awaitReady(network.childId, requiredCaps)
        }
        scenario.launch()
        return bootstrap to network
    }

    @Test
    fun onboardingTrustsEphemeralTlsAndImportsNetwork() {
        val bootstrap = E2eBootstrap.fromApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        scenario.launch()
        OnboardingRobot(compose).importSoju(bootstrap.args)
        val rows = runBlocking { bootstrap.seams.networks().observeNetworks().first() }
        val root = rows.single { it.role == NetworkRole.BOUNCER_ROOT }
        val child = rows.single {
            it.role == NetworkRole.BOUNCER_CHILD && it.parentId == root.id &&
                it.name == "libera" && !it.bouncerNetId.isNullOrBlank()
        }
        runBlocking { ConnectionProbe(bootstrap.seams.connections(), milestones).awaitReady(child.id, emptySet()) }
        assertTrue(runBlocking { bootstrap.seams.certTrust().isPinned(bootstrap.args.host, bootstrap.args.port, bootstrap.args.fingerprint) })
        compose.onAllNodesWithTag("cert_trust_dialog", useUnmergedTree = true).assertCountEquals(0)
        milestones.record("onboarding_imported", "root=${root.id} child=${child.id}")
    }

    @Test
    fun sendEchoPersistsVisibleRowAndReconnects() {
        val (bootstrap, network) = launchBootstrapped(
            setOf(
                "echo-message",
                "draft/chathistory",
                "batch",
                "message-tags",
                "server-time",
            ),
        )
        val bufferId = runBlocking { BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel) }
        ChatListRobot(compose).open(bufferId)
        val token = "required${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(16)}"
        val probe = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val canonical = runBlocking {
            coroutineScope {
                val observed = async(start = CoroutineStart.UNDISPATCHED) { probe.awaitCanonical(token, bufferId) }
                ChatRobot(compose).send(token)
                observed.await()
            }
        }
        TimelineRobot(compose).assertMessage(token)
        runBlocking {
            bootstrap.seams.connections().disconnect(network.childId)
            bootstrap.seams.connections().connect(network.childId)
            ConnectionProbe(bootstrap.seams.connections(), milestones).awaitReady(
                network.childId,
                setOf(
                    "echo-message",
                    "draft/chathistory",
                    "batch",
                    "message-tags",
                    "server-time",
                ),
            )
        }
        val after = runBlocking { probe.awaitCanonical(token, bufferId) }
        assertEquals(canonical.id, after.id)
        TimelineRobot(compose).assertMessage(token)

        val fixture = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "required-${bootstrap.args.runId}.ogg",
        )
        fixture.delete()
        assertTrue(fixture.createNewFile())
        try {
            val upload = runBlocking {
                bootstrap.seams.voiceMessages().send(
                    VoiceSendRequest(
                        bufferId = bufferId,
                        file = fixture,
                        durationMs = 1_000,
                        mimeType = "audio/ogg",
                        extension = ".ogg",
                        sizeBytes = 0,
                        encrypt = false,
                    ),
                ).filterIsInstance<VoiceSendProgress.Complete>().first()
            }
            val voice = runBlocking { probe.awaitCanonicalContaining("voice", upload.url, bufferId) }
            TimelineRobot(compose).assertCompactAudioPlayer(voice.tag())
            milestones.record("filehost_audio_rendered", "buffer=$bufferId")
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun unreadHistoryEntersAtMarkerAndRemainsCanonical() {
        val (bootstrap, network) = launchBootstrapped(
            setOf("draft/chathistory", "draft/read-marker", "batch", "message-tags", "server-time"),
        )
        val connectionProbe = ConnectionProbe(bootstrap.seams.connections(), milestones)
        val bufferId = runBlocking {
            BufferProbe(bootstrap.seams.buffers(), milestones).awaitJoinedChannel(network.childId, bootstrap.args.channel)
        }
        val token = "unread${bootstrap.args.runId.filter(Char::isLetterOrDigit).takeLast(14)}"
        val lifecycle = MessageLifecycleProbe(bootstrap.seams.search(), milestones)
        val runProbe = MessageRunProbe(bootstrap.seams.search(), milestones)

        val marker = FixtureIrcClient.connect(bootstrap.args).use { fixture ->
            fixture.sendMessage(bootstrap.args.channel, "$token marker")
            fixture.flushThroughServer("${token}marker")
            runBlocking { lifecycle.awaitCanonicalFromAnySender("$token marker", bufferId) }
        }
        val markerAnchor = TimelineAnchor(marker.serverTime, marker.id, marker.timelineOrder)
        runBlocking {
            bootstrap.seams.connections().markRead(bufferId, markerAnchor)
            awaitMarkerAtLeast(bootstrap, bufferId, markerAnchor, requireRemote = true)
            bootstrap.seams.connections().disconnect(network.childId)
            connectionProbe.awaitDisconnected(network.childId)
            awaitWallClockAfter(markerAnchor.serverTime)
        }

        FixtureIrcClient.connect(bootstrap.args).use { fixture ->
            (1..260).forEach { ordinal ->
                fixture.sendMessage(bootstrap.args.channel, "$token row${ordinal.toString().padStart(3, '0')}")
            }
            fixture.flushThroughServer("${token}gap")
        }
        runBlocking {
            coroutineScope {
                val historySettled = async(start = CoroutineStart.UNDISPATCHED) {
                    HistorySyncProbe(bootstrap.seams.history(), milestones).awaitCycle(bufferId)
                }
                bootstrap.seams.connections().connect(network.childId)
                connectionProbe.awaitReady(
                    network.childId,
                    setOf("draft/chathistory", "draft/read-marker", "batch", "message-tags", "server-time"),
                )
                historySettled.await()
            }
        }
        // CHATHISTORY caps primary events at 150; chat-only search omits replayed state events.
        val recentWindow = runBlocking {
            runProbe.awaitRecentRows(
                token = token,
                bufferId = bufferId,
                minimumCount = 49,
                maximumCount = 49,
                expectedNewestOrdinal = 260,
                requiredText = "$token row260",
                excludedText = "$token row001",
            )
        }
        val newest = recentWindow.single { it.text == "$token row260" }
        assertTrue(recentWindow.none { it.text == "$token row001" })
        assertMarkerAtLeast(bootstrap, bufferId, marker)
        val roomBeforeEntry = runBlocking {
            bootstrap.seams.buffers().observeBuffer(bufferId).first { it != null }
        }
        assertEquals(markerAnchor.serverTime, roomBeforeEntry?.localReadAnchorTime)
        assertEquals(Long.MAX_VALUE, roomBeforeEntry?.localReadAnchorEventId)
        val listBeforeEntry = runBlocking {
            withTimeout(10_000) {
                bootstrap.seams.buffers().observeChatList().first { rows ->
                    rows.singleOrNull { it.bufferId == bufferId }?.let { row ->
                        row.unreadCount == 49 && row.unreadCountIncomplete
                    } == true
                }
            }
        }
        val boundedRow = listBeforeEntry.single { it.bufferId == bufferId }
        assertEquals(49, boundedRow.unreadCount)
        assertTrue(boundedRow.unreadCountIncomplete)

        ChatListRobot(compose).open(bufferId)
        val firstUnread = runBlocking {
            lifecycle.awaitCanonicalFromAnySender("$token row001", bufferId)
        }
        val secondUnread = runBlocking {
            lifecycle.awaitCanonicalFromAnySender("$token row002", bufferId)
        }
        assertTrue(markerAnchor.serverTime < firstUnread.serverTime)
        assertTrue(markerAnchor < firstUnread.anchor())
        assertTrue(firstUnread.anchor() < newest.anchor())
        val timeline = TimelineRobot(compose)
        timeline.assertUnreadEntry(firstUnread.tag(), secondUnread.tag())
        compose.waitForIdle()
        assertMarkerAtLeast(bootstrap, bufferId, marker)

        // Reopening before marking read must reproduce the same frozen divider and viewport.
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ChatListRobot(compose).apply { awaitTag("chatlist_row_$bufferId"); open(bufferId) }
        timeline.assertUnreadEntry(firstUnread.tag(), secondUnread.tag())
        assertMarkerAtLeast(bootstrap, bufferId, marker)

        timeline.scrollToBottom()
        runBlocking {
            awaitMarkerAtLeast(bootstrap, bufferId, newest.anchor())
        }
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        ChatListRobot(compose).apply { awaitTag("chatlist_row_$bufferId"); open(bufferId) }
        timeline.assertNoUnreadDivider()
        timeline.assertMessage("$token row260")

        scenario.scenario?.onActivity { activity ->
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                activity,
                Intent(activity, MainActivity::class.java)
                    .setAction(MotdNotifications.ACTION_OPEN_BUFFER)
                    .putExtra(MotdNotifications.EXTRA_BUFFER_ID, bufferId)
                    .putExtra(MotdNotifications.EXTRA_JUMP_MSGID, firstUnread.msgid)
                    .putExtra(MotdNotifications.EXTRA_JUMP_TIME, firstUnread.serverTime)
                    .putExtra(MotdNotifications.EXTRA_EVENT_ID, firstUnread.id),
            )
        }
        timeline.assertMessageVisible(firstUnread.tag())
        scenario.scenario?.onActivity { it.recreate() }
        timeline.assertMessageVisible(firstUnread.tag())
        // Directional paging restores older rows; search then exposes its exact newest-200 cap.
        runBlocking {
            runProbe.awaitRows(
                token = token,
                bufferId = bufferId,
                count = 200,
                expectedExtras = emptySet(),
                expectedNewestOrdinal = 260,
            )
        }
        milestones.record("notification_restore_stable", "buffer=$bufferId event=${firstUnread.id}")
    }

    @Test
    fun bootstrappedNavigationSettingsAndBouncerSmoke() {
        val (bootstrap, network) = launchBootstrapped()
        SettingsRobot(compose).apply {
            open()
            appearance()
        }
        ThemeSheetRobot(compose).selectAyuDarkAndTrueBlack()
        scenario.scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onAllNodesWithTag("settings_theme_sheet", useUnmergedTree = true).assertCountEquals(0)
        // Return from Appearance to Settings, then exercise the category and bouncer routes.
        SettingsRobot(compose).apply {
            returnToRoot()
            chat()
            assertDisplayed("settings_switch_show_jpq")
            returnToRoot()
            networks()
        }
        NetworksRobot(compose).openRoot(network.rootId)
        BouncerRobot(compose).assertPanels()
        milestones.record("settings_bouncer_smoke", "root=${network.rootId}")
    }

    private suspend fun awaitMarkerAtLeast(
        bootstrap: E2eBootstrap,
        bufferId: Long,
        expected: TimelineAnchor,
        requireRemote: Boolean = false,
    ) {
        withTimeout(20_000) {
            bootstrap.seams.buffers().observeBuffer(bufferId).first { room ->
                room != null && markerAtLeast(room.localReadAnchorTime, room.localReadAnchorEventId, expected) &&
                    (!requireRemote || (room.readMarkerTime ?: Long.MIN_VALUE) >= expected.serverTime)
            }
        }
    }

    private fun assertMarkerAtLeast(
        bootstrap: E2eBootstrap,
        bufferId: Long,
        marker: io.github.trevarj.motd.data.db.MessageEntity,
    ) {
        val room = runBlocking { bootstrap.seams.buffers().observeBuffer(bufferId).first { it != null } }
        assertTrue(markerAtLeast(room?.localReadAnchorTime, room?.localReadAnchorEventId, marker.anchor()))
    }

    private fun markerAtLeast(time: Long?, eventId: Long?, expected: TimelineAnchor): Boolean =
        time != null && eventId != null &&
            (time > expected.serverTime || (time == expected.serverTime && eventId >= expected.eventId))

    private suspend fun awaitWallClockAfter(serverTime: Long) {
        // IRC read markers are timestamp-only and therefore include every message in the same
        // millisecond. Keep the fixture's unread burst outside that intentionally inclusive tie.
        withTimeout(5_000) {
            while (System.currentTimeMillis() <= serverTime) delay(1)
        }
    }

    private fun io.github.trevarj.motd.data.db.MessageEntity.anchor(): TimelineAnchor =
        TimelineAnchor(serverTime, id, timelineOrder)

    private fun io.github.trevarj.motd.data.db.MessageEntity.tag(): String = "chat_message_${msgid ?: id}"
}
