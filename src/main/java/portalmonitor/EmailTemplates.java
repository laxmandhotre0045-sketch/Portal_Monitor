package portalmonitor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the subject, HTML body and plain-text body of every alert. */
public final class EmailTemplates {

    public record Email(String subject, String html, String text) { }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String RED = "#c0392b";
    private static final String GREEN = "#27ae60";
    private static final String BLUE = "#2c6fbb";
    private static final String RED_CIRCLE = "🔴";
    private static final String GREEN_CIRCLE = "🟢";

    private EmailTemplates() { }

    // ------------------------------------------------------------------ state changes

    /**
     * One email covering every check that just changed state, grouped by app_group.
     * A single check gets a named subject; several get a counted one.
     */
    public static Email stateChange(String type, List<MonitorService.PendingAlert> alerts,
                                    LocalDateTime now) {
        boolean down = "DOWN".equals(type);
        String circle = down ? RED_CIRCLE : GREEN_CIRCLE;
        String colour = down ? RED : GREEN;
        String word = down ? "DOWN" : "RECOVERED";

        String subject;
        String title;
        if (alerts.size() == 1) {
            String name = alerts.get(0).portal().name();
            subject = circle + " " + word + ": " + name;
            title = down ? name + " is DOWN" : name + " is back UP";
        } else {
            subject = circle + " " + alerts.size() + " services " + word;
            title = alerts.size() + " services " + word;
        }

        // app_group -> its alerts, so the email reads like the inventory
        Map<String, List<MonitorService.PendingAlert>> byGroup = new LinkedHashMap<>();
        for (MonitorService.PendingAlert a : alerts) {
            String g = Text.isBlank(a.portal().appGroup()) ? "Ungrouped" : a.portal().appGroup();
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(a);
        }

        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder(title).append("\n\n");

        byGroup.forEach((group, list) -> {
            html.append("<tr><td colspan=\"2\" style=\"padding:14px 12px 6px;font-size:13px;")
                .append("font-weight:bold;color:#333;background:#f7f7f9;border-top:1px solid #e3e3e8\">")
                .append(esc(group)).append("</td></tr>");
            text.append("== ").append(group).append(" ==\n");

            for (MonitorService.PendingAlert a : list) {
                Portal p = a.portal();
                Map<String, String> rows = new LinkedHashMap<>();
                rows.put("Check type", p.checkType().name()
                        + (p.checkType() == Portal.CheckType.CONTAINER ? " (Docker)" : ""));
                rows.put(p.checkType() == Portal.CheckType.CONTAINER ? "Container" : "URL",
                        p.target() == null ? "-" : p.target());
                rows.put("Detail", a.result().detail() == null ? "-" : a.result().detail());

                if (down) {
                    rows.put("Failing since", FMT.format(a.failSince()));
                    rows.put("Consecutive failures", String.valueOf(a.failCount()));
                    rows.put("Detected at", FMT.format(now));
                } else {
                    if (a.result().responseMs() > 0) {
                        rows.put("Response time", a.result().responseMs() + " ms");
                    }
                    rows.put("Was down from", FMT.format(a.failSince()));
                    rows.put("Recovered at", FMT.format(now));
                    rows.put("Total downtime", humanDuration(Duration.between(a.failSince(), now)));
                }

                html.append("<tr><td colspan=\"2\" style=\"padding:10px 12px 2px;font-size:14px;")
                    .append("font-weight:bold;color:").append(colour).append("\">")
                    .append(esc(p.name())).append("</td></tr>");
                text.append("  ").append(p.name()).append('\n');

                rows.forEach((k, v) -> {
                    html.append(row(k, v));
                    text.append(String.format("    %-22s%s%n", k + ":", v));
                });
                text.append('\n');
            }
        });

        return new Email(subject, wrap(title, colour, html.toString()), text.toString());
    }

    // ------------------------------------------------------------------ agent watchdog

