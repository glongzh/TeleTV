package com.teletv

import android.app.Application
import com.teletv.analytics.Analytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TeleTvApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Before ServiceLocator: auth starts inside init() and its first events
        // would otherwise be captured against an uninitialized SDK.
        Analytics.init(this)
        // init() builds the first generation and starts auth internally.
        ServiceLocator.init(this, appScope)
    }
}
