// NAR-bundle module for Apache NiFi.
// Apache NiFi has no official Gradle plugin (only nifi-nar-maven-plugin), so this
// module assembles the NAR archive manually as a Zip following the convention
// produced by nifi-nar-maven-plugin:
//
//   META-INF/MANIFEST.MF                — declares Nar-Id / Nar-Version / Nar-Group
//   META-INF/bundled-dependencies/*.jar — processor jar AND all its runtime deps
//
// NiFi expands the NAR at startup and exposes a dedicated classloader rooted at
// META-INF/bundled-dependencies, isolating processor libraries from the framework.

plugins {
    base
}

val bundledDependencies: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundledDependencies(project(":nifi-rml-processors"))
}

val processorsProject = project(":nifi-rml-processors")
val narFileName = "${rootProject.name}-${project.version}.nar"

val manifestFileProvider = layout.buildDirectory.file("tmp/MANIFEST.MF")
val manifestContent = """
Manifest-Version: 1.0
Nar-Id: nifi-rml-nar
Nar-Group: com.agreev.nifi
Nar-Version: ${project.version}
Build-Tag: nifi-rml-processor
Build-Jdk: ${System.getProperty("java.version")}
Built-By: gradle
""".trimIndent() + "\n"

val writeNarManifest = tasks.register("writeNarManifest") {
    val out = manifestFileProvider
    outputs.file(out)
    doLast {
        val mf = out.get().asFile
        mf.parentFile.mkdirs()
        mf.writeText(manifestContent)
    }
}

tasks.register<Zip>("nar") {
    description = "Assembles the Apache NiFi NAR-bundle"
    group = "build"

    archiveFileName.set(narFileName)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    dependsOn(processorsProject.tasks.named("jar"))
    dependsOn(writeNarManifest)

    // Processor jar — into bundled-dependencies (NiFi convention).
    from(processorsProject.tasks.named<Jar>("jar").map { it.archiveFile.get().asFile }) {
        into("META-INF/bundled-dependencies")
    }

    // Runtime dependencies into META-INF/bundled-dependencies.
    // Filter out nifi-* artifacts since NiFi provides them at runtime.
    from({
        bundledDependencies
            .resolvedConfiguration
            .resolvedArtifacts
            .filter { artifact ->
                val id = artifact.id.componentIdentifier
                id !is org.gradle.api.artifacts.component.ProjectComponentIdentifier
            }
            .map { it.file }
            .filter { file ->
                !file.name.startsWith("nifi-api")
                    && !file.name.startsWith("nifi-utils")
                    && !file.name.startsWith("nifi-mock")
            }
    }) {
        into("META-INF/bundled-dependencies")
    }

    from(manifestFileProvider) {
        into("META-INF")
        rename { "MANIFEST.MF" }
    }
}

tasks.named("assemble") {
    dependsOn("nar")
}