    /**
     * AGENT_OFFLINE / AGENT_ONLINE. These are about the Windows host itself, so they say
     * plainly that container status is unknown rather than implying every app died.
     */
    public static Email agentAlert(String type, String agentId, String hostname,
                                  LocalDateTime since, LocalDateTime now) {
        boolean offline = "AGENT_OFFLINE".equals(type);
        String host = Text.isBlank(hostname) ? agentId : hostname;

        String subject = (offline ? RED_CIRCLE + " SERVER OFFLINE: " : GREEN_CIRCLE + " SERVER BACK ONLINE: ")
                + host;
        String title = offline ? host + " is not reporting" : host + " is reporting again";

        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("Host", host);
        rows.put("Agent id", agentId);
        if (offline) {
            rows.put("Last report", since == null ? "never" : FMT.format(since));
            rows.put("Detected at", FMT.format(now));
            rows.put("What this means",
                    "The Windows Docker server or its internet connection is down. "
                  + "Container status is unknown, so container checks have been set to UNKNOWN "
                  + "and no per-container emails were sent.");
        } else {
            rows.put("Offline since", since == null ? "unknown" : FMT.format(since));
            rows.put("Back online at", FMT.format(now));
            if (since != null) {
                rows.put("Outage duration", humanDuration(Duration.between(since, now)));
            }
            rows.put("What happens next",
                    "Container states are being re-evaluated from the incoming reports.");
        }

        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder(title).append("\n\n");
        rows.forEach((k, v) -> {
            html.append(row(k, v));
            text.append(String.format("%-20s%s%n", k + ":", v));
        });
        text.append("\nAutomated alert from Portal Monitor.\n");

        return new Email(subject, wrap(title, offline ? RED : GREEN, html.toString()), text.toString());
    }

    // ------------------------------------------------------------------ test email

    public static Email test(Config cfg) {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("SMTP host", cfg.smtpHost + ":" + cfg.smtpPort + " (" + cfg.smtpSecurity + ")");
        rows.put("From", cfg.smtpFrom);
        rows.put("Database", cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbName);
        rows.put("Check interval", cfg.intervalSeconds + "s");
        rows.put("Fail threshold", cfg.failThreshold + " consecutive failures");
        rows.put("Agent offline after", cfg.agentOfflineAfterSeconds + "s without a report");
        rows.put("Sent at", FMT.format(LocalDateTime.now(cfg.zone).withNano(0)));

        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder("SMTP is working").append("\n\n");
        rows.forEach((k, v) -> {
            html.append(row(k, v));
            text.append(String.format("%-22s%s%n", k + ":", v));
        });
        return new Email("Portal Monitor - test email",
                wrap("SMTP is working", BLUE, html.toString()), text.toString());
    }

    // ------------------------------------------------------------------ helpers

    /** 0s, 45s, "5m 30s", "1h 12m", "2d 3h" - smaller units drop once hours appear. */
    static String humanDuration(Duration d) {
        long s = Math.max(0, d.getSeconds());
        long days = s / 86400; s %= 86400;
        long h = s / 3600;     s %= 3600;
        long m = s / 60;       s %= 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0 || (days == 0 && h == 0)) sb.append(s).append("s");
        return sb.toString().trim();
    }

    private static String row(String k, String v) {
        return "<tr><td style=\"padding:6px 12px;color:#555;border-bottom:1px solid #eee;"
             + "white-space:nowrap;vertical-align:top\">" + esc(k) + "</td>"
             + "<td style=\"padding:6px 12px;border-bottom:1px solid #eee;word-break:break-word\">"
             + esc(v) + "</td></tr>";
    }

    private static String wrap(String title, String colour, String bodyRows) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
             + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
             + "<body style=\"margin:0;padding:16px;background:#f4f5f7\">"
             + "<div style=\"font-family:Arial,Helvetica,sans-serif;max-width:640px;margin:0 auto;"
             + "background:#fff;border-radius:6px;overflow:hidden\">"
             + "<div style=\"background:" + colour + ";color:#fff;padding:14px 16px;font-size:18px;"
             + "font-weight:bold\">" + esc(title) + "</div>"
             + "<table style=\"border-collapse:collapse;width:100%;font-size:14px\">" + bodyRows + "</table>"
             + "<p style=\"color:#888;font-size:12px;margin:12px 16px\">"
             + "Automated alert from Portal Monitor.</p></div></body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
