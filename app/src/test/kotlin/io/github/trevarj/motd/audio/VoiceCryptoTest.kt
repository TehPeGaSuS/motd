package io.github.trevarj.motd.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceCryptoTest {
    @Test fun encryptedVoiceRoundTripsThroughKeyFragment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = VoiceCrypto(AudioCacheStore(context))
        val plain = File.createTempFile("voice-plain-", ".opus", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }

        val encrypted = crypto.encrypt(plain)
        val decrypted = crypto.decrypt(encrypted.file, encrypted.keyFragment)

        assertTrue(encrypted.keyFragment.startsWith("motd-key="))
        assertArrayEquals(plain.readBytes(), decrypted.readBytes())
    }
}
