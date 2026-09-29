package portalmonitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Agent mode. Runs on the Windows Docker host as a service.
 *
 * Every agent.intervalSeconds it runs
 *   docker ps -a --no-trunc --format "{{json .}}"
 * and POSTs the parsed list to the server. A failed POST is simply retried next cycle -
 * the agent never crashes and never queues, because the server's watchdog is what notices
 * a silent agent.
 */
public final class DockerAgent {

    private final Config cfg;
    private final HttpClient client;
    private final String hostname;

    public DockerAgent(Config cfg) {
        this.cfg = cfg;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(cfg.connectTimeoutSeconds))
                .build();
        this.hostname = resolveHostname();
    }

    /** One collect-and-send pass. Returns true when the server accepted the report. */
    public boolean runOnce() {
        List<ContainerReport> containers;
        try {
            containers = collectContainers();
        } catch (Exception e) {
            // Docker Desktop not started, or the service account cannot reach the daemon.
            Log.error("Could not run docker: " + Text.oneLine(e.getMessage())
                    + " - is Docker running, and can this account use it?");
            return false;
        }

        if (containers.isEmpty()) {
            Log.error("docker ps returned no containers - reporting an empty list.");
        }

        try {
            return post(containers);
        } catch (Exception e) {
            Log.error("Could not send report to " + cfg.agentServerUrl + ": "
                    + Text.oneLine(e.getMessage()) + " - will retry next cycle.");
            return false;
        }
    }

    /**
     * Runs docker ps and parses one JSON object per line.
     *
     * The output is drained on a separate thread so the deadline below is real: a Docker
     * daemon that accepts the command and then never answers leaves its pipe open, and
     * reading it on this thread would block forever regardless of any waitFor timeout.
     * An agent that hangs stops reporting silently, which is exactly what the server's
     * watchdog would then have to catch - so it must not happen.
     */
    List<ContainerReport> collectContainers() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                cfg.dockerCommand, "ps", "-a", "--no-trunc", "--format", "{{json .}}");
        // One stream to drain, so a full stderr buffer can never deadlock the process.
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
                // the process was killed under us; whatever we already read still counts
            }
        }, "docker-ps-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = proc.waitFor(cfg.dockerTimeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            proc.waitFor(5, TimeUnit.SECONDS);
            reader.interrupt();
            throw new IllegalStateException("docker ps did not finish within "
                    + cfg.dockerTimeoutSeconds + "s - the Docker daemon is not responding");
        }
        // The process is gone, so the pipe is closed and this join cannot hang.
        reader.join(5_000);

        List<ContainerReport> out = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();
        synchronized (lines) {
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (!trimmed.startsWith("{")) {
                    unparsed.add(trimmed);   // stderr text, mixed in by redirectErrorStream
                    continue;
                }
                try {
                    out.add(Json.parseDockerLine(trimmed));
                } catch (Exception e) {
                    unparsed.add(trimmed);
                }
            }
        }

        if (proc.exitValue() != 0) {
            String why = unparsed.isEmpty() ? "" : ": " + Text.clean(String.join(" ", unparsed), 200);
            throw new IllegalStateException("docker ps exited with " + proc.exitValue() + why);
        }
        for (String u : unparsed) {
            Log.error("Ignoring unexpected docker ps output: " + Text.truncate(u, 160));
        }
        return out;
    }

    private boolean post(List<ContainerReport> containers) throws Exception {
        String body = Json.agentReport(cfg.agentId, hostname,
                LocalDateTime.now(cfg.zone).withNano(0), containers);

        HttpRequest req = HttpRequest.newBuilder(URI.create(reportUrl()))
                .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-Agent-Token", cfg.agentToken)
                .header("User-Agent", "PortalMonitorAgent/2.0")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            Log.info("Reported " + containers.size() + " container(s) to the server.");
            return true;
        }
        if (resp.statusCode() == 401) {
            Log.error("Server rejected the report: 401 - agent.token does not match the server's api.token.");
        } else {
            Log.error("Server returned HTTP " + resp.statusCode() + ": "
                    + Text.truncate(Text.oneLine(resp.body()), 200));
        }
        return false;
    }

    /** Accepts either a base URL or the full endpoint in agent.serverUrl. */
    private String reportUrl() {
        String base = cfg.agentServerUrl.trim();
        if (base.contains("/api/agent/report")) return base;
        return base.endsWith("/") ? base + "api/agent/report" : base + "/api/agent/report";
    }

    private static String resolveHostname() {
        String fromEnv = System.getenv("COMPUTERNAME");
        if (!Text.isBlank(fromEnv)) return fromEnv;
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    public String hostname() {
        return hostname;
    }
}
