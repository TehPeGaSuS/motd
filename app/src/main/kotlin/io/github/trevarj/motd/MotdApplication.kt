package io.github.trevarj.motd

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp
import io.github.trevarj.motd.di.AppVisibilityImpl
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.push.PushInstanceCoordinator
import io.github.trevarj.motd.push.PushLifecycleCoordinator
import io.github.trevarj.motd.ui.ComposeFoundationWorkarounds
import javax.inject.Inject

@HiltAndroidApp
class MotdApplication : Application(), ImageLoaderFactory {
    // THE UnifiedPush registration trigger: reconciles registered instances against the
    // delivery mode and connectable-network set for the process lifetime.
    @Inject lateinit var pushInstanceCoordinator: PushInstanceCoordinator

    @Inject lateinit var pushLifecycleCoordinator: PushLifecycleCoordinator

    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    // Process-wide "is the user looking at us", read by panes that navigation disposes.
    @Inject lateinit var appVisibility: AppVisibilityImpl

    override fun onCreate() {
        super.onCreate()
        ComposeFoundationWorkarounds.apply()
        diagnosticLogger.record("app", "process_started") {
            mapOf("cold_start" to true)
        }
        appVisibility.start()
        pushInstanceCoordinator.start()
        pushLifecycleCoordinator.start()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            // Coil's GIF and video modules provide their decoders but do not register them by
            // themselves. Keep the platform decoder where available for animated formats.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(VideoFrameDecoder.Factory())
        }
        .build()
}
