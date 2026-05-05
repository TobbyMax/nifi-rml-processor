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

public final class CsvRecordSource implements Iterable<RecordView> {

    private final List<String> header;
    private final List<List<String>> rows;

    public CsvRecordSource(Path inputPath) throws IOException {
        try (InputStream in = Files.newInputStream(inputPath)) {
            this.rows = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String headerLine = r.readLine();
                if (headerLine == null) {
                    throw new IOException("Empty CSV input: " + inputPath);
                }
                this.header = parseRow(headerLine);
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    rows.add(parseRow(line));
                }
            }
        }
    }

    public int size() {
        return rows.size();
    }

    @Override
    public Iterator<RecordView> iterator() {
        return new Iterator<>() {
            private int idx = 0;

            @Override public boolean hasNext() { return idx < rows.size(); }

            @Override
            public RecordView next() {
                List<String> row = rows.get(idx++);
                Map<String, String> map = new HashMap<>(header.size());
                for (int i = 0; i < header.size(); i++) {
                    map.put(header.get(i), i < row.size() ? row.get(i) : null);
                }
                return key -> map.get(key);
            }
        };
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
