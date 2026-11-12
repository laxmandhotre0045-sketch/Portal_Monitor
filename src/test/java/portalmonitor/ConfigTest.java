package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

    /** A config with nothing set, to prove the defaults. */
    private static Properties empty() {
        return new Properties();
    }

    private static Config config(String... keyValues) {
        Properties p = empty();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return new Config(p);
    }

    @Test
    @DisplayName("defaults match the documented behaviour")
    void defaults() {
        Config c = new Config(empty());

        assertEquals("localhost", c.dbHost);
        assertEquals(3306, c.dbPort);
        assertEquals("portal_monitor", c.dbName);
        assertEquals(465, c.smtpPort);
        assertEquals("ssl", c.smtpSecurity);
        assertEquals(60, c.intervalSeconds);
        assertEquals(3, c.failThreshold);
        assertEquals(5, c.connectTimeoutSeconds);
        assertEquals(10, c.requestTimeoutSeconds);
        assertEquals(Set.of(200), c.successCodes);
        assertTrue(c.verifySsl);
        assertEquals(8085, c.serverPort);
        assertEquals("127.0.0.1", c.serverBind);
        assertEquals(180, c.agentOfflineAfterSeconds);
        assertEquals(60, c.agentIntervalSeconds);
        assertEquals("docker", c.dockerCommand);
        assertEquals(30, c.logRetentionDays);
        assertEquals("Asia/Kolkata", c.zone.getId());
    }

    // ---------------------------------------------------------------- the '#' password

    @Test
    @DisplayName("a password containing # and @ survives, and stays out of the JDBC URL")
    void passwordWithHashIsNotInUrl() {
        // This is the real password shape; '#' only starts a comment at the start of a line.
        Config c = config("db.password", "Example@1234#", "db.host", "10.0.0.5", "db.name", "pm");

        assertEquals("Example@1234#", c.dbPassword, "the whole password must be kept verbatim");
        assertEquals("jdbc:mariadb://10.0.0.5:3306/pm", c.jdbcUrl());
        assertFalse(c.jdbcUrl().contains("Laxman"), "credentials must never appear in the URL");
        assertFalse(c.jdbcUrl().contains("password"), "credentials must never appear in the URL");
    }

    @Test
    @DisplayName("db.params is appended as a query string")
    void dbParams() {
        Config c = config("db.params", "allowPublicKeyRetrieval=true", "db.name", "pm");

        assertEquals("jdbc:mariadb://localhost:3306/pm?allowPublicKeyRetrieval=true", c.jdbcUrl());
    }

    // ---------------------------------------------------------------- mode requirements

    @Test
    @DisplayName("server mode demands mail settings and a real api.token")
    void serverRequirements() {
        // no smtp at all
        assertThrows(IllegalArgumentException.class, () -> config("api.token", "x".repeat(32))
                .requireServerSettings());

        // smtp present, token missing
        Config noToken = config("smtp.host", "smtp.hostinger.com", "smtp.from", "a@b.com");
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, noToken::requireServerSettings);
        assertTrue(e.getMessage().contains("api.token"), e.getMessage());

        // token too short to be a secret
        Config shortToken = config("smtp.host", "h", "smtp.from", "a@b.com", "api.token", "abc");
        assertThrows(IllegalArgumentException.class, shortToken::requireServerSettings);

        // all present
        assertDoesNotThrow(() -> config("smtp.host", "h", "smtp.from", "a@b.com",
                "api.token", "x".repeat(32)).requireServerSettings());
    }

    @Test
    @DisplayName("agent mode needs a server URL and a token, but no mail or database")
    void agentRequirements() {
        assertThrows(IllegalArgumentException.class, () -> new Config(empty()).requireAgentSettings());
        assertThrows(IllegalArgumentException.class,
                () -> config("agent.serverUrl", "https://m.example.com").requireAgentSettings());

        // An agent config carries no smtp.* and no db.* at all, and that must be fine.
        assertDoesNotThrow(() -> config(
                "agent.serverUrl", "https://m.example.com",
                "agent.token", "x".repeat(32)).requireAgentSettings());
    }

    @Test
    @DisplayName("example placeholders are rejected instead of failing silently later")
    void rejectsPlaceholders() {
        assertThrows(IllegalArgumentException.class, () -> config(
                "agent.serverUrl", "https://monitor.yourdomain.com",
                "agent.token", "x".repeat(32)).requireAgentSettings());
        assertThrows(IllegalArgumentException.class, () -> config(
                "agent.serverUrl", "monitor.example.com",
                "agent.token", "x".repeat(32)).requireAgentSettings());
        assertThrows(IllegalArgumentException.class, () -> config(
                "smtp.host", "smtp.example.com", "smtp.from", "a@example.com",
                "api.token", "CHANGE_ME_generate_a_long_random_token").requireServerSettings());
    }

    @Test
    @DisplayName("unconfigured HTTP rows are recognised so they never raise a false DOWN")
    void placeholderUrls() {
        assertTrue(MonitorService.isPlaceholderUrl("http://SERVER_PUBLIC_IP:3001/"));
        assertTrue(MonitorService.isPlaceholderUrl(null));
        assertTrue(MonitorService.isPlaceholderUrl("  "));
        assertFalse(MonitorService.isPlaceholderUrl("https://www.sensovibe.in/"));
    }

    // ---------------------------------------------------------------- validation

    @Test
    @DisplayName("bad values are rejected at startup, not at 3am")
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> config("check.failThreshold", "0"));
        assertThrows(IllegalArgumentException.class, () -> config("check.failThreshold", "abc"));
        assertThrows(IllegalArgumentException.class, () -> config("check.intervalSeconds", "-1"));
        assertThrows(IllegalArgumentException.class, () -> config("check.successCodes", "OK"));
        assertThrows(IllegalArgumentException.class, () -> config("check.successCodes", ""));
        assertThrows(IllegalArgumentException.class, () -> config("check.successCodes", "999"));
        assertThrows(IllegalArgumentException.class, () -> config("check.verifySsl", "maybe"));
        assertThrows(IllegalArgumentException.class, () -> config("smtp.security", "starttls"));
        assertThrows(IllegalArgumentException.class, () -> config("smtp.port", "70000"));
        assertThrows(IllegalArgumentException.class, () -> config("server.port", "0"));
        assertThrows(IllegalArgumentException.class, () -> config("agent.offlineAfterSeconds", "10"));
        assertThrows(IllegalArgumentException.class, () -> config("app.timezone", "Mars/Olympus"));
    }

    @Test
    @DisplayName("several success codes and tls security parse")
    void parsesLists() {
        Config c = config("check.successCodes", "200, 204,301", "smtp.security", "TLS",
                "smtp.port", "587");

        assertEquals(Set.of(200, 204, 301), c.successCodes);
        assertEquals("tls", c.smtpSecurity);
        assertEquals(587, c.smtpPort);
    }

    @Test
    @DisplayName("blank optional values fall back to the default")
    void blankFallsBack() {
        Config c = config("check.intervalSeconds", "   ", "log.retentionDays", "");

        assertEquals(60, c.intervalSeconds);
        assertEquals(30, c.logRetentionDays);
    }
}
