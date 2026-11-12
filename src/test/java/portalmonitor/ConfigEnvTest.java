package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Docker-style configuration: environment overrides, secret files, and masked summaries. */
class ConfigEnvTest {

    private static Properties file(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) p.setProperty(kv[i], kv[i + 1]);
        return p;
    }

    @Test
    @DisplayName("env names follow PM_ + upper-case key with dots as underscores")
    void envNames() {
        assertEquals("PM_DB_PASSWORD", Config.envName("db.password"));
        assertEquals("PM_SMTP_FROMNAME", Config.envName("smtp.fromName"));
        assertEquals("PM_SERVER_BIND", Config.envName("server.bind"));
    }

    @Test
    @DisplayName("an environment variable overrides the properties file")
    void envOverridesFile() {
        List<String> notes = new ArrayList<>();
        Properties merged = Config.withEnvironment(
                file("db.host", "localhost", "db.user", "portal_monitor", "server.bind", "127.0.0.1"),
                Map.of("PM_DB_HOST", "host.docker.internal", "PM_SERVER_BIND", "0.0.0.0"),
                notes);
        Config c = new Config(merged);

        assertEquals("host.docker.internal", c.dbHost);
        assertEquals("0.0.0.0", c.serverBind);
        assertEquals("portal_monitor", c.dbUser, "keys not in the environment keep the file value");
        assertTrue(notes.contains("db.host <- PM_DB_HOST"));
    }

    @Test
    @DisplayName("an empty variable (compose's ${PM_X:-}) leaves the file value alone")
    void emptyIsUnset() {
        Config c = new Config(Config.withEnvironment(file("db.host", "mysql"),
                Map.of("PM_DB_HOST", ""), new ArrayList<>()));
        assertEquals("mysql", c.dbHost);
    }

    @Test
    @DisplayName("a _FILE variable reads a Docker secret, without its trailing newline")
    void secretFile(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("db_password");
        Files.writeString(secret, "Pa#ss@w0rd!\n");
        List<String> notes = new ArrayList<>();
        Config c = new Config(Config.withEnvironment(file("db.password", "old"),
                Map.of("PM_DB_PASSWORD_FILE", secret.toString()), notes));

        assertEquals("Pa#ss@w0rd!", c.dbPassword, "#, @ and ! survive; the newline does not");
        assertTrue(notes.contains("db.password <- PM_DB_PASSWORD_FILE"));
        assertFalse(String.join(" ", notes).contains("Pa#ss"), "notes never carry values");
    }

    @Test
    @DisplayName("a missing secret file is a startup error naming the variable")
    void missingSecretFile() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Config.withEnvironment(new Properties(),
                        Map.of("PM_DB_PASSWORD_FILE", "/definitely/not/here"), new ArrayList<>()));
        assertTrue(e.getMessage().contains("PM_DB_PASSWORD_FILE"));
    }

    @Test
    @DisplayName("a mistyped PM_ variable is reported instead of silently ignored")
    void unknownVariableWarns() {
        List<String> notes = new ArrayList<>();
        Config.withEnvironment(new Properties(),
                Map.of("PM_DB_PASSWRD", "x", "PM_IMAGE_TAG", "abc123", "PATH", "/bin"), notes);
        assertTrue(notes.stream().anyMatch(n -> n.contains("PM_DB_PASSWRD")));
        assertFalse(notes.stream().anyMatch(n -> n.contains("PM_IMAGE_TAG")), "deploy variables are expected");
        assertFalse(notes.stream().anyMatch(n -> n.contains("PATH")), "only PM_ variables are considered");
    }

    @Test
    @DisplayName("startup summaries never contain a password or token")
    void summariesAreMasked() {
        Config c = new Config(file(
                "db.user", "portal_monitor", "db.password", "SuperSecret#1",
                "smtp.host", "smtp.gmail.com", "smtp.password", "mailsecret",
                "api.token", "tok-0123456789abcdef", "agent.serverUrl", "https://m.example.com",
                "agent.token", "tok-0123456789abcdef"));
        String s = c.describeServer() + " " + c.describeAgent();

        assertFalse(s.contains("SuperSecret"));
        assertFalse(s.contains("mailsecret"));
        assertFalse(s.contains("tok-0123"));
        assertTrue(s.contains("db.password set (13 chars)"));
        assertTrue(s.contains("portal_monitor@localhost:3306/portal_monitor"));
    }

    @Test
    @DisplayName("an invisible trailing space in db.password is flagged without showing the password")
    void trailingSpaceFlagged() {
        String s = new Config(file("db.password", "Secret-123 ")).describeServer();
        assertTrue(s.contains("WARNING: starts or ends with a space"));
        assertFalse(s.contains("Secret-123"));
        assertFalse(new Config(file("db.password", "Secret-123")).describeServer().contains("WARNING"));
    }

    @Test
    @DisplayName("the agent refuses to send its token over plain http to a remote host")
    void agentNeedsHttpsRemotely() {
        String token = "x".repeat(32);
        assertThrows(IllegalArgumentException.class, () -> new Config(file(
                "agent.serverUrl", "http://monitor.sensovibe.in", "agent.token", token)).requireAgentSettings());
        assertDoesNotThrow(() -> new Config(file(
                "agent.serverUrl", "https://monitor.sensovibe.in", "agent.token", token)).requireAgentSettings());
        assertDoesNotThrow(() -> new Config(file(
                "agent.serverUrl", "http://127.0.0.1:8085", "agent.token", token)).requireAgentSettings(),
                "loopback never leaves the machine");
        assertDoesNotThrow(() -> new Config(file(
                "agent.serverUrl", "http://10.0.0.5:8085", "agent.token", token,
                "agent.allowInsecureHttp", "true")).requireAgentSettings(), "explicit opt-in");
        assertThrows(IllegalArgumentException.class, () -> new Config(file(
                "agent.serverUrl", "https://m.example.com", "agent.token", "short")).requireAgentSettings());
    }
}
