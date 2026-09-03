package com.ve.sandbox

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import com.ve.sandbox.core.stub.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StubManagerTest {

    private val hostPackage = "com.ve.sandbox"
    private val guestPackage = "com.target.sampleapp"
    private val guestActivity = "com.target.sampleapp.TargetMainActivity"

    @Test
    fun testMasqueradeAndDemasqueradeStandard() {
        val realIntent = Intent().apply {
            component = ComponentName(guestPackage, guestActivity)
            putExtra("user_id", 42)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Outbound masquerade
        val stubIntent = StubManager.masqueradeIntent(realIntent, hostPackage, ActivityInfo.LAUNCH_MULTIPLE)
        assertEquals(hostPackage, stubIntent.component?.packageName)
        assertEquals(StubManager.STUB_STANDARD, stubIntent.component?.className)
        assertTrue(StubManager.isStubIntent(stubIntent))
        assertEquals(guestPackage, StubManager.extractTargetPackage(stubIntent))
        assertEquals(guestActivity, StubManager.extractTargetClass(stubIntent))

        // Inbound demasquerade
        val unpacked = StubManager.demasqueradeIntent(stubIntent)
        assertNotNull(unpacked)
        assertEquals(guestPackage, unpacked?.component?.packageName)
        assertEquals(guestActivity, unpacked?.component?.className)
        assertEquals(42, unpacked?.getIntExtra("user_id", 0))
    }

    @Test
    fun testLaunchModeMappings() {
        val realIntent = Intent().apply {
            component = ComponentName(guestPackage, guestActivity)
        }

        val topIntent = StubManager.masqueradeIntent(realIntent, hostPackage, ActivityInfo.LAUNCH_SINGLE_TOP)
        assertEquals(StubManager.STUB_SINGLE_TOP, topIntent.component?.className)

        val taskIntent = StubManager.masqueradeIntent(realIntent, hostPackage, ActivityInfo.LAUNCH_SINGLE_TASK)
        assertEquals(StubManager.STUB_SINGLE_TASK, taskIntent.component?.className)

        val instanceIntent = StubManager.masqueradeIntent(realIntent, hostPackage, ActivityInfo.LAUNCH_SINGLE_INSTANCE)
        assertEquals(StubManager.STUB_SINGLE_INSTANCE, instanceIntent.component?.className)
    }

    @Test
    fun testNonStubIntent() {
        val regularIntent = Intent().apply {
            component = ComponentName(hostPackage, "com.ve.sandbox.ui.MainActivity")
        }
        assertFalse(StubManager.isStubIntent(regularIntent))
        assertNull(StubManager.demasqueradeIntent(regularIntent))
    }
}
