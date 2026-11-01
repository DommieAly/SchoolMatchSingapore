#!/bin/bash
# First boot of the Lightsail server (Ubuntu 24.04, run as root by provision.sh through user data; docs/deployment.md,
# step 2). Its output goes to /var/log/cloud-init-output.log on the server. Plain sh on purpose, with no bash features:
# it must work whichever shell runs it, because Lightsail adds its own lines to user data.
set -eu
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
APT="apt-get -y -o DPkg::Lock::Timeout=300"   # on first boot Ubuntu's own updates may hold the lock for a while

# DPkg::Lock::Timeout does not cover apt-get update's own lock, which Ubuntu's daily job can hold on first boot
apt_update() {
    tries=0
    until $APT update; do
        tries=$((tries + 1))
        [ "$tries" -lt 30 ] || return 1
        sleep 10
    done
}

# A 1 GB swap file for short memory peaks: the three containers may use up to about 1.5 GB of the 2 GB
if [ ! -f /swapfile ]; then
    fallocate -l 1G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Docker Engine and the compose plugin from Docker's official apt repository
apt_update
$APT install ca-certificates curl unattended-upgrades
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
cat > /etc/apt/sources.list.d/docker.sources <<SOURCES
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
SOURCES
apt_update
$APT install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
usermod -aG docker ubuntu

# Security updates every day. They do not reboot the server, and Docker's own packages are not among them.
cat > /etc/apt/apt.conf.d/20auto-upgrades <<UPGRADES
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
UPGRADES
systemctl enable --now unattended-upgrades

# The site's folder; the marker tells write-server-env.sh that this script has finished
install -d -o ubuntu -g ubuntu -m 750 /opt/schoolmatch
touch /opt/schoolmatch/.setup-done
