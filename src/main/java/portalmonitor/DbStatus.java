package portalmonitor;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Tracks whether the database is reachable, for readiness and for readable logs.
 *
 * Without this, a wrong password produced the same raw driver error every 60 seconds,
 * twice (cycle + watchdog), forever, while /health still answered UP. Now:
 *   - the first failure is logged once, in full, with what to check;
 *   - while it keeps failing, one short reminder every REMIND_EVERY;
 *   - recovery is logged with how long it was down;
 *   - /ready reports the state, so Docker shows the container as unhealthy.
 *
 * Thread-safe: written by the scheduler and API threads, read by /ready.
 */
public final class DbStatus {

    public enum State { UNKNOWN, UP, DOWN }

    static final Duration REMIND_EVERY = Duration.ofMinutes(10);

    private final String dbUser;
    private final String dbTarget;

    private State state = State.UNKNOWN;
    private String reason;          // short, safe to show in /ready
    private String hint;            // what to check, for the log
    private LocalDateTime downSince;
    private LocalDateTime lastOk;
    private LocalDateTime lastAttempt;
    private LocalDateTime lastLogged;
    private int failuresInRow;

    public DbStatus(Config cfg) {
        this(cfg.dbUser, cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbName);
    }

    DbStatus(String dbUser, String dbTarget) {
        this.dbUser = dbUser;
        this.dbTarget = dbTarget;
    }

    /** A connection (and its first statement) just worked. */
    public synchronized void succeeded(LocalDateTime now) {
        if (state == State.DOWN) {
            Log.info("Database connection RESTORED (" + dbTarget + ") after "
                    + EmailTemplates.humanDuration(Duration.between(downSince, now))
                    + " and " + failuresInRow + " failed attempt(s).");
        } else if (state == State.UNKNOWN) {
            Log.info("Database connection OK (" + dbUser + "@" + dbTarget + ").");
        }
        state = State.UP;
        reason = null;
        hint = null;
        downSince = null;
        failuresInRow = 0;
        lastOk = now;
        lastAttempt = now;
    }

    /** Opening a connection failed. Logs in full once, then only periodic reminders. */
    public synchronized void failed(Throwable e, LocalDateTime now) {
        String[] d = describe(e, dbUser);
        boolean changed = state != State.DOWN || !d[0].equals(reason);
        if (state != State.DOWN) downSince = now;
        state = State.DOWN;
        reason = d[0];
        hint = d[1];
        failuresInRow++;
        lastAttempt = now;

        if (changed) {
            Log.error("Database UNAVAILABLE (" + dbUser + "@" + dbTarget + "): " + reason);
            Log.error("  What to check: " + hint);
            Log.error("  Checks and alerts are paused until it connects. Retrying every cycle; "
                    + "repeats of this error are summarised every " + REMIND_EVERY.toMinutes() + " minutes.");
            lastLogged = now;
        } else if (lastLogged == null || Duration.between(lastLogged, now).compareTo(REMIND_EVERY) >= 0) {
            Log.error("Database still UNAVAILABLE for "
                    + EmailTemplates.humanDuration(Duration.between(downSince, now))
                    + " (" + failuresInRow + " attempts): " + reason);
            lastLogged = now;
        }
    }

    public synchronized State state() { return state; }
    public synchronized String reason() { return reason; }
    public synchronized LocalDateTime lastOk() { return lastOk; }
    public synchronized LocalDateTime lastAttempt() { return lastAttempt; }
    public synchronized LocalDateTime downSince() { return downSince; }
    public synchronized int failuresInRow() { return failuresInRow; }

    /**
     * Turns a connection failure into {short reason, what to check}. Driver messages are
     * kept (they name the user and client host, which is exactly what is needed) but are
     * never combined with configuration values, so no password can reach a log.
     */
    static String[] describe(Throwable e, String dbUser) {
        SQLException sql = find(e, SQLException.class);
        int code = sql == null ? 0 : sql.getErrorCode();
        String driverMsg = Text.clean(stripConnPrefix(rootMessage(e)), 200);

        switch (code) {
            case 1045:
                return new String[] {
                        "MySQL rejected the login (error 1045, access denied): " + driverMsg,
                        "either db.password differs from the MySQL password, or no account '" + dbUser
                        + "' exists for the host shown after the @ above (inside Docker that is the "
                        + "container's IP, not 'localhost'). In MySQL run: SELECT user, host, plugin "
                        + "FROM mysql.user WHERE user='" + dbUser + "';" };
            case 1130:
                return new String[] {
                        "MySQL does not accept connections from this host (error 1130): " + driverMsg,
                        "create the account for the Docker subnet, e.g. '" + dbUser + "'@'172.16.0.0/255.240.0.0'." };
            case 1044:
                return new String[] {
                        "Logged in, but no privileges on the database (error 1044): " + driverMsg,
                        "GRANT SELECT, INSERT, UPDATE, DELETE ON <db.name>.* TO the account." };
            case 1049:
                return new String[] {
                        "Database does not exist (error 1049): " + driverMsg,
                        "check db.name, or import database.sql." };
            case 1251:
                return new String[] {
                        "Authentication method not supported (error 1251): " + driverMsg,
                        "add allowPublicKeyRetrieval=true to db.params, or use sslMode=trust for a remote server." };
            default:
                break;
        }
        if (find(e, UnknownHostException.class) != null || driverMsg.toLowerCase().contains("unknownhost")) {
            return new String[] {
                    "Database host name does not resolve: " + driverMsg,
                    "check db.host. Inside Docker, use the MySQL service/container name, or "
                    + "host.docker.internal for MySQL running on the VPS itself." };
        }
        if (find(e, ConnectException.class) != null || driverMsg.toLowerCase().contains("connection refused")) {
            return new String[] {
                    "Nothing accepted the connection at db.host/db.port: " + driverMsg,
                    "is MySQL running and listening on that address? Inside Docker 'localhost' is the "
                    + "container itself; MySQL's bind-address must include the Docker bridge." };
        }
        if (find(e, SocketTimeoutException.class) != null || driverMsg.toLowerCase().contains("timed out")) {
            return new String[] {
                    "Database connection timed out: " + driverMsg,
                    "a firewall is dropping the traffic, or db.host points at the wrong machine." };
        }
        return new String[] {
                "Database error" + (code != 0 ? " " + code : "") + ": " + driverMsg,
                "run --test-db with the same configuration for details." };
    }

    private static <T extends Throwable> T find(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return type.cast(t);
        }
        return null;
    }

    private static String rootMessage(Throwable e) {
        // Prefer the first SQLException's message: the driver puts the useful text there.
        SQLException sql = find(e, SQLException.class);
        Throwable t = sql != null ? sql : e;
        String m = t.getMessage();
        return m != null ? m : t.getClass().getSimpleName();
    }

    /** "(conn=123) Access denied ..." -> "Access denied ..." */
    private static String stripConnPrefix(String m) {
        return m == null ? "" : m.replaceFirst("^\\(conn=\\d+\\)\\s*", "");
    }
}
