package portalmonitor;

import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent side of reporting and authentication, against a stub server. Plus watchdog timing. */
class AgentReportingTest {

    private static final String TOKEN = "agent-token-0123456789abcdef";
    private HttpServer stub;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> seenToken = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private String base;

    @BeforeEach
    void start() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            seenPath.set(ex.getRequestURI().getPath());
            seenToken.set(ex.getRequestHeaders().getFirst("X-Agent-Token"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (status.get() == 301) ex.getResponseHeaders().add("Location", "https://monitor.example.com/");
            byte[] out = "{\"ok\":\"x\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        stub.start();
        base = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        stub.stop(0);
    }

    private DockerAgent agent(String serverUrl) {
        Properties p = new Properties();
        p.setProperty("agent.serverUrl", serverUrl);
        p.setProperty("agent.token", TOKEN);
        p.setProperty("agent.id", "windows-docker-01");
        return new DockerAgent(new Config(p));
    }

    private static final List<ContainerReport> CONTAINERS = List.of(
            new ContainerReport("tms-backend", "tms:1", "running", "Up 2 hours (healthy)"),
            new ContainerReport("solar-db-backup", "x", "exited", "Exited (0) 1 hour ago"));

    @Test
    @DisplayName("a report carries the token and the container list to /api/agent/report")
    void reportShape() throws Exception {
        assertTrue(agent(base).post(CONTAINERS));
        assertEquals("/api/agent/report", seenPath.get(), "path appended to a base URL");
        assertEquals(TOKEN, seenToken.get());
        JSONObject body = new JSONObject(seenBody.get());
        assertEquals("windows-docker-01", body.getString("agentId"));
        assertEquals(2, body.getJSONArray("containers").length());
        assertEquals("tms-backend", body.getJSONArray("containers").getJSONObject(0).getString("name"));
    }

    @Test
    @DisplayName("the full endpoint URL is accepted as-is")
    void fullUrl() throws Exception {
        assertTrue(agent(base + "/api/agent/report").post(CONTAINERS));
        assertEquals("/api/agent/report", seenPath.get());
    }

    @Test
    @DisplayName("401, 301 and 503 answers are failures, never exceptions")
    void failuresAreReported() throws Exception {
        for (int code : new int[] {401, 301, 503}) {
            status.set(code);
            assertFalse(agent(base).post(CONTAINERS), "HTTP " + code + " must not count as delivered");
        }
    }

    // ---------------------------------------------------------------- offline detection timing

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 12, 9, 0, 0);

    @Test
    @DisplayName("watchdog: silence is measured from the last report")
    void quietFromLastReport() {
        LocalDateTime seen = START.plusMinutes(10);
        assertEquals(240, MonitorService.quietSeconds(seen, null, START, seen.plusMinutes(4)));
    }

    @Test
    @DisplayName("watchdog: a server restart gives the agent a fresh window (no false SERVER OFFLINE)")
    void restartGrace() {
        LocalDateTime staleSeen = START.minusHours(2);    // server was down for two hours
        assertEquals(60, MonitorService.quietSeconds(staleSeen, null, START, START.plusMinutes(1)));
    }

    @Test
    @DisplayName("watchdog: reports that arrived while MySQL was down still count as heartbeats")
    void heartbeatDuringDbOutage() {
        LocalDateTime seenInDb = START.plusMinutes(1);    // last stored report
        LocalDateTime received = START.plusMinutes(30);   // kept arriving, could not be stored
        assertEquals(30, MonitorService.quietSeconds(seenInDb, received, START, received.plusSeconds(30)));
    }
}
