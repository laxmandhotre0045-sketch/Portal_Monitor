-- =====================================================================
--  Portal Uptime Monitor 2.0 - schema and seed data (MySQL 8 / MariaDB)
--
--      mysql -u root -p -e "CREATE DATABASE portal_monitor CHARACTER SET utf8mb4;"
--      mysql -u root -p portal_monitor < database.sql
--
--  Tables use CREATE TABLE IF NOT EXISTS and the seeds use INSERT IGNORE,
--  so re-running this file is harmless.
--
--  REPLACE SERVER_PUBLIC_IP below with the Windows machine's public IP
--  before importing, or the HTTP checks will all fail.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) portals : one row per thing we monitor.
--
--    check_type = CONTAINER -> judged from the agent's docker ps report
--                              (container_name must be set)
--    check_type = HTTP      -> the server fetches health_url itself
--                              (health_url must be set)
--
--    YOU manage:  portal_name, app_group, check_type, container_name,
--                 health_url, notify_email, is_active
--    The APP manages every remaining column.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS portals (
    id                 INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,

    -- ===== Fields you manage =====
    portal_name        VARCHAR(100) NOT NULL
                       COMMENT 'Unique name; for CONTAINER checks use the container name',
    app_group          VARCHAR(100) NULL
                       COMMENT 'Grouping used in emails, e.g. Senvia UAT, TMS, Inventory',
    check_type         ENUM('CONTAINER','HTTP') NOT NULL DEFAULT 'CONTAINER',
    container_name     VARCHAR(150) NULL COMMENT 'Docker container name, for CONTAINER checks',
    health_url         VARCHAR(500) NULL COMMENT 'Full URL, for HTTP checks',
    notify_email       VARCHAR(500) NOT NULL DEFAULT 'laxmandhotre0045@gmail.com'
                       COMMENT 'Who gets the alert. Several allowed, comma-separated',
    is_active          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 = monitor, 0 = pause',

    -- ===== Fields the app manages =====
    status             ENUM('UNKNOWN','UP','DOWN') NOT NULL DEFAULT 'UNKNOWN',
    fail_count         INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Consecutive failures',
    fail_since         DATETIME     NULL COMMENT 'First failure of the current streak',
    last_http_code     INT          NULL COMMENT 'NULL for CONTAINER checks and for no-response',
    last_response_ms   INT          NULL,
    last_detail        VARCHAR(255) NULL
                       COMMENT 'e.g. Exited (1) 5 minutes ago / unhealthy / HTTP 503 / Timeout',
    last_checked       DATETIME     NULL,
    last_status_change DATETIME     NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uq_portal_name (portal_name),
    KEY idx_active_type (is_active, check_type),
    KEY idx_container (container_name),
    KEY idx_group (app_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 2) check_logs : every check result. The app deletes rows older than
--    log.retentionDays (default 30) at the end of each HTTP cycle.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS check_logs (
    id           BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    portal_id    INT UNSIGNED NOT NULL,
    http_code    INT          NULL COMMENT 'NULL for container checks / no response',
    response_ms  INT          NULL,
    is_up        TINYINT(1)   NOT NULL,
    detail       VARCHAR(255) NULL,
    checked_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_portal_time (portal_id, checked_at),
    KEY idx_checked_at (checked_at),
    CONSTRAINT fk_logs_portal FOREIGN KEY (portal_id) REFERENCES portals(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 3) alert_logs : every email attempt, successful or not.
--    portal_id is NULL for agent-level alerts, which are about the host
--    rather than one check.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS alert_logs (
    id          BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    portal_id   INT UNSIGNED NULL,
    alert_type  ENUM('DOWN','RECOVERED','AGENT_OFFLINE','AGENT_ONLINE') NOT NULL,
    sent_to     VARCHAR(500) NOT NULL,
    success     TINYINT(1)   NOT NULL,
    error       VARCHAR(500) NULL,
    sent_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_portal_sent (portal_id, sent_at),
    KEY idx_sent_at (sent_at),
    CONSTRAINT fk_alerts_portal FOREIGN KEY (portal_id) REFERENCES portals(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- 4) agents : one row per reporting host, maintained by the app.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agents (
    agent_id   VARCHAR(100) NOT NULL PRIMARY KEY,
    hostname   VARCHAR(150) NULL,
    last_seen  DATETIME     NULL,
    status     ENUM('ONLINE','OFFLINE') NOT NULL DEFAULT 'ONLINE',
    KEY idx_last_seen (last_seen)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- =====================================================================
--  SEED: CONTAINER checks - all 31 containers
--  portal_name = container name. Uses the notify_email column default.
-- =====================================================================
INSERT IGNORE INTO portals (portal_name, app_group, check_type, container_name) VALUES
-- Senvia (UAT)
 ('senvia_uat_ems_mqtt_listener',    'Senvia UAT', 'CONTAINER', 'senvia_uat_ems_mqtt_listener'),
 ('senvia_uat_frontend',             'Senvia UAT', 'CONTAINER', 'senvia_uat_frontend'),
 ('senvia_uat_backend',              'Senvia UAT', 'CONTAINER', 'senvia_uat_backend'),
 ('senvia_uat_archiver',             'Senvia UAT', 'CONTAINER', 'senvia_uat_archiver'),
 ('senvia_uat_vibkpi_mqtt_listener', 'Senvia UAT', 'CONTAINER', 'senvia_uat_vibkpi_mqtt_listener'),
 ('senvia_uat_ems_alarm_generator',  'Senvia UAT', 'CONTAINER', 'senvia_uat_ems_alarm_generator'),
-- Inventory Management System
 ('inventory-frontend',              'Inventory',  'CONTAINER', 'inventory-frontend'),
 ('inventory-backend',               'Inventory',  'CONTAINER', 'inventory-backend'),
 ('inventory-mysql',                 'Inventory',  'CONTAINER', 'inventory-mysql'),
-- Solar Proposal System
 ('solar-frontend',                  'Solar',      'CONTAINER', 'solar-frontend'),
 ('solar-backend',                   'Solar',      'CONTAINER', 'solar-backend'),
 ('solar-db-backup',                 'Solar',      'CONTAINER', 'solar-db-backup'),
-- Vibeinscript website
 ('vibeinscript_website_app',        'Vibeinscript', 'CONTAINER', 'vibeinscript_website_app'),
-- Sensovibe website
 ('sensovibe-frontend',              'Sensovibe',  'CONTAINER', 'sensovibe-frontend'),
 ('sensovibe-backend',               'Sensovibe',  'CONTAINER', 'sensovibe-backend'),
 ('sensovibe-mysql',                 'Sensovibe',  'CONTAINER', 'sensovibe-mysql'),
-- POS demo
 ('pos_demo_deploy_frontend_con',    'POS demo',   'CONTAINER', 'pos_demo_deploy_frontend_con'),
 ('pos_demo_deploy_con',             'POS demo',   'CONTAINER', 'pos_demo_deploy_con'),
-- TMS
 ('tms_chatbot_agent',               'TMS',        'CONTAINER', 'tms_chatbot_agent'),
 ('tms_agent_redis',                 'TMS',        'CONTAINER', 'tms_agent_redis'),
 ('tms-frontend',                    'TMS',        'CONTAINER', 'tms-frontend'),
 ('tms-backend',                     'TMS',        'CONTAINER', 'tms-backend'),
 ('tms_backend_mqtt_listener',       'TMS',        'CONTAINER', 'tms_backend_mqtt_listener'),
-- Task Tracker
 ('tasktracker-app-1',               'Task Tracker', 'CONTAINER', 'tasktracker-app-1'),
 ('tasktracker-db-1',                'Task Tracker', 'CONTAINER', 'tasktracker-db-1'),
-- Energlob website
 ('energlob_website_app',            'Energlob',   'CONTAINER', 'energlob_website_app'),
-- EMS chat
 ('ems_chat_con',                    'EMS chat',   'CONTAINER', 'ems_chat_con'),
-- Shared infrastructure
 ('emqx',                            'Infrastructure', 'CONTAINER', 'emqx'),
 ('mqtt_broker-mosquitto-1',         'Infrastructure', 'CONTAINER', 'mqtt_broker-mosquitto-1'),
 ('redis-container',                 'Infrastructure', 'CONTAINER', 'redis-container'),
 ('nodered',                         'Infrastructure', 'CONTAINER', 'nodered');


-- =====================================================================
--  SEED: HTTP checks - the public web entry points (expect HTTP 200)
--  REPLACE SERVER_PUBLIC_IP with the Windows machine's public IP.
--  Each of these ports must be reachable from the VPS (see the README
--  firewall section).
-- =====================================================================
INSERT IGNORE INTO portals (portal_name, app_group, check_type, health_url) VALUES
 ('HTTP Senvia UAT frontend',   'Senvia UAT',     'HTTP', 'http://SERVER_PUBLIC_IP:3001/'),
 ('HTTP Inventory frontend',    'Inventory',      'HTTP', 'http://SERVER_PUBLIC_IP:8090/'),
 ('HTTP Solar frontend',        'Solar',          'HTTP', 'http://SERVER_PUBLIC_IP:1113/'),
 ('HTTP Vibeinscript website',  'Vibeinscript',   'HTTP', 'http://SERVER_PUBLIC_IP:1111/'),
 ('HTTP Sensovibe website',     'Sensovibe',      'HTTP', 'http://SERVER_PUBLIC_IP:3000/'),
 ('HTTP POS demo frontend',     'POS demo',       'HTTP', 'http://SERVER_PUBLIC_IP:3003/'),
 ('HTTP TMS frontend',          'TMS',            'HTTP', 'http://SERVER_PUBLIC_IP:6001/'),
 ('HTTP Energlob website',      'Energlob',       'HTTP', 'http://SERVER_PUBLIC_IP:1112/'),
 ('HTTP Node-RED',              'Infrastructure', 'HTTP', 'http://SERVER_PUBLIC_IP:1880/'),
 ('HTTP EMQX dashboard',        'Infrastructure', 'HTTP', 'http://SERVER_PUBLIC_IP:18083/');

-- ---------------------------------------------------------------------
--  Backend health URLs - enable these once you have confirmed the paths
--  exist and the ports are published and reachable from the VPS.
--  Note that several backends currently bind to 127.0.0.1 only, so they
--  are NOT reachable from outside; the CONTAINER check covers those.
-- ---------------------------------------------------------------------
-- INSERT IGNORE INTO portals (portal_name, app_group, check_type, health_url) VALUES
--  ('HTTP Senvia UAT backend', 'Senvia UAT', 'HTTP', 'http://SERVER_PUBLIC_IP:8081/actuator/health'),
--  ('HTTP TMS backend',        'TMS',        'HTTP', 'http://SERVER_PUBLIC_IP:7070/actuator/health'),
--  ('HTTP POS demo backend',   'POS demo',   'HTTP', 'http://SERVER_PUBLIC_IP:8088/actuator/health'),
--  ('HTTP TMS chatbot',        'TMS',        'HTTP', 'http://SERVER_PUBLIC_IP:5001/health'),
--  ('HTTP EMS chat',           'EMS chat',   'HTTP', 'http://SERVER_PUBLIC_IP:5000/health');


-- =====================================================================
--  EVERYDAY QUERIES
-- =====================================================================

-- Set the real public IP in every HTTP check at once:
-- UPDATE portals SET health_url = REPLACE(health_url, 'SERVER_PUBLIC_IP', '203.0.113.45')
--  WHERE check_type = 'HTTP';

-- What is broken right now:
-- SELECT app_group, portal_name, check_type, status, fail_count,
--        last_detail, last_checked
--   FROM portals
--  WHERE is_active = 1 AND status <> 'UP'
--  ORDER BY app_group, portal_name;

-- Everything, worst first:
-- SELECT app_group, portal_name, check_type, status, last_http_code,
--        last_response_ms, last_detail, last_checked, last_status_change
--   FROM portals
--  ORDER BY FIELD(status,'DOWN','UNKNOWN','UP'), app_group, portal_name;

-- Is the Windows host reporting?
-- SELECT agent_id, hostname, status, last_seen,
--        TIMESTAMPDIFF(SECOND, last_seen, NOW()) AS seconds_ago
--   FROM agents;

-- Pause / resume a check (no checks, no emails while paused):
-- UPDATE portals SET is_active = 0 WHERE portal_name = 'solar-db-backup';
-- UPDATE portals SET is_active = 1 WHERE portal_name = 'solar-db-backup';

-- Pause a whole group:
-- UPDATE portals SET is_active = 0 WHERE app_group = 'POS demo';

-- Change who gets alerts, for one check or a whole group:
-- UPDATE portals SET notify_email = 'ops@yourdomain.com' WHERE portal_name = 'emqx';
-- UPDATE portals SET notify_email = 'laxmandhotre0045@gmail.com, devops@yourdomain.com'
--  WHERE app_group = 'Senvia UAT';

-- Add a new container check:
-- INSERT INTO portals (portal_name, app_group, check_type, container_name)
-- VALUES ('new_container', 'Senvia UAT', 'CONTAINER', 'new_container');

-- Add a new HTTP check:
-- INSERT INTO portals (portal_name, app_group, check_type, health_url)
-- VALUES ('HTTP new site', 'Sensovibe', 'HTTP', 'http://203.0.113.45:9000/health');

-- Emails from the last 24 hours, including failed sends:
-- SELECT COALESCE(p.portal_name,'(agent)') AS name, a.alert_type, a.sent_to,
--        a.success, a.error, a.sent_at
--   FROM alert_logs a LEFT JOIN portals p ON p.id = a.portal_id
--  WHERE a.sent_at > NOW() - INTERVAL 1 DAY
--  ORDER BY a.sent_at DESC;

-- Uptime percentage per check over the last 7 days:
-- SELECT p.app_group, p.portal_name,
--        ROUND(100 * AVG(c.is_up), 2) AS uptime_pct, COUNT(*) AS checks
--   FROM check_logs c JOIN portals p ON p.id = c.portal_id
--  WHERE c.checked_at > NOW() - INTERVAL 7 DAY
--  GROUP BY p.app_group, p.portal_name
--  ORDER BY uptime_pct;

-- The last 20 failures for one check:
-- SELECT checked_at, http_code, response_ms, detail
--   FROM check_logs
--  WHERE portal_id = (SELECT id FROM portals WHERE portal_name = 'tms-backend')
--    AND is_up = 0
--  ORDER BY checked_at DESC LIMIT 20;

-- Force a re-detect after fixing SMTP (the next state change will email again):
-- UPDATE portals SET status='UNKNOWN', fail_count=0, fail_since=NULL
--  WHERE portal_name='tms-backend';


-- =====================================================================
--  OPTIONAL safety net: let MySQL trim check_logs too, in case the
--  server is stopped for a long stretch. The app trims every cycle,
--  so this is not required.
-- =====================================================================
-- SET GLOBAL event_scheduler = ON;
-- CREATE EVENT IF NOT EXISTS ev_trim_check_logs
--   ON SCHEDULE EVERY 1 DAY
--   DO DELETE FROM check_logs WHERE checked_at < NOW() - INTERVAL 30 DAY;
