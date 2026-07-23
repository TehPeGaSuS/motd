package io.github.trevarj.motd.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.ConnectionBanner
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.MotdMotion
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Stateful entry: wires the ViewModel and drives navigation/empty-state. */
@Composable
fun ChatListScreen(
    onOpenBuffer: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    // Round 5 (plans/16): drawer/network-management pass-throughs.
    onOpenNetworkSettings: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onOpenChannelList: (Long) -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // No networks configured -> jump straight into onboarding (once loaded).
    LaunchedEffect(state.loading, state.networks.isEmpty()) {
        if (!state.loading && state.networks.isEmpty()) {
            onOpenOnboarding()
        }
    }

    ChatListContent(
        state = state,
        onOpenBuffer = onOpenBuffer,
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onSetArchived = viewModel::setArchived,
        onDeleteBuffer = viewModel::deleteBuffer,
        onJoinChannel = viewModel::joinChannel,
        onMessageUser = { networkId, nick -> viewModel.messageUser(networkId, nick, onOpenBuffer) },
        // Round 5: drawer selection + connectivity + nav.
        onSelectNetwork = viewModel::selectNetwork,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onGoOffline = viewModel::goOffline,
        onGoOnline = viewModel::goOnline,
        onServerMessages = { networkId -> viewModel.openServerBuffer(networkId, onOpenBuffer) },
        onOpenNetworkSettings = onOpenNetworkSettings,
        onOpenAddNetwork = onOpenAddNetwork,
        onOpenChannelList = onOpenChannelList,
        onMarkAllRead = viewModel::markCurrentScopeRead,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListContent(
    state: ChatListState,
    onOpenBuffer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit = { _, _ -> },
    onJoinChannel: (Long, String) -> Unit,
    onMessageUser: (Long, String) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit = {},
    // Round 5 (plans/16 §3): drawer + scoping. Defaulted so previews stay terse.
    onSelectNetwork: (Long?) -> Unit = {},
    onConnect: (Long) -> Unit = {},
    onDisconnect: (Long) -> Unit = {},
    onGoOffline: () -> Unit = {},
    onGoOnline: () -> Unit = {},
    onServerMessages: (Long) -> Unit = {},
    onOpenNetworkSettings: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onOpenChannelList: (Long) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
) {
    var archiveMode by rememberSaveable { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showMarkAllReadDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // The per-row network tag is redundant once the list is scoped to one network.
    val showNetworkChip = state.networks.size > 1 && state.selectedNetworkId == null

    // Close the drawer with Back when it's open (before popping the back stack).
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = archiveMode) { archiveMode = false }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ServerDrawerContent(
                drawerRows = state.drawerRows,
                selectedNetworkId = state.selectedNetworkId,
                allUnread = state.allUnread,
                allMentions = state.allMentions,
                scopedUnreadCount = state.scopedUnreadCount,
                allOffline = state.allOffline,
                onSelectNetwork = { id ->
                    onSelectNetwork(id)
                    scope.launch { drawerState.close() }
                },
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onServerMessages = { id ->
                    onServerMessages(id)
                    scope.launch { drawerState.close() }
                },
                onOpenNetworkSettings = { id ->
                    onOpenNetworkSettings(id)
                    scope.launch { drawerState.close() }
                },
                onAddNetwork = {
                    onOpenAddNetwork()
                    scope.launch { drawerState.close() }
                },
                onToggleOffline = { if (state.allOffline) onGoOnline() else onGoOffline() },
                onOpenSettings = {
                    onOpenSettings()
                    scope.launch { drawerState.close() }
                },
                onMarkAllRead = {
                    scope.launch { drawerState.close() }
                    showMarkAllReadDialog = true
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.testTag("screen_chat_list"),
            topBar = {
                TopAppBar(
                    title = {
                        val scopedName = state.selectedNetworkName
                        if (archiveMode) {
                            Text(text = stringResource(R.string.chatlist_archived_chats), fontWeight = FontWeight.Bold)
                        } else if (scopedName != null) {
                            // Scoped: show the network name so the active filter is legible.
                            Text(text = scopedName, fontWeight = FontWeight.Bold)
                        } else {
                            // Unscoped: brand the bar with the /motd wordmark. Tinted onSurface so
                            // it reads on every theme (including terminal); never hardcode periwinkle.
                            Icon(
                                painter = painterResource(R.drawable.motd_wordmark),
                                contentDescription = stringResource(R.string.app_name),
                                tint = MaterialTheme.colorScheme.onSurface,
                                // Keep the thin stroked lettering crisp without crowding the app bar.
                                // 330:100 source ratio, rendered at an exact 3.25:1 ratio.
                                modifier = Modifier.height(24.dp).width(78.dp),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (archiveMode) archiveMode = false else scope.launch { drawerState.open() } }) {
                            Icon(
                                if (archiveMode) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Menu,
                                contentDescription = stringResource(
                                    if (archiveMode) R.string.action_back else R.string.drawer_open,
                                ),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.chatlist_search),
                            )
                        }
                        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("chatlist_open_settings")) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.chatlist_settings),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showSheet = true }, modifier = Modifier.testTag("chatlist_new_conversation")) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chatlist_new_conversation),
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                ConnectionBanner(
                    states = state.connection,
                    networkName = { id -> state.networks.firstOrNull { it.id == id }?.name },
                )

                // Active-scope chip: keeps the filter discoverable/escapable without the drawer.
                if (state.selectedNetworkId != null) {
                    ScopeChip(
                        name = state.selectedNetworkName.orEmpty(),
                        onClear = { onSelectNetwork(null) },
                    )
                }

                val visibleRows = if (archiveMode) state.archivedRows else state.rows
                if (!shouldRenderChatList(archiveMode, state.rows, state.archivedRows) &&
                    !state.loading && state.networks.isNotEmpty()
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Forum,
                        title = stringResource(
                            if (state.selectedNetworkId != null) {
                                R.string.chatlist_scoped_empty_title
                            } else {
                                R.string.chatlist_empty_title
                            },
                        ),
                        message = stringResource(
                            if (state.selectedNetworkId != null) {
                                R.string.chatlist_scoped_empty_message
                            } else {
                                R.string.chatlist_empty_message
                            },
                        ),
                    )
                } else {
                    ChatList(
                        rows = visibleRows,
                        archivedRows = state.archivedRows,
                        archiveMode = archiveMode,
                        onOpenArchive = { archiveMode = true },
                        presence = state.queryPresence,
                        friends = state.friends,
                        fools = state.fools,
                        multiNetwork = showNetworkChip,
                        onOpenBuffer = onOpenBuffer,
                        onSetPinned = onSetPinned,
                        onSetMuted = onSetMuted,
                        onSetArchived = onSetArchived,
                        onDeleteBuffer = onDeleteBuffer,
                    )
                }
            }
        }
    }

    if (showSheet) {
        NewConversationSheet(
            networks = state.networks,
            preselectedNetworkId = state.selectedNetworkId,
            sheetState = sheetState,
            onDismiss = { showSheet = false },
            onJoinChannel = { networkId, channel ->
                onJoinChannel(networkId, channel)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
            onMessageUser = { networkId, nick ->
                onMessageUser(networkId, nick)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
            onBrowseChannels = { networkId ->
                onOpenChannelList(networkId)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
        )
    }

    if (showMarkAllReadDialog) {
        val networkName = state.selectedNetworkName
        AlertDialog(
            onDismissRequest = { showMarkAllReadDialog = false },
            title = {
                Text(
                    if (networkName == null) {
                        stringResource(R.string.mark_all_read_dialog_title_all)
                    } else {
                        stringResource(R.string.mark_all_read_dialog_title_network, networkName)
                    },
                )
            },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.mark_all_read_dialog_message,
                        state.scopedUnreadCount,
                        state.scopedUnreadCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMarkAllReadDialog = false
                        onMarkAllRead()
                    },
                    modifier = Modifier.testTag("drawer_mark_all_read_confirm"),
                ) {
                    Text(stringResource(R.string.mark_all_read_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMarkAllReadDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeChip(name: String, onClear: () -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilterChip(
            selected = true,
            onClick = onClear,
            label = { Text(name) },
            trailingIcon = {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chatlist_scope_clear),
                )
            },
        )
    }
}

/** Chat-list rows fade for inserts/removals but snap when activity changes their position. */
internal object ChatListItemMotion {
    val fadeInSpec: FiniteAnimationSpec<Float> = MotdMotion.microFadeIn
    val fadeOutSpec: FiniteAnimationSpec<Float> = MotdMotion.microFadeOut
    val placementSpec: FiniteAnimationSpec<IntOffset>? = null
}

@Composable
private fun ChatList(
    rows: List<ChatListRow>,
    archivedRows: List<ChatListRow>,
    archiveMode: Boolean,
    onOpenArchive: () -> Unit,
    presence: Map<Long, io.github.trevarj.motd.service.PresenceState>,
    friends: Set<String>,
    fools: Set<String>,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit,
) {
    // Pinning has global priority, then unpinned friends, recent chats, and collapsed fools.
    // Pin state remains an inline row marker; it does not add a visible section header.
    val sections = sectionChatList(rows, friends, fools)
    // Fools section is collapsed by default; state is local to the screen (accepted, plans/13).
    var foolsExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hasActiveRows = rows.isNotEmpty()
    val hasArchivedRows = archivedRows.isNotEmpty()
    val archiveFolderHeight = 56.dp
    val archiveFolderGeometry = ArchiveFolderPullGeometry(with(LocalDensity.current) { archiveFolderHeight.toPx() })
    val archiveFolderPullEligible = !archiveMode && hasActiveRows && hasArchivedRows
    val archivedOnly = !archiveMode && !hasActiveRows && hasArchivedRows
    var archivePullState by remember { mutableStateOf(ArchiveFolderPullState()) }
    var archiveDisplayExposurePx by remember { mutableFloatStateOf(0f) }
    var archiveSettling by remember { mutableStateOf(false) }
    var archiveSettleJob by remember { mutableStateOf<Job?>(null) }
    val view = LocalView.current

    fun dispatchArchiveEvent(event: ArchiveFolderPullEvent): ArchiveFolderPullResult {
        val result = reduceArchiveFolderPull(archivePullState, event, archiveFolderGeometry)
        archivePullState = result.state
        result.effects.forEach { effect ->
            when (effect) {
                ArchiveFolderPullEffect.HapticThresholdActivated -> view.performArchiveThresholdHaptic()
                ArchiveFolderPullEffect.AnnounceShown -> view.announceForAccessibility(view.context.getString(R.string.chatlist_archived_revealed_announcement))
                ArchiveFolderPullEffect.AnnounceHidden -> view.announceForAccessibility(view.context.getString(R.string.chatlist_archived_hidden_announcement))
            }
        }
        return result
    }

    fun settleArchivePull(targetPx: Float) {
        archiveSettleJob?.cancel()
        if (!archiveFolderGeometry.isValid || targetPx == archiveDisplayExposurePx) {
            archiveDisplayExposurePx = targetPx
            archiveSettling = false
            return
        }
        archiveSettling = true
        val remaining = abs(targetPx - archiveDisplayExposurePx) / archiveFolderGeometry.rowPx
        archiveSettleJob = scope.launch {
            animate(
                initialValue = archiveDisplayExposurePx,
                targetValue = targetPx,
                animationSpec = MotdMotion.archiveSettleSpec(remaining),
            ) { value, _ ->
                archiveDisplayExposurePx = value
            }
            archiveDisplayExposurePx = targetPx
            archiveSettling = false
            archiveSettleJob = null
        }
    }

    fun applyArchiveFolderPull(deltaY: Float, atTop: Boolean): Float {
        archiveSettleJob?.cancel()
        archiveSettleJob = null
        archiveSettling = false
        val result = dispatchArchiveEvent(
            ArchiveFolderPullEvent.DragDelta(deltaY, SystemClock.uptimeMillis(), ArchiveFolderPullSource.USER_INPUT, atTop),
        )
        archiveDisplayExposurePx = result.state.exposurePx
        return result.consumedY
    }

    LaunchedEffect(archiveFolderPullEligible) {
        if (!archiveFolderPullEligible) {
            archiveSettleJob?.cancel()
            archiveSettleJob = null
            archiveSettling = false
            archiveDisplayExposurePx = 0f
            dispatchArchiveEvent(ArchiveFolderPullEvent.Reset)
        }
    }

    LaunchedEffect(archivePullState.gestureActive, archivePullState.phase, archivePullState.dwellStartedAtMs, archivePullState.exposurePx) {
        val dwellStart = archivePullState.dwellStartedAtMs
        if (archivePullState.gestureActive && archivePullState.phase != ArchiveFolderPullPhase.ARMED && dwellStart != null) {
            delay((ArchiveFolderPull.DwellMillis - (SystemClock.uptimeMillis() - dwellStart)).coerceAtLeast(0L))
            val result = dispatchArchiveEvent(ArchiveFolderPullEvent.Tick(SystemClock.uptimeMillis()))
            archiveDisplayExposurePx = result.state.exposurePx
        }
    }

    DisposableEffect(Unit) { onDispose { archiveSettleJob?.cancel() } }

    val archiveFolderPullConnection = remember(archiveFolderPullEligible, archiveSettling) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !archiveFolderPullEligible || archiveSettling) return Offset.Zero
                if (available.y < 0f && archivePullState.phase == ArchiveFolderPullPhase.REVEALED) {
                    val result = scrollRevealedArchiveFolder(
                        archiveDisplayExposurePx,
                        available.y,
                        archiveFolderGeometry,
                    )
                    archiveDisplayExposurePx = result.exposurePx
                    if (result.hidden) dispatchArchiveEvent(ArchiveFolderPullEvent.RevealedRowHidden)
                    return Offset(0f, result.consumedY)
                }
                return if (available.y < 0f && archivePullState.gestureActive && archiveDisplayExposurePx > 0f) {
                    Offset(0f, applyArchiveFolderPull(available.y, atTop = true))
                } else Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || !archiveFolderPullEligible || archiveSettling) return Offset.Zero
                if (available.y > 0f && !listState.canScrollBackward &&
                    archivePullState.phase == ArchiveFolderPullPhase.REVEALED
                ) {
                    val result = scrollRevealedArchiveFolder(
                        archiveDisplayExposurePx,
                        available.y,
                        archiveFolderGeometry,
                    )
                    archiveDisplayExposurePx = result.exposurePx
                    return Offset(0f, result.consumedY)
                }
                return if (available.y > 0f && !listState.canScrollBackward && archivePullState.gestureActive) {
                    Offset(0f, applyArchiveFolderPull(available.y, atTop = true))
                } else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity.Zero
        }
    }
    val currentDispatchArchiveEvent by rememberUpdatedState(::dispatchArchiveEvent)
    val revealArchiveActionLabel = stringResource(R.string.chatlist_archived_reveal_action)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .nestedScroll(archiveFolderPullConnection)
            .pointerInput(archiveFolderPullEligible) {
                if (!archiveFolderPullEligible) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val gestureStartedRevealed = archivePullState.phase == ArchiveFolderPullPhase.REVEALED
                    archiveSettleJob?.cancel()
                    archiveSettleJob = null
                    archiveSettling = false
                    currentDispatchArchiveEvent(ArchiveFolderPullEvent.StartGesture(SystemClock.uptimeMillis()))
                    // Observe release after children without treating LazyColumn drag consumption as
                    // cancellation. The observer never consumes input from scrolling or row taps.
                    var pointerEvent = awaitPointerEvent(PointerEventPass.Final)
                    while (pointerEvent.changes.any { it.pressed }) {
                        pointerEvent = awaitPointerEvent(PointerEventPass.Final)
                    }
                    if (gestureStartedRevealed) {
                        currentDispatchArchiveEvent(ArchiveFolderPullEvent.Cancel)
                    } else {
                        val event = if (pointerEvent.type == PointerEventType.Release) {
                            ArchiveFolderPullEvent.Release(SystemClock.uptimeMillis())
                        } else {
                            ArchiveFolderPullEvent.Cancel
                        }
                        val result = currentDispatchArchiveEvent(event)
                        settleArchivePull(archiveFolderPullSettleTarget(result.state, archiveFolderGeometry))
                    }
                }
            }
            .semantics {
                if (archiveFolderPullEligible && archivePullState.phase != ArchiveFolderPullPhase.REVEALED) {
                    customActions = listOf(
                        CustomAccessibilityAction(revealArchiveActionLabel) {
                            val result = dispatchArchiveEvent(ArchiveFolderPullEvent.RevealAccessibilityAction)
                            archiveDisplayExposurePx = result.state.exposurePx
                            true
                        },
                    )
                }
            }
            .testTag("chatlist_archive_pull_target"),
    ) {
        if (archivedOnly) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(archiveFolderHeight)
            ) {
                ArchivedChatsFolder(archivedRows.size, onOpenArchive)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = when {
                        archivedOnly -> archiveFolderGeometry.rowPx
                        archiveFolderPullEligible -> archiveDisplayExposurePx
                        else -> 0f
                    }
                },
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(sections.pinned, key = { it.bufferId }) { row ->
                RowWithMenu(
                    row,
                    presence[row.bufferId],
                    isFriend = isFriendQuery(row, friends),
                    multiNetwork,
                    onOpenBuffer,
                    onSetPinned,
                    onSetMuted,
                    onSetArchived,
                    archiveMode,
                    onDeleteBuffer,
                    modifier = Modifier.animateItem(
                        fadeInSpec = ChatListItemMotion.fadeInSpec,
                        fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                        placementSpec = ChatListItemMotion.placementSpec,
                    ),
                )
            }
            if (sections.friends.isNotEmpty()) {
                item(key = "friends-header") {
                    SectionHeader(stringResource(R.string.chatlist_friends))
                }
                items(sections.friends, key = { it.bufferId }) { row ->
                    RowWithMenu(
                        row,
                        presence[row.bufferId],
                        isFriend = true,
                        multiNetwork,
                        onOpenBuffer,
                        onSetPinned,
                        onSetMuted,
                        onSetArchived,
                        archiveMode,
                        onDeleteBuffer,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ChatListItemMotion.fadeInSpec,
                            fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                            placementSpec = ChatListItemMotion.placementSpec,
                        ),
                    )
                }
            }
            if (sections.showRecentHeader) {
                item(key = "recent-header") {
                    SectionHeader(stringResource(R.string.chatlist_recent))
                }
            }
            items(sections.regular, key = { it.bufferId }) { row ->
                RowWithMenu(
                    row,
                    presence[row.bufferId],
                    isFriend = false,
                    multiNetwork,
                    onOpenBuffer,
                    onSetPinned,
                    onSetMuted,
                    onSetArchived,
                    archiveMode,
                    onDeleteBuffer,
                    modifier = Modifier.animateItem(
                        fadeInSpec = ChatListItemMotion.fadeInSpec,
                        fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                        placementSpec = ChatListItemMotion.placementSpec,
                    ),
                )
            }
            if (sections.fools.isNotEmpty()) {
                item(key = "fools-header") {
                    FoolsSectionHeader(
                        count = sections.fools.size,
                        expanded = foolsExpanded,
                        onToggle = { foolsExpanded = !foolsExpanded },
                    )
                }
                if (foolsExpanded) {
                    items(sections.fools, key = { it.bufferId }) { row ->
                        Box(
                            modifier = Modifier
                                .alpha(0.55f)
                                .animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = ChatListItemMotion.placementSpec,
                                ),
                        ) {
                            RowWithMenu(row, presence[row.bufferId], isFriend = false, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted, onSetArchived, archiveMode, onDeleteBuffer)
                        }
                    }
                }
            }
            }

        if (archiveFolderPullEligible && archiveDisplayExposurePx > 0f) {
            val overlayModifier = Modifier
                .fillMaxWidth()
                .height(archiveFolderHeight)
                .graphicsLayer { translationY = archiveDisplayExposurePx - archiveFolderGeometry.rowPx }
            ArchiveFolderPullOverlay(
                phase = archivePullState.phase,
                exposurePx = archiveDisplayExposurePx,
                geometry = archiveFolderGeometry,
                archivedCount = archivedRows.size,
                onOpenArchive = onOpenArchive,
                modifier = overlayModifier,
            )
        }

        ViewportScrollToTopFab(
            listState = listState,
            sections = sections,
            foolsExpanded = foolsExpanded,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp),
        )
    }
}

