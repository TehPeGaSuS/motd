package io.github.trevarj.motd.audio

import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioPlaybackServiceManifestTest {
    @Test fun mediaSessionServiceIsResolvableByMedia3() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = context.packageManager.queryIntentServices(
            Intent(MediaSessionService.SERVICE_INTERFACE).setPackage(context.packageName),
            0,
        )

        assertTrue(
            services.any { it.serviceInfo.name == AudioPlaybackService::class.java.name },
        )
    }
}
