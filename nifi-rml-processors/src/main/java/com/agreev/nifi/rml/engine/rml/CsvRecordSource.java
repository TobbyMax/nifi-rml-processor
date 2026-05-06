package com.agreev.nifi.rml.engine.rml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public final class CsvRecordSource implements Iterable<RecordView>, AutoCloseable {

    private final BufferedReader reader;
    private final List<String> header;

    public CsvRecordSource(Path inputPath) throws IOException {
        InputStream in = Files.newInputStream(inputPath);
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String headerLine = reader.readLine();
        if (headerLine == null) {
            reader.close();
            throw new IOException("Empty CSV input: " + inputPath);
        }
        this.header = parseRow(headerLine);
    }

    @Override
    public Iterator<RecordView> iterator() {
        return new LazyIterator();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private final class LazyIterator implements Iterator<RecordView> {
        private String pendingLine;
        private boolean exhausted;

        @Override
        public boolean hasNext() {
            if (exhausted) {
                return false;
            }
            if (pendingLine != null) {
                return true;
            }
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        pendingLine = line;
                        return true;
                    }
                }
                exhausted = true;
                return false;
            } catch (IOException e) {
                exhausted = true;
                throw new IllegalStateException("CSV read failed", e);
            }
        }

        @Override
        public RecordView next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            List<String> row = parseRow(pendingLine);
            pendingLine = null;
            Map<String, String> map = new HashMap<>(header.size());
            for (int i = 0; i < header.size(); i++) {
                map.put(header.get(i), i < row.size() ? row.get(i) : null);
            }
            return map::get;
        }
    }

    /**
     * Minimalistic CSV row parser supporting double-quoted values with embedded commas
     * and doubled quotes as escape sequence. No multi-line cells (acceptable subset for
     * the academic implementation of RML-CSV reference formulation).
     */
    static List<String> parseRow(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (ch == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }
}
