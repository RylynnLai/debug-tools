
plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.debugtools.desktop.DesktopMain")
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

val isWindows = System.getProperty("os.name").lowercase().contains("win")
val isMac     = System.getProperty("os.name").lowercase().contains("mac")

/** Find the jpackage binary that belongs to the active JDK 17 installation. */
fun findJpackage(): String {
    val javaHome = System.getProperty("java.home") ?: ""
    return listOf(
        File(javaHome, "bin/jpackage"),            // JDK 9+ layout
        File(javaHome, "../bin/jpackage"),          // JRE-in-JDK legacy layout
        File(System.getenv("JAVA_HOME") ?: "", "bin/jpackage")
    ).firstOrNull { it.canExecute() }?.canonicalPath ?: "jpackage"
}

// ─────────────────────────────────────────────────────────────────────────────
// Fat JAR
// Produces build/libs/debug-tools-desktop-all.jar
// Usage: java -jar build/libs/debug-tools-desktop-all.jar
// ─────────────────────────────────────────────────────────────────────────────

tasks.register<Jar>("fatJar") {
    group       = "build"
    description = "Assemble a self-contained executable JAR with all dependencies bundled."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.debugtools.desktop.DesktopMain"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.named<Jar>("jar").get())
}

// ─────────────────────────────────────────────────────────────────────────────
// packageDesktopApp — native app via jpackage (bundles its own JRE)
//
//   macOS   → build/jpackage/DebugTools.app          (double-click in Finder)
//   Windows → build/jpackage/DebugTools/DebugTools.exe
//   Linux   → build/jpackage/DebugTools/bin/DebugTools
//
// Run:
//   ./gradlew :desktop:packageDesktopApp
// ─────────────────────────────────────────────────────────────────────────────

