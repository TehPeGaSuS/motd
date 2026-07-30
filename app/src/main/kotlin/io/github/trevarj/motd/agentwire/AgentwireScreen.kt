package io.github.trevarj.motd.agentwire

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun AgentwireGateScreen(
    onBack: () -> Unit,
    showBack: Boolean,
    viewModel: AgentwireViewModel = hiltViewModel(),
    ordinaryChat: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.gate == AgentwireGate.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 48.dp))
        }
        state.gate == AgentwireGate.ORDINARY || state.transcriptOverride -> ordinaryChat()
        else -> AgentwireScreen(state, viewModel, onBack, showBack)
    }
}

private enum class AgentwireSheet { STATUS, QUEUE, QUESTION }

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireScreen(
    state: AgentwireUiState,
    viewModel: AgentwireViewModel,
    onBack: () -> Unit,
    showBack: Boolean,
) {
    var sheet by remember { mutableStateOf<AgentwireSheet?>(null) }
    var questionRequestId by remember { mutableStateOf<String?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var composer by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${state.backend.orEmpty()}  ${state.activeSid ?: "detached"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text("View IRC transcript") },
                                onClick = { overflow = false; viewModel.viewTranscript() },
                            )
                        }
                    },
                )
                AgentwireStatusStrip(state) { sheet = AgentwireSheet.STATUS }
            }
        },
        bottomBar = {
            if (state.gate == AgentwireGate.ACTIVE) {
                AgentwireComposer(
                    value = composer,
                    state = state,
                    onValueChange = { composer = it },
                    onSend = { viewModel.submit(composer); composer = "" },
                    onCancel = viewModel::cancelTurn,
                )
            }
        },
    ) { padding ->
        if (state.gate == AgentwireGate.BLOCKED) {
            AgentwireBlocked(state, Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("agentwire_timeline"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.syncing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!state.connected && state.timeline.isEmpty()) item {
                    Card(Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Agentwire is offline", style = MaterialTheme.typography.titleMedium)
                            Text("Structured state will be rebuilt after reconnecting.")
                            TextButton(onClick = viewModel::viewTranscript) { Text("View IRC transcript") }
                        }
                    }
                }
                if (state.error != null) item {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (state.olderHistoryAvailable) item {
                    TextButton(onClick = viewModel::loadOlderHistory, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.historyLoading) "Loading history…" else "Load older history")
                    }
                }
                items(state.timeline, key = AgentwireTimelineItem::id) { item ->
                    AgentwireTimelineCard(item, state.actionStatus[item.id])
                }
                items(state.requests, key = AgentwireRequest::rid) { request ->
                    AgentwireRequestCard(request, request.sid == null || request.sid == state.activeSid, viewModel) {
                        questionRequestId = request.rid
                        sheet = AgentwireSheet.QUESTION
                    }
                }
                if (state.queue.isNotEmpty()) item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth().clickable { sheet = AgentwireSheet.QUEUE },
                    ) {
                        Text(
                            "${state.queue.size} queued  •  tap to edit",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    when (sheet) {
        AgentwireSheet.STATUS -> AgentwireStatusSheet(state, viewModel) { sheet = null }
        AgentwireSheet.QUEUE -> AgentwireQueueSheet(state, viewModel) { sheet = null }
        AgentwireSheet.QUESTION -> state.requests.firstOrNull { it.rid == questionRequestId }?.let {
            AgentwireQuestionSheet(it, viewModel) { sheet = null }
        }
        null -> Unit
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireStatusStrip(state: AgentwireUiState, onClick: () -> Unit) {
    val status = when {
        !state.connected -> "offline"
        state.syncing -> "syncing"
        state.busy -> "running"
        state.activeSid == null -> "detached"
        else -> "ready"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("agentwire_status_strip"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("● $status", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(state.cwd ?: "No session", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("settings / sessions", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireBlocked(state: AgentwireUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Agentwire unavailable", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("This channel is activated, but the IRC connection did not negotiate every required capability.")
        Spacer(Modifier.height(12.dp))
        Text(state.missingCaps.sorted().joinToString("\n"), fontFamily = FontFamily.Monospace)
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireComposer(
    value: String,
    state: AgentwireUiState,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (state.busy) {
                val mode = state.settings["delivery"] ?: "queue"
                AssistChip(onClick = {}, label = { Text(mode.replaceFirstChar(Char::uppercase)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).testTag("agentwire_composer"),
                    placeholder = { Text(if (state.busy) "Queue or steer the running turn" else "Message the agent") },
                    maxLines = 6,
                    enabled = state.connected && state.activeSid != null,
                )
                Spacer(Modifier.width(8.dp))
                if (state.busy && "turn.cancel" in state.actions) {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel turn") }
                }
                Button(onClick = onSend, enabled = value.isNotBlank() && state.connected && state.activeSid != null) {
                    Text(if (state.busy) "Send" else "Run")
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireTimelineCard(item: AgentwireTimelineItem, actionStatus: String?) {
    var expanded by remember(item.running) { mutableStateOf(item.running || item.kind == "assistant.completed") }
    val collapsible = item.kind.startsWith("tool.") || item.kind == "plan.updated" || item.kind == "usage.updated"
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.kind == "user.prompt") MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().clickable(enabled = collapsible) { expanded = !expanded }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    listOfNotNull(actionStatus, item.tid?.take(8), if (item.historical) "history" else null).joinToString(" • "),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (item.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (!collapsible || expanded) item.body?.let {
                Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (collapsible && !expanded) {
                Text("Tap to expand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireRequestCard(
    request: AgentwireRequest,
    canRespond: Boolean,
    viewModel: AgentwireViewModel,
    openQuestions: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("agentwire_request_${request.rid}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (request.type == "approval") "Approval required" else "Question", fontWeight = FontWeight.Bold)
            if (request.redacted) Text("Sensitive details were redacted. Review carefully.", color = MaterialTheme.colorScheme.error)
            request.summary?.let { Text(it) }
            if (request.inactive) Text("This request belongs to an inactive session: ${request.sid}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.type == "approval") {
                    Button(onClick = { viewModel.respondApproval(request.rid, true) }, enabled = canRespond) { Text("Allow once") }
                    OutlinedButton(onClick = { viewModel.respondApproval(request.rid, false) }, enabled = canRespond) { Text("Deny") }
                } else if (!request.redacted) {
                    Button(onClick = openQuestions, enabled = canRespond) { Text("Answer") }
                }
                if (request.canSkip) TextButton(onClick = { viewModel.skipRequest(request.rid) }, enabled = canRespond) { Text("Skip") }
                if (request.inactive && request.sid != null) {
                    TextButton(onClick = { viewModel.attachSession(request.sid) }) { Text("Reattach") }
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireQueueSheet(state: AgentwireUiState, viewModel: AgentwireViewModel, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::clearQueue) { Text("Clear") }
            }
            state.queue.forEachIndexed { index, item ->
                var text by remember(item.iid, item.content) { mutableStateOf(item.content) }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Item ${index + 1}") })
                        Row {
                            TextButton(onClick = { viewModel.moveQueue(item.iid, (index - 1).coerceAtLeast(0)) }, enabled = index > 0) { Text("Up") }
                            TextButton(onClick = { viewModel.moveQueue(item.iid, (index + 1).coerceAtMost(state.queue.lastIndex)) }, enabled = index < state.queue.lastIndex) { Text("Down") }
                            TextButton(onClick = { viewModel.editQueue(item.iid, text) }, enabled = text != item.content) { Text("Save") }
                            TextButton(onClick = { viewModel.deleteQueue(item.iid) }) { Text("Delete") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireQuestionSheet(request: AgentwireRequest, viewModel: AgentwireViewModel, dismiss: () -> Unit) {
    val answers = remember(request.rid) { mutableStateMapOf<String, Set<String>>() }
    val customAnswers = remember(request.rid) { mutableStateMapOf<String, String>() }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Questions", style = MaterialTheme.typography.titleLarge)
            request.questions.forEach { question ->
                Text(question.header ?: question.prompt, fontWeight = FontWeight.SemiBold)
                if (question.header != null) Text(question.prompt)
                question.options.forEach { option ->
                    AssistChip(onClick = {
                        val selected = answers[question.id].orEmpty()
                        answers[question.id] = if (question.multiple) {
                            if (option in selected) selected - option else selected + option
                        } else {
                            setOf(option)
                        }
                    }, label = { Text(option) })
                }
                if (question.custom || question.options.isEmpty()) {
                    OutlinedTextField(
                        value = customAnswers[question.id].orEmpty(),
                        onValueChange = { customAnswers[question.id] = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(onClick = {
                viewModel.respondQuestions(request.rid, request.questions.map { question ->
                    val values = buildList {
                        addAll(answers[question.id].orEmpty())
                        customAnswers[question.id]?.takeIf(String::isNotBlank)?.let(::add)
                    }
                    kotlinx.serialization.json.JsonArray(values.map(::JsonPrimitive))
                })
                dismiss()
            }) { Text("Submit answers") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireStatusSheet(state: AgentwireUiState, viewModel: AgentwireViewModel, dismiss: () -> Unit) {
    var cwd by remember { mutableStateOf(state.cwd.orEmpty()) }
    var model by remember { mutableStateOf(state.settings["model"].orEmpty()) }
    var effort by remember { mutableStateOf(state.settings["effort"].orEmpty()) }
    var confirmAutoReview by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Agent session", style = MaterialTheme.typography.titleLarge) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = viewModel::listWorkspaces) { Text("Workspaces") }
                    FilledTonalButton(onClick = { viewModel.listSessions(cwd.ifBlank { null }) }) { Text("Sessions") }
                    if (state.activeSid != null) OutlinedButton(onClick = viewModel::detachSession) { Text("Detach") }
                }
            }
            items(state.workspaces, key = AgentwireListItem::id) { workspace ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(workspace.title); workspace.subtitle?.let { Text(it, fontFamily = FontFamily.Monospace) } }
                    TextButton(onClick = { cwd = workspace.id; viewModel.listSessions(workspace.id) }) { Text("Open") }
                    TextButton(onClick = { viewModel.createSession(workspace.id) }, enabled = "session.create" in state.actions) { Text("Create") }
                }
            }
            items(state.sessions, key = AgentwireListItem::id) { session ->
                AgentwireSessionRow(session, state.actions, viewModel)
            }
            item { HorizontalDivider(); Text("Safe settings", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(model, { model = it }, label = { Text("Model") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(effort, { effort = it }, label = { Text("Effort") }, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("queue", "steer").forEach { delivery ->
                        AssistChip(onClick = { viewModel.updateSettings(mapOf("delivery" to delivery)) }, label = { Text(delivery) })
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { viewModel.updateSettings(mapOf("collaboration" to "default")) }, label = { Text("Default") })
                    AssistChip(
                        onClick = { if (model.isNotBlank()) viewModel.updateSettings(mapOf("collaboration" to "plan", "model" to model)) },
                        label = { Text("Plan") },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.updateSettings(buildMap {
                        if (model.isNotBlank()) put("model", model)
                        if (effort.isNotBlank()) put("effort", effort)
                    }) }) { Text("Apply") }
                    OutlinedButton(onClick = {
                        if (state.autoReviewConfirmed) viewModel.enableAutoReview() else confirmAutoReview = true
                    }) { Text("Enable auto-review") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirmAutoReview) AlertDialog(
        onDismissRequest = { confirmAutoReview = false },
        title = { Text("Enable auto-review for this session?") },
        text = { Text("Interactive approval policy and sandbox restrictions remain in effect. Auto-review does not mean never ask.") },
        confirmButton = { TextButton(onClick = { viewModel.enableAutoReview(); confirmAutoReview = false }) { Text("Enable") } },
        dismissButton = { TextButton(onClick = { confirmAutoReview = false }) { Text("Cancel") } },
    )
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireSessionRow(
    session: AgentwireListItem,
    actions: Set<String>,
    viewModel: AgentwireViewModel,
) {
    var title by remember(session.id, session.title) { mutableStateOf(session.title) }
    val archived = "archived" in session.raw.stringList("flags")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            if ("session.rename" in actions) {
                OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Session title") })
            } else {
                Text(session.title, fontWeight = FontWeight.SemiBold)
            }
            session.subtitle?.let { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { viewModel.attachSession(session.id, session.subtitle) }) { Text("Attach") }
                if ("session.rename" in actions) {
                    TextButton(onClick = { viewModel.renameSession(session.id, title) }, enabled = title.isNotBlank() && title != session.title) { Text("Rename") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if ("session.fork" in actions) TextButton(onClick = { viewModel.forkSession(session.id) }) { Text("Fork") }
                if (archived && "session.unarchive" in actions) {
                    TextButton(onClick = { viewModel.archiveSession(session.id, false) }) { Text("Unarchive") }
                } else if (!archived && "session.archive" in actions) {
                    TextButton(onClick = { viewModel.archiveSession(session.id, true) }) { Text("Archive") }
                }
            }
        }
    }
}
