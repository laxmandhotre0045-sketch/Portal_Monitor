package portalmonitor;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wire format between agent and server. */
class JsonTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 15, 10, 0, 0);

    @Test
    @DisplayName("an agent report round-trips through JSON unchanged")
    void reportRoundTrip() {
        List<ContainerReport> sent = List.of(
                new ContainerReport("senvia_uat_backend", "senvia_backend:uat", "running",
                        "Up 3 days (healthy)"),
                new ContainerReport("solar-db-backup", "solarproposalsystem-db-backup", "exited",
                        "Exited (0) 2 hours ago"));

        String body = Json.agentReport("windows-docker-01", "DESKTOP-DOCKER", T0, sent);
        JSONObject parsed = new JSONObject(body);

        assertEquals("windows-docker-01", parsed.getString("agentId"));
        assertEquals("DESKTOP-DOCKER", parsed.getString("hostname"));
        assertEquals("2026-01-15 10:00:00", parsed.getString("reportedAt"));

        List<ContainerReport> got = Json.parseContainers(parsed);
        assertEquals(sent, got, "every field must survive the round trip");
        assertTrue(got.get(0).toCheckResult().isUp());
        assertTrue(got.get(1).toCheckResult().isDown());
    }

    @Test
    @DisplayName("an empty container list is valid - it means docker reported nothing")
    void emptyReport() {
        String body = Json.agentReport("a", "h", T0, List.of());

        assertEquals(List.of(), Json.parseContainers(new JSONObject(body)));
    }

    @Test
    @DisplayName("a body with no containers array is rejected, not silently accepted")
    void missingContainersArray() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Json.parseContainers(new JSONObject("{\"agentId\":\"a\"}")));

        assertTrue(e.getMessage().contains("containers"), e.getMessage());
    }

    @Test
    @DisplayName("status JSON carries the totals and every check")
    void statusJson() {
        List<Portal> portals = List.of(
                new Portal(1, "tms-backend", "TMS", Portal.CheckType.CONTAINER, "tms-backend", null,
                        "a@b.com", Portal.DOWN, 4, T0, T0),
                new Portal(2, "HTTP TMS frontend", "TMS", Portal.CheckType.HTTP, null,
                        "http://1.2.3.4:6001/", "a@b.com", Portal.UP, 0, null, T0),
                new Portal(3, "emqx", "Infrastructure", Portal.CheckType.CONTAINER, "emqx", null,
                        "a@b.com", Portal.UNKNOWN, 0, null, null));

        List<Database.Agent> agents = List.of(
                new Database.Agent("windows-docker-01", "DESKTOP-DOCKER", T0, "ONLINE"));

        JSONObject o = new JSONObject(Json.status(portals, agents, T0.plusMinutes(1)));

        assertEquals("2026-01-15 10:01:00", o.getString("generatedAt"));
        assertEquals(3, o.getJSONObject("totals").getInt("checks"));
        assertEquals(1, o.getJSONObject("totals").getInt("up"));
        assertEquals(1, o.getJSONObject("totals").getInt("down"));
        assertEquals(1, o.getJSONObject("totals").getInt("unknown"));

        assertEquals(1, o.getJSONArray("agents").length());
        assertEquals("ONLINE", o.getJSONArray("agents").getJSONObject(0).getString("status"));

        JSONObject first = o.getJSONArray("checks").getJSONObject(0);
        assertEquals("tms-backend", first.getString("name"));
        assertEquals("CONTAINER", first.getString("checkType"));
        assertEquals("tms-backend", first.getString("target"), "target is the container name");
        assertEquals(4, first.getInt("failCount"));

        JSONObject second = o.getJSONArray("checks").getJSONObject(1);
        assertEquals("http://1.2.3.4:6001/", second.getString("target"), "target is the URL");
        assertTrue(second.isNull("failSince"), "a healthy check has no failSince");
    }

    @Test
    @DisplayName("status JSON never leaks recipient addresses")
    void statusDoesNotLeakEmails() {
        List<Portal> portals = List.of(new Portal(1, "x", "G", Portal.CheckType.CONTAINER, "x", null,
                "secret-recipient@example.com", Portal.UP, 0, null, T0));

        String json = Json.status(portals, List.of(), T0);

        assertTrue(!json.contains("secret-recipient"),
                "/api/status is read-only telemetry and should not expose who gets alerted");
    }
}
