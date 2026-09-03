package com.ve.sandbox

import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.parser.PackageArchiveExtractor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class PackageArchiveExtractorTest {

    private val targetApk = File("/workspaces/VE/test-target-app/build/outputs/apk/debug/test-target-app-debug.apk")
    private val assetsDir = File("/workspaces/VE/app/src/main/assets/test_packages")
    private lateinit var sandboxDir: File
    private lateinit var extractor: PackageArchiveExtractor

    @Before
    fun setup() {
        sandboxDir = File("/tmp/ve_sandbox_test_${System.currentTimeMillis()}").apply { mkdirs() }
        extractor = PackageArchiveExtractor(sandboxDir)
    }

    @Test
    fun testInstallSingleApk() {
        val apkFile = File(assetsDir, "sample_app.apk")
        assertTrue(apkFile.exists())

        val installed = extractor.installArchive(apkFile)
        assertEquals("com.target.sampleapp", installed.packageName)
        assertEquals(ArchiveType.APK, installed.archiveType)
        assertTrue(File(installed.baseApkPath).exists())
        assertEquals("com.target.sampleapp.TargetApp", installed.manifest.applicationClassName)
        assertEquals("com.target.sampleapp.TargetMainActivity", installed.manifest.launcherActivity?.name)
    }

    @Test
    fun testInstallApks() {
        val apksFile = File(assetsDir, "sample_app.apks")
        assertTrue(apksFile.exists())

        val installed = extractor.installArchive(apksFile)
        assertEquals("com.target.sampleapp", installed.packageName)
        assertEquals(ArchiveType.APKS, installed.archiveType)
        assertTrue(File(installed.baseApkPath).exists())
        assertEquals("com.target.sampleapp.TargetApp", installed.manifest.applicationClassName)
    }

    @Test
    fun testInstallXapk() {
        val xapkFile = File(assetsDir, "sample_app.xapk")
        assertTrue(xapkFile.exists())

        val installed = extractor.installArchive(xapkFile)
        assertEquals("com.target.sampleapp", installed.packageName)
        assertEquals(ArchiveType.XAPK, installed.archiveType)
        assertTrue(File(installed.baseApkPath).exists())
    }

    @Test
    fun testInstallApkm() {
        val apkmFile = File(assetsDir, "sample_app.apkm")
        assertTrue(apkmFile.exists())

        val installed = extractor.installArchive(apkmFile)
        assertEquals("com.target.sampleapp", installed.packageName)
        assertEquals(ArchiveType.APKM, installed.archiveType)
        assertTrue(File(installed.baseApkPath).exists())
    }

    @Test
    fun testInstallApksWithMisleadingApkExtension() {
        val apksFile = File(assetsDir, "sample_app.apks")
        assertTrue(apksFile.exists())

        // Create a disguised copy named with .apk extension (reproducing user scenario)
        val disguisedFile = File(sandboxDir, "picked_1788423011650.apk")
        apksFile.copyTo(disguisedFile, overwrite = true)
        assertTrue(disguisedFile.exists())

        val detectedType = extractor.detectArchiveType(disguisedFile)
        assertEquals("Should accurately detect APKS despite .apk filename", ArchiveType.APKS, detectedType)

        val installed = extractor.installArchive(disguisedFile)
        assertEquals("com.target.sampleapp", installed.packageName)
        assertEquals(ArchiveType.APKS, installed.archiveType)
        assertTrue(File(installed.baseApkPath).exists())
    }
}
