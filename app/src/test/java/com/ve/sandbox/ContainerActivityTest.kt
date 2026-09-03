package com.ve.sandbox

import android.content.Intent
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedComponent
import com.ve.sandbox.core.model.ParsedManifest
import com.ve.sandbox.ui.ContainerActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class ContainerActivityTest {

    private val guestPackage = "com.target.sampleapp"
    private lateinit var engine: VeEngine

    @Before
    fun setup() {
        val appContext = RuntimeEnvironment.getApplication()
        engine = VeEngine.init(appContext)

        val sandboxDir = File(appContext.filesDir, "ve_sandbox/$guestPackage").apply { mkdirs() }
        val guestDataDir = File(sandboxDir, "data").apply { mkdirs() }
        File(guestDataDir, "files").apply { mkdirs() }

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
                    ParsedComponent(name = MockGuestActivity::class.java.name, isLauncher = true)
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

        val installedField = VeEngine::class.java.getDeclaredField("installedPackages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val instMap = installedField.get(engine) as ConcurrentHashMap<String, InstalledPackage>
        instMap[guestPackage] = installed
    }

    @Test
    fun testCreateContainerIntent() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ContainerActivity.createIntent(context, guestPackage, MockGuestActivity::class.java.name)

        assertEquals(guestPackage, intent.getStringExtra(ContainerActivity.EXTRA_PACKAGE_NAME))
        assertEquals(MockGuestActivity::class.java.name, intent.getStringExtra(ContainerActivity.EXTRA_ACTIVITY_CLASS))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testContainerActivityLifecycle() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ContainerActivity.createIntent(context, guestPackage, MockGuestActivity::class.java.name)

        val controller = Robolectric.buildActivity(ContainerActivity::class.java, intent)
        val activity = controller.create().start().resume().get()

        assertNotNull(activity)
        assertFalse(activity.isFinishing)

        // Verify window decor content view is populated
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        assertNotNull("Guest content view must be mounted", content)
        assertTrue(content.childCount > 0)
    }

    @Test
    fun testClearDataAndUninstall() {
        val appContext = RuntimeEnvironment.getApplication()
        val testFile = File(appContext.filesDir, "ve_sandbox/$guestPackage/data/files/some_data.txt")
        testFile.parentFile?.mkdirs()
        testFile.writeText("sample data")
        assertTrue(testFile.exists())

        // Test clearData
        val cleared = engine.clearData(guestPackage)
        assertTrue(cleared)
        assertFalse("Data file must be deleted upon clearData()", testFile.exists())

        // Test uninstall
        val uninstalled = engine.uninstall(guestPackage)
        assertTrue(uninstalled)
        assertNull(engine.getLoadedPackage(guestPackage))
        assertFalse(engine.getInstalledPackages().any { it.packageName == guestPackage })
    }
}
