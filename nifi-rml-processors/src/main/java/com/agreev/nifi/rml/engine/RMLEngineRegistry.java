package com.agreev.nifi.rml.engine;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RMLEngineRegistry implements AutoCloseable {

    private final Map<String, RMLEngine> engines = new LinkedHashMap<>();

    public synchronized RMLEngineRegistry register(RMLEngine engine) {
        engines.put(engine.id(), engine);
        return this;
    }

    public synchronized RMLEngine findById(String id) {
        return engines.get(id);
    }

    public synchronized RMLEngine requireById(String id) {
        RMLEngine engine = engines.get(id);
        if (engine == null) {
            throw new IllegalArgumentException("RML engine not registered: " + id);
        }
        return engine;
    }

    public synchronized boolean isAvailable(String id) {
        RMLEngine engine = engines.get(id);
        return engine != null && engine.isAvailable();
    }

    @Override
    public synchronized void close() {
        engines.values().forEach(e -> {
            try {
                e.close();
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        });
        engines.clear();
    }
}
