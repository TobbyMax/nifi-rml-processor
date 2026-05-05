package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;

public final class RMLMapperEngine implements RMLEngine {

    public static final String ID = "RMLMAPPER";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        // The RML in-process engine is bundled with the NAR, so it is always available.
        return true;
    }

    @Override
    public MappingResult execute(MappingRequest request) throws RMLEngineException {
        throw new UnsupportedOperationException("RMLMapperEngine implementation pending");
    }
}
