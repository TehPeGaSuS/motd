package io.github.trevarj.motd.di

import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

fun interface AppClock {
    fun nowMillis(): Long
}

private const val APP_SCOPE_TAG = "AppScope"

/**
 * Last-resort containment for a root [ApplicationScope] coroutine that throws.
 *
 * `SupervisorJob` only stops a failure from cancelling its siblings; it does not absorb the throw,
 * so without a handler an uncaught failure in any of the scope's ~30 root `launch` sites (Room
 * `observeAll` collectors, DataStore collectors, reconnect catch-up) reaches the thread's default
 * handler and kills the process. Containment applies to every build type on purpose: the `e2e`
 * build type runs the required headless gate, where a debug-only rethrow would convert a flake
 * into a crash.
 *
 * Only the failure's type is recorded. [DiagnosticLogger] must never receive user data and a
 * throwable's message routinely carries hosts, nicks, or message bodies; the full stack trace goes
 * to logcat instead.
 */
internal fun applicationExceptionHandler(diagnostics: DiagnosticLogger): CoroutineExceptionHandler =
    CoroutineExceptionHandler { context, error ->
        // The handler is the last line of defense, so it must not be able to throw itself.
        runCatching {
            Log.e(APP_SCOPE_TAG, "uncaught failure on the application scope", error)
            diagnostics.record("app", "scope_failure") {
                mapOf(
                    "error" to error::class.simpleName,
                    "coroutine" to context[CoroutineName]?.name,
                )
            }
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
        diagnostics: DiagnosticLogger,
    ): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + dispatcher + applicationExceptionHandler(diagnostics),
        )

    @Provides
    @Singleton
    fun appClock(): AppClock = AppClock(System::currentTimeMillis)
}
