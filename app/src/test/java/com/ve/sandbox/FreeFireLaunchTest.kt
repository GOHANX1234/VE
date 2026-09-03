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
        assertEquals("com.dts.freefireth.FFMainActivity", loaded.manifest.launcherActivity?.name)
        assertTrue("Must mount split APKs", loaded.splitApkPaths.isNotEmpty())
    }
}
