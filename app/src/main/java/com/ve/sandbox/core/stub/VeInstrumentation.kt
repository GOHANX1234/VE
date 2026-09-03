package com.ve.sandbox.core.stub

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.os.Bundle
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import android.view.ContextThemeWrapper
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.compat.HiddenApiManager
import com.ve.sandbox.core.context.ProxyContext
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * VeInstrumentation: The core interceptor for Activity virtualization.
 *
 * Android Internals Mental Model:
 * 1. Outbound (execStartActivity):
 *    Intercepts startActivity() before the Binder IPC call to ActivityTaskManagerService (ATMS).
 *    Masquerades the guest Intent into a host-declared StubActivity Intent so PMS validation passes.
 *
 * 2. Inbound (newActivity):
 *    When ATMS instructs our app process to launch the StubActivity, newActivity() intercepts
 *    the instantiation, extracts the real guest Intent, and instantiates the REAL target
 *    Activity class from the guest APK using our VirtualClassLoader.
 *
 * 3. Lifecycle Binding (callActivityOnCreate):
 *    Before onCreate() executes, injects the ProxyContext, guest Resources, and guest Application
 *    into the newly minted Activity, ensuring layouts, views, and APIs operate in the sandbox.
 */
class VeInstrumentation(
    val baseInstrumentation: Instrumentation,
    private val hostPackageName: String
) : Instrumentation() {

    companion object {
        private const val TAG = "VeInstrumentation"
    }

    init {
        HiddenApiManager.unseal()
    }

    private fun masqueradeIfNeeded(intent: Intent): Intent {
        val targetPkg = intent.component?.packageName
        return if (targetPkg != null && targetPkg != hostPackageName) {
            val engine = try { VeEngine.get() } catch (e: Exception) { null }
            val loadedPkg = engine?.getLoadedPackage(targetPkg)
            if (loadedPkg != null) {
                val targetClass = intent.component?.className
                val comp = loadedPkg.manifest.activities.firstOrNull { it.name == targetClass }
                val launchMode = ActivityInfo.LAUNCH_MULTIPLE
                Log.d(TAG, "Masquerading guest Activity launch: $targetClass -> StubActivity")
                StubManager.masqueradeIntent(intent, hostPackageName, launchMode)
            } else {
                intent
            }
        } else {
            intent
        }
    }

    // -------------------------------------------------------------
    // Step 1: Outbound Intent Masquerade (execStartActivity Overloads)
    // -------------------------------------------------------------

    // Overload 1: Context, IBinder, IBinder, Activity, Intent, int, Bundle
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        target: Activity?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val masqueradedIntent = masqueradeIfNeeded(intent)
        return try {
            val execMethod = Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            ).apply { isAccessible = true }

            execMethod.invoke(
                baseInstrumentation,
                who,
                contextThread,
                token,
                target,
                masqueradedIntent,
                requestCode,
                options
            ) as? ActivityResult
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to invoke baseInstrumentation.execStartActivity (Activity target)", t)
            null
        }
    }

    // Overload 2: Context, IBinder, IBinder, String, Intent, int, Bundle (non-Activity context)
    fun execStartActivity(
        who: Context,
        contextThread: IBinder,
        token: IBinder?,
        resultWho: String?,
        intent: Intent,
        requestCode: Int,
        options: Bundle?
    ): ActivityResult? {
        val masqueradedIntent = masqueradeIfNeeded(intent)
        return try {
            val execMethod = Instrumentation::class.java.getDeclaredMethod(
                "execStartActivity",
                Context::class.java,
                IBinder::class.java,
                IBinder::class.java,
                String::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            ).apply { isAccessible = true }

            execMethod.invoke(
                baseInstrumentation,
                who,
                contextThread,
                token,
                resultWho,
                masqueradedIntent,
                requestCode,
                options
            ) as? ActivityResult
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to invoke baseInstrumentation.execStartActivity (String resultWho)", t)
            null
        }
    }

    // -------------------------------------------------------------
    // Step 2: Inbound Class Swap (newActivity)
    // -------------------------------------------------------------

    override fun newActivity(
        cl: ClassLoader,
        className: String,
        intent: Intent
    ): Activity {
        if (StubManager.isStubComponent(className) || StubManager.isStubIntent(intent)) {
            val realIntent = StubManager.demasqueradeIntent(intent)
            val realPkg = StubManager.extractTargetPackage(intent) ?: realIntent?.component?.packageName
            val realClass = StubManager.extractTargetClass(intent) ?: realIntent?.component?.className

            if (realPkg != null && realClass != null) {
                val engine = try { VeEngine.get() } catch (e: Exception) { null }
                val loadedPkg = engine?.getLoadedPackage(realPkg)
                if (loadedPkg != null) {
                    Log.i(TAG, "Swapping stub '$className' with real guest Activity: '$realClass'")
                    try {
                        val guestActivityClass = loadedPkg.classLoader.loadClass(realClass)
                        val activity = guestActivityClass.getDeclaredConstructor().newInstance() as Activity

                        if (realIntent != null) {
                            intent.component = realIntent.component
                        }
                        return activity
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to instantiate real guest Activity: $realClass", e)
                    }
                }
            }
        }

        return baseInstrumentation.newActivity(cl, className, intent)
    }

    // -------------------------------------------------------------
    // Step 3: Lifecycle Hook & Context Injection (callActivityOnCreate)
    // -------------------------------------------------------------

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        injectGuestContextAndResources(activity)
        try {
            baseInstrumentation.callActivityOnCreate(activity, icicle)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during guest Activity onCreate(): ${activity.javaClass.name}", t)
            throw t
        }
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?
    ) {
        injectGuestContextAndResources(activity)
        try {
            baseInstrumentation.callActivityOnCreate(activity, icicle, persistentState)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during guest Activity onCreate(PersistableBundle): ${activity.javaClass.name}", t)
            throw t
        }
    }

    private fun injectGuestContextAndResources(activity: Activity) {
        val activityClass = activity.javaClass
        val className = activityClass.name
        val engine = try { VeEngine.get() } catch (e: Exception) { null } ?: return

        // Extract target package from intent or search all loaded packages
        val targetPkgFromIntent = StubManager.extractTargetPackage(activity.intent)
            ?: activity.intent?.component?.packageName

        val loadedPkg = if (targetPkgFromIntent != null && targetPkgFromIntent != hostPackageName) {
            engine.getLoadedPackage(targetPkgFromIntent)
        } else {
            engine.getInstalledPackages().mapNotNull {
                engine.getLoadedPackage(it.packageName)
            }.firstOrNull { pkg ->
                pkg.manifest.activities.any { it.name == className } ||
                        activityClass.classLoader == pkg.classLoader
            }
        } ?: return

        Log.i(TAG, "Injecting ProxyContext & Resources into guest Activity: $className")

        val proxyContext = engine.getProxyContext(loadedPkg.packageName) ?: return

        try {
            // 1. Replace mBase on ContextWrapper with ProxyContext
            val mBaseField = findFieldInHierarchy(activity.javaClass, "mBase")
            mBaseField?.let { field ->
                field.isAccessible = true
                field.set(activity, proxyContext)
                Log.d(TAG, "Successfully injected ProxyContext into mBase of $className")
            }

            // 2. Replace mResources on ContextThemeWrapper / Activity
            val mResourcesField = findFieldInHierarchy(activity.javaClass, "mResources")
            mResourcesField?.let { field ->
                field.isAccessible = true
                field.set(activity, loadedPkg.resources)
                Log.d(TAG, "Successfully injected guest Resources into $className")
            }

            // 3. Set guest Application on Activity.mApplication
            val guestApp = engine.startGuestApplication(loadedPkg)
            val mAppField = findFieldInHierarchy(activity.javaClass, "mApplication")
            mAppField?.let { field ->
                field.isAccessible = true
                field.set(activity, guestApp)
                Log.d(TAG, "Successfully bound guest Application to $className")
            }

            // 4. Apply guest Activity theme if declared
            try {
                val comp = loadedPkg.manifest.activities.firstOrNull { it.name == className }
                val themeResName = comp?.theme ?: loadedPkg.manifest.applicationTheme
                if (themeResName != null) {
                    val cleanName = themeResName.substringAfterLast('/')
                    val themeId = loadedPkg.resources.getIdentifier(cleanName, "style", loadedPkg.packageName)
                    if (themeId != 0) {
                        activity.setTheme(themeId)
                        Log.d(TAG, "Applied guest theme: $themeResName (id=$themeId) to $className")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not apply guest theme to $className: ${e.message}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error injecting guest context into Activity: $className", t)
        }
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (e: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    // -------------------------------------------------------------
    // Lifecycle Delegation
    // -------------------------------------------------------------

    override fun callActivityOnStart(activity: Activity) = baseInstrumentation.callActivityOnStart(activity)
    override fun callActivityOnResume(activity: Activity) = baseInstrumentation.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = baseInstrumentation.callActivityOnPause(activity)
    override fun callActivityOnStop(activity: Activity) = baseInstrumentation.callActivityOnStop(activity)
    override fun callActivityOnDestroy(activity: Activity) = baseInstrumentation.callActivityOnDestroy(activity)
    override fun callActivityOnRestart(activity: Activity) = baseInstrumentation.callActivityOnRestart(activity)
    override fun callActivityOnRestoreInstanceState(activity: Activity, savedInstanceState: Bundle) =
        baseInstrumentation.callActivityOnRestoreInstanceState(activity, savedInstanceState)
    override fun callActivityOnSaveInstanceState(activity: Activity, outState: Bundle) =
        baseInstrumentation.callActivityOnSaveInstanceState(activity, outState)

    override fun finish(resultCode: Int, results: Bundle?) {
        try {
            baseInstrumentation.finish(resultCode, results)
        } catch (e: Throwable) {
            // Safe fallback if underlying thread is already terminated
        }
    }

    override fun onDestroy() {
        try {
            baseInstrumentation.onDestroy()
        } catch (e: Throwable) {
            // Safe fallback
        }
    }
}
