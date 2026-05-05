val nifiVersion: String by rootProject.extra
val jenaVersion: String by rootProject.extra
val snakeyamlVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val mockitoVersion: String by rootProject.extra

dependencies {
    // NiFi processor SPI — provided by the runtime, not bundled into the NAR.
    compileOnly("org.apache.nifi:nifi-api:$nifiVersion")
    compileOnly("org.apache.nifi:nifi-utils:$nifiVersion")

    // Apache Jena — RDF model, Turtle parser, format conversion, isomorphism.
    implementation("org.apache.jena:jena-arq:$jenaVersion")

    // JSONPath evaluator for RML JSON sources (ql:JSONPath).
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    // YAML parser for the YARRRML translator.
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")

    // Tests
    testImplementation("org.apache.nifi:nifi-mock:$nifiVersion")
    testImplementation("org.apache.nifi:nifi-api:$nifiVersion")
    testImplementation("org.apache.nifi:nifi-utils:$nifiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "M. Ageev (HSE BI coursework)"
        )
    }
}
