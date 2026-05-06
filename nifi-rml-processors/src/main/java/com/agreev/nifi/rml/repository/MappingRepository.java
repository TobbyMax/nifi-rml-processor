package com.agreev.nifi.rml.repository;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves mapping documents from external repositories: HTTP(S) URLs, classpath resources,
 * or any Java {@code java.net} schema. Caches resolved bodies on disk for the lifetime of
 * the processor instance to avoid hitting the remote on every FlowFile.
 */
public final class MappingRepository {

    private final HttpClient httpClient;
    private final Path cacheDirectory;
    private final Duration cacheTtl;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    public MappingRepository(Path cacheDirectory, Duration cacheTtl) {
        this.cacheDirectory = cacheDirectory;
        this.cacheTtl = cacheTtl == null ? Duration.ofMinutes(10) : cacheTtl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public String fetch(String urlOrUri) throws IOException {
        if (urlOrUri == null || urlOrUri.isBlank()) {
            throw new IOException("Mapping URL is empty");
        }

        long now = System.currentTimeMillis();
        CachedEntry cached = cache.get(urlOrUri);
        if (cached != null && (now - cached.timestamp) < cacheTtl.toMillis()) {
            return cached.body;
        }

        String body = fetchUncached(urlOrUri);
        cache.put(urlOrUri, new CachedEntry(body, now));
        writeCacheFile(urlOrUri, body);
        return body;
    }

    private String fetchUncached(String urlOrUri) throws IOException {
        URI uri = URI.create(urlOrUri);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IOException("Mapping URL has no scheme: " + urlOrUri);
        }
        if (scheme.startsWith("http")) {
            return fetchHttp(uri);
        }
        if ("file".equals(scheme)) {
            return Files.readString(Path.of(uri), StandardCharsets.UTF_8);
        }
        if ("classpath".equals(scheme)) {
            return fetchClasspath(uri);
        }
        // Fallback: any URLConnection-supported scheme.
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String fetchHttp(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Mapping repository responded with HTTP "
                    + response.statusCode() + " for " + uri);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Mapping fetch interrupted", e);
        }
    }

    private String fetchClasspath(URI uri) throws IOException {
        String path = uri.getSchemeSpecificPart();
        if (path.startsWith("//")) {
            path = path.substring(2);
        }
        try (InputStream in = MappingRepository.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Classpath resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void writeCacheFile(String urlOrUri, String body) {
        if (cacheDirectory == null) {
            return;
        }
        try {
            Files.createDirectories(cacheDirectory);
            Path target = cacheDirectory.resolve(stableHash(urlOrUri) + ".ttl");
            Files.writeString(target, body, StandardCharsets.UTF_8);
        } catch (IOException ignore) {
            // Cache is best-effort.
        }
    }

    private static String stableHash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public void invalidate(String urlOrUri) {
        cache.remove(urlOrUri);
    }

    public void clearCache() {
        cache.clear();
    }

    private record CachedEntry(String body, long timestamp) { }
}