@Composable
private fun ArchivedChatsFolder(
    count: Int,
    onOpenArchive: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenArchive)
            .testTag("chatlist_archived_folder")
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Archive, contentDescription = null, tint = contentColor)
        Text(
            text = stringResource(R.string.chatlist_archived_chats_count, count),
            modifier = Modifier.padding(start = 16.dp),
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}

@Composable
private fun ArchiveFolderPullOverlay(
    phase: ArchiveFolderPullPhase,
    exposurePx: Float,
    geometry: ArchiveFolderPullGeometry,
    archivedCount: Int,
    onOpenArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val armed = phase == ArchiveFolderPullPhase.ARMED
    val committed = phase == ArchiveFolderPullPhase.REVEALED
    val activeProgress by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = tween(durationMillis = 230, easing = FastOutLinearInEasing),
        label = "archive pull color",
    )
    val activeBackground = MaterialTheme.colorScheme.primaryContainer
    val pullBackground = MaterialTheme.colorScheme.background
    val inactiveContent = MaterialTheme.colorScheme.onSurface
    val activeContent = MaterialTheme.colorScheme.onPrimaryContainer
    val backgroundColor = lerp(pullBackground, activeBackground, activeProgress)
    val contentColor = lerp(inactiveContent, activeContent, activeProgress)
    val prompt = stringResource(
        if (armed) R.string.chatlist_archived_pull_armed else R.string.chatlist_archived_pull_hint,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (armed || committed) 1f else archiveFolderPullHintAlpha(exposurePx, geometry)
            }
            .background(backgroundColor),
    ) {
        if (committed) {
            // Keep rendering the same pull surface after release so the list never changes shape.
            ArchivedChatsFolder(
                count = archivedCount,
                onOpenArchive = onOpenArchive,
                contentColor = contentColor,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { stateDescription = prompt }
                    .testTag("chatlist_archived_pull_${phase.name.lowercase()}")
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(contentColor.copy(alpha = .14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = 180f * activeProgress },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chatlist_archived_pull_hint),
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.graphicsLayer {
                            alpha = 1f - activeProgress
                            translationY = -8.dp.toPx() * activeProgress
                            scaleX = 1f - .1f * activeProgress
                            scaleY = scaleX
                        },
                    )
                    Text(
                        text = stringResource(R.string.chatlist_archived_pull_armed),
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.graphicsLayer {
                            alpha = activeProgress
                            translationY = 8.dp.toPx() * (1f - activeProgress)
                            scaleX = .9f + .1f * activeProgress
                            scaleY = scaleX
                        },
                    )
                }
            }
        }
    }
}

