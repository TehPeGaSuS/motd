package io.github.trevarj.motd

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64

/**
 * E2E-only bridge for non-ASCII text entry from the shell harness.
 *
 * `adb shell input text` synthesizes key events through the device [android.view.KeyCharacterMap],
 * which has no key sequence for characters outside the virtual keyboard's map (for example the
 * U+2014 em dash in the seeded channel topic); a single such character makes the whole command
 * abort and type nothing. test/e2e/lib.sh therefore broadcasts arbitrary text here base64-encoded,
 * then presses KEYCODE_PASTE, which Compose maps to paste-at-caret in the focused text field.
 *
 * Ships only in the e2e build type (never debug/release) and is exported solely so the adb shell
 * uid can reach it while the harness drives the foreground app.
 */
class E2eClipboardReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val encoded = intent.getStringExtra(EXTRA_TEXT_B64) ?: return
        // Base64 keeps the payload intact across the host shell, adb's re-quoting, and the device
        // shell; decoding here is the only place the raw text is materialized.
        val text = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("motd-e2e", text))
    }

    companion object {
        const val EXTRA_TEXT_B64 = "text_b64"
    }
}
