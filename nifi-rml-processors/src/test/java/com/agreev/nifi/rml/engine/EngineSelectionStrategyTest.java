package com.agreev.nifi.rml.engine;

import com.agreev.nifi.rml.model.EngineMode;
import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;
import com.agreev.nifi.rml.model.MappingResult.SelectionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineSelectionStrategyTest {

    private RMLEngineRegistry registry;
    private RMLEngine rmlMapperStub;
    private FakeEngine morphAvailable;
    private FakeEngine morphUnavailable;

    @BeforeEach
    void setUp() {
        registry = new RMLEngineRegistry();
        rmlMapperStub = new RMLMapperEngine();
        registry.register(rmlMapperStub);

        morphAvailable = new FakeEngine(MorphKGCEngine.ID, true);
        morphUnavailable = new FakeEngine(MorphKGCEngine.ID, false);
    }

    @Test
    void strictRmlmapperReturnsRmlEngine() {
        var s = new EngineSelectionStrategy(registry, EngineMode.RMLMAPPER);
        var sel = s.select(EngineMode.RMLMAPPER, 100, 1024);
        assertThat(sel.engine().id()).isEqualTo(RMLMapperEngine.ID);
        assertThat(sel.reason()).isEqualTo(SelectionReason.STRICT);
    }

    @Test
    void strictMorphReturnsMorphEngine() {
        registry.register(morphAvailable);
        var s = new EngineSelectionStrategy(registry, EngineMode.RMLMAPPER);
        var sel = s.select(EngineMode.MORPH_KGC, 100, 1024);
        assertThat(sel.engine().id()).isEqualTo(MorphKGCEngine.ID);
        assertThat(sel.reason()).isEqualTo(SelectionReason.STRICT);
    }

    @Test
    void autoBelowThresholdPicksRmlMapper() {
        registry.register(morphAvailable);
        var s = new EngineSelectionStrategy(registry, EngineMode.RMLMAPPER);
        var sel = s.select(EngineMode.AUTO, 1024, 4096);
        assertThat(sel.engine().id()).isEqualTo(RMLMapperEngine.ID);
        assertThat(sel.reason()).isEqualTo(SelectionReason.AUTO_BELOW_THRESHOLD);
    }

    @Test
    void autoAboveThresholdPicksMorphWhenAvailable() {
        registry.register(morphAvailable);
        var s = new EngineSelectionStrategy(registry, EngineMode.RMLMAPPER);
        var sel = s.select(EngineMode.AUTO, 8192, 4096);
        assertThat(sel.engine().id()).isEqualTo(MorphKGCEngine.ID);
        assertThat(sel.reason()).isEqualTo(SelectionReason.AUTO_ABOVE_THRESHOLD);
    }

    @Test
    void autoAboveThresholdFallsBackToRmlMapperWhenMorphUnavailable() {
        registry.register(morphUnavailable);
        var s = new EngineSelectionStrategy(registry, EngineMode.RMLMAPPER);
        var sel = s.select(EngineMode.AUTO, 8192, 4096);
        assertThat(sel.engine().id()).isEqualTo(RMLMapperEngine.ID);
        assertThat(sel.reason()).isEqualTo(SelectionReason.AUTO_FALLBACK);
    }

    @Test
    void autoConstructionRejectsAutoFallback() {
        assertThatThrownBy(() -> new EngineSelectionStrategy(registry, EngineMode.AUTO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class FakeEngine implements RMLEngine {
        private final String id;
        private final boolean available;

        FakeEngine(String id, boolean available) {
            this.id = id;
            this.available = available;
        }

        @Override public String id() { return id; }
        @Override public boolean isAvailable() { return available; }
        @Override public MappingResult execute(MappingRequest r) { throw new UnsupportedOperationException(); }
    }
}
