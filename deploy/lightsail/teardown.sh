#!/bin/bash
# Deletes the Lightsail server, its static IP and the imported SSH key (docs/deployment.md, "Tear down"), after asking.
# Every account and all data on the server go with it and cannot be brought back. A stopped server still costs money:
# only deleting ends the bill. Safe to run twice: it skips what is already gone.
set -euo pipefail
export AWS_PAGER=""

REGION=ap-southeast-1
INSTANCE=schoolmatch
STATIC_IP=schoolmatch-ip
KEY_PAIR=schoolmatch-lightsail

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

echo "This deletes the server '$INSTANCE' in $REGION, with every account and all data on it."
read -r -p "Type delete-schoolmatch to go on: " answer
[[ $answer == delete-schoolmatch ]] || { echo "Nothing deleted."; exit 1; }

if exists get-static-ip --static-ip-name "$STATIC_IP"; then
    if [[ $(lightsail get-static-ip --static-ip-name "$STATIC_IP" --query staticIp.attachedTo --output text) != None ]]; then
        lightsail detach-static-ip --static-ip-name "$STATIC_IP" >/dev/null
    fi
    lightsail release-static-ip --static-ip-name "$STATIC_IP" >/dev/null
    echo "Static IP released: $STATIC_IP"
fi
if exists get-instance --instance-name "$INSTANCE"; then
    lightsail delete-instance --instance-name "$INSTANCE" --force-delete-add-ons >/dev/null
    echo "Server deleted: $INSTANCE"
fi
if exists get-key-pair --key-pair-name "$KEY_PAIR"; then
    lightsail delete-key-pair --key-pair-name "$KEY_PAIR" >/dev/null
    echo "SSH key removed from Lightsail: $KEY_PAIR"
fi

echo
echo "Done. Still to do by hand: delete the site's browser key in Google Cloud, and the SSH key on this computer"
echo "(rm ~/.ssh/schoolmatch-lightsail ~/.ssh/schoolmatch-lightsail.pub)."
