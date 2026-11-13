package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplatesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 15, 10, 0, 0);

    private static Portal container(String name, String group) {
        return new Portal(1, name, group, Portal.CheckType.CONTAINER, name, null,
                "ops@example.com", Portal.DOWN, 3, T0, T0);
    }

    private static Portal http(String name, String group, String url) {
        return new Portal(2, name, group, Portal.CheckType.HTTP, null, url,
                "ops@example.com", Portal.DOWN, 3, T0, T0);
    }

    private static MonitorService.PendingAlert alert(Portal p, CheckResult r, String type) {
        return new MonitorService.PendingAlert(p, r, T0, 3, type);
    }

    // ---------------------------------------------------------------- downtime wording

    @Test
    @DisplayName("humanDuration reads the way a person would say it")
    void humanDuration() {
        assertEquals("0s", EmailTemplates.humanDuration(Duration.ZERO));
        assertEquals("45s", EmailTemplates.humanDuration(Duration.ofSeconds(45)));
        assertEquals("5m 30s", EmailTemplates.humanDuration(Duration.ofSeconds(330)));
        assertEquals("1h 12m", EmailTemplates.humanDuration(Duration.ofMinutes(72)));
        assertEquals("1h", EmailTemplates.humanDuration(Duration.ofHours(1)));
        assertEquals("2d 3h", EmailTemplates.humanDuration(Duration.ofHours(51)));
        assertEquals("3d", EmailTemplates.humanDuration(Duration.ofDays(3)));
        // a clock change or a hand-edited row could hand us a negative span
        assertEquals("0s", EmailTemplates.humanDuration(Duration.ofSeconds(-60)));
    }

    // ---------------------------------------------------------------- single alerts

    @Test
    @DisplayName("one DOWN check gets a named subject and every required field")
    void singleDownEmail() {
        EmailTemplates.Email e = EmailTemplates.stateChange("DOWN",
                List.of(alert(container("tms-backend", "TMS"),
                        CheckResult.down("Exited (1) 5 minutes ago"), "DOWN")),
                T0.plusMinutes(2));

        assertEquals("[Portal Monitor] DOWN: tms-backend", e.subject());
        for (String needed : new String[]{
                "tms-backend", "TMS", "CONTAINER", "Exited (1) 5 minutes ago",
                "2026-01-15 10:00:00", "2026-01-15 10:02:00", "Consecutive failures"}) {
            assertTrue(e.text().contains(needed), "plain text missing: " + needed);
            assertTrue(e.html().contains(needed), "html missing: " + needed);
        }
        assertTrue(e.html().contains("charset=\"UTF-8\""), "html must declare UTF-8");
    }

    @Test
    @DisplayName("one RECOVERED check reports response time and downtime")
    void singleRecoveredEmail() {
        Portal p = http("HTTP TMS frontend", "TMS", "http://203.0.113.45:6001/");
        CheckResult r = new CheckResult(CheckResult.Outcome.UP, 200, 87, "HTTP 200");

        EmailTemplates.Email e = EmailTemplates.stateChange("RECOVERED",
                List.of(new MonitorService.PendingAlert(p, r, T0, 0, "RECOVERED")),
                T0.plusMinutes(95));

        assertEquals("[Portal Monitor] RECOVERED: HTTP TMS frontend", e.subject());
        assertTrue(e.text().contains("87 ms"), "response time missing");
        assertTrue(e.text().contains("1h 35m"), "downtime missing:\n" + e.text());
        assertTrue(e.text().contains("http://203.0.113.45:6001/"));
    }

    @Test
    @DisplayName("a container alert shows no response-time row")
    void containerRecoveryHasNoResponseTime() {
        EmailTemplates.Email e = EmailTemplates.stateChange("RECOVERED",
                List.of(new MonitorService.PendingAlert(container("emqx", "Infrastructure"),
                        CheckResult.up("Up 1 second (healthy)"), T0, 0, "RECOVERED")),
                T0.plusMinutes(5));

        assertFalse(e.text().contains("Response time"), "containers have no response time");
        assertTrue(e.text().contains("Total downtime"));
    }

    // ---------------------------------------------------------------- combined alerts

    @Test
    @DisplayName("several DOWN checks become ONE email, counted and grouped")
    void combinedDownEmail() {
        // What a host reboot looks like: several containers across several app groups.
        EmailTemplates.Email e = EmailTemplates.stateChange("DOWN", List.of(
                alert(container("senvia_uat_backend", "Senvia UAT"),
                        CheckResult.down("Exited (137) 1 minute ago"), "DOWN"),
                alert(container("senvia_uat_archiver", "Senvia UAT"),
                        CheckResult.down("Exited (137) 1 minute ago"), "DOWN"),
                alert(container("tms-backend", "TMS"),
                        CheckResult.down("unhealthy - Up 2 minutes (unhealthy)"), "DOWN")),
                T0.plusMinutes(2));

        assertEquals("[Portal Monitor] 3 services DOWN", e.subject());

        // every check is named
        assertTrue(e.text().contains("senvia_uat_backend"));
        assertTrue(e.text().contains("senvia_uat_archiver"));
        assertTrue(e.text().contains("tms-backend"));

        // grouped by app_group, and each group appears once
        assertTrue(e.text().contains("== Senvia UAT =="));
        assertTrue(e.text().contains("== TMS =="));
        assertEquals(1, countOf(e.text(), "== Senvia UAT =="),
                "the two Senvia checks must share one group heading");
    }

    @Test
    @DisplayName("a combined email of two reads as 2 services")
    void combinedTwo() {
        EmailTemplates.Email e = EmailTemplates.stateChange("RECOVERED", List.of(
                new MonitorService.PendingAlert(container("a", "G"), CheckResult.up("Up 1s"), T0, 0, "RECOVERED"),
                new MonitorService.PendingAlert(container("b", "G"), CheckResult.up("Up 1s"), T0, 0, "RECOVERED")),
                T0.plusMinutes(10));

        assertEquals("[Portal Monitor] 2 services RECOVERED", e.subject());
        assertTrue(e.text().contains("10m"), "downtime missing:\n" + e.text());
    }

    @Test
    @DisplayName("a check with no app_group still appears, under Ungrouped")
    void ungrouped() {
        EmailTemplates.Email e = EmailTemplates.stateChange("DOWN",
                List.of(alert(container("orphan", null), CheckResult.down("Dead"), "DOWN")), T0);

        assertTrue(e.text().contains("Ungrouped"));
        assertTrue(e.text().contains("orphan"));
    }

    // ---------------------------------------------------------------- agent alerts

    @Test
    @DisplayName("SERVER OFFLINE explains that container status is unknown")
    void agentOfflineEmail() {
        EmailTemplates.Email e = EmailTemplates.agentAlert("AGENT_OFFLINE",
                "windows-docker-01", "DESKTOP-DOCKER", T0, T0.plusMinutes(4));

        assertEquals("[Portal Monitor] SERVER OFFLINE: DESKTOP-DOCKER", e.subject());
        assertTrue(e.text().contains("windows-docker-01"));
        assertTrue(e.text().contains("2026-01-15 10:00:00"), "last report time missing");
        assertTrue(e.text().toLowerCase().contains("unknown"),
                "must say container status is unknown:\n" + e.text());
    }

    @Test
    @DisplayName("SERVER BACK ONLINE reports the outage duration")
    void agentOnlineEmail() {
        EmailTemplates.Email e = EmailTemplates.agentAlert("AGENT_ONLINE",
                "windows-docker-01", "DESKTOP-DOCKER", T0, T0.plusMinutes(140));

        assertEquals("[Portal Monitor] SERVER BACK ONLINE: DESKTOP-DOCKER", e.subject());
        assertTrue(e.text().contains("2h 20m"), "outage duration missing:\n" + e.text());
    }

    @Test
    @DisplayName("a blank hostname falls back to the agent id")
    void agentAlertWithoutHostname() {
        EmailTemplates.Email e = EmailTemplates.agentAlert("AGENT_OFFLINE",
                "windows-docker-01", "", T0, T0.plusMinutes(4));

        assertEquals("[Portal Monitor] SERVER OFFLINE: windows-docker-01", e.subject());
    }

    // ---------------------------------------------------------------- escaping

    @Test
    @DisplayName("names and URLs are HTML-escaped")
    void escapesHtml() {
        Portal p = http("A&B <script>", "G", "http://h/health?a=1&b=2");
        EmailTemplates.Email e = EmailTemplates.stateChange("DOWN",
                List.of(alert(p, CheckResult.down("HTTP 500"), "DOWN")), T0);

        assertFalse(e.html().contains("<script>"), "a raw tag leaked into the HTML body");
        assertTrue(e.html().contains("A&amp;B &lt;script&gt;"));
        assertTrue(e.html().contains("a=1&amp;b=2"));
        // the subject and plain text are not HTML, so they stay literal
        assertEquals("[Portal Monitor] DOWN: A&B <script>", e.subject());
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }
}
