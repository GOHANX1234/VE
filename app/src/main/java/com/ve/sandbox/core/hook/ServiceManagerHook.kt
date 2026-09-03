package com.ve.sandbox.core.hook

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.ve.sandbox.core.compat.HiddenApiManager
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * ServiceManagerHook: Coordinates the installation of all System Service Binder IPC proxies.
 *
 * Intercepts:
 * 1. IPackageManager (ActivityThread.sPackageManager)
 * 2. IActivityManager (ActivityManager.IActivityManagerSingleton)
 * 3. IActivityTaskManager (ActivityTaskManager.IActivityTaskManagerSingleton)
 */
object ServiceManagerHook {
    private const val TAG = "VeServiceManagerHook"
    private var isInstalled = false

    @Synchronized
    fun installAll(context: Context): Boolean {
        if (isInstalled) return true

        HiddenApiManager.unseal()

        val hostPkg = context.packageName
        Log.i(TAG, "Installing system service Binder hooks for host package: $hostPkg")

        val pmSuccess = PackageManagerHook.install()
        val amSuccess = ActivityManagerHook.install(hostPkg)

        isInstalled = pmSuccess && amSuccess
        Log.i(TAG, "Service hooks installation result: PM=$pmSuccess, AM=$amSuccess (overall=$isInstalled)")
        return isInstalled
    }
}
