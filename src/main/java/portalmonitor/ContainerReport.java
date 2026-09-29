package portalmonitor;

/**
 * One container as the agent saw it, parsed from
 * {@code docker ps -a --no-trunc --format "{{json .}}"}.
 *
 * {@code state} is Docker's State field: running, exited, restarting, paused, dead, created.
 * {@code status} is the human string, which is also where the health suffix lives:
 * "Up 2 hours (healthy)", "Up 5 seconds (health: starting)", "Exited (1) 5 minutes ago".
 */
public record ContainerReport(String name, String image, String state, String status) {

    public enum Health {
        /** the container declares a healthcheck and it passes */
        HEALTHY,
        /** the healthcheck is failing */
        UNHEALTHY,
        /** still inside start_period - no verdict yet */
        STARTING,
        /** no healthcheck declared, so State alone decides */
        NONE
    }

    /** Reads the health suffix out of Docker's Status string. */
    public Health health() {
        String s = status == null ? "" : status.toLowerCase();
        if (s.contains("(health: starting)")) return Health.STARTING;
        if (s.contains("(unhealthy)")) return Health.UNHEALTHY;
        if (s.contains("(healthy)")) return Health.HEALTHY;
        return Health.NONE;
    }

    public boolean isRunning() {
        return "running".equalsIgnoreCase(state == null ? "" : state.trim());
    }

    /**
     * Turns one container's Docker state into a check result.
     *
     *   UP            - running, and either healthy or without a healthcheck
     *   DOWN          - not running, or the healthcheck is failing
     *   INDETERMINATE - running but still starting up
     */
    public CheckResult toCheckResult() {
        if (!isRunning()) {
            // Docker's own wording is the most useful detail here,
            // e.g. "Exited (1) 5 minutes ago" or "Restarting (1) 3 seconds ago".
            String detail = (status == null || status.isBlank())
                    ? state
                    : status;
            return CheckResult.down(Text.clean(detail, 250));
        }
        return switch (health()) {
            case UNHEALTHY -> CheckResult.down("unhealthy - " + Text.clean(status, 230));
            case STARTING -> CheckResult.starting("health: starting");
            case HEALTHY -> CheckResult.up(Text.clean(status, 250));
            case NONE -> CheckResult.up(Text.clean(status, 250));
        };
    }

    /** Used when a monitored container is absent from the agent report entirely. */
    public static CheckResult missing() {
        return CheckResult.down("Container not found on host");
    }
}
