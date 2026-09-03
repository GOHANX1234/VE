package com.ve.sandbox.core.hook

import android.content.ComponentName
import android.content.Intent
import android.content.pm.*
import android.os.Build
import android.os.Process
import com.ve.sandbox.core.model.LoadedPackage
import com.ve.sandbox.core.model.ParsedComponent
import java.io.File

/**
 * Synthesizes official Android framework PackageManager metadata objects
 * (PackageInfo, ApplicationInfo, ActivityInfo, ResolveInfo) from our virtual LoadedPackage.
 */
object PackageInfoSynthesizer {

    fun getPrimaryAbiDir(baseNativeLibDir: String): String {
        val dir = File(baseNativeLibDir)
        if (!dir.exists()) return baseNativeLibDir
        val supportedAbis = Build.SUPPORTED_ABIS ?: emptyArray()
        for (abi in supportedAbis) {
            val abiDir = File(dir, abi)
            if (abiDir.exists() && abiDir.isDirectory) {
                return abiDir.absolutePath
            }
        }
        val firstSub = dir.listFiles()?.firstOrNull { it.isDirectory }
        if (firstSub != null) return firstSub.absolutePath
        return baseNativeLibDir
    }

    fun extractSplitNames(splitApkPaths: List<String>): Array<String>? {
        if (splitApkPaths.isEmpty()) return null
        return splitApkPaths.map { path ->
            val fileName = File(path).name
            if (fileName.endsWith(".apk", ignoreCase = true)) {
                fileName.substring(0, fileName.length - 4)
            } else {
                fileName
            }
        }.toTypedArray()
    }

    fun buildApplicationInfo(loaded: LoadedPackage, flags: Int = 0): ApplicationInfo {
        val manifest = loaded.manifest
        val primaryAbiDir = getPrimaryAbiDir(loaded.installedPackage.nativeLibDir)
        val splitNamesArray = extractSplitNames(loaded.installedPackage.splitApkPaths)
        return ApplicationInfo().apply {
            packageName = loaded.packageName
            name = manifest.applicationClassName
            className = manifest.applicationClassName
            sourceDir = loaded.installedPackage.baseApkPath
            publicSourceDir = loaded.installedPackage.baseApkPath
            if (loaded.installedPackage.splitApkPaths.isNotEmpty()) {
                splitSourceDirs = loaded.installedPackage.splitApkPaths.toTypedArray()
                splitPublicSourceDirs = loaded.installedPackage.splitApkPaths.toTypedArray()
                splitNames = splitNamesArray
            }
            dataDir = loaded.installedPackage.dataDir
            nativeLibraryDir = primaryAbiDir
            targetSdkVersion = manifest.targetSdkVersion
            minSdkVersion = manifest.minSdkVersion
            uid = Process.myUid()
            this.flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_BACKUP
        }
    }

    fun buildPackageInfo(loaded: LoadedPackage, flags: Int = 0): PackageInfo {
        val manifest = loaded.manifest
        val appInfo = buildApplicationInfo(loaded, flags)

        return PackageInfo().apply {
            packageName = loaded.packageName
            versionName = manifest.versionName
            splitNames = appInfo.splitNames
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode = manifest.versionCode
            }
            @Suppress("DEPRECATION")
            versionCode = manifest.versionCode.toInt()
            applicationInfo = appInfo

            if ((flags and PackageManager.GET_PERMISSIONS) != 0) {
                requestedPermissions = manifest.permissions.toTypedArray()
                requestedPermissionsFlags = IntArray(manifest.permissions.size) {
                    PackageInfo.REQUESTED_PERMISSION_GRANTED
                }
            }

            if ((flags and PackageManager.GET_ACTIVITIES) != 0) {
                activities = manifest.activities.map { buildActivityInfo(loaded, it, appInfo) }.toTypedArray()
            }

            if ((flags and PackageManager.GET_SERVICES) != 0) {
                services = manifest.services.map { buildServiceInfo(loaded, it, appInfo) }.toTypedArray()
            }

            if ((flags and PackageManager.GET_RECEIVERS) != 0) {
                receivers = manifest.receivers.map { buildActivityInfo(loaded, it, appInfo) }.toTypedArray()
            }

            if ((flags and PackageManager.GET_PROVIDERS) != 0) {
                providers = manifest.providers.map { buildProviderInfo(loaded, it, appInfo) }.toTypedArray()
            }
        }
    }

    fun buildActivityInfo(
        loaded: LoadedPackage,
        comp: ParsedComponent,
        appInfo: ApplicationInfo = buildApplicationInfo(loaded)
    ): ActivityInfo {
        return ActivityInfo().apply {
            name = comp.name
            packageName = loaded.packageName
            applicationInfo = appInfo
            exported = comp.exported
            processName = comp.processName ?: loaded.packageName
            launchMode = comp.launchMode
            configChanges = comp.configChanges
            flags = ActivityInfo.FLAG_HARDWARE_ACCELERATED
            screenOrientation = comp.screenOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun buildServiceInfo(
        loaded: LoadedPackage,
        comp: ParsedComponent,
        appInfo: ApplicationInfo = buildApplicationInfo(loaded)
    ): ServiceInfo {
        return ServiceInfo().apply {
            name = comp.name
            packageName = loaded.packageName
            applicationInfo = appInfo
            exported = comp.exported
            processName = comp.processName ?: loaded.packageName
        }
    }

    fun buildProviderInfo(
        loaded: LoadedPackage,
        comp: ParsedComponent,
        appInfo: ApplicationInfo = buildApplicationInfo(loaded)
    ): ProviderInfo {
        return ProviderInfo().apply {
            name = comp.name
            packageName = loaded.packageName
            applicationInfo = appInfo
            exported = comp.exported
            authority = comp.authorities
            processName = comp.processName ?: loaded.packageName
        }
    }

    fun buildResolveInfo(loaded: LoadedPackage, comp: ParsedComponent): ResolveInfo {
        return ResolveInfo().apply {
            activityInfo = buildActivityInfo(loaded, comp)
            resolvePackageName = loaded.packageName
            isDefault = comp.isLauncher
        }
    }
}
