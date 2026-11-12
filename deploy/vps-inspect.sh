#!/usr/bin/env bash
# =====================================================================
#  READ-ONLY inspection of the Portal Monitor deployment on the VPS.
#  Changes nothing. Prints no passwords or tokens: config values for
#  secret keys are masked, and container environment variables are
#  listed by NAME only.
#
#    sudo bash deploy/vps-inspect.sh [project-dir] > inspect.txt
#
#  Share inspect.txt to get a diagnosis based on the real setup.
# =====================================================================
set -uo pipefail
DIR="${1:-/opt/portal-monitor}"
C=portal-monitor
mask() { sed -E 's/^([[:space:]]*[^#=[:space:]]*(password|passwd|token|secret|key)[^=]*=).*/\1***MASKED***/I'; }
hr()   { printf '\n==================== %s ====================\n' "$*"; }

hr "host"
hostname; . /etc/os-release 2>/dev/null && echo "$PRETTY_NAME"; uptime
free -h; df -h / | tail -1
docker version --format 'docker {{.Server.Version}}' 2>/dev/null; docker compose version 2>/dev/null

hr "project dir $DIR"
ls -la "$DIR"
if git -C "$DIR" rev-parse >/dev/null 2>&1; then
  echo "--- git"; git -C "$DIR" status -sb | head -30; git -C "$DIR" log -3 --oneline; git -C "$DIR" remote -v
else
  echo "(not a git checkout)"
fi

hr "compose / Dockerfile in $DIR"
for f in docker-compose.yml docker-compose.yaml compose.yml compose.yaml Dockerfile .env; do
  [[ -f "$DIR/$f" ]] && { echo "--- $f"; mask <"$DIR/$f"; }
done

hr "containers"
docker ps -a --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'

hr "container $C"
docker inspect "$C" --format 'image={{.Config.Image}} id={{.Image}}
created={{.Created}} restart={{.HostConfig.RestartPolicy.Name}} init={{.HostConfig.Init}}
compose project={{index .Config.Labels "com.docker.compose.project"}} workdir={{index .Config.Labels "com.docker.compose.project.working_dir"}} files={{index .Config.Labels "com.docker.compose.project.config_files"}}
entrypoint={{json .Config.Entrypoint}} cmd={{json .Config.Cmd}} user={{.Config.User}}
healthcheck={{json .Config.Healthcheck}}
networks={{range $k,$v := .NetworkSettings.Networks}}{{$k}}({{$v.IPAddress}} gw {{$v.Gateway}}) {{end}}
extra_hosts={{json .HostConfig.ExtraHosts}}' 2>&1
echo "--- mounts"
docker inspect "$C" --format '{{range .Mounts}}{{.Type}} {{.Source}} -> {{.Destination}} (rw={{.RW}}){{"\n"}}{{end}}' 2>&1
echo "--- environment variable NAMES (values hidden)"
docker inspect "$C" --format '{{range .Config.Env}}{{.}}{{"\n"}}{{end}}' 2>/dev/null | cut -d= -f1 | sort
echo "--- health log (last 3)"
docker inspect "$C" --format '{{if .State.Health}}{{range .State.Health.Log}}{{.End}} exit={{.ExitCode}} {{.Output}}{{"\n"}}{{end}}{{end}}' 2>/dev/null | tail -3

hr "config file used by the container (secrets masked)"
src="$(docker inspect "$C" --format '{{range .Mounts}}{{if eq .Destination "/run/portal-monitor/application.properties"}}{{.Source}}{{end}}{{end}}' 2>/dev/null)"
if [[ -n "$src" && -f "$src" ]]; then
  echo "source: $src  ($(stat -c 'owner=%U uid=%u mode=%a' "$src"))"
  git -C "$DIR" ls-files --error-unmatch "$src" >/dev/null 2>&1 && echo "!! this file is TRACKED by git"
  grep -vE '^[[:space:]]*(#|$)' "$src" | mask
else
  echo "no host file mounted at /run/portal-monitor/application.properties (source='$src')"
  docker exec "$C" sh -c 'ls -l /run/portal-monitor/ 2>&1' 2>&1
fi

hr "app logs (last 60)"
docker logs --tail 60 "$C" 2>&1

hr "MySQL"
ss -ltnp 2>/dev/null | grep -E ':(3306|33060)\b' || echo "nothing listening on 3306 on the host"
docker ps --format '{{.Names}} {{.Image}} {{.Ports}}' | grep -iE 'mysql|mariadb' || echo "no MySQL/MariaDB container"
grep -rhsE '^[[:space:]]*bind-address' /etc/mysql/ 2>/dev/null | sed 's/^/bind: /'
systemctl is-active mysql 2>/dev/null | sed 's/^/mysql service: /'
if command -v mysql >/dev/null 2>&1; then
  echo "--- accounts named portal_monitor (read-only query via local root socket)"
  mysql -NBe "SELECT user, host, plugin FROM mysql.user WHERE user='portal_monitor';" 2>&1
  mysql -NBe "SHOW DATABASES LIKE 'portal_monitor';" 2>&1
fi

hr "nginx"
nginx -v 2>&1
ls -l /etc/nginx/sites-enabled/ 2>&1
nginx -T 2>/dev/null | grep -nE '^# configuration file|server_name|listen|proxy_pass|ssl_certificate |http2' | head -60

hr "local endpoints"
curl -s -m 5 -w '  (HTTP %{http_code})\n' http://127.0.0.1:8085/health || echo "no answer on 127.0.0.1:8085/health"
curl -s -m 20 -w '\n  (HTTP %{http_code})\n' http://127.0.0.1:8085/ready || true
echo; echo "done - nothing was changed."
