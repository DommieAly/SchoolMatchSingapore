# Deploying SchoolMatch SG on AWS

This guide puts the app online at an `https://` address for the demo, on **Amazon ECS Express Mode** (the app) and **Amazon RDS for PostgreSQL 17** (the database), in the Singapore Region `ap-southeast-1`. One teammate with the AWS account does it; the console steps take about an hour, plus about 20 minutes of waiting.

AWS facts in this guide were checked against AWS documentation and price lists on 25 Oct 2026. AWS renames console buttons from time to time; if a label differs slightly, pick the closest match.

## What gets built

```
Browser ──HTTPS──▶ Load balancer (made by ECS Express, with its own https://… address and certificate)
                        │ plain HTTP, port 8080
                        ▼
                  App container (1 task on Fargate, image from Amazon ECR, profile "prod")
                        │ PostgreSQL over TLS (sslmode=verify-full)        │ HTTPS out to the internet
                        ▼                                                   ▼
                  RDS PostgreSQL 17 (private: no public access)      Google Routes / Places, OneMap
```

- The browser loads Google Maps JavaScript itself, with the browser key that the page carries.
- Exactly **one** app task runs. Page state (flash messages, the chosen starting point, the last recommendations) lives in the task's memory, so a second task would lose it between requests. Logins are in the database and survive restarts.
- The image contains the app, the school snapshot (`data/snapshots`) and Amazon's RDS certificate bundle. It contains no keys or passwords: those are set on the service.
- The `prod` profile ([`application-prod.yml`](../src/main/resources/application-prod.yml)) is written for this setup: it trusts the load balancer's `X-Forwarded-*` headers, redirects with relative URLs, marks both cookies `Secure` with the `__Host-` prefix, and checks the database certificate.

