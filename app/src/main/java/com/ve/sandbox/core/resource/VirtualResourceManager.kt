package com.ve.sandbox.core.resource

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.util.Log
import com.ve.sandbox.core.compat.HiddenApiManager
import java.io.File
import java.lang.reflect.Method

/**
 * Creates and manages guest app Resources and AssetManager via Android framework reflection.
 *
 * Android Internals Mental Model:
 * 1. Android UI elements (TextView, ImageView, layouts) query Resources to resolve IDs (0x7f...).
 * 2. Resources does not store files itself; it delegates all binary lookup to AssetManager.
 * 3. AssetManager wraps a native C++ ResTable / AssetManager2 instance that holds mApkAssets.
 * 4. By reflecting on the hidden AssetManager.addAssetPath(String path) method, we inject the
 *    target APK (and any split APKs) into AssetManager's asset cookie table.
 * 5. Wrapping this AssetManager in a new Resources(assetManager, metrics, config) creates a complete,
 *    fully functional resource bridge for the uninstalled guest APK.
 */
object VirtualResourceManager {
    private const val TAG = "VeResourceManager"
    private var addAssetPathMethod: Method? = null

    init {
        HiddenApiManager.unseal()
        try {
            addAssetPathMethod = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Direct reflection for addAssetPath failed, will retry dynamically", t)
        }
    }

    /**
     * Creates an AssetManager with the target APK and all split APK paths added.
     */
    fun createAssetManager(apkPaths: List<String>): AssetManager {
        HiddenApiManager.unseal()
        val assetManager = AssetManager::class.java.getConstructor().newInstance()
        val method = addAssetPathMethod ?: try {
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java).apply {
                isAccessible = true
                addAssetPathMethod = this
            }
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "addAssetPath not found in class definition (stub environment)")
            null
        }

        if (method != null) {
            for (path in apkPaths) {
                val file = File(path)
                if (!file.exists()) {
                    Log.w(TAG, "APK asset path does not exist: $path")
                    continue
                }
                val cookie = method.invoke(assetManager, path) as? Int ?: 0
                if (cookie == 0) {
                    Log.e(TAG, "Failed to add asset path: $path (cookie was 0)")
                } else {
                    Log.d(TAG, "Successfully added asset path: $path with cookie: $cookie")
                }
            }
        }

        return assetManager
    }

    /**
     * Creates a Resources instance bound to the guest APK's AssetManager,
     * inheriting the host's DisplayMetrics and Configuration.
     */
    fun createResources(
        hostContext: Context,
        apkPaths: List<String>
    ): Resources {
        val assetManager = createAssetManager(apkPaths)
        return Resources(
            assetManager,
            hostContext.resources.displayMetrics,
            hostContext.resources.configuration
        )
    }

    /**
     * Creates a Resources instance with explicit DisplayMetrics and Configuration.
     */
    fun createResources(
        apkPaths: List<String>,
        metrics: DisplayMetrics,
        config: Configuration
    ): Resources {
        val assetManager = createAssetManager(apkPaths)
        return Resources(assetManager, metrics, config)
    }
}
