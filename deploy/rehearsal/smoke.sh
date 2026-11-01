#!/bin/bash
# Smoke test of a running deployment (docs/deployment.md, steps 1 and 6): register, log in, open a members-only page,
# log out, and check that a form posted from another site is refused. It leaves one smoke_… test account behind.
# Usage: ./smoke.sh [base URL]   default https://localhost:8443 (the rehearsal). Exit code 0 = every check passed.
B=${1:-https://localhost:8443}; B=${B%/}
K=(); [[ $B == https://localhost* ]] && K=(-k)          # the rehearsal's certificate is self-made
J=$(mktemp); U=smoke_$RANDOM$RANDOM; P="Smoke$(openssl rand -hex 8)1"; FAIL=0
trap 'rm -f "$J"' EXIT

for i in $(seq 1 30); do          # wait up to 60 s for the app
  [[ $(curl -s "${K[@]}" -o /dev/null -w '%{http_code}' "$B/actuator/health/readiness") == 200 ]] && break
  [[ $i == 30 ]] && { echo "FAIL: $B/actuator/health/readiness is not answering 200"; exit 1; }
  sleep 2
done

req() { curl -s "${K[@]}" -o /dev/null -D - -c "$J" -b "$J" "$@" | tr -d '\r'; }
check() {  # check <name> <headers> <expected status> [expected location]
  local status loc; status=$(awk 'NR==1{print $2}' <<<"$2"); loc=$(awk 'tolower($1)=="location:"{print $2}' <<<"$2")
  local ok=1; [[ $status == "$3" ]] || ok=0; [[ -z ${4:-} || $loc == "$4" ]] || ok=0
  grep -i '^set-cookie:' <<<"$2" | grep -v -q 'Secure' && ok=0                 # every cookie must be Secure
  if ((ok)); then echo "ok    $1 ($status${loc:+ → $loc})"; else echo "FAIL  $1: got $status${loc:+ → $loc}, expected $3${4:+ → $4}"; FAIL=1; fi
}
check "home page"                 "$(req "$B/")" 200
check "members page as guest"     "$(req "$B/profile")" 302 "/login?next=/profile"
registered=$(req -H "Origin: $B" --data-urlencode "username=$U" --data-urlencode "email=$U@example.com" --data-urlencode "password=$P" --data-urlencode "confirm=$P" "$B/register")
check "register"                  "$registered" 302 "/login"
check "log in"                    "$(req -H "Origin: $B" --data-urlencode "identifier=$U" --data-urlencode "password=$P" "$B/login")" 302 "/"
check "members page as member"    "$(req "$B/profile")" 200
check "log out"                   "$(req -H "Origin: $B" -X POST "$B/logout")" 302 "/"
check "form from another site"    "$(curl -s "${K[@]}" -o /dev/null -D - -H "Origin: https://evil.example" --data-urlencode "identifier=x" --data-urlencode "password=y" "$B/login" | tr -d '\r')" 403
[[ $(awk 'NR==1{print $2}' <<<"$registered") == 302 ]] && echo "Test account left behind: $U@example.com (docs/deployment.md, \"Deleting one account\")"
((FAIL)) && echo "Some checks failed (docs/deployment.md, Troubleshooting)." || echo "All checks passed."
exit $FAIL
