package portalmonitor;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point. One jar, two modes.
 *
 *   --mode server   runs the API, the HTTP checks, the watchdog and the emails (on the VPS)
 *   --mode agent    reports docker ps to the server (on the Windows Docker host)
 */
public final class App {

    private static final String USAGE = """
            Portal Monitor 2.1.0 - Docker and HTTP uptime monitoring with email alerts

            Usage:
              java -jar portal-monitor.jar --mode server            run the server (VPS)
              java -jar portal-monitor.jar --mode agent             run the agent (Windows Docker host)
              java -jar portal-monitor.jar --mode server --once     one HTTP cycle, then exit
              java -jar portal-monitor.jar --mode agent  --once     one docker report, then exit
              java -jar portal-monitor.jar --test-email <address>   send one test email, then exit
              java -jar portal-monitor.jar --test-db                check the database settings, then exit
              java -jar portal-monitor.jar --test-docker            run docker ps and print what the agent sees
              java -jar portal-monitor.jar --config <file>          use this properties file
              java -jar portal-monitor.jar --help

            The config file defaults to ./application.properties, then to the folder holding the jar.
            Any setting can be overridden by an environment variable: PM_ + the key in upper case
            with dots as underscores (db.password -> PM_DB_PASSWORD), or PM_..._FILE to read it
            from a file (Docker secrets).

            HTTP endpoints (server mode):
              GET /health       liveness - the process is up (no database needed)
              GET /ready        readiness - database reachable and cycles completing; 503 if not
              GET /api/status   every check and agent (needs the API token)
              POST /api/agent/report   agent reports (needs the API token)

            Container healthcheck:
              java -cp portal-monitor.jar portalmonitor.HealthProbe [http://127.0.0.1:8085/ready]
            """;

    static final String VERSION = "2.1.0";

    private enum Mode { SERVER, AGENT, NONE }

