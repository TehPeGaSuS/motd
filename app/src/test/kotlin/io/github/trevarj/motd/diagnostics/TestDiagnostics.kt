package io.github.trevarj.motd.diagnostics

import java.io.OutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class RecordedDiagnostic(
    val component: String,
    val event: String,
    val fields: Map<String, Any?>,
)

/**
 * Always-enabled [DiagnosticLogger] double that keeps every record in memory.
 *
 * Shared by the containment tests, which assert both that a failure is observable and that the
 * throwable's message never reaches the journal.
 */
internal class RecordingDiagnostics : DiagnosticLogger {
    val events = mutableListOf<RecordedDiagnostic>()

    override val enabled: StateFlow<Boolean> = MutableStateFlow(true)

    override fun setEnabled(enabled: Boolean) = Unit

    override fun record(component: String, event: String, fields: () -> Map<String, Any?>) {
        events += RecordedDiagnostic(component, event, fields())
    }

    override fun fingerprint(value: String?): String? = diagnosticFingerprint(value)

    override suspend fun exportTo(output: OutputStream) = Unit
}
