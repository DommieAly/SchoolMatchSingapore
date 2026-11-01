# Deploying SchoolMatch SG on Amazon Lightsail

This guide puts the app online at an `https://` address on **one Amazon Lightsail server** in the Singapore Region `ap-southeast-1`: the app, PostgreSQL 17 and a Caddy HTTPS proxy, all in Docker Compose. One teammate with the AWS account runs the scripts in [`deploy/lightsail/`](../deploy/lightsail/) from their own computer. It takes about 30 minutes, most of it waiting.

AWS facts in this guide were checked against AWS documentation and the AWS CLI help on 1 Nov 2026. The scripts are written for AWS CLI 2.27.42 or later.

## What gets built

```
Browser ──HTTPS──▶ proxy: Caddy (gets and renews the certificate by itself)
                     │ plain HTTP inside Docker, port 8080
                     ▼
                   app: the image built on your computer, profile "prod" ──HTTPS out──▶ Google Routes / Places, OneMap
                     │ PostgreSQL over TLS (sslmode=verify-full)
                     ▼
                   db: PostgreSQL 17, no published port, data in a named volume
```

- **The server:** Ubuntu 24.04, x86, 2 GB memory, 2 vCPUs, 60 GB disk, with a static IP and a 1 GB swap file. The firewall opens ports 80 and 443 to everyone and port 22 only to your computer's public IP.
- **In this guide,** `<ip>` means the server's static IP, and `<address>` means the host name of the site, `<ip-with-dashes>.sslip.io` (for `203.0.113.7`, `203-0-113-7.sslip.io`). sslip.io is a free name service that answers with the IP written in the name, so no domain has to be bought.
- The browser loads Google Maps JavaScript itself, with the browser key that the page carries.
- Exactly **one** app container runs. Page state (flash messages, the chosen starting point, the last recommendations) lives in its memory, so a restart loses that, but logins and accounts are in the database and stay.
- The image contains the app and the school snapshot (`data/snapshots`). It contains no keys or passwords: those are only in `/opt/schoolmatch/.env` on the server, with mode 600.
- There are no backups. The site holds made-up data only, and all of it goes when the server is deleted.

| Container | Memory limit | Highest use in the rehearsal |
|:--|:--:|:--:|
| `app` (the Java heap gets 70% of the limit) | 1 GB | about 520 MB |
| `db` | 384 MB | about 120 MB, after loading the schools for the first time |
| `proxy` | 128 MB | about 30 MB |

The limits add up to about 1.5 GB, which leaves about 500 MB for Ubuntu and Docker.

## Cost

