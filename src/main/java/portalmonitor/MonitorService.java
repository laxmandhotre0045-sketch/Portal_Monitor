package portalmonitor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the UP/DOWN state logic and decides when email goes out.
 *
 * Two entry points, both serialised on this object so a cycle and an incoming agent
 * report can never interleave:
 *   runHttpCycle()     - the server's own HTTP checks, every check.intervalSeconds
 *   applyAgentReport() - container checks, evaluated when a report arrives
 */
public final class MonitorService {

    private final Config cfg;
    private final HttpChecker httpChecker;
    private final EmailService mailer;

    public MonitorService(Config cfg) {
        this.cfg = cfg;
        this.httpChecker = new HttpChecker(cfg);
        this.mailer = new EmailService(cfg);
    }

    /** What one check result means for one portal. alert is null, "DOWN" or "RECOVERED". */
    public record Decision(String status, int failCount, LocalDateTime failSince,
                           LocalDateTime statusChange, String alert) { }

    /** A state change that still needs an email. */
    public record PendingAlert(Portal portal, CheckResult result, LocalDateTime failSince,
                               int failCount, String type) { }

    /**
     * The whole state machine as a pure function - no database, no email.
     * This is what the unit tests drive.
     */
    public static Decision decide(Portal p, CheckResult r, LocalDateTime now, int failThreshold) {
        // "health: starting" is neither a pass nor a failure: hold everything as it was,
        // so a restarting container neither alerts nor clears an existing failure streak.
        if (r.isIndeterminate()) {
            return new Decision(p.status(), p.failCount(), p.failSince(), p.lastStatusChange(), null);
        }

        if (r.isUp()) {
            // Healthy: the streak is over. Alert only if we had already declared it DOWN.
            String alert = Portal.DOWN.equals(p.status()) ? "RECOVERED" : null;
            boolean changed = !Portal.UP.equals(p.status());
            return new Decision(Portal.UP, 0, null, changed ? now : p.lastStatusChange(), alert);
        }

        // Failing: count consecutive failures, remember when the streak began.
        int failCount = p.failCount() + 1;
        LocalDateTime failSince = p.failSince() != null ? p.failSince() : now;
        boolean cross = !Portal.DOWN.equals(p.status()) && failCount >= failThreshold;
        return new Decision(
                cross ? Portal.DOWN : p.status(),
                failCount,
                failSince,
                cross ? now : p.lastStatusChange(),
                cross ? "DOWN" : null);
    }

    // ------------------------------------------------------------------ HTTP cycle