/** Telegram deliberately bypasses disabled touch feedback for this explicit threshold latch. */
@Suppress("DEPRECATION")
private fun View.performArchiveThresholdHaptic() {
    performHapticFeedback(
        HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
    )
}

@Composable
private fun ViewportScrollToTopFab(
    listState: androidx.compose.foundation.lazy.LazyListState,
    sections: ChatListSections,
    foolsExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canScrollToTop by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val firstVisibleItemIndex by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val unreadAbove = remember(sections, foolsExpanded, firstVisibleItemIndex) {
        unreadActivityBeforeDisplayIndex(sections, foolsExpanded, firstVisibleItemIndex)
    }
    val description = if (unreadAbove > 0) {
        pluralStringResource(
            R.plurals.chatlist_scroll_to_top_with_unread,
            unreadAbove,
            unreadAbove,
        )
    } else {
        stringResource(R.string.chatlist_scroll_to_top)
    }

    AnimatedVisibility(
        visible = canScrollToTop,
        enter = scaleIn(),
        exit = scaleOut(),
        modifier = modifier,
    ) {
        BadgedBox(
            badge = {
                if (unreadAbove > 0) {
                    Badge { Text(if (unreadAbove > 99) "99+" else unreadAbove.toString()) }
                }
            },
        ) {
            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier.testTag("chatlist_scroll_to_top"),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = description,
                )
            }
        }
    }
}

