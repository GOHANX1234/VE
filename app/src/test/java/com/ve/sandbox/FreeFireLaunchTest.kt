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
    }
}
