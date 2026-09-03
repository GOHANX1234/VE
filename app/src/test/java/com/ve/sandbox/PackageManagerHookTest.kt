package com.ve.sandbox

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.hook.PackageManagerHook
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedComponent
import com.ve.sandbox.core.model.ParsedManifest
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
class PackageManagerHookTest {

    private val guestPackage = "com.target.sampleapp"
    private lateinit var engine: VeEngine
    private lateinit var iPmClass: Class<*>
    private lateinit var proxyPm: Any

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
                permissions = listOf(
                    "android.permission.INTERNET",
                    "android.permission.ACCESS_NETWORK_STATE",
                    "android.permission.VIBRATE"
                ),
                activities = listOf(
                    ParsedComponent(
                        name = "com.target.sampleapp.TargetMainActivity",
                        exported = true,
                        isLauncher = true
                    )
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

        assertTrue("PackageManagerHook installation must succeed", PackageManagerHook.install())

        iPmClass = Class.forName("android.content.pm.IPackageManager")
        proxyPm = PackageManagerHook.getProxyPackageManager()!!
        assertTrue("Installed hook must be a Java dynamic proxy", Proxy.isProxyClass(proxyPm.javaClass))
    }

    @Test
    fun testInterceptGetPackageInfo() {
        val method = iPmClass.methods.first { it.name == "getPackageInfo" }
        val paramCount = method.parameterCount

        val result = if (paramCount == 3) {
            val flagParam = if (method.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
            method.invoke(proxyPm, guestPackage, flagParam, 0)
        } else {
            method.invoke(proxyPm, guestPackage, 0, 0)
        }

        assertNotNull("PackageInfo must be returned by proxy for uninstalled guest app", result)
        val packageInfo = result as PackageInfo
        assertEquals(guestPackage, packageInfo.packageName)
        assertEquals("1.0.1-target", packageInfo.versionName)
        assertEquals(101L, packageInfo.longVersionCode)
        assertNotNull(packageInfo.applicationInfo)
        assertEquals("com.target.sampleapp.TargetApp", packageInfo.applicationInfo?.className)
    }

    @Test
    fun testInterceptGetApplicationInfo() {
        val method = iPmClass.methods.first { it.name == "getApplicationInfo" }
        val paramCount = method.parameterCount

        val result = if (paramCount == 3) {
            val flagParam = if (method.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
            method.invoke(proxyPm, guestPackage, flagParam, 0)
        } else {
            method.invoke(proxyPm, guestPackage, 0, 0)
        }

        assertNotNull("ApplicationInfo must be returned by proxy for uninstalled guest app", result)
        val appInfo = result as ApplicationInfo
        assertEquals(guestPackage, appInfo.packageName)
        assertEquals("com.target.sampleapp.TargetApp", appInfo.className)
        assertTrue(appInfo.sourceDir.endsWith("base.apk"))
    }

    @Test
    fun testInterceptCheckPermission() {
        val method = iPmClass.methods.first { it.name == "checkPermission" }
        val hasInternet = method.invoke(proxyPm, "android.permission.INTERNET", guestPackage, 0) as Int
        val hasCamera = method.invoke(proxyPm, "android.permission.CAMERA", guestPackage, 0) as Int

        assertEquals("Declared permission must be GRANTED by virtual PMS", PackageManager.PERMISSION_GRANTED, hasInternet)
        assertEquals("Undeclared permission must be DENIED by virtual PMS", PackageManager.PERMISSION_DENIED, hasCamera)
    }

    @Test
    fun testInterceptResolveIntent() {
        val method = iPmClass.methods.firstOrNull { it.name == "resolveIntent" }
        if (method != null) {
            val intent = Intent().apply {
                component = ComponentName(guestPackage, "com.target.sampleapp.TargetMainActivity")
            }
            val args = Array<Any?>(method.parameterCount) { idx ->
                when (method.parameterTypes[idx]) {
                    Intent::class.java -> intent
                    String::class.java -> null
                    Long::class.javaPrimitiveType -> 0L
                    Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            val result = method.invoke(proxyPm, *args)
            assertNotNull("ResolveInfo must be synthesized for guest intent", result)
        }
    }
}
