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

    /** When this process started; the watchdog gives agents this long to check in. */
    private final LocalDateTime startedAt;
    /** Set once the "no agent has ever reported" alert went out, so it is sent once per start. */
    private boolean noAgentAlerted;
    /** HTTP rows already warned about for a placeholder URL - warned once, not every minute. */
    private final java.util.Set<Integer> placeholderWarned = new java.util.HashSet<>();

    /** Database reachability, shared with /ready. */
    private final DbStatus dbStatus;
    /** Readiness probes may run at most this often; between them the last answer is reused. */
    static final java.time.Duration READY_PROBE_EVERY = java.time.Duration.ofSeconds(15);
    private final Object readyProbeLock = new Object();

    // Written by the scheduler thread, read by /ready.
    private volatile LocalDateTime lastCycleOk;
    private volatile LocalDateTime lastCycleFailed;
    private volatile String lastCycleError;
    private volatile LocalDateTime lastWatchdogOk;

    public MonitorService(Config cfg) {
        this.cfg = cfg;
        this.httpChecker = new HttpChecker(cfg);
        this.mailer = new EmailService(cfg);
        this.startedAt = LocalDateTime.now(cfg.zone).withNano(0);
        this.dbStatus = new DbStatus(cfg);
    }

    /** Thrown when no connection could be opened; DbStatus has already logged why. */
    static final class DatabaseUnavailableException extends Exception {
        DatabaseUnavailableException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** Every connection goes through here, so DbStatus always reflects the latest attempt. */
    private Database openDb() throws DatabaseUnavailableException {
        LocalDateTime now = LocalDateTime.now(cfg.zone).withNano(0);
        try {
            Database db = new Database(cfg);
            dbStatus.succeeded(now);
            return db;
        } catch (Exception e) {
            dbStatus.failed(e, now);
            throw new DatabaseUnavailableException(e);
        }
    }

    public DbStatus dbStatus() {
        return dbStatus;
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
        try (Database db = openDb()) {
            List<Portal> portals = new ArrayList<>();
            for (Portal p : db.activePortalsOfType(Portal.CheckType.HTTP)) {
                if (isPlaceholderUrl(p.healthUrl())) {
                    // Never alert on a row that was never configured: it would be a false DOWN.
                    if (placeholderWarned.add(p.id())) {
                        Log.error("Skipping " + p.name() + ": health_url is not configured ("
                                + p.healthUrl() + "). Set a real URL or set is_active=0.");
                    }
                } else {
                    portals.add(p);
                }
            }
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
            lastCycleOk = LocalDateTime.now(cfg.zone).withNano(0);
            lastCycleError = null;
            return true;
        } catch (DatabaseUnavailableException e) {
            // Already logged (once, with what to check) by DbStatus.
            cycleFailed("database unavailable");
            return false;
        } catch (Exception e) {
            Log.error("HTTP cycle failed: " + Text.oneLine(e.getMessage()));
            cycleFailed(Text.clean(e.getMessage(), 200));
            return false;
        }
    }

    private void cycleFailed(String why) {
        lastCycleFailed = LocalDateTime.now(cfg.zone).withNano(0);
        lastCycleError = why;
    }

    // ------------------------------------------------------------------ agent reports

    /**
     * Applies a container report from the Windows host. Containers listed in portals but
     * absent from the report are treated as DOWN ("Container not found on host").
     */
    public synchronized void applyAgentReport(String agentId, String hostname,
                                              List<ContainerReport> containers) {
        try (Database db = openDb()) {
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
        } catch (DatabaseUnavailableException e) {
            // Already logged by DbStatus. The API also answered the agent with 503.
        } catch (Exception e) {
            Log.error("Could not apply agent report from " + agentId + ": " + Text.oneLine(e.getMessage()));
        }
    }

    /**
     * Sends SERVER OFFLINE once when an agent goes quiet, and puts its container checks
     * back to UNKNOWN so they do not sit there claiming a stale UP or DOWN.
     */
    public synchronized void checkAgentWatchdog() {
        try (Database db = openDb()) {
            LocalDateTime now = LocalDateTime.now(cfg.zone).withNano(0);
            List<Database.Agent> agents = db.allAgents();

            // No agent has ever reported: container checks would sit at UNKNOWN forever with
            // nobody told. Say so once, after giving the agent a full window to check in.
            if (agents.isEmpty()) {
                long sinceStart = java.time.Duration.between(startedAt, now).getSeconds();
                if (!noAgentAlerted && sinceStart >= cfg.agentOfflineAfterSeconds
                        && !db.activePortalsOfType(Portal.CheckType.CONTAINER).isEmpty()) {
                    noAgentAlerted = true;
                    Log.error("No agent has reported in " + sinceStart + "s since startup - "
                            + "container checks are NOT being evaluated.");
                    sendAgentAlert(db, "AGENT_OFFLINE", "(none)", "Docker agent", null, now);
                }
                return;
            }

            for (Database.Agent a : agents) {
                if (!Database.Agent.ONLINE.equals(a.status())) continue;
                if (a.lastSeen() == null) continue;

                long quietSeconds = quietSeconds(a.lastSeen(), reportsReceived.get(a.agentId()), startedAt, now);
                if (quietSeconds < cfg.agentOfflineAfterSeconds) continue;

                db.setAgentStatus(a.agentId(), Database.Agent.OFFLINE);
                int reset = db.resetContainerChecksToUnknown(now);
                long silent = java.time.Duration.between(a.lastSeen(), now).getSeconds();
                Log.error("Agent " + a.agentId() + " (" + a.hostname() + ") has not reported for "
                        + silent + "s - marking OFFLINE, " + reset
                        + " UP container check(s) set to UNKNOWN (DOWN ones stay DOWN).");
                sendAgentAlert(db, "AGENT_OFFLINE", a.agentId(), a.hostname(), a.lastSeen(), now);
            }
            lastWatchdogOk = now;
        } catch (DatabaseUnavailableException e) {
            // Already logged by DbStatus; nothing can be judged without the database.
        } catch (Exception e) {
            Log.error("Agent watchdog failed: " + Text.oneLine(e.getMessage()));
        }
    }

    /** agentId -> when its last report reached the API, whether or not the DB could store it. */
    private final java.util.concurrent.ConcurrentHashMap<String, LocalDateTime> reportsReceived =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Called by the API the moment an authenticated report arrives - the agent's heartbeat. */
    public void noteReportReceived(String agentId) {
        reportsReceived.put(agentId, LocalDateTime.now(cfg.zone).withNano(0));
    }

    /**
     * How long an agent has been silent, for the watchdog. Measured from the latest of:
     *   - last_seen in the database;
     *   - the last report that reached this process (stored or not - while MySQL was down
     *     reports still arrived, and the agent must not be blamed for that);
     *   - this server's start (while the server was down the agent's reports were refused,
     *     so it gets a fresh window; otherwise every restart longer than
     *     agent.offlineAfterSeconds sent a false SERVER OFFLINE / BACK ONLINE pair).
     */
    static long quietSeconds(LocalDateTime lastSeenDb, LocalDateTime lastReceived,
                             LocalDateTime startedAt, LocalDateTime now) {
        LocalDateTime from = startedAt;
        if (lastSeenDb != null && lastSeenDb.isAfter(from)) from = lastSeenDb;
        if (lastReceived != null && lastReceived.isAfter(from)) from = lastReceived;
        return Math.max(0, java.time.Duration.between(from, now).getSeconds());
    }

    /** True for rows still carrying the seed placeholder, or with no URL at all. */
    static boolean isPlaceholderUrl(String url) {
        return url == null || url.isBlank() || url.contains("SERVER_PUBLIC_IP");
    }

    // ------------------------------------------------------------------ shared internals

    /** Records one result, updates the row, and queues an email if the state changed. */
    private boolean apply(Database db, Portal p, CheckResult r, LocalDateTime now,
                          List<PendingAlert> alerts) throws Exception {
        db.insertCheckLog(p.id(), r, now);
        Decision d = decide(p, r, now, cfg.failThreshold);

        // Persist first, queue the email second: if the UPDATE fails, the state change is
        // re-detected next cycle and the email goes out then - once, not twice.
        db.updatePortal(p.id(), d.status(), d.failCount(), d.failSince(), r, now, d.statusChange());

        if (d.alert() != null) {
            LocalDateTime since = "RECOVERED".equals(d.alert())
                    ? (p.failSince() != null ? p.failSince() : now)
                    : d.failSince();
            alerts.add(new PendingAlert(p, r, since, d.failCount(), d.alert()));
        }

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

    /** The answer behind GET /ready. */
    public record Readiness(boolean ready, String json) { }

    /**
     * Readiness: is this server actually able to do its job right now?
     *
     *   database  - a real connection + query, at most every READY_PROBE_EVERY (cheap: one
     *               connection every 15s, never blocked behind a running cycle).
     *   httpCycle - the scheduled cycle completed recently. Catches a stuck or dying
     *               scheduler, which a database probe alone would not.
     *
     * /health stays a pure liveness answer ("the process is up") for compatibility.
     */
    public Readiness readiness() {
        LocalDateTime now = LocalDateTime.now(cfg.zone).withNano(0);
        synchronized (readyProbeLock) {
            LocalDateTime last = dbStatus.lastAttempt();
            if (last == null || java.time.Duration.between(last, now).compareTo(READY_PROBE_EVERY) >= 0) {
                try (Database db = openDb()) {
                    db.countActivePortals();
                } catch (DatabaseUnavailableException e) {
                    // recorded by openDb
                } catch (Exception e) {
                    // Connected, but the query failed: missing table or privilege.
                    dbStatus.failed(e, now);
                }
            }
        }

        List<String> reasons = new ArrayList<>();
        DbStatus.State db = dbStatus.state();
        if (db != DbStatus.State.UP) {
            reasons.add("database " + db + (dbStatus.reason() == null ? "" : ": " + dbStatus.reason()));
        }

        // A cycle may legitimately take a while (slow sites, email retries), so allow
        // three intervals plus two minutes before calling it stale.
        long maxAge = 3L * cfg.intervalSeconds + 120;
        String cycleState;
        LocalDateTime ok = lastCycleOk;
        if (ok != null && java.time.Duration.between(ok, now).getSeconds() <= maxAge) {
            cycleState = "OK";
        } else if (ok == null && java.time.Duration.between(startedAt, now).getSeconds() <= maxAge) {
            cycleState = "STARTING";
            reasons.add("first monitoring cycle has not completed yet");
        } else {
            cycleState = lastCycleError != null ? "FAILING" : "STALE";
            reasons.add("no successful monitoring cycle for over " + maxAge + "s"
                    + (lastCycleError != null ? " (last error: " + lastCycleError + ")" : ""));
        }

        boolean ready = reasons.isEmpty();
        org.json.JSONObject agents = new org.json.JSONObject();
        reportsReceived.forEach((id, at) -> agents.put(id, Json.TS.format(at)));

        String json = new org.json.JSONObject()
                .put("status", ready ? "READY" : "NOT_READY")
                .put("checkedAt", Json.TS.format(now))
                .put("startedAt", Json.TS.format(startedAt))
                .put("reasons", reasons)
                .put("database", new org.json.JSONObject()
                        .put("status", db.name())
                        .put("lastOk", ts(dbStatus.lastOk()))
                        .put("downSince", ts(dbStatus.downSince()))
                        .put("failedAttempts", dbStatus.failuresInRow())
                        .put("error", dbStatus.reason() == null ? org.json.JSONObject.NULL : dbStatus.reason()))
                .put("monitoringCycle", new org.json.JSONObject()
                        .put("status", cycleState)
                        .put("intervalSeconds", cfg.intervalSeconds)
                        .put("lastOk", ts(ok))
                        .put("lastFailure", ts(lastCycleFailed))
                        .put("error", lastCycleError == null ? org.json.JSONObject.NULL : lastCycleError))
                .put("agentWatchdog", new org.json.JSONObject()
                        .put("lastOk", ts(lastWatchdogOk))
                        .put("lastReportReceived", agents))
                .toString(2);
        return new Readiness(ready, json);
    }

    private static Object ts(LocalDateTime t) {
        return t == null ? org.json.JSONObject.NULL : Json.TS.format(t);
    }

    /** Server startup: say plainly, once, whether the database works before the first cycle. */
    public void logStartupDatabaseCheck() {
        try (Database db = openDb()) {
            int n = db.countActivePortals();
            Log.info("Startup check: " + n + " active check(s) in the database.");
        } catch (DatabaseUnavailableException e) {
            // DbStatus logged the reason and what to check.
        } catch (Exception e) {
            dbStatus.failed(e, LocalDateTime.now(cfg.zone).withNano(0));
        }
    }

    public boolean testDatabase() {
        Log.info("Testing database: " + cfg.dbUser + "@" + cfg.dbHost + ":" + cfg.dbPort + "/" + cfg.dbName
                + " (password " + (cfg.dbPassword.isEmpty() ? "NOT SET" : "set") + ")");
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
            String[] d = DbStatus.describe(e, cfg.dbUser);
            Log.error("Database connection FAILED: " + d[0]);
            Log.error("What to check: " + d[1]);
            return false;
        }
    }

    /** Backs the GET /api/status endpoint. */
    public String statusJson() throws Exception {
        try (Database db = openDb()) {
            return Json.status(db.activePortals(), db.allAgents(),
                    LocalDateTime.now(cfg.zone).withNano(0));
        }
    }
}
