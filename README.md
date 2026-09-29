# Portal Uptime Monitor 2.0

Backend-only monitoring for Docker deployments. Emails you when a deployment goes
down and again when it comes back. No frontend, no Spring Boot — one Maven project,
one runnable jar, two modes.

```
                 Windows Docker host                      Hostinger VPS
              ┌────────────────────────┐            ┌──────────────────────────┐
              │  portal-monitor.jar    │  HTTPS     │  nginx :443              │
              │  --mode agent          │ ─────────► │    └─► :8085 (loopback)  │
              │                        │  every 60s │        portal-monitor    │
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
3. [Deploy the server (VPS)](#3-deploy-the-server-vps)
4. [Deploy the agent (Windows)](#4-deploy-the-agent-windows)
5. [Firewall: what must be reachable](#5-firewall-what-must-be-reachable)
6. [Command line](#6-command-line)
7. [Day-to-day SQL](#7-day-to-day-sql)
8. [Troubleshooting](#8-troubleshooting)
9. [Settings reference](#9-settings-reference)

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
  recipient**, grouped by `app_group` — subject `🔴 3 services DOWN`, not 20 separate
  emails.

**Agent watchdog.** If no report arrives for 3 minutes
(`agent.offlineAfterSeconds`) you get one `🔴 SERVER OFFLINE: <hostname>` email and
every container check is set to `UNKNOWN`. No per-container emails are sent — the
host is unreachable, so container status is genuinely unknown rather than bad. When
reports resume you get `🟢 SERVER BACK ONLINE` with the outage duration.

Recipients come from each row's `notify_email` (comma-separated is fine), falling
back to `alert.defaultEmail`. Every attempt, successful or not, lands in `alert_logs`.

---

## 3. Deploy the server (VPS)

Ubuntu 24.04, as root or with `sudo`.

### 3.1 Java, MySQL, nginx

```bash
apt update
apt install -y openjdk-17-jre-headless mysql-server nginx
java -version                      # must print 17 or newer
```

### 3.2 Database

```bash
mysql -e "CREATE DATABASE portal_monitor CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql portal_monitor < database.sql
```

Put your real public IP into the HTTP checks:

```sql
UPDATE portals SET health_url = REPLACE(health_url, 'SERVER_PUBLIC_IP', '203.0.113.45')
 WHERE check_type = 'HTTP';
```

Confirm the seed loaded — expect 31 CONTAINER and 10 HTTP:

```sql
SELECT check_type, COUNT(*) FROM portals GROUP BY check_type;
```

### 3.3 Install the jar and its config

```bash
useradd -r -s /usr/sbin/nologin monitor
mkdir -p /opt/portal-monitor
cp portal-monitor.jar /opt/portal-monitor/
cp application-server.properties /opt/portal-monitor/application.properties
chown -R monitor:monitor /opt/portal-monitor
chmod 600 /opt/portal-monitor/application.properties    # holds passwords and the API token
```

Edit `/opt/portal-monitor/application.properties` — every line marked `<-- CHANGE`:
`db.*`, `smtp.*`, and **`api.token`**. Generate the token with:

```bash
openssl rand -hex 32
```

> The database password may contain `#`, `@` or `!` exactly as-is. Credentials are
> passed to JDBC as separate properties and never appear in the connection URL, so
> nothing needs escaping. (`#` only starts a comment at the *start* of a properties
> line, so `db.password=Laxman@1234#` is read in full.)

### 3.4 Prove it works before installing the service

```bash
cd /opt/portal-monitor
java -jar portal-monitor.jar --test-db                     # connects, counts the checks
java -jar portal-monitor.jar --test-email you@example.com  # proves SMTP
java -jar portal-monitor.jar --mode server --once          # one HTTP cycle, printed
```

### 3.5 systemd

```bash
cp deploy/portal-monitor.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now portal-monitor
systemctl status portal-monitor
journalctl -u portal-monitor -f          # watch it run
```

### 3.6 nginx + HTTPS

The app listens on `127.0.0.1:8085` and is never exposed directly. nginx terminates
TLS so the agent's token never crosses the internet in clear text.

```bash
cp deploy/nginx-portal-monitor.conf /etc/nginx/sites-available/portal-monitor
ln -s /etc/nginx/sites-available/portal-monitor /etc/nginx/sites-enabled/
# edit the file and replace monitor.yourdomain.com throughout
nginx -t && systemctl reload nginx

apt install -y certbot python3-certbot-nginx
certbot --nginx -d monitor.yourdomain.com     # rewrites the file with the real cert paths
```

