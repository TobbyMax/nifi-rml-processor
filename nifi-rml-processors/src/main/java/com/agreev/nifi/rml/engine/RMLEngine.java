package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;

public interface RMLEngine extends AutoCloseable {

    String id();

    boolean isAvailable();

    MappingResult execute(MappingRequest request) throws RMLEngineException;

    @Override
    default void close() { }
}
