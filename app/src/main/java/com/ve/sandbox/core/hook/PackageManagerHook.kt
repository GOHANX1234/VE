package com.ve.sandbox.core.hook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.compat.HiddenApiManager
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hooks into ActivityThread.sPackageManager (IPackageManager) using a Java Dynamic Proxy.
 *
 * Android Internals Mental Model:
 * 1. Apps communicate with PackageManagerService (PMS) inside system_server via the IPackageManager AIDL interface.
 * 2. In our process, ActivityThread caches this proxy in a static field: ActivityThread.sPackageManager.
 * 3. By replacing sPackageManager with a dynamic proxy (Proxy.newProxyInstance), all package queries
 *    made by the app (or third-party libraries inside the guest APK) pass through our InvocationHandler.
 * 4. Queries for installed system apps pass through to the real OS PMS.
 * 5. Queries for our uninstalled guest APK are answered directly by our virtual engine, making the
 *    guest APK appear fully "installed" to Android framework queries without touching the OS registry.
 */
object PackageManagerHook {
    private const val TAG = "VePackageManagerHook"
    private var isHooked = false
    private var realPackageManager: Any? = null
    private var proxyPackageManager: Any? = null

    fun getProxyPackageManager(): Any? = proxyPackageManager
    fun getRealPackageManager(): Any? = realPackageManager

    @Synchronized
    fun install(): Boolean {
        if (isHooked) return true

        HiddenApiManager.unseal()

        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val getPackageManagerMethod = activityThreadClass.getDeclaredMethod("getPackageManager").apply {
                isAccessible = true
            }

            // Ensure sPackageManager is initialized
            val originalPm = getPackageManagerMethod.invoke(null)
                ?: throw IllegalStateException("ActivityThread.getPackageManager() returned null")
            realPackageManager = originalPm

            val iPackageManagerClass = Class.forName("android.content.pm.IPackageManager")

            val proxy = Proxy.newProxyInstance(
                iPackageManagerClass.classLoader,
                arrayOf(iPackageManagerClass),
                PackageManagerInvocationHandler(originalPm)
            )
            proxyPackageManager = proxy

            // Replace ActivityThread.sPackageManager
            val sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager").apply {
                isAccessible = true
            }
            sPackageManagerField.set(null, proxy)

