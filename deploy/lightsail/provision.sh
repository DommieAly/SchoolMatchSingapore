#!/bin/bash
# Creates the Lightsail server with the AWS CLI (docs/deployment.md, step 2). Real money: USD 12 a month until
# teardown.sh deletes it. Safe to run twice: it skips what exists and sets the firewall again, so run it again when
# this computer's public IP has changed and SSH stops answering. Needs the AWS CLI signed in to the right account.
set -euo pipefail
cd "$(dirname "$0")"
export AWS_PAGER=""

REGION=ap-southeast-1
INSTANCE=schoolmatch
STATIC_IP=schoolmatch-ip
KEY_PAIR=schoolmatch-lightsail
KEY_FILE=$HOME/.ssh/schoolmatch-lightsail

lightsail() { aws --region "$REGION" lightsail "$@"; }

# True if it exists, false if AWS says it does not. Any other error (not signed in, no permission) stops the script.
exists() {
    local err
    if err=$(lightsail "$@" 2>&1 >/dev/null); then return 0; fi
    [[ $err == *NotFoundException* ]] && return 1
    echo "FAIL: $err"; exit 1
}
ACCOUNT=$(aws --region "$REGION" sts get-caller-identity --query Account --output text)   # stops here if not signed in
echo "AWS account: $ACCOUNT"

if [[ ! -f $KEY_FILE ]]; then
    if exists get-key-pair --key-pair-name "$KEY_PAIR"; then echo "FAIL: Lightsail already has the key $KEY_PAIR, but $KEY_FILE is not on this computer. Copy it and its .pub file from the computer that first ran this script."; exit 1; fi
    mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
    ssh-keygen -q -t rsa -b 4096 -N "" -C "$KEY_PAIR" -f "$KEY_FILE"   # Lightsail imports only the ssh-rsa type
    echo "SSH key made: $KEY_FILE"
fi
if ! exists get-key-pair --key-pair-name "$KEY_PAIR"; then
    lightsail import-key-pair --key-pair-name "$KEY_PAIR" --public-key-base64 "$(cat "$KEY_FILE.pub")" >/dev/null
    echo "SSH key imported: $KEY_PAIR"
fi

if ! exists get-instance --instance-name "$INSTANCE"; then
    lightsail create-instances --instance-names "$INSTANCE" --availability-zone "${REGION}a" \
        --blueprint-id ubuntu_24_04 --bundle-id small_3_0 --ip-address-type ipv4 \
        --key-pair-name "$KEY_PAIR" --user-data file://server-setup.sh >/dev/null
    echo "Server created: $INSTANCE"
fi
for i in $(seq 1 60); do          # wait up to 5 minutes: the steps below need a running server
    state=$(lightsail get-instance-state --instance-name "$INSTANCE" --query state.name --output text)
    [[ $state == running ]] && break
    [[ $i == 60 ]] && { echo "FAIL: the server is still '$state' after 5 minutes"; exit 1; }
    sleep 5
done

if ! exists get-static-ip --static-ip-name "$STATIC_IP"; then
    lightsail allocate-static-ip --static-ip-name "$STATIC_IP" >/dev/null
    echo "Static IP allocated: $STATIC_IP"
fi
if [[ $(lightsail get-static-ip --static-ip-name "$STATIC_IP" --query staticIp.attachedTo --output text) != "$INSTANCE" ]]; then
    lightsail attach-static-ip --static-ip-name "$STATIC_IP" --instance-name "$INSTANCE" >/dev/null
    echo "Static IP attached"
fi
IP=$(lightsail get-static-ip --static-ip-name "$STATIC_IP" --query staticIp.ipAddress --output text)

MY_IP=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
[[ $MY_IP =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "FAIL: could not find this computer's public IP"; exit 1; }
# This call replaces all the rules, so anything not listed here (such as Lightsail's default open SSH) is closed.
lightsail put-instance-public-ports --instance-name "$INSTANCE" --port-infos "[
    {\"fromPort\": 80, \"toPort\": 80, \"protocol\": \"tcp\", \"cidrs\": [\"0.0.0.0/0\"]},
    {\"fromPort\": 443, \"toPort\": 443, \"protocol\": \"tcp\", \"cidrs\": [\"0.0.0.0/0\"]},
    {\"fromPort\": 22, \"toPort\": 22, \"protocol\": \"tcp\", \"cidrs\": [\"$MY_IP/32\"]}
]" >/dev/null
echo "Firewall set: 80 and 443 open to everyone, 22 only to $MY_IP"

echo
echo "Server IP:    $IP"
echo "Site address: https://${IP//./-}.sslip.io"
