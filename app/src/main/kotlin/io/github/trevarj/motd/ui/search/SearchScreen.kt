package io.github.trevarj.motd.ui.search

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.repo.SearchCoverage
import io.github.trevarj.motd.ui.chatlist.relativeChatTime
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.resolveIs24Hour
import io.github.trevarj.motd.ui.theme.LocalTimestampConfig
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel, applies the nav buffer scope, drives navigation. */
@Composable
fun SearchScreen(
    bufferId: Long? = null,
    onBack: () -> Unit = {},
    // Legacy plain-open (bufferId only). Kept until R3 rewires NavGraph to onOpenHit; onOpenHit
    // defaults to delegating here so the current NavGraph call site stays source-compatible.
    onOpenBuffer: (Long) -> Unit = {},
    // Deep-jump carries canonical local identity so msgidless repeated events remain exact. A
    // server hit has no local row at all, so eventId is nullable and null for those.
    onOpenHit: (bufferId: Long, msgid: String?, serverTime: Long, eventId: Long?) -> Unit =
        { b, _, _, _ -> onOpenBuffer(b) },
    viewModel: SearchViewModel = hiltViewModel(),
) {
    LaunchedEffect(bufferId) { viewModel.init(bufferId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    SearchContent(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onScopeChange = viewModel::onScopeChange,
        onServerSearchSubmit = viewModel::onServerSearchSubmit,
        onBack = onBack,
        onOpenHit = { hit ->
            onOpenHit(
                hit.message.bufferId,
                hit.message.msgid,
                hit.message.serverTime,
                hit.message.id,
            )
        },
        onOpenServerHit = { hit -> onOpenHit(hit.bufferId, hit.msgid, hit.serverTime, null) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchContent(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onScopeChange: (SearchScope) -> Unit,
    onBack: () -> Unit,
    onOpenHit: (SearchHit) -> Unit,
    onServerSearchSubmit: () -> Unit = {},
    onOpenServerHit: (ServerHitUi) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Visible query lives in local IME state so the cursor/selection is preserved and keystrokes
    // aren't dropped waiting on the async DB round-trip. Seeded once from the incoming state (e.g. a
    // pre-scoped query); the ViewModel remains the source of RESULTS only, not the field's value.
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.rawQuery))
    }
    // The ViewModel publishes a new key immediately, but Compose may render this local edit one
    // frame before the collected state advances. Never let that frame show old rows or highlights.
    val visibleState = if (text.text == state.rawQuery) {
        state
    } else {
        state.copy(
            rawQuery = text.text,
            groups = emptyList(),
            // The cap belongs to the results being dropped for this frame, not to the new query.
            truncated = false,
            searching = !isEmptySearchQuery(text.text),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                title = {
                    TextField(
                        value = text,
                        onValueChange = { text = it; onQueryChange(it.text) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag("search_field"),
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        trailingIcon = {
                            // The slot stays mounted so the clear affordance fades with the
                            // house micro tempo instead of popping in on the first keystroke.
                            AnimatedVisibility(
                                visible = text.text.isNotEmpty(),
                                enter = fadeIn(MotdMotion.microFadeIn),
                                exit = fadeOut(MotdMotion.microFadeOut),
                            ) {
                                IconButton(
                                    onClick = {
                                        text = clearedSearchText()
                                        onQueryChange("")
                                    },
                                    modifier = Modifier.testTag("search_clear"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.search_clear),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // The wire round trip is submit-driven only; local search stays debounced.
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (visibleState.scope == SearchScope.SERVER) onServerSearchSubmit()
                            },
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (visibleState.hasBufferScope) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = visibleState.scope == SearchScope.ALL,
                        onClick = { onScopeChange(SearchScope.ALL) },
                        label = { Text(stringResource(R.string.search_chip_all)) },
                    )
                    FilterChip(
                        selected = visibleState.scope == SearchScope.CURRENT,
                        onClick = { onScopeChange(SearchScope.CURRENT) },
                        label = { Text(stringResource(R.string.search_chip_current)) },
                    )
                    if (visibleState.serverSearchAvailable) {
                        FilterChip(
                            selected = visibleState.scope == SearchScope.SERVER,
                            onClick = { onScopeChange(SearchScope.SERVER) },
                            label = { Text(stringResource(R.string.search_chip_server)) },
                            modifier = Modifier.testTag("search_scope_server"),
                        )
                    }
                }
            }

            coverageNotice(visibleState)?.let { notice ->
                Text(
                    text = stringResource(notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("search_coverage_notice"),
                )
            }

            // Keyed on the pane's identity, never on the query or its rows, so debounce cycles
            // fade between panes while within-pane updates recompose in place without re-fading.
            Crossfade(
                targetState = searchPane(visibleState),
                animationSpec = MotdMotion.microFadeIn,
                label = "search_pane",
                modifier = Modifier.fillMaxSize(),
            ) { pane ->
                when (pane) {
                    SearchPane.SERVER -> ServerSearchSection(
                        server = visibleState.server,
                        query = parseSearchQuery(visibleState.rawQuery).text,
                        onRetry = onServerSearchSubmit,
                        onOpenHit = onOpenServerHit,
                    )

                    SearchPane.PROMPT -> EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.search_prompt_title),
                        message = stringResource(R.string.search_prompt_message),
                    )

                    SearchPane.SEARCHING -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_loading"),
                    )

                    SearchPane.NO_RESULTS -> EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = stringResource(R.string.search_empty_title),
                        message = stringResource(emptyMessage(visibleState)),
                    )

                    SearchPane.RESULTS -> SearchResults(
                        groups = visibleState.groups,
                        query = parseSearchQuery(visibleState.rawQuery).text,
                        truncated = visibleState.truncated,
                        onOpenHit = onOpenHit,
                    )
                }
            }
        }
    }
}

/** Clear the visible IME value, including any selection or active composition. */
internal fun clearedSearchText(): TextFieldValue = TextFieldValue("")

/** The results pane's identity; each value keeps exactly one branch (and its tags) mounted. */
private enum class SearchPane { SERVER, PROMPT, SEARCHING, NO_RESULTS, RESULTS }

/** Same precedence as the pane branches themselves; the crossfade keys on this. */
private fun searchPane(state: SearchUiState): SearchPane = when {
    state.scope == SearchScope.SERVER -> SearchPane.SERVER
    state.rawQuery.isBlank() -> SearchPane.PROMPT
    state.searching -> SearchPane.SEARCHING
    state.groups.isEmpty() -> SearchPane.NO_RESULTS
    else -> SearchPane.RESULTS
}

/**
 * The standing disclosure about what was searched, or null when the scope needs no caveat.
 *
 * The all-buffers scope always spans a device-only corpus. A buffer scope only stays quiet when
 * coverage is positively known complete; unknown coverage is disclosed like partial coverage so a
 * not-yet-loaded state never reads as a completeness promise.
 */
@StringRes
private fun coverageNotice(state: SearchUiState): Int? = when {
    // The server scope searches a different corpus and states its own caveat with its results.
    state.scope == SearchScope.SERVER -> null
    state.scope == SearchScope.ALL -> R.string.search_coverage_all
    state.coverage is SearchCoverage.BufferComplete -> null
    else -> R.string.search_coverage_partial
}

/** Empty-result copy that names the corpus that was actually searched. */
@StringRes
private fun emptyMessage(state: SearchUiState): Int = when {
    state.scope == SearchScope.ALL -> R.string.search_empty_message_all
    state.coverage is SearchCoverage.BufferComplete -> R.string.search_empty_message_buffer_complete
    else -> R.string.search_empty_message_buffer_partial
}

@Composable
private fun SearchResults(
    groups: List<SearchGroup>,
    query: String,
    truncated: Boolean,
    onOpenHit: (SearchHit) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("search_results")) {
        groups.forEach { group ->
            item(key = "h-${group.bufferId}") {
                Text(
                    text = "${group.bufferDisplayName} · ${group.networkName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(group.hits, key = { it.message.id }) { hit ->
                SearchHitRow(hit = hit, query = query, onClick = { onOpenHit(hit) })
            }
        }
        if (truncated) {
            item(key = "truncated_footer") {
                Text(
                    text = stringResource(R.string.search_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag("search_truncated_footer"),
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, query: String, onClick: () -> Unit) = SearchRow(
    sender = hit.message.sender,
    text = hit.message.text,
    serverTime = hit.message.serverTime,
    query = query,
    // Per-hit handle (sender+text can repeat); msgid when present, else the local row id.
    tag = "search_result_${hit.message.msgid ?: hit.message.id}",
    onClick = onClick,
)

@Composable
private fun ServerHitRow(hit: ServerHitUi, query: String, onClick: () -> Unit) = SearchRow(
    sender = hit.sender,
    text = hit.text,
    serverTime = hit.serverTime,
    query = query,
    tag = "search_result_${hit.msgid ?: hit.serverTime}",
    onClick = onClick,
)

/** Shared visual row for a local or a server hit. */
@Composable
private fun SearchRow(
    sender: String,
    text: String,
    serverTime: Long,
    query: String,
    tag: String,
    onClick: () -> Unit,
) {
    // Search results always show a time (independent of the in-chat "show timestamps" toggle);
    // only its 12h/24h format follows the user's preference.
    val context = LocalContext.current
    val is24Hour = resolveIs24Hour(LocalTimestampConfig.current.format, DateFormat.is24HourFormat(context))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sender,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = highlightSnippet(text, query),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
        }
        // A server hit without a time tag carries 0; render nothing rather than the epoch.
        if (serverTime > 0) {
            Text(
                text = relativeChatTime(serverTime, is24Hour = is24Hour),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Top),
            )
        }
    }
}

/** Server-scope content: prompt, progress, failure with retry, or the transient hit list. */
@Composable
private fun ServerSearchSection(
    server: ServerSearchState,
    query: String,
    onRetry: () -> Unit,
    onOpenHit: (ServerHitUi) -> Unit,
) {
    when (server) {
        ServerSearchState.Idle -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.search_server_prompt_title),
            message = stringResource(R.string.search_server_prompt_message),
        )

        ServerSearchState.Searching -> LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_loading"),
        )

        is ServerSearchState.Failed -> Column(modifier = Modifier.fillMaxWidth()) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.search_server_error_title),
                message = stringResource(
                    when (server.error) {
                        ServerSearchError.REJECTED -> R.string.search_server_error_rejected
                        ServerSearchError.UNAVAILABLE -> R.string.search_server_error_unavailable
                    },
                ),
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("search_server_retry"),
            ) {
                Text(stringResource(R.string.search_server_retry))
            }
        }

        is ServerSearchState.Results -> if (server.hits.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.search_server_empty_title),
                message = stringResource(R.string.search_server_empty_message),
            )
        } else {
            Text(
                text = stringResource(R.string.search_server_notice),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("search_coverage_notice"),
            )
            LazyColumn(modifier = Modifier.fillMaxSize().testTag("search_results")) {
                itemsIndexed(
                    server.hits,
                    // Msgid-less hits share a timestamp only by accident; the index keeps keys unique.
                    key = { index, hit -> hit.msgid ?: "t-${hit.serverTime}-$index" },
                ) { _, hit ->
                    ServerHitRow(hit = hit, query = query, onClick = { onOpenHit(hit) })
                }
                if (server.truncated) {
                    item(key = "truncated_footer") {
                        Text(
                            text = stringResource(R.string.search_server_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(16.dp)
                                .testTag("search_truncated_footer"),
                        )
                    }
                }
            }
        }
    }
}

