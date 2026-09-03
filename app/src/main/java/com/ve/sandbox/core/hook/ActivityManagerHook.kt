package com.ve.sandbox.core.hook

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Log
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.compat.HiddenApiManager
import com.ve.sandbox.core.stub.StubManager
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Hooks into ActivityManagerService (AMS) and ActivityTaskManagerService (ATMS)
 * Binder client proxies using Java Dynamic Proxies on the framework Singletons.
 *
 * Android Internals Mental Model:
 * 1. All framework calls to AMS / ATMS pass through ActivityManager.getService() or
 *    ActivityTaskManager.getService().
 * 2. These services are held in static Singleton<IActivityManager> instances.
 * 3. By replacing the mInstance field in these Singletons with a dynamic proxy,
 *    we intercept outbound Binder transactions at the highest client layer.
 * 4. When an app initiates startActivity() directly through ActivityManager / ActivityTaskManager,
 *    we masquerade the target Intent with a Stub Activity Intent before it ever hits Binder IPC.
 */
object ActivityManagerHook {
    private const val TAG = "VeActivityManagerHook"
    private var isHooked = false
    var lastMasqueradedIntent: Intent? = null
        internal set

    @Synchronized
    fun install(hostPackageName: String): Boolean {
        if (isHooked) return true

        HiddenApiManager.unseal()

        var hookedAm = false
        var hookedAtm = false

        // 1. Hook ActivityManager.IActivityManagerSingleton
        try {
            hookedAm = hookActivityManager(hostPackageName)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to hook ActivityManager", t)
        }

        // 2. Hook ActivityTaskManager.IActivityTaskManagerSingleton (Android 10+ / API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                hookedAtm = hookActivityTaskManager(hostPackageName)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to hook ActivityTaskManager", t)
            }
        }

        isHooked = hookedAm || hookedAtm
        return isHooked
    }

    private fun hookActivityManager(hostPackageName: String): Boolean {
        val amClass = Class.forName("android.app.ActivityManager")
        val singletonField = try {
            amClass.getDeclaredField("IActivityManagerSingleton")
        } catch (e: NoSuchFieldException) {
            // Android <= 7 fallback
            val amnClass = Class.forName("android.app.ActivityManagerNative")
            amnClass.getDeclaredField("gDefault")
        }.apply { isAccessible = true }

        val singleton = singletonField.get(null) ?: return false
        val singletonClass = Class.forName("android.util.Singleton")

        // Try getting original instance (may be null in test runner without system_server)
        val getMethod = singletonClass.getDeclaredMethod("get").apply { isAccessible = true }
        val originalAm = try { getMethod.invoke(singleton) } catch (e: Throwable) { null }

        val iAmClass = Class.forName("android.app.IActivityManager")
        val proxy = Proxy.newProxyInstance(
            iAmClass.classLoader,
            arrayOf(iAmClass),
            ActivityManagerInvocationHandler(originalAm, hostPackageName)
        )

        val mInstanceField = singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }
        mInstanceField.set(singleton, proxy)

        Log.i(TAG, "Successfully hooked IActivityManagerSingleton with dynamic proxy")
        return true
    }

    private fun hookActivityTaskManager(hostPackageName: String): Boolean {
        val atmClass = try {
            Class.forName("android.app.ActivityTaskManager")
        } catch (e: ClassNotFoundException) {
            return false
        }

        val singletonField = try {
            atmClass.getDeclaredField("IActivityTaskManagerSingleton").apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            return false
        }

        val singleton = singletonField.get(null) ?: return false

        val singletonClass = Class.forName("android.util.Singleton")
        val getMethod = singletonClass.getDeclaredMethod("get").apply { isAccessible = true }
        val originalAtm = try { getMethod.invoke(singleton) } catch (e: Throwable) { null }

        val iAtmClass = Class.forName("android.app.IActivityTaskManager")
        val proxy = Proxy.newProxyInstance(
            iAtmClass.classLoader,
            arrayOf(iAtmClass),
            ActivityManagerInvocationHandler(originalAtm, hostPackageName)
        )

        val mInstanceField = singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }
        mInstanceField.set(singleton, proxy)

        Log.i(TAG, "Successfully hooked IActivityTaskManagerSingleton with dynamic proxy")
        return true
    }

    private class ActivityManagerInvocationHandler(
        private val base: Any?,
        private val hostPackageName: String
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            val methodName = method.name
            val safeArgs = args?.toMutableList() ?: mutableListOf()

            if (methodName == "toString") return "VeActivityManagerProxy@${Integer.toHexString(System.identityHashCode(proxy))}"
            if (methodName == "hashCode") return System.identityHashCode(proxy)
            if (methodName == "equals") return proxy === safeArgs.getOrNull(0)

            try {
                if (methodName == "startActivity" || methodName.startsWith("startActivity")) {
                    var intentIndex = -1
                    for (i in safeArgs.indices) {
                        if (safeArgs[i] is Intent) {
                            intentIndex = i
                            break
                        }
                    }

                    if (intentIndex != -1) {
                        val originalIntent = safeArgs[intentIndex] as Intent
                        val targetPkg = originalIntent.component?.packageName

                        if (targetPkg != null && targetPkg != hostPackageName) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            val loaded = engine?.getLoadedPackage(targetPkg)

                            if (loaded != null) {
                                val targetClass = originalIntent.component?.className
                                val comp = loaded.manifest.activities.firstOrNull { it.name == targetClass }
                                val launchMode = comp?.launchMode ?: ActivityInfo.LAUNCH_MULTIPLE
                                val screenOrientation = comp?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                                Log.d(TAG, "Binder Hook: Masquerading $targetClass in $methodName")
                                val stubIntent = StubManager.masqueradeIntent(originalIntent, hostPackageName, launchMode, screenOrientation)
                                lastMasqueradedIntent = stubIntent
                                safeArgs[intentIndex] = stubIntent
                            }
                        }
                    }
                } else if (methodName.startsWith("getIntentSender")) {
                    // Masquerade PendingIntent targets and ensure calling package is host package
                    for (i in safeArgs.indices) {
                        val arg = safeArgs[i]
                        if (arg is Array<*>) {
                            @Suppress("UNCHECKED_CAST")
                            val intentArray = arg as? Array<Intent?>
                            if (intentArray != null) {
                                val newArray = intentArray.map { itemIntent ->
                                    if (itemIntent != null && itemIntent.component?.packageName != null && itemIntent.component?.packageName != hostPackageName) {
                                        val targetPkg = itemIntent.component!!.packageName
                                        val engine = try { VeEngine.get() } catch (e: Exception) { null }
                                        val loaded = engine?.getLoadedPackage(targetPkg)
                                        if (loaded != null) {
                                            val comp = loaded.manifest.activities.firstOrNull { it.name == itemIntent.component?.className }
                                            val launchMode = comp?.launchMode ?: ActivityInfo.LAUNCH_MULTIPLE
                                            val screenOrientation = comp?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                            val stub = StubManager.masqueradeIntent(itemIntent, hostPackageName, launchMode, screenOrientation)
                                            lastMasqueradedIntent = stub
                                            stub
                                        } else itemIntent
                                    } else itemIntent
                                }.toTypedArray()
                                safeArgs[i] = newArray
                            }
                        } else if (arg is Intent) {
                            val targetPkg = arg.component?.packageName
                            if (targetPkg != null && targetPkg != hostPackageName) {
                                val engine = try { VeEngine.get() } catch (e: Exception) { null }
                                val loaded = engine?.getLoadedPackage(targetPkg)
                                if (loaded != null) {
                                    val comp = loaded.manifest.activities.firstOrNull { it.name == arg.component?.className }
                                    val launchMode = comp?.launchMode ?: ActivityInfo.LAUNCH_MULTIPLE
                                    val screenOrientation = comp?.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                    val stub = StubManager.masqueradeIntent(arg, hostPackageName, launchMode, screenOrientation)
                                    lastMasqueradedIntent = stub
                                    safeArgs[i] = stub
                                }
                            }
                        } else if (arg is String && arg != hostPackageName) {
                            val engine = try { VeEngine.get() } catch (e: Exception) { null }
                            if (engine?.getLoadedPackage(arg) != null) {
                                safeArgs[i] = hostPackageName
                            }
                        }
                    }
                } else if (methodName.startsWith("startService") || methodName.startsWith("startForegroundService")) {
                    for (arg in safeArgs) {
                        if (arg is Intent) {
                            val targetPkg = arg.component?.packageName
                            if (targetPkg != null && targetPkg != hostPackageName) {
                                val engine = try { VeEngine.get() } catch (e: Exception) { null }
                                if (engine?.getLoadedPackage(targetPkg) != null) {
                                    Log.d(TAG, "Binder Hook: Intercepted $methodName for virtual package: $targetPkg (${arg.component?.className})")
                                    return arg.component
                                }
                            }
                        }
                    }
                } else if (methodName.startsWith("bindService") || methodName.startsWith("bindIsolatedService")) {
                    for (arg in safeArgs) {
                        if (arg is Intent) {
                            val targetPkg = arg.component?.packageName
                            if (targetPkg != null && targetPkg != hostPackageName) {
                                val engine = try { VeEngine.get() } catch (e: Exception) { null }
                                if (engine?.getLoadedPackage(targetPkg) != null) {
                                    Log.d(TAG, "Binder Hook: Intercepted $methodName for virtual package: $targetPkg (${arg.component?.className})")
                                    return 1
                                }
                            }
                        }
                    }
                } else if (methodName.startsWith("stopService")) {
                    for (arg in safeArgs) {
                        if (arg is Intent) {
                            val targetPkg = arg.component?.packageName
                            if (targetPkg != null && targetPkg != hostPackageName) {
                                val engine = try { VeEngine.get() } catch (e: Exception) { null }
                                if (engine?.getLoadedPackage(targetPkg) != null) {
                                    Log.d(TAG, "Binder Hook: Intercepted $methodName for virtual package: $targetPkg")
                                    return 1
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error in intercepted ActivityManager call: $methodName", e)
            }

            if (base == null) {
                // Fallback in unit test environments without real system_server
                return when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Double::class.javaPrimitiveType -> 0.0
                    Float::class.javaPrimitiveType -> 0.0f
                    else -> null
                }
            }

            return method.invoke(base, *safeArgs.toTypedArray())
        }
    }
}
