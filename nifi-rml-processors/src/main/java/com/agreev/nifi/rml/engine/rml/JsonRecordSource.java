package com.agreev.nifi.rml.engine.rml;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonRecordSource implements Iterable<RecordView> {

    private final List<Object> records;

    public JsonRecordSource(Path inputPath, String iterator) throws IOException {
        try (InputStream in = Files.newInputStream(inputPath)) {
            this.records = readRecords(in, iterator);
        }
    }

    public JsonRecordSource(InputStream in, String iterator) throws IOException {
        this.records = readRecords(in, iterator);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readRecords(InputStream in, String iterator) throws IOException {
        ReadContext ctx = JsonPath.using(Configuration.defaultConfiguration()).parse(in);
        Object result = ctx.read(iterator == null || iterator.isEmpty() ? "$" : iterator);
        if (result instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        List<Object> single = new ArrayList<>(1);
        single.add(result);
        return single;
    }

    public int size() {
        return records.size();
    }

    @Override
    public Iterator<RecordView> iterator() {
        return new Iterator<>() {
            private int idx = 0;

            @Override public boolean hasNext() { return idx < records.size(); }

            @Override
            public RecordView next() {
                Object record = records.get(idx++);
                return new JsonRecordView(record);
            }
        };
    }

    private static final class JsonRecordView implements RecordView {
        private final Object record;

        JsonRecordView(Object record) {
            this.record = record;
        }

        @Override
        public String get(String reference) {
            if (record == null) {
                return null;
            }
            // Bare field name → direct map lookup. JSONPath expression → evaluate.
            if (reference.startsWith("$") || reference.contains(".") || reference.contains("[")) {
                Object value = JsonPath.read(record, reference);
                return value == null ? null : value.toString();
            }
            if (record instanceof Map<?, ?> map) {
                Object v = map.get(reference);
                return v == null ? null : v.toString();
            }
            return null;
        }
    }
}
