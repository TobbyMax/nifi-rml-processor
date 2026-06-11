package com.agreev.nifi.rml;

import com.agreev.nifi.rml.engine.RMLMapperEngine;
import com.agreev.nifi.rml.model.EngineMode;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteRMLMappingProcessorTest {

    private TestRunner runner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        runner = TestRunners.newTestRunner(ExecuteRMLMappingProcessor.class);
        runner.setProperty(ExecuteRMLMappingProcessor.MAPPING_SOURCE,
            ExecuteRMLMappingProcessor.MAPPING_SOURCE_INLINE.getValue());
        runner.setProperty(ExecuteRMLMappingProcessor.INPUT_DATA_FORMAT, "JSON");
        runner.setProperty(ExecuteRMLMappingProcessor.OUTPUT_RDF_FORMAT, "TURTLE");
        runner.setProperty(ExecuteRMLMappingProcessor.ENGINE_MODE, EngineMode.RMLMAPPER.name());
        runner.setProperty(ExecuteRMLMappingProcessor.AUTO_THRESHOLD_BYTES, "1000000");
        runner.setProperty(ExecuteRMLMappingProcessor.MORPH_KGC_COMMAND, "morph-kgc-not-installed");
        runner.setProperty(ExecuteRMLMappingProcessor.MORPH_KGC_FALLBACK, EngineMode.RMLMAPPER.name());
        runner.setProperty(ExecuteRMLMappingProcessor.BASE_IRI, "http://example.org/");
        runner.setProperty(ExecuteRMLMappingProcessor.TEMPORARY_DIRECTORY, tempDir.toString());
    }

    @Test
    void successfulJsonToTurtle() throws Exception {
        runner.setProperty(ExecuteRMLMappingProcessor.MAPPING_CONTENT, loadResource("/fixtures/mappings/customers.rml.ttl"));

        Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "customers.json");
        runner.enqueue(loadResource("/fixtures/data/customers.json").getBytes(StandardCharsets.UTF_8), attrs);
        runner.run();

        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_SUCCESS, 1);
        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_ORIGINAL, 1);
        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_FAILURE, 0);

        MockFlowFile out = runner.getFlowFilesForRelationship(ExecuteRMLMappingProcessor.REL_SUCCESS).get(0);
        out.assertAttributeEquals("rml.engine.selected", RMLMapperEngine.ID);
        out.assertAttributeEquals("rml.output.format", "TURTLE");
        out.assertAttributeEquals("rml.triples.count", "6");
        out.assertAttributeExists("rml.duration.ms");

        String body = new String(runner.getContentAsByteArray(out), StandardCharsets.UTF_8);
        Model actual = ModelFactory.createDefaultModel();
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            RDFParser.source(in).lang(Lang.TURTLE).parse(actual);
        }

        Model expected = ModelFactory.createDefaultModel();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/expected/customers.ttl")) {
            RDFParser.source(in).lang(Lang.TURTLE).parse(expected);
        }
        assertThat(actual.isIsomorphicWith(expected)).isTrue();
    }

    @Test
    void invalidMappingRoutesToFailure() throws Exception {
        runner.setProperty(ExecuteRMLMappingProcessor.MAPPING_CONTENT,
            "@prefix rr: <http://www.w3.org/ns/r2rml#> .\n# no triples maps");
        runner.enqueue("[]".getBytes(StandardCharsets.UTF_8));
        runner.run();

        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_FAILURE, 1);
        MockFlowFile failed = runner.getFlowFilesForRelationship(ExecuteRMLMappingProcessor.REL_FAILURE).get(0);
        failed.assertAttributeExists("rml.error.message");
        failed.assertAttributeExists("rml.error.type");
    }

    @Test
    void yarrrmlInlineMappingTranspilesAndExecutes() throws Exception {
        runner.setProperty(ExecuteRMLMappingProcessor.MAPPING_FORMAT,
            ExecuteRMLMappingProcessor.MAPPING_FORMAT_YARRRML.getValue());
        runner.setProperty(ExecuteRMLMappingProcessor.MAPPING_CONTENT, loadResource("/fixtures/mappings/customers.yarrrml.yml"));

        Map<String, String> attrs = new HashMap<>();
        attrs.put("filename", "customers.json");
        runner.enqueue(loadResource("/fixtures/data/customers.json").getBytes(StandardCharsets.UTF_8), attrs);
        runner.run();

        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_SUCCESS, 1);
        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_ORIGINAL, 1);
        runner.assertTransferCount(ExecuteRMLMappingProcessor.REL_FAILURE, 0);

        MockFlowFile out = runner.getFlowFilesForRelationship(ExecuteRMLMappingProcessor.REL_SUCCESS).get(0);
        out.assertAttributeEquals("rml.engine.selected", RMLMapperEngine.ID);
        out.assertAttributeEquals("rml.output.format", "TURTLE");

        String body = new String(runner.getContentAsByteArray(out), StandardCharsets.UTF_8);
        Model actual = ModelFactory.createDefaultModel();
        try (InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))) {
            RDFParser.source(in).lang(Lang.TURTLE).parse(actual);
        }

        Model expected = ModelFactory.createDefaultModel();
        try (InputStream in = getClass().getResourceAsStream("/fixtures/expected/customers.ttl")) {
            RDFParser.source(in).lang(Lang.TURTLE).parse(expected);
        }
        assertThat(actual.isIsomorphicWith(expected)).isTrue();
    }

    private String loadResource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("Resource %s should exist", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
