package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.engine.rml.CsvRecordSource;
import com.agreev.nifi.rml.engine.rml.JsonRecordSource;
import com.agreev.nifi.rml.engine.rml.RMLMapping;
import com.agreev.nifi.rml.engine.rml.RMLMappingParser;
import com.agreev.nifi.rml.engine.rml.RecordMaterializer;
import com.agreev.nifi.rml.engine.rml.XmlRecordSource;
import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;
import com.agreev.nifi.rml.util.RDFFormatConverters;
import com.agreev.nifi.rml.util.TempFiles;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RMLMapperEngine implements RMLEngine {

    public static final String ID = "RMLMAPPER";

    private final RMLMappingParser parser = new RMLMappingParser();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public MappingResult execute(MappingRequest request) throws RMLEngineException {
        long start = System.currentTimeMillis();
        Path workDir = TempFiles.ensureDir(request.workingDirectory());
        Path output = TempFiles.createTempFile(workDir, "rml-out-", outputSuffix(request));

        try {
            RMLMapping mapping = parser.parse(request.mappingDocument());
            Model model = ModelFactory.createDefaultModel();
            RecordMaterializer materializer = new RecordMaterializer(model);

            for (RMLMapping.TriplesMap tm : mapping.triplesMaps()) {
                materializeTriplesMap(tm, request, materializer);
            }

            try (OutputStream os = Files.newOutputStream(output)) {
                RDFFormatConverters.write(model, os, request.outputFormat());
            }

            return MappingResult.builder()
                .output(output)
                .outputFormat(request.outputFormat())
                .durationMillis(System.currentTimeMillis() - start)
                .triplesCount(model.size())
                .engineId(ID)
                .build();

        } catch (RMLEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new RMLEngineException("RMLMapper engine failed: " + e.getMessage(), e);
        }
    }

    private void materializeTriplesMap(RMLMapping.TriplesMap tm,
                                       MappingRequest request,
                                       RecordMaterializer materializer) throws RMLEngineException {
        switch (tm.referenceFormulation()) {
            case JSONPATH -> materializeJson(tm, request, materializer);
            case CSV      -> materializeCsv(tm, request, materializer);
            case XPATH    -> materializeXml(tm, request, materializer);
        }
    }

    private void materializeJson(RMLMapping.TriplesMap tm,
                                 MappingRequest request,
                                 RecordMaterializer materializer) throws RMLEngineException {
        try {
            JsonRecordSource source = new JsonRecordSource(request.inputData(), tm.iterator());
            for (var record : source) {
                materializer.materialize(tm, record);
            }
        } catch (IOException e) {
            throw new RMLEngineException("Failed to read JSON input: " + request.inputData(), e);
        }
    }

    private void materializeCsv(RMLMapping.TriplesMap tm,
                                MappingRequest request,
                                RecordMaterializer materializer) throws RMLEngineException {
        try {
            CsvRecordSource source = new CsvRecordSource(request.inputData());
            for (var record : source) {
                materializer.materialize(tm, record);
            }
        } catch (IOException e) {
            throw new RMLEngineException("Failed to read CSV input: " + request.inputData(), e);
        }
    }

    private void materializeXml(RMLMapping.TriplesMap tm,
                                MappingRequest request,
                                RecordMaterializer materializer) throws RMLEngineException {
        try {
            XmlRecordSource source = new XmlRecordSource(request.inputData(), tm.iterator());
            for (var record : source) {
                materializer.materialize(tm, record);
            }
        } catch (IOException e) {
            throw new RMLEngineException("Failed to read XML input: " + request.inputData(), e);
        }
    }

    private static String outputSuffix(MappingRequest request) {
        return switch (request.outputFormat()) {
            case TURTLE   -> ".ttl";
            case NTRIPLES -> ".nt";
            case JSONLD   -> ".jsonld";
            case RDFXML   -> ".rdf";
        };
    }
}
