package portalmonitor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * All SQL lives here. A fresh connection is opened for each unit of work, so a MySQL
 * restart or a dropped connection costs one cycle rather than the whole service.
 */
public final class Database implements AutoCloseable {

    private final Connection conn;

    public Database(Config cfg) throws SQLException {
        // Credentials go in as JDBC properties, never in the URL: the password contains '#'
        // and would otherwise need URL-escaping.
        Properties props = new Properties();
        props.setProperty("user", cfg.dbUser);
        props.setProperty("password", cfg.dbPassword);
        conn = DriverManager.getConnection(cfg.jdbcUrl(), props);

        // Make MySQL's NOW() agree with app.timezone, so DEFAULT CURRENT_TIMESTAMP columns
        // and the timestamps we pass in are on the same clock.
        try (Statement st = conn.createStatement()) {
            st.execute("SET time_zone = '" + sessionOffset(cfg) + "'");
        }
    }

    /** MySQL only accepts named zones when the tz tables are loaded; a numeric offset always works. */
    private static String sessionOffset(Config cfg) {
        String offset = ZonedDateTime.now(cfg.zone).getOffset().getId();
        return "Z".equals(offset) ? "+00:00" : offset;
    }

    // ------------------------------------------------------------------ portals

    private static final String PORTAL_COLUMNS =
            "id, portal_name, app_group, check_type, container_name, health_url, notify_email, "
          + "status, fail_count, fail_since, last_status_change";

    public List<Portal> activePortals() throws SQLException {
        return query("SELECT " + PORTAL_COLUMNS + " FROM portals WHERE is_active = 1 ORDER BY app_group, id");
    }

