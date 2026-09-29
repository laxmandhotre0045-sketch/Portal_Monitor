package portalmonitor;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Minimal timestamped console logging. systemd captures stdout/stderr, so
 * `journalctl -u portal-monitor` shows these lines as-is.
 */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile ZoneId zone = ZoneId.systemDefault();

    private Log() { }

    /** Called once at startup so log timestamps use app.timezone. */
    static void useZone(ZoneId z) {
        zone = z;
    }

    public static void info(String msg) {
        System.out.println(stamp() + " " + msg);
    }

    public static void error(String msg) {
        System.err.println(stamp() + " ERROR " + msg);
    }

    private static String stamp() {
        return "[" + FMT.format(ZonedDateTime.now(zone)) + "]";
    }
}
