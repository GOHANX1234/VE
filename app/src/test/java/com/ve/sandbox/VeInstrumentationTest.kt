package com.ve.sandbox

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedComponent
import com.ve.sandbox.core.model.ParsedManifest
import com.ve.sandbox.core.stub.StubActivity
import com.ve.sandbox.core.stub.StubManager
import com.ve.sandbox.core.stub.VeInstrumentation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

open class MockGuestActivity : Activity() {
    var wasOnCreateCalled = false
    var capturedPackageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wasOnCreateCalled = true
        capturedPackageName = packageName
    }
}

@RunWith(RobolectricTestRunner::class)
class VeInstrumentationTest {

    private val hostPackage = "com.ve.sandbox"
    private val guestPackage = "com.target.sampleapp"
    private val guestActivityClass = MockGuestActivity::class.java.name

    private lateinit var veInstrumentation: VeInstrumentation
    private lateinit var baseInstrumentation: Instrumentation
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
                    ParsedComponent(name = guestActivityClass, isLauncher = true)
                )
            )
        )

        val loaded = LoadedPackage(
            installedPackage = installed,
            classLoader = javaClass.classLoader!!,
            assetManager = appContext.assets,
            resources = appContext.resources
        )

        // Register package in VeEngine
        val loadedPackagesField = VeEngine::class.java.getDeclaredField("loadedPackages").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val map = loadedPackagesField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, LoadedPackage>
        map[guestPackage] = loaded

        val installedPackagesField = VeEngine::class.java.getDeclaredField("installedPackages").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val installedMap = installedPackagesField.get(engine) as java.util.concurrent.ConcurrentHashMap<String, InstalledPackage>
        installedMap[guestPackage] = installed

        baseInstrumentation = Instrumentation()
        veInstrumentation = VeInstrumentation(baseInstrumentation, hostPackage)
    }

    @Test
    fun testInboundNewActivitySwap() {
        // Prepare a real guest intent and masquerade it into StubActivity
        val realIntent = Intent().apply {
            component = ComponentName(guestPackage, guestActivityClass)
        }
        val stubIntent = StubManager.masqueradeIntent(realIntent, hostPackage)

        // When Android's ActivityThread calls newActivity with StubActivity:
        val activity = veInstrumentation.newActivity(
            javaClass.classLoader!!,
            StubActivity::class.java.name,
            stubIntent
        )

        assertNotNull("Activity must be instantiated", activity)
        assertTrue(
            "StubActivity must be swapped with the real MockGuestActivity class! Actual: ${activity.javaClass.name}",
            activity is MockGuestActivity
        )
        assertEquals(guestPackage, stubIntent.component?.packageName)
        assertEquals(guestActivityClass, stubIntent.component?.className)
    }

    @Test
    fun testCallActivityOnCreateContextInjection() {
        val realIntent = Intent().apply {
            component = ComponentName(guestPackage, guestActivityClass)
        }
        val stubIntent = StubManager.masqueradeIntent(realIntent, hostPackage)

        val activity = veInstrumentation.newActivity(
            javaClass.classLoader!!,
            StubActivity::class.java.name,
            stubIntent
        ) as MockGuestActivity

        // Attach a base context to satisfy Activity.onCreate
        val attachMethod = Activity::class.java.getDeclaredMethod(
            "attach",
            android.content.Context::class.java,
            Class.forName("android.app.ActivityThread"),
            android.app.Instrumentation::class.java,
            android.os.IBinder::class.java,
            Int::class.javaPrimitiveType,
            android.app.Application::class.java,
            android.content.Intent::class.java,
            android.content.pm.ActivityInfo::class.java,
            CharSequence::class.java,
            Activity::class.java,
            String::class.java,
            Class.forName("android.app.Activity\$NonConfigurationInstances"),
            android.content.res.Configuration::class.java,
            String::class.java,
            Class.forName("com.android.internal.app.IVoiceInteractor"),
            android.view.Window::class.java,
            Class.forName("android.view.ViewRootImpl\$ActivityConfigCallback"),
            android.os.IBinder::class.java,
            android.os.IBinder::class.java
        ).apply { isAccessible = true }

        val app = RuntimeEnvironment.getApplication()
        val dummyThread = null
        val dummyToken = android.os.Binder()
        val dummyInfo = android.content.pm.ActivityInfo().apply {
            packageName = guestPackage
            name = guestActivityClass
        }

        try {
            attachMethod.invoke(
                activity,
                app,
                dummyThread,
                veInstrumentation,
                dummyToken,
                0,
                app,
                stubIntent,
                dummyInfo,
                "Guest Activity",
                null,
                null,
                null,
                app.resources.configuration,
                null,
                null,
                null,
                null,
                null,
                null
            )
        } catch (e: Throwable) {
            // If internal attach fails due to OS version parameter variation, fallback to set mBase directly
            val mBaseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase").apply {
                isAccessible = true
            }
            mBaseField.set(activity, app)
        }

        // Call callActivityOnCreate: verifies ProxyContext injection
        veInstrumentation.callActivityOnCreate(activity, Bundle())

        assertTrue("Guest Activity onCreate must be invoked", activity.wasOnCreateCalled)
        assertEquals("Guest Activity must see its own package name", guestPackage, activity.capturedPackageName)
    }
}