            isHooked = true
            Log.i(TAG, "Successfully hooked ActivityThread.sPackageManager with dynamic proxy")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hook PackageManager", t)
            false
        }
    }

    private class PackageManagerInvocationHandler(
        private val base: Any
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val methodName = method.name
            val safeArgs = args ?: emptyArray()

            if (methodName == "toString") return "VePackageManagerProxy@${Integer.toHexString(System.identityHashCode(proxy))}"
            if (methodName == "hashCode") return System.identityHashCode(proxy)
            if (methodName == "equals") return proxy === safeArgs.getOrNull(0)

            println("[IPackageManager Hook] Invoking $methodName with args: ${safeArgs.map { it?.toString() }}")

            try {
                when {
                    methodName == "getPackageInfo" -> {
                        val pkgName = safeArgs.getOrNull(0) as? String
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (pkgName != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(pkgName)
                            if (loaded != null) {
                                Log.d(TAG, "Intercepted getPackageInfo for virtual package: $pkgName")
                                return PackageInfoSynthesizer.buildPackageInfo(loaded, flags)
                            }
                        }
                    }

                    methodName == "getPackageInfoVersioned" || methodName.startsWith("getPackageInfoVersioned") -> {
                        val versionedPkg = safeArgs.getOrNull(0)
                        val pkgName = if (versionedPkg is String) versionedPkg else {
                            try {
                                versionedPkg?.javaClass?.getMethod("getPackageName")?.invoke(versionedPkg) as? String
                            } catch (e: Exception) {
                                versionedPkg?.toString()
                            }
                        }
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (pkgName != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(pkgName)
                            if (loaded != null) {
                                Log.d(TAG, "Intercepted getPackageInfoVersioned for virtual package: $pkgName")
                                return PackageInfoSynthesizer.buildPackageInfo(loaded, flags)
                            }
                        }
                    }

                    methodName == "getApplicationInfo" -> {
                        val pkgName = safeArgs.getOrNull(0) as? String
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (pkgName != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(pkgName)
                            if (loaded != null) {
                                Log.d(TAG, "Intercepted getApplicationInfo for virtual package: $pkgName")
                                return PackageInfoSynthesizer.buildApplicationInfo(loaded, flags)
                            }
                        }
                    }

                    methodName == "getActivityInfo" -> {
                        val comp = safeArgs.getOrNull(0) as? ComponentName
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (comp != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(comp.packageName)
                            if (loaded != null) {
                                val parsedComp = loaded.manifest.activities.firstOrNull { it.name == comp.className }
                                if (parsedComp != null) {
                                    Log.d(TAG, "Intercepted getActivityInfo for virtual component: ${comp.className}")
                                    return PackageInfoSynthesizer.buildActivityInfo(loaded, parsedComp)
                                }
                            }
                        }
                    }

                    methodName == "getServiceInfo" -> {
                        val comp = safeArgs.getOrNull(0) as? ComponentName
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (comp != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(comp.packageName)
                            if (loaded != null) {
                                val parsedComp = loaded.manifest.services.firstOrNull { it.name == comp.className }
                                if (parsedComp != null) {
                                    Log.d(TAG, "Intercepted getServiceInfo for virtual service: ${comp.className}")
                                    return PackageInfoSynthesizer.buildServiceInfo(loaded, parsedComp)
                                }
                            }
                        }
                    }

                    methodName == "getReceiverInfo" -> {
                        val comp = safeArgs.getOrNull(0) as? ComponentName
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (comp != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(comp.packageName)
                            if (loaded != null) {
                                val parsedComp = loaded.manifest.receivers.firstOrNull { it.name == comp.className }
                                if (parsedComp != null) {
                                    Log.d(TAG, "Intercepted getReceiverInfo for virtual receiver: ${comp.className}")
                                    return PackageInfoSynthesizer.buildActivityInfo(loaded, parsedComp)
                                }
                            }
                        }
                    }

                    methodName == "getProviderInfo" -> {
                        val comp = safeArgs.getOrNull(0) as? ComponentName
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (comp != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(comp.packageName)
                            if (loaded != null) {
                                val parsedComp = loaded.manifest.providers.firstOrNull { it.name == comp.className }
                                if (parsedComp != null) {
                                    Log.d(TAG, "Intercepted getProviderInfo for virtual provider: ${comp.className}")
                                    return PackageInfoSynthesizer.buildProviderInfo(loaded, parsedComp)
                                }
                            }
                        }
                    }

                    methodName.startsWith("resolveContentProvider") -> {
                        val authority = safeArgs.getOrNull(0) as? String
                        val flags = (safeArgs.getOrNull(1) as? Number)?.toInt() ?: 0
                        if (authority != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            if (engine != null) {
                                for (pkg in engine.getInstalledPackages()) {
                                    val loaded = engine.getLoadedPackage(pkg.packageName)
                                    val comp = loaded?.manifest?.providers?.firstOrNull {
                                        it.authorities == authority || it.authorities?.split(";")?.contains(authority) == true
                                    } ?: pkg.manifest.providers.firstOrNull {
                                        it.authorities == authority || it.authorities?.split(";")?.contains(authority) == true
                                    }
                                    if (comp != null && loaded != null) {
                                        Log.d(TAG, "Intercepted resolveContentProvider for virtual authority: $authority -> ${comp.name}")
                                        return PackageInfoSynthesizer.buildProviderInfo(loaded, comp)
                                    }
                                }
                            }
                        }
                    }

                    methodName == "checkPermission" || methodName == "checkUidPermission" -> {
                        val perm = safeArgs.getOrNull(0) as? String
                        val pkgNameOrUid = safeArgs.getOrNull(1)
                        if (perm != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val pkgName = when (pkgNameOrUid) {
                                is String -> pkgNameOrUid
                                is Number -> if (pkgNameOrUid.toInt() == android.os.Process.myUid()) {
                                    engine?.getInstalledPackages()?.firstOrNull()?.packageName
                                } else null
                                else -> null
                            }
                            if (pkgName != null) {
                                val loaded = engine?.getLoadedPackage(pkgName)
                                if (loaded != null) {
                                    val granted = loaded.manifest.permissions.contains(perm)
                                    Log.d(TAG, "Intercepted checkPermission for $pkgName ($perm): granted=$granted")
                                    return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                                }
                            }
                        }
                    }

                    methodName == "checkSignatures" -> {
                        val pkg1 = safeArgs.getOrNull(0) as? String
                        val pkg2 = safeArgs.getOrNull(1) as? String
                        if (pkg1 != null && pkg1 == pkg2) {
                            return PackageManager.SIGNATURE_MATCH
                        }
                    }

                    methodName.startsWith("getPackageUid") -> {
                        val pkgName = safeArgs.getOrNull(0) as? String
                        if (pkgName != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            if (engine?.getLoadedPackage(pkgName) != null) {
                                return android.os.Process.myUid()
                            }
                        }
                    }

                    methodName == "resolveIntent" -> {
                        val intent = safeArgs.getOrNull(0) as? Intent
                        if (intent != null) {
                            val targetPkg = intent.component?.packageName
                            if (targetPkg != null) {
                                val engine = try { VeEngine.get() } catch (e: Exception) { null }
                                val loaded = engine?.getLoadedPackage(targetPkg)
                                if (loaded != null) {
                                    val targetClass = intent.component?.className
                                    val comp = loaded.manifest.activities.firstOrNull { it.name == targetClass }
                                        ?: loaded.manifest.launcherActivity
                                    if (comp != null) {
                                        Log.d(TAG, "Intercepted resolveIntent for virtual intent: $intent")
                                        return PackageInfoSynthesizer.buildResolveInfo(loaded, comp)
                                    }
                                }
                            }
                        }
                    }

                    methodName == "getInstallerPackageName" -> {
                        val pkgName = safeArgs.getOrNull(0) as? String
                        if (pkgName != null) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            if (engine?.getLoadedPackage(pkgName) != null) {
                                return "com.android.vending"
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error in intercepted PackageManager call: $methodName", e)
            }

            // Fallback: delegate to real OS PackageManagerService
            return method.invoke(base, *safeArgs)
        }
    }
}
