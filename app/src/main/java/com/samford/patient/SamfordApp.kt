package com.samford.patient

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class SamfordApp : Application() {

    /** Replaceable by tests (set it before super.onCreate in a test Application subclass). */
    lateinit var container: AppContainer

    private var foregroundPoll: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (!::container.isInitialized) container = AppContainer(this)
        container.notifier.ensureChannel()
        container.appScope.launch {
            container.appLock.onLaunch()
            container.ensureBackgroundSync()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.appScope.launch { container.appLock.onForeground() }
                startForegroundPolling()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.appLock.onBackground()
                foregroundPoll?.cancel()
                foregroundPoll = null
            }
        })
    }

    /**
     * While the app is visible it polls the notification endpoint every 60 seconds, as the contract
     * suggests. Each poll is normally one 304 Not Modified; the engine's backoff still applies.
     */
    private fun startForegroundPolling() {
        if (!container.backgroundEnabled || foregroundPoll?.isActive == true) return
        foregroundPoll = container.appScope.launch {
            if (container.serverDirectory.state.value.root == null) container.serverDirectory.refresh()
            while (isActive) {
                container.syncCoordinator.run(force = false, reason = "foreground")
                delay(FOREGROUND_POLL_MS)
            }
        }
    }

    private companion object {
        const val FOREGROUND_POLL_MS = 60_000L
    }
}
