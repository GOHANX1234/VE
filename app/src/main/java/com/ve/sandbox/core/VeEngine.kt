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
                        val installed = archiveExtractor.installArchive(baseApk)
                        installedPackages[installed.packageName] = installed
                        Log.i(TAG, "Restored previously installed package: ${installed.packageName}")
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
        val loaded = getLoadedPackage(packageName)
            ?: throw IllegalStateException("Package '$packageName' not loaded into sandbox")
        val targetClass = activityClassName ?: loaded.manifest.launcherActivity?.name
            ?: throw IllegalStateException("No launcher Activity found in manifest for '$packageName'")

        val targetIntent = android.content.Intent().apply {
            component = android.content.ComponentName(packageName, targetClass)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(targetIntent)
    }

    fun clearData(packageName: String): Boolean {
        val installed = installedPackages[packageName] ?: return false
        val dataDir = File(installed.dataDir)
        return if (dataDir.exists()) {
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
            sandboxPkgDir.deleteRecursively()
        }
        return true
    }

    fun getInstalledPackages(): List<InstalledPackage> = installedPackages.values.toList()

    fun getLoadedPackage(packageName: String): LoadedPackage? = loadedPackages[packageName]

    fun getClassLoader(packageName: String): ClassLoader? = loadedPackages[packageName]?.classLoader

    fun getResources(packageName: String): android.content.res.Resources? = loadedPackages[packageName]?.resources
}
