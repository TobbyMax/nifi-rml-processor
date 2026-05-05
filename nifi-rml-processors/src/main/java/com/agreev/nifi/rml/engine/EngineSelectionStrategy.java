package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.EngineMode;
import com.agreev.nifi.rml.model.MappingResult.SelectionReason;

import java.util.Objects;

public final class EngineSelectionStrategy {

    private final RMLEngineRegistry registry;
    private final EngineMode autoFallback;

    public EngineSelectionStrategy(RMLEngineRegistry registry, EngineMode autoFallback) {
        this.registry = Objects.requireNonNull(registry);
        this.autoFallback = autoFallback == null ? EngineMode.RMLMAPPER : autoFallback;
        if (this.autoFallback == EngineMode.AUTO) {
            throw new IllegalArgumentException("autoFallback must be a strict mode");
        }
    }

    public EngineSelection select(EngineMode mode, long inputSizeBytes, long autoThresholdBytes) {
        switch (mode) {
            case RMLMAPPER:
                return new EngineSelection(registry.requireById(RMLMapperEngine.ID), SelectionReason.STRICT);
            case MORPH_KGC:
                return new EngineSelection(registry.requireById(MorphKGCEngine.ID), SelectionReason.STRICT);
            case AUTO:
                if (inputSizeBytes <= autoThresholdBytes) {
                    return new EngineSelection(
                        registry.requireById(RMLMapperEngine.ID),
                        SelectionReason.AUTO_BELOW_THRESHOLD);
                }
                RMLEngine morph = registry.findById(MorphKGCEngine.ID);
                if (morph != null && morph.isAvailable()) {
                    return new EngineSelection(morph, SelectionReason.AUTO_ABOVE_THRESHOLD);
                }
                if (autoFallback == EngineMode.RMLMAPPER) {
                    return new EngineSelection(
                        registry.requireById(RMLMapperEngine.ID),
                        SelectionReason.AUTO_FALLBACK);
                }
                throw new IllegalStateException(
                    "Morph-KGC unavailable in AUTO mode and fallback disabled");
            default:
                throw new IllegalArgumentException("Unsupported engine mode: " + mode);
        }
    }
}
