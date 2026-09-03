# VE — Virtual Engine

**VE (Virtual Engine)** is a high-performance Android App-Virtualization and Sandboxing Container built from scratch in Kotlin. It enables dynamically loading and executing external Android applications (`.apk`, `.apks`, `.xapk`, `.apkm`) inside its own process runtime **without installing them into the host Android operating system**.

Similar in concept to open-source systems like *VirtualApp* and *DroidPlugin*, VE is designed to demonstrate low-level Android OS mechanics: ClassLoader delegation, ART reflection, ContextWrapper redirection, Binder IPC interception, and Activity lifecycle virtualization on modern Android versions (Android 9.0 through Android 14+).

---

## Direct Download

- **VE Sandbox Container Debug APK**: [`apk/app-debug.apk`](apk/app-debug.apk)
- **Sample Guest Target APK**: [`apk/test-target-app-debug.apk`](apk/test-target-app-debug.apk)

---

## Architectural Breakdown (Phases 1 — 5)

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                VE Container Application                                │
│                                                                                        │
│   Phase 1: Dynamic Loading Layer                                                       │
│   ├── AndroidBinaryXmlParser: Pure Kotlin LE AXML parser for uninstalled manifests     │
│   ├── PackageArchiveExtractor: Multi-format extractor (.apk, .apks, .xapk, .apkm)      │
│   ├── VirtualClassLoader: Multi-split Dalvik DexClassLoader + ABI native .so locator   │
│   ├── VirtualResourceManager: Reflection bridge on AssetManager.addAssetPath           │
│   └── HiddenApiManager: Unseals hidden API restrictions on Android 9–14+               │
│                                                                                        │
│   Phase 2: Fake Context & Storage Quarantine Layer                                     │
│   ├── ProxyContext: Intercepts getPackageName(), isolates storage paths to sandbox dir │
│   ├── VirtualSharedPreferences: Thread-safe, XML-persisting key-value storage          │
│   └── GuestApplicationManager: Manages guest Application lifecycle (attach + onCreate) │
│                                                                                        │
│   Phase 3: Activity Virtualization Layer                                               │
│   ├── Manifest Stub Pool: StubActivity (standard, singleTop, singleTask, singleInstance│
│   ├── StubManager: Masquerades guest Intent outbound / demasquerades inbound           │
│   ├── VeInstrumentation: Swaps stub class for real target Activity in newActivity()    │
│   └── ActivityThreadHook: Hooks mInstrumentation and mH ClientTransaction callbacks   │
│                                                                                        │
│   Phase 4: System Service Hooking Layer (Binder IPC)                                   │
│   ├── IPackageManager Dynamic Proxy: ActivityThread.sPackageManager hook               │
│   ├── IActivityManager Dynamic Proxy: ActivityManager.IActivityManagerSingleton hook   │
│   └── PackageInfoSynthesizer: Synthesizes PackageInfo, ApplicationInfo, permissions   │
│                                                                                        │
│   Phase 5: Rendering & Launcher UX                                                     │
│   ├── ContainerActivity: Embedded view hierarchy hosting & supervisor window           │
│   └── MainActivity: Full Material 3 launcher, system file picker, and app dashboard   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### Phase 1: Dynamic APK & Multi-Split Loading
- **Universal Package Support**: Ingests `.apk` (single APK), `.apks` (split bundle), `.xapk` (APKs + OBBs + native libraries), and `.apkm` (APKM archives).
- **Pure Kotlin AXML Parser**: Custom Little-Endian binary chunk parser (`AndroidBinaryXmlParser`) that reads binary `AndroidManifest.xml` without standard Android package manager dependencies.
- **`VirtualClassLoader`**: Multi-dex `DexClassLoader` hierarchy binding split dex paths (`File.pathSeparator`) and device-matched ABI native libraries (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- **Resource Synthesis**: Reflectively invokes `@hide AssetManager.addAssetPath()` across all split APKs to build a unified `Resources` table.
- **Hidden API Bypass**: Double-reflection and `HiddenApiBypass` unsealer ensuring runtime stability on Android 9–14+.

### Phase 2: Fake Context & Storage Isolation Layer
- **`ProxyContext`**: Wraps `ContextWrapper` to isolate guest applications from host system state:
  - Identity redirection: `getPackageName()`, `getApplicationInfo()`, `getPackageCodePath()`.
  - Storage redirection: `getFilesDir()`, `getCacheDir()`, `getDataDir()`, `getDatabasePath()`, `openFileOutput()`, `openFileInput()` redirect into `<host_files>/ve_sandbox/<guest_pkg>/data/`.
- **`VirtualSharedPreferences`**: High-performance, thread-safe XML-persisted `SharedPreferences` implementation with atomic `.bak` fail-safes.
- **Guest `Application` Lifecycle**: Instantiates the guest `<application android:name="...">` class, injects `ProxyContext` via `attachBaseContext()`, and calls `onCreate()`.

### Phase 3: Stub Activity & Reflection Swap
- **Pre-Declared Stub Pool**: Declares standard, `singleTop`, `singleTask`, and `singleInstance` stub activities in the host `AndroidManifest.xml`.
- **Outbound Masquerade (`StubManager`)**: Transforms raw guest Activity intents into matching host `StubActivity` intents before Binder transmission so system PMS manifest validation passes.
- **Inbound Class Swap (`VeInstrumentation`)**: Intercepts `Instrumentation.newActivity()` inside `ActivityThread` to substitute the real target Activity class before instantiation.
- **Context Injection**: Intercepts `callActivityOnCreate()` to bind `ProxyContext`, guest `Resources`, and guest `Application` before `onCreate()` runs.
- **`ActivityThread.mH` Hook**: Demasquerades Android 9+ `ClientTransaction` (`EXECUTE_TRANSACTION`) and legacy `LAUNCH_ACTIVITY` messages.

### Phase 4: System Service Hooking (Binder IPC)
- **`IPackageManager` Dynamic Proxy**: Hooks `ActivityThread.sPackageManager` using `Proxy.newProxyInstance`. Guest package queries (`getPackageInfo`, `getApplicationInfo`, `getActivityInfo`, `checkPermission`, `resolveIntent`) are answered from in-memory virtual manifests.
- **`IActivityManager` & `IActivityTaskManager` Dynamic Proxies**: Hooks `ActivityManager.IActivityManagerSingleton` and `ActivityTaskManager.IActivityTaskManagerSingleton` to intercept outbound `startActivity()` IPC calls at the framework layer.

### Phase 5: Rendering & Launcher UX
- **Embedded UI Container (`ContainerActivity`)**: Hosts guest view hierarchies inside an isolated container window with a supervisor header.
- **Material 3 Launcher (`MainActivity`)**:
  - **System File Picker**: Select any APK, APKS, XAPK, or APKM from device storage to run inside VE.
  - **Bundled Demo Carousel**: One-tap installation of test packages.
  - **App Dashboard**: App icons, version tags, component counters, launch modes, storage wipe, and uninstallation.
  - **Live Execution Console**: Real-time monospace activity logger.

---

## Building and Testing

### Prerequisites
- JDK 17 or JDK 21
- Android SDK (API 34, Build Tools 34.0.0)

### Build Debug APKs
```bash
./gradlew :app:assembleDebug :test-target-app:assembleDebug
```

Output binaries:
- `app/build/outputs/apk/debug/app-debug.apk`
- `test-target-app/build/outputs/apk/debug/test-target-app-debug.apk`

### Run Test Suite (30 Automated Tests)
```bash
./gradlew :app:testDebugUnitTest
```
Tests cover:
- Binary XML manifest decoding
- Archive extraction (`.apk`, `.apks`, `.xapk`, `.apkm`)
- `VirtualSharedPreferences` persistence & atomic recovery
- `ProxyContext` storage and identity redirection
- `GuestApplicationManager` lifecycle
- `StubManager` Intent masquerading & demasquerading
- `VeInstrumentation` Activity swapping & context injection
- `IPackageManager` dynamic proxy metadata synthesis
- `IActivityManager` dynamic proxy IPC interception
- `ContainerActivity` embedded UI hosting & sandbox cleanup

---

## License
Educational & Research Sandbox. Distributed for learning Android operating system internals.
