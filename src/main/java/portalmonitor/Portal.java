package portalmonitor;

import java.time.LocalDateTime;

/** One row of the portals table: a single thing we monitor. */
public record Portal(
        int id,
        String name,
        String appGroup,
        CheckType checkType,
        String containerName,   // set for CONTAINER checks
        String healthUrl,       // set for HTTP checks
        String notifyEmail,
        String status,          // UNKNOWN / UP / DOWN
        int failCount,
        LocalDateTime failSince,
        LocalDateTime lastStatusChange) {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String UP = "UP";
    public static final String DOWN = "DOWN";

    public enum CheckType { CONTAINER, HTTP }

    /** What the check actually targets, for logs and emails. */
    public String target() {
        return checkType == CheckType.CONTAINER ? containerName : healthUrl;
    }
}
