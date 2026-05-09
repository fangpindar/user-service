#!/usr/bin/env bash
#
# Provision an EC2 instance + security group + key pair, then deploy this
# project via docker compose. Idempotent — re-running re-uses existing
# resources and re-deploys.
#
# Prerequisites on your laptop:
#   - aws CLI configured (`aws sts get-caller-identity` works)
#   - jq, curl, ssh installed
#   - this directory contains a filled-in .env (see .env.example)
#
# Override defaults via env vars, e.g.:
#   AWS_REGION=us-east-1 ./scripts/aws-deploy.sh
#
set -euo pipefail

# -----------------------------------------------------------------------------
# Configuration
# -----------------------------------------------------------------------------
PROJECT_NAME="${PROJECT_NAME:-denden-user-service}"
AWS_REGION="${AWS_REGION:-ap-northeast-1}"
INSTANCE_TYPE="${INSTANCE_TYPE:-t2.micro}"
GITHUB_REPO="${GITHUB_REPO:-https://github.com/fangpindar/user-service.git}"
GITHUB_BRANCH="${GITHUB_BRANCH:-main}"
KEY_NAME="${KEY_NAME:-${PROJECT_NAME}-key}"
SG_NAME="${SG_NAME:-${PROJECT_NAME}-sg}"
INSTANCE_TAG="${INSTANCE_TAG:-${PROJECT_NAME}-app}"
ROOT_VOLUME_GB="${ROOT_VOLUME_GB:-20}"

LOCAL_KEY_PATH="${LOCAL_KEY_PATH:-$HOME/.ssh/${KEY_NAME}.pem}"
LOCAL_ENV_FILE="${LOCAL_ENV_FILE:-.env}"
REPO_DIR_ON_EC2="user-service"

# -----------------------------------------------------------------------------
# Pretty logging
# -----------------------------------------------------------------------------
RED=$'\033[31m'; GRN=$'\033[32m'; YLW=$'\033[33m'; BLU=$'\033[34m'; OFF=$'\033[0m'
log()  { printf '%s[%s]%s %s\n' "$BLU" "$(date +%H:%M:%S)" "$OFF" "$*"; }
ok()   { printf '%s✓%s %s\n' "$GRN" "$OFF" "$*"; }
warn() { printf '%s!%s %s\n' "$YLW" "$OFF" "$*"; }
err()  { printf '%s✗%s %s\n' "$RED" "$OFF" "$*" >&2; }

aws_ec2() { aws ec2 --region "$AWS_REGION" "$@"; }
aws_ssm() { aws ssm --region "$AWS_REGION" "$@"; }

