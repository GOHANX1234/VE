package com.ve.sandbox

import android.content.ComponentName
import android.content.Intent
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.hook.ActivityManagerHook
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedComponent
import com.ve.sandbox.core.model.ParsedManifest
import com.ve.sandbox.core.stub.StubManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class ActivityManagerHookTest {

    private val hostPackage = "com.ve.sandbox"
    private val guestPackage = "com.target.sampleapp"
    private val guestActivity = "com.target.sampleapp.TargetMainActivity"
    private lateinit var engine: VeEngine

    @Before
    fun setup() {
        val appContext = RuntimeEnvironment.getApplication()
        engine = VeEngine.init(appContext)

        val sandboxDir = File(appContext.filesDir, "ve_sandbox/$guestPackage").apply { mkdirs() }
        val guestDataDir = File(sandboxDir, "data").apply { mkdirs() }

        val installed = InstalledPackage(
            packageName = guestPackage,
            archiveType = ArchiveType.APK,
            baseApkPath = File(sandboxDir, "base.apk").apply { createNewFile() }.absolutePath,
            splitApkPaths = emptyList(),
            nativeLibDir = File(sandboxDir, "lib").apply { mkdirs() }.absolutePath,
            dataDir = guestDataDir.absolutePath,
            manifest = ParsedManifest(
                packageName = guestPackage,
                versionCode = 101,
                versionName = "1.0.1-target",
                applicationClassName = "com.target.sampleapp.TargetApp",
                activities = listOf(
                    ParsedComponent(name = guestActivity, isLauncher = true)
                )
            )
        )

        val loaded = LoadedPackage(
            installedPackage = installed,
            classLoader = javaClass.classLoader!!,
            assetManager = appContext.assets,
            resources = appContext.resources
        )

        val loadedField = VeEngine::class.java.getDeclaredField("loadedPackages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map = loadedField.get(engine) as ConcurrentHashMap<String, LoadedPackage>
        map[guestPackage] = loaded

        assertTrue("ActivityManagerHook installation must succeed", ActivityManagerHook.install(hostPackage))
    }

    @Test
    fun testActivityManagerHookInstalled() {
        val amClass = Class.forName("android.app.ActivityManager")
        val singletonField = amClass.getDeclaredField("IActivityManagerSingleton").apply { isAccessible = true }
        val singleton = singletonField.get(null)
        assertNotNull(singleton)

        val singletonClass = Class.forName("android.util.Singleton")
        val mInstanceField = singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }
        val proxyInstance = mInstanceField.get(singleton)

        assertNotNull(proxyInstance)
        assertTrue("mInstance in IActivityManagerSingleton must be dynamic proxy", Proxy.isProxyClass(proxyInstance.javaClass))
    }

    @Test
    fun testStartActivityMasqueradeViaDynamicProxy() {
        val amClass = Class.forName("android.app.ActivityManager")
        val singletonField = amClass.getDeclaredField("IActivityManagerSingleton").apply { isAccessible = true }
        val singleton = singletonField.get(null)
        val singletonClass = Class.forName("android.util.Singleton")
        val mInstanceField = singletonClass.getDeclaredField("mInstance").apply { isAccessible = true }
        val proxyAm = mInstanceField.get(singleton)!!

        val realGuestIntent = Intent().apply {
            component = ComponentName(guestPackage, guestActivity)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Find a startActivity method on the interface that takes an Intent
        val iAmClass = Class.forName("android.app.IActivityManager")
        val startActivityMethod = iAmClass.methods.firstOrNull { it.name.startsWith("startActivity") && it.parameterTypes.contains(Intent::class.java) }

        if (startActivityMethod != null) {
            // Find which argument position is Intent
            val intentIndex = startActivityMethod.parameterTypes.indexOfFirst { it == Intent::class.java }
            assertTrue("startActivity must accept an Intent parameter", intentIndex != -1)

            val safeArgs = Array<Any?>(startActivityMethod.parameterCount) { idx ->
                when (startActivityMethod.parameterTypes[idx]) {
                    Intent::class.java -> realGuestIntent
                    String::class.java -> hostPackage
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }

            try {
                startActivityMethod.invoke(proxyAm, *safeArgs)
            } catch (e: Throwable) {
                // Delegation to underlying mock is fine even if mock throws
            }

            // Verify that the Intent was intercepted and masqueraded to a StubActivity
            val replacedIntent = ActivityManagerHook.lastMasqueradedIntent
            assertNotNull("Masqueraded intent must be captured", replacedIntent)
            assertTrue("Intent passed to Binder must be masqueraded into a Stub Intent", StubManager.isStubIntent(replacedIntent))
            assertEquals(hostPackage, replacedIntent?.component?.packageName)
            assertEquals(StubManager.STUB_STANDARD, replacedIntent?.component?.className)
            assertEquals(guestPackage, StubManager.extractTargetPackage(replacedIntent))
            assertEquals(guestActivity, StubManager.extractTargetClass(replacedIntent))
        }
    }
}
