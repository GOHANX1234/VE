package com.ve.sandbox

import android.content.SharedPreferences
import com.ve.sandbox.core.context.VirtualSharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VirtualSharedPreferencesTest {

    private lateinit var tempFile: File
    private lateinit var prefs: VirtualSharedPreferences

    @Before
    fun setup() {
        val tempDir = File("/tmp/ve_prefs_test_${System.currentTimeMillis()}").apply { mkdirs() }
        tempFile = File(tempDir, "test_prefs.xml")
        prefs = VirtualSharedPreferences(tempFile)
    }

    @Test
    fun testPutAndGetPrimitives() {
        prefs.edit()
            .putString("key_string", "VirtualAppSandbox")
            .putInt("key_int", 1337)
            .putLong("key_long", 9876543210L)
            .putFloat("key_float", 3.14159f)
            .putBoolean("key_bool", true)
            .putStringSet("key_set", setOf("A", "B", "C"))
            .commit()

        assertEquals("VirtualAppSandbox", prefs.getString("key_string", null))
        assertEquals(1337, prefs.getInt("key_int", 0))
        assertEquals(9876543210L, prefs.getLong("key_long", 0L))
        assertEquals(3.14159f, prefs.getFloat("key_float", 0f), 0.0001f)
        assertTrue(prefs.getBoolean("key_bool", false))
        assertEquals(setOf("A", "B", "C"), prefs.getStringSet("key_set", null))
    }

    @Test
    fun testPersistenceAndReload() {
        prefs.edit()
            .putString("guest_user", "ve_tester")
            .putInt("launch_count", 42)
            .commit()

        assertTrue("SharedPreferences XML file must exist: ${tempFile.absolutePath}", tempFile.exists())
        val xmlContent = tempFile.readText()
        println("Generated XML:\n$xmlContent")
        assertTrue(xmlContent.contains("guest_user"))
        assertTrue(xmlContent.contains("ve_tester"))

        // Create new instance pointing to same file
        val reloadedPrefs = VirtualSharedPreferences(tempFile)
        assertEquals("ve_tester", reloadedPrefs.getString("guest_user", null))
        assertEquals(42, reloadedPrefs.getInt("launch_count", 0))
    }

    @Test
    fun testRemoveAndClear() {
        prefs.edit()
            .putString("item1", "val1")
            .putString("item2", "val2")
            .commit()

        assertEquals("val1", prefs.getString("item1", null))

        prefs.edit().remove("item1").commit()
        assertNull(prefs.getString("item1", null))
        assertEquals("val2", prefs.getString("item2", null))

        prefs.edit().clear().commit()
        assertNull(prefs.getString("item2", null))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun testChangeListener() {
        val changedKeys = mutableListOf<String>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) changedKeys.add(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("notif_key", "active").commit()
        assertTrue("Listener should have recorded changed key", changedKeys.contains("notif_key"))

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("another_key", "passive").commit()
        assertFalse("Listener should not receive events after unregister", changedKeys.contains("another_key"))
    }
}
