package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.engine.rml.CsvRecordSource;
import com.agreev.nifi.rml.engine.rml.JsonRecordSource;
import com.agreev.nifi.rml.engine.rml.RMLMapping;
import com.agreev.nifi.rml.engine.rml.RMLMappingParser;
import com.agreev.nifi.rml.engine.rml.RecordMaterializer;
import com.agreev.nifi.rml.engine.rml.XmlRecordSource;
import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;
import com.agreev.nifi.rml.model.OutputFormat;
import com.agreev.nifi.rml.util.TempFiles;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFFormat;
import org.apache.jena.riot.RDFWriter;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;

import java.io.BufferedOutputStream;
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

        System.err.printf("RMLMapper: Starting execution%n" +
            "  Mapping: %s%n" +
            "  Input: %s%n" +
            "  Output format: %s%n" +
            "  Output file: %s%n" +
            "  Work dir: %s%n",
            request.mappingDocument(), request.inputData(),
            request.outputFormat(), output, workDir);

        RMLMapping mapping = parser.parse(request.mappingDocument());
        System.err.printf("RMLMapper: Parsed mapping with %d triple maps%n", mapping.triplesMaps().size());

        try (OutputStream raw = Files.newOutputStream(output);
             OutputStream buffered = new BufferedOutputStream(raw)) {

            RDFFormat format = streamingFormat(request.outputFormat());
            System.err.printf("RMLMapper: Creating StreamRDF writer for format: %s%n", format);

            StreamRDF stream = StreamRDFWriter.getWriterStream(buffered, format);
            if (stream == null) {
                throw new RMLEngineException(
                    "Failed to create StreamRDF writer: StreamRDFWriter.getWriterStream returned null for format " + format);
            }

            RecordMaterializer materializer = new RecordMaterializer(stream);
            System.err.println("RMLMapper: Starting stream");
            stream.start();

            for (RMLMapping.TriplesMap tm : mapping.triplesMaps()) {
                materializeTriplesMap(tm, request, materializer);
            }

            System.err.println("RMLMapper: Finishing stream");
            stream.finish();
            buffered.flush();

            long durationMs = System.currentTimeMillis() - start;
            long tripleCount = materializer.tripleCount();
            System.err.printf("RMLMapper: Execution completed successfully%n" +
                "  Duration: %d ms%n" +
                "  Triples: %d%n" +
                "  Output: %s%n",
                durationMs, tripleCount, output);

            return MappingResult.builder()
                .output(output)
                .outputFormat(request.outputFormat())
                .durationMillis(durationMs)
                .triplesCount(tripleCount)
                .engineId(ID)
                .build();

        } catch (RMLEngineException e) {
            System.err.printf("RMLMapper: RMLEngineException: %s%n", e.getMessage());
            throw e;
        } catch (IOException e) {
            System.err.printf("RMLMapper: IOException: %s%n", e.getMessage());
            throw new RMLEngineException("RMLMapper engine I/O failure: " + e.getMessage(), e);
        } catch (Exception e) {
            System.err.printf("RMLMapper: Unexpected exception: %s%n", e.getMessage());
            e.printStackTrace(System.err);
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
        System.err.printf("RMLMapper: Materializing JSON triple map%n  Iterator: %s%n", tm.iterator());
        try (JsonRecordSource source = new JsonRecordSource(request.inputData(), tm.iterator())) {
            long recordCount = 0;
            for (var record : source) {
                recordCount++;
                materializer.materialize(tm, record);
            }
            System.err.printf("RMLMapper: JSON: processed %d records%n", recordCount);
        } catch (IOException e) {
            throw new RMLEngineException("Failed to read JSON input: " + request.inputData(), e);
        }
    }

    private void materializeCsv(RMLMapping.TriplesMap tm,
                                MappingRequest request,
                                RecordMaterializer materializer) throws RMLEngineException {
        System.err.println("RMLMapper: Materializing CSV triple map");
        try (CsvRecordSource source = new CsvRecordSource(request.inputData())) {
            long recordCount = 0;
            for (var record : source) {
                recordCount++;
                materializer.materialize(tm, record);
            }
            System.err.printf("RMLMapper: CSV: processed %d records%n", recordCount);
        } catch (IOException e) {
            throw new RMLEngineException("Failed to read CSV input: " + request.inputData(), e);
        }
    }

    private void materializeXml(RMLMapping.TriplesMap tm,
                                MappingRequest request,
                                RecordMaterializer materializer) throws RMLEngineException {
        System.err.printf("RMLMapper: Materializing XML triple map%n  Iterator: %s%n", tm.iterator());
        try {
            XmlRecordSource source = new XmlRecordSource(request.inputData(), tm.iterator());
            long recordCount = 0;
            System.err.printf("RMLMapper: XML: record source size = %d%n", source.size());
            for (var record : source) {
                recordCount++;
                materializer.materialize(tm, record);
            }
            System.err.printf("RMLMapper: XML: processed %d records%n", recordCount);
        } catch (IOException e) {
            throw new RMLEngineException("Failed to read XML input: " + request.inputData(), e);
        }
    }

    private static RDFFormat streamingFormat(OutputFormat format) {
        return switch (format) {
            case TURTLE   -> RDFFormat.TURTLE_BLOCKS;
            case NTRIPLES -> RDFFormat.NTRIPLES_UTF8;
            case JSONLD   -> RDFFormat.JSONLD_FLAT;
            case RDFXML   -> RDFFormat.RDFXML_PLAIN;
        };
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
