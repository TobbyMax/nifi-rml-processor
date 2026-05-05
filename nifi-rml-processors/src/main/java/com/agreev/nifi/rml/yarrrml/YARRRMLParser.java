package com.agreev.nifi.rml.yarrrml;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YARRRMLParser {

    private static final Pattern VAR = Pattern.compile("\\$\\(([^)]+)\\)");

    public String parse(String yarrrmlYaml) {
        if (yarrrmlYaml == null || yarrrmlYaml.isBlank()) {
            throw new IllegalArgumentException("YARRRML document is empty");
        }
        return parse(new StringReader(yarrrmlYaml));
    }

    @SuppressWarnings("unchecked")
    public String parse(Reader reader) {
        Yaml yaml = new Yaml();
        Object parsed = yaml.load(reader);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("YARRRML root must be a YAML mapping");
        }

        Map<String, String> prefixes = readPrefixes((Map<String, Object>) root.get("prefixes"));
        Map<String, Map<String, Object>> sources =
            readSources((Map<String, Object>) root.get("sources"));
        Map<String, Object> mappings = (Map<String, Object>) root.get("mappings");

        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("YARRRML document has no mappings");
        }

        StringBuilder ttl = new StringBuilder(1024);
        ttl.append("@prefix rr:  <http://www.w3.org/ns/r2rml#> .\n");
        ttl.append("@prefix rml: <http://semweb.mmlab.be/ns/rml#> .\n");
        ttl.append("@prefix ql:  <http://semweb.mmlab.be/ns/ql#> .\n");
        for (Map.Entry<String, String> entry : prefixes.entrySet()) {
            ttl.append("@prefix ").append(entry.getKey()).append(": <")
               .append(entry.getValue()).append("> .\n");
        }
        ttl.append('\n');

        for (Map.Entry<String, Object> e : mappings.entrySet()) {
            renderMapping(e.getKey(), (Map<String, Object>) e.getValue(), sources, prefixes, ttl);
        }
        return ttl.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readPrefixes(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        Map<String, String> r = new LinkedHashMap<>(raw.size());
        raw.forEach((k, v) -> r.put(k, v.toString()));
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readSources(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        Map<String, Map<String, Object>> r = new LinkedHashMap<>(raw.size());
        raw.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) {
                r.put(k, (Map<String, Object>) m);
            }
        });
        return r;
    }

    private void renderMapping(String name,
                               Map<String, Object> mapping,
                               Map<String, Map<String, Object>> sources,
                               Map<String, String> prefixes,
                               StringBuilder ttl) {
        Object sourcesAttr = mapping.get("sources");
        Map<String, Object> source = resolveSource(sourcesAttr, sources);

        ttl.append("<#").append(name).append("Map>\n");
        ttl.append("  rml:logicalSource [\n");
        ttl.append("    rml:source              \"")
           .append(stringValue(source.get("access"))).append("\" ;\n");
        String refForm = stringValue(source.get("referenceFormulation"));
        if (refForm != null && !refForm.isEmpty()) {
            ttl.append("    rml:referenceFormulation ql:")
               .append(canonicalReferenceFormulation(refForm)).append(" ;\n");
        }
        Object iterator = source.get("iterator");
        if (iterator != null) {
            ttl.append("    rml:iterator            \"").append(iterator).append("\" ;\n");
        }
        // strip trailing semicolon and close
        trimTrailingSemicolon(ttl);
        ttl.append("\n  ] ;\n");

        Object subject = mapping.get("s");
        if (subject == null) {
            subject = mapping.get("subject");
        }
        ttl.append("  rr:subjectMap [\n");
        ttl.append("    rr:template \"").append(toTemplateIri(subject.toString(), prefixes)).append("\"");

        Object types = mapping.get("po") != null ? extractTypes((List<?>) mapping.get("po")) : null;
        if (types instanceof List<?> typeList && !typeList.isEmpty()) {
            for (Object t : typeList) {
                ttl.append(" ;\n    rr:class    ").append(expandPrefix(t.toString(), prefixes));
            }
        }
        ttl.append("\n  ]");

        Object po = mapping.get("po");
        if (po instanceof List<?> entries) {
            for (Object item : entries) {
                if (item instanceof List<?> pair) {
                    String predicate = pair.get(0).toString();
                    if ("a".equals(predicate)) continue;
                    String object = pair.size() > 1 ? pair.get(1).toString() : "";
                    ttl.append(" ;\n  rr:predicateObjectMap [\n");
                    ttl.append("    rr:predicate ").append(expandPrefix(predicate, prefixes)).append(" ;\n");
                    ttl.append("    rr:objectMap [ ");
                    if (looksLikeReference(object)) {
                        ttl.append("rml:reference \"").append(extractReference(object)).append("\"");
                    } else if (object.contains("$(")) {
                        ttl.append("rr:template \"").append(toTemplateIri(object, prefixes)).append("\"");
                    } else {
                        ttl.append("rr:constant \"").append(object).append("\"");
                    }
                    ttl.append(" ]\n  ]");
                }
            }
        }
        ttl.append(" .\n\n");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveSource(Object sourcesAttr,
                                              Map<String, Map<String, Object>> sources) {
        if (sourcesAttr instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String key) {
                Map<String, Object> resolved = sources.get(key);
                if (resolved == null) {
                    throw new IllegalArgumentException("Unknown source: " + key);
                }
                return resolved;
            }
            if (first instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        if (sourcesAttr instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (sourcesAttr instanceof String key) {
            Map<String, Object> resolved = sources.get(key);
            if (resolved == null) {
                throw new IllegalArgumentException("Unknown source: " + key);
            }
            return resolved;
        }
        throw new IllegalArgumentException("Mapping has no recognized 'sources' attribute");
    }

    private static List<Object> extractTypes(List<?> po) {
        List<Object> types = new ArrayList<>();
        for (Object item : po) {
            if (item instanceof List<?> pair && !pair.isEmpty() && "a".equals(pair.get(0).toString())) {
                if (pair.size() > 1) {
                    Object value = pair.get(1);
                    if (value instanceof List<?> typeList) {
                        types.addAll(typeList);
                    } else {
                        types.add(value);
                    }
                }
            }
        }
        return Collections.unmodifiableList(types);
    }

    private static String canonicalReferenceFormulation(String value) {
        switch (value.trim().toLowerCase()) {
            case "jsonpath": return "JSONPath";
            case "xpath":    return "XPath";
            case "csv":      return "CSV";
            default:         return value;
        }
    }

    private static String toTemplateIri(String value, Map<String, String> prefixes) {
        String expanded = expandPrefixInTemplate(value, prefixes);
        Matcher m = VAR.matcher(expanded);
        return m.replaceAll(mr -> Matcher.quoteReplacement("{" + mr.group(1) + "}"));
    }

    private static String expandPrefixInTemplate(String value, Map<String, String> prefixes) {
        int colon = value.indexOf(':');
        if (colon <= 0) return value;
        String prefix = value.substring(0, colon);
        String suffix = value.substring(colon + 1);
        String iri = prefixes.get(prefix);
        if (iri == null) return value;
        return iri + suffix;
    }

    private static String expandPrefix(String value, Map<String, String> prefixes) {
        if ("a".equals(value)) {
            return "rdf:type";
        }
        int colon = value.indexOf(':');
        if (colon <= 0) return "<" + value + ">";
        String prefix = value.substring(0, colon);
        String suffix = value.substring(colon + 1);
        String iri = prefixes.get(prefix);
        if (iri == null) return "<" + value + ">";
        return "<" + iri + suffix + ">";
    }

    private static boolean looksLikeReference(String value) {
        if (value == null) return false;
        Matcher m = VAR.matcher(value);
        return m.matches();
    }

    private static String extractReference(String value) {
        Matcher m = VAR.matcher(value);
        if (m.matches()) {
            return m.group(1);
        }
        return value;
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static void trimTrailingSemicolon(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            char ch = sb.charAt(i);
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue;
            if (ch == ';') {
                sb.deleteCharAt(i);
            }
            break;
        }
    }
}