    public List<Portal> activePortalsOfType(Portal.CheckType type) throws SQLException {
        String sql = "SELECT " + PORTAL_COLUMNS + " FROM portals "
                   + "WHERE is_active = 1 AND check_type = ? ORDER BY app_group, id";
        List<Portal> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(readPortal(rs));
            }
        }
        return list;
    }

    private List<Portal> query(String sql) throws SQLException {
        List<Portal> list = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(readPortal(rs));
        }
        return list;
    }

    private static Portal readPortal(ResultSet rs) throws SQLException {
        return new Portal(
                rs.getInt("id"),
                rs.getString("portal_name"),
                rs.getString("app_group"),
                Portal.CheckType.valueOf(rs.getString("check_type")),
                rs.getString("container_name"),
                rs.getString("health_url"),
                rs.getString("notify_email"),
                rs.getString("status"),
                rs.getInt("fail_count"),
                toLdt(rs.getTimestamp("fail_since")),
                toLdt(rs.getTimestamp("last_status_change")));
    }

    public void updatePortal(int id, String status, int failCount, LocalDateTime failSince,
                             CheckResult r, LocalDateTime checkedAt, LocalDateTime statusChange)
            throws SQLException {
        String sql = "UPDATE portals SET status=?, fail_count=?, fail_since=?, last_http_code=?, "
                   + "last_response_ms=?, last_detail=?, last_checked=?, last_status_change=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setInt(2, failCount);
            ps.setTimestamp(3, toTs(failSince));
            if (r.httpCode() == 0) ps.setNull(4, java.sql.Types.INTEGER);
            else ps.setInt(4, r.httpCode());
            ps.setLong(5, r.responseMs());
            ps.setString(6, Text.clean(r.detail(), 250));
            ps.setTimestamp(7, Timestamp.valueOf(checkedAt));
            ps.setTimestamp(8, toTs(statusChange));
            ps.setInt(9, id);
            ps.executeUpdate();
        }
    }

    /**
     * Used by the agent watchdog: when the Windows host stops reporting we genuinely do not
     * know the container states, so they go back to UNKNOWN with the streak cleared. No
     * per-container emails are sent for this - one SERVER OFFLINE email covers it.
     */
    public int resetContainerChecksToUnknown(LocalDateTime at) throws SQLException {
        String sql = "UPDATE portals SET status='UNKNOWN', fail_count=0, fail_since=NULL, "
                   + "last_detail='Host not reporting - container status unknown', last_status_change=? "
                   + "WHERE is_active=1 AND check_type='CONTAINER' AND status <> 'UNKNOWN'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(at));
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ check_logs

    public void insertCheckLog(int portalId, CheckResult r, LocalDateTime at) throws SQLException {
        String sql = "INSERT INTO check_logs (portal_id, http_code, response_ms, is_up, detail, checked_at) "
                   + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, portalId);
            if (r.httpCode() == 0) ps.setNull(2, java.sql.Types.INTEGER);
            else ps.setInt(2, r.httpCode());
            ps.setLong(3, r.responseMs());
            ps.setInt(4, r.isUp() ? 1 : 0);
            ps.setString(5, Text.clean(r.detail(), 250));
            ps.setTimestamp(6, Timestamp.valueOf(at));
            ps.executeUpdate();
        }
    }

    /** Housekeeping: drop check_logs rows past the retention window. */
    public int deleteOldLogs(int retentionDays) throws SQLException {
        // retentionDays is validated as a positive int by Config, so inlining it is safe
        // and sidesteps placeholder quirks inside INTERVAL expressions.
        try (Statement st = conn.createStatement()) {
            return st.executeUpdate(
                    "DELETE FROM check_logs WHERE checked_at < NOW() - INTERVAL " + retentionDays + " DAY");
        }
    }

    // ------------------------------------------------------------------ alert_logs

    /** portalId may be null: agent-level alerts are not tied to one portal. */
    public void insertAlertLog(Integer portalId, String type, String sentTo, boolean ok,
                               String error, LocalDateTime at) throws SQLException {
        String sql = "INSERT INTO alert_logs (portal_id, alert_type, sent_to, success, error, sent_at) "
                   + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (portalId == null) ps.setNull(1, java.sql.Types.INTEGER);
            else ps.setInt(1, portalId);
            ps.setString(2, type);
            ps.setString(3, Text.truncate(sentTo, 500));
            ps.setInt(4, ok ? 1 : 0);
            ps.setString(5, Text.clean(error, 490));
            ps.setTimestamp(6, Timestamp.valueOf(at));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ agents

    /** One row of the agents table. */
    public record Agent(String agentId, String hostname, LocalDateTime lastSeen, String status) {
        public static final String ONLINE = "ONLINE";
        public static final String OFFLINE = "OFFLINE";
    }

    public Agent findAgent(String agentId) throws SQLException {
        String sql = "SELECT agent_id, hostname, last_seen, status FROM agents WHERE agent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Agent(rs.getString("agent_id"), rs.getString("hostname"),
                        toLdt(rs.getTimestamp("last_seen")), rs.getString("status"));
            }
        }
    }

    public List<Agent> allAgents() throws SQLException {
        List<Agent> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT agent_id, hostname, last_seen, status FROM agents ORDER BY agent_id")) {
            while (rs.next()) {
                list.add(new Agent(rs.getString("agent_id"), rs.getString("hostname"),
                        toLdt(rs.getTimestamp("last_seen")), rs.getString("status")));
            }
        }
        return list;
    }

    /** Records that an agent just reported in, creating the row on first contact. */
    public void markAgentSeen(String agentId, String hostname, LocalDateTime at) throws SQLException {
        String sql = "INSERT INTO agents (agent_id, hostname, last_seen, status) VALUES (?,?,?,'ONLINE') "
                   + "ON DUPLICATE KEY UPDATE hostname=VALUES(hostname), last_seen=VALUES(last_seen), "
                   + "status='ONLINE'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, Text.truncate(hostname, 150));
            ps.setTimestamp(3, Timestamp.valueOf(at));
            ps.executeUpdate();
        }
    }

    public void setAgentStatus(String agentId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE agents SET status=? WHERE agent_id=?")) {
            ps.setString(1, status);
            ps.setString(2, agentId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ misc

    private static LocalDateTime toLdt(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private static Timestamp toTs(LocalDateTime t) {
        return t == null ? null : Timestamp.valueOf(t);
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
