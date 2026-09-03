package com.ve.sandbox

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.DisplayMetrics
import android.view.Display
import com.ve.sandbox.core.context.ProxyContext
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedManifest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class ProxyContextTest {

    private lateinit var sandboxDir: File
    private lateinit var guestDataDir: File
    private lateinit var proxyContext: ProxyContext
    private lateinit var loadedPackage: LoadedPackage

    open class TestBaseContext : Context() {
        override fun getPackageName(): String = "com.ve.sandbox"
        override fun getAssets(): AssetManager = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        override fun getResources(): Resources = Resources(assets, DisplayMetrics(), Configuration())
        override fun getPackageManager(): PackageManager = throw UnsupportedOperationException()
        override fun getContentResolver(): ContentResolver = throw UnsupportedOperationException()
        override fun getMainLooper(): Looper = Looper.getMainLooper()
        override fun getApplicationContext(): Context = this
        override fun setTheme(resid: Int) {}
        override fun getTheme(): Resources.Theme = resources.newTheme()
        override fun getClassLoader(): ClassLoader = ClassLoader.getSystemClassLoader()
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply { packageName = "com.ve.sandbox" }
        override fun getPackageCodePath(): String = "/data/app/com.ve.sandbox/base.apk"
        override fun getPackageResourcePath(): String = "/data/app/com.ve.sandbox/base.apk"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = throw UnsupportedOperationException()
        override fun moveSharedPreferencesFrom(sourceContext: Context?, name: String?): Boolean = false
        override fun deleteSharedPreferences(name: String?): Boolean = false
        override fun openFileInput(name: String?): FileInputStream = throw UnsupportedOperationException()
        override fun openFileOutput(name: String?, mode: Int): FileOutputStream = throw UnsupportedOperationException()
        override fun deleteFile(name: String?): Boolean = false
        override fun getFileStreamPath(name: String?): File = File("/data/user/0/com.ve.sandbox/files", name ?: "")
        override fun getDataDir(): File = File("/data/user/0/com.ve.sandbox")
        override fun getFilesDir(): File = File("/data/user/0/com.ve.sandbox/files")
        override fun getNoBackupFilesDir(): File = File("/data/user/0/com.ve.sandbox/no_backup")
        override fun getExternalFilesDir(type: String?): File = File("/sdcard/Android/data/com.ve.sandbox/files")
        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(getExternalFilesDir(type))
        override fun getObbDir(): File = File("/sdcard/Android/obb/com.ve.sandbox")
        override fun getObbDirs(): Array<File> = arrayOf(getObbDir())
        override fun getCacheDir(): File = File("/data/user/0/com.ve.sandbox/cache")
        override fun getCodeCacheDir(): File = File("/data/user/0/com.ve.sandbox/code_cache")
        override fun getExternalCacheDir(): File = File("/sdcard/Android/data/com.ve.sandbox/cache")
        override fun getExternalCacheDirs(): Array<File> = arrayOf(getExternalCacheDir())
        override fun getExternalMediaDirs(): Array<File> = emptyArray()
        override fun fileList(): Array<String> = emptyArray()
        override fun getDir(name: String?, mode: Int): File = File("/data/user/0/com.ve.sandbox/app_$name")
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String?, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase = throw UnsupportedOperationException()
        override fun moveDatabaseFrom(sourceContext: Context?, name: String?): Boolean = false
        override fun deleteDatabase(name: String?): Boolean = false
        override fun getDatabasePath(name: String?): File = File("/data/user/0/com.ve.sandbox/databases", name ?: "")
        override fun databaseList(): Array<String> = emptyArray()
        override fun getWallpaper(): Drawable = throw UnsupportedOperationException()
        override fun peekWallpaper(): Drawable = throw UnsupportedOperationException()
        override fun getWallpaperDesiredMinimumWidth(): Int = 0
        override fun getWallpaperDesiredMinimumHeight(): Int = 0
        override fun setWallpaper(bitmap: Bitmap?) {}
        override fun setWallpaper(data: InputStream?) {}
        override fun clearWallpaper() {}
        override fun startActivity(intent: Intent?) {}
        override fun startActivity(intent: Intent?, options: Bundle?) {}
        override fun startActivities(intents: Array<out Intent>?) {}
        override fun startActivities(intents: Array<out Intent>?, options: Bundle?) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {}
        override fun startIntentSender(intent: android.content.IntentSender?, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: Bundle?) {}
        override fun sendBroadcast(intent: Intent?) {}
        override fun sendBroadcast(intent: Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: Intent?, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: Intent, receiverPermission: String?, resultReceiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun sendBroadcastAsUser(intent: Intent?, user: UserHandle?) {}
        override fun sendBroadcastAsUser(intent: Intent?, user: UserHandle?, receiverPermission: String?) {}
        override fun sendOrderedBroadcastAsUser(intent: Intent?, user: UserHandle?, receiverPermission: String?, resultReceiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun sendStickyBroadcast(intent: Intent?) {}
        override fun sendStickyOrderedBroadcast(intent: Intent?, resultReceiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun removeStickyBroadcast(intent: Intent?) {}
        override fun sendStickyBroadcastAsUser(intent: Intent?, user: UserHandle?) {}
        override fun sendStickyOrderedBroadcastAsUser(intent: Intent?, user: UserHandle?, resultReceiver: BroadcastReceiver?, scheduler: Handler?, initialCode: Int, initialData: String?, initialExtras: Bundle?) {}
        override fun removeStickyBroadcastAsUser(intent: Intent?, user: UserHandle?) {}
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? = null
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, flags: Int): Intent? = null
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?): Intent? = null
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?, broadcastPermission: String?, scheduler: Handler?, flags: Int): Intent? = null
        override fun unregisterReceiver(receiver: BroadcastReceiver?) {}
        override fun startService(service: Intent?): ComponentName? = null
        override fun startForegroundService(service: Intent?): ComponentName? = null
        override fun stopService(service: Intent?): Boolean = false
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        override fun unbindService(conn: ServiceConnection) {}
        override fun startInstrumentation(className: ComponentName, profileFile: String?, arguments: Bundle?): Boolean = false
        override fun getSystemService(name: String): Any? = null
        override fun getSystemServiceName(serviceClass: Class<*>): String? = null
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = 0
        override fun checkCallingPermission(permission: String): Int = 0
        override fun checkCallingOrSelfPermission(permission: String): Int = 0
        override fun checkSelfPermission(permission: String): Int = 0
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun grantUriPermission(toPackage: String?, uri: Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(uri: Uri?, modeFlags: Int) {}
        override fun revokeUriPermission(toPackage: String?, uri: Uri?, modeFlags: Int) {}
        override fun checkUriPermission(uri: Uri?, pid: Int, uid: Int, modeFlags: Int): Int = 0
        override fun checkCallingUriPermission(uri: Uri?, modeFlags: Int): Int = 0
        override fun checkCallingOrSelfUriPermission(uri: Uri?, modeFlags: Int): Int = 0
        override fun checkUriPermission(uri: Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int): Int = 0
        override fun enforceUriPermission(uri: Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun enforceCallingUriPermission(uri: Uri?, modeFlags: Int, message: String?) {}
        override fun enforceCallingOrSelfUriPermission(uri: Uri?, modeFlags: Int, message: String?) {}
        override fun enforceUriPermission(uri: Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun createPackageContext(packageName: String?, flags: Int): Context = this
        override fun createContextForSplit(splitName: String?): Context = this
        override fun createConfigurationContext(overrideConfiguration: Configuration): Context = this
        override fun createDisplayContext(display: Display): Context = this
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun isDeviceProtectedStorage(): Boolean = false
    }

    @Before
    fun setup() {
        sandboxDir = File("/tmp/ve_context_test_${System.currentTimeMillis()}").apply { mkdirs() }
        guestDataDir = File(sandboxDir, "com.target.sampleapp/data").apply { mkdirs() }

        val installedPackage = InstalledPackage(
            packageName = "com.target.sampleapp",
            archiveType = ArchiveType.APK,
            baseApkPath = File(sandboxDir, "base.apk").apply { createNewFile() }.absolutePath,
            splitApkPaths = emptyList(),
            nativeLibDir = File(sandboxDir, "lib").apply { mkdirs() }.absolutePath,
            dataDir = guestDataDir.absolutePath,
            manifest = ParsedManifest(
                packageName = "com.target.sampleapp",
                versionCode = 101,
                versionName = "1.0.1-target",
                applicationClassName = "com.target.sampleapp.TargetApp",
                minSdkVersion = 26,
                targetSdkVersion = 34
            )
        )

        val baseContext = TestBaseContext()

        val dummyClassLoader = java.lang.ClassLoader.getSystemClassLoader()
        val dummyAssetManager = android.content.res.AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val dummyResources = android.content.res.Resources(
            dummyAssetManager,
            android.util.DisplayMetrics(),
            android.content.res.Configuration()
        )

        loadedPackage = LoadedPackage(
            installedPackage = installedPackage,
            classLoader = dummyClassLoader,
            assetManager = dummyAssetManager,
            resources = dummyResources
        )

        proxyContext = ProxyContext(
            base = baseContext,
            loadedPackage = loadedPackage,
            guestAppSupplier = { null }
        )
    }

    @Test
    fun testIdentityRedirection() {
        // Intercepts getPackageName: returns guest identity, NEVER host identity!
        assertEquals("com.target.sampleapp", proxyContext.packageName)
        assertEquals("com.target.sampleapp", proxyContext.getBasePackageName())
        assertEquals("com.target.sampleapp", proxyContext.opPackageName)

        // Code and resource paths
        assertEquals(loadedPackage.installedPackage.baseApkPath, proxyContext.packageCodePath)
        assertEquals(loadedPackage.installedPackage.baseApkPath, proxyContext.packageResourcePath)

        // Synthesized ApplicationInfo
        val appInfo = proxyContext.applicationInfo
        assertNotNull(appInfo)
        assertEquals("com.target.sampleapp", appInfo.packageName)
        assertEquals("com.target.sampleapp.TargetApp", appInfo.className)
        assertEquals(loadedPackage.installedPackage.baseApkPath, appInfo.sourceDir)
        assertEquals(guestDataDir.absolutePath, appInfo.dataDir)
        assertEquals(34, appInfo.targetSdkVersion)
        assertEquals(26, appInfo.minSdkVersion)
    }

    @Test
    fun testStorageIsolation() {
        // All paths must be within guestDataDir
        assertEquals(guestDataDir.absolutePath, proxyContext.dataDir.absolutePath)
        assertEquals(File(guestDataDir, "files").absolutePath, proxyContext.filesDir.absolutePath)
        assertEquals(File(guestDataDir, "cache").absolutePath, proxyContext.cacheDir.absolutePath)
        assertEquals(File(guestDataDir, "code_cache").absolutePath, proxyContext.codeCacheDir.absolutePath)
        assertEquals(File(guestDataDir, "no_backup").absolutePath, proxyContext.noBackupFilesDir.absolutePath)

        // Database path redirection
        val dbFile = proxyContext.getDatabasePath("sample.db")
        assertEquals(File(guestDataDir, "databases/sample.db").absolutePath, dbFile.absolutePath)

        // Directory creation
        val customDir = proxyContext.getDir("custom_folder", 0)
        assertEquals(File(guestDataDir, "app_custom_folder").absolutePath, customDir.absolutePath)
        assertTrue(customDir.exists())
    }

    @Test
    fun testFileStreamIoRedirection() {
        val fileName = "guest_state.json"
        val payload = """{"logged_in": true, "session": "SESSION_GUEST_12345"}"""

        // Write via openFileOutput
        proxyContext.openFileOutput(fileName, 0).use { fos ->
            fos.write(payload.toByteArray(Charsets.UTF_8))
        }

        // Verify physical file was written under guest filesDir, not host storage
        val physicalFile = File(proxyContext.filesDir, fileName)
        assertTrue("File must exist in isolated filesDir", physicalFile.exists())
        assertEquals(payload, physicalFile.readText(Charsets.UTF_8))

        // Read back via openFileInput
        val readContent = proxyContext.openFileInput(fileName).use { fis ->
            fis.bufferedReader().readText()
        }
        assertEquals(payload, readContent)

        // List files
        val list = proxyContext.fileList()
        assertTrue("fileList() should contain written file", list.contains(fileName))

        // Delete file
        val deleted = proxyContext.deleteFile(fileName)
        assertTrue("deleteFile() should return true", deleted)
        assertFalse("File must no longer exist", physicalFile.exists())
    }

    @Test
    fun testSharedPreferencesRedirection() {
        val prefsName = "guest_user_settings"
        val prefs = proxyContext.getSharedPreferences(prefsName, 0)
        assertNotNull(prefs)

        prefs.edit()
            .putString("auth_token", "AUTH_TOKEN_ABC_998")
            .putBoolean("dark_mode", true)
            .putInt("volume", 85)
            .commit()

        // Verify physical XML file was created inside isolated shared_prefs/
        val physicalPrefFile = File(guestDataDir, "shared_prefs/$prefsName.xml")
        assertTrue("Shared prefs XML must exist in isolated directory: ${physicalPrefFile.absolutePath}", physicalPrefFile.exists())
        val xmlText = physicalPrefFile.readText()
        assertTrue(xmlText.contains("AUTH_TOKEN_ABC_998"))

        // Read back from context
        val samePrefs = proxyContext.getSharedPreferences(prefsName, 0)
        assertEquals("AUTH_TOKEN_ABC_998", samePrefs.getString("auth_token", null))
        assertTrue(samePrefs.getBoolean("dark_mode", false))
        assertEquals(85, samePrefs.getInt("volume", 0))

        // Delete SharedPreferences
        val deleted = proxyContext.deleteSharedPreferences(prefsName)
        assertTrue("deleteSharedPreferences should succeed", deleted)
        assertFalse("Shared prefs file must be deleted", physicalPrefFile.exists())
    }

    @Test
    fun testResourcesAndClassLoaderBridge() {
        assertEquals(loadedPackage.classLoader, proxyContext.classLoader)
        assertEquals(loadedPackage.resources, proxyContext.resources)
        assertEquals(loadedPackage.assetManager, proxyContext.assets)
        assertEquals(proxyContext, proxyContext.applicationContext)
    }
}
