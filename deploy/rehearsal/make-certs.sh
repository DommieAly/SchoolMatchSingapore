#!/bin/bash
# Makes a throwaway CA and a server certificate for the rehearsal database (host name "db"), valid 30 days.
# Output: deploy/rehearsal/certs/ (gitignored). Run once before "docker compose up". Needs bash and openssl
# (macOS/Linux Terminal, WSL, or Git Bash on Windows).
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Git Bash: keep "/CN=..." as it is instead of turning it into a Windows path
mkdir -p certs && cd certs
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -subj "/CN=rehearsal-ca" -keyout ca.key -out ca.crt
openssl req -newkey rsa:2048 -nodes -subj "/CN=db" -keyout server.key -out server.csr
printf "subjectAltName=DNS:db,DNS:localhost\nextendedKeyUsage=serverAuth\n" > ext.cnf
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile ext.cnf -out server.crt
rm -f server.csr ext.cnf ca.srl
echo "certs ready in $(pwd)"
