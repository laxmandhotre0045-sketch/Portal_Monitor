package portalmonitor;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real SQL against a real MySQL/MariaDB. Skipped unless PM_IT_DB_HOST,
 * PM_IT_DB_USER and PM_IT_DB_PASSWORD are set (PM_IT_DB_PORT optional). The account
 * needs CREATE/DROP: the test creates portal_monitor_it, loads database.sql, and drops it.
 *
 *   PM_IT_DB_HOST=127.0.0.1 PM_IT_DB_USER=root PM_IT_DB_PASSWORD=... mvn test
 */
class DatabaseIntegrationTest {

    private static final String DB = "portal_monitor_it";
    private static String host, port, user, password;

    @BeforeAll
    static void createSchema() throws Exception {
        host = System.getenv("PM_IT_DB_HOST");
        user = System.getenv("PM_IT_DB_USER");
        password = System.getenv("PM_IT_DB_PASSWORD");
        port = System.getenv().getOrDefault("PM_IT_DB_PORT", "3306");
        assumeTrue(host != null && user != null && password != null,
                "PM_IT_DB_* not set - skipping the MySQL integration test");

        try (Connection c = admin(""); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        String sql = Files.readString(Path.of("database.sql"));
        StringBuilder noComments = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) noComments.append(line).append('\n');
        }
        try (Connection c = admin(DB); Statement st = c.createStatement()) {
            for (String stmt : noComments.toString().split(";\\s*\n")) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (host == null || user == null || password == null) return;
        try (Connection c = admin(""); Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
        }
    }

    private static Connection admin(String db) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", user);
        p.setProperty("password", password);
        return DriverManager.getConnection(
                "jdbc:mariadb://" + host + ":" + port + "/" + db + "?allowPublicKeyRetrieval=true", p);
    }

    private static Config cfg(String pw) {
        Properties p = new Properties();
        p.setProperty("db.host", host);
        p.setProperty("db.port", port);
        p.setProperty("db.name", DB);
        p.setProperty("db.user", user);
        p.setProperty("db.password", pw);
        p.setProperty("db.params", "allowPublicKeyRetrieval=true");
        return new Config(p);
    }

    @Test
    @DisplayName("the seed loads: 31 CONTAINER and 10 HTTP checks, readiness query works")
    void seedLoads() throws Exception {
        try (Database db = new Database(cfg(password))) {
            List<Portal> all = db.activePortals();
            assertEquals(31, all.stream().filter(p -> p.checkType() == Portal.CheckType.CONTAINER).count());
            assertEquals(10, all.stream().filter(p -> p.checkType() == Portal.CheckType.HTTP).count());
            assertEquals(41, db.countActivePortals());
        }
    }

    @Test
    @DisplayName("state writes round-trip, and the watchdog reset leaves DOWN rows DOWN")
    void stateRoundTrip() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        try (Database db = new Database(cfg(password))) {
            List<Portal> containers = db.activePortalsOfType(Portal.CheckType.CONTAINER);
            Portal up = containers.get(0), down = containers.get(1);
            db.updatePortal(up.id(), "UP", 0, null, CheckResult.up("Up 1 hour"), now, now);
            db.updatePortal(down.id(), "DOWN", 3, now, CheckResult.down("Exited (1)"), now, now);
            db.insertCheckLog(down.id(), CheckResult.down("Exited (1)"), now);
            db.insertAlertLog(down.id(), "DOWN", "ops@example.com", false, "smtp refused", now);
            db.insertAlertLog(null, "AGENT_OFFLINE", "ops@example.com", true, null, now);
            db.markAgentSeen("it-agent", "IT-HOST", now);

            int reset = db.resetContainerChecksToUnknown(now);
            assertEquals(1, reset, "only the UP row is reset");

            Portal upAfter = byId(db, up.id()), downAfter = byId(db, down.id());
            assertEquals("UNKNOWN", upAfter.status());
            assertEquals("DOWN", downAfter.status(), "DOWN must survive so RECOVERED is still sent");
            assertEquals(3, downAfter.failCount());
            assertEquals("ONLINE", db.findAgent("it-agent").status());
        }
    }

    @Test
    @DisplayName("a wrong password is classified as error 1045 with the explanation")
    void wrongPassword() {
        Exception e = assertThrows(Exception.class, () -> new Database(cfg(password + "-wrong")).close());
        String[] d = DbStatus.describe(e, user);
        assertTrue(d[0].contains("error 1045"), d[0]);
        assertTrue(!d[0].contains(password), "the password never appears");
    }

    private static Portal byId(Database db, int id) throws Exception {
        return db.activePortals().stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }
}
