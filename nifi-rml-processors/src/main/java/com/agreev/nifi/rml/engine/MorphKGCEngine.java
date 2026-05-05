package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;
import com.agreev.nifi.rml.model.OutputFormat;
import com.agreev.nifi.rml.util.RDFFormatConverters;
import com.agreev.nifi.rml.util.TempFiles;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

public final class MorphKGCEngine implements RMLEngine {

    public static final String ID = "MORPH_KGC";

    private final String command;
    private final long executionTimeoutMillis;
    private volatile Boolean cachedAvailability;

    public MorphKGCEngine(String command, long executionTimeoutMillis) {
        this.command = command == null || command.isBlank() ? "morph-kgc" : command.trim();
        this.executionTimeoutMillis = executionTimeoutMillis <= 0 ? 300_000L : executionTimeoutMillis;
    }

    public MorphKGCEngine() {
        this("morph-kgc", 300_000L);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = cachedAvailability;
        if (cached != null) {
            return cached;
        }
        boolean detected = detectCommand();
        cachedAvailability = detected;
        return detected;
    }

    public void invalidateAvailabilityCache() {
        cachedAvailability = null;
    }

    private boolean detectCommand() {
        try {
            ProcessBuilder pb = new ProcessBuilder(command, "--version")
                .redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Override
    public MappingResult execute(MappingRequest request) throws RMLEngineException {
        long start = System.currentTimeMillis();
        Path workDir = TempFiles.ensureDir(request.workingDirectory());
        Path configFile = TempFiles.createTempFile(workDir, "morph-config-", ".ini");
        Path rawOutput = workDir.resolve("morph-output.nt");
        Path stagedMapping = workDir.resolve("morph-mapping.ttl");
        Path finalOutput = TempFiles.createTempFile(workDir, "rml-out-", outputSuffix(request));

        try {
            Files.copy(request.mappingDocument(), stagedMapping, StandardCopyOption.REPLACE_EXISTING);
            writeMorphConfig(configFile, stagedMapping, request.inputData(), rawOutput);

            int exitCode = runMorph(configFile);
            if (exitCode != 0) {
                throw new RMLEngineException("morph-kgc exited with code " + exitCode);
            }

            // morph-kgc emits N-Triples by default; convert to the requested format.
            Model model = ModelFactory.createDefaultModel();
            RDFParser.source(rawOutput.toUri().toString()).lang(Lang.NTRIPLES).parse(model);
            try (OutputStream os = Files.newOutputStream(finalOutput)) {
                RDFFormatConverters.write(model, os, request.outputFormat());
            }

            return MappingResult.builder()
                .output(finalOutput)
                .outputFormat(request.outputFormat())
                .durationMillis(System.currentTimeMillis() - start)
                .triplesCount(model.size())
                .engineId(ID)
                .build();

        } catch (IOException e) {
            throw new RMLEngineException("MorphKGC engine I/O failure", e);
        } catch (RMLEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new RMLEngineException("MorphKGC engine failed: " + e.getMessage(), e);
        } finally {
            TempFiles.deleteSilently(configFile);
            TempFiles.deleteSilently(rawOutput);
            TempFiles.deleteSilently(stagedMapping);
        }
    }

    private void writeMorphConfig(Path configFile, Path mapping, Path inputData, Path output)
            throws IOException {
        // morph-kgc consumes an INI configuration file. We declare a single data source
        // pointing to the input and instruct morph-kgc to write N-Triples.
        String content = """
            [CONFIGURATION]
            output_file=%s
            output_format=N-TRIPLES

            [datasource]
            mappings=%s
            """.formatted(output.toAbsolutePath(), mapping.toAbsolutePath());
        Files.writeString(configFile, content, StandardCharsets.UTF_8);
        // Ensure paths in the mapping are resolvable.
        ensureMappingHasAbsoluteSourcePaths(mapping, inputData);
    }

    private void ensureMappingHasAbsoluteSourcePaths(Path mapping, Path inputData) throws IOException {
        // Replace the relative rml:source with the absolute input path so morph-kgc resolves it
        // regardless of working directory.
        String content = Files.readString(mapping, StandardCharsets.UTF_8);
        String absolute = inputData.toAbsolutePath().toString();
        // Pattern intentionally narrow: rml:source "filename" → rml:source "<absolute>"
        String rewritten = content.replaceAll(
            "rml:source\\s+\"[^\"]+\"",
            "rml:source \"" + absolute.replace("\\", "/") + "\"");
        if (!rewritten.equals(content)) {
            Files.writeString(mapping, rewritten, StandardCharsets.UTF_8);
        }
    }

    private int runMorph(Path configFile) throws IOException, InterruptedException, RMLEngineException {
        ProcessBuilder pb = new ProcessBuilder(command, configFile.toAbsolutePath().toString())
            .redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.append(line).append('\n');
            }
        }

        boolean finished = p.waitFor(executionTimeoutMillis, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RMLEngineException("morph-kgc timed out after " + executionTimeoutMillis + " ms");
        }
        if (p.exitValue() != 0) {
            throw new RMLEngineException("morph-kgc failed (exit=" + p.exitValue() + "):\n" + log);
        }
        return p.exitValue();
    }

    private static String outputSuffix(MappingRequest request) {
        return switch (request.outputFormat()) {
            case TURTLE   -> ".ttl";
            case NTRIPLES -> ".nt";
            case JSONLD   -> ".jsonld";
            case RDFXML   -> ".rdf";
        };
    }

    public String command() {
        return command;
    }

    public long executionTimeoutMillis() {
        return executionTimeoutMillis;
    }
}
