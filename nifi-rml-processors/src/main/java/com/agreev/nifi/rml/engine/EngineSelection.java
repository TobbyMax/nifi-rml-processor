package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.MappingResult.SelectionReason;

public record EngineSelection(RMLEngine engine, SelectionReason reason) {
}
