#!/usr/bin/env bash
# =====================================================================
#  Portal Monitor - deploy, verify and roll back on the Hostinger VPS.
#
#    ./deploy.sh                  pull the branch, build, deploy, verify (auto-rollback on failure)
#    ./deploy.sh --no-pull        same, from the code already checked out
#    ./deploy.sh rollback [TAG]   go back to the previous image (or a given tag)
#    ./deploy.sh status           what is running, health, readiness, recent logs
#    ./deploy.sh history          deployed / rollback images
#
#  Touches ONLY the portal-monitor container. Never runs `docker compose down`,
#  never deletes images, volumes or database data.
#
#  Downtime: only the container swap, typically 5-15 s. Everything that can fail
#  (git, config checks, image build, database test with the new image) runs first,
#  while the old container keeps serving.
# =====================================================================
set -Eeuo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$APP_DIR"

# Only the deploy settings are read from .env (never the secrets); the environment wins.
if [[ -f .env ]]; then
  set -a; # shellcheck disable=SC1091
  source <(grep -E '^(PM_PUBLIC_URL|DEPLOY_BRANCH)=' .env || true)
  set +a
fi

BRANCH="${DEPLOY_BRANCH:-main}"
SERVICE="portal-monitor"
CONTAINER="portal-monitor"
IMAGE_REPO="portal-monitor"
CONFIG_IN_CONTAINER="/run/portal-monitor/application.properties"
ENV_FILE="$APP_DIR/.env"
API="http://127.0.0.1:8085"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
STATE_DIR="$APP_DIR/.deploy"
LOG_DIR="$APP_DIR/deploy-logs"
HISTORY="$STATE_DIR/history"

# ------------------------------------------------------------------ output helpers
c_b=$'\033[1m'; c_g=$'\033[32m'; c_y=$'\033[33m'; c_r=$'\033[31m'; c_0=$'\033[0m'
step() { printf '\n%s==> %s%s\n' "$c_b" "$*" "$c_0"; }
ok()   { printf '  %sOK%s    %s\n' "$c_g" "$c_0" "$*"; }
warn() { printf '  %sWARN%s  %s\n' "$c_y" "$c_0" "$*"; }
fail() { printf '\n%sFAILED:%s %s\n' "$c_r" "$c_0" "$*" >&2; exit 1; }

compose() { docker compose -f "$APP_DIR/docker-compose.yml" "$@"; }
now()     { date '+%Y-%m-%d %H:%M:%S'; }
stamp()   { date '+%Y%m%d-%H%M%S'; }

