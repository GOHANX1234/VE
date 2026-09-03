package com.ve.sandbox.core.stub

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build

/**
 * Manages the masquerade and demasquerade of Intents targeting uninstalled guest Activities.
 *
 * Android Internals Mental Model:
 * 1. Android's ActivityTaskManagerService (ATMS) verifies that any Activity being launched
 *    is registered with PackageManagerService (PMS) in an installed AndroidManifest.xml.
 * 2. An uninstalled guest APK's Activities do NOT exist in the real PMS registry.
 * 3. StubManager wraps the guest Activity's Intent into a host-declared "Stub" Activity.
 * 4. ATMS inspects the host's manifest, validates the Stub Activity, and authorizes the launch.
 * 5. Upon arrival in our process, StubManager unpacks the real Intent so our engine can swap
 *    in the guest Activity bytecode before onCreate() is executed.
 */
object StubManager {
    const val EXTRA_REAL_INTENT = "_ve_real_intent_"
    const val EXTRA_TARGET_PACKAGE = "_ve_target_pkg_"
    const val EXTRA_TARGET_CLASS = "_ve_target_class_"

    val STUB_STANDARD = StubActivity::class.java.name
    val STUB_SINGLE_TOP = StubSingleTopActivity::class.java.name
    val STUB_SINGLE_TASK = StubSingleTaskActivity::class.java.name
    val STUB_SINGLE_INSTANCE = StubSingleInstanceActivity::class.java.name

    private val allStubClasses = setOf(
        STUB_STANDARD,
        STUB_SINGLE_TOP,
        STUB_SINGLE_TASK,
        STUB_SINGLE_INSTANCE
    )

    fun isStubComponent(className: String?): Boolean {
        if (className == null) return false
        return allStubClasses.contains(className)
    }

    fun isStubIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.hasExtra(EXTRA_REAL_INTENT)) return true
        val componentName = intent.component?.className ?: return false
        return isStubComponent(componentName)
    }

    /**
     * Replaces the real Intent targeting a guest Activity with an Intent targeting
     * one of our host-declared Stub Activities matching the requested launchMode.
     */
    fun masqueradeIntent(
        realIntent: Intent,
        hostPackageName: String,
        launchMode: Int = ActivityInfo.LAUNCH_MULTIPLE
    ): Intent {
        val targetComponent = realIntent.component
            ?: throw IllegalArgumentException("Explicit Intent with ComponentName required for Activity masquerade")

        val stubClass = when (launchMode) {
            ActivityInfo.LAUNCH_SINGLE_TOP -> STUB_SINGLE_TOP
            ActivityInfo.LAUNCH_SINGLE_TASK -> STUB_SINGLE_TASK
            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> STUB_SINGLE_INSTANCE
            else -> STUB_STANDARD
        }

        val stubIntent = Intent().apply {
            component = ComponentName(hostPackageName, stubClass)
            // Preserve flags from original intent (e.g. FLAG_ACTIVITY_NEW_TASK)
            flags = realIntent.flags
            putExtra(EXTRA_REAL_INTENT, realIntent)
            putExtra(EXTRA_TARGET_PACKAGE, targetComponent.packageName)
            putExtra(EXTRA_TARGET_CLASS, targetComponent.className)
        }

        return stubIntent
    }

    /**
     * Unpacks the original guest Intent from a masqueraded Stub Intent.
     */
    fun demasqueradeIntent(stubIntent: Intent?): Intent? {
        if (stubIntent == null || !stubIntent.hasExtra(EXTRA_REAL_INTENT)) return null
        return try {
            try {
                stubIntent.setExtrasClassLoader(StubManager::class.java.classLoader)
            } catch (ignored: Throwable) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stubIntent.getParcelableExtra(EXTRA_REAL_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                stubIntent.getParcelableExtra(EXTRA_REAL_INTENT)
            }
        } catch (e: Throwable) {
            null
        }
    }

    fun extractTargetPackage(stubIntent: Intent?): String? {
        return stubIntent?.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: demasqueradeIntent(stubIntent)?.component?.packageName
    }

    fun extractTargetClass(stubIntent: Intent?): String? {
        return stubIntent?.getStringExtra(EXTRA_TARGET_CLASS)
            ?: demasqueradeIntent(stubIntent)?.component?.className
    }
}
