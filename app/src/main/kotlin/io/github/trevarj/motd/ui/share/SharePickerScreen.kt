package io.github.trevarj.motd.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Share targets in the chat list's own order (pinned first, then recency). Archived and SERVER
 * buffers are never share destinations; the DAO already excludes SERVER, so that check is
 * defensive. A blank query keeps everything.
 */
internal fun filterShareTargets(
    rows: List<ChatListRow>,
    query: String,
): List<ChatListRow> {
    val needle = query.trim()
    return rows.filter { row ->
        !row.archived &&
            row.type != BufferType.SERVER &&
            (needle.isEmpty() || row.displayName.contains(needle, ignoreCase = true))
    }
}

@HiltViewModel
class SharePickerViewModel
    @Inject
    constructor(
        bufferRepository: BufferRepository,
        private val store: PendingShareStore,
        private val draftStore: ComposerDraftStore,
    ) : ViewModel() {
        private val queryState = MutableStateFlow("")
        val query: StateFlow<String> = queryState.asStateFlow()

        // Set once the payload leaves this screen (picked or explicitly dismissed) so teardown never
        // discards someone else's parked share.
        private var handled = false

        val targets: StateFlow<List<ChatListRow>> =
            bufferRepository
                .observeChatList()
                .combine(queryState, ::filterShareTargets)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun onQueryChange(value: String) {
            queryState.value = value
        }

        /**
         * Route the parked payload to [bufferId]: text becomes a composer prefill, a file is queued for
         * that buffer's upload sheet. False when the payload was already consumed (nothing to open).
         */
        fun pick(bufferId: Long): Boolean {
            val share = store.consume() ?: return false
            when (share) {
                is PendingShare.Text -> draftStore.push(bufferId, share.text)
                is PendingShare.File -> store.assignFile(bufferId, share)
            }
            handled = true
            return true
        }

        fun cancel() = discardUnhandled()

        /** System back / pop tears the screen down without a cancel callback; clean up here too. */
        override fun onCleared() = discardUnhandled()

        private fun discardUnhandled() {
            if (handled) return
            handled = true
            store.consume()
        }
    }

/** Chat picker for an inbound share. Picking never sends: it prefills or opens the upload sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(
    onPicked: (Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: SharePickerViewModel = hiltViewModel(),
) {
    val rows by viewModel.targets.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_picker_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancel()
                            onCancel()
                        },
                        modifier = Modifier.testTag("share_picker_close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("share_picker_search"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.share_picker_search)) },
            )
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.share_picker_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("share_picker_list")) {
                    items(rows, key = { it.bufferId }) { row ->
                        ChatListRowItem(
                            row = row,
                            showNetworkChip = true,
                            // A consumed payload means there is nothing left to deliver: leave the
                            // picker instead of opening a chat the user didn't ask for.
                            onClick = { if (viewModel.pick(row.bufferId)) onPicked(row.bufferId) else onCancel() },
                            onLongClick = {},
                        )
                    }
                }
            }
        }
    }
}
