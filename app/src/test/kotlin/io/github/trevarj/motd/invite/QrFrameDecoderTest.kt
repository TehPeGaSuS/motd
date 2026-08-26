package io.github.trevarj.motd.invite

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrFrameDecoderTest {
    @Test
    fun `generated invitation decodes from padded luminance frame`() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = JoinInviteCodec.installUri(JoinInviteV1(networkName = "Ergo", host = "irc.example", port = 6697, channel = "#friends"))
        val bitmap = inviteQrBitmap(text, 320)
        val stride = bitmap.width + 16
        val bytes = ByteArray(stride * bitmap.height) { 0x7f }
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                bytes[y * stride + x] = if ((pixel and 0xFF) < 128) 0 else 0xFF.toByte()
            }
        }

        assertEquals(text, decodeQrFrame(bytes, bitmap.width, bitmap.height, stride, 0))
    }

    @Test
    fun `empty frame is ignored`() {
        assertNull(decodeQrFrame(ByteArray(100), 10, 10, 10, 0))
    }
}
