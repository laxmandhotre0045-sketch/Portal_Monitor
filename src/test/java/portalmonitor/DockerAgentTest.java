package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerAgentTest {

    private static Config cfg(String dockerCommand, int timeoutSeconds) {
        Properties p = new Properties();
        p.setProperty("agent.serverUrl", "http://127.0.0.1:1");   // nothing listens there
        p.setProperty("agent.token", "x".repeat(32));
        p.setProperty("agent.dockerCommand", dockerCommand);
        p.setProperty("agent.dockerTimeoutSeconds", String.valueOf(timeoutSeconds));
        return new Config(p);
    }

    @Test
    @DisplayName("a missing docker executable never escapes runOnce")
    void missingDockerIsHandled() {
        DockerAgent agent = new DockerAgent(cfg("definitely-not-a-real-command-xyz", 5));

        assertThrows(Exception.class, agent::collectContainers,
                "collectContainers surfaces the reason");
        assertFalse(agent.runOnce(),
                "runOnce must swallow it and report failure, so the schedule survives");
    }

    @Test
    @DisplayName("an unreachable server never escapes runOnce either")
    void unreachableServerIsHandled() {
        // docker works (we ask for a trivial command that exits 0), but the POST fails.
        // The agent must just report false and try again next cycle.
        DockerAgent agent = new DockerAgent(cfg("hostname", 10));

        assertFalse(agent.runOnce(), "a failed POST must not throw");
    }

    @Test
    @DisplayName("docker ps output becomes check results")
    void parsesRealisticOutput() {
        // Exactly the shape `docker ps -a --no-trunc --format "{{json .}}"` emits.
        List<String> lines = List.of(
                "{\"Names\":\"senvia_uat_backend\",\"Image\":\"senvia_backend:uat\","
                        + "\"State\":\"running\",\"Status\":\"Up 3 days\"}",
                "{\"Names\":\"solar-db-backup\",\"Image\":\"x\","
                        + "\"State\":\"exited\",\"Status\":\"Exited (0) 2 hours ago\"}",
                "{\"Names\":\"inventory-mysql\",\"Image\":\"mysql:8.0\","
                        + "\"State\":\"running\",\"Status\":\"Up 5 seconds (health: starting)\"}");

        List<ContainerReport> parsed = lines.stream().map(Json::parseDockerLine).toList();

        assertEquals(3, parsed.size());
        assertTrue(parsed.get(0).toCheckResult().isUp());
        assertTrue(parsed.get(1).toCheckResult().isDown());
        assertTrue(parsed.get(2).toCheckResult().isIndeterminate());
    }

    @Test
    @DisplayName("the hostname used in reports is never blank")
    void hostnameIsAlwaysSet() {
        assertFalse(Text.isBlank(new DockerAgent(cfg("docker", 30)).hostname()));
    }
}
