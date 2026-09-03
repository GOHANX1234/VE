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
        val isStub = StubManager.isStubComponent(className) || StubManager.isStubIntent(intent)
        val realIntent = if (isStub) StubManager.demasqueradeIntent(intent) else intent

        // 1. Determine target package
        var targetPkg = if (isStub) {
            StubManager.extractTargetPackage(intent) ?: realIntent?.component?.packageName
        } else {
            intent.component?.packageName?.takeIf { it != hostPackageName }
                ?: StubManager.extractTargetPackage(intent)
        }

        // 2. Determine target class
        var targetClass = if (isStub) {
            StubManager.extractTargetClass(intent) ?: realIntent?.component?.className
        } else {
            if (!StubManager.isStubComponent(className)) className
            else StubManager.extractTargetClass(intent) ?: realIntent?.component?.className
        }

        val engine = try { VeEngine.get() } catch (e: Exception) { null }

        // If package not determined yet, check if any installed/loaded sandbox package owns this class
        if (targetPkg == null && engine != null) {
            val candidate = targetClass ?: className
            targetPkg = engine.getInstalledPackages().firstOrNull { pkg ->
                pkg.manifest.activities.any { it.name == candidate }
            }?.packageName
        }

        // If it targets a guest sandbox package, load via VirtualClassLoader
        if (targetPkg != null && targetPkg != hostPackageName) {
            val loadedPkg = engine?.let { eng ->
                eng.getLoadedPackage(targetPkg) ?: eng.getInstalledPackages().firstOrNull { it.packageName == targetPkg }?.let {
                    try { eng.load(it) } catch (e: Throwable) { null }
                }
            }

            if (loadedPkg != null) {
                val finalClass = targetClass ?: className
                Log.i(TAG, "Instantiating guest Activity: '$finalClass' for package '$targetPkg'")

                try {
                    Thread.currentThread().contextClassLoader = loadedPkg.classLoader
                } catch (ignored: Throwable) {}

                val targetIntent = realIntent ?: intent
                try {
                    targetIntent.setExtrasClassLoader(loadedPkg.classLoader)
                } catch (ignored: Throwable) {}

                if (isStub && realIntent != null) {
                    intent.component = realIntent.component
                    intent.action = realIntent.action
                    intent.data = realIntent.data
                    intent.type = realIntent.type
                    intent.flags = realIntent.flags
                    realIntent.categories?.forEach { intent.addCategory(it) }
                    realIntent.extras?.let { intent.putExtras(it) }
                    intent.removeExtra(StubManager.EXTRA_REAL_INTENT)
                    intent.removeExtra(StubManager.EXTRA_TARGET_PACKAGE)
                    intent.removeExtra(StubManager.EXTRA_TARGET_CLASS)
                }

                try {
                    return baseInstrumentation.newActivity(loadedPkg.classLoader, finalClass, targetIntent)
                } catch (t: Throwable) {
                    Log.w(TAG, "baseInstrumentation.newActivity failed for $finalClass with guest ClassLoader, trying reflection: ${t.message}")
                    try {
                        val guestActivityClass = loadedPkg.classLoader.loadClass(finalClass)
                        val constructor = guestActivityClass.getDeclaredConstructor().apply { isAccessible = true }
                        return constructor.newInstance() as Activity
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to reflectively instantiate guest Activity: $finalClass", e)
                        throw e
                    }
                }
            }
        }

        // Host activity or system component
        try {
            return baseInstrumentation.newActivity(cl, className, intent)
        } catch (cnf: ClassNotFoundException) {
            // Safety net: if host ClassLoader cannot find className, search all guest VirtualClassLoaders
            if (engine != null) {
                for (pkg in engine.getInstalledPackages()) {
                    val loaded = engine.getLoadedPackage(pkg.packageName)
                        ?: try { engine.load(pkg) } catch (e: Throwable) { null }
                    if (loaded != null) {
                        try {
                            val clazz = loaded.classLoader.loadClass(className)
                            Log.i(TAG, "Safety net: Resolved '$className' from guest package '${pkg.packageName}'")
                            try {
                                intent.setExtrasClassLoader(loaded.classLoader)
                            } catch (ignored: Throwable) {}
                            val constructor = clazz.getDeclaredConstructor().apply { isAccessible = true }
                            return constructor.newInstance() as Activity
                        } catch (ignored: ClassNotFoundException) {}
                    }
                }
            }
            throw cnf
        }
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

        val loadedPkg = (if (targetPkgFromIntent != null && targetPkgFromIntent != hostPackageName) {
            engine.getLoadedPackage(targetPkgFromIntent) ?: engine.getInstalledPackages().firstOrNull { it.packageName == targetPkgFromIntent }?.let {
                try { engine.load(it) } catch (e: Throwable) { null }
            }
        } else null) ?: run {
            engine.getInstalledPackages().mapNotNull { pkg ->
                engine.getLoadedPackage(pkg.packageName) ?: try { engine.load(pkg) } catch (e: Throwable) { null }
            }.firstOrNull { pkg ->
                pkg.manifest.activities.any { it.name == className } ||
                        activityClass.classLoader == pkg.classLoader
            }
        } ?: return

        Log.i(TAG, "Injecting ProxyContext & Resources into guest Activity: $className")

        try {
            Thread.currentThread().contextClassLoader = loadedPkg.classLoader
        } catch (ignored: Throwable) {}

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

            // 4. Update Activity.mIntent and mComponent if needed
            val mIntentField = findFieldInHierarchy(activity.javaClass, "mIntent")
            mIntentField?.let { field ->
                field.isAccessible = true
                val currentIntent = field.get(activity) as? Intent
                if (currentIntent != null && StubManager.isStubIntent(currentIntent)) {
                    val demasqueraded = StubManager.demasqueradeIntent(currentIntent)
                    if (demasqueraded != null) {
                        field.set(activity, demasqueraded)
                    }
                }
            }

            val mComponentField = findFieldInHierarchy(activity.javaClass, "mComponent")
            mComponentField?.let { field ->
                field.isAccessible = true
                val currentComp = field.get(activity) as? ComponentName
                if (currentComp == null || StubManager.isStubComponent(currentComp.className)) {
                    field.set(activity, ComponentName(loadedPkg.packageName, className))
                }
            }

            val mActivityInfoField = findFieldInHierarchy(activity.javaClass, "mActivityInfo")
            mActivityInfoField?.let { field ->
                field.isAccessible = true
                val comp = loadedPkg.manifest.activities.firstOrNull { it.name == className }
                if (comp != null) {
                    field.set(activity, com.ve.sandbox.core.hook.PackageInfoSynthesizer.buildActivityInfo(loadedPkg, comp))
                }
            }

            // 5. Apply guest Activity theme if declared
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
