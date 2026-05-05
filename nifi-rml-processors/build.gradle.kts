val nifiVersion: String by rootProject.extra
val jenaVersion: String by rootProject.extra
val carmlVersion: String by rootProject.extra
val snakeyamlVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val mockitoVersion: String by rootProject.extra

dependencies {
    // NiFi processor SPI — provided by the runtime, not bundled into the NAR.
    compileOnly("org.apache.nifi:nifi-api:$nifiVersion")
    compileOnly("org.apache.nifi:nifi-utils:$nifiVersion")
    compileOnly("org.apache.nifi:nifi-mock:$nifiVersion")

    // RML in-process engine: CARML — Maven Central, Apache 2.0.
    // CARML name is preserved internally; engine id() exposed to operators is "RMLMAPPER"
    // (covers both reference RMLMapper and CARML semantics for the user).
    implementation("io.carml:carml-engine:$carmlVersion")
    implementation("io.carml:carml-model:$carmlVersion")
    implementation("io.carml:carml-logical-source-resolver-jsonpath:$carmlVersion")
    implementation("io.carml:carml-logical-source-resolver-csv:$carmlVersion")
    implementation("io.carml:carml-logical-source-resolver-xpath:$carmlVersion")

    // Apache Jena for RDF format conversion and isomorphism checks.
    implementation("org.apache.jena:jena-arq:$jenaVersion")

    // YAML parser for the YARRRML translator (basic subset).
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
