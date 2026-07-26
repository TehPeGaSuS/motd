package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType

@Composable
fun NetworkToolsScreen(
    networkId: Long,
    onBack: () -> Unit = {},
    viewModel: NetworkToolsViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    NetworkToolsContent(
        state = state,
        onBack = onBack,
        onAddIgnore = viewModel::addIgnore,
        onSetIgnoreEnabled = viewModel::setIgnoreEnabled,
        onDeleteIgnore = viewModel::deleteIgnore,
        onSetMuted = viewModel::setMuted,
        onOper = viewModel::oper,
        onKill = viewModel::kill,
        onMode = viewModel::mode,
        onRehash = viewModel::rehash,
        onConnectServer = viewModel::connectServer,
        onSquit = viewModel::squit,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsContent(
    state: NetworkToolsUiState,
    onBack: () -> Unit,
    onAddIgnore: (String) -> Unit = {},
    onSetIgnoreEnabled: (Long, Boolean) -> Unit = { _, _ -> },
    onDeleteIgnore: (Long) -> Unit = {},
    onSetMuted: (Long, Boolean) -> Unit = { _, _ -> },
    onOper: (String, String) -> Unit = { _, _ -> },
    onKill: (String, String) -> Unit = { _, _ -> },
    onMode: (String, String, String) -> Unit = { _, _, _ -> },
    onRehash: (String) -> Unit = {},
    onConnectServer: (String, String, String) -> Unit = { _, _, _ -> },
    onSquit: (String, String) -> Unit = { _, _ -> },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.network_tools_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.status?.let {
                Text(
                    text = stringResource(R.string.network_tools_status, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("network_tools_status"),
                )
            }
            IgnoreSection(state, onAddIgnore, onSetIgnoreEnabled, onDeleteIgnore)
            MuteSection(state, onSetMuted)
            OperatorSection(state, onOper, onKill, onMode, onRehash, onConnectServer, onSquit)
        }
    }
}

@Composable
private fun IgnoreSection(
    state: NetworkToolsUiState,
    onAddIgnore: (String) -> Unit,
    onSetIgnoreEnabled: (Long, Boolean) -> Unit,
    onDeleteIgnore: (Long) -> Unit,
) {
    var pattern by remember { mutableStateOf("") }
    SettingsGroup(title = stringResource(R.string.network_tools_privacy_section)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text(stringResource(R.string.network_tools_ignore_hint)) },
                supportingText = { Text(stringResource(R.string.network_tools_ignore_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("network_tools_ignore_input"),
            )
            Button(
                onClick = {
                    onAddIgnore(pattern)
                    pattern = ""
                },
                enabled = pattern.isNotBlank(),
                modifier = Modifier.testTag("network_tools_add_ignore"),
            ) { Text(stringResource(R.string.network_tools_add_ignore)) }
        }
        if (state.ignores.isEmpty()) {
            Text(
                text = stringResource(R.string.network_tools_ignores_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.ignores.forEach { ignore ->
                ListItem(
                    headlineContent = { Text(ignore.pattern) },
                    supportingContent = {
                        Text(stringResource(if (ignore.enabled) R.string.network_tools_enabled else R.string.network_tools_disabled))
                    },
                    trailingContent = {
                        Row {
                            Switch(
                                checked = ignore.enabled,
                                onCheckedChange = { onSetIgnoreEnabled(ignore.id, it) },
                            )
                            IconButton(onClick = { onDeleteIgnore(ignore.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MuteSection(
    state: NetworkToolsUiState,
    onSetMuted: (Long, Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.network_tools_mutes_section)) {
        if (state.buffers.isEmpty()) {
            Text(
                text = stringResource(R.string.network_tools_mutes_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.buffers.forEach { row ->
                ListItem(
                    headlineContent = { Text(row.displayName) },
                    supportingContent = {
                        Text(
                            when (row.type) {
                                BufferType.CHANNEL -> stringResource(R.string.channelinfo_title)
                                BufferType.QUERY -> stringResource(R.string.nick_sheet_message)
                                BufferType.SERVER -> stringResource(R.string.network_settings_server_messages)
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = row.muted,
                            onCheckedChange = { onSetMuted(row.bufferId, it) },
                            modifier = Modifier.testTag("network_tools_mute_${row.bufferId}"),
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun OperatorSection(
    state: NetworkToolsUiState,
    onOper: (String, String) -> Unit,
    onKill: (String, String) -> Unit,
    onMode: (String, String, String) -> Unit,
    onRehash: (String) -> Unit,
    onConnectServer: (String, String, String) -> Unit,
    onSquit: (String, String) -> Unit,
) {
    var operUser by remember { mutableStateOf("") }
    var operPassword by remember { mutableStateOf("") }
    var modeTarget by remember { mutableStateOf("") }
    var modes by remember { mutableStateOf("") }
    var modeArgs by remember { mutableStateOf("") }
    var killNick by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var rehashServer by remember { mutableStateOf("") }
    var connectServer by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var connectRemote by remember { mutableStateOf("") }
    var squitServer by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingOperatorCommand?>(null) }
    val enabled = state.connected

    SettingsGroup(title = stringResource(R.string.network_tools_operator_section)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (enabled) {
                    stringResource(R.string.network_tools_operator_help)
                } else {
                    stringResource(R.string.network_tools_disconnected)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = operUser,
                onValueChange = { operUser = it },
                label = { Text(stringResource(R.string.network_tools_oper_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = operPassword,
                onValueChange = { operPassword = it },
                label = { Text(stringResource(R.string.network_tools_oper_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onOper(operUser, operPassword); operPassword = "" },
                enabled = enabled && operUser.isNotBlank() && operPassword.isNotBlank(),
            ) { Text(stringResource(R.string.network_tools_send_oper)) }

            HorizontalDivider()
            OutlinedTextField(value = modeTarget, onValueChange = { modeTarget = it }, label = { Text(stringResource(R.string.network_tools_target)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = modes, onValueChange = { modes = it }, label = { Text(stringResource(R.string.network_tools_modes)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = modeArgs, onValueChange = { modeArgs = it }, label = { Text(stringResource(R.string.network_tools_mode_args)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = { onMode(modeTarget, modes, modeArgs) },
                enabled = enabled && modeTarget.isNotBlank() && modes.isNotBlank(),
            ) { Text(stringResource(R.string.network_tools_send_mode)) }

            HorizontalDivider()
            OutlinedTextField(value = killNick, onValueChange = { killNick = it }, label = { Text(stringResource(R.string.network_tools_kill_nick)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ReasonField(reason, { reason = it })
            DestructiveButton(
                label = stringResource(R.string.network_tools_send_kill),
                enabled = enabled && killNick.isNotBlank() && reason.isNotBlank(),
                command = PendingOperatorCommand("KILL") { onKill(killNick, reason) },
                onConfirm = { pending = it },
            )
            OutlinedTextField(value = rehashServer, onValueChange = { rehashServer = it }, label = { Text(stringResource(R.string.network_tools_rehash_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DestructiveButton(
                label = stringResource(R.string.network_tools_send_rehash),
                enabled = enabled,
                command = PendingOperatorCommand("REHASH") { onRehash(rehashServer) },
                onConfirm = { pending = it },
            )
            OutlinedTextField(value = connectServer, onValueChange = { connectServer = it }, label = { Text(stringResource(R.string.network_tools_connect_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = connectPort, onValueChange = { connectPort = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.network_tools_connect_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = connectRemote, onValueChange = { connectRemote = it }, label = { Text(stringResource(R.string.network_tools_connect_remote)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DestructiveButton(
                label = stringResource(R.string.network_tools_send_connect),
                enabled = enabled && connectServer.isNotBlank(),
                command = PendingOperatorCommand("CONNECT") { onConnectServer(connectServer, connectPort, connectRemote) },
                onConfirm = { pending = it },
            )
            OutlinedTextField(value = squitServer, onValueChange = { squitServer = it }, label = { Text(stringResource(R.string.network_tools_squit_server)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DestructiveButton(
                label = stringResource(R.string.network_tools_send_squit),
                enabled = enabled && squitServer.isNotBlank() && reason.isNotBlank(),
                command = PendingOperatorCommand("SQUIT") { onSquit(squitServer, reason) },
                onConfirm = { pending = it },
            )
        }
    }

    pending?.let { command ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.network_tools_confirm_title, command.name)) },
            text = { Text(stringResource(R.string.network_tools_confirm_message, state.network?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    command.run()
                }) { Text(command.name, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ReasonField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.network_tools_reason)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DestructiveButton(
    label: String,
    enabled: Boolean,
    command: PendingOperatorCommand,
    onConfirm: (PendingOperatorCommand) -> Unit,
) {
    TextButton(
        onClick = { onConfirm(command) },
        enabled = enabled,
    ) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class PendingOperatorCommand(
    val name: String,
    val run: () -> Unit,
)