Check it from outside:

```bash
curl https://monitor.yourdomain.com/health                 # {"status":"UP"}
curl -H "X-Agent-Token: <api.token>" https://monitor.yourdomain.com/api/status
```

`/health` is deliberately open and dependency-free — it answers even when MySQL is
down, so you can point an external uptime service at the monitor itself. Point that
service from somewhere outside this VPS; a monitor cannot report its own death.

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
jar. Bad settings are rejected at startup with the key named, not at 3am.

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
| `Missing required setting: smtp.host` | Server mode needs `smtp.host` and `smtp.from`. The agent config needs neither. |
| `api.token must be set in server mode` | Generate one: `openssl rand -hex 32`. Must be at least 16 characters. |
| Agent logs `401 - agent.token does not match` | `agent.token` differs from the server's `api.token`. They must match exactly. |
| `Could not run docker: ... is Docker running?` | Docker Desktop is stopped, or the service account cannot reach it. Run `--test-docker` as that account; see §4.4. |
| `docker ps did not finish within 30s` | The Docker daemon is wedged — most often a **full disk**. Check free space, then restart Docker Desktop. The agent retries next cycle rather than hanging. |
| `SERVER OFFLINE` but the machine is fine | The agent is not reporting: service stopped, `agent.serverUrl` wrong, or outbound 443 blocked. Check `logs\agent.log`. |
| Every container reads "Container not found on host" | `container_name` does not match reality. Compare `docker ps -a --format "{{.Names}}"` with `SELECT container_name FROM portals`. |
| All HTTP checks say "Couldn't connect to server" | The seed still contains `SERVER_PUBLIC_IP`, or the ports are not open to the VPS. See §5. |
| One HTTP check times out on the first try, then works | Cold DNS + TLS handshake. Keep `check.requestTimeoutSeconds` at 10 or more for internet endpoints. |
| `Access denied for user` | Wrong `db.user` / `db.password`. Confirm with `--test-db`. |
| `Public Key Retrieval is not allowed` | MySQL 8 `caching_sha2_password`. Local DB: `db.params=allowPublicKeyRetrieval=true`. Remote DB: `db.params=sslMode=trust`. |
| No email, but `alert_logs.success = 1` | The relay accepted it. Check Spam, and that `smtp.from` is a real mailbox on that domain (SPF/DKIM). |
| `AuthenticationFailedException` | Hostinger wants the full mailbox address as `smtp.user`. Port 465 → `smtp.security=ssl`; 587 → `tls`. |
| Timestamps look shifted | `app.timezone` (default `Asia/Kolkata`). The app sets the MySQL session offset to match. |
| Want to re-test alerting on a live check | `UPDATE portals SET status='UNKNOWN', fail_count=0, fail_since=NULL WHERE portal_name='x';` — the next state change emails again. |

---

## 9. Settings reference

**Server** (`application-server.properties`)

| Key | Default | Notes |
|---|---|---|
| `db.host` / `db.port` / `db.name` | `localhost` / `3306` / `portal_monitor` | |
| `db.user` / `db.password` | `root` / — | passed as JDBC properties, never in the URL |
| `db.params` | `allowPublicKeyRetrieval=true` | use `sslMode=trust` for a remote DB |
| `smtp.host` / `smtp.port` / `smtp.security` | — / `465` / `ssl` | `ssl` for 465, `tls` for 587 |
| `smtp.user` / `smtp.password` / `smtp.from` | — | `from` must be a real mailbox |
| `alert.defaultEmail` | — | fallback recipient, and used for all agent alerts |
| `check.intervalSeconds` | `60` | cycles never overlap |
| `check.failThreshold` | `3` | consecutive failures before DOWN |
| `check.connectTimeoutSeconds` / `check.requestTimeoutSeconds` | `5` / `10` | |
| `check.successCodes` | `200` | comma-separated |
| `check.verifySsl` | `true` | `false` disables verification for **all** checks |
| `server.port` / `server.bind` | `8085` / `127.0.0.1` | keep on loopback behind nginx |
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
| `check.connectTimeoutSeconds` / `check.requestTimeoutSeconds` | `5` / `10` | for the POST |
| `app.timezone` | `Asia/Kolkata` | |

---

## Build from source

```bash
mvn clean package          # runs the tests, then builds target/portal-monitor.jar
mvn test                   # tests only
```

Java 17+ and Maven 3.6+. The shade plugin bundles the JDBC driver, Jakarta Mail and
`org.json` into one ~6 MB jar with no external dependencies at runtime.
