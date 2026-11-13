# Portal Uptime Monitor 2.1

Backend-only monitoring for Docker deployments. Emails you when a deployment goes
down and again when it comes back. No frontend, no Spring Boot — one Maven project,
one runnable jar, two modes.

```
                 Windows Docker host                      Hostinger VPS
              ┌────────────────────────┐            ┌──────────────────────────┐
              │  portal-monitor.jar    │  HTTPS     │  nginx :443              │
              │  --mode agent          │ ─────────► │    └─► :8085 (loopback)  │
              │                        │  every 60s │  docker: portal-monitor  │
              │  docker ps -a          │            │        --mode server     │
              │  every 60s             │            │          │               │
              └────────────────────────┘            │          ├─► MySQL       │
                       ▲                            │          └─► SMTP ─► you │
                       └──── HTTP checks ───────────┤                          │
                            (public ports)          └──────────────────────────┘
```

**Why two modes?** Several of your containers publish no ports, and others bind only
to `127.0.0.1`, so an HTTP check from the VPS cannot see them. The agent reads
`docker ps` locally and reports the truth; the server additionally fetches the public
web entry points to prove they answer from the outside.

---

## Table of contents

1. [What gets monitored](#1-what-gets-monitored)
2. [When it emails you](#2-when-it-emails-you)
3. [Deploy the server (Hostinger VPS, Docker)](#3-deploy-the-server-hostinger-vps-docker)
   - [Liveness and readiness](#31-liveness-and-readiness) · [Database access from Docker](#32-database-access-from-docker) · [Everyday deployment](#35-everyday-deployment-github--hostinger) · [Status, logs, restart, rollback](#36-operate-status-logs-restart-rollback)
4. [Deploy the agent (Windows)](#4-deploy-the-agent-windows)
5. [Firewall: what must be reachable](#5-firewall-what-must-be-reachable)
6. [Command line](#6-command-line)
7. [Day-to-day SQL](#7-day-to-day-sql)
8. [Troubleshooting](#8-troubleshooting)
9. [Settings reference](#9-settings-reference)
10. [Local development](#10-local-development)

---

## 1. What gets monitored

Two kinds of check live in the same `portals` table.

| `check_type` | Judged from | Covers |
|---|---|---|
| `CONTAINER` | the agent's `docker ps` report | all 31 containers, including those with no published ports |
| `HTTP` | the server fetching `health_url` | the 10 public web entry points, proving they answer from outside |

`database.sql` seeds one `CONTAINER` row per container, plus 10 `HTTP` rows — 41 in
total. Backend health URLs are included but commented out; enable them once you have
confirmed the paths exist.

**Container verdicts**

| Docker state | Verdict |
|---|---|
| `running`, healthcheck passing | UP |
| `running`, no healthcheck declared | UP |
| `running`, `(unhealthy)` | DOWN |
| `running`, `(health: starting)` | **neither** — the previous state is kept |
| `exited` / `restarting` / `paused` / `dead` / `created` | DOWN |
| not in the report at all | DOWN — "Container not found on host" |

`health: starting` deliberately neither alerts nor clears an existing failure streak,
so a container that restarts every few minutes cannot mask a real outage.

**HTTP verdicts** — UP on `200` (widen with `check.successCodes`). Redirects are
*not* followed: a `301` is not a healthy answer. Failures are recorded in readable
form: `HTTP 503`, `Timeout - no response in time`, `Couldn't connect to server`,
`Unknown host (DNS failed)`.

> **`solar-db-backup` is a batch job.** It exits when it finishes, which reads as DOWN.
> Either pause it (`UPDATE portals SET is_active=0 WHERE portal_name='solar-db-backup'`)
> or accept a nightly alert.

---

## 2. When it emails you

- A check must fail **3 times in a row** (`check.failThreshold`) before it is declared
  DOWN. One blip sends nothing.
- Email goes out **only on a state change**. `UP → DOWN` sends one email.
  `DOWN → UP` sends one recovery email with the total downtime. `UNKNOWN → UP`
  (the first successful check) sends nothing. While a check stays DOWN, **nothing
  further is sent**.
- If several checks change state in the same cycle you get **one combined email per
  recipient**, grouped by `app_group` — subject `[Portal Monitor] 3 services DOWN`, not 20 separate
  emails.

**Agent watchdog.** If no report arrives for 3 minutes
(`agent.offlineAfterSeconds`) you get one `[Portal Monitor] SERVER OFFLINE: <hostname>` email.
Container checks that were UP become `UNKNOWN`; checks already DOWN **stay DOWN**, so
you still get their RECOVERED email later. No per-container emails are sent — the
host is unreachable, so container status is genuinely unknown rather than bad. When
reports resume you get `[Portal Monitor] SERVER BACK ONLINE` with the outage duration.

The watchdog counts from the server's own start as well as the last report, so
restarting or redeploying the server never causes a false SERVER OFFLINE. If **no**
agent reports within that window after the server starts, you get one
`SERVER OFFLINE: Docker agent` email saying containers are not being monitored.

A failed email send is retried twice (after 5 s and 15 s) before it is logged as failed
in `alert_logs`. Bad credentials are not retried.

Recipients come from each row's `notify_email` (comma-separated is fine), falling
back to `alert.defaultEmail`. Every attempt, successful or not, lands in `alert_logs`.

---

## 3. Deploy the server (Hostinger VPS, Docker)

The server runs as **one Docker container, `portal-monitor`**, defined in
`docker-compose.yml` (compose project `portal-monitor`). It is separate from HRMS and
every other stack on the VPS: `deploy.sh` only ever recreates this one container, never
runs `docker compose down`, and never deletes images, volumes or data.

```
Hostinger VPS (Ubuntu 24.04)
  nginx :80/:443  monitor.sensovibe.in (Let's Encrypt, §3.7)
     └─► 127.0.0.1:8085 ──► container portal-monitor   (user uid 10001, mem limit 384 MB)
                              java -Xms32m -Xmx192m -cp /app/portal-monitor.jar
                                   portalmonitor.App --mode server
                                   --config /run/portal-monitor/application.properties
                                   (written at start by docker/entrypoint.sh from .env)
                              ├─► MySQL on the VPS   (DB_HOST=host.docker.internal,
                              │                       database portal_monitor_v2, user pm_app)
                              └─► SMTP               (Gmail 587/tls)
  Docker healthcheck every 30 s: portalmonitor.HealthProbe → GET /ready
```

### 3.1 Liveness and readiness

| Endpoint | Meaning | Answers 200 when | Used by |
|---|---|---|---|
| `GET /health` (`/health/live`) | **liveness** - the process is up | always, even with MySQL down (unchanged from 2.0) | external uptime probes via nginx |
| `GET /ready` (`/health/ready`) | **readiness** - it can do its job | MySQL accepts the login **and** a monitoring cycle completed recently | the Docker healthcheck, `deploy.sh` |

`/ready` returns JSON with the reason when it is not ready, for example:

```json
{ "status": "NOT_READY",
  "reasons": ["database DOWN: MySQL rejected the login (error 1045, access denied): ..."],
  "database": { "status": "DOWN", "downSince": "...", "failedAttempts": 8, ... },
  "monitoringCycle": { "status": "OK", "lastOk": "...", ... } }
```

So **`docker ps` now shows `(unhealthy)` within about 90 seconds of the database
failing**, and `(healthy)` again by itself once it recovers. Before 2.1 the healthcheck
only proved the process was alive, which is why the container showed "healthy" for
11 days while every cycle failed with `Access denied`. `/ready` is not published
through nginx.

### 3.2 Database access from Docker

Inside a container, `localhost` is the container itself, and MySQL sees the
connection coming from the container's bridge address (`172.x.x.x`), **not from
`localhost`**. Two consequences:

1. `db.host` must point at MySQL from the container's point of view:
   `host.docker.internal` for MySQL running on the VPS itself (compose maps it to the
   host), or the container name of a MySQL container on the same Docker network.
2. The MySQL account must exist **for that address**. An account created as
   `'portal_monitor'@'localhost'` does not match a connection from `172.20.0.2`, and
   MySQL answers exactly the production error:
   `Access denied for user 'portal_monitor'@'172.20.0.2' (using password: YES)`.

**Production setup (since 2026-11-12):** database `portal_monitor_v2`, account
`'pm_app'@'172.16.0.0/255.240.0.0'` (Docker's private range only) with SELECT, INSERT,
UPDATE, DELETE on `portal_monitor_v2.*` and nothing else. The older `portal_monitor`
database and account are no longer used and were left untouched.

**Diagnose a login failure (read-only)**, as root on the VPS:

```bash
mysql -u root -p -e "SELECT user, host, plugin FROM mysql.user WHERE user='pm_app';"   # prompts
```

* no row matching `172.%` (or `%`) → the account does not match the container's address;
* a matching row exists → the **password differs** from `DB_PASSWORD` in `.env`. 2.1
  warns at startup when the password starts or ends with a space.

The SQL patterns are in `deploy/mysql-docker-access.sql`. **Nothing runs it
automatically** - review it and run it yourself.

Verify from inside the container, with the app's own settings:

```bash
docker exec portal-monitor java -cp /app/portal-monitor.jar portalmonitor.App     --test-db --config /run/portal-monitor/application.properties
# Database OK: 37 active check(s) - 31 CONTAINER, 6 HTTP.
```

A brand-new database is created with `database.sql` and then `enable-http-checks.sql`
(both safe to re-run; they never delete data).

### 3.3 The configuration: `.env`

* All settings and secrets are in **`/opt/portal-monitor/.env`**: git-ignored, `chmod 600`,
  never copied into the image. Template: `.env.example`.
* `docker-compose.yml` passes it to the container (`env_file`); `docker/entrypoint.sh`
  writes `application.properties` to an in-memory tmpfs readable only by the app user
  (uid 10001), then starts the server. The container filesystem is otherwise read-only.
* `DB_HOST=host.docker.internal` reaches the MySQL running on the VPS.
* The startup log prints a summary in which passwords and tokens appear only as
  `set (N chars)` / `NOT SET`.
* Change a setting: edit `.env`, then `docker compose up -d --no-build portal-monitor`
  (recreates the container with the same image; ~5 s). `docker restart` does **not**
  re-read `.env`.

`deploy.sh` refuses to deploy if `.env` is tracked by git or is missing a required key
(it prints key names only, never values).

### 3.4 First-time switch to this setup (done 2026-11-12)

The original container was built from a `Dockerfile`, `docker-compose.yml` and
`docker/entrypoint.sh` that existed only on the VPS. They are now in git (adapted), and
the VPS copies were moved to `/opt/portal-monitor/backup-<date>/` before the first pull.

### 3.5 Everyday deployment: GitHub → Hostinger

On your PC:

```bash
mvn clean package                  # all tests must pass
git add -A && git commit -m "..."
git push origin main
```

On the VPS:

```bash
ssh root@<vps-ip>
cd /opt/portal-monitor
./deploy.sh
```

`deploy.sh` does, in order - stopping at the first failure:

| # | Step | If it fails |
|---|---|---|
| 1 | tools present, `docker-compose.yml` valid | stops - nothing changed |
| 2 | on branch `main`, no local edits to tracked files | stops - nothing changed |
| 3 | `git fetch` + fast-forward only (never merges, never forces) | stops - nothing changed |
| 4 | `.env` checks (§3.3) | stops - nothing changed |
| 5 | `docker build` → image `portal-monitor:<git-sha>` | stops - old container still running |
| 6 | `--test-db` **with the new image**, same `.env` and network | stops - old container still running |
| 7 | running image tagged `portal-monitor:rollback-<time>`, its logs saved to `deploy-logs/` | - |
| 8 | container recreated from the new image (**the only downtime: ~5-15 s**) | automatic rollback |
| 9 | wait for Docker `healthy` (≤ 180 s), then `/health`, `/ready`, a completed cycle, no DB errors, `nginx -t` | automatic rollback |
| 10 | tag `portal-monitor:current`, record in `.deploy/history` | - |

During the swap the agent's report may miss one 60-second cycle; the watchdog's grace
period means this never raises a false SERVER OFFLINE. Old images are **never deleted
automatically** - list them with `./deploy.sh history` and remove by hand
(`docker rmi portal-monitor:<tag>`) when no longer needed.

Options: `./deploy.sh --no-pull` (deploy what is checked out), `DEPLOY_BRANCH=x`,
`HEALTH_TIMEOUT=300`, `DEPLOY_PULL_BASE=1` (also pull newer base images).

### 3.6 Operate: status, logs, restart, rollback

```bash
cd /opt/portal-monitor
./deploy.sh status                    # container, health, /health, /ready, last log lines
./deploy.sh history                   # deploys, rollbacks, images kept

docker logs -f portal-monitor         # follow the logs
docker logs --since 1h portal-monitor
ls deploy-logs/                       # logs of replaced / failed containers

docker restart portal-monitor         # restart, same image and settings
docker compose up -d --no-build portal-monitor   # after editing .env

./deploy.sh rollback                  # back to the version before the last deploy
./deploy.sh rollback rollback-20261112-101110   # or any kept image
```

A rollback first keeps the currently running image, so it can itself be undone.
Rolled-back images use their own healthcheck; an image older than 2.1 has none, so
`deploy.sh` checks `/health` instead.

Logs rotate at 10 MB × 5 files per container (`docker-compose.yml`). The non-Docker
systemd unit in `deploy/portal-monitor.service` is not used by this setup.

### 3.7 nginx + HTTPS: monitor.sensovibe.in

* DNS (Cloudflare): **A** `monitor` → `72.62.228.26`, **DNS only** (grey cloud).
* nginx site `/etc/nginx/sites-available/portal-monitor` (a separate file; no other
  site is touched), certificate by certbot (auto-renewing). Only `/api/agent/report`,
  `/api/status` and `/health` are passed to the app; everything else is 404.
* Ports 80 and 443 must be reachable. Never expose 8085 or 3306.
* Agent: `agent.serverUrl=https://monitor.sensovibe.in`, `agent.token` = `API_TOKEN`
  from the VPS `.env`. The agent refuses plain `http://` to a remote host.

Set up from scratch (nginx 1.24 - the shipped file deliberately has no `http2 on;`):

```bash
cp deploy/nginx-portal-monitor.conf /etc/nginx/sites-available/portal-monitor
sed -i 's/monitor.yourdomain.com/monitor.sensovibe.in/' /etc/nginx/sites-available/portal-monitor
ln -s /etc/nginx/sites-available/portal-monitor /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
certbot --nginx --redirect -d monitor.sensovibe.in
```

```bash
curl https://monitor.sensovibe.in/health                                # {"status":"UP"}
curl -H "X-Agent-Token: <api.token>" https://monitor.sensovibe.in/api/status
```

---

## 4. Deploy the agent (Windows)

On the machine running Docker Desktop.

### 4.1 Java 17

Install the Temurin 17 JRE from <https://adoptium.net>, then confirm:

```cmd
java -version
where java
```

### 4.2 Files

```
C:\portal-monitor\
    portal-monitor.jar
    application.properties        <- copied from application-agent.properties
```

Edit `application.properties` and set **`agent.serverUrl`** (your HTTPS domain) and
**`agent.token`** (the same value as the server's `api.token`). The agent needs no
database credentials and no mailbox password — that is the point of splitting it.

### 4.3 Prove it works by hand first

```cmd
cd C:\portal-monitor
java -jar portal-monitor.jar --test-docker
java -jar portal-monitor.jar --mode agent --once
```

`--test-docker` prints every container with the verdict the server would reach. Run it
**as the account the service will use** — see the next step.

### 4.4 Install as a service (NSSM)

Download NSSM from <https://nssm.cc/download>, unzip it, then run
`deploy\install-agent-windows.cmd` from an **elevated** prompt after editing the four
paths at the top. It installs the service but does not start it.

> **The service account must be able to run `docker`.**
> LocalSystem (NSSM's default) usually **cannot** talk to Docker Desktop, because
> Docker Desktop runs per-user. Either run the service as the user who is logged into
> Docker Desktop —
> `nssm set PortalMonitorAgent ObjectName ".\YourUser" "YourPassword"` — with that user
> in the local **docker-users** group, or install Docker Engine as a Windows service
> instead. Verify with `--test-docker` as that account *before* starting the service.

```cmd
nssm start   PortalMonitorAgent
nssm status  PortalMonitorAgent
```

Logs roll at 10 MB in `C:\portal-monitor\logs\agent.log`.

A Task Scheduler "at startup" task works too, but NSSM restarts the agent if it ever
exits, which Task Scheduler will not.

---

## 5. Firewall: what must be reachable

**From the VPS to the Windows machine** — only the ports behind the 10 HTTP checks.
Open these inbound on the Windows firewall and on any router doing NAT, ideally
restricted to the VPS's IP:

| Port | Service |
|---|---|
| 3001 | Senvia UAT frontend |
| 8090 | Inventory frontend |
| 1113 | Solar frontend |
| 1111 | Vibeinscript website |
| 3000 | Sensovibe website |
| 3003 | POS demo frontend |
| 6001 | TMS frontend |
| 1112 | Energlob website |
| 1880 | Node-RED |
| 18083 | EMQX dashboard |

```powershell
New-NetFirewallRule -DisplayName "Portal Monitor checks" -Direction Inbound `
  -Protocol TCP -LocalPort 3001,8090,1113,1111,3000,3003,6001,1112,1880,18083 `
  -RemoteAddress 203.0.113.45 -Action Allow      # <- your VPS IP
```

The containers that bind to `127.0.0.1` (`inventory-backend` :8091,
`solar-backend` :8092, `sensovibe-backend` :8082, and the MySQL / Postgres / Redis
containers) are **not** reachable from outside and need no firewall rule — the
`CONTAINER` check covers them.

**From the Windows machine to the VPS** — outbound 443 only. No inbound rule is needed
on the VPS beyond 80/443 for nginx; port 8085 stays on loopback.

---

## 6. Command line

```
java -jar portal-monitor.jar --mode server            run the server (VPS)
java -jar portal-monitor.jar --mode agent             run the agent (Windows)
java -jar portal-monitor.jar --mode server --once     one HTTP cycle, then exit
java -jar portal-monitor.jar --mode agent  --once     one docker report, then exit
java -jar portal-monitor.jar --test-email <address>   send one test email
java -jar portal-monitor.jar --test-db                check the database settings
java -jar portal-monitor.jar --test-docker            print what the agent sees
java -jar portal-monitor.jar --config <file>          use a specific properties file
java -jar portal-monitor.jar --help
```

The config file defaults to `./application.properties`, then to the folder holding the
jar. Bad settings are rejected at startup with the key named, not at 3am. Any setting
can be overridden by an environment variable (`db.host` → `PM_DB_HOST`, or
`PM_DB_PASSWORD_FILE=/run/secrets/x` to read it from a file).

Server endpoints: `GET /health` (liveness), `GET /ready` (readiness, 503 with reasons),
`GET /api/status` and `POST /api/agent/report` (both need the token). The container
healthcheck is `java -cp portal-monitor.jar portalmonitor.HealthProbe [url]`
(exit 0 = ready).

---

## 7. Day-to-day SQL

`database.sql` ends with a commented block of these. The ones you will want often:

```sql
-- What is broken right now
SELECT app_group, portal_name, check_type, status, fail_count, last_detail, last_checked
  FROM portals WHERE is_active = 1 AND status <> 'UP' ORDER BY app_group;

-- Is the Windows host reporting?
SELECT agent_id, hostname, status, last_seen,
       TIMESTAMPDIFF(SECOND, last_seen, NOW()) AS seconds_ago FROM agents;

-- Pause / resume a check (no checks, no emails while paused)
UPDATE portals SET is_active = 0 WHERE portal_name = 'solar-db-backup';
UPDATE portals SET is_active = 1 WHERE portal_name = 'solar-db-backup';
UPDATE portals SET is_active = 0 WHERE app_group  = 'POS demo';       -- a whole group

-- Change recipients (comma-separated for several)
UPDATE portals SET notify_email = 'ops@yourdomain.com' WHERE portal_name = 'emqx';
UPDATE portals SET notify_email = 'a@x.com, b@x.com'   WHERE app_group   = 'Senvia UAT';

-- Add a check
INSERT INTO portals (portal_name, app_group, check_type, container_name)
VALUES ('new_container', 'Senvia UAT', 'CONTAINER', 'new_container');
INSERT INTO portals (portal_name, app_group, check_type, health_url)
VALUES ('HTTP new site', 'Sensovibe', 'HTTP', 'http://203.0.113.45:9000/health');

-- Uptime % per check over the last 7 days
SELECT p.app_group, p.portal_name, ROUND(100*AVG(c.is_up),2) AS uptime_pct, COUNT(*) AS checks
  FROM check_logs c JOIN portals p ON p.id = c.portal_id
 WHERE c.checked_at > NOW() - INTERVAL 7 DAY
 GROUP BY p.app_group, p.portal_name ORDER BY uptime_pct;

-- Emails sent in the last day, including failures
SELECT COALESCE(p.portal_name,'(agent)') AS name, a.alert_type, a.sent_to,
       a.success, a.error, a.sent_at
  FROM alert_logs a LEFT JOIN portals p ON p.id = a.portal_id
 WHERE a.sent_at > NOW() - INTERVAL 1 DAY ORDER BY a.sent_at DESC;
```

Changes take effect on the next cycle — no restart needed.
`check_logs` is trimmed to `log.retentionDays` (30) at the end of every cycle.

---

## 8. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Container `(healthy)` but nothing works (2.0) | The 2.0 healthcheck only proved the process was alive. 2.1 checks readiness: see `./deploy.sh status` or `curl 127.0.0.1:8085/ready`. |
| `Access denied for user 'portal_monitor'@'172.20.0.2'` | The container connects from its Docker address, not `localhost`. See §3.2: the account must exist for that host, and the password must equal `db.password`. |
| `Nothing accepted the connection at db.host/db.port` | In Docker, `localhost` is the container. Use `db.host=host.docker.internal` for MySQL on the VPS. |
| `/health` gets no answer on `127.0.0.1:8085` although the log says "API listening" | `server.bind=127.0.0.1` inside a container. It must be `0.0.0.0` in Docker (compose sets `PM_SERVER_BIND`). |
| `deploy.sh`: "cannot use the database" | Expected while MySQL access is broken - it stops before touching the running container. Fix §3.2, run again. |
| `deploy.sh`: "Permission denied" | Run once as `bash deploy.sh`; the executable bit is stored in git from 2.1 on. |
| `$'\r': command not found` | A script was saved with Windows line endings. `.gitattributes` prevents this for files from git; fix a copied file with `sed -i 's/\r$//' deploy.sh`. |
| `Missing required setting: smtp.host` | Server mode needs `smtp.host` and `smtp.from`. The agent config needs neither. |
| `api.token must be set in server mode` | Generate one: `openssl rand -hex 32`. Must be at least 16 characters. |
| Agent logs `401 - agent.token does not match` | `agent.token` differs from the server's `api.token`. They must match exactly. |
| `Could not run docker: ... is Docker running?` | Docker Desktop is stopped, or the service account cannot reach it. Run `--test-docker` as that account; see §4.4. |
| `docker ps did not finish within 30s` | The Docker daemon is wedged — most often a **full disk**. Check free space, then restart Docker Desktop. The agent retries next cycle rather than hanging. |
| `SERVER OFFLINE` but the machine is fine | The agent is not reporting: service stopped, `agent.serverUrl` wrong, or outbound 443 blocked. Check `logs\agent.log`. |
| Every container reads "Container not found on host" | `container_name` does not match reality. Compare `docker ps -a --format "{{.Names}}"` with `SELECT container_name FROM portals`. |
| All HTTP checks say "Couldn't connect to server" | The seed still contains `SERVER_PUBLIC_IP`, or the ports are not open to the VPS. See §5. |
| One HTTP check times out on the first try, then works | Cold DNS + TLS handshake. Keep `check.requestTimeoutSeconds` at 10 or more for internet endpoints. |
| `Access denied for user` | Wrong `db.user` / `db.password`, or `db.user=root` (socket-only on Ubuntu, see §3.2). Confirm with `--test-db`. |
| `nginx: unknown directive "http2"` | An old copy of the nginx file. Ubuntu 24.04 ships nginx 1.24; use the current `deploy/nginx-portal-monitor.conf`. |
| `cannot load certificate ... letsencrypt` | The 443 block was added before certbot ran. Use the shipped port-80-only file, then run certbot. |
| Agent logs `Server answered HTTP 301` | `agent.serverUrl` uses `http://`. Change it to `https://`. |
| Agent logs `docker ps returned no containers - NOT reporting` | The account sees a different Docker engine: wrong `docker context`, Windows-containers mode, or no access. The server sends SERVER OFFLINE rather than 31 false DOWN emails. |
| Log says `Skipping HTTP ...: health_url is not configured` | The row still has `SERVER_PUBLIC_IP`. Set a real URL, or run `enable-http-checks.sql`, which pauses it. |
| `[Portal Monitor] SERVER OFFLINE: Docker agent` with "Last report: never" | No agent has reported since the server started. Check that the agent service is running and that its `agent.serverUrl` / `agent.token` are right. |
| `check.php` errors in the Hostinger cron log | That is the retired 1.x PHP checker. Delete its cron job; v2 is the Java service only. |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password`. Local DB: `db.params=allowPublicKeyRetrieval=true`. Remote DB: `db.params=sslMode=trust`. |
| No email, but `alert_logs.success = 1` | The relay accepted it. Check Spam, and that `smtp.from` is a real mailbox on that domain (SPF/DKIM). |
| `AuthenticationFailedException` | Hostinger wants the full mailbox address as `smtp.user`. Port 465 → `smtp.security=ssl`; 587 → `tls`. |
| Timestamps look shifted | `app.timezone` (default `Asia/Kolkata`). The app sets the MySQL session offset to match. |
| Want to re-test alerting on a live check | `UPDATE portals SET status='UNKNOWN', fail_count=0, fail_since=NULL WHERE portal_name='x';` — the next state change emails again. |

---

## 9. Settings reference

Every key can also be set by environment variable: `PM_` + upper-case key with dots as
underscores, or `PM_<KEY>_FILE` to read it from a file.

**Server** (`application-server.properties`)

| Key | Default | Notes |
|---|---|---|
| `db.host` / `db.port` / `db.name` | `localhost` / `3306` / `portal_monitor` | |
| `db.user` / `db.password` | `root` / — | passed as JDBC properties, never in the URL. Use a dedicated user, not `root` |
| `db.params` | `allowPublicKeyRetrieval=true` | use `sslMode=trust` for a remote DB |
| `smtp.host` / `smtp.port` / `smtp.security` | — / `465` / `ssl` | `ssl` for 465, `tls` for 587 |
| `smtp.user` / `smtp.password` / `smtp.from` | — | `from` must be a real mailbox |
| `alert.defaultEmail` | — | fallback recipient, and used for all agent alerts |
| `check.intervalSeconds` | `60` | cycles never overlap |
| `check.failThreshold` | `3` | consecutive failures before DOWN |
| `check.connectTimeoutSeconds` / `check.requestTimeoutSeconds` | `5` / `10` | |
| `check.successCodes` | `200` | comma-separated |
| `check.verifySsl` | `true` | `false` disables verification for **all** checks |
| `server.port` / `server.bind` | `8085` / `127.0.0.1` | loopback for a plain install; `0.0.0.0` inside Docker (port published on the VPS loopback only) |
| `api.token` | — | shared secret, at least 16 characters |
| `agent.offlineAfterSeconds` | `180` | silence before SERVER OFFLINE |
| `log.retentionDays` | `30` | `check_logs` trim |
| `app.timezone` | `Asia/Kolkata` | also sets the MySQL session offset |

**Agent** (`application-agent.properties`)

| Key | Default | Notes |
|---|---|---|
| `agent.serverUrl` | — | base URL or the full endpoint |
| `agent.token` | — | must equal the server's `api.token` |
| `agent.id` | `windows-docker-01` | identifies the host |
| `agent.intervalSeconds` | `60` | keep well under the server's `agent.offlineAfterSeconds` |
| `agent.dockerCommand` | `docker` | full path if not on the service PATH (double the backslashes) |
| `agent.dockerTimeoutSeconds` | `30` | how long to wait for a wedged daemon |
| `agent.allowInsecureHttp` | `false` | allow `http://` to a non-local server (sends the token unencrypted) |
| `check.connectTimeoutSeconds` / `check.requestTimeoutSeconds` | `5` / `10` | for the POST |
| `app.timezone` | `Asia/Kolkata` | |

---

## 10. Local development

Java 17+ and Maven 3.6+ (Docker only for building the image).

```bash
mvn clean package          # runs all tests, then builds target/portal-monitor.jar (~6 MB, no runtime deps)
mvn test                   # tests only

# Also run the MySQL integration test (creates and drops portal_monitor_it):
PM_IT_DB_HOST=127.0.0.1 PM_IT_DB_USER=root PM_IT_DB_PASSWORD='...' mvn test

# Run the server locally against a local MySQL:
cp application-server.properties application.properties     # git-ignored; edit db.* and smtp.*
java -jar target/portal-monitor.jar --test-db
java -jar target/portal-monitor.jar --mode server
curl http://127.0.0.1:8085/ready

# Run the agent once against it:
java -jar target/portal-monitor.jar --mode agent --once --config application-agent.properties

# Build and run the server image exactly as on the VPS:
docker build -t portal-monitor:dev .
```

The tests cover the UP/DOWN state machine, configuration and environment overrides,
database error classification, the HTTP API (authentication, reporting, liveness,
readiness, the healthcheck probe), agent reporting, watchdog timing, Docker output
parsing, email templates and failure handling, and - with `PM_IT_DB_*` set - the real
SQL against MySQL.
