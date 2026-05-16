package com.agreev.nifi.rml.engine.rml;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class JsonRecordSource implements Iterable<RecordView>, AutoCloseable {

    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();
    private static final JsonFactory STREAM_FACTORY = new JsonFactory().setCodec(STREAM_MAPPER);

    private final List<Object> bufferedRecords;
    private final JsonParser streamingParser;
    private final boolean streaming;

    public JsonRecordSource(Path inputPath, String iterator) throws IOException {
        if (!Files.exists(inputPath)) {
            throw new IOException("Input JSON file does not exist: " + inputPath.toAbsolutePath());
        }
        if (!Files.isReadable(inputPath)) {
            throw new IOException("Input JSON file is not readable: " + inputPath.toAbsolutePath());
        }

        try {
            if (isStreamingIterator(iterator)) {
                JsonParser parser = STREAM_FACTORY.createParser(Files.newInputStream(inputPath));
                JsonToken first = parser.nextToken();
                if (first == JsonToken.START_ARRAY) {
                    this.streamingParser = parser;
                    this.streaming = true;
                    this.bufferedRecords = null;
                    System.err.println("JSON record source: streaming mode, iterator=" + (iterator == null ? "$" : iterator));
                    return;
                }
                System.err.println("JSON record source: non-array root detected, switching to eager mode");
                JsonNode root = parser.readValueAsTree();
                parser.close();
                this.streamingParser = null;
                this.streaming = false;
                this.bufferedRecords = Collections.singletonList(STREAM_MAPPER.convertValue(root, Object.class));
                return;
            }
            try (InputStream in = Files.newInputStream(inputPath)) {
                this.bufferedRecords = readEager(in, iterator);
                System.err.printf("JSON record source: buffered mode, iterator=%s, records=%d%n",
                    (iterator == null || iterator.isEmpty() ? "$" : iterator),
                    this.bufferedRecords.size());
            }
            this.streaming = false;
            this.streamingParser = null;
        } catch (IOException e) {
            throw new IOException(
                String.format("Failed to read JSON input %s with iterator '%s': %s",
                    inputPath.toAbsolutePath(), (iterator == null ? "$" : iterator), e.getMessage()),
                e);
        }
    }

    public JsonRecordSource(InputStream in, String iterator) throws IOException {
        this.bufferedRecords = readEager(in, iterator);
        this.streaming = false;
        this.streamingParser = null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readEager(InputStream in, String iterator) throws IOException {
        try {
            String expr = iterator == null || iterator.isEmpty() ? "$" : iterator;
            ReadContext ctx = JsonPath.using(Configuration.defaultConfiguration()).parse(in);
            Object result = ctx.read(expr);

            if (result == null) {
                System.err.printf("Warning: JSONPath expression '%s' returned null%n", expr);
                return Collections.emptyList();
            }

            if (result instanceof List<?> list) {
                System.err.printf("JSONPath '%s' matched %d items%n", expr, list.size());
                return new ArrayList<>(list);
            }

            System.err.printf("JSONPath '%s' returned single item of type %s%n", expr, result.getClass().getSimpleName());
            List<Object> single = new ArrayList<>(1);
            single.add(result);
            return single;
        } catch (Exception e) {
            throw new IOException(
                String.format("Failed to parse JSON with iterator '%s': %s",
                    (iterator == null || iterator.isEmpty() ? "$" : iterator), e.getMessage()),
                e);
        }
    }

    private static boolean isStreamingIterator(String iterator) {
        if (iterator == null) {
            return true;
        }
        String trimmed = iterator.trim();
        return trimmed.isEmpty() || "$".equals(trimmed) || "$[*]".equals(trimmed);
    }

    @Override
    public Iterator<RecordView> iterator() {
        if (streaming) {
            return new StreamingIterator(streamingParser);
        }
        return new BufferedIterator(bufferedRecords);
    }

    @Override
    public void close() throws IOException {
        if (streamingParser != null) {
            streamingParser.close();
        }
    }

    private static final class BufferedIterator implements Iterator<RecordView> {
        private final List<Object> records;
        private int idx;

        BufferedIterator(List<Object> records) {
            this.records = records;
        }

        @Override public boolean hasNext() { return idx < records.size(); }

        @Override
        public RecordView next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return new JsonRecordView(records.get(idx++));
        }
    }

    private static final class StreamingIterator implements Iterator<RecordView> {
        private final JsonParser parser;
        private JsonNode pending;
        private boolean done;

        StreamingIterator(JsonParser parser) {
            this.parser = parser;
        }

        @Override
        public boolean hasNext() {
            if (done) {
                return false;
            }
            if (pending != null) {
                return true;
            }
            try {
                JsonToken t = parser.nextToken();
                if (t == null || t == JsonToken.END_ARRAY) {
                    done = true;
                    return false;
                }
                pending = STREAM_MAPPER.readTree(parser);
                return true;
            } catch (IOException e) {
                done = true;
                throw new IllegalStateException("JSON streaming read failed", e);
            }
        }

        @Override
        public RecordView next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            JsonNode node = pending;
            pending = null;
            return new JsonRecordView(STREAM_MAPPER.convertValue(node, Object.class));
        }
    }

    private static final class JsonRecordView implements RecordView {
        private final Object record;

        JsonRecordView(Object record) {
            this.record = record;
        }

        @Override
        public String get(String reference) {
            if (record == null) {
                System.err.println("Warning: JSON record is null");
                return null;
            }
            if (reference == null || reference.isEmpty()) {
                System.err.println("Warning: Empty or null reference in JSON evaluation");
                return null;
            }

            try {
                if (reference.startsWith("$") || reference.contains(".") || reference.contains("[")) {
                    Object value = JsonPath.read(record, reference);
                    return value == null ? null : value.toString();
                }
                if (record instanceof Map<?, ?> map) {
                    Object v = map.get(reference);
                    if (v == null) {
                        System.err.printf("Warning: Reference '%s' not found in JSON record. Available keys: %s%n",
                            reference, map.keySet());
                    }
                    return v == null ? null : v.toString();
                }
                System.err.printf("Warning: Cannot evaluate reference '%s' on non-Map JSON record of type %s%n",
                    reference, record.getClass().getSimpleName());
                return null;
            } catch (Exception e) {
                System.err.printf("Error evaluating JSON reference '%s': %s%n", reference, e.getMessage());
                return null;
            }
        }
    }
}
