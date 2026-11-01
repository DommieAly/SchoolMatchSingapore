#!/bin/bash
# Makes the database's CA and server certificate (host name "db"), valid 825 days, once: a later run keeps them.
# Usage: make-db-certs.sh <folder>. write-server-env.sh runs it on the server, make-rehearsal-files.sh on your computer.
# Same file names as deploy/rehearsal/make-certs.sh. The private keys (ca.key, server.key) stay in that folder.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep "/CN=..." as it is instead of turning it into a Windows path
mkdir -p "$1" && cd "$1"
if [[ -f server.crt ]]; then
    echo "Database certificates are already there; keeping them."
    exit 0
fi
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=schoolmatch-db-ca" -keyout ca.key -out ca.crt
openssl req -newkey rsa:2048 -nodes -subj "/CN=db" -keyout server.key -out server.csr
printf "subjectAltName=DNS:db,DNS:localhost\nextendedKeyUsage=serverAuth\n" > ext.cnf
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -extfile ext.cnf -out server.crt
rm -f server.csr ext.cnf ca.srl
chmod 644 ca.crt server.crt
echo "Database certificates made."
