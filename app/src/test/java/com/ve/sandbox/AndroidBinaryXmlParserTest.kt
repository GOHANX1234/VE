package com.ve.sandbox

import com.ve.sandbox.core.parser.AndroidBinaryXmlParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class AndroidBinaryXmlParserTest {

    @Test
    fun testParseTargetApkManifest() {
        val apkFile = File("/workspaces/VE/test-target-app/build/outputs/apk/debug/test-target-app-debug.apk")
        assertTrue("Test APK must exist: ${apkFile.absolutePath}", apkFile.exists())

        val zip = ZipFile(apkFile)
        val manifestEntry = zip.getEntry("AndroidManifest.xml")
        assertNotNull("AndroidManifest.xml must exist in APK", manifestEntry)

        val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
        val parser = AndroidBinaryXmlParser()
        val manifest = parser.parse(manifestBytes)

        println("Parsed Manifest:")
        println("  Package: ${manifest.packageName}")
        println("  VersionCode: ${manifest.versionCode}")
        println("  VersionName: ${manifest.versionName}")
        println("  MinSdk: ${manifest.minSdkVersion}")
        println("  TargetSdk: ${manifest.targetSdkVersion}")
        println("  AppClass: ${manifest.applicationClassName}")
        println("  Permissions: ${manifest.permissions}")
        println("  Activities: ${manifest.activities.map { "${it.name} (launcher=${it.isLauncher})" }}")

        assertEquals("com.target.sampleapp", manifest.packageName)
        assertEquals("com.target.sampleapp.TargetApp", manifest.applicationClassName)
        assertTrue(manifest.permissions.contains("android.permission.INTERNET"))
        assertTrue(manifest.permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(manifest.permissions.contains("android.permission.VIBRATE"))

        val mainActivity = manifest.activities.firstOrNull { it.isLauncher }
        assertNotNull("Launcher activity must be found", mainActivity)
        assertEquals("com.target.sampleapp.TargetMainActivity", mainActivity?.name)

        val secondActivity = manifest.activities.firstOrNull { it.name.endsWith("TargetSecondActivity") }
        assertNotNull("Secondary activity must be found", secondActivity)
    }
}
