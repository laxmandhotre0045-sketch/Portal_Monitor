-- =====================================================================
--  MySQL access for the portal-monitor CONTAINER - review before running.
--  NOT run by any script. Run by hand, as MySQL root on the VPS:
--      sudo mysql
--
--  Why: inside Docker the app connects from the container's bridge IP
--  (e.g. 172.20.0.2), not from 'localhost'. An account created as
--  'portal_monitor'@'localhost' does not match, and MySQL answers
--  "Access denied for user 'portal_monitor'@'172.20.0.2'" (error 1045).
-- =====================================================================

-- ---------------------------------------------------------------------
-- STEP 1 - look (read-only). Which portal_monitor accounts exist, from where?
-- ---------------------------------------------------------------------
SELECT user, host, plugin, account_locked FROM mysql.user WHERE user = 'portal_monitor';
-- For each row: SHOW GRANTS FOR 'portal_monitor'@'<host from above>';

-- Interpretation:
--   * only host = 'localhost'                -> no account matches the container: do STEP 2a.
--   * a row matching 172.x / '%' exists      -> the account matches; the PASSWORD differs from
--                                               db.password in the mounted file: do STEP 2b.
--   * plugin = auth_socket                   -> cannot log in over TCP at all: do STEP 2b.

-- ---------------------------------------------------------------------
-- STEP 2a - add the account for Docker's private ranges (172.16.0.0/12).
--           Use the SAME password as db.password in the mounted file.
--           Existing accounts and all data are untouched.
-- ---------------------------------------------------------------------
-- CREATE USER 'portal_monitor'@'172.16.0.0/255.240.0.0' IDENTIFIED BY '<same as db.password>';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON portal_monitor.* TO 'portal_monitor'@'172.16.0.0/255.240.0.0';

-- ---------------------------------------------------------------------
-- STEP 2b - the account exists for the container's host but the password differs:
--           set it to db.password (or change db.password in the file instead).
-- ---------------------------------------------------------------------
-- ALTER USER 'portal_monitor'@'<host from STEP 1>' IDENTIFIED WITH caching_sha2_password BY '<same as db.password>';

-- ---------------------------------------------------------------------
-- STEP 3 - MySQL must listen where the container can reach it.
--   MySQL on the VPS (systemd): /etc/mysql/mysql.conf.d/mysqld.cnf
--     bind-address = 127.0.0.1   -> the container cannot connect at all
--     bind-address = 0.0.0.0     -> reachable; keep port 3306 closed in ufw / Hostinger firewall
--   (Not needed if you already see error 1045 - that proves the connection reaches MySQL.)
-- ---------------------------------------------------------------------

-- ---------------------------------------------------------------------
-- STEP 4 - verify from the container itself, with the app's own settings:
--   docker exec portal-monitor java -cp /app/portal-monitor.jar portalmonitor.App \
--       --test-db --config /run/portal-monitor/application.properties
-- Expect: "Database OK: 41 active check(s) ..."
-- ---------------------------------------------------------------------
