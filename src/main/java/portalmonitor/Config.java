package portalmonitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/** Reads application.properties into validated, typed settings. */
public final class Config {

    // ---- database ----
    public final String dbHost, dbName, dbUser, dbPassword, dbParams;
    public final int dbPort;

    // ---- email ----
    public final String smtpHost, smtpSecurity, smtpUser, smtpPassword, smtpFrom, smtpFromName;
    public final int smtpPort;
    public final String defaultEmail;

    // ---- checks ----
    public final int intervalSeconds, failThreshold, connectTimeoutSeconds, requestTimeoutSeconds;
    public final Set<Integer> successCodes;
    public final boolean verifySsl;

    // ---- server API ----
    public final int serverPort;
    public final String serverBind;
    public final String apiToken;

    // ---- agent ----
    public final String agentServerUrl, agentToken, agentId;
    public final int agentIntervalSeconds, agentOfflineAfterSeconds;
    public final String dockerCommand;
    public final int dockerTimeoutSeconds;

    // ---- general ----
    public final int logRetentionDays;
    public final ZoneId zone;

    Config(Properties p) {
        dbHost     = p.getProperty("db.host", "localhost").trim();
        dbPort     = intInRange(p, "db.port", 3306, 1, 65535);
        dbName     = p.getProperty("db.name", "portal_monitor").trim();
        dbUser     = p.getProperty("db.user", "root").trim();
        dbPassword = p.getProperty("db.password", "");
        // Extra JDBC options, e.g. allowPublicKeyRetrieval=true for MySQL 8 on localhost.
        dbParams   = p.getProperty("db.params", "").trim();

        // SMTP is only needed by the server; the agent never sends mail, so these are
        // optional at load time and checked in requireServerSettings().
        smtpHost     = p.getProperty("smtp.host", "").trim();
        smtpPort     = intInRange(p, "smtp.port", 465, 1, 65535);
        smtpSecurity = oneOf(p, "smtp.security", "ssl", "ssl", "tls", "none");
        smtpUser     = p.getProperty("smtp.user", "").trim();
        smtpPassword = p.getProperty("smtp.password", "");
        smtpFrom     = p.getProperty("smtp.from", "").trim();
        smtpFromName = p.getProperty("smtp.fromName", "Portal Monitor").trim();
        defaultEmail = p.getProperty("alert.defaultEmail", "").trim();

        intervalSeconds       = intInRange(p, "check.intervalSeconds", 60, 5, 86400);
        failThreshold         = intInRange(p, "check.failThreshold", 3, 1, 1000);
        connectTimeoutSeconds = intInRange(p, "check.connectTimeoutSeconds", 5, 1, 300);
        requestTimeoutSeconds = intInRange(p, "check.requestTimeoutSeconds", 10, 1, 300);
        successCodes          = codes(p.getProperty("check.successCodes", "200"));
        verifySsl             = bool(p, "check.verifySsl", true);

        serverPort = intInRange(p, "server.port", 8085, 1, 65535);
        // Bind to loopback by default; nginx terminates HTTPS in front of it.
        serverBind = p.getProperty("server.bind", "127.0.0.1").trim();
        apiToken   = p.getProperty("api.token", "").trim();

        agentServerUrl           = p.getProperty("agent.serverUrl", "").trim();
        agentToken               = p.getProperty("agent.token", "").trim();
        agentId                  = p.getProperty("agent.id", "windows-docker-01").trim();
        agentIntervalSeconds     = intInRange(p, "agent.intervalSeconds", 60, 5, 86400);
        agentOfflineAfterSeconds = intInRange(p, "agent.offlineAfterSeconds", 180, 30, 86400);
        dockerCommand            = p.getProperty("agent.dockerCommand", "docker").trim();
        dockerTimeoutSeconds     = intInRange(p, "agent.dockerTimeoutSeconds", 30, 1, 600);

        logRetentionDays = intInRange(p, "log.retentionDays", 30, 1, 3650);
        zone             = zone(p.getProperty("app.timezone", "Asia/Kolkata").trim());
    }

    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        return new Config(p);
    }

    /**
     * The JDBC URL carries no credentials - the password contains '#' and other characters
     * that would need escaping inside a URL. Database passes user and password as separate
     * JDBC properties instead.
     */
    public String jdbcUrl() {
        String url = "jdbc:mariadb://" + dbHost + ":" + dbPort + "/" + dbName;
        return dbParams.isEmpty() ? url : url + "?" + dbParams;
    }

    /** Fails fast when the settings a server needs are missing. */
    public void requireServerSettings() {
        requireMailSettings();
        if (apiToken.isBlank()) {
            throw new IllegalArgumentException(
                    "api.token must be set in server mode - the agent API would otherwise be open to anyone");
        }
        if (apiToken.length() < 16) {
            throw new IllegalArgumentException("api.token is too short; use at least 16 characters");
        }
    }

    /** Needed by anything that sends mail: server mode and --test-email. */
    public void requireMailSettings() {
        if (smtpHost.isBlank()) {
            throw new IllegalArgumentException("Missing required setting: smtp.host");
        }
        if (smtpFrom.isBlank()) {
            throw new IllegalArgumentException("Missing required setting: smtp.from");
        }
    }

    /** Fails fast when the settings an agent needs are missing. */
    public void requireAgentSettings() {
        if (agentServerUrl.isBlank()) {
            throw new IllegalArgumentException("agent.serverUrl must be set in agent mode");
        }
        if (agentToken.isBlank()) {
            throw new IllegalArgumentException("agent.token must be set in agent mode");
        }
    }

    // ------------------------------------------------------------------ parsing helpers

    private static String req(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required setting: " + key);
        }
        return v.trim();
    }

    private static int intInRange(Properties p, String key, int def, int min, int max) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) return def;
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, got: " + raw.trim());
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", got: " + v);
        }
        return v;
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String raw = p.getProperty(key);
        if (raw == null || raw.isBlank()) return def;
        String v = raw.trim().toLowerCase();
        if (v.equals("true") || v.equals("yes") || v.equals("1")) return true;
        if (v.equals("false") || v.equals("no") || v.equals("0")) return false;
        throw new IllegalArgumentException(key + " must be true or false, got: " + raw.trim());
    }

    private static String oneOf(Properties p, String key, String def, String... allowed) {
        String raw = p.getProperty(key);
        String v = (raw == null || raw.isBlank()) ? def : raw.trim().toLowerCase();
        for (String a : allowed) {
            if (a.equals(v)) return v;
        }
        throw new IllegalArgumentException(key + " must be one of " + String.join("/", allowed) + ", got: " + v);
    }

    private static Set<Integer> codes(String raw) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String s : raw.split("[,;\\s]+")) {
            if (s.isBlank()) continue;
            int c;
            try {
                c = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("check.successCodes contains a non-number: " + s.trim());
            }
            if (c < 100 || c > 599) {
                throw new IllegalArgumentException("check.successCodes contains an invalid HTTP code: " + c);
            }
            out.add(c);
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("check.successCodes must list at least one HTTP code");
        }
        return Set.copyOf(out);
    }

    private static ZoneId zone(String raw) {
        try {
            return ZoneId.of(raw);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("app.timezone is not a known timezone: " + raw);
        }
    }
}