container_exists() { docker inspect "$CONTAINER" >/dev/null 2>&1; }
running_image_id() { docker inspect -f '{{.Image}}' "$CONTAINER" 2>/dev/null || true; }
health_status()    { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER" 2>/dev/null || echo missing; }

# ------------------------------------------------------------------ checks

check_tools() {
  command -v docker >/dev/null || fail "docker is not installed."
  docker compose version >/dev/null 2>&1 || fail "the docker compose plugin is not installed."
  command -v git >/dev/null || fail "git is not installed."
  command -v curl >/dev/null || fail "curl is not installed (apt install -y curl)."
  git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "$APP_DIR is not a git checkout."
  compose config -q || fail "docker-compose.yml is invalid (see above)."
  ok "docker, compose, git, curl present; docker-compose.yml valid"
}

check_git() {
  local cur
  cur="$(git rev-parse --abbrev-ref HEAD)"
  [[ "$cur" == "$BRANCH" ]] || fail "on branch '$cur', expected '$BRANCH'. Run: git checkout $BRANCH"
  if ! git diff --quiet || ! git diff --cached --quiet; then
    git status --short --untracked-files=no
    fail "tracked files were changed on the server (above). Commit them in your local repo instead, or inspect with 'git diff' and discard with 'git checkout -- <file>'."
  fi
  ok "branch $BRANCH, no local changes to tracked files"
}

pull_code() {
  local before after
  before="$(git rev-parse --short HEAD)"
  git fetch --quiet origin "$BRANCH" || fail "git fetch failed - check network / GitHub access."
  if ! git merge-base --is-ancestor HEAD "origin/$BRANCH"; then
    fail "local $BRANCH has commits not on origin/$BRANCH (history diverged). Resolve by hand; nothing was changed."
  fi
  if [[ "$(git rev-parse HEAD)" == "$(git rev-parse "origin/$BRANCH")" ]]; then
    ok "already at origin/$BRANCH ($before)"
  else
    echo "  incoming commits:"
    git log --oneline "HEAD..origin/$BRANCH" | sed 's/^/    /'
    git merge --ff-only --quiet "origin/$BRANCH"
    after="$(git rev-parse --short HEAD)"
    ok "updated $before -> $after"
  fi
}

check_env() {
  [[ -f "$ENV_FILE" ]] || fail "$ENV_FILE not found. Create it from .env.example (chmod 600)."
  if git ls-files --error-unmatch .env >/dev/null 2>&1; then
    fail ".env is tracked by git, so a pull could replace your production secrets. Run: git rm --cached .env"
  fi
  local mode; mode="$(stat -c '%a' "$ENV_FILE")"
  [[ "${mode: -1}" == "0" && "${mode: -2:1}" == "0" ]] || warn ".env is readable by other users (mode $mode). Recommended: chmod 600 .env"

  # Names only - values are never printed.
  local k missing=()
  for k in DB_HOST DB_NAME DB_USERNAME DB_PASSWORD SMTP_HOST SMTP_FROM ALERT_DEFAULT_EMAIL API_TOKEN; do
    grep -qE "^${k}=.+" "$ENV_FILE" || missing+=("$k")
  done
  (( ${#missing[@]} == 0 )) || fail ".env is missing a value for: ${missing[*]}"
  local host; host="$(grep -E '^DB_HOST=' "$ENV_FILE" | tail -1 | cut -d= -f2- | tr -d '[:space:]')"
  if [[ "$host" == "localhost" || "$host" == "127.0.0.1" ]]; then
    warn "DB_HOST=$host. Inside Docker that is the container itself; for MySQL on the VPS use DB_HOST=host.docker.internal."
  fi
  ok ".env present (mode $mode), required keys set"
}

# ------------------------------------------------------------------ build and verify

build_image() {
  SHA="$(git rev-parse --short=12 HEAD)"
  NEW_TAG="$SHA"
  if docker image inspect "$IMAGE_REPO:$NEW_TAG" >/dev/null 2>&1; then
    ok "image $IMAGE_REPO:$NEW_TAG already built - reusing"
    return
  fi
  # Base images are pulled only when missing, so a Docker Hub hiccup cannot block a
  # deploy. Refresh them (security updates) with:  DEPLOY_PULL_BASE=1 ./deploy.sh
  local pull_flag=()
  [[ "${DEPLOY_PULL_BASE:-0}" == "1" ]] && pull_flag=(--pull)
  docker build "${pull_flag[@]}" --build-arg "GIT_SHA=$SHA" -t "$IMAGE_REPO:$NEW_TAG" "$APP_DIR" \
    || fail "image build failed (see above). The running container was not touched."
  ok "built $IMAGE_REPO:$NEW_TAG"
}

# Runs --test-db with the NEW image, the same mounts, env and network - before any switch.
test_db_with_image() {
  local tag="$1" out
  if out="$(PM_IMAGE_TAG="$tag" compose run --rm --no-deps -T "$SERVICE" \
            --test-db --config "$CONFIG_IN_CONTAINER" 2>&1)"; then
    echo "$out" | sed 's/^/    /' | tail -5
    ok "database reachable from the new image"
  else
    echo "$out" | sed 's/^/    /' | tail -12
    fail "the new image cannot use the database (above). Nothing was deployed - the old container is still running.
    Fix the DB_* values in .env / MySQL access first (README: 'Database access from Docker'), then run ./deploy.sh again."
  fi
}

wait_until_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) h
  printf '  waiting up to %ss for the container to become healthy ' "$HEALTH_TIMEOUT"
  while (( SECONDS < deadline )); do
    h="$(health_status)"
    case "$h" in
      healthy)   echo; ok "container healthy"; return 0 ;;
      unhealthy) echo; warn "container reported UNHEALTHY"; return 1 ;;
      none)      # an image without a HEALTHCHECK (older builds): fall back to /health
                 if curl -fsS -m 5 "$API/health" >/dev/null 2>&1; then
                   echo; ok "container up (image has no healthcheck; /health answers)"; return 0
                 fi ;;
      missing)   echo; warn "container disappeared"; return 1 ;;
    esac
    printf '.'; sleep 5
  done
  echo; warn "not healthy after ${HEALTH_TIMEOUT}s (last state: $(health_status))"
  return 1
}

