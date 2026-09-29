package portalmonitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The server's small HTTP API, bound to 127.0.0.1 by default and published through nginx.
 *
 *   POST /api/agent/report   token-protected; the agent's container list
 *   GET  /api/status         token-protected; JSON of every check and agent
 *   GET  /health             open, so the monitor itself can be monitored
 */
public final class AgentApiServer {

    /** Reports larger than this are refused rather than buffered. */
    private static final int MAX_BODY_BYTES = 1_000_000;

    private final Config cfg;
    private final MonitorService monitor;
    private HttpServer server;
    private ExecutorService pool;

    public AgentApiServer(Config cfg, MonitorService monitor) {
        this.cfg = cfg;
        this.monitor = monitor;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(cfg.serverBind, cfg.serverPort), 0);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "api");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);

        server.createContext("/api/agent/report", this::handleReport);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/health", this::handleHealth);

        server.start();
        Log.info("API listening on http://" + cfg.serverBind + ":" + cfg.serverPort
                + " (/api/agent/report, /api/status, /health)");
    }

    public void stop() {
        if (server != null) server.stop(2);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ handlers

    private void handleReport(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                send(ex, 405, Json.message("error", "use POST"));
                return;
            }
            if (!authorised(ex)) {
                Log.error("Rejected /api/agent/report from " + ex.getRemoteAddress() + ": bad or missing token");
                send(ex, 401, Json.message("error", "unauthorized"));
                return;
            }

            String body = readBody(ex);
            if (body == null) {
                send(ex, 413, Json.message("error", "body too large"));
                return;
            }

            JSONObject json;
            List<ContainerReport> containers;
            String agentId, hostname;
            try {
                json = new JSONObject(body);
                agentId = json.optString("agentId", "").trim();
                hostname = json.optString("hostname", "").trim();
                containers = Json.parseContainers(json);
            } catch (Exception e) {
                Log.error("Malformed agent report: " + Text.oneLine(e.getMessage()));
                send(ex, 400, Json.message("error", "malformed json: " + e.getMessage()));
                return;
            }
            if (agentId.isEmpty()) {
                send(ex, 400, Json.message("error", "agentId is required"));
                return;
            }

            // Answer before the (slower) evaluation so the agent is never held up by
            // email sending; the work still happens under the monitor's own lock.
            send(ex, 200, Json.message("ok", "accepted " + containers.size() + " container(s)"));
            monitor.applyAgentReport(agentId, hostname.isEmpty() ? agentId : hostname, containers);
        } catch (Exception e) {
            // A handler must never leak an exception, or the connection just hangs.
            Log.error("Error handling /api/agent/report: " + e);
            trySend(ex, 500, Json.message("error", "internal error"));
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        try {
            if (!authorised(ex)) {
                send(ex, 401, Json.message("error", "unauthorized"));
                return;
            }
            send(ex, 200, monitor.statusJson());
        } catch (Exception e) {
            Log.error("Error handling /api/status: " + e);
            trySend(ex, 500, Json.message("error", "internal error"));
        }
    }

    /** Deliberately open and dependency-free: it answers even when MySQL is down. */
    private void handleHealth(HttpExchange ex) throws IOException {
        trySend(ex, 200, Json.message("status", "UP"));
    }

    // ------------------------------------------------------------------ helpers

    /** Accepts the token in X-Agent-Token or as a Bearer Authorization header. */
    private boolean authorised(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst("X-Agent-Token");
        if (token == null) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = auth.substring(7);
            }
        }
        return token != null && constantTimeEquals(token.trim(), cfg.apiToken);
    }

    /** Compares without leaking length or position through timing. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(x, y);
    }

    /** Returns null when the body exceeds MAX_BODY_BYTES. */
    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private static void trySend(HttpExchange ex, int code, String body) {
        try {
            send(ex, code, body);
        } catch (IOException ignored) {
            // the client has already gone away
        }
    }
}
