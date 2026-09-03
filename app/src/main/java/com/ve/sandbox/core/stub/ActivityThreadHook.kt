package com.ve.sandbox.core.stub

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Message
import android.util.Log
import com.ve.sandbox.core.compat.HiddenApiManager
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Hooks into the Android runtime's ActivityThread singleton to install:
 * 1. VeInstrumentation (replaces ActivityThread.mInstrumentation)
 * 2. ActivityThread.mH.mCallback (intercepts EXECUTE_TRANSACTION and LAUNCH_ACTIVITY)
 */
object ActivityThreadHook {
    private const val TAG = "ActivityThreadHook"
    private var isHooked = false

    @Synchronized
    fun install(hostPackageName: String): Boolean {
        if (isHooked) return true

        HiddenApiManager.unseal()

        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread").apply {
                isAccessible = true
            }
            val activityThread = currentActivityThreadMethod.invoke(null)
                ?: throw IllegalStateException("ActivityThread.currentActivityThread() returned null")

            // 1. Hook mInstrumentation
            val mInstrumentationField = activityThreadClass.getDeclaredField("mInstrumentation").apply {
                isAccessible = true
            }
            val currentInstrumentation = mInstrumentationField.get(activityThread) as Instrumentation

            if (currentInstrumentation !is VeInstrumentation) {
                val veInstrumentation = VeInstrumentation(currentInstrumentation, hostPackageName)
                copyInstrumentationFields(currentInstrumentation, veInstrumentation)
                mInstrumentationField.set(activityThread, veInstrumentation)
                Log.i(TAG, "Successfully installed VeInstrumentation into ActivityThread.mInstrumentation")
            }

            // 2. Hook mH Handler Callback (Android 9+ ClientTransaction & legacy LAUNCH_ACTIVITY)
            try {
                hookHandlerCallback(activityThread, activityThreadClass)
            } catch (t: Throwable) {
                Log.w(TAG, "Optional mH Handler hook failed, relying on VeInstrumentation", t)
            }

            isHooked = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook ActivityThread", t)
            false
        }
    }

    private fun copyInstrumentationFields(source: Instrumentation, destination: Instrumentation) {
        var clazz: Class<*>? = Instrumentation::class.java
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(source)
                    field.set(destination, value)
                } catch (e: Throwable) {
                    // Ignore inaccessible or synthetic fields
                }
            }
            clazz = clazz.superclass
        }
    }

    private fun hookHandlerCallback(activityThread: Any, activityThreadClass: Class<*>) {
        val mHField = activityThreadClass.getDeclaredField("mH").apply { isAccessible = true }
        val mH = mHField.get(activityThread) as Handler

        val mCallbackField = Handler::class.java.getDeclaredField("mCallback").apply { isAccessible = true }
        val currentCallback = mCallbackField.get(mH) as? Handler.Callback

        val proxyCallback = Handler.Callback { msg ->
            handleActivityThreadMessage(msg)
            currentCallback?.handleMessage(msg) ?: false
        }

        mCallbackField.set(mH, proxyCallback)
        Log.i(TAG, "Successfully attached Handler.Callback to ActivityThread.mH")
    }

    private fun handleActivityThreadMessage(msg: Message) {
        // Message codes in ActivityThread$H:
        // 100 = LAUNCH_ACTIVITY (Android <= 8.1)
        // 159 = EXECUTE_TRANSACTION (Android 9.0+)
        try {
            if (msg.what == 100) {
                val record = msg.obj ?: return
                val intentField = record.javaClass.getDeclaredField("intent").apply { isAccessible = true }
                val intent = intentField.get(record) as? Intent
                if (StubManager.isStubIntent(intent)) {
                    val realIntent = StubManager.demasqueradeIntent(intent)
                    if (realIntent != null) {
                        intentField.set(record, realIntent)
                        Log.d(TAG, "mH callback demasqueraded legacy LAUNCH_ACTIVITY intent")
                    }
                }
            } else if (msg.what == 159) {
                val transaction = msg.obj ?: return
                demasqueradeClientTransaction(transaction)
            }
        } catch (e: Throwable) {
            // Non-fatal, VeInstrumentation.newActivity is the primary interceptor
        }
    }

    private fun demasqueradeClientTransaction(transaction: Any) {
        try {
            val getCallbacksMethod = transaction.javaClass.getDeclaredMethod("getCallbacks").apply {
                isAccessible = true
            }
            val callbacks = getCallbacksMethod.invoke(transaction) as? List<*> ?: return

            for (item in callbacks) {
                if (item != null && item.javaClass.name.contains("LaunchActivityItem")) {
                    val mIntentField = item.javaClass.getDeclaredField("mIntent").apply { isAccessible = true }
                    val intent = mIntentField.get(item) as? Intent
                    if (StubManager.isStubIntent(intent)) {
                        val realIntent = StubManager.demasqueradeIntent(intent)
                        if (realIntent != null) {
                            mIntentField.set(item, realIntent)
                            Log.d(TAG, "mH callback demasqueraded Android 9+ ClientTransaction intent")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Ignored
        }
    }
}
