package com.ve.sandbox

import android.app.Application
import android.content.Context
import com.ve.sandbox.core.context.GuestApplicationManager
import com.ve.sandbox.core.context.ProxyContext
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedManifest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class SampleCustomGuestApp : Application() {
    var wasOnCreateCalled = false
    var attachedContext: Context? = null

    public override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        attachedContext = base
    }

    override fun onCreate() {
        super.onCreate()
        wasOnCreateCalled = true
    }
}

class GuestApplicationManagerTest {

    private lateinit var sandboxDir: File
    private lateinit var guestDataDir: File
    private lateinit var loadedPackage: LoadedPackage
    private lateinit var appManager: GuestApplicationManager

    @Before
    fun setup() {
        sandboxDir = File("/tmp/ve_app_mgr_test_${System.currentTimeMillis()}").apply { mkdirs() }
        guestDataDir = File(sandboxDir, "com.target.sampleapp/data").apply { mkdirs() }

        val installedPackage = InstalledPackage(
            packageName = "com.target.sampleapp",
            archiveType = ArchiveType.APK,
            baseApkPath = File(sandboxDir, "base.apk").apply { createNewFile() }.absolutePath,
            splitApkPaths = emptyList(),
            nativeLibDir = File(sandboxDir, "lib").apply { mkdirs() }.absolutePath,
            dataDir = guestDataDir.absolutePath,
            manifest = ParsedManifest(
                packageName = "com.target.sampleapp",
                versionCode = 101,
                versionName = "1.0.1-target",
                applicationClassName = SampleCustomGuestApp::class.java.name,
                minSdkVersion = 26,
                targetSdkVersion = 34
            )
        )

        val baseContext = ProxyContextTest.TestBaseContext()
        val dummyClassLoader = java.lang.ClassLoader.getSystemClassLoader()
        val dummyAssetManager = android.content.res.AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val dummyResources = android.content.res.Resources(
            dummyAssetManager,
            android.util.DisplayMetrics(),
            android.content.res.Configuration()
        )

        loadedPackage = LoadedPackage(
            installedPackage = installedPackage,
            classLoader = dummyClassLoader,
            assetManager = dummyAssetManager,
            resources = dummyResources
        )

        appManager = GuestApplicationManager(baseContext)
    }

    @Test
    fun testStartGuestApplicationLifecycle() {
        val app = appManager.startGuestApplication(loadedPackage)
        assertNotNull(app)
        assertTrue(app is SampleCustomGuestApp)

        val customApp = app as SampleCustomGuestApp
        assertTrue("Guest Application onCreate() must be called", customApp.wasOnCreateCalled)
        assertNotNull("ProxyContext must be attached to guest Application", customApp.attachedContext)
        assertTrue(customApp.attachedContext is ProxyContext)

        val proxy = customApp.attachedContext as ProxyContext
        assertEquals("com.target.sampleapp", proxy.packageName)
        assertEquals(File(guestDataDir, "files").absolutePath, proxy.filesDir.absolutePath)
        assertEquals(File(guestDataDir, "cache").absolutePath, proxy.cacheDir.absolutePath)

        // Verifying cache in appManager
        val cachedApp = appManager.getGuestApplication("com.target.sampleapp")
        assertSame(app, cachedApp)
    }
}
