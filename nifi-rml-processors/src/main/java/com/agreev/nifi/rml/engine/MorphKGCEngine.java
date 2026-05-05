package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;

public final class MorphKGCEngine implements RMLEngine {

    public static final String ID = "MORPH_KGC";

    private final String command;
    private final long executionTimeoutMillis;

    public MorphKGCEngine(String command, long executionTimeoutMillis) {
        this.command = command;
        this.executionTimeoutMillis = executionTimeoutMillis;
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
        return false;
    }

    @Override
    public MappingResult execute(MappingRequest request) throws RMLEngineException {
        throw new UnsupportedOperationException("MorphKGCEngine implementation pending");
    }

    public String command() {
        return command;
    }

    public long executionTimeoutMillis() {
        return executionTimeoutMillis;
    }
}
