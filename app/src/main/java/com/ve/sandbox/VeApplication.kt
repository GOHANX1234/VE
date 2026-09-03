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

        // Install global uncaught exception logger to capture guest app and container crash reports
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = java.io.StringWriter()
                val pw = java.io.PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()
                android.util.Log.e("VE_CRASH", "FATAL UNCAUGHT EXCEPTION in thread ${thread.name}:\n$stackTrace")
                java.io.File(filesDir, "last_crash.txt").writeText(
                    "Crash in thread '${thread.name}' (${throwable.javaClass.name}):\n${throwable.message}\n\n$stackTrace"
                )
            } catch (ignored: Throwable) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

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
