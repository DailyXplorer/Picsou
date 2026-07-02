#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────
# Picsou container entrypoint — "zero-config" secrets bootstrap.
#
# On first boot, generates the three secrets the app needs before Spring
# can even start (they're read at bean-construction time, so they cannot
# live in the DB):
#   • JWT_SECRET              → /data/.secrets/jwt_secret
#   • CRYPTO_ENCRYPTION_KEY   → /data/.secrets/crypto_key
#   • POSTGRES_PASSWORD       → /data/.secrets/postgres_password
#
# On subsequent boots, the files already exist on the /data volume, so we
# just re-export. A secret is **never** regenerated once created — doing
# so would invalidate every previously-issued JWT (users logged out) and
# every encrypted crypto API secret in the DB (permanent data loss).
#
# If the env var is already set (user-supplied via .env or an external
# secret manager), we respect it and skip generation entirely. That means
# existing installs upgrading to this image see zero behavior change.
# ─────────────────────────────────────────────────────────────────────────
set -euo pipefail

SECRETS_DIR="/data/.secrets"
mkdir -p "$SECRETS_DIR"
chmod 0700 "$SECRETS_DIR" 2>/dev/null || true

# $1 = file basename, $2 = env var name, $3 = openssl args (e.g. "rand -base64 48")
bootstrap_secret() {
    local file="$SECRETS_DIR/$1"
    local var_name="$2"
    local gen_args="$3"

    # 1. If the env var is already set by the operator, honor it and write
    #    it to the file for consistency across restarts.
    if [ -n "${!var_name:-}" ]; then
        if [ ! -f "$file" ]; then
            printf '%s' "${!var_name}" > "$file"
            chmod 0600 "$file" 2>/dev/null || true
        fi
        return 0
    fi

    # 2. Env var unset — use the file if present, else generate.
    if [ ! -f "$file" ]; then
        # shellcheck disable=SC2086
        openssl $gen_args > "$file"
        chmod 0600 "$file" 2>/dev/null || true
        echo "[entrypoint] generated $var_name at $file"
    fi

    export "$var_name=$(cat "$file")"
}

bootstrap_secret "jwt_secret"        "JWT_SECRET"            "rand -base64 48"
bootstrap_secret "crypto_key"        "CRYPTO_ENCRYPTION_KEY" "rand -base64 32"
bootstrap_secret "postgres_password" "POSTGRES_PASSWORD"     "rand -base64 24"

# Defaults that never changed — keep backward compatibility with the old
# single-line entrypoint.
export SERVER_PORT=9090
export TR_AUTH_URL=${TR_AUTH_URL:-http://127.0.0.1:8001}
export REVOLUT_AUTH_URL=${REVOLUT_AUTH_URL:-http://127.0.0.1:8002}

exec /usr/bin/supervisord -c /etc/supervisor/supervisord.conf