verify() {
  local since="$1" body code
  step "Verifying"

  code="$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$API/health" || true)"
  [[ "$code" == "200" ]] || { warn "/health answered $code"; return 1; }
  ok "/health (liveness) 200"

  body="$(curl -s -m 25 "$API/ready" || true)"
  code="$(curl -s -o /dev/null -w '%{http_code}' -m 25 "$API/ready" || true)"
  if [[ "$code" == "200" ]]; then
    ok "/ready (database + monitoring cycle) 200"
  elif [[ "$code" == "404" ]]; then
    warn "/ready not available (older image) - skipping readiness check"
  else
    echo "$body" | sed 's/^/    /'
    warn "/ready answered $code"; return 1
  fi

  if docker logs --since "$since" "$CONTAINER" 2>&1 | grep -q 'HTTP cycle done\|No active HTTP checks'; then
    ok "a monitoring cycle completed"
  else
    warn "no completed monitoring cycle in the logs yet"; return 1
  fi
  if docker logs --since "$since" "$CONTAINER" 2>&1 | grep -q 'Database UNAVAILABLE\|HTTP cycle failed'; then
    docker logs --since "$since" "$CONTAINER" 2>&1 | grep 'ERROR' | tail -5 | sed 's/^/    /'
    warn "errors since start (above)"; return 1
  fi

  if command -v nginx >/dev/null 2>&1; then
    if [[ $EUID -eq 0 ]]; then
      if nginx -t >/dev/null 2>&1; then ok "nginx -t: configuration valid"; else warn "nginx -t reports a problem (run: nginx -t). Not caused by this deploy - nginx was not changed."; fi
    else
      warn "skipping nginx -t (needs root)"
    fi
  fi
  if [[ -n "${PM_PUBLIC_URL:-}" ]]; then
    code="$(curl -s -o /dev/null -w '%{http_code}' -m 10 "${PM_PUBLIC_URL%/}/health" || true)"
    if [[ "$code" == "200" ]]; then ok "public $PM_PUBLIC_URL/health 200 (through nginx)"
    else warn "public $PM_PUBLIC_URL/health answered $code - check nginx / DNS / certificate"; fi
  fi
  return 0
}

save_old_logs() {
  container_exists || return 0
  mkdir -p "$LOG_DIR"
  local f="$LOG_DIR/$(stamp)-before-deploy.log"
  docker logs --tail 2000 "$CONTAINER" >"$f" 2>&1 || true
  ok "previous container's last 2000 log lines saved to $f"
}

switch_to() {   # $1 = image tag
  PM_IMAGE_TAG="$1" compose up -d --no-build --no-deps "$SERVICE"
}

record() { mkdir -p "$STATE_DIR"; echo "$(now)  $*" >>"$HISTORY"; }

# ------------------------------------------------------------------ commands

do_deploy() {
  local pull=1
  [[ "${1:-}" == "--no-pull" ]] && pull=0

  step "Pre-flight (nothing changes yet)"
  check_tools
  check_git
  (( pull )) && pull_code
  check_env

  step "Building"
  build_image

  step "Testing the database with the new image (old container still serving)"
  test_db_with_image "$NEW_TAG"

  step "Keeping the current version for rollback"
  local prev_id prev_tag=""
  prev_id="$(running_image_id)"
  if [[ -n "$prev_id" ]]; then
    prev_tag="rollback-$(stamp)"
    docker tag "$prev_id" "$IMAGE_REPO:$prev_tag"
    echo "$prev_tag" >"$STATE_DIR/previous"
    record "saved running image ${prev_id:7:12} as $IMAGE_REPO:$prev_tag"
    ok "running image saved as $IMAGE_REPO:$prev_tag"
    save_old_logs
  else
    warn "no running $CONTAINER container - first deploy, nothing to roll back to"
  fi

  step "Deploying $IMAGE_REPO:$NEW_TAG (brief API interruption starts)"
  local since; since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  switch_to "$NEW_TAG" || { [[ -n "$prev_tag" ]] && rollback_to "$prev_tag" "compose up failed"; fail "compose up failed"; }

  if wait_until_healthy && verify "$since"; then
    docker tag "$IMAGE_REPO:$NEW_TAG" "$IMAGE_REPO:current"
    record "deployed $IMAGE_REPO:$NEW_TAG ($(git log -1 --format='%s' | cut -c1-60))"
    step "Deployed $NEW_TAG successfully"
    docker logs --since "$since" "$CONTAINER" 2>&1 | tail -15 | sed 's/^/    /'
    echo
    echo "  Roll back any time with:  ./deploy.sh rollback"
    echo "  Old images are kept. List: ./deploy.sh history  (remove by hand when no longer needed)"
  else
    echo "  --- last log lines of the failed container ---"
    docker logs --since "$since" "$CONTAINER" 2>&1 | tail -30 | sed 's/^/    /'
    mkdir -p "$LOG_DIR"; docker logs "$CONTAINER" >"$LOG_DIR/$(stamp)-failed-$NEW_TAG.log" 2>&1 || true
    if [[ -n "$prev_tag" ]]; then
      rollback_to "$prev_tag" "verification of $NEW_TAG failed"
      fail "deploy of $NEW_TAG failed verification and was rolled back to $prev_tag. Logs: $LOG_DIR"
    fi
    fail "deploy of $NEW_TAG failed verification (no previous version to roll back to)."
  fi
}

