package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Readable database errors, and logs that do not repeat the same failure every minute. */
class DbStatusTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 12, 10, 0, 0);

    /** Exactly what the MariaDB driver throws for the production error. */
    private static SQLException accessDenied() {
        return new SQLInvalidAuthorizationSpecException(
                "(conn=42) Access denied for user 'portal_monitor'@'172.20.0.2' (using password: YES)",
                "28000", 1045);
    }

    @Test
    @DisplayName("error 1045 is explained: password mismatch or no account for the container's host")
    void accessDeniedExplained() {
        String[] d = DbStatus.describe(accessDenied(), "portal_monitor");
        assertTrue(d[0].startsWith("MySQL rejected the login (error 1045"));
        assertTrue(d[0].contains("'portal_monitor'@'172.20.0.2'"), "keeps the user@host MySQL saw");
        assertFalse(d[0].contains("(conn=42)"));
        assertTrue(d[1].contains("SELECT user, host, plugin FROM mysql.user WHERE user='portal_monitor'"));
        assertTrue(d[1].contains("not 'localhost'"));
    }

    @Test
    @DisplayName("other common failures each get their own explanation")
    void otherFailures() {
        assertTrue(DbStatus.describe(new SQLException("Host '172.20.0.2' is not allowed", "HY000", 1130), "u")[0]
                .contains("error 1130"));
        assertTrue(DbStatus.describe(new SQLException("Unknown database 'pm'", "42000", 1049), "u")[0]
                .contains("error 1049"));
        assertTrue(DbStatus.describe(new SQLNonTransientConnectionException("Socket fail to connect",
                "08000", -1, new ConnectException("Connection refused")), "u")[1]
                .contains("Inside Docker 'localhost' is the container itself"));
        assertTrue(DbStatus.describe(new SQLNonTransientConnectionException("Socket fail",
                "08000", -1, new UnknownHostException("mysql")), "u")[0]
                .contains("does not resolve"));
    }

    @Test
    @DisplayName("a persistent failure is logged once, then summarised every 10 minutes, then recovery")
    void throttledLogging() {
        DbStatus s = new DbStatus("portal_monitor", "mysql:3306/portal_monitor");
        PrintStream origErr = System.err, origOut = System.out;
        ByteArrayOutputStream err = new ByteArrayOutputStream(), out = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            for (int minute = 0; minute < 25; minute++) {       // 25 failed cycles, one per minute
                s.failed(accessDenied(), T0.plusMinutes(minute));
            }
            s.succeeded(T0.plusMinutes(25));
        } finally {
            System.setErr(origErr);
            System.setOut(origOut);
        }
        String log = err.toString(StandardCharsets.UTF_8);

        assertEquals(1, count(log, "Database UNAVAILABLE"), "full explanation exactly once");
        assertEquals(2, count(log, "Database still UNAVAILABLE"), "reminders at +10 and +20 minutes only");
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Database connection RESTORED"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("25 failed attempt(s)"));
        assertEquals(DbStatus.State.UP, s.state());
        assertNull(s.reason());
        assertEquals(0, s.failuresInRow());
    }

    @Test
    @DisplayName("state and reason are exposed for /ready")
    void exposesState() {
        DbStatus s = new DbStatus("u", "h:3306/d");
        assertEquals(DbStatus.State.UNKNOWN, s.state());
        s.failed(accessDenied(), T0);
        assertEquals(DbStatus.State.DOWN, s.state());
        assertEquals(T0, s.downSince());
        assertTrue(s.reason().contains("1045"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }
}
