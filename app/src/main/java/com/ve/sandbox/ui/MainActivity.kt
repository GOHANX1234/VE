package com.ve.sandbox.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ve.sandbox.core.VeEngine
import com.ve.sandbox.core.hook.ActivityManagerHook
import com.ve.sandbox.core.hook.PackageManagerHook
import com.ve.sandbox.core.model.ArchiveType
import com.ve.sandbox.core.model.InstalledPackage
import com.ve.sandbox.core.model.LoadedPackage
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VeLauncherScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VeLauncherScreen() {
    val context = LocalContext.current
    val veEngine = remember { VeEngine.get() }

    var installedPackages by remember { mutableStateOf(veEngine.getInstalledPackages()) }
    var selectedPackageName by remember { mutableStateOf<String?>(installedPackages.firstOrNull()?.packageName) }
    var executionLog by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }

    fun refreshPackages() {
        installedPackages = veEngine.getInstalledPackages()
        if (selectedPackageName == null || installedPackages.none { it.packageName == selectedPackageName }) {
            selectedPackageName = installedPackages.firstOrNull()?.packageName
        }
    }

    // System File Picker for any APK / APKS / XAPK / APKM on the device
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isBusy = true
            executionLog = "Importing package from storage: $uri..."
            try {
                // Query real display name and extension from ContentResolver
                var originalName: String? = null
                try {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameIdx != -1) {
                                originalName = cursor.getString(nameIdx)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // Fallback
                }

                if (originalName.isNullOrBlank()) {
                    originalName = uri.lastPathSegment?.substringAfterLast('/')
                }

                val safeFileName = if (!originalName.isNullOrBlank()) {
                    originalName!!.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                } else {
                    "picked_${System.currentTimeMillis()}.apks"
                }

                val cacheFile = File(context.cacheDir, safeFileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val loaded = veEngine.installAndLoad(cacheFile)
                refreshPackages()
                selectedPackageName = loaded.packageName
                executionLog = "Successfully installed & loaded '${loaded.packageName}' (${loaded.archiveType}) into VE Sandbox!"
            } catch (e: Throwable) {
                executionLog = "Failed to import package: ${e.message}"
            } finally {
                isBusy = false
            }
        }
    }

    // Bundled Sample Archive Loader
    fun loadSampleArchive(fileName: String) {
        isBusy = true
        executionLog = "Loading bundled archive '$fileName' into sandbox..."
        try {
            val cacheFile = File(context.cacheDir, fileName)
            context.assets.open("test_packages/$fileName").use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            val loaded = veEngine.installAndLoad(cacheFile)
            refreshPackages()
            selectedPackageName = loaded.packageName
            executionLog = "Package '${loaded.packageName}' (${loaded.archiveType}) ready in VE Sandbox!"
        } catch (e: Throwable) {
            executionLog = "Error installing $fileName: ${e.message}"
        } finally {
            isBusy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("VE Sandbox Launcher", fontWeight = FontWeight.Bold)
                        Text(
                            "Android App Virtualization Container",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showStatusDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Engine Status")
                    }
                    IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Install from Storage")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Install from Storage Button
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Install Guest Application", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "Pick any APK, APKS, XAPK, or APKM from storage to run isolated inside the sandbox.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            enabled = !isBusy
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Pick File")
                        }
                    }
                }
            }

            // Bundled Test Packages
            item {
                Column {
                    Text("Bundled Test Archives:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val samples = listOf(
                            "sample_app.apk" to "APK",
                            "sample_app.apks" to "APKS",
                            "sample_app.xapk" to "XAPK",
                            "sample_app.apkm" to "APKM"
                        )
                        samples.forEach { (file, label) ->
                            FilterChip(
                                selected = false,
                                onClick = { loadSampleArchive(file) },
                                label = { Text("+$label") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }

            // Installed Virtual Apps Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Installed Virtual Apps (${installedPackages.size}):",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    TextButton(onClick = { refreshPackages() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh", fontSize = 12.sp)
                    }
                }
            }

            // Empty state
            if (installedPackages.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Apps,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("No Guest Apps Installed", fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Tap '+APK' above or pick a file from storage to launch inside VE.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // List of installed guest apps
                items(installedPackages) { pkg ->
                    VirtualAppCard(
                        pkg = pkg,
                        onLaunchReal = {
                            try {
                                val launcher = pkg.manifest.launcherActivity?.name
                                if (launcher != null) {
                                    veEngine.launchGuestActivity(context, pkg.packageName, launcher)
                                    executionLog = "Launching '${pkg.packageName}' natively via Stub Activity swap!"
                                } else {
                                    executionLog = "No launcher Activity found for '${pkg.packageName}'"
                                }
                            } catch (e: Throwable) {
                                executionLog = "Launch error: ${e.message}"
                            }
                        },
                        onLaunchEmbedded = {
                            try {
                                val intent = ContainerActivity.createIntent(context, pkg.packageName)
                                context.startActivity(intent)
                                executionLog = "Launching '${pkg.packageName}' inside ContainerActivity window!"
                            } catch (e: Throwable) {
                                executionLog = "Container launch error: ${e.message}"
                            }
                        },
                        onClearData = {
                            val cleared = veEngine.clearData(pkg.packageName)
                            executionLog = if (cleared) "Sandbox data cleared for '${pkg.packageName}'" else "Failed to clear data"
                            refreshPackages()
                        },
                        onUninstall = {
                            val removed = veEngine.uninstall(pkg.packageName)
                            executionLog = if (removed) "Uninstalled '${pkg.packageName}' from sandbox" else "Uninstall failed"
                            refreshPackages()
                        }
                    )
                }
            }

            // Real-time Engine Console Output
            if (executionLog != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Execution Console", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                TextButton(onClick = { executionLog = null }) {
                                    Text("Clear", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = executionLog ?: "",
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Engine Status Dialog
    if (showStatusDialog) {
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("Sandbox Engine Architecture", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Phase 1: Multi-Split DexClassLoader & AssetManager reflection bridge", fontSize = 13.sp)
                    Text("• Phase 2: ProxyContext filesystem & identity quarantine", fontSize = 13.sp)
                    Text("• Phase 3: Manifest Stub Activity masquerade & VeInstrumentation swap", fontSize = 13.sp)
                    Text("• Phase 4: Dynamic Proxies on IPackageManager & IActivityManager Binder IPC", fontSize = 13.sp)
                    Text("• Phase 5: Dual Rendering: Real Window Swap & Embedded Container Window", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun VirtualAppCard(
    pkg: InstalledPackage,
    onLaunchReal: () -> Unit,
    onLaunchEmbedded: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit
) {
    val manifest = pkg.manifest

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Monogram
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = manifest.packageName.substringAfterLast('.').take(2).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = manifest.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                            Text(pkg.archiveType.name, fontSize = 10.sp)
                        }
                    }
                    Text(
                        text = manifest.packageName,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metadata Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Version: ${manifest.versionName} (${manifest.versionCode})", fontSize = 12.sp)
                Text("Activities: ${manifest.activities.size}", fontSize = 12.sp)
                Text("Permissions: ${manifest.permissions.size}", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row 1: Launchers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onLaunchReal
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Launch (Stub)", fontSize = 12.sp)
                }

                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = onLaunchEmbedded
                ) {
                    Icon(Icons.Default.Window, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Container View", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action Buttons Row 2: Management
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = onClearData
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Data", fontSize = 11.sp)
                }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = onUninstall
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Uninstall", fontSize = 11.sp)
                }
            }
        }
    }
}