# -----------------------------------------------------------------------------
# Steps
# -----------------------------------------------------------------------------
preflight() {
  log "Preflight checks..."

  for cmd in aws jq curl ssh scp; do
    command -v "$cmd" >/dev/null || { err "$cmd not installed"; exit 1; }
  done

  if ! aws sts get-caller-identity --region "$AWS_REGION" >/dev/null 2>&1; then
    err "AWS CLI not configured for region $AWS_REGION. Run 'aws configure'."
    exit 1
  fi

  if [[ ! -f "$LOCAL_ENV_FILE" ]]; then
    err "$LOCAL_ENV_FILE not found in $(pwd). Copy .env.example and fill it in first."
    exit 1
  fi

  # shellcheck disable=SC1090
  set -a; source "$LOCAL_ENV_FILE"; set +a

  local missing=()
  [[ -z "${SENDGRID_API_KEY:-}" || "$SENDGRID_API_KEY" =~ ^SG\.replace ]] && missing+=("SENDGRID_API_KEY")
  [[ -z "${EMAIL_FROM:-}" ]] && missing+=("EMAIL_FROM")
  [[ -z "${JWT_ACCESS_SECRET:-}" || "$JWT_ACCESS_SECRET" =~ ^replace ]] && missing+=("JWT_ACCESS_SECRET")
  [[ -z "${JWT_REFRESH_SECRET:-}" || "$JWT_REFRESH_SECRET" =~ ^replace ]] && missing+=("JWT_REFRESH_SECRET")
  [[ -z "${DB_PASSWORD:-}" || "$DB_PASSWORD" == "changeme" ]] && missing+=("DB_PASSWORD (must not be 'changeme')")

  if (( ${#missing[@]} > 0 )); then
    err "These values in $LOCAL_ENV_FILE are missing or placeholders:"
    printf '   - %s\n' "${missing[@]}"
    err "Generate JWT secrets with:  openssl rand -base64 48"
    exit 1
  fi

  ok "Preflight passed (region=$AWS_REGION, project=$PROJECT_NAME)"
}

ensure_keypair() {
  log "Ensuring SSH key pair '$KEY_NAME'..."

  if aws_ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
    if [[ -f "$LOCAL_KEY_PATH" ]]; then
      ok "Key pair '$KEY_NAME' exists in AWS and locally at $LOCAL_KEY_PATH"
      return
    fi
    err "Key pair '$KEY_NAME' exists in AWS but local file $LOCAL_KEY_PATH is missing."
    err "Either delete it in AWS (aws ec2 delete-key-pair --key-name $KEY_NAME --region $AWS_REGION)"
    err "or set LOCAL_KEY_PATH to point to your existing private key."
    exit 1
  fi

  mkdir -p "$(dirname "$LOCAL_KEY_PATH")"
  aws_ec2 create-key-pair --key-name "$KEY_NAME" \
    --query KeyMaterial --output text > "$LOCAL_KEY_PATH"
  chmod 400 "$LOCAL_KEY_PATH"
  ok "Created key pair, saved to $LOCAL_KEY_PATH"
}

ensure_security_group() {
  log "Ensuring security group '$SG_NAME'..."

  local default_vpc
  default_vpc=$(aws_ec2 describe-vpcs \
    --filters Name=is-default,Values=true \
    --query 'Vpcs[0].VpcId' --output text)
  if [[ "$default_vpc" == "None" || -z "$default_vpc" ]]; then
    err "No default VPC found in $AWS_REGION."
    exit 1
  fi

  SG_ID=$(aws_ec2 describe-security-groups \
    --filters "Name=group-name,Values=$SG_NAME" "Name=vpc-id,Values=$default_vpc" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo "None")

  if [[ "$SG_ID" == "None" || -z "$SG_ID" ]]; then
    SG_ID=$(aws_ec2 create-security-group \
      --group-name "$SG_NAME" \
      --description "${PROJECT_NAME} app + ssh" \
      --vpc-id "$default_vpc" \
      --query GroupId --output text)
    aws_ec2 create-tags --resources "$SG_ID" --tags "Key=Project,Value=$PROJECT_NAME"
    ok "Created security group $SG_ID"
  else
    ok "Reusing security group $SG_ID"
  fi

  local my_ip
  my_ip=$(curl -s https://checkip.amazonaws.com)
  [[ -z "$my_ip" ]] && { err "Could not determine your public IP"; exit 1; }
  log "Your current public IP: $my_ip"

  authorize_rule() {
    local proto="$1" port="$2" cidr="$3" desc="$4"
    if aws_ec2 authorize-security-group-ingress \
        --group-id "$SG_ID" \
        --ip-permissions "IpProtocol=$proto,FromPort=$port,ToPort=$port,IpRanges=[{CidrIp=$cidr,Description=$desc}]" \
        >/dev/null 2>&1; then
      ok "  Added rule: $proto/$port from $cidr ($desc)"
    else
      log "  Rule $proto/$port from $cidr already present, skipping"
    fi
  }

  authorize_rule tcp 22 "${my_ip}/32" "ssh-from-deployer"
  authorize_rule tcp 80 "0.0.0.0/0"   "http-public"
}

resolve_ami() {
  log "Resolving latest Amazon Linux 2023 AMI..."
  AMI_ID=$(aws_ssm get-parameter \
    --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
    --query Parameter.Value --output text)
  ok "AMI: $AMI_ID"
}

ensure_instance() {
  log "Ensuring EC2 instance '$INSTANCE_TAG'..."

  INSTANCE_ID=$(aws_ec2 describe-instances \
    --filters "Name=tag:Name,Values=$INSTANCE_TAG" \
              "Name=instance-state-name,Values=pending,running,stopping,stopped" \
    --query 'Reservations[].Instances[0].InstanceId' --output text 2>/dev/null || echo "None")

  if [[ "$INSTANCE_ID" != "None" && -n "$INSTANCE_ID" ]]; then
    ok "Reusing instance $INSTANCE_ID"
    local state
    state=$(aws_ec2 describe-instances --instance-ids "$INSTANCE_ID" \
      --query 'Reservations[0].Instances[0].State.Name' --output text)
    if [[ "$state" == "stopped" ]]; then
      log "Instance is stopped, starting..."
      aws_ec2 start-instances --instance-ids "$INSTANCE_ID" >/dev/null
    fi
    return
  fi

  log "Launching new $INSTANCE_TYPE instance..."
  INSTANCE_ID=$(aws_ec2 run-instances \
    --image-id "$AMI_ID" \
    --instance-type "$INSTANCE_TYPE" \
    --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" \
    --block-device-mappings "DeviceName=/dev/xvda,Ebs={VolumeSize=$ROOT_VOLUME_GB,VolumeType=gp3,DeleteOnTermination=true}" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_TAG},{Key=Project,Value=$PROJECT_NAME}]" \
    --query 'Instances[0].InstanceId' --output text)
  ok "Launched instance $INSTANCE_ID"
}

wait_for_instance() {
  log "Waiting for instance to reach 'running' state..."
  aws_ec2 wait instance-running --instance-ids "$INSTANCE_ID"
  log "Waiting for status checks (this takes ~1-2 minutes)..."
  aws_ec2 wait instance-status-ok --instance-ids "$INSTANCE_ID"

  PUBLIC_DNS=$(aws_ec2 describe-instances --instance-ids "$INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicDnsName' --output text)
  PUBLIC_IP=$(aws_ec2 describe-instances --instance-ids "$INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
  ok "Instance up: $PUBLIC_DNS ($PUBLIC_IP)"
}

wait_for_ssh() {
  log "Waiting for SSH to become reachable..."
  local tries=0
  while ! ssh -i "$LOCAL_KEY_PATH" \
              -o ConnectTimeout=5 \
              -o StrictHostKeyChecking=no \
              -o UserKnownHostsFile=/dev/null \
              -o LogLevel=ERROR \
              -o BatchMode=yes \
              "ec2-user@$PUBLIC_DNS" 'echo ok' >/dev/null 2>&1; do
    tries=$((tries + 1))
    if (( tries > 30 )); then
      err "SSH did not become reachable after 30 attempts"
      exit 1
    fi
    sleep 5
  done
  ok "SSH ready"
}

ssh_run() {
  ssh -i "$LOCAL_KEY_PATH" \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      -o LogLevel=ERROR \
      "ec2-user@$PUBLIC_DNS" "$@"
}

scp_to() {
  scp -i "$LOCAL_KEY_PATH" \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      -o LogLevel=ERROR \
      "$1" "ec2-user@$PUBLIC_DNS:$2"
}

bootstrap_instance() {
  log "Installing Docker + git on instance (idempotent)..."
  ssh_run 'bash -se' <<'REMOTE'
set -euo pipefail
need_install=0
command -v docker >/dev/null 2>&1 || need_install=1
command -v git    >/dev/null 2>&1 || need_install=1

if (( need_install )); then
  sudo dnf install -y docker git >/dev/null
  sudo systemctl enable --now docker
  sudo usermod -aG docker ec2-user
fi

sudo mkdir -p /usr/local/lib/docker/cli-plugins

if [ ! -x /usr/local/lib/docker/cli-plugins/docker-compose ]; then
  sudo curl -fsSL \
    https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
  sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
fi

# Buildx is required by compose v2 for `compose up --build`, but Amazon
# Linux 2023's bundled docker package does not include it.
if [ ! -x /usr/local/lib/docker/cli-plugins/docker-buildx ]; then
  BUILDX_VERSION="v0.18.0"
  ARCH=$(uname -m)
  case "$ARCH" in
    x86_64)  BUILDX_ARCH="amd64" ;;
    aarch64) BUILDX_ARCH="arm64" ;;
    *)       echo "Unsupported arch: $ARCH" >&2; exit 1 ;;
  esac
  sudo curl -fsSL \
    "https://github.com/docker/buildx/releases/download/${BUILDX_VERSION}/buildx-${BUILDX_VERSION}.linux-${BUILDX_ARCH}" \
    -o /usr/local/lib/docker/cli-plugins/docker-buildx
  sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx
fi
REMOTE
  ok "Instance bootstrapped"
}

deploy_app() {
  log "Cloning / updating repo on instance..."
  ssh_run "bash -s" <<REMOTE
set -euo pipefail
if [ -d "$REPO_DIR_ON_EC2/.git" ]; then
  cd "$REPO_DIR_ON_EC2"
  git fetch --quiet
  git checkout --quiet "$GITHUB_BRANCH"
  git reset --hard --quiet "origin/$GITHUB_BRANCH"
else
  git clone --quiet --branch "$GITHUB_BRANCH" "$GITHUB_REPO" "$REPO_DIR_ON_EC2"
fi
REMOTE
  ok "Repo synced"

  log "Building production .env (with APP_BASE_URL = http://$PUBLIC_DNS)..."
  local tmp_env
  tmp_env=$(mktemp)
  awk -v dns="http://$PUBLIC_DNS" '
    BEGIN { FS="="; OFS="=" }
    /^APP_BASE_URL=/ { print "APP_BASE_URL=" dns; next }
    { print }
  ' "$LOCAL_ENV_FILE" > "$tmp_env"

  scp_to "$tmp_env" "/home/ec2-user/$REPO_DIR_ON_EC2/.env"
  rm -f "$tmp_env"
  ok "Synced .env to instance"

  log "Bringing up the stack via docker compose..."
  # sudo because new docker group membership needs re-login to take effect on first run
  ssh_run "cd $REPO_DIR_ON_EC2 && sudo docker compose pull --quiet 2>/dev/null || true && sudo docker compose up -d --build --remove-orphans 2>&1 | tail -20"
  ok "docker compose up complete"
}

wait_for_app_healthy() {
  log "Waiting for app to become healthy at http://$PUBLIC_DNS/actuator/health ..."
  local tries=0
  while true; do
    local body status
    body=$(curl -s -m 5 "http://$PUBLIC_DNS/actuator/health" || true)
    status=$(echo "$body" | jq -r '.status' 2>/dev/null || echo "")
    if [[ "$status" == "UP" ]]; then
      ok "App is UP"
      return
    fi
    tries=$((tries + 1))
    if (( tries > 60 )); then
      err "App did not become healthy after 60 attempts"
      err "Last response: $body"
      err "SSH in to debug: ssh -i $LOCAL_KEY_PATH ec2-user@$PUBLIC_DNS"
      err "  Then: cd $REPO_DIR_ON_EC2 && sudo docker compose logs app | tail -100"
      exit 1
    fi
    sleep 5
  done
}

print_summary() {
  cat <<SUMMARY

${GRN}╔══════════════════════════════════════════════════════════════════╗
║ Deployment complete                                              ║
╚══════════════════════════════════════════════════════════════════╝${OFF}

  Project:        $PROJECT_NAME
  Region:         $AWS_REGION
  Instance:       $INSTANCE_ID  ($INSTANCE_TYPE)
  Public DNS:     $PUBLIC_DNS
  Public IP:      $PUBLIC_IP

  ${GRN}🌐 Swagger UI:  http://$PUBLIC_DNS/swagger-ui.html${OFF}
     Health:      http://$PUBLIC_DNS/actuator/health

  SSH:    ssh -i $LOCAL_KEY_PATH ec2-user@$PUBLIC_DNS
  Logs:   ssh -i $LOCAL_KEY_PATH ec2-user@$PUBLIC_DNS \\
            'cd $REPO_DIR_ON_EC2 && sudo docker compose logs -f app'

  To re-deploy after a 'git push' to main:    ./scripts/aws-deploy.sh
  To tear everything down (terminates EC2):   ./scripts/aws-teardown.sh

SUMMARY
}

# -----------------------------------------------------------------------------
# Run
# -----------------------------------------------------------------------------
preflight
ensure_keypair
ensure_security_group
resolve_ami
ensure_instance
wait_for_instance
wait_for_ssh
bootstrap_instance
deploy_app
wait_for_app_healthy
print_summary
