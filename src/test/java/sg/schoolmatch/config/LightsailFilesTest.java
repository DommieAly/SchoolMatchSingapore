package sg.schoolmatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * The production files in {@code deploy/lightsail}, read as text (docs/deployment.md, DC-92). Plain JUnit: no Docker,
 * no AWS. These files really run only in the rehearsal (rehearsal.override.yaml) and on the server, so these tests stop
 * a later edit from dropping a safety rule without anyone noticing.
 */
class LightsailFilesTest {

    private static final String DIR = "deploy/lightsail/";
    private static final List<String> SERVICES = List.of("db", "app", "proxy");

    private static Properties compose() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new FileSystemResource(DIR + "compose.yaml"));
        return factory.getObject();
    }

    private static String file(String name) throws IOException {
        return Files.readString(Path.of(DIR + name), StandardCharsets.UTF_8);
    }

    /** The lines Caddy really reads: not blank, not a comment. */
    private static List<String> activeCaddyLines() throws IOException {
        return file("Caddyfile").lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    @Test
    @Tag("NFR-SEC-03")
    @DisplayName("TC-LightsailFiles-01: only the proxy has published ports (80 and 443); the database is not published and the app reaches it over TLS with certificate checking")
    void onlyTheProxyIsPublished() {
        Properties c = compose();
        assertThat(c.getProperty("services.proxy.ports[0]")).isEqualTo("80:80");
        assertThat(c.getProperty("services.proxy.ports[1]")).isEqualTo("443:443");
        assertThat(c.getProperty("services.proxy.ports[2]")).isNull();
        assertThat(c.getProperty("services.db.ports[0]")).isNull();
        assertThat(c.getProperty("services.app.ports[0]")).isNull();
        assertThat(c.getProperty("services.db.image")).isEqualTo("postgres:17-alpine");
        assertThat(c.getProperty("services.db.entrypoint[2]")).contains("-c ssl=on");
        assertThat(c.getProperty("services.app.environment.DB_URL"))
                .startsWith("jdbc:postgresql://db:5432/schoolmatch?")
                .contains("sslmode=verify-full", "sslrootcert=/certs/ca.crt");
        assertThat(c.getProperty("services.app.volumes[0]")).isEqualTo("./certs/ca.crt:/certs/ca.crt:ro");
    }

    @Test
    @Tag("NFR-SEC-01")
    @DisplayName("TC-LightsailFiles-02: no secret is written in compose.yaml: the database password and keys come from .env, the app image from IMAGE, and the CA's private key is not mounted")
    void noSecretInComposeFile() throws IOException {
        Properties c = compose();
        assertThat(c.getProperty("services.db.environment.POSTGRES_PASSWORD")).startsWith("${DB_PASSWORD:?");
        assertThat(c.getProperty("services.app.env_file")).isEqualTo(".env");
        assertThat(c.getProperty("services.app.image")).startsWith("${IMAGE:?");
        assertThat(c.getProperty("services.app.environment.DB_PASSWORD")).isNull();
        assertThat(c.getProperty("services.app.environment.GOOGLE_MAPS_SERVER_KEY")).isNull();
        assertThat(c.getProperty("services.app.environment.GOOGLE_MODE")).isEqualTo("${GOOGLE_MODE:-live}");
        assertThat(c.getProperty("services.app.environment.ONEMAP_MODE")).isEqualTo("${ONEMAP_MODE:-live}");
        assertThat(c.getProperty("services.app.environment.SPRING_PROFILES_ACTIVE"))
                .as("the image sets the prod profile").isNull();
        assertThat(file("compose.yaml")).doesNotContain("ca.key");
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-03: every service restarts by itself, has a health check and limited logs, and the memory limits leave room on a 2 GB server")
    void everyServiceLooksAfterItself() {
        Properties c = compose();
        long totalMegabytes = 0;
        for (String service : SERVICES) {
            String key = "services." + service + ".";
            assertThat(c.getProperty(key + "restart")).as(service).isEqualTo("unless-stopped");
            assertThat(c.getProperty(key + "healthcheck.test[0]")).as(service).isNotNull();
            assertThat(c.getProperty(key + "logging.driver")).as(service).isEqualTo("json-file");
            assertThat(c.getProperty(key + "logging.options.max-size")).as(service).isEqualTo("10m");
            assertThat(c.getProperty(key + "logging.options.max-file")).as(service).isEqualTo("3");
            assertThat(c.getProperty(key + "stop_grace_period")).as(service).isEqualTo("30s");
            String limit = c.getProperty(key + "mem_limit");
            assertThat(limit).as(service).matches("\\d+[mg]");
            long number = Long.parseLong(limit.substring(0, limit.length() - 1));
            totalMegabytes += limit.endsWith("g") ? number * 1024 : number;
        }
        assertThat(totalMegabytes).as("Ubuntu and Docker need the rest of the 2 GB").isLessThanOrEqualTo(1536);
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-04: accounts and the HTTPS certificate are in named volumes, so they survive restarts and new images")
    void dataIsInNamedVolumes() {
        Properties c = compose();
        assertThat(c.getProperty("services.db.volumes[0]")).isEqualTo("db-data:/var/lib/postgresql/data");
        assertThat(c.getProperty("services.proxy.volumes[0]")).isEqualTo("./Caddyfile:/etc/caddy/Caddyfile:ro");
        assertThat(c.getProperty("services.proxy.volumes[1]")).isEqualTo("caddy-data:/data");
        assertThat(c.getProperty("services.proxy.volumes[2]")).isEqualTo("caddy-config:/config");
        for (String volume : List.of("db-data", "caddy-data", "caddy-config")) {
            assertThat(c).as(volume).containsKey("volumes." + volume);
        }
    }

    @Test
    @Tag("NFR-SEC-03")
    @DisplayName("TC-LightsailFiles-05: the Caddyfile takes the site address from the environment, sends the security headers and forwards to the app, with no Referrer-Policy: the Google browser key depends on the referrer")
    void caddyfile() throws IOException {
        List<String> active = activeCaddyLines();
        assertThat(active).contains("{$SITE_ADDRESS} {", "X-Content-Type-Options nosniff", "X-Frame-Options DENY",
                "Strict-Transport-Security \"max-age=86400\"", "reverse_proxy app:8080 {");
        assertThat(active).noneMatch(l -> l.toLowerCase().contains("referrer-policy"));
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-13: the Caddyfile sets an email, which gives Caddy a second certificate source, and leaves only the health check's lines out of the log: Troubleshooting reads the rest")
    void caddyLogAndEmail() throws IOException {
        List<String> active = activeCaddyLines();
        assertThat(active).anyMatch(l -> l.matches("email \\S+@\\S+"));
        assertThat(active).filteredOn(l -> l.startsWith("exclude ")).singleElement().asString()
                .startsWith("exclude admin.api ");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-LightsailFiles-06: the password block is there, on or off, and it leaves the health check open")
    void passwordBlockKeepsHealthCheckOpen() throws IOException {
        assertThat(file("Caddyfile").replace("# ", "")).contains(
                "@locked not path /actuator/health/readiness",
                "basic_auth @locked {",
                "{$SITE_USERNAME} {$SITE_PASSWORD_HASH}");
        Properties c = compose();
        assertThat(c.getProperty("services.proxy.environment.SITE_PASSWORD_HASH"))
                .as("empty unless .env sets it").isEqualTo("${SITE_PASSWORD_HASH:-}");
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-07: provision.sh and teardown.sh always name the Singapore Region, the server has the agreed size, and only 80 and 443 are open to everyone")
    void awsCommands() throws IOException {
        String provision = file("provision.sh");
        String teardown = file("teardown.sh");
        for (String script : List.of(provision, teardown)) {
            assertThat(script).contains("REGION=ap-southeast-1");
            assertThat(script.lines().filter(l -> l.contains("aws ") && l.contains("lightsail()")).toList())
                    .containsExactly("lightsail() { aws --region \"$REGION\" lightsail \"$@\"; }");
        }
        assertThat(provision.lines().filter(l -> l.contains("$(aws ") || l.strip().startsWith("aws ")).toList())
                .allMatch(l -> l.contains("aws --region \"$REGION\""));
        assertThat(provision).contains("--blueprint-id ubuntu_24_04", "--bundle-id small_3_0", "--ip-address-type ipv4",
                "--user-data file://server-setup.sh", "ssh-keygen -q -t rsa");
        List<String> rules = provision.lines().filter(l -> l.contains("\\\"fromPort\\\"")).toList();
        assertThat(rules).hasSize(3);
        assertThat(rules).filteredOn(l -> l.contains("0.0.0.0/0")).hasSize(2)
                .allMatch(l -> l.contains("\\\"fromPort\\\": 80,") || l.contains("\\\"fromPort\\\": 443,"));
        assertThat(rules).filteredOn(l -> l.contains("\\\"fromPort\\\": 22,")).singleElement().asString()
                .contains("$MY_IP/32");
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-12: provision.sh and teardown.sh stop on an AWS error and show the account: only AWS's own \"not found\" means something is gone")
    void awsErrorsStopTheScripts() throws IOException {
        for (String name : List.of("provision.sh", "teardown.sh")) {
            String script = file(name);
            assertThat(script).as(name).contains("ACCOUNT=$(aws", "echo \"AWS account: $ACCOUNT\"",
                    "[[ $err == *NotFoundException* ]] && return 1", "echo \"FAIL: $err\"; exit 1",
                    "exists get-static-ip", "exists get-instance", "exists get-key-pair");
            assertThat(script).as(name).doesNotContain(">/dev/null 2>&1");
        }
        String teardown = file("teardown.sh");
        assertThat(teardown.indexOf("echo \"AWS account: $ACCOUNT\""))
                .as("the account is shown before the question").isLessThan(teardown.indexOf("read -r -p"));
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-14: without the SSH key on this computer, provision.sh stops if Lightsail already has that key, before it makes a new key or changes the firewall")
    void provisionNeedsTheFirstKey() throws IOException {
        String provision = file("provision.sh");
        int guard = provision.indexOf("if exists get-key-pair --key-pair-name \"$KEY_PAIR\"; then echo \"FAIL: ");

        assertThat(guard).isGreaterThan(provision.indexOf("if [[ ! -f $KEY_FILE ]]; then"));
        assertThat(guard).isLessThan(provision.indexOf("ssh-keygen -q"));
        assertThat(guard).isLessThan(provision.indexOf("put-instance-public-ports"));
        assertThat(provision.substring(guard, provision.indexOf('\n', guard))).endsWith("exit 1; fi");
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-15: the guide covers the key on another computer, going back to an earlier version, the second certificate source, and a check that each visitor has their own count")
    void guideCoversTheseCases() throws IOException {
        String guide = Files.readString(Path.of("docs/deployment.md"), StandardCharsets.UTF_8);
        assertThat(guide).contains("`Permission denied (publickey)`", "chmod 600 ~/.ssh/schoolmatch-lightsail",
                "To go back to an earlier version:", "`git checkout <old commit>`", "migrations are not undone",
                "Caddy also tries ZeroSSL by itself",
                "/directions?to=school:catholic-high-school&mode=x", "The last code must be `429`");
    }

    @Test
    @Tag("NFR-SEC-01")
    @DisplayName("TC-LightsailFiles-08: write-server-env.sh sends the keys on stdin and prints none of them; deploy.sh and the others never turn on command tracing or print the Compose settings")
    void scriptsDoNotPrintSecrets() throws IOException {
        String env = file("write-server-env.sh");
        for (String name : List.of("GOOGLE_MAPS_SERVER_KEY", "GOOGLE_MAPS_MAP_ID", "GOOGLE_MAPS_BROWSER_KEY_SITE")) {
            assertThat(env.lines().filter(l -> l.contains("$" + name)))
                    .as(name + " is only checked or sent with printf, a shell builtin")
                    .allMatch(l -> l.contains("printf") || l.strip().startsWith("[["));
        }
        assertThat(env).contains("| \"${SSH[@]}\" \"$REMOTE\"", "openssl rand -hex", "grep -q '^DB_PASSWORD=' .env",
                "chmod 600 .env", "umask 077");
        assertThat(env.lines().filter(l -> l.contains("echo") || l.contains("printf"))
                .filter(l -> l.contains("DB_PASSWORD"))).allMatch(l -> l.contains(">> .env"));
        for (String script : List.of("provision.sh", "server-setup.sh", "make-db-certs.sh", "write-server-env.sh",
                "deploy.sh", "teardown.sh", "make-rehearsal-files.sh")) {
            assertThat(file(script)).as(script).doesNotContain("set -x", "compose config", "cat .env");
        }
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-09: deploy.sh refuses a dirty tree, builds for x86, sends the image over SSH with no registry, and copies only the two production files")
    void deployScript() throws IOException {
        String deploy = file("deploy.sh");
        assertThat(deploy).contains("git status --porcelain", "--platform linux/amd64", "--load",
                "docker save \"$IMAGE\" | gzip | \"${SSH[@]}\" 'gunzip | docker load'",
                "/opt/schoolmatch/compose.yaml", "/opt/schoolmatch/Caddyfile", "IMAGE=schoolmatch:$(git rev-parse --short HEAD)",
                "docker compose up -d --wait", "caddy reload", "[[ $rc != 255 ]]", "cannot reach the server over SSH",
                "If the line above is a Caddy error", "curl -s -m 5 -o /dev/null");
        assertThat(deploy).doesNotContain("docker push", "rehearsal");
        assertThat(deploy).as("a commit without the password block must not open the site silently")
                .contains("WARNING: the server's .env has a site password");
        assertThat(deploy).as("a password block with no hash on the server would lock everyone out")
                .contains("FAIL: this commit's Caddyfile has the password block");
        assertThat(file("rehearsal.override.yaml")).contains("GOOGLE_MODE: stub", "ONEMAP_MODE: stub", "127.0.0.1:8443:443");
        assertThat(file(".gitignore")).contains(".rehearsal/");
    }

    @Test
    @Tag("NFR-DEPLOY-01")
    @DisplayName("TC-LightsailFiles-10: server-setup.sh is plain sh, installs Docker from Docker's own repository, adds the swap file and security updates, and make-db-certs.sh makes 825-day certificates once")
    void firstBoot() throws IOException {
        String setup = file("server-setup.sh");
        assertThat(setup).as("Lightsail may run it with sh").doesNotContain("pipefail", "[[", "function ");
        assertThat(setup.lines().filter(l -> l.contains("$APT update")).toList())
                .as("apt-get update's own lock has no timeout, so it runs only inside the retry")
                .containsExactly("    until $APT update; do");
        assertThat(setup).contains("set -eu", "https://download.docker.com/linux/ubuntu", "docker-compose-plugin",
                "fallocate -l 1G /swapfile", "unattended-upgrades", "/opt/schoolmatch");
        String certs = file("make-db-certs.sh");
        assertThat(certs).contains("-days 825 -subj", "-days 825 -extfile", "subjectAltName=DNS:db", "[[ -f server.crt ]]");
        assertThat(certs).contains("-keyout ca.key", "-keyout server.key");
    }

    @Test
    @Tag("NFR-PRIV-01")
    @DisplayName("TC-LightsailFiles-11: the guide says the removal date is not decided and gives no removal date")
    void noRemovalDate() throws IOException {
        String guide = Files.readString(Path.of("docs/deployment.md"), StandardCharsets.UTF_8);
        assertThat(guide).contains("The removal date is not decided", "when the course project ends");
        assertThat(guide).doesNotContainPattern("(?i)(remov|delet|tear)[^.\\n|]{0,60}\\b\\d{1,2} (Nov|Dec|Jan)");
        assertThat(guide).as("the password has no team deadline").doesNotContainPattern("(?i)\\bby \\d{1,2} (Nov|Dec|Jan)");
    }
}
