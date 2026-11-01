#!/bin/bash
# Writes the server's secrets (docs/deployment.md, step 4): /opt/schoolmatch/.env with mode 600, and the database
# certificates. Safe to run again, for example after changing a Google key: the database password and the certificates
# are made once on the server and kept, and nothing secret is printed. Run deploy.sh afterwards to use new values.
# Usage: ./write-server-env.sh <server ip>
set -euo pipefail
cd "$(dirname "$0")"
[[ $# == 1 ]] || { echo "Usage: $0 <server ip>"; exit 2; }
SSH=(ssh -i "$HOME/.ssh/schoolmatch-lightsail" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o ServerAliveInterval=30 "ubuntu@$1")

# The .env of the main checkout, also from a linked git worktree. Read as text, never run: it is not a shell script.
SOURCE_ENV=${SOURCE_ENV:-$(git worktree list --porcelain | sed -n '1s/^worktree //p')/.env}
[[ -f $SOURCE_ENV ]] || { echo "FAIL: $SOURCE_ENV not found"; exit 1; }
value() { sed -n "s/^$1=//p" "$SOURCE_ENV" | tail -n 1 | tr -d '\r'; }
GOOGLE_MAPS_SERVER_KEY=$(value GOOGLE_MAPS_SERVER_KEY)
GOOGLE_MAPS_MAP_ID=$(value GOOGLE_MAPS_MAP_ID)
GOOGLE_MAPS_BROWSER_KEY_SITE=$(value GOOGLE_MAPS_BROWSER_KEY_SITE)
[[ -n $GOOGLE_MAPS_SERVER_KEY ]] || { echo "FAIL: GOOGLE_MAPS_SERVER_KEY is empty in $SOURCE_ENV"; exit 1; }
# Compose reads the server's .env too, and treats quote marks and $ in it as special. Google's values have neither.
for name in GOOGLE_MAPS_SERVER_KEY GOOGLE_MAPS_MAP_ID GOOGLE_MAPS_BROWSER_KEY_SITE; do
    [[ ${!name} =~ ^[A-Za-z0-9_-]*$ ]] || { echo "FAIL: $name in $SOURCE_ENV has a character other than letters, digits, - and _"; exit 1; }
done

echo "Waiting for the server's first boot to finish (a few minutes) ..."
for i in $(seq 1 60); do          # up to 10 minutes for server-setup.sh, or 20 if SSH gets no answer at all
    ssh_says=$("${SSH[@]}" 'test -f /opt/schoolmatch/.setup-done' 2>&1) && break
    [[ $i == 60 ]] && { echo "FAIL: no finished server at $1.${ssh_says:+ SSH said: $ssh_says} Has this computer's IP changed (run provision.sh again)? Otherwise read /var/log/cloud-init-output.log on the server."; exit 1; }
    sleep 10
done

"${SSH[@]}" 'bash -s /opt/schoolmatch/certs' < make-db-certs.sh

# Runs on the server. The secrets arrive on its stdin, never as command-line arguments. It replaces only the lines
# below, so DB_PASSWORD, IMAGE, SITE_ADDRESS and the site password stay as they are.
REMOTE=$(cat <<'SCRIPT'
set -euo pipefail
umask 077
cd /opt/schoolmatch
touch .env
chmod 600 .env
grep -q '^DB_PASSWORD=' .env || printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> .env
incoming=$(cat)
{ grep -Ev '^(GOOGLE_MAPS_SERVER_KEY|GOOGLE_MAPS_MAP_ID|GOOGLE_MAPS_BROWSER_KEY|GOOGLE_MODE|ONEMAP_MODE)=' .env || true
  printf '%s\n' "$incoming"; } > .env.new
mv .env.new .env
SCRIPT
)
{
    printf 'GOOGLE_MAPS_SERVER_KEY=%s\n' "$GOOGLE_MAPS_SERVER_KEY"
    printf 'GOOGLE_MAPS_MAP_ID=%s\n' "$GOOGLE_MAPS_MAP_ID"
    printf 'GOOGLE_MAPS_BROWSER_KEY=%s\n' "$GOOGLE_MAPS_BROWSER_KEY_SITE"
    printf 'GOOGLE_MODE=live\nONEMAP_MODE=live\n'
} | "${SSH[@]}" "$REMOTE"

echo "Server secrets written: /opt/schoolmatch/.env, mode 600."
[[ -n $GOOGLE_MAPS_BROWSER_KEY_SITE ]] || echo "Note: GOOGLE_MAPS_BROWSER_KEY_SITE is not set, so map pages will show \"Map unavailable\" (docs/deployment.md, step 3)."