**USD 12 a month before tax (about USD 13.10 with 9% GST if the account's address is in Singapore), and it is real money on this account: there are no credits and no free tier.** The plan is charged by the hour up to that amount. A stopped server is still charged: only deleting it ends the bill ([Tear down](#tear-down)). The static IP is free while it is attached. The image goes straight to the server over SSH, so there is no container registry to pay for. The plan includes 3 TB of data transfer a month, far more than this site will use.

Set a reminder: Billing and Cost Management → **Budgets** → Create budget → *Monthly cost budget*, amount **USD 20**, alert emails to two teammates. AWS does not stop anything when the budget is reached. If Budgets says access denied, sign in once as the root user and turn on Account → IAM user and role access to Billing information.

## How long it stays online

**The removal date is not decided.** It depends on when the TA's marking is finished, which we do not know yet. The site itself says only that all data is deleted "when the course project ends"; do not write a date on it.

**If the site is still online when the PSLE results come out, put a password on it first** ([how](#putting-a-password-on-the-site)). MOE expects the results on 24-25 Nov 2026 (tentative). That is when real parents may start looking for a site like this one and could type in a real child's details.

## Before you start

- [ ] **Made-up data only.** The site stores a child's PSLE score and home address. Tell testers to use test accounts, made-up scores and a public address such as a school's postal code. The Terms and privacy page and the register and profile pages say the same.
- [ ] **Tell the TA before you share the address** (README, "Current state"): a public site republishes the MOE SchoolFinder PSLE ranges and affiliations.
- [ ] **The OneMap Terms of Use question** ([`data/README.md`](../data/README.md), "Licences and credits") is settled or accepted by the team.
- [ ] **Team approval.** The team has approved DC-92 (this server) and DC-94 (the Google caching difference in [Google allowance](#google-allowance)). Going online puts both into use.
- [ ] **Expect visitors you did not invite.** New HTTPS certificates are published in public logs that scanners read, so the address will not stay within the class.
- [ ] **Account safety.** MFA is on for the root user, and you work as an admin user. Never create access keys for the root user.
- [ ] **Tools on your computer:** Docker Desktop (running), the AWS CLI signed in to the right account (`aws sts get-caller-identity` prints its number), and a **bash** shell (macOS or Linux Terminal; on Windows, Git Bash or WSL).
- [ ] **Keys:** `GOOGLE_MAPS_SERVER_KEY` and `GOOGLE_MAPS_MAP_ID` are in the `.env` of your main checkout of this repository. The key owner is E (backup A).

## 1. Rehearse on your computer

This runs the **real production files** with [`rehearsal.override.yaml`](../deploy/lightsail/rehearsal.override.yaml), which changes only what must differ on a laptop: stub modes, a certificate from Caddy's own internal authority for `localhost`, the address `127.0.0.1:8443`, a local build of the image, and throwaway `.env` and certificates in `deploy/lightsail/.rehearsal/` (gitignored).

```bash
cd deploy/lightsail
./make-rehearsal-files.sh
docker compose --project-directory .rehearsal -f compose.yaml -f rehearsal.override.yaml up -d --build --wait
../rehearsal/smoke.sh
```

`smoke.sh` registers a throwaway account, logs in, opens the profile, logs out, and posts a form "from another site". It prints one line per check and ends with **"All checks passed."** You can also open https://localhost:8443 and accept the browser's warning about the rehearsal certificate.

To check that accounts survive, register an account in the browser, then run the same `docker compose` command with `restart`, or with `down` and then `up -d --wait` (no `-v`), and log in again.

Finish with the same command and `down -v`, which removes the throwaway data. Then go back to the repository root.

## 2. Create the server

```bash
deploy/lightsail/provision.sh
```

From here on it costs money. The script makes an SSH key at `~/.ssh/schoolmatch-lightsail` if there is none, imports it, creates the server (`ubuntu_24_04`, `small_3_0`, name `schoolmatch`), attaches a static IP, and sets the firewall. It prints the server's IP and the site address. It is safe to run twice. If Lightsail already has the key but this computer does not, it stops and asks you to copy the key from the first computer.

On its first boot the server runs [`server-setup.sh`](../deploy/lightsail/server-setup.sh) by itself: Docker Engine and the compose plugin from Docker's own apt repository, the 1 GB swap file, automatic security updates, and the folder `/opt/schoolmatch`. This takes a few minutes; the next scripts wait for it. To check by hand: `ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<ip> cloud-init status --wait` prints `status: done` when first boot has ended, or `status: error` if it failed.

## 3. Set the Google keys

In Google Cloud → APIs & Services → **Credentials** (see [`google-maps-setup.md`](google-maps-setup.md)):

- **Browser key:** create a **new** key for this site, with Website restriction `https://<address>/*` only and API restriction *Maps JavaScript API* only, so the laptop key keeps its `localhost` entries. Add it to the `.env` of your main checkout as `GOOGLE_MAPS_BROWSER_KEY_SITE=<the key>`. The restriction takes a few minutes to apply. Without this key the site works, but map pages show "Map unavailable".
- **Server key:** keep its API restriction (Routes API and Places API (New)) and the daily quotas from `google-maps-setup.md`. Because the static IP does not change, you can also limit it: Application restrictions → **IP addresses** → `<ip>`. The key then stops working on every laptop, so first give any laptop that still needs live mode a second server key of its own.

## 4. Send the secrets to the server

```bash
deploy/lightsail/write-server-env.sh <ip>
```

It reads the keys from your main checkout's `.env` without printing them and sends them over SSH to `/opt/schoolmatch/.env`, mode 600, with `GOOGLE_MODE=live` and `ONEMAP_MODE=live`. On the server it makes a random database password and the database's own certificates, valid 825 days. It makes both only once and keeps them on later runs, and the private keys never leave the server.

## 5. Build, send and start

From a clean git tree on `deploy/prep` (or `main` once it is merged), on a commit whose CI is green:

```bash
deploy/lightsail/deploy.sh <ip>
```

It refuses a tree with uncommitted changes. It builds the image for x86 as `schoolmatch:<short commit>`, sends it over SSH with `docker save | gzip | ssh … docker load`, copies `compose.yaml` and `Caddyfile`, and starts everything. It then waits for the health checks and for the HTTPS certificate, and ends with the site's address. The first run takes several minutes.

To look at the server afterwards:

```bash
ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<ip>
cd /opt/schoolmatch
docker compose ps
docker compose logs --tail 50 app       # or db, proxy
```

## 6. Check list

Work through it after the first deployment. Steps 5 to 7 use real Google calls, so keep them few.

1. `https://<address>/actuator/health` shows `{"groups":["liveness","readiness"],"status":"UP"}`.
2. `docker compose ps` on the server shows all three containers **healthy**. `docker compose logs app` contains `The following 1 profile is active: "prod"` and `Started SchoolMatchApplication`. The first start also logs `School dataset 2026-10-04.1 loaded (147 schools …)`; later starts only log `School snapshot 2026-10-04.1 …`.
3. From the repository root: `deploy/rehearsal/smoke.sh https://<address>`. It must end with "All checks passed." It leaves one `smoke_…` test account behind, which you can [delete](#deleting-one-account).
4. The site is closed where it should be: `curl -sI https://<address>/ | grep -i -E 'strict-transport|nosniff|x-frame'` prints three lines, and `nc -vz -w 5 <ip> 5432` does not connect.
5. In a browser: search, open a school, open the map (a real Google map, not "Map unavailable"), register with made-up details, set a home address in the profile (a real OneMap lookup), get directions, log out. No page shows the "Demo data (stub)" badge, and the browser console shows no `RefererNotAllowedMapError`.
6. **One travel-time filter against real Google:** on the search page set a starting point and one short filter, for example walking, 15 minutes. The list shows travel times with the line "Travel times: Google Maps". Do not use the island-wide public-transport filter here (see [Google allowance](#google-allowance)).
7. **One recommendation against real Google:** log in and run one recommendation with a home address and a commute limit. The results show commute times with the line "Commute times: Google Maps".
8. **Each visitor has their own count.** This uses no Google call. On your computer, run `for i in $(seq 31); do curl -s -o /dev/null -w '%{http_code} ' 'https://<address>/directions?to=school:catholic-high-school&mode=x'; done`. The last code must be `429`; if it is not, the loop ran into a new minute, so run it again. Within the same clock minute, open the same URL on a phone on mobile data. It must open. If the phone also gets 429, the app is seeing one address for everyone.

Only now share the address, after telling the TA.

## Updating the site

Commit, wait for CI, then run `deploy/lightsail/deploy.sh <ip>` again. Accounts, the database password and the HTTPS certificate stay, and only the app container is replaced. The app is away for about 30 seconds, and anyone in the middle of a flow loses their page state, so do not update during a demo or marking slot.

After changing a key in your `.env`, run `write-server-env.sh <ip>` and then `deploy.sh <ip>`.

Before a demo with the whole class on one Wi-Fi, add `APP_VISITORLIMIT_ENABLED=false` to `/opt/schoolmatch/.env` and run `docker compose up -d app`, then remove the line and run the same command afterwards. Otherwise 30 people reach the address-search limit (60 an hour, and every Save profile counts) and the Google-pages limit (120 an hour) within one walk-through.

To go back to an earlier version: `git checkout <old commit>`, run `deploy/lightsail/deploy.sh <ip>`, then `git checkout -`. First check that the newer commit added no file in `src/main/resources/db/migration`: migrations are not undone, so the older image may not start against the newer tables. Two things to expect: that commit's `Caddyfile` goes online too, so if the password went on after it, the password comes off; and the school data stays at the newer snapshot ([`data/README.md`](../data/README.md), "Rollback").

## Google allowance

- The app's own daily budget (`app.external.budget`) applies as on a laptop. Its counters are in the database, so a restart does not reset them. **The budget day ends at 16:00 Singapore time** (midnight Pacific Time).
- Every visitor shares it, including guests and crawlers. `robots.txt` asks search engines not to crawl, but only polite ones obey.
- The limits per visitor do not protect the allowance: 26 facility pages or 2 island-wide travel-time filters from one address use up that kind of call until 16:00 Singapore time. If this happens on a marking day, [put the password on the site](#putting-a-password-on-the-site).
- When maps or nearby facilities say "temporarily unavailable", the day's allowance is used up.
- One island-wide public-transport travel-time filter uses about half a day's route-matrix allowance. Avoid repeating it on a demo or marking day.
- Known difference from Google's terms: the app caches Places content for 24 hours and Routes content for 30 minutes to stay inside the free amount. The same caches keep the rounded starting point for 30 minutes and a typed address for 24 hours. Section 4.4(c)(iii)(B) allows caching a visitor's location only with consent the visitor can withdraw, and a visitor cannot clear these copies. This is proposed for this course prototype only and needs the team's approval (DC-94).

## Putting a password on the site

The [`Caddyfile`](../deploy/lightsail/Caddyfile) has a marked, commented-out block that puts one username and password on every page and leaves `/actuator/health/readiness` open.

1. Make a hash of the password. This asks for the password twice and prints the hash:
   ```bash
   docker run --rm -it caddy:2-alpine caddy hash-password
   ```
2. On the server, add two lines to `/opt/schoolmatch/.env`. Keep the single quotes: the hash contains `$`, and without them Compose would change it.
   ```
   SITE_USERNAME=schoolmatch
   SITE_PASSWORD_HASH='<the hash>'
   ```
3. In `deploy/lightsail/Caddyfile`, remove the `# ` from the four lines of the block. Commit on a branch, merge it to `main` the same day (README: every change goes through a PR), and run `deploy/lightsail/deploy.sh <ip>` from that commit. Until the merge, deploy only from that branch: a deploy from any commit without the block takes the password off.
4. Check: `curl -s -o /dev/null -w '%{http_code}\n' https://<address>/` prints `401`, and the same for `https://<address>/actuator/health/readiness` prints `200`. In a browser, the password opens the site, and the maps still work.

Give the password to the TA and the team directly, not in a public place. `smoke.sh` stops working while the password is on, because it gets `401`. To take the password off, put the `# ` back, remove the two `SITE_` lines from `.env`, commit, and deploy again.

Never add a `Referrer-Policy` header that strips the referrer: the Google browser key only works on pages whose referrer matches its website restriction.

## Deleting one account

The Terms and privacy page promises this to anyone who writes to the contact address. From the repository root:

```bash
ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<ip> 'cd /opt/schoolmatch && docker compose exec -T db psql -U schoolmatch -v ON_ERROR_STOP=1 -v email=someone@example.com' < deploy/lightsail/delete-account.sql
```

The output is `BEGIN`, `DELETE 0` or `DELETE 1` (the choice plan), `DELETE 1` (the account) and `COMMIT`. If the account line says `DELETE 0`, no account has that email. Everything else that belongs to the account goes with it through its foreign keys. [`delete-account.sql`](../deploy/lightsail/delete-account.sql) deletes the choice plan first, because it is the one table with no link to the account.

## Clearing the score from one choice plan

The Terms and privacy page also promises to remove the PSLE score and posting group kept on a person's choice plan. First ask the person to clear the score on their profile page: while the profile has a score, the site copies it back the next time they open the plan (`ChoicePlanController.getPlan`). Then, from the repository root:

```bash
ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<ip> 'cd /opt/schoolmatch && docker compose exec -T db psql -U schoolmatch -v ON_ERROR_STOP=1 -v email=someone@example.com' < deploy/lightsail/clear-plan-score.sql
```

The output is `BEGIN`, `UPDATE 1` and `COMMIT`. `UPDATE 1` means it worked; `UPDATE 0` means that email has no plan. The plan's schools stay. [`clear-plan-score.sql`](../deploy/lightsail/clear-plan-score.sql) changes nothing else.

## Tear down

When the team decides the site can go:

```bash
deploy/lightsail/teardown.sh
```

It prints the AWS account number first, so check that it is the right one. After you type the confirmation, it deletes the server, its static IP and the imported SSH key. Every account and all data on the server go with it, and that keeps the promise on the Terms and privacy page. Then, by hand:

1. Google Cloud → delete the site's browser key.
2. If only the site used the server key, delete that too; otherwise remove its IP restriction.
3. Remove `GOOGLE_MAPS_BROWSER_KEY_SITE` from your `.env`, and `rm ~/.ssh/schoolmatch-lightsail ~/.ssh/schoolmatch-lightsail.pub`.
4. Lightsail console, Region Singapore: no instance and no static IP is left. A static IP left without a server costs about USD 3.60 a month.

## Troubleshooting

To read a log: on the server, in `/opt/schoolmatch`, `docker compose logs --tail 100 app` (or `db`, `proxy`).

Do not paste the full output of `docker inspect` or `docker compose config`: they contain the secrets.

| What you see | Why, and what to do |
|:--|:--|
| `provision.sh` or `teardown.sh` stops with a credentials error | The AWS CLI is not signed in. `aws sts get-caller-identity` must print your account. |
| SSH or a script times out: `Connection timed out`, `cannot reach the server over SSH`, `no finished server` | Port 22 is open only to the IP your computer had when you ran `provision.sh`, and that changes between home, NTU Wi-Fi and a phone hotspot. Run `provision.sh` again: it sets the firewall to your current IP. On a brand-new server, also give the first boot a few minutes. |
| SSH or a script says `Permission denied (publickey)` | This computer does not have the key that `provision.sh` first made. Copy `~/.ssh/schoolmatch-lightsail` and its `.pub` file from that computer, run `chmod 600 ~/.ssh/schoolmatch-lightsail`, then run `provision.sh` here so that port 22 opens to this computer's IP. |
| `write-server-env.sh` keeps waiting, then says `no finished server`, though SSH works | The first boot failed. Read `/var/log/cloud-init-output.log` on the server. Then run it again, which is safe to repeat: `ssh -i ~/.ssh/schoolmatch-lightsail ubuntu@<ip> 'sudo sh -s' < deploy/lightsail/server-setup.sh`. |
| `REMOTE HOST IDENTIFICATION HAS CHANGED` | A new server has the IP of an old one. `ssh-keygen -R <ip>`, then run the script again. |
| The log shows `exec format error` | The image was built for arm64. `deploy.sh` builds for x86 and checks it, so run it rather than building by hand. |
| `deploy.sh` says the containers are healthy but the address does not answer; `docker compose logs proxy` says `too many certificates` or `rateLimited` | **The shared certificate quota of sslip.io has run out.** Every user of sslip.io shares one Let's Encrypt quota, and it ran out several times in 2026. Caddy also tries ZeroSSL by itself, so wait a few minutes first. If it still fails, it keeps trying, but that can take days. Switch to nip.io, which works the same way: `SITE_ADDRESS=<ip-with-dashes>.nip.io deploy/lightsail/deploy.sh <ip>`. Later deploys keep that address. Add `https://<new address>/*` to the browser key. If nip.io has run out too, buy a cheap domain, point an A record at `<ip>`, and use `SITE_ADDRESS=<your domain>`. |
| `docker compose logs proxy` says the certificate request timed out or could not connect | Ports 80 and 443 must be open to everyone: run `provision.sh` again. |
| Log: `Connection to db:5432 refused` | The database is not up. See `docker compose ps` and `docker compose logs db`. |
| Log: `PKIX path building failed` or another SSL error | `sslrootcert` in `DB_URL` must be `/certs/ca.crt`, and `/opt/schoolmatch/certs/` must hold all four files from `write-server-env.sh`. |
| Log: `password authentication failed` | `DB_PASSWORD` in `.env` differs from the one the database volume was first made with. The volume keeps its first password, so put the old value back. On a server with only test data you can start over: `docker compose down && docker volume rm schoolmatch_db-data`, then `deploy.sh <ip>` (this deletes every account and keeps the certificate). |
| Log: `Could not resolve placeholder 'DB_URL'` | A database variable is missing; the prod profile has no defaults on purpose. Do not edit `compose.yaml` on the server: run `deploy.sh <ip>` again. |
| Start-up stops with `GOOGLE_MODE=live needs GOOGLE_MAPS_SERVER_KEY …` | Run `write-server-env.sh <ip>`, then `deploy.sh <ip>`. The `.env` advice in the message is for laptops. |
| `app` restarts again and again, or `docker inspect -f '{{.State.ExitCode}} {{.State.OOMKilled}}' schoolmatch-app-1` shows `137 true` | It ran out of memory. Check `free -m` and `docker stats --no-stream` on the server. |
| Map pages show "Map unavailable"; the browser console says `RefererNotAllowedMapError` | The browser key does not allow `https://<address>/*` yet (step 3), or `GOOGLE_MAPS_BROWSER_KEY_SITE` was not in your `.env` when you ran `write-server-env.sh`. |
| 429 Too many requests during a demo | Everyone on the same Wi-Fi shares one count. `docker compose restart app` clears the counts. For a demo with the whole class on the site, add `APP_VISITORLIMIT_ENABLED=false` to `/opt/schoolmatch/.env`, run `docker compose up -d app`, and remove the line afterwards (then run the same command again). |
| "temporarily unavailable" from Google, though the allowance cannot be used up | The server key's IP restriction does not list `<ip>` (step 3). |
| After logging in you are logged out again, or redirects go to `http://` | The image is older than the `deploy/prep` changes to `application-prod.yml`. Run `deploy.sh <ip>` from a current commit. |
| "Demo data (stub)" badge on pages | `GOOGLE_MODE` / `ONEMAP_MODE` are not `live` in the server's `.env`. Run `write-server-env.sh <ip>`, then `deploy.sh <ip>`. |

## Other options we looked at

All prices are approximate, for Singapore, per month. They were checked in Oct 2026.

| Option | Cost | Why it was not chosen |
|:--|:--:|:--|
| Amazon ECS Express Mode with a private RDS database | about USD 100 | The easiest to run, with its own `https://` address and automatic rollback, but it costs about eight times as much for one small class project, with no credits on this account. |
| AWS App Runner with a public RDS database | about USD 40 | App Runner stopped taking new customers on 30 Apr 2026, and without a paid private connection the database would have to be reachable from the internet. |
| One EC2 server with a private RDS database | about USD 45 | The same hands-on server work as Lightsail for almost four times the cost. A separate database service adds little for made-up data. |