Why this platform: AWS App Runner, the usual "container to URL" service, stopped taking new customers on 30 Apr 2026, and AWS points to ECS Express Mode instead. Express Mode gives an `https://` address without buying a domain, and its tasks run in public subnets, so they reach Google and OneMap without a NAT gateway (about USD 43/month). The [alternative](#alternative-one-ec2-server) at the end is cheaper but needs more hands-on server work.

In this guide, **`<address>`** means the host name of the deployed site only, e.g. `schoolmatch-abc123.ecs.ap-southeast-1.on.aws` (no `https://`, no trailing `/`). You get it in step 7.

## Before you start

- [ ] **The TA knows about the MOE data** (README, "Current state"). A public site republishes the MOE SchoolFinder PSLE ranges and affiliations.
- [ ] **The OneMap Terms of Use question** ([`data/README.md`](../data/README.md), Licences) is settled or accepted by the team.
- [ ] **Testers use made-up data.** The site stores a child's PSLE score and home address, and there is no "delete my account" yet ([`database-design.md`](database-design.md), sections 12 and 13). The address will not stay within the class: new HTTPS certificates are published in public logs that scanners read. Tell testers to use test accounts, made-up scores and a public address such as a school's postal code, and delete the deployment after the demo (step 8). There is also no limit on login attempts; the team accepts that for the demo week.
- [ ] **Money.** It costs about **USD 24 a week** (table below) and is charged per hour even with no visitors. New AWS accounts get USD 100 in credits at sign-up (plus up to USD 100 more for small "Explore AWS" tasks).
  - Pick the **Paid plan** at sign-up: the credits are still used first, and the site keeps running if they run out (you pay the difference, so set the budget alert in step 4).
  - On the **Free plan** nothing is ever charged, but when the credits reach zero (about 4 weeks at this cost) the account closes and the site goes offline, so deploy close to the demo.
- [ ] **The account can use Singapore.** AWS's newest sign-up flow (since 16 Sep 2026) fixes a new account with a Singapore address to the Sydney Region. Sign up with **"Sign up for AWS (advanced)"** (the classic flow; a card is required) to choose `ap-southeast-1`. An account already fixed to Sydney either does every step in `ap-southeast-2` (the data then sits in Australia), or turns on "Activate advanced features" to open other Regions (this cannot be undone, and the account stops earning activity credits).
- [ ] **Account safety.** Turn on MFA for the root user at the first sign-in, create an admin user (IAM Identity Center, or IAM) and do every step as that user. Never create access keys for the root user.
- [ ] **Tools on your computer:** Docker Desktop (running), AWS CLI version 2.32 or later (`aws --version`), a **bash** shell for steps 2, 3 and 7 (macOS or Linux Terminal; on Windows, Git Bash from Git for Windows, or WSL; not PowerShell), and this repository on the `deploy/prep` branch (or `main` once it is merged).

| Item (Singapore prices, Oct 2026) | Per month | Per week |
|:--|:--:|:--:|
| Fargate task, 1 vCPU / 2 GB, x86 | ~$45 | ~$10.5 |
| Load balancer (+ a little traffic) | ~$20 | ~$4.6 |
| Public IPv4 addresses (load balancer + task) | ~$11–15 | ~$3 |
| RDS db.t4g.micro + 20 GB storage | ~$21 | ~$4.9 |
| Logs, image storage | ~$1–2 | ~$0.4 |
| **Total** | **~$100** | **~$24** |

## 1. Build the image on your computer

From the repository root, on a commit whose CI is green (the build does not run the tests; CI runs them on every push):

```bash
docker build -t schoolmatch:latest .
```

The first build downloads Maven dependencies and takes a few minutes; later builds take about a minute.

## 2. Rehearse production locally

[`deploy/rehearsal/`](../deploy/rehearsal/) runs the image exactly as on AWS: the `prod` profile, PostgreSQL over TLS with certificate checking, and an HTTPS proxy in front that talks plain HTTP to the app, like the load balancer.

```bash
cd deploy/rehearsal
./make-certs.sh
docker compose up -d --build --wait
./smoke.sh
```

`--build` rebuilds the image from your working tree, and `--wait` returns once the app answers its health check. `smoke.sh` waits for the site, registers a throwaway account, logs in, opens the profile, logs out, and posts a form "from another site". It prints one line per check and ends with **"All checks passed."**:

- redirects are relative (`/login`, `/`), never `http://`;
- every cookie is `Secure`;
- the profile gives `200` after login;
- the form from another site gets `403`.

You can also open https://localhost:8443 (accept the browser's warning about the rehearsal certificate). Finish with `docker compose down -v`.

## 3. Push the image to Amazon ECR

```bash
git status --porcelain                       # must print nothing: deploy only committed code
aws login                                    # signs the CLI in through your browser; Region: ap-southeast-1
export AWS_REGION=ap-southeast-1             # ap-southeast-2 if your account is fixed to Sydney
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY=$ACCOUNT.dkr.ecr.$AWS_REGION.amazonaws.com
TAG=$(git rev-parse --short HEAD)            # the image name records the commit it was built from
aws ecr create-repository --repository-name schoolmatch      # first time only
aws ecr get-login-password | docker login --username AWS --password-stdin $REGISTRY
docker build --platform linux/amd64 -t $REGISTRY/schoolmatch:$TAG .
docker image inspect $REGISTRY/schoolmatch:$TAG --format '{{.Architecture}}'   # must print amd64
docker push $REGISTRY/schoolmatch:$TAG
echo $REGISTRY/schoolmatch:$TAG              # the image URI for step 6
```

- `--platform linux/amd64` matters on Apple silicon Macs: Fargate runs x86 by default, and an arm64 image does not start there. Only the small final stage is built for x86, so the build stays fast.
- Without CLI 2.32+, use `aws configure` with an access key of your admin user instead of `aws login`, and delete that key after the demo.

## 4. Set a budget alert

Billing and Cost Management → **Budgets** → Create budget → *Monthly cost budget*, amount **USD 50**, alert emails to two teammates. AWS does not stop anything when the budget is reached; the email is the reminder to [tear down](#8-tear-down).

## 5. Create the database (RDS)

RDS console (Region `ap-southeast-1`, or `ap-southeast-2`) → **Create database**:

| Setting | Value |
|:--|:--|
| Creation method | Standard create |
| Engine | PostgreSQL, version **17.x** (the newest 17) |
| Templates | Free tier or Sandbox (whichever appears); otherwise Dev/Test |
| Availability | Single-AZ |
| DB instance identifier | `schoolmatch-db` |
| Master username | `schoolmatch` |
| Credentials management | **Self managed**. Do not pick "Managed in AWS Secrets Manager": it changes the password every 7 days, and the app only reads it at start-up. If you tick *Auto generate a password*, click **View credential details** on the banner right after Create and save the password; it is shown only once. |
| Instance class | `db.t4g.micro` |
| Storage | 20 GB gp3, storage autoscaling off |
| Compute resource / VPC | Don't connect to an EC2 compute resource; **Default VPC** |
| Public access | **No** |
| VPC security group | Create new: `schoolmatch-db` |
| Additional configuration → Initial database name | `schoolmatch` |
| Backup retention | 1 day |
| Encryption | Enabled (the default) |
| Maintenance | Auto minor version upgrade **off**; maintenance window outside your demo slots |

Then let the app reach it: EC2 console → **Security Groups** → `schoolmatch-db` → Edit inbound rules → the PostgreSQL rule on port 5432 → Source: **Custom**, the default VPC's CIDR (VPC console → Your VPCs → default VPC → IPv4 CIDR, usually `172.31.0.0/16`). The database has no public address, so only resources inside your VPC can reach it. (Tighter, after step 6: change the source to the service's security group, shown under the ECS service's Networking, and remove the CIDR.)

Wait until the status is **Available** (about 10 minutes), then copy the **Endpoint** from Connectivity & security (e.g. `schoolmatch-db.abc123xyz.ap-southeast-1.rds.amazonaws.com`).

The app connects as the master user, which owns the `schoolmatch` database, so Flyway can create the tables on the first start. A separate app user without DDL rights ([`database-design.md`](database-design.md), section 9) is the better long-term setup but needs SQL access from inside the VPC; it is not needed for the demo.

## 6. Create the app service (ECS Express Mode)

### 6a. Secrets (recommended when more than one person can open the AWS console)

Plain environment variables are visible to anyone who can open the service in the console. To keep `DB_PASSWORD` and `GOOGLE_MAPS_SERVER_KEY` out of sight, do this **before** creating the service:

1. Systems Manager → **Parameter Store** → Create parameter, twice: `/schoolmatch/db-password` and `/schoolmatch/google-server-key`, Tier *Standard* (free), Type **SecureString**, default KMS key. Copy each parameter's ARN.
2. IAM → Roles → **Create role** → trusted entity *AWS service*, use case **Elastic Container Service Task** → attach `AmazonECSTaskExecutionRolePolicy` → name `schoolmatch-task-execution` → Create. Then open the role → Add permissions → **Create inline policy** → JSON:
   ```json
   {"Version": "2012-10-17", "Statement": [{"Effect": "Allow", "Action": "ssm:GetParameters",
     "Resource": "arn:aws:ssm:ap-southeast-1:<account id>:parameter/schoolmatch/*"}]}
   ```
3. In step 6b, choose `schoolmatch-task-execution` as the task execution role, and enter those two variables with value type **Secret** and the parameter ARN as the value.

If only one person can open the console, skip 6a: choose "Create new role" in 6b and enter the two values as plain environment variables.

### 6b. Create the service

ECS console → **Express mode** → Create. Fill in **Image URI** (from step 3) and the two roles (**Task execution role**: `schoolmatch-task-execution` from 6a, or *Create new role*; **Infrastructure role**: *Create new role*). Then open **Additional configurations – optional**: most of the settings below are inside it, and its defaults (port 80, health check path `/`) would keep the task from ever becoming healthy. Leave **Customize networking configurations** unticked, so the default VPC and its public subnets are used.

| Setting | Value |
|:--|:--|
| Name | `schoolmatch` (part of the address; it cannot be changed later) |
| Container port | `8080` |
| Health check path | **`/actuator/health/readiness`** |
| Compute: CPU / Memory | 1 vCPU / 2 GB |
| Auto scaling: Minimum number of tasks / Maximum number of tasks | **1** / **1** |
| Environment variables | the table below |

| Variable | Value type | Value |
|:--|:--|:--|
| `DB_URL` | Environment variable | `jdbc:postgresql://<RDS endpoint>:5432/schoolmatch?sslmode=verify-full&sslrootcert=/app/certs/rds-global-bundle.pem&reWriteBatchedInserts=true` |
| `DB_USERNAME` | Environment variable | `schoolmatch` |
| `DB_PASSWORD` | Secret (6a) or Environment variable | the parameter ARN, or the RDS master password |
| `GOOGLE_MODE` | Environment variable | `live` |
| `ONEMAP_MODE` | Environment variable | `live` |
| `GOOGLE_MAPS_SERVER_KEY` | Secret (6a) or Environment variable | the parameter ARN, or the server key |
| `GOOGLE_MAPS_BROWSER_KEY` | Environment variable | the browser key for the deployed site (step 7) |
| `GOOGLE_MAPS_MAP_ID` | Environment variable | the map ID, if you use one |

`SPRING_PROFILES_ACTIVE=prod` is already set inside the image; do not override it.

Create the service. Express Mode now makes the load balancer, certificate, security groups, log group and task. Wait until the service is **Active** and one task is **Running** (5–15 minutes). If the task stops and restarts, read the log ([troubleshooting](#troubleshooting)).

## 7. Google keys, then check the site

The **Application URL** is on the service page (`https://….on.aws`). Use the address the console shows, not one you build from the service name. `<address>` is its host name.

**Google keys first** (Google Cloud → APIs & Services → **Credentials**, see [`google-maps-setup.md`](google-maps-setup.md)):

- **Browser key:** create a separate key for the deployed site, with Website restriction `https://<address>/*` only and API restriction *Maps JavaScript API* only, so the laptop key keeps its `localhost` entries. Put it in `GOOGLE_MAPS_BROWSER_KEY` (service → Update, if you created the service with the laptop key). The restriction takes a few minutes to apply.
- **Server key:** keep its API restriction (Routes API and Places API (New)) and the daily quotas from `google-maps-setup.md`. Fargate tasks get a new public IP on every start, so an IP restriction would break the key; the quotas are the protection instead.

**Then check:**

1. `https://<address>/actuator/health` shows `{"groups":["liveness","readiness"],"status":"UP"}`.
2. The task log (service → Logs, or CloudWatch → Log groups) contains `The following 1 profile is active: "prod"` and `Started SchoolMatchApplication`. The first task logs `School dataset 2026-10-04.1 loaded (147 schools …)`; later tasks log `School snapshot 2026-10-04.1 … serving 2026-10-04.1 … 147 schools` instead.
3. When the service events show the deployment as **completed**, run from the repository root: `deploy/rehearsal/smoke.sh https://<address>`. It must end with "All checks passed." (It leaves one `smoke_…` test account behind.)
4. In a browser: search, open a school, open the map (a real Google map), register, set a home address in the profile (a real OneMap lookup), get directions, run a recommendation, log out. No page shows the "Demo data (stub)" badge.

## During the demo week

- **Do not deploy during a demo slot.** A new deployment starts a second task, moves traffic over in steps and then stops the old one; users in the middle of a flow lose their page state. While a deployment is in progress, Express Mode rolls it back automatically if more than 1% of requests fail with 4xx or 5xx (for 2 data points within 3 minutes), which a few wrong links can trigger. Deploy at quiet times, do not run `smoke.sh` until the deployment is completed (its last check is a deliberate 403), and watch the service events.
- **Keep exactly one task.** Do not raise the maximum above 1 (page state is in memory).
- **Google allowance.** The app's own daily budget (`app.external.budget`) applies as on a laptop. Its counters are kept in the database, so a restart does not reset them, and the budget day ends at midnight Pacific Time (15:00 Singapore time, 16:00 from 1 Nov). Every visitor shares it, including guests and crawlers. `robots.txt` asks search engines not to crawl, but only polite crawlers obey. When maps or nearby facilities say "temporarily unavailable", the day's allowance is used up. One island-wide public-transport travel-time filter uses about half a day's route-matrix allowance; avoid repeating it on demo day.
- **Updating:** commit, build and push with step 3 (the tag changes with the commit), then service → **Update** → new image URI. The database and its data stay.
- Check the budget alert email and the Billing page once a day.

## 8. Tear down

When the demo is over (or before the credits run out), in your Region:

1. ECS → Clusters → `default` → Services → select `schoolmatch` → **Delete** → type `delete`. The dialog lists the resources Express created and any it keeps; delete the kept ones too (e.g. the CloudWatch log group, which never expires on its own). Then ECS → Task definitions → `schoolmatch` → deregister and delete every revision (they hold the plain environment variables).
2. RDS → `schoolmatch-db` → Delete: untick *Create final snapshot* and *Retain automated backups*, tick the acknowledgement, Delete. Delete any manual snapshots.
3. ECR → repository `schoolmatch` → Delete.
4. Parameter Store parameters (6a), the `schoolmatch-db` security group, and the IAM roles created for the service (if nothing else uses them).
5. Google Cloud → delete the deployed site's browser key. If the server key was a plain environment variable and others had console access, rotate it ([`google-maps-setup.md`](google-maps-setup.md)).

A Free-plan AWS account closes by itself after 6 months or when its credits run out; its data is kept only 90 days after that.

## Troubleshooting

| What you see | Why, and what to do |
|:--|:--|
| Task stops with `CannotPullContainerError … does not contain descriptor matching platform 'linux/amd64'`, or the log shows `exec format error` | The image was built for arm64. Rebuild with `--platform linux/amd64` and check it with `docker image inspect` (step 3). |
| Task stops with `ResourceInitializationError … not authorized to perform: ssm:GetParameters` | The task execution role lacks the inline policy from 6a, or the service uses a different role. Fix the role, then service → Update (force a new deployment). |
| Log: `Connection to … refused`, or a timeout to the RDS endpoint | The `schoolmatch-db` security group does not allow the VPC CIDR (or the service's security group) on 5432 (step 5), or the database is not Available yet. |
| Log: `PKIX path building failed` / SSL error | `sslrootcert` in `DB_URL` must be exactly `/app/certs/rds-global-bundle.pem`. |
| Log: `password authentication failed` | `DB_USERNAME` / `DB_PASSWORD` do not match the RDS master user. A changed password needs a new deployment (the app reads it at start-up). |
| Log: `Could not resolve placeholder 'DB_URL'` | A database variable is missing; the prod profile has no defaults on purpose. |
| Start-up stops with `GOOGLE_MODE=live needs GOOGLE_MAPS_SERVER_KEY …` | Set `GOOGLE_MAPS_SERVER_KEY` on the service (step 6b); the `.env` advice in the message is for laptops. |
| Health check fails, task restarts every few minutes | The health check path must be `/actuator/health/readiness` and the port `8080` (inside *Additional configurations*). Start-up takes about 10–30 s. |
| Map pages show "Map unavailable"; the browser console says `RefererNotAllowedMapError` | The browser key does not allow `https://<address>/*` yet (step 7), or `GOOGLE_MAPS_BROWSER_KEY` is not set on the service. |
| After logging in you are logged out again, or redirects go to `http://` | The image is older than the `deploy/prep` changes to `application-prod.yml`. Rebuild and push (step 3). |
| "Demo data (stub)" badge on pages | `GOOGLE_MODE` / `ONEMAP_MODE` are not `live` on the service. |

## Alternative: one EC2 server

About half the price (~USD 45/month): one `t3.small` EC2 instance with an Elastic IP, running this image plus a Caddy container that gets a free Let's Encrypt certificate for a name such as `<ip-with-dashes>.sslip.io`, and the same private RDS database (RDS → Set up EC2 connection). The fixed IP also lets the Google server key be IP-restricted, and there is no automatic rollback. The costs: the team looks after the server (SSH or Session Manager, OS and Docker updates), and the shared sslip.io certificate quota ran out several times in 2026 (fall back to nip.io or a cheap domain). Use it if Express Mode is not available on your account or its cost is a problem; `deploy/rehearsal/compose.yaml` is close to what that server would run.
