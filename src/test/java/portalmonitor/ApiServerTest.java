package portalmonitor;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real API server, with a database that cannot be reached - the production situation.
 * Liveness must stay UP; readiness, the healthcheck and the agent must all see the problem.
 */
class ApiServerTest {

    private static final String TOKEN = "test-token-0123456789abcdef";
    private static AgentApiServer api;
    private static MonitorService monitor;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        int apiPort = freePort();
        int deadDbPort = freePort();   // nothing listens here: connection refused
        Properties p = new Properties();
        p.setProperty("db.host", "127.0.0.1");
        p.setProperty("db.port", String.valueOf(deadDbPort));
        p.setProperty("db.user", "portal_monitor");
        p.setProperty("db.password", "irrelevant");
        p.setProperty("smtp.host", "127.0.0.1");
        p.setProperty("smtp.from", "monitor@example.com");
        p.setProperty("server.port", String.valueOf(apiPort));
        p.setProperty("api.token", TOKEN);
        Config cfg = new Config(p);
        monitor = new MonitorService(cfg);
        api = new AgentApiServer(cfg, monitor);
        api.start();
        base = "http://127.0.0.1:" + apiPort;
    }

    @AfterAll
    static void stop() {
        api.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (token != null) b.header("X-Agent-Token", token);
        return http.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) b.header("X-Agent-Token", token);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("liveness: /health stays UP (unchanged response) even with the database down")
    void livenessIgnoresDatabase() throws Exception {
        HttpResponse<String> r = get("/health", null);
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"UP\"}", r.body());
        assertEquals(200, get("/health/live", null).statusCode());
        assertEquals(404, get("/healthz", null).statusCode(), "no accidental prefix matches");
    }

    @Test
    @DisplayName("readiness: /ready is 503 NOT_READY with the database reason")
    void readinessSeesDatabase() throws Exception {
        HttpResponse<String> r = get("/ready", null);
        assertEquals(503, r.statusCode());
        JSONObject j = new JSONObject(r.body());
        assertEquals("NOT_READY", j.getString("status"));
        assertEquals("DOWN", j.getJSONObject("database").getString("status"));
        assertTrue(j.getJSONObject("database").getString("error").contains("Nothing accepted the connection"));
        assertTrue(j.getJSONArray("reasons").length() >= 1);
        assertEquals(503, get("/health/ready", null).statusCode(), "alias");
        assertTrue(!r.body().contains("irrelevant"), "never shows the password");
    }

    @Test
    @DisplayName("the Docker healthcheck probe fails on NOT_READY and passes on liveness")
    void healthProbe() {
        assertEquals(1, HealthProbe.probe(base + "/ready", Duration.ofSeconds(20)));
        assertEquals(0, HealthProbe.probe(base + "/health", Duration.ofSeconds(5)));
        assertEquals(2, HealthProbe.probe("http://127.0.0.1:1/ready", Duration.ofSeconds(5)),
                "no server at all");
    }

    @Test
    @DisplayName("agent authentication: missing or wrong token is 401, wrong method 405")
    void authentication() throws Exception {
        String body = "{\"agentId\":\"a1\",\"hostname\":\"h\",\"containers\":[]}";
        assertEquals(401, post("/api/agent/report", null, body).statusCode());
        assertEquals(401, post("/api/agent/report", "wrong-token-0123456789", body).statusCode());
        assertEquals(401, get("/api/status", null).statusCode());
        assertEquals(405, get("/api/agent/report", TOKEN).statusCode());
        assertEquals(400, post("/api/agent/report", TOKEN, "not json").statusCode());
        assertEquals(400, post("/api/agent/report", TOKEN, "{\"containers\":[]}").statusCode(),
                "agentId is required");
    }

    @Test
    @DisplayName("agent reporting: with the database down the agent gets 503, but its heartbeat is recorded")
    void reportWhileDatabaseDown() throws Exception {
        get("/ready", null);   // makes sure the database has been probed and found DOWN
        HttpResponse<String> r = post("/api/agent/report", TOKEN,
                "{\"agentId\":\"win-01\",\"hostname\":\"WIN\",\"containers\":"
                        + "[{\"name\":\"tms-backend\",\"state\":\"running\",\"status\":\"Up 1 hour\"}]}");
        assertEquals(503, r.statusCode());
        assertTrue(r.body().contains("database unavailable"));

        JSONObject ready = new JSONObject(get("/ready", null).body());
        assertTrue(ready.getJSONObject("agentWatchdog").getJSONObject("lastReportReceived").has("win-01"),
                "the report still counts as a heartbeat");
        assertEquals(503, get("/api/status", TOKEN).statusCode(), "status says why instead of a 500");
    }
}
