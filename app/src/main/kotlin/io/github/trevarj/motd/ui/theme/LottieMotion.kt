package io.github.trevarj.motd.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieDynamicProperty
import com.airbnb.lottie.model.KeyPath

/**
 * The animator-scale gate shared by every Lottie call site.
 *
 * [MotdMotion]'s specs are ordinary Compose animation specs, so Compose applies
 * [MotionDurationScale] to them for free. Lottie drives its own clock and only ever consults
 * [Settings.Global] -- never the element a test rule or recomposer installs -- so the app resolves
 * the scale itself and snaps each asset to its settled frame when motion is off.
 */
object MotdLottieMotion {
    /** Scale 0 is the platform's "animations off"; anything above it plays at the normal tempo. */
    internal fun motionEnabled(scaleFactor: Float): Boolean = scaleFactor > 0f

    /**
     * Whether a one-shot beat should play now.
     *
     * [previous] is null on the first observation of a value, which is exactly the scrollback case:
     * a row composed fresh already at [target] has not transitioned into it and must render the
     * settled end frame instead of replaying the morph under the user.
     */
    internal fun <T> playOnceOnTransition(
        previous: T?,
        current: T,
        target: T,
    ): Boolean = previous != null && previous != target && current == target
}

/**
 * Whether Lottie assets may play, resolved once per [MotdTheme] scope.
 *
 * Static rather than per-call-site: the chat timeline mounts a delivery tick on every own row, and
 * resolving the scale there would repeat a coroutine-scope lookup (and, without an installed
 * element, a binder read of [Settings.Global]) for every row scrolled in. Defaults to true so
 * previews and any tree composed outside the theme still animate.
 */
internal val LocalLottieMotionEnabled = staticCompositionLocalOf { true }

/**
 * A stroke recolor pinned to an ARGB [Int].
 *
 * The typed return matters: [LottieDynamicProperty]'s only public constructor takes the value
 * positionally, and handing it a trailing lambda instead silently widens `T` to `Any` -- the lambda
 * object itself becomes the stored color and Lottie's cast to Integer crashes on first draw.
 */
internal fun lottieStrokeColor(
    argb: Int,
    keyPath: KeyPath,
): LottieDynamicProperty<Int> = LottieDynamicProperty(LottieProperty.STROKE_COLOR, keyPath, argb)

/**
 * A fill recolor pinned to an ARGB [Int], the sibling of [lottieStrokeColor] for filled shapes.
 *
 * [LottieProperty.COLOR] is the fill/solid-layer color; [LottieProperty.STROKE_COLOR] only reaches
 * stroke contents, so a filled asset recolored through the stroke helper silently keeps the
 * placeholder color baked into its JSON. The same positional-value rule applies: never a lambda.
 */
internal fun lottieFillColor(
    argb: Int,
    keyPath: KeyPath,
): LottieDynamicProperty<Int> = LottieDynamicProperty(LottieProperty.COLOR, keyPath, argb)

/**
 * Resolves the platform animator duration scale for the whole tree. Provided by [MotdTheme].
 *
 * The recomposer publishes a [MotionDurationScale] on every real window (and the Compose test rules
 * inject their own), so that is the authoritative source. [Settings.Global] is only the fallback for
 * contexts carrying no element, and is read lazily so an installed element costs nothing.
 */
@Composable
internal fun resolveLottieMotionEnabled(): Boolean {
    val scope = rememberCoroutineScope()
    val element = scope.coroutineContext[MotionDurationScale]
    val resolver = LocalContext.current.contentResolver
    val fallbackScale =
        remember(resolver, element) {
            if (element != null) {
                null
            } else {
                Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            }
        }
    return MotdLottieMotion.motionEnabled(element?.scaleFactor ?: fallbackScale ?: 1f)
}
