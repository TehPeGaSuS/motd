package io.github.trevarj.motd.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.PresenceMode

/** Per-conversation presence-event choice; mirrors [ConversationLayoutSheet]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresenceModeSheet(
    state: ConversationPresenceState,
    onSelect: (PresenceMode?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("chat_presence_sheet")) {
        Text(text = stringResource(R.string.chat_presence_title))
        androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
            PresenceOption(
                value = null,
                selected = state.override == null,
                tag = "chat_presence_global",
                label = stringResource(R.string.chat_presence_use_global),
                supporting = stringResource(
                    R.string.chat_presence_global_summary,
                    stringResource(presenceModeLabel(state.global)),
                ),
                onSelect = onSelect,
            )
            PresenceMode.entries.forEach { mode ->
                PresenceOption(
                    value = mode,
                    selected = state.override == mode,
                    tag = "chat_presence_${mode.name.lowercase()}",
                    label = stringResource(presenceModeLabel(mode)),
                    supporting = stringResource(presenceModeDescription(mode)),
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun PresenceOption(
    value: PresenceMode?,
    selected: Boolean,
    tag: String,
    label: String,
    supporting: String?,
    onSelect: (PresenceMode?) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .testTag(tag)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(value) },
            ),
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { text -> { Text(text) } },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

@StringRes
internal fun presenceModeLabel(mode: PresenceMode): Int = when (mode) {
    PresenceMode.ALL -> R.string.settings_presence_all
    PresenceMode.SMART -> R.string.settings_presence_smart
    PresenceMode.HIDDEN -> R.string.settings_presence_hidden
}

@StringRes
internal fun presenceModeDescription(mode: PresenceMode): Int = when (mode) {
    PresenceMode.ALL -> R.string.settings_presence_all_desc
    PresenceMode.SMART -> R.string.settings_presence_smart_desc
    PresenceMode.HIDDEN -> R.string.settings_presence_hidden_desc
}
