package com.ve.sandbox.core.context

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.ve.sandbox.core.compat.HiddenApiManager
import com.ve.sandbox.core.model.LoadedPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages instantiation and lifecycle execution of the guest Application instance.
 *
 * Android Internals Mental Model:
 * 1. When an Android app starts, ActivityThread creates a LoadedApk and calls makeApplication().
 * 2. It instantiates the Application subclass declared in <application android:name="...">.
 * 3. It attaches the Context via attachBaseContext(context) (or Application.attach(context)).
 * 4. Finally, it invokes application.onCreate().
 * 5. In VE, we replicate this exact sequence, attaching our ProxyContext as the base context.
 */
class GuestApplicationManager(
    private val hostContext: Context
) {
    companion object {
        private const val TAG = "GuestAppManager"
    }

    private val applicationMap = ConcurrentHashMap<String, Application>()
    private val proxyContextMap = ConcurrentHashMap<String, ProxyContext>()

    /**
     * Obtains or creates the ProxyContext for a loaded package.
     */
    fun getOrCreateProxyContext(loadedPackage: LoadedPackage): ProxyContext {
        return proxyContextMap.computeIfAbsent(loadedPackage.packageName) {
            ProxyContext(
                base = hostContext,
                loadedPackage = loadedPackage,
                guestAppSupplier = { applicationMap[loadedPackage.packageName] }
            )
        }
    }

    /**
     * Instantiates the guest Application class, attaches the ProxyContext,
     * and calls onCreate().
     */
    @Synchronized
    fun startGuestApplication(loadedPackage: LoadedPackage): Application {
        val existing = applicationMap[loadedPackage.packageName]
        if (existing != null) return existing

        HiddenApiManager.unseal()

        val proxyContext = getOrCreateProxyContext(loadedPackage)
        val className = loadedPackage.manifest.applicationClassName ?: "android.app.Application"
        Log.i(TAG, "Creating guest Application: $className for package: ${loadedPackage.packageName}")

        val appClass = loadedPackage.classLoader.loadClass(className)
        val guestApp = appClass.getConstructor().newInstance() as Application

        // Attach ProxyContext as the base context
        try {
            val mBaseField = ContextWrapper::class.java.getDeclaredField("mBase").apply {
                isAccessible = true
            }
            mBaseField.set(guestApp, proxyContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not directly set mBase field on guest Application: ${t.message}")
        }

        try {
            val attachBaseContextMethod = ContextWrapper::class.java.getDeclaredMethod(
                "attachBaseContext",
                Context::class.java
            ).apply {
                isAccessible = true
            }
            attachBaseContextMethod.invoke(guestApp, proxyContext)
            Log.d(TAG, "Successfully attached ProxyContext to guest Application: $className")
        } catch (t: Throwable) {
            Log.w(TAG, "attachBaseContext reflection: ${t.message}")
        }

        applicationMap[loadedPackage.packageName] = guestApp

        // Install guest ContentProviders before Application.onCreate() (standard Android lifecycle)
        installGuestContentProviders(loadedPackage, proxyContext)

        // Invoke guest Application onCreate()
        try {
            guestApp.onCreate()
            Log.i(TAG, "Guest Application onCreate() executed successfully for: ${loadedPackage.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during guest Application onCreate()", t)
            throw t
        }

        return guestApp
    }

    fun getGuestApplication(packageName: String): Application? = applicationMap[packageName]

    private fun installGuestContentProviders(loadedPackage: LoadedPackage, proxyContext: ProxyContext) {
        for (comp in loadedPackage.manifest.providers) {
            try {
                val providerClass = try {
                    loadedPackage.classLoader.loadClass(comp.name)
                } catch (cnf: ClassNotFoundException) {
                    Log.w(TAG, "ContentProvider class not found in guest APK: ${comp.name}")
                    continue
                }
                val providerInstance = providerClass.getDeclaredConstructor().newInstance() as? android.content.ContentProvider ?: continue
                val providerInfo = com.ve.sandbox.core.hook.PackageInfoSynthesizer.buildProviderInfo(loadedPackage, comp)
                providerInstance.attachInfo(proxyContext, providerInfo)
                Log.i(TAG, "Installed guest ContentProvider: ${comp.name} (authorities=${comp.authorities})")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to initialize guest ContentProvider: ${comp.name}", t)
            }
        }
    }
}
