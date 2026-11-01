#!/bin/bash
# Puts the current commit online (docs/deployment.md, step 5, and "Updating the site"): builds the image here, sends it
# to the server over SSH (no container registry, which would cost money), and starts it. Accounts and the HTTPS
# certificate stay in their volumes. Prints no secrets.
# Usage: ./deploy.sh <server ip>
# The address is <ip-with-dashes>.sslip.io. For another one, once: SITE_ADDRESS=<address> ./deploy.sh <server ip>
# (later runs keep it).
set -euo pipefail
cd "$(dirname "$0")"
[[ $# == 1 ]] || { echo "Usage: $0 <server ip>"; exit 2; }
SSH=(ssh -i "$HOME/.ssh/schoolmatch-lightsail" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -o ServerAliveInterval=30 "ubuntu@$1")

[[ -z $(git status --porcelain) ]] || { echo "FAIL: the git tree has uncommitted changes. Only committed code goes online."; exit 1; }
rc=0; "${SSH[@]}" 'test -f /opt/schoolmatch/.env' || rc=$?
[[ $rc != 255 ]] || { echo "FAIL: cannot reach the server over SSH. Has this computer's IP changed? Run provision.sh again (docs/deployment.md, Troubleshooting)."; exit 1; }
[[ $rc == 0 ]] || { echo "FAIL: no /opt/schoolmatch/.env on the server. Run write-server-env.sh first."; exit 1; }

IMAGE=schoolmatch:$(git rev-parse --short HEAD)
SITE_ADDRESS=${SITE_ADDRESS:-$("${SSH[@]}" "sed -n 's/^SITE_ADDRESS=//p' /opt/schoolmatch/.env")}
SITE_ADDRESS=${SITE_ADDRESS:-${1//./-}.sslip.io}
[[ $SITE_ADDRESS =~ ^[A-Za-z0-9.-]+$ ]] || { echo "FAIL: SITE_ADDRESS must be a host name only, with no https:// and no /"; exit 1; }
# A commit without the password block takes the password off (docs/deployment.md, "Putting a password on the site")
if ! grep -Eq '^[[:space:]]*basic_auth' Caddyfile && "${SSH[@]}" "grep -q '^SITE_PASSWORD_HASH=' /opt/schoolmatch/.env"; then
    echo "WARNING: the server's .env has a site password, but this commit's Caddyfile does not use it, so the site will be open to everyone."
fi

# --load: a build with a docker-container builder otherwise leaves no image for docker save
docker build --platform linux/amd64 --provenance=false --load -t "$IMAGE" "$(git rev-parse --show-toplevel)"
[[ $(docker image inspect "$IMAGE" --format '{{.Architecture}}') == amd64 ]] || { echo "FAIL: $IMAGE is not an x86 image"; exit 1; }
echo "Sending $IMAGE to the server ..."
docker save "$IMAGE" | gzip | "${SSH[@]}" 'gunzip | docker load'

# Written in place, not replaced: the running proxy has this very file mounted
"${SSH[@]}" 'cat > /opt/schoolmatch/compose.yaml' < compose.yaml
"${SSH[@]}" 'cat > /opt/schoolmatch/Caddyfile' < Caddyfile
echo "Starting $IMAGE at $SITE_ADDRESS (up to 5 minutes) ..."
"${SSH[@]}" "set -euo pipefail
cd /opt/schoolmatch
umask 077
{ grep -Ev '^(IMAGE|SITE_ADDRESS)=' .env || true; printf 'IMAGE=%s\nSITE_ADDRESS=%s\n' '$IMAGE' '$SITE_ADDRESS'; } > .env.new
mv .env.new .env
docker compose up -d --wait --wait-timeout 300
docker compose exec -T proxy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile" \
    || { echo "FAIL: the start did not finish. If the line above is a Caddy error, fix the Caddyfile, commit and deploy again (the old settings stay in use until the proxy restarts). Otherwise, on the server, in /opt/schoolmatch: docker compose ps, docker compose logs app (docs/deployment.md, Troubleshooting)."; exit 1; }

for i in $(seq 1 36); do          # wait 3 to 6 minutes for the HTTPS certificate (-m 5: a closed port must not hang)
    [[ $(curl -s -m 5 -o /dev/null -w '%{http_code}' "https://$SITE_ADDRESS/actuator/health/readiness") == 200 ]] && break
    [[ $i == 36 ]] && { echo "FAIL: the containers are healthy, but https://$SITE_ADDRESS does not answer. On the server: docker compose logs proxy (docs/deployment.md, Troubleshooting)."; exit 1; }
    sleep 5
done
echo
echo "Online: https://$SITE_ADDRESS ($IMAGE)"