@Composable
private fun FoolsSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chatlist_fools, count).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowWithMenu(
    row: ChatListRow,
    presence: io.github.trevarj.motd.service.PresenceState?,
    isFriend: Boolean,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit,
    archiveMode: Boolean,
    onDeleteBuffer: (ChatListRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val removalCopy = chatRemovalCopy(row.type)

    // Swipe end-to-start reveals a destructive red background; releasing arms the confirm dialog
    // (deleting drops history + parts a joined channel, so we never delete silently). We do NOT
    // let the box settle into the dismissed state — confirmValueChange returns false so the row
    // snaps back and the dialog drives the actual delete.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                confirmDelete = true
            }
            false
        },
    )

    Box(modifier = modifier) {
        SwipeToDismissBox(
            state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = { DeleteSwipeBackground(removalCopy.actionLabel) },
        ) {
        // Row background must be opaque so the swipe background stays hidden until swiped.
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            ChatListRowItem(
                row = row,
                showNetworkChip = multiNetwork,
                onClick = { onOpenBuffer(row.bufferId) },
                onLongClick = { menuOpen = true },
                isFriend = isFriend,
                presence = presence,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.pinned) R.string.chatlist_unpin else R.string.chatlist_pin,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (row.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onSetPinned(row.bufferId, !row.pinned)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(stringResource(if (archiveMode) R.string.chatlist_unarchive else R.string.chatlist_archive))
                    },
                    leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                    onClick = {
                        onSetArchived(row.bufferId, !archiveMode)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.muted) R.string.chatlist_unmute else R.string.chatlist_mute,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (row.muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onSetMuted(row.bufferId, !row.muted)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(removalCopy.actionLabel)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteConfirmDialog(
            row = row,
            onConfirm = {
                confirmDelete = false
                onDeleteBuffer(row)
            },
            onDismiss = { confirmDelete = false },
        )
        }
    }
}


