package io.github.trevarj.motd.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Debug-only host for [SendSpikeHarness].
 *
 * Nothing in the shipped UI links here and nothing here is referenced by production code: the whole
 * spike lives in the `debug` source set, so `release` (and the `e2e` build type) never see it.
 * It is declared in `app/src/debug/AndroidManifest.xml` with `exported="true"` and no intent
 * filter, so it stays out of the launcher and is reached only over adb:
 *
 * ```sh
 * nix develop -c ./gradlew :app:installDebug
 * adb shell am start -n io.github.trevarj.motd.debug/io.github.trevarj.motd.spike.SendSpikeActivity
 * ```
 *
 * (`.debug` is the debug `applicationIdSuffix`; the class name is not suffixed.)
 */
class SendSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MotdTheme {
                SendSpikeHarness()
            }
        }
    }
}
