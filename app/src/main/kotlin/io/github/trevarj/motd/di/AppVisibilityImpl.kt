package io.github.trevarj.motd.di

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.trevarj.motd.service.AppVisibility
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AppVisibility] over [ProcessLifecycleOwner].
 *
 * Started from `MotdApplication.onCreate`, like the other process-lifetime observers: registering
 * there keeps the touch on `ProcessLifecycleOwner` (main-thread only) off whatever thread Hilt
 * happens to build this singleton on, and seeds the first value before any screen can read it.
 */
@Singleton
class AppVisibilityImpl @Inject constructor() : AppVisibility, DefaultLifecycleObserver {
    private val _onScreen = MutableStateFlow(false)
    override val onScreen: StateFlow<Boolean> = _onScreen.asStateFlow()

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        // Seeded rather than left false: onStart has already fired for the caller that warm-started
        // the process from a notification tap, and no further event would correct it.
        _onScreen.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { _onScreen.value = true }

    override fun onStop(owner: LifecycleOwner) { _onScreen.value = false }
}
