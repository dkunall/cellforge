#!/usr/bin/env bash
# Starts the compose stack with secrets generated on first use and kept outside the repo (mode 600).
# Reruns reuse them, so the databases in existing volumes still accept the passwords.
set -euo pipefail
cd "$(dirname "$0")/.."
ENV_FILE=${CELLFORGE_ENV_FILE:-$HOME/.config/cellforge/compose.env}
if [[ ! -f $ENV_FILE ]]; then
  mkdir -p "$(dirname "$ENV_FILE")"
  umask 077
  {
    for name in EDGE_DB_PASSWORD CELL_DB_OWNER_PASSWORD CELL_DB_APP_PASSWORD CELLFORGE_CLIENT_SECRET CELLFORGE_WEBHOOK_SECRET; do
      echo "$name=$(openssl rand -hex 24)"
    done
  } > "$ENV_FILE"
  echo "wrote new secrets to $ENV_FILE"
fi
exec docker compose --env-file "$ENV_FILE" up -d --wait --wait-timeout "${WAIT:-300}" "$@"
