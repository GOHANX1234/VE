package com.ve.sandbox

import android.app.Application
import com.ve.sandbox.core.compat.HiddenApiManager

class VeApplication : Application() {
    companion object {
        lateinit var instance: VeApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize Hidden API bypass early in process lifecycle
        HiddenApiManager.unseal()
        // Initialize VE Engine
        com.ve.sandbox.core.VeEngine.init(this)
        // Install ActivityThread Instrumentation and mH Hooks for Phase 3
        com.ve.sandbox.core.stub.ActivityThreadHook.install(packageName)
        // Install Binder IPC System Service Dynamic Proxies for Phase 4
        com.ve.sandbox.core.hook.ServiceManagerHook.installAll(this)
    }
}
