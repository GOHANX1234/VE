package com.ve.sandbox.core.model

import android.content.res.AssetManager
import android.content.res.Resources

enum class ArchiveType {
    APK,
    APKS,
    APKM,
    XAPK,
    UNKNOWN
}

data class ParsedIntentFilter(
    val actions: MutableList<String> = mutableListOf(),
    val categories: MutableList<String> = mutableListOf(),
    val dataSchemes: MutableList<String> = mutableListOf()
)

data class ParsedComponent(
    val name: String,
    val exported: Boolean = false,
    val processName: String? = null,
    val authorities: String? = null,
    val theme: String? = null,
    val screenOrientation: Int? = null,
    val launchMode: Int = 0,
    val configChanges: Int = 0,
    val isLauncher: Boolean = false,
    val intentFilters: List<ParsedIntentFilter> = emptyList()
)

data class ParsedManifest(
    val packageName: String,
    val versionCode: Long = 0,
    val versionName: String = "",
    val minSdkVersion: Int = 1,
    val targetSdkVersion: Int = 1,
    val applicationClassName: String? = null,
    val applicationLabel: String? = null,
    val applicationTheme: String? = null,
    val permissions: List<String> = emptyList(),
    val activities: List<ParsedComponent> = emptyList(),
    val services: List<ParsedComponent> = emptyList(),
    val receivers: List<ParsedComponent> = emptyList(),
    val providers: List<ParsedComponent> = emptyList()
) {
    val launcherActivity: ParsedComponent?
        get() = activities.firstOrNull { it.isLauncher } ?: activities.firstOrNull()
}

data class InstalledPackage(
    val packageName: String,
    val archiveType: ArchiveType,
    val baseApkPath: String,
    val splitApkPaths: List<String> = emptyList(),
    val nativeLibDir: String,
    val dataDir: String,
    val manifest: ParsedManifest
) {
    val allApkPaths: List<String>
        get() = listOf(baseApkPath) + splitApkPaths
}

data class LoadedPackage(
    val installedPackage: InstalledPackage,
    val classLoader: ClassLoader,
    val assetManager: AssetManager,
    val resources: Resources
) {
    val packageName: String get() = installedPackage.packageName
    val manifest: ParsedManifest get() = installedPackage.manifest
    val archiveType: ArchiveType get() = installedPackage.archiveType
    val splitApkPaths: List<String> get() = installedPackage.splitApkPaths
}
