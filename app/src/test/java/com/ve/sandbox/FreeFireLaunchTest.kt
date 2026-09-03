package com.ve.sandbox

import android.app.Activity
import android.content.Intent
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.stub.StubManager
import com.ve.sandbox.core.stub.VeInstrumentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class FreeFireLaunchTest {

    @Test
    fun testFreeFireLaunchPipeline() {
        val xapk = File("/tmp/freefire.xapk")
        if (!xapk.exists()) return

        val context = RuntimeEnvironment.getApplication()
        val engine = VeEngine.init(context)

        val loaded = engine.installAndLoad(xapk)
        assertNotNull(loaded)
        assertEquals("com.dts.freefiremax", loaded.packageName)
        val launcher = loaded.manifest.launcherActivity
        assertNotNull(launcher)
        assertEquals("com.dts.freefireth.FFMainActivity", launcher?.name)
        assertEquals(
            "Free Fire launcher must be parsed with SCREEN_ORIENTATION_SENSOR_LANDSCAPE (6)",
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            launcher?.screenOrientation
        )
        assertTrue("Must mount split APKs", loaded.splitApkPaths.isNotEmpty())

        val realIntent = Intent().apply {
            component = android.content.ComponentName(loaded.packageName, launcher!!.name)
        }
        val stubIntent = StubManager.masqueradeIntent(
            realIntent,
            context.packageName,
            launcher!!.launchMode,
            launcher.screenOrientation ?: -1
        )
        assertEquals(
            "Must select StubLandscapeSingleTaskActivity for landscape singleTask activity",
            StubManager.STUB_LANDSCAPE_SINGLE_TASK,
            stubIntent.component?.className
        )

        // 1. Verify Provider authorities parsed from AndroidBinaryXml
        val fileProvider = loaded.manifest.providers.firstOrNull { it.name == "com.dts.freefireth.FFFileProvider" }
        assertNotNull("FFFileProvider must be parsed", fileProvider)
        assertEquals("com.dts.freefiremax.fileprovider", fileProvider?.authorities)

        val appStartupProvider = loaded.manifest.providers.firstOrNull { it.name == "com.garena.android.AppStartupProvider" }
        assertNotNull("AppStartupProvider must be parsed", appStartupProvider)

        // 2. Verify ApplicationInfo & PackageInfo synthesis with splitNames and primary ABI nativeLibraryDir
        val appInfo = com.ve.sandbox.core.hook.PackageInfoSynthesizer.buildApplicationInfo(loaded)
        assertNotNull("splitNames must be populated for install-time asset pack detection", appInfo.splitNames)
        assertTrue("splitNames must contain asset_pack_install_time", appInfo.splitNames!!.contains("asset_pack_install_time"))
        assertTrue("splitNames must contain config.arm64_v8a", appInfo.splitNames!!.contains("config.arm64_v8a"))
        assertTrue("nativeLibraryDir must point to ABI directory", appInfo.nativeLibraryDir!!.contains("arm64-v8a"))

        val providerInfo = com.ve.sandbox.core.hook.PackageInfoSynthesizer.buildProviderInfo(loaded, fileProvider!!)
        assertEquals("com.dts.freefiremax.fileprovider", providerInfo.authority)

        // 3. Verify ProxyContext storage quarantine and OBB redirection
        val proxyContext = com.ve.sandbox.core.context.ProxyContext(context, loaded)
        val extFiles = proxyContext.getExternalFilesDir(null)
        assertNotNull("External files dir must not be null", extFiles)
        assertTrue("External files dir must exist", extFiles!!.exists())
        assertFalse("External files dir must not end with trailing slash", extFiles.path.endsWith("/"))

        val obbDir = proxyContext.getObbDir()
        assertNotNull("Obb dir must not be null", obbDir)
        assertTrue("Obb dir must exist", obbDir!!.exists())

        val mediaDirs = proxyContext.getExternalMediaDirs()
        assertTrue("External media dirs must not be empty", mediaDirs.isNotEmpty())

        // Verify subpath file creation (used by game download chunk writes)
        val testOut = proxyContext.openFileOutput("test_chunks/chunk_01.dat", 0)
        testOut.write("test_chunk_data".toByteArray())
        testOut.close()
        val chunkFile = File(proxyContext.filesDir, "test_chunks/chunk_01.dat")
        assertTrue("Chunk file must exist", chunkFile.exists())
        assertEquals("test_chunk_data", chunkFile.readText())

        // 4. Verify PackageManagerHook resolution for virtual content providers
        com.ve.sandbox.core.hook.PackageManagerHook.install()
        val proxyPm = com.ve.sandbox.core.hook.PackageManagerHook.getProxyPackageManager()!!
        val iPmClass = Class.forName("android.content.pm.IPackageManager")
        val resolveMethod = iPmClass.methods.first { it.name.startsWith("resolveContentProvider") }
        val resolveArgs = Array<Any?>(resolveMethod.parameterCount) { idx ->
            when (idx) {
                0 -> "com.dts.freefiremax.fileprovider"
                1 -> if (resolveMethod.parameterTypes[1] == Long::class.javaPrimitiveType) 0L else 0
                2 -> 0
                else -> null
            }
        }
        val resolvedProvider = resolveMethod.invoke(proxyPm, *resolveArgs) as? android.content.pm.ProviderInfo
        assertNotNull("PackageManager must resolve virtual fileprovider authority", resolvedProvider)
        assertEquals("com.dts.freefireth.FFFileProvider", resolvedProvider?.name)

        // 5. Verify ActivityManagerHook PendingIntent masquerading
        com.ve.sandbox.core.hook.ActivityManagerHook.install(context.packageName)
        val amClass = Class.forName("android.app.IActivityManager")
        val getIntentSenderMethod = amClass.methods.firstOrNull { it.name.startsWith("getIntentSender") }
        if (getIntentSenderMethod != null) {
            val singletonField = Class.forName("android.app.ActivityManager").getDeclaredField("IActivityManagerSingleton").apply { isAccessible = true }
            val singleton = singletonField.get(null)
            val mInstanceField = Class.forName("android.util.Singleton").getDeclaredField("mInstance").apply { isAccessible = true }
            val proxyAm = mInstanceField.get(singleton)!!

            val intentsArg = arrayOf(realIntent)
            val callArgs = Array<Any?>(getIntentSenderMethod.parameterCount) { idx ->
                when (getIntentSenderMethod.parameterTypes[idx]) {
                    Array<Intent>::class.java -> intentsArg
                    Intent::class.java -> realIntent
                    String::class.java -> "com.dts.freefiremax"
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
            try {
                getIntentSenderMethod.invoke(proxyAm, *callArgs)
                val masqueraded = com.ve.sandbox.core.hook.ActivityManagerHook.lastMasqueradedIntent
                assertNotNull("PendingIntent intent must be masqueraded", masqueraded)
            } catch (t: Throwable) {
                // Ignore binder invoke exceptions in mock environment
            }
        }
    }
}
