package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.messageContextOrNull

/**
 * The ViewModel's IRC-event boundary.  It admits backend state only from the account that the
 * channel topic provisions, never from the first account that answers a sync request.
 */
internal class AgentwireEventIngestor(
    private val reducer: AgentwireReducer = AgentwireReducer(),
    private val reassembler: AgentwireReassembler = AgentwireReassembler(),
) {
    sealed interface Result {
        data object Ignored : Result
        data class Rejected(val state: AgentwireUiState) : Result
        data class Applied(val state: AgentwireUiState, val envelope: AgentwireEnvelope) : Result
    }

    fun reset() {
        reducer.reset()
        reassembler.clear()
    }

    fun ingest(state: AgentwireUiState, event: IrcEvent, syncId: String?): Result {
        if (event is IrcEvent.PlaybackBatch || event is IrcEvent.HistoryBatch || event is IrcEvent.ReplayBatch) {
            return Result.Ignored
        }
        val context = event.messageContextOrNull() ?: return Result.Ignored
        val raw = context.clientTags[AGENTWIRE_TAG] ?: return Result.Ignored
        val target = when (event) {
            is IrcEvent.ChatMessage -> event.target
            is IrcEvent.TagMessage -> event.target
            else -> return Result.Ignored
        }
        if (!target.equals(state.channel, ignoreCase = true)) return Result.Ignored
        val account = context.account?.takeUnless { it == "*" } ?: return Result.Ignored
        val trusted = state.backendAccount ?: return Result.Ignored
        if (!account.equals(trusted, ignoreCase = true)) {
            // Actions are authorized separately by controllerAccount. They never authenticate
            // events, unless the topic deliberately provisions that same account as the backend.
            if (account.equals(state.controllerAccount, ignoreCase = true)) return Result.Ignored
            return Result.Rejected(state.copy(error = "Rejected Agentwire event from untrusted account: $account"))
        }
        val decoded = decodeAgentwireValue(raw).getOrElse {
            return Result.Rejected(state.copy(error = "Invalid Agentwire message: ${it.message}"))
        }
        val envelope = when (decoded) {
            is AgentwireValue.Envelope -> decoded.value
            is AgentwireValue.Fragment -> reassembler.accept(decoded.value).getOrElse {
                return Result.Rejected(state.copy(error = "Invalid Agentwire fragments: ${it.message}"))
            } ?: return Result.Ignored
        }
        if (envelope.type != "event") return Result.Ignored
        val pinned = state.botAccount
        val candidate = if (pinned == null) {
            if (envelope.kind != "agent.hello" || envelope.reply != syncId) return Result.Ignored
            state.copy(botAccount = account)
        } else if (!account.equals(pinned, ignoreCase = true)) {
            return Result.Rejected(state.copy(error = "Rejected Agentwire event from untrusted account: $account"))
        } else {
            state
        }
        if (!acceptsAgentwireEpoch(envelope, candidate.epoch)) return Result.Ignored
        return Result.Applied(reducer.reduce(candidate, envelope), envelope)
    }
}
