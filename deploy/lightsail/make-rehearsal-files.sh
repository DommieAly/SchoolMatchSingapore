#!/bin/bash
# Makes the throwaway files of the local rehearsal in .rehearsal/ (gitignored), which stands in for the server's
# /opt/schoolmatch: certs/ and an .env with a random password, the local image name and the address localhost.
# Used with rehearsal.override.yaml (docs/deployment.md, step 1).
set -euo pipefail
cd "$(dirname "$0")"
./make-db-certs.sh .rehearsal/certs
if [[ ! -f .rehearsal/.env ]]; then
    (umask 077; printf 'DB_PASSWORD=%s\nIMAGE=schoolmatch:lightsail-rehearsal\nSITE_ADDRESS=localhost\n' "$(openssl rand -hex 24)" > .rehearsal/.env)
fi
echo "Rehearsal files ready in $(pwd)/.rehearsal"
