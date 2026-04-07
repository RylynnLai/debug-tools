# Debug Tools MVP

Debug Tools is a lightweight in-app debugging toolkit for Android, with a Swing desktop control panel.
It complements Android Studio tooling instead of replacing it.

## What this project provides

- In-app debug server (TCP, LAN)
- View tree export for current foreground Activity
- Leak watch list for retained objects
- In-app network mock rule registry
- Desktop panel for inspection and control

## Repository layout

- `android/`: Android project (sample app + `debugkit` library)
- `desktop/`: Java Swing desktop client
- `protocol/`: JSON-line protocol notes

## Integrate into your target Android app

This section is the minimal path to add Debug Tools into a real app.

### 1) Add `debugkit` dependency

If your app and this repo are in the same Gradle project, include module `:debugkit` and add:

```kotlin
dependencies {
    implementation(project(":debugkit"))
}
```

### 2) Install DebugKit in `Application`

```kotlin
class YourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugKit.install(this) // default port: 4939
    }
}
```

Optional custom port:

```kotlin
DebugKit.install(this, port = 5940)
```

### 3) Register your `Application` in manifest

Set your app class in `AndroidManifest.xml`:

```xml
<application
    android:name=".YourApp"
    ... />
```

### 4) Enable non-intrusive network interception (VPN proxy)

Preferred path (no business `OkHttpClient` changes):

```kotlin
val prepareIntent = DebugVpnController.prepareIntent(this)
if (prepareIntent == null) {
    DebugVpnController.start(this, packageName)
}
```

Stop when done:

```kotlin
DebugVpnController.stop(this)
```

`DebugMockInterceptor` is still available as an optional fallback for apps/environments where VPN mode is not enabled.

### 5) Watch suspicious objects for leaks

```kotlin
DebugKit.watch("checkout-viewmodel", viewModel)
DebugKit.watch("activity:${activity::class.java.simpleName}", activity)
```

### 6) Read runtime connect info (for desktop)

```kotlin
val state = DebugKit.describeState()
val connect = "${state.host}:${state.port}"
```

## Integrate into an external app (outside this repository)

If your business app is in a different repository, use one of these approaches.

### Option A: Source-based integration

#### A1) Composite build (`includeBuild`) [recommended for active development]

In your business app `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

includeBuild("/absolute/path/to/debug-tools/android")
```

Then in your app module:

```kotlin
dependencies {
    implementation(project(":debugkit"))
}
```

#### A2) Copy/import module into your repo

If your build policy does not allow `includeBuild`, import `debugkit` as a local module and depend on:

```kotlin
dependencies {
    implementation(project(":debugkit"))
}
```

### Option B: Maven artifact integration

Use this when you want versioned consumption in multiple apps.

1) Publish `debugkit` to your internal Maven repository (or `mavenLocal()` for quick local testing).
2) Add repository in your business app:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal() // or your internal Maven URL
    }
}
```

3) Add dependency (replace with your actual coordinates):

```kotlin
dependencies {
    implementation("com.yourorg.debug:debugkit:<version>")
}
```

4) Continue with the same runtime setup in this README (`DebugKit.install`, `DebugVpnController`, watch, desktop connect).

### External integration validation

1. App builds successfully after adding `debugkit` dependency.
2. `DebugKit.install(...)` is called in `Application`.
3. App can show `host:port` from `DebugKit.describeState()`.
4. Desktop can connect and fetch View Tree / Leak list.

### Common pitfalls (external app)

- `Project with path ':debugkit' not found`: check `settings.gradle.kts` include order and path.
- Repository resolution errors with `FAIL_ON_PROJECT_REPOS`: add repositories in `dependencyResolutionManagement`, not module build files.
- Mock not working in VPN mode: HTTP cleartext can be mocked by path; HTTPS payload mocking requires MITM support (not in current MVP).
- Connected but no data: verify app has foreground Activity and both devices are on the same LAN.

## Run and connect

### Start Android app

Run your app on a device in the same LAN as desktop.

### Start desktop client

From repo root:

```bash
cd /Users/llm/Documents/debug-tools
./gradlew :desktop:run
```

Or run `com.debugtools.desktop.DesktopMain` from IDE.

### Connect desktop to app

1. Copy `host:port` from `DebugKit.describeState()` in app.
2. Enter host and port in desktop panel.
3. Click `Connect`.

## Validate integration (quick checklist)

1. `Connect` succeeds in desktop.
2. `Fetch View Tree` returns current Activity hierarchy.
3. `HTTP Mock` tab can set/list/clear mock rules.
4. `Memory Leak` tab shows watch list and retained entries.

## Common issues

- `Cannot connect`: make sure phone and desktop are on same LAN, and port is reachable.
- `Connection refused` on `127.0.0.1:4939`: localhost has no listener unless port forward is set. Use:

```bash
adb devices
adb -s <device-id> forward tcp:4939 tcp:4939
nc -zv 127.0.0.1 4939
```

  Or use LAN mode directly: fill desktop `Host` with app `DebugKit.describeState().host` (not `127.0.0.1`).
- `No view tree`: ensure app has a foreground Activity when requesting.
- `Mock not hit`: verify exact `method + path` match with request.
- `Leak list empty`: call `DebugKit.watch(...)`, then trigger GC and refresh watches.

## Scope and security notes

This is an MVP for internal debugging, not production-hardened security.

- No authentication by default
- Plain TCP by default
- Intended for trusted local networks

Before production usage, add:

- AuthN/AuthZ
- TLS
- Command permission control
- Robust error handling and auditing
