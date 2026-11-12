package portalmonitor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public final boolean agentAllowInsecureHttp;

    // ---- general ----
    public final int logRetentionDays;
    public final ZoneId zone;

    /**
     * Every setting the application reads. Each can also come from the environment:
     * PM_ + the key upper-cased with dots as underscores (db.password -> PM_DB_PASSWORD,
     * smtp.fromName -> PM_SMTP_FROMNAME), or from a file named by that variable plus _FILE
     * (PM_DB_PASSWORD_FILE=/run/secrets/db_password), which is how Docker secrets work.
     */
    static final List<String> KEYS = List.of(
            "db.host", "db.port", "db.name", "db.user", "db.password", "db.params",
            "smtp.host", "smtp.port", "smtp.security", "smtp.user", "smtp.password",
            "smtp.from", "smtp.fromName", "alert.defaultEmail",
            "check.intervalSeconds", "check.failThreshold", "check.connectTimeoutSeconds",
            "check.requestTimeoutSeconds", "check.successCodes", "check.verifySsl",
            "server.port", "server.bind", "api.token",
            "agent.serverUrl", "agent.token", "agent.id", "agent.intervalSeconds",
            "agent.offlineAfterSeconds", "agent.dockerCommand", "agent.dockerTimeoutSeconds",
            "agent.allowInsecureHttp",
            "log.retentionDays", "app.timezone");

    /** PM_ variables used by docker-compose / deploy.sh rather than by the application. */
    private static final Set<String> DEPLOY_VARS = Set.of("PM_IMAGE_TAG", "PM_CONFIG_FILE");

    /** Which settings came from the environment, plus warnings - names only, never values. */
    private final List<String> envNotes = new ArrayList<>();

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
        agentAllowInsecureHttp   = bool(p, "agent.allowInsecureHttp", false);

        logRetentionDays = intInRange(p, "log.retentionDays", 30, 1, 3650);
        zone             = zone(p.getProperty("app.timezone", "Asia/Kolkata").trim());
    }

    public static Config load(Path file) throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        List<String> notes = new ArrayList<>();
        Config c = new Config(withEnvironment(p, System.getenv(), notes));
        c.envNotes.addAll(notes);
        return c;
    }

    /** The environment variable that overrides a key: db.password -> PM_DB_PASSWORD. */
    static String envName(String key) {
        return "PM_" + key.replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /**
     * Overlays PM_* environment variables, and PM_*_FILE secret files, on top of the file.
     * The environment wins, so one image and one properties file serve several environments
     * and passwords can stay out of the file entirely.
     */
    static Properties withEnvironment(Properties fromFile, Map<String, String> env, List<String> notes) {
        Properties out = new Properties();
        out.putAll(fromFile);
        Set<String> known = new HashSet<>(DEPLOY_VARS);
        for (String key : KEYS) {
            String name = envName(key);
            known.add(name);
            known.add(name + "_FILE");
            String fileVar = env.get(name + "_FILE");
            String direct = env.get(name);
            if (fileVar != null && !fileVar.isBlank()) {
                String v;
                try {
                    v = Files.readString(Path.of(fileVar.trim()));
                } catch (IOException | RuntimeException e) {
                    throw new IllegalArgumentException(name + "_FILE points to an unreadable file: " + fileVar.trim());
                }
                // Secret files usually end with a newline that is not part of the value.
                out.setProperty(key, stripTrailingNewlines(v));
                notes.add(key + " <- " + name + "_FILE");
            } else if (direct != null && !direct.isEmpty()) {
                // Empty means "not set": docker-compose passes ${PM_X:-} through as "".
                out.setProperty(key, direct);
                notes.add(key + " <- " + name);
            }
        }
        env.keySet().stream().sorted()
                .filter(n -> n.startsWith("PM_") && !known.contains(n))
                .forEach(n -> notes.add("WARNING: unknown environment variable " + n + " is ignored (typo?)"));
        return out;
    }

    private static String stripTrailingNewlines(String v) {
        int end = v.length();
        while (end > 0 && (v.charAt(end - 1) == '\n' || v.charAt(end - 1) == '\r')) end--;
        return v.substring(0, end);
    }

    /** Which settings came from the environment - names only. Logged at startup. */
    public List<String> envNotes() {
        return List.copyOf(envNotes);
    }

    /** One startup line describing the server settings - secrets shown only as set / NOT SET. */
    public String describeServer() {
        return "db=" + dbUser + "@" + dbHost + ":" + dbPort + "/" + dbName
             + (dbParams.isEmpty() ? "" : " params=" + dbParams)
             + ", db.password " + secretState(dbPassword)
             // .properties keeps trailing spaces in values: an invisible one is a classic 1045.
             + (!dbPassword.equals(dbPassword.strip()) ? " (WARNING: starts or ends with a space)" : "")
             + "; smtp=" + smtpHost + ":" + smtpPort + "/" + smtpSecurity
             + " user=" + (smtpUser.isEmpty() ? "(none)" : smtpUser)
             + ", smtp.password " + secretState(smtpPassword)
             + "; api=" + serverBind + ":" + serverPort
             + ", api.token " + secretState(apiToken);
    }

    /** One startup line describing the agent settings - the token shown only as set / NOT SET. */
    public String describeAgent() {
        return "server=" + agentServerUrl + ", agent.id=" + agentId
             + ", agent.token " + secretState(agentToken) + ", docker=" + dockerCommand;
    }

    private static String secretState(String v) {
        return v == null || v.isEmpty() ? "NOT SET" : "set (" + v.length() + " chars)";
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
        if (apiToken.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("api.token is still the example value - generate one with: openssl rand -hex 32");
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
        if (agentServerUrl.contains("yourdomain.com")) {
            throw new IllegalArgumentException("agent.serverUrl is still the example value ("
                    + agentServerUrl + ") - set it to your VPS's https:// address");
        }
        if (!agentServerUrl.startsWith("http://") && !agentServerUrl.startsWith("https://")) {
            throw new IllegalArgumentException("agent.serverUrl must start with https:// - got: "
                    + agentServerUrl);
        }
        if (agentServerUrl.startsWith("http://") && !isLoopbackUrl(agentServerUrl) && !agentAllowInsecureHttp) {
            throw new IllegalArgumentException("agent.serverUrl uses plain http:// to a remote host, which would "
                    + "send agent.token unencrypted. Use https://, or set agent.allowInsecureHttp=true "
                    + "if you accept that risk.");
        }
        if (agentToken.length() < 16) {
            throw new IllegalArgumentException("agent.token is too short; it must equal the server's api.token");
        }
    }

    /** http://localhost, http://127.x, http://[::1] - traffic that never leaves the machine. */
    static boolean isLoopbackUrl(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]");
        } catch (IllegalArgumentException e) {
            return false;
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
