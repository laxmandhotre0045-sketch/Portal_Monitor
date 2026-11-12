-- Activate the public HTTP checks for each deployment.
-- URLs verified 2026-11-12: each returns a direct HTTP 200 (no redirect,
-- which matters because check.successCodes=200 and redirects are not followed).
--
-- Rows are matched by portal_name, not by id: ids shift if database.sql was
-- imported more than once (INSERT IGNORE still consumes AUTO_INCREMENT values).
-- Safe to re-run.

UPDATE portals SET health_url='https://senvia.sensovibe.in/',  is_active=1 WHERE portal_name='HTTP Senvia UAT frontend';
UPDATE portals SET health_url='https://vibeinscript.com/',     is_active=1 WHERE portal_name='HTTP Vibeinscript website';
UPDATE portals SET health_url='https://www.sensovibe.in/',     is_active=1 WHERE portal_name='HTTP Sensovibe website';
UPDATE portals SET health_url='https://pos.vibeinscript.com/', is_active=1 WHERE portal_name='HTTP POS demo frontend';
UPDATE portals SET health_url='https://energlob.in/',          is_active=1 WHERE portal_name='HTTP Energlob website';
UPDATE portals SET health_url='https://emqx.sensovibe.in/',    is_active=1 WHERE portal_name='HTTP EMQX dashboard';

-- Pause every HTTP check that still has the seed placeholder, so it can never
-- raise a false DOWN alert. (The server also skips these, but this keeps the
-- table honest.) Today that is: Inventory, Solar, TMS and Node-RED.
UPDATE portals SET is_active=0, status='UNKNOWN', fail_count=0, fail_since=NULL
 WHERE check_type='HTTP' AND (health_url IS NULL OR health_url LIKE '%SERVER_PUBLIC_IP%');

-- STILL UNKNOWN - no public URL found. Fill in and set is_active=1:
-- UPDATE portals SET health_url='https://<inventory>/', is_active=1 WHERE portal_name='HTTP Inventory frontend';
-- UPDATE portals SET health_url='https://<solar>/',     is_active=1 WHERE portal_name='HTTP Solar frontend';
-- UPDATE portals SET health_url='https://<tms>/',       is_active=1 WHERE portal_name='HTTP TMS frontend';
-- UPDATE portals SET health_url='https://<nodered>/',   is_active=1 WHERE portal_name='HTTP Node-RED';

-- Check the result - every active HTTP row should show a real https:// URL:
SELECT id, portal_name, is_active, status, health_url FROM portals WHERE check_type='HTTP' ORDER BY id;
