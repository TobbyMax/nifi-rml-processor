package com.agreev.nifi.rml.yarrrml;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class YARRRMLParserTest {

    @Test
    void transpilesCustomersYarrrmlIntoValidTurtle() throws Exception {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/mappings/customers.yarrrml.yml")) {
            assertThat(in).isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String turtle = new YARRRMLParser().parse(yaml);

        // Result must be parseable Turtle.
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = new ByteArrayInputStream(turtle.getBytes(StandardCharsets.UTF_8))) {
            RDFParser.source(in).lang(Lang.TURTLE).parse(model);
        }

        // It must declare exactly one TriplesMap with rml:logicalSource and a JSONPath formulation.
        assertThat(turtle)
            .contains("rml:logicalSource")
            .contains("ql:JSONPath")
            .contains("rml:reference \"name\"")
            .contains("rml:reference \"email\"")
            .contains("http://example.org/customer/{id}");
    }
}
