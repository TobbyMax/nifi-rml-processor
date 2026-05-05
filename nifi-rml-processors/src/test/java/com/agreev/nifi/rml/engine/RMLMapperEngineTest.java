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

        MappingRequest request = MappingRequest.builder()
            .inputData(inputData)
            .inputSizeBytes(Files.size(inputData))
            .inputFormat(InputFormat.JSON)
            .mappingDocument(mapping)
            .outputFormat(OutputFormat.TURTLE)
            .baseIri("http://example.org/")
            .workingDirectory(tempDir.resolve("work"))
            .build();

        RMLMapperEngine engine = new RMLMapperEngine();
        MappingResult result = engine.execute(request);

        assertThat(result.engineId()).isEqualTo(RMLMapperEngine.ID);
        assertThat(result.triplesCount()).isEqualTo(6L);
        assertThat(result.output()).exists();

        Model actual = ModelFactory.createDefaultModel();
        RDFParser.source(result.output().toUri().toString()).lang(Lang.TURTLE).parse(actual);

        Model expected = ModelFactory.createDefaultModel();
        try (InputStream expectedStream = getClass().getResourceAsStream("/fixtures/expected/customers.ttl")) {
            RDFParser.source(expectedStream).lang(Lang.TURTLE).parse(expected);
        }

        assertThat(actual.isIsomorphicWith(expected))
            .as("Generated RDF should be isomorphic to expected fixture")
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
