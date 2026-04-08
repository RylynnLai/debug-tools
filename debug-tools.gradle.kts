/**
 * Debug Tools — convention script for external app projects.
 *
 * Drop into your app project's root build.gradle.kts:
 *
 *   apply(from = "/absolute/path/to/debug-tools/debug-tools.gradle.kts")
 *
 * Or read the path from local.properties (recommended):
 *
 *   // root build.gradle.kts
 *   val debugToolsPath = java.util.Properties()
 *       .also { p -> rootProject.file("local.properties").inputStream().use(p::load) }
 *       .getProperty("debug.tools.path", "")
 *   if (debugToolsPath.isNotEmpty()) {
 *       apply(from = "$debugToolsPath/debug-tools.gradle.kts")
 *   }
 *
 *   # local.properties (do NOT commit this file)
 *   debug.tools.path=/Users/you/code/debug-tools
 *
 * After applying, run:
 *   ./gradlew launchDebugDesktop
 */

// ── Locate the best available launcher ────────────────────────────────────────
// Priority:
//   1. /Applications/DebugTools.app          (macOS native bundle, no Java needed)
//   2. ~/.debug-tools/bin/debug-tools-desktop (script-based install, needs Java)
//   3. run-desktop.sh next to this script     (source-tree fallback)

val _isMac       = System.getProperty("os.name").lowercase().contains("mac")
val _nativeApp   = File("/Applications/DebugTools.app/Contents/MacOS/DebugTools")
val _installedBin= File(System.getProperty("user.home"), ".debug-tools/bin/debug-tools-desktop")
val _scriptDir   = buildscript.sourceFile?.parentFile
val _runSh       = _scriptDir?.resolve("run-desktop.sh")

tasks.register<Exec>("launchDebugDesktop") {
    group       = "debug tools"
    description = "Launch the Debug Tools desktop control panel."

    when {
        _isMac && _nativeApp.canExecute() -> {
            println("🖥  Using native app: /Applications/DebugTools.app")
            commandLine(_nativeApp.absolutePath)
        }
        _installedBin.exists() -> {
            println("🖥  Using installed binary: ${_installedBin.absolutePath}")
            commandLine(_installedBin.absolutePath)
        }
        _runSh != null && _runSh.exists() -> {
            println("🖥  Using source launcher: ${_runSh.absolutePath}")
            commandLine("bash", _runSh.absolutePath)
        }
        else -> {
            doFirst {
                throw GradleException(
                    """
                    |Debug Tools desktop launcher not found.
                    |
                    |Option A — install native app (recommended, no Java needed):
                    |  cd /path/to/debug-tools
                    |  ./gradlew :desktop:installDesktopApp
                    |
                    |Option B — install script-based launcher (needs Java on PATH):
                    |  cd /path/to/debug-tools
                    |  ./gradlew :desktop:installDebugDesktop
                    |
                    |Option C — set debug.tools.path in local.properties:
                    |  debug.tools.path=/absolute/path/to/debug-tools
                    """.trimMargin()
                )
            }
            commandLine("true")
        }
    }

    isIgnoreExitValue = true   // GUI process; exit code is not meaningful
}

tasks.register("debugToolsHelp") {
    group       = "debug tools"
    description = "Print Debug Tools integration help."
    doLast {
        println("""
            |╔══════════════════════════════════════════════════════╗
            |║          Debug Tools — integration help              ║
            |╚══════════════════════════════════════════════════════╝
            |
            | Tasks registered by debug-tools.gradle.kts:
            |   launchDebugDesktop  — start the desktop panel
            |   debugToolsHelp     — show this message
            |
            | Launcher priority (first found wins):
            |   1. /Applications/DebugTools.app   (macOS, no Java needed)
            |   2. ~/.debug-tools/bin/debug-tools-desktop
            |   3. run-desktop.sh (debug-tools source tree)
            |
            | Install native app once:
            |   cd /path/to/debug-tools && ./gradlew :desktop:installDesktopApp
            |
            | Then from your app project:
            |   ./gradlew launchDebugDesktop
            |
            | Or open directly:
            |   open /Applications/DebugTools.app
        """.trimMargin())
    }
}
