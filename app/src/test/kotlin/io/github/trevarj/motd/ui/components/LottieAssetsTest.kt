package io.github.trevarj.motd.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.model.KeyPath
import io.github.trevarj.motd.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The three hand-authored bodymovin assets parse, share the 60fps timebase, and expose exactly the
 * layer names the call sites recolor through. A typo in a keypath is otherwise silent: Lottie just
 * renders the placeholder stroke color baked into the JSON.
 */
@RunWith(RobolectricTestRunner::class)
class LottieAssetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private companion object {
        /** Lottie parks `endFrame` a hundredth short of `op` so the last frame stays drawable. */
        const val END_FRAME_TOLERANCE = 0.02f
    }

    private fun load(rawRes: Int): LottieComposition {
        val result = LottieCompositionFactory.fromRawResSync(context, rawRes)
        assertNull(result.exception)
        return requireNotNull(result.value)
    }

    /** Keypaths resolve against a drawable, which is where the runtime applies them too. */
    private fun LottieComposition.resolves(vararg keyPath: String) =
        LottieDrawable().apply { composition = this@resolves }
            .resolveKeyPath(KeyPath(*keyPath))
            .isNotEmpty()

    @Test fun `every asset shares the 60fps timebase`() {
        listOf(R.raw.status_delivered, R.raw.connection_state, R.raw.onboarding_hero).forEach { res ->
            assertEquals(60f, load(res).frameRate, 0f)
        }
    }

    @Test fun `the delivery tick is one short one-shot holding both glyphs`() {
        val composition = load(R.raw.status_delivered)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(23f, composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("clock", "**"))
        assertTrue(composition.resolves("check", "**"))
    }

    @Test fun `the connection asset spans both banner beats`() {
        val composition = load(R.raw.connection_state)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(ConnectionStateFrames.Total.toFloat(), composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("arc", "**"))
        assertTrue(composition.resolves("check", "**"))
    }

    @Test fun `the resolve beat is short enough to survive the banner exit fade`() {
        // 63 frames total at 60fps: a 900ms arc loop plus a resolve that lands inside the banner's
        // own 140ms fade-out. A longer resolve would draw the check after the row is invisible.
        assertEquals(63f, load(R.raw.connection_state).endFrame, END_FRAME_TOLERANCE)
    }

    @Test fun `the onboarding hero names the glyph and each staggered ray`() {
        val composition = load(R.raw.onboarding_hero)

        assertEquals(0f, composition.startFrame, 0f)
        assertEquals(42f, composition.endFrame, END_FRAME_TOLERANCE)
        assertTrue(composition.resolves("hero_glyph", "**"))
        assertTrue(composition.resolves("hero_ray_1", "**"))
        assertTrue(composition.resolves("hero_ray_2", "**"))
        assertTrue(composition.resolves("hero_ray_3", "**"))
    }
}
