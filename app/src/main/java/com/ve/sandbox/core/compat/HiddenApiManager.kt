package com.ve.sandbox.core.compat

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Manages Hidden API restrictions on Android 9+ (API 28 to 34+).
 *
 * Background:
 * Starting in Android 9 (Pie), the Android Runtime (ART) enforces restrictions on reflection
 * against non-SDK (hidden / @hide) methods and fields (e.g. AssetManager.addAssetPath,
 * ActivityThread.currentActivityThread, ServiceManager.getService).
 *
 * By calling VMRuntime.getRuntime().setHiddenApiExemptions(["L"]), we instruct ART that any
 * method signature starting with "L" (which covers all Java classes) is exempt from restriction.
 */
object HiddenApiManager {
    private const val TAG = "VeHiddenApi"
    private var isExempted = false

    @Synchronized
    fun unseal(): Boolean {
        if (isExempted) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            isExempted = true
            return true
        }

        try {
            // Attempt LSPosed HiddenApiBypass (uses Unsafe to invoke VMRuntime.setHiddenApiExemptions)
            HiddenApiBypass.addHiddenApiExemptions("L")
            isExempted = true
            Log.i(TAG, "Successfully bypassed Hidden API restrictions using HiddenApiBypass")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "HiddenApiBypass direct call failed: ${t.message}, attempting double reflection fallback")
        }

        try {
            // Fallback: double reflection
            val forNameMethod = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = forNameMethod.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntimeMethod = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as java.lang.reflect.Method
            val setHiddenApiExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass,
                "setHiddenApiExemptions",
                arrayOf(arrayOf<String>()::class.java)
            ) as java.lang.reflect.Method

            val vmRuntimeInstance = getRuntimeMethod.invoke(null)
            setHiddenApiExemptions.invoke(vmRuntimeInstance, arrayOf("L"))
            isExempted = true
            Log.i(TAG, "Successfully bypassed Hidden API restrictions using double reflection")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bypass Hidden API restrictions", t)
            return false
        }
    }
}