/** Red end-aligned trash background revealed while swiping a chat-list row toward delete. */
@Composable
private fun DeleteSwipeBackground(@StringRes actionLabel: Int) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(actionLabel),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** Destructive-delete confirmation; channel copy mentions the implicit part/leave. */
@Composable
private fun DeleteConfirmDialog(
    row: ChatListRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = chatRemovalCopy(row.type)
    val message = if (copy.messageFormatsDisplayName) {
        stringResource(copy.message, row.displayName)
    } else {
        stringResource(copy.message)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(copy.confirmTitle)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(copy.confirmAction),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

internal data class ChatRemovalCopy(
    @get:StringRes val actionLabel: Int,
    @get:StringRes val confirmTitle: Int,
    @get:StringRes val message: Int,
    @get:StringRes val confirmAction: Int,
    val messageFormatsDisplayName: Boolean,
)

internal fun chatRemovalCopy(type: BufferType): ChatRemovalCopy = when (type) {
    BufferType.QUERY -> ChatRemovalCopy(
        actionLabel = R.string.chatlist_forget,
        confirmTitle = R.string.chatlist_forget_confirm_title,
        message = R.string.chatlist_forget_confirm_message,
        confirmAction = R.string.chatlist_forget_action,
        messageFormatsDisplayName = false,
    )
    BufferType.CHANNEL -> ChatRemovalCopy(
        actionLabel = R.string.chatlist_delete,
        confirmTitle = R.string.chatlist_delete_confirm_title,
        message = R.string.chatlist_delete_confirm_channel,
        confirmAction = R.string.action_delete,
        messageFormatsDisplayName = true,
    )
    BufferType.SERVER -> ChatRemovalCopy(
        actionLabel = R.string.chatlist_delete,
        confirmTitle = R.string.chatlist_delete_confirm_title,
        message = R.string.chatlist_delete_confirm_message,
        confirmAction = R.string.action_delete,
        messageFormatsDisplayName = true,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Preview
@Composable
private fun ChatListContentPreview() {
    MotdTheme {
        ChatListContent(
            state = ChatListState(
                rows = listOf(
                    ChatListRow(
                        bufferId = 1, networkId = 1, networkName = "Libera",
                        displayName = "#kotlin", type = BufferType.CHANNEL,
                        pinned = true, muted = false,
                        lastMessageText = "check out the new coroutines API",
                        lastMessageSender = "alice",
                        lastMessageTime = System.currentTimeMillis() - 60_000,
                        unreadCount = 5, mentionCount = 1,
                    ),
                    ChatListRow(
                        bufferId = 2, networkId = 1, networkName = "Libera",
                        displayName = "#libera", type = BufferType.CHANNEL,
                        pinned = false, muted = true,
                        lastMessageText = "welcome!", lastMessageSender = "bob",
                        lastMessageTime = System.currentTimeMillis() - 3_600_000,
                        unreadCount = 0, mentionCount = 0,
                    ),
                    ChatListRow(
                        bufferId = 3, networkId = 1, networkName = "Libera",
                        displayName = "carol", type = BufferType.QUERY,
                        pinned = false, muted = false,
                        lastMessageText = "ping me when you're around",
                        lastMessageSender = "carol",
                        lastMessageTime = System.currentTimeMillis() - 86_400_000,
                        unreadCount = 2, mentionCount = 0,
                    ),
                ),
                connection = mapOf(1L to IrcClientState.Connecting),
                networks = listOf(
                    NetworkEntity(
                        id = 1, name = "Libera", role = NetworkRole.DIRECT,
                        host = "irc.libera.chat", port = 6697,
                        nick = "me", username = "me", realname = "Me",
                    ),
                ),
                loading = false,
            ),
            onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
            onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
            onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ChatListEmptyPreview() {
    MotdTheme {
        ChatListContent(
            state = ChatListState(
                rows = emptyList(),
                networks = listOf(
                    NetworkEntity(
                        id = 1, name = "Libera", role = NetworkRole.DIRECT,
                        host = "irc.libera.chat", port = 6697,
                        nick = "me", username = "me", realname = "Me",
                    ),
                ),
                loading = false,
            ),
            onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
            onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
            onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
        )
    }
}
