package com.ve.sandbox.core.context

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.LayoutInflater
import androidx.annotation.RequiresApi
import com.ve.sandbox.core.model.LoadedPackage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * ProxyContext: A high-fidelity fake Context layer for virtualized guest apps.
 *
 * Android Internals Mental Model:
 * 1. Every Application, Activity, and Service is a ContextWrapper.
 * 2. When components query their identity or storage paths, they query their Context.
 * 3. By intercepting these calls, ProxyContext creates an illusion of a dedicated sandbox:
 *    - Identity: getPackageName(), getApplicationInfo(), getPackageCodePath() report the guest identity.
 *    - Storage: getFilesDir(), getCacheDir(), getSharedPreferences(), getDatabasePath() redirect
 *      to an isolated directory under VE's private files.
 *    - Resources: getResources(), getAssets(), getClassLoader() route to our Phase 1 synthesized engines.
 *    - UI: LayoutInflater is cloned in this context so view inflation uses the guest ClassLoader & Resources.
 */
class ProxyContext(
    base: Context,
    val loadedPackage: LoadedPackage,
    private val guestAppSupplier: () -> Application? = { null }
) : ContextWrapper(base) {

    companion object {
        private const val TAG = "VeProxyContext"
    }

    // Isolated directories under VE host storage
    private val rootDataDir = File(loadedPackage.installedPackage.dataDir).apply { mkdirs() }
    private val filesDirectory = File(rootDataDir, "files").apply { mkdirs() }
    private val cacheDirectory = File(rootDataDir, "cache").apply { mkdirs() }
    private val codeCacheDirectory = File(rootDataDir, "code_cache").apply { mkdirs() }
    private val noBackupDirectory = File(rootDataDir, "no_backup").apply { mkdirs() }
    private val sharedPrefsDirectory = File(rootDataDir, "shared_prefs").apply { mkdirs() }
    private val databasesDirectory = File(rootDataDir, "databases").apply { mkdirs() }

    private val sharedPreferencesCache = ConcurrentHashMap<String, VirtualSharedPreferences>()
    private var cachedApplicationInfo: ApplicationInfo? = null
    private var layoutInflater: LayoutInflater? = null

    // -------------------------------------------------------------
    // Package Identity Redirection
    // -------------------------------------------------------------

    override fun getPackageName(): String = loadedPackage.packageName

    fun getBasePackageName(): String = loadedPackage.packageName

    override fun getOpPackageName(): String = loadedPackage.packageName

    override fun getPackageCodePath(): String = loadedPackage.installedPackage.baseApkPath

    override fun getPackageResourcePath(): String = loadedPackage.installedPackage.baseApkPath

    override fun getApplicationInfo(): ApplicationInfo {
        cachedApplicationInfo?.let { return it }

        val info = ApplicationInfo().apply {
            packageName = loadedPackage.packageName
            name = loadedPackage.manifest.applicationClassName
            className = loadedPackage.manifest.applicationClassName
            sourceDir = loadedPackage.installedPackage.baseApkPath
            publicSourceDir = loadedPackage.installedPackage.baseApkPath
            if (loadedPackage.installedPackage.splitApkPaths.isNotEmpty()) {
                splitSourceDirs = loadedPackage.installedPackage.splitApkPaths.toTypedArray()
                splitPublicSourceDirs = loadedPackage.installedPackage.splitApkPaths.toTypedArray()
            }
            dataDir = rootDataDir.absolutePath
            nativeLibraryDir = loadedPackage.installedPackage.nativeLibDir
            targetSdkVersion = loadedPackage.manifest.targetSdkVersion
            minSdkVersion = loadedPackage.manifest.minSdkVersion
            uid = Process.myUid()
            flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP
        }

        cachedApplicationInfo = info
        return info
    }

    // -------------------------------------------------------------
    // Resources & ClassLoader Redirection
    // -------------------------------------------------------------

    override fun getClassLoader(): ClassLoader = loadedPackage.classLoader

    override fun getResources(): Resources = loadedPackage.resources

    override fun getAssets(): AssetManager = loadedPackage.assetManager

    override fun getApplicationContext(): Context {
        return guestAppSupplier() ?: this
    }

    // -------------------------------------------------------------
    // Storage & Filesystem Redirection (Sandbox Isolation)
    // -------------------------------------------------------------

    override fun getDataDir(): File = rootDataDir

    override fun getFilesDir(): File = filesDirectory

    override fun getCacheDir(): File = cacheDirectory

    override fun getCodeCacheDir(): File = codeCacheDirectory

    override fun getNoBackupFilesDir(): File = noBackupDirectory

    override fun getDir(name: String, mode: Int): File {
        return File(rootDataDir, "app_$name").apply { mkdirs() }
    }

    override fun getDatabasePath(name: String): File {
        return if (name.startsWith(File.separator)) {
            File(name)
        } else {
            File(databasesDirectory, name).apply { parentFile?.mkdirs() }
        }
    }

    override fun databaseList(): Array<String> = databasesDirectory.list() ?: emptyArray()

    override fun deleteDatabase(name: String): Boolean = getDatabasePath(name).delete()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return sharedPreferencesCache.computeIfAbsent(name) {
            val file = File(sharedPrefsDirectory, "$name.xml")
            VirtualSharedPreferences(file)
        }
    }

    override fun deleteSharedPreferences(name: String): Boolean {
        sharedPreferencesCache.remove(name)
        val file = File(sharedPrefsDirectory, "$name.xml")
        val backup = File(sharedPrefsDirectory, "$name.xml.bak")
        val deletedFile = if (file.exists()) file.delete() else false
        val deletedBackup = if (backup.exists()) backup.delete() else false
        return deletedFile || deletedBackup
    }

    override fun fileList(): Array<String> = filesDirectory.list() ?: emptyArray()

    override fun getFileStreamPath(name: String): File = File(filesDirectory, name)

    override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val file = getFileStreamPath(name)
        val append = (mode and Context.MODE_APPEND) != 0
        return FileOutputStream(file, append)
    }

    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()

    override fun getExternalFilesDir(type: String?): File? {
        val baseExt = super.getExternalFilesDir(null) ?: return null
        val guestExt = File(baseExt, "ve_sandbox/${loadedPackage.packageName}/files/${type ?: ""}")
        guestExt.mkdirs()
        return guestExt
    }

    override fun getExternalFilesDirs(type: String?): Array<File> {
        val dir = getExternalFilesDir(type)
        return if (dir != null) arrayOf(dir) else emptyArray()
    }

    override fun getExternalCacheDir(): File? {
        val baseExt = super.getExternalCacheDir() ?: return null
        val guestExt = File(baseExt, "ve_sandbox/${loadedPackage.packageName}/cache")
        guestExt.mkdirs()
        return guestExt
    }

    override fun getExternalCacheDirs(): Array<File> {
        val dir = getExternalCacheDir()
        return if (dir != null) arrayOf(dir) else emptyArray()
    }

    // -------------------------------------------------------------
    // System Services & View Inflation
    // -------------------------------------------------------------

    override fun getSystemService(name: String): Any? {
        if (name == Context.LAYOUT_INFLATER_SERVICE) {
            if (layoutInflater == null) {
                val baseInflater = super.getSystemService(name) as? LayoutInflater
                layoutInflater = baseInflater?.cloneInContext(this)
            }
            return layoutInflater
        }
        return super.getSystemService(name)
    }
}
