// NAR-bundle module for Apache NiFi.
// Apache NiFi has no official Gradle plugin (only nifi-nar-maven-plugin), so this
// module assembles the NAR archive manually as a Zip with the layout NiFi expects:
//
//   META-INF/MANIFEST.MF                — declares Nar-Id / Nar-Version / Nar-Group
//   META-INF/bundled-dependencies/*.jar — runtime dependencies (excluding nifi-api)
//   <main-jar>.jar                      — the processors module jar
//
// NiFi expands the NAR at startup and exposes a dedicated classloader rooted at the
// bundled dependencies, isolating processor libraries from the framework.

val nifiVersion: String by rootProject.extra

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

tasks.register<Zip>("nar") {
    description = "Assembles the Apache NiFi NAR-bundle"
    group = "build"

    archiveFileName.set(narFileName)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))

    dependsOn(processorsProject.tasks.named("jar"))

    // Main processors jar at NAR root.
    from(processorsProject.tasks.named("jar")) {
        // Single jar at archive root.
    }

    // Runtime dependencies into META-INF/bundled-dependencies.
    from({
        bundledDependencies
            .resolvedConfiguration
            .resolvedArtifacts
            .map { it.file }
            .filter { file ->
                // nifi-api is provided by NiFi runtime — exclude from the bundle.
                !file.name.startsWith("nifi-api")
                    && !file.name.startsWith("nifi-utils")
                    && !file.name.startsWith("nifi-mock")
            }
    }) {
        into("META-INF/bundled-dependencies")
    }

    // NAR manifest.
    val manifestContent = """
        Manifest-Version: 1.0
        Nar-Id: nifi-rml-nar
        Nar-Group: com.agreev.nifi
        Nar-Version: ${project.version}
        Build-Tag: nifi-rml-processor
        Build-Jdk: ${System.getProperty("java.version")}
        Built-By: gradle
    """.trimIndent() + "\n"

    val manifestFile = layout.buildDirectory.file("tmp/MANIFEST.MF")
    doFirst {
        val mf = manifestFile.get().asFile
        mf.parentFile.mkdirs()
        mf.writeText(manifestContent)
    }
    from(manifestFile) {
        into("META-INF")
        rename { "MANIFEST.MF" }
    }
}

tasks.named("assemble") {
    dependsOn("nar")
}
