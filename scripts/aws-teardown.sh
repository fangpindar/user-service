#!/usr/bin/env bash
#
# Tear down everything created by aws-deploy.sh:
#   - Terminate the EC2 instance
#   - Delete the security group
#   - Delete the key pair (and the local .pem file)
#
# Use this when you no longer need the demo running, to avoid being billed
# beyond the free tier (e.g. after the interview is done).
#
set -euo pipefail

PROJECT_NAME="${PROJECT_NAME:-denden-user-service}"
AWS_REGION="${AWS_REGION:-ap-northeast-1}"
KEY_NAME="${KEY_NAME:-${PROJECT_NAME}-key}"
SG_NAME="${SG_NAME:-${PROJECT_NAME}-sg}"
INSTANCE_TAG="${INSTANCE_TAG:-${PROJECT_NAME}-app}"
LOCAL_KEY_PATH="${LOCAL_KEY_PATH:-$HOME/.ssh/${KEY_NAME}.pem}"

RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; BLU=$'\033[34m'; OFF=$'\033[0m'
log()  { printf '%s[%s]%s %s\n' "$BLU" "$(date +%H:%M:%S)" "$OFF" "$*"; }
ok()   { printf '%s✓%s %s\n' "$GRN" "$OFF" "$*"; }
warn() { printf '%s!%s %s\n' "$YLW" "$OFF" "$*"; }

aws_ec2() { aws ec2 --region "$AWS_REGION" "$@"; }

cat <<WARN
${YLW}This will permanently destroy:${OFF}
  - EC2 instance(s) tagged Name=$INSTANCE_TAG  (region: $AWS_REGION)
  - Security group: $SG_NAME
  - Key pair: $KEY_NAME (and local file $LOCAL_KEY_PATH)
WARN

read -r -p "Type 'destroy' to confirm: " confirm
if [[ "$confirm" != "destroy" ]]; then
  log "Aborted."
  exit 0
fi

# 1. Terminate instance(s)
INSTANCE_IDS=$(aws_ec2 describe-instances \
  --filters "Name=tag:Name,Values=$INSTANCE_TAG" \
            "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text)

if [[ -n "$INSTANCE_IDS" ]]; then
  log "Terminating instance(s): $INSTANCE_IDS"
  # shellcheck disable=SC2086
  aws_ec2 terminate-instances --instance-ids $INSTANCE_IDS >/dev/null
  log "Waiting for termination..."
  # shellcheck disable=SC2086
  aws_ec2 wait instance-terminated --instance-ids $INSTANCE_IDS
  ok "Instance(s) terminated"
else
  warn "No instance found with tag Name=$INSTANCE_TAG"
fi

# 2. Delete security group (must be after instance termination releases ENIs)
SG_ID=$(aws_ec2 describe-security-groups \
  --filters "Name=group-name,Values=$SG_NAME" \
  --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo "None")

if [[ "$SG_ID" != "None" && -n "$SG_ID" ]]; then
  log "Deleting security group $SG_ID..."
  for i in 1 2 3 4 5; do
    if aws_ec2 delete-security-group --group-id "$SG_ID" 2>/dev/null; then
      ok "Security group deleted"
      break
    fi
    warn "  Retry $i/5: SG still in use (waiting for ENI release)..."
    sleep 6
  done
else
  warn "No security group named $SG_NAME"
fi

# 3. Delete key pair
if aws_ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
  aws_ec2 delete-key-pair --key-name "$KEY_NAME"
  ok "Key pair deleted from AWS"
fi

if [[ -f "$LOCAL_KEY_PATH" ]]; then
  rm -f "$LOCAL_KEY_PATH"
  ok "Local key file removed: $LOCAL_KEY_PATH"
fi

log "Teardown complete."
