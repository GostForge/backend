#!/bin/sh
set -eu

UPLOADS_DIR="${UPLOADS_DIR:-/app/uploads}"
APP_USER="${APP_USER:-app}"
APP_GROUP="${APP_GROUP:-app}"

log() {
  printf '[entrypoint] %s\n' "$*"
}

ensure_uploads_dir() {
  if ! mkdir -p "$UPLOADS_DIR" 2>/dev/null; then
    log "ERROR: cannot create uploads dir: $UPLOADS_DIR"
    exit 1
  fi

  # When running as root, proactively repair ownership and permissions for mounted volumes.
  if [ "$(id -u)" -eq 0 ]; then
    if ! su-exec "$APP_USER:$APP_GROUP" test -w "$UPLOADS_DIR" 2>/dev/null; then
      log "Repairing permissions for $UPLOADS_DIR"
      chown -R "$APP_USER:$APP_GROUP" "$UPLOADS_DIR" || true
      chmod -R u+rwX,g+rwX "$UPLOADS_DIR" || true
    fi

    if ! su-exec "$APP_USER:$APP_GROUP" test -w "$UPLOADS_DIR" 2>/dev/null; then
      log "ERROR: uploads dir is not writable for $APP_USER:$APP_GROUP ($UPLOADS_DIR)"
      exit 1
    fi
    return
  fi

  if [ ! -w "$UPLOADS_DIR" ]; then
    log "ERROR: uploads dir is not writable for current user ($UPLOADS_DIR)"
    exit 1
  fi
}

ensure_uploads_dir

if [ "$(id -u)" -eq 0 ]; then
  exec su-exec "$APP_USER:$APP_GROUP" "$@"
fi

exec "$@"
