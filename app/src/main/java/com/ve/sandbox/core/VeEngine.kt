package com.ve.sandbox.core

import android.content.Context
import android.util.Log
import com.ve.sandbox.core.compat.HiddenApiManager
import com.ve.sandbox.core.loader.VirtualClassLoader
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.parser.PackageArchiveExtractor
import com.ve.sandbox.core.resource.VirtualResourceManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * VeEngine: The core sandbox manager for the VE Container.
 *
 * Coordinates:
 * - Package extraction & normalization (APK, APKS, APKM, XAPK)
 * - Binary manifest parsing
 * - Dynamic dex classloading via VirtualClassLoader
 * - AssetManager and Resources synthesis via VirtualResourceManager
 */
class VeEngine private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "VeEngine"

        @Volatile
        private var INSTANCE: VeEngine? = null

        fun init(context: Context): VeEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VeEngine(context.applicationContext).also {
                    INSTANCE = it
                    HiddenApiManager.unseal()
                }
            }
        }

        fun get(): VeEngine {
            return INSTANCE ?: throw IllegalStateException("VeEngine must be initialized via init(Context) first")
        }
    }

    private val sandboxRootDir = File(appContext.filesDir, "ve_sandbox").apply { mkdirs() }
    private val archiveExtractor = PackageArchiveExtractor(sandboxRootDir)
    private val loadedPackages = ConcurrentHashMap<String, LoadedPackage>()
    private val installedPackages = ConcurrentHashMap<String, InstalledPackage>()

    init {
        HiddenApiManager.unseal()
        scanExistingInstallations()
    }

    private fun scanExistingInstallations() {
        val dirs = sandboxRootDir.listFiles() ?: return
        for (pkgDir in dirs) {
            if (pkgDir.isDirectory && !pkgDir.name.startsWith("temp_")) {
                val baseApk = File(pkgDir, "base.apk")
                if (baseApk.exists()) {
                    try {
                        val manifest = com.ve.sandbox.core.parser.AndroidBinaryXmlParser().parse(
                            java.util.zip.ZipFile(baseApk).use { zip ->
                                val entry = zip.getEntry("AndroidManifest.xml")
                                    ?: return@use null
                                zip.getInputStream(entry).use { it.readBytes() }
                            } ?: continue
                        )

                        val splitsDir = File(pkgDir, "splits")
                        val splitPaths = if (splitsDir.exists()) {
                            splitsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") }
                                ?.map { it.absolutePath } ?: emptyList()
                        } else {
                            emptyList()
                        }

                        val nativeLibDir = File(pkgDir, "lib").absolutePath
                        val dataDir = File(pkgDir, "data").apply { mkdirs() }.absolutePath

                        val archiveType = when {
                            File(pkgDir, "obb").exists() -> com.ve.sandbox.core.model.ArchiveType.XAPK
                            splitPaths.isNotEmpty() -> com.ve.sandbox.core.model.ArchiveType.APKS
                            else -> com.ve.sandbox.core.model.ArchiveType.APK
                        }

                        val installed = InstalledPackage(
                            packageName = manifest.packageName,
                            archiveType = archiveType,
                            baseApkPath = baseApk.absolutePath,
                            splitApkPaths = splitPaths,
                            nativeLibDir = nativeLibDir,
                            dataDir = dataDir,
                            manifest = manifest
                        )
                        installedPackages[installed.packageName] = installed
                        try {
                            load(installed)
                        } catch (e: Throwable) {
                            Log.w(TAG, "Could not eagerly load ${installed.packageName}", e)
                        }
                        Log.i(TAG, "Restored previously installed package: ${installed.packageName} (splits: ${splitPaths.size})")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore installed package in ${pkgDir.name}", e)
                    }
                }
            }
        }
    }

    /**
     * Installs an archive (APK, APKS, APKM, XAPK) into the sandbox storage.
     */
    fun install(archiveFile: File): InstalledPackage {
        Log.i(TAG, "Installing archive: ${archiveFile.absolutePath}")
        val installed = archiveExtractor.installArchive(archiveFile)
        installedPackages[installed.packageName] = installed
        return installed
    }

    /**
     * Dynamically loads an installed package into memory:
     * - Creates guest DexClassLoader with isolated libraries and APK dex files
     * - Reflectively builds guest AssetManager and Resources
     */
    fun load(installedPackage: InstalledPackage): LoadedPackage {
        val existing = loadedPackages[installedPackage.packageName]
        if (existing != null) return existing

        Log.i(TAG, "Loading package into sandbox: ${installedPackage.packageName}")

        // Android 14 Security Hardening: Ensure all APKs are marked read-only before DexClassLoader
        for (path in installedPackage.allApkPaths) {
            val f = File(path)
            if (f.exists()) {
                f.setReadOnly()
            }
        }

        // 1. Dynamic Dex ClassLoader
        val virtualClassLoader = VirtualClassLoader(
            apkPaths = installedPackage.allApkPaths,
            nativeLibDir = installedPackage.nativeLibDir,
            cacheDir = appContext.codeCacheDir ?: appContext.cacheDir,
            parentClassLoader = appContext.classLoader
        )

        // 2. Resources and AssetManager
        val resources = VirtualResourceManager.createResources(
            hostContext = appContext,
            apkPaths = installedPackage.allApkPaths
        )
        val assetManager = resources.assets

        val loadedPackage = LoadedPackage(
            installedPackage = installedPackage,
            classLoader = virtualClassLoader.classLoader,
            assetManager = assetManager,
            resources = resources
        )

        loadedPackages[installedPackage.packageName] = loadedPackage
        Log.i(TAG, "Successfully loaded package: ${installedPackage.packageName}")
        return loadedPackage
    }

    /**
     * Convenience method to install and load in one step.
     */
    fun installAndLoad(archiveFile: File): LoadedPackage {
        val installed = install(archiveFile)
        return load(installed)
    }

    private val guestApplicationManager = com.ve.sandbox.core.context.GuestApplicationManager(appContext)

    fun getProxyContext(packageName: String): com.ve.sandbox.core.context.ProxyContext? {
        val loaded = loadedPackages[packageName] ?: return null
        return guestApplicationManager.getOrCreateProxyContext(loaded)
    }

    fun startGuestApplication(packageName: String): android.app.Application {
        val loaded = loadedPackages[packageName]
            ?: throw IllegalStateException("Package '$packageName' has not been loaded into sandbox")
        return guestApplicationManager.startGuestApplication(loaded)
    }

    fun startGuestApplication(loadedPackage: LoadedPackage): android.app.Application {
        return guestApplicationManager.startGuestApplication(loadedPackage)
    }

    fun launchGuestActivity(context: Context, packageName: String, activityClassName: String? = null) {
        val loaded = getLoadedPackage(packageName) ?: run {
            val installed = installedPackages[packageName]
                ?: throw IllegalStateException("Package '$packageName' not loaded into sandbox")
            load(installed)
        }
        val targetClass = activityClassName ?: loaded.manifest.launcherActivity?.name
            ?: throw IllegalStateException("No launcher Activity found in manifest for '$packageName'")

        val targetIntent = android.content.Intent().apply {
            component = android.content.ComponentName(packageName, targetClass)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Determine launchMode & screenOrientation from guest manifest
        val comp = loaded.manifest.activities.firstOrNull { it.name == targetClass }
        val launchMode = comp?.launchMode ?: android.content.pm.ActivityInfo.LAUNCH_MULTIPLE
        val screenOrientation = comp?.screenOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Pre-masquerade Intent directly to StubActivity so ATMS validation in system_server never fails!
        val masqueradedIntent = com.ve.sandbox.core.stub.StubManager.masqueradeIntent(
            targetIntent,
            context.packageName,
            launchMode,
            screenOrientation
        )
        masqueradedIntent.flags = masqueradedIntent.flags or android.content.Intent.FLAG_ACTIVITY_NEW_TASK

        context.startActivity(masqueradedIntent)
    }

    private fun makeWritableRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { makeWritableRecursively(it) }
        }
        file.setWritable(true)
    }

    fun clearData(packageName: String): Boolean {
        val installed = installedPackages[packageName] ?: return false
        val dataDir = File(installed.dataDir)
        return if (dataDir.exists()) {
            makeWritableRecursively(dataDir)
            dataDir.deleteRecursively()
            dataDir.mkdirs()
        } else {
            true
        }
    }

    fun uninstall(packageName: String): Boolean {
        val installed = installedPackages.remove(packageName) ?: return false
        loadedPackages.remove(packageName)
        val sandboxPkgDir = File(sandboxRootDir, packageName)
        if (sandboxPkgDir.exists()) {
            makeWritableRecursively(sandboxPkgDir)
            sandboxPkgDir.deleteRecursively()
        }
        return true
    }

    fun getInstalledPackages(): List<InstalledPackage> = installedPackages.values.toList()

    fun getLoadedPackage(packageName: String): LoadedPackage? = loadedPackages[packageName]

    fun getClassLoader(packageName: String): ClassLoader? = loadedPackages[packageName]?.classLoader

    fun getResources(packageName: String): android.content.res.Resources? = loadedPackages[packageName]?.resources
}