/** Bold every case-insensitive occurrence of [query]'s terms in [text]. */
private fun highlightSnippet(text: String, query: String): AnnotatedString {
    val terms = query.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
    if (terms.isEmpty()) return AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        val lower = text.lowercase()
        for (term in terms) {
            val t = term.lowercase()
            var idx = lower.indexOf(t)
            while (idx >= 0) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), idx, idx + term.length)
                idx = lower.indexOf(t, idx + term.length)
            }
        }
    }
}

@Preview
@Composable
private fun SearchContentPreview() {
    MotdTheme {
        SearchContent(
            state = SearchUiState(
                rawQuery = "coroutine",
                hasBufferScope = true,
                scope = SearchScope.ALL,
                groups = listOf(
                    SearchGroup(
                        bufferId = 1, bufferDisplayName = "#kotlin", networkName = "Libera",
                        hits = listOf(
                            SearchHit(
                                message = MessageEntity(
                                    id = 1, bufferId = 1, serverTime = System.currentTimeMillis() - 60_000,
                                    sender = "alice", kind = MessageKind.PRIVMSG,
                                    text = "the new coroutine builder is great", dedupKey = "a",
                                ),
                                bufferDisplayName = "#kotlin", networkName = "Libera",
                            ),
                        ),
                    ),
                ),
            ),
            onQueryChange = {}, onScopeChange = {}, onBack = {}, onOpenHit = {},
        )
    }
}

@Preview
@Composable
private fun SearchEmptyPreview() {
    MotdTheme {
        SearchContent(
            state = SearchUiState(rawQuery = "", hasBufferScope = false),
            onQueryChange = {}, onScopeChange = {}, onBack = {}, onOpenHit = {},
        )
    }
}
