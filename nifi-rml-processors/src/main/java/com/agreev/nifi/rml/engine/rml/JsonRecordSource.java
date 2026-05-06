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
        if (isStreamingIterator(iterator)) {
            this.streamingParser = STREAM_FACTORY.createParser(Files.newInputStream(inputPath));
            JsonToken first = streamingParser.nextToken();
            if (first == JsonToken.START_ARRAY) {
                this.streaming = true;
                this.bufferedRecords = null;
                return;
            }
            // Non-array root with a streaming-shaped iterator: read once eagerly via tree.
            JsonNode root = streamingParser.readValueAsTree();
            streamingParser.close();
            this.streamingParser = null;
            this.streaming = false;
            this.bufferedRecords = Collections.singletonList(STREAM_MAPPER.convertValue(root, Object.class));
            return;
        }
        try (InputStream in = Files.newInputStream(inputPath)) {
            this.bufferedRecords = readEager(in, iterator);
        }
        this.streaming = false;
        this.streamingParser = null;
    }

    public JsonRecordSource(InputStream in, String iterator) throws IOException {
        this.bufferedRecords = readEager(in, iterator);
        this.streaming = false;
        this.streamingParser = null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readEager(InputStream in, String iterator) throws IOException {
        ReadContext ctx = JsonPath.using(Configuration.defaultConfiguration()).parse(in);
        Object result = ctx.read(iterator == null || iterator.isEmpty() ? "$" : iterator);
        if (result instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        List<Object> single = new ArrayList<>(1);
        single.add(result);
        return single;
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
                return null;
            }
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
