package com.agreev.nifi.rml.model;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public final class MappingRequest {

    private final Path inputData;
    private final long inputSizeBytes;
    private final InputFormat inputFormat;
    private final Path mappingDocument;
    private final OutputFormat outputFormat;
    private final String baseIri;
    private final Path workingDirectory;
    private final Map<String, String> options;

    private MappingRequest(Builder b) {
        this.inputData = Objects.requireNonNull(b.inputData, "inputData");
        this.inputSizeBytes = b.inputSizeBytes;
        this.inputFormat = Objects.requireNonNull(b.inputFormat, "inputFormat");
        this.mappingDocument = Objects.requireNonNull(b.mappingDocument, "mappingDocument");
        this.outputFormat = Objects.requireNonNull(b.outputFormat, "outputFormat");
        this.baseIri = b.baseIri == null ? "http://example.org/" : b.baseIri;
        this.workingDirectory = Objects.requireNonNull(b.workingDirectory, "workingDirectory");
        this.options = b.options == null ? Map.of() : Collections.unmodifiableMap(b.options);
    }

    public Path inputData() { return inputData; }
    public long inputSizeBytes() { return inputSizeBytes; }
    public InputFormat inputFormat() { return inputFormat; }
    public Path mappingDocument() { return mappingDocument; }
    public OutputFormat outputFormat() { return outputFormat; }
    public String baseIri() { return baseIri; }
    public Path workingDirectory() { return workingDirectory; }
    public Map<String, String> options() { return options; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path inputData;
        private long inputSizeBytes;
        private InputFormat inputFormat;
        private Path mappingDocument;
        private OutputFormat outputFormat = OutputFormat.TURTLE;
        private String baseIri;
        private Path workingDirectory;
        private Map<String, String> options;

        public Builder inputData(Path v) { this.inputData = v; return this; }
        public Builder inputSizeBytes(long v) { this.inputSizeBytes = v; return this; }
        public Builder inputFormat(InputFormat v) { this.inputFormat = v; return this; }
        public Builder mappingDocument(Path v) { this.mappingDocument = v; return this; }
        public Builder outputFormat(OutputFormat v) { this.outputFormat = v; return this; }
        public Builder baseIri(String v) { this.baseIri = v; return this; }
        public Builder workingDirectory(Path v) { this.workingDirectory = v; return this; }
        public Builder options(Map<String, String> v) { this.options = v; return this; }

        public MappingRequest build() { return new MappingRequest(this); }
    }
}
