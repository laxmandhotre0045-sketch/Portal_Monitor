package portalmonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers how real `docker ps` output maps to UP / DOWN / INDETERMINATE. */
class ContainerReportTest {

    private static ContainerReport c(String state, String status) {
        return new ContainerReport("tms-backend", "tms-backend:latest", state, status);
    }

    // ---------------------------------------------------------------- health parsing

    @Test
    @DisplayName("the health suffix is read out of Docker's Status string")
    void healthParsing() {
        assertEquals(ContainerReport.Health.HEALTHY, c("running", "Up 2 hours (healthy)").health());
        assertEquals(ContainerReport.Health.UNHEALTHY, c("running", "Up 2 hours (unhealthy)").health());
        assertEquals(ContainerReport.Health.STARTING,
                c("running", "Up 5 seconds (health: starting)").health());
        assertEquals(ContainerReport.Health.NONE, c("running", "Up 3 days").health());
        assertEquals(ContainerReport.Health.NONE, c("exited", "Exited (0) 2 days ago").health());
    }

    @Test
    @DisplayName("(unhealthy) is not mistaken for (healthy)")
    void unhealthyIsNotHealthy() {
        // "(unhealthy)" contains the substring "healthy", so order of checks matters.
        assertEquals(ContainerReport.Health.UNHEALTHY, c("running", "Up 1 hour (unhealthy)").health());
        assertTrue(c("running", "Up 1 hour (unhealthy)").toCheckResult().isDown());
    }

    // ---------------------------------------------------------------- state mapping

    @Test
    @DisplayName("running with a passing healthcheck is UP")
    void runningHealthyIsUp() {
        CheckResult r = c("running", "Up 2 hours (healthy)").toCheckResult();
        assertTrue(r.isUp());
        assertEquals("Up 2 hours (healthy)", r.detail());
    }

    @Test
    @DisplayName("running with no healthcheck is UP")
    void runningWithoutHealthcheckIsUp() {
        // Most of the listed containers (the Java listeners, archiver, mosquitto) have none,
        // so State alone has to be enough.
        assertTrue(c("running", "Up 3 days").toCheckResult().isUp());
    }

    @Test
    @DisplayName("running but unhealthy is DOWN, and says so")
    void runningUnhealthyIsDown() {
        CheckResult r = c("running", "Up 10 minutes (unhealthy)").toCheckResult();
        assertTrue(r.isDown());
        assertTrue(r.detail().startsWith("unhealthy"), "detail was: " + r.detail());
    }

    @Test
    @DisplayName("running but still starting is INDETERMINATE")
    void runningStartingIsIndeterminate() {
        CheckResult r = c("running", "Up 5 seconds (health: starting)").toCheckResult();
        assertTrue(r.isIndeterminate());
        assertEquals("health: starting", r.detail());
    }

    @Test
    @DisplayName("every non-running state is DOWN, keeping Docker's own wording")
    void nonRunningStatesAreDown() {
        CheckResult exited = c("exited", "Exited (1) 5 minutes ago").toCheckResult();
        assertTrue(exited.isDown());
        assertEquals("Exited (1) 5 minutes ago", exited.detail());

        assertTrue(c("restarting", "Restarting (1) 3 seconds ago").toCheckResult().isDown());
        assertTrue(c("paused", "Up 2 hours (Paused)").toCheckResult().isDown());
        assertTrue(c("dead", "Dead").toCheckResult().isDown());
        assertTrue(c("created", "Created").toCheckResult().isDown());
    }

    @Test
    @DisplayName("a clean exit (0) still counts as DOWN")
    void cleanExitIsStillDown() {
        // solar-db-backup finishes and exits 0. That is DOWN for a long-running service,
        // so pause that row instead of special-casing exit code 0.
        assertTrue(c("exited", "Exited (0) 2 hours ago").toCheckResult().isDown());
    }

    @Test
    @DisplayName("a container missing from the report is DOWN with a clear reason")
    void missingContainerIsDown() {
        CheckResult r = ContainerReport.missing();
        assertTrue(r.isDown());
        assertEquals("Container not found on host", r.detail());
    }

    @Test
    @DisplayName("state matching ignores case and stray whitespace")
    void stateMatchingIsLenient() {
        assertTrue(new ContainerReport("x", "i", " Running ", "Up 1 hour").isRunning());
        assertTrue(new ContainerReport("x", "i", "RUNNING", "Up 1 hour").isRunning());
    }

    @Test
    @DisplayName("a long status is truncated to fit last_detail")
    void detailFitsColumn() {
        CheckResult r = c("exited", "Exited (1) " + "x".repeat(500)).toCheckResult();
        assertTrue(r.detail().length() <= 250, "detail was " + r.detail().length() + " chars");
    }

    // ---------------------------------------------------------------- docker ps parsing

    @Test
    @DisplayName("a real docker ps JSON line parses")
    void parsesDockerLine() {
        String line = """
                {"Command":"\\"/docker-entrypoint.…\\"","CreatedAt":"2026-09-01 10:00:00 +0530 IST",\
                "ID":"abc123","Image":"senvia_frontend:uat","Labels":"","LocalVolumes":"0",\
                "Mounts":"","Names":"senvia_uat_frontend","Networks":"bridge",\
                "Ports":"0.0.0.0:3001->3001/tcp","RunningFor":"3 days ago","Size":"0B",\
                "State":"running","Status":"Up 3 days (healthy)"}""";

        ContainerReport r = Json.parseDockerLine(line);
        assertEquals("senvia_uat_frontend", r.name());
        assertEquals("senvia_frontend:uat", r.image());
        assertEquals("running", r.state());
        assertEquals("Up 3 days (healthy)", r.status());
        assertTrue(r.toCheckResult().isUp());
    }

    @Test
    @DisplayName("only the first of several container names is used")
    void takesFirstName() {
        String line = "{\"Names\":\"tms-backend,tms_backend_alias\",\"Image\":\"i\","
                    + "\"State\":\"running\",\"Status\":\"Up 1 hour\"}";

        assertEquals("tms-backend", Json.parseDockerLine(line).name());
    }

    @Test
    @DisplayName("missing docker fields do not throw")
    void toleratesMissingFields() {
        ContainerReport r = Json.parseDockerLine("{\"Names\":\"lonely\"}");
        assertEquals("lonely", r.name());
        assertEquals("", r.state());
        assertTrue(r.toCheckResult().isDown(), "no State means not running, so DOWN");
    }
}
