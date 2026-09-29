-- Activate the public HTTP checks for each deployment.
-- URLs verified 2026-09-28: each returns a direct HTTP 200 (no redirect,
-- which matters because check.successCodes=200 and redirects are not followed).

UPDATE portals SET health_url='https://senvia.sensovibe.in/',  is_active=1 WHERE id=32;  -- Senvia UAT frontend
UPDATE portals SET health_url='https://vibeinscript.com/',     is_active=1 WHERE id=35;  -- Vibeinscript website
UPDATE portals SET health_url='https://www.sensovibe.in/',     is_active=1 WHERE id=36;  -- Sensovibe website
UPDATE portals SET health_url='https://pos.vibeinscript.com/', is_active=1 WHERE id=37;  -- POS demo frontend
UPDATE portals SET health_url='https://energlob.in/',          is_active=1 WHERE id=39;  -- Energlob website
UPDATE portals SET health_url='https://emqx.sensovibe.in/',    is_active=1 WHERE id=41;  -- EMQX dashboard

-- STILL UNKNOWN - no public URL found. Fill in and set is_active=1:
-- UPDATE portals SET health_url='https://<solar>/',   is_active=1 WHERE id=34;  -- Solar frontend
-- UPDATE portals SET health_url='https://<tms>/',     is_active=1 WHERE id=38;  -- TMS frontend
-- UPDATE portals SET health_url='https://<nodered>/', is_active=1 WHERE id=40;  -- Node-RED