rollback_to() {   # $1 = tag, $2 = reason
  step "ROLLING BACK to $IMAGE_REPO:$1 ($2)"
  docker image inspect "$IMAGE_REPO:$1" >/dev/null 2>&1 || fail "image $IMAGE_REPO:$1 not found. See ./deploy.sh history"
  local since; since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  switch_to "$1" || fail "compose up for the rollback failed - check 'docker ps -a' and 'docker logs $CONTAINER'."
  if wait_until_healthy; then
    docker tag "$IMAGE_REPO:$1" "$IMAGE_REPO:current"
    record "rolled back to $IMAGE_REPO:$1 ($2)"
    ok "rollback complete: $IMAGE_REPO:$1 is running and healthy"
    docker logs --since "$since" "$CONTAINER" 2>&1 | tail -10 | sed 's/^/    /'
    return 0
  fi
  record "ROLLBACK to $1 NOT HEALTHY ($2)"
  warn "the rolled-back container is running but not healthy - see: docker logs $CONTAINER"
  return 1
}

do_rollback() {
  check_tools
  local tag="${1:-}"
  if [[ -z "$tag" ]]; then
    [[ -f "$STATE_DIR/previous" ]] || fail "no previous version recorded. Pick one from: ./deploy.sh history"
    tag="$(cat "$STATE_DIR/previous")"
  fi
  # Validate BEFORE changing anything, so a mistyped tag leaves all state as it was.
  docker image inspect "$IMAGE_REPO:$tag" >/dev/null 2>&1 \
    || fail "image $IMAGE_REPO:$tag not found - nothing was changed. See ./deploy.sh history"
  if [[ "$(docker image inspect -f '{{.Id}}' "$IMAGE_REPO:$tag")" == "$(running_image_id)" ]]; then
    fail "$IMAGE_REPO:$tag is the image already running - nothing to do. Pick another from ./deploy.sh history"
  fi
  # Keep what is running now, so the rollback itself can be undone.
  local cur_id; cur_id="$(running_image_id)"
  if [[ -n "$cur_id" ]]; then
    local undo="rollback-$(stamp)"
    docker tag "$cur_id" "$IMAGE_REPO:$undo"
    echo "$undo" >"$STATE_DIR/previous"
    ok "current image kept as $IMAGE_REPO:$undo (undo with: ./deploy.sh rollback $undo)"
  fi
  rollback_to "$tag" "requested by hand" || exit 1
}

do_status() {
  step "Container"
  docker ps -a --filter "name=^/${CONTAINER}$" --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
  echo "  health: $(health_status)"
  step "Code"
  echo "  checkout: $(git rev-parse --short HEAD) on $(git rev-parse --abbrev-ref HEAD) - $(git log -1 --format='%s' | cut -c1-70)"
  step "Liveness  GET /health"
  curl -s -m 5 -w '  (HTTP %{http_code})\n' "$API/health" || echo "  no answer"
  step "Readiness GET /ready"
  curl -s -m 25 -w '\n  (HTTP %{http_code})\n' "$API/ready" | sed 's/^/  /' || echo "  no answer"
  step "Last 25 log lines"
  docker logs --tail 25 "$CONTAINER" 2>&1 | sed 's/^/  /'
}

do_history() {
  step "Deploy history"
  [[ -f "$HISTORY" ]] && tail -20 "$HISTORY" | sed 's/^/  /' || echo "  (none yet)"
  step "Images kept"
  docker images "$IMAGE_REPO" --format '  {{.Tag}}\t{{.ID}}\t{{.CreatedSince}}'
  [[ -f "$STATE_DIR/previous" ]] && echo "  default rollback target: $(cat "$STATE_DIR/previous")"
}

mkdir -p "$STATE_DIR"

case "${1:-deploy}" in
  --no-pull)        do_deploy --no-pull ;;
  deploy)           do_deploy "${2:-}" ;;
  rollback)         do_rollback "${2:-}" ;;
  status)           do_status ;;
  history)          do_history ;;
  -h|--help|help)   sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' ;;
  *)                fail "unknown command '$1'. Use: deploy | --no-pull | rollback [TAG] | status | history" ;;
esac
