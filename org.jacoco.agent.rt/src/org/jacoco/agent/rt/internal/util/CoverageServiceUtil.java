package org.jacoco.agent.rt.internal.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small helper to talk to the CodeCoverage Flask service (Java 8 compatible).
 *
 * Flask routes:
 *   POST /api/register/process
 *   POST /api/process/dump
 */
public final class CoverageServiceUtil {

    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private static final Pattern PROCESS_ID_PATTERN =
            Pattern.compile("\"process_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern OK_PATTERN =
            Pattern.compile("\"ok\"\\s*:\\s*(true|false)");

    private final URI baseUri;
    private final int timeoutMs;

    public CoverageServiceUtil(String endpoint) {
        this(endpoint, DEFAULT_TIMEOUT_MS);
    }

    /** @param timeoutMs connect+read timeout in milliseconds */
    public CoverageServiceUtil(String endpoint, int timeoutMs) {
        String baseUrl = "http://" + endpoint;
        Objects.requireNonNull(baseUrl, "baseUrl");
        this.baseUri = normalizeBaseUri(baseUrl);
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be > 0");
        this.timeoutMs = timeoutMs;
    }

    /**
     * Register a Java process.
     *
     * @return process_id (which is actually process.process_uid from the server)
     */
    public String registerJavaProcess(int envId,
                                      String processId,
                                      String hostname,
                                      int serverPort) throws IOException {
        RegisterJavaProcessRequest req = new RegisterJavaProcessRequest(
                envId,
                processId,
                hostname,
                serverPort
        );
        return registerJavaProcess(req);
    }

    public String registerJavaProcess(RegisterJavaProcessRequest req) throws IOException {
        Objects.requireNonNull(req, "req");
        req.validate();

        URI uri = baseUri.resolve("/api/register/process");
        String json = req.toJson();

        HttpResult res = postJson(uri, json);

        if (res.statusCode / 100 != 2) {
            throw new CoverageServiceException(
                    "registerJavaProcess failed: HTTP " + res.statusCode + " body=" + safeBody(res.body),
                    res.statusCode,
                    res.body
            );
        }

        return parseProcessId(res.body)
                .orElseThrow(() -> new CoverageServiceException(
                        "registerJavaProcess: missing process_id in response: " + safeBody(res.body),
                        res.statusCode,
                        res.body
                ));
    }

    public String dumpProcessCoverage(String processUid) throws IOException {
        Objects.requireNonNull(processUid, "processUid");
        processUid = processUid.trim();
        if (processUid.isEmpty()) {
            throw new IllegalArgumentException("processUid must be non-empty");
        }

        URI uri = baseUri.resolve("/api/coverage/process/dump");

        String json = "{"
                + "\"process_uid\":\"" + escapeJson(processUid) + "\""
                + "}";

        HttpResult res = postJson(uri, json);

        if (res.statusCode / 100 != 2) {
            throw new CoverageServiceException(
                    "dumpProcessCoverage failed: HTTP " + res.statusCode + " body=" + safeBody(res.body),
                    res.statusCode,
                    res.body
            );
        }
        return res.body;
    }

    /** Optional convenience if you only care about the boolean "ok" field in the result JSON. */
    public boolean dumpProcessCoverageOk(String processUid) throws IOException {
        String body = dumpProcessCoverage(processUid);
        return parseOk(body).orElse(false);
    }

    /**
     * Mirrors RegisterJavaProcessDto.
     * Note: processId should be the string value of your KnownProcess enum.
     */
    public static final class RegisterJavaProcessRequest {
        public final int envId;
        public final String processId;
        public final String kind;      // always "java"
        public final String hostname;
        public final int serverPort;

        public RegisterJavaProcessRequest(int envId, String processId, String hostname, int serverPort) {
            this.envId = envId;
            this.processId = processId;
            this.kind = "java";
            this.hostname = hostname;
            this.serverPort = serverPort;
        }

        public void validate() {
            if (envId <= 0) throw new IllegalArgumentException("envId must be > 0");
            if (isBlank(processId)) throw new IllegalArgumentException("processId must be non-empty");
            if (isBlank(hostname)) throw new IllegalArgumentException("hostname must be non-empty");
            if (serverPort <= 0 || serverPort > 65535) throw new IllegalArgumentException("serverPort out of range");
        }

        public String toJson() {
            // Keep it dependency-free (no Jackson/Gson).
            return "{"
                    + "\"env_id\":" + envId + ","
                    + "\"process_id\":\"" + escapeJson(processId) + "\","
                    + "\"kind\":\"java\","
                    + "\"hostname\":\"" + escapeJson(hostname) + "\","
                    + "\"server_port\":" + serverPort
                    + "}";
        }
    }

    // -------- HTTP (Java 8) --------

    private HttpResult postJson(URI uri, String json) throws IOException {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(json, "json");

        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");

        byte[] bytes = json.getBytes(UTF8);
        conn.setFixedLengthStreamingMode(bytes.length);

        OutputStream os = null;
        try {
            os = conn.getOutputStream();
            os.write(bytes);
            os.flush();

            int code = conn.getResponseCode();
            String body = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
            return new HttpResult(code, body);
        } finally {
            if (os != null) {
                try { os.close(); } catch (IOException ignore) {}
            }
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, UTF8));
            StringBuilder sb = new StringBuilder(256);
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            try { in.close(); } catch (IOException ignore) {}
            if (br != null) {
                try { br.close(); } catch (IOException ignore) {}
            }
        }
    }

    private static final class HttpResult {
        final int statusCode;
        final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    // -------- helpers --------

    private static URI normalizeBaseUri(String baseUrl) {
        String u = baseUrl.trim();
        return URI.create(u);
    }

    private static Optional<String> parseProcessId(String body) {
        if (body == null) return Optional.empty();
        Matcher m = PROCESS_ID_PATTERN.matcher(body);
        if (!m.find()) return Optional.empty();
        return Optional.ofNullable(m.group(1));
    }

    private static Optional<Boolean> parseOk(String body) {
        if (body == null) return Optional.empty();
        Matcher m = OK_PATTERN.matcher(body);
        if (!m.find()) return Optional.empty();
        return Optional.of(Boolean.valueOf(Boolean.parseBoolean(m.group(1))));
    }

    private static String escapeJson(String s) {
        // minimal escaping
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeBody(String body) {
        if (body == null) return "<null>";
        return body.length() > 2000 ? body.substring(0, 2000) + "...(truncated)" : body;
    }

    // -------- exception --------

    public static final class CoverageServiceException extends RuntimeException {
        public final int statusCode;
        public final String responseBody;

        public CoverageServiceException(String message, int statusCode, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }
    }
}
