package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.InputFormat;
import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;
import com.agreev.nifi.rml.model.OutputFormat;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

class RMLMapperEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void executesJsonMappingAndProducesIsomorphicTurtle() throws Exception {
        Path inputData = copyFixture("/fixtures/data/customers.json", tempDir.resolve("customers.json"));
        Path mapping = copyFixture("/fixtures/mappings/customers.rml.ttl",
            tempDir.resolve("customers.rml.ttl"));

        runEngineAndAssertIsomorphic(
            inputData, mapping, InputFormat.JSON, "/fixtures/expected/customers.ttl");
    }

    @Test
    void executesCsvMappingAndProducesIsomorphicTurtle() throws Exception {
        Path inputData = copyFixture("/fixtures/data/customers.csv", tempDir.resolve("customers.csv"));
        Path mapping = copyFixture("/fixtures/mappings/customers.csv.rml.ttl",
            tempDir.resolve("customers.csv.rml.ttl"));

        runEngineAndAssertIsomorphic(
            inputData, mapping, InputFormat.CSV, "/fixtures/expected/customers.ttl");
    }

    @Test
    void executesXmlMappingAndProducesIsomorphicTurtle() throws Exception {
        Path inputData = copyFixture("/fixtures/data/customers.xml", tempDir.resolve("customers.xml"));
        Path mapping = copyFixture("/fixtures/mappings/customers.xml.rml.ttl",
            tempDir.resolve("customers.xml.rml.ttl"));

        runEngineAndAssertIsomorphic(
            inputData, mapping, InputFormat.XML, "/fixtures/expected/customers_xml.ttl");
    }

    private void runEngineAndAssertIsomorphic(Path inputData, Path mapping,
                                              InputFormat format, String expectedResource)
            throws Exception {
        MappingRequest request = MappingRequest.builder()
            .inputData(inputData)
            .inputSizeBytes(Files.size(inputData))
            .inputFormat(format)
            .mappingDocument(mapping)
            .outputFormat(OutputFormat.TURTLE)
            .baseIri("http://example.org/")
            .workingDirectory(tempDir.resolve("work-" + format.name().toLowerCase()))
            .build();

        RMLMapperEngine engine = new RMLMapperEngine();
        MappingResult result = engine.execute(request);

        assertThat(result.engineId()).isEqualTo(RMLMapperEngine.ID);
        assertThat(result.triplesCount()).isEqualTo(6L);
        assertThat(result.output()).exists();

        Model actual = ModelFactory.createDefaultModel();
        RDFParser.source(result.output().toUri().toString()).lang(Lang.TURTLE).parse(actual);

        Model expected = ModelFactory.createDefaultModel();
        try (InputStream expectedStream = getClass().getResourceAsStream(expectedResource)) {
            RDFParser.source(expectedStream).lang(Lang.TURTLE).parse(expected);
        }
        assertThat(actual.isIsomorphicWith(expected))
            .as("Generated RDF should be isomorphic to %s", expectedResource)
            .isTrue();
    }

    private Path copyFixture(String resource, Path target) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("Fixture %s should exist", resource).isNotNull();
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
