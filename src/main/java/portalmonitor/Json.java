package portalmonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Builds and parses the JSON that travels between the agent and the server. */
public final class Json {

    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Json() { }

    // ------------------------------------------------------------------ agent -> server

    /**
     * The body the agent POSTs to /api/agent/report:
     * { "agentId": "...", "hostname": "...", "reportedAt": "...", "containers": [ ... ] }
     */
    public static String agentReport(String agentId, String hostname, LocalDateTime at,
                                     List<ContainerReport> containers) {
        JSONArray arr = new JSONArray();
        for (ContainerReport c : containers) {
            arr.put(new JSONObject()
                    .put("name", c.name() == null ? "" : c.name())
                    .put("image", c.image() == null ? "" : c.image())
                    .put("state", c.state() == null ? "" : c.state())
                    .put("status", c.status() == null ? "" : c.status()));
        }
        return new JSONObject()
                .put("agentId", agentId)
                .put("hostname", hostname)
                .put("reportedAt", TS.format(at))
                .put("containers", arr)
                .toString();
    }

    /** Reads the containers array out of an agent report. Throws if the body is malformed. */
    public static List<ContainerReport> parseContainers(JSONObject body) {
        List<ContainerReport> out = new ArrayList<>();
        JSONArray arr = body.optJSONArray("containers");
        if (arr == null) {
            throw new IllegalArgumentException("body has no \"containers\" array");
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new ContainerReport(
                    o.optString("name", ""),
                    o.optString("image", ""),
                    o.optString("state", ""),
                    o.optString("status", "")));
        }
        return out;
    }

    /**
     * One line of {@code docker ps -a --no-trunc --format "{{json .}}"}.
     * Docker capitalises its keys: Names, Image, State, Status.
     */
    public static ContainerReport parseDockerLine(String line) {
        JSONObject o = new JSONObject(line);
        // A container can have several names; Docker joins them with commas.
        String names = o.optString("Names", "");
        String first = names.contains(",") ? names.substring(0, names.indexOf(',')) : names;
        return new ContainerReport(
                first.trim(),
                o.optString("Image", ""),
                o.optString("State", ""),
                o.optString("Status", ""));
    }

    // ------------------------------------------------------------------ server -> caller

    /** The body of GET /api/status. */
    public static String status(List<Portal> portals, List<Database.Agent> agents, LocalDateTime now) {
        JSONArray checks = new JSONArray();
        for (Portal p : portals) {
            checks.put(new JSONObject()
                    .put("id", p.id())
                    .put("name", p.name())
                    .put("appGroup", p.appGroup() == null ? "" : p.appGroup())
                    .put("checkType", p.checkType().name())
                    .put("target", p.target() == null ? "" : p.target())
                    .put("status", p.status())
                    .put("failCount", p.failCount())
                    .put("failSince", p.failSince() == null ? JSONObject.NULL : TS.format(p.failSince()))
                    .put("lastStatusChange", p.lastStatusChange() == null
                            ? JSONObject.NULL : TS.format(p.lastStatusChange())));
        }

        JSONArray ags = new JSONArray();
        for (Database.Agent a : agents) {
            ags.put(new JSONObject()
                    .put("agentId", a.agentId())
                    .put("hostname", a.hostname() == null ? "" : a.hostname())
                    .put("status", a.status())
                    .put("lastSeen", a.lastSeen() == null ? JSONObject.NULL : TS.format(a.lastSeen())));
        }

        long down = portals.stream().filter(p -> Portal.DOWN.equals(p.status())).count();
        long up = portals.stream().filter(p -> Portal.UP.equals(p.status())).count();

        return new JSONObject()
                .put("generatedAt", TS.format(now))
                .put("totals", new JSONObject()
                        .put("checks", portals.size())
                        .put("up", up)
                        .put("down", down)
                        .put("unknown", portals.size() - up - down))
                .put("agents", ags)
                .put("checks", checks)
                .toString(2);
    }

    public static String message(String key, String value) {
        return new JSONObject().put(key, value).toString();
    }
}