    /** Runs all HTTP checks. Returns false only if the cycle itself broke (e.g. DB down). */
    public synchronized boolean runHttpCycle() {
        try (Database db = new Database(cfg)) {
            List<Portal> portals = db.activePortalsOfType(Portal.CheckType.HTTP);
            LocalDateTime now = LocalDateTime.now(cfg.zone).withNano(0);

            if (!portals.isEmpty()) {
                Map<Integer, CheckResult> results = httpChecker.checkAll(portals);
                List<PendingAlert> alerts = new ArrayList<>();
                int up = 0, down = 0;
                for (Portal p : portals) {
                    CheckResult r = results.get(p.id());
                    if (r == null) r = CheckResult.down("No check result");
                    try {
                        if (apply(db, p, r, now, alerts)) up++; else down++;
                    } catch (Exception e) {
                        Log.error("Failed to process " + p.name() + ": " + e);
                    }
                }
                sendGroupedAlerts(db, alerts, now);
                Log.info("HTTP cycle done: " + portals.size() + " check(s), " + up + " up, " + down + " not up.");
            } else {
                Log.info("No active HTTP checks configured.");
            }

            housekeeping(db);
            return true;
        } catch (Exception e) {
            Log.error("HTTP cycle failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ agent reports

    /**
     * Applies a container report from the Windows host. Containers listed in portals but
     * absent from the report are treated as DOWN ("Container not found on host").
     */
    public synchronized void applyAgentReport(String agentId, String hostname,
                                              List<ContainerReport> containers) {
        try (Database db = new Database(cfg)) {
            LocalDateTime now = LocalDateTime.now(cfg.zone).withNano(0);

            // An agent that was offline coming back is its own alert, and must be sent
            // before the per-container verdicts so the story reads in order.
            Database.Agent before = db.findAgent(agentId);
            db.markAgentSeen(agentId, hostname, now);
            if (before != null && Database.Agent.OFFLINE.equals(before.status())) {
                sendAgentAlert(db, "AGENT_ONLINE", agentId, hostname, before.lastSeen(), now);
            }

            Map<String, ContainerReport> byName = new LinkedHashMap<>();
            for (ContainerReport c : containers) {
                if (c.name() != null) byName.put(c.name(), c);
            }

            List<Portal> portals = db.activePortalsOfType(Portal.CheckType.CONTAINER);
            List<PendingAlert> alerts = new ArrayList<>();
            int up = 0, down = 0, starting = 0;

            for (Portal p : portals) {
                ContainerReport c = byName.get(p.containerName());
                CheckResult r = (c == null) ? ContainerReport.missing() : c.toCheckResult();
                try {
                    if (r.isIndeterminate()) {
                        starting++;
                        // still recorded in check_logs, but the state is untouched
                        apply(db, p, r, now, alerts);
                    } else if (apply(db, p, r, now, alerts)) {
                        up++;
                    } else {
                        down++;
                    }
                } catch (Exception e) {
                    Log.error("Failed to process " + p.name() + ": " + e);
                }
            }

            sendGroupedAlerts(db, alerts, now);
            Log.info("Agent report from " + agentId + " (" + hostname + "): "
                    + containers.size() + " container(s) reported, " + portals.size() + " monitored, "
                    + up + " up, " + down + " not up, " + starting + " starting.");
        } catch (Exception e) {
            Log.error("Could not apply agent report from " + agentId + ": " + e.getMessage());
        }
    }

    /**
     * Sends SERVER OFFLINE once when an agent goes quiet, and puts its container checks
     * back to UNKNOWN so they do not sit there claiming a stale UP or DOWN.
     */
    public synchronized void checkAgentWatchdog() {
        try (Database db = new Database(cfg)) {
            LocalDateTime now = LocalDateTime.now(cfg.zone).withNano(0);
            for (Database.Agent a : db.allAgents()) {
                if (!Database.Agent.ONLINE.equals(a.status())) continue;
                if (a.lastSeen() == null) continue;

                long quietSeconds = java.time.Duration.between(a.lastSeen(), now).getSeconds();
                if (quietSeconds < cfg.agentOfflineAfterSeconds) continue;

                db.setAgentStatus(a.agentId(), Database.Agent.OFFLINE);
                int reset = db.resetContainerChecksToUnknown(now);
                Log.error("Agent " + a.agentId() + " (" + a.hostname() + ") has not reported for "
                        + quietSeconds + "s - marking OFFLINE, " + reset + " container check(s) set to UNKNOWN.");
                sendAgentAlert(db, "AGENT_OFFLINE", a.agentId(), a.hostname(), a.lastSeen(), now);
            }
        } catch (Exception e) {
            Log.error("Agent watchdog failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ shared internals

    /** Records one result, updates the row, and queues an email if the state changed. */
    private boolean apply(Database db, Portal p, CheckResult r, LocalDateTime now,
                          List<PendingAlert> alerts) throws Exception {
        db.insertCheckLog(p.id(), r, now);
        Decision d = decide(p, r, now, cfg.failThreshold);

        if (d.alert() != null) {
            LocalDateTime since = "RECOVERED".equals(d.alert())
                    ? (p.failSince() != null ? p.failSince() : now)
                    : d.failSince();
            alerts.add(new PendingAlert(p, r, since, d.failCount(), d.alert()));
        }

        db.updatePortal(p.id(), d.status(), d.failCount(), d.failSince(), r, now, d.statusChange());

        Log.info(String.format("%-32s %-8s %-7s %s",
                p.name(), d.status(),
                r.isIndeterminate() ? "start" : (r.isUp() ? "up" : "fail=" + d.failCount()),
                r.detail() == null ? "" : r.detail()));
        return r.isUp();
    }

    /**
     * One email per recipient per kind, rather than twenty separate emails when a host
     * reboots and every container goes down at once.
     */
    private void sendGroupedAlerts(Database db, List<PendingAlert> alerts, LocalDateTime now) {
        if (alerts.isEmpty()) return;

        // recipient -> type -> alerts, preserving the order checks were evaluated in
        Map<String, Map<String, List<PendingAlert>>> byRecipient = new LinkedHashMap<>();
        for (PendingAlert a : alerts) {
            String to = recipientFor(a.portal());
            byRecipient
                    .computeIfAbsent(to, k -> new LinkedHashMap<>())
                    .computeIfAbsent(a.type(), k -> new ArrayList<>())
                    .add(a);
        }

        byRecipient.forEach((to, byType) -> byType.forEach((type, group) -> {
            EmailTemplates.Email email = EmailTemplates.stateChange(type, group, now);
            EmailService.SendResult res = sendTo(to, email);

            Log.info("  -> " + type + " email for " + group.size() + " check(s) to "
                    + (Text.isBlank(to) ? "(nobody)" : to)
                    + ": " + (res.ok() ? "sent" : "FAILED (" + res.error() + ")"));

            // One alert_logs row per portal, so each check keeps its own alert history
            // even when they shared a combined email.
            for (PendingAlert a : group) {
                try {
                    db.insertAlertLog(a.portal().id(), type, to == null ? "" : to,
                            res.ok(), res.error(), now);
                } catch (Exception e) {
                    Log.error("Could not write alert_logs for " + a.portal().name() + ": " + e.getMessage());
                }
            }
        }));
    }

    private void sendAgentAlert(Database db, String type, String agentId, String hostname,
                                LocalDateTime since, LocalDateTime now) {
        EmailTemplates.Email email = EmailTemplates.agentAlert(type, agentId, hostname, since, now);
        String to = Text.isBlank(cfg.defaultEmail) ? "" : cfg.defaultEmail;
        EmailService.SendResult res = sendTo(to, email);

        Log.info("  -> " + type + " email to " + (Text.isBlank(to) ? "(nobody)" : to)
                + ": " + (res.ok() ? "sent" : "FAILED (" + res.error() + ")"));
        try {
            db.insertAlertLog(null, type, to, res.ok(), res.error(), now);
        } catch (Exception e) {
            Log.error("Could not write alert_logs for " + type + ": " + e.getMessage());
        }
    }

    private EmailService.SendResult sendTo(String to, EmailTemplates.Email email) {
        if (Text.isBlank(to)) {
            return new EmailService.SendResult(false,
                    "No recipient: notify_email is empty and alert.defaultEmail is not set");
        }
        return mailer.send(to, email.subject(), email.html(), email.text());
    }

    /** A portal's own recipients, or the configured fallback when the column is empty. */
    private String recipientFor(Portal p) {
        return Text.isBlank(p.notifyEmail()) ? cfg.defaultEmail : p.notifyEmail().trim();
    }

    private void housekeeping(Database db) {
        try {
            int deleted = db.deleteOldLogs(cfg.logRetentionDays);
            if (deleted > 0) {
                Log.info("Housekeeping: deleted " + deleted + " check_logs rows older than "
                        + cfg.logRetentionDays + " days.");
            }
        } catch (Exception e) {
            Log.error("Housekeeping failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ diagnostics

    public boolean sendTestEmail(String to) {
        EmailTemplates.Email e = EmailTemplates.test(cfg);
        EmailService.SendResult r = mailer.send(to, e.subject(), e.html(), e.text());
        if (r.ok()) {
            Log.info("Test email sent to " + to + ". If it does not arrive, check the Spam folder.");
        } else {
            Log.error("Test email FAILED: " + r.error());
        }
        return r.ok();
    }

    public boolean testDatabase() {
        try (Database db = new Database(cfg)) {
            List<Portal> all = db.activePortals();
            long http = all.stream().filter(p -> p.checkType() == Portal.CheckType.HTTP).count();
            Log.info("Database OK: " + all.size() + " active check(s) - "
                    + (all.size() - http) + " CONTAINER, " + http + " HTTP.");
            for (Database.Agent a : db.allAgents()) {
                Log.info("  agent " + a.agentId() + " (" + a.hostname() + ") " + a.status()
                        + ", last seen " + a.lastSeen());
            }
            return true;
        } catch (Exception e) {
            Log.error("Database connection FAILED: " + e.getMessage());
            return false;
        }
    }

    /** Backs the GET /api/status endpoint. */
    public String statusJson() throws Exception {
        try (Database db = new Database(cfg)) {
            return Json.status(db.activePortals(), db.allAgents(),
                    LocalDateTime.now(cfg.zone).withNano(0));
        }
    }
}