    public static void main(String[] args) {
        Path configPath = null;
        Mode mode = Mode.NONE;
        boolean once = false;
        boolean testDb = false;
        boolean testDocker = false;
        String testEmailTo = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode" -> mode = parseMode(needValue(args, ++i, "--mode"));
                case "--once" -> once = true;
                case "--test-db" -> testDb = true;
                case "--test-docker" -> testDocker = true;
                case "--config" -> configPath = Path.of(needValue(args, ++i, "--config"));
                case "--test-email" -> testEmailTo = needValue(args, ++i, "--test-email");
                case "--help", "-h" -> { System.out.print(USAGE); return; }
                default -> fail("Unknown argument: " + args[i] + "\n\n" + USAGE, 2);
            }
        }

        Config cfg = loadConfig(configPath);
        TimeZone.setDefault(TimeZone.getTimeZone(cfg.zone));
        Log.useZone(cfg.zone);

        // One-shot diagnostics first: these need no mode.
        if (testDocker) {
            System.exit(printDockerView(cfg) ? 0 : 1);
        }
        if (testEmailTo != null) {
            try {
                cfg.requireMailSettings();
            } catch (IllegalArgumentException e) {
                fail("Cannot send mail: " + e.getMessage(), 1);
            }
            System.exit(new MonitorService(cfg).sendTestEmail(testEmailTo) ? 0 : 1);
        }
        if (testDb) {
            System.exit(new MonitorService(cfg).testDatabase() ? 0 : 1);
        }

        switch (mode) {
            case SERVER -> runServer(cfg, once);
            case AGENT -> runAgent(cfg, once);
            case NONE -> fail("--mode server or --mode agent is required.\n\n" + USAGE, 2);
        }
    }

    // ------------------------------------------------------------------ server mode

    private static void runServer(Config cfg, boolean once) {
        try {
            cfg.requireServerSettings();
        } catch (IllegalArgumentException e) {
            fail("Bad server configuration: " + e.getMessage(), 1);
        }

        Log.info("Portal Monitor " + VERSION + " starting in server mode: " + cfg.describeServer());
        for (String note : cfg.envNotes()) Log.info("  from environment: " + note);

        MonitorService monitor = new MonitorService(cfg);
        // Say plainly, before anything else, whether the database works - and why not.
        // The server keeps running either way: it recovers by itself once MySQL accepts it,
        // and meanwhile /ready (the Docker healthcheck) reports NOT_READY.
        monitor.logStartupDatabaseCheck();

        if (once) {
            // One HTTP cycle, no API, no watchdog - handy from cron or by hand.
            System.exit(monitor.runHttpCycle() ? 0 : 1);
        }

        AgentApiServer api = new AgentApiServer(cfg, monitor);
        try {
            api.start();
        } catch (IOException e) {
            fail("Could not bind " + cfg.serverBind + ":" + cfg.serverPort + " - " + e.getMessage(), 1);
        }

        Log.info("Server mode. HTTP checks every " + cfg.intervalSeconds + "s, failThreshold="
                + cfg.failThreshold + ", agent offline after " + cfg.agentOfflineAfterSeconds
                + "s, timezone=" + cfg.zone + ", successCodes=" + cfg.successCodes);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "monitor-cycle");
            t.setDaemon(false);
            return t;
        });

        // fixed DELAY, not fixed RATE: the next cycle starts only once the previous one has
        // finished, so slow checks can never make cycles overlap.
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                monitor.runHttpCycle();
                monitor.checkAgentWatchdog();
            } catch (Throwable t) {
                // Anything escaping here would otherwise silently kill the schedule.
                Log.error("Unexpected error in cycle: " + t);
            }
        }, 0, cfg.intervalSeconds, TimeUnit.SECONDS);

        awaitShutdown(() -> {
            scheduler.shutdownNow();
            api.stop();
        });
    }

    // ------------------------------------------------------------------ agent mode

    private static void runAgent(Config cfg, boolean once) {
        try {
            cfg.requireAgentSettings();
        } catch (IllegalArgumentException e) {
            fail("Bad agent configuration: " + e.getMessage(), 1);
        }

        Log.info("Portal Monitor " + VERSION + " starting in agent mode: " + cfg.describeAgent());
        for (String note : cfg.envNotes()) Log.info("  from environment: " + note);

        DockerAgent agent = new DockerAgent(cfg);

        if (once) {
            System.exit(agent.runOnce() ? 0 : 1);
        }

        Log.info("Agent mode. id=" + cfg.agentId + ", host=" + agent.hostname()
                + ", reporting to " + cfg.agentServerUrl + " every " + cfg.agentIntervalSeconds + "s");

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-cycle");
            t.setDaemon(false);
            return t;
        });

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                agent.runOnce();
            } catch (Throwable t) {
                Log.error("Unexpected error in agent cycle: " + t);
            }
        }, 0, cfg.agentIntervalSeconds, TimeUnit.SECONDS);

        awaitShutdown(scheduler::shutdownNow);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean printDockerView(Config cfg) {
        try {
            DockerAgent agent = new DockerAgent(cfg);
            var containers = agent.collectContainers();
            Log.info("docker ps returned " + containers.size() + " container(s) on " + agent.hostname() + ":");
            for (ContainerReport c : containers) {
                CheckResult r = c.toCheckResult();
                Log.info(String.format("  %-34s %-11s %-6s %s",
                        c.name(), c.state(), r.outcome(), c.status()));
            }
            return true;
        } catch (Exception e) {
            Log.error("docker ps failed: " + Text.oneLine(e.getMessage()));
            return false;
        }
    }

    private static Config loadConfig(Path configPath) {
        if (configPath == null) {
            configPath = findConfig();
            if (configPath == null) {
                fail("No application.properties found. Looked in " + Path.of("").toAbsolutePath()
                        + " and next to the jar. Pass --config /path/application.properties.", 1);
            }
        } else if (!Files.isReadable(configPath)) {
            fail("Config file not found or not readable: " + configPath.toAbsolutePath(), 1);
        }
        try {
            return Config.load(configPath);
        } catch (IllegalArgumentException e) {
            fail("Bad setting in " + configPath.toAbsolutePath() + ": " + e.getMessage(), 1);
        } catch (IOException e) {
            fail("Could not read " + configPath.toAbsolutePath() + ": " + e.getMessage(), 1);
        }
        throw new IllegalStateException("unreachable");
    }

    /** Blocks until SIGTERM, then runs the cleanup. systemd and NSSM both stop us this way. */
    private static void awaitShutdown(Runnable cleanup) {
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Shutting down.");
            try {
                cleanup.run();
            } catch (Throwable ignored) {
                // nothing useful left to do during shutdown
            }
            stopped.countDown();
        }));
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** ./application.properties, else the folder the jar itself sits in. */
    private static Path findConfig() {
        Path cwd = Path.of("application.properties");
        if (Files.isReadable(cwd)) return cwd;
        Path beside = jarDirectory();
        if (beside != null) {
            Path p = beside.resolve("application.properties");
            if (Files.isReadable(p)) return p;
        }
        return null;
    }

    private static Path jarDirectory() {
        try {
            Path self = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(self) ? self : self.getParent();
        } catch (URISyntaxException | RuntimeException e) {
            return null;   // running from an exploded classpath
        }
    }

    private static Mode parseMode(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "server" -> Mode.SERVER;
            case "agent" -> Mode.AGENT;
            default -> {
                fail("--mode must be server or agent, got: " + raw, 2);
                yield Mode.NONE;
            }
        };
    }

    private static String needValue(String[] args, int i, String flag) {
        if (i >= args.length || args[i].startsWith("--")) {
            fail(flag + " needs a value.\n\n" + USAGE, 2);
        }
        return args[i];
    }

    private static void fail(String msg, int code) {
        System.err.println(msg);
        System.exit(code);
    }
}
