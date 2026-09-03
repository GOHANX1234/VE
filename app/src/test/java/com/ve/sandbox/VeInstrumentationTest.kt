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

    @Test
    fun testDemasqueradedGuestActivityLaunch() {
        // This reproduces the exact crash: ActivityThreadHook demasqueraded the intent in mH,
        // so ActivityThread called newActivity with className = guestActivityClass and intent = realIntent.
        // The host ClassLoader passed cannot find guestActivityClass.
        val realIntent = Intent().apply {
            component = ComponentName(guestPackage, guestActivityClass)
        }

        // Create an isolated ClassLoader that cannot find MockGuestActivity to simulate host ClassLoader
        val hostClassLoader = object : ClassLoader(ClassLoader.getSystemClassLoader().parent) {
            override fun findClass(name: String?): Class<*> {
                throw ClassNotFoundException("Host classloader does not contain $name")
            }
        }

        val activity = veInstrumentation.newActivity(
            hostClassLoader,
            guestActivityClass,
            realIntent
        )

        assertNotNull("Activity must be instantiated via guest VirtualClassLoader", activity)
        assertTrue(
            "Expected MockGuestActivity but was ${activity.javaClass.name}",
            activity is MockGuestActivity
        )
    }

    @Test
    fun testGuestFirstClassLoaderDelegation() {
        var findClassCalled = false

        val dummyParent = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                return super.loadClass(name, resolve)
            }
        }

        val testLoader = object : com.ve.sandbox.core.loader.GuestDexClassLoader(
            "",
            null,
            null,
            dummyParent
        ) {
            override fun findClass(name: String): Class<*> {
                findClassCalled = true
                if (name == "kotlin.jvm.internal.Intrinsics") {
                    return TestGuestIntrinsics::class.java
                }
                throw ClassNotFoundException(name)
            }
        }

        // 1. kotlin.jvm.internal.Intrinsics must search guest APK first (Guest-First)
        findClassCalled = false
        val intrinsicsClass = testLoader.loadClass("kotlin.jvm.internal.Intrinsics")
        assertTrue("findClass must be called first for kotlin.* classes", findClassCalled)
        assertEquals(TestGuestIntrinsics::class.java, intrinsicsClass)

        // 2. Framework class (java.lang.String) must delegate to system without calling findClass
        findClassCalled = false
        val stringClass = testLoader.loadClass("java.lang.String")
        assertFalse("findClass must NOT be called for java.lang.String", findClassCalled)
        assertEquals(String::class.java, stringClass)

        // 3. Android platform class (android.app.Activity) must delegate to system without calling findClass
        findClassCalled = false
        val activityClass = testLoader.loadClass("android.app.Activity")
        assertFalse("findClass must NOT be called for android.app.Activity", findClassCalled)
        assertEquals(android.app.Activity::class.java, activityClass)

        // 4. Host class (com.ve.sandbox.core.stub.StubActivity) must delegate to host parent
        findClassCalled = false
        val stubClass = testLoader.loadClass("com.ve.sandbox.core.stub.StubActivity")
        assertFalse("findClass must NOT be called for host com.ve.sandbox.* classes", findClassCalled)
        assertEquals(StubActivity::class.java, stubClass)
    }

    class TestGuestIntrinsics
}
