import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

plugins {
    // AGP supplies the root Kotlin compiler; using it without a second version avoids
    // loading two copies of Kotlin into the same build.
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":transfer"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
    implementation("net.java.dev.jna:jna-platform:${libs.versions.jna.get()}")
    testImplementation(libs.junit)
    testImplementation(compose.desktop.uiTestJUnit4)
}

compose.desktop {
    application {
        mainClass = "com.cleo.cleos.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Cleos"
            packageVersion = "0.13.0"
            description = "Cleos Desktop Preview"
            vendor = "Cleos"
            includeAllModules = true
            windows { menuGroup = "Cleos"; shortcut = true; dirChooser = true }
        }
    }
}

tasks.test { jvmArgs("--add-modules=jdk.httpserver"); systemProperty("file.encoding", "UTF-8") }

tasks.register<Copy>("copyDesktopLicenses") {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE"),rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/Cleos/licenses/cleos"))
    doLast {
        val base = layout.buildDirectory.dir("compose/binaries/main/app/Cleos").get().asFile
        rootProject.copy { from(project.file("licenses")); into(base.resolve("licenses/upstream")) }
        rootProject.copy { from(rootProject.file("docs/DESKTOP_PREVIEW.md")); into(base) }
        val inventory = configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }
        base.resolve("licenses/DEPENDENCIES.tsv").writeText("coordinate\tartifact\n" + inventory.joinToString("\n") { "${it.moduleVersion.id}\t${it.file.name}" } + "\n")
        inventory.forEach { artifact ->
            if (artifact.file.extension == "jar") ZipFile(artifact.file).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory && !it.name.endsWith(".class") &&
                    listOf("LICENSE", "NOTICE", "COPYRIGHT").any { word -> it.name.uppercase().contains(word) } }
                    .forEach { entry ->
                        val destination = base.resolve("licenses/jars/${artifact.moduleVersion.id.group}/${artifact.file.name}/${entry.name}").normalize()
                        require(destination.toPath().startsWith(base.resolve("licenses/jars").toPath()))
                        destination.parentFile.mkdirs()
                        jar.getInputStream(entry).use { input -> destination.outputStream().use { input.copyTo(it) } }
                    }
            }
        }
    }
}
tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy("copyDesktopLicenses") }
