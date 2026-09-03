package com.ve.sandbox.core.parser

import android.os.Build
import android.util.Log
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.ParsedManifest
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Extracts and prepares APK, APKS, APKM, and XAPK package archives for virtualization.
 *
 * It normalizes all package formats into an installed directory structure:
 * - base.apk
 * - splits/
 * - lib/<abi>/
 * - obb/ (for XAPK)
 * - manifest (parsed)
 */
class PackageArchiveExtractor(
    private val appRootDir: File,
    private val manifestParser: AndroidBinaryXmlParser = AndroidBinaryXmlParser()
) {
    companion object {
        private const val TAG = "PackageArchiveExtractor"
    }

    /**
     * Inspects the internal ZIP structure first to accurately detect the package format
     * regardless of whether the file extension was preserved, missing, or renamed.
     */
    fun detectArchiveType(file: File): ArchiveType {
        try {
            ZipFile(file).use { zip ->
                val hasRootManifest = zip.getEntry("AndroidManifest.xml") != null
                val hasManifestJson = zip.getEntry("manifest.json") != null
                val hasInfoJson = zip.getEntry("info.json") != null
                val hasChildApks = zip.entries().asSequence().any {
                    !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true)
                }
                val hasSplitsDir = zip.entries().asSequence().any {
                    it.name.startsWith("splits/", ignoreCase = true)
                }

                // If it contains child APKs or splits/ directory, it is a multi-split bundle!
                if (hasSplitsDir || hasChildApks) {
                    return when {
                        hasManifestJson || file.name.endsWith(".xapk", ignoreCase = true) -> ArchiveType.XAPK
                        hasInfoJson || file.name.endsWith(".apkm", ignoreCase = true) -> ArchiveType.APKM
                        else -> ArchiveType.APKS
                    }
                }

                if (hasManifestJson) return ArchiveType.XAPK
                if (hasInfoJson) return ArchiveType.APKM
                if (hasRootManifest) return ArchiveType.APK
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect ZIP structure for ${file.name}: ${e.message}")
        }

        // Fallback: examine file extension
        val name = file.name.lowercase()
        return when {
            name.endsWith(".apks") -> ArchiveType.APKS
            name.endsWith(".xapk") -> ArchiveType.XAPK
            name.endsWith(".apkm") -> ArchiveType.APKM
            name.endsWith(".apk") -> ArchiveType.APK
            else -> ArchiveType.UNKNOWN
        }
    }

    fun installArchive(sourceArchive: File): InstalledPackage {
        val archiveType = detectArchiveType(sourceArchive)
        val tempDir = File(appRootDir, "temp_staging_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            return when (archiveType) {
                ArchiveType.APK -> {
                    try {
                        installSingleApk(sourceArchive)
                    } catch (e: IllegalArgumentException) {
                        // Fallback: if AndroidManifest.xml is missing at root, it might be an APKS named as .apk
                        Log.w(TAG, "Single APK installation failed for ${sourceArchive.name}, retrying as APKS bundle: ${e.message}")
                        installApks(sourceArchive, tempDir)
                    }
                }
                ArchiveType.APKS -> installApks(sourceArchive, tempDir)
                ArchiveType.XAPK -> installXapk(sourceArchive, tempDir)
                ArchiveType.APKM -> installApkm(sourceArchive, tempDir)
                ArchiveType.UNKNOWN -> {
                    // Fail-safe attempt as APKS then single APK
                    try {
                        installApks(sourceArchive, tempDir)
                    } catch (e: Exception) {
                        try {
                            installSingleApk(sourceArchive)
                        } catch (e2: Exception) {
                            throw IllegalArgumentException("Unsupported package archive: ${sourceArchive.name}")
                        }
                    }
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun installSingleApk(apkFile: File): InstalledPackage {
        val manifest = extractAndParseManifest(apkFile)
        val packageDir = File(appRootDir, manifest.packageName).apply { mkdirs() }
        val targetBaseApk = File(packageDir, "base.apk")
        apkFile.copyTo(targetBaseApk, overwrite = true)

        val nativeLibDir = File(packageDir, "lib").apply { mkdirs() }
        extractNativeLibraries(targetBaseApk, nativeLibDir)

        val dataDir = File(packageDir, "data").apply { mkdirs() }

        return InstalledPackage(
            packageName = manifest.packageName,
            archiveType = ArchiveType.APK,
            baseApkPath = targetBaseApk.absolutePath,
            splitApkPaths = emptyList(),
            nativeLibDir = nativeLibDir.absolutePath,
            dataDir = dataDir.absolutePath,
            manifest = manifest
        )
    }

    private fun installApks(archiveFile: File, stagingDir: File): InstalledPackage {
        unzip(archiveFile, stagingDir)

        // Locate all APKs inside the APKS
        val apkFiles = stagingDir.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files found inside APKS bundle")
        }

        // Find base APK: look for base-master.apk or parse each to find the launcher activity
        val baseApkFile = apkFiles.firstOrNull {
            it.name.contains("base-master", ignoreCase = true) || it.name.equals("base.apk", ignoreCase = true)
        } ?: findBaseApkFromList(apkFiles)

        val manifest = extractAndParseManifest(baseApkFile)
        val packageDir = File(appRootDir, manifest.packageName).apply { mkdirs() }
        val targetBaseApk = File(packageDir, "base.apk")
        baseApkFile.copyTo(targetBaseApk, overwrite = true)

        val splitsDir = File(packageDir, "splits").apply { mkdirs() }
        val splitPaths = mutableListOf<String>()
        val nativeLibDir = File(packageDir, "lib").apply { mkdirs() }

        for (apk in apkFiles) {
            if (apk.absolutePath != baseApkFile.absolutePath) {
                val targetSplit = File(splitsDir, apk.name)
                apk.copyTo(targetSplit, overwrite = true)
                splitPaths.add(targetSplit.absolutePath)
                extractNativeLibraries(targetSplit, nativeLibDir)
            }
        }
        extractNativeLibraries(targetBaseApk, nativeLibDir)

        val dataDir = File(packageDir, "data").apply { mkdirs() }

        return InstalledPackage(
            packageName = manifest.packageName,
            archiveType = ArchiveType.APKS,
            baseApkPath = targetBaseApk.absolutePath,
            splitApkPaths = splitPaths,
            nativeLibDir = nativeLibDir.absolutePath,
            dataDir = dataDir.absolutePath,
            manifest = manifest
        )
    }

    private fun installXapk(archiveFile: File, stagingDir: File): InstalledPackage {
        unzip(archiveFile, stagingDir)

        val apkFiles = stagingDir.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files found inside XAPK")
        }

        val baseApkFile = findBaseApkFromList(apkFiles)
        val manifest = extractAndParseManifest(baseApkFile)
        val packageDir = File(appRootDir, manifest.packageName).apply { mkdirs() }

        val targetBaseApk = File(packageDir, "base.apk")
        baseApkFile.copyTo(targetBaseApk, overwrite = true)

        val splitsDir = File(packageDir, "splits").apply { mkdirs() }
        val splitPaths = mutableListOf<String>()
        val nativeLibDir = File(packageDir, "lib").apply { mkdirs() }

        for (apk in apkFiles) {
            if (apk.absolutePath != baseApkFile.absolutePath) {
                val targetSplit = File(splitsDir, apk.name)
                apk.copyTo(targetSplit, overwrite = true)
                splitPaths.add(targetSplit.absolutePath)
                extractNativeLibraries(targetSplit, nativeLibDir)
            }
        }
        extractNativeLibraries(targetBaseApk, nativeLibDir)

        // Handle OBB directory if present inside XAPK
        val obbSource = File(stagingDir, "Android/obb")
        if (obbSource.exists() && obbSource.isDirectory) {
            val targetObb = File(packageDir, "obb").apply { mkdirs() }
            obbSource.copyRecursively(targetObb, overwrite = true)
        }

        val dataDir = File(packageDir, "data").apply { mkdirs() }

        return InstalledPackage(
            packageName = manifest.packageName,
            archiveType = ArchiveType.XAPK,
            baseApkPath = targetBaseApk.absolutePath,
            splitApkPaths = splitPaths,
            nativeLibDir = nativeLibDir.absolutePath,
            dataDir = dataDir.absolutePath,
            manifest = manifest
        )
    }

    private fun installApkm(archiveFile: File, stagingDir: File): InstalledPackage {
        unzip(archiveFile, stagingDir)

        val apkFiles = stagingDir.walkTopDown().filter { it.isFile && it.name.endsWith(".apk") }.toList()
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files found inside APKM")
        }

        val baseApkFile = apkFiles.firstOrNull { it.name.equals("base.apk", ignoreCase = true) }
            ?: findBaseApkFromList(apkFiles)
        val manifest = extractAndParseManifest(baseApkFile)
        val packageDir = File(appRootDir, manifest.packageName).apply { mkdirs() }

        val targetBaseApk = File(packageDir, "base.apk")
        baseApkFile.copyTo(targetBaseApk, overwrite = true)

        val splitsDir = File(packageDir, "splits").apply { mkdirs() }
        val splitPaths = mutableListOf<String>()
        val nativeLibDir = File(packageDir, "lib").apply { mkdirs() }

        for (apk in apkFiles) {
            if (apk.absolutePath != baseApkFile.absolutePath) {
                val targetSplit = File(splitsDir, apk.name)
                apk.copyTo(targetSplit, overwrite = true)
                splitPaths.add(targetSplit.absolutePath)
                extractNativeLibraries(targetSplit, nativeLibDir)
            }
        }
        extractNativeLibraries(targetBaseApk, nativeLibDir)

        val dataDir = File(packageDir, "data").apply { mkdirs() }

        return InstalledPackage(
            packageName = manifest.packageName,
            archiveType = ArchiveType.APKM,
            baseApkPath = targetBaseApk.absolutePath,
            splitApkPaths = splitPaths,
            nativeLibDir = nativeLibDir.absolutePath,
            dataDir = dataDir.absolutePath,
            manifest = manifest
        )
    }

    private fun findBaseApkFromList(apkFiles: List<File>): File {
        for (apk in apkFiles) {
            try {
                val manifest = extractAndParseManifest(apk)
                if (manifest.launcherActivity != null || manifest.activities.isNotEmpty()) {
                    return apk
                }
            } catch (e: Exception) {
                // Not a base APK
            }
        }
        // Fallback to the largest APK file
        return apkFiles.maxByOrNull { it.length() } ?: apkFiles.first()
    }

    private fun extractAndParseManifest(apkFile: File): ParsedManifest {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw IllegalArgumentException("Missing AndroidManifest.xml in ${apkFile.name}")

            zip.getInputStream(entry).use { input ->
                val bytes = input.readBytes()
                return manifestParser.parse(bytes)
            }
        }
    }

    private fun extractNativeLibraries(apkFile: File, targetLibDir: File) {
        val supportedAbis = getSupportedAbis()
        ZipFile(apkFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val parts = entry.name.split("/")
                    if (parts.size == 3) {
                        val abi = parts[1]
                        val soName = parts[2]
                        if (supportedAbis.contains(abi)) {
                            val abiDir = File(targetLibDir, abi).apply { mkdirs() }
                            val outFile = File(abiDir, soName)
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getSupportedAbis(): List<String> {
        val abis = mutableListOf<String>()
        if (Build.SUPPORTED_ABIS != null) {
            abis.addAll(Build.SUPPORTED_ABIS)
        }
        if (abis.isEmpty()) {
            abis.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
        }
        return abis
    }

    private fun unzip(zipFile: File, targetDir: File) {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val targetFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                } else {
                    targetFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }
}