tasks.register<Exec>("packageDesktopApp") {
    group       = "debug tools"
    description = "Package the desktop panel as a self-contained native app using jpackage."
    dependsOn("fatJar")

    // All values that must be resolved at execution time are captured via
    // providers / lazy properties so configuration cache stays happy.
    val fatJarProvider = tasks.named<Jar>("fatJar").flatMap { it.archiveFile }
    val jpackageOutDir = layout.buildDirectory.dir("jpackage")

    // Placeholder so Gradle validation passes at configuration time;
    // overridden with the real command in doFirst.
    commandLine("true")
    isIgnoreExitValue = false

    doFirst {
        val fatJarFile  = fatJarProvider.get().asFile
        val jpackageOut = jpackageOutDir.get().asFile

        jpackageOut.deleteRecursively()
        jpackageOut.mkdirs()

        val exe = findJpackage()
        println("🔧  jpackage: $exe")

        setCommandLine(listOf(
            exe,
            "--type",         "app-image",   // raw bundle, no installer
            "--input",        fatJarFile.parentFile.absolutePath,
            "--main-jar",     fatJarFile.name,
            "--main-class",   "com.debugtools.desktop.DesktopMain",
            "--name",         "DebugTools",
            "--dest",         jpackageOut.absolutePath,
            "--app-version",  "1.0",
            "--java-options", "-Xmx512m"
        ))
    }

    doLast {
        val jpackageOut = jpackageOutDir.get().asFile
        val appPath = if (isMac) File(jpackageOut, "DebugTools.app")
                      else       File(jpackageOut, "DebugTools")
        println("")
        println("✅  Native app → ${appPath.absolutePath}")
        if (isMac) {
            println("    Install → /Applications:  ./gradlew :desktop:installDesktopApp")
            println("    Open now:                 open \"${appPath.absolutePath}\"")
        } else {
            println("    Install:  ./gradlew :desktop:installDesktopApp")
        }
        println("")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// installDesktopApp — install the native app system-wide
//
//   macOS   → /Applications/DebugTools.app
//   Linux   → ~/.local/share/DebugTools  +  symlink ~/.local/bin/DebugTools
//   Windows → prints the build output path
//
// Run:
//   ./gradlew :desktop:installDesktopApp
// ─────────────────────────────────────────────────────────────────────────────

tasks.register("installDesktopApp") {
    group       = "debug tools"
    description = "Install the native DebugTools app to /Applications (macOS) or ~/.local (Linux)."
    dependsOn("packageDesktopApp")

    doLast {
        val jpackageOut = layout.buildDirectory.dir("jpackage").get().asFile

        when {
            isMac -> {
                val src = File(jpackageOut, "DebugTools.app")
                val dst = File("/Applications/DebugTools.app")
                check(src.exists()) { "App bundle not found: ${src.absolutePath}" }
                dst.deleteRecursively()
                // Use 'cp -rp' to preserve file permissions (execute bits) that
                // copyRecursively() strips.
                val proc = Runtime.getRuntime().exec(arrayOf(
                    "cp", "-rp", src.absolutePath, dst.absolutePath))
                val exitCode = proc.waitFor()
                check(exitCode == 0) {
                    "cp failed (exit $exitCode): ${proc.errorStream.bufferedReader().readText()}"
                }
                println("")
                println("✅  Installed: /Applications/DebugTools.app")
                println("    • Double-click it in Finder")
                println("    • Or: open /Applications/DebugTools.app")
                println("")
            }
            !isWindows -> {   // Linux
                val src     = File(jpackageOut, "DebugTools")
                val dstDir  = File(System.getProperty("user.home"), ".local/share/DebugTools")
                val binDir  = File(System.getProperty("user.home"), ".local/bin")
                val symlink = File(binDir, "DebugTools")
                check(src.exists()) { "App dir not found: ${src.absolutePath}" }
                dstDir.deleteRecursively()
                src.copyRecursively(dstDir, overwrite = true)
                binDir.mkdirs()
                File(dstDir, "bin/DebugTools").setExecutable(true)
                symlink.delete()   // safe no-op if absent; also removes stale symlinks
                Runtime.getRuntime()
                    .exec(arrayOf("ln", "-sf", File(dstDir, "bin/DebugTools").absolutePath, symlink.absolutePath))
                    .waitFor()
                println("")
                println("✅  Installed: $dstDir")
                println("    Symlink:  $symlink")
                println("    Make sure ~/.local/bin is in PATH, then:  DebugTools")
                println("")
            }
            else -> {   // Windows
                println("")
                println("✅  Built: ${File(jpackageOut, "DebugTools")}")
                println("    Copy the folder anywhere and run DebugTools.exe inside it.")
                println("")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// installDebugDesktop — install Gradle-generated launcher scripts + PATH link
//
//   Installs to ~/.debug-tools/
//   Symlinks  /usr/local/bin/debug-desktop  →  ~/.debug-tools/bin/debug-tools-desktop
//
// This is the lighter alternative to installDesktopApp (requires Java on PATH;
// does NOT bundle its own JRE).
//
// Run:
//   ./gradlew :desktop:installDebugDesktop
// ─────────────────────────────────────────────────────────────────────────────

val debugToolsInstallDir = File(System.getProperty("user.home"), ".debug-tools")

tasks.register<Copy>("installDebugDesktop") {
    group       = "debug tools"
    description = "Install desktop launcher to ~/.debug-tools/ and symlink to /usr/local/bin/debug-desktop."
    dependsOn("installDist")
    from(layout.buildDirectory.dir("install/debug-tools-desktop"))
    into(debugToolsInstallDir)

    doLast {
        val launcher = File(debugToolsInstallDir, "bin/debug-tools-desktop")
        launcher.setExecutable(true)

        val symlink = File("/usr/local/bin/debug-desktop")
        try {
            if (symlink.exists()) symlink.delete()   // also removes stale symlinks
            Runtime.getRuntime()
                .exec(arrayOf("ln", "-sf", launcher.absolutePath, symlink.absolutePath))
                .waitFor()
            println("")
            println("✅  Installed : $debugToolsInstallDir")
            println("    PATH link  : $symlink  →  $launcher")
            println("    Run from anywhere:  debug-desktop")
            println("")
        } catch (e: Exception) {
            println("")
            println("✅  Installed : $debugToolsInstallDir")
            println("    ⚠️  /usr/local/bin symlink failed: ${e.message}")
            println("    Add manually: sudo ln -sf ${launcher.absolutePath} /usr/local/bin/debug-desktop")
            println("    Or run directly: ${launcher.absolutePath}")
            println("")
        }
    }
}
