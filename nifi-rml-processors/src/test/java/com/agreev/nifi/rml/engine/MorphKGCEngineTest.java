package com.agreev.nifi.rml.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MorphKGCEngineTest {

    @Test
    void availabilityIsFalseForUnknownCommand() {
        MorphKGCEngine engine = new MorphKGCEngine("morph-kgc-does-not-exist", 1_000L);
        assertThat(engine.isAvailable()).isFalse();
    }

    @Test
    void idIsExposedConsistently() {
        assertThat(new MorphKGCEngine().id()).isEqualTo(MorphKGCEngine.ID);
    }

    @Test
    void defaultsAreApplied() {
        MorphKGCEngine engine = new MorphKGCEngine(null, -1L);
        assertThat(engine.command()).isEqualTo("morph-kgc");
        assertThat(engine.executionTimeoutMillis()).isEqualTo(300_000L);
    }
}
