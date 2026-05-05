plugins {
    java
}

allprojects {
    group = "com.agreev.nifi"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven {
            name = "Eclipse RDF4J snapshots"
            url = uri("https://repo.eclipse.org/content/repositories/rdf4j-snapshots/")
        }
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}

extra["nifiVersion"] = "2.8.0"
extra["jenaVersion"] = "5.2.0"
extra["carmlVersion"] = "1.4.0"
extra["snakeyamlVersion"] = "2.3"
extra["junitVersion"] = "5.10.2"
extra["assertjVersion"] = "3.26.3"
extra["mockitoVersion"] = "5.14.2"
