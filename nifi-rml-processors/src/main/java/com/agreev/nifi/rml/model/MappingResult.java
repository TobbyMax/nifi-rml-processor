package com.agreev.nifi.rml.model;

import java.nio.file.Path;
import java.util.Objects;

public final class MappingResult {

    public enum SelectionReason {
        STRICT,
        AUTO_BELOW_THRESHOLD,
        AUTO_ABOVE_THRESHOLD,
        AUTO_FALLBACK
    }

    private final Path output;
    private final OutputFormat outputFormat;
    private final long durationMillis;
    private final long triplesCount;
    private final String engineId;
    private final SelectionReason selectionReason;

    private MappingResult(Builder b) {
        this.output = Objects.requireNonNull(b.output, "output");
        this.outputFormat = Objects.requireNonNull(b.outputFormat, "outputFormat");
        this.durationMillis = b.durationMillis;
        this.triplesCount = b.triplesCount;
        this.engineId = Objects.requireNonNull(b.engineId, "engineId");
        this.selectionReason = b.selectionReason == null ? SelectionReason.STRICT : b.selectionReason;
    }

    public Path output() { return output; }
    public OutputFormat outputFormat() { return outputFormat; }
    public long durationMillis() { return durationMillis; }
    public long triplesCount() { return triplesCount; }
    public String engineId() { return engineId; }
    public SelectionReason selectionReason() { return selectionReason; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path output;
        private OutputFormat outputFormat;
        private long durationMillis;
        private long triplesCount;
        private String engineId;
        private SelectionReason selectionReason;

        public Builder output(Path v) { this.output = v; return this; }
        public Builder outputFormat(OutputFormat v) { this.outputFormat = v; return this; }
        public Builder durationMillis(long v) { this.durationMillis = v; return this; }
        public Builder triplesCount(long v) { this.triplesCount = v; return this; }
        public Builder engineId(String v) { this.engineId = v; return this; }
        public Builder selectionReason(SelectionReason v) { this.selectionReason = v; return this; }

        public MappingResult build() { return new MappingResult(this); }
    }
}
