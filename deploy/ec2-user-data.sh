#!/bin/bash
#
# First-boot configuration for the EC2 instance that runs the application.
#
# Paste this into the launch wizard's "User data" box. cloud-init runs it as
# root, once, before the instance is usable; output lands in
# /var/log/cloud-init-output.log.
#
# This exists instead of an AMI. An AMI is backed by an EBS snapshot, which
# bills for as long as it exists, and it hides how the box was built. A script
# costs nothing, is versioned with the code it deploys, and is reviewable.
#
# It deliberately stops short of starting the application, because the last
# two steps need things that must not be in user data:
#
#   - .env holds credentials, and user data is readable by ANY process on the
#     instance through the metadata service, as well as in the console. The
#     proper way to automate it is SSM Parameter Store (standard tier is free)
#     read by the instance role; until that exists, .env is created by hand.
#   - The image tag to deploy is a decision, not a constant.
#
# Nothing here contains an account id, a region or a bucket name: the account
# comes from the instance role via STS and the region from the metadata
# service, so this file stays free of anything environment-specific.

set -euo pipefail

SWAP_GB=2
COMPOSE_FALLBACK_VERSION=v2.39.1
# Only used to fetch the compose file, and only if the repository is public.
# Harmless if it fails: scp the one file up instead.
COMPOSE_URL=https://raw.githubusercontent.com/lumjahaj/subscription-hub/main/docker-compose.staging.yml
APP_USER=ec2-user
APP_DIR=/opt/subscription-hub

log() { echo "[bootstrap] $*"; }

# ---------------------------------------------------------------- docker ----
log "installing docker"
dnf install -y docker
systemctl enable --now docker
usermod -aG docker "$APP_USER"

# AL2023 does not package the compose v2 plugin, and the plugin is what
# provides `docker compose` (as opposed to the long-dead `docker-compose`).
# Try the package first in case that changes, then fall back to the release
# binary, matched to the instance's architecture - this must not assume x86,
# because a Graviton instance is the cheaper option and silently downloading
# the wrong binary would fail with a confusing "exec format error".
log "installing the compose plugin"
if ! dnf install -y docker-compose-plugin 2>/dev/null; then
    case "$(uname -m)" in
        x86_64)  COMPOSE_ARCH=x86_64 ;;
        aarch64) COMPOSE_ARCH=aarch64 ;;
        *) log "unknown architecture $(uname -m); install the compose plugin by hand"; COMPOSE_ARCH= ;;
    esac
    if [ -n "$COMPOSE_ARCH" ]; then
        install -d /usr/local/lib/docker/cli-plugins
        curl -fsSL \
            "https://github.com/docker/compose/releases/download/${COMPOSE_FALLBACK_VERSION}/docker-compose-linux-${COMPOSE_ARCH}" \
            -o /usr/local/lib/docker/cli-plugins/docker-compose
        chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
    fi
fi

# ------------------------------------------------------------------ swap ----
# A free-tier instance has 1 GiB of RAM and this application rasterises PDFs
# through java.awt. Measured peak is ~562MB against a 900MB container limit,
# which fits - but with no swap there is nothing between a spike and the OOM
# killer taking the JVM.
if ! swapon --show | grep -q /swapfile; then
    log "creating ${SWAP_GB}G swap"
    fallocate -l "${SWAP_GB}G" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# ------------------------------------------------------------------- ecr ----
# Resolved at run time rather than baked in, so this file carries no account
# id. Both come from the instance itself: the region from IMDSv2, the account
# from STS through the instance role - which also makes this the first real
# proof that the role is attached and working.
log "resolving region and account"
IMDS_TOKEN=$(curl -fsS -X PUT http://169.254.169.254/latest/api/token \
    -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
REGION=$(curl -fsS -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/placement/region)
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"
log "registry ${REGISTRY}"

# The ECR token lasts 12 hours, so logging in once here is not enough for the
# life of the box. A helper means re-authenticating is one word rather than a
# command nobody remembers.
cat > /usr/local/bin/ecr-login <<EOF
#!/bin/bash
# Re-authenticate docker to ECR. The token lasts 12 hours.
set -euo pipefail
aws ecr get-login-password --region ${REGION} \\
  | docker login --username AWS --password-stdin ${REGISTRY}
EOF
chmod +x /usr/local/bin/ecr-login

log "logging in to ECR"
/usr/local/bin/ecr-login

# The login above wrote root's credentials. Copy them to the unprivileged user
# so `docker compose` works without sudo once its new group membership takes
# effect on the next login.
install -d -o "$APP_USER" -g "$APP_USER" -m 700 "/home/${APP_USER}/.docker"
cp /root/.docker/config.json "/home/${APP_USER}/.docker/config.json"
chown "${APP_USER}:${APP_USER}" "/home/${APP_USER}/.docker/config.json"

# --------------------------------------------------------------- compose ----
install -d -o "$APP_USER" -g "$APP_USER" "$APP_DIR"
if curl -fsSL "$COMPOSE_URL" -o "${APP_DIR}/docker-compose.staging.yml"; then
    chown "${APP_USER}:${APP_USER}" "${APP_DIR}/docker-compose.staging.yml"
    log "fetched docker-compose.staging.yml"
else
    log "could not fetch the compose file (private repository?) - copy it up by hand"
fi

# ----------------------------------------------------------- what is left ----
cat > /etc/motd <<EOF

  subscription-hub - bootstrap complete.

  Two steps remain, both deliberately not automated:

    1. cd ${APP_DIR} && create .env
         - copy the local one, then DELETE these four lines, because their
           absence is what makes the SDK use this instance's role:
               AWS_ACCESS_KEY_ID  AWS_SECRET_ACCESS_KEY
               STORAGE_ACCESS_KEY STORAGE_SECRET_KEY
         - add   APP_IMAGE=${REGISTRY}/subscription-hub:<tag>
         - set fresh JWT_SECRET and METRICS_SCRAPE_PASSWORD; the values in
           .env.example are published in the repository

    2. docker compose -f docker-compose.staging.yml up -d
       docker compose -f docker-compose.staging.yml logs cloudflared | grep -i trycloudflare

  Re-authenticate to ECR when the 12-hour token expires:  ecr-login

  This instance BILLS WHILE RUNNING. Terminate it when you are done;
  'docker compose down' does not stop the charge.

EOF

log "done"
touch /var/log/subscription-hub-bootstrap.done
